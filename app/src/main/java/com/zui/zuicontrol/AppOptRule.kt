package com.zui.zuicontrol

data class AppOptRule(
    val packageName: String,
    val preset: AppOptPreset,
)

enum class AppOptPreset(val cpuSet: String, val title: String) {
    ALL("0-7", "全核心"),
    EFFICIENCY("0-4", "能效"),
    PERFORMANCE("5-7", "性能"),
    LITTLE("0-1", "小核");

    val displayName: String
        get() = "$cpuSet $title"

    companion object {
        fun fromCpuSet(value: String): AppOptPreset? =
            entries.firstOrNull { it.cpuSet == value }
    }
}

object AppOptRules {
    fun parse(value: String): LinkedHashMap<String, AppOptRule> {
        val rules = linkedMapOf<String, AppOptRule>()
        value.lineSequence().forEach { line ->
            val fields = line.trim().split('|')
            if (fields.size != 2 || !PackageNames.isValid(fields[0])) {
                return@forEach
            }
            val preset = AppOptPreset.fromCpuSet(fields[1]) ?: return@forEach
            rules[fields[0]] = AppOptRule(fields[0], preset)
        }
        return rules
    }
}
