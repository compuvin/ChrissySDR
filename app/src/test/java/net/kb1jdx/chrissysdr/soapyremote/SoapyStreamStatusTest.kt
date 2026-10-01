package com.kb1jdx.chrissysdr.soapyremote

import com.kb1jdx.chrissysdr.radio.SoapyStreamException
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertThrows
import org.junit.Test

class SoapyStreamStatusTest {
    @Test fun acceptsSuccessfulAndUnsupportedStatus() {
        SoapyStreamStatus.drain(input(0, -5), "RX")
    }

    @Test fun reportsDriverStreamFailure() {
        assertThrows(SoapyStreamException::class.java) {
            SoapyStreamStatus.drain(input(-1), "TX")
        }
    }

    @Test fun rejectsMalformedStatusPacket() {
        val packet = ByteBuffer.wrap(packet(0)).order(ByteOrder.BIG_ENDIAN)
        packet.putInt(0, 99)
        assertThrows(SoapyStreamException::class.java) {
            SoapyStreamStatus.drain(DataInputStream(ByteArrayInputStream(packet.array())), "RX")
        }
    }

    private fun input(vararg results: Int) = DataInputStream(
        ByteArrayInputStream(results.flatMap { packet(it).asIterable() }.toByteArray()),
    )

    private fun packet(result: Int): ByteArray = ByteBuffer.allocate(24)
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(24)
        .putInt(0)
        .putInt(result)
        .putInt(0)
        .putLong(0)
        .array()
}
