package com.zui.zuicontrol

/** Local display state only. The captured value is confirmed only by the owner ACK. */
class OptimisticControl<T>(confirmed: T) {
    var confirmed = confirmed; private set
    var displayed = confirmed; private set
    var pending = false; private set
    fun begin(value: T): Boolean {
        if (pending) return false
        displayed = value; pending = true; return true
    }
    fun finish(succeeded: Boolean) {
        check(pending)
        if (succeeded) confirmed = displayed else displayed = confirmed
        pending = false
    }
    fun observe(value: T) { if (!pending) { confirmed = value; displayed = value } }
}

class GpuDefaultsDraft(val expectedGeneration: Long, saved: Map<String, GpuRanges.Range>) {
    val original = saved.toMap()
    val ranges = saved.toMutableMap()
    val dirty get() = ranges != original
    init { require(expectedGeneration > 0 && ranges.keys == modes.toSet()) }
    fun set(mode: String, range: GpuRanges.Range) { require(mode in modes); ranges[mode] = range }
    fun restoreDefaults() { modes.forEach { ranges[it] = GpuRanges.default(it) } }
    companion object { val modes = listOf("powersave", "balance", "performance", "fast") }
}

object FrontendTheme {
    fun dark(preference: String, systemDark: Boolean) = when (preference) {
        "dark" -> true; "light" -> false; else -> systemDark
    }
}
