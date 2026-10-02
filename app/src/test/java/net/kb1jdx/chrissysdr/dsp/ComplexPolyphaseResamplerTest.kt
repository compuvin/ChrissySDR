package com.kb1jdx.chrissysdr.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

class ComplexPolyphaseResamplerTest {
    @Test fun nativeRatePassesSamplesWithoutChangingTheirCount() {
        val iq = floatArrayOf(1f, 0f, 0.5f, -0.25f, 0f, 0.75f)
        assertArrayEquals(iq, ComplexPolyphaseResampler(48_000.0, 48_000.0, 12_000.0).process(iq), 0f)
    }

    @Test fun integerRateRejectsAnAliasNearTheInputNyquist() {
        val pass = response(96_000.0, 1_000.0)
        val alias = response(96_000.0, 47_000.0)
        assertTrue("pass=$pass", pass > 0.85)
        assertTrue("alias=$alias pass=$pass", alias < pass * 0.01)
    }

    @Test fun nonIntegerRatePreservesToneAndRejectsAnAliasingTone() {
        val pass = response(225_001.0, 1_000.0)
        val alias = response(225_001.0, 50_000.0)
        assertTrue("pass=$pass", pass > 0.8)
        assertTrue("alias=$alias pass=$pass", alias < pass * 0.02)
    }

    @Test fun highRadioRateUsesStagedFilteringWithoutLosingThePassband() {
        val pass = response(3_200_000.0, 2_000.0, seconds = 0.05)
        val alias = response(3_200_000.0, 100_000.0, seconds = 0.05)
        assertTrue("pass=$pass", pass > 0.75)
        assertTrue("alias=$alias pass=$pass", alias < pass * 0.03)
    }

    @Test fun lowRadioRateInterpolatesWithoutAudioRateImages() {
        val input = tone(8_000.0, 1_000.0, 1_600)
        val output = ComplexPolyphaseResampler(8_000.0, 48_000.0, 6_000.0).process(input)
        assertTrue(output.size / 2 in 9_300..9_600)
        val pass = spectralAmplitude(output, 1_000.0)
        val image = maxOf(spectralAmplitude(output, 9_000.0), spectralAmplitude(output, -7_000.0))
        assertTrue("pass=$pass", pass > 0.8)
        assertTrue("image=$image pass=$pass", image < pass * 0.03)
    }

    @Test fun packetBoundariesDoNotResetFilterOrFractionalPhase() {
        val rate = 225_001.0
        val iq = tone(rate, 1_000.0, 25_000)
        val whole = ComplexPolyphaseResampler(rate, 48_000.0, 12_000.0).process(iq)
        val streamed = ComplexPolyphaseResampler(rate, 48_000.0, 12_000.0)
        val chunks = ArrayList<Float>()
        var index = 0
        while (index < iq.size / 2) {
            val count = minOf(1 + index % 191, iq.size / 2 - index)
            streamed.process(iq.copyOfRange(index * 2, (index + count) * 2)).forEach(chunks::add)
            index += count
        }
        assertEquals(whole.size, chunks.size)
        assertArrayEquals(whole, chunks.toFloatArray(), 0f)
    }

    private fun response(rate: Double, frequency: Double, seconds: Double = 0.2): Double {
        val input = tone(rate, frequency, (rate * seconds).toInt())
        val output = ComplexPolyphaseResampler(rate, 48_000.0, 12_000.0).process(input)
        val start = minOf(300, output.size / 4)
        var energy = 0.0
        var count = 0
        for (index in start until output.size / 2) {
            val i = output[index * 2].toDouble()
            val q = output[index * 2 + 1].toDouble()
            energy += i * i + q * q
            count++
        }
        return sqrt(energy / count)
    }

    private fun spectralAmplitude(iq: FloatArray, frequency: Double): Double {
        val start = 500
        var real = 0.0
        var imaginary = 0.0
        for (index in start until iq.size / 2) {
            val phase = 2.0 * PI * frequency * index / 48_000.0
            val c = cos(phase)
            val s = sin(phase)
            real += iq[index * 2] * c + iq[index * 2 + 1] * s
            imaginary += iq[index * 2 + 1] * c - iq[index * 2] * s
        }
        return hypot(real, imaginary) / (iq.size / 2 - start)
    }

    private fun tone(rate: Double, frequency: Double, samples: Int): FloatArray = FloatArray(samples * 2).also {
        for (index in 0 until samples) {
            val phase = 2.0 * PI * frequency * index / rate
            it[index * 2] = cos(phase).toFloat()
            it[index * 2 + 1] = sin(phase).toFloat()
        }
    }
}
