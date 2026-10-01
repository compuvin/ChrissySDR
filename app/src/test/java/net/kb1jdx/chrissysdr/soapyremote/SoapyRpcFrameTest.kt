package com.kb1jdx.chrissysdr.soapyremote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    @Test fun rejectsOlderProtocolBeforeReadingThePayload() {
        val bytes = SoapyRpcFrame(payload = byteArrayOf(1)).encode()
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(4, 0x000300)
        assertThrows(IllegalArgumentException::class.java) { SoapyRpcFrame.decode(bytes) }
        assertThrows(IllegalArgumentException::class.java) {
            SoapyRpcFrame.readFrom(ByteArrayInputStream(bytes))
        }
    }

    @Test fun rejectsOversizedFrameBeforeAllocating() {
        val bytes = SoapyRpcFrame(payload = byteArrayOf(1)).encode()
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(8, 9 * 1024 * 1024)
        assertThrows(IllegalArgumentException::class.java) {
            SoapyRpcFrame.readFrom(ByteArrayInputStream(bytes))
        }
    }
}
