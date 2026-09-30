package com.kb1jdx.chrissysdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamFormatPolicyTest {
    @Test fun prefersEfficientNativeIntegerFormatAndItsFullScale() {
        assertEquals(
            StreamFormatChoice("CS8", 64.0),
            StreamFormatPolicy.choose(listOf("CF32", "CS16", "CS8"), "CS8", 64.0),
        )
        assertEquals(
            StreamFormatChoice("CS16", 2048.0),
            StreamFormatPolicy.choose(listOf("CF32", "CS16"), "CS16", 2048.0),
        )
    }

    @Test fun choosesSmallerWireFormatInsteadOfNativeFloatWhenAvailable() {
        assertEquals(
            StreamFormatChoice("CS16", 32768.0),
            StreamFormatPolicy.choose(listOf("CF32", "CS16"), "CF32", 1.0),
        )
    }

    @Test fun fallsBackInAdvertisedOrderAndUsesNativeFloatScaleWhenNeeded() {
        assertEquals(
            StreamFormatChoice("CF32", 0.5),
            StreamFormatPolicy.choose(listOf("CF32", "CS8"), "CF32", 0.5),
        )
        assertEquals(
            StreamFormatChoice("CS8", 128.0),
            StreamFormatPolicy.choose(listOf("CS8"), null, null),
        )
        assertNull(StreamFormatPolicy.choose(listOf("CU8"), "CU8", 128.0))
    }

    @Test fun invalidNativeScaleFallsBackToNominalConversionScale() {
        assertEquals(
            StreamFormatChoice("CS16", 32768.0),
            StreamFormatPolicy.choose(listOf("CS16"), "CS16", 0.0),
        )
    }
}
