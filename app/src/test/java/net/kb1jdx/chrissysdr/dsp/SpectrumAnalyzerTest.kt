package com.kb1jdx.chrissysdr.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class SpectrumAnalyzerTest {
    @Test fun positiveToneAppearsRightOfCenterAtExpectedLevel() {
        val analyzer = SpectrumAnalyzer(1024, 0)
        val iq = tone(1024, 128)
        val spectrum = requireNotNull(analyzer.accept(iq, nowNs = 1))
        val peak = spectrum.indices.maxBy { spectrum[it] }
        assertEquals(512 + 128, peak)
        assertTrue("peak=${spectrum[peak]}", spectrum[peak] in -0.1f..0.1f)
        assertTrue("opposite=${spectrum[512 - 128]}", spectrum[512 - 128] < -55f)
    }

    @Test fun negativeToneAppearsLeftOfCenter() {
        val analyzer = SpectrumAnalyzer(1024, 0)
        val spectrum = requireNotNull(analyzer.accept(tone(1024, -64), nowNs = 1))
        assertEquals(512 - 64, spectrum.indices.maxBy { spectrum[it] })
    }

    @Test fun waitsForCompleteWindowAndLimitsFrameRate() {
        val analyzer = SpectrumAnalyzer(16, 100)
        assertNull(analyzer.accept(tone(8, 1), nowNs = 0))
        assertNotNull(analyzer.accept(tone(8, 1), nowNs = 1))
        assertNull(analyzer.accept(tone(16, 1), nowNs = 50))
        assertNotNull(analyzer.accept(tone(16, 1), nowNs = 101))
    }

    private fun tone(count: Int, bins: Int): FloatArray = FloatArray(count * 2) { index ->
        val phase = 2.0 * PI * bins * (index / 2) / count
        if (index % 2 == 0) cos(phase).toFloat() else sin(phase).toFloat()
    }
}
