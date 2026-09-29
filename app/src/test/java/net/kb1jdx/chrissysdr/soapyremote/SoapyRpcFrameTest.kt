package com.kb1jdx.chrissysdr.soapyremote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SoapyRpcFrameTest {
    @Test
    fun roundTripsPayload() {
        val original = SoapyRpcFrame(payload = byteArrayOf(0, 1, 2, 0x7f))
        val decoded = SoapyRpcFrame.decode(original.encode())

        assertEquals(SoapyRpcFrame.PROTOCOL_VERSION, decoded.version)
        assertArrayEquals(original.payload, decoded.payload)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBadTrailer() {
        val bytes = SoapyRpcFrame(payload = byteArrayOf(1)).encode()
        bytes[bytes.lastIndex] = 0
        SoapyRpcFrame.decode(bytes)
    }
}
