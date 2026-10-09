package com.kb1jdx.chrissysdr.dsp

import kotlin.math.exp
import kotlin.math.pow

/** Audio gate controlled by channel-filtered IQ power, before demodulator AGC. */
class SignalPowerSquelch(private val audioSampleRate: Int) {
    init { require(audioSampleRate > 0) }

    private var smoothedPower = 0.0
    private var initialized = false
    private var open = false
    private var holdSamples = 0
    private var gain = 0.0

    fun processInPlace(audio: ShortArray, signalPower: Double, thresholdDbfs: Double?) {
        if (thresholdDbfs == null) {
            open = true
            gain = 1.0
            initialized = false
            return
        }
        require(thresholdDbfs.isFinite() && thresholdDbfs in -120.0..0.0)
        val power = signalPower.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val openPower = 10.0.pow(thresholdDbfs / 10.0)
        val closePower = openPower * HYSTERESIS_POWER_RATIO
        if (!initialized) {
            smoothedPower = power
            open = power >= openPower
            gain = if (open) 1.0 else 0.0
            initialized = true
        } else if (audio.isNotEmpty()) {
            val alpha = 1.0 - exp(-audio.size / (audioSampleRate * POWER_TIME_SECONDS))
            smoothedPower += alpha * (power - smoothedPower)
        }
        if (smoothedPower >= openPower) {
            open = true
            holdSamples = (audioSampleRate * HANG_SECONDS).toInt()
        } else if (smoothedPower < closePower) {
            holdSamples = (holdSamples - audio.size).coerceAtLeast(0)
            if (holdSamples == 0) open = false
        }
        val target = if (open) 1.0 else 0.0
        val ramp = 1.0 / (audioSampleRate * if (open) ATTACK_SECONDS else RELEASE_SECONDS)
        for (index in audio.indices) {
            gain = (gain + (target - gain) * ramp).coerceIn(0.0, 1.0)
            audio[index] = (audio[index] * gain).toInt().toShort()
        }
    }

    private companion object {
        const val POWER_TIME_SECONDS = 0.02
        const val HANG_SECONDS = 0.15
        const val ATTACK_SECONDS = 0.008
        const val RELEASE_SECONDS = 0.025
        const val HYSTERESIS_POWER_RATIO = 0.5 // About 3 dB.
    }
}
