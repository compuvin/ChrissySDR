package com.kb1jdx.chrissysdr.dsp

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.cos

class SpectralNoiseReducerTest {
    @Test fun `streaming output is independent of packet boundaries`() {
        val source = ShortArray(4096) { index ->
            (9000.0 * sin(2.0 * PI * 1000.0 * index / 48000.0) +
                1800.0 * sin(2.0 * PI * 7000.0 * index / 48000.0)).roundToInt().toShort()
        }
        val continuous = source.copyOf()
        SpectralNoiseReducer(1).processInPlace(continuous)
        val chunks = source.toList().chunked(173).map { chunk -> chunk.toShortArray() }
        val reducer = SpectralNoiseReducer(1)
        chunks.forEach(reducer::processInPlace)
        assertTrue(continuous.contentEquals(chunks.flatMap { it.toList() }.toShortArray()))
        assertTrue(continuous.drop(512).any { abs(it.toInt()) > 1000 })
    }

    @Test fun `strong reduces stationary audio more than light`() {
        val source = ShortArray(48000) { index ->
            (5000.0 * sin(2.0 * PI * 6900.0 * index / 48000.0)).roundToInt().toShort()
        }
        val light = source.copyOf()
        val strong = source.copyOf()
        SpectralNoiseReducer(1).processInPlace(light)
        SpectralNoiseReducer(2).processInPlace(strong)
        fun power(samples: ShortArray) = samples.drop(24000).sumOf {
            it.toDouble() * it.toDouble()
        }
        assertTrue(power(strong) < power(light))
    }

    @Test fun `noise reduction improves voice tone over broadband noise`() {
        var seed = 0x12345678
        val source = ShortArray(48000) { index ->
            seed = seed * 1664525 + 1013904223
            val noise = ((seed ushr 16) - 32768) * 0.12
            val tone = 5000.0 * sin(2.0 * PI * 1000.0 * index / 48000.0)
            (tone + noise).roundToInt().toShort()
        }
        val light = source.copyOf()
        val strong = source.copyOf()
        SpectralNoiseReducer(1).processInPlace(light)
        SpectralNoiseReducer(2).processInPlace(strong)
        fun snr(samples: ShortArray): Double {
            val start = 24000
            var sine = 0.0
            var cosine = 0.0
            for (index in start until samples.size) {
                val phase = 2.0 * PI * 1000.0 * index / 48000.0
                sine += samples[index] * sin(phase)
                cosine += samples[index] * cos(phase)
            }
            val amplitude = 2.0 * kotlin.math.hypot(sine, cosine) / (samples.size - start)
            val phase = kotlin.math.atan2(cosine, sine)
            var residual = 0.0
            for (index in start until samples.size) {
                val predicted = amplitude * sin(2.0 * PI * 1000.0 * index / 48000.0 + phase)
                val error = samples[index] - predicted
                residual += error * error
            }
            return amplitude * amplitude / (residual / (samples.size - start))
        }
        assertTrue("Light should improve SNR", snr(light) > snr(source))
        assertTrue("Strong should improve SNR further", snr(strong) > snr(light))
    }
}
