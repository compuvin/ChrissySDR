package com.kb1jdx.chrissysdr

import kotlin.math.round

/** Screen x grows to the right; positive IQ frequencies are right of center. */
internal object SpectrumTuning {
    fun tap(centerHz: Double, spanHz: Double, x: Float, width: Float, stepHz: Double): Double? =
        target(centerHz, spanHz, x.toDouble() / width - 0.5, stepHz, width, 0.5)

    /** Dragging the spectrum right pans toward lower center frequencies. */
    fun drag(centerHz: Double, spanHz: Double, deltaX: Float, width: Float, stepHz: Double): Double? =
        target(centerHz, spanHz, -deltaX.toDouble() / width, stepHz, width, 1.0)

    private fun target(
        centerHz: Double,
        spanHz: Double,
        fraction: Double,
        stepHz: Double,
        width: Float,
        limit: Double,
    ): Double? {
        if (!centerHz.isFinite() || centerHz <= 0.0 || !spanHz.isFinite() || spanHz <= 0.0 ||
            !width.isFinite() || width <= 0f || !fraction.isFinite() ||
            !stepHz.isFinite() || stepHz <= 0.0
        ) return null
        val candidate = centerHz + fraction.coerceIn(-limit, limit) * spanHz
        val snapped = round(candidate / stepHz) * stepHz
        return snapped.takeIf { it.isFinite() && it > 0.0 }
    }
}
