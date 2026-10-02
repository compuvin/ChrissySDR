package com.kb1jdx.chrissysdr

import com.kb1jdx.chrissysdr.radio.RadioRange

internal object TxControlPolicy {
    fun unavailableReason(
        mode: String,
        deviceReady: Boolean,
        deviceStatus: String,
        frequency: String,
        ranges: List<RadioRange>,
    ): String? {
        if (mode != "AM") return "TX unavailable: only AM transmit is implemented"
        if (!deviceReady) return deviceStatus
        val frequencyHz = frequency.toDoubleOrNull()
        if (frequencyHz == null || !frequencyHz.isFinite() || frequencyHz <= 0.0 ||
            frequencyHz > Long.MAX_VALUE.toDouble()
        ) return "TX unavailable: enter a valid frequency"
        if (ranges.isNotEmpty() && ranges.none { frequencyHz in it.minimum..it.maximum }) {
            return "TX unavailable: frequency is outside the radio's reported TX ranges"
        }
        return null
    }
}
