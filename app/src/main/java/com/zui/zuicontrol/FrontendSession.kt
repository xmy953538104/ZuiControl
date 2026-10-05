package com.zui.zuicontrol

import java.util.concurrent.Executors
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/** Retained in memory across configuration changes; no second persistent policy/rule store. */
internal class FrontendSession(val gateway: FrontendGateway, val userId: Int,
    private val executor: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor(),
    private val post: (() -> Unit) -> Unit = { android.os.Handler(android.os.Looper.getMainLooper()).post(it) }) {
    var section = "tune"
    var selected = ""
    var settingsModule = 0
    var appDraft: ZuiControlClient.AppPolicyDraft? = null
    var originalApp: ZuiControlClient.AppPolicyDraft? = null
    var newApp = false
    var gpuDraft: GpuDefaultsDraft? = null
    var gpuAuthority: GpuDefaultsDraft? = null; private set
    fun observeGpuDefaults(fresh: GpuDefaultsDraft) {
        if (fresh.expectedGeneration < (gpuAuthority?.expectedGeneration ?: 0)) return
        gpuAuthority = fresh
        if (gpuDraft != null && gpuDraft?.dirty == false && !busy) gpuDraft = fresh
    }
    fun gpuDraftFrom(state: String): GpuDefaultsDraft {
        gpuDraft?.let { return it }
        val fresh = GpuDefaultsDraft.fromState(state, userId)
        observeGpuDefaults(fresh)
        return checkNotNull(gpuAuthority).also { gpuDraft = GpuDefaultsDraft(it.expectedGeneration, it.original) }
            .let { checkNotNull(gpuDraft) }
    }
    var ruleDraft: RuleDraft? = null
    var busy = false
    val refresh = OptimisticControl(0)
    val mode = OptimisticControl("")
    val overlay = OptimisticControl(false)
    var error = ""
    var notice = ""
    var exportBytes = byteArrayOf()
    var backupBytes = byteArrayOf()
    var pendingDocument = 0
    var pendingInspection: SettingsBackup.Inspection? = null
    var latest: RuleUpstreamFetcher.Latest? = null
    var updateStatus = "尚未检查更新"
    var preview: ZuioptLibrary.Preview? = null
    var incoming = byteArrayOf()
    var source: ZuioptLibrary.Provenance? = null
    var appOpt = false
    val decisions = linkedMapOf<String, ZuioptLibrary.Decision>()
    var manual: ZuioptRuleModel? = null
    var onChanged: (() -> Unit)? = null
    val appDirty get() = appDraft != null && (newApp || appDraft != originalApp)
    val dirty get() = appDirty || gpuDraft?.dirty == true || ruleDraft?.dirty == true
    fun clearDrafts() { appDraft = null; originalApp = null; newApp = false; gpuDraft = null; ruleDraft = null }
    fun work(success: String = "", completed: (() -> Unit)? = null, task: () -> Unit) {
        if (busy) return
        busy = true; error = ""; notice = ""
        executor.execute {
            val result = runCatching(task)
            post {
                busy = false
                result.onSuccess { notice = success; completed?.invoke() }.onFailure { error = it.message ?: "操作失败" }
                onChanged?.invoke()
            }
        }
        onChanged?.invoke()
    }
    fun saveApp(completed: (() -> Unit)? = null) {
        val captured = appDraft ?: return
        work("已保存并生效", completed) {
            val reply = gateway.saveAppPolicy(captured); check(reply.ok) { reply.text }
            originalApp = captured; newApp = false
        }
    }
    fun saveGpu(completed: (() -> Unit)? = null) {
        val captured = gpuDraft ?: return
        val r = captured.ranges.toMap()
        work("已保存并生效", completed) {
            val reply = gateway.saveGpuDefaultsAtomic(r.getValue("powersave"), r.getValue("balance"),
                r.getValue("performance"), r.getValue("fast"), captured.expectedGeneration)
            if (!reply.ok) {
                // A rejected CAS never erases the user's ranges or reports success.
                runCatching {
                    val fresh = gateway.readGpuDefaults(userId)
                    check(fresh.expectedGeneration >= captured.expectedGeneration) { "GPU_CAS_REFRESH_STALE" }
                    observeGpuDefaults(fresh)
                    val authority = checkNotNull(gpuAuthority)
                    gpuDraft = GpuDefaultsDraft(authority.expectedGeneration, authority.original).also {
                        r.forEach { (mode, range) -> it.set(mode, range) }
                    }
                }
                error(reply.text)
            }
            // Establish post-terminal authority BEFORE making a clean draft. MainActivity
            // may still hold its pre-save state while onChanged immediately renders again.
            val fresh = gateway.readGpuDefaults(userId)
            check(fresh.expectedGeneration > captured.expectedGeneration) { "GPU_POST_ACK_READ_STALE" }
            observeGpuDefaults(fresh)
            val authority = checkNotNull(gpuAuthority)
            gpuDraft = GpuDefaultsDraft(authority.expectedGeneration, authority.original)
        }
    }
    fun close() { onChanged = null; executor.shutdown() }
    fun save(out: Bundle) {
        out.putInt("user", userId); out.putString("section", section); out.putString("selected", selected)
        out.putInt("module", settingsModule); out.putBoolean("newApp", newApp)
        out.putByteArray("export", exportBytes); out.putByteArray("backup", backupBytes); out.putInt("document", pendingDocument)
        pendingInspection?.let { out.putString("inspection", it.summary.toString()) }
        appDraft?.let { out.putString("app", appJson(it).toString()) }
        originalApp?.let { out.putString("originalApp", appJson(it).toString()) }
        gpuDraft?.let { d ->
            out.putLong("gpuGeneration", d.expectedGeneration)
            out.putString("gpu", rangesJson(d.ranges).toString()); out.putString("gpuOriginal", rangesJson(d.original).toString())
        }
        ruleDraft?.let { d ->
            out.putString("ruleBase", d.base.normalized()); out.putString("ruleGeneration", d.generation)
            out.putString("rulePackage", d.packageName); out.putBoolean("clone", d.cloneConfirmed)
            out.putString("profile", profileJson(d.profile).toString())
        }
    }
    fun restore(saved: Bundle) {
        if (saved.getInt("user", -1) != userId) return
        section = saved.getString("section", "tune"); selected = saved.getString("selected", "")
        settingsModule = saved.getInt("module"); newApp = saved.getBoolean("newApp")
        exportBytes = saved.getByteArray("export") ?: byteArrayOf(); backupBytes = saved.getByteArray("backup") ?: byteArrayOf()
        pendingDocument = saved.getInt("document")
        runCatching {
            saved.getString("inspection")?.let { text -> val j = JSONObject(text)
                pendingInspection = SettingsBackup.Inspection(j.getString("transaction"), j.getString("hash"), j) }
            saved.getString("app")?.let { appDraft = readApp(JSONObject(it)) }
            saved.getString("originalApp")?.let { originalApp = readApp(JSONObject(it)) }
            saved.getString("gpu")?.let { ranges ->
                gpuDraft = GpuDefaultsDraft(saved.getLong("gpuGeneration"), readRanges(JSONObject(saved.getString("gpuOriginal").orEmpty())))
                    .apply { readRanges(JSONObject(ranges)).forEach { (m, r) -> set(m, r) } }
            }
            saved.getString("ruleBase")?.let { text ->
                val base = ZuioptRuleModel.parseNormalized(text); val pkg = saved.getString("rulePackage").orEmpty()
                ruleDraft = RuleDraft(pkg, base, saved.getString("ruleGeneration").orEmpty(), base.appProfile(pkg),
                    readProfile(JSONObject(saved.getString("profile").orEmpty())), saved.getBoolean("clone"))
            }
        }.onFailure { error = "草稿恢复失败：${it.message}" }
    }
    private fun appJson(d: ZuiControlClient.AppPolicyDraft) = JSONObject().put("pkg", d.packageName).put("hz", d.refreshHz)
        .put("mode", d.uperfMode).put("policy", d.gpuPolicy.name).put("generation", d.expectedGeneration)
        .put("min", d.gpuMinMHz).put("max", d.gpuMaxMHz)
    private fun readApp(j: JSONObject) = ZuiControlClient.AppPolicyDraft(j.getString("pkg"), j.getInt("hz"), j.getString("mode"),
        ZuiControlClient.GpuPolicy.valueOf(j.getString("policy")), j.getLong("generation"),
        if (j.has("min")) j.getInt("min") else null, if (j.has("max")) j.getInt("max") else null)
    private fun rangesJson(r: Map<String, GpuRanges.Range>) = JSONObject(r.mapValues { "${it.value.min},${it.value.max}" })
    private fun readRanges(j: JSONObject) = GpuDefaultsDraft.modes.associateWith { m ->
        val p = j.getString(m).split(',').map(String::toInt); GpuRanges.Range(p[0], p[1]) }
    private fun profileJson(p: ZuioptRuleModel.Profile) = JSONObject().put("mask", JSONArray(p.generalMask.toList()))
        .put("rules", JSONArray(p.rules.map { r -> JSONObject().put("class", r.competitionClass).put("kind", r.matchKind)
            .put("pattern", r.pattern).put("selector", r.selector).put("priority", r.priority).put("mask", JSONArray(r.cpuMask.toList())) }))
    private fun readProfile(j: JSONObject): ZuioptRuleModel.Profile {
        fun mask(a: JSONArray) = (0 until a.length()).map { a.getInt(it) }.toSet()
        val rows = j.getJSONArray("rules")
        return ZuioptRuleModel.Profile(mask(j.getJSONArray("mask")), (0 until rows.length()).map {
            val r = rows.getJSONObject(it); ZuioptRuleModel.Rule(r.getString("class"), r.getString("kind"), r.getString("pattern"),
                r.getString("selector"), r.getInt("priority"), mask(r.getJSONArray("mask"))) })
    }
}

class RuleDraft(val packageName: String, val base: ZuioptRuleModel, val generation: String,
    val original: ZuioptRuleModel.Profile?, var profile: ZuioptRuleModel.Profile,
    var cloneConfirmed: Boolean = false) {
    val dirty get() = original == null || profile != original
    fun canonical(): String {
        require(profile.generalMask.isNotEmpty() && profile.rules.all { it.cpuMask.isNotEmpty() }) { "请为每条规则选择 CPU" }
        val ordered = profile.copy(rules = profile.rules.mapIndexed { i, r -> r.copy(priority = 100000 - i) })
        if (original != null) return base.editApp(packageName) { ordered }.normalized()
        val alias = (0..64).map { "user$it" }.first { it !in base.profiles }
        val mappings = (listOf(ZuioptRuleModel.Mapping("exact", packageName, alias, 0)) + base.mappings)
            .mapIndexed { i, m -> m.copy(priority = 100000 - i) }
        return base.copy(profiles = base.profiles + (alias to ordered), mappings = mappings).normalized()
            .also { ZuioptRuleModel.parseNormalized(it) }
    }
}
