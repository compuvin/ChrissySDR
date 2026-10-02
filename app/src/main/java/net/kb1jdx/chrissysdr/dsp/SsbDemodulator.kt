package com.kb1jdx.chrissysdr.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/** Product detector for an IQ stream tuned to the suppressed carrier. */
class SsbDemodulator(
    private val sampleRate: Double,
    private val upperSideband: Boolean,
    passbandHz: Double,
    bfoOffsetHz: Double = 0.0,
    private val agcEnabled: Boolean = true,
) {
    private val tapsI = DoubleArray(TAPS)
    private val tapsQ = DoubleArray(TAPS)
    private val historyI = DoubleArray(TAPS)
    private val historyQ = DoubleArray(TAPS)
    private var newest = -1
    private var oscillatorPhase = 0.0
    private val oscillatorStep: Double
    private var previousInput = 0.0
    private var previousOutput = 0.0
    private var agcEnvelope = 0.01
    private val dcCoefficient: Double
    private val agcAttack: Double
    private val agcRelease: Double

    init {
        require(sampleRate.isFinite() && sampleRate > 0.0)
        require(passbandHz.isFinite() && passbandHz > 0.0 && passbandHz < sampleRate / 2.0)
        require(bfoOffsetHz.isFinite() && abs(bfoOffsetHz) < sampleRate / 2.0)
        oscillatorStep = 2.0 * PI * bfoOffsetHz / sampleRate
        dcCoefficient = exp(-2.0 * PI * 20.0 / sampleRate)
        agcAttack = 1.0 - exp(-1.0 / (sampleRate * 0.010))
        agcRelease = 1.0 - exp(-1.0 / (sampleRate * 0.300))

        // A complex bandpass admits only one side of the carrier; the opposite
        // sideband and the carrier are rejected before taking the real product.
        val high = minOf(passbandHz, sampleRate * 0.4)
        val low = minOf(250.0, high * 0.1)
        val center = (low + high) / 2.0 * if (upperSideband) 1.0 else -1.0
        val width = high - low
        for (tap in 0 until TAPS) {
            val offset = tap - (TAPS - 1) / 2
            val prototype = if (offset == 0) width / sampleRate else
                sin(PI * width * offset / sampleRate) / (PI * offset)
            val window = 0.54 - 0.46 * cos(2.0 * PI * tap / (TAPS - 1))
            val phase = 2.0 * PI * center * offset / sampleRate
            tapsI[tap] = prototype * window * cos(phase)
            tapsQ[tap] = prototype * window * sin(phase)
        }
    }

    fun process(i: Double, q: Double): Double {
        val rotationI = cos(oscillatorPhase)
        val rotationQ = sin(oscillatorPhase)
        newest = (newest + 1) % TAPS
        historyI[newest] = i * rotationI + q * rotationQ
        historyQ[newest] = q * rotationI - i * rotationQ
        oscillatorPhase += oscillatorStep
        if (oscillatorPhase > PI) oscillatorPhase -= 2.0 * PI
        if (oscillatorPhase < -PI) oscillatorPhase += 2.0 * PI

        var product = 0.0
        for (tap in 0 until TAPS) {
            val position = (newest - tap + TAPS) % TAPS
            product += historyI[position] * tapsI[tap] - historyQ[position] * tapsQ[tap]
        }
        val raw = product * 2.0
        val audio = raw - previousInput + dcCoefficient * previousOutput
        previousInput = raw
        previousOutput = audio
        if (!agcEnabled) return audio
        val magnitude = abs(audio)
        agcEnvelope += (if (magnitude > agcEnvelope) agcAttack else agcRelease) *
            (magnitude - agcEnvelope)
        val gain = minOf(5_000.0, 0.6 / max(agcEnvelope, 0.00001), 0.9 / max(magnitude, 1.0e-9))
        return audio * gain
    }

    companion object { const val TAPS = 129 }
}
