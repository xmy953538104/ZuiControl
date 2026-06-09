package com.zui.perfctl

data class PerformanceProfile(
    val packageName: String,
    val mode: PerformanceMode,
    val cpuMaxKHz: Int,
    val cpuMinKHz: Int,
    val gpuMaxKHz: Int,
    val gpuMinKHz: Int,
) {
    val key: String
        get() = "$packageName|${mode.id}"

    fun serialize(): String {
        return listOf(
            packageName,
            mode.id,
            cpuMaxKHz,
            cpuMinKHz,
            gpuMaxKHz,
            gpuMinKHz,
        ).joinToString("|")
    }

    companion object {
        fun parse(line: String): PerformanceProfile? {
            val parts = line.trim().split("|")
            if (parts.size != 6 || !PackageNames.isValid(parts[0])) {
                return null
            }
            val mode = PerformanceMode.fromId(parts[1]) ?: return null
            val cpuMax = parts[2].toIntOrNull() ?: return null
            val cpuMin = parts[3].toIntOrNull() ?: return null
            val gpuMax = parts[4].toIntOrNull() ?: return null
            val gpuMin = parts[5].toIntOrNull() ?: return null
            if (cpuMin <= 0 || cpuMax < cpuMin || gpuMin <= 0 || gpuMax < gpuMin) {
                return null
            }
            return PerformanceProfile(parts[0], mode, cpuMax, cpuMin, gpuMax, gpuMin)
        }
    }
}

object PackageNames {
    private val pattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")

    fun isValid(value: String): Boolean = pattern.matches(value)
}

enum class PerformanceMode(val id: String, val title: String) {
    BALANCED("balanced", "均衡"),
    POWER_SAVE("powersave", "省电"),
    SAVAGE("savage", "野兽");

    companion object {
        fun fromId(value: String): PerformanceMode? = entries.firstOrNull { it.id == value }
    }
}
