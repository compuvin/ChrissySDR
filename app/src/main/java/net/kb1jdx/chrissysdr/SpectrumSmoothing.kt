package com.kb1jdx.chrissysdr

import com.kb1jdx.chrissysdr.radio.SpectrumFrame

/** Apply the same display averaging to fresh RX and reattached RX callbacks. */
internal fun smoothSpectrum(
    previous: SpectrumFrame?,
    incoming: SpectrumFrame,
    weight: Float,
): SpectrumFrame {
    if (previous == null || previous.centerFrequencyHz != incoming.centerFrequencyHz ||
        previous.sampleRateHz != incoming.sampleRateHz ||
        previous.binsDbfs.size != incoming.binsDbfs.size
    ) return incoming
    val bins = FloatArray(incoming.binsDbfs.size) { index ->
        previous.binsDbfs[index] * (1f - weight) + incoming.binsDbfs[index] * weight
    }
    return incoming.copy(binsDbfs = bins)
}
