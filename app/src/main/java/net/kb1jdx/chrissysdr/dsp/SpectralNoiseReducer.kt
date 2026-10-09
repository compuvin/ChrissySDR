package com.kb1jdx.chrissysdr.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Conservative, streaming spectral subtraction for demodulated mono audio. */
internal class SpectralNoiseReducer(private val level: Int) : AudioNoiseReducer {
    init { require(level in 1..2) }

    private val size = 512
    private val hop = size / 2
    private val window = DoubleArray(size) { index ->
        sin(PI * (index + 0.5) / size)
    }
    private val input = DoubleArray(size)
    private val real = DoubleArray(size)
    private val imaginary = DoubleArray(size)
    private val overlap = DoubleArray(hop)
    private val ready = DoubleArray(hop)
    private val noise = DoubleArray(hop + 1)
    private val magnitudes = DoubleArray(hop + 1)
    private val smoothedGain = DoubleArray(hop + 1) { 1.0 }
    private var received = 0
    private var queued = 0
    private var read = 0
    private var frames = 0

    override fun processInPlace(audio: ShortArray) {
        for (index in audio.indices) {
            val output = if (read < queued) ready[read++] else 0.0
            input[received++] = audio[index].toDouble()
            if (received == size) processFrame()
            audio[index] = output.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun processFrame() {
        for (index in 0 until size) {
            real[index] = input[index] * window[index]
            imaginary[index] = 0.0
        }
        fft(inverse = false)
        val suppression = if (level == 1) 0.75 else 1.15
        val floor = if (level == 1) 0.50 else 0.22
        for (bin in 0..hop) {
            magnitudes[bin] = hypot(real[bin], imaginary[bin])
        }
        for (bin in 0..hop) {
            val magnitude = magnitudes[bin]
            val from = maxOf(0, bin - 4)
            val to = minOf(hop, bin + 4)
            var neighborhood = 0.0
            for (near in from..to) neighborhood += magnitudes[near]
            neighborhood /= (to - from + 1)
            // A narrow, persistent speech harmonic should not become the noise floor.
            val tonal = magnitude > neighborhood * 2.1
            if (frames == 0) noise[bin] = neighborhood
            else if (!tonal) noise[bin] += (neighborhood - noise[bin]) *
                if (neighborhood < noise[bin]) 0.08 else 0.002
            val ratio = magnitude / (noise[bin] + 1e-9)
            val estimatedNoiseFraction = 1.0 / (ratio * ratio + 1e-9)
            var target = (1.0 - suppression * estimatedNoiseFraction).coerceIn(floor, 1.0)
            if (tonal) target = maxOf(target, if (level == 1) 0.95 else 0.90)
            val gain = smoothedGain[bin] * 0.65 + target * 0.35
            smoothedGain[bin] = gain
            real[bin] *= gain
            imaginary[bin] *= gain
            if (bin in 1 until hop) {
                real[size - bin] *= gain
                imaginary[size - bin] *= gain
            }
        }
        fft(inverse = true)
        for (index in 0 until hop) {
            ready[index] = overlap[index] + real[index] * window[index]
            overlap[index] = real[index + hop] * window[index + hop]
            input[index] = input[index + hop]
        }
        received = hop
        queued = hop
        read = 0
        frames++
    }

    private fun fft(inverse: Boolean) {
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val r = real[i]; real[i] = real[j]; real[j] = r
                val q = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = q
            }
        }
        var length = 2
        while (length <= size) {
            val angle = (if (inverse) 2.0 else -2.0) * PI / length
            val wr = cos(angle)
            val wi = sin(angle)
            for (start in 0 until size step length) {
                var ur = 1.0
                var ui = 0.0
                for (offset in 0 until length / 2) {
                    val a = start + offset
                    val b = a + length / 2
                    val tr = ur * real[b] - ui * imaginary[b]
                    val ti = ur * imaginary[b] + ui * real[b]
                    real[b] = real[a] - tr
                    imaginary[b] = imaginary[a] - ti
                    real[a] += tr
                    imaginary[a] += ti
                    val nextR = ur * wr - ui * wi
                    ui = ur * wi + ui * wr
                    ur = nextR
                }
            }
            length *= 2
        }
        if (inverse) for (i in 0 until size) real[i] /= size
    }
}
