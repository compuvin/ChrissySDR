package com.kb1jdx.chrissysdr

import com.kb1jdx.chrissysdr.radio.RadioRange
import kotlin.math.ceil

object BandwidthPolicy {
    /** Null means the radio did not advertise hardware bandwidth control. */
    fun choose(discrete: List<Double>, ranges: List<RadioRange>, passbandHz: Double): Double? {
        require(passbandHz.isFinite() && passbandHz > 0.0) { "Passband must be positive" }
        val discreteCandidates = discrete.filter { it.isFinite() && it >= passbandHz }
        val rangeCandidates = ranges.mapNotNull { range ->
            if (!range.minimum.isFinite() || !range.maximum.isFinite() ||
                range.minimum < 0.0 || range.maximum < range.minimum
            ) return@mapNotNull null
            val minimum = maxOf(passbandHz, range.minimum)
            if (minimum > range.maximum) return@mapNotNull null
            if (range.step <= 0.0) return@mapNotNull minimum
            val steps = ceil((minimum - range.minimum) / range.step)
            val candidate = range.minimum + steps * range.step
            candidate.takeIf { it <= range.maximum + 1e-6 }
        }
        return (discreteCandidates + rangeCandidates).minOrNull()
    }

    fun isReported(discrete: List<Double>, ranges: List<RadioRange>): Boolean =
        discrete.isNotEmpty() || ranges.isNotEmpty()
}
