package com.zui.zuicontrol

data class AppOptThreadRule(
    val pattern: String,
    val preset: AppOptPreset,
)

data class AppOptRule(
    val packageName: String,
    val preset: AppOptPreset,
    val threadRules: List<AppOptThreadRule> = emptyList(),
) {
    val totalRules: Int
        get() = 1 + threadRules.size
}

class AppOptPreset private constructor(
    val cpuSet: String,
    val title: String,
    val canUseAsPackagePreset: Boolean,
) {
    val displayName: String
        get() = "$cpuSet · $title"

    override fun equals(other: Any?): Boolean =
        other is AppOptPreset && cpuSet == other.cpuSet

    override fun hashCode(): Int = cpuSet.hashCode()

    companion object {
        val GAME_BACKGROUND = AppOptPreset("2-6", "游戏通用", true)
        val PERFORMANCE_CLUSTER = AppOptPreset("2-4", "性能核", false)
        val PRIME = AppOptPreset("7", "超大核", false)
        val ALL = AppOptPreset("0-7", "全核心", true)
        val EFFICIENCY = AppOptPreset("0-4", "能效", true)
        val PERFORMANCE = AppOptPreset("5-7", "高性能", true)
        val LITTLE = AppOptPreset("0-1", "小核", true)

        private val named = listOf(
            GAME_BACKGROUND,
            PERFORMANCE_CLUSTER,
            PRIME,
            ALL,
            EFFICIENCY,
            PERFORMANCE,
            LITTLE,
        ).associateBy(AppOptPreset::cpuSet)

        val packagePresets: List<AppOptPreset> = named.values.filter(AppOptPreset::canUseAsPackagePreset)

        fun fromCpuSet(value: String): AppOptPreset? {
            val normalized = value.trim()
            if (Regex("^[0-7]$").matches(normalized)) {
                return named[normalized] ?: AppOptPreset(normalized, "自定义", true)
            }
            val match = Regex("^([0-7])-([0-7])$").matchEntire(normalized) ?: return null
            val start = match.groupValues[1].toInt()
            val end = match.groupValues[2].toInt()
            if (start >= end) return null
            return named[normalized] ?: AppOptPreset(normalized, "自定义", true)
        }

        fun fromEndpoints(first: Int, second: Int = first): AppOptPreset? {
            if (first !in 0..7 || second !in 0..7) return null
            val start = minOf(first, second)
            val end = maxOf(first, second)
            return fromCpuSet(if (start == end) "$start" else "$start-$end")
        }
    }
}

object AppOptRules {
    fun parse(value: String): LinkedHashMap<String, AppOptRule> =
        runCatching { AppOptConfig.parse(value) }.getOrElse {
            parseLegacyState(value)
        }

    private fun parseLegacyState(value: String): LinkedHashMap<String, AppOptRule> {
        val rules = linkedMapOf<String, AppOptRule>()
        value.lineSequence().forEach { line ->
            val fields = line.trim().split('|')
            if (fields.size != 2 || !PackageNames.isValid(fields[0])) return@forEach
            val preset = AppOptPreset.fromCpuSet(fields[1]) ?: return@forEach
            rules[fields[0]] = AppOptRule(fields[0], preset)
        }
        return rules
    }
}
