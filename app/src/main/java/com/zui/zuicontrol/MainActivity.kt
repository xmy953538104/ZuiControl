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
    private val owner by lazy { OwnerUi(this) }
    private lateinit var ownerHost: FrameLayout
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
    private fun localRender() { handler.removeCallbacks(renderDraft); handler.postDelayed(renderDraft, 360) }

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
    private var themeReady=false
    private data class ThemeFrame(val bitmap:android.graphics.Bitmap,val detailScroll:Int,val masterScroll:Int,val user:Int,val query:String,val record:JSONObject?,val threads:Boolean,val analysis:JSONObject?,val analysisPage:Boolean)
    companion object { private var themeFrame:ThemeFrame?=null }
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
    private var recordRequested = false
    private var recordThreads = false
    private var analysis: JSONObject? = null
    private var analysisError = ""
    private var analysisRead = 0
    private var analysisPage = false
    private var reading = false
    private var boundGeneration = ""
    private val controlsChanged: () -> Unit = {
        if (visible && session.section == "tune" && session.selected.isEmpty()) {
            val oldHz = refresh.displayed; val oldMode = mode.displayed
            observeGlobals()
            if (oldHz != refresh.displayed || oldMode != mode.displayed) render()
        }
        val generation = value(ControlsState.snapshot, "policyGeneration")
        if (visible && generation.isNotEmpty() && generation != boundGeneration && !session.busy) load()
    }
    private val changed: () -> Unit = {
        if (visible && !isDestroyed) { render(); if (!session.busy) { presentPending(); load() } }
    }
    private val accent get() = owner.accent
    private val ink get() = owner.text
    private val sub get() = owner.sub
    private val field get() = owner.card2
    private val surface get() = owner.card
    private val orange get() = owner.inks[2]

    override fun attachBaseContext(base: Context) {
        val theme=base.getSharedPreferences("frontend",MODE_PRIVATE).getString("theme","system").orEmpty()
        val themed=if(theme=="system")base else base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if(FrontendTheme.dark(theme,false))Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        })
        super.attachBaseContext(themed)
    }
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        session = (lastNonConfigurationInstance as? FrontendSession)?.takeIf { it.userId == ZuiControlClient.currentUserId() }
            ?: FrontendSession(V84FrontendGateway(applicationContext), ZuiControlClient.currentUserId())
        if (lastNonConfigurationInstance == null && saved != null) session.restore(saved)
        themeFrame?.takeIf{it.user==session.userId}?.let{query=it.query;selectedRecord=it.record;recordThreads=it.threads;recordRequested=it.record!=null;analysis=it.analysis;analysisPage=it.analysisPage}
        window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply { hide(WindowInsets.Type.systemBars()); systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        }
        val appContext = applicationContext
        Thread { runCatching { ZuiControlRequest.recoverPending(appContext) } }.start()
        render()
    }
    override fun onRetainNonConfigurationInstance(): Any = session
    override fun onSaveInstanceState(out: Bundle) { session.save(out); super.onSaveInstanceState(out) }
    override fun onResume() {
        super.onResume(); visible = true; session.onChanged = changed
        ControlsState.observe(controlsChanged); MonitorPresentation.observe(monitorChanged); load()
        ZuiControlQuickService.start(this)
        presentPending()
    }
    override fun onPause() {
        visible = false; session.onChanged = null; ControlsState.remove(controlsChanged)
        MonitorPresentation.remove(monitorChanged)
        handler.removeCallbacks(recordClock); handler.removeCallbacks(coreTicker); super.onPause()
    }
    override fun onDestroy() { if (!isChangingConfigurations) session.close(); super.onDestroy() }
    private fun load() {
        if (reading || session.busy) return
        reading = true
        Thread {
            val result = runCatching {
                val state = ZuiControlClient.stateText(); this@MainActivity.state = state
                caps = checkNotNull(ZuiControlManager.get()).getCapabilities()
                policies = ZuiControlClient.appPolicies()
                installed = packageManager.getInstalledApplications(0).sortedBy { name(it.packageName).lowercase(Locale.ROOT) }
                ruleError = ""
                runCatching {
                    ruleState = ZuioptRules.state(applicationContext)
                    val current = ZuioptRules.userRules(applicationContext); val up = ZuioptLibrary.baseline(applicationContext)
                    check(current.generation == up.generation) { "规则版本变化，请刷新" }
                    snapshot = current; baseline = up
                    model = ZuioptRuleModel.parseNormalized(current.text); upstreamModel = ZuioptRuleModel.parseNormalized(up.rules)
                }.onFailure { model = null; upstreamModel = null; ruleError = it.message.orEmpty() }
                records = JSONObject(PerformanceMonitor.command("recordList")).optJSONArray("records") ?: JSONArray()
                recordsReadAt = SystemClock.elapsedRealtime()
            }
            handler.post {
                reading = false;themeReady=true
                if (!visible || isDestroyed) return@post
                result.onFailure { session.error = it.message.orEmpty() }
                boundGeneration = value(ControlsState.snapshot, "policyGeneration")
                observeGlobals()
                runCatching { GpuDefaultsDraft.fromState(state, session.userId) }.onSuccess(session::observeGpuDefaults)
                if (!session.appDirty && session.appDraft != null) {
                    policies?.apps?.firstOrNull { it.draft.packageName == session.selected }?.draft?.let { session.appDraft = it; session.originalApp = it }
                }
                if (session.ruleDraft == null && session.section == "thread" && session.selected.isNotEmpty()) openRule(session.selected)
                render()
                if (session.section == "thread" && session.selected.isNotEmpty() && analysis == null && analysisError.isEmpty()) loadAnalysis(session.selected)
                if (session.section == "monitor" && session.selected.isNotEmpty() && !recordRequested) readRecord(session.selected)
            }
        }.start()
    }
    private fun observeGlobals() {
        val scene = ControlsState.snapshot
        val foreground = value(scene, "editableScenePackage")
        val inherited = value(scene, "editableSceneIsHome") == "true" || foreground.isNotEmpty() && policies?.apps?.none { it.draft.packageName == foreground } == true
        if (inherited) value(scene, "editableDisplayHz").toIntOrNull()?.let(refresh::observe)
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
        if (recordingChanged && !session.busy) load()
    }
    private fun bindMonitor() {
        val fresh = monitor.optLong("elapsedMs") > 0 && SystemClock.elapsedRealtime() - monitor.optLong("elapsedMs") in 0..monitor.optLong("ttlMs", 3500)
        val q = if (fresh) monitor.optDouble("quietC", -1.0) else -1.0
        val p = if (fresh) monitor.optDouble("powerW", -1.0) else -1.0
        quietLabel?.text = number(q); powerLabel?.text = if (p < 0) "--" else number(p)
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
    private fun render() {
        if (ownerModal?.isOpen == true) return
        if(session.busy && phaseOne && ::ownerHost.isInitialized){ownerPending();return}
        ownerControls.clear()
        handler.removeCallbacks(coreTicker); coreLabel = null;coreSlot=null;quietMeter = null; powerMeter = null
        quietLabel = null; powerLabel = null; powerReason = null; powerUnit = null; recordLabel = null; recordBanner = null; overlayButton = null
        ownerQuiet = null; ownerPower = null; ownerSwitch = null
        val root = owner.row().apply { setBackgroundColor(owner.detail) }
        fun navigate(key: String) { guard {
            session.section = key; session.selected = ""; session.clearDrafts(); query = ""; selectedRecord = null; recordRequested = false; analysisPage=false;render()
        } }
        val rail = owner.column().apply { setPadding(0, owner.px(22), 0, owner.px(18)); setBackgroundColor(owner.rail); gravity = Gravity.CENTER_HORIZONTAL }
        rail.addView(owner.column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(owner.label("ZUI",17f,owner.accent,900).apply{letterSpacing=.03f},LinearLayout.LayoutParams(-2,owner.px(17)))
            addView(owner.label("CONTROL",8f,owner.muted,700).apply{letterSpacing=.04f},LinearLayout.LayoutParams(-2,owner.px(8)).apply{topMargin=owner.px(4)})
        },LinearLayout.LayoutParams(-2,owner.px(29)).apply{topMargin=owner.px(6);bottomMargin=owner.px(30)})
        fun nav(key: String,title: String,icon: Int,gap: Int=8,action:()->Unit) {
            val selected=session.section==key
            val frame=FrameLayout(this).apply {
                val color=if(selected)owner.accent else owner.muted
                addView(owner.column().apply {
                    gravity=Gravity.CENTER;background=owner.shape(if(selected)owner.soft(owner.accent) else Color.TRANSPARENT,14f)
                    addView(owner.icon(icon,color,20),LinearLayout.LayoutParams(owner.px(20),owner.px(20)))
                    addView(owner.label(title,10.5f,color,700),LinearLayout.LayoutParams(-2,owner.px(13)).apply{topMargin=owner.px(4)})
                    isFocusable=true;contentDescription=title;setOnClickListener{action()}
                },FrameLayout.LayoutParams(owner.px(52),owner.px(52),Gravity.CENTER))
                if(selected)addView(View(this@MainActivity).apply{background=owner.shape(owner.accent,3f)},FrameLayout.LayoutParams(owner.px(3),owner.px(24),Gravity.START or Gravity.CENTER_VERTICAL))
            }
            rail.addView(frame,LinearLayout.LayoutParams(-1,owner.px(52)).apply{bottomMargin=owner.px(gap)})
        }
        nav("tune","调控",R.drawable.owner_tune){navigate("tune")}
        nav("thread","线程",R.drawable.owner_chip){navigate("thread")}
        nav("monitor","监测",R.drawable.owner_pulse){navigate("monitor")}
        rail.addView(View(this),LinearLayout.LayoutParams(1,0,1f))
        nav("theme",if(owner.dark)"浅色" else "深色",if(owner.dark)R.drawable.owner_sun else R.drawable.owner_moon,6){theme(if(owner.dark)"light" else "dark")}
        nav("settings","设置",R.drawable.owner_settings,0){navigate("settings")}
        root.addView(owner.borderedColumn(rail,72),LinearLayout.LayoutParams(owner.px(72),-1))
        master=owner.column().apply{setBackgroundColor(owner.master)}
        root.addView(owner.borderedColumn(master,312),LinearLayout.LayoutParams(owner.px(312),-1))
        detail=owner.column().apply{setPadding(owner.px(22),owner.px(20),owner.px(22),owner.px(20))}
        root.addView(ScrollView(this).apply{isFillViewport=false;isVerticalScrollBarEnabled=false;clipToPadding=false;addView(detail)},LinearLayout.LayoutParams(0,-1,1f))
        ownerHost=FrameLayout(this).apply{setBackgroundColor(owner.detail);clipToOutline=false;clipChildren=false;clipToPadding=false;addView(root,FrameLayout.LayoutParams(-1,-1))}
        ownerModal=OwnerModal(owner,ownerHost,root)
        setContentView(OwnerDesignLayout(this).apply{setBackgroundColor(owner.detail);clipChildren=false;clipToPadding=false;addView(ownerHost)})
        buildMaster()
        if(session.error.isNotEmpty()) detail.addView(owner.label(session.error,12f,owner.chipFg[3],600).apply{
            setSingleLine(false);setPadding(owner.px(14),owner.px(10),owner.px(14),owner.px(10));background=owner.shape(owner.chipBg[3],12f)
        },owner.gap())
        when(session.section) {
            "tune" -> if(session.selected.isEmpty())dashboard() else appPage()
            "thread" -> if(preview!=null)updatePage() else if(session.selected.isEmpty())threadHome() else if(analysisPage){
                detail.addView(owner.title("线程分析 · ${name(session.selected)}","读取已完成记录；CPU 放置由你决定",leading=owner.button("‹",small=true){analysisPage=false;render()}));analysisCard()
            }else threadApp()
            "monitor" -> monitorPage()
            "settings" -> settingsPage()
        }
        val page="${session.section}/${session.selected}/${session.settingsModule}/$analysisPage"
        if(page!=shownPage){shownPage=page;detail.alpha=0f;detail.translationY=owner.px(6).toFloat();detail.animate().alpha(1f).translationY(0f).setDuration(300).setInterpolator(OwnerUi.smooth).start()}
        bindMonitor()
        if(session.busy)ownerPending()
        if(session.notice.isNotEmpty() && session.notice!=shownToast){shownToast=session.notice;toast(session.notice)}
        if(session.notice.isEmpty())shownToast=""
        showThemeTransition()
    }
    private fun buildMaster() {
        val titles=mapOf("tune" to "应用策略","thread" to "线程策略","monitor" to "监测记录","settings" to "设置")
        val subtitle=when(session.section){"tune"->"${policies?.apps?.size ?: 0} 个独立配置";"thread"->"${installed.count{model?.appProfile(it.packageName)!=null}} 个应用 · ${model?.profiles?.values?.sumOf{it.rules.size} ?: 0} 条特殊线程规则";"monitor"->"${records.length()} 条记录 · 每个应用保留最近一次";"settings"->"ZuiControl 偏好与工具";else->""}
        master.addView(owner.row().apply {
            setPadding(owner.px(18),owner.px(22),owner.px(18),owner.px(12))
            addView(owner.column().apply{
                addView(owner.label(titles.getValue(session.section),19f,owner.text,800),LinearLayout.LayoutParams(-1,owner.px(24)))
                addView(owner.label(subtitle,11f,owner.muted),LinearLayout.LayoutParams(-1,owner.px(14)).apply{topMargin=owner.px(3)})
            },LinearLayout.LayoutParams(0,-2,1f))
            if(session.section in setOf("tune","thread"))addView(owner.icon(R.drawable.owner_plus,Color.WHITE,18).apply{
                background=owner.shape(owner.accent,11f);setPadding(owner.px(8),owner.px(8),owner.px(8),owner.px(8));elevation=owner.px(5).toFloat()
                isFocusable=true;contentDescription="添加应用";setOnClickListener{guard{picker()}};owner.press(this,.92f)
            },LinearLayout.LayoutParams(owner.px(34),owner.px(34)))
        },LinearLayout.LayoutParams(-1,owner.px(82)))
        val list=owner.column().apply{setPadding(owner.px(14),owner.px(4),owner.px(14),owner.px(18))}
        if(session.section=="settings") {
            val icons=listOf(R.drawable.owner_monitor,R.drawable.owner_gpu,R.drawable.owner_data,R.drawable.owner_info)
            val rows=listOf("监测与显示" to "悬浮窗权限 · 通知 · 主题","GPU 默认范围" to "四档默认 GPU 频率区间","数据与维护" to "备份 · 恢复 · 维护","关于" to "Release · Build · Schema")
            rows.forEachIndexed{i,(title,sub)->list.addView(ownerListRow("",title,sub,session.settingsModule==i,icons[i]){guard{session.settingsModule=i;session.clearDrafts();render()}})}
        } else {
            val (box,search)=owner.search(when(session.section){"thread"->"搜索应用或包名...";"monitor"->"搜索记录...";else->"搜索应用..."},query)
            master.addView(box,LinearLayout.LayoutParams(-1,owner.px(38)).apply{marginStart=owner.px(18);marginEnd=owner.px(18);bottomMargin=owner.px(12)})
            if(session.section=="thread"){
                master.addView(segment(listOf("全部","上游","我的"),filter){filter=it;populateList(list)},LinearLayout.LayoutParams(-1,-2).apply{marginStart=owner.px(18);marginEnd=owner.px(18);bottomMargin=owner.px(12)})
                list.addView(listRow("","规则库","",session.selected.isEmpty()){guard{session.clearDrafts();session.selected="";render()}})
            }
            search.addTextChangedListener(watcher{query=it;populateList(list)});populateList(list)
        }
        master.addView(ScrollView(this).apply{isVerticalScrollBarEnabled=false;addView(list)},LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun ownerAppIcon(pkg: String,size: Int=40): View=ImageView(this).apply {
        setImageDrawable(runCatching{packageManager.getApplicationIcon(pkg)}.getOrNull());scaleType=ImageView.ScaleType.CENTER_CROP
        val radius=when(size){44->13f;36->10f;else->12f}
        background=owner.shape(owner.card2,radius);foreground=owner.shape(Color.TRANSPARENT,radius,0x14ffffff)
        clipToOutline=true;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams=LinearLayout.LayoutParams(owner.px(size),owner.px(size))
    }
    private fun ownerListRow(pkg: String,title: String,subtitle: String,selected: Boolean,icon: Int?=null,action:()->Unit): View=owner.row().apply{
        setPadding(owner.px(13),owner.px(12),owner.px(13),owner.px(12))
        background=owner.shape(owner.card,14f,if(selected)owner.accent else owner.line,if(selected)2f else 1f)
        if(selected){elevation=owner.px(6).toFloat();outlineSpotShadowColor=owner.soft(owner.accent,102)}
        val image=if(pkg.isNotEmpty())ownerAppIcon(pkg) else owner.icon(icon ?: R.drawable.owner_tune,if(selected)owner.accent else owner.sub,19).apply{
            background=owner.shape(if(selected)owner.soft(owner.accent) else owner.card2,12f);setPadding(owner.px(10),owner.px(10),owner.px(10),owner.px(10))
        }
        addView(image,LinearLayout.LayoutParams(owner.px(40),owner.px(40)).apply{marginEnd=owner.px(12)})
        addView(owner.column().apply{
            addView(owner.label(title,13.5f,owner.text,800),LinearLayout.LayoutParams(-1,owner.px(17)))
            val d=policies?.apps?.firstOrNull{it.draft.packageName==pkg}?.draft
            val chips=owner.row()
            if(d!=null && session.section=="tune"){
                chips.addView(owner.chip("${d.refreshHz}Hz"));chips.addView(owner.chip(modeTitle(d.uperfMode),GpuDefaultsDraft.modes.indexOf(d.uperfMode)+1),LinearLayout.LayoutParams(-2,owner.px(18)).apply{marginStart=owner.px(6)})
                if(model?.appProfile(pkg)!=null)chips.addView(owner.icon(R.drawable.owner_chip,owner.zoFg,12).apply{background=owner.shape(owner.zoBg,5f);setPadding(owner.px(3),owner.px(3),owner.px(3),owner.px(3))},LinearLayout.LayoutParams(owner.px(18),owner.px(18)).apply{marginStart=owner.px(6)})
            } else if(session.section=="thread" && pkg.isNotEmpty()){
                chips.addView(provenanceChip(pkg))
                chips.addView(owner.chip("${model?.appProfile(pkg)?.rules?.size ?: 0} 条规则"),LinearLayout.LayoutParams(-2,dp(18)).apply{marginStart=dp(6)})
            }else chips.addView(owner.label(subtitle,11f,owner.muted),LinearLayout.LayoutParams(-1,owner.px(14)))
            addView(chips,LinearLayout.LayoutParams(-1,-2).apply{topMargin=owner.px(5)})
        },LinearLayout.LayoutParams(0,-2,1f))
        addView(owner.icon(R.drawable.owner_chevron,owner.muted,16).apply{alpha=.6f},LinearLayout.LayoutParams(owner.px(16),owner.px(16)).apply{marginStart=owner.px(12)})
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=owner.px(8)};isFocusable=true;contentDescription=title;setOnClickListener{action()}
    }

    private fun populateList(list: LinearLayout) {
        list.removeAllViews()
        if(session.section=="thread")list.addView(listRow("","规则库","",session.selected.isEmpty()){guard{session.clearDrafts();session.selected="";render()}})
        val rows = when (session.section) {
            "tune" -> policies?.apps.orEmpty().map { Triple(it.draft.packageName, name(it.draft.packageName), "${it.draft.refreshHz} Hz · ${modeTitle(it.draft.uperfMode)}${if (model?.appProfile(it.draft.packageName) != null) " · 线程" else ""}") }
            "thread" -> installed.mapNotNull { app ->
                val p = model?.appProfile(app.packageName) ?: return@mapNotNull null
                val source = provenance(app.packageName)
                if (filter == 1 && source != "UPSTREAM" || filter == 2 && source !in setOf("USER_MODIFIED", "USER_CREATED")) return@mapNotNull null
                Triple(app.packageName, name(app.packageName), "${sourceTitle(source)} · ${p.rules.size} 条规则") }
            else -> (0 until records.length()).map { i -> val r = records.getJSONObject(i)
                Triple(r.getString("package"), r.optString("label", r.getString("package")), "${whenRecorded(r.optLong("wall"))} · ${duration(r.optLong("duration"))}") }
        }
        rows.filter { query.isBlank() || it.first.contains(query, true) || it.second.contains(query, true) }.forEach { (pkg, title, subtitle) ->
            list.addView(if(session.section == "tune") ownerListRow(pkg,title,subtitle,session.selected==pkg){guard{select(pkg)}}
                else listRow(pkg, title, subtitle, session.selected == pkg) { guard { select(pkg) } })
        }
        if (list.childCount == 0) list.addView(note(if (reading) "正在读取…" else "暂无应用"))
    }
    private fun select(pkg: String) {
        session.clearDrafts(); selectedRecord = null; recordRequested = false; analysis = null; analysisError = ""
        session.selected = if (session.selected == pkg) "" else pkg
        if (session.selected.isEmpty()) { render(); return }
        when (session.section) {
            "tune" -> { val d = policies?.apps?.firstOrNull { it.draft.packageName == pkg }?.draft ?: return
                session.originalApp = d; session.appDraft = d; render() }
            "thread" -> { openRule(pkg); render(); loadAnalysis(pkg) }
            else -> { render(); readRecord(pkg) }
        }
    }
    private fun dashboard() {
        val tier=GpuDefaultsDraft.modes.indexOf(mode.displayed).coerceAtLeast(0)
        val modeChip=owner.row().apply{
            background=owner.shape(owner.chipBg[tier+1],999f);setPadding(owner.px(11),0,owner.px(13),0)
            minimumHeight=owner.px(30)
            addView(OwnerPing(this@MainActivity,owner,owner.tiers[tier]),LinearLayout.LayoutParams(owner.px(7),owner.px(7)).apply{marginEnd=owner.px(8)})
            addView(owner.label("${modeTitle(mode.displayed)}模式",12f,owner.chipFg[tier+1],800));layoutParams=LinearLayout.LayoutParams(-2,owner.px(30))
        }
        detail.addView(owner.title("系统全局状态","系统关键性能参数与组件运行情况",trailing=modeChip))
        val stats=owner.row()
        listOf("温度","功耗","核心组件").forEachIndexed{i,title ->
            val box=owner.card(false).apply{setPadding(owner.px(17),owner.px(15),owner.px(17),owner.px(17))}
            box.addView(owner.row().apply{
                addView(owner.icon(listOf(R.drawable.owner_temperature,R.drawable.owner_power,R.drawable.owner_health)[i],owner.muted,16),LinearLayout.LayoutParams(owner.px(16),owner.px(16)).apply{marginEnd=owner.px(7)})
                addView(owner.label(title,12f,owner.sub,700))
            },LinearLayout.LayoutParams(-1,owner.px(22)))
            val metric=owner.label("--",if(i==2)20f else 28f,owner.text,800)
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
                    OwnerMeter(this,owner,segments=health.map{when(it.state){BackendHealth.State.OK->owner.accent;BackendHealth.State.DEGRADED->owner.tiers[2];BackendHealth.State.FAILED->owner.tiers[3];else->owner.line2}})
                }
            }
            box.addView(meter,LinearLayout.LayoutParams(-1,owner.px(6)))
            stats.addView(box,LinearLayout.LayoutParams(0,owner.px(118),if(i==2)1.34f else 1f).apply{if(i>0)marginStart=owner.px(12)})
        }
        detail.addView(stats,owner.gap())
        detail.addView(owner.card().apply{
            addView(owner.section("全局刷新率","全局默认屏幕刷新率档位模式"))
            val rates=supportedRates();addView(ownerControl(OwnerSegment(this@MainActivity,owner,rates.map{"$it Hz"},rates.indexOf(refresh.displayed),enabled=!session.busy){i->
                optimistic(refresh,rates[i]){session.gateway.setGlobal("refresh",value=rates[i])}
            }))
            if(refresh.confirmed==0)addView(owner.label("全局档位尚未确认",11f,owner.inks[2]))
            addView(owner.divider());addView(owner.section("全局性能档位","日常系统调度激进程度"))
            addView(ownerControl(owner.tiers(mode.displayed,enabled=!session.busy){id->optimistic(mode,id){session.gateway.setGlobal("mode",mode=id)}}))
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
            isEnabled=!session.busy;alpha=if(session.busy).6f else 1f;isFocusable=true;contentDescription="性能监视悬浮窗，${if(this@MainActivity.overlay.displayed)"已开启" else "已关闭"}";setOnClickListener{toggleOverlay()}
            tag="owner-control"
        },owner.gap())
    }

    private fun <T> optimistic(control: OptimisticControl<T>, value: T, action: () -> ZuiControlClient.Reply) {
        if (session.busy || !control.begin(value)) return
        session.work("已生效") {
            try { val reply = action(); control.finish(reply.ok); check(reply.ok) { reply.text } }
            catch (e: Exception) { if (control.pending) control.finish(false); throw e }
        }
    }
    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) { overlayPermission(); return }
        ZuiControlQuickService.start(this); val desired = !this@MainActivity.overlay.displayed
        optimistic(overlay, desired) { session.gateway.setOverlay(desired) }
    }
    private fun coreHealth() {
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
                    addView(owner.chip(when(h.state){BackendHealth.State.OK->"正常";BackendHealth.State.DEGRADED->"降级";BackendHealth.State.FAILED->"故障";else->"未知"},tone),FrameLayout.LayoutParams(-2,dp(18),Gravity.CENTER_VERTICAL))
                },LinearLayout.LayoutParams(owner.px(72),owner.px(18)).apply{marginStart=owner.px(10)})
                addView(owner.column().apply{
                    addView(owner.label(h.reason,11.5f,owner.sub).apply{setSingleLine(false);setLineSpacing(0f,1.5f)})
                    addView(owner.label("本次会话内状态变化：—",10f,owner.muted),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(3)})
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
            addView(owner.icon(R.drawable.owner_back,owner.sub,18).apply{
                background=owner.shape(owner.card,11f,owner.line);setPadding(owner.px(9),owner.px(9),owner.px(9),owner.px(9));isFocusable=true;contentDescription="返回全局"
                setOnClickListener{guard{session.clearDrafts();session.selected="";render()}}
            },LinearLayout.LayoutParams(owner.px(36),owner.px(36)).apply{marginEnd=owner.px(14)})
            addView(ownerAppIcon(d.packageName,44))
        }
        detail.addView(owner.title(name(d.packageName),d.packageName,leading,if(session.appDirty)owner.chip("未保存",3,true) else null))
        val configurable=UperfAppPolicy.isConfigurable(packageManager,d.packageName)
        val rates=supportedRates()
        val box=owner.card()
        box.addView(owner.section("自定义应用刷新率","前台应用自定义刷新率档位"))
        box.addView(OwnerSegment(this,owner,rates.map{"$it Hz"},rates.indexOf(d.refreshHz),enabled=!session.busy){session.appDraft=checkNotNull(session.appDraft).copy(refreshHz=rates[it]);localRender()})
        box.addView(owner.divider());box.addView(owner.section("自定义应用性能档位","调配集群负载迁移阈值与超大核激进度"))
        box.addView(owner.tiers(d.uperfMode,true,!session.busy && configurable){id->session.appDraft=checkNotNull(session.appDraft).copy(uperfMode=id,gpuPolicy=ZuiControlClient.GpuPolicy.DEFAULT_FOR_MODE,gpuMinMHz=null,gpuMaxMHz=null);localRender()})
        if(!configurable)box.addView(owner.label("此应用不支持 Uperf 配置；刷新率和适用的线程规则仍可配置",11f,owner.muted).apply{setSingleLine(false)})
        box.addView(owner.divider())
        val range=runCatching{if(d.gpuPolicy==ZuiControlClient.GpuPolicy.CUSTOM)GpuRanges.Range(d.gpuMinMHz!!,d.gpuMaxMHz!!) else globalRanges().getValue(d.uperfMode)}
        range.onSuccess{r->
            val readout=ownerReadout(r,owner.accent)
            val custom=d.gpuPolicy==ZuiControlClient.GpuPolicy.CUSTOM
            val trailing=owner.row().apply{
                addView(OwnerSegment(this@MainActivity,owner,listOf("默认","自定义"),if(custom)1 else 0,true,!session.busy && configurable){i->
                    val current=checkNotNull(session.appDraft)
                    val effective=if(current.gpuPolicy==ZuiControlClient.GpuPolicy.CUSTOM)GpuRanges.Range(current.gpuMinMHz!!,current.gpuMaxMHz!!) else globalRanges().getValue(current.uperfMode)
                    session.appDraft=current.copy(gpuPolicy=if(i==0)ZuiControlClient.GpuPolicy.DEFAULT_FOR_MODE else ZuiControlClient.GpuPolicy.CUSTOM,gpuMinMHz=if(i==0)null else effective.min,gpuMaxMHz=if(i==0)null else effective.max);localRender()
                },LinearLayout.LayoutParams(owner.px(132),owner.px(34)).apply{marginEnd=owner.px(14)})
                addView(readout)
            }
            box.addView(owner.section("GPU 频率范围",if(custom)"仅此应用使用的固定区间" else "持续跟随「${modeTitle(d.uperfMode)}」档默认范围",trailing))
            val bar=GpuRangeBar(this,r).apply{
                isEnabled=!session.busy && configurable && custom
                onPreview={ownerUpdateReadout(readout,it)}
                onCommit={session.appDraft=checkNotNull(session.appDraft).copy(gpuPolicy=ZuiControlClient.GpuPolicy.CUSTOM,gpuMinMHz=it.min,gpuMaxMHz=it.max);handler.postDelayed({if(visible)render()},320)}
            };box.addView(bar,LinearLayout.LayoutParams(-1,bar.preferredHeight))
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
                session.section="thread";session.selected=d.packageName;session.clearDrafts();openRule(d.packageName);render();loadAnalysis(d.packageName)
            }})
        })
        detail.addView(box,owner.gap())
        detail.addView(owner.row().apply{
            if(!session.newApp)addView(owner.button("删除独立配置","danger",icon=R.drawable.owner_trash,enabled=!session.busy){confirm("删除独立配置？","回到全局策略，线程规则保留。"){
                session.work("已删除"){val reply=ZuiControlClient.removePackageProfile(applicationContext,d.packageName);check(reply.ok){reply.text};session.clearDrafts();session.selected=""}
            }})
            addView(View(this@MainActivity),LinearLayout.LayoutParams(0,1,1f))
            addView(owner.button(if(session.busy)"正在保存…" else "保存并生效","primary",enabled=!session.busy && session.appDirty){saveDraft()})
        })
    }
    private fun ownerReadout(range: GpuRanges.Range,color: Int,size: Float=22f)=owner.row().apply{
        addView(owner.label("${range.min} – ${range.max}",size,color,800));addView(owner.label("MHz",11f,owner.sub,700),LinearLayout.LayoutParams(-2,-2).apply{marginStart=owner.px(3);topMargin=owner.px(5)})
    }
    private fun ownerUpdateReadout(view: LinearLayout,range: GpuRanges.Range){(view.getChildAt(0) as TextView).text="${range.min} – ${range.max}"}
    private fun gpuDefaultsPage() {
        val d=runCatching{session.gpuDraftFrom(state)}.getOrElse{heading("GPU 默认范围","各性能档位的默认 GPU 频率区间");detail.addView(note("默认范围暂不可用 · ${it.message}",orange));return}
        val trailing=owner.row().apply{
            if(d.dirty)addView(owner.chip("未保存",3,true),LinearLayout.LayoutParams(-2,owner.px(22)).apply{marginEnd=owner.px(10)})
            addView(owner.button("恢复默认",small=true,enabled=!session.busy){d.restoreDefaults();render()})
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
                val bar=GpuRangeBar(this@MainActivity,r).apply{tone=owner.tiers[i];isEnabled=!session.busy;onPreview={ownerUpdateReadout(readout,it)};onCommit={d.set(id,it);handler.postDelayed({if(visible)render()},320)}}
                addView(bar,LinearLayout.LayoutParams(-1,bar.preferredHeight))
            }
        },owner.gap())
        detail.addView(owner.label("单应用选择“跟随档位默认”时会持续使用这里的区间，修改后这些应用随之更新。拖动后需确认才会保存。",11f,owner.muted).apply{setSingleLine(false);setPadding(owner.px(4),0,owner.px(4),0)},owner.gap())
        detail.addView(owner.row().apply{gravity=Gravity.END;addView(owner.button(if(session.busy)"正在保存…" else "保存并生效","primary",enabled=!session.busy && d.dirty){saveDraft()})})
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
                    check(validation.valid) { validation.error }; ZuioptRules.upload(applicationContext, "user", bytes, expectedGeneration = rule.generation); session.ruleDraft = null
                }
            }
        }
    }
    private fun guard(next: () -> Unit) {
        if (session.busy) { toast("操作处理中，请等待结果"); return }
        if (!session.dirty) { next(); return }
        ownerModal?.open("有未保存的修改","保存成功后继续，或放弃本次草稿。",360,owner.column(),listOf(
            owner.button("取消"){ownerModal?.close()},owner.button("放弃修改"){ownerModal?.close();session.clearDrafts();next()},
            owner.button("保存","primary"){ownerModal?.close();saveDraft(next)}))
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if(ownerModal?.isOpen==true){ownerModal?.close();return};guard { if (session.selected.isNotEmpty()) { session.clearDrafts(); session.selected = ""; render() } else finish() } }
    private fun picker() { ownerPicker() }
    private fun ownerPicker() {
        val box=owner.column();val list=owner.column();var system=false;var selected=""
        val (searchBox,search)=owner.search("搜索应用或包名")
        val next=owner.button("下一步","primary",enabled=false){
            if(selected.isEmpty())return@button
            val authority=policies ?: return@button
            if(session.section=="tune" && (mode.confirmed !in GpuDefaultsDraft.modes || refresh.confirmed !in supportedRates())){toast("全局策略暂不可用");return@button}
            session.clearDrafts();session.selected=selected
            if(session.section=="tune"){session.appDraft=ZuiControlClient.AppPolicyDraft(selected,refresh.confirmed,mode.confirmed,ZuiControlClient.GpuPolicy.DEFAULT_FOR_MODE,authority.generation);session.newApp=true}else openRule(selected)
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
        heading("线程调度", "ZUIopt · 按线程 / 任务的 CPU 放置（cpuset / affinity）")
        if (ruleError.isNotEmpty()) detail.addView(owner.empty("规则暂不可用",ruleError,true), gap())
        if (ZuioptRules.field(ruleState, "failure") == "1") detail.addView(actionRow("ZUIopt 已进入故障保护", ZuioptRules.field(ruleState, "failure_reason") + " · 下次开机重新启用") {
            confirm("下次开机重新启用？", "本次开机继续由 Android 调度，不会立即重启。") { session.work("已安排") { ZuioptRules.command(applicationContext, "reset") } }
        }, gap())
        val b = baseline
        detail.addView(card().apply {
            addView(owner.section("当前规则集",b?.metadata?.let { "${it.optString("source")} · ${it.optString("sourceVersion")}" } ?: "规则集不可用",owner.row().apply {
                addView(owner.button("查看原文",small=true){rawView()})
                addView(owner.button("导出",small=true){snapshot?.let { export(it.text.toByteArray(),"ZuiControl_rules.conf") }},LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(8)})
            }))
            addView(note(b?.metadata?.let { "${it.optString("sourceDate")} · ${it.optString("sourceCommit")}\n${b.generation}" } ?: "等待原生规则权威"))
            val apps=installed.filter { model?.appProfile(it.packageName)!=null }
            val counts=listOf(apps.size,model?.profiles?.values?.sumOf{it.rules.size} ?: 0,apps.count{provenance(it.packageName) in setOf("USER_MODIFIED","USER_CREATED")})
            addView(owner.row().apply {
                listOf("规则应用","特殊线程规则","我的修改 / 新建").forEachIndexed{i,title->
                    addView(owner.column().apply {
                        if(i>0)setPadding(dp(18),0,0,0)
                        addView(owner.label(counts[i].toString(),24f,owner.text,800),LinearLayout.LayoutParams(-1,dp(28)))
                        addView(owner.label(title,11f,owner.muted),LinearLayout.LayoutParams(-1,dp(16)).apply{topMargin=dp(6)})
                    },LinearLayout.LayoutParams(0,-2,1f))
                }
            },LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(16)})
        },gap())
        detail.addView(card().apply {
            addView(owner.label("规则库",11f,owner.muted,800))
            addView(actionRow("同步上游规则",if(!RuleRemoteConfig.configured)"在线规则待兼容性实验室确认" else latest?.let{"${it.version} · ${it.date}"} ?: "无待同步更新"){syncUpstream()}.apply{isEnabled=RuleRemoteConfig.configured && latest!=null;alpha=if(isEnabled)1f else .4f})
            val previous=ZuioptRules.field(ruleState,"previous_generation").matches(Regex("g[0-9a-f]{24}"))
            addView(actionRow("回退规则版本",if(previous)"回到上一代完整规则集" else "暂无可回退版本"){confirm("回退规则版本？","上游基线和生效规则一起回退。"){
                session.work("已回退"){ZuioptRules.command(applicationContext,"rollback",checkNotNull(snapshot).generation)}
            }}.apply{isEnabled=previous;alpha=if(previous)1f else .4f})
            addView(actionRow("高级兼容导入 · AppOpt","兼容转换与差异预览后确认"){document(Intent.ACTION_OPEN_DOCUMENT,102,"*/*")})
            addView(owner.button("检查更新",small=true){checkUpdates()}.apply{isEnabled=RuleRemoteConfig.configured;alpha=if(isEnabled)1f else .4f})
            if(RuleRemoteConfig.configured && updateStatus.isNotEmpty())addView(note(updateStatus))
        },gap())
        detail.addView(card().apply {
            addView(owner.label("职责边界",11f,owner.muted,800))
            addView(owner.row().apply {
                listOf("ZUIopt" to "只决定线程可以运行在哪些 CPU（cpuset / affinity）","Uperf" to "宏观性能模式：调频策略与档位","GPU · Thermal" to "各自 owner 管理，不受线程规则影响").forEachIndexed{i,(title,description)->
                    addView(owner.column().apply{
                        background=owner.shape(if(i==0)owner.zoBg else owner.card2,12f);setPadding(dp(12),dp(10),dp(12),dp(10))
                        addView(owner.label(title,12.5f,if(i==0)owner.zoFg else owner.text,800));addView(note(description,if(i==0)owner.zoFg else owner.muted))
                    },LinearLayout.LayoutParams(0,-1,1f).apply{if(i>0)marginStart=dp(10)})
                }
            },LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10)})
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
        heading(if(appOpt)"高级兼容导入 · AppOpt" else "规则库更新", "转换结果需确认后才写入；不会整体替换当前规则集")
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
    private fun rawView() {
        val raw = snapshot?.text ?: return
        val code=owner.row().apply {
            background=owner.shape(if(owner.dark)Color.parseColor("#080E18") else Color.parseColor("#F8FAFC"),12f,owner.line)
            addView(owner.label(raw.lines().indices.joinToString("\n"){(it+1).toString()},12f,owner.muted).apply{setSingleLine(false);typeface=Typeface.MONOSPACE;gravity=Gravity.TOP;setPadding(dp(12),dp(12),dp(8),dp(12))},LinearLayout.LayoutParams(dp(40),-2))
            addView(label(raw,12f,owner.text).apply{typeface=Typeface.MONOSPACE;setTextIsSelectable(true);gravity=Gravity.TOP;setPadding(dp(14),dp(12),dp(14),dp(12))},LinearLayout.LayoutParams(0,-2,1f))
        }
        ownerModal?.open("规则集原文 · 只读","完整 canonical 规则；编辑请进入单个应用。",600,
            owner.scroll(code,340),listOf(owner.button("关闭"){ownerModal?.close()},owner.button("导出","primary"){ownerModal?.close();export(raw.toByteArray(),"ZuiControl_rules.conf")}))
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
            addView(owner.icon(R.drawable.owner_back,owner.sub,18).apply{
                background=owner.shape(owner.card,11f,owner.line);setPadding(dp(9),dp(9),dp(9),dp(9));isFocusable=true;contentDescription="返回线程首页"
                setOnClickListener{guard{session.clearDrafts();session.selected="";analysisPage=false;render()}}
            },LinearLayout.LayoutParams(dp(36),dp(36)).apply{marginEnd=dp(14)})
            addView(ownerAppIcon(pkg,44))
        }
        detail.addView(owner.title(name(pkg),pkg,leading=leading,trailing=owner.row().apply{
            addView(provenanceChip(pkg,true));if(d?.dirty==true)addView(owner.chip("未保存",3,true),LinearLayout.LayoutParams(-2,dp(22)).apply{marginStart=dp(6)})
        }))
        if(d==null)detail.addView(owner.empty("此规则暂不能无损显示","可查看完整原文。$ruleError",true),gap())
        else {
            val mapping=d.base.mappings.firstOrNull{when(it.matchKind){"exact"->it.packageName==pkg;"prefix"->pkg.startsWith(it.packageName);else->pkg.contains(it.packageName)}}
            detail.addView(card().apply {
                addView(owner.section("线程规则","Profile ${mapping?.profile ?: "新建"} · 按顺序匹配，可拖动调整",owner.button("导出",small=true,icon=R.drawable.owner_export){snapshot?.let{export(it.text.toByteArray(),"ZuiControl_rules.conf")}}))
                addView(owner.row().apply{
                    addView(owner.column().apply{addView(owner.label("默认 CPU 范围",13f,owner.text,800));addView(note("未命中特殊规则的线程 · ${cpuRange(d.profile.generalMask)}"))},LinearLayout.LayoutParams(0,-2,1f))
                    addView(cpuPicker(d.profile.generalMask){mask->mutateRule{d.profile=d.profile.copy(generalMask=mask);render()}})
                },LinearLayout.LayoutParams(-1,dp(48)))
                val table=owner.column()
                fun cell(parent:LinearLayout,view:View,width:Int){parent.addView(view,LinearLayout.LayoutParams(if(width==0)0 else dp(width),-2,if(width==0)1f else 0f).apply{marginStart=dp(10)})}
                table.addView(owner.row().apply{
                    addView(View(this@MainActivity),LinearLayout.LayoutParams(dp(16),dp(22)))
                    cell(this,owner.label("匹配",11f,owner.muted,700),0);cell(this,owner.label("竞争组",11f,owner.muted,700),62)
                    cell(this,owner.label("竞争选择",11f,owner.muted,700),84);cell(this,owner.label("CPU 0–7",11f,owner.muted,700),117);cell(this,View(this@MainActivity),22)
                },LinearLayout.LayoutParams(-1,dp(28)))
                if(d.profile.rules.isEmpty())table.addView(owner.empty("还没有特殊线程规则","所有线程使用默认 CPU 范围"))
                d.profile.rules.forEachIndexed{index,rule->
                    table.addView(owner.row().apply{
                        setPadding(0,dp(8),0,dp(8))
                        addView(owner.icon(R.drawable.owner_grip,owner.muted,14).apply{
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
                        cell(this,owner.row().apply{addView(owner.chip(if(rule.selector=="all")"全部候选" else "第${rule.selector.substringAfter(':')}个",2));setOnClickListener{editRule(index)}},84)
                        cell(this,cpuPicker(rule.cpuMask){mask->mutateRule{d.profile=d.profile.copy(rules=d.profile.rules.map{if(it.competitionClass==rule.competitionClass)it.copy(cpuMask=mask)else it});render()}},117)
                        cell(this,owner.icon(R.drawable.owner_close,owner.muted,14).apply{isFocusable=true;contentDescription="删除规则 ${index+1}";setOnClickListener{mutateRule{d.profile=d.profile.copy(rules=d.profile.rules.filterIndexed{i,_->i!=index});render()}}},22)
                        setOnDragListener{_,event->when(event.action){DragEvent.ACTION_DRAG_STARTED->event.clipDescription?.label=="rule-order";DragEvent.ACTION_DROP->{(event.localState as? Int)?.let{moveRule(it,index)};true};else->true}}
                    })
                    table.addView(View(this@MainActivity).apply{setBackgroundColor(owner.line)},LinearLayout.LayoutParams(-1,dp(1)))
                    if(rule.cpuMask.isEmpty())table.addView(note("请选择 CPU 后再保存",orange))
                }
                addView(owner.scroll(table,minOf(214,28+d.profile.rules.size*40)))
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
                if(provenance(pkg)=="USER_MODIFIED")addView(owner.button("恢复上游"){confirm("恢复上游规则？","将放弃此应用的修改。"){
                    session.work("已恢复上游"){ZuioptLibrary.restoreApp(applicationContext,pkg,checkNotNull(this@MainActivity.baseline));session.ruleDraft=null}
                }},LinearLayout.LayoutParams(-2,-2).apply{marginEnd=dp(8)})
                addView(View(this@MainActivity),LinearLayout.LayoutParams(0,1,1f))
                addView(owner.button("记录关联线程分析",small=true,icon=R.drawable.owner_chip){analysisPage=true;render()}.apply{minimumHeight=dp(40)},LinearLayout.LayoutParams(-2,-2).apply{marginEnd=dp(8)})
                addView(owner.button("原文预览",small=true,icon=R.drawable.owner_info){rawView()}.apply{minimumHeight=dp(40)},LinearLayout.LayoutParams(-2,-2).apply{marginEnd=dp(8)})
                addView(owner.button("保存并应用","primary",small=true,enabled=!session.busy && d.dirty){saveDraft()}.apply{minimumHeight=dp(40)})
            },gap())
        }
        if(d==null)detail.addView(owner.button("查看原文 · 只读",small=true){rawView()},gap())
    }
    private fun mutateRule(next: () -> Unit) {
        if (session.busy) return
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
                addView(rank,LinearLayout.LayoutParams(dp(32),dp(26)))
                addView(step("+",1),LinearLayout.LayoutParams(dp(26),dp(26)))
                visibility=if(selector=="all")View.GONE else View.VISIBLE
            }
            val box=owner.column()
            box.addView(owner.formRow("匹配方式",segment(listOf("精确","前缀","包含","通配"),kinds.indexOf(kind)){kind=kinds[it]}))
            box.addView(owner.formRow("匹配内容",pattern))
            box.addView(owner.formRow("竞争组",HorizontalScrollView(this@MainActivity).apply{
                isHorizontalScrollBarEnabled=false
                addView(segment(classes.mapIndexed{i,_->"组 ${('A'.code+i).toChar()}"},classes.indexOf(cls)){cls=classes[it]},android.widget.FrameLayout.LayoutParams(dp(minOf(340,classes.size*52)),dp(32)))
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
        Thread { val result = runCatching { session.gateway.readRecordLinkedThreadAnalysis(pkg) }
            handler.post { if (read != analysisRead || session.selected != pkg || !visible) return@post
                result.onSuccess { analysis = it }.onFailure { analysisError = it.message.orEmpty() }; render() }
        }.start()
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

    private fun readRecord(pkg: String, threads: Boolean = false) { recordRequested = true; session.work {
        selectedRecord = JSONObject(PerformanceMonitor.command("recordRead", JSONObject().put("package", pkg).put("threads", threads).put("thread", "").toString())); recordThreads = threads
    } }
    private fun monitorPage() {
        val r = selectedRecord
        if(r!=null && r.has("package")){
            val actions=owner.row().apply{
                addView(owner.button("导出记录",small=true,icon=R.drawable.owner_export){export(r.toString(2).toByteArray(),"ZuiControl_record.json")})
                addView(owner.button("删除","danger",true,R.drawable.owner_trash){confirm("删除记录？","仅删除此应用最近一次记录。"){
                    session.work("已删除"){val reply=PerformanceMonitor.command("recordDelete",session.selected);check(reply.startsWith("ok=1")){reply};selectedRecord=null}
                }},LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(8)})
            }
            detail.addView(owner.title(if(recordThreads)"线程运行记录" else name(session.selected),"${whenRecorded(r.optLong("wall"))} · ${duration(r.optLong("duration"))} · ${if(r.optBoolean("complete"))"已结束" else r.optString("terminalReason","未完成")}",trailing=if(recordThreads)null else actions))
        }else heading("性能监测","通过性能监视悬浮窗开始记录")
        recordBanner = row().apply {
            background = shape(field, 14); recordLabel = label("", 13f, orange, true)
            addView(recordLabel, LinearLayout.LayoutParams(0, -2, 1f)); addView(button("停止") { session.work("记录已停止") {
                val reply = session.gateway.stopRecord(); check(reply.ok) { reply.text }
            } }); visibility = View.GONE
        }; detail.addView(recordBanner, gap())
        if (r == null || !r.has("package")) { detail.addView(owner.empty("选择一条记录查看详情","暂无记录时，请先开启性能监视悬浮窗。"),gap()); detail.addView(button("记录指引") { monitorGuidance() }); return }
        if (recordThreads) {
            detail.addView(button("‹ 返回记录详情") { readRecord(session.selected) }, gap())
            detail.addView(note("Top15 入榜线程；入榜均值不是整段平均。时间线断档表示无已保存的 Top15 样本，不代表 CPU=0。"), gap())
            val rows = r.optJSONArray("threads") ?: JSONArray(); val same = (0 until rows.length()).groupingBy { rows.getJSONArray(it).optString(1) }.eachCount()
            detail.addView(card().apply{
                setPadding(dp(14),dp(8),dp(14),dp(8))
                val widths=listOf(0,56,80,70,70,84)
                addView(owner.tableRow(listOf("线程名称","TID","入榜均值","峰值","入榜样本","CPU 时间线").map{owner.label(it,11f,owner.muted,700)},widths,true))
                for (i in 0 until rows.length()) {
                    val t = rows.getJSONArray(i)
                    val values=listOf(t.optString(1)+(if(same[t.optString(1)]!!>1)" · 同名 ${same[t.optString(1)]}" else ""),t.optString(0).split(':').getOrNull(2).orEmpty(),"${number(t.optDouble(2))}%","${number(t.optDouble(3))}%",t.optLong(4).toString(),"查看 ›")
                    addView(owner.tableRow(values.mapIndexed{j,v->owner.label(v,if(j==0)12f else 11f,if(j==5)owner.accent else owner.text,700)},widths).apply{
                        isFocusable=true;contentDescription=values.joinToString(" · ");setOnClickListener{
                            startActivity(Intent(this@MainActivity, PerformanceRecordActivity::class.java).putExtra("package", session.selected).putExtra("thread", t.optString(0)).putExtra("name", t.optString(1)))
                        };owner.press(this)
                    })
                }
                if(rows.length()==0)addView(owner.empty("暂无线程样本","断档表示未保存 Top15 样本，不代表 CPU=0。"))
            },gap());return
        }
        val policy = r.optJSONObject("policySnapshot")
        detail.addView(note(if (policy == null || policy.length() == 0) "记录开始时未保存策略快照" else
            "本次策略：${snapshotValue(policy, "refreshHz")} Hz · ${modeTitle(policy.optString("uperfMode"))} · GPU ${snapshotValue(policy, "gpuMinMHz")}–${snapshotValue(policy, "gpuMaxMHz")} MHz · ${when (policy.optString("gpuPolicy")) { "DEFAULT_FOR_MODE" -> "默认"; "CUSTOM" -> "自定义"; else -> "未知" }}\n策略 generation ${snapshotValue(policy, "policyGeneration")} · ZUIopt ${policy.optString("zuioptGeneration")} · ${policy.optString("profileSummaryValidity")}"), gap())
        val scalars = r.optJSONArray("scalars") ?: JSONArray(); val stats = r.optJSONArray("stats")?.optJSONArray(0) ?: JSONArray()
        fun chart(index:Int,title:String,height:Int)=card().apply{
            setPadding(dp(16),dp(14),dp(16),dp(10))
            addView(owner.row().apply{
                addView(owner.label(title,13f,owner.text,800),LinearLayout.LayoutParams(0,-2,1f))
                addView(owner.label("最低 ${number(stats.optDouble(index*3,Double.NaN))} · 平均 ${number(stats.optDouble(index*3+1,Double.NaN))} · 最高 ${number(stats.optDouble(index*3+2,Double.NaN))}",10f,owner.muted,600))
            },LinearLayout.LayoutParams(-1,dp(22)))
            addView(RecordChart(this@MainActivity,scalars,index+1,r.optLong("duration")),LinearLayout.LayoutParams(-1,dp(height)))
        }
        detail.addView(chart(0,"帧率 · FPS",155),gap())
        detail.addView(owner.row().apply{
            addView(chart(1,"功耗 · W",120),LinearLayout.LayoutParams(0,-2,1f))
            addView(chart(2,"温度 · quiet ℃",120),LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(12)})
        },gap())
        detail.addView(note("功耗统计不含插电/不可用时段，曲线断档保留缺失；FPS 为 DISPLAY_MEASURED_FPS。"))
        detail.addView(card().apply{addView(actionRow("线程运行记录","Top15 入榜线程 · 点击查看 CPU 时间线"){readRecord(session.selected,true)})},gap())
    }

    private fun settingsPage() {
        when (session.settingsModule) {
            0 -> {
                heading("监测与显示","悬浮窗权限、通知状态与界面主题")
                val nm=getSystemService(NotificationManager::class.java)
                val enabled=nm.areNotificationsEnabled() && nm.getNotificationChannel("zui_control_monitor_v1")?.importance!=NotificationManager.IMPORTANCE_NONE
                detail.addView(card().apply{
                    addView(owner.label("权限与通知",11f,owner.muted,800))
                    addView(actionRow("悬浮窗权限",if(Settings.canDrawOverlays(this@MainActivity))"已授权 · 性能监视与记录手势" else "去授权 · 性能监视与记录手势"){overlayPermission()})
                    addView(owner.formRow("通知",owner.chip(if(enabled)"已开启" else "已关闭",if(enabled)1 else 0,true)))
                    if (!enabled) addView(button("系统通知设置") { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName)) })
                },gap())
                detail.addView(card().apply{
                    addView(owner.label("显示",11f,owner.muted,800))
                    addView(owner.section("界面主题","侧边栏按钮可快速切换深色 / 浅色"))
                    val values=listOf("system","dark","light")
                    addView(segment(listOf("跟随系统", "深色", "浅色"),values.indexOf(prefs.getString("theme","system"))){theme(values[it])})
                },gap())
            }
            1 -> gpuDefaultsPage()
            2 -> {
                heading("数据与维护", "配置备份、恢复与系统维护")
                val rows=mutableListOf<View>()
                rows+=actionRow("立即备份", prefs.getString("backup", "保存到你选择的位置").orEmpty()) {
                    session.work { backupBytes = SettingsBackup.export(applicationContext); session.pendingDocument = 103 }
                }
                rows+=actionRow("从备份恢复", "校验 → 摘要 → 确认") { document(Intent.ACTION_OPEN_DOCUMENT, 104, "application/zip") }
                rows+=actionRow("恢复出厂配置", "保留监测记录与上游基线") { confirm("恢复出厂配置？", "清除本用户应用策略，恢复全局档位、GPU 默认与偏好；线程规则恢复当前上游基线。保留监测记录、上游版本与诊断记录。") {
                    session.work("已恢复出厂配置") { SettingsBackup.factoryReset(applicationContext) }
                } }
                rows+=actionRow("导出运行日志", "可能包含应用与使用记录") { confirm("导出运行日志？", "日志可能包含已安装应用和使用信息，请妥善保存。") {
                    session.work { val id = ZuiControlRequest.send(applicationContext, ZuiControlContract.CMD_EXPORT_LOGS); val ack = ZuiControlRequest.awaitTerminalAck(applicationContext, id); check(ack.succeeded) { ack.detail }
                        exportBytes = ZuiControlClient.utilityValue("result", "$id|logs").toByteArray(); session.pendingDocument = 101 }
                } }
                rows+=actionRow("重启调度核心", "Uperf 与 ZUIopt；已保存配置保留") { confirm("重启调度核心？", "短暂恢复 Android 调度后重新加载已保存配置。") {
                    session.work("调度核心已重启") { val id = ZuiControlRequest.send(applicationContext, ZuiControlContract.CMD_RESTART_SCHEDULER); val ack = ZuiControlRequest.awaitTerminalAck(applicationContext, id); check(ack.succeeded) { ack.detail } }
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
    private fun theme(theme: String) {
        themeFrame?.bitmap?.recycle();themeFrame=null
        if(::ownerHost.isInitialized && ownerHost.width>0 && ownerHost.height>0){
            val bitmap=android.graphics.Bitmap.createBitmap(ownerHost.width,ownerHost.height,android.graphics.Bitmap.Config.ARGB_8888)
            ownerHost.draw(android.graphics.Canvas(bitmap))
            val masterScroll=(0 until master.childCount).map{master.getChildAt(it)}.filterIsInstance<ScrollView>().firstOrNull()?.scrollY ?: 0
            themeFrame=ThemeFrame(bitmap,(detail.parent as? ScrollView)?.scrollY ?: 0,masterScroll,session.userId,query,selectedRecord,recordThreads,analysis,analysisPage)
        }
        prefs.edit().putString("theme", theme).apply();recreate()
        overridePendingTransition(0,0)
    }
    private fun showThemeTransition(){
        val frame=themeFrame ?: return
        if(frame.user!=session.userId){frame.bitmap.recycle();themeFrame=null;return}
        val image=ImageView(this).apply{setImageBitmap(frame.bitmap);scaleType=ImageView.ScaleType.FIT_XY;isClickable=true}
        ownerHost.addView(image,FrameLayout.LayoutParams(-1,-1))
        if(!themeReady)return
        themeFrame=null
        ownerHost.post{
            (detail.parent as? ScrollView)?.scrollTo(0,frame.detailScroll)
            (0 until master.childCount).map{master.getChildAt(it)}.filterIsInstance<ScrollView>().firstOrNull()?.scrollTo(0,frame.masterScroll)
            image.animate().alpha(0f).setDuration(250).withEndAction{ownerHost.removeView(image);image.setImageDrawable(null);frame.bitmap.recycle()}.start()
        }
    }
    private fun monitorGuidance() {
        ownerModal?.open("监测记录指引","通过悬浮窗开始记录，在监测页可停止。",480,owner.column().apply{
            listOf("轻触长条切换圆形，双击圆形开始记录。","记录中轻触悬浮窗，或在监测页点击停止。","切换业务应用、锁屏或满 30 分钟自动结束。","线程分析读取该记录产生的结果，CPU 放置由你选择。").forEach{addView(note("•  $it"),gap())}
        },listOf(owner.button("知道了","primary"){ownerModal?.close()}))
    }
    private fun overlayPermission() { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
    private fun export(bytes: ByteArray, title: String) { exportBytes = bytes; document(Intent.ACTION_CREATE_DOCUMENT, 101, "text/plain", title) }
    @Suppress("DEPRECATION")
    private fun document(action: String, code: Int, mime: String, title: String = "") { startActivityForResult(Intent(action).apply {
        addCategory(Intent.CATEGORY_OPENABLE); type = mime; if (title.isNotEmpty()) putExtra(Intent.EXTRA_TITLE, title)
    }, code) }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data); if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            101 -> session.work("已导出") { check(exportBytes.isNotEmpty()); checkNotNull(contentResolver.openOutputStream(uri, "wt")).use { it.write(exportBytes) }; exportBytes = byteArrayOf() }
            102 -> session.work {
                val b = checkNotNull(baseline); incoming = ZuioptRules.readDocument(applicationContext, uri, "appopt"); appOpt = true
                source = ZuioptLibrary.Provenance("AppOpt", "manual", evidence = ZuioptRules.digest(incoming)); decisions.clear(); manual = null; stagePreview(b)
            }
            103 -> session.work("备份已保存") { SettingsBackup.save(applicationContext, uri, backupBytes); backupBytes = byteArrayOf(); prefs.edit().putString("backup", whenRecorded(System.currentTimeMillis())).apply() }
            104 -> session.work {
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
        fun decide(restore: Boolean) { if(session.pendingInspection!==inspection)return;inspectionShowing=false;session.pendingInspection = null; session.work(if (restore) "已恢复" else "") {
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
    private fun name(pkg: String) = runCatching { packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString() }.getOrDefault(pkg)
    private fun isSystem(app: ApplicationInfo) = app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    private fun whenRecorded(wall: Long) = if (wall > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(wall)) else "时间未知"
    private fun duration(ms: Long) = String.format(Locale.ROOT, "%02d:%02d", ms / 60000, ms / 1000 % 60)
    private fun number(n: Double) = if (!n.isFinite() || n < 0) "--" else String.format(Locale.ROOT, "%.1f", n)
    private fun toast(message: String) {
        if(!::ownerHost.isInitialized){Toast.makeText(this,message,Toast.LENGTH_SHORT).show();return}
        toastView?.let{(it.parent as? ViewGroup)?.removeView(it)}
        val pill=owner.label(message,12f,if(owner.dark)Color.parseColor("#0B1120") else Color.WHITE,700).apply{
            setPadding(owner.px(24),owner.px(11),owner.px(24),owner.px(11));background=owner.shape(if(owner.dark)0xf0e8edf6.toInt() else 0xeb0f172a.toInt(),999f)
            elevation=owner.px(12).toFloat();alpha=0f;translationY=owner.px(60).toFloat();importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        ownerHost.addView(pill,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply{bottomMargin=owner.px(24)})
        toastView=pill;pill.animate().alpha(1f).translationY(0f).setDuration(300).setInterpolator(OwnerUi.spring).start();pill.announceForAccessibility(message)
        handler.postDelayed({pill.animate().alpha(0f).translationY(owner.px(60).toFloat()).setDuration(300).withEndAction{(pill.parent as? ViewGroup)?.removeView(pill)}.start()},2600)
    }
    private fun confirm(title: String,message: String,action:()->Unit) {
        ownerModal?.open(title,message,360,owner.column(),listOf(owner.button("取消"){ownerModal?.close()},owner.button("确认","primary"){ownerModal?.close();action()}))
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
    private fun button(title: String,selected: Boolean=false,action:()->Unit={})=owner.button(title,if(selected)"primary" else "ghost"){
        if(!session.busy)action()else toast("操作处理中，请等待结果")
    }
    private fun segment(values: List<String>,selected: Int,enabled: Boolean=true,action:(Int)->Unit):View = OwnerSegment(this,owner,values,selected,true,enabled,action)
    private fun tiers(current: String,enabled: Boolean,action:(String)->Unit):View = owner.tiers(current,false,enabled,action)
    private fun cpuPicker(initial: Set<Int>,large:Boolean=false,action:(Set<Int>)->Unit):View = owner.cpus(initial,large){next->
        if(next.isEmpty()){toast("至少保留一个 CPU");false}else{action(next);true}
    }
    private fun input(hint: String,text: String="")=EditText(this).apply{
        this.hint=hint;setText(text);setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,12.5f);setTextColor(owner.text);setHintTextColor(owner.muted)
        setSingleLine(true);includeFontPadding=false;typeface=Typeface.MONOSPACE
        background=owner.shape(if(owner.dark)Color.parseColor("#080E18") else Color.parseColor("#F8FAFC"),10f,owner.line2)
        setPadding(dp(12),0,dp(12),0);minimumHeight=dp(34);inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    private fun watcher(action:(String)->Unit)=object:TextWatcher{
        override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
        override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){action(s.toString())}
        override fun afterTextChanged(s:Editable?)=Unit
    }
    private fun actionRow(title: String,subtitle: String,action:()->Unit)=owner.row().apply{
        minimumHeight=dp(46);setPadding(0,dp(6),0,dp(6))
        val icon=when{title.contains("线程") || title.contains("规则")->R.drawable.owner_chip;title.contains("备份") || title.contains("恢复")->R.drawable.owner_data;title.contains("重启")->R.drawable.owner_warn;else->R.drawable.owner_info}
        addView(owner.icon(icon,owner.accent,16).apply{background=owner.shape(owner.soft(owner.accent),10f);setPadding(dp(8),dp(8),dp(8),dp(8))},LinearLayout.LayoutParams(dp(32),dp(32)).apply{marginEnd=dp(12)})
        addView(owner.column().apply{addView(owner.label(title,13f,owner.text,800));addView(note(subtitle,owner.muted))},LinearLayout.LayoutParams(0,-2,1f))
        val actionLabel=when(title){"立即备份"->"备份";"从备份恢复"->"选择文件";"恢复出厂配置"->"重置";"导出运行日志"->"导出";"重启调度核心"->"重启";else->""}
        if(actionLabel.isEmpty())addView(owner.icon(R.drawable.owner_chevron,owner.muted,16))else addView(owner.button(actionLabel,if(title=="立即备份")"primary" else "ghost",true){if(!session.busy)action()})
        isFocusable=true;contentDescription=title;setOnClickListener{if(!session.busy)action()};owner.press(this)
    }
    private fun listRow(pkg: String,title: String,subtitle: String,selected: Boolean,action:()->Unit)=ownerListRow(pkg,title,subtitle,selected,if(session.section=="thread")R.drawable.owner_chip else R.drawable.owner_pulse){if(!session.busy)action()}
}
