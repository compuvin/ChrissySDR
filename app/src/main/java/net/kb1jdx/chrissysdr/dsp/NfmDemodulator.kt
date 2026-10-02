package com.kb1jdx.chrissysdr.dsp

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Stateful narrowband-FM discriminator, channel/audio filters, and deemphasis. */
class NfmDemodulator(
    private val sampleRate: Double,
    rfWidthHz: Double,
    audioCutoffHz: Double,
    deemphasisUs: Int,
) {
    init {
        require(sampleRate.isFinite() && sampleRate > 0.0)
        require(rfWidthHz.isFinite() && rfWidthHz > 0.0 && rfWidthHz < sampleRate)
        require(audioCutoffHz.isFinite() && audioCutoffHz > 0.0 && audioCutoffHz < sampleRate / 2.0)
        require(deemphasisUs in setOf(0, 50, 75))
    }

    private val rfTaps = lowPassTaps(minOf(rfWidthHz * 0.45, sampleRate * 0.4), sampleRate)
    private val audioTaps = lowPassTaps(audioCutoffHz, sampleRate)
    private val historyI = DoubleArray(TAPS)
    private val historyQ = DoubleArray(TAPS)
    private val audioHistory = DoubleArray(TAPS)
    private var newest = -1
    private var previousI = 0.0
    private var previousQ = 0.0
    private var hasPrevious = false
    private var previousDiscriminator = 0.0
    private var previousDcOutput = 0.0
    private var deemphasisOutput = 0.0
    private val dcCoefficient = exp(-2.0 * PI * 25.0 / sampleRate)
    private val deemphasisAlpha = if (deemphasisUs == 0) 1.0 else
        1.0 - exp(-1.0 / (sampleRate * deemphasisUs * 1e-6))
    private val discriminatorScale = sampleRate / (2.0 * PI * DEVIATION_HZ)

    fun process(i: Double, q: Double): Double {
        newest = (newest + 1) % TAPS
        historyI[newest] = i
        historyQ[newest] = q
        var filteredI = 0.0
        var filteredQ = 0.0
        var position = newest
        for (tap in 0 until TAPS) {
            filteredI += historyI[position] * rfTaps[tap]
            filteredQ += historyQ[position] * rfTaps[tap]
            position = if (position == 0) TAPS - 1 else position - 1
        }
        val discriminator = if (hasPrevious &&
            (filteredI * filteredI + filteredQ * filteredQ) > 1e-12 &&
            (previousI * previousI + previousQ * previousQ) > 1e-12
        ) {
            val cross = previousI * filteredQ - previousQ * filteredI
            val dot = previousI * filteredI + previousQ * filteredQ
            atan2(cross, dot) * discriminatorScale
        } else 0.0
        previousI = filteredI
        previousQ = filteredQ
        hasPrevious = true

        val dcRemoved = discriminator - previousDiscriminator + dcCoefficient * previousDcOutput
        previousDiscriminator = discriminator
        previousDcOutput = dcRemoved
        deemphasisOutput += deemphasisAlpha * (dcRemoved - deemphasisOutput)
        audioHistory[newest] = deemphasisOutput
        var audio = 0.0
        position = newest
        for (tap in 0 until TAPS) {
            audio += audioHistory[position] * audioTaps[tap]
            position = if (position == 0) TAPS - 1 else position - 1
        }
        return audio
    }

    private companion object {
        const val TAPS = 129
        const val DEVIATION_HZ = 2_500.0

        fun lowPassTaps(cutoffHz: Double, sampleRate: Double): DoubleArray {
            val taps = DoubleArray(TAPS)
            val center = (TAPS - 1) / 2
            var sum = 0.0
            for (tap in taps.indices) {
                val offset = tap - center
                val prototype = if (offset == 0) 2.0 * cutoffHz / sampleRate else
                    sin(2.0 * PI * cutoffHz * offset / sampleRate) / (PI * offset)
                val window = 0.54 - 0.46 * cos(2.0 * PI * tap / (TAPS - 1))
                taps[tap] = prototype * window
                sum += taps[tap]
            }
            for (tap in taps.indices) taps[tap] /= sum
            return taps
        }
    }
}
