package com.zui.zuicontrol

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class FrontendV3Test {
    @Test fun unchangedAppRevisionAdvancesCleanCasButPreservesDirtyCas() {
        val session=FrontendSession(FakeFrontendGateway(),0,Executors.newSingleThreadExecutor()){}
        try {
            val saved=ZuiControlClient.AppPolicyDraft("org.test.app",120,"balance",ZuiControlClient.GpuPolicy.DEFAULT_FOR_MODE,10)
            session.appDraft=saved;session.originalApp=saved
            session.observeUnchangedAppGeneration(11)
            assertEquals(saved.copy(expectedGeneration=11),session.appDraft);assertFalse(session.appDirty)
            session.appDraft=session.appDraft!!.copy(refreshHz=60);val dirty=session.appDraft
            session.observeUnchangedAppGeneration(12)
            assertEquals(dirty,session.appDraft);assertEquals(11L,session.appDraft!!.expectedGeneration)
            assertEquals(12L,session.appAuthorityGeneration);assertTrue(session.appDirty)
        }finally{session.close()}
    }
    @Test fun globalTerminalRebindIdentifiesOnlyItsOwnControl() {
        val callbacks=LinkedBlockingQueue<()->Unit>();val rebound=mutableListOf<Any>()
        val session=FrontendSession(FakeFrontendGateway(),0,Executors.newSingleThreadExecutor()){callbacks.put(it)}
        try {
            session.mode.observe("balance");session.onControlsChanged={rebound+=it}
            session.intent(session.refresh,90){ZuiControlClient.Reply(true,"ACK")}
            checkNotNull(callbacks.poll(5,TimeUnit.SECONDS)).invoke()
            assertEquals(listOf(session.refresh),rebound);assertEquals("balance",session.mode.confirmed);assertFalse(session.busy)
        }finally{session.close()}
    }
    @Test fun directReadDoesNotHoldCommandsOrMutationBusy() {
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val callbacks=LinkedBlockingQueue<()->Unit>();val session=FrontendSession(FakeFrontendGateway(),0,Executors.newSingleThreadExecutor()){callbacks.put(it)}
        try {
            session.directRead{entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
            check(entered.await(5,TimeUnit.SECONDS));assertFalse(session.busy)
            session.appDraft=ZuiControlClient.AppPolicyDraft("org.test.app",120,"balance",ZuiControlClient.GpuPolicy.DEFAULT_FOR_MODE,10)
            session.originalApp=session.appDraft
            session.appDraft=session.appDraft!!.copy(refreshHz=90);assertTrue(session.appDirty)
            session.intent(session.refresh,60){ZuiControlClient.Reply(true,"ACK")}
            checkNotNull(callbacks.poll(1,TimeUnit.SECONDS)).invoke()
            assertEquals(60,session.refresh.confirmed);assertFalse(session.busy);assertTrue(session.appDirty)
        }finally{release.countDown();session.close()}
    }
    @Test fun defaultSessionsAndNotificationUseOneProcessCommandLane() {
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val complete=java.util.concurrent.CountDownLatch(1);val events=java.util.Collections.synchronizedList(mutableListOf<String>())
        val callbacks=LinkedBlockingQueue<()->Unit>()
        val first=FrontendSession(FakeFrontendGateway(),0,post={callbacks.put(it)})
        val second=FrontendSession(FakeFrontendGateway(),0,post={callbacks.put(it)})
        try {
            first.read{events+="read-start";entered.countDown();check(release.await(5,TimeUnit.SECONDS));events+="read-consumed"}
            check(entered.await(5,TimeUnit.SECONDS))
            second.intent(second.refresh,90){events+="policy";ZuiControlClient.Reply(true,"ACK")}
            FrontendTransport.commands.execute{events+="quick";complete.countDown()}
            assertFalse(complete.await(100,TimeUnit.MILLISECONDS));assertEquals(listOf("read-start"),events.toList())
            first.close();release.countDown();check(complete.await(5,TimeUnit.SECONDS))
            checkNotNull(callbacks.poll(5,TimeUnit.SECONDS)).invoke()
            assertEquals(listOf("read-start","read-consumed","policy","quick"),events.toList());assertEquals(90,second.refresh.confirmed)
        }finally{release.countDown();first.close();second.close()}
    }
    @Test fun selectedAppProjectionUpdatesCleanDetailAndPreservesDirtyCas() {
        val session=FrontendSession(FakeFrontendGateway(),0,Executors.newSingleThreadExecutor()){}
        try {
            val old=ZuiControlClient.AppPolicyDraft("org.test.app",120,"balance",ZuiControlClient.GpuPolicy.DEFAULT_FOR_MODE,10)
            session.selected=old.packageName;session.appDraft=old;session.originalApp=old
            val fresh=old.copy(refreshHz=90,uperfMode="fast",gpuPolicy=ZuiControlClient.GpuPolicy.CUSTOM,expectedGeneration=11,gpuMinMHz=422,gpuMaxMHz=903)
            fun projection(d:ZuiControlClient.AppPolicyDraft)=ZuiControlClient.AppPolicies(0,d.expectedGeneration,listOf(ZuiControlClient.AppPolicyRead(d,422,903)))
            session.observeAppPolicies(projection(fresh));assertEquals(fresh,session.appDraft);assertEquals(fresh,session.originalApp);assertFalse(session.appDirty)
            val local=fresh.copy(refreshHz=60);session.appDraft=local
            session.observeAppPolicies(projection(fresh.copy(expectedGeneration=12,refreshHz=165)))
            assertEquals(local,session.appDraft);assertEquals(fresh,session.originalApp);assertEquals(12L,session.appAuthorityGeneration)
            assertEquals(11L,session.appDraft!!.expectedGeneration);assertTrue(session.appDirty)
            session.observeAppPolicies(projection(old));assertEquals(12L,session.appAuthorityGeneration)
        }finally{session.close()}
    }
    private fun gpuState(generation:Long=458) = "ok=1\npolicyGeneration=$generation\n" +
        GpuDefaultsDraft.modes.joinToString("\n") { "gpuGlobal=0|$it|231|903" }
    @Test fun gpuSameContextNavigationPreservesCleanAndDirtyModels() {
        val gateway=FakeFrontendGateway();val session=FrontendSession(gateway,0,Executors.newSingleThreadExecutor()){}
        try {
            assertTrue(session.changeContext("settings",1))
            val d=session.gpuDraftFrom(gpuState());assertSame(d,session.gpuDraft)
            assertFalse(session.changeContext("settings"));assertFalse(session.changeContext("settings",1))
            assertSame(d,session.gpuDraft);assertFalse(d.dirty)
            d.set("powersave",GpuRanges.Range(231,422));assertTrue(session.dirty)
            assertFalse(session.changeContext("settings"));assertFalse(session.changeContext("settings",1))
            assertSame(d,session.gpuDraft);assertEquals(GpuRanges.Range(231,422),d.ranges.getValue("powersave"))
            FrontendTheme.dark("dark",false);session.returnHome() // Theme and genuine background do not own drafts.
            assertSame(d,session.gpuDraft);assertTrue(d.dirty);assertEquals(0,gateway.calls)
        }finally{session.close()}
    }
    @Test fun gpuModelReconciliationUsesLatestAuthorityAndNeverOverwritesDirtyRanges() {
        val session=FrontendSession(FakeFrontendGateway(),0,Executors.newSingleThreadExecutor()){}
        try {
            session.changeContext("settings",1);val d=session.gpuDraftFrom(gpuState())
            d.set("powersave",GpuRanges.Range(231,422))
            session.observeGpuDefaults(GpuDefaultsDraft.fromState(gpuState(459),0))
            assertSame(d,session.gpuDraftFrom(gpuState(459)));assertEquals(458L,d.expectedGeneration);assertTrue(d.dirty)
            session.gpuDraft=null // Defensive lost-model path; retained authority stays newer than Activity.state.
            val recovered=session.gpuDraftFrom(gpuState(458))
            assertEquals(459L,recovered.expectedGeneration);assertFalse(recovered.dirty)
            assertThrows(IllegalStateException::class.java){GpuDefaultsDraft.fromState("ok=0",0)}
        }finally{session.close()}
    }
    @Test fun realGpuContextLeaveCancelsDiscardsOrCommitsBeforeNavigation() {
        val gateway=FakeFrontendGateway().apply{succeed=true;authoritativeGeneration=458}
        val callbacks=LinkedBlockingQueue<()->Unit>();val session=FrontendSession(gateway,0,Executors.newSingleThreadExecutor()){callbacks.put(it)}
        try {
            session.changeContext("settings",1);val d=session.gpuDraftFrom(gpuState());d.restoreDefaults()
            assertTrue(d.dirty);assertEquals(0,gateway.calls)
            assertFalse(session.sameContext("settings",2)) // Cancel does not call the transition.
            assertSame(d,session.gpuDraft);assertTrue(session.dirty)
            session.saveGpu{session.changeContext("settings",2)}
            assertEquals(1,session.settingsModule);checkNotNull(callbacks.poll(5,TimeUnit.SECONDS)).invoke()
            assertEquals(2,session.settingsModule);assertNull(session.gpuDraft);assertEquals(1,gateway.calls)
            assertEquals(458L,gateway.generation);assertEquals(GpuDefaultsDraft.modes.map(GpuRanges::default),gateway.gpu)
            session.changeContext("settings",1);session.gpuDraftFrom(gpuState(459)).restoreDefaults()
            session.clearDrafts();session.changeContext("settings",0) // Explicit Discard, then transition.
            assertNull(session.gpuDraft);assertEquals(0,session.settingsModule);assertEquals(1,gateway.calls)
        }finally{session.close()}
    }
    /** Real pending-identity/ACK predicate; the platform transport is a counted boundary. */
    private fun twoSavesWithRead(enqueue:(FrontendSession,()->Unit)->Unit,blocked:Boolean) {
        val owner=FakeFrontendGateway().apply{succeed=true;authoritativeGeneration=365}
        val request=java.util.concurrent.atomic.AtomicReference("")
        val ack=java.util.concurrent.atomic.AtomicReference("")
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val readFinished=java.util.concurrent.CountDownLatch(1);val callbacks=LinkedBlockingQueue<()->Unit>()
        val saveThreads=java.util.Collections.synchronizedList(mutableListOf<Long>())
        var readThread=0L;var terminalAcks=0;var rejected=0;val cas=mutableListOf<Long>()
        val gateway=object:FrontendGateway by owner {
            override fun saveGpuDefaultsAtomic(powersave:GpuRanges.Range,balance:GpuRanges.Range,performance:GpuRanges.Range,fast:GpuRanges.Range,expectedGeneration:Long):ZuiControlClient.Reply {
                saveThreads.add(Thread.currentThread().id)
                if(ZuiControlRequest.hasPendingRequest(request.get(),ack.get())) {
                    rejected++;return ZuiControlClient.Reply(false,"上一条系统命令尚未完成，已重新唤醒系统处理")
                }
                check(expectedGeneration==owner.authoritativeGeneration){"CAS"};cas+=expectedGeneration
                val reply=owner.saveGpuDefaultsAtomic(powersave,balance,performance,fast,expectedGeneration)
                terminalAcks++;return reply
            }
        }
        val session=FrontendSession(gateway,0,Executors.newSingleThreadExecutor()){callbacks.put(it)}
        try {
            session.gpuDraft=GpuDefaultsDraft(365,owner.authoritativeRanges).apply{restoreDefaults()}
            session.saveGpu();checkNotNull(callbacks.poll(5,TimeUnit.SECONDS)).invoke()
            assertFalse(session.busy);assertFalse(session.gpuDraft!!.dirty);assertEquals(366L,session.gpuDraft!!.expectedGeneration)
            enqueue(session) {
                readThread=Thread.currentThread().id
                request.set("read|zo_upstream_read||gd938e249d8a8784dc432585f|metadata:0")
                ack.set("read|running|zo_upstream_read|validating");entered.countDown()
                check(release.await(5,TimeUnit.SECONDS))
                ack.set("read|done|zo_upstream_read|loaded")
                check(!ZuiControlRequest.hasPendingRequest(request.get(),ack.get()))
                // Consumption completes before the next command can replace its slot.
                readFinished.countDown()
            }
            check(entered.await(5,TimeUnit.SECONDS));session.gpuDraft!!.set("powersave",GpuRanges.Range(231,422))
            val dirty=session.gpuDraft!!.ranges.toMap();session.saveGpu()
            if(blocked) {
                checkNotNull(callbacks.poll(5,TimeUnit.SECONDS)).invoke()
                assertEquals(1,rejected);assertTrue(session.gpuDraft!!.dirty);assertEquals(dirty,session.gpuDraft!!.ranges)
                assertEquals(366L,session.gpuDraft!!.expectedGeneration);assertEquals(1,owner.calls)
                assertTrue(session.error.contains("上一条系统命令"))
            } else {
                assertTrue(session.busy);assertNull(callbacks.poll(100,TimeUnit.MILLISECONDS));assertEquals(1,owner.calls)
            }
            release.countDown();check(readFinished.await(5,TimeUnit.SECONDS))
            if(blocked)session.saveGpu()
            checkNotNull(callbacks.poll(5,TimeUnit.SECONDS)).invoke()
            assertFalse(session.busy);assertFalse(session.gpuDraft!!.dirty);assertEquals("",session.error)
            assertEquals(367L,session.gpuDraft!!.expectedGeneration);assertEquals(listOf(365L,366L),cas)
            assertEquals(2,owner.calls);assertEquals(2,terminalAcks);assertEquals(GpuDefaultsDraft.modes.map{dirty.getValue(it)},owner.gpu)
            assertEquals(1,saveThreads.toSet().size)
            if(!blocked){assertEquals(0,rejected);assertEquals(saveThreads.first(),readThread)}
        }finally{release.countDown();session.close()}
    }
    @Test fun independentPageReadProvesExactPendingGateAndRetainsDirtySecondSave() {
        twoSavesWithRead({_,read->Thread(read).start()},true)
    }
    @Test fun gpuImmediateSecondSaveWaitsForPageReadOnExistingQueue() {
        twoSavesWithRead({session,read->session.read(read)},false)
    }
    @Test fun rapidIntentCoalescesWithoutOverlappingActionsAndAckConfirmsCapturedValue() {
        val callbacks=LinkedBlockingQueue<()->Unit>()
        val session=FrontendSession(FakeFrontendGateway(),0,Executors.newSingleThreadExecutor()){callbacks.put(it)}
        val submitted=java.util.Collections.synchronizedList(mutableListOf<Int>())
        val control=OptimisticControl(120)
        val action:(Int)->ZuiControlClient.Reply={ submitted.add(it);ZuiControlClient.Reply(true,"ACK") }
        try{
            session.intent(control,60,action);session.intent(control,120,action);session.intent(control,90,action)
            assertEquals(90,control.displayed);assertEquals(120,control.confirmed);assertEquals(60,control.inFlight)
            checkNotNull(callbacks.poll(5,TimeUnit.SECONDS)).invoke()
            assertEquals(60,control.confirmed);assertEquals(90,control.inFlight)
            checkNotNull(callbacks.poll(5,TimeUnit.SECONDS)).invoke()
            assertEquals(listOf(60,90),submitted.toList());assertEquals(90,control.confirmed);assertFalse(control.pending)
            val mode=OptimisticControl("balance");val modes=mutableListOf<String>()
            val change:(String)->ZuiControlClient.Reply={ modes+=it;ZuiControlClient.Reply(true,"ACK") }
            session.intent(mode,"performance",change);session.intent(mode,"balance",change)
            assertEquals("balance",mode.displayed);assertEquals("performance",mode.inFlight)
            checkNotNull(callbacks.poll(5,TimeUnit.SECONDS)).invoke();checkNotNull(callbacks.poll(5,TimeUnit.SECONDS)).invoke()
            assertEquals(listOf("performance","balance"),modes);assertEquals("balance",mode.confirmed)
        }finally{session.close()}
    }
    @Test fun closingUiDrainsAlreadyRequestedLatestIntentWithoutExecutorRejection(){
        val callbacks=LinkedBlockingQueue<()->Unit>();val session=FrontendSession(FakeFrontendGateway(),0,Executors.newSingleThreadExecutor()){callbacks.put(it)}
        val control=session.refresh.apply{observe(120)};val sent=mutableListOf<Int>();val action:(Int)->ZuiControlClient.Reply={sent+=it;ZuiControlClient.Reply(true,"ACK")}
        session.intent(control,60,action);session.intent(control,90,action);session.close()
        checkNotNull(callbacks.poll(5,TimeUnit.SECONDS)).invoke();checkNotNull(callbacks.poll(5,TimeUnit.SECONDS)).invoke()
        assertEquals(listOf(60,90),sent);assertEquals(90,control.confirmed);assertFalse(control.pending)
    }
    @Test fun localAppFieldsNeverWriteBeforeOneCompleteSave() {
        val gateway=FakeFrontendGateway().apply{succeed=true};val callbacks=LinkedBlockingQueue<()->Unit>()
        val session=FrontendSession(gateway,0,Executors.newSingleThreadExecutor()){callbacks.put(it)}
        try{
            session.appDraft=ZuiControlClient.AppPolicyDraft("org.example.game",120,"balance",ZuiControlClient.GpuPolicy.DEFAULT_FOR_MODE,42)
            session.originalApp=session.appDraft
            repeat(5){session.appDraft=session.appDraft!!.copy(refreshHz=listOf(60,90,120)[it%3])}
            repeat(5){session.appDraft=session.appDraft!!.copy(uperfMode=GpuDefaultsDraft.modes[it%4])}
            session.appDraft=session.appDraft!!.copy(gpuPolicy=ZuiControlClient.GpuPolicy.CUSTOM,gpuMinMHz=310,gpuMaxMHz=903)
            assertEquals(0,gateway.calls);assertTrue(session.appDirty)
            val complete=session.appDraft;session.saveApp();checkNotNull(callbacks.poll(5,TimeUnit.SECONDS)).invoke()
            assertEquals(1,gateway.calls);assertEquals(complete,gateway.app);assertFalse(session.appDirty);assertEquals(43L,session.originalApp!!.expectedGeneration)
        }finally{session.close()}
    }
    private class FakeFrontendGateway : FrontendGateway {
        var calls = 0
        var succeed = false
        var gpu: List<GpuRanges.Range>? = null
        var generation = -1L
        var app: ZuiControlClient.AppPolicyDraft? = null
        var authoritativeGeneration = 84L
        var readFailure = false
        var staleRead = false
        var authoritativeRanges = GpuDefaultsDraft.modes.associateWith { GpuRanges.Range(231,903) }
        override fun readGpuDefaults(user: Int): GpuDefaultsDraft {
            check(!readFailure) { "READ_UNAVAILABLE" }
            return GpuDefaultsDraft(if(staleRead)generation else authoritativeGeneration, authoritativeRanges)
        }
        override fun saveGpuDefaultsAtomic(powersave: GpuRanges.Range, balance: GpuRanges.Range,
            performance: GpuRanges.Range, fast: GpuRanges.Range, expectedGeneration: Long): ZuiControlClient.Reply {
            calls++; gpu = listOf(powersave, balance, performance, fast); generation = expectedGeneration
            if(succeed){authoritativeGeneration=maxOf(authoritativeGeneration,expectedGeneration+1);authoritativeRanges=GpuDefaultsDraft.modes.zip(checkNotNull(gpu)).toMap()}
            return ZuiControlClient.Reply(succeed, if (succeed) "ACK" else "GENERATION_MISMATCH")
        }
        override fun saveAppPolicy(draft: ZuiControlClient.AppPolicyDraft): ZuiControlClient.Reply {
            calls++; app = draft; return ZuiControlClient.Reply(succeed, if (succeed) "ACK" else "REFUSED")
        }
        override fun readAppPolicy(packageName: String) = if(succeed)app?.copy(expectedGeneration=app!!.expectedGeneration+1)else null
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
        assertFalse(control.begin(60)); control.observe(90); assertEquals(60, control.displayed)
        control.finish(false); assertFalse(control.pending); assertEquals(60, control.displayed)
        control.begin(60);control.finish(false);assertEquals(120,control.displayed)
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
    @Test fun postAckSameGenerationIsStaleEvenWhenReturnedRangesMatch() {
        val gateway=FakeFrontendGateway().apply{succeed=true;staleRead=true}
        val callbacks=LinkedBlockingQueue<()->Unit>();val session=FrontendSession(gateway,0,Executors.newSingleThreadExecutor()){callbacks.put(it)}
        try{
            val draft=GpuDefaultsDraft(359,gateway.authoritativeRanges).apply{restoreDefaults()};session.gpuDraft=draft
            session.saveGpu();checkNotNull(callbacks.poll(5,TimeUnit.SECONDS)).invoke()
            assertSame(draft,session.gpuDraft);assertTrue(draft.dirty);assertEquals("",session.notice);assertEquals("GPU_POST_ACK_READ_STALE",session.error)
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
            assertEquals(edited.copy(expectedGeneration=43), session.originalApp); assertFalse(session.appDirty)
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
    @Test fun themePreservesOpaqueAndTranslucentWhiteControlInkWhenCardPaletteChanges() {
        val recolor:(Int)->Int={0xff111927.toInt()}
        assertEquals(0xffffffff.toInt(),ownerForegroundColor(0xffffffff.toInt(),recolor))
        assertEquals(0xd9ffffff.toInt(),ownerForegroundColor(0xd9ffffff.toInt(),recolor))
        assertEquals(0xd1ffffff.toInt(),ownerForegroundColor(0xd1ffffff.toInt(),recolor))
        assertEquals(0xff111927.toInt(),ownerForegroundColor(0xff475569.toInt(),recolor))
    }
    @Test fun genuineBackgroundReturnsToSectionHomeWithoutDiscardingEitherDraft() {
        val gateway=FakeFrontendGateway();val session=FrontendSession(gateway,0,Executors.newSingleThreadExecutor()){}
        try {
            val saved=ZuiControlClient.AppPolicyDraft("org.example.one",120,"balance",ZuiControlClient.GpuPolicy.DEFAULT_FOR_MODE,42)
            session.section="tune";session.selected=saved.packageName;session.originalApp=saved;session.appDraft=saved.copy(refreshHz=165)
            val edited=session.appDraft;session.returnHome()
            assertEquals("tune",session.section);assertEquals("",session.selected);assertSame(edited,session.appDraft);assertTrue(session.appDirty)
            assertTrue(session.hasDraftFor("tune",saved.packageName));assertFalse(session.hasDraftFor("tune","org.example.two"));assertEquals(0,gateway.calls)
            val p=ZuioptRuleModel.Profile(setOf(0,1),emptyList());val base=ZuioptRuleModel(true,emptyMap(),emptyList())
            val rule=RuleDraft(saved.packageName,base,"generation",p,p.copy(generalMask=setOf(7)))
            session.ruleDraft=rule;session.section="thread";session.selected=saved.packageName;session.analysisPage=true;session.returnHome()
            assertEquals("thread",session.section);assertEquals("",session.selected);assertFalse(session.analysisPage);assertSame(rule,session.ruleDraft);assertTrue(rule.dirty)
            assertTrue(session.hasDraftFor("thread",saved.packageName));assertEquals(0,gateway.calls)
        }finally{session.close()}
    }
    @Test fun foregroundTrackerExcludesInternalActivitiesConfigurationAndOwnedExternalFlows() {
        val foreground=FrontendForegroundState()
        foreground.start();foreground.start()
        assertFalse(foreground.stop(false,false)) // Main -> internal record Activity.
        assertTrue(foreground.stop(false,false)) // Last internal Activity -> Home.
        foreground.start();assertFalse(foreground.stop(true,false))
        foreground.start();assertFalse(foreground.stop(false,true)) // SAF/settings.
        foreground.start();assertTrue(foreground.stop(false,false))
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
