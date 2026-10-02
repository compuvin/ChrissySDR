package com.kb1jdx.chrissysdr.dsp

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

class AmDemodulatorTest {
    @Test
    fun recoversOneKilohertzModulation() {
        val demodulator = AmDemodulator(agcEnabled = false)
        var correlationCos = 0.0
        var correlationSin = 0.0
        for (index in 0 until 12_000) {
            val phase = 2.0 * PI * 1_000.0 * index / 48_000.0
            val reference = cos(phase)
            val output = demodulator.process(1.0 + 0.5 * reference, 0.0)
            if (index > 1_000) {
                correlationCos += output * reference
                correlationSin += output * sin(phase)
            }
        }
        assertTrue(hypot(correlationCos, correlationSin) > 1_000.0)
    }

    @Test
    fun suppressesAudioAboveAmPassband() {
        fun response(frequency: Double): Double {
            val demodulator = AmDemodulator(agcEnabled = false)
            var energy = 0.0
            for (index in 0 until 12_000) {
                val modulation = 0.5 * cos(2.0 * PI * frequency * index / 48_000.0)
                val output = demodulator.process(1.0 + modulation, 0.0)
                if (index > 1_000) energy += output * output
            }
            return energy
        }
        assertTrue(response(5_000.0) < response(1_000.0) * 0.01)
    }

    @Test
    fun rejectsCarrierDcAfterSettling() {
        val demodulator = AmDemodulator(cutoffHz = 3_000.0, agcEnabled = false)
        var maximum = 0.0
        for (index in 0 until 48_000) {
            val output = demodulator.process(0.8, 0.0)
            if (index >= 24_000) maximum = maxOf(maximum, kotlin.math.abs(output))
        }
        assertTrue("Residual DC $maximum", maximum < 0.001)
    }

    @Test
    fun envelopeRecoversAudioWithCarrierOffset() {
        val demodulator = AmDemodulator(cutoffHz = 3_000.0, agcEnabled = false)
        var inPhase = 0.0
        var quadrature = 0.0
        for (index in 0 until 24_000) {
            val time = index / 48_000.0
            val envelope = 0.8 * (1.0 + 0.4 * cos(2.0 * PI * 1_000.0 * time))
            val carrierPhase = 2.0 * PI * 500.0 * time
            val output = demodulator.process(envelope * cos(carrierPhase), envelope * sin(carrierPhase))
            if (index >= 2_000) {
                inPhase += output * cos(2.0 * PI * 1_000.0 * time)
                quadrature += output * sin(2.0 * PI * 1_000.0 * time)
            }
        }
        assertTrue(hypot(inPhase, quadrature) > 1_000.0)
    }

    @Test
    fun audioAgcBalancesWeakAndStrongModulationWithoutClipping() {
        fun outputLevel(depth: Double): Double {
            val demodulator = AmDemodulator(cutoffHz = 3_000.0)
            var energy = 0.0
            var peak = 0.0
            for (index in 0 until 96_000) {
                val input = 0.8 * (1.0 + depth * cos(2.0 * PI * 1_000.0 * index / 48_000.0))
                val output = demodulator.process(input, 0.0)
                if (index >= 48_000) {
                    energy += output * output
                    peak = maxOf(peak, kotlin.math.abs(output))
                }
            }
            assertTrue("peak=$peak", peak <= 0.900001)
            return sqrt(energy / 48_000.0)
        }
        val weak = outputLevel(0.1)
        val strong = outputLevel(0.5)
        assertTrue("weak=$weak strong=$strong", weak > 0.15 && strong > 0.15)
        assertTrue("weak=$weak strong=$strong", weak / strong in 0.67..1.5)
    }

    @Test
    fun weakRadioLevelIsAmplifiedToAudibleAudio() {
        val demodulator = AmDemodulator()
        var energy = 0.0
        for (index in 0 until 96_000) {
            val modulation = 1.0 + 0.5 * cos(2.0 * PI * 1_000.0 * index / 48_000.0)
            val output = demodulator.process(0.001 * modulation, 0.0)
            if (index >= 48_000) energy += output * output
        }
        val rms = sqrt(energy / 48_000.0)
        assertTrue("Weak-signal audio RMS=$rms", rms > 0.15)
    }
}
