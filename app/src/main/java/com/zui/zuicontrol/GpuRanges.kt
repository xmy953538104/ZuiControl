package com.zui.zuicontrol

import kotlin.math.roundToInt

object GpuRanges {
    val opps = listOf(231, 310, 366, 422, 500, 578, 629, 680, 720, 770, 834, 903)
    data class Range(val min: Int, val max: Int) {
        init { require(min in opps && max in opps && min <= max) }
    }
    fun default(mode: String): Range = when (mode) {
        "powersave" -> Range(231, 422)
        "balance" -> Range(231, 629)
        "performance" -> Range(231, 903)
        "fast" -> Range(629, 903)
        else -> error("Unknown performance mode")
    }
    fun snap(fraction: Float): Int = opps[(fraction.coerceIn(0f, 1f) * opps.lastIndex).roundToInt()]
    fun profile(line: String, user: Int): Pair<String, Range>? = runCatching {
        val p = line.removePrefix("gpuProfile=").split('|')
        if (!line.startsWith("gpuProfile=") || p.size != 4 || p[0].toInt() != user ||
            !PackageNames.isValid(p[1])) return null
        p[1] to Range(p[2].toInt(), p[3].toInt())
    }.getOrNull()
}
