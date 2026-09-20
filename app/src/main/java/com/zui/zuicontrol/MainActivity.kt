package com.zui.zuicontrol

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRules = linkedMapOf<String, Int>()
    private val uperfRules = linkedMapOf<String, UperfMode>()
    private val gpuOverrides = linkedMapOf<String, GpuRanges.Range>()
    private val gpuGlobals = linkedMapOf<String, GpuRanges.Range>()
    private val labelCache = linkedMapOf<String, String>()

    private lateinit var contentHost: FrameLayout
    private lateinit var tabButtons: Map<Page, TextView>
    private lateinit var settingsButton: ImageView
    private lateinit var headerStatus: TextView

    private var currentPage = Page.REFRESH
    private var commandInFlight = false
    private var lastCommandAt = 0L
    private var pendingExportText = ""
    private var zuioptState = ""
    private val refreshUi = Runnable {
        if (!commandInFlight && !isFinishing && ::contentHost.isInitialized) {
            reloadState(); renderCurrentPage()
        }
    }
    private val profileObserver = object : android.database.ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(refreshUi); handler.postDelayed(refreshUi, 100)
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = COLOR_BG
        window.navigationBarColor = COLOR_BG
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        reloadState()
        val appContext = applicationContext
        Thread { runCatching { ZuiControlRequest.recoverPending(appContext) } }.start()
        setContentView(buildRoot())
        val restoredPage = savedInstanceState?.getString(STATE_PAGE)?.let { name ->
            Page.entries.firstOrNull { it.name == name }
        } ?: Page.REFRESH
        showPage(restoredPage)
        if (!BuildConfig.DEBUG) handler.postDelayed({ ZuiControlQuickService.start(this) }, 250)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PAGE, currentPage.name)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        listOf(ZuiControlContract.KEY_STATUS_TEXT, ZuiControlContract.KEY_UPERF_MODE,
            ZuiControlContract.KEY_UPERF_RULES_TEXT).forEach {
            contentResolver.registerContentObserver(Settings.System.getUriFor(it), false, profileObserver)
        }
        if (::contentHost.isInitialized) {
            reloadState()
            renderCurrentPage()
        }
    }

    override fun onPause() {
        contentResolver.unregisterContentObserver(profileObserver)
        handler.removeCallbacks(refreshUi)
        super.onPause()
    }

    private fun buildRoot(): View {
        val portrait = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val root = vertical().apply {
            setBackgroundColor(COLOR_BG)
            setPadding(dp(16), dp(12), dp(16), dp(16))
            addView(header(), matchWrap())
        }
        contentHost = FrameLayout(this)
        if (portrait) {
            root.addView(contentHost, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ))
            root.addView(pageTabs(horizontal = true), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(70),
            ).apply { setMargins(0, dp(8), 0, 0) })
        } else {
            root.addView(horizontalRow().apply {
                background = null
                setPadding(0, 0, 0, 0)
                addView(pageTabs(horizontal = false), LinearLayout.LayoutParams(
                    dp(104), ViewGroup.LayoutParams.MATCH_PARENT,
                ).apply { setMargins(0, 0, dp(12), 0) })
                addView(contentHost, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f,
                ))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        return root
    }

    private fun header(): View = horizontalRow().apply {
        background = null
        elevation = 0f
        setPadding(0, 0, 0, dp(12))
        addView(vertical().apply {
            addView(label("ZuiControl", 28f, COLOR_TEXT, Typeface.BOLD))
            headerStatus = label(headerStatusText(), 12f, COLOR_SUBTLE, Typeface.NORMAL)
            addView(headerStatus)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(iconButton(R.drawable.ic_action_refresh, "刷新状态") {
            runCommand("正在刷新", success = "状态已刷新") {
                ZuiControlRequest.send(this@MainActivity, ZuiControlContract.CMD_STATUS)
            }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        settingsButton = iconButton(R.drawable.ic_nav_system, "设置") { showPage(Page.SYSTEM) }
        addView(settingsButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            setMargins(dp(8), 0, 0, 0)
        })
    }

    private fun pageTabs(horizontal: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(COLOR_BG)
        }
        val map = linkedMapOf<Page, TextView>()
        Page.entries.filter { it != Page.SYSTEM }.forEach { page ->
            val button = label(page.title, 12f, COLOR_SUBTLE, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                setCompoundDrawablesRelativeWithIntrinsicBounds(0, page.iconRes, 0, 0)
                compoundDrawablePadding = dp(4)
                setPadding(dp(10), dp(7), dp(10), dp(6))
                setOnClickListener { showPage(page) }
            }
            map[page] = button
            row.addView(button, LinearLayout.LayoutParams(
                if (horizontal) dp(108) else dp(96),
                if (horizontal) dp(62) else dp(76),
            ).apply {
                if (row.childCount > 0) {
                    if (horizontal) setMargins(dp(8), 0, 0, 0)
                    else setMargins(0, dp(8), 0, 0)
                }
            })
        }
        tabButtons = map
        return row
    }

    private fun showPage(page: Page) {
        currentPage = page
        tabButtons.forEach { (item, view) ->
            val selected = item == page
            val color = if (selected) COLOR_ACCENT else COLOR_SUBTLE
            view.setTextColor(color)
            view.compoundDrawableTintList = ColorStateList.valueOf(color)
            view.background = rounded(
                if (selected) COLOR_SELECTED else Color.TRANSPARENT,
                dp(18), Color.TRANSPARENT,
            )
        }
        val selected = page == Page.SYSTEM
        settingsButton.imageTintList = ColorStateList.valueOf(
            if (selected) COLOR_ACCENT else COLOR_SUBTLE,
        )
        settingsButton.background = rounded(
            if (selected) COLOR_SELECTED else COLOR_FIELD,
            dp(22), Color.TRANSPARENT,
        )
        renderCurrentPage()
    }

    private fun renderCurrentPage() {
        headerStatus.text = headerStatusText()
        contentHost.removeAllViews()
        val view = when (currentPage) {
            Page.REFRESH -> buildRefreshPage()
            Page.UPERF -> buildUperfPage()
            Page.THREADS -> buildThreadsPage()
            Page.SYSTEM -> buildSystemPage()
        }
        contentHost.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
    }

    private fun headerStatusText(): String {
        val displayHz = ZuiControlClient.currentDisplayHz()?.toString()
            ?: setting(ZuiControlContract.KEY_ACTIVE_REFRESH).removeSuffix(".0").ifBlank { "120" }
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        return "v$version · ${displayHz}Hz"
    }

    private fun buildRefreshPage(): View {
        val root = FrameLayout(this)
        val content = vertical().apply { setPadding(0, 0, 0, dp(76)) }
        if (refreshRules.isEmpty()) {
            content.addView(emptyText("点击右下角 + 添加应用"), matchWrap())
        } else {
            addAppGrid(content, refreshRules.keys.toList(), { "${refreshRules.getValue(it)}Hz" }) {
                showRefreshRateDialog(it, refreshRules[it])
            }
        }
        root.addView(ScrollView(this).apply { addView(content) }, matchMatchFrame())
        addFloatingButton(root) {
            showPackagePicker("选择刷新率应用") { entry ->
                labelCache[entry.info.packageName] = entry.label()
                showRefreshRateDialog(entry.info.packageName, refreshRules[entry.info.packageName])
            }
        }
        return root
    }

    private fun showRefreshRateDialog(pkg: String, currentRate: Int?) {
        val picker = traySpinner(ZuiControlContract.rates.map { "${it}Hz" })
        picker.setSelection(
            ZuiControlContract.rates.indexOf(currentRate).takeIf { it >= 0 }
                ?: ZuiControlContract.rates.indexOf(RefreshSceneController.BASE_REFRESH_RATE),
        )
        AlertDialog.Builder(this)
            .setTitle(if (currentRate == null) "添加刷新率" else "编辑刷新率")
            .setView(dialogContent(pkg, "刷新率", picker))
            .setPositiveButton("保存") { _, _ ->
                setRefreshProfile(pkg, ZuiControlContract.rates[picker.selectedItemPosition])
            }
            .apply {
                if (currentRate != null) setNeutralButton("删除") { _, _ -> removeRefreshProfile(pkg) }
            }
            .setNegativeButton("取消", null)
            .showStyled()
    }

    private fun setRefreshProfile(pkg: String, rate: Int) {
        runCommand("正在设置 ${rate}Hz", refreshNotification = true) {
            val reply = ZuiControlClient.setPackageDisplayHz(pkg, rate)
            check(reply.ok) { reply.text }
            null
        }
    }

    private fun removeRefreshProfile(pkg: String) {
        runCommand("正在移除规则", refreshNotification = true) {
            val reply = ZuiControlClient.removePackageProfile(pkg)
            check(reply.ok) { reply.text }
            null
        }
    }

    private fun buildUperfPage(): View {
        val root = FrameLayout(this)
        val content = vertical().apply {
            setPadding(0, 0, 0, dp(76))
            addView(sectionTitle("全局模式"), sectionMargins())
            val selected = UperfMode.fromId(setting(ZuiControlContract.KEY_UPERF_MODE))
                ?: UperfMode.BALANCE
            addView(horizontalRow().apply {
                background = null
                elevation = 0f
                setPadding(0, 0, 0, 0)
                UperfMode.entries.forEachIndexed { index, mode ->
                    addView(UiControls.modeChip(this@MainActivity, mode, mode == selected).apply {
                        textSize = 15f
                        setOnClickListener { setUperfMode(mode) }
                    }, LinearLayout.LayoutParams(0, dimen(R.dimen.ui_mode_height), 1f).apply {
                        if (index > 0) setMargins(dimen(R.dimen.ui_row_gap), 0, 0, 0)
                    })
                }
            }, LinearLayout.LayoutParams(minOf(dimen(R.dimen.ui_mode_group_width),
                dp(resources.configuration.screenWidthDp - 48 -
                    if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 116 else 0)), -2)
                .apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(sectionTitle("自定义应用"), sectionMargins())
            if (uperfRules.isEmpty() && gpuOverrides.isEmpty()) {
                addView(emptyText("点击右下角 + 添加应用"))
            } else {
                addAppGrid(this, (uperfRules.keys + gpuOverrides.keys).toList(),
                    { (uperfRules[it] ?: selected).title }, { (uperfRules[it] ?: selected) }) { showUperfAppDialog(it) }
            }
        }
        root.addView(ScrollView(this).apply { addView(content) }, matchMatchFrame())
        addFloatingButton(root) {
            showPackagePicker(
                "选择 Uperf 应用",
                userAppsOnly = true,
                launchableOnly = true,
            ) { showUperfAppDialog(it.info.packageName) }
        }
        return root
    }

    private fun showUperfAppDialog(pkg: String, current: UperfMode? = uperfRules[pkg]) {
        val modes = UperfMode.entries
        val picker = traySpinner(modes.map { it.title })
        picker.setSelection(modes.indexOf(current ?: UperfMode.PERFORMANCE).coerceAtLeast(0))
        val gpuEditor = gpuRangeEditor(pkg) { modes[picker.selectedItemPosition] }
        picker.onSelection = { position ->
            (gpuEditor.tag as GpuRangeBar).range = gpuOverrides[pkg] ?: globalGpuRange(modes[position])
        }
        val content = dialogContent(pkg, "性能模式", picker) as LinearLayout
        content.addView(fieldTitle("GPU频率范围"), fieldMargins())
        content.addView(gpuEditor)
        AlertDialog.Builder(this)
            .setTitle(if (current == null) "添加自定义应用" else "编辑自定义应用")
            .setView(content)
            .setPositiveButton("保存") { _, _ -> setUperfApp(pkg, modes[picker.selectedItemPosition]) }
            .apply {
                if (current != null) setNeutralButton("删除") { _, _ -> removeUperfApp(pkg) }
            }
            .setNegativeButton("取消", null)
            .showStyled()
    }

    private fun globalGpuRange(mode: UperfMode) = gpuGlobals[mode.id] ?: GpuRanges.default(mode.id)

    private fun gpuRangeEditor(pkg: String, mode: () -> UperfMode): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        val bar = GpuRangeBar(this@MainActivity, gpuOverrides[pkg] ?: globalGpuRange(mode()))
        tag = bar
        val reset = chip("默认")
        fun update() { reset.isEnabled = gpuOverrides.containsKey(pkg); reset.alpha = if (reset.isEnabled) 1f else 0.5f }
        fun save(value: GpuRanges.Range?) {
            bar.isEnabled = false; reset.isEnabled = false
            Thread {
                val reply = ZuiControlClient.setGpuRange(pkg, value)
                handler.post {
                    if (reply.ok) {
                        if (value == null) gpuOverrides.remove(pkg) else gpuOverrides[pkg] = value
                    } else toast("GPU 范围保存失败：${reply.text}")
                    bar.range = gpuOverrides[pkg] ?: globalGpuRange(mode())
                    bar.isEnabled = true; reset.isEnabled = true; update()
                }
            }.start()
        }
        bar.onCommit = { save(it) }
        reset.setOnClickListener { save(null) }
        addView(bar, LinearLayout.LayoutParams(0, bar.preferredHeight, 1f))
        addView(reset, LinearLayout.LayoutParams(-2, dimen(R.dimen.ui_chip_height)).apply {
            marginStart = dimen(R.dimen.ui_row_gap)
            gravity = Gravity.TOP; topMargin = bar.chipTopMargin
        })
        update()
    }

    private fun showGlobalGpuRanges() {
        val rows = vertical()
        UperfMode.entries.forEach { mode ->
            val bar = GpuRangeBar(this, globalGpuRange(mode))
            bar.onCommit = { value ->
                bar.isEnabled = false
                Thread {
                    val reply = ZuiControlClient.setGlobalGpuRange(mode.id, value)
                    handler.post {
                        if (reply.ok) gpuGlobals[mode.id] = value else toast("GPU 范围保存失败：${reply.text}")
                        bar.range = globalGpuRange(mode); bar.isEnabled = true
                    }
                }.start()
            }
            rows.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(chip(mode.title), LinearLayout.LayoutParams(-2, dimen(R.dimen.ui_chip_height)).apply {
                    marginEnd = dimen(R.dimen.ui_row_gap)
                    gravity = Gravity.TOP; topMargin = bar.chipTopMargin
                })
                addView(bar, LinearLayout.LayoutParams(0, bar.preferredHeight, 1f))
            }, cardMargins())
        }
        AlertDialog.Builder(this).setTitle("GPU频率范围")
            .setView(ScrollView(this).apply { addView(rows) })
            .setPositiveButton("完成", null).showStyled()
    }

    private fun setUperfMode(mode: UperfMode) {
        runCommand("正在切换 Uperf ${mode.title}", success = "Uperf 已切换为${mode.title}") {
            ZuiControlRequest.send(this, ZuiControlContract.CMD_SET_UPERF_MODE, mode = mode.id)
        }
    }

    private fun setUperfApp(pkg: String, mode: UperfMode) {
        runCommand("正在保存自定义应用", success = "自定义应用已保存") {
            ZuiControlRequest.send(
                this, ZuiControlContract.CMD_SET_UPERF_APP, pkg = pkg, mode = mode.id,
            )
        }
    }

    private fun removeUperfApp(pkg: String) {
        runCommand("正在删除自定义应用", success = "应用已恢复全局模式") {
            ZuiControlRequest.send(this, ZuiControlContract.CMD_REMOVE_UPERF_APP, pkg = pkg)
        }
    }

    private fun buildThreadsPage(): View = ScrollView(this).apply {
        addView(vertical().apply {
            setPadding(0, 0, 0, dp(20))
            addView(sectionTitle("ZUIopt"), sectionMargins())
            addView(compactNote(
                if (zuioptState.isEmpty()) "尚未查询本次开机状态，请先刷新。" else
                    "线程管理：${ZuioptRules.field(zuioptState, "threadManagerState")}\n" +
                    "ZUIopt：${ZuioptRules.field(zuioptState, "service")} · " +
                    "故障保护：${ZuioptRules.field(zuioptState, "fail_safe")}",
            ))
            addView(settingsAction(
                R.drawable.ic_action_refresh, "刷新规则与运行状态", "按需查询，不在后台轮询线程",
            ) { zuioptAction("正在查询规则") {} }, settingsActionMargins())
            if (ZuioptRules.field(zuioptState, "failure") == "1") {
                addView(settingsAction(
                    R.drawable.ic_action_refresh, "清除 ZUIopt 故障保护", "下次重启重新启用 ZUIopt；保留所有规则",
                ) {
                    AlertDialog.Builder(this@MainActivity).setTitle("清除 ZUIopt 故障保护")
                        .setMessage("下次重启重新启用 ZUIopt。本次开机继续使用 Android 默认调度，不会自动重启设备。")
                        .setPositiveButton("清除") { _, _ ->
                            zuioptAction("正在清除故障保护") {
                                ZuioptRules.command(this@MainActivity, "reset")
                            }
                        }.setNegativeButton("取消", null).showStyled()
                }, settingsActionMargins())
            } else if (ZuioptRules.field(zuioptState, "fail_safe") == "1") {
                addView(compactNote("下次重启重新启用 ZUIopt。本次开机继续使用 Android 默认调度。"), fieldMargins())
            }
            addView(sectionTitle("Rule Packs"), sectionMargins())
            addView(settingsAction(R.drawable.ic_action_import, "导入 ZUIopt 规则包", "最多 128 KiB；新包默认禁用；同 ID 替换") {
                openZuioptImport(REQUEST_IMPORT_ZUIOPT)
            }, settingsActionMargins())
            addView(settingsAction(R.drawable.ic_action_import, "导入 AppOpt 文本", "最多 64 KiB；必须有明确的应用默认 CPU") {
                openZuioptImport(REQUEST_IMPORT_APPOPT)
            }, settingsActionMargins())
            zuioptState.lineSequence().filter { it.startsWith("pack=") }.forEach { line ->
                val pack = line.substringAfter('=').split('|')
                if (pack.size == 5) addView(settingsAction(
                    R.drawable.ic_nav_threads, "${pack[0]} · ${if (pack[3] == "1") "已启用" else "已禁用"}",
                    "v${pack[1]} · 优先级 ${pack[2]} · 点击${if (pack[3] == "1") "禁用" else "启用"}",
                ) {
                    zuioptAction("正在修改规则包状态") {
                        ZuioptRules.command(this@MainActivity, if (pack[3] == "1") "disable" else "enable", pack[0])
                    }
                }, settingsActionMargins())
            }
            addView(sectionTitle("User Rules"), sectionMargins())
            addView(compactNote("用户规则 > 已启用规则包 > 工厂规则。工厂库：27 个 profile / 316 条应用映射；这是静态兼容库，不代表全部应用已实机测试。"))
            addView(settingsAction(R.drawable.ic_nav_threads, "编辑用户规则", "Schema 2；保存前校验，失败保留原配置") {
                var text = ""
                runCommand("正在读取用户规则", success = null, onSuccess = { editZuioptRules(text) }) {
                    text = ZuioptRules.userRules(this@MainActivity); null
                }
            }, settingsActionMargins())
            addView(settingsAction(R.drawable.ic_action_refresh, "回退上一份规则", "仅回退规则，不改变故障保护") {
                zuioptAction("正在回退规则") { ZuioptRules.command(this@MainActivity, "rollback") }
            }, settingsActionMargins())
        })
    }

    private fun zuioptAction(message: String, action: () -> Unit) {
        var latest = ""
        runCommand(message, onSuccess = { zuioptState = latest }) {
            action(); latest = ZuioptRules.state(this); null
        }
    }

    private fun editZuioptRules(text: String) {
        val editor = EditText(this).apply {
            setText(text)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            gravity = Gravity.TOP
            minLines = 8
            maxLines = 18
            filters = arrayOf(android.text.InputFilter.LengthFilter(ZuioptRules.RULE_LIMIT))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(this).setTitle("用户规则 · Schema 2")
            .setView(editor).setPositiveButton("保存") { _, _ ->
                val bytes = editor.text.toString().toByteArray(Charsets.UTF_8)
                zuioptAction("正在校验并保存用户规则") { ZuioptRules.upload(this, "user", bytes) }
            }.setNegativeButton("取消", null).showStyled()
    }

    @Suppress("DEPRECATION")
    private fun openZuioptImport(requestCode: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, requestCode)
    }

    private fun importZuioptDocument(uri: Uri, appopt: Boolean) {
        fun doImport(packId: String = "-", priority: Int = 0) {
            zuioptAction("正在分段传输并校验规则") {
                val kind = if (appopt) "appopt" else "pack"
                ZuioptRules.upload(this, kind, ZuioptRules.readDocument(this, uri, kind), packId, priority)
            }
        }
        if (!appopt) { doImport(); return }
        val id = EditText(this).apply { hint = "规则包 ID（小写字母开头）"; setText("appopt-import"); setSingleLine(true) }
        val priority = EditText(this).apply {
            hint = "优先级（-1000000 至 1000000）"; setText("0"); setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        AlertDialog.Builder(this).setTitle("导入 AppOpt")
            .setView(vertical().apply { addView(id); addView(priority) })
            .setPositiveButton("导入") { _, _ ->
                val value = priority.text.toString().toIntOrNull()
                val name = id.text.toString()
                if (value == null || value !in -1000000..1000000 || !name.matches(Regex("[a-z][a-z0-9_.-]{0,63}"))) toast("ID 或优先级无效")
                else doImport(name, value)
            }.setNegativeButton("取消", null).showStyled()
    }

    private fun buildSystemPage(): View = ScrollView(this).apply {
        addView(vertical().apply {
            setPadding(0, 0, 0, dp(20))
            val state = ZuiControlClient.stateText()
            val active = ZuiControlClient.stateValue(state, "schedulerActive") ?: "unknown"
            val uperfState = ZuiControlClient.stateValue(state, "uperfServiceState") ?: "unknown"
            val uperfMode = ZuiControlClient.stateValue(state, "uperfMode") ?: "unknown"
            val threadState = ZuiControlClient.stateValue(state, "threadManagerState") ?: "unknown"
            val schedulerError = ZuiControlClient.stateValue(state, "schedulerHealth") ?: "unknown"
            val ownership = when (active) {
                "1" -> "active"
                "0" -> "inactive"
                else -> "unknown"
            }
            listOf("调度" to ownership, "Uperf" to "$uperfState / $uperfMode",
                "ZUIopt" to threadState, "刷新率" to "system").chunked(2).forEach { items ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    items.forEachIndexed { index, (title, value) ->
                        addView(vertical().apply {
                            val pad = dimen(R.dimen.ui_chip_padding)
                            setPadding(pad, dimen(R.dimen.ui_row_gap), pad, dimen(R.dimen.ui_row_gap))
                            background = rounded(COLOR_FIELD, dimen(R.dimen.ui_card_radius), Color.TRANSPARENT)
                            addView(label(title, 11f, COLOR_SUBTLE, Typeface.NORMAL))
                            addView(label(value, 14f, COLOR_TEXT, Typeface.BOLD).apply { maxLines = 1 })
                        }, LinearLayout.LayoutParams(0, -2, 1f).apply {
                            if (index > 0) marginStart = dimen(R.dimen.ui_row_gap)
                        })
                    }
                }, cardMargins())
            }
            addView(label("health：$schedulerError", 11f, COLOR_SUBTLE, Typeface.NORMAL))
            addView(sectionTitle("工具"), sectionMargins())
            addView(settingsAction(R.drawable.ic_nav_threads, "性能监视器", "通知快捷控制 · FPS / quiet / W") {
                showPerformanceMonitor()
            }, settingsActionMargins())
            addView(settingsAction(R.drawable.ic_action_logs, "应用记录", "每个应用的最近一次记录") {
                startActivity(Intent(this@MainActivity, PerformanceRecordActivity::class.java))
            }, settingsActionMargins())
            addView(settingsAction(R.drawable.ic_nav_system, "GPU频率范围", "节能 · 均衡 · 性能 · 快速") {
                showGlobalGpuRanges()
            }, settingsActionMargins())
            addView(settingsAction(
                R.drawable.ic_action_logs, "导出运行日志", "排查刷新率、Uperf 与 ZUIopt",
            ) { exportLogs() }, settingsActionMargins())
            addView(settingsAction(
                R.drawable.ic_action_refresh, "重启调度核心", "重新加载 Uperf 配置并检查 ZUIopt；不清除故障保护",
            ) {
                runCommand("正在重启调度核心", success = "调度核心已重启") {
                    ZuiControlRequest.send(this@MainActivity, ZuiControlContract.CMD_RESTART_SCHEDULER)
                }
            }, settingsActionMargins())
        })
    }

    private fun exportLogs() {
        runCommand("正在整理日志", success = null, onSuccess = {
            pendingExportText = "[zui_control Binder state]\n${ZuiControlClient.stateText()}\n\n" +
                setting(ZuiControlContract.KEY_LOG_EXPORT)
            if (pendingExportText.isBlank()) toast("没有可导出的日志") else openExportDocument()
        }) { ZuiControlRequest.send(this, ZuiControlContract.CMD_EXPORT_LOGS) }
    }

    private fun requestMonitorPermission() {
        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:$packageName")))
        toast("请允许显示在其他应用上层，然后返回开启监视器")
    }

    private fun connectMonitor() {
        startForegroundService(Intent(this, ZuiControlQuickService::class.java).apply {
            action = "com.zui.zuicontrol.MONITOR_CONNECT"
        })
    }

    private fun showPerformanceMonitor() {
        val content = vertical().apply {
            addView(compactNote("从通知点击监视器图标。日常显示不写记录、不扫描线程。\n轻触长条切换圆形；长按圆形 2 秒开始录制，轻触录制圆形结束。切换应用/锁屏仅暂停，返回目标应用继续。成功开始新录制才替换该应用的上一条。\n屏幕 FPS 非游戏逐帧统计；W 为设备功率，充电时显示 --。quiet 为独立温度传感器。"))
            addView(commandButton("开启 / 关闭监视器") {
                startForegroundService(Intent(this@MainActivity, ZuiControlQuickService::class.java)
                    .setAction(ZuiControlQuickService.FULL))
            },fieldMargins())
            addView(commandButton("悬浮窗权限") { requestMonitorPermission() },fieldMargins())
            addView(commandButton("显示快捷通知") { connectMonitor() },fieldMargins())
        }
        AlertDialog.Builder(this).setTitle("性能监视器").setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("完成", null).showStyled()
    }

    private fun openExportDocument() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "ZuiControl_logs_$stamp.txt")
        }, REQUEST_EXPORT_LOG)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        if (requestCode == REQUEST_IMPORT_ZUIOPT || requestCode == REQUEST_IMPORT_APPOPT) {
            importZuioptDocument(uri, requestCode == REQUEST_IMPORT_APPOPT)
            return
        }
        if (requestCode != REQUEST_EXPORT_LOG) return
        runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingExportText) }
        }.onSuccess { toast("日志已导出") }.onFailure { toast("日志导出失败") }
    }

    private fun addFloatingButton(root: FrameLayout, action: () -> Unit) {
        root.addView(label("+", 28f, Color.WHITE, Typeface.NORMAL).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            contentDescription = "添加"
            background = rounded(COLOR_ACCENT, dp(27), COLOR_ACCENT)
            setOnClickListener { action() }
        }, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.END or Gravity.BOTTOM).apply {
            setMargins(0, 0, dp(6), dp(8))
        })
    }

    private fun addAppGrid(content: LinearLayout, packages: List<String>, badge: (String) -> String,
        mode: ((String) -> UperfMode)? = null, open: (String) -> Unit) {
        val available = if (contentHost.width > 0) (contentHost.width / resources.displayMetrics.density).toInt()
            else resources.configuration.screenWidthDp - 32 -
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 116 else 0
        val columns = UiControls.gridColumns(available)
        packages.chunked(columns).forEach { entries ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(columns) { index ->
                val pkg = entries.getOrNull(index)
                row.addView(if (pkg == null) View(this) else appCard(pkg, badge(pkg), mode?.invoke(pkg)) { open(pkg) },
                    LinearLayout.LayoutParams(0, -2, 1f).apply {
                        if (index > 0) marginStart = dimen(R.dimen.ui_row_gap)
                    })
            }
            content.addView(row, cardMargins())
        }
    }

    private fun appCard(pkg: String, badge: String, mode: UperfMode?, action: () -> Unit): View = horizontalRow().apply {
        val pad = dimen(R.dimen.ui_chip_padding)
        setPadding(pad, pad, pad, pad)
        background = rounded(Color.WHITE, dimen(R.dimen.ui_card_radius), Color.TRANSPARENT)
        elevation = 0f
        addView(appIcon(pkg), LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(label(labelForPackage(pkg), 12f, COLOR_TEXT, Typeface.BOLD).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f).apply {
            setMargins(dimen(R.dimen.ui_row_gap), 0, dimen(R.dimen.ui_row_gap), 0)
        })
        addView(mode?.let { UiControls.modeChip(this@MainActivity, it, true) } ?: UiControls.chip(this@MainActivity, badge, true),
            LinearLayout.LayoutParams(-2, dimen(R.dimen.ui_chip_height)))
        setOnClickListener { action() }
    }

    private fun dialogContent(pkg: String, title: String, field: View): View = vertical().apply {
        setPadding(dimen(R.dimen.ui_dialog_spacing), dimen(R.dimen.ui_row_gap), dimen(R.dimen.ui_dialog_spacing), 0)
        addView(appIdentity(pkg))
        addView(fieldTitle(title), fieldMargins())
        addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dimen(R.dimen.ui_control_height)).apply {
            setMargins(0, dp(6), 0, 0)
        })
    }

    private fun showPackagePicker(
        title: String,
        userAppsOnly: Boolean = false,
        launchableOnly: Boolean = false,
        onSelected: (PackageEntry) -> Unit,
    ) {
        val root = vertical().apply { setPadding(dp(16), dp(8), dp(16), 0) }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val userTab = chip("用户应用")
        val systemTab = chip("系统应用")
        tabs.addView(userTab, LinearLayout.LayoutParams(0, dimen(R.dimen.ui_chip_height), 1f))
        if (!userAppsOnly) tabs.addView(systemTab, LinearLayout.LayoutParams(0, dimen(R.dimen.ui_chip_height), 1f).apply {
            setMargins(dp(8), 0, 0, 0)
        })
        root.addView(tabs)
        val search = EditText(this).apply {
            hint = "搜索应用或包名"
            setSingleLine(true)
            textSize = 14f
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(COLOR_FIELD, dp(22), Color.TRANSPARENT)
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
            setMargins(0, dp(10), 0, dp(8))
        })
        val list = ListView(this).apply {
            divider = null
            cacheColorHint = Color.TRANSPARENT
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(520)))
        val dialog = AlertDialog.Builder(this)
            .setTitle(title).setView(root).setNegativeButton("取消", null).createStyled()
        val adapter = PackagePickerAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            adapter.getEntry(position)?.let { entry ->
                labelCache[entry.info.packageName] = entry.label()
                dialog.dismiss()
                onSelected(entry)
            }
        }
        fun selectSystem(system: Boolean) {
            if (userAppsOnly && system) return
            adapter.systemApps = system
            adapter.applyFilter(search.text.toString())
            styleChip(userTab, !system)
            styleChip(systemTab, system)
        }
        userTab.setOnClickListener { selectSystem(false) }
        if (!userAppsOnly) systemTab.setOnClickListener { selectSystem(true) }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.applyFilter(s?.toString().orEmpty())
            }
        })
        selectSystem(false)
        dialog.show()
        Thread {
            @Suppress("DEPRECATION")
            val entries = packageManager.getInstalledApplications(0)
                .asSequence()
                .filter { it.packageName != packageName }
                .map(::PackageEntry)
                .filter { !userAppsOnly || !it.system }
                .filter { !launchableOnly || packageManager.getLaunchIntentForPackage(it.info.packageName) != null }
                .sortedBy { it.info.packageName.lowercase(Locale.ROOT) }
                .toList()
            handler.post {
                adapter.setEntries(entries)
                selectSystem(false)
            }
        }.start()
    }

    private fun reloadState() {
        refreshRules.clear()
        val serviceState = ZuiControlClient.stateText()
        gpuOverrides.clear()
        gpuGlobals.clear()
        serviceState.lineSequence().forEach { line ->
            GpuRanges.profile(line, ZuiControlClient.currentUserId())?.let { gpuOverrides[it.first] = it.second }
            GpuRanges.global(line, ZuiControlClient.currentUserId())?.let { gpuGlobals[it.first] = it.second }
        }
        serviceState.lineSequence()
            .filter { it.startsWith("profile=") }
            .forEach { line ->
                val parts = line.substringAfter('=').split('|')
                val userId = parts.getOrNull(0)?.toIntOrNull()
                val pkg = parts.getOrNull(1).orEmpty()
                val rate = parts.getOrNull(2)?.toIntOrNull()
                if (parts.size >= 5 && userId == ZuiControlClient.currentUserId() &&
                    PackageNames.isValid(pkg) &&
                    rate != null && rate in ZuiControlContract.rates &&
                    rate != RefreshSceneController.BASE_REFRESH_RATE) {
                    refreshRules[pkg] = rate
                }
            }
        uperfRules.clear()
        setting(ZuiControlContract.KEY_UPERF_RULES_TEXT).lineSequence().forEach { line ->
            val fields = line.split('|', limit = 2)
            val pkg = fields.getOrNull(0).orEmpty()
            val mode = fields.getOrNull(1)?.let(UperfMode::fromId)
            if (PackageNames.isValid(pkg) && mode != null) uperfRules[pkg] = mode
        }
    }

    private fun runCommand(
        message: String,
        success: String? = "操作完成",
        refreshNotification: Boolean = false,
        onSuccess: (() -> Unit)? = null,
        block: () -> String?,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (commandInFlight || now - lastCommandAt < 180) return toast("操作处理中")
        commandInFlight = true
        lastCommandAt = now
        val progressUi = commandProgressDialog(message)
        progressUi.first.show()
        Thread {
            val result = runCatching {
                block()?.let { requestId ->
                    ZuiControlRequest.awaitTerminalAck(this, requestId) { ack ->
                        handler.post {
                            if (progressUi.first.isShowing) {
                                progressUi.second.text = ZuiControlRequest.progressLabel(ack.detail)
                            }
                        }
                    }
                }
            }
            handler.post {
                commandInFlight = false
                runCatching { progressUi.first.dismiss() }
                val ack = result.getOrNull()
                when {
                    result.isFailure -> toast(result.exceptionOrNull()?.message ?: "操作失败")
                    ack != null && !ack.succeeded -> toast("操作失败：${ack.detail}")
                    else -> {
                        if (success != null) toast(success)
                        onSuccess?.invoke()
                    }
                }
                reloadState()
                renderCurrentPage()
                if (result.isSuccess && (ack == null || ack.succeeded) &&
                    refreshNotification && !BuildConfig.DEBUG) {
                    ZuiControlQuickService.start(this)
                }
            }
        }.start()
    }

    private fun commandProgressDialog(title: String): Pair<AlertDialog, TextView> {
        val state = label("正在等待系统处理", 13f, COLOR_SUBTLE, Typeface.NORMAL)
        val content = horizontalRow().apply {
            background = null
            elevation = 0f
            addView(ProgressBar(this@MainActivity), LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(state, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(14), 0, 0, 0)
            })
        }
        return AlertDialog.Builder(this).setTitle(title).setView(content).createStyled() to state
    }

    private fun AlertDialog.Builder.showStyled(): AlertDialog = createStyled().also { it.show() }

    private fun AlertDialog.Builder.createStyled(): AlertDialog = create().apply {
        setOnShowListener { UiControls.styleDialog(this) }
    }

    private fun labelForPackage(pkg: String): String = labelCache.getOrPut(pkg) {
        runCatching {
            packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
        }.getOrDefault(pkg)
    }

    private fun setting(key: String): String =
        Settings.System.getString(contentResolver, key).orEmpty().takeUnless { it == "null" }.orEmpty()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun horizontalRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = rounded(Color.WHITE, dp(18), Color.TRANSPARENT)
        elevation = dp(1).toFloat()
    }

    private fun sectionTitle(text: String) = label(text, 18f, COLOR_TEXT, Typeface.BOLD)
    private fun fieldTitle(text: String) = label(text, 13f, COLOR_SUBTLE, Typeface.BOLD)

    private fun compactNote(text: String) = label(text, 12f, COLOR_SUBTLE, Typeface.NORMAL).apply {
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = rounded(COLOR_NOTE, dp(18), Color.TRANSPARENT)
    }

    private fun emptyText(text: String) = label(text, 13f, COLOR_SUBTLE, Typeface.NORMAL).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(24), dp(12), dp(24))
    }

    private fun rateBadge(text: String) = label(text, 13f, Color.WHITE, Typeface.BOLD).apply {
        gravity = Gravity.CENTER
        background = rounded(COLOR_ACCENT, dp(20), COLOR_ACCENT)
    }

    private fun commandButton(text: String, action: () -> Unit) =
        label(text, 13f, COLOR_TEXT, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = rounded(COLOR_FIELD, dp(22), Color.TRANSPARENT)
            setOnClickListener { action() }
        }

    private fun iconButton(iconRes: Int, description: String, action: () -> Unit) = ImageView(this).apply {
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(COLOR_SUBTLE)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = rounded(COLOR_FIELD, dp(22), Color.TRANSPARENT)
        setOnClickListener { action() }
    }

    private fun traySpinner(items: List<String>) = AnchoredDropdown(this, items)

    private fun appIcon(pkg: String) = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setImageDrawable(runCatching {
            packageManager.getApplicationInfo(pkg, 0).loadIcon(packageManager)
        }.getOrElse { getDrawable(android.R.drawable.sym_def_app_icon) })
        contentDescription = labelForPackage(pkg)
    }

    private fun appIdentity(pkg: String) = horizontalRow().apply {
        background = null
        elevation = 0f
        setPadding(0, dp(4), 0, dp(4))
        addView(appIcon(pkg), LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(label(labelForPackage(pkg), 15f, COLOR_TEXT, Typeface.BOLD),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(12), 0, 0, 0)
                gravity = Gravity.CENTER_VERTICAL
            })
    }

    private fun settingsAction(iconRes: Int, title: String, subtitle: String, action: () -> Unit) =
        horizontalRow().apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(COLOR_FIELD, dimen(R.dimen.ui_card_radius), Color.TRANSPARENT)
            elevation = 0f
            addView(ImageView(this@MainActivity).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(COLOR_SUBTLE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = rounded(COLOR_SURFACE, dp(18), Color.TRANSPARENT)
            }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { setMargins(0, 0, dp(12), 0) })
            addView(vertical().apply {
                addView(label(title, 14f, COLOR_TEXT, Typeface.BOLD))
                addView(label(subtitle, 11f, COLOR_SUBTLE, Typeface.NORMAL))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label("›", 22f, COLOR_SUBTLE, Typeface.NORMAL),
                LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnClickListener { action() }
        }

    private fun chip(text: String) = UiControls.chip(this, text)
    private fun styleChip(view: TextView, selected: Boolean) = UiControls.styleChip(view, selected)

    private fun label(value: String, size: Float, color: Int, style: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = if (style == Typeface.BOLD) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun rounded(color: Int, radius: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        setStroke(dp(1), stroke)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )
    private fun matchMatchFrame() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
    )
    private fun sectionMargins() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { setMargins(0, dp(18), 0, dp(8)) }
    private fun fieldMargins() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { setMargins(0, dp(14), 0, 0) }
    private fun cardMargins() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { setMargins(0, 0, 0, dimen(R.dimen.ui_row_gap)) }
    private fun settingsActionMargins() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dimen(R.dimen.ui_tool_height),
    ).apply { setMargins(0, 0, 0, dimen(R.dimen.ui_row_gap)) }
    private fun dimen(id: Int) = resources.getDimensionPixelSize(id)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private inner class PackageEntry(val info: ApplicationInfo) {
        private var resolvedLabel: String? = null
        val system: Boolean get() = info.flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
            info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        fun label(): String = resolvedLabel ?: runCatching {
            info.loadLabel(packageManager).toString().ifBlank { info.packageName }
        }.getOrDefault(info.packageName).also { resolvedLabel = it }
    }

    private inner class PackagePickerAdapter : BaseAdapter() {
        private val all = mutableListOf<PackageEntry>()
        private val visible = mutableListOf<PackageEntry>()
        var systemApps = false

        fun setEntries(entries: List<PackageEntry>) {
            all.clear()
            all.addAll(entries)
        }

        fun applyFilter(query: String) {
            val lower = query.trim().lowercase(Locale.ROOT)
            visible.clear()
            visible.addAll(all.filter {
                it.system == systemApps && (lower.isBlank() ||
                    it.info.packageName.lowercase(Locale.ROOT).contains(lower) ||
                    it.label().lowercase(Locale.ROOT).contains(lower))
            })
            notifyDataSetChanged()
        }

        fun getEntry(position: Int): PackageEntry? = visible.getOrNull(position)
        override fun getCount(): Int = visible.size
        override fun getItem(position: Int): Any = visible[position]
        override fun getItemId(position: Int): Long = visible[position].info.packageName.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: horizontalRow().apply {
                setPadding(dp(12), dp(9), dp(12), dp(9))
                addView(ImageView(this@MainActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }, LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(vertical().apply {
                    addView(label("", 14f, COLOR_TEXT, Typeface.BOLD))
                    addView(label("", 11f, COLOR_SUBTLE, Typeface.NORMAL))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(12), 0, 0, 0)
                })
            }
            val entry = visible[position]
            (row.getChildAt(0) as ImageView).setImageDrawable(entry.info.loadIcon(packageManager))
            val text = row.getChildAt(1) as LinearLayout
            (text.getChildAt(0) as TextView).text = entry.label()
            (text.getChildAt(1) as TextView).text = entry.info.packageName
            return row
        }
    }

    private enum class Page(val title: String, val iconRes: Int) {
        REFRESH("刷新率", R.drawable.ic_nav_display_rate),
        UPERF("Uperf", R.drawable.ic_nav_performance),
        THREADS("线程", R.drawable.ic_nav_threads),
        SYSTEM("系统", R.drawable.ic_nav_system),
    }

    private val COLOR_SURFACE get() = getColor(R.color.ui_surface)
    private val COLOR_FIELD get() = getColor(R.color.ui_field)
    private val COLOR_TEXT get() = getColor(R.color.ui_text)
    private val COLOR_SUBTLE get() = getColor(R.color.ui_secondary)
    private val COLOR_ACCENT get() = getColor(R.color.ui_accent)
    companion object {
        private const val REQUEST_EXPORT_LOG = 901
        private const val REQUEST_IMPORT_ZUIOPT = 902
        private const val REQUEST_IMPORT_APPOPT = 903
        private const val STATE_PAGE = "page"
        private val COLOR_BG = Color.rgb(248, 247, 252)
        private val COLOR_NOTE = Color.rgb(245, 247, 241)
        private val COLOR_SELECTED = Color.rgb(224, 230, 248)
    }
}
