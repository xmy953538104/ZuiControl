package com.zui.zuicontrol

import java.util.Locale
import kotlin.math.*

/** Small native nice-axis calculation shared by scalar and single-thread charts. */
internal data class RecordAxis(val low: Double, val high: Double, val step: Double) {
    val ticks: List<Double> get() = (0..((high - low) / step).roundToInt()).map { low + it * step }
    fun label(value: Double): String {
        val decimals = String.format(Locale.ROOT, "%.6f", step).trimEnd('0').substringAfter('.', "").length
        return String.format(Locale.ROOT, "%.${decimals}f", if (abs(value) < step / 100) 0.0 else value)
    }
    companion object {
        fun of(values: List<Double>): RecordAxis {
            val valid = values.filter { it.isFinite() && it >= 0 }
            if (valid.isEmpty()) return RecordAxis(0.0, 1.0, 0.2)
            val min = valid.min(); val max = valid.max()
            val padding = maxOf((max - min) * 0.08, if (max == min) maxOf(0.1, max * 0.03) else 0.0001)
            val low = (min - padding).coerceAtLeast(0.0); val high = max + padding
            val exponent = floor(log10((high - low) / 4)).toInt()
            for (power in exponent - 1..exponent + 2) for (factor in listOf(1.0, 2.0, 2.5, 5.0)) {
                val step = factor * 10.0.pow(power)
                val start = floor(low / step) * step; val end = ceil(high / step) * step
                if (((end - start) / step).roundToInt() in 3..5) return RecordAxis(start, end, step)
            }
            return RecordAxis(low, high, (high - low) / 4)
        }
    }
}
