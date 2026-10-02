package com.kb1jdx.chrissysdr

import com.kb1jdx.chrissysdr.radio.RadioRange

internal object FrequencyStepPolicy {
    fun next(currentHz: Double, stepHz: Double, direction: Int, ranges: List<RadioRange>): Double? {
        if (!currentHz.isFinite() || currentHz <= 0.0 || !stepHz.isFinite() || stepHz <= 0.0 ||
            direction != -1 && direction != 1
        ) return null
        val target = currentHz + direction * stepHz
        return target.takeIf {
            it.isFinite() && it > 0.0 && it <= Long.MAX_VALUE.toDouble() &&
                (ranges.isEmpty() || ranges.any { range -> it in range.minimum..range.maximum })
        }
    }
}
