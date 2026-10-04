package com.zui.zuicontrol

import android.content.Context
import org.json.JSONObject

/** Integration binds the two V84 methods after merge. Production must never emulate them. */
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

class V83FrontendGateway(private val context: Context) : FrontendGateway {
    private val upstream = RuleUpstreamFetcher()
    override fun saveGpuDefaultsAtomic(powersave: GpuRanges.Range, balance: GpuRanges.Range,
        performance: GpuRanges.Range, fast: GpuRanges.Range, expectedGeneration: Long): ZuiControlClient.Reply =
        ZuiControlClient.Reply(false, "GPU_DEFAULTS_V84_INTEGRATION_REQUIRED · 等待四档原子保存接口")
    override fun readRecordLinkedThreadAnalysis(packageName: String): JSONObject =
        throw IllegalStateException("RECORD_LINKED_ANALYSIS_V84_INTEGRATION_REQUIRED · 等待记录关联分析接口")
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
}
