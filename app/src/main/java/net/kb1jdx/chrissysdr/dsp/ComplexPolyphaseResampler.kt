package com.kb1jdx.chrissysdr.dsp

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Streaming, anti-aliasing IQ rate conversion. Half-band polyphase stages keep
 * large radio/audio ratios inexpensive; the final fractional-delay polyphase
 * FIR handles non-integer ratios. Filter/history state spans stream packets.
 */
class ComplexPolyphaseResampler(
    inputSampleRate: Double,
    private val outputSampleRate: Double,
    passbandHz: Double,
) {
    private val halfBandStages = ArrayList<HalfBandDecimator>()
    private val finalStage: FractionalStage?

    init {
        require(inputSampleRate.isFinite() && inputSampleRate > 0.0)
        require(outputSampleRate.isFinite() && outputSampleRate > 0.0)
        require(passbandHz.isFinite() && passbandHz > 0.0)

        var rate = inputSampleRate
        while (rate >= outputSampleRate * 2.0) {
            halfBandStages += HalfBandDecimator()
            rate /= 2.0
        }
        finalStage = if (rate == outputSampleRate) null else FractionalStage(
            rate,
            outputSampleRate,
            minOf(passbandHz / 2.0, rate * 0.4, outputSampleRate * 0.4),
        )
    }

    fun process(iq: FloatArray, elements: Int = iq.size / 2): FloatArray {
        require(elements >= 0 && elements * 2 <= iq.size)
        var current = if (elements * 2 == iq.size) iq else iq.copyOf(elements * 2)
        halfBandStages.forEach { current = it.process(current) }
        return finalStage?.process(current) ?: current
    }
}

private class HalfBandDecimator {
    private val historyI = FloatArray(TAPS)
    private val historyQ = FloatArray(TAPS)
    private val coefficients = DoubleArray(TAPS)
    private var newest = -1
    private var samplesSeen = 0L

    init {
        val center = (TAPS - 1) / 2
        var sum = 0.0
        for (tap in 0 until TAPS) {
            val offset = tap - center
            // A half-band prototype has exact zero taps at even, nonzero offsets.
            val value = when {
                offset == 0 -> 0.5
                offset % 2 == 0 -> 0.0
                else -> sin(PI * offset / 2.0) / (PI * offset)
            } * blackman(tap, TAPS)
            coefficients[tap] = value
            sum += value
        }
        for (tap in coefficients.indices) coefficients[tap] /= sum
    }

    fun process(iq: FloatArray): FloatArray {
        require(iq.size % 2 == 0)
        val output = FloatArray((iq.size / 4 + 1) * 2)
        var written = 0
        for (index in 0 until iq.size / 2) {
            newest = (newest + 1) % TAPS
            historyI[newest] = iq[index * 2]
            historyQ[newest] = iq[index * 2 + 1]
            samplesSeen++
            if (samplesSeen % 2L != 0L) continue
            var i = 0.0
            var q = 0.0
            for (tap in coefficients.indices) {
                val coefficient = coefficients[tap]
                if (coefficient == 0.0) continue
                val position = (newest - tap + TAPS) % TAPS
                i += historyI[position] * coefficient
                q += historyQ[position] * coefficient
            }
            output[written++] = i.toFloat()
            output[written++] = q.toFloat()
        }
        return output.copyOf(written)
    }

    private companion object { const val TAPS = 47 }
}

private class FractionalStage(
    inputRate: Double,
    outputRate: Double,
    cutoffHz: Double,
) {
    private val step = inputRate / outputRate
    private val cutoff = cutoffHz / inputRate
    private val historyI = FloatArray(TAPS)
    private val historyQ = FloatArray(TAPS)
    private val phases = Array(PHASES + 1) { phase -> coefficients(phase.toDouble() / PHASES) }
    private var newest = -1
    private var samplesSeen = 0L
    private var nextOutputTime = 0.0

    fun process(iq: FloatArray): FloatArray {
        require(iq.size % 2 == 0)
        val estimated = ceil((iq.size / 2) / step).toInt() + 2
        val output = FloatArray(estimated * 2)
        var written = 0
        for (index in 0 until iq.size / 2) {
            newest = (newest + 1) % TAPS
            historyI[newest] = iq[index * 2]
            historyQ[newest] = iq[index * 2 + 1]
            val currentIndex = samplesSeen++
            while (floor(nextOutputTime).toLong() + CENTER <= currentIndex) {
                val fraction = nextOutputTime - floor(nextOutputTime)
                val phase = (fraction * PHASES + 0.5).toInt().coerceIn(0, PHASES)
                val taps = phases[phase]
                var i = 0.0
                var q = 0.0
                for (tap in 0 until TAPS) {
                    val position = (newest - tap + TAPS) % TAPS
                    i += historyI[position] * taps[tap]
                    q += historyQ[position] * taps[tap]
                }
                output[written++] = i.toFloat()
                output[written++] = q.toFloat()
                nextOutputTime += step
            }
        }
        return output.copyOf(written)
    }

    private fun coefficients(fraction: Double): DoubleArray {
        val taps = DoubleArray(TAPS)
        var sum = 0.0
        for (tap in taps.indices) {
            val offset = tap - CENTER + fraction
            val value = if (offset == 0.0) 2.0 * cutoff else
                sin(2.0 * PI * cutoff * offset) / (PI * offset)
            taps[tap] = value * blackman(tap, TAPS)
            sum += taps[tap]
        }
        for (tap in taps.indices) taps[tap] /= sum
        return taps
    }

    private companion object {
        const val TAPS = 63
        const val CENTER = (TAPS - 1) / 2
        const val PHASES = 256
    }
}

private fun blackman(tap: Int, count: Int): Double {
    val angle = 2.0 * PI * tap / (count - 1)
    return 0.42 - 0.5 * cos(angle) + 0.08 * cos(2.0 * angle)
}
