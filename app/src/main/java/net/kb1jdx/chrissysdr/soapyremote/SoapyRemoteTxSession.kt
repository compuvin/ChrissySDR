package com.kb1jdx.chrissysdr.soapyremote

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import com.kb1jdx.chrissysdr.dsp.AmModulator

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
    private val outputSampleRate: Double,
    private val streamFormat: String,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private var writerThread: Thread? = null
    @Volatile private var audioRecord: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun start(
        maximumSeconds: Int = TEST_TX_LIMIT_SECONDS,
        onStatistics: (TxStatistics) -> Unit,
        onStopped: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        check(running.compareAndSet(false, true)) { "TX stream is already running" }
        require(maximumSeconds in 1..TEST_TX_LIMIT_SECONDS) { "Invalid TX time limit" }

        val minimumBuffer = AudioRecord.getMinBufferSize(
            MICROPHONE_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimumBuffer > 0) { "Android microphone input is unavailable ($minimumBuffer)" }
        audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(MICROPHONE_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minimumBuffer * 2, MICROPHONE_SAMPLE_RATE / 5 * 2))
            .build()
            .also {
                check(it.state == AudioRecord.STATE_INITIALIZED) { "Android microphone failed to initialize" }
            }

        val activation = transact(
            SoapyRpcWriter().call(ACTIVATE_STREAM).int32(streamId).int32(0).int64(0).int32(0),
        ) { it.int32() }
        check(activation == 0) { "SoapyRemote activateStream returned $activation" }
        audioRecord!!.startRecording()
        check(audioRecord!!.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            "Android microphone did not start"
        }
        writerThread = Thread(
            { transmitLoop(maximumSeconds, onStatistics, onStopped, onError) },
            "SoapyRemote-TX",
        ).apply { start() }
    }

    private fun transmitLoop(
        maximumSeconds: Int,
        onStatistics: (TxStatistics) -> Unit,
        onStopped: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val input = DataInputStream(stream.getInputStream())
        val output = stream.getOutputStream()
        val microphone = audioRecord ?: return
        val bytesPerElement = if (streamFormat == "CS16") 4 else 8
        val maximumElements = (STREAM_TRANSFER_BYTES - STREAM_HEADER_BYTES) / bytesPerElement
        val audio = ShortArray(1_024)
        val modulator = AmModulator()
        var sequence = 0L
        var acknowledgedSequence: Long
        var flowWindow: Int
        var resamplePhase = 0.0
        var totalSamples = 0L
        var intervalSamples = 0L
        var microphonePeak = 0.0
        val startedAt = System.nanoTime()
        var intervalStart = startedAt

        try {
            val initialAck = readHeader(input)
            acknowledgedSequence = initialAck.sequence
            flowWindow = initialAck.elements
            require(flowWindow > 0) { "SoapyRemote returned an invalid TX flow window" }

            while (running.get()) {
                val elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
                if (elapsedSeconds >= maximumSeconds) {
                    running.set(false)
                    break
                }

                val count = microphone.read(audio, 0, audio.size, AudioRecord.READ_BLOCKING)
                check(count >= 0) { "Android microphone read failed ($count)" }
                if (count == 0) continue

                var index = 0
                while (index < count && running.get()) {
                    val packet = ByteBuffer.allocate(STREAM_TRANSFER_BYTES).order(ByteOrder.BIG_ENDIAN)
                    packet.position(STREAM_HEADER_BYTES)
                    packet.order(ByteOrder.LITTLE_ENDIAN)
                    var elements = 0
                    while (index < count && elements < maximumElements) {
                        val microphoneSample = audio[index++].toDouble() / 32768.0
                        microphonePeak = maxOf(microphonePeak, abs(microphoneSample))
                        resamplePhase += outputSampleRate
                        while (resamplePhase >= MICROPHONE_SAMPLE_RATE && elements < maximumElements) {
                            resamplePhase -= MICROPHONE_SAMPLE_RATE
                            val i = modulator.process(microphoneSample)
                            if (streamFormat == "CS16") {
                                packet.putShort((i * Short.MAX_VALUE).toInt().toShort())
                                packet.putShort(0)
                            } else {
                                packet.putFloat(i.toFloat())
                                packet.putFloat(0f)
                            }
                            elements++
                        }
                    }
                    if (elements == 0) continue

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
                if (interval >= 1.0) {
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
            if (!running.get()) onStopped()
        } catch (error: Throwable) {
            if (running.getAndSet(false)) onError(error)
        }
    }

    private fun drainStatusErrors() {
        val input = DataInputStream(status.getInputStream())
        while (input.available() >= STREAM_HEADER_BYTES) {
            val report = readHeader(input)
            if (report.elements < 0 && report.elements != SOAPY_SDR_NOT_SUPPORTED) {
                error("SoapyRemote TX stream status ${report.elements}")
            }
        }
    }

    override fun close() {
        running.set(false)
        audioRecord?.runCatching { stop() }
        writerThread?.join(2_000)
        audioRecord?.release()
        audioRecord = null
        runCatching {
            transact(
                SoapyRpcWriter().call(DEACTIVATE_STREAM).int32(streamId).int32(0).int64(0),
            ) { it.int32() }
        }
        runCatching { transact(SoapyRpcWriter().call(CLOSE_STREAM).int32(streamId)) { it.requireVoid() } }
        runCatching { stream.close() }
        runCatching { status.close() }
        runCatching { transact(SoapyRpcWriter().call(UNMAKE)) { it.requireVoid() } }
        runCatching { transact(SoapyRpcWriter().call(HANGUP)) { it.requireVoid() } }
        runCatching { control.close() }
    }

    @Synchronized
    private fun <T> transact(request: SoapyRpcWriter, decode: (SoapyRpcReader) -> T): T {
        control.getOutputStream().apply { write(request.frame().encode()); flush() }
        return decode(SoapyRpcReader(SoapyRpcFrame.readFrom(control.getInputStream()).payload))
    }

    companion object {
        const val TEST_TX_LIMIT_SECONDS = 30
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
        private const val STREAM_HEADER_BYTES = 24
        private const val MTU = 4096
        private const val STREAM_TRANSFER_BYTES = MTU - 48
        private const val SOCKET_WINDOW = 1_048_576
        private const val MICROPHONE_SAMPLE_RATE = 48_000
        private const val SOAPY_SDR_NOT_SUPPORTED = -5

        fun open(
            host: String,
            port: Int,
            deviceArgs: Map<String, String>,
            frequencyHz: Double,
            sampleRate: Double,
            format: String,
        ): SoapyRemoteTxSession {
            require(sampleRate >= 8_000) { "TX sample rate must be at least 8000 Hz" }
            require(format == "CS16" || format == "CF32") {
                "This transmitter currently supports CS16 or CF32 streams, not $format"
            }
            val control = Socket()
            var stream: Socket? = null
            var status: Socket? = null
            try {
                control.connect(InetSocketAddress(host.trim(), port), 3_000)
                control.soTimeout = 10_000
                fun <T> transact(request: SoapyRpcWriter, decode: (SoapyRpcReader) -> T): T {
                    control.getOutputStream().apply { write(request.frame().encode()); flush() }
                    return decode(SoapyRpcReader(SoapyRpcFrame.readFrom(control.getInputStream()).payload))
                }

                transact(SoapyRpcWriter().call(MAKE).kwargs(deviceArgs - "soapy_remote_no_deeper")) {
                    it.requireVoid()
                }
                transact(
                    SoapyRpcWriter().call(SET_FREQUENCY).char(TX).int32(0)
                        .float64(frequencyHz).kwargs(emptyMap()),
                ) { it.requireVoid() }
                transact(
                    SoapyRpcWriter().call(SET_SAMPLE_RATE).char(TX).int32(0).float64(sampleRate),
                ) { it.requireVoid() }

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
                ).string().toInt()

                stream = Socket().apply {
                    sendBufferSize = SOCKET_WINDOW
                    connect(InetSocketAddress(host.trim(), bindPort), 3_000)
                    soTimeout = 3_000
                }
                status = Socket().apply { connect(InetSocketAddress(host.trim(), bindPort), 3_000) }
                val setupReply = SoapyRpcReader(
                    SoapyRpcFrame.readFrom(control.getInputStream()).payload,
                )
                val streamId = setupReply.int32()
                setupReply.string()
                return SoapyRemoteTxSession(
                    control, stream!!, status!!, streamId, sampleRate, format,
                )
            } catch (error: Throwable) {
                runCatching { stream?.close() }
                runCatching { status?.close() }
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
