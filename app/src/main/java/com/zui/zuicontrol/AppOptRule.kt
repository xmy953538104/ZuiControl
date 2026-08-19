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

enum class AppOptPreset(
    val cpuSet: String,
    val title: String,
    val canUseAsPackagePreset: Boolean,
) {
    GAME_BACKGROUND("2-6", "游戏通用", true),
    PERFORMANCE_CLUSTER("2-4", "性能核", false),
    PRIME("7", "超大核", false),
    ALL("0-7", "全核心", true),
    EFFICIENCY("0-4", "能效", true),
    PERFORMANCE("5-7", "高性能", true),
    LITTLE("0-1", "小核", true);

    val displayName: String
        get() = "$cpuSet · $title"

    companion object {
        val packagePresets: List<AppOptPreset> = entries.filter(AppOptPreset::canUseAsPackagePreset)

        fun fromCpuSet(value: String): AppOptPreset? =
            entries.firstOrNull { it.cpuSet == value }
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
