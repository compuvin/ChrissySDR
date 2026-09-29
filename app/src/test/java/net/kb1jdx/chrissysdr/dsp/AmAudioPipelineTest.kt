package com.kb1jdx.chrissysdr.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmAudioPipelineTest {
    @Test
    fun transmitPipelineProducesInterleavedIqAtRequestedRate() {
        val pipeline = AmTransmitPipeline(48_000, 96_000.0)
        val iq = pipeline.process(ShortArray(480))
        assertEquals(1_920, iq.size)
        assertTrue(iq.indices.filter { it % 2 == 1 }.all { iq[it] == 0f })
    }

    @Test
    fun receivePipelineProducesRequestedAudioRate() {
        val pipeline = AmReceivePipeline(96_000.0, 48_000)
        val iq = FloatArray(1_920) { if (it % 2 == 0) 0.5f else 0f }
        assertEquals(480, pipeline.process(iq).size)
    }
}
