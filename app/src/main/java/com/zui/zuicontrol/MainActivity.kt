package com.zui.zuicontrol

import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import android.zui.ZuiControlManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Native V3 shell; all applied facts come from the existing owners. */
class MainActivity : Activity() {
    private lateinit var session: FrontendSession
    private lateinit var owner: OwnerUi
    private lateinit var ownerHost: FrameLayout
    private lateinit var physicalHost: FrameLayout
    private lateinit var ownerCanvas: OwnerDesignLayout
    private lateinit var shellRoot:LinearLayout
    private lateinit var rail:LinearLayout
    private lateinit var pageHost:FrameLayout
    private var masterSection=""
    private var masterFingerprint=""
    private var masterSubtitle:TextView?=null
    private var masterList:LinearLayout?=null
    private val railBindings=mutableListOf<()->Unit>()
    private val masterSelectionBindings=mutableListOf<()->Unit>()
    private val pageBindings=mutableListOf<()->Unit>()
    private val controlBindings=mutableMapOf<Any,MutableList<()->Unit>>()
    private fun bindPresentation(bind:()->Unit){pageBindings+=bind;bind()}
    private fun bindControl(control:Any,bind:()->Unit){controlBindings.getOrPut(control){mutableListOf()}+=bind;bindPresentation(bind)}
    private fun rebindControl(control:Any){controlBindings[control]?.toList()?.forEach{it()};OwnerRenderTrace.event("CONTROL_PRESENTATION_REBOUND",when(control){refresh->"refresh";mode->"uperf";else->"overlay"})}
    private val cpuSparklines=OwnerCpuSparklineLoader()
    private var ownerModal: OwnerModal? = null
    private var ownerQuiet: OwnerMeter? = null
    private var ownerPower: OwnerMeter? = null
    private var ownerSwitch: View? = null
    private var shownPage = ""
    private var shownToast = ""
    private var toastView: View? = null
    private val ownerControls=mutableListOf<View>()
    private fun <T:View> ownerControl(view:T):T {ownerControls+=view;return view}
    private fun ownerPending() {
        fun disable(v:View){v.isEnabled=false;if(v is ViewGroup)for(i in 0 until v.childCount)disable(v.getChildAt(i))}
        ownerControls.forEach{disable(it);it.alpha=.6f}
        fun visit(v:View){
            if(v is OwnerSegment || v is GpuRangeBar || v.tag=="owner-control" || v.tag=="owner-button"){
                disable(v);v.alpha=if(v.tag=="owner-button").4f else .6f
            }else if(v is ViewGroup)for(i in 0 until v.childCount)visit(v.getChildAt(i))
        }
        if(::detail.isInitialized)visit(detail)
    }
    private val phaseOne get() = session.section == "tune" || session.section == "settings" && session.settingsModule == 1
    private val renderDraft = Runnable { if (visible && !isDestroyed) render() }
    private fun localRender() { handler.removeCallbacks(renderDraft); handler.postDelayed(renderDraft, 16) }

