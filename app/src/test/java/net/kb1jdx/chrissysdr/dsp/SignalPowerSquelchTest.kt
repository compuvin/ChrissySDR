package com.kb1jdx.chrissysdr.dsp

import org.junit.Assert.assertTrue
import org.junit.Test

class SignalPowerSquelchTest {
    @Test fun offLeavesAudioUntouched() {
        val audio = shortArrayOf(1_000, -2_000, 3_000)
        SignalPowerSquelch(48_000).processInPlace(audio, 0.0, null)
        assertTrue(audio.contentEquals(shortArrayOf(1_000, -2_000, 3_000)))
    }

    @Test fun weakSignalIsMutedAndStrongSignalOpens() {
        val squelch = SignalPowerSquelch(48_000)
        val weak = ShortArray(4_800) { 10_000 }
        squelch.processInPlace(weak, 1e-6, -40.0)
        assertTrue(weak.all { it == 0.toShort() })

        val strong = ShortArray(4_800) { 10_000 }
        squelch.processInPlace(strong, 1e-2, -40.0)
        assertTrue(strong.last().toInt() > 9_000)
    }

    @Test fun hangAndFadeAvoidAbruptClosure() {
        val squelch = SignalPowerSquelch(48_000)
        squelch.processInPlace(ShortArray(4_800) { 10_000 }, 1e-2, -40.0)
        val firstWeak = ShortArray(960) { 10_000 }
        squelch.processInPlace(firstWeak, 1e-8, -40.0)
        assertTrue(firstWeak.last().toInt() > 9_000)

        var last = 0.toShort()
        repeat(30) {
            val weak = ShortArray(960) { 10_000 }
            squelch.processInPlace(weak, 1e-8, -40.0)
            last = weak.last()
        }
        assertTrue(kotlin.math.abs(last.toInt()) < 50)
    }

    @Test fun eachModePipelineReportsFilteredSignalPower() {
        val iq = FloatArray(4_800 * 2) { index -> if (index % 2 == 0) 0.5f else 0f }
        val pipelines: List<ReceiveAudioPipeline> = listOf(
            AmReceivePipeline(48_000.0, 48_000),
            SsbReceivePipeline(48_000.0, 48_000, 3_000.0, true),
            SsbReceivePipeline(48_000.0, 48_000, 3_000.0, false),
            NfmReceivePipeline(48_000.0, 48_000, 12_500.0, 3_000.0, 75),
        )
        pipelines.forEach { pipeline ->
            pipeline.process(iq)
            assertTrue(pipeline.lastSignalPower > 0.0)
        }
    }
}
