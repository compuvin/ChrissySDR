package com.kb1jdx.chrissysdr.dsp

/** Android-independent microphone PCM to full-carrier AM IQ conversion. */
class AmTransmitPipeline(
    private val inputSampleRate: Int,
    private val outputSampleRate: Double,
) {
    private val modulator = AmModulator()
    private var outputPhase = 0.0

    fun process(audio: ShortArray, count: Int = audio.size): FloatArray {
        require(count in 0..audio.size)
        val output = ArrayList<Float>((count * outputSampleRate / inputSampleRate).toInt() * 2 + 2)
        repeat(count) { index ->
            val sample = audio[index].toDouble() / 32768.0
            outputPhase += outputSampleRate
            while (outputPhase >= inputSampleRate) {
                outputPhase -= inputSampleRate
                output += modulator.process(sample).toFloat()
                output += 0f
            }
        }
        return output.toFloatArray()
    }
}
