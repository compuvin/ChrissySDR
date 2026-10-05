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
import kotlin.math.abs

data class TxStatistics(
    val totalSamples: Long,
    val samplesPerSecond: Double,
    val microphonePeak: Double,
    val secondsRemaining: Int,
)

class SoapyRemoteTxSession private constructor(
    private val control: Socket,
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
                close()
                onError(error)
            }
        }
    }

    private fun drainStatusErrors() {
        SoapyStreamStatus.drain(DataInputStream(status.getInputStream()), "TX")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        microphoneInput?.runCatching { close() }
        microphoneInput = null
        // The microphone paces TX in real time, so there should only be a very
        // small downstream cushion. Let already-submitted samples reach the
        // radio before deactivating without waiting for a potentially large or
        // driver-dependent transport buffer to drain.
        runCatching { Thread.sleep(TX_TAIL_DRAIN_MS) }
        runCatching {
            transact(
                SoapyRpcWriter().call(DEACTIVATE_STREAM).int32(streamId).int32(0).int64(0),
            ) { it.int32() }
        }
        // The writer may be waiting for a flow-control ACK. Interrupt its socket
        // waits immediately instead of delaying the RX hand-back by up to the
        // stream socket timeout.
        runCatching { stream.shutdownInput() }
        runCatching { stream.shutdownOutput() }
        runCatching { status.close() }
        if (Thread.currentThread() !== writerThread) {
            runCatching { writerThread?.join(250) }
        }
        runCatching { transact(SoapyRpcWriter().call(CLOSE_STREAM).int32(streamId)) { it.requireVoid() } }
        runCatching { stream.close() }
        runCatching { transact(SoapyRpcWriter().call(UNMAKE)) { it.requireVoid() } }
        runCatching { transact(SoapyRpcWriter().call(HANGUP)) { it.requireVoid() } }
        runCatching { control.close() }
    }

    @Synchronized
    private fun <T> transact(request: SoapyRpcWriter, decode: (SoapyRpcReader) -> T): T {
        control.getOutputStream().apply { write(request.frame().encode()); flush() }
        val reader = SoapyRpcReader(SoapyRpcFrame.readFrom(control.getInputStream()).payload)
        return decode(reader).also { reader.requireFinished() }
    }

    companion object {
        private const val TX = 0
        private const val MAKE = 1
        private const val UNMAKE = 2
        private const val HANGUP = 3
        private const val SETUP_STREAM = 300
        private const val CLOSE_STREAM = 301
        private const val ACTIVATE_STREAM = 302
        private const val DEACTIVATE_STREAM = 303
        private const val SET_FREQUENCY = 800
        private const val SET_SAMPLE_RATE = 900
        private const val GET_SAMPLE_RATE = 901
        private const val STREAM_HEADER_BYTES = 24
        private const val MTU = 4096
        private const val STREAM_TRANSFER_BYTES = MTU - 48
        private const val SOCKET_WINDOW = 1_048_576
        private const val MICROPHONE_SAMPLE_RATE = 48_000
        private const val TX_TAIL_DRAIN_MS = 40L

        fun open(
            host: String,
            port: Int,
            deviceArgs: Map<String, String>,
            frequencyHz: Double,
            sampleRate: Double,
            format: String,
            fullScale: Double,
            mode: String,
        ): SoapyRemoteTxSession {
            require(sampleRate >= 8_000) { "TX sample rate must be at least 8000 Hz" }
            IqSampleCodec(format, fullScale)
            val control = Socket()
            var stream: Socket? = null
            var status: Socket? = null
            var made = false
            var streamId: Int? = null
            fun <T> transact(request: SoapyRpcWriter, decode: (SoapyRpcReader) -> T): T {
                control.getOutputStream().apply { write(request.frame().encode()); flush() }
                val reader = SoapyRpcReader(SoapyRpcFrame.readFrom(control.getInputStream()).payload)
                return decode(reader).also { reader.requireFinished() }
            }
            try {
                control.connect(InetSocketAddress(host.trim(), port), 3_000)
                control.soTimeout = 10_000

                transact(SoapyRpcWriter().call(MAKE).kwargs(deviceArgs - "soapy_remote_no_deeper")) {
                    it.requireVoid()
                }
                made = true
                transact(
                    SoapyRpcWriter().call(SET_SAMPLE_RATE).char(TX).int32(0).float64(sampleRate),
                ) { it.requireVoid() }
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
                control.getOutputStream().apply { write(setup.frame().encode()); flush() }
                val bindPort = SoapyRpcReader(
                    SoapyRpcFrame.readFrom(control.getInputStream()).payload,
                ).let { reader -> reader.string().toInt().also { reader.requireFinished() } }

                stream = Socket().apply {
                    sendBufferSize = SOCKET_WINDOW
                    connect(InetSocketAddress(host.trim(), bindPort), 3_000)
                    soTimeout = 3_000
                }
                status = Socket().apply { connect(InetSocketAddress(host.trim(), bindPort), 3_000) }
                val setupReply = SoapyRpcReader(
                    SoapyRpcFrame.readFrom(control.getInputStream()).payload,
                )
                streamId = setupReply.int32()
                setupReply.string()
                setupReply.requireFinished()
                return SoapyRemoteTxSession(
                    control, stream!!, status!!, streamId, appliedSampleRate, format, fullScale, mode,
                )
            } catch (error: Throwable) {
                runCatching { stream?.close() }
                runCatching { status?.close() }
                if (control.isConnected && !control.isClosed) {
                    runCatching { control.soTimeout = 1_000 }
                    streamId?.let { id ->
                        runCatching {
                            transact(SoapyRpcWriter().call(CLOSE_STREAM).int32(id)) { it.requireVoid() }
                        }
                    }
                    if (made) {
                        runCatching { transact(SoapyRpcWriter().call(UNMAKE)) { it.requireVoid() } }
                    }
                    runCatching { transact(SoapyRpcWriter().call(HANGUP)) { it.requireVoid() } }
                }
                runCatching { control.close() }
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
