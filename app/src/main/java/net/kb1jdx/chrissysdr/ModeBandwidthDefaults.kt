package com.kb1jdx.chrissysdr

/** Displayed passband width. SSB is one sideband; AM is total centered RF width. */
object ModeBandwidthDefaults {
    const val AM_HZ = 6_000
    const val SSB_HZ = 3_000

    fun forMode(mode: String): Int? = when (mode) {
        "AM" -> AM_HZ
        "USB", "LSB" -> SSB_HZ
        else -> null
    }

    fun centeredRfWidth(mode: String, passbandHz: Double): Double =
        if (mode == "USB" || mode == "LSB") passbandHz * 2.0 else passbandHz
}
