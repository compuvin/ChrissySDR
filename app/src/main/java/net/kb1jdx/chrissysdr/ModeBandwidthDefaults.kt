package com.kb1jdx.chrissysdr

/** Displayed passband width. SSB is one sideband; AM/NFM are centered RF widths. */
object ModeBandwidthDefaults {
    const val AM_HZ = 6_000
    const val NFM_HZ = 12_500
    const val SSB_HZ = 3_000

    fun forMode(mode: String): Int? = when (mode) {
        "AM" -> AM_HZ
        "NFM" -> NFM_HZ
        "USB", "LSB" -> SSB_HZ
        else -> null
    }

    fun centeredRfWidth(mode: String, passbandHz: Double): Double =
        if (mode == "USB" || mode == "LSB") passbandHz * 2.0 else passbandHz
}
