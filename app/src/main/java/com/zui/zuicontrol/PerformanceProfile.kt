package com.zui.zuicontrol

data class PerformanceProfile(
    val packageName: String,
    val mode: PerformanceMode,
    val littleMaxKHz: Int,
    val littleMinKHz: Int,
    val bigMaxKHz: Int,
    val bigMinKHz: Int,
    val titanMaxKHz: Int,
    val titanMinKHz: Int,
    val megaMaxKHz: Int,
    val megaMinKHz: Int,
    val gpuMaxKHz: Int,
    val gpuMinKHz: Int,
    val stages: List<PerformanceStage> = listOf(
        PerformanceStage(
            TEMP_DEFAULT_LEVEL,
            littleMaxKHz,
            littleMinKHz,
            bigMaxKHz,
            bigMinKHz,
            titanMaxKHz,
            titanMinKHz,
            megaMaxKHz,
            megaMinKHz,
            gpuMaxKHz,
            gpuMinKHz,
        ),
    ),
) {
    val key: String
        get() = "$packageName|${mode.id}"

    fun serialize(): String {
        if (stages.size > 1 || stages.firstOrNull()?.thresholdLevel != TEMP_DEFAULT_LEVEL) {
            return listOf(packageName, mode.id, "v2", stagePayload()).joinToString("|")
        }
        return listOf(
            packageName,
            mode.id,
            littleMaxKHz,
            littleMinKHz,
            bigMaxKHz,
            bigMinKHz,
            titanMaxKHz,
            titanMinKHz,
            megaMaxKHz,
            megaMinKHz,
            gpuMaxKHz,
            gpuMinKHz,
        ).joinToString("|")
    }

    fun stagePayload(): String = stages.joinToString(";") { it.serialize() }

    fun thermalSummary(): String {
        if (stages.size <= 1) {
            return "默认温区"
        }
        return stages.joinToString(" / ") { it.title() }
    }

    companion object {
        fun parse(line: String): PerformanceProfile? {
            val parts = line.trim().split("|")
            if (parts.size !in setOf(4, 6, 12) || !PackageNames.isValid(parts[0])) {
                return null
            }
            val mode = PerformanceMode.fromId(parts[1]) ?: return null
            if (parts.size == 4 && parts[2] == "v2") {
                val stages = parseStages(parts[3]).takeIf { it.isNotEmpty() } ?: return null
                val primary = stages.first()
                return PerformanceProfile(
                    parts[0],
                    mode,
                    primary.littleMaxKHz,
                    primary.littleMinKHz,
                    primary.bigMaxKHz,
                    primary.bigMinKHz,
                    primary.titanMaxKHz,
                    primary.titanMinKHz,
                    primary.megaMaxKHz,
                    primary.megaMinKHz,
                    primary.gpuMaxKHz,
                    primary.gpuMinKHz,
                    stages,
                ).takeIf { it.isValid() }
            }
            val profile = if (parts.size == 6) {
                val cpuMax = parts[2].toIntOrNull() ?: return null
                val cpuMin = parts[3].toIntOrNull() ?: return null
                val gpuMax = parts[4].toIntOrNull() ?: return null
                val gpuMin = parts[5].toIntOrNull() ?: return null
                PerformanceProfile(
                    parts[0],
                    mode,
                    cpuMax,
                    cpuMin,
                    cpuMax,
                    cpuMin,
                    cpuMax,
                    cpuMin,
                    cpuMax,
                    cpuMin,
                    gpuMax,
                    gpuMin,
                )
            } else {
                PerformanceProfile(
                    parts[0],
                    mode,
                    parts[2].toIntOrNull() ?: return null,
                    parts[3].toIntOrNull() ?: return null,
                    parts[4].toIntOrNull() ?: return null,
                    parts[5].toIntOrNull() ?: return null,
                    parts[6].toIntOrNull() ?: return null,
                    parts[7].toIntOrNull() ?: return null,
                    parts[8].toIntOrNull() ?: return null,
                    parts[9].toIntOrNull() ?: return null,
                    parts[10].toIntOrNull() ?: return null,
                    parts[11].toIntOrNull() ?: return null,
                )
            }
            return profile.takeIf { it.isValid() }
        }

        private fun parseStages(value: String): List<PerformanceStage> {
            val parts = value.split(";").filter { it.isNotBlank() }
            val stages = parts.map { PerformanceStage.parse(it) ?: return emptyList() }
            return if (stages.size == parts.size) stages else emptyList()
        }
    }

    private fun isValid(): Boolean =
        littleMinKHz > 0 && littleMaxKHz >= littleMinKHz &&
            bigMinKHz > 0 && bigMaxKHz >= bigMinKHz &&
            titanMinKHz > 0 && titanMaxKHz >= titanMinKHz &&
            megaMinKHz > 0 && megaMaxKHz >= megaMinKHz &&
            gpuMinKHz > 0 && gpuMaxKHz >= gpuMinKHz &&
            stages.isNotEmpty() &&
            stages.first().thresholdLevel == TEMP_DEFAULT_LEVEL &&
            stages.all { it.isValid() } &&
            stages.drop(1).map { it.thresholdLevel }.zipWithNext().all { (left, right) -> left < right } &&
            stages.drop(1).all { it.thresholdLevel in 1..16 }
}

data class PerformanceStage(
    val thresholdLevel: Int,
    val littleMaxKHz: Int,
    val littleMinKHz: Int,
    val bigMaxKHz: Int,
    val bigMinKHz: Int,
    val titanMaxKHz: Int,
    val titanMinKHz: Int,
    val megaMaxKHz: Int,
    val megaMinKHz: Int,
    val gpuMaxKHz: Int,
    val gpuMinKHz: Int,
) {
    fun serialize(): String = listOf(
        thresholdLevel,
        littleMaxKHz,
        littleMinKHz,
        bigMaxKHz,
        bigMinKHz,
        titanMaxKHz,
        titanMinKHz,
        megaMaxKHz,
        megaMinKHz,
        gpuMaxKHz,
        gpuMinKHz,
    ).joinToString(",")

    fun title(): String = if (thresholdLevel == TEMP_DEFAULT_LEVEL) {
        "默认"
    } else {
        "${temperatureCelsius()}℃"
    }

    fun temperatureCelsius(): Int = thresholdLevel + TEMP_LEVEL_OFFSET

    fun isValid(): Boolean =
        (thresholdLevel == TEMP_DEFAULT_LEVEL || thresholdLevel in 1..16) &&
            littleMinKHz > 0 && littleMaxKHz >= littleMinKHz &&
            bigMinKHz > 0 && bigMaxKHz >= bigMinKHz &&
            titanMinKHz > 0 && titanMaxKHz >= titanMinKHz &&
            megaMinKHz > 0 && megaMaxKHz >= megaMinKHz &&
            gpuMinKHz > 0 && gpuMaxKHz >= gpuMinKHz

    companion object {
        fun parse(value: String): PerformanceStage? {
            val parts = value.trim().split(",")
            if (parts.size != 11) {
                return null
            }
            val numbers = parts.map { it.toIntOrNull() ?: return null }
            return PerformanceStage(
                numbers[0],
                numbers[1],
                numbers[2],
                numbers[3],
                numbers[4],
                numbers[5],
                numbers[6],
                numbers[7],
                numbers[8],
                numbers[9],
                numbers[10],
            ).takeIf { it.isValid() }
        }
    }
}

const val TEMP_DEFAULT_LEVEL = -1000
const val TEMP_LEVEL_OFFSET = 34

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
