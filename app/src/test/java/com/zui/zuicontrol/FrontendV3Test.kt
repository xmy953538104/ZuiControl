package com.zui.zuicontrol

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class FrontendV3Test {
    private class FakeFrontendGateway : FrontendGateway {
        var calls = 0
        var succeed = false
        var gpu: List<GpuRanges.Range>? = null
        var generation = -1L
        var app: ZuiControlClient.AppPolicyDraft? = null
        var authoritativeGeneration = 84L
        var readFailure = false
        var authoritativeRanges = GpuDefaultsDraft.modes.associateWith { GpuRanges.Range(231,903) }
        override fun readGpuDefaults(user: Int): GpuDefaultsDraft {
            check(!readFailure) { "READ_UNAVAILABLE" }
            return GpuDefaultsDraft(authoritativeGeneration, authoritativeRanges)
        }
        override fun saveGpuDefaultsAtomic(powersave: GpuRanges.Range, balance: GpuRanges.Range,
            performance: GpuRanges.Range, fast: GpuRanges.Range, expectedGeneration: Long): ZuiControlClient.Reply {
            calls++; gpu = listOf(powersave, balance, performance, fast); generation = expectedGeneration
            return ZuiControlClient.Reply(succeed, if (succeed) "ACK" else "GENERATION_MISMATCH")
        }
        override fun saveAppPolicy(draft: ZuiControlClient.AppPolicyDraft): ZuiControlClient.Reply {
            calls++; app = draft; return ZuiControlClient.Reply(succeed, if (succeed) "ACK" else "REFUSED")
        }
        override fun readRecordLinkedThreadAnalysis(packageName: String): JSONObject = error("unused")
        override fun setGlobal(action: String, value: Int, mode: String) = error("unused")
        override fun setOverlay(enabled: Boolean) = error("unused")
        override fun stopRecord() = error("unused")
        override fun checkUpstream(baseline: ZuioptLibrary.Baseline) = error("unused")
        override fun downloadUpstream(latest: RuleUpstreamFetcher.Latest, baseline: ZuioptLibrary.Baseline) = error("unused")
    }
    @Test fun optimisticSelectionWaitsForAckAndRollsBack() {
        val control = OptimisticControl(120)
        assertTrue(control.begin(165)); assertEquals(165, control.displayed); assertEquals(120, control.confirmed)
        assertFalse(control.begin(60)); control.observe(90); assertEquals(165, control.displayed)
        control.finish(false); assertFalse(control.pending); assertEquals(120, control.displayed)
        control.begin(144); control.finish(true); assertEquals(144, control.confirmed)
        control.observe(60); assertEquals(60, control.displayed)
    }
    @Test fun gpuDraftUsesOneCapturedAtomicCallAndSurvivesFailureAndListenerReplacement() {
        val gateway = FakeFrontendGateway(); val callbacks = LinkedBlockingQueue<() -> Unit>()
        val session = FrontendSession(gateway, 0, Executors.newSingleThreadExecutor()) { callbacks.put(it) }
        try {
            val custom = GpuDefaultsDraft.modes.associateWith { GpuRanges.Range(231, 903) }
            val draft = GpuDefaultsDraft(83, custom); session.gpuDraft = draft
            draft.restoreDefaults(); assertTrue(draft.dirty); assertEquals(0, gateway.calls)
            val captured = draft.ranges.toMap(); var oldListener = 0; var newListener = 0; var navigated = false
            session.onChanged = { oldListener++ }; session.saveGpu { navigated = true }
            assertTrue(session.busy); assertEquals("", session.notice)
            session.onChanged = { newListener++ }; session.saveGpu()
            checkNotNull(callbacks.poll(5, TimeUnit.SECONDS)).invoke()
            assertFalse(session.busy); assertFalse(navigated); assertEquals(1, gateway.calls)
            assertEquals(83L, gateway.generation); assertEquals(GpuDefaultsDraft.modes.map { captured.getValue(it) }, gateway.gpu)
            assertEquals(captured, session.gpuDraft!!.ranges); assertTrue(session.gpuDraft!!.dirty)
            assertEquals(84L,session.gpuDraft!!.expectedGeneration)
            assertEquals("GENERATION_MISMATCH", session.error); assertEquals(1, oldListener); assertEquals(1, newListener)
            gateway.succeed = true; session.saveGpu { navigated = true }
            checkNotNull(callbacks.poll(5, TimeUnit.SECONDS)).invoke()
            assertTrue(navigated); assertFalse(session.gpuDraft!!.dirty); assertEquals(2, gateway.calls)
            assertEquals("已保存并生效", session.notice)
        } finally { session.close() }
    }
    @Test fun gpuPostAckAuthorityWinsOverStaleActivityStateAndNextSaveUsesFreshGeneration() {
        val gateway=FakeFrontendGateway().apply{succeed=true;authoritativeGeneration=360}
        val callbacks=LinkedBlockingQueue<()->Unit>()
        val session=FrontendSession(gateway,0,Executors.newSingleThreadExecutor()){callbacks.put(it)}
        try {
            val stale=GpuDefaultsDraft(359,gateway.authoritativeRanges)
            session.observeGpuDefaults(stale);session.gpuDraft=GpuDefaultsDraft(359,stale.original).apply{set("fast",GpuRanges.Range(680,903))}
            gateway.authoritativeRanges=session.gpuDraft!!.ranges.toMap()
            session.saveGpu();checkNotNull(callbacks.poll(5,TimeUnit.SECONDS)).invoke()
            assertEquals(359L,gateway.generation);assertEquals(360L,session.gpuDraft!!.expectedGeneration);assertFalse(session.gpuDraft!!.dirty)
            session.observeGpuDefaults(stale);assertEquals(360L,session.gpuDraft!!.expectedGeneration)
            session.gpuDraft!!.set("fast",GpuRanges.Range(629,903))
            val dirty=session.gpuDraft;session.observeGpuDefaults(GpuDefaultsDraft(360,gateway.authoritativeRanges))
            assertSame(dirty,session.gpuDraft);assertTrue(session.gpuDraft!!.dirty)
            gateway.authoritativeGeneration=361;gateway.authoritativeRanges=session.gpuDraft!!.ranges.toMap()
            session.saveGpu();checkNotNull(callbacks.poll(5,TimeUnit.SECONDS)).invoke()
            assertEquals(360L,gateway.generation);assertEquals(361L,session.gpuDraft!!.expectedGeneration);assertFalse(session.gpuDraft!!.dirty)
            session.clearDrafts();assertEquals(361L,session.gpuDraftFrom("ok=1\npolicyGeneration=359\n"+stale.original.entries.joinToString("\n"){"gpuGlobal=0|${it.key}|${it.value.min}|${it.value.max}"}).expectedGeneration)
        } finally {session.close()}
    }
    @Test fun postAckReadFailureKeepsDraftAndCannotClaimSuccess() {
        val gateway=FakeFrontendGateway().apply{succeed=true;readFailure=true}
        val callbacks=LinkedBlockingQueue<()->Unit>();val session=FrontendSession(gateway,0,Executors.newSingleThreadExecutor()){callbacks.put(it)}
        try{
            val draft=GpuDefaultsDraft(83,gateway.authoritativeRanges).apply{restoreDefaults()};session.gpuDraft=draft
            session.saveGpu();checkNotNull(callbacks.poll(5,TimeUnit.SECONDS)).invoke()
            assertSame(draft,session.gpuDraft);assertTrue(draft.dirty);assertEquals("",session.notice);assertEquals("READ_UNAVAILABLE",session.error)
        }finally{session.close()}
    }
    @Test fun completeAppDraftIsConfirmedOnlyAfterSuccessfulAck() {
        val gateway = FakeFrontendGateway(); val callbacks = LinkedBlockingQueue<() -> Unit>()
        val session = FrontendSession(gateway, 0, Executors.newSingleThreadExecutor()) { callbacks.put(it) }
        try {
            val saved = ZuiControlClient.AppPolicyDraft("org.example.game", 120, "balance", ZuiControlClient.GpuPolicy.DEFAULT_FOR_MODE, 42)
            val edited = saved.copy(refreshHz = 165, uperfMode = "fast", gpuPolicy = ZuiControlClient.GpuPolicy.CUSTOM, gpuMinMHz = 422, gpuMaxMHz = 903)
            session.originalApp = saved; session.appDraft = edited
            session.saveApp(); checkNotNull(callbacks.poll(5, TimeUnit.SECONDS)).invoke()
            assertEquals(edited, gateway.app); assertEquals(saved, session.originalApp); assertEquals(edited, session.appDraft)
            assertTrue(session.appDirty); assertEquals("REFUSED", session.error)
            gateway.succeed = true; session.saveApp(); checkNotNull(callbacks.poll(5, TimeUnit.SECONDS)).invoke()
            assertEquals(edited, session.originalApp); assertFalse(session.appDirty)
        } finally { session.close() }
    }
    @Test fun restoreDefaultRemainsDraftUntilSavedAndMayBeDiscarded() {
        val custom = GpuDefaultsDraft.modes.associateWith { GpuRanges.Range(310, 903) }
        val d = GpuDefaultsDraft(9, custom); assertFalse(d.dirty)
        d.restoreDefaults(); assertTrue(d.dirty); assertEquals(custom, d.original)
        assertEquals(GpuDefaultsDraft.modes.associateWith(GpuRanges::default), d.ranges)
        assertFalse(GpuDefaultsDraft(9, custom).dirty)
    }
    @Test fun sharedRuleDraftIsolationAndOrderAndExplicitCpuSelection() {
        val r = ZuioptRuleModel.Rule("shared", "prefix", "Worker", "rank:2", 90, setOf(7))
        val p = ZuioptRuleModel.Profile(setOf(0,1,2), listOf(r, r.copy(pattern = "Pool", priority = 80)))
        val base = ZuioptRuleModel(true, mapOf("Common" to p), listOf(
            ZuioptRuleModel.Mapping("prefix", "org.example.", "Common", 100)))
        val draft = RuleDraft("org.example.one", base, "generation", p, p.copy(rules = p.rules.reversed()))
        val next = ZuioptRuleModel.parseNormalized(draft.canonical())
        assertEquals(p, next.appProfile("org.example.two")); assertEquals("Pool", next.appProfile("org.example.one")!!.rules.first().pattern)
        assertEquals("Worker", base.appProfile("org.example.one")!!.rules.first().pattern)
        draft.profile = draft.profile.copy(rules = draft.profile.rules + r.copy(competitionClass = "new", pattern = "Selected", cpuMask = emptySet()))
        assertThrows(IllegalArgumentException::class.java) { draft.canonical() }
        assertTrue(draft.dirty)
    }
    @Test fun explicitThemeOverridesSystemAndFollowSystemTracksBoth() {
        assertTrue(FrontendTheme.dark("dark", false)); assertFalse(FrontendTheme.dark("light", true))
        assertTrue(FrontendTheme.dark("system", true)); assertFalse(FrontendTheme.dark("system", false))
    }
    private val bytes = "schema 2\nenabled true\ndebug false\n".toByteArray()
    private fun latest(revision: Long = 84, hash: String = RuleUpstreamFetcher.hash(bytes)) =
        RuleUpstreamFetcher.Latest(revision, "v84", "2026-10-04", "a".repeat(40), bytes.size, hash, "SM8650", "0-7", 2, "qualification-fixture")
    @Test fun upstreamIntegrityAndRevisionFailClosed() {
        val l = latest(); l.verify(bytes, 83)
        assertThrows(IllegalArgumentException::class.java) { l.verify(bytes + 1, 83) }
        assertThrows(IllegalArgumentException::class.java) { l.verify(bytes, 84) }
        assertFalse(l.newerThan(84, l.sha256)); assertTrue(l.newerThan(83, "0".repeat(64)))
        assertFalse(l.newerThan(83, l.sha256))
        assertThrows(IllegalArgumentException::class.java) { l.newerThan(85, l.sha256) }
        assertThrows(IllegalArgumentException::class.java) { l.newerThan(84, "0".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { l.copy(soc = "other") }
        assertThrows(IllegalArgumentException::class.java) { l.copy(topology = "0-8") }
        assertThrows(IllegalArgumentException::class.java) { l.copy(rulesSchema = 1) }
        assertThrows(IllegalArgumentException::class.java) { l.copy(size = 65537) }
        assertThrows(IllegalArgumentException::class.java) { latest(hash = "X".repeat(64)) }
        val schema1 = "schema 1\nenabled true\n".toByteArray()
        assertThrows(IllegalArgumentException::class.java) { l.copy(size = schema1.size, sha256 = RuleUpstreamFetcher.hash(schema1)).verify(schema1,83) }
    }
}
