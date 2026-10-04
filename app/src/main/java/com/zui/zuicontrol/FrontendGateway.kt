package com.zui.zuicontrol

import android.content.Context
import org.json.JSONObject

/** Production adapters use the existing authenticated owners and terminal ACKs. */
interface FrontendGateway {
    fun saveGpuDefaultsAtomic(powersave: GpuRanges.Range, balance: GpuRanges.Range,
        performance: GpuRanges.Range, fast: GpuRanges.Range, expectedGeneration: Long): ZuiControlClient.Reply
    fun readRecordLinkedThreadAnalysis(packageName: String): JSONObject
    fun saveAppPolicy(draft: ZuiControlClient.AppPolicyDraft): ZuiControlClient.Reply
    fun setGlobal(action: String, value: Int = 0, mode: String = ""): ZuiControlClient.Reply
    fun setOverlay(enabled: Boolean): ZuiControlClient.Reply
    fun stopRecord(): ZuiControlClient.Reply
    fun checkUpstream(baseline: ZuioptLibrary.Baseline): RuleUpstreamFetcher.Latest?
    fun downloadUpstream(latest: RuleUpstreamFetcher.Latest, baseline: ZuioptLibrary.Baseline): ByteArray
}

class V84FrontendGateway(private val context: Context) : FrontendGateway {
    private val upstream = RuleUpstreamFetcher()
    override fun saveGpuDefaultsAtomic(powersave: GpuRanges.Range, balance: GpuRanges.Range,
        performance: GpuRanges.Range, fast: GpuRanges.Range, expectedGeneration: Long): ZuiControlClient.Reply = runCatching {
        val payload = gpuBatchPayload(ZuiControlClient.currentUserId(), expectedGeneration,
            listOf(powersave, balance, performance, fast))
        val id = ZuiControlRequest.send(context, "policy", mode = java.util.Base64.getEncoder()
            .encodeToString(payload.toString().toByteArray(Charsets.UTF_8)))
        val ack = ZuiControlRequest.awaitTerminalAck(context, id)
        ZuiControlClient.Reply(ack.succeeded, ack.detail)
    }.getOrElse { ZuiControlClient.Reply(false, it.message.orEmpty()) }
    override fun readRecordLinkedThreadAnalysis(packageName: String): JSONObject {
        require(PackageNames.isValid(packageName))
        val result = ThreadAnalysisClient.read(packageName)
        if (result.length() == 0) return result
        // Read the current source after the hash-bound chunks; a replaced/deleted record cannot be reused.
        val record = JSONObject(PerformanceMonitor.command("recordRead", JSONObject().put("package", packageName).toString()))
        validateRecordRelation(result, record, ZuiControlClient.currentUserId(), packageName)
        return result
    }
    override fun saveAppPolicy(draft: ZuiControlClient.AppPolicyDraft) = ZuiControlClient.saveAppPolicy(context, draft)
    override fun setGlobal(action: String, value: Int, mode: String): ZuiControlClient.Reply = runCatching {
        require(action in setOf("refresh", "mode"))
        val id = ZuiControlClient.sendPolicy(context, action, "", "GLOBAL", value = value, mode = mode)
        val ack = ZuiControlRequest.awaitTerminalAck(context, id)
        ZuiControlClient.Reply(ack.succeeded, ack.detail)
    }.getOrElse { ZuiControlClient.Reply(false, it.message.orEmpty()) }
    override fun setOverlay(enabled: Boolean): ZuiControlClient.Reply {
        // V83 full toggles. Read once at this explicit action to preserve an already-full owner.
        val current = PerformanceMonitor.command("state")
        if (!current.startsWith("ok=1")) return ZuiControlClient.Reply(false, current)
        val full = ZuiControlClient.stateValue(current, "monitorMode") == "1"
        return if (full == enabled) ZuiControlClient.Reply(true, current) else monitor(if (enabled) "full" else "off")
    }
    override fun stopRecord() = monitor("recordStop")
    private fun monitor(action: String): ZuiControlClient.Reply {
        val reply = PerformanceMonitor.command(action)
        return ZuiControlClient.Reply(reply.startsWith("ok=1"), reply)
    }
    override fun checkUpstream(baseline: ZuioptLibrary.Baseline) = upstream.check(baseline)
    override fun downloadUpstream(latest: RuleUpstreamFetcher.Latest, baseline: ZuioptLibrary.Baseline) = upstream.download(latest, baseline)

    companion object {
        internal fun gpuBatchPayload(user: Int, generation: Long, ranges: List<GpuRanges.Range>): JSONObject {
            require(user >= 0 && generation > 0 && ranges.size == 4)
            val pairs = JSONObject()
            GpuDefaultsDraft.modes.zip(ranges).forEach { (mode, range) ->
                pairs.put(mode, JSONObject().put("min", range.min).put("max", range.max))
            }
            return JSONObject().put("action", "defaultGpuBatch").put("userId", user)
                .put("generation", generation).put("ranges", pairs)
        }
        internal fun validateRecordRelation(a: JSONObject, r: JSONObject, user: Int, pkg: String) {
            require(a.getInt("schema") == 2 && a.getString("acquisition") == "MONITOR_RECORDING") { "RECORD_LINKED_ANALYSIS_REQUIRED" }
            require(a.getInt("user") == user && r.getInt("user") == user &&
                a.getString("package") == pkg && r.getString("package") == pkg &&
                a.getLong("sourceRecordId") > 0 && a.getLong("sourceRecordId") == r.getLong("recordId") &&
                a.getLong("sourceRecordWall") == r.getLong("wall") &&
                a.getLong("sourceRecordStartElapsed") == r.getLong("startElapsed") &&
                a.getString("sourceRecordCompletion") == (if (r.getBoolean("complete")) "COMPLETE" else "INCOMPLETE") &&
                a.getString("sourceRecordTerminalReason") == r.getString("terminalReason") && !r.getBoolean("active")) { "RECORD_ANALYSIS_SOURCE_MISMATCH" }
            check(a.getString("state") in setOf("FINISHED", "TIMED_OUT")) { "RECORD_ANALYSIS_UNAVAILABLE · ${a.optString("error")}" }
            require(a.getInt("eligibleSamples") in 0..600 && a.getInt("processSegmentCount") in 0..1024)
            val rows = a.getJSONArray("threads"); require(rows.length() <= 512)
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i); require(row.getString("name").isNotEmpty())
                for (key in listOf("distinctIdentityCount", "sameNameConcurrencyMax", "presenceSamples")) require(row.getInt(key) >= 0)
                for (key in listOf("presencePct", "avgCpuPct", "peakCpuPct", "medianRank", "top1SharePct", "top3SharePct")) {
                    require(row.has(key))
                    if (!row.isNull(key)) require(row.getDouble(key).isFinite() && row.getDouble(key) >= 0)
                }
            }
        }
    }
}