    private val handler = Handler(Looper.getMainLooper())
    private val prefs get() = getSharedPreferences("frontend", MODE_PRIVATE)
    private lateinit var master: LinearLayout
    private lateinit var detail: LinearLayout
    private var state = ""
    private var caps = ""
    private var policies: ZuiControlClient.AppPolicies? = null
    private var snapshot: ZuioptRules.Snapshot? = null
    private var model: ZuioptRuleModel? = null
    private var baseline: ZuioptLibrary.Baseline? = null
    private var upstreamModel: ZuioptRuleModel? = null
    private var ruleState = ""
    private var ruleError = ""
    private var records = JSONArray()
    private var installed = emptyList<ApplicationInfo>()
    private var appMetadata=emptyMap<String,FrontendPackages.Entry>()
    private var inventoryVersion=-1L
    private var inventoryReady:(()->Unit)?=null
    private val inventoryChanged:()->Unit={configurableApps.clear();if(visible)loadInventory()}
    private val configurableApps=mutableMapOf<String,Boolean>()
    private var query = ""
    private var filter = 0
    private var latest get() = session.latest; set(v) { session.latest = v }
    private var updateStatus get() = session.updateStatus; set(v) { session.updateStatus = v }
    private var preview get() = session.preview; set(v) { session.preview = v }
    private var incoming get() = session.incoming; set(v) { session.incoming = v }
    private var source get() = session.source; set(v) { session.source = v }
    private var appOpt get() = session.appOpt; set(v) { session.appOpt = v }
    private val decisions get() = session.decisions
    private var manual get() = session.manual; set(v) { session.manual = v }
    private var monitor = JSONObject()
    private val monitorChanged: (JSONObject?) -> Unit = { next ->
        if (visible) acceptMonitor(next ?: JSONObject())
    }
    private var recordsReadAt = 0L
    private val recordClock = object : Runnable {
        override fun run() {
            bindMonitor()
            if (visible && monitor.optString("recordState") == "RECORDING") handler.postDelayed(this, 1000)
        }
    }
    private var visible = false
    private var quietLabel: TextView? = null
    private var powerLabel: TextView? = null
    private var powerReason: TextView? = null
    private var powerUnit: TextView? = null
    private var recordLabel: TextView? = null
    private var recordBanner: LinearLayout? = null
    private var overlayButton: TextView? = null
    private var quietMeter: ProgressBar? = null
    private var powerMeter: ProgressBar? = null
    private var coreLabel: TextView? = null
    private var coreSlot:FrameLayout?=null
    private var coreIndex = 0
    private val coreTicker = object : Runnable {
        override fun run() {
            if (!visible || coreLabel == null) return
            val bad = BackendHealth.components(state).filter { it.state in setOf(BackendHealth.State.FAILED, BackendHealth.State.DEGRADED) }
            if (bad.size < 2) return
            coreIndex = (coreIndex + 1) % bad.size
            coreLabel?.let{old->coreSlot?.let{slot->
                val next=owner.label(bad[coreIndex].component,20f,if(bad[coreIndex].state==BackendHealth.State.FAILED)owner.inks[3] else owner.inks[2],800)
                next.translationY=owner.px(32).toFloat();next.alpha=0f;slot.addView(next,FrameLayout.LayoutParams(-1,-1));coreLabel=next
                old.animate().translationY(-owner.px(32).toFloat()).alpha(0f).setDuration(450).setInterpolator(OwnerUi.smooth).withEndAction{slot.removeView(old)}.start()
                next.animate().translationY(0f).alpha(1f).setDuration(450).setInterpolator(OwnerUi.smooth).start()
            }}
            handler.postDelayed(this, 2000)
        }
    }
    private val refresh get() = session.refresh
    private val mode get() = session.mode
    private val overlay get() = session.overlay
    private var exportBytes get() = session.exportBytes; set(v) { session.exportBytes = v }
    private var backupBytes get() = session.backupBytes; set(v) { session.backupBytes = v }
    private var selectedRecord: JSONObject? = null
    private var selectedThread:JSONObject?=null
    private var rawPage=false
    private var recordRequested = false
    private var recordThreads = false
    private var recordEpoch=0L
    private var recordDetailLoading=false
    private var threadDetailLoading=false
    private var analysis: JSONObject? = null
    private var analysisError = ""
    private var analysisRead = 0
    private var analysisPage get()=session.analysisPage;set(v){session.analysisPage=v}
    private var recordLaunch:Intent?=null
    private var boundGeneration = ""
    private var appPoliciesRevision=""
    private var gpuDefaultsRevision=""
    private val controlsChanged: () -> Unit = {
        val oldRefresh=refresh.displayed;val oldMode=mode.displayed
        observeGlobals()
        if(visible){if(oldRefresh!=refresh.displayed)rebindControl(refresh);if(oldMode!=mode.displayed)rebindControl(mode)}
        val generation = value(ControlsState.snapshot, "policyGeneration")
        if (visible && generation.isNotEmpty() && generation != boundGeneration) {
            OwnerRenderTrace.event("POLICY_GENERATION_CALLBACK", generation)
            val apps=value(ControlsState.snapshot,"appPoliciesRevision")
            val gpu=value(ControlsState.snapshot,"gpuDefaultsRevision")
            if(apps!=appPoliciesRevision){appPoliciesRevision=apps;loadPolicies()}
            else if(apps.matches(Regex("[0-9a-f]{64}")))generation.toLongOrNull()?.let{next->
                session.observeUnchangedAppGeneration(next)
                policies=policies?.takeIf{it.generation<=next}?.let{old->old.copy(generation=next,apps=old.apps.map{row->row.copy(draft=row.draft.copy(expectedGeneration=next))})} ?: policies
            }
            if(gpu!=gpuDefaultsRevision){gpuDefaultsRevision=gpu;if(session.section=="settings" && session.settingsModule==1)loadState()}
            boundGeneration=generation
        }
    }
    private val changed: () -> Unit = {
        if (visible && !isDestroyed) { render(); if (!session.busy) presentPending() }
    }
    private val reconciled:(String)->Unit={feature->
        if(visible && !isDestroyed)when(feature){
            "appPolicy"->loadPolicies()
            "gpuDefaults"->loadState()
            "rules"->loadRules()
            "records"->loadRecords()
            "restore"->{loadPolicies();loadRules();if(session.section=="settings" && session.settingsModule==1)loadState()}
            "diagnostics"->loadState()
        }
    }
    private val accent get() = owner.accent
    private val ink get() = owner.text
    private val sub get() = owner.sub
    private val field get() = owner.card2
    private val surface get() = owner.card
    private val orange get() = owner.inks[2]

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        owner=OwnerUi(this)
        var createdSession=false
        session = FrontendForeground.obtain(application,ZuiControlClient.currentUserId()) {
            createdSession=true
            (lastNonConfigurationInstance as? FrontendSession)?.takeIf { it.userId == ZuiControlClient.currentUserId() }
                ?: FrontendSession(V84FrontendGateway(applicationContext), ZuiControlClient.currentUserId())
        }
        if (createdSession && lastNonConfigurationInstance == null && saved != null) session.restore(saved)
        session.onQueueEvent = { kind, detail -> OwnerRenderTrace.event(kind, detail) }
        if(intent.getBooleanExtra("openRecord",false))recordLaunch=intent
        OwnerWindow.fullscreen(this)
        val appContext = applicationContext
        session.read { runCatching { ZuiControlRequest.recoverPending(appContext) } }
        render()
    }
    override fun onRetainNonConfigurationInstance(): Any = session
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);if(intent.getBooleanExtra("openRecord",false)){recordLaunch=intent;if(visible)load()}}
    override fun onSaveInstanceState(out: Bundle) { session.save(out); super.onSaveInstanceState(out) }
    override fun onResume() {
        super.onResume(); visible = true; session.onChanged = changed;session.onReconciled=reconciled; session.onControlsChanged = { control->if(visible){rebindControl(control);if(session.error.isNotEmpty())render()} }
        session.ownedExternalFlow=false
        FrontendPackages.observe(applicationContext,inventoryChanged)
        render();ControlsState.observe(controlsChanged); MonitorPresentation.observe(monitorChanged); load()
        if(session.section=="monitor" && recordThreads && selectedThread==null)selectedRecord?.let{record->
            val targets=mutableListOf<Pair<String,OwnerCpuSparklineView>>()
            fun visit(view:View){if(view is OwnerCpuSparklineView)targets+=view.tag.toString() to view;else if(view is ViewGroup)for(i in 0 until view.childCount)visit(view.getChildAt(i))}
            visit(detail);cpuSparklines.show(record,targets)
        }
        ZuiControlQuickService.start(this)
        presentPending()
    }
    override fun onPause() {
        visible = false; session.onChanged = null;session.onReconciled=null; session.onControlsChanged = null; ControlsState.remove(controlsChanged)
        MonitorPresentation.remove(monitorChanged)
        FrontendPackages.remove(inventoryChanged)
        handler.removeCallbacks(recordClock); handler.removeCallbacks(coreTicker); super.onPause()
        cpuSparklines.cancel()
    }
    override fun onDestroy() { cpuSparklines.close(); super.onDestroy() }
    private val loading = mutableSetOf<String>()
    /** Each operation consumes its own transport result and presents on the main thread. */
    private fun <T,R> read(name:String, shared:Boolean=false, accept:()->Boolean={true}, finished:()->Unit={}, fetch:()->T, parse:(T)->R, present:(R)->Unit) {
        if(!loading.add(name))return
        val queued=SystemClock.elapsedRealtimeNanos()
        val task={
            val start=SystemClock.elapsedRealtimeNanos();var fetched=start;var parsed=start
            val result=runCatching{val raw=fetch();fetched=SystemClock.elapsedRealtimeNanos();parse(raw).also{parsed=SystemClock.elapsedRealtimeNanos()}}
            handler.post {
                loading.remove(name)
                if(!isDestroyed && accept()) {
                    val presentationStart=SystemClock.elapsedRealtimeNanos()
                    finished()
                    result.mapCatching{present(it)}.onFailure{session.error=it.message.orEmpty();if(visible)render()}
                    OwnerRenderTrace.event("READ_TIMING",JSONObject().put("operation",name).put("lane",if(shared)"SHARED_COMMAND_LANE" else "DIRECT_READ_LANE")
                        .put("queue_wait_ms",(start-queued)/1e6).put("binder_or_command_ms",(fetched-start)/1e6)
                        .put("parse_ms",(parsed-fetched)/1e6).put("presentation_ms",(SystemClock.elapsedRealtimeNanos()-presentationStart)/1e6).toString())
                }
            }
            Unit
        }
        try { if(shared)session.read(task)else session.directRead(task) }
        catch(_:java.util.concurrent.RejectedExecutionException){loading.remove(name);finished();session.error="读取请求过多，请重试";if(visible)render()}
    }
    private fun loadState() = read("state",fetch={ZuiControlClient.stateText()},parse={it}) { fresh ->
        check(fresh.startsWith("ok=1")){fresh};state=fresh
        runCatching{GpuDefaultsDraft.fromState(state,session.userId)}.onSuccess(session::observeGpuDefaults)
        if(visible)render()
    }
    private fun loadInventory(ready:(()->Unit)?=null) {
        if(ready!=null)inventoryReady=ready
        if(inventoryVersion==FrontendPackages.version){inventoryReady?.also{inventoryReady=null;it()};return}
        read("packageInventory",fetch={FrontendPackages.read(applicationContext)},parse={it}) { fresh ->
            inventoryVersion=fresh.version;appMetadata=fresh.entries.associateBy{it.info.packageName};installed=fresh.entries.map{it.info}
            if(visible)render()
            inventoryReady?.also{inventoryReady=null;it()}
            if(inventoryVersion!=FrontendPackages.version)loadInventory()
        }
    }
    private fun loadAppMetadata(pkg:String) {
        loadInventory()
        if(pkg in configurableApps)return
        read("appMetadata/$pkg",fetch={UperfAppPolicy.isConfigurable(packageManager,pkg)},parse={it}){
            configurableApps[pkg]=it;if(session.section=="tune" && session.selected==pkg){shownPage="";if(visible)render()}
        }
    }
    private fun loadPolicies():Unit = read("appPolicies",fetch={ZuiControlClient.utilityValue("appPolicies","")},parse=ZuiControlClient::parseAppPolicies) { fresh ->
        if(fresh.generation >= (policies?.generation ?: 0)) {
            policies=fresh;boundGeneration=fresh.generation.toString()
            session.observeAppPolicies(fresh)
            if(fresh.apps.isNotEmpty() && inventoryVersion<0)loadInventory()
            if(visible)render()
            OwnerRenderTrace.event("APPPOLICIES_MASTER_REBOUND",fresh.generation.toString())
        }
        if(visible && value(ControlsState.snapshot,"policyGeneration").toLongOrNull()?.let{it>fresh.generation}==true)loadPolicies()
    }
    private fun loadRules() = read("rules",shared=true,fetch={
        val rs=ZuioptRules.state(applicationContext)
        val generation=ZuioptRules.field(rs,"generation")
        if(snapshot?.generation==generation && baseline?.generation==generation)Triple(rs,snapshot!!,baseline!!)
        else {
            val current=ZuioptRules.userRules(applicationContext);val up=ZuioptLibrary.baseline(applicationContext)
            check(current.generation==up.generation){"规则版本变化，请刷新"};Triple(rs,current,up)
        }
    },parse={Triple(it.first,it.second to ZuioptRuleModel.parseNormalized(it.second.text),it.third to ZuioptRuleModel.parseNormalized(it.third.rules))}) { fresh ->
        val changed=snapshot?.generation!=fresh.second.first.generation
        ruleState=fresh.first;snapshot=fresh.second.first;model=fresh.second.second;baseline=fresh.third.first;upstreamModel=fresh.third.second;ruleError=""
        if(session.section=="thread" && session.selected.isNotEmpty() && session.ruleDraft==null)openRule(session.selected)
        if(changed && session.section=="thread")shownPage=""
        if(visible)render()
    }
    private fun loadRecords() = read("recordList",fetch={PerformanceMonitor.command("recordList")},parse={JSONObject(it).optJSONArray("records") ?: JSONArray()}) { fresh ->
        records=fresh;recordsReadAt=SystemClock.elapsedRealtime()
        if(session.section=="monitor") {
            val requested=recordLaunch;recordLaunch=null
            val explicit=requested?.getStringExtra("package").orEmpty()
            val available=(0 until records.length()).map{records.getJSONObject(it).getString("package")}
            val target=when{explicit in available -> explicit;session.selected in available ->session.selected;else->available.firstOrNull().orEmpty()}
            if(session.selected!=target){recordEpoch++;session.selected=target;selectedRecord=null;recordRequested=false;selectedThread=null;recordThreads=false;recordDetailLoading=false;threadDetailLoading=false}
            if(target.isNotEmpty() && (!recordRequested || requested!=null))readRecord(target,requested?.getBooleanExtra("threads",false) ?: false){
                requested?.getStringExtra("thread")?.takeIf{it.isNotEmpty()}?.let{readThread(it,requested.getStringExtra("name").orEmpty())}
            }
        }
        if(visible)render()
    }
    private fun load() {
        recordLaunch?.let{guard{session.clearDrafts();session.section="monitor";session.selected="";selectedRecord=null;recordRequested=false;selectedThread=null;recordThreads=false;analysisPage=false;rawPage=false;render();loadRecords()};return}
        when(session.section){
            "tune"->{loadState();loadPolicies();loadCapabilities()}
            "thread"->{loadInventory();loadRules()}
            "monitor"->loadRecords()
            "settings"->when(session.settingsModule){1->loadState();3->loadCapabilities()}
        }
    }
    private fun loadCapabilities() {if(caps.isEmpty())read("capabilities",fetch={checkNotNull(ZuiControlManager.get()).getCapabilities()},parse={it}){caps=it;if(visible)render()}}
    private fun observeGlobals() {
        val scene = ControlsState.snapshot
        value(scene, "savedGlobalRefresh").toIntOrNull()?.takeIf{it in supportedRates()}?.let(refresh::observe)
        val savedMode = value(scene, "savedGlobalUperf")
        if (savedMode in GpuDefaultsDraft.modes) mode.observe(savedMode)
    }
    private fun acceptMonitor(next: JSONObject) {
        val recordingChanged = next.optString("recordState") != monitor.optString("recordState")
        monitor = next; overlay.observe(next.optInt("mode") == 1); bindMonitor()
        if (recordingChanged) { analysisRead++; analysis = null; analysisError = "" }
        if (recordingChanged && session.section == "monitor") { selectedRecord = null; recordRequested = false }
        handler.removeCallbacks(recordClock)
        if (next.optString("recordState") == "RECORDING") handler.postDelayed(recordClock, 1000)
        if (recordingChanged && session.section=="monitor") loadRecords()
    }
    private fun bindMonitor() {
        val fresh = monitor.optLong("elapsedMs") > 0 && SystemClock.elapsedRealtime() - monitor.optLong("elapsedMs") in 0..monitor.optLong("ttlMs", 3500)
        val q = if (fresh) monitor.optDouble("quietC", -1.0) else -1.0
        val p = if (fresh) monitor.optDouble("powerW", -1.0) else -1.0
        quietLabel?.apply { val value=number(q);if(text.toString()!=value)text=value }
        powerLabel?.apply { val value=if(p<0)"--" else number(p);if(text.toString()!=value)text=value }
        ownerQuiet?.apply { tone = if (q >= 45) owner.tiers[3] else if (q >= 40) owner.tiers[2] else owner.accent; setFraction(if(q < 0) 0f else ((q-20)/30).toFloat()) }
        ownerPower?.apply { tone = if (p >= 14) owner.tiers[3] else if (p >= 7) owner.tiers[2] else owner.accent; setFraction(if(p < 0) 0f else (p/20).toFloat()) }
        ownerSwitch?.animate()?.translationX(owner.px(if(overlay.displayed)18 else 0).toFloat())?.setDuration(300)?.setInterpolator(OwnerUi.spring)?.start()
        quietMeter?.progress = if (q > 0) (q / 55 * 100).toInt().coerceIn(0, 100) else 0
        powerMeter?.progress = if (p > 0) (p / 20 * 100).toInt().coerceIn(0, 100) else 0
        quietLabel?.setTextColor(if (q >= 45) owner.inks[3] else if (q >= 40) owner.inks[2] else owner.text)
        powerLabel?.setTextColor(if (p >= 14) owner.inks[3] else if (p >= 7) owner.inks[2] else owner.text)
        val plugged=fresh && monitor.optInt("batteryPlugged",-1)>0
        powerUnit?.visibility=if(p>=0)View.VISIBLE else View.GONE
        powerReason?.text = if(plugged) "插电中 · 不显示功耗" else if(p < 0) "不可用" else ""
        powerReason?.contentDescription=if(p < 0) "功耗不可用 · ${monitor.optString("powerValidity", "等待数据")}" else "设备电池侧功耗"
        overlayButton?.text = if (this@MainActivity.overlay.displayed) "已开启" else "已关闭"
        recordBanner?.visibility = if (monitor.optString("recordState") == "RECORDING") View.VISIBLE else View.GONE
        val active = (0 until records.length()).map { records.getJSONObject(it) }.firstOrNull { it.optBoolean("active") }
        val elapsed = if (active != null) active.optLong("duration") + (SystemClock.elapsedRealtime() - recordsReadAt).coerceAtLeast(0) else 0
        recordLabel?.text = "正在记录：${active?.optString("label") ?: "当前应用"} · ${duration(elapsed)}"
    }
    private fun createShell() {
        check(!::physicalHost.isInitialized)
        shellRoot=owner.row().apply{setBackgroundColor(owner.detail)}
        rail=owner.column().apply{setPadding(0,owner.px(22),0,owner.px(18));setBackgroundColor(owner.rail);gravity=Gravity.CENTER_HORIZONTAL}
        rail.addView(owner.column().apply{
            gravity=Gravity.CENTER_HORIZONTAL
            addView(owner.label("ZUI",17f,owner.accent,900).apply{letterSpacing=.03f},LinearLayout.LayoutParams(-2,owner.px(17)))
            addView(owner.label("CONTROL",8f,owner.muted,700).apply{letterSpacing=.04f},LinearLayout.LayoutParams(-2,owner.px(8)).apply{topMargin=owner.px(4)})
        },LinearLayout.LayoutParams(-2,owner.px(29)).apply{topMargin=owner.px(6);bottomMargin=owner.px(30)})
        fun nav(key:String,title:String,icon:Int,gap:Int=8,action:()->Unit) {
            val image=owner.icon(icon,owner.muted,20)
            val text=owner.label(title,10.5f,owner.muted,700)
            val cell=owner.column().apply{
                gravity=Gravity.CENTER
                addView(image,LinearLayout.LayoutParams(owner.px(20),owner.px(20)))
                addView(text,LinearLayout.LayoutParams(-2,owner.px(13)).apply{topMargin=owner.px(4)})
                isFocusable=true;contentDescription=title;setOnClickListener{action()}
            }
            val mark=View(this).apply{background=owner.shape(owner.accent,3f)}
            val frame=FrameLayout(this).apply{
                addView(cell,FrameLayout.LayoutParams(owner.px(52),owner.px(52),Gravity.CENTER))
                addView(mark,FrameLayout.LayoutParams(owner.px(3),owner.px(24),Gravity.START or Gravity.CENTER_VERTICAL))
            }
            railBindings+={
                val selected=session.section==key;val color=if(selected)owner.accent else owner.muted
                image.imageTintList=android.content.res.ColorStateList.valueOf(color);text.setTextColor(color);mark.visibility=if(selected)View.VISIBLE else View.INVISIBLE
                mark.background=owner.shape(owner.accent,3f);cell.background=owner.shape(if(selected)owner.soft(owner.accent) else Color.TRANSPARENT,14f)
                if(key=="theme") {text.text=if(owner.dark)"浅色" else "深色";image.setImageResource(if(owner.dark)R.drawable.owner_sun else R.drawable.owner_moon);cell.contentDescription=text.text}
            }
            rail.addView(frame,LinearLayout.LayoutParams(-1,owner.px(52)).apply{bottomMargin=owner.px(gap)})
        }
        fun navigate(key:String){
            if(session.sameContext(key))return
            guard{
            session.changeContext(key);query="";selectedRecord=null;recordRequested=false;recordThreads=false;analysisPage=false
            render();load()
        }}
        nav("tune","调控",R.drawable.owner_tune){navigate("tune")}
        nav("thread","线程",R.drawable.owner_chip){navigate("thread")}
        nav("monitor","监测",R.drawable.owner_pulse){navigate("monitor")}
        rail.addView(View(this),LinearLayout.LayoutParams(1,0,1f))
        nav("theme",if(owner.dark)"浅色" else "深色",if(owner.dark)R.drawable.owner_sun else R.drawable.owner_moon,6){theme(if(owner.dark)"light" else "dark")}
        nav("settings","设置",R.drawable.owner_settings,0){navigate("settings")}
        OwnerWindow.safeContent(rail,22f,18f)
        shellRoot.addView(owner.borderedColumn(rail,72),LinearLayout.LayoutParams(owner.px(72),-1))
        master=owner.column().apply{setBackgroundColor(owner.master)}
        shellRoot.addView(owner.borderedColumn(master,312),LinearLayout.LayoutParams(owner.px(312),-1))
        pageHost=FrameLayout(this).apply{clipChildren=false;clipToPadding=false}
        shellRoot.addView(pageHost,LinearLayout.LayoutParams(0,-1,1f))
        ownerHost=FrameLayout(this).apply{setBackgroundColor(owner.detail);clipToOutline=false;clipChildren=false;clipToPadding=false;addView(shellRoot,FrameLayout.LayoutParams(-1,-1))}
        ownerModal=OwnerModal(owner,ownerHost,shellRoot)
        ownerCanvas=OwnerDesignLayout(this).apply{setBackgroundColor(owner.detail);clipChildren=false;clipToPadding=false;addView(ownerHost);OwnerWindow.inset(this)}
        physicalHost=FrameLayout(this).apply{setBackgroundColor(owner.detail);addView(ownerCanvas,FrameLayout.LayoutParams(-1,-1))}
        setContentView(physicalHost)
        OwnerRenderTrace.construct(physicalHost,ownerCanvas,rail,master,pageHost)
    }
    /** Page changes are local; scalar/control/draft callbacks bind existing views. */
    private fun render() {
        if (ownerModal?.isOpen == true) return
        if(!::physicalHost.isInitialized)createShell()
        railBindings.forEach{it()}
        val listFingerprint=when(session.section){
            "tune"->"$inventoryVersion/${policies?.apps?.map{it.draft.let{d->listOf(d.packageName,d.refreshHz,d.uperfMode,d.gpuPolicy,d.gpuMinMHz,d.gpuMaxMHz)}}}/${if(session.newApp)session.appDraft else null}"
            "thread"->"${snapshot?.generation}/${installed.map{it.packageName}}"
            "monitor"->records.toString()
            else->"settings"
        }
        if(masterSection!=session.section){
            master.removeAllViews();masterSelectionBindings.clear();masterList=null;masterSection=session.section;masterFingerprint=listFingerprint;buildMaster()
            OwnerRenderTrace.event("MASTER_SECTION_CHANGED")
        }else if(masterFingerprint!=listFingerprint){
            masterFingerprint=listFingerprint;masterList?.let{populateList(it)};OwnerRenderTrace.event("MASTER_LIST_CHANGED")
        }
        masterSelectionBindings.forEach{it()}
        masterSubtitle?.text=masterSubtitleText()
        // Reconcile before binding retained controls. A failed read yields the existing
        // non-editable unavailable page; range edits never participate in page identity.
        if(session.section=="settings" && session.settingsModule==1 && session.gpuDraft==null)
            runCatching{session.gpuDraftFrom(state)}
        val page="${session.section}/${session.selected}/${session.settingsModule}/$analysisPage/$recordThreads/${selectedThread?.optString("key")}/$rawPage/${preview?.hashCode()}/${caps.isNotEmpty()}/${session.gpuAuthority!=null}/${session.appDraft!=null}/${session.ruleDraft!=null}/${session.gpuDraft!=null}/${selectedRecord?.optLong("recordId")}/$recordDetailLoading/$threadDetailLoading/${analysisPage && analysis!=null}"
        if(page!=shownPage) {
        shownPage=page;cpuSparklines.cancel();pageBindings.clear();controlBindings.clear()
        // A read completion can interrupt an incoming page at alpha=0. Keep the
        // actually visible outgoing page rather than deleting it for that child.
        val previous=(0 until pageHost.childCount).map{pageHost.getChildAt(it)}.maxByOrNull{it.alpha}
        for(i in pageHost.childCount-1 downTo 0){
            val child=pageHost.getChildAt(i);child.animate().cancel()
            if(child!==previous)pageHost.removeViewAt(i)
        }
        previous?.alpha=1f
        ownerControls.clear()
        handler.removeCallbacks(coreTicker); coreLabel = null;coreSlot=null;quietMeter = null; powerMeter = null
        quietLabel = null; powerLabel = null; powerReason = null; powerUnit = null; recordLabel = null; recordBanner = null; overlayButton = null
        ownerQuiet = null; ownerPower = null; ownerSwitch = null
        detail=owner.column().apply{setPadding(owner.px(22),owner.px(20),owner.px(22),owner.px(20))}
        OwnerWindow.safeContent(detail,20f)
        val error=owner.label(session.error,12f,owner.chipFg[3],600).apply{
            setSingleLine(false);setPadding(owner.px(14),owner.px(10),owner.px(14),owner.px(10));background=owner.shape(owner.chipBg[3],12f)
        };detail.addView(error,owner.gap());bindPresentation{error.text=session.error;error.visibility=if(session.error.isEmpty())View.GONE else View.VISIBLE}
        when(session.section) {
            "tune" -> if(session.selected.isEmpty())dashboard() else appPage()
            "thread" -> if(rawPage)rawPage() else if(preview!=null)updatePage() else if(session.selected.isEmpty())threadHome() else if(analysisPage){
                detail.addView(owner.title("线程分析 · ${name(session.selected)}","读取已完成记录；CPU 放置由你决定",leading=owner.back(){analysisPage=false;render()}));analysisCard()
            }else threadApp()
            "monitor" -> monitorPage()
            "settings" -> settingsPage()
        }
        val next=ScrollView(this).apply{isFillViewport=false;isVerticalScrollBarEnabled=false;clipToPadding=false;addView(detail)}
        pageHost.addView(next,FrameLayout.LayoutParams(-1,-1))
        ownerCanvas.requestApplyInsets()
        if(previous!=null){
            previous.animate().cancel();previous.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            next.alpha=0f;next.translationY=owner.px(6).toFloat()
            next.animate().alpha(1f).translationY(0f).setDuration(300).setInterpolator(OwnerUi.smooth).start()
            previous.animate().alpha(0f).setDuration(300).setInterpolator(OwnerUi.smooth).withEndAction{pageHost.removeView(previous)}.start()
        }
        OwnerRenderTrace.event("PAGE_CHANGED",page)
        }
        pageBindings.toList().forEach{it()}
        bindMonitor()
        OwnerRenderTrace.bind(physicalHost,ownerCanvas,rail,master,pageHost)
        if(session.notice.isNotEmpty() && session.notice!=shownToast){shownToast=session.notice;toast(session.notice)}
        if(session.notice.isEmpty())shownToast=""

    }
    private fun buildMaster() {
        val titles=mapOf("tune" to "应用策略","thread" to "线程策略","monitor" to "监测记录","settings" to "设置")
        master.addView(owner.row().apply {
            setPadding(owner.px(18),owner.px(22),owner.px(18),owner.px(12))
            addView(owner.column().apply{
                addView(owner.label(titles.getValue(session.section),19f,owner.text,800),LinearLayout.LayoutParams(-1,owner.px(24)))
                masterSubtitle=owner.label(masterSubtitleText(),11f,owner.muted)
                addView(masterSubtitle,LinearLayout.LayoutParams(-1,owner.px(14)).apply{topMargin=owner.px(3)})
            },LinearLayout.LayoutParams(0,-2,1f))
            if(session.section in setOf("tune","thread"))addView(owner.icon(R.drawable.owner_plus,Color.WHITE,18).apply{
                background=owner.shadow(owner.shape(owner.accent,11f),11f,14f,6f,-4f,owner.accentGlow);setPadding(owner.px(8),owner.px(8),owner.px(8),owner.px(8))
                isFocusable=true;contentDescription="添加应用";setOnClickListener{guard{picker()}};owner.press(this,.92f)
            },LinearLayout.LayoutParams(owner.px(34),owner.px(34)))
        },LinearLayout.LayoutParams(-1,owner.px(82)))
        OwnerWindow.safeContent(master.getChildAt(0),22f)
        val list=owner.column().apply{setPadding(owner.px(14),owner.px(4),owner.px(14),owner.px(18))}
        masterList=list
        if(session.section=="settings") {
            val icons=listOf(R.drawable.owner_monitor,R.drawable.owner_gpu,R.drawable.owner_data,R.drawable.owner_info)
            val rows=listOf("监测与显示" to "悬浮窗权限 · 通知 · 主题","GPU 默认范围" to "四档默认 GPU 频率区间","数据与维护" to "备份 · 恢复 · 维护","关于" to "Release · Build · Schema")
            rows.forEachIndexed{i,(title,sub)->list.addView(ownerListRow("",title,sub,session.settingsModule==i,icons[i]){
                if(!session.sameContext("settings",i))guard{session.changeContext("settings",i);render()}
            })}
        } else {
            val (box,search)=owner.search(when(session.section){"thread"->"搜索应用或包名...";"monitor"->"搜索记录...";else->"搜索应用..."},query)
            master.addView(box,LinearLayout.LayoutParams(-1,owner.px(38)).apply{marginStart=owner.px(18);marginEnd=owner.px(18);bottomMargin=owner.px(12)})
            if(session.section=="thread"){
                master.addView(segment(listOf("全部","上游","我的"),filter){filter=it;populateList(list)},LinearLayout.LayoutParams(-1,-2).apply{marginStart=owner.px(18);marginEnd=owner.px(18);bottomMargin=owner.px(12)})

            }
            search.addTextChangedListener(watcher{query=it;populateList(list)});populateList(list)
        }
        master.addView(ScrollView(this).apply{isVerticalScrollBarEnabled=false;addView(list)},LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun masterSubtitleText()=when(session.section){"tune"->"${policies?.apps?.size ?: 0} 个独立配置";"thread"->"${installed.count{model?.appProfile(it.packageName)!=null}} 个应用 · ${model?.profiles?.values?.sumOf{it.rules.size} ?: 0} 条特殊线程规则";"monitor"->"${records.length()} 条记录 · 每个应用保留最近一次";"settings"->"ZuiControl 偏好与工具";else->""}
    private fun ownerAppIcon(pkg: String,size: Int=40): View=ImageView(this).apply {
        appMetadata[pkg]?.icon?.let{setImageDrawable(it.constantState?.newDrawable(resources) ?: it)}
        scaleType=ImageView.ScaleType.FIT_CENTER;setPadding(0,0,0,0)
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams=LinearLayout.LayoutParams(owner.px(size),owner.px(size))
    }
    private fun ownerListRow(pkg: String,title: String,subtitle: String,selected: Boolean,icon: Int?=null,action:()->Unit): View=owner.row().apply{
        val item=this
        setPadding(owner.px(13),owner.px(12),owner.px(13),owner.px(12))
        background=owner.shape(owner.card,14f,if(selected)owner.accent else owner.line,if(selected)2f else 1f)
        if(selected)background=owner.shadow(checkNotNull(background),14f,24f,10f,-12f,owner.accentGlow)
        val image=if(pkg.isNotEmpty())ownerAppIcon(pkg) else owner.icon(icon ?: R.drawable.owner_tune,if(selected)owner.accent else owner.sub,19).apply{
            background=owner.shape(if(selected)owner.soft(owner.accent) else owner.card2,12f);setPadding(owner.px(10),owner.px(10),owner.px(10),owner.px(10))
        }
        addView(image,LinearLayout.LayoutParams(owner.px(40),owner.px(40)).apply{marginEnd=owner.px(12)})
        addView(owner.column().apply{
            addView(owner.label(title,13.5f,owner.text,800),LinearLayout.LayoutParams(-1,owner.px(17)))
            val d=if(session.newApp && session.appDraft?.packageName==pkg)session.appDraft else policies?.apps?.firstOrNull{it.draft.packageName==pkg}?.draft
            val chips=owner.row()
            if(d!=null && session.section=="tune"){
                chips.addView(owner.chip("${d.refreshHz}Hz"));chips.addView(owner.chip(modeTitle(d.uperfMode),GpuDefaultsDraft.modes.indexOf(d.uperfMode)+1),LinearLayout.LayoutParams(-2,owner.px(18)).apply{marginStart=owner.px(6)})
                if(session.newApp && session.appDraft?.packageName==pkg)chips.addView(owner.chip("未保存",3),LinearLayout.LayoutParams(-2,owner.px(18)).apply{marginStart=owner.px(6)})
                if(model?.appProfile(pkg)!=null)chips.addView(owner.icon(R.drawable.owner_chip,owner.zoFg,12).apply{background=owner.shape(owner.zoBg,5f);setPadding(owner.px(3),owner.px(3),owner.px(3),owner.px(3))},LinearLayout.LayoutParams(owner.px(18),owner.px(18)).apply{marginStart=owner.px(6)})
            } else if(session.section=="thread" && pkg.isNotEmpty()){
                chips.addView(provenanceChip(pkg))
                chips.addView(owner.chip("${model?.appProfile(pkg)?.rules?.size ?: 0} 条规则"),LinearLayout.LayoutParams(-2,dp(18)).apply{marginStart=dp(6)})
            }else chips.addView(owner.label(subtitle,11f,owner.muted),LinearLayout.LayoutParams(-1,owner.px(14)))
            addView(chips,LinearLayout.LayoutParams(-1,-2).apply{topMargin=owner.px(5)})
        },LinearLayout.LayoutParams(0,-2,1f))
        addView(owner.icon(R.drawable.owner_chevron,owner.muted,16).apply{alpha=.6f},LinearLayout.LayoutParams(owner.px(16),owner.px(16)).apply{marginStart=owner.px(12)})
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=owner.px(8)};isFocusable=true;contentDescription=title;setOnClickListener{action()}
        masterSelectionBindings+={
            val on=if(pkg.isNotEmpty())session.selected==pkg else if(session.section=="settings")session.settingsModule==listOf("监测与显示","GPU 默认范围","数据与维护","关于").indexOf(title) else false
            item.isSelected=on
            item.background=owner.shape(owner.card,14f,if(on)owner.accent else owner.line,if(on)2f else 1f)
            if(on)item.background=owner.shadow(checkNotNull(item.background),14f,24f,10f,-12f,owner.accentGlow)
            if(pkg.isEmpty() && image is ImageView){val tone=if(on)owner.accent else owner.sub;image.imageTintList=android.content.res.ColorStateList.valueOf(tone);image.background=owner.shape(if(on)owner.soft(owner.accent)else owner.card2,12f)}
        }
    }

    private fun populateList(list: LinearLayout) {
        list.removeAllViews()
        masterSelectionBindings.clear()

        val rows = when (session.section) {
            "tune" -> (policies?.apps.orEmpty().map{it.draft}+listOfNotNull(session.appDraft?.takeIf{session.newApp && policies?.apps.orEmpty().none{p->p.draft.packageName==it.packageName}})).map { Triple(it.packageName, name(it.packageName), "${it.refreshHz} Hz · ${modeTitle(it.uperfMode)}") }
            "thread" -> installed.mapNotNull { app ->
                val p = model?.appProfile(app.packageName) ?: return@mapNotNull null
                val source = provenance(app.packageName)
                if (filter == 1 && source != "UPSTREAM" || filter == 2 && source !in setOf("USER_MODIFIED", "USER_CREATED")) return@mapNotNull null
                Triple(app.packageName, name(app.packageName), "${sourceTitle(source)} · ${p.rules.size} 条规则") }
            else -> (0 until records.length()).map { i -> val r = records.getJSONObject(i)
                Triple(r.getString("package"), r.optString("label", r.getString("package")), "${whenRecorded(r.optLong("wall"))} · ${duration(r.optLong("duration"))}") }
        }
        rows.filter { query.isBlank() || it.first.contains(query, true) || it.second.contains(query, true) }.forEach { (pkg, title, subtitle) ->
            list.addView(if(session.section == "tune") ownerListRow(pkg,title,subtitle,session.selected==pkg){selectWithGuard(pkg)}
                else listRow(pkg, title, subtitle, session.selected == pkg) { selectWithGuard(pkg) })
        }
        if (list.childCount == 0) list.addView(note(if (loading.isNotEmpty()) "正在读取…" else "暂无应用"))
    }
    private fun select(pkg: String) {
        val restoring=session.selected!=pkg && session.hasDraftFor(session.section,pkg)
        if(!restoring)session.clearDrafts()
        selectedRecord = null;selectedThread=null;recordThreads=false;rawPage=false; recordRequested = false; analysis = null; analysisError = ""
        session.selected = if (session.selected == pkg && session.section!="monitor") "" else pkg
        if (session.selected.isEmpty()) { render(); return }
        when (session.section) {
            "tune" -> { val d = policies?.apps?.firstOrNull { it.draft.packageName == pkg }?.draft ?: return
                if(!restoring){session.originalApp = d; session.appDraft = d};loadAppMetadata(pkg); render() }
            "thread" -> { if(!restoring)openRule(pkg); render(); loadAnalysis(pkg) }
            else -> { render(); readRecord(pkg) }
        }
    }
    private fun selectWithGuard(pkg:String){
        if(!session.busy && session.selected.isEmpty() && session.hasDraftFor(session.section,pkg))select(pkg)
        else guard{select(pkg)}
    }
    private fun dashboard() {
        val tier=GpuDefaultsDraft.modes.indexOf(mode.displayed).coerceAtLeast(0)
        val modeChip=owner.row().apply{
            background=owner.shape(owner.chipBg[tier+1],999f);setPadding(owner.px(11),0,owner.px(13),0)
            minimumHeight=owner.px(30)
            addView(OwnerPing(this@MainActivity,owner,owner.tiers[tier]),LinearLayout.LayoutParams(owner.px(7),owner.px(7)).apply{marginEnd=owner.px(8)})
            addView(owner.label("${modeTitle(mode.displayed)}模式",12f,owner.chipFg[tier+1],800));layoutParams=LinearLayout.LayoutParams(-2,owner.px(30))
        }
        bindControl(mode){
            val tier=GpuDefaultsDraft.modes.indexOf(mode.displayed).coerceAtLeast(0)
            modeChip.background=owner.shape(owner.chipBg[tier+1],999f)
            (modeChip.getChildAt(1) as TextView).apply{val caption="${modeTitle(mode.displayed)}模式";if(text.toString()!=caption)text=caption;setTextColor(owner.chipFg[tier+1])}
        }
        detail.addView(owner.title("系统全局状态","系统关键性能参数与组件运行情况",trailing=modeChip))
        val stats=owner.row()
        listOf("温度","功耗","核心组件").forEachIndexed{i,title ->
            val box=owner.card(false).apply{setPadding(owner.px(17),owner.px(15),owner.px(17),owner.px(17))}
            box.addView(owner.row().apply{
                addView(owner.icon(listOf(R.drawable.owner_temperature,R.drawable.owner_power,R.drawable.owner_health)[i],owner.muted,16),LinearLayout.LayoutParams(owner.px(16),owner.px(16)).apply{marginEnd=owner.px(7)})
                addView(owner.label(title,12f,owner.sub,700))
            },LinearLayout.LayoutParams(-1,owner.px(22)))
            val metric=owner.label("--",if(i==2)20f else 28f,owner.text,800).apply{if(i<2)fontFeatureSettings="tnum"}
            val values=owner.row().apply{
                if(i==2){
                    val slot=FrameLayout(this@MainActivity).apply{clipChildren=true;addView(metric,FrameLayout.LayoutParams(-1,-1))};coreSlot=slot
                    addView(slot,LinearLayout.LayoutParams(-1,owner.px(32)))
                }else addView(metric,LinearLayout.LayoutParams(-2,owner.px(32)))
                if(i<2)addView(owner.label(if(i==0)"℃" else "W",13f,owner.sub,700).also{if(i==1)powerUnit=it},LinearLayout.LayoutParams(-2,-2).apply{marginStart=owner.px(3);topMargin=owner.px(9)})
                if(i==1){powerReason=owner.label("",11.5f,owner.muted,700).apply{setSingleLine(false);maxLines=2};addView(powerReason,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=owner.px(8)})}
            }
            box.addView(values,LinearLayout.LayoutParams(-1,owner.px(32)).apply{topMargin=owner.px(12);bottomMargin=owner.px(12)})
            val meter=when(i){
                0 -> OwnerMeter(this,owner,listOf(.6667f,.8333f)).also{ownerQuiet=it;quietLabel=metric}
                1 -> OwnerMeter(this,owner,listOf(.35f,.7f)).also{ownerPower=it;powerLabel=metric}
                else -> {
                    val health=BackendHealth.components(state)
                    val bad=health.firstOrNull{it.state in setOf(BackendHealth.State.FAILED,BackendHealth.State.DEGRADED)}
                    metric.text=if(health.all{it.state==BackendHealth.State.OK})"5/5 正常" else bad?.component ?: "状态未知"
                    coreLabel=metric;if(bad!=null)metric.setTextColor(if(bad.state==BackendHealth.State.FAILED)owner.inks[3] else owner.inks[2])
                    handler.postDelayed(coreTicker,2000);box.setOnClickListener{coreHealth()};box.contentDescription="核心组件详情";box.isFocusable=true
                    OwnerMeter(this,owner,segments=health.map{when(it.state){BackendHealth.State.OK->owner.accent;BackendHealth.State.DEGRADED->owner.tiers[2];BackendHealth.State.FAILED->owner.tiers[3];else->owner.line2}}).also{healthMeter->
                        bindPresentation{
                            val fresh=BackendHealth.components(state);val bad=fresh.firstOrNull{it.state in setOf(BackendHealth.State.FAILED,BackendHealth.State.DEGRADED)}
                            coreLabel?.text=if(fresh.all{it.state==BackendHealth.State.OK})"5/5 正常" else bad?.component ?: "状态未知"
                            coreLabel?.setTextColor(if(bad?.state==BackendHealth.State.FAILED)owner.inks[3] else if(bad!=null)owner.inks[2] else owner.text)
                            healthMeter.showSegments(fresh.map{when(it.state){BackendHealth.State.OK->owner.accent;BackendHealth.State.DEGRADED->owner.tiers[2];BackendHealth.State.FAILED->owner.tiers[3];else->owner.line2}})
                        }
                    }
                }
            }
            box.addView(meter,LinearLayout.LayoutParams(-1,owner.px(6)))
            stats.addView(box,LinearLayout.LayoutParams(0,owner.px(118),if(i==2)1.34f else 1f).apply{if(i>0)marginStart=owner.px(12)})
        }
        detail.addView(stats,owner.gap())
        detail.addView(owner.card().apply{
            addView(owner.section("全局刷新率","全局默认屏幕刷新率档位模式"))
            val rates=supportedRates();val refreshSelector=ownerControl(OwnerSegment(this@MainActivity,owner,rates.map{"$it Hz"},rates.indexOf(refresh.displayed)){i->
                optimistic(refresh,rates[i]){target->session.gateway.setGlobal("refresh",value=target)}
            });addView(refreshSelector);bindControl(refresh){refreshSelector.showSelection(rates.indexOf(refresh.displayed))}
            val unknown=owner.label("全局档位尚未确认",11f,owner.inks[2]);addView(unknown)
            bindControl(refresh){unknown.visibility=if(refresh.confirmed==0)View.VISIBLE else View.GONE}
            addView(owner.divider());addView(owner.section("全局性能档位","日常系统调度激进程度"))
            addView(ownerControl(owner.tiers(mode.displayed,reconcile={bind->bindControl(mode){bind(mode.displayed)}}){id->optimistic(mode,id){target->session.gateway.setGlobal("mode",mode=target)}}))
        },owner.gap())
        detail.addView(owner.card(false).apply{
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(owner.px(19),owner.px(16),owner.px(19),owner.px(16))
            addView(owner.icon(R.drawable.owner_pulse,owner.accent).apply{background=owner.shape(owner.soft(owner.accent),12f);setPadding(owner.px(10),owner.px(10),owner.px(10),owner.px(10))},LinearLayout.LayoutParams(owner.px(40),owner.px(40)).apply{marginEnd=owner.px(14)})
            addView(owner.column().apply{
                addView(owner.label("性能监视悬浮窗",14f,owner.text,800),LinearLayout.LayoutParams(-1,owner.px(20)))
                addView(owner.label("在屏幕顶部居中实时浮动展示帧率、温度与功耗；通过悬浮窗开始 / 停止记录",11f,owner.muted),LinearLayout.LayoutParams(-1,owner.px(14)).apply{topMargin=owner.px(3)})
            },LinearLayout.LayoutParams(0,-2,1f))
            addView(FrameLayout(this@MainActivity).apply{
                background=owner.shape(if(this@MainActivity.overlay.displayed)owner.accent else owner.line2,13f)
                ownerSwitch=View(this@MainActivity).apply{background=owner.shape(Color.WHITE,99f);elevation=owner.px(2).toFloat();translationX=owner.px(if(this@MainActivity.overlay.displayed)18 else 0).toFloat()}
                addView(ownerSwitch,FrameLayout.LayoutParams(owner.px(20),owner.px(20)).apply{leftMargin=owner.px(3);topMargin=owner.px(3)})
            },LinearLayout.LayoutParams(owner.px(44),owner.px(26)).apply{marginStart=owner.px(14)})
            isEnabled=true;alpha=1f;isFocusable=true;contentDescription="性能监视悬浮窗，${if(this@MainActivity.overlay.displayed)"已开启" else "已关闭"}";setOnClickListener{toggleOverlay()}
            tag="owner-control"
        },owner.gap())
    }

    private fun <T> optimistic(control: OptimisticControl<T>, value: T, action: (T) -> ZuiControlClient.Reply) {
        session.intent(control,value,action)
        rebindControl(control)
    }
    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) { overlayPermission(); return }
        ZuiControlQuickService.start(this); val desired = !this@MainActivity.overlay.displayed
        optimistic(overlay, desired) { target->session.gateway.setOverlay(target) }
        ownerSwitch?.animate()?.translationX(owner.px(if(desired)18 else 0).toFloat())?.setDuration(300)?.setInterpolator(OwnerUi.spring)?.start()
        (ownerSwitch?.parent as? View)?.background=owner.shape(if(desired)owner.accent else owner.line2,13f)
    }
    private fun coreHealth() {
        loadState()
        val body=owner.column()
        val zuioptState=ruleState
        val kind=listOf("逻辑组件","调度守护进程","线程优化守护进程","逻辑组件","逻辑组件")
        BackendHealth.components(state).forEachIndexed{i,h->
            if(i>0)body.addView(View(this).apply{setBackgroundColor(owner.line)},LinearLayout.LayoutParams(-1,owner.px(1)))
            body.addView(owner.row().apply{
                setPadding(0,owner.px(10),0,owner.px(10))
                addView(owner.column().apply{addView(owner.label(h.component,12.5f,owner.text,800));addView(owner.label(kind.getOrElse(i){"逻辑组件"},10f,owner.muted,600))},LinearLayout.LayoutParams(owner.px(150),-2))
                val tone=when(h.state){BackendHealth.State.OK->1;BackendHealth.State.DEGRADED->3;BackendHealth.State.FAILED->4;else->0}
                addView(FrameLayout(this@MainActivity).apply{
                    val status=owner.chip("",tone)
                    addView(status,FrameLayout.LayoutParams(-2,dp(18),Gravity.CENTER_VERTICAL))
                    bindPresentation{val fresh=BackendHealth.components(state)[i];val tone=when(fresh.state){BackendHealth.State.OK->1;BackendHealth.State.DEGRADED->3;BackendHealth.State.FAILED->4;else->0}
                        status.text=when(fresh.state){BackendHealth.State.OK->"正常";BackendHealth.State.DEGRADED->"降级";BackendHealth.State.FAILED->"故障";else->"未知"}
                        status.setTextColor(owner.chipFg[tone]);status.background=owner.shape(owner.chipBg[tone],5f)
                    }
                },LinearLayout.LayoutParams(owner.px(72),owner.px(18)).apply{marginStart=owner.px(10)})
                addView(owner.column().apply{
                    val reason=owner.label(h.reason,11.5f,owner.sub).apply{setSingleLine(false);setLineSpacing(0f,1.5f)};addView(reason)
                    bindPresentation{reason.text=BackendHealth.components(state)[i].reason}
                },LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=owner.px(10)})
                addView(owner.label(if(h.component=="ZUIopt")"线程" else if(h.component=="监视服务")"悬浮窗" else "维护",11.5f,owner.accent,700).apply{
                    isFocusable=true;setOnClickListener{ownerModal?.close();guard{session.section=if(h.component=="ZUIopt")"thread" else "settings";session.settingsModule=if(h.component=="监视服务")0 else 2;session.selected="";session.clearDrafts();render()}}
                },LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(10)})
            })
        }
        body.addView(owner.label("Monitor OFF、GPU 在桌面 handle=0 均属正常",11f,owner.muted).apply{setSingleLine(false)})
        val buttons=mutableListOf<View>()
        if(ZuioptRules.field(zuioptState, "failure") == "1")buttons+=owner.button("复位 ZUIopt 故障"){ownerModal?.close();confirm("复位故障锁存？","下次重启重新启用 ZUIopt；本次启动继续保持故障保护。"){
            session.work("故障已复位；下次重启生效"){ZuioptRules.command(this@MainActivity, "reset")}
        }}
        buttons+=owner.button("知道了","primary"){ownerModal?.close()}
        ownerModal?.open("核心组件","5 个逻辑组件；其中部分是 system_server 内的逻辑组件，不是独立进程。",600,body,buttons)
    }

    private fun appPage() {
        val d=session.appDraft ?: return
        val leading=owner.row().apply{
            addView(owner.back("返回全局"){guard{session.clearDrafts();session.selected="";render()}},LinearLayout.LayoutParams(owner.px(36),owner.px(36)).apply{marginEnd=owner.px(14)})
            addView(ownerAppIcon(d.packageName,44))
        }
        val dirtyChip=owner.chip("未保存",3,true).apply{visibility=if(session.appDirty)View.VISIBLE else View.GONE}
        var saveButton:View?=null
        fun markDirty(){
            dirtyChip.visibility=if(session.appDirty)View.VISIBLE else View.GONE
            saveButton?.let{it.isEnabled=!session.busy && session.appDirty;it.alpha=if(it.isEnabled)1f else .4f}
        }
        pageBindings+={markDirty()}
        var syncGpu:()->Unit={}
        val identity=owner.identityTitle(name(d.packageName),d.packageName,leading,dirtyChip) as LinearLayout
        detail.addView(identity)
        bindPresentation{
            ((identity.getChildAt(1) as LinearLayout).getChildAt(0) as TextView).text=name(d.packageName)
            appMetadata[d.packageName]?.icon?.let{(leading.getChildAt(1) as ImageView).setImageDrawable(it.constantState?.newDrawable(resources)?.mutate() ?: it)}
        }
        val configurable=configurableApps[d.packageName]==true
        loadAppMetadata(d.packageName)
        val rates=supportedRates()
        val box=owner.card()
        box.addView(owner.section("自定义应用刷新率","前台应用自定义刷新率档位"))
        val appRefresh=OwnerSegment(this,owner,rates.map{"$it Hz"},rates.indexOf(d.refreshHz)){session.appDraft=checkNotNull(session.appDraft).copy(refreshHz=rates[it]);markDirty()}
        box.addView(appRefresh);bindPresentation{session.appDraft?.let{appRefresh.showSelection(rates.indexOf(it.refreshHz))}}
        box.addView(owner.divider());box.addView(owner.section("自定义应用性能档位","调配集群负载迁移阈值与超大核激进度"))
        box.addView(owner.tiers(d.uperfMode,true,configurable,reconcile={bind->pageBindings+={session.appDraft?.let{bind(it.uperfMode)}}}){id->session.appDraft=checkNotNull(session.appDraft).copy(uperfMode=id,gpuPolicy=ZuiControlClient.GpuPolicy.DEFAULT_FOR_MODE,gpuMinMHz=null,gpuMaxMHz=null);syncGpu();markDirty()})
        if(!configurable)box.addView(owner.label("此应用不支持 Uperf 配置；刷新率和适用的线程规则仍可配置",11f,owner.muted).apply{setSingleLine(false)})
        box.addView(owner.divider())
        val range=runCatching{if(d.gpuPolicy==ZuiControlClient.GpuPolicy.CUSTOM)GpuRanges.Range(d.gpuMinMHz!!,d.gpuMaxMHz!!) else globalRanges().getValue(d.uperfMode)}
        range.onSuccess{r->
            val readout=ownerReadout(r,owner.accent)
            val custom=d.gpuPolicy==ZuiControlClient.GpuPolicy.CUSTOM
            val heading=owner.section("GPU 频率范围",if(custom)"仅此应用使用的固定区间" else "持续跟随「${modeTitle(d.uperfMode)}」档默认范围",readout)
            box.addView(heading)
            val bar=GpuRangeBar(this,r).apply{
                isEnabled=configurable && custom
                onPreview={ownerUpdateReadout(readout,it)}
                onCommit={session.appDraft=checkNotNull(session.appDraft).copy(gpuPolicy=ZuiControlClient.GpuPolicy.CUSTOM,gpuMinMHz=it.min,gpuMaxMHz=it.max);markDirty()}
            }
            val policySelector=OwnerSegment(this,owner,listOf("默认","自定义"),if(custom)1 else 0,true,configurable){i->
                val current=checkNotNull(session.appDraft)
                val effective=if(current.gpuPolicy==ZuiControlClient.GpuPolicy.CUSTOM)GpuRanges.Range(current.gpuMinMHz!!,current.gpuMaxMHz!!) else globalRanges().getValue(current.uperfMode)
                session.appDraft=current.copy(gpuPolicy=if(i==0)ZuiControlClient.GpuPolicy.DEFAULT_FOR_MODE else ZuiControlClient.GpuPolicy.CUSTOM,gpuMinMHz=if(i==0)null else effective.min,gpuMaxMHz=if(i==0)null else effective.max)
                syncGpu();markDirty()
            }
            // DOCX: switch and interval share the row below the numeric readout.
            box.addView(owner.row().apply{
                addView(policySelector,LinearLayout.LayoutParams(owner.px(132),owner.px(34)).apply{marginEnd=owner.px(14)})
                addView(bar,LinearLayout.LayoutParams(0,bar.preferredHeight,1f))
            })
            syncGpu={
                val current=checkNotNull(session.appDraft);val isCustom=current.gpuPolicy==ZuiControlClient.GpuPolicy.CUSTOM
                val effective=if(isCustom)GpuRanges.Range(current.gpuMinMHz!!,current.gpuMaxMHz!!) else globalRanges().getValue(current.uperfMode)
                policySelector.showSelection(if(isCustom)1 else 0)
                bar.isEnabled=configurable && isCustom;bar.showRange(effective);ownerUpdateReadout(readout,effective)
                ((heading.getChildAt(0) as LinearLayout).getChildAt(1) as TextView).text=if(isCustom)"仅此应用使用的固定区间" else "持续跟随「${modeTitle(current.uperfMode)}」档默认范围"
            }
            bindPresentation{if(session.appDraft!=null)syncGpu()}
        }.onFailure{box.addView(owner.label("GPU 默认范围暂不可用 · ${it.message}",11f,owner.inks[2]).apply{setSingleLine(false)})}
        box.addView(View(this).apply{setBackgroundColor(owner.line)},LinearLayout.LayoutParams(-1,owner.px(1)).apply{topMargin=owner.px(18);bottomMargin=owner.px(16)})
        val profile=model?.appProfile(d.packageName)
        box.addView(owner.row().apply{
            addView(owner.icon(R.drawable.owner_chip,owner.zoFg).apply{background=owner.shape(owner.zoBg,12f);setPadding(owner.px(10),owner.px(10),owner.px(10),owner.px(10))},LinearLayout.LayoutParams(owner.px(40),owner.px(40)).apply{marginEnd=owner.px(12)})
            addView(owner.column().apply{
                addView(owner.label("线程规则",13f,owner.text,800),LinearLayout.LayoutParams(-1,owner.px(18)))
                addView(owner.label(if(profile!=null)"${profile.rules.size} 条特殊线程规则 · ${sourceTitle(provenance(d.packageName))}" else "尚未配置特殊线程规则",11f,owner.muted),LinearLayout.LayoutParams(-1,owner.px(14)).apply{topMargin=owner.px(3)})
            },LinearLayout.LayoutParams(0,-2,1f))
            addView(owner.button(if(profile!=null)"查看规则" else "新建规则",small=true){guard{
                session.section="thread";session.selected=d.packageName;session.clearDrafts();openRule(d.packageName);render();loadInventory();loadRules();loadAnalysis(d.packageName)
            }})
        })
        detail.addView(box,owner.gap())
        detail.addView(owner.row().apply{
            if(!session.newApp)addView(owner.button("删除独立配置","danger",icon=R.drawable.owner_trash,enabled=!session.busy){confirm("删除独立配置？","回到全局策略，线程规则保留。"){
                session.work("已删除",feature="appPolicy"){val reply=ZuiControlClient.removePackageProfile(applicationContext,d.packageName);check(reply.ok){reply.text};session.clearDrafts();session.selected=""}
            }})
            addView(View(this@MainActivity),LinearLayout.LayoutParams(0,1,1f))
            saveButton=owner.button(if(session.busy && session.workFeature=="appPolicy")"正在保存…" else "保存并生效","primary",enabled=!session.busy && session.appDirty){saveDraft()};addView(saveButton)
        })
    }
    private fun ownerReadout(range: GpuRanges.Range,color: Int,size: Float=22f)=owner.row().apply{
        addView(owner.label("${range.min} – ${range.max}",size,color,800));addView(owner.label("MHz",11f,owner.sub,700),LinearLayout.LayoutParams(-2,-2).apply{marginStart=owner.px(3);topMargin=owner.px(5)})
    }
    private fun ownerUpdateReadout(view: LinearLayout,range: GpuRanges.Range){(view.getChildAt(0) as TextView).text="${range.min} – ${range.max}"}
    private fun gpuDefaultsPage() {
        val d=runCatching{session.gpuDraftFrom(state)}.getOrElse{heading("GPU 默认范围","各性能档位的默认 GPU 频率区间");detail.addView(note("默认范围暂不可用 · ${it.message}",orange));return}
        fun draft() = session.gpuDraftFrom(state)
        fun edit(action:(GpuDefaultsDraft)->Unit) {
            runCatching { action(draft()) }.onFailure { session.error="GPU 草稿状态不可用：${it.message}" }
            render()
        }
        val dirtyChip=owner.chip("未保存",3,true)
        val trailing=owner.row().apply{
            addView(dirtyChip,LinearLayout.LayoutParams(-2,owner.px(22)).apply{marginEnd=owner.px(10)})
            addView(owner.button("恢复默认",small=true){edit{it.restoreDefaults()}})
        }
        bindPresentation{
            val current=draft();dirtyChip.visibility=if(current.dirty)View.VISIBLE else View.GONE
            OwnerRenderTrace.event("GPU_DRAFT_BOUND","model=${System.identityHashCode(current)};generation=${current.expectedGeneration};dirty=${current.dirty};ranges=${current.ranges}")
        }
        detail.addView(owner.title("GPU 默认范围","各性能档位的默认 GPU 频率区间",trailing=trailing))
        detail.addView(owner.card().apply{
            GpuDefaultsDraft.modes.forEachIndexed{i,id->
                if(i>0)addView(View(this@MainActivity).apply{setBackgroundColor(owner.line)},LinearLayout.LayoutParams(-1,owner.px(1)).apply{topMargin=owner.px(16);bottomMargin=owner.px(16)})
                val r=d.ranges.getValue(id);val readout=ownerReadout(r,owner.inks[i],17f)
                addView(owner.row().apply{
                    addView(OwnerGauge(this@MainActivity,owner,i),LinearLayout.LayoutParams(owner.px(30),owner.px(30)).apply{marginEnd=owner.px(10)})
                    addView(owner.label(modeTitle(id),13.5f,owner.text,800));addView(owner.label(OwnerUi.descriptions[i],11f,owner.muted,600),LinearLayout.LayoutParams(-2,-2).apply{marginStart=owner.px(6)})
                    addView(View(this@MainActivity),LinearLayout.LayoutParams(0,1,1f));addView(readout)
                },LinearLayout.LayoutParams(-1,owner.px(30)).apply{bottomMargin=owner.px(10)})
                val bar=GpuRangeBar(this@MainActivity,r).apply{tone=owner.tiers[i];onPreview={ownerUpdateReadout(readout,it)};onCommit={range->edit{it.set(id,range)}}}
                addView(bar,LinearLayout.LayoutParams(-1,bar.preferredHeight))
                bindPresentation{val range=draft().ranges.getValue(id);bar.showRange(range);ownerUpdateReadout(readout,range)}
            }
        },owner.gap())
        detail.addView(owner.label("单应用选择“跟随档位默认”时会持续使用这里的区间，修改后这些应用随之更新。拖动后需确认才会保存。",11f,owner.muted).apply{setSingleLine(false);setPadding(owner.px(4),0,owner.px(4),0)},owner.gap())
        detail.addView(owner.row().apply{gravity=Gravity.END;addView(draftSave("保存并生效"){draft().dirty})})
    }
    private fun draftSave(title:String,dirty:()->Boolean):View=owner.button(title,"primary",small=true,enabled=!session.busy && dirty()){saveDraft()}.also{button->
        button.minimumHeight=dp(40)
        bindPresentation{button.isEnabled=!session.busy && dirty();button.alpha=if(button.isEnabled)1f else .4f;((button as LinearLayout).getChildAt(0) as TextView).text=if(session.busy && session.workFeature=="gpuDefaults")"正在保存…" else title}
    }

    private fun saveDraft(after: (() -> Unit)? = null) {
        if (session.busy) return
        val app = session.appDraft; val gpu = session.gpuDraft; val rule = session.ruleDraft
        if (app != null) { session.saveApp(after); return }
        if (gpu != null) { session.saveGpu(after); return }
        session.work("已保存并生效", completed = after) {
            when {
                rule != null -> {
                    val bytes = rule.canonical().toByteArray(Charsets.UTF_8); val validation = ZuioptRules.validate(applicationContext, bytes, rule.generation)
                    check(validation.valid) { validation.error }; ZuioptRules.upload(applicationContext, "user", bytes, expectedGeneration = rule.generation)
                    val fresh=ZuioptRules.userRules(applicationContext);val accepted=ZuioptRuleModel.parseNormalized(fresh.text)
                    val profile=checkNotNull(accepted.appProfile(rule.packageName)){"RULE_POST_ACK_PROFILE_UNAVAILABLE"}
                    check(fresh.generation!=rule.generation){"RULE_POST_ACK_READ_STALE"}
                    rule.base=accepted;rule.generation=fresh.generation;rule.original=profile;rule.profile=profile
                }
            }
        }
    }
    private fun guard(next: () -> Unit) {
        if (!session.dirty) { next(); return }
        ownerModal?.open("有未保存的修改","保存成功后继续，或放弃本次草稿。",360,owner.column(),listOf(
            owner.button("取消"){ownerModal?.close()},owner.button("放弃修改"){ownerModal?.close();session.clearDrafts();next()},
            owner.button("保存","primary"){ownerModal?.close();saveDraft(next)}))
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if(ownerModal?.isOpen==true){ownerModal?.close();return}
        if(rawPage){rawPage=false;render();return}
        if(analysisPage){analysisPage=false;render();return}
        if(session.section=="monitor" && selectedThread!=null){selectedThread=null;render();return}
        if(session.section=="monitor" && recordThreads){readRecord(session.selected);return}
        guard { if (session.selected.isNotEmpty()) { session.clearDrafts(); session.selected = ""; render() } else moveTaskToBack(true) }
    }
    private fun picker() { loadInventory{if(visible)ownerPicker()} }
    private fun ownerPicker() {
        val box=owner.column();val list=owner.column();var system=false;var selected=""
        val (searchBox,search)=owner.search("搜索应用或包名")
        val next=owner.button("下一步","primary",enabled=false){
            if(selected.isEmpty())return@button
            val authority=policies
            if(session.section=="tune" && authority==null)return@button
            if(session.section=="tune" && (mode.confirmed !in GpuDefaultsDraft.modes || refresh.confirmed !in supportedRates())){toast("全局策略暂不可用");return@button}
            session.clearDrafts();session.selected=selected
            if(session.section=="tune"){session.appDraft=ZuiControlClient.AppPolicyDraft(selected,refresh.confirmed,mode.confirmed,ZuiControlClient.GpuPolicy.DEFAULT_FOR_MODE,checkNotNull(authority).generation);session.newApp=true}else openRule(selected)
            ownerModal?.close();render();if(session.section=="thread")loadAnalysis(selected)
        }
        fun populate() {
            list.removeAllViews()
            installed.filter{isSystem(it)==system && (search.text.isBlank() || it.packageName.contains(search.text.toString(),true) || name(it.packageName).contains(search.text.toString(),true))}.forEach{app->
                val exists=if(session.section=="tune")policies?.apps?.any{it.draft.packageName==app.packageName}==true else model?.appProfile(app.packageName)!=null
                list.addView(owner.row().apply{
                    setPadding(owner.px(10),owner.px(8),owner.px(10),owner.px(8))
                    background=owner.shape(Color.TRANSPARENT,12f,if(selected==app.packageName)owner.accent else null,2f)
                    addView(ownerAppIcon(app.packageName,36),LinearLayout.LayoutParams(owner.px(36),owner.px(36)).apply{marginEnd=owner.px(12)})
                    addView(owner.column().apply{
                        addView(owner.label(name(app.packageName),13.5f,owner.text,800));addView(owner.label(if(exists)"已配置" else app.packageName,11f,owner.muted))
                    },LinearLayout.LayoutParams(0,-2,1f))
                    if(exists)addView(owner.chip("已配置")) else addView(View(this@MainActivity).apply{
                        background=owner.shape(if(selected==app.packageName)owner.accent else Color.TRANSPARENT,99f,if(selected==app.packageName)owner.accent else owner.line2,2f)
                    },LinearLayout.LayoutParams(owner.px(18),owner.px(18)))
                    isEnabled=!exists;alpha=if(exists).45f else 1f;isFocusable=!exists;contentDescription=name(app.packageName)
                    setOnClickListener{selected=app.packageName;next.isEnabled=true;next.alpha=1f;populate()}
                },LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=owner.px(4)})
            }
            if(list.childCount==0)list.addView(owner.empty("没有匹配的应用","尝试其他名称或包名"))
        }
        box.addView(OwnerSegment(this,owner,listOf("用户应用","系统应用"),0,true){system=it==1;selected="";next.isEnabled=false;next.alpha=.4f;populate()},LinearLayout.LayoutParams(-1,owner.px(34)).apply{bottomMargin=owner.px(10)})
        box.addView(searchBox,LinearLayout.LayoutParams(-1,owner.px(38)).apply{bottomMargin=owner.px(10)})
        search.addTextChangedListener(watcher{populate()});box.addView(ScrollView(this).apply{
            isVerticalScrollBarEnabled=false;clipChildren=true;clipToPadding=true
            addOnLayoutChangeListener{view,_,_,_,_,_,_,_,_->view.clipBounds=android.graphics.Rect(0,0,view.width,view.height)}
            addView(list)
        },LinearLayout.LayoutParams(-1,owner.px(300)));populate()
        ownerModal?.open("添加应用",if(session.section=="tune")"选择一个应用，创建独立性能策略" else "选择一个应用，创建独立线程规则",440,box,listOf(owner.button("取消"){ownerModal?.close()},next))
    }

    private fun threadHome() {
        val health=BackendHealth.components(state).firstOrNull{it.component=="ZUIopt"}
        val status=when {
            health?.reason=="DISABLED_BY_OWNER" -> "已关闭"
            health?.reason=="FAILSAFE" -> "故障保护"
            health?.state==BackendHealth.State.OK -> "运行中"
            health?.state==BackendHealth.State.FAILED -> "异常"
            health?.state==BackendHealth.State.DEGRADED -> "需关注"
            else -> "状态不可用"
        }
        detail.addView(owner.title("线程调度","ZUIopt · 按线程 / 任务的 CPU 放置（cpuset / affinity）",trailing=if(health?.state==BackendHealth.State.OK && health.reason!="DISABLED_BY_OWNER")owner.modeChip(status,0)else owner.chip(status,when(health?.state){BackendHealth.State.FAILED->4;BackendHealth.State.DEGRADED->3;else->0},true)))
        if (ruleError.isNotEmpty()) detail.addView(owner.empty("规则暂不可用",ruleError,true), gap())
        if (ZuioptRules.field(ruleState, "failure") == "1") detail.addView(actionRow("ZUIopt 已进入故障保护", ZuioptRules.field(ruleState, "failure_reason") + " · 下次开机重新启用") {
            confirm("下次开机重新启用？", "本次开机继续由 Android 调度，不会立即重启。") { session.work("已安排") { ZuioptRules.command(applicationContext, "reset") } }
        }, gap())
        val b = baseline
        detail.addView(card().apply {
            addView(owner.section("当前规则集",b?.metadata?.let { listOf(it.optString("source").ifBlank{"来源未提供"},it.optString("sourceVersion")).filter{it.isNotBlank()}.joinToString(" · ") } ?: "规则集不可用",owner.row().apply {
                addView(owner.chip("规则源待验证",0,true),LinearLayout.LayoutParams(-2,dp(22)).apply{marginEnd=dp(10)})
            addView(owner.button("查看原文",small=true,icon=R.drawable.owner_code){rawView()})

            }))
            addView(note(b?.let { listOf(it.metadata.optString("sourceDate"),it.metadata.optString("sourceCommit"),"生效 generation ${it.generation}").filter{it.isNotBlank()}.joinToString(" · ") } ?: "等待原生规则权威"))
            val apps=installed.filter { model?.appProfile(it.packageName)!=null }
            val counts=listOf(apps.size,model?.profiles?.values?.sumOf{it.rules.size} ?: 0,apps.count{provenance(it.packageName) in setOf("USER_MODIFIED","USER_CREATED")})
            addView(owner.row().apply {
                listOf("规则应用","特殊线程规则","我的修改 / 新建").forEachIndexed{i,title->
                    if(i>0)addView(View(this@MainActivity).apply{setBackgroundColor(owner.line)},LinearLayout.LayoutParams(dp(1),dp(50)))
                    addView(owner.column().apply {
                        setPadding(if(i>0)dp(18)else 0,dp(2),dp(18),dp(2))
                        addView(owner.row().apply{
                            addView(owner.label(counts[i].toString(),24f,owner.text,800))
                            addView(owner.label(if(i==1)"条" else "个",12f,owner.sub,700),LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(3);topMargin=dp(8)})
                        },LinearLayout.LayoutParams(-1,dp(24)))
                        addView(owner.label(title,11f,owner.muted),LinearLayout.LayoutParams(-1,dp(16)).apply{topMargin=dp(6)})
                    },LinearLayout.LayoutParams(0,-2,1f))
                }
            },LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(16)})
        },gap())
        detail.addView(card().apply {
            setPadding(dp(18),dp(12),dp(18),dp(12))
            addView(owner.label("规则库",11.5f,owner.muted,800),LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(4)})
            // Deferred feed has no manual-import affordance. Existing feed logic stays disabled.
            val previous=ZuioptRules.field(ruleState,"previous_generation").matches(Regex("g[0-9a-f]{24}"))
            addView(owner.domainRow("回退规则版本",if(previous)"回到上一代完整规则集" else "暂无可回退版本",R.drawable.owner_undo,"warn",true){confirm("回退规则版本？","上游基线和生效规则一起回退。"){
                session.work("已回退"){ZuioptRules.command(applicationContext,"rollback",checkNotNull(snapshot).generation)}
            }}.apply{isEnabled=previous;alpha=if(previous)1f else .4f})
            addView(View(this@MainActivity).apply{setBackgroundColor(owner.line)},LinearLayout.LayoutParams(-1,dp(1)))
            addView(owner.domainRow("高级兼容导入 · AppOpt","兼容转换与差异预览后确认",R.drawable.owner_appopt,"mute",true){if(!session.busy)document(Intent.ACTION_OPEN_DOCUMENT,102,"*/*")})
            addView(View(this@MainActivity).apply{setBackgroundColor(owner.line)},LinearLayout.LayoutParams(-1,dp(1)))
            addView(owner.domainRow("规则集导出","导出当前生效规则集",R.drawable.owner_export,"zo",true){snapshot?.let{export(it.text.toByteArray(),"ZuiControl_rules.conf")}})
            addView(note("在线规则源尚未启用 · 兼容实验室验证后开放"))
            if(RuleRemoteConfig.configured && updateStatus.isNotEmpty())addView(note(updateStatus))
        },gap())
    }
    private fun checkUpdates() {
        val b = baseline ?: return
        if (!RuleRemoteConfig.configured) { updateStatus = RuleRemoteConfig.DIAGNOSTIC; render(); return }
        session.work { latest = session.gateway.checkUpstream(b); updateStatus = latest?.let { "可更新：${it.version} · ${it.date}" } ?: "已是最新" }
    }
    private fun syncUpstream() {
        val l = latest ?: return; val b = baseline ?: return
        session.work {
            incoming = session.gateway.downloadUpstream(l, b); appOpt = false
            source = ZuioptLibrary.Provenance(l.source, l.version, l.date, l.commit, "publisher-revision:${l.revision}")
            decisions.clear(); manual = null; stagePreview(b)
        }
    }
    private fun stagePreview(b: ZuioptLibrary.Baseline) {
        preview?.let { ZuioptLibrary.cancel(applicationContext, it) }; preview = null
        val bytes = ZuioptLibrary.pack(b, checkNotNull(source), incoming, appOpt, decisions, manual?.normalized() ?: "schema 2\nenabled true\ndebug false\n")
        preview = ZuioptLibrary.preview(applicationContext, bytes, b)
    }
    private fun updatePage() {
        val p = preview ?: return
        detail.addView(owner.title(if(appOpt)"高级兼容导入 · AppOpt" else "规则库更新", "转换结果需确认后才写入；不会整体替换当前规则集",leading=owner.back(){session.work("已放弃导入"){ZuioptLibrary.cancel(applicationContext,p);preview=null}}))
        detail.addView(card().apply{
            addView(owner.section("预览摘要","${p.apps.size} 个应用变化",owner.chip(if(p.apps.any{it.optBoolean("conflict")})"需要你决定" else "待确认",3,true)))
            addView(owner.row().apply{
                listOf("来源" to source?.source.orEmpty(),"版本" to source?.version.orEmpty(),"变化应用" to p.apps.size.toString(),"竞争选择" to if(appOpt)"全部候选" else "原生 Schema2").forEach{(key,value)->
                    addView(owner.column().apply{addView(owner.label(key,10f,owner.muted,600));addView(owner.label(value,12f,owner.text,700),LinearLayout.LayoutParams(-1,dp(22)))},LinearLayout.LayoutParams(0,-2,1f))
                }
            })
            if(appOpt)addView(note("每条转换规则独立竞争组；共享竞争组 / rank:N 无法由 AppOpt 表达。转换失败时不会应用不完整包。"))
        },gap())
        for(conflict in listOf(true,false)){
            val rows=p.apps.filter{it.optBoolean("conflict")==conflict}
            if(rows.isEmpty())continue
            detail.addView(owner.label(if(conflict)"需要你决定 · ${rows.size}（默认保留我的）" else "自动采用 / 保留你的版本 · ${rows.size}",11f,owner.muted,800),owner.gap(8))
            detail.addView(card().apply{
                setPadding(dp(14),dp(4),dp(14),dp(4))
                rows.forEachIndexed{i,r->
                    val pkg=r.getString("package")
                    addView(owner.row().apply{
                        setPadding(0,dp(14),0,dp(14))
                        addView(owner.column().apply{
                            addView(owner.label(name(pkg),13f,owner.text,800));addView(note(sourceTitle(r.optString("provenance"))))
                            addView(owner.label("新增 ${r.optInt("threadAdded")} · 移除 ${r.optInt("threadRemoved")} · 修改 ${r.optInt("threadChanged")}",10.5f,owner.muted,600))
                            addView(owner.label("CPU ${if(r.optBoolean("cpuMaskChanged"))"变化" else "保持"} · 竞争组 ${if(r.optBoolean("selectorClassChanged"))"变化" else "保持"}",10.5f,owner.muted,600))
                        },LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=dp(12)})
                        addView(segment(listOf("保留我的",if(appOpt)"采用转换" else "采用上游","手动合并"), (decisions[pkg] ?: ZuioptLibrary.Decision.valueOf(r.getString("decision"))).ordinal) { choice ->
                            if(choice==2)mergeDialog(pkg)else session.work{decisions[pkg]=ZuioptLibrary.Decision.entries[choice];stagePreview(checkNotNull(this@MainActivity.baseline))}
                        },LinearLayout.LayoutParams(dp(234),dp(32)))
                    })
                    if(i<rows.lastIndex)addView(View(this@MainActivity).apply{setBackgroundColor(owner.line)},LinearLayout.LayoutParams(-1,dp(1)))
                }
            },gap())
        }
        detail.addView(owner.row().apply{
            addView(button("放弃导入"){session.work("已放弃导入"){ZuioptLibrary.cancel(applicationContext,p);preview=null}})
            addView(View(this@MainActivity),LinearLayout.LayoutParams(0,1,1f))
            addView(button(if(appOpt)"写入转换结果" else "应用更新",true){confirm("应用规则更新？","全部变化作为一次新规则集写入，可整体回退。"){
                session.work("已应用更新"){ZuioptLibrary.confirm(applicationContext,p);preview=null;if(!appOpt){latest=null;updateStatus="已是最新"}}
            }})
        },gap())
    }
    private fun mergeDialog(pkg: String) {
        if(appOpt){mergeAppOpt(pkg);return}
        val mine=model?.appProfile(pkg)
        val up=runCatching{ZuioptRuleModel.parseNormalized(incoming.toString(Charsets.UTF_8)).appProfile(pkg)}.getOrNull()
        if(mine==null || up==null){toast("新增 / 删除应用请选择保留我的或采用上游");return}
        var upMask=false;var upRules=false
        val box=column().apply{
            addView(owner.section("默认 CPU","当前 ${mine.generalMask.sorted()} · 上游 ${up.generalMask.sorted()}"))
            addView(segment(listOf("我的默认 CPU","上游默认 CPU"),0){upMask=it==1})
            addView(owner.section("特殊线程规则","当前 ${mine.rules.size} 条 · 上游 ${up.rules.size} 条"),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(18)})
            addView(segment(listOf("我的特殊规则","上游特殊规则"),0){upRules=it==1})
        }
        ownerModal?.open("手动合并 · ${name(pkg)}","选择各组内容后重新生成原生预览",480,box,listOf(owner.button("取消"){ownerModal?.close()},owner.button("重新预览","primary"){
            ownerModal?.close();session.work{
                val chosen=ZuioptRuleModel.Profile(if(upMask)up.generalMask else mine.generalMask,if(upRules)up.rules else mine.rules)
                val current=manual ?: ZuioptRuleModel(true,emptyMap(),emptyList())
                manual=if(current.appProfile(pkg)!=null)current.editApp(pkg){chosen}else addProfile(current,pkg,chosen)
                decisions[pkg]=ZuioptLibrary.Decision.MANUAL_MERGE;stagePreview(checkNotNull(this@MainActivity.baseline))
            }
        }))
    }
    private fun addProfile(base: ZuioptRuleModel, pkg: String, p: ZuioptRuleModel.Profile): ZuioptRuleModel {
        val alias = (0..64).map { "user$it" }.first { it !in base.profiles }
        return base.copy(profiles = base.profiles + (alias to p), mappings = (listOf(ZuioptRuleModel.Mapping("exact", pkg, alias, 0)) + base.mappings).mapIndexed { i, m -> m.copy(priority = 100000 - i) })
    }
    private fun rawView() {rawPage=true;render()}
    private fun rawPage() {
        val raw = snapshot?.text ?: return
        detail.addView(owner.title("规则集原文 · 只读","完整 canonical 规则；编辑请进入单个应用。",leading=owner.back(){rawPage=false;render()},trailing=owner.chip("Schema 2 · 原文",0,true).apply{background=owner.shape(owner.zoBg,6f);setTextColor(owner.zoFg)}))
        val code=owner.row().apply {
            background=owner.shape(owner.code,12f,owner.line)
            addView(owner.label(raw.lines().indices.joinToString("\n"){(it+1).toString()},12f,owner.muted).apply{setSingleLine(false);typeface=Typeface.MONOSPACE;gravity=Gravity.TOP;setPadding(dp(12),dp(12),dp(8),dp(12))},LinearLayout.LayoutParams(dp(40),-2))
            addView(label(raw,12f,owner.text).apply{typeface=Typeface.MONOSPACE;setTextIsSelectable(true);gravity=Gravity.TOP;setPadding(dp(14),dp(12),dp(14),dp(12))},LinearLayout.LayoutParams(0,-2,1f))
        }
        detail.addView(card().apply{addView(code)},gap())
    }
    private fun openRule(pkg: String) {
        analysisPage=false
        val base = model ?: return
        val p = base.appProfile(pkg)
        session.ruleDraft = RuleDraft(pkg, base, checkNotNull(snapshot).generation, p, p ?: ZuioptRuleModel.Profile((0..7).toSet(), emptyList()))
    }
    private fun threadApp() {
        val pkg=session.selected;val d=session.ruleDraft
        val leading=owner.row().apply{
            addView(owner.back("返回线程首页"){guard{session.clearDrafts();session.selected="";analysisPage=false;render()}},LinearLayout.LayoutParams(dp(36),dp(36)).apply{marginEnd=dp(14)})
            addView(ownerAppIcon(pkg,44))
        }
        val dirtyChip=owner.chip("未保存",3,true)
        bindPresentation{dirtyChip.visibility=if(session.ruleDraft?.dirty==true)View.VISIBLE else View.GONE}
        detail.addView(owner.identityTitle(name(pkg),pkg,leading=leading,trailing=owner.row().apply{
            addView(provenanceChip(pkg,true));addView(dirtyChip,LinearLayout.LayoutParams(-2,dp(22)).apply{marginStart=dp(6)})
        }))
        if(d==null)detail.addView(owner.empty("此规则暂不能无损显示","可查看完整原文。$ruleError",true),gap())
        else {
            val mapping=d.base.mappings.firstOrNull{when(it.matchKind){"exact"->it.packageName==pkg;"prefix"->pkg.startsWith(it.packageName);else->pkg.contains(it.packageName)}}
            detail.addView(card().apply {
                addView(owner.section("线程规则","Profile ${mapping?.profile ?: "新建"} · 按顺序匹配，可拖动调整"))
                addView(owner.row().apply{
                    val caption=note("未命中特殊规则的线程 · ${cpuRange(d.profile.generalMask)}")
                    addView(owner.column().apply{addView(owner.label("默认 CPU 范围",13f,owner.text,800));addView(caption)},LinearLayout.LayoutParams(0,-2,1f))
                    val picker=cpuPicker(d.profile.generalMask){mask->mutateRule{d.profile=d.profile.copy(generalMask=mask);render()}} as OwnerCpuPicker
                    addView(picker);bindPresentation{picker.showSelection(d.profile.generalMask);caption.text="未命中特殊规则的线程 · ${cpuRange(d.profile.generalMask)}"}
                },LinearLayout.LayoutParams(-1,dp(48)))
                val rowsHost=owner.column();val rowsBindings=mutableListOf<()->Unit>()
                var structure=""
                fun rebuildRules(){
                rowsHost.removeAllViews();rowsBindings.clear()
                val table=owner.column()
                fun cell(parent:LinearLayout,view:View,width:Int,height:Int=-2){parent.addView(view,LinearLayout.LayoutParams(if(width==0)0 else dp(width),if(height<0)height else dp(height),if(width==0)1f else 0f).apply{marginStart=dp(10)})}
                table.addView(owner.row().apply{
                    addView(View(this@MainActivity),LinearLayout.LayoutParams(dp(16),dp(22)))
                    cell(this,owner.label("匹配",11f,owner.muted,700),0);cell(this,owner.label("竞争组",11f,owner.muted,700),62)
                    cell(this,owner.label("竞争选择",11f,owner.muted,700),84);cell(this,owner.label("CPU 0–7",11f,owner.muted,700),117);cell(this,View(this@MainActivity),22)
                },LinearLayout.LayoutParams(-1,dp(28)))
                if(d.profile.rules.isEmpty())table.addView(owner.empty("还没有特殊线程规则","所有线程使用默认 CPU 范围"))
                d.profile.rules.forEachIndexed{index,rule->
                    table.addView(owner.row().apply{
                        setPadding(0,dp(8),0,dp(8))
                        addView(FrameLayout(this@MainActivity).apply{
                            addView(owner.icon(R.drawable.owner_grip,owner.muted,14),FrameLayout.LayoutParams(dp(14),dp(14),Gravity.CENTER))
                            isFocusable=true;contentDescription="拖动调整规则顺序"
                            setOnLongClickListener{startDragAndDrop(ClipData.newPlainText("rule-order",index.toString()),View.DragShadowBuilder(this),index,0);true}
                            accessibilityDelegate=object:View.AccessibilityDelegate(){
                                override fun onInitializeAccessibilityNodeInfo(host:View,info:android.view.accessibility.AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(host,info);info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,"上移规则"));info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,"下移规则"))}
                                override fun performAccessibilityAction(host:View,action:Int,args:Bundle?):Boolean=when(action){android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD->{moveRule(index,index-1);true};android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD->{moveRule(index,index+1);true};else->super.performAccessibilityAction(host,action,args)}
                            }
                        },LinearLayout.LayoutParams(dp(16),dp(22)))
                        cell(this,owner.row().apply{
                            addView(owner.chip(when(rule.matchKind){"exact"->"精确";"prefix"->"前缀";"contains"->"包含";else->"通配"}))
                            addView(owner.label(rule.pattern,12.5f,owner.text,600).apply{typeface=Typeface.MONOSPACE},LinearLayout.LayoutParams(0,dp(22),1f).apply{marginStart=dp(6)})
                            setOnClickListener{editRule(index)}
                        },0)
                        cell(this,owner.row().apply{addView(owner.chip("组 ${groupName(d,rule.competitionClass)}"));setOnClickListener{editRule(index)}},62)
                        cell(this,owner.row().apply{addView(owner.chip(if(rule.selector=="all")"全部候选" else "第${rule.selector.substringAfter(':')}个").apply{background=owner.shape(owner.zoBg,5f);setTextColor(owner.zoFg)});setOnClickListener{editRule(index)}},84)
                        val picker=cpuPicker(rule.cpuMask){mask->mutateRule{d.profile=d.profile.copy(rules=d.profile.rules.map{if(it.competitionClass==rule.competitionClass)it.copy(cpuMask=mask)else it});render()}} as OwnerCpuPicker
                        cell(this,picker,117);rowsBindings+={d.profile.rules.getOrNull(index)?.let{picker.showSelection(it.cpuMask)}}
                        cell(this,FrameLayout(this@MainActivity).apply{
                            addView(owner.icon(R.drawable.owner_close,owner.muted,14),FrameLayout.LayoutParams(dp(14),dp(14),Gravity.CENTER))
                            isFocusable=true;contentDescription="删除规则 ${index+1}";setOnClickListener{mutateRule{d.profile=d.profile.copy(rules=d.profile.rules.filterIndexed{i,_->i!=index});render()}}
                        },22,22)
                        setOnDragListener{_,event->when(event.action){DragEvent.ACTION_DRAG_STARTED->event.clipDescription?.label=="rule-order";DragEvent.ACTION_DROP->{(event.localState as? Int)?.let{moveRule(it,index)};true};else->true}}
                    })
                    table.addView(View(this@MainActivity).apply{setBackgroundColor(owner.line)},LinearLayout.LayoutParams(-1,dp(1)))
                    if(rule.cpuMask.isEmpty())table.addView(note("请选择 CPU 后再保存",orange))
                }
                rowsHost.addView(owner.scroll(table,minOf(214,28+d.profile.rules.size*40)))
                }
                bindPresentation{
                    val next=d.profile.rules.map{listOf(it.competitionClass,it.matchKind,it.pattern,it.selector,it.priority,it.cpuMask.isEmpty())}.toString()
                    if(next!=structure){structure=next;rebuildRules();OwnerRenderTrace.event("RULE_ROWS_CHANGED")}
                    rowsBindings.forEach{it()}
                }
                addView(rowsHost)
                addView(owner.button("添加特殊线程规则","dashed",small=true,icon=R.drawable.owner_plus){editRule(null)},LinearLayout.LayoutParams(-1,dp(34)).apply{topMargin=dp(8)})
            },gap())
            detail.addView(owner.row().apply{
                if(d.original!=null)addView(owner.button("删除规则","danger",icon=R.drawable.owner_trash){confirm("删除此应用规则？","只移除此应用的独立包映射。"){
                    session.work("已删除"){
                        check(mapping?.matchKind=="exact"){"此应用由共享宽匹配映射覆盖，无法单独删除"}
                        val next=d.base.copy(mappings=d.base.mappings.filterNot{it.matchKind=="exact" && it.packageName==pkg})
                        ZuioptRules.upload(applicationContext,"user",next.normalized().toByteArray(),expectedGeneration=d.generation);session.ruleDraft=null
                    }
                }})
                else addView(owner.button("放弃新建","danger",icon=R.drawable.owner_trash){guard{session.clearDrafts();session.selected="";render()}})
                if(provenance(pkg)=="USER_MODIFIED")addView(owner.button("恢复上游",icon=R.drawable.owner_undo){confirm("恢复上游规则？","将放弃此应用的修改。"){
                    session.work("已恢复上游"){ZuioptLibrary.restoreApp(applicationContext,pkg,checkNotNull(this@MainActivity.baseline));session.ruleDraft=null}
                }},LinearLayout.LayoutParams(-2,-2).apply{marginEnd=dp(8)})
                addView(View(this@MainActivity),LinearLayout.LayoutParams(0,1,1f))
                addView(owner.button("记录关联线程分析",small=true,icon=R.drawable.owner_pulse){analysisPage=true;render()}.apply{minimumHeight=dp(40)},LinearLayout.LayoutParams(-2,-2).apply{marginEnd=dp(8)})
                addView(owner.button("原文预览",small=true,icon=R.drawable.owner_code){rawView()}.apply{minimumHeight=dp(40)},LinearLayout.LayoutParams(-2,-2).apply{marginEnd=dp(8)})
                addView(draftSave("保存并应用"){session.ruleDraft?.dirty==true})
            },gap())
        }
        if(d==null)detail.addView(owner.button("查看原文 · 只读",small=true){rawView()},gap())
    }
    private fun mutateRule(next: () -> Unit) {
        if (session.busy && session.workFeature=="rules") return
        val d = session.ruleDraft ?: return
        if (!d.cloneConfirmed && d.original != null) {
            val mapping = d.base.mappings.firstOrNull { it.matchKind == "exact" && it.packageName == d.packageName }
            if (mapping == null || d.base.mappings.count { it.profile == mapping.profile } > 1) {
                confirm("创建独立副本并编辑？", "当前 Profile 为多个应用共享。仅为此应用创建副本，其它应用保留原规则。") { d.cloneConfirmed = true; next() }; return
            }
        }; d.cloneConfirmed = true; next()
    }
    private fun moveRule(from: Int, to: Int) {
        val d = session.ruleDraft ?: return
        if (to !in d.profile.rules.indices || from !in d.profile.rules.indices) return
        mutateRule { val rows = d.profile.rules.toMutableList(); rows.add(to, rows.removeAt(from)); d.profile = d.profile.copy(rules = rows); render() }
    }
    private fun groupName(d: RuleDraft, key: String) = ('A'.code + d.profile.rules.map { it.competitionClass }.distinct().indexOf(key).coerceAtLeast(0)).toChar().toString()
    private fun cpuRange(mask:Set<Int>):String {
        val ranges=mutableListOf<String>();val cores=mask.sorted();var i=0
        while(i<cores.size){val start=cores[i];var end=start;while(i+1<cores.size && cores[i+1]==end+1){i++;end=cores[i]};ranges+=if(start==end)"$start" else "$start–$end";i++}
        return ranges.joinToString("、").ifEmpty{"未选择"}
    }
    private fun editRule(index: Int?) {
        mutateRule {
            val d=session.ruleDraft ?: return@mutateRule;val old=index?.let{d.profile.rules[it]}
            val kinds=listOf("exact","prefix","contains","glob");var kind=old?.matchKind ?: "exact"
            val pattern=input("线程名，例如 GameThread",old?.pattern.orEmpty())
            val classes=d.profile.rules.map{it.competitionClass}.distinct().toMutableList()
            val newClass=(0..64).map{"group$it"}.first{it !in classes};classes+=newClass
            var cls=old?.competitionClass ?: newClass;var selector=old?.selector ?: "all";var mask=old?.cpuMask ?: emptySet()
            val rank=input("",selector.substringAfter(':',"1")).apply{
                inputType=android.text.InputType.TYPE_CLASS_NUMBER;background=null;setPadding(0,0,0,0);minimumHeight=0;gravity=Gravity.CENTER
                contentDescription="排名 N（1–1024）"
            }
            val stepper=owner.row().apply{
                fun step(text:String,delta:Int)=owner.label(text,12f,owner.text,800).apply{
                    gravity=Gravity.CENTER;background=owner.shape(owner.card2,7f);isFocusable=true;contentDescription=if(delta<0)"降低排名" else "提高排名"
                    setOnClickListener{rank.setText(((rank.text.toString().toIntOrNull() ?: 1)+delta).coerceIn(1,1024).toString())};owner.press(this)
                }
                addView(step("−",-1),LinearLayout.LayoutParams(dp(26),dp(26)))
                addView(rank,LinearLayout.LayoutParams(dp(48),dp(26)))
                addView(step("+",1),LinearLayout.LayoutParams(dp(26),dp(26)))
                visibility=if(selector=="all")View.GONE else View.VISIBLE
            }
            val box=owner.column()
            box.addView(owner.formRow("匹配方式",segment(listOf("精确","前缀","包含","通配"),kinds.indexOf(kind)){kind=kinds[it]}))
            box.addView(owner.formRow("匹配内容",pattern))
            box.addView(owner.formRow("竞争组",HorizontalScrollView(this@MainActivity).apply{
                isHorizontalScrollBarEnabled=false
                addView(segment(classes.mapIndexed{i,_->"组 ${('A'.code+i).toChar()}"},classes.indexOf(cls)){cls=classes[it]},android.widget.FrameLayout.LayoutParams(dp(classes.size*52),dp(34)))
            }))
            box.addView(owner.formRow("竞争选择",owner.row().apply{
                addView(segment(listOf("全部候选","按排名"),if(selector=="all")0 else 1){selector=if(it==0)"all" else "rank";stepper.visibility=if(it==0)View.GONE else View.VISIBLE},LinearLayout.LayoutParams(dp(200),dp(32)))
                addView(stepper,LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(8)})
            }))
            box.addView(owner.formRow("CPU 范围",cpuPicker(mask,true){mask=it}))
            ownerModal?.open(if(index==null)"添加特殊线程规则" else "编辑规则 ${index+1}","同组候选一起排名；选择全部候选或排名第 N 的候选。",480,box,listOf(
                owner.button("取消"){ownerModal?.close()},owner.button("加入草稿","primary"){
                    val selected=if(selector=="all")"all" else "rank:${rank.text}"
                    if(pattern.text.isBlank() || pattern.text.length>64 || mask.isEmpty() || selected!="all" && rank.text.toString().toIntOrNull() !in 1..1024){toast("请填写合法匹配内容、排名和 CPU");return@button}
                    val rows=d.profile.rules.toMutableList();val r=ZuioptRuleModel.Rule(cls,kind,pattern.text.toString(),selected,0,mask)
                    if(index==null){if(rows.size>=32){toast("最多 32 条规则");return@button};rows+=r}else rows[index]=r
                    d.profile=d.profile.copy(rules=rows.map{if(it.competitionClass==cls)it.copy(selector=selected,cpuMask=mask)else it})
                    ownerModal?.close();render()
                }))
        }
    }
    private fun loadAnalysis(pkg: String) {
        val read = ++analysisRead
        session.directRead { val result = runCatching { session.gateway.readRecordLinkedThreadAnalysis(pkg) }
            handler.post { if (read != analysisRead || session.selected != pkg || !visible) return@post
                result.onSuccess { analysis = it }.onFailure { analysisError = it.message.orEmpty() }; render() }
        }
    }
    private fun analysisCard() {
        val a = analysis
        detail.addView(card().apply {
            addView(owner.section("记录关联线程分析","读取已完成记录，由你决定 CPU 放置"))
            if (a == null || a.length() == 0) {
                addView(owner.empty("暂无可用于线程分析的监测记录","请先开启性能监视悬浮窗，并对该应用完成一次记录。"))
                if (analysisError.isNotEmpty()) addView(note(analysisError, orange))
                addView(button("开启监测与查看记录指引") { if (!this@MainActivity.overlay.displayed) toggleOverlay(); monitorGuidance() }); return@apply
            }
            if (a.optInt("user", -1) != session.userId || a.optString("package") != session.selected) { addView(note("记录关联分析身份不匹配", orange)); return@apply }
            addView(note("来源记录 #${a.getLong("sourceRecordId")} · ${whenRecorded(a.getLong("sourceRecordWall"))} · ${duration(a.getLong("wallElapsedMs"))}\n${a.getString("sourceRecordCompletion")} · ${a.getString("sourceRecordTerminalReason")}\n有效样本 ${a.getInt("eligibleSamples")} · 分段 ${a.getInt("processSegmentCount")}"))
            val rows = a.getJSONArray("threads"); addView(note("${rows.length()} 个线程名组 · 单核 CPU = 100%")); val chosen = linkedSetOf<String>()
            val widths=listOf(0,58,63,63,63,110,52)
            addView(owner.tableRow(listOf("线程名","同名 / 并发","平均 CPU","峰值 CPU","出现率","排名中位 · Top1/3","样本").map{owner.label(it,10f,owner.muted,700)},widths,true))
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                val name=r.getString("name")
                val choose=owner.check(name,action={yes->if(yes)chosen+=name else chosen-=name})
                val values=listOf("${r.getInt("distinctIdentityCount")} / ${r.getInt("sameNameConcurrencyMax")}","${number(r.optDouble("avgCpuPct"))}%","${number(r.optDouble("peakCpuPct"))}%","${number(r.optDouble("presencePct"))}%","${number(r.optDouble("medianRank"))} · ${number(r.optDouble("top1SharePct"))}/${number(r.optDouble("top3SharePct"))}%",r.getInt("presenceSamples").toString())
                addView(owner.tableRow(listOf(choose)+values.map{owner.label(it,10.5f,owner.text,600)},widths))
            }
            addView(button("加入规则草稿", true) { mutateRule {
                val d = session.ruleDraft ?: return@mutateRule
                if (chosen.isEmpty()) { toast("请先选择线程名组"); return@mutateRule }
                if (chosen.any { it.length > 64 || it.any { c -> c.code !in 32..126 } }) { toast("所选线程名无法表示为 Schema2 匹配，请手动编辑"); return@mutateRule }
                if (d.profile.rules.size + chosen.size > 32) { toast("最多 32 条规则"); return@mutateRule }
                val used = d.profile.rules.map { it.competitionClass }.toMutableSet()
                val additions = chosen.map { name -> val group = (0..64).map { "group$it" }.first { it !in used }; used += group
                    ZuioptRuleModel.Rule(group, "exact", name, "all", 0, emptySet()) }
                d.profile = d.profile.copy(rules = d.profile.rules + additions); render()
            } })
        }, gap())
    }

    private fun readRecord(pkg: String, threads: Boolean = false,completed:(()->Unit)?=null) {
        val token=++recordEpoch;recordRequested=true;recordDetailLoading=true;threadDetailLoading=false
        selectedRecord=null;recordThreads=threads;selectedThread=null
        cpuSparklines.cancel();render();OwnerRenderTrace.event("RECORD_VISUAL_SELECTION","$token/$pkg")
        read("recordRead/$token",accept={token==recordEpoch && session.section=="monitor" && session.selected==pkg},finished={recordDetailLoading=false},
            fetch={PerformanceMonitor.command("recordRead",JSONObject().put("package",pkg).put("threads",threads).put("thread","").toString())},parse={JSONObject(it)}) { fresh ->
            check(fresh.optString("package")==pkg && fresh.optInt("user",-1)==session.userId && fresh.optLong("recordId")>0){"记录身份不匹配"}
            selectedRecord=fresh;if(visible)render();completed?.invoke()
        }
    }
    private fun readThread(key:String,name:String){
        val record=selectedRecord ?: return;val pkg=record.optString("package");val id=record.optLong("recordId")
        val token=++recordEpoch;threadDetailLoading=true;selectedThread=null;cpuSparklines.cancel();render()
        read("recordThread/$token",accept={token==recordEpoch && session.section=="monitor" && session.selected==pkg},finished={threadDetailLoading=false},
            fetch={PerformanceMonitor.command("recordRead",JSONObject().put("package",pkg).put("threads",false).put("thread",key).toString())},parse={JSONObject(it)}) { data ->
            check(data.optString("package")==pkg && data.optLong("recordId")==id && data.has("detail")){"记录线程身份不匹配"}
            selectedThread=data.put("key",key).put("name",name)
            if(visible)render()
        }
    }
    private fun monitorPage() {
        val r = selectedRecord
        if(r!=null && r.has("package")){
            val actions=owner.row().apply{
                addView(owner.button("导出记录",small=true,icon=R.drawable.owner_export){export(r.toString(2).toByteArray(),"ZuiControl_record.json")})
                addView(owner.button("删除","danger",true,R.drawable.owner_trash){confirm("删除记录？","仅删除此应用最近一次记录。"){
                    session.work("已删除",feature="records"){val reply=PerformanceMonitor.command("recordDelete",session.selected);check(reply.startsWith("ok=1")){reply};selectedRecord=null}
                }},LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(8)})
            }
            detail.addView(owner.title(selectedThread?.optString("name") ?: if(recordThreads)"线程运行记录" else name(session.selected),"${whenRecorded(r.optLong("wall"))} · ${duration(r.optLong("duration"))} · ${if(r.optBoolean("complete"))"已结束" else r.optString("terminalReason","未完成")}",leading=if(recordThreads)owner.back(if(selectedThread!=null)"返回线程记录" else "返回记录详情"){if(selectedThread!=null){selectedThread=null;render()}else readRecord(session.selected)}else null,trailing=if(recordThreads)null else actions))
        }else if(recordDetailLoading)heading(name(session.selected),"正在读取记录…")
        recordBanner = row().apply {
            background = shape(field, 14); recordLabel = label("", 13f, orange, true)
            addView(recordLabel, LinearLayout.LayoutParams(0, -2, 1f)); addView(button("停止") { session.work("记录已停止",feature="records") {
                val reply = session.gateway.stopRecord(); check(reply.ok) { reply.text }
            } }); visibility = View.GONE
        }; detail.addView(recordBanner, gap())
        if(recordDetailLoading || threadDetailLoading){detail.addView(note("正在读取记录…"));return}
        if (r == null || !r.has("package")) { detail.addView(owner.empty("暂无应用监测","开启性能监视悬浮窗后可生成记录"),gap()); detail.addView(button("查看指引") { monitorGuidance() }); return }
        selectedThread?.let{thread->
            detail.addView(card().apply{
                addView(owner.section("CPU 时间线","入榜期间单核百分比；缺失样本保留断档"))
                addView(RecordChart(this@MainActivity,thread.optJSONArray("detail") ?: JSONArray(),1,thread.optLong("duration")),LinearLayout.LayoutParams(-1,dp(208)))
                addView(note(thread.optString("key")));addView(note("无已保存 Top15 样本不代表 CPU=0。"))
            },gap());return
        }
        if (recordThreads) {
            detail.addView(note("Top15 入榜线程；入榜均值不是整段平均。时间线断档表示无已保存的 Top15 样本，不代表 CPU=0。"), gap())
            detail.addView(owner.cpuThreadTable(r,cpuSparklines){key,name->readThread(key,name)},gap());return
        }
        val policy = r.optJSONObject("policySnapshot")
        detail.addView(note(if (policy == null || policy.length() == 0) "记录开始时未保存策略快照" else
            "本次策略：${snapshotValue(policy, "refreshHz")} Hz · ${modeTitle(policy.optString("uperfMode"))} · GPU ${snapshotValue(policy, "gpuMinMHz")}–${snapshotValue(policy, "gpuMaxMHz")} MHz · ${when (policy.optString("gpuPolicy")) { "DEFAULT_FOR_MODE" -> "默认"; "CUSTOM" -> "自定义"; else -> "未知" }}\n策略 generation ${snapshotValue(policy, "policyGeneration")} · ZUIopt ${policy.optString("zuioptGeneration")} · ${policy.optString("profileSummaryValidity")}"), gap())
        val scalars = r.optJSONArray("scalars") ?: JSONArray(); val stats = r.optJSONArray("stats")?.optJSONArray(0) ?: JSONArray()
        fun chart(index:Int,title:String,height:Int)=card().apply{
            setPadding(dp(16),dp(14),dp(16),dp(10))
            addView(owner.row().apply{
                addView(View(this@MainActivity).apply{background=owner.shape(when(index){1->owner.tiers[0];2->owner.tiers[2];else->owner.accent},99f)},LinearLayout.LayoutParams(dp(8),dp(8)).apply{marginEnd=dp(8)})
                addView(owner.label(title,13f,owner.text,800),LinearLayout.LayoutParams(0,-2,1f))
                val prefix=if(index==0)"最低 ${number(stats.optDouble(index*3,Double.NaN))} · " else ""
                addView(owner.label("${prefix}平均 ${number(stats.optDouble(index*3+1,Double.NaN))} · 最高 ${number(stats.optDouble(index*3+2,Double.NaN))}",10f,owner.muted,600))
            },LinearLayout.LayoutParams(-1,dp(22)))
            addView(RecordChart(this@MainActivity,scalars,index+1,r.optLong("duration")),LinearLayout.LayoutParams(-1,dp(height)))
        }
        detail.addView(chart(0,"帧率 · FPS",155),gap())
        detail.addView(owner.row().apply{
            addView(chart(1,"功耗 · W",120),LinearLayout.LayoutParams(0,-2,1f))
            addView(chart(2,"温度 · quiet ℃",120),LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(12)})
        },gap())
        detail.addView(note("功耗统计不含插电/不可用时段，曲线断档保留缺失；FPS 为 DISPLAY_MEASURED_FPS。"))
        // #viewMonitor .card.row overrides the generic row padding to 12×18.
        // Native stroke is inset, so include the Owner 1px border in content inset.
        detail.addView(card().apply{setPadding(dp(19),dp(13),dp(19),dp(13));addView(owner.domainRow("线程运行记录","Top15 入榜线程 · 点击查看 CPU 时间线",R.drawable.owner_thread_list){readRecord(session.selected,true)})},gap())
    }

    private fun settingsPage() {
        when (session.settingsModule) {
            0 -> {
                heading("监测与显示","悬浮窗权限、通知状态与界面主题")
                val nm=getSystemService(NotificationManager::class.java)
                val enabled=nm.areNotificationsEnabled() && nm.getNotificationChannel("zui_control_monitor_v1")?.importance!=NotificationManager.IMPORTANCE_NONE
                detail.addView(card().apply{
                    setPadding(dp(18),dp(14),dp(18),dp(14));addView(owner.label("权限与通知",11.5f,owner.muted,800),owner.gap(4))
                    val granted=Settings.canDrawOverlays(this@MainActivity)
                    addView(owner.settingsRow("悬浮窗权限","性能监视悬浮窗与记录开始 / 停止手势依赖此权限",if(granted)owner.chip("已授权",1,true).apply{isFocusable=true;contentDescription="悬浮窗权限已授权";setOnClickListener{overlayPermission()}}else owner.button("去授权",small=true){overlayPermission()}))
                    addView(View(this@MainActivity).apply{setBackgroundColor(owner.line)},LinearLayout.LayoutParams(-1,dp(1)))
                    addView(owner.settingsRow("通知","故障保护与监测记录状态提醒",owner.row().apply{
                        addView(owner.chip(if(enabled)"已开启" else "已关闭",if(enabled)1 else 0,true))
                        if(!enabled)addView(owner.button("系统通知设置",small=true){ownedActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))},LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(8)})
                    }))
                },gap())
                detail.addView(card().apply{
                    setPadding(dp(18),dp(14),dp(18),dp(14));addView(owner.label("显示",11.5f,owner.muted,800),owner.gap(4))
                    val values=listOf("system","dark","light")
                    val themeSegment=segment(listOf("跟随系统", "深色", "浅色"),values.indexOf(prefs.getString("theme","system"))){theme(values[it])} as OwnerSegment
                    addView(owner.settingsRow("界面主题","侧边栏按钮可快速切换深色 / 浅色",themeSegment.apply{layoutParams=LinearLayout.LayoutParams(dp(220),dp(34))}))
                    bindPresentation{themeSegment.showSelection(values.indexOf(prefs.getString("theme","system")))}
                },gap())
            }
            1 -> gpuDefaultsPage()
            2 -> {
                heading("数据与维护", "配置备份、恢复与系统维护")
                val rows=mutableListOf<View>()
                rows+=actionRow("立即备份", prefs.getString("backup", "保存到你选择的位置").orEmpty()) {
                    read("backupExport",shared=true,fetch={SettingsBackup.export(applicationContext)},parse={it}){backupBytes=it;session.pendingDocument=103;presentPending()}
                }
                rows+=actionRow("从备份恢复", "校验 → 摘要 → 确认") { document(Intent.ACTION_OPEN_DOCUMENT, 104, "application/zip") }
                rows+=actionRow("恢复出厂配置", "保留监测记录与上游基线") { confirm("恢复出厂配置？", "清除本用户应用策略，恢复全局档位、GPU 默认与偏好；线程规则恢复当前上游基线。保留监测记录、上游版本与诊断记录。") {
                    session.work("已恢复出厂配置",feature="restore") { SettingsBackup.factoryReset(applicationContext) }
                } }
                rows+=actionRow("导出运行日志", "可能包含应用与使用记录") { confirm("导出运行日志？", "日志可能包含已安装应用和使用信息，请妥善保存。") {
                    read("logsExport",shared=true,fetch={val id=ZuiControlRequest.send(applicationContext,ZuiControlContract.CMD_EXPORT_LOGS);val ack=ZuiControlRequest.awaitTerminalAck(applicationContext,id);check(ack.succeeded){ack.detail};ZuiControlClient.utilityValue("result","$id|logs").toByteArray()},parse={it}){exportBytes=it;session.pendingDocument=101;presentPending()}
                } }
                rows+=actionRow("重启调度核心", "Uperf 与 ZUIopt；已保存配置保留") { confirm("重启调度核心？", "短暂恢复 Android 调度后重新加载已保存配置。") {
                    session.work("调度核心已重启",feature="diagnostics") { val id = ZuiControlRequest.send(applicationContext, ZuiControlContract.CMD_RESTART_SCHEDULER); val ack = ZuiControlRequest.awaitTerminalAck(applicationContext, id); check(ack.succeeded) { ack.detail } }
                } }
                listOf("备份" to rows.take(1),"恢复" to rows.subList(1,3),"维护" to rows.takeLast(2)).forEach{(title,items)->
                    detail.addView(card().apply{
                        setPadding(dp(18),dp(14),dp(18),dp(14));addView(owner.label(title,11.5f,owner.muted,800))
                        items.forEachIndexed{i,v->if(i>0)addView(View(this@MainActivity).apply{setBackgroundColor(owner.line)},LinearLayout.LayoutParams(-1,dp(1)));addView(v)}
                    },gap())
                }
            }
            else -> {
                heading("关于","版本信息与使用帮助")
                detail.addView(card().apply{
                    addView(owner.row().apply{
                        addView(owner.column().apply{addView(owner.label("ZUI",22f,owner.accent,900));addView(owner.label("CONTROL",9f,owner.muted,700))},LinearLayout.LayoutParams(dp(64),-2).apply{marginEnd=dp(14)})
                        addView(owner.column().apply{addView(owner.label("ZuiControl",17f,owner.text,800));addView(note(value(caps,"releaseVersion").ifEmpty{"暂不可用"}))})
                    })
                },gap())
                detail.addView(card().apply{
                    addView(owner.label("版本信息",11f,owner.muted,800))
                    listOf("Release" to "releaseVersion","Build" to "sourceBuild","Integration" to "integrationSchema","AppPolicy" to "appPolicySchema","ZUIopt" to "zuioptSchema").forEach{(title,key)->addView(owner.formRow(title,note(value(caps,key).ifEmpty{"暂不可用"})))}
                },gap())
                detail.addView(card().apply{addView(actionRow("使用帮助","功能说明与监测记录指引"){monitorGuidance()})},gap())
            }
        }
    }
    private var themeFramePending=false
    private fun theme(theme: String) {
        if(prefs.getString("theme","system")==theme && OwnerWindow.dark(this)==owner.dark)return
        prefs.edit().putString("theme",theme).apply()
        pageBindings.toList().forEach{it()}
        if(themeFramePending)return
        themeFramePending=true
        // Commit the palette and status-region bridge at the next presentation
        // boundary. Pending requests read the latest local theme.
        ownerCanvas.postOnAnimation {
        if(isDestroyed)return@postOnAnimation
        val dark=OwnerWindow.dark(this)
        if(dark==owner.dark){themeFramePending=false;return@postOnAnimation}
        OwnerGeometry.theme(ownerCanvas,0,"persistent")
        OwnerWindow.transitionSystemBars(this,physicalHost,dark,palette={
            owner.applyTheme(physicalHost,dark)
            physicalHost.setBackgroundColor(owner.detail);ownerCanvas.setBackgroundColor(owner.detail)
            ownerHost.setBackgroundColor(owner.detail);shellRoot.setBackgroundColor(owner.detail)
            rail.setBackgroundColor(owner.rail);master.setBackgroundColor(owner.master)
            railBindings.forEach{it()};masterSelectionBindings.forEach{it()};pageBindings.toList().forEach{it()};bindMonitor()
            OwnerRenderTrace.event("PALETTE_CHANGED",prefs.getString("theme","system").orEmpty())
            OwnerGeometry.theme(ownerCanvas,100,"persistent")
        }){
            themeFramePending=false
            val latest=prefs.getString("theme","system").orEmpty()
            if(!isDestroyed && OwnerWindow.dark(this)!=owner.dark)theme(latest)
        }
        }
    }
    private fun monitorGuidance() {
        ownerModal?.open("监测记录指引","通过悬浮窗开始记录，在监测页可停止。",480,owner.column().apply{
            listOf("轻触长条切换圆形，双击圆形开始记录。","记录中轻触悬浮窗，或在监测页点击停止。","切换业务应用、锁屏或满 30 分钟自动结束。","线程分析读取该记录产生的结果，CPU 放置由你选择。").forEach{addView(note("•  $it"),gap())}
        },listOf(owner.button("知道了","primary"){ownerModal?.close()}))
    }
    private fun ownedActivity(intent:Intent){session.ownedExternalFlow=true;try{startActivity(intent)}catch(e:RuntimeException){session.ownedExternalFlow=false;throw e}}
    private fun overlayPermission() { ownedActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
    private fun export(bytes: ByteArray, title: String) { exportBytes = bytes; document(Intent.ACTION_CREATE_DOCUMENT, 101, "text/plain", title) }
    @Suppress("DEPRECATION")
    private fun document(action: String, code: Int, mime: String, title: String = "") { session.ownedExternalFlow=true;try{startActivityForResult(Intent(action).apply {
        addCategory(Intent.CATEGORY_OPENABLE); type = mime; if (title.isNotEmpty()) putExtra(Intent.EXTRA_TITLE, title)
    }, code)}catch(e:RuntimeException){session.ownedExternalFlow=false;throw e} }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data); if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            101 -> session.work("已导出",feature="export") { check(exportBytes.isNotEmpty()); checkNotNull(contentResolver.openOutputStream(uri, "wt")).use { it.write(exportBytes) }; exportBytes = byteArrayOf() }
            102 -> session.work {
                val b = checkNotNull(baseline); incoming = ZuioptRules.readDocument(applicationContext, uri, "appopt"); appOpt = true
                source = ZuioptLibrary.Provenance("AppOpt", "manual", evidence = ZuioptRules.digest(incoming)); decisions.clear(); manual = null; stagePreview(b)
            }
            103 -> session.work("备份已保存",feature="export") { SettingsBackup.save(applicationContext, uri, backupBytes); backupBytes = byteArrayOf(); prefs.edit().putString("backup", whenRecorded(System.currentTimeMillis())).apply() }
            104 -> session.work(feature="backup") {
                session.pendingInspection = SettingsBackup.inspect(applicationContext, uri)
            }
        }
    }
    private var inspectionShowing = false
    private fun presentPending() {
        if (session.busy || !visible) return
        if (session.pendingDocument != 0) {
            val code = session.pendingDocument; session.pendingDocument = 0
            document(Intent.ACTION_CREATE_DOCUMENT, code, if (code == 103) "application/zip" else "text/plain",
                if (code == 103) "ZuiControl_settings.zip" else "ZuiControl_logs.txt")
        }
        val inspection = session.pendingInspection ?: return
        if (inspectionShowing) return
        val s = inspection.summary
        val summary = "兼容性：${s.optString("compatibility")}\n来源：${s.optString("sourceBuild")}\n用户范围：${s.opt("userScope")}\n应用策略：${s.optInt("appPolicyRows")} 条\nGPU 默认：${if (s.optBoolean("gpuDefaultsPresent")) "包含" else "无"}\n线程规则：${if (s.optBoolean("rulesPresent")) "包含" else "无"}\n悬浮窗偏好：${if (s.optBoolean("preferencesPresent")) "包含" else "无"}\n校验 SHA256：${inspection.hash}"
        fun decide(restore: Boolean) { if(session.pendingInspection!==inspection)return;inspectionShowing=false;session.pendingInspection = null; session.work(if (restore) "已恢复" else "",feature=if(restore)"restore" else "backup") {
            if (restore) SettingsBackup.restore(applicationContext, inspection) else SettingsBackup.abort(inspection)
        } }
        inspectionShowing=true
        ownerModal?.open("恢复摘要","校验后的备份摘要；确认后才会恢复。",480,owner.scroll(note(summary),300),listOf(
            owner.button("取消"){ownerModal?.close()},owner.button("确认恢复","primary"){decide(true);ownerModal?.close()}),onDismiss={decide(false)})
    }
    private fun snapshotValue(j: JSONObject, key: String) = if (j.has(key) && !j.isNull(key)) j.get(key).toString() else "--"
    private fun supportedRates() = value(caps, "supportedDisplayHz").split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }.distinct()
    private fun globalRanges(): Map<String, GpuRanges.Range> = session.gpuAuthority?.original ?: GpuDefaultsDraft.fromState(state, session.userId).original
    private fun provenance(pkg: String) = upstreamModel?.let { model?.provenance(pkg, it) }.orEmpty()
    private fun sourceTitle(source: String) = when (source) { "UPSTREAM" -> "上游"; "USER_MODIFIED" -> "我的修改"; "USER_CREATED" -> "我的新建"; else -> "来源未知" }
    private fun provenanceChip(pkg:String,large:Boolean=false)=owner.chip(sourceTitle(provenance(pkg)),if(provenance(pkg)=="USER_MODIFIED")3 else 0,large).apply{
        if(provenance(pkg)=="USER_CREATED"){setTextColor(owner.zoFg);background=owner.shape(owner.zoBg,if(large)6f else 5f)}
    }
    private fun modeTitle(id: String) = UperfMode.fromId(id)?.title ?: "--"
    private fun value(source: String, key: String) = ZuiControlClient.stateValue(source, key).orEmpty()
    private fun name(pkg: String) = appMetadata[pkg]?.label ?: pkg
    private fun isSystem(app: ApplicationInfo) = app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    private fun whenRecorded(wall: Long) = if (wall > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(wall)) else "时间未知"
    private fun duration(ms: Long) = String.format(Locale.ROOT, "%02d:%02d", ms / 60000, ms / 1000 % 60)
    private fun number(n: Double) = if (!n.isFinite() || n < 0) "--" else String.format(Locale.ROOT, "%.1f", n)
    private fun toast(message: String) {
        if(!::ownerHost.isInitialized){Toast.makeText(this,message,Toast.LENGTH_SHORT).show();return}
        toastView?.let{(it.parent as? ViewGroup)?.removeView(it)}
        val pill=owner.label(message,12f,owner.toastFg,700).apply{
            tag="owner-toast-foreground"
            setPadding(owner.px(24),owner.px(11),owner.px(24),owner.px(11));background=owner.shape(owner.toastBg,999f)
            elevation=owner.px(12).toFloat();alpha=0f;translationY=owner.px(60).toFloat();importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val surface=FrameLayout(this).apply{background=owner.shape(Color.TRANSPARENT,999f);clipToOutline=true;alpha=0f;addView(pill);pill.alpha=1f;pill.translationY=0f}
        ownerHost.addView(surface,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply{bottomMargin=owner.px(24)})
        toastView=surface
        surface.post{
            // Owner toast blur is 8px (modal is 3px): cache only this small backdrop once.
            if(Build.VERSION.SDK_INT>=31 && surface.width>0 && surface.height>0){
                val bitmap=android.graphics.Bitmap.createBitmap(surface.width,surface.height,android.graphics.Bitmap.Config.ARGB_8888)
                val canvas=android.graphics.Canvas(bitmap);canvas.translate(-surface.left.toFloat(),-surface.top.toFloat());ownerHost.draw(canvas)
                val backdrop=ImageView(this).apply{setImageBitmap(bitmap);scaleType=ImageView.ScaleType.FIT_XY;setRenderEffect(android.graphics.RenderEffect.createBlurEffect(owner.px(8).toFloat(),owner.px(8).toFloat(),android.graphics.Shader.TileMode.CLAMP))}
                surface.addView(backdrop,0,FrameLayout.LayoutParams(-1,-1))
            }
            surface.translationY=owner.px(60).toFloat();surface.animate().alpha(1f).translationY(0f).setDuration(300).setInterpolator(OwnerUi.spring).start()
        }
        pill.announceForAccessibility(message)
        handler.postDelayed({surface.animate().alpha(0f).translationY(owner.px(60).toFloat()).setDuration(300).withEndAction{(surface.parent as? ViewGroup)?.removeView(surface)}.start()},2600)
    }
    private fun confirm(title: String,message: String,action:()->Unit) {
        val kind=if(title.contains("删除") || title.contains("恢复") || title.contains("重启") || title.contains("回退"))"danger-fill" else "primary"
        ownerModal?.open(title,message,360,owner.column(),listOf(owner.button("取消"){ownerModal?.close()},owner.button("确认",kind){ownerModal?.close();action()}))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun row() = owner.row()
    private fun column() = owner.column()
    private fun shape(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), stroke) }
    private fun translucent(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    private fun meter() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100; progressTintList = android.content.res.ColorStateList.valueOf(accent)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(field)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun mergeAppOpt(pkg:String) {
        val mine=manual?.appProfile(pkg) ?: model?.appProfile(pkg)
        var mask=mine?.generalMask ?: emptySet();val kept=mine?.rules.orEmpty().toMutableList()
        val pattern=input("可选：添加精确线程名")
        val box=column().apply{
            addView(note("以当前规则为基础，选择 CPU、保留线程或手动添加；采用导入配置请选「采用转换」。"))
            addView(owner.formRow("默认 CPU",cpuPicker(mask,true){mask=it}))
            mine?.rules?.forEach{rule->addView(owner.check("${when(rule.matchKind){"exact"->"精确";"prefix"->"前缀";"contains"->"包含";else->"通配"}} ${rule.pattern} · ${if(rule.selector=="all")"全部候选" else "第${rule.selector.substringAfter(':')}个"} · CPU ${cpuRange(rule.cpuMask)}",true){yes->if(yes){if(rule !in kept)kept+=rule}else kept-=rule})}
            addView(owner.formRow("新增线程",pattern));addView(note("新增精确规则使用上方选择的 CPU；已有规则保持原竞争组和 CPU。"))
        }
        ownerModal?.open("手动合并 · ${name(pkg)}","选择要保留的规则后重新预览",480,owner.scroll(box,320),listOf(owner.button("取消"){ownerModal?.close()},owner.button("重新预览","primary"){
            if(mask.isEmpty() || pattern.text.length>64 || pattern.text.any{it.code !in 32..126}){toast("请选择 CPU 并填写合法线程名");return@button}
            val ordered=mine?.rules.orEmpty().filter{it in kept}.toMutableList()
            if(pattern.text.isNotBlank()){
                if(ordered.size>=32){toast("最多 32 条规则");return@button}
                val alias=(0..64).map{"group$it"}.first{name->ordered.none{it.competitionClass==name}}
                ordered+=ZuioptRuleModel.Rule(alias,"exact",pattern.text.toString(),"all",0,mask)
            }
            val profile=ZuioptRuleModel.Profile(mask,ordered.mapIndexed{i,r->r.copy(priority=100000-i)})
            ownerModal?.close();session.work{
                val base=manual ?: ZuioptRuleModel(true,emptyMap(),emptyList())
                manual=if(base.appProfile(pkg)==null)addProfile(base,pkg,profile)else base.editApp(pkg){profile}
                decisions[pkg]=ZuioptLibrary.Decision.MANUAL_MERGE;stagePreview(checkNotNull(this@MainActivity.baseline))
            }
        }))
    }
    private fun card() = owner.card()
    private fun gap() = owner.gap()
    private fun label(text: String,size: Float=13f,color: Int=ink,bold: Boolean=false)=owner.label(text,size,color,if(bold)800 else 400).apply{
        setSingleLine(false);setLineSpacing(0f,1.35f)
    }
    private fun heading(title: String,subtitle: String) { detail.addView(owner.title(title,subtitle)) }
    private fun note(text: String,color: Int=sub)=label(text,11.5f,color).apply{setPadding(0,dp(4),0,dp(4))}
    private fun button(title: String,selected: Boolean=false,action:()->Unit={})=owner.button(title,if(selected)"primary" else "ghost",action=action)
    private fun segment(values: List<String>,selected: Int,enabled: Boolean=true,action:(Int)->Unit):View = OwnerSegment(this,owner,values,selected,true,enabled,action)
    private fun tiers(current: String,enabled: Boolean,action:(String)->Unit):View = owner.tiers(current,false,enabled,action=action)
    private fun cpuPicker(initial: Set<Int>,large:Boolean=false,action:(Set<Int>)->Unit):View = owner.cpus(initial,large){next->
        if(next.isEmpty()){toast("至少保留一个 CPU");false}else{action(next);true}
    }
    private fun input(hint: String,text: String="")=EditText(this).apply{
        this.hint=hint;setText(text);setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,12.5f);setTextColor(owner.text);setHintTextColor(owner.muted)
        setSingleLine(true);includeFontPadding=false;typeface=Typeface.MONOSPACE
        background=owner.shape(owner.code,10f,owner.line2)
        setPadding(dp(12),0,dp(12),0);minimumHeight=dp(34);inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    private fun watcher(action:(String)->Unit)=object:TextWatcher{
        override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
        override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){action(s.toString())}
        override fun afterTextChanged(s:Editable?)=Unit
    }
    private fun actionRow(title: String,subtitle: String,action:()->Unit)=owner.row().apply{
        minimumHeight=dp(46);setPadding(0,dp(6),0,dp(6))
        val icon=when(title){"立即备份"->R.drawable.owner_backup;"从备份恢复"->R.drawable.owner_restore;"恢复出厂配置"->R.drawable.owner_reset;"导出运行日志"->R.drawable.owner_logs;"重启调度核心"->R.drawable.owner_restart;"使用帮助"->R.drawable.owner_help;"ZUIopt 已进入故障保护"->R.drawable.owner_warn;else->error("Owner row icon is required: $title")}
        val tone=when(title){"恢复出厂配置","重启调度核心"->owner.inks[3];"导出运行日志","使用帮助"->owner.muted;else->owner.accent}
        addView(owner.icon(icon,tone,16).apply{background=owner.shape(owner.soft(tone),10f);setPadding(dp(8),dp(8),dp(8),dp(8))},LinearLayout.LayoutParams(dp(32),dp(32)).apply{marginEnd=dp(12)})
        addView(owner.column().apply{addView(owner.label(title,13f,owner.text,800));addView(note(subtitle,owner.muted))},LinearLayout.LayoutParams(0,-2,1f))
        val actionLabel=when(title){"立即备份"->"备份";"从备份恢复"->"选择文件";"恢复出厂配置"->"重置";"导出运行日志"->"导出";"重启调度核心"->"重启";else->""}
        if(actionLabel.isEmpty())addView(owner.icon(R.drawable.owner_chevron,owner.muted,16))else addView(owner.button(actionLabel,when(title){"立即备份"->"primary";"恢复出厂配置","重启调度核心"->"warn-outline";else->"ghost"},true){if(!session.busy)action()})
        isFocusable=true;contentDescription=title;setOnClickListener{if(!session.busy)action()};owner.press(this)
    }
    private fun listRow(pkg: String,title: String,subtitle: String,selected: Boolean,action:()->Unit)=ownerListRow(pkg,title,subtitle,selected,if(session.section=="thread")R.drawable.owner_chip else R.drawable.owner_pulse){action()}
}
