package com.kb1jdx.chrissysdr.soapyremote

import com.kb1jdx.chrissysdr.radio.SoapyStreamException
import java.io.DataInputStream

internal object SoapyStreamStatus {
    private const val HEADER_BYTES = 24
    private const val NOT_SUPPORTED = -5

    fun drain(input: DataInputStream, direction: String) {
        while (input.available() >= HEADER_BYTES) {
            val bytes = input.readInt()
            input.readInt() // sequence
            val result = input.readInt()
            input.readInt() // flags
            input.readLong() // timestamp
            if (bytes != HEADER_BYTES) {
                throw SoapyStreamException("Invalid $direction status packet size $bytes")
            }
            if (result < 0 && result != NOT_SUPPORTED) {
                throw SoapyStreamException("SoapyRemote $direction stream status $result")
            }
        }
    }
}
