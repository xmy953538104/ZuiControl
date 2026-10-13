package com.zui.zuicontrol

/** Draft/view model only. Native validation and generation CAS remain authoritative. */
data class ZuioptRuleModel(val enabled: Boolean, val profiles: Map<String, Profile>, val mappings: List<Mapping>,
    val schema: Int = 2, val mode: Int = 0, val rt: Int = 0) {
    data class Rule(val competitionClass: String, val matchKind: String, val pattern: String,
                    val selector: String, val priority: Int, val cpuMask: Set<Int>)
    data class Runtime(val mode: Int = -1, val rt: Int = -1, val placement: String = "explicit", val marker: String = "none") {
        init { require(mode in -1..2 && rt in -1..1 && placement in setOf("explicit","preset") && marker in setOf("none","dummy","double") && (placement=="preset" || marker=="none")) }
    }
    data class Profile(val generalMask: Set<Int>, val rules: List<Rule>, val runtime: Runtime = Runtime())
    data class Mapping(val matchKind: String, val packageName: String, val profile: String, val priority: Int)

    fun appProfile(packageName: String): Profile? = mappings.sortedByDescending { it.priority }.firstOrNull {
        when(it.matchKind){"exact"->it.packageName==packageName;"prefix"->packageName.startsWith(it.packageName);else->packageName.contains(it.packageName)}
    }?.let { profiles.getValue(it.profile) }

    /** Same semantic comparison as the native library: profile labels and absolute priorities are not behavior. */
    fun provenance(packageName: String, upstream: ZuioptRuleModel): String? {
        fun behavior(profile: Profile?) = profile?.copy(rules=profile.rules.sortedByDescending { row->row.priority }.mapIndexed { i,r -> r.copy(priority=100000-i) })
        val effective=behavior(appProfile(packageName));val baseline=behavior(upstream.appProfile(packageName))
        return when { effective==null->null;baseline==null->"USER_CREATED";effective==baseline->"UPSTREAM";else->"USER_MODIFIED" }
    }

    fun normalized(): String = buildString {
        require(schema in 2..3 && mode in 0..2 && rt in 0..1)
        val runtime=schema==3 || mode!=0 || rt!=0 || profiles.values.any { it.runtime!=Runtime() }
        append("schema ${if(runtime)3 else 2}\nenabled $enabled\ndebug false\n")
        if(runtime)append("runtime_defaults $mode $rt\n")
        for ((name, profile) in profiles.toSortedMap()) {
            append("profile $name ${maskText(profile.generalMask)}\n")
            if(runtime)profile.runtime.let { append("runtime $name ${it.mode} ${it.rt} ${it.placement} ${it.marker}\n") }
            for (r in profile.rules.sortedByDescending { it.priority })
                append("thread $name ${r.competitionClass} ${r.matchKind} ${quote(r.pattern)} selector=${r.selector} ${r.priority} ${maskText(r.cpuMask)}\n")
        }
        for (m in mappings.sortedByDescending { it.priority })
            append("package ${m.matchKind} ${m.packageName} ${m.profile} ${m.priority}\n")
    }.also { require(it.length in 1..65536) }

    /** Always clone before a single-App edit; even a prefix mapping may cover unknown siblings. */
    fun editApp(packageName: String, edit: (Profile) -> Profile): ZuioptRuleModel {
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
        val mapping = mappings.sortedByDescending { it.priority }.firstOrNull {
            when (it.matchKind) { "exact" -> it.packageName == packageName
                "prefix" -> packageName.startsWith(it.packageName)
                else -> packageName.contains(it.packageName) }
        }
        require(mapping != null) { "No existing profile for App" }
        require(profiles.size < 64)
        val alias = (0..64).map { "user$it" }.first { it !in profiles }
        val nextProfiles = profiles + (alias to edit(profiles.getValue(mapping.profile)))
        val rows = mappings.filterNot { it.matchKind == "exact" && it.packageName == packageName }
        val next = listOf(Mapping("exact", packageName, alias, 0)) + rows.sortedByDescending { it.priority }
        require(next.size <= 512)
        return copy(profiles = nextProfiles, mappings = next.mapIndexed { i, m -> m.copy(priority = 100000 - i) })
            .also { parse(it.normalized()) }
    }

    companion object {
        private val label = Regex("[A-Za-z0-9_.-]{1,128}")
        private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        private fun maskText(mask: Set<Int>): String {
            require(mask.isNotEmpty() && mask.all { it in 0..7 })
            return mask.sorted().joinToString(",")
        }
        private fun mask(text: String): Set<Int> {
            require(text.matches(Regex("[0-9]+(-[0-9]+)?(,[0-9]+(-[0-9]+)?)*")))
            return text.split(',').flatMap {
                val ends = it.split('-').map(String::toInt); val a = ends.first(); val b = ends.last()
                require(a in 0..7 && b in a..7); (a..b).toList()
            }.toSet()
        }
        private fun tokens(line: String): List<String> {
            val out = mutableListOf<String>(); var i = 0
            while (i < line.length) {
                while (i < line.length && line[i].isWhitespace()) i++
                if (i == line.length) break
                if (line[i] != '"') { val begin = i; while (i < line.length && !line[i].isWhitespace()) i++; out += line.substring(begin, i) }
                else {
                    i++; val value = StringBuilder(); var closed = false
                    while (i < line.length) {
                        val c = line[i++]
                        if (c == '"') { closed = true; break }
                        if (c == '\\') { require(i < line.length); value.append(line[i++]) } else value.append(c)
                    }
                    require(closed && (i == line.length || line[i].isWhitespace())); out += value.toString()
                }
            }
            return out
        }
        fun parseNormalized(text: String): ZuioptRuleModel = parse(text).also { require(it.normalized() == text) { "Raw-only: non-normalized or lossy model" } }
        fun parse(text: String): ZuioptRuleModel {
            require(text.length in 1..65536 && text.all { it == '\n' || it == '\r' || it == '\t' || it.code in 32..126 })
            val headers = mutableMapOf<String, String>(); val profiles = linkedMapOf<String, Profile>(); val mappings = mutableListOf<Mapping>()
            var defaults: Pair<Int,Int>? = null;val runtimeProfiles=mutableSetOf<String>()
            for ((index, raw) in text.lineSequence().withIndex()) {
                val line = raw.trim(); if (line.isEmpty() || line.startsWith('#')) continue
                require(line.length < 1024) { "Line ${index + 1}: too long" }
                val t = tokens(line)
                when (t[0]) {
                    "schema", "enabled", "debug" -> { require(t.size == 2 && t[0] !in headers); headers[t[0]] = t[1] }
                    "profile" -> { require(t.size == 3 && label.matches(t[1]) && t[1] !in profiles); profiles[t[1]] = Profile(mask(t[2]), emptyList()) }
                    "runtime_defaults" -> { require(t.size==3 && defaults==null);val mode=t[1].toInt();val rt=t[2].toInt();require(mode in 0..2 && rt in 0..1);defaults=mode to rt }
                    "runtime" -> { require(t.size==6 && t[1] in profiles && runtimeProfiles.add(t[1]));profiles[t[1]]=profiles.getValue(t[1]).copy(runtime=Runtime(t[2].toInt(),t[3].toInt(),t[4],t[5])) }
                    "thread" -> {
                        require(t.size == 8 && t[1] in profiles && label.matches(t[2]) && t[3] in setOf("exact", "prefix", "contains", "glob"))
                        require(t[4].length <= 64 && (t[4].isNotEmpty() || t[3] == "contains"))
                        val selector = t[5].removePrefix("selector="); require(t[5].startsWith("selector="))
                        require(selector == "all" || (selector.startsWith("rank:") && selector.substringAfter(':').toIntOrNull() in 1..1024))
                        val rule = Rule(t[2], t[3], t[4], selector, t[6].toInt(), mask(t[7])); val p = profiles.getValue(t[1])
                        require(p.rules.size < 32 && p.rules.none { it.priority == rule.priority || (it.competitionClass == rule.competitionClass && (it.selector != rule.selector || it.cpuMask != rule.cpuMask)) })
                        profiles[t[1]] = p.copy(rules = (p.rules + rule).sortedByDescending { it.priority })
                    }
                    "package" -> {
                        require(t.size == 5 && t[1] in setOf("exact", "prefix", "contains") && label.matches(t[2]) && t[3] in profiles)
                        val m = Mapping(t[1], t[2], t[3], t[4].toInt())
                        require(mappings.none { it.priority == m.priority || (it.matchKind == m.matchKind && it.packageName == m.packageName) }); mappings += m
                    }
                    else -> error("Line ${index + 1}: unsupported directive")
                }
                require(profiles.size <= 64 && mappings.size <= 512)
            }
            require(headers["schema"] in setOf("2","3") && headers["enabled"] in setOf("true", "false") && headers["debug"] in setOf(null, "false"))
            require(headers["schema"]=="3" || (defaults==null && runtimeProfiles.isEmpty()))
            return ZuioptRuleModel(headers["enabled"] == "true", profiles.toSortedMap(), mappings.sortedByDescending { it.priority },headers.getValue("schema").toInt(),defaults?.first ?: 0,defaults?.second ?: 0)
        }
    }
}
