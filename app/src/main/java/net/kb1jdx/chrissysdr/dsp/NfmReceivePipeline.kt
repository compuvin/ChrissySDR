package com.kb1jdx.chrissysdr.dsp

/** Arbitrary-rate IQ to 48-kHz PCM narrowband FM receive path. */
class NfmReceivePipeline(
    inputSampleRate: Double,
    outputSampleRate: Int,
    rfWidthHz: Double,
    audioCutoffHz: Double,
    deemphasisUs: Int,
) {
    private val resampler = ComplexPolyphaseResampler(
        inputSampleRate, outputSampleRate.toDouble(), rfWidthHz,
    )
    private val detector = NfmDemodulator(
        outputSampleRate.toDouble(), rfWidthHz, audioCutoffHz, deemphasisUs,
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
