package com.kb1jdx.chrissysdr

import com.kb1jdx.chrissysdr.radio.SpectrumFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SpectrumSmoothingTest {
    @Test fun reattachedSpectrumUsesTheSameAveragingAsLiveRx() {
        val previous = SpectrumFrame(floatArrayOf(-80f, -40f), 48_000.0, 7_100_000.0)
        val incoming = SpectrumFrame(floatArrayOf(-40f, -80f), 48_000.0, 7_100_000.0)

        assertArrayEquals(floatArrayOf(-70f, -50f),
            smoothSpectrum(previous, incoming, 0.25f).binsDbfs, 0f)
        assertArrayEquals(incoming.binsDbfs,
            smoothSpectrum(previous, incoming, 1f).binsDbfs, 0f)
    }

    @Test fun frequencyOrRateChangeStartsAFreshTrace() {
        val previous = SpectrumFrame(floatArrayOf(-80f), 48_000.0, 7_100_000.0)
        val retuned = SpectrumFrame(floatArrayOf(-40f), 48_000.0, 7_101_000.0)
        val newRate = SpectrumFrame(floatArrayOf(-40f), 96_000.0, 7_100_000.0)

        assertSame(retuned, smoothSpectrum(previous, retuned, 0.25f))
        assertSame(newRate, smoothSpectrum(previous, newRate, 0.25f))
    }
}
