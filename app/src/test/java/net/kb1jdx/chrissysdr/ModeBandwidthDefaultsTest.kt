package com.kb1jdx.chrissysdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModeBandwidthDefaultsTest {
    @Test fun amDefaultsToSixKilohertzOfRfBandwidth() {
        assertEquals(6_000, ModeBandwidthDefaults.forMode("AM"))
        assertEquals("6000", RadioUiState().bandwidth)
    }

    @Test fun ssbModesDefaultToThreeKilohertzSideband() {
        assertEquals(3_000, ModeBandwidthDefaults.forMode("USB"))
        assertEquals(3_000, ModeBandwidthDefaults.forMode("LSB"))
        assertEquals(6_000.0, ModeBandwidthDefaults.centeredRfWidth("USB", 3_000.0), 0.0)
        assertEquals(6_000.0, ModeBandwidthDefaults.centeredRfWidth("LSB", 3_000.0), 0.0)
        assertEquals(6_000.0, ModeBandwidthDefaults.centeredRfWidth("AM", 6_000.0), 0.0)
    }

    @Test fun nfmDefaultsToTwelvePointFiveKilohertzRfWidth() {
        assertEquals(12_500, ModeBandwidthDefaults.forMode("NFM"))
        assertEquals(12_500.0, ModeBandwidthDefaults.centeredRfWidth("NFM", 12_500.0), 0.0)
        assertNull(ModeBandwidthDefaults.forMode("CW"))
    }
}
