package com.kb1jdx.chrissysdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpectrumTuningTest {
    @Test fun tapMapsVisibleSpectrumAndSnapsToStep() {
        assertEquals(7_200_000.0, SpectrumTuning.tap(7_200_000.0, 24_000.0, 500f, 1000f, 100.0)!!, 0.0)
        assertEquals(7_206_000.0, SpectrumTuning.tap(7_200_000.0, 24_000.0, 750f, 1000f, 100.0)!!, 0.0)
        assertEquals(7_188_000.0, SpectrumTuning.tap(7_200_000.0, 24_000.0, -100f, 1000f, 100.0)!!, 0.0)
    }

    @Test fun dragRightTunesLowerAndDragLeftTunesHigher() {
        assertEquals(7_197_600.0, SpectrumTuning.drag(7_200_000.0, 24_000.0, 100f, 1000f, 100.0)!!, 0.0)
        assertEquals(7_202_400.0, SpectrumTuning.drag(7_200_000.0, 24_000.0, -100f, 1000f, 100.0)!!, 0.0)
    }

    @Test fun invalidGeometryCannotTune() {
        assertNull(SpectrumTuning.tap(7_200_000.0, 24_000.0, 500f, 0f, 100.0))
        assertNull(SpectrumTuning.drag(7_200_000.0, 24_000.0, 100f, 1000f, 0.0))
    }
}
