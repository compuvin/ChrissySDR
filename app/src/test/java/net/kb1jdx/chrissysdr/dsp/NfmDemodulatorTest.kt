package com.kb1jdx.chrissysdr.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class NfmDemodulatorTest {
    private fun toneRms(audioHz: Double, deemphasisUs: Int, cutoffHz: Double = 4_000.0): Double {
        val sampleRate = 48_000.0
        val detector = NfmDemodulator(sampleRate, 12_500.0, cutoffHz, deemphasisUs)
        var phase = 0.0
        var power = 0.0
        var count = 0
        repeat(24_000) { index ->
            val frequency = 800.0 * sin(2.0 * PI * audioHz * index / sampleRate)
            phase += 2.0 * PI * frequency / sampleRate
            val audio = detector.process(cos(phase), sin(phase))
            if (index >= 6_000) { power += audio * audio; count++ }
        }
        return sqrt(power / count)
    }

    @Test fun demodulatesNarrowFmVoiceTone() {
        assertTrue(toneRms(1_000.0, 0) > 0.15)
    }

    @Test fun deemphasisAttenuatesHigherAudioFrequencies() {
        val lowOff = toneRms(500.0, 0)
        val highOff = toneRms(3_000.0, 0)
        val low75 = toneRms(500.0, 75)
        val high75 = toneRms(3_000.0, 75)
        assertTrue("off low=$lowOff high=$highOff", highOff > lowOff * 0.5)
        assertTrue("75us low=$low75 high=$high75", high75 / low75 < highOff / lowOff * 0.65)
    }

    @Test fun lowerAudioCutoffRejectsHigherVoiceFrequencies() {
        val narrow = toneRms(3_500.0, 0, 2_500.0)
        val wide = toneRms(3_500.0, 0, 4_000.0)
        assertTrue("narrow=$narrow wide=$wide", narrow < wide * 0.4)
    }

    @Test fun removesSteadyFrequencyOffset() {
        val detector = NfmDemodulator(48_000.0, 12_500.0, 3_000.0, 0)
        var power = 0.0
        repeat(24_000) { index ->
            val phase = 2.0 * PI * 200.0 * index / 48_000.0
            val audio = detector.process(cos(phase), sin(phase))
            if (index >= 18_000) power += audio * audio
        }
        assertTrue(sqrt(power / 6_000.0) < 0.01)
    }

    @Test fun pipelinePreservesStateAcrossIqPackets() {
        val iq = FloatArray(9_600 * 2)
        var phase = 0.0
        repeat(9_600) { index ->
            phase += 2.0 * PI * (800.0 * sin(2.0 * PI * 1_000.0 * index / 96_000.0)) / 96_000.0
            iq[index * 2] = cos(phase).toFloat()
            iq[index * 2 + 1] = sin(phase).toFloat()
        }
        val whole = NfmReceivePipeline(96_000.0, 48_000, 12_500.0, 3_000.0, 75).process(iq)
        val chunks = NfmReceivePipeline(96_000.0, 48_000, 12_500.0, 3_000.0, 75)
        val split = ArrayList<Short>()
        for (offset in iq.indices step 384) {
            val end = minOf(offset + 384, iq.size)
            split.addAll(chunks.process(iq.copyOfRange(offset, end)).toList())
        }
        assertArrayEquals(whole, split.toShortArray())
        assertTrue(whole.any { it != 0.toShort() })
    }

    @Test fun rtlSdrLikeRateConvertsToAndroidAudioRate() {
        val sampleRate = 250_000.0
        val iq = FloatArray(25_000 * 2)
        var phase = 0.0
        repeat(25_000) { index ->
            phase += 2.0 * PI * (800.0 * sin(2.0 * PI * 1_000.0 * index / sampleRate)) / sampleRate
            iq[index * 2] = cos(phase).toFloat()
            iq[index * 2 + 1] = sin(phase).toFloat()
        }
        val audio = NfmReceivePipeline(sampleRate, 48_000, 12_500.0, 3_000.0, 75).process(iq)
        assertTrue("samples=${audio.size}", audio.size in 4_700..4_800)
        assertTrue(audio.any { it != 0.toShort() })
    }
}
