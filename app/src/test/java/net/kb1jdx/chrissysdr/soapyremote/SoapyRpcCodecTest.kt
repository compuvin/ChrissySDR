package net.kb1jdx.chrissysdr.soapyremote

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SoapyRpcCodecTest {
    @Test
    fun encodesCallExactlyLikeUpstreamPacker() {
        val payload = SoapyRpcWriter().call(20).frame().payload
        assertEquals(listOf(15, 2, 0, 0, 0, 20), payload.map { it.toInt() })
    }

    @Test
    fun decodesStringAndKeywordListResponses() {
        val serverIdPayload = SoapyRpcWriter().string("server-123").frame().payload
        assertEquals("server-123", SoapyRpcReader(serverIdPayload).string())

        val payload = byteArrayOf(12, 2, 0, 0, 0, 1) +
            SoapyRpcWriter().kwargs(mapOf("driver" to "mock", "label" to "Test Radio")).frame().payload
        assertEquals(
            listOf(mapOf("driver" to "mock", "label" to "Test Radio")),
            SoapyRpcReader(payload).kwargsList(),
        )
    }

    @Test
    fun decodesUpstreamFloatingPointRepresentation() {
        val payload = ByteBuffer.allocate(1 + 5 + 1 + 5 + 1 + 9)
            .order(ByteOrder.BIG_ENDIAN)
            .put(10.toByte()) // FLOAT64_LIST
            .put(2.toByte()).putInt(1)
            .put(4.toByte()) // FLOAT64
            .put(2.toByte()).putInt(16) // exponent
            .put(3.toByte()).putLong(6_597_069_766_656_000L) // 48,000 * 2^37
            .array()

        assertEquals(listOf(48_000.0), SoapyRpcReader(payload).float64List())
    }

    @Test
    fun encodesFloatingPointLikeUpstreamPacker() {
        val expected = ByteBuffer.allocate(1 + 5 + 9)
            .order(ByteOrder.BIG_ENDIAN)
            .put(4.toByte())
            .put(2.toByte()).putInt(16)
            .put(3.toByte()).putLong(6_597_069_766_656_000L)
            .array()
        assertEquals(expected.toList(), SoapyRpcWriter().float64(48_000.0).frame().payload.toList())
    }
}
