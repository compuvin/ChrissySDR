package com.kb1jdx.chrissysdr.dsp

import kotlin.math.ceil

/** Android-independent streaming AM demodulation and rate conversion. */
class AmReceivePipeline(
    private val inputSampleRate: Double,
    private val outputSampleRate: Int,
    private val passbandHz: Double = 12_000.0,
) {
    init { require(passbandHz.isFinite() && passbandHz > 0.0) }
    private val demodulator = AmDemodulator(
        sampleRate = inputSampleRate,
        cutoffHz = minOf(passbandHz / 2.0, outputSampleRate * 0.4, inputSampleRate * 0.4),
    )
    private var outputPhase = 0.0

    fun process(iq: FloatArray, elements: Int = iq.size / 2): ShortArray {
        require(elements * 2 <= iq.size)
        val output = ShortArray(ceil(elements * outputSampleRate / inputSampleRate).toInt() + 1)
        var count = 0
        repeat(elements) { index ->
            val audio = demodulator.process(iq[index * 2].toDouble(), iq[index * 2 + 1].toDouble())
            outputPhase += outputSampleRate
            if (outputPhase >= inputSampleRate) {
                outputPhase -= inputSampleRate
                output[count++] = (audio.coerceIn(-0.95, 0.95) * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return output.copyOf(count)
    }
}
