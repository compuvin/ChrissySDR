package com.kb1jdx.chrissysdr.dsp

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SsbDemodulatorTest {
    private fun response(usb: Boolean, toneHz: Double, bfoHz: Double = 0.0): Double {
        val detector = SsbDemodulator(48_000.0, usb, 3_000.0, bfoHz, agcEnabled = false)
        var power = 0.0
        for (index in 0 until 24_000) {
            val phase = 2.0 * PI * toneHz * index / 48_000.0
            val output = detector.process(cos(phase), sin(phase))
            if (index > 2_000) power += output * output
        }
        return sqrt(power / 22_000.0)
    }

    @Test fun usbSelectsUpperSidebandAndRejectsLower() {
        val wanted = response(true, 1_000.0)
        val unwanted = response(true, -1_000.0)
        assertTrue("wanted=$wanted unwanted=$unwanted", wanted > 0.4 && unwanted < wanted * 0.03)
    }

    @Test fun lsbSelectsLowerSidebandAndRejectsUpper() {
        val wanted = response(false, -1_000.0)
        val unwanted = response(false, 1_000.0)
        assertTrue("wanted=$wanted unwanted=$unwanted", wanted > 0.4 && unwanted < wanted * 0.03)
    }

    @Test fun bfoOffsetMovesSuppressedCarrierToZero() {
        val wanted = response(true, 1_500.0, 500.0)
        val wrongSide = response(true, -500.0, 500.0)
        assertTrue("wanted=$wanted unwanted=$wrongSide", wanted > 0.4 && wrongSide < wanted * 0.03)
    }

    @Test fun carrierLeakageIsRejected() {
        assertTrue(response(true, 0.0) < response(true, 1_000.0) * 0.03)
    }
}
