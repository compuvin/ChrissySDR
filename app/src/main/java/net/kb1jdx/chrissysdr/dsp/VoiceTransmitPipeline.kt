package com.kb1jdx.chrissysdr.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Microphone PCM to mode-specific, band-limited Soapy TX IQ. State spans audio blocks. */
class VoiceTransmitPipeline(
    private val mode: String,
    private val inputSampleRate: Int,
    outputSampleRate: Double,
) {
    private val am = AmModulator()
    private val audioFilter = AudioLowPass(inputSampleRate, if (mode == "AM") 5_000.0 else 3_000.0)
    private val hilbert = HilbertTransformer()
    private val resampler = ComplexPolyphaseResampler(
        inputSampleRate.toDouble(), outputSampleRate,
        if (mode == "AM") 12_000.0 else if (mode == "NFM") 16_000.0 else 7_000.0,
    )
    private var fmPhase = 0.0

    init {
        require(mode in setOf("AM", "NFM", "USB", "LSB")) { "Unsupported TX mode: $mode" }
        require(inputSampleRate > 0 && outputSampleRate.isFinite() && outputSampleRate >= 8_000.0)
    }

    fun process(audio: ShortArray, count: Int = audio.size): FloatArray {
        require(count in 0..audio.size)
        val baseband = FloatArray(count * 2)
        for (index in 0 until count) {
            val speech = audioFilter.process(audio[index].toDouble() / 32768.0)
            val i: Double
            val q: Double
            when (mode) {
                "AM" -> {
                    i = am.process(speech)
                    q = 0.0
                }
                "NFM" -> {
                    // 2.5 kHz peak deviation, with a constant-envelope carrier.
                    fmPhase += 2.0 * PI * 2_500.0 * speech.coerceIn(-1.0, 1.0) / inputSampleRate
                    if (fmPhase > PI) fmPhase -= 2.0 * PI
                    if (fmPhase < -PI) fmPhase += 2.0 * PI
                    i = 0.7 * cos(fmPhase)
                    q = 0.7 * sin(fmPhase)
                }
                else -> {
                    val (delayed, quadrature) = hilbert.process(speech)
                    i = (0.7 * delayed).coerceIn(-1.0, 1.0)
                    q = (if (mode == "USB") 0.7 * quadrature else -0.7 * quadrature)
                        .coerceIn(-1.0, 1.0)
                }
            }
            baseband[index * 2] = i.toFloat()
            baseband[index * 2 + 1] = q.toFloat()
        }
        return resampler.process(baseband)
    }
}

private class AudioLowPass(sampleRate: Int, cutoffHz: Double) {
    private val taps = DoubleArray(65)
    private val history = DoubleArray(taps.size)
    private var newest = -1

    init {
        val cutoff = cutoffHz / sampleRate
        val center = (taps.size - 1) / 2
        var total = 0.0
        for (index in taps.indices) {
            val offset = index - center
            val sinc = if (offset == 0) 2.0 * cutoff else
                sin(2.0 * PI * cutoff * offset) / (PI * offset)
            val window = 0.42 - 0.5 * cos(2.0 * PI * index / (taps.size - 1)) +
                0.08 * cos(4.0 * PI * index / (taps.size - 1))
            taps[index] = sinc * window
            total += taps[index]
        }
        for (index in taps.indices) taps[index] /= total
    }

    fun process(sample: Double): Double {
        newest = (newest + 1) % taps.size
        history[newest] = sample
        var result = 0.0
        for (tap in taps.indices) result += taps[tap] * history[(newest - tap + taps.size) % taps.size]
        return result
    }
}

private class HilbertTransformer {
    private val taps = DoubleArray(65)
    private val history = DoubleArray(taps.size)
    private var newest = -1

    init {
        val center = (taps.size - 1) / 2
        for (index in taps.indices) {
            val offset = index - center
            if (offset != 0 && offset % 2 != 0) {
                val window = 0.42 - 0.5 * cos(2.0 * PI * index / (taps.size - 1)) +
                    0.08 * cos(4.0 * PI * index / (taps.size - 1))
                taps[index] = 2.0 * window / (PI * offset)
            }
        }
    }

    fun process(sample: Double): Pair<Double, Double> {
        newest = (newest + 1) % taps.size
        history[newest] = sample
        var q = 0.0
        for (tap in taps.indices) q += taps[tap] * history[(newest - tap + taps.size) % taps.size]
        val delayed = history[(newest - (taps.size - 1) / 2 + taps.size) % taps.size]
        return delayed to q
    }
}
