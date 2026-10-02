package com.kb1jdx.chrissysdr.soapyremote

import com.kb1jdx.chrissysdr.audio.AndroidAudioOutput
import com.kb1jdx.chrissysdr.dsp.AmReceivePipeline
import com.kb1jdx.chrissysdr.dsp.SsbReceivePipeline
import com.kb1jdx.chrissysdr.dsp.SpectrumAnalyzer
import com.kb1jdx.chrissysdr.dsp.ComplexPolyphaseResampler
import com.kb1jdx.chrissysdr.radio.SoapyStreamException
import com.kb1jdx.chrissysdr.radio.RadioOpenCancellation
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
    val spectrumSampleRate: Double,
    private val streamFormat: String,
    private val fullScale: Double,
    private val passbandHz: Double,
    private val mode: String,
) : AutoCloseable {
    private val sampleCodec = IqSampleCodec(streamFormat, fullScale)
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var readerThread: Thread? = null
    @Volatile private var audioOutput: AndroidAudioOutput? = null

    fun start(
        onStatistics: (RxStatistics) -> Unit,
        onSpectrum: (FloatArray) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        check(!closed.get()) { "RX stream is closed" }
        check(running.compareAndSet(false, true)) { "RX stream is already running" }
        try {
            val activation = transact(
                SoapyRpcWriter().call(ACTIVATE_STREAM).int32(streamId).int32(0).int64(0).int32(0),
            ) { it.int32() }
            check(activation == 0) { "SoapyRemote activateStream returned $activation" }

            val audioSampleRate = ANDROID_AUDIO_SAMPLE_RATE
            check(inputSampleRate >= MIN_RADIO_SAMPLE_RATE) {
                "The selected sample rate is too low for Android audio ($inputSampleRate Hz)"
            }
            audioOutput = AndroidAudioOutput(audioSampleRate)
            readerThread = Thread(
                { receiveLoop(audioSampleRate, onStatistics, onSpectrum, onError) },
                "SoapyRemote-RX",
            ).apply { start() }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun receiveLoop(
        audioSampleRate: Int,
        onStatistics: (RxStatistics) -> Unit,
        onSpectrum: (FloatArray) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        var nextSequence = 0L
        var acknowledgedSequence = 0L
        var gaps = 0L
        var totalSamples = 0L
        var intervalSamples = 0L
        var sumSquares = 0.0
        var peak = 0.0
        var intervalStart = System.nanoTime()
        var lastPacketAt = intervalStart
        val bytesPerElement = sampleCodec.bytesPerElement
        val spectrumAnalyzer = SpectrumAnalyzer()
        val spectrumResampler = if (inputSampleRate > spectrumSampleRate)
            ComplexPolyphaseResampler(
                inputSampleRate, spectrumSampleRate, spectrumSampleRate * 0.85,
            ) else null

        try {
            val input = DataInputStream(stream.getInputStream())
            val output = stream.getOutputStream()
            val amPipeline = if (mode == "AM")
                AmReceivePipeline(inputSampleRate, audioSampleRate, passbandHz) else null
            val ssbPipeline = if (mode == "USB" || mode == "LSB")
                SsbReceivePipeline(inputSampleRate, audioSampleRate, passbandHz, mode == "USB") else null
            while (running.get()) {
                try {
                    val bytes = input.readInt()
                    val sequence = input.readInt().toLong() and 0xffff_ffffL
                    val elements = input.readInt()
                    input.readInt() // flags
                    input.readLong() // timestamp
                    require(bytes >= STREAM_HEADER_BYTES) { "Invalid stream packet size $bytes" }
                    if (elements < 0) throw SoapyStreamException("SoapyRemote RX stream error $elements")
                    val payloadBytes = bytes - STREAM_HEADER_BYTES
                    require(payloadBytes == elements * bytesPerElement) {
                        "Stream packet has $payloadBytes bytes for $elements $streamFormat samples"
                    }
                    if (sequence != nextSequence) gaps++
                    nextSequence = sequence + 1

                    val payload = ByteArray(payloadBytes)
                    input.readFully(payload)
                    lastPacketAt = System.nanoTime()
                    val iq = FloatArray(elements * 2)
                    sampleCodec.decode(payload, elements, iq)
                    repeat(elements) { index ->
                        val i = iq[index * 2].toDouble()
                        val q = iq[index * 2 + 1].toDouble()
                        val power = i * i + q * q
                        val magnitude = sqrt(power)
                        sumSquares += power
                        peak = maxOf(peak, magnitude)
                    }
                    val audio = amPipeline?.process(iq, elements)
                        ?: checkNotNull(ssbPipeline).process(iq, elements)
                    audioOutput?.write(audio, audio.size)
                        ?: error("Android audio output is closed")
                    val spectrumIq = spectrumResampler?.process(iq, elements) ?: iq
                    spectrumAnalyzer.accept(
                        spectrumIq, spectrumIq.size / 2, System.nanoTime(),
                    )?.let(onSpectrum)
                    totalSamples += elements
                    intervalSamples += elements

                    if (nextSequence - acknowledgedSequence >= ACK_INTERVAL_PACKETS) {
                        sendAck(output, nextSequence, FLOW_WINDOW_PACKETS)
                        acknowledgedSequence = nextSequence
                    }
                    drainStatusErrors()

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
                    if (System.nanoTime() - lastPacketAt > STREAM_STALL_NS) {
                        throw SoapyStreamException("No RX samples received for 10 seconds")
                    }
                    drainStatusErrors()
                }
            }
        } catch (error: Throwable) {
            if (running.get()) {
                close()
                onError(error)
            }
        }
    }

    private fun drainStatusErrors() {
        SoapyStreamStatus.drain(DataInputStream(status.getInputStream()), "RX")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (running.getAndSet(false)) {
            runCatching {
                transact(
                    SoapyRpcWriter().call(DEACTIVATE_STREAM).int32(streamId).int32(0).int64(0),
                ) { it.int32() }
            }
        }
        runCatching { stream.close() }
        runCatching { status.close() }
        if (Thread.currentThread() !== readerThread) {
            runCatching { readerThread?.join(2_000) }
        }
        audioOutput?.runCatching { close() }
        audioOutput = null
        runCatching { transact(SoapyRpcWriter().call(CLOSE_STREAM).int32(streamId)) { it.requireVoid() } }
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
        private const val SET_ANTENNA = 501
        private const val SET_GAIN_MODE = 701
        private const val SET_GAIN_ELEMENT = 704
        private const val STREAM_HEADER_BYTES = 24
        private const val MTU = 4096
        private const val SOCKET_WINDOW = 1_048_576
        private const val FLOW_WINDOW_PACKETS = SOCKET_WINDOW / MTU
        private const val ACK_INTERVAL_PACKETS = FLOW_WINDOW_PACKETS / 8
        private const val ANDROID_AUDIO_SAMPLE_RATE = 48_000
        private const val MAX_SPECTRUM_RATE = 48_000.0
        private const val MIN_RADIO_SAMPLE_RATE = 8_000
        private const val STREAM_STALL_NS = 10_000_000_000L
        private const val SET_SAMPLE_RATE = 900
        private const val GET_SAMPLE_RATE = 901

        fun open(
            host: String,
            port: Int,
            deviceArgs: Map<String, String>,
            frequencyHz: Double,
            bandwidthHz: Double,
            hardwareBandwidthHz: Double?,
            sampleRate: Double,
            format: String,
            fullScale: Double,
            mode: String,
            rxGains: Map<String, Double>,
            rxAntenna: String?,
            rxHardwareAgc: Boolean?,
            cancellation: RadioOpenCancellation,
        ): SoapyRemoteRxSession {
            require(sampleRate >= MIN_RADIO_SAMPLE_RATE) { "Sample rate must be at least 8000 Hz" }
            require(bandwidthHz.isFinite() && bandwidthHz > 0.0) { "Passband must be positive" }
            IqSampleCodec(format, fullScale)
            require(mode == "AM" || mode == "USB" || mode == "LSB") { "Unsupported RX mode $mode" }
            val control = Socket().also(cancellation::register)
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
                    SoapyRpcWriter().call(SET_SAMPLE_RATE).char(RX).int32(0).float64(sampleRate),
                ) { it.requireVoid() }
                rxAntenna?.let { antenna ->
                    transact(SoapyRpcWriter().call(SET_ANTENNA).char(RX).int32(0).string(antenna)) {
                        it.requireVoid()
                    }
                }
                rxHardwareAgc?.let { enabled ->
                    transact(SoapyRpcWriter().call(SET_GAIN_MODE).char(RX).int32(0).bool(enabled)) {
                        it.requireVoid()
                    }
                }
                if (rxHardwareAgc != true) rxGains.forEach { (name, gain) ->
                    transact(SoapyRpcWriter().call(SET_GAIN_ELEMENT).char(RX).int32(0)
                        .string(name).float64(gain)) { it.requireVoid() }
                }
                transact(
                    SoapyRpcWriter().call(SET_FREQUENCY).char(RX).int32(0)
                        .float64(frequencyHz).kwargs(emptyMap()),
                ) { it.requireVoid() }
                hardwareBandwidthHz?.let { hardwareBandwidth ->
                    transact(
                        SoapyRpcWriter().call(SET_BANDWIDTH).char(RX).int32(0).float64(hardwareBandwidth),
                    ) { it.requireVoid() }
                }
                val appliedSampleRate = transact(
                    SoapyRpcWriter().call(GET_SAMPLE_RATE).char(RX).int32(0),
                ) { it.float64() }
                require(appliedSampleRate >= MIN_RADIO_SAMPLE_RATE) {
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
                ).let { reader -> reader.string().toInt().also { reader.requireFinished() } }

                stream = Socket().apply {
                    cancellation.register(this)
                    receiveBufferSize = SOCKET_WINDOW
                    connect(InetSocketAddress(host.trim(), bindPort), 3_000)
                    soTimeout = 1_000
                }
                status = Socket().apply {
                    cancellation.register(this)
                    connect(InetSocketAddress(host.trim(), bindPort), 3_000)
                }

                val setupReply = SoapyRpcReader(
                    SoapyRpcFrame.readFrom(control.getInputStream()).payload,
                )
                streamId = setupReply.int32()
                setupReply.string() // repeated server port
                setupReply.requireFinished()
                sendAck(stream!!.getOutputStream(), 0, FLOW_WINDOW_PACKETS)
                return SoapyRemoteRxSession(
                    control, stream!!, status!!, streamId, appliedSampleRate,
                    minOf(appliedSampleRate, MAX_SPECTRUM_RATE), format, fullScale,
                    bandwidthHz, mode,
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
