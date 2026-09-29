package com.kb1jdx.chrissysdr.soapyremote

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.DataInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt
import com.kb1jdx.chrissysdr.dsp.AmDemodulator

data class RxStatistics(
    val totalSamples: Long,
    val samplesPerSecond: Double,
    val rmsDbfs: Double,
    val peakDbfs: Double,
    val sequenceGaps: Long,
)

class SoapyRemoteRxSession private constructor(
    private val control: Socket,
    private val stream: Socket,
    private val status: Socket,
    private val streamId: Int,
    val inputSampleRate: Double,
    private val streamFormat: String,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private var readerThread: Thread? = null
    @Volatile private var audioTrack: AudioTrack? = null

    fun start(onStatistics: (RxStatistics) -> Unit, onError: (Throwable) -> Unit) {
        check(running.compareAndSet(false, true)) { "RX stream is already running" }
        val activation = transact(
            SoapyRpcWriter().call(ACTIVATE_STREAM).int32(streamId).int32(0).int64(0).int32(0),
        ) { it.int32() }
        check(activation == 0) { "SoapyRemote activateStream returned $activation" }

        val audioSampleRate = minOf(MAX_AUDIO_SAMPLE_RATE, inputSampleRate.toInt())
        check(audioSampleRate >= MIN_AUDIO_SAMPLE_RATE) {
            "The selected sample rate is too low for Android audio ($inputSampleRate Hz)"
        }
        val minimumAudioBuffer = AudioTrack.getMinBufferSize(
            audioSampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimumAudioBuffer > 0) { "Android audio output is unavailable ($minimumAudioBuffer)" }
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(audioSampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minimumAudioBuffer * 2, audioSampleRate / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also {
                check(it.state == AudioTrack.STATE_INITIALIZED) { "Android audio output failed to initialize" }
                it.play()
            }

        readerThread = Thread(
            { receiveLoop(audioSampleRate, onStatistics, onError) },
            "SoapyRemote-RX",
        ).apply { start() }
    }

    private fun receiveLoop(
        audioSampleRate: Int,
        onStatistics: (RxStatistics) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val input = DataInputStream(stream.getInputStream())
        val output = stream.getOutputStream()
        var nextSequence = 0L
        var acknowledgedSequence = 0L
        var gaps = 0L
        var totalSamples = 0L
        var intervalSamples = 0L
        var sumSquares = 0.0
        var peak = 0.0
        var intervalStart = System.nanoTime()
        val demodulator = AmDemodulator(
            sampleRate = inputSampleRate,
            cutoffHz = minOf(6_000.0, audioSampleRate * 0.4),
        )
        val bytesPerElement = when (streamFormat) {
            "CS16" -> 4
            "CF32" -> 8
            else -> error("Unsupported stream format $streamFormat")
        }
        var audioPhase = 0.0

        try {
            while (running.get()) {
                try {
                    val bytes = input.readInt()
                    val sequence = input.readInt().toLong() and 0xffff_ffffL
                    val elements = input.readInt()
                    input.readInt() // flags
                    input.readLong() // timestamp
                    require(bytes >= STREAM_HEADER_BYTES) { "Invalid stream packet size $bytes" }
                    require(elements >= 0) { "SoapyRemote stream error $elements" }
                    val payloadBytes = bytes - STREAM_HEADER_BYTES
                    require(payloadBytes == elements * bytesPerElement) {
                        "Stream packet has $payloadBytes bytes for $elements $streamFormat samples"
                    }
                    if (sequence != nextSequence) gaps++
                    nextSequence = sequence + 1

                    val payload = ByteArray(payloadBytes)
                    input.readFully(payload)
                    val samples = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                    val audio = ShortArray(
                        kotlin.math.ceil(elements * audioSampleRate / inputSampleRate).toInt() + 1,
                    )
                    var audioCount = 0
                    repeat(elements) {
                        val (i, q) = when (streamFormat) {
                            "CS16" -> samples.short.toDouble() / 32768.0 to
                                samples.short.toDouble() / 32768.0
                            "CF32" -> samples.float.toDouble() to samples.float.toDouble()
                            else -> error("Unsupported stream format $streamFormat")
                        }
                        val power = i * i + q * q
                        val magnitude = sqrt(power)
                        sumSquares += power
                        peak = maxOf(peak, magnitude)

                        val sample = demodulator.process(i, q)
                        audioPhase += audioSampleRate
                        if (audioPhase >= inputSampleRate) {
                            audioPhase -= inputSampleRate
                            audio[audioCount++] = (sample.coerceIn(-0.95, 0.95) * Short.MAX_VALUE)
                                .toInt().toShort()
                        }
                    }
                    val written = audioTrack?.write(audio, 0, audioCount, AudioTrack.WRITE_BLOCKING) ?: -1
                    check(written >= 0) { "Android audio write failed ($written)" }
                    totalSamples += elements
                    intervalSamples += elements

                    if (nextSequence - acknowledgedSequence >= ACK_INTERVAL_PACKETS) {
                        sendAck(output, nextSequence, FLOW_WINDOW_PACKETS)
                        acknowledgedSequence = nextSequence
                    }

                    val now = System.nanoTime()
                    val elapsed = (now - intervalStart) / 1_000_000_000.0
                    if (elapsed >= 1.0) {
                        val rms = if (intervalSamples == 0L) 0.0 else sqrt(sumSquares / intervalSamples)
                        onStatistics(
                            RxStatistics(
                                totalSamples = totalSamples,
                                samplesPerSecond = intervalSamples / elapsed,
                                rmsDbfs = amplitudeDbfs(rms),
                                peakDbfs = amplitudeDbfs(peak),
                                sequenceGaps = gaps,
                            ),
                        )
                        intervalSamples = 0
                        sumSquares = 0.0
                        peak = 0.0
                        intervalStart = now
                    }
                } catch (_: SocketTimeoutException) {
                    // Recheck the stop flag.
                }
            }
        } catch (error: Throwable) {
            if (running.get()) onError(error)
        }
    }

    override fun close() {
        if (running.getAndSet(false)) {
            runCatching {
                transact(
                    SoapyRpcWriter().call(DEACTIVATE_STREAM).int32(streamId).int32(0).int64(0),
                ) { it.int32() }
            }
        }
        audioTrack?.runCatching { pause(); flush() }
        readerThread?.join(2_000)
        audioTrack?.runCatching { stop(); release() }
        audioTrack = null
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
        private const val RX = 1
        private const val MAKE = 1
        private const val UNMAKE = 2
        private const val HANGUP = 3
        private const val SETUP_STREAM = 300
        private const val CLOSE_STREAM = 301
        private const val ACTIVATE_STREAM = 302
        private const val DEACTIVATE_STREAM = 303
        private const val SET_FREQUENCY = 800
        private const val SET_BANDWIDTH = 903
        private const val STREAM_HEADER_BYTES = 24
        private const val MTU = 4096
        private const val SOCKET_WINDOW = 1_048_576
        private const val FLOW_WINDOW_PACKETS = SOCKET_WINDOW / MTU
        private const val ACK_INTERVAL_PACKETS = FLOW_WINDOW_PACKETS / 8
        private const val MAX_AUDIO_SAMPLE_RATE = 48_000
        private const val MIN_AUDIO_SAMPLE_RATE = 8_000
        private const val SET_SAMPLE_RATE = 900
        private const val GET_SAMPLE_RATE = 901

        fun open(
            host: String,
            port: Int,
            deviceArgs: Map<String, String>,
            frequencyHz: Double,
            bandwidthHz: Double,
            sampleRate: Double,
            format: String,
        ): SoapyRemoteRxSession {
            require(sampleRate >= MIN_AUDIO_SAMPLE_RATE) { "Sample rate must be at least 8000 Hz" }
            require(format == "CS16" || format == "CF32") {
                "This receiver currently supports CS16 or CF32 streams, not $format"
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
                    SoapyRpcWriter().call(SET_SAMPLE_RATE).char(RX).int32(0).float64(sampleRate),
                ) { it.requireVoid() }
                transact(
                    SoapyRpcWriter().call(SET_FREQUENCY).char(RX).int32(0)
                        .float64(frequencyHz).kwargs(emptyMap()),
                ) { it.requireVoid() }
                transact(
                    SoapyRpcWriter().call(SET_BANDWIDTH).char(RX).int32(0).float64(bandwidthHz),
                ) { it.requireVoid() }
                val appliedSampleRate = transact(
                    SoapyRpcWriter().call(GET_SAMPLE_RATE).char(RX).int32(0),
                ) { it.float64() }
                require(appliedSampleRate >= MIN_AUDIO_SAMPLE_RATE) {
                    "Radio applied an unusable sample rate: $appliedSampleRate Hz"
                }

                val setup = SoapyRpcWriter().call(SETUP_STREAM)
                    .char(RX)
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
                    receiveBufferSize = SOCKET_WINDOW
                    connect(InetSocketAddress(host.trim(), bindPort), 3_000)
                    soTimeout = 1_000
                }
                status = Socket().apply { connect(InetSocketAddress(host.trim(), bindPort), 3_000) }

                val setupReply = SoapyRpcReader(
                    SoapyRpcFrame.readFrom(control.getInputStream()).payload,
                )
                val streamId = setupReply.int32()
                setupReply.string() // repeated server port
                sendAck(stream!!.getOutputStream(), 0, FLOW_WINDOW_PACKETS)
                return SoapyRemoteRxSession(
                    control, stream!!, status!!, streamId, appliedSampleRate, format,
                )
            } catch (error: Throwable) {
                runCatching { stream?.close() }
                runCatching { status?.close() }
                runCatching { control.close() }
                throw error
            }
        }

        private fun sendAck(output: OutputStream, sequence: Long, window: Int) {
            val header = ByteBuffer.allocate(STREAM_HEADER_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(STREAM_HEADER_BYTES)
                .putInt(sequence.toInt())
                .putInt(window)
                .putInt(0)
                .putLong(0)
                .array()
            output.write(header)
            output.flush()
        }

        private fun amplitudeDbfs(value: Double): Double =
            if (value <= 0.0) -160.0 else 20.0 * log10(value)
    }
}
