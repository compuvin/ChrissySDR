package com.kb1jdx.chrissysdr.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmModulatorTest {
    @Test
    fun silenceProducesCarrier() {
        val modulator = AmModulator()
        repeat(100) { assertEquals(0.5, modulator.process(0.0), 1e-9) }
    }

    @Test
    fun audioVariesCarrierWithoutClipping() {
        val modulator = AmModulator()
        val values = buildList {
            repeat(1_000) { index ->
                add(modulator.process(kotlin.math.sin(index * 2.0 * Math.PI / 48.0)))
            }
        }
        assertTrue(values.min() >= 0.09)
        assertTrue(values.max() <= 0.91)
        assertTrue(values.max() - values.min() > 0.7)
    }
}
