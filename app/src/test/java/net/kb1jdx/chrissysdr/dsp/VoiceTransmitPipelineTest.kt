package com.kb1jdx.chrissysdr.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTransmitPipelineTest {
    private fun tone(count: Int = 4_800, frequencyHz: Double = 1_000.0): ShortArray =
        ShortArray(count) { index ->
            (12_000 * sin(2.0 * PI * frequencyHz * index / 48_000.0)).toInt().toShort()
        }

    private fun sidebandMagnitude(iq: FloatArray, frequencyHz: Double): Double {
        var real = 0.0
        var imaginary = 0.0
        for (index in 2_000 until iq.size / 2) {
            val phase = 2.0 * PI * frequencyHz * index / 48_000.0
            val i = iq[2 * index].toDouble()
            val q = iq[2 * index + 1].toDouble()
            real += i * cos(phase) + q * sin(phase)
            imaginary += q * cos(phase) - i * sin(phase)
        }
        return hypot(real, imaginary)
    }

    @Test fun sidebandModesSuppressTheOppositeSideband() {
        for (mode in listOf("USB", "LSB")) {
            val iq = VoiceTransmitPipeline(mode, 48_000, 48_000.0).process(tone())
            val wanted = sidebandMagnitude(iq, if (mode == "USB") 1_000.0 else -1_000.0)
            val unwanted = sidebandMagnitude(iq, if (mode == "USB") -1_000.0 else 1_000.0)
            assertTrue("$mode sideband rejection", wanted > unwanted * 10.0)
        }
    }

    @Test fun nfmHasConstantEnvelopeAndAmHasCarrier() {
        val microphone = tone()
        val fm = VoiceTransmitPipeline("NFM", 48_000, 48_000.0).process(microphone)
        for (index in 2_000 until fm.size / 2) {
            assertTrue(abs(hypot(fm[2 * index].toDouble(), fm[2 * index + 1].toDouble()) - 0.7) < 0.02)
        }
        val am = VoiceTransmitPipeline("AM", 48_000, 48_000.0).process(microphone)
        assertTrue((2_000 until am.size / 2).any { am[2 * it] > 0.55f })
        assertTrue((2_000 until am.size / 2).any { am[2 * it] < 0.45f })
    }

    @Test fun allModesResampleToAdvertisedRadioRate() {
        for (mode in listOf("AM", "NFM", "USB", "LSB")) {
            val iq = VoiceTransmitPipeline(mode, 48_000, 96_000.0).process(tone())
            assertTrue("$mode output count", iq.size in 18_000..20_000)
        }
    }
}
