package com.kb1jdx.chrissysdr.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Streaming AM demodulator based on the validated flex1500d receive DSP:
 * 129-tap Hamming low-pass, envelope detection, DC blocking, and AGC.
 */
class AmDemodulator(
    private val sampleRate: Double = 48_000.0,
    cutoffHz: Double = 6_000.0,
    private val agcEnabled: Boolean = true,
) {
    private val coefficients = DoubleArray(FIR_TAPS)
    private val historyI = DoubleArray(FIR_TAPS)
    private val historyQ = DoubleArray(FIR_TAPS)
    private var historyIndex = 0
    private var previousAudioInput = 0.0
    private var previousAudioOutput = 0.0
    private var agcEnvelope = 1.0

    init {
        require(sampleRate > 0.0)
        require(cutoffHz > 0.0 && cutoffHz < sampleRate / 2.0)
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
        val highPass = raw - previousAudioInput + DC_BLOCKER * previousAudioOutput
        previousAudioInput = raw
        previousAudioOutput = highPass
        if (!agcEnabled) return highPass

        val magnitude = abs(highPass)
        val coefficient = if (magnitude > agcEnvelope) AGC_ATTACK else AGC_RELEASE
        agcEnvelope += coefficient * (magnitude - agcEnvelope)
        return highPass * (AGC_TARGET / max(agcEnvelope, AGC_FLOOR))
    }

    companion object {
        const val FIR_TAPS = 129
        private const val DC_BLOCKER = 0.995
        private const val AGC_ATTACK = 0.01
        private const val AGC_RELEASE = 0.0001
        private const val AGC_TARGET = 0.7
        private const val AGC_FLOOR = 1.0e-6
    }
}
