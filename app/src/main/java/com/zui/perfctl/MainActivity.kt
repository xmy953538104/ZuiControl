package com.zui.perfctl

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val performanceProfiles = linkedMapOf<String, PerformanceProfile>()
    private val refreshRules = linkedMapOf<String, Int>()
    private val labelCache = linkedMapOf<String, String>()

    private lateinit var contentHost: FrameLayout
    private lateinit var tabButtons: Map<Page, TextView>
    private lateinit var refreshRulesHost: LinearLayout
    private lateinit var refreshStatus: TextView
    private lateinit var performanceListHost: LinearLayout
    private lateinit var selectedAppTitle: TextView
    private lateinit var selectedPackageView: TextView
    private lateinit var modeSpinner: Spinner
    private lateinit var cpuMaxInput: EditText
    private lateinit var cpuMinInput: EditText
    private lateinit var gpuMaxInput: EditText
    private lateinit var gpuMinInput: EditText
    private lateinit var performanceSummary: TextView
    private lateinit var systemStatus: TextView
    private lateinit var asoulStatus: TextView

    private var currentPage = Page.REFRESH
    private var selectedPackage: String? = null
    private var commandInFlight = false
    private var lastCommandAt = 0L
    private var pendingExportText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        reloadState()
        setContentView(buildRoot())
        showPage(Page.REFRESH)
        handler.postDelayed({ PerfCtlQuickService.start(this) }, 250)
        sendCommand(null) {
            PerfCtlRequest.send(this, PerfCtlContract.CMD_STATUS)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::contentHost.isInitialized) {
            reloadState()
            renderCurrentPage()
        }
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG)
            setPadding(dp(18), dp(14), dp(18), dp(18))
        }
        root.addView(header(), matchWrap())
        root.addView(pageTabs(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(44),
        ).apply {
            setMargins(0, 0, 0, dp(12))
        })
        contentHost = FrameLayout(this)
        root.addView(contentHost, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        return root
    }

    private fun header(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
            val titleBox = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("ZuiperfCtl", 23f, COLOR_TEXT, Typeface.BOLD))
                addView(label("ZUI 性能控制", 12f, COLOR_SUBTLE, Typeface.NORMAL))
            }
            addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(commandButton("刷新") {
                sendCommand("正在刷新") {
                    PerfCtlRequest.send(this@MainActivity, PerfCtlContract.CMD_STATUS)
                }
            }, LinearLayout.LayoutParams(dp(92), dp(40)))
        }
    }

    private fun pageTabs(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(COLOR_FIELD, dp(8), COLOR_STROKE)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val map = linkedMapOf<Page, TextView>()
        Page.entries.forEach { page ->
            val tab = label(page.title, 14f, COLOR_TEXT, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                setOnClickListener { showPage(page) }
            }
            map[page] = tab
            row.addView(tab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        tabButtons = map
        return row
    }

    private fun showPage(page: Page) {
        currentPage = page
        tabButtons.forEach { (item, view) ->
            val selected = item == page
            view.setTextColor(if (selected) Color.WHITE else COLOR_TEXT)
            view.background = rounded(
                if (selected) COLOR_ACCENT else Color.TRANSPARENT,
                dp(6),
                Color.TRANSPARENT,
            )
        }
        renderCurrentPage()
    }

    private fun renderCurrentPage() {
        contentHost.removeAllViews()
        val view = when (currentPage) {
            Page.REFRESH -> buildRefreshPage()
            Page.PERFORMANCE -> buildPerformancePage()
            Page.SYSTEM -> buildSystemPage()
        }
        contentHost.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
    }

    private fun buildRefreshPage(): View {
        val scroll = ScrollView(this)
        val root = vertical()
        scroll.addView(root)

        refreshStatus = infoPanel()
        root.addView(refreshStatus, matchWrap())
        root.addView(sectionTitle("已学习规则"), sectionMargins())

        refreshRulesHost = vertical()
        root.addView(refreshRulesHost, matchWrap())

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(commandButton("恢复全局 120Hz") {
                sendCommand("正在恢复 120Hz") {
                    PerfCtlRequest.send(this@MainActivity, PerfCtlContract.CMD_RESTORE_REFRESH)
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(commandButton("刷新通知") {
                PerfCtlQuickService.start(this@MainActivity)
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                setMargins(dp(8), 0, 0, 0)
            })
        }
        root.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            setMargins(0, dp(12), 0, 0)
        })
        renderRefreshState()
        return scroll
    }

    private fun renderRefreshState() {
        if (!::refreshStatus.isInitialized) {
            return
        }
        val peak = setting("peak_refresh_rate").cleanSetting().ifBlank { "120" }
        val min = setting("min_refresh_rate").cleanSetting().ifBlank { "120" }
        val top = setting(PerfCtlContract.KEY_TOP_PACKAGE).ifBlank { "未识别" }
        refreshStatus.text = "当前 ${peak}Hz · 最低 ${min}Hz\n前台 $top · 基线 120Hz"

        refreshRulesHost.removeAllViews()
        if (refreshRules.isEmpty()) {
            refreshRulesHost.addView(emptyText("暂无例外规则"), matchWrap())
            return
        }
        refreshRules.forEach { (pkg, rate) ->
            val row = horizontalRow()
            val text = vertical().apply {
                addView(label(labelForPackage(pkg), 15f, COLOR_TEXT, Typeface.BOLD))
                addView(label(pkg, 11f, COLOR_SUBTLE, Typeface.NORMAL))
            }
            row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(rateBadge("${rate}Hz"), LinearLayout.LayoutParams(dp(72), dp(36)))
            row.addView(commandButton("移除") {
                sendCommand("正在移除规则") {
                    PerfCtlRequest.send(
                        this@MainActivity,
                        PerfCtlContract.CMD_REMOVE_REFRESH_RULE,
                        pkg = pkg,
                    )
                }
            }, LinearLayout.LayoutParams(dp(72), dp(36)).apply {
                setMargins(dp(8), 0, 0, 0)
            })
            refreshRulesHost.addView(row, cardMargins())
        }
    }

    private fun buildPerformancePage(): View {
        val tablet = resources.configuration.screenWidthDp >= 700
        val root = LinearLayout(this).apply {
            orientation = if (tablet) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }

        val listPanel = panel().apply {
            orientation = LinearLayout.VERTICAL
            val head = horizontalRow().apply {
                background = null
                setPadding(0, 0, 0, dp(8))
                addView(sectionTitle("性能配置"), LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ))
                addView(commandButton("添加应用") { showPackagePicker() },
                    LinearLayout.LayoutParams(dp(104), dp(40)))
            }
            addView(head)
            performanceListHost = vertical()
            addView(ScrollView(this@MainActivity).apply {
                addView(performanceListHost)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (tablet) 0 else dp(260),
                if (tablet) 1f else 0f,
            ))
        }

        val formPanel = panel().apply {
            orientation = LinearLayout.VERTICAL
            selectedAppTitle = label("选择或添加应用", 20f, COLOR_TEXT, Typeface.BOLD)
            selectedPackageView = label("", 12f, COLOR_SUBTLE, Typeface.NORMAL)
            addView(selectedAppTitle)
            addView(selectedPackageView)

            addView(fieldTitle("运行模式"), fieldMargins())
            modeSpinner = Spinner(this@MainActivity).apply {
                adapter = SimpleTextAdapter(PerformanceMode.entries.map { it.title })
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long,
                    ) {
                        loadSelectedProfile()
                    }
                }
            }
            addView(modeSpinner, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48),
            ))

            val cpuRow = horizontalRow().apply {
                background = null
                setPadding(0, 0, 0, 0)
            }
            cpuMaxInput = numericField("上限 GHz", "2.5")
            cpuMinInput = numericField("下限 GHz", "1.5")
            cpuRow.addView(fieldBox("CPU 上限", cpuMaxInput), LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            cpuRow.addView(fieldBox("CPU 下限", cpuMinInput), LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(10), 0, 0, 0)
            })
            addView(cpuRow, fieldMargins())

            val gpuRow = horizontalRow().apply {
                background = null
                setPadding(0, 0, 0, 0)
            }
            gpuMaxInput = numericField("上限 MHz", "720")
            gpuMinInput = numericField("下限 MHz", "422")
            gpuRow.addView(fieldBox("GPU 上限", gpuMaxInput), LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            gpuRow.addView(fieldBox("GPU 下限", gpuMinInput), LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(10), 0, 0, 0)
            })
            addView(gpuRow, fieldMargins())

            performanceSummary = infoPanel()
            addView(performanceSummary, fieldMargins())

            val saveRow = horizontalRow().apply {
                background = null
                setPadding(0, 0, 0, 0)
                addView(primaryButton("保存模式") { savePerformanceProfile() },
                    LinearLayout.LayoutParams(0, dp(44), 1f))
                addView(commandButton("删除模式") { removePerformanceProfile() },
                    LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                        setMargins(dp(8), 0, 0, 0)
                    })
            }
            addView(saveRow, fieldMargins())

            val applyRow = horizontalRow().apply {
                background = null
                setPadding(0, 0, 0, 0)
                addView(primaryButton("生成并应用调度") {
                    sendCommand("正在生成并应用") {
                        PerfCtlRequest.send(
                            this@MainActivity,
                            PerfCtlContract.CMD_APPLY_PERFORMANCE,
                        )
                    }
                }, LinearLayout.LayoutParams(0, dp(44), 1f))
                addView(commandButton("恢复官方调度") {
                    sendCommand("正在恢复官方调度") {
                        PerfCtlRequest.send(
                            this@MainActivity,
                            PerfCtlContract.CMD_RESTORE_ZUIPP,
                        )
                    }
                }, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    setMargins(dp(8), 0, 0, 0)
                })
            }
            addView(applyRow)
        }

        if (tablet) {
            root.addView(listPanel, LinearLayout.LayoutParams(dp(390), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                setMargins(0, 0, dp(12), 0)
            })
            root.addView(ScrollView(this).apply {
                addView(formPanel)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        } else {
            root.addView(listPanel, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(0, 0, 0, dp(12))
            })
            root.addView(ScrollView(this).apply {
                addView(formPanel)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
        }
        renderPerformanceProfiles()
        updatePerformanceForm()
        return root
    }

    private fun renderPerformanceProfiles() {
        if (!::performanceListHost.isInitialized) {
            return
        }
        performanceListHost.removeAllViews()
        if (performanceProfiles.isEmpty()) {
            performanceListHost.addView(emptyText("暂无性能配置"), matchWrap())
            return
        }
        performanceProfiles.values.forEach { profile ->
            val selected = profile.packageName == selectedPackage &&
                profile.mode == selectedMode()
            val row = vertical().apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(
                    if (selected) COLOR_SELECTED else Color.WHITE,
                    dp(7),
                    if (selected) COLOR_ACCENT else COLOR_STROKE,
                )
                addView(label(
                    "${labelForPackage(profile.packageName)} · ${profile.mode.title}",
                    14f,
                    COLOR_TEXT,
                    Typeface.BOLD,
                ))
                addView(label(
                    "CPU ${formatGHz(profile.cpuMinKHz)}-${formatGHz(profile.cpuMaxKHz)}GHz  " +
                        "GPU ${profile.gpuMinKHz / 1000}-${profile.gpuMaxKHz / 1000}MHz",
                    11f,
                    COLOR_SUBTLE,
                    Typeface.NORMAL,
                ))
                setOnClickListener {
                    selectedPackage = profile.packageName
                    modeSpinner.setSelection(profile.mode.ordinal)
                    updatePerformanceForm()
                    renderPerformanceProfiles()
                }
            }
            performanceListHost.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(0, 0, 0, dp(8))
            })
        }
    }

    private fun updatePerformanceForm() {
        if (!::selectedAppTitle.isInitialized) {
            return
        }
        val pkg = selectedPackage
        selectedAppTitle.text = pkg?.let { labelForPackage(it) } ?: "选择或添加应用"
        selectedPackageView.text = pkg.orEmpty()
        loadSelectedProfile()
        performanceSummary.text = setting(PerfCtlContract.KEY_PERFORMANCE_SUMMARY)
            .ifBlank { "工作 XML 尚未生成" }
    }

    private fun loadSelectedProfile() {
        if (!::cpuMaxInput.isInitialized) {
            return
        }
        val pkg = selectedPackage ?: return
        val profile = performanceProfiles["$pkg|${selectedMode().id}"]
        if (profile == null) {
            cpuMaxInput.setText("2.5")
            cpuMinInput.setText("1.5")
            gpuMaxInput.setText("720")
            gpuMinInput.setText("422")
        } else {
            cpuMaxInput.setText(formatGHz(profile.cpuMaxKHz))
            cpuMinInput.setText(formatGHz(profile.cpuMinKHz))
            gpuMaxInput.setText((profile.gpuMaxKHz / 1000).toString())
            gpuMinInput.setText((profile.gpuMinKHz / 1000).toString())
        }
        renderPerformanceProfiles()
    }

    private fun savePerformanceProfile() {
        val pkg = selectedPackage ?: return toast("请先添加应用")
        val cpuMax = parseGHz(cpuMaxInput.text.toString()) ?: return toast("CPU 上限格式错误")
        val cpuMin = parseGHz(cpuMinInput.text.toString()) ?: return toast("CPU 下限格式错误")
        val gpuMax = parseMHz(gpuMaxInput.text.toString()) ?: return toast("GPU 上限格式错误")
        val gpuMin = parseMHz(gpuMinInput.text.toString()) ?: return toast("GPU 下限格式错误")
        if (cpuMax < cpuMin || gpuMax < gpuMin) {
            return toast("上限不能低于下限")
        }
        if (cpuMin !in 300_000..3_400_000 || cpuMax !in 300_000..3_400_000) {
            return toast("CPU 范围应为 0.3-3.4GHz")
        }
        if (gpuMin !in 231_000..903_000 || gpuMax !in 231_000..903_000) {
            return toast("GPU 范围应为 231-903MHz")
        }
        val profile = PerformanceProfile(pkg, selectedMode(), cpuMax, cpuMin, gpuMax, gpuMin)
        performanceProfiles[profile.key] = profile
        renderPerformanceProfiles()
        sendCommand("正在保存性能配置") {
            PerfCtlRequest.send(
                this,
                PerfCtlContract.CMD_SET_PERFORMANCE_PROFILE,
                pkg = pkg,
                mode = profile.mode.id,
                cpuMax = cpuMax,
                cpuMin = cpuMin,
                gpuMax = gpuMax,
                gpuMin = gpuMin,
            )
        }
    }

    private fun removePerformanceProfile() {
        val pkg = selectedPackage ?: return toast("请先选择应用")
        val key = "$pkg|${selectedMode().id}"
        performanceProfiles.remove(key)
        renderPerformanceProfiles()
        sendCommand("正在删除性能配置") {
            PerfCtlRequest.send(
                this,
                PerfCtlContract.CMD_REMOVE_PERFORMANCE_PROFILE,
                pkg = pkg,
                mode = selectedMode().id,
            )
        }
    }

    private fun buildSystemPage(): View {
        val scroll = ScrollView(this)
        val root = vertical()
        scroll.addView(root)

        root.addView(sectionTitle("运行状态"))
        systemStatus = infoPanel()
        root.addView(systemStatus, fieldMargins())

        root.addView(sectionTitle("AsoulOpt"), sectionMargins())
        asoulStatus = infoPanel()
        root.addView(asoulStatus, fieldMargins())

        val row = horizontalRow().apply {
            background = null
            setPadding(0, 0, 0, 0)
            addView(commandButton("刷新状态") {
                sendCommand("正在刷新") {
                    PerfCtlRequest.send(this@MainActivity, PerfCtlContract.CMD_STATUS)
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(primaryButton("导出运行日志") { exportLogs() },
                LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    setMargins(dp(8), 0, 0, 0)
                })
        }
        root.addView(row)
        renderSystemState()
        return scroll
    }

    private fun renderSystemState() {
        if (!::systemStatus.isInitialized) {
            return
        }
        val status = setting(PerfCtlContract.KEY_STATUS_TEXT)
        val xml = setting(PerfCtlContract.KEY_XML_STATE).ifBlank { "未挂载" }
        val last = setting(PerfCtlContract.KEY_STATUS_LAST).ifBlank { "无" }
        systemStatus.text = "守护服务 运行中\nXML $xml\n最近操作 ${commandName(last)}\n$status"
        asoulStatus.text = setting(PerfCtlContract.KEY_ASOUL_HEALTH)
            .ifBlank { "正在读取 AsoulOpt 状态" }
    }

    private fun exportLogs() {
        sendCommand("正在整理日志") {
            PerfCtlRequest.send(this, PerfCtlContract.CMD_EXPORT_LOGS)
        }
        handler.postDelayed({
            pendingExportText = setting(PerfCtlContract.KEY_LOG_EXPORT)
            if (pendingExportText.isBlank()) {
                toast("日志尚未准备好")
                return@postDelayed
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "ZuiperfCtl_logs_$stamp.txt")
                },
                REQUEST_EXPORT_LOG,
            )
        }, 1600)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT_LOG || resultCode != RESULT_OK) {
            return
        }
        val uri: Uri = data?.data ?: return
        runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(pendingExportText)
            }
        }.onSuccess {
            toast("日志已导出")
        }.onFailure {
            toast("日志导出失败")
        }
    }

    private fun showPackagePicker() {
        val root = vertical().apply {
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val userTab = chip("用户应用")
        val systemTab = chip("系统应用")
        tabs.addView(userTab, LinearLayout.LayoutParams(0, dp(40), 1f))
        tabs.addView(systemTab, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            setMargins(dp(8), 0, 0, 0)
        })
        root.addView(tabs)

        val search = EditText(this).apply {
            hint = "搜索包名"
            setSingleLine(true)
            textSize = 14f
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(COLOR_FIELD, dp(7), COLOR_STROKE)
        }
        root.addView(search, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(46),
        ).apply {
            setMargins(0, dp(10), 0, dp(8))
        })

        val list = ListView(this).apply {
            divider = null
            cacheColorHint = Color.TRANSPARENT
        }
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(520),
        ))

        val dialog = AlertDialog.Builder(this)
            .setTitle("选择应用")
            .setView(root)
            .setNegativeButton("取消", null)
            .create()
        val adapter = PackagePickerAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            adapter.getEntry(position)?.let {
                selectedPackage = it.info.packageName
                labelCache[it.info.packageName] = it.label()
                dialog.dismiss()
                updatePerformanceForm()
            }
        }
        fun selectSystem(system: Boolean) {
            adapter.systemApps = system
            adapter.applyFilter(search.text.toString())
            styleChip(userTab, !system)
            styleChip(systemTab, system)
        }
        userTab.setOnClickListener { selectSystem(false) }
        systemTab.setOnClickListener { selectSystem(true) }
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
                .map { PackageEntry(it) }
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
        setting(PerfCtlContract.KEY_RULES_TEXT).lineSequence().forEach { line ->
            val parts = line.trim().split("=", limit = 2)
            val rate = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size == 2 && PackageNames.isValid(parts[0]) &&
                rate != null && rate in PerfCtlContract.rates && rate != 120) {
                refreshRules[parts[0]] = rate
            }
        }
        performanceProfiles.clear()
        setting(PerfCtlContract.KEY_PERFORMANCE_PROFILES_TEXT).lineSequence()
            .mapNotNull { PerformanceProfile.parse(it) }
            .forEach { performanceProfiles[it.key] = it }
    }

    private fun sendCommand(message: String?, block: () -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (commandInFlight || now - lastCommandAt < 180) {
            if (message != null) toast("操作处理中")
            return
        }
        commandInFlight = true
        lastCommandAt = now
        if (message != null) toast(message)
        Thread {
            runCatching { block() }
            handler.post {
                PerfCtlQuickService.start(this)
                handler.postDelayed({
                    commandInFlight = false
                    reloadState()
                    renderCurrentPage()
                }, 380)
            }
        }.start()
    }

    private fun selectedMode(): PerformanceMode {
        val position = if (::modeSpinner.isInitialized) modeSpinner.selectedItemPosition else 0
        return PerformanceMode.entries.getOrElse(position) { PerformanceMode.BALANCED }
    }

    private fun parseGHz(value: String): Int? =
        value.trim().toDoubleOrNull()?.let { (it * 1_000_000).toInt() }

    private fun parseMHz(value: String): Int? =
        value.trim().toDoubleOrNull()?.let { (it * 1000).toInt() }

    private fun formatGHz(khz: Int): String =
        String.format(Locale.US, "%.3f", khz / 1_000_000.0).trimEnd('0').trimEnd('.')

    private fun labelForPackage(pkg: String): String {
        return labelCache.getOrPut(pkg) {
            runCatching {
                packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
            }.getOrDefault(pkg)
        }
    }

    private fun setting(key: String): String =
        Settings.System.getString(contentResolver, key).orEmpty().takeUnless { it == "null" }.orEmpty()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun String.cleanSetting(): String = removeSuffix(".0")

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 187)
        }
    }

    private fun commandName(value: String): String = when (value) {
        PerfCtlContract.CMD_LEARN_REFRESH -> "记忆刷新率"
        PerfCtlContract.CMD_REMOVE_REFRESH_RULE -> "移除刷新率规则"
        PerfCtlContract.CMD_RESTORE_REFRESH -> "恢复 120Hz"
        PerfCtlContract.CMD_SET_PERFORMANCE_PROFILE -> "保存性能配置"
        PerfCtlContract.CMD_REMOVE_PERFORMANCE_PROFILE -> "删除性能配置"
        PerfCtlContract.CMD_APPLY_PERFORMANCE -> "应用性能调度"
        PerfCtlContract.CMD_RESTORE_ZUIPP -> "恢复官方调度"
        PerfCtlContract.CMD_EXPORT_LOGS -> "导出日志"
        PerfCtlContract.CMD_STATUS -> "刷新状态"
        "init" -> "初始化"
        else -> value
    }

    private fun vertical() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun horizontalRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(Color.WHITE, dp(8), COLOR_STROKE)
    }

    private fun panel() = LinearLayout(this).apply {
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = rounded(Color.WHITE, dp(8), COLOR_STROKE)
    }

    private fun sectionTitle(text: String) = label(text, 17f, COLOR_TEXT, Typeface.BOLD)

    private fun fieldTitle(text: String) = label(text, 13f, COLOR_SUBTLE, Typeface.BOLD)

    private fun fieldBox(title: String, field: EditText) = vertical().apply {
        addView(fieldTitle(title))
        addView(field, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(46),
        ).apply {
            setMargins(0, dp(6), 0, 0)
        })
    }

    private fun numericField(hintText: String, defaultValue: String) = EditText(this).apply {
        hint = hintText
        setText(defaultValue)
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        setSingleLine(true)
        textSize = 15f
        setPadding(dp(12), 0, dp(12), 0)
        background = rounded(COLOR_FIELD, dp(7), COLOR_STROKE)
    }

    private fun infoPanel() = label("", 13f, COLOR_TEXT, Typeface.NORMAL).apply {
        setPadding(dp(13), dp(11), dp(13), dp(11))
        background = rounded(COLOR_FIELD, dp(8), COLOR_STROKE)
    }

    private fun emptyText(text: String) = label(text, 13f, COLOR_SUBTLE, Typeface.NORMAL).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(24), dp(12), dp(24))
    }

    private fun rateBadge(text: String) = label(text, 13f, Color.WHITE, Typeface.BOLD).apply {
        gravity = Gravity.CENTER
        background = rounded(COLOR_GREEN, dp(7), COLOR_GREEN)
    }

    private fun commandButton(text: String, action: () -> Unit) =
        label(text, 13f, COLOR_TEXT, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = rounded(COLOR_FIELD, dp(7), COLOR_STROKE)
            setOnClickListener { action() }
        }

    private fun primaryButton(text: String, action: () -> Unit) =
        label(text, 13f, Color.WHITE, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = rounded(COLOR_ACCENT, dp(7), COLOR_ACCENT)
            setOnClickListener { action() }
        }

    private fun chip(text: String) = label(text, 13f, COLOR_TEXT, Typeface.BOLD).apply {
        gravity = Gravity.CENTER
        background = rounded(COLOR_FIELD, dp(7), COLOR_STROKE)
    }

    private fun styleChip(view: TextView, selected: Boolean) {
        view.setTextColor(if (selected) Color.WHITE else COLOR_TEXT)
        view.background = rounded(
            if (selected) COLOR_ACCENT else COLOR_FIELD,
            dp(7),
            if (selected) COLOR_ACCENT else COLOR_STROKE,
        )
    }

    private fun label(value: String, size: Float, color: Int, style: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = if (style == Typeface.BOLD) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        includeFontPadding = true
    }

    private fun rounded(color: Int, radius: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        setStroke(dp(1), stroke)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun sectionMargins() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        setMargins(0, dp(18), 0, dp(8))
    }

    private fun fieldMargins() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        setMargins(0, dp(14), 0, 0)
    }

    private fun cardMargins() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        setMargins(0, 0, 0, dp(8))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private inner class PackageEntry(val info: ApplicationInfo) {
        private var resolvedLabel: String? = null
        val system: Boolean
            get() = info.flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0

        fun label(): String {
            return resolvedLabel ?: runCatching {
                info.loadLabel(packageManager).toString().ifBlank { info.packageName }
            }.getOrDefault(info.packageName).also {
                resolvedLabel = it
            }
        }
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
                it.system == systemApps &&
                    (lower.isBlank() ||
                        it.info.packageName.lowercase(Locale.ROOT).contains(lower) ||
                        labelCache[it.info.packageName]?.lowercase(Locale.ROOT)?.contains(lower) == true)
            })
            notifyDataSetChanged()
        }

        fun getEntry(position: Int): PackageEntry? = visible.getOrNull(position)

        override fun getCount(): Int = visible.size
        override fun getItem(position: Int): Any = visible[position]
        override fun getItemId(position: Int): Long = visible[position].info.packageName.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: vertical().apply {
                setPadding(dp(10), dp(8), dp(10), dp(8))
                addView(label("", 14f, COLOR_TEXT, Typeface.BOLD))
                addView(label("", 11f, COLOR_SUBTLE, Typeface.NORMAL))
            }
            val entry = visible[position]
            val title = row.getChildAt(0) as TextView
            val pkg = row.getChildAt(1) as TextView
            title.text = entry.label()
            pkg.text = entry.info.packageName
            labelCache[entry.info.packageName] = title.text.toString()
            row.background = if (position % 2 == 0) {
                rounded(COLOR_FIELD, dp(6), Color.TRANSPARENT)
            } else {
                Color.TRANSPARENT.toDrawable()
            }
            return row
        }
    }

    private inner class SimpleTextAdapter(private val items: List<String>) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
            label(items[position], 14f, COLOR_TEXT, Typeface.BOLD).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
                background = rounded(COLOR_FIELD, dp(7), COLOR_STROKE)
            }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup?): View =
            label(items[position], 14f, COLOR_TEXT, Typeface.NORMAL).apply {
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundColor(Color.WHITE)
            }
    }

    private fun Int.toDrawable() = android.graphics.drawable.ColorDrawable(this)

    private enum class Page(val title: String) {
        REFRESH("刷新率"),
        PERFORMANCE("性能调度"),
        SYSTEM("系统状态"),
    }

    companion object {
        private const val REQUEST_EXPORT_LOG = 901
        private val COLOR_BG = Color.rgb(244, 247, 250)
        private val COLOR_FIELD = Color.rgb(238, 242, 246)
        private val COLOR_SELECTED = Color.rgb(226, 239, 255)
        private val COLOR_STROKE = Color.rgb(211, 220, 230)
        private val COLOR_TEXT = Color.rgb(28, 34, 42)
        private val COLOR_SUBTLE = Color.rgb(91, 101, 114)
        private val COLOR_ACCENT = Color.rgb(35, 102, 207)
        private val COLOR_GREEN = Color.rgb(32, 132, 99)
    }
}
