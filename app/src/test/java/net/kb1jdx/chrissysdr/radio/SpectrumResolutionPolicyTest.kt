package com.kb1jdx.chrissysdr.radio

import org.junit.Assert.assertEquals
import org.junit.Test

class SpectrumResolutionPolicyTest {
    @Test fun zoomingIncreasesActualFftResolution() {
        assertEquals(1024, SpectrumResolutionPolicy.fftSize(250_000.0, null))
        assertEquals(8192, SpectrumResolutionPolicy.fftSize(250_000.0, 48_000.0))
        assertEquals(16384, SpectrumResolutionPolicy.fftSize(500_000.0, 48_000.0))
        assertEquals(65536, SpectrumResolutionPolicy.fftSize(500_000.0, 1_000.0))
        assertEquals(16384, SpectrumResolutionPolicy.fftSize(48_000.0, 1_000.0))
    }
}
