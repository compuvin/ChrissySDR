package com.kb1jdx.chrissysdr.soapyremote

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.InputStream

/** The fixed envelope used by SoapyRemote RPC messages. */
data class SoapyRpcFrame(
    val version: Int = PROTOCOL_VERSION,
    val payload: ByteArray,
) {
    fun encode(): ByteArray = ByteBuffer.allocate(HEADER_SIZE + payload.size + TRAILER_SIZE)
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(HEADER_MAGIC)
        .putInt(version)
        .putInt(HEADER_SIZE + payload.size + TRAILER_SIZE)
        .put(payload)
        .putInt(TRAILER_MAGIC)
        .array()

    companion object {
        const val PROTOCOL_VERSION = 0x000400
        const val HEADER_SIZE = 12
        const val TRAILER_SIZE = 4
        private const val HEADER_MAGIC = 0x53525043 // SRPC
        private const val TRAILER_MAGIC = 0x43505253 // CPRS
        private const val MAX_FRAME_BYTES = 8 * 1024 * 1024

        private fun requireSupportedVersion(version: Int) {
            // Range replies have a step field starting at upstream RPC 0x400.
            require(version in PROTOCOL_VERSION..0x0004ff) {
                "Unsupported SoapyRemote RPC version 0x${version.toString(16)}"
            }
        }

        fun decode(bytes: ByteArray): SoapyRpcFrame {
            require(bytes.size >= HEADER_SIZE + TRAILER_SIZE) { "Truncated SoapyRemote RPC frame" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            require(buffer.int == HEADER_MAGIC) { "Invalid SoapyRemote RPC header" }
            val version = buffer.int
            requireSupportedVersion(version)
            val length = buffer.int
            require(length <= MAX_FRAME_BYTES) { "SoapyRemote RPC frame is too large: $length bytes" }
            require(length == bytes.size) { "RPC frame length $length does not match ${bytes.size}" }
            val payload = ByteArray(length - HEADER_SIZE - TRAILER_SIZE)
            buffer.get(payload)
            require(buffer.int == TRAILER_MAGIC) { "Invalid SoapyRemote RPC trailer" }
            return SoapyRpcFrame(version, payload)
        }

        fun readFrom(input: InputStream): SoapyRpcFrame {
            val header = input.readExactly(HEADER_SIZE)
            val view = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            require(view.int == HEADER_MAGIC) { "Invalid SoapyRemote RPC header" }
            val version = view.int
            requireSupportedVersion(version)
            val length = view.int
            require(length > HEADER_SIZE + TRAILER_SIZE && length <= MAX_FRAME_BYTES) {
                "Invalid RPC frame length $length"
            }
            return decode(header + input.readExactly(length - HEADER_SIZE))
        }

        private fun InputStream.readExactly(size: Int): ByteArray {
            val result = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = read(result, offset, size - offset)
                if (count < 0) throw java.io.EOFException("SoapyRemote closed the connection")
                offset += count
            }
            return result
        }
    }
}
