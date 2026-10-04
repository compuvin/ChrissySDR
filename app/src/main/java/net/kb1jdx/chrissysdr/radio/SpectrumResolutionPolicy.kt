package com.kb1jdx.chrissysdr.radio

import kotlin.math.ceil

/** Grow the FFT as the display zooms so a narrow view gains real detail. */
object SpectrumResolutionPolicy {
    private const val MIN_FFT_SIZE = 1024
    private const val MAX_FFT_SIZE = 65536
    private const val MAX_WINDOW_SECONDS = 0.35

    fun fftSize(sampleRateHz: Double, visibleSpanHz: Double?): Int {
        if (!sampleRateHz.isFinite() || sampleRateHz <= 0.0) return MIN_FFT_SIZE
        val span = visibleSpanHz?.takeIf { it.isFinite() && it > 0.0 }
            ?.coerceAtMost(sampleRateHz) ?: sampleRateHz
        val desired = ceil(MIN_FFT_SIZE * sampleRateHz / span)
        var latencyCap = MIN_FFT_SIZE
        while (latencyCap * 2 <= sampleRateHz * MAX_WINDOW_SECONDS &&
            latencyCap < MAX_FFT_SIZE
        ) latencyCap *= 2
        var size = MIN_FFT_SIZE
        while (size < desired && size < latencyCap) size *= 2
        return size
    }
}
