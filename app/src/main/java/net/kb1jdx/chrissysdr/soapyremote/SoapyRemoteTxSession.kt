package com.kb1jdx.chrissysdr.soapyremote

import com.kb1jdx.chrissysdr.audio.AndroidMicrophoneInput
import com.kb1jdx.chrissysdr.dsp.VoiceTransmitPipeline
import com.kb1jdx.chrissysdr.radio.SoapyStreamException
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class TxStatistics(
    val totalSamples: Long,
    val samplesPerSecond: Double,
    val microphonePeak: Double,
    val secondsRemaining: Int,
)

class SoapyRemoteTxSession private constructor(
    private val device: SoapyRemoteDeviceConnection,
    private val stream: Socket,
    private val status: Socket,
    private val streamId: Int,
    val outputSampleRate: Double,
    private val streamFormat: String,
    private val fullScale: Double,
    private val mode: String,
) : AutoCloseable {
    private val sampleCodec = IqSampleCodec(streamFormat, fullScale)
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val closeComplete = CountDownLatch(1)
    @Volatile private var closeFailure: Throwable? = null
    private var writerThread: Thread? = null
    @Volatile private var microphoneInput: AndroidMicrophoneInput? = null

    fun start(
        maximumSeconds: Int,
        onStatistics: (TxStatistics) -> Unit,
        onStopped: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        require(maximumSeconds in 30..600) { "Invalid TX time limit" }
        check(!closed.get()) { "TX stream is closed" }
        check(running.compareAndSet(false, true)) { "TX stream is already running" }
        try {
            microphoneInput = AndroidMicrophoneInput(MICROPHONE_SAMPLE_RATE)
            val activation = transact(
                SoapyRpcWriter().call(ACTIVATE_STREAM).int32(streamId).int32(0).int64(0).int32(0),
            ) { it.int32() }
            check(activation == 0) { "SoapyRemote activateStream returned $activation" }
            microphoneInput!!.start()
            writerThread = Thread(
                { transmitLoop(maximumSeconds, onStatistics, onStopped, onError) },
                "SoapyRemote-TX",
            ).apply { start() }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun transmitLoop(
        maximumSeconds: Int,
        onStatistics: (TxStatistics) -> Unit,
        onStopped: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val bytesPerElement = sampleCodec.bytesPerElement
        val maximumElements = (STREAM_TRANSFER_BYTES - STREAM_HEADER_BYTES) / bytesPerElement
        val audio = ShortArray(1_024)
        var sequence = 0L
        var acknowledgedSequence: Long
        var flowWindow: Int
        var totalSamples = 0L
        var intervalSamples = 0L
        var microphonePeak = 0.0
        val startedAt = System.nanoTime()
        var intervalStart = startedAt
        var timedOut = false

        try {
            val input = DataInputStream(stream.getInputStream())
            val output = stream.getOutputStream()
            val microphone = microphoneInput ?: return
            val audioPipeline = VoiceTransmitPipeline(mode, MICROPHONE_SAMPLE_RATE, outputSampleRate)
            val initialAck = readHeader(input)
            acknowledgedSequence = initialAck.sequence
            flowWindow = initialAck.elements
            require(flowWindow > 0) { "SoapyRemote returned an invalid TX flow window" }

            while (running.get()) {
                val elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
                if (elapsedSeconds >= maximumSeconds) {
                    running.set(false)
                    timedOut = true
                    break
                }

                val count = microphone.read(audio)
                if (count == 0) continue
                repeat(count) { index ->
                    microphonePeak = maxOf(microphonePeak, abs(audio[index].toDouble() / 32768.0))
                }
                val iq = audioPipeline.process(audio, count)

                var iqIndex = 0
                while (iqIndex < iq.size && running.get()) {
                    val packet = ByteBuffer.allocate(STREAM_TRANSFER_BYTES).order(ByteOrder.BIG_ENDIAN)
                    packet.position(STREAM_HEADER_BYTES)
                    val elements = minOf(maximumElements, (iq.size - iqIndex) / 2)
                    sampleCodec.encode(iq, iqIndex, elements, packet)
                    iqIndex += elements * 2

                    while (sequence - acknowledgedSequence >= flowWindow) {
                        val ack = readHeader(input)
                        acknowledgedSequence = ack.sequence
                        flowWindow = ack.elements
                    }
                    while (input.available() >= STREAM_HEADER_BYTES) {
                        val ack = readHeader(input)
                        acknowledgedSequence = ack.sequence
                        flowWindow = ack.elements
                    }
                    drainStatusErrors()

                    val packetBytes = STREAM_HEADER_BYTES + elements * bytesPerElement
                    packet.order(ByteOrder.BIG_ENDIAN)
                    packet.putInt(0, packetBytes)
                    packet.putInt(4, sequence.toInt())
                    packet.putInt(8, elements)
                    packet.putInt(12, 0)
                    packet.putLong(16, 0)
                    output.write(packet.array(), 0, packetBytes)
                    output.flush()
                    sequence++
                    totalSamples += elements
                    intervalSamples += elements
                }

                val now = System.nanoTime()
                val interval = (now - intervalStart) / 1_000_000_000.0
                if (interval >= 0.2) {
                    onStatistics(
                        TxStatistics(
                            totalSamples = totalSamples,
                            samplesPerSecond = intervalSamples / interval,
                            microphonePeak = microphonePeak,
                            secondsRemaining = (maximumSeconds -
                                ((now - startedAt) / 1_000_000_000L).toInt()).coerceAtLeast(0),
                        ),
                    )
                    intervalSamples = 0
                    microphonePeak = 0.0
                    intervalStart = now
                }
            }
            if (timedOut) onStopped()
        } catch (error: Throwable) {
            if (running.get()) {
                val closeError = runCatching { close() }.exceptionOrNull()
                onError(closeError ?: error)
            }
        }
    }

    private fun drainStatusErrors() {
        SoapyStreamStatus.drain(DataInputStream(status.getInputStream()), "TX")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            check(closeComplete.await(TX_CLOSE_WATCHDOG_MS + 1_000, TimeUnit.MILLISECONDS)) {
                "Concurrent TX shutdown did not finish"
            }
            closeFailure?.let { throw it }
            return
        }
        val failures = ArrayList<String>()
        val watchdogFired = AtomicBoolean(false)
        Thread({
            try {
                Thread.sleep(TX_CLOSE_WATCHDOG_MS)
                if (closeComplete.count > 0) {
                    watchdogFired.set(true)
                    forceCloseSockets()
                    device.invalidate()
                }
            } catch (_: InterruptedException) { }
        }, "SoapyRemote-TX-close-watchdog").apply { isDaemon = true; start() }
        fun stage(name: String, critical: Boolean = true, action: () -> Unit): Boolean {
            try {
                action()
                return true
            } catch (error: Throwable) {
                if (critical) failures += "$name: ${error.message ?: error.javaClass.simpleName}"
                return false
            }
        }
        try {
            running.set(false)
            // Retain only a short tail; do not wait for a radio-dependent buffer drain.
            stage("short TX tail", critical = false) { Thread.sleep(TX_TAIL_DRAIN_MS) }
            stage("microphone stop") { microphoneInput?.close() }
            microphoneInput = null
            stage("stop TX writer") {
                if (Thread.currentThread() !== writerThread) {
                    writerThread?.join(TX_WRITER_JOIN_MS)
                    if (writerThread?.isAlive == true) {
                        // Only interrupt the endpoint if the writer cannot stop cooperatively.
                        // In the normal path, keep it intact through the closeStream reply.
                        runCatching { stream.shutdownInput() }
                        runCatching { stream.shutdownOutput() }
                        runCatching { status.close() }
                        writerThread?.join(TX_WRITER_JOIN_MS)
                    }
                    check(writerThread?.isAlive != true) { "TX writer did not stop" }
                }
            }
            val deactivated = stage("deactivateStream") {
                val result = transact(
                    SoapyRpcWriter().call(DEACTIVATE_STREAM).int32(streamId).int32(0).int64(0),
                    TX_RPC_TIMEOUT_MS,
                ) { it.int32() }
                check(result == 0) { "SoapyRemote returned $result" }
            }
            val streamClosed = if (deactivated) stage("closeStream") {
                transact(SoapyRpcWriter().call(CLOSE_STREAM).int32(streamId),
                    TX_CLOSE_STREAM_TIMEOUT_MS) { it.requireVoid() }
            } else false
            // Match the upstream SoapyRemote client: keep the data and status
            // endpoints alive until closeStream has returned.
            stage("stream socket close", critical = false) { stream.close() }
            stage("status socket close", critical = false) { status.close() }
            if (!streamClosed) failures += "device stream remains unconfirmed"
        } finally {
            forceCloseSockets()
            if (watchdogFired.get()) failures += "transport watchdog expired"
            if (failures.isNotEmpty()) device.invalidate()
            closeFailure = failures.takeIf { it.isNotEmpty() }?.let {
                IllegalStateException("TX shutdown unconfirmed: ${it.joinToString("; ")}")
            }
            closeComplete.countDown()
        }
        closeFailure?.let { throw it }
    }

    private fun forceCloseSockets() {
        runCatching { stream.close() }
        runCatching { status.close() }
    }

    private fun <T> transact(request: SoapyRpcWriter, timeoutMs: Int =
        SoapyRemoteDeviceConnection.DEFAULT_RPC_TIMEOUT_MS,
        decode: (SoapyRpcReader) -> T): T = device.transact(request, timeoutMs, decode)

    companion object {
        private const val TX = 0
        private const val SETUP_STREAM = 300
        private const val CLOSE_STREAM = 301
        private const val ACTIVATE_STREAM = 302
        private const val DEACTIVATE_STREAM = 303
        private const val SET_FREQUENCY = 800
        private const val SET_GAIN_ELEMENT = 704
        private const val SET_SAMPLE_RATE = 900
        private const val GET_SAMPLE_RATE = 901
        private const val STREAM_HEADER_BYTES = 24
        private const val MTU = 4096
        private const val STREAM_TRANSFER_BYTES = MTU - 48
        private const val SOCKET_WINDOW = 1_048_576
        private const val MICROPHONE_SAMPLE_RATE = 48_000
        private const val TX_TAIL_DRAIN_MS = 40L
        private const val TX_WRITER_JOIN_MS = 250L
        private const val TX_RPC_TIMEOUT_MS = 1_500
        private const val TX_CLOSE_STREAM_TIMEOUT_MS = 3_000
        private const val TX_CLOSE_WATCHDOG_MS = 7_000L

        internal fun open(
            device: SoapyRemoteDeviceConnection,
            frequencyHz: Double,
            sampleRate: Double,
            format: String,
            fullScale: Double,
            mode: String,
            txGains: Map<String, Double>,
        ): SoapyRemoteTxSession {
            require(sampleRate >= 8_000) { "TX sample rate must be at least 8000 Hz" }
            IqSampleCodec(format, fullScale)
            var stream: Socket? = null
            var status: Socket? = null
            var streamId: Int? = null
            fun <T> transact(request: SoapyRpcWriter, decode: (SoapyRpcReader) -> T): T =
                device.transact(request, decode = decode)
            try {
                transact(
                    SoapyRpcWriter().call(SET_SAMPLE_RATE).char(TX).int32(0).float64(sampleRate),
                ) { it.requireVoid() }
                txGains.forEach { (name, gain) ->
                    transact(SoapyRpcWriter().call(SET_GAIN_ELEMENT).char(TX).int32(0)
                        .string(name).float64(gain)) { it.requireVoid() }
                }
                transact(
                    SoapyRpcWriter().call(SET_FREQUENCY).char(TX).int32(0)
                        .float64(frequencyHz).kwargs(emptyMap()),
                ) { it.requireVoid() }
                val appliedSampleRate = transact(
                    SoapyRpcWriter().call(GET_SAMPLE_RATE).char(TX).int32(0),
                ) { it.float64() }
                require(appliedSampleRate >= 8_000) {
                    "Radio applied an unusable TX sample rate: $appliedSampleRate Hz"
                }

                val setup = SoapyRpcWriter().call(SETUP_STREAM)
                    .char(TX)
                    .string(format)
                    .sizeList(listOf(0))
                    .kwargs(
                        mapOf(
                            "remote:prot" to "tcp",
                            "remote:mtu" to MTU.toString(),
                            "remote:window" to SOCKET_WINDOW.toString(),
                        ),
                    )
                    .string("")
                    .string("")
                device.exchange { control ->
                    control.getOutputStream().apply { write(setup.frame().encode()); flush() }
                    val bindPort = SoapyRpcReader(
                        SoapyRpcFrame.readFrom(control.getInputStream()).payload,
                    ).let { reader -> reader.string().toInt().also { reader.requireFinished() } }

                    stream = Socket().apply {
                        sendBufferSize = SOCKET_WINDOW
                        connect(InetSocketAddress(device.endpoint.host, bindPort), 3_000)
                        soTimeout = 3_000
                    }
                    status = Socket().apply {
                        connect(InetSocketAddress(device.endpoint.host, bindPort), 3_000)
                    }
                    val setupReply = SoapyRpcReader(
                        SoapyRpcFrame.readFrom(control.getInputStream()).payload,
                    )
                    streamId = setupReply.int32()
                    setupReply.string()
                    setupReply.requireFinished()
                }
                return SoapyRemoteTxSession(
                    device, stream!!, status!!, streamId!!, appliedSampleRate, format, fullScale, mode,
                )
            } catch (error: Throwable) {
                runCatching { stream?.close() }
                runCatching { status?.close() }
                streamId?.let { id -> runCatching {
                    device.transact(SoapyRpcWriter().call(CLOSE_STREAM).int32(id), 1_000) {
                        it.requireVoid()
                    }
                } }
                device.invalidate()
                throw error
            }
        }

        private fun readHeader(input: DataInputStream): StreamHeader {
            val bytes = input.readInt()
            val sequence = input.readInt().toLong() and 0xffff_ffffL
            val elements = input.readInt()
            input.readInt()
            input.readLong()
            require(bytes == STREAM_HEADER_BYTES) { "Invalid SoapyRemote flow/status packet size $bytes" }
            return StreamHeader(sequence, elements)
        }
    }
}

private data class StreamHeader(val sequence: Long, val elements: Int)
