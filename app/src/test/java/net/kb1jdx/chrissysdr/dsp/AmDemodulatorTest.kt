package com.kb1jdx.chrissysdr.dsp

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

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
        assertTrue(response(10_000.0) < response(1_000.0) * 0.01)
    }
}
