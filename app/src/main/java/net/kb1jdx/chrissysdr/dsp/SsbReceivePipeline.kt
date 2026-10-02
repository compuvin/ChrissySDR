package com.kb1jdx.chrissysdr.dsp

/** Streaming SSB product detection and rate conversion. */
class SsbReceivePipeline(
    inputSampleRate: Double,
    outputSampleRate: Int,
    passbandHz: Double,
    upperSideband: Boolean,
    bfoOffsetHz: Double = 0.0,
) {
    private val resampler = ComplexPolyphaseResampler(
        inputSampleRate, outputSampleRate.toDouble(), passbandHz * 2.0,
    )
    private val detector = SsbDemodulator(
        outputSampleRate.toDouble(), upperSideband, passbandHz, bfoOffsetHz,
    )

    fun process(iq: FloatArray, elements: Int = iq.size / 2): ShortArray {
        require(elements >= 0 && elements * 2 <= iq.size)
        val baseband = resampler.process(iq, elements)
        return ShortArray(baseband.size / 2) { index ->
            val audio = detector.process(
                baseband[index * 2].toDouble(), baseband[index * 2 + 1].toDouble(),
            )
            (audio.coerceIn(-0.95, 0.95) * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
