package com.kb1jdx.chrissysdr.dsp

/** Produces conventional full-carrier AM baseband with the carrier on I and Q at zero. */
class AmModulator(
    private val carrierAmplitude: Double = 0.5,
    private val modulationDepth: Double = 0.8,
) {
    private var previousInput = 0.0
    private var previousOutput = 0.0

    fun process(audio: Double): Double {
        val bounded = audio.coerceIn(-1.0, 1.0)
        val dcBlocked = bounded - previousInput + DC_BLOCK_ALPHA * previousOutput
        previousInput = bounded
        previousOutput = dcBlocked
        return (carrierAmplitude * (1.0 + modulationDepth * dcBlocked.coerceIn(-1.0, 1.0)))
            .coerceIn(-1.0, 1.0)
    }

    companion object {
        private const val DC_BLOCK_ALPHA = 0.995
    }
}
