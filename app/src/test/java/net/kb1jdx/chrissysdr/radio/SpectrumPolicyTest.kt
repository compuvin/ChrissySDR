package com.kb1jdx.chrissysdr.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumPolicyTest {
    @Test fun capsWideRadiosAndKeepsChoicesWithinAvailableRate() {
        assertEquals(500_000.0, SpectrumPolicy.displayRate(2_400_000.0), 0.0)
        assertEquals(48_000.0, SpectrumPolicy.displayRate(48_000.0), 0.0)
        assertTrue(SpectrumPolicy.presetSpans(2_400_000.0).contains(500_000.0))
        assertTrue(SpectrumPolicy.presetSpans(250_000.0).contains(250_000.0))
        assertFalse(SpectrumPolicy.presetSpans(250_000.0).contains(500_000.0))
        assertFalse(SpectrumPolicy.presetSpans(48_000.0).contains(96_000.0))
    }
}
