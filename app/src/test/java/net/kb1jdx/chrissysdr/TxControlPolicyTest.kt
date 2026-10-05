package com.kb1jdx.chrissysdr

import com.kb1jdx.chrissysdr.radio.RadioRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TxControlPolicyTest {
    private val ranges = listOf(RadioRange(7_000_000.0, 7_300_000.0, 1.0))

    @Test fun reportedTxRangesAreStrict() {
        assertNull(TxControlPolicy.unavailableReason("AM", true, "ready", "7200000", ranges))
        assertEquals(
            "TX unavailable: frequency is outside the radio's reported TX ranges",
            TxControlPolicy.unavailableReason("AM", true, "ready", "10000000", ranges),
        )
    }

    @Test fun unknownRangesNeedSeparateDeviceAuthorization() {
        assertEquals("TX disabled: unknown limits", TxControlPolicy.unavailableReason(
            "AM", false, "TX disabled: unknown limits", "7200000", emptyList()))
        assertNull(TxControlPolicy.unavailableReason("AM", true, "ready", "7200000", emptyList()))
    }

    @Test fun acceptsVoiceModesAndRejectsInvalidFrequency() {
        assertEquals("TX unavailable: enter a valid frequency", TxControlPolicy.unavailableReason(
            "AM", true, "ready", "NaN", ranges))
        listOf("AM", "NFM", "USB", "LSB").forEach { mode ->
            assertNull(TxControlPolicy.unavailableReason(mode, true, "ready", "7200000", ranges))
        }
        assertEquals("TX unavailable: this mode cannot transmit", TxControlPolicy.unavailableReason(
            "CW", true, "ready", "7200000", ranges))
    }
}
