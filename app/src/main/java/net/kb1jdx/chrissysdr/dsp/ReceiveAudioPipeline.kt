package com.kb1jdx.chrissysdr.dsp

/** Demodulated audio plus the channel-filtered IQ power before audio AGC. */
interface ReceiveAudioPipeline {
    val lastSignalPower: Double
    fun process(iq: FloatArray, elements: Int = iq.size / 2): ShortArray
}

internal fun meanComplexPower(iq: FloatArray): Double {
    if (iq.isEmpty()) return 0.0
    var power = 0.0
    for (index in iq.indices step 2) {
        val i = iq[index].toDouble()
        val q = iq[index + 1].toDouble()
        power += i * i + q * q
    }
    return power / (iq.size / 2)
}
