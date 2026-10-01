package com.kb1jdx.chrissysdr

import com.kb1jdx.chrissysdr.radio.RadioRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BandwidthPolicyTest {
    @Test fun choosesSmallestDiscreteBandwidthContainingPassband() {
        assertEquals(
            15_000.0,
            BandwidthPolicy.choose(listOf(48_000.0, 6_000.0, 15_000.0), emptyList(), 12_000.0),
        )
    }

    @Test fun alignsToRangeStepAndComparesWithDiscreteValues() {
        assertEquals(
            13_000.0,
            BandwidthPolicy.choose(
                listOf(16_000.0),
                listOf(RadioRange(1_000.0, 20_000.0, 3_000.0)),
                12_000.0,
            ),
        )
    }

    @Test fun reportsWhenNoSupportedBandwidthContainsPassband() {
        assertNull(BandwidthPolicy.choose(listOf(6_000.0), listOf(RadioRange(1_000.0, 10_000.0, 1.0)), 12_000.0))
    }

    @Test fun noAdvertisedBandwidthLeavesHardwareFilterUnset() {
        assertNull(BandwidthPolicy.choose(emptyList(), emptyList(), 12_000.0))
    }

    @Test fun zeroMinimumRangeStillContainsPositivePassband() {
        assertEquals(
            12_000.0,
            BandwidthPolicy.choose(emptyList(), listOf(RadioRange(0.0, 8_000_000.0, 0.0)), 12_000.0),
        )
    }
}
