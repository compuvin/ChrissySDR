package com.kb1jdx.chrissysdr.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin

/** Bounded-rate, streaming complex FFT. Input and transform buffers are reused. */
class SpectrumAnalyzer(
    val fftSize: Int = 1024,
    private val frameIntervalNs: Long = 100_000_000L,
) {
    init {
        require(fftSize >= 8 && fftSize and (fftSize - 1) == 0)
        require(frameIntervalNs >= 0)
    }

    private val ringI = FloatArray(fftSize)
    private val ringQ = FloatArray(fftSize)
    private val real = DoubleArray(fftSize)
    private val imaginary = DoubleArray(fftSize)
    private val window = DoubleArray(fftSize) { index ->
        0.5 - 0.5 * cos(2.0 * PI * index / (fftSize - 1))
    }
    private val windowSum = window.sum()
    private val twiddleI = DoubleArray(fftSize / 2) { index ->
        cos(-2.0 * PI * index / fftSize)
    }
    private val twiddleQ = DoubleArray(fftSize / 2) { index ->
        sin(-2.0 * PI * index / fftSize)
    }
    private var nextRing = 0
    private var samplesSeen = 0L
    private var lastFrameNs = Long.MIN_VALUE

    /** Returns a new dBFS snapshot, ordered from -Fs/2 to +Fs/2, or null. */
    fun accept(iq: FloatArray, elements: Int = iq.size / 2, nowNs: Long): FloatArray? {
        require(elements >= 0 && elements * 2 <= iq.size)
        for (index in 0 until elements) {
            ringI[nextRing] = iq[index * 2]
            ringQ[nextRing] = iq[index * 2 + 1]
            nextRing = (nextRing + 1) % fftSize
        }
        samplesSeen += elements
        if (samplesSeen < fftSize ||
            (lastFrameNs != Long.MIN_VALUE && nowNs - lastFrameNs < frameIntervalNs)
        ) return null
        lastFrameNs = nowNs

        for (index in 0 until fftSize) {
            val source = (nextRing + index) % fftSize
            real[index] = ringI[source] * window[index]
            imaginary[index] = ringQ[source] * window[index]
        }
        transform()
        return FloatArray(fftSize) { displayedBin ->
            val bin = (displayedBin + fftSize / 2) % fftSize
            val amplitude = hypot(real[bin], imaginary[bin]) / windowSum
            (20.0 * log10(amplitude.coerceAtLeast(1.0e-9))).toFloat()
        }
    }

    private fun transform() {
        var reversed = 0
        for (index in 1 until fftSize) {
            var bit = fftSize shr 1
            while (reversed and bit != 0) {
                reversed = reversed xor bit
                bit = bit shr 1
            }
            reversed = reversed xor bit
            if (index < reversed) {
                val swapReal = real[index]
                val swapImaginary = imaginary[index]
                real[index] = real[reversed]
                imaginary[index] = imaginary[reversed]
                real[reversed] = swapReal
                imaginary[reversed] = swapImaginary
            }
        }
        var width = 2
        while (width <= fftSize) {
            val half = width / 2
            for (start in 0 until fftSize step width) {
                for (offset in 0 until half) {
                    val twiddle = offset * fftSize / width
                    val rotationI = twiddleI[twiddle]
                    val rotationQ = twiddleQ[twiddle]
                    val upper = start + offset
                    val lower = upper + half
                    val productI = real[lower] * rotationI - imaginary[lower] * rotationQ
                    val productQ = real[lower] * rotationQ + imaginary[lower] * rotationI
                    real[lower] = real[upper] - productI
                    imaginary[lower] = imaginary[upper] - productQ
                    real[upper] += productI
                    imaginary[upper] += productQ
                }
            }
            width = width shl 1
        }
    }
}
