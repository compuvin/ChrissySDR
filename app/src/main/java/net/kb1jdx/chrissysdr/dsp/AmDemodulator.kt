package com.kb1jdx.chrissysdr.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Streaming AM detector: complex passband filtering, phase-independent
 * envelope detection, audio DC removal, and bounded audio AGC.
 */
class AmDemodulator(
    private val sampleRate: Double = 48_000.0,
    cutoffHz: Double = 3_000.0,
    private val agcEnabled: Boolean = true,
) {
    private val coefficients = DoubleArray(FIR_TAPS)
    private val historyI = DoubleArray(FIR_TAPS)
    private val historyQ = DoubleArray(FIR_TAPS)
    private var historyIndex = 0
    private var previousAudioInput = 0.0
    private var previousAudioOutput = 0.0
    private var agcEnvelope = AGC_INITIAL_ENVELOPE
    private val dcCoefficient = exp(-2.0 * PI * DC_CUTOFF_HZ / sampleRate)
    private val agcAttack = 1.0 - exp(-1.0 / (sampleRate * AGC_ATTACK_SECONDS))
    private val agcRelease = 1.0 - exp(-1.0 / (sampleRate * AGC_RELEASE_SECONDS))

    init {
        require(sampleRate.isFinite() && sampleRate > 0.0)
        require(cutoffHz.isFinite() && cutoffHz > 0.0 && cutoffHz < sampleRate / 2.0)
        val middle = (FIR_TAPS - 1) / 2
        var sum = 0.0
        for (tap in coefficients.indices) {
            val offset = tap - middle
            val lowPass = if (offset == 0) {
                2.0 * cutoffHz / sampleRate
            } else {
                sin(2.0 * PI * cutoffHz * offset / sampleRate) / (PI * offset)
            }
            val window = 0.54 - 0.46 * cos(2.0 * PI * tap / (FIR_TAPS - 1))
            coefficients[tap] = lowPass * window
            sum += coefficients[tap]
        }
        coefficients.indices.forEach { coefficients[it] /= sum }
    }

    fun process(i: Double, q: Double): Double {
        historyI[historyIndex] = i
        historyQ[historyIndex] = q
        var filteredI = 0.0
        var filteredQ = 0.0
        var position = historyIndex
        for (tap in coefficients.indices) {
            val coefficient = coefficients[tap]
            filteredI += historyI[position] * coefficient
            filteredQ += historyQ[position] * coefficient
            position = if (position == 0) FIR_TAPS - 1 else position - 1
        }
        historyIndex = (historyIndex + 1) % FIR_TAPS

        val raw = hypot(filteredI, filteredQ)
        val highPass = raw - previousAudioInput + dcCoefficient * previousAudioOutput
        previousAudioInput = raw
        previousAudioOutput = highPass
        if (!agcEnabled) return highPass

        val magnitude = abs(highPass)
        val coefficient = if (magnitude > agcEnvelope) agcAttack else agcRelease
        agcEnvelope += coefficient * (magnitude - agcEnvelope)
        val gain = minOf(
            AGC_MAX_GAIN,
            AGC_TARGET / max(agcEnvelope, AGC_FLOOR),
            AGC_PEAK_LIMIT / max(magnitude, 1.0e-9),
        )
        return highPass * gain
    }

    companion object {
        const val FIR_TAPS = 129
        private const val DC_CUTOFF_HZ = 20.0
        private const val AGC_ATTACK_SECONDS = 0.010
        private const val AGC_RELEASE_SECONDS = 0.300
        private const val AGC_INITIAL_ENVELOPE = 0.01
        private const val AGC_TARGET = 0.6
        private const val AGC_FLOOR = 0.00001
        private const val AGC_MAX_GAIN = 5_000.0
        private const val AGC_PEAK_LIMIT = 0.9
    }
}
