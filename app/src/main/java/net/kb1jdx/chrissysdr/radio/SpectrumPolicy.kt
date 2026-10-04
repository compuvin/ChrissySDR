package com.kb1jdx.chrissysdr.radio

/** Bound wideband display work independently of the radio and audio rates. */
object SpectrumPolicy {
    const val MAX_DISPLAY_RATE_HZ = 500_000.0

    fun displayRate(radioRateHz: Double): Double = minOf(radioRateHz, MAX_DISPLAY_RATE_HZ)

    fun presetSpans(availableRateHz: Double): List<Double?> =
        listOf(null, 3_000.0, 6_000.0, 12_000.0, 24_000.0, 48_000.0,
            96_000.0, 192_000.0, 250_000.0, MAX_DISPLAY_RATE_HZ)
            .filter { it == null || it <= displayRate(availableRateHz) }
}
