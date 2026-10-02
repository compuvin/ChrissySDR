package com.kb1jdx.chrissysdr

import com.kb1jdx.chrissysdr.radio.RadioRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrequencyStepPolicyTest {
    @Test fun stepsBySelectedAmountInBothDirections() {
        assertEquals(7_200_100.0, FrequencyStepPolicy.next(7_200_000.0, 100.0, 1, emptyList())!!, 0.0)
        assertEquals(7_199_900.0, FrequencyStepPolicy.next(7_200_000.0, 100.0, -1, emptyList())!!, 0.0)
    }

    @Test fun respectsReportedRxRanges() {
        val ranges = listOf(RadioRange(7_100_000.0, 7_200_000.0, 1.0))
        assertNull(FrequencyStepPolicy.next(7_200_000.0, 100.0, 1, ranges))
        assertEquals(7_199_900.0, FrequencyStepPolicy.next(7_200_000.0, 100.0, -1, ranges)!!, 0.0)
    }

    @Test fun rejectsInvalidInput() {
        assertNull(FrequencyStepPolicy.next(Double.NaN, 100.0, 1, emptyList()))
        assertNull(FrequencyStepPolicy.next(50.0, 100.0, -1, emptyList()))
        assertNull(FrequencyStepPolicy.next(100.0, 0.0, 1, emptyList()))
    }
}
