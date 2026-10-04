package com.zui.zuicontrol

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ClipData
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
    private var recordLabel: TextView? = null
    private var recordBanner: LinearLayout? = null
    private var overlayButton: TextView? = null
    private var quietMeter: ProgressBar? = null
    private var powerMeter: ProgressBar? = null
    private var coreLabel: TextView? = null
    private var coreIndex = 0
    private val coreTicker = object : Runnable {
        override fun run() {
            if (!visible || coreLabel == null) return
            val bad = BackendHealth.components(state).filter { it.state in setOf(BackendHealth.State.FAILED, BackendHealth.State.DEGRADED) }
            if (bad.size < 2) return
            coreIndex = (coreIndex + 1) % bad.size
            coreLabel?.apply { text = bad[coreIndex].component; translationY = dp(6).toFloat(); animate().translationY(0f).setDuration(300).start() }
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
    private val accent get() = getColor(R.color.ui_accent)
    private val ink get() = getColor(R.color.ui_text)
    private val sub get() = getColor(R.color.ui_secondary)
    private val field get() = getColor(R.color.ui_field)
    private val surface get() = getColor(R.color.ui_surface)
    private val orange get() = getColor(R.color.mode_performance)

    override fun onCreate(saved: Bundle?) {
        val theme = prefs.getString("theme", "system")
        if (theme != "system") applyOverrideConfiguration(Configuration().apply {
            uiMode = if (FrontendTheme.dark(theme.orEmpty(), false)) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        })
        super.onCreate(saved)
        session = (lastNonConfigurationInstance as? FrontendSession)?.takeIf { it.userId == ZuiControlClient.currentUserId() }
            ?: FrontendSession(V84FrontendGateway(applicationContext), ZuiControlClient.currentUserId())
        if (lastNonConfigurationInstance == null && saved != null) session.restore(saved)
        window.statusBarColor = getColor(R.color.ui_detail); window.navigationBarColor = getColor(R.color.ui_detail)
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
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
                reading = false
                if (!visible || isDestroyed) return@post
                result.onFailure { session.error = it.message.orEmpty() }
                boundGeneration = value(ControlsState.snapshot, "policyGeneration")
                observeGlobals()
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
        quietLabel?.text = "${number(q)} ℃"; powerLabel?.text = "${number(p)} W"
        quietMeter?.progress = if (q > 0) (q / 55 * 100).toInt().coerceIn(0, 100) else 0
        powerMeter?.progress = if (p > 0) (p / 20 * 100).toInt().coerceIn(0, 100) else 0
        quietLabel?.setTextColor(if (q >= 45) getColor(R.color.mode_fast) else if (q >= 40) orange else ink)
        powerLabel?.setTextColor(if (p >= 14) getColor(R.color.mode_fast) else if (p >= 7) orange else ink)
        powerReason?.text = if (p < 0) "功耗不可用 · ${monitor.optString("powerValidity", "等待数据")}" else "设备电池侧功耗"
        overlayButton?.text = if (this@MainActivity.overlay.displayed) "已开启" else "已关闭"
        recordBanner?.visibility = if (monitor.optString("recordState") == "RECORDING") View.VISIBLE else View.GONE
        val active = (0 until records.length()).map { records.getJSONObject(it) }.firstOrNull { it.optBoolean("active") }
        val elapsed = if (active != null) active.optLong("duration") + (SystemClock.elapsedRealtime() - recordsReadAt).coerceAtLeast(0) else 0
        recordLabel?.text = "正在记录：${active?.optString("label") ?: "当前应用"} · ${duration(elapsed)}"
    }
    private fun render() {
        handler.removeCallbacks(coreTicker); coreLabel = null; quietMeter = null; powerMeter = null
        quietLabel = null; powerLabel = null; powerReason = null; recordLabel = null; recordBanner = null; overlayButton = null
        val compact = resources.configuration.screenWidthDp < 600
        val root = (if (compact) column() else row()).apply { setBackgroundColor(getColor(R.color.ui_detail)) }
        if (compact) root.addView(segment(listOf("调控", "线程", "监测", "设置"), listOf("tune", "thread", "monitor", "settings").indexOf(session.section)) { i -> guard {
            session.section = listOf("tune", "thread", "monitor", "settings")[i]; session.selected = ""; session.clearDrafts(); query = ""; selectedRecord = null; recordRequested = false; render()
        } })
        val rail = column().apply { setPadding(dp(10), dp(20), dp(10), dp(16)); setBackgroundColor(getColor(R.color.ui_rail)) }
        rail.addView(label("ZUI", 17f, accent, true))
        listOf("tune" to "调控", "thread" to "线程", "monitor" to "监测", "settings" to "设置").forEach { (key, title) ->
            val icons = mapOf("tune" to R.drawable.ic_nav_refresh, "thread" to R.drawable.ic_nav_threads, "monitor" to R.drawable.ic_tool_monitor, "settings" to R.drawable.ic_nav_system)
            val nav = column().apply {
                gravity = Gravity.CENTER; background = shape(if (session.section == key) translucent(accent, 36) else Color.TRANSPARENT, 14)
                addView(ImageView(this@MainActivity).apply { setImageResource(icons.getValue(key)); setColorFilter(if (session.section == key) accent else sub); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(20), dp(20)))
                addView(label(title, 10.5f, if (session.section == key) accent else sub, true))
                isFocusable = true; contentDescription = title; setOnClickListener { guard {
                session.section = key; session.selected = ""; session.clearDrafts(); query = ""; selectedRecord = null; render()
                } }
            }
            rail.addView(nav, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
        }
        rail.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        rail.addView(button("◐") { theme(if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) "light" else "dark") })
        if (!compact) root.addView(rail, LinearLayout.LayoutParams(dp(72), -1))
        master = column().apply { setPadding(dp(14), dp(22), dp(14), dp(18)); setBackgroundColor(getColor(R.color.ui_master)) }
        root.addView(master, if (compact) LinearLayout.LayoutParams(-1, dp(200)) else LinearLayout.LayoutParams(dp(312), -1))
        detail = column().apply { setPadding(dp(22), dp(20), dp(22), dp(20)) }
        root.addView(ScrollView(this).apply { addView(detail) }, if (compact) LinearLayout.LayoutParams(-1, 0, 1f) else LinearLayout.LayoutParams(0, -1, 1f))
        setContentView(root); buildMaster()
        if (session.error.isNotEmpty()) detail.addView(note(session.error, orange))
        if (session.notice.isNotEmpty()) detail.addView(note(session.notice, accent))
        when (session.section) {
            "tune" -> if (session.selected.isEmpty()) dashboard() else appPage()
            "thread" -> if (preview != null) updatePage() else if (session.selected.isEmpty()) threadHome() else threadApp()
            "monitor" -> monitorPage()
            "settings" -> settingsPage()
        }
        bindMonitor()
    }
    private fun buildMaster() {
        val titles = mapOf("tune" to "应用策略", "thread" to "线程策略", "monitor" to "监测记录", "settings" to "设置")
        master.addView(row().apply {
            addView(label(titles.getValue(session.section), 19f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
            if (session.section in setOf("tune", "thread")) addView(button("+") { guard { picker() } }, LinearLayout.LayoutParams(dp(44), dp(44)))
        })
        if (session.section == "settings") {
            listOf("监测与显示", "GPU 默认范围", "数据与维护", "关于").forEachIndexed { i, title ->
                master.addView(listRow("", title, "", session.settingsModule == i) { guard { session.settingsModule = i; session.clearDrafts(); render() } })
            }; return
        }
        if (session.section in setOf("tune", "thread")) master.addView(listRow("", if (session.section == "tune") "全局调控" else "规则库", "", session.selected.isEmpty()) { guard {
            session.clearDrafts(); session.selected = ""; render()
        } })
        val search = input("搜索应用或包名", query)
        master.addView(search, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(12); bottomMargin = dp(12) })
        val list = column()
        if (session.section == "thread") master.addView(segment(listOf("全部", "上游", "我的"), filter) { filter = it; populateList(list) })
        master.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        search.addTextChangedListener(watcher { query = it; populateList(list) }); populateList(list)
    }
    private fun populateList(list: LinearLayout) {
        list.removeAllViews()
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
            list.addView(listRow(pkg, title, subtitle, session.selected == pkg) { guard { select(pkg) } })
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
        heading("系统全局状态", "系统关键性能参数与组件运行情况")
        val stats = row()
        listOf("温度", "功耗", "核心组件").forEachIndexed { i, title ->
            val box = card(); box.addView(label(title, 12f, sub, true)); val metric = label("--", if (i == 2) 20f else 28f, ink, true); box.addView(metric)
            when (i) {
                0 -> { quietLabel = metric; quietMeter = meter(); box.addView(quietMeter, LinearLayout.LayoutParams(-1, dp(6))) }
                1 -> { powerLabel = metric; powerMeter = meter(); box.addView(powerMeter, LinearLayout.LayoutParams(-1, dp(6))); powerReason = note("等待数据"); box.addView(powerReason) }
                else -> {
                    val health = BackendHealth.components(state)
                    metric.text = if (health.all { it.state == BackendHealth.State.OK }) "5/5 正常" else health.firstOrNull { it.state in setOf(BackendHealth.State.FAILED, BackendHealth.State.DEGRADED) }?.component ?: "状态未知"
                    coreLabel = metric; metric.setSingleLine(true); metric.ellipsize = android.text.TextUtils.TruncateAt.END
                    if (health.any { it.state in setOf(BackendHealth.State.FAILED, BackendHealth.State.DEGRADED) }) metric.setTextColor(orange)
                    handler.postDelayed(coreTicker, 2000)
                    val bars = row(); health.forEach { c -> bars.addView(View(this).apply {
                        background = shape(when (c.state) { BackendHealth.State.OK -> accent; BackendHealth.State.DEGRADED -> orange; BackendHealth.State.FAILED -> getColor(R.color.mode_fast); else -> sub }, 3)
                    }, LinearLayout.LayoutParams(0, dp(6), 1f).apply { marginEnd = dp(4) }) }; box.addView(bars)
                    box.setOnClickListener { coreHealth() }
                }
            }
            stats.addView(box, LinearLayout.LayoutParams(0, -1, if (i == 2) 1.34f else 1f).apply { if (i > 0) marginStart = dp(12) })
        }; detail.addView(stats, gap())
        detail.addView(card().apply {
            addView(label("全局刷新率", 14f, ink, true)); addView(note("全局默认屏幕刷新率档位模式")); val rates = supportedRates()
            if (refresh.confirmed == 0) addView(note("全局档位尚未确认；独立应用的刷新率不会作为全局值。", orange))
            addView(segment(rates.map { "$it Hz" }, rates.indexOf(refresh.displayed)) { i -> optimistic(refresh, rates[i]) { session.gateway.setGlobal("refresh", value = rates[i]) } })
        }, gap())
        detail.addView(card().apply {
            addView(label("全局性能档位", 14f, ink, true)); addView(note("日常系统调度激进程度"))
            addView(tiers(mode.displayed, true) { id -> optimistic(mode, id) { session.gateway.setGlobal("mode", mode = id) } })
        }, gap())
        detail.addView(card().apply {
            addView(label("性能监视悬浮窗", 14f, ink, true)); addView(note("实时显示帧率、温度与功耗；通过悬浮窗开始记录"))
            overlayButton = button(if (this@MainActivity.overlay.displayed) "已开启" else "已关闭") { toggleOverlay() }; addView(overlayButton)
        }, gap())
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
        val health = BackendHealth.components(state)
        val zuioptState = ruleState
        val dialog = AlertDialog.Builder(this).setTitle("核心组件详情")
            .setMessage(health.joinToString("\n\n") { "${it.component} · ${it.state}\n${it.reason}" })
            .setPositiveButton("关闭", null)
        if (ZuioptRules.field(zuioptState, "failure") == "1") dialog.setNeutralButton("复位 ZUIopt 故障") { _, _ ->
            confirm("复位故障锁存？", "下次重启重新启用 ZUIopt；本次启动继续保持故障保护。") {
                session.work("故障已复位；下次重启生效") { ZuioptRules.command(this@MainActivity, "reset") }
            }
        }
        dialog.show().also { UiControls.styleDialog(it) }
    }
    private fun appPage() {
        val d = session.appDraft ?: return
        heading(name(d.packageName), "${d.packageName} · ${if (session.appDirty) "未保存" else "已保存"}")
        val configurable = UperfAppPolicy.isConfigurable(packageManager, d.packageName)
        detail.addView(card().apply {
            addView(label("自定义应用刷新率", 14f, ink, true)); val rates = supportedRates()
            addView(segment(rates.map { "$it Hz" }, rates.indexOf(d.refreshHz)) { session.appDraft = d.copy(refreshHz = rates[it]); render() })
            addView(label("自定义应用性能档位", 14f, ink, true))
            addView(tiers(d.uperfMode, configurable) { id -> session.appDraft = d.copy(uperfMode = id, gpuPolicy = ZuiControlClient.GpuPolicy.DEFAULT_FOR_MODE, gpuMinMHz = null, gpuMaxMHz = null); render() })
            if (!configurable) addView(note("此应用不支持 Uperf 配置；刷新率和适用的线程规则仍可配置"))
        }, gap())
        val range = runCatching {
            if (d.gpuPolicy == ZuiControlClient.GpuPolicy.CUSTOM) GpuRanges.Range(d.gpuMinMHz!!, d.gpuMaxMHz!!) else globalRanges().getValue(d.uperfMode)
        }.getOrElse { detail.addView(note("GPU 默认范围暂不可用 · ${it.message}", orange)); return }
        detail.addView(card().apply {
            addView(label("GPU 频率范围   ${range.min}–${range.max} MHz", 14f, ink, true))
            val bar = GpuRangeBar(this@MainActivity, range).apply {
                isEnabled = !session.busy && configurable && d.gpuPolicy == ZuiControlClient.GpuPolicy.CUSTOM
                onCommit = { session.appDraft = d.copy(gpuPolicy = ZuiControlClient.GpuPolicy.CUSTOM, gpuMinMHz = it.min, gpuMaxMHz = it.max); render() }
            }; addView(bar, LinearLayout.LayoutParams(-1, bar.preferredHeight))
            addView(segment(listOf("默认", "自定义"), if (d.gpuPolicy == ZuiControlClient.GpuPolicy.CUSTOM) 1 else 0, configurable) {
                session.appDraft = d.copy(gpuPolicy = if (it == 0) ZuiControlClient.GpuPolicy.DEFAULT_FOR_MODE else ZuiControlClient.GpuPolicy.CUSTOM,
                    gpuMinMHz = if (it == 0) null else range.min, gpuMaxMHz = if (it == 0) null else range.max); render()
            }); addView(note(if (d.gpuPolicy == ZuiControlClient.GpuPolicy.DEFAULT_FOR_MODE) "持续跟随「${modeTitle(d.uperfMode)}」档默认范围" else "仅此应用使用的固定范围"))
        }, gap())
        detail.addView(actionRow(if (model?.appProfile(d.packageName) != null) "查看规则" else "新建规则", "线程 CPU 放置") { guard {
            session.section = "thread"; session.selected = d.packageName; session.clearDrafts(); openRule(d.packageName); render(); loadAnalysis(d.packageName)
        } }, gap())
        if (!session.newApp) detail.addView(button("删除独立配置") { confirm("删除独立配置？", "回到全局策略，线程规则保留。") {
            session.work("已删除") { val reply = ZuiControlClient.removePackageProfile(applicationContext, d.packageName); check(reply.ok) { reply.text }; session.clearDrafts(); session.selected = "" }
        } }, gap())
        detail.addView(button(if (session.busy) "正在保存…" else "保存并生效", true) { saveDraft() }, gap())
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
        AlertDialog.Builder(this).setTitle("有未保存的修改").setMessage("保存成功后继续，或放弃本次草稿。")
            .setNegativeButton("取消", null).setNeutralButton("放弃修改") { _, _ -> session.clearDrafts(); next() }
            .setPositiveButton("保存") { _, _ -> saveDraft(next) }.show()
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { guard { if (session.selected.isNotEmpty()) { session.clearDrafts(); session.selected = ""; render() } else finish() } }
    private fun picker() {
        val box = column(); val list = column(); var system = false; var selected = ""; val search = input("搜索应用或包名")
        fun populate() {
            list.removeAllViews()
            installed.filter { isSystem(it) == system && (search.text.isBlank() || it.packageName.contains(search.text.toString(), true) || name(it.packageName).contains(search.text.toString(), true)) }.forEach { app ->
                val exists = if (session.section == "tune") policies?.apps?.any { it.draft.packageName == app.packageName } == true else model?.appProfile(app.packageName) != null
                list.addView(listRow(app.packageName, name(app.packageName), if (exists) "已配置" else app.packageName, selected == app.packageName) {
                    if (!exists) { selected = app.packageName; populate() }
                }.apply { isEnabled = !exists })
            }
        }
        box.addView(segment(listOf("用户应用", "系统应用"), 0) { system = it == 1; selected = ""; populate() }); box.addView(search)
        search.addTextChangedListener(watcher { populate() }); box.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, dp(320))); populate()
        val dialog = AlertDialog.Builder(this).setTitle("添加应用").setView(box).setNegativeButton("取消", null).setPositiveButton("下一步", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (selected.isEmpty()) return@setOnClickListener
            val authority = policies ?: return@setOnClickListener
            if (session.section == "tune" && (mode.confirmed !in GpuDefaultsDraft.modes || refresh.confirmed !in supportedRates())) { toast("全局策略暂不可用"); return@setOnClickListener }
            session.clearDrafts(); session.selected = selected
            if (session.section == "tune") {
                session.appDraft = ZuiControlClient.AppPolicyDraft(selected, refresh.confirmed, mode.confirmed, ZuiControlClient.GpuPolicy.DEFAULT_FOR_MODE, authority.generation); session.newApp = true
            } else openRule(selected)
            dialog.dismiss(); render(); if (session.section == "thread") loadAnalysis(selected)
        } }; dialog.show()
    }

    private fun threadHome() {
        heading("线程调度", "ZUIopt · 按线程 / 任务的 CPU 放置")
        if (ruleError.isNotEmpty()) detail.addView(note(ruleError, orange), gap())
        if (ZuioptRules.field(ruleState, "failure") == "1") detail.addView(actionRow("ZUIopt 已进入故障保护", ZuioptRules.field(ruleState, "failure_reason") + " · 下次开机重新启用") {
            confirm("下次开机重新启用？", "本次开机继续由 Android 调度，不会立即重启。") { session.work("已安排") { ZuioptRules.command(applicationContext, "reset") } }
        }, gap())
        val b = baseline
        detail.addView(card().apply {
            addView(row().apply {
                addView(label("当前规则集", 14f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(button("↻") { checkUpdates() }.apply { contentDescription = "检查更新" }, LinearLayout.LayoutParams(dp(44), dp(44)))
            })
            addView(note(b?.metadata?.let { "${it.optString("source")} · ${it.optString("sourceVersion")}\n${it.optString("sourceDate")} · ${it.optString("sourceCommit")}\n${b.generation}" } ?: "规则集不可用"))
            addView(note(updateStatus)); addView(button("查看原文") { rawView() })
            val apps = installed.filter { model?.appProfile(it.packageName) != null }
            addView(note("${apps.size} 个规则应用 · ${model?.profiles?.values?.sumOf { it.rules.size } ?: 0} 条特殊规则 · ${apps.count { provenance(it.packageName) in setOf("USER_MODIFIED", "USER_CREATED") }} 个我的"))
        }, gap())
        detail.addView(card().apply {
            addView(label("规则库", 14f, ink, true))
            addView(actionRow("同步上游规则", if (!RuleRemoteConfig.configured) RuleRemoteConfig.DIAGNOSTIC else latest?.let { "${it.version} · ${it.date}" } ?: "无待同步更新") { syncUpstream() }.apply { isEnabled = RuleRemoteConfig.configured && latest != null })
            val previous = ZuioptRules.field(ruleState, "previous_generation").matches(Regex("g[0-9a-f]{24}"))
            addView(actionRow("回退规则版本", if (previous) "回到上一代完整规则集" else "暂无可回退版本") { confirm("回退规则版本？", "上游基线和生效规则一起回退。") {
                session.work("已回退") { ZuioptRules.command(applicationContext, "rollback", checkNotNull(snapshot).generation) }
            } }.apply { isEnabled = previous })
            addView(actionRow("规则集导出", "导出完整 canonical 原文") { snapshot?.let { export(it.text.toByteArray(), "ZuiControl_rules.conf") } })
            addView(actionRow("高级兼容导入 · AppOpt", "兼容转换与差异预览后确认") { document(Intent.ACTION_OPEN_DOCUMENT, 102, "*/*") })
        }, gap())
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
        heading("规则库更新", "${source?.version} · ${p.apps.size} 个变化 · 原生三方预览")
        if (appOpt) detail.addView(note("AppOpt 为兼容格式；原生转换每条规则独立竞争组，不能表达共享竞争组 / rank:N。转换失败行由原生校验返回，不应用不完整包。"), gap())
        for (r in p.apps) {
            val pkg = r.getString("package")
            detail.addView(card().apply {
                addView(label(name(pkg), 14f, ink, true)); addView(note("${sourceTitle(r.optString("provenance"))} · ${if (r.optBoolean("conflict")) "需要你决定" else "自动采用 / 保留你的"}\n新增 ${r.optInt("threadAdded")} · 移除 ${r.optInt("threadRemoved")} · 修改 ${r.optInt("threadChanged")}\nCPU变化 ${r.optBoolean("cpuMaskChanged")} · 竞争组变化 ${r.optBoolean("selectorClassChanged")}"))
                addView(segment(listOf("保留我的", "采用上游", "手动合并"), (decisions[pkg] ?: ZuioptLibrary.Decision.valueOf(r.getString("decision"))).ordinal) { i ->
                    if (i == 2) mergeDialog(pkg) else session.work { decisions[pkg] = ZuioptLibrary.Decision.entries[i]; stagePreview(checkNotNull(this@MainActivity.baseline)) }
                })
            }, gap())
        }
        detail.addView(button("跳过此更新") { session.work("已跳过") { ZuioptLibrary.cancel(applicationContext, p); preview = null } }, gap())
        detail.addView(button("应用更新", true) { confirm("应用规则更新？", "全部变化作为一次新规则集写入，可整体回退。") {
            session.work("已应用更新") { ZuioptLibrary.confirm(applicationContext, p); preview = null; if (!appOpt) { latest = null; updateStatus = "已是最新" } }
        } }, gap())
    }
    private fun mergeDialog(pkg: String) {
        if (appOpt) { mergeAppOpt(pkg); return }
        val mine = model?.appProfile(pkg)
        val up = runCatching { ZuioptRuleModel.parseNormalized(incoming.toString(Charsets.UTF_8)).appProfile(pkg) }.getOrNull()
        if (mine == null || up == null) { toast("新增 / 删除应用请选择保留我的或采用上游"); return }
        var upMask = false; var upRules = false
        val box = column().apply {
            addView(note("当前默认 CPU：${mine.generalMask.sorted()}\n上游默认 CPU：${up.generalMask.sorted()}"))
            addView(segment(listOf("我的默认 CPU", "上游默认 CPU"), 0) { upMask = it == 1 })
            addView(note("当前 ${mine.rules.size} 条规则 · 上游 ${up.rules.size} 条规则"))
            addView(segment(listOf("我的特殊规则", "上游特殊规则"), 0) { upRules = it == 1 })
        }
        AlertDialog.Builder(this).setTitle("手动合并 · ${name(pkg)}").setView(box).setNegativeButton("取消", null)
            .setPositiveButton("重新预览") { _, _ -> session.work {
                val chosen = ZuioptRuleModel.Profile(if (upMask) up.generalMask else mine.generalMask, if (upRules) up.rules else mine.rules)
                val current = manual ?: ZuioptRuleModel(true, emptyMap(), emptyList())
                manual = if (current.appProfile(pkg) != null) current.editApp(pkg) { chosen } else addProfile(current, pkg, chosen)
                decisions[pkg] = ZuioptLibrary.Decision.MANUAL_MERGE; stagePreview(checkNotNull(this@MainActivity.baseline))
            } }.show()
    }
    private fun addProfile(base: ZuioptRuleModel, pkg: String, p: ZuioptRuleModel.Profile): ZuioptRuleModel {
        val alias = (0..64).map { "user$it" }.first { it !in base.profiles }
        return base.copy(profiles = base.profiles + (alias to p), mappings = (listOf(ZuioptRuleModel.Mapping("exact", pkg, alias, 0)) + base.mappings).mapIndexed { i, m -> m.copy(priority = 100000 - i) })
    }
    private fun rawView() {
        val raw = snapshot?.text ?: return
        val code = label(raw, 12f, sub).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        AlertDialog.Builder(this).setTitle("规则集原文 · 只读").setView(ScrollView(this).apply { addView(code) })
            .setNegativeButton("关闭", null).setPositiveButton("导出") { _, _ -> export(raw.toByteArray(), "ZuiControl_rules.conf") }.show()
    }
    private fun openRule(pkg: String) {
        val base = model ?: return
        val p = base.appProfile(pkg)
        session.ruleDraft = RuleDraft(pkg, base, checkNotNull(snapshot).generation, p, p ?: ZuioptRuleModel.Profile((0..7).toSet(), emptyList()))
    }
    private fun threadApp() {
        val pkg = session.selected; val d = session.ruleDraft
        heading(name(pkg), "$pkg · ${sourceTitle(provenance(pkg))} · ${if (d?.dirty == true) "未保存" else "已保存"}")
        if (d == null) detail.addView(note("此规则暂不能无损显示；可查看完整原文。$ruleError", orange), gap())
        else {
            val mapping = d.base.mappings.firstOrNull { when (it.matchKind) { "exact" -> it.packageName == pkg; "prefix" -> pkg.startsWith(it.packageName); else -> pkg.contains(it.packageName) } }
            detail.addView(card().apply {
                addView(label("Profile ${mapping?.profile ?: "新建"} · 按顺序匹配，可拖动调整", 14f, ink, true))
                addView(note("默认 CPU 范围")); addView(cpuPicker(d.profile.generalMask) { mask -> mutateRule { d.profile = d.profile.copy(generalMask = mask); render() } })
                d.profile.rules.forEachIndexed { index, rule ->
                    addView(card().apply {
                        addView(row().apply {
                            addView(button("≡").apply {
                                contentDescription = "拖动调整规则顺序"
                                setOnLongClickListener { startDragAndDrop(ClipData.newPlainText("rule-order", index.toString()), View.DragShadowBuilder(this), index, 0); true }
                            }, LinearLayout.LayoutParams(dp(44), dp(44)))
                            addView(label("${rule.matchKind} · ${rule.pattern}\n竞争组 ${groupName(d, rule.competitionClass)} · ${rule.selector}", 12f, ink, true).apply {
                                setOnClickListener { editRule(index) }
                            }, LinearLayout.LayoutParams(0, -2, 1f))
                            addView(button("×") { mutateRule { d.profile = d.profile.copy(rules = d.profile.rules.filterIndexed { i, _ -> i != index }); render() } })
                        })
                        addView(cpuPicker(rule.cpuMask) { mask -> mutateRule {
                            d.profile = d.profile.copy(rules = d.profile.rules.map { if (it.competitionClass == rule.competitionClass) it.copy(cpuMask = mask) else it }); render()
                        } })
                        if (rule.cpuMask.isEmpty()) addView(note("请选择 CPU 后再保存", orange))
                        addView(row().apply { addView(button("↑") { moveRule(index, index - 1) }); addView(button("↓") { moveRule(index, index + 1) }) })
                        setOnDragListener { _, event -> when (event.action) {
                            DragEvent.ACTION_DRAG_STARTED -> event.clipDescription?.label == "rule-order"
                            DragEvent.ACTION_DROP -> { (event.localState as? Int)?.let { moveRule(it, index) }; true }
                            else -> true
                        } }
                    }, gap())
                }
                addView(button("添加特殊线程规则") { editRule(null) })
            }, gap())
            detail.addView(button("保存并应用", true) { saveDraft() }, gap())
            if (provenance(pkg) == "USER_MODIFIED") detail.addView(button("恢复上游") { confirm("恢复上游规则？", "将放弃此应用的修改。") {
                session.work("已恢复上游") { ZuioptLibrary.restoreApp(applicationContext, pkg, checkNotNull(baseline)); session.ruleDraft = null }
            } }, gap())
            if (d.original != null) detail.addView(button("删除规则") { confirm("删除此应用规则？", "只移除此应用的独立包映射。") { session.work("已删除") {
                check(mapping?.matchKind == "exact") { "此应用由共享宽匹配映射覆盖，无法单独删除" }
                val next = d.base.copy(mappings = d.base.mappings.filterNot { it.matchKind == "exact" && it.packageName == pkg })
                ZuioptRules.upload(applicationContext, "user", next.normalized().toByteArray(), expectedGeneration = d.generation); session.ruleDraft = null
            } } }, gap())
        }
        detail.addView(button("查看原文 · 只读") { rawView() }, gap()); analysisCard()
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
    private fun editRule(index: Int?) {
        mutateRule {
            val d = session.ruleDraft ?: return@mutateRule; val old = index?.let { d.profile.rules[it] }
            val kinds = listOf("exact", "prefix", "contains", "glob"); var kind = old?.matchKind ?: "exact"
            val pattern = input("线程名匹配内容", old?.pattern.orEmpty())
            val classes = d.profile.rules.map { it.competitionClass }.distinct().toMutableList()
            val newClass = (0..64).map { "group$it" }.first { it !in classes }; classes += newClass
            var cls = old?.competitionClass ?: newClass; var selector = old?.selector ?: "all"; var mask = old?.cpuMask ?: emptySet()
            val rank = input("排名 N（1–1024）", selector.substringAfter(':', "1")).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
            val box = column(); box.addView(segment(listOf("精确", "前缀", "包含", "通配"), kinds.indexOf(kind)) { kind = kinds[it] }); box.addView(pattern)
            box.addView(AnchoredDropdown(this@MainActivity, classes.mapIndexed { i, _ -> "竞争组 ${('A'.code + i).toChar()}" }).apply {
                commitSelection(classes.indexOf(cls)); onSelection = { cls = classes[it] }
            })
            box.addView(segment(listOf("全部候选", "按排名"), if (selector == "all") 0 else 1) { selector = if (it == 0) "all" else "rank" }); box.addView(rank)
            box.addView(cpuPicker(mask) { mask = it })
            val dialog = AlertDialog.Builder(this).setTitle("特殊线程规则").setView(box).setNegativeButton("取消", null).setPositiveButton("加入草稿", null).create()
            dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = if (selector == "all") "all" else "rank:${rank.text}"
                if (pattern.text.isBlank() || pattern.text.length > 64 || mask.isEmpty() || selected != "all" && rank.text.toString().toIntOrNull() !in 1..1024) { toast("请填写合法匹配内容、排名和 CPU"); return@setOnClickListener }
                val rows = d.profile.rules.toMutableList(); val r = ZuioptRuleModel.Rule(cls, kind, pattern.text.toString(), selected, 0, mask)
                if (index == null) { if (rows.size >= 32) { toast("最多 32 条规则"); return@setOnClickListener }; rows += r } else rows[index] = r
                d.profile = d.profile.copy(rules = rows.map { if (it.competitionClass == cls) it.copy(selector = selected, cpuMask = mask) else it })
                dialog.dismiss(); render()
            } }; dialog.show(); UiControls.styleDialog(dialog)
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
            addView(label("记录关联线程分析", 14f, ink, true))
            if (a == null || a.length() == 0) {
                addView(note("暂无可用于线程分析的监测记录")); addView(note("请先开启性能监视悬浮窗，并对该应用完成一次记录。"))
                if (analysisError.isNotEmpty()) addView(note(analysisError, orange))
                addView(button("开启监测与查看记录指引") { if (!this@MainActivity.overlay.displayed) toggleOverlay(); monitorGuidance() }); return@apply
            }
            if (a.optInt("user", -1) != session.userId || a.optString("package") != session.selected) { addView(note("记录关联分析身份不匹配", orange)); return@apply }
            addView(note("来源记录 #${a.getLong("sourceRecordId")} · ${whenRecorded(a.getLong("sourceRecordWall"))} · ${duration(a.getLong("wallElapsedMs"))}\n${a.getString("sourceRecordCompletion")} · ${a.getString("sourceRecordTerminalReason")}\n有效样本 ${a.getInt("eligibleSamples")} · 分段 ${a.getInt("processSegmentCount")}"))
            val rows = a.getJSONArray("threads"); addView(note("${rows.length()} 个线程名组 · 单核 CPU = 100%")); val chosen = linkedSetOf<String>()
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                addView(CheckBox(this@MainActivity).apply {
                    setTextColor(ink); textSize = 12f
                    text = "${r.getString("name")} · 同名 ${r.getInt("distinctIdentityCount")} / 并发 ${r.getInt("sameNameConcurrencyMax")}\n出现 ${number(r.optDouble("presencePct"))}% · 平均 ${number(r.optDouble("avgCpuPct"))}% · 峰值 ${number(r.optDouble("peakCpuPct"))}%\n排名中位 ${number(r.optDouble("medianRank"))} · Top1 ${number(r.optDouble("top1SharePct"))}% / Top3 ${number(r.optDouble("top3SharePct"))}% · 样本 ${r.getInt("presenceSamples")}";
                    setOnCheckedChangeListener { _, yes -> if (yes) chosen += r.getString("name") else chosen -= r.getString("name") }
                })
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
        heading(if (session.selected.isEmpty()) "性能监测" else "${name(session.selected)} · 监测记录", "通过性能监视悬浮窗开始记录")
        recordBanner = row().apply {
            background = shape(field, 14); recordLabel = label("", 13f, orange, true)
            addView(recordLabel, LinearLayout.LayoutParams(0, -2, 1f)); addView(button("停止") { session.work("记录已停止") {
                val reply = session.gateway.stopRecord(); check(reply.ok) { reply.text }
            } }); visibility = View.GONE
        }; detail.addView(recordBanner, gap())
        val r = selectedRecord
        if (r == null || !r.has("package")) { detail.addView(note("选择一条记录查看详情。暂无记录时，请先开启性能监视悬浮窗。")); detail.addView(button("记录指引") { monitorGuidance() }); return }
        detail.addView(note("${whenRecorded(r.optLong("wall"))} · ${duration(r.optLong("duration"))} · ${if (r.optBoolean("complete")) "已结束" else r.optString("terminalReason", "未完成")}"), gap())
        if (recordThreads) {
            detail.addView(button("‹ 返回记录详情") { readRecord(session.selected) }, gap())
            detail.addView(note("Top15 入榜线程；入榜均值不是整段平均。时间线断档表示无已保存的 Top15 样本，不代表 CPU=0。"), gap())
            val rows = r.optJSONArray("threads") ?: JSONArray(); val same = (0 until rows.length()).groupingBy { rows.getJSONArray(it).optString(1) }.eachCount()
            for (i in 0 until rows.length()) {
                val t = rows.getJSONArray(i)
                detail.addView(actionRow("${t.optString(1)} · 同名 ${same[t.optString(1)]}", "TID ${t.optString(0).split(':').getOrNull(2)} · 入榜均值 ${number(t.optDouble(2))}% · 峰值 ${number(t.optDouble(3))}% · 入榜样本 ${t.optLong(4)}") {
                    startActivity(Intent(this, PerformanceRecordActivity::class.java).putExtra("package", session.selected).putExtra("thread", t.optString(0)).putExtra("name", t.optString(1)))
                }, gap())
            }; return
        }
        val policy = r.optJSONObject("policySnapshot")
        detail.addView(note(if (policy == null || policy.length() == 0) "记录开始时未保存策略快照" else
            "本次策略：${snapshotValue(policy, "refreshHz")} Hz · ${modeTitle(policy.optString("uperfMode"))} · GPU ${snapshotValue(policy, "gpuMinMHz")}–${snapshotValue(policy, "gpuMaxMHz")} MHz · ${when (policy.optString("gpuPolicy")) { "DEFAULT_FOR_MODE" -> "默认"; "CUSTOM" -> "自定义"; else -> "未知" }}\n策略 generation ${snapshotValue(policy, "policyGeneration")} · ZUIopt ${policy.optString("zuioptGeneration")} · ${policy.optString("profileSummaryValidity")}"), gap())
        val scalars = r.optJSONArray("scalars") ?: JSONArray(); val stats = r.optJSONArray("stats")?.optJSONArray(0) ?: JSONArray()
        listOf("帧率 · FPS", "功耗 · W", "温度 · quiet ℃").forEachIndexed { i, title -> detail.addView(card().apply {
            addView(label(title, 14f, ink, true)); addView(note("最低 ${number(stats.optDouble(i * 3, Double.NaN))} · 平均 ${number(stats.optDouble(i * 3 + 1, Double.NaN))} · 最高 ${number(stats.optDouble(i * 3 + 2, Double.NaN))}"))
            addView(RecordChart(this@MainActivity, scalars, i + 1, r.optLong("duration")), LinearLayout.LayoutParams(-1, dp(if (i == 0) 180 else 130)))
        }, gap()) }
        detail.addView(note("功耗统计不含插电/不可用时段，曲线断档保留缺失；FPS 为 DISPLAY_MEASURED_FPS。"))
        detail.addView(button("线程运行记录 · Top15") { readRecord(session.selected, true) }, gap())
        detail.addView(button("导出记录") { export(r.toString(2).toByteArray(), "ZuiControl_record.json") }, gap())
        detail.addView(button("删除记录") { confirm("删除记录？", "仅删除此应用最近一次记录。") { session.work("已删除") {
            val reply = PerformanceMonitor.command("recordDelete", session.selected); check(reply.startsWith("ok=1")) { reply }; selectedRecord = null
        } } }, gap())
    }
    private fun settingsPage() {
        when (session.settingsModule) {
            0 -> {
                heading("监测与显示", "悬浮窗权限、通知状态与界面主题")
                detail.addView(actionRow("悬浮窗权限", if (Settings.canDrawOverlays(this)) "已授权" else "去授权") { overlayPermission() }, gap())
                val nm = getSystemService(NotificationManager::class.java)
                val enabled = nm.areNotificationsEnabled() && nm.getNotificationChannel("zui_control_monitor_v1")?.importance != NotificationManager.IMPORTANCE_NONE
                detail.addView(card().apply {
                    addView(label("通知", 14f, ink, true)); addView(note(if (enabled) "已开启" else "已关闭"))
                    if (!enabled) addView(button("系统通知设置") { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) })
                }, gap())
                detail.addView(card().apply {
                    addView(label("界面主题", 14f, ink, true)); val values = listOf("system", "dark", "light")
                    addView(segment(listOf("跟随系统", "深色", "浅色"), values.indexOf(prefs.getString("theme", "system"))) { theme(values[it]) })
                }, gap())
            }
            1 -> {
                val d = session.gpuDraft ?: runCatching { GpuDefaultsDraft.fromState(state, session.userId) }.getOrElse {
                    heading("GPU 默认范围", "四档区间"); detail.addView(note("默认范围暂不可用 · ${it.message}", orange)); return
                }.also { session.gpuDraft = it }
                heading("GPU 默认范围", "四档区间 · ${if (d.dirty) "未保存" else "已保存"}")
                GpuDefaultsDraft.modes.forEach { id -> val r = d.ranges.getValue(id)
                    detail.addView(card().apply {
                        addView(label("${modeTitle(id)}  ${r.min}–${r.max} MHz", 14f, ink, true))
                        val bar = GpuRangeBar(this@MainActivity, r).apply { isEnabled = !session.busy; onCommit = { d.set(id, it); render() } }; addView(bar, LinearLayout.LayoutParams(-1, bar.preferredHeight))
                    }, gap())
                }
                detail.addView(button("恢复默认") { d.restoreDefaults(); render() }, gap()); detail.addView(button("保存并生效", true) { saveDraft() }, gap())
            }
            2 -> {
                heading("数据与维护", "配置备份、恢复与系统维护")
                detail.addView(actionRow("立即备份", prefs.getString("backup", "保存到你选择的位置").orEmpty()) {
                    session.work { backupBytes = SettingsBackup.export(applicationContext); session.pendingDocument = 103 }
                }, gap())
                detail.addView(actionRow("从备份恢复", "校验 → 摘要 → 确认") { document(Intent.ACTION_OPEN_DOCUMENT, 104, "application/zip") }, gap())
                detail.addView(actionRow("恢复出厂配置", "保留监测记录与上游基线") { confirm("恢复出厂配置？", "清除本用户应用策略，恢复全局档位、GPU 默认与偏好；线程规则恢复当前上游基线。保留监测记录、上游版本与诊断记录。") {
                    session.work("已恢复出厂配置") { SettingsBackup.factoryReset(applicationContext) }
                } }, gap())
                detail.addView(actionRow("导出运行日志", "可能包含应用与使用记录") { confirm("导出运行日志？", "日志可能包含已安装应用和使用信息，请妥善保存。") {
                    session.work { val id = ZuiControlRequest.send(applicationContext, ZuiControlContract.CMD_EXPORT_LOGS); val ack = ZuiControlRequest.awaitTerminalAck(applicationContext, id); check(ack.succeeded) { ack.detail }
                        exportBytes = ZuiControlClient.utilityValue("result", "$id|logs").toByteArray(); session.pendingDocument = 101 }
                } }, gap())
                detail.addView(actionRow("重启调度核心", "Uperf 与 ZUIopt；已保存配置保留") { confirm("重启调度核心？", "短暂恢复 Android 调度后重新加载已保存配置。") {
                    session.work("调度核心已重启") { val id = ZuiControlRequest.send(applicationContext, ZuiControlContract.CMD_RESTART_SCHEDULER); val ack = ZuiControlRequest.awaitTerminalAck(applicationContext, id); check(ack.succeeded) { ack.detail } }
                } }, gap())
            }
            else -> {
                heading("关于", "ZuiControl")
                detail.addView(card().apply { listOf("releaseVersion", "sourceBuild", "integrationSchema", "appPolicySchema", "zuioptSchema").forEach { key -> addView(note("$key · ${value(caps, key).ifEmpty { "暂不可用" }}")) } }, gap())
                detail.addView(button("使用帮助") { monitorGuidance() }, gap())
            }
        }
    }
    private fun theme(theme: String) { prefs.edit().putString("theme", theme).apply(); recreate() }
    private fun monitorGuidance() { AlertDialog.Builder(this).setTitle("监测记录指引")
        .setMessage("开启性能监视悬浮窗后，轻触长条切换圆形，双击圆形开始记录；记录中轻触悬浮窗或在监测页点击停止。切换业务应用、锁屏或满30分钟自动结束。线程分析读取该记录产生的结果，CPU 放置由你选择。")
        .setPositiveButton("知道了", null).show() }
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
    private var inspectionDialog: AlertDialog? = null
    private fun presentPending() {
        if (session.busy || !visible) return
        if (session.pendingDocument != 0) {
            val code = session.pendingDocument; session.pendingDocument = 0
            document(Intent.ACTION_CREATE_DOCUMENT, code, if (code == 103) "application/zip" else "text/plain",
                if (code == 103) "ZuiControl_settings.zip" else "ZuiControl_logs.txt")
        }
        val inspection = session.pendingInspection ?: return
        if (inspectionDialog?.isShowing == true) return
        val s = inspection.summary
        val summary = "兼容性：${s.optString("compatibility")}\n来源：${s.optString("sourceBuild")}\n用户范围：${s.opt("userScope")}\n应用策略：${s.optInt("appPolicyRows")} 条\nGPU 默认：${if (s.optBoolean("gpuDefaultsPresent")) "包含" else "无"}\n线程规则：${if (s.optBoolean("rulesPresent")) "包含" else "无"}\n悬浮窗偏好：${if (s.optBoolean("preferencesPresent")) "包含" else "无"}\n校验 SHA256：${inspection.hash}"
        fun decide(restore: Boolean) { session.pendingInspection = null; session.work(if (restore) "已恢复" else "") {
            if (restore) SettingsBackup.restore(applicationContext, inspection) else SettingsBackup.abort(inspection)
        } }
        inspectionDialog = AlertDialog.Builder(this).setTitle("恢复摘要").setMessage(summary)
            .setNegativeButton("取消") { _, _ -> decide(false) }.setPositiveButton("确认恢复") { _, _ -> decide(true) }
            .setOnCancelListener { decide(false) }.show()
    }
    private fun snapshotValue(j: JSONObject, key: String) = if (j.has(key) && !j.isNull(key)) j.get(key).toString() else "--"
    private fun supportedRates() = value(caps, "supportedDisplayHz").split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }.distinct()
    private fun globalRanges(): Map<String, GpuRanges.Range> = GpuDefaultsDraft.fromState(state, session.userId).original
    private fun provenance(pkg: String) = upstreamModel?.let { model?.provenance(pkg, it) }.orEmpty()
    private fun sourceTitle(source: String) = when (source) { "UPSTREAM" -> "上游"; "USER_MODIFIED" -> "我的修改"; "USER_CREATED" -> "我的新建"; else -> "来源未知" }
    private fun modeTitle(id: String) = UperfMode.fromId(id)?.title ?: "--"
    private fun value(source: String, key: String) = ZuiControlClient.stateValue(source, key).orEmpty()
    private fun name(pkg: String) = runCatching { packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString() }.getOrDefault(pkg)
    private fun isSystem(app: ApplicationInfo) = app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    private fun whenRecorded(wall: Long) = if (wall > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(wall)) else "时间未知"
    private fun duration(ms: Long) = String.format(Locale.ROOT, "%02d:%02d", ms / 60000, ms / 1000 % 60)
    private fun number(n: Double) = if (!n.isFinite() || n < 0) "--" else String.format(Locale.ROOT, "%.1f", n)
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    private fun confirm(title: String, message: String, action: () -> Unit) { AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("取消", null).setPositiveButton("确认") { _, _ -> action() }.show() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun shape(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), stroke) }
    private fun translucent(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    private fun meter() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100; progressTintList = android.content.res.ColorStateList.valueOf(accent)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(field)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun mergeAppOpt(pkg: String) {
        val mine = manual?.appProfile(pkg) ?: model?.appProfile(pkg)
        var mask = mine?.generalMask ?: emptySet()
        val kept = mine?.rules.orEmpty().toMutableList()
        val pattern = input("可选：添加精确线程名")
        val box = column().apply {
            addView(note("原生预览提供差异摘要。手动合并以当前规则为基础，选择 CPU、保留线程或手动添加；采用导入配置请选「采用上游」。"))
            addView(cpuPicker(mask) { mask = it })
            mine?.rules?.forEach { rule -> addView(CheckBox(this@MainActivity).apply {
                text = "${rule.matchKind} ${rule.pattern} · ${rule.selector} · CPU ${rule.cpuMask.sorted()}"; setTextColor(ink); isChecked = true
                setOnCheckedChangeListener { _, yes -> if (yes) { if (rule !in kept) kept += rule } else kept -= rule }
            }) }
            addView(pattern); addView(note("新增精确规则使用上方选择的 CPU；已有规则保持原竞争组和 CPU。"))
        }
        val dialog = AlertDialog.Builder(this).setTitle("手动合并 · ${name(pkg)}").setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("取消", null).setPositiveButton("重新预览", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (mask.isEmpty() || pattern.text.length > 64 || pattern.text.any { it.code !in 32..126 }) { toast("请选择 CPU 并填写合法线程名"); return@setOnClickListener }
            val ordered = mine?.rules.orEmpty().filter { it in kept }.toMutableList()
            if (pattern.text.isNotBlank()) {
                if (ordered.size >= 32) { toast("最多 32 条规则"); return@setOnClickListener }
                val alias = (0..64).map { "group$it" }.first { name -> ordered.none { it.competitionClass == name } }
                ordered += ZuioptRuleModel.Rule(alias, "exact", pattern.text.toString(), "all", 0, mask)
            }
            val profile = ZuioptRuleModel.Profile(mask, ordered.mapIndexed { i, r -> r.copy(priority = 100000 - i) })
            dialog.dismiss(); session.work {
                val base = manual ?: ZuioptRuleModel(true, emptyMap(), emptyList())
                manual = if (base.appProfile(pkg) == null) addProfile(base, pkg, profile) else base.editApp(pkg) { profile }
                decisions[pkg] = ZuioptLibrary.Decision.MANUAL_MERGE; stagePreview(checkNotNull(this@MainActivity.baseline))
            }
        } }; dialog.show(); UiControls.styleDialog(dialog)
    }
    private fun card() = column().apply { setPadding(dp(20), dp(18), dp(20), dp(18)); background = shape(surface, 18, getColor(R.color.ui_line)) }
    private fun gap() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
    private fun label(text: String, size: Float = 13f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(4), 0, dp(4))
    }
    private fun heading(title: String, subtitle: String) { detail.addView(label(title, 20f, ink, true)); detail.addView(label(subtitle, 11.5f, sub), gap()) }
    private fun note(text: String, color: Int = sub) = label(text, 11.5f, color)
    private fun button(title: String, selected: Boolean = false, action: () -> Unit = {}) = label(title, 13f, if (selected) Color.WHITE else sub, true).apply {
        gravity = Gravity.CENTER; minHeight = dp(44); setPadding(dp(10), dp(6), dp(10), dp(6)); background = shape(if (selected) accent else field, 12)
        isFocusable = true; contentDescription = title; setOnClickListener { if (!session.busy) action() else toast("操作处理中，请等待结果") }
    }
    private fun segment(values: List<String>, selected: Int, enabled: Boolean = true, action: (Int) -> Unit): View = row().apply {
        background = shape(field, 14); setPadding(dp(4), dp(4), dp(4), dp(4))
        values.forEachIndexed { i, title -> addView(button(title, i == selected) { if (enabled) action(i) }.apply { isEnabled = enabled; if (!enabled) alpha = .45f }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i > 0) marginStart = dp(4) }) }
    }
    private fun tiers(current: String, enabled: Boolean, action: (String) -> Unit): View = row().apply {
        UperfMode.entries.forEach { m -> addView(button("◴ ${m.title}", current == m.id) { if (enabled) action(m.id) }.apply {
            if (current == m.id) background = shape(getColor(m.color), 14); isEnabled = enabled; if (!enabled) alpha = .45f
        }, LinearLayout.LayoutParams(0, dp(64), 1f).apply { if (m.ordinal > 0) marginStart = dp(10) }) }
    }
    private fun cpuPicker(initial: Set<Int>, action: (Set<Int>) -> Unit): View {
        var selected = initial; val group = row()
        fun bind() { group.removeAllViews(); (0..7).forEach { cpu ->
            group.addView(button(cpu.toString(), cpu in selected) {
                val next = if (cpu in selected) selected - cpu else selected + cpu
                if (next.isEmpty()) { toast("至少保留一个 CPU"); return@button }; selected = next; action(next); bind()
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(3) })
        } }; bind(); return group
    }
    private fun input(hint: String, text: String = "") = EditText(this).apply {
        this.hint = hint; setText(text); textSize = 12f; setTextColor(ink); setHintTextColor(sub); setSingleLine(true)
        background = shape(field, 12); setPadding(dp(12), dp(8), dp(12), dp(8))
    }
    private fun watcher(action: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { action(s.toString()) }
        override fun afterTextChanged(s: Editable?) = Unit
    }
    private fun actionRow(title: String, subtitle: String, action: () -> Unit) = row().apply {
        minimumHeight = dp(60); setPadding(0, dp(8), 0, dp(8))
        addView(column().apply { addView(label(title, 13f, ink, true)); addView(note(subtitle)) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(label("›", 22f, sub)); isFocusable = true; setOnClickListener { if (!session.busy) action() }
    }
    private fun listRow(pkg: String, title: String, subtitle: String, selected: Boolean, action: () -> Unit) = row().apply {
        setPadding(dp(12), dp(11), dp(12), dp(11)); background = shape(surface, 14, if (selected) accent else getColor(R.color.ui_line))
        if (pkg.isNotEmpty()) addView(ImageView(this@MainActivity).apply {
            setImageDrawable(runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        addView(column().apply { addView(label(title, 13.5f, ink, true)); addView(note(subtitle)) }, LinearLayout.LayoutParams(0, -2, 1f))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8); topMargin = dp(4) }
        isFocusable = true; contentDescription = "$title $subtitle"; setOnClickListener { if (!session.busy) action() }
    }
}
