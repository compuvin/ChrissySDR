package com.kb1jdx.chrissysdr.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

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

    @Test
    fun receivePipelineRecoversAmAtNonIntegerRadioRate() {
        val radioRate = 225_001.0
        val pipeline = AmReceivePipeline(radioRate, 48_000)
        val iq = FloatArray(45_000 * 2)
        for (index in 0 until 45_000) {
            iq[index * 2] = (1.0 + 0.4 * cos(2.0 * PI * 1_000.0 * index / radioRate)).toFloat()
        }
        val audio = pipeline.process(iq)
        assertTrue(audio.size in 9_400..9_600)
        var inPhase = 0.0
        var quadrature = 0.0
        for (index in 1_000 until audio.size) {
            val phase = 2.0 * PI * 1_000.0 * index / 48_000.0
            inPhase += audio[index] * cos(phase)
            quadrature += audio[index] * sin(phase)
        }
        assertTrue(hypot(inPhase, quadrature) / (audio.size - 1_000) > 1_000.0)
    }
}
