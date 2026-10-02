package com.kb1jdx.chrissysdr

import com.kb1jdx.chrissysdr.radio.RadioRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleRatePolicyTest {
    @Test fun overrideMustContainCurrentPassband() {
        assertTrue(SampleRatePolicy.isUsable(48_000.0, "AM", 6_000.0))
        assertTrue(!SampleRatePolicy.isUsable(8_000.0, "AM", 12_000.0))
        assertTrue(!SampleRatePolicy.isUsable(8_000.0, "USB", 4_000.0))
        assertTrue(SampleRatePolicy.isUsable(48_000.0, "NFM", 12_500.0))
        assertTrue(!SampleRatePolicy.isUsable(96_000.0, "NFM", 50_000.0))
    }
    @Test
    fun rtlSdrRatesSupportTwelveKilohertzPassband() {
        val choice = SampleRatePolicy.choose(
            discreteRates = listOf(250_000.0, 1_024_000.0, 1_536_000.0),
            ranges = listOf(RadioRange(225_001.0, 300_000.0, 0.0)),
            bandwidthHz = 12_000.0,
        )
        assertEquals(250_000.0, choice.automaticRate)
    }

    @Test
    fun fixedFlexRateIsSelectedAutomatically() {
        val choice = SampleRatePolicy.choose(
            discreteRates = listOf(48_000.0),
            ranges = listOf(RadioRange(48_000.0, 48_000.0, 0.0)),
            bandwidthHz = 12_000.0,
        )
        assertEquals(48_000.0, choice.automaticRate)
        assertEquals(listOf(48_000.0), choice.overrideOptions)
    }

    @Test
    fun discreteSelectionUsesSmallestSufficientRate() {
        val choice = SampleRatePolicy.choose(
            discreteRates = listOf(2_000_000.0, 48_000.0, 192_000.0),
            ranges = emptyList(),
            bandwidthHz = 100_000.0,
        )
        assertEquals(192_000.0, choice.automaticRate)
        assertEquals(listOf(48_000.0, 192_000.0, 2_000_000.0), choice.overrideOptions)
    }

    @Test
    fun steppedRangeProducesOnlyAlignedOptions() {
        val choice = SampleRatePolicy.choose(
            discreteRates = emptyList(),
            ranges = listOf(RadioRange(100_000.0, 1_000_000.0, 50_000.0)),
            bandwidthHz = 180_000.0,
        )
        assertEquals(250_000.0, choice.automaticRate)
        assertTrue(choice.overrideOptions.all { (it - 100_000.0) % 50_000.0 == 0.0 })
        assertTrue(100_000.0 in choice.overrideOptions)
        assertTrue(1_000_000.0 in choice.overrideOptions)
    }

    @Test
    fun reportsNoAutomaticRateWhenNoneCanFitFilter() {
        val choice = SampleRatePolicy.choose(
            discreteRates = listOf(48_000.0, 96_000.0),
            ranges = emptyList(),
            bandwidthHz = 100_000.0,
        )
        assertNull(choice.automaticRate)
    }
}
