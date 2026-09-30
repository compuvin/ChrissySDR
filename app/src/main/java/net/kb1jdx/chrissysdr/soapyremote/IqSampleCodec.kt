package com.kb1jdx.chrissysdr.soapyremote

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Converts interleaved complex I/Q samples between Soapy stream bytes and normalized floats. */
internal class IqSampleCodec(
    val format: String,
    private val fullScale: Double,
    private val byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
) {
    val bytesPerElement: Int = when (format) {
        "CS8" -> 2
        "CS16" -> 4
        "CF32" -> 8
        else -> throw IllegalArgumentException("Unsupported stream format $format")
    }

    init { require(fullScale.isFinite() && fullScale > 0.0) { "Invalid $format full scale" } }

    fun decode(payload: ByteArray, elements: Int, target: FloatArray) {
        require(elements >= 0 && payload.size == elements * bytesPerElement)
        require(target.size >= elements * 2)
        val input = ByteBuffer.wrap(payload).order(byteOrder)
        repeat(elements * 2) { index ->
            val raw = when (format) {
                "CS8" -> input.get().toDouble()
                "CS16" -> input.short.toDouble()
                else -> input.float.toDouble()
            }
            target[index] = (raw / fullScale).toFloat()
        }
    }

    fun encode(samples: FloatArray, sampleOffset: Int, elements: Int, output: ByteBuffer) {
        require(elements >= 0 && sampleOffset >= 0 && sampleOffset + elements * 2 <= samples.size)
        require(output.remaining() >= elements * bytesPerElement)
        output.order(byteOrder)
        repeat(elements * 2) { index ->
            val normalized = samples[sampleOffset + index].toDouble()
            require(normalized.isFinite()) { "Non-finite I/Q sample" }
            val scaled = normalized * fullScale
            when (format) {
                "CS8" -> output.put(scaled.coerceIn(-128.0, 127.0).toInt().toByte())
                "CS16" -> output.putShort(scaled.coerceIn(-32768.0, 32767.0).toInt().toShort())
                else -> output.putFloat(scaled.toFloat())
            }
        }
    }
}
