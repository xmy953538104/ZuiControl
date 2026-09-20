package com.zui.zuicontrol

/** Presentation only. The system-server recording keeps its raw qualified sample. */
internal class LivePower {
    private val samples = ArrayDeque<Double>(3)
    fun clear() = samples.clear()
    fun add(watts: Double): Double {
        if (!watts.isFinite() || watts < 0) { clear(); return -1.0 }
        if (samples.size == 3) samples.removeFirst()
        samples.addLast(watts)
        val sorted = samples.sorted()
        return if (sorted.size == 2) (sorted[0] + sorted[1]) / 2 else sorted[sorted.size / 2]
    }
}
