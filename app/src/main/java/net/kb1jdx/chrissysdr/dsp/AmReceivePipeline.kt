package com.kb1jdx.chrissysdr.dsp

/** Android-independent streaming AM demodulation and rate conversion. */
class AmReceivePipeline(
    private val inputSampleRate: Double,
    private val outputSampleRate: Int,
    private val passbandHz: Double = 12_000.0,
) {
    init { require(passbandHz.isFinite() && passbandHz > 0.0) }
    private val resampler = ComplexPolyphaseResampler(inputSampleRate, outputSampleRate.toDouble(), passbandHz)
    private val demodulator = AmDemodulator(
        sampleRate = outputSampleRate.toDouble(),
        cutoffHz = minOf(passbandHz / 2.0, outputSampleRate * 0.4),
    )

    fun process(iq: FloatArray, elements: Int = iq.size / 2): ShortArray {
        require(elements * 2 <= iq.size)
        val baseband = resampler.process(iq, elements)
        val output = ShortArray(baseband.size / 2)
        repeat(output.size) { index ->
            val audio = demodulator.process(
                baseband[index * 2].toDouble(), baseband[index * 2 + 1].toDouble(),
            )
            output[index] = (audio.coerceIn(-0.95, 0.95) * Short.MAX_VALUE).toInt().toShort()
        }
        return output
    }
}
