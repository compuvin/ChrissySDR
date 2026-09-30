package com.kb1jdx.chrissysdr.soapyremote

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class IqSampleCodecTest {
    @Test fun decodesSignedEightBitSamplesWithReportedFullScale() {
        val output = FloatArray(4)
        IqSampleCodec("CS8", 64.0).decode(byteArrayOf(32, -64, 0, 63), 2, output)
        assertArrayEquals(floatArrayOf(0.5f, -1f, 0f, 63f / 64f), output, 0.00001f)
    }

    @Test fun decodesSignedSixteenBitSamplesWithExplicitByteOrder() {
        val little = byteArrayOf(0x00, 0x04, 0x00, 0xF8.toByte())
        val big = byteArrayOf(0x04, 0x00, 0xF8.toByte(), 0x00)
        val expected = floatArrayOf(0.5f, -1f)
        val littleOutput = FloatArray(2)
        val bigOutput = FloatArray(2)
        IqSampleCodec("CS16", 2048.0).decode(little, 1, littleOutput)
        IqSampleCodec("CS16", 2048.0, ByteOrder.BIG_ENDIAN).decode(big, 1, bigOutput)
        assertArrayEquals(expected, littleOutput, 0.00001f)
        assertArrayEquals(expected, bigOutput, 0.00001f)
    }

    @Test fun decodesFloatSamplesUsingNativeFullScale() {
        val payload = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(0.25f).putFloat(-0.5f).array()
        val output = FloatArray(2)
        IqSampleCodec("CF32", 0.5).decode(payload, 1, output)
        assertArrayEquals(floatArrayOf(0.5f, -1f), output, 0.00001f)
    }

    @Test fun encodesAllThreeFormatsAndClampsIntegerSamples() {
        val iq = floatArrayOf(0.5f, -1f, 2f, -2f)
        val eight = ByteBuffer.allocate(4)
        IqSampleCodec("CS8", 64.0).encode(iq, 0, 2, eight)
        assertArrayEquals(byteArrayOf(32, -64, 127, -128), eight.array())

        val sixteen = ByteBuffer.allocate(8)
        IqSampleCodec("CS16", 2048.0).encode(iq, 0, 2, sixteen)
        assertArrayEquals(
            shortArrayOf(1024, -2048, 4096, -4096),
            ShortArray(4) { ByteBuffer.wrap(sixteen.array()).order(ByteOrder.LITTLE_ENDIAN).getShort(it * 2) },
        )

        val floats = ByteBuffer.allocate(8)
        IqSampleCodec("CF32", 0.5).encode(iq, 0, 1, floats)
        assertEquals(0.25f, ByteBuffer.wrap(floats.array()).order(ByteOrder.LITTLE_ENDIAN).getFloat(0), 0f)
        assertEquals(-0.5f, ByteBuffer.wrap(floats.array()).order(ByteOrder.LITTLE_ENDIAN).getFloat(4), 0f)
    }
}
