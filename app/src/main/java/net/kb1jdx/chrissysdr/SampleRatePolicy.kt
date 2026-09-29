package com.kb1jdx.chrissysdr

import com.kb1jdx.chrissysdr.soapyremote.SoapyRange
import kotlin.math.ceil
import kotlin.math.floor

data class SampleRateChoice(
    val automaticRate: Double?,
    val overrideOptions: List<Double>,
)

object SampleRatePolicy {
    private val commonRates = listOf(
        8_000.0, 12_000.0, 16_000.0, 24_000.0, 32_000.0, 48_000.0,
        96_000.0, 192_000.0, 250_000.0, 500_000.0, 1_000_000.0,
        2_000_000.0, 2_400_000.0, 5_000_000.0, 10_000_000.0,
    )

    fun choose(
        discreteRates: List<Double>,
        ranges: List<SoapyRange>,
        bandwidthHz: Double,
    ): SampleRateChoice {
        val requiredRate = maxOf(MINIMUM_RATE, bandwidthHz * BANDWIDTH_MARGIN)
        val discrete = discreteRates.filter { it >= MINIMUM_RATE }.distinct().sorted()
        if (discrete.isNotEmpty()) {
            return SampleRateChoice(
                automaticRate = discrete.firstOrNull { it >= requiredRate },
                overrideOptions = discrete,
            )
        }

        val validRanges = ranges.filter { it.maximum >= MINIMUM_RATE && it.maximum >= it.minimum }
        val automatic = validRanges.mapNotNull { range ->
            alignToRange(maxOf(requiredRate, range.minimum, MINIMUM_RATE), range)
        }.minOrNull()
        val options = buildSet {
            validRanges.forEach { range ->
                alignToRange(maxOf(range.minimum, MINIMUM_RATE), range)?.let(::add)
                alignDownToRange(range.maximum, range)?.let(::add)
                commonRates.forEach { candidate -> alignToRange(candidate, range)?.let(::add) }
            }
            automatic?.let(::add)
        }.sorted()
        return SampleRateChoice(automaticRate = automatic, overrideOptions = options)
    }

    private fun alignToRange(candidate: Double, range: SoapyRange): Double? {
        if (candidate > range.maximum) return null
        if (range.step <= 0.0) return candidate.coerceAtLeast(range.minimum)
        val steps = ceil((candidate - range.minimum).coerceAtLeast(0.0) / range.step)
        val aligned = range.minimum + steps * range.step
        return aligned.takeIf { it <= range.maximum + EPSILON }
    }

    private fun alignDownToRange(candidate: Double, range: SoapyRange): Double? {
        if (range.step <= 0.0) return candidate.coerceIn(range.minimum, range.maximum)
        val steps = floor(((candidate - range.minimum) / range.step).coerceAtLeast(0.0))
        val aligned = range.minimum + steps * range.step
        return aligned.takeIf { it in range.minimum..range.maximum }
    }

    private const val MINIMUM_RATE = 8_000.0
    private const val BANDWIDTH_MARGIN = 1.25
    private const val EPSILON = 1e-6
}
