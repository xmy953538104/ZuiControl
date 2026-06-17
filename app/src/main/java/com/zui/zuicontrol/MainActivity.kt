package com.zui.zuicontrol

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
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
    private lateinit var headerStatus: TextView
    private lateinit var refreshRulesHost: LinearLayout
    private lateinit var refreshStatus: TextView
    private lateinit var performanceListHost: LinearLayout
    private lateinit var selectedAppTitle: TextView
    private lateinit var selectedPackageView: TextView
    private lateinit var modeSpinner: Spinner
    private lateinit var littleMaxInput: EditText
    private lateinit var littleMinInput: EditText
    private lateinit var bigMaxInput: EditText
    private lateinit var bigMinInput: EditText
    private lateinit var titanMaxInput: EditText
    private lateinit var titanMinInput: EditText
    private lateinit var megaMaxInput: EditText
    private lateinit var megaMinInput: EditText
    private lateinit var gpuMaxInput: EditText
    private lateinit var gpuMinInput: EditText
    private lateinit var thermalZoneSpinner: Spinner
    private lateinit var warmStartInput: EditText
    private lateinit var hotStartInput: EditText
    private lateinit var thermalPreview: TextView
    private lateinit var performanceSummary: TextView
    private lateinit var systemStatus: TextView
    private lateinit var asoulStatus: TextView

    private var currentPage = Page.REFRESH
    private var selectedPackage: String? = null
    private var commandInFlight = false
    private var lastCommandAt = 0L
    private var pendingExportText = ""
    private var loadingPerformanceForm = false
    private var currentThermalZone = ThermalZone.NORMAL
    private val thermalDrafts = mutableMapOf<ThermalZone, FrequencyBundle>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reloadState()
        setContentView(buildRoot())
        showPage(Page.REFRESH)
        handler.postDelayed({ ZuiControlQuickService.start(this) }, 250)
        sendCommand(null, refreshNotification = true) {
            ZuiControlRequest.send(this, ZuiControlContract.CMD_STATUS)
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
        val wide = isWideLayout()
        val root = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG)
            setPadding(dp(18), dp(14), dp(18), dp(18))
        }
        if (wide) {
            val nav = vertical().apply {
                setPadding(0, 0, dp(12), 0)
                addView(pageTabs(verticalTabs = true), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            }
            root.addView(nav, LinearLayout.LayoutParams(dp(168), ViewGroup.LayoutParams.MATCH_PARENT))

            val main = vertical()
            main.addView(header(), matchWrap())
            contentHost = FrameLayout(this)
            main.addView(contentHost, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
            root.addView(main, LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f,
            ))
            return root
        }
        root.addView(header(), matchWrap())
        root.addView(pageTabs(verticalTabs = false), LinearLayout.LayoutParams(
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
                addView(label("ZuiControl", 23f, COLOR_TEXT, Typeface.BOLD))
                headerStatus = label(headerStatusText(), 12f, COLOR_SUBTLE, Typeface.NORMAL)
                addView(headerStatus)
            }
            addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(commandButton("刷新") {
                sendCommand("正在刷新", refreshNotification = true) {
                    ZuiControlRequest.send(this@MainActivity, ZuiControlContract.CMD_STATUS)
                }
            }, LinearLayout.LayoutParams(dp(92), dp(40)))
        }
    }

    private fun pageTabs(verticalTabs: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = if (verticalTabs) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
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
            if (verticalTabs) {
                row.addView(tab, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(46),
                ).apply {
                    setMargins(0, if (row.childCount == 0) 0 else dp(4), 0, 0)
                })
            } else {
                row.addView(tab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
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
        updateHeaderStatus()
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

    private fun isWideLayout(): Boolean {
        val config = resources.configuration
        return config.screenWidthDp >= 700 &&
            config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    }

    private fun headerStatusText(): String {
        val displayHz = ZuiControlClient.currentDisplayHz()?.toString()
            ?: setting(ZuiControlContract.KEY_ACTIVE_REFRESH)
            .ifBlank { setting("peak_refresh_rate").cleanSetting() }
            .ifBlank { "120" }
        val last = setting(ZuiControlContract.KEY_STATUS_LAST).ifBlank { "init" }
        return "v$APP_VERSION_NAME · ${displayHz}Hz · ${commandName(last)}"
    }

    private fun updateHeaderStatus() {
        if (::headerStatus.isInitialized) {
            headerStatus.text = headerStatusText()
        }
    }

    private fun buildRefreshPage(): View {
        val scroll = ScrollView(this)
        val root = vertical()
        scroll.addView(root)

        refreshStatus = infoPanel()
        root.addView(refreshStatus, matchWrap())
        root.addView(sectionTitle("场景刷新率"), sectionMargins())

        refreshRulesHost = vertical()
        root.addView(refreshRulesHost, matchWrap())

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(commandButton("当前场景默认") {
                sendCommand("正在恢复默认", refreshNotification = true) {
                    val reply = ZuiControlClient.setCurrentSceneDisplayHz(120)
                    if (!reply.ok) {
                        throw IllegalStateException(reply.text)
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(commandButton("添加规则") {
                showPackagePicker("选择刷新率应用") { entry ->
                    labelCache[entry.info.packageName] = entry.label()
                    showRefreshRateDialog(entry.info.packageName, refreshRules[entry.info.packageName])
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                setMargins(dp(8), 0, 0, 0)
            })
            addView(commandButton("刷新通知") {
                ZuiControlQuickService.start(this@MainActivity)
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
        val serviceState = ZuiControlClient.stateText()
        val stateOk = serviceState.startsWith("ok") || serviceState.contains("\nok=")
        val active = stateValue(serviceState, "targetDisplayHz")
            .ifBlank { setting(ZuiControlContract.KEY_ACTIVE_REFRESH) }
            .ifBlank { "120" }
        val actual = stateValue(serviceState, "actualDisplayHz").ifBlank { active }
        val currentScene = stateValue(serviceState, "currentScenePackage").ifBlank { "等待前台场景" }
        val rawFocused = stateValue(serviceState, "rawFocusedPackage").ifBlank { "无" }
        val lastScene = stateValue(serviceState, "lastNonTransientScenePackage").ifBlank { "无" }
        val appliedScene = stateValue(serviceState, "appliedScenePackage").ifBlank { currentScene }
        val owner = stateValue(serviceState, "refreshOwner").ifBlank { "system_server" }
        refreshStatus.text = if (stateOk) {
            val sceneLine = if (appliedScene != currentScene) {
                "显示场景 $appliedScene\n业务场景 $currentScene"
            } else {
                "当前场景 $currentScene"
            }
            "$sceneLine\n目标 ${active}Hz · 实际 ${actual}Hz · owner $owner\nraw $rawFocused · last $lastScene"
        } else {
            "zui_control 服务暂不可用\n$serviceState"
        }

        refreshRulesHost.removeAllViews()
        if (refreshRules.isEmpty()) {
            refreshRulesHost.addView(emptyText("暂无手动规则"), matchWrap())
            return
        }
        refreshRules.forEach { (pkg, rate) ->
            val card = vertical().apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(Color.WHITE, dp(8), COLOR_STROKE)
            }
            val header = horizontalRow().apply {
                background = null
                setPadding(0, 0, 0, dp(8))
                val text = vertical().apply {
                    addView(label(labelForPackage(pkg), 15f, COLOR_TEXT, Typeface.BOLD))
                    addView(label(pkg, 11f, COLOR_SUBTLE, Typeface.NORMAL))
                }
                addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(rateBadge("${rate}Hz").apply {
                    setOnClickListener { showRefreshRateDialog(pkg, rate) }
                }, LinearLayout.LayoutParams(dp(72), dp(36)))
                addView(commandButton("移除") {
                    removeRefreshProfile(pkg)
                }, LinearLayout.LayoutParams(dp(72), dp(36)).apply {
                    setMargins(dp(8), 0, 0, 0)
                })
            }
            card.addView(header, matchWrap())
            val rates = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                ZuiControlContract.rates.forEach { target ->
                    val title = if (target == RefreshSceneController.BASE_REFRESH_RATE) {
                        "默认"
                    } else {
                        target.toString()
                    }
                    val button = if (target == rate) {
                        primaryButton(title) { setRefreshProfile(pkg, target) }
                    } else {
                        commandButton(title) { setRefreshProfile(pkg, target) }
                    }
                    addView(button, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                        if (target != ZuiControlContract.rates.first()) {
                            setMargins(dp(6), 0, 0, 0)
                        }
                    })
                }
            }
            card.addView(rates, matchWrap())
            refreshRulesHost.addView(card, cardMargins())
        }
    }

    private fun showRefreshRateDialog(pkg: String, currentRate: Int?) {
        val labels = ZuiControlContract.rates.map { rate ->
            if (rate == RefreshSceneController.BASE_REFRESH_RATE) {
                "默认 ${rate}Hz"
            } else if (rate == currentRate) {
                "${rate}Hz（当前）"
            } else {
                "${rate}Hz"
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(labelForPackage(pkg))
            .setMessage(pkg)
            .setItems(labels) { _, which ->
                setRefreshProfile(pkg, ZuiControlContract.rates[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setRefreshProfile(pkg: String, rate: Int) {
        val message = if (rate == RefreshSceneController.BASE_REFRESH_RATE) {
            "正在恢复默认"
        } else {
            "正在设置 ${rate}Hz"
        }
        sendCommand(message, refreshNotification = true) {
            val reply = ZuiControlClient.setPackageDisplayHz(pkg, rate)
            if (!reply.ok) {
                throw IllegalStateException(reply.text)
            }
        }
    }

    private fun removeRefreshProfile(pkg: String) {
        sendCommand("正在移除规则", refreshNotification = true) {
            val reply = ZuiControlClient.removePackageProfile(pkg)
            if (!reply.ok) {
                throw IllegalStateException(reply.text)
            }
        }
    }

    private fun buildPerformancePage(): View {
        val tablet = isWideLayout()
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
                background = rounded(COLOR_FIELD, dp(7), COLOR_STROKE)
                setPadding(dp(4), 0, dp(4), 0)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long,
                    ) {
                        if (::performanceSummary.isInitialized) {
                            updatePerformanceForm()
                            renderPerformanceProfiles()
                        } else {
                            loadSelectedProfile()
                        }
                    }
                }
            }
            addView(modeSpinner, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48),
            ))
            addView(compactNote("每个模式保存三段温区配置；切换温区后填写该温区的 CPU/GPU 上下限。"),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    setMargins(0, dp(8), 0, 0)
                })

            addView(fieldTitle("温区"), fieldMargins())
            thermalZoneSpinner = Spinner(this@MainActivity).apply {
                adapter = SimpleTextAdapter(ThermalZone.entries.map { it.title })
                background = rounded(COLOR_FIELD, dp(7), COLOR_STROKE)
                setPadding(dp(4), 0, dp(4), 0)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long,
                    ) {
                        if (!loadingPerformanceForm && ::littleMaxInput.isInitialized) {
                            switchThermalZone(position)
                        }
                    }
                }
            }
            addView(thermalZoneSpinner, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48),
            ))
            addView(compactNote("温区由两个起点决定：低温小于中温起点，中温介于两个起点，高温大于等于高温起点。"),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    setMargins(0, dp(8), 0, 0)
                })

            warmStartInput = numericField("42", "42")
            hotStartInput = numericField("48", "48")
            addView(horizontalRow().apply {
                background = null
                setPadding(0, 0, 0, 0)
                addView(fieldBox("中温起点 ℃", warmStartInput), LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(fieldBox("高温起点 ℃", hotStartInput), LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(10), 0, 0, 0)
                })
            }, fieldMargins())

            littleMaxInput = numericField("上限 GHz", formatFreq(LITTLE_FREQS.last()))
            littleMinInput = numericField("下限 GHz", formatFreq(LITTLE_FREQS.first()))

            bigMaxInput = numericField("上限 GHz", formatFreq(BIG_FREQS.last()))
            bigMinInput = numericField("下限 GHz", formatFreq(BIG_FREQS.first()))

            titanMaxInput = numericField("上限 GHz", formatFreq(TITAN_FREQS.last()))
            titanMinInput = numericField("下限 GHz", formatFreq(TITAN_FREQS.first()))

            megaMaxInput = numericField("上限 GHz", formatFreq(MEGA_FREQS.last()))
            megaMinInput = numericField("下限 GHz", formatFreq(MEGA_FREQS.first()))

            gpuMaxInput = numericField("上限 GHz", formatFreq(GPU_FREQS.first()))
            gpuMinInput = numericField("下限 GHz", formatFreq(GPU_FREQS.last()))
            val littleRow = freqRow("Little cpu0-cpu2", littleMaxInput, littleMinInput, LITTLE_FREQS)
            val bigRow = freqRow("Big cpu3-cpu4", bigMaxInput, bigMinInput, BIG_FREQS)
            val titanRow = freqRow("Titan cpu5-cpu6", titanMaxInput, titanMinInput, TITAN_FREQS)
            val megaRow = freqRow("Mega cpu7", megaMaxInput, megaMinInput, MEGA_FREQS)
            val gpuRow = freqRow("GPU", gpuMaxInput, gpuMinInput, GPU_FREQS)
            if (tablet) {
                addView(freqPairRow(littleRow, bigRow), fieldMargins())
                addView(freqPairRow(titanRow, megaRow), fieldMargins())
                addView(gpuRow, fieldMargins())
            } else {
                addView(littleRow, fieldMargins())
                addView(bigRow, fieldMargins())
                addView(titanRow, fieldMargins())
                addView(megaRow, fieldMargins())
                addView(gpuRow, fieldMargins())
            }

            thermalPreview = infoPanel()
            addView(thermalPreview, fieldMargins())
            bindThermalPreviewUpdates()

            performanceSummary = infoPanel()
            addView(performanceSummary, fieldMargins())

            addView(sectionTitle("操作"), fieldMargins())
            addView(actionPair(
                primaryButton("保存并应用") { savePerformanceProfile() },
                commandButton("删除并应用") { removePerformanceProfile() },
            ), buttonMargins())
            addView(actionPair(
                primaryButton("生成并应用调度") {
                    sendCommand("正在生成并应用", settleDelayMs = LONG_COMMAND_DELAY_MS) {
                        ZuiControlRequest.send(
                            this@MainActivity,
                            ZuiControlContract.CMD_APPLY_PERFORMANCE,
                        )
                    }
                },
                commandButton("恢复基线调度") {
                    sendCommand("正在恢复基线调度", settleDelayMs = LONG_COMMAND_DELAY_MS) {
                        ZuiControlRequest.send(
                            this@MainActivity,
                            ZuiControlContract.CMD_RESTORE_ZUIPP,
                        )
                    }
                },
            ), buttonMargins())
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
                    "L ${formatFreq(profile.littleMinKHz)}-${formatFreq(profile.littleMaxKHz)}  " +
                        "B ${formatFreq(profile.bigMinKHz)}-${formatFreq(profile.bigMaxKHz)}  " +
                        "T ${formatFreq(profile.titanMinKHz)}-${formatFreq(profile.titanMaxKHz)}",
                    11f,
                    COLOR_SUBTLE,
                    Typeface.NORMAL,
                ))
                addView(label(
                    "M ${formatFreq(profile.megaMinKHz)}-${formatFreq(profile.megaMaxKHz)}  " +
                        "GPU ${formatFreq(profile.gpuMinKHz)}-${formatFreq(profile.gpuMaxKHz)}GHz",
                    11f,
                    COLOR_SUBTLE,
                    Typeface.NORMAL,
                ))
                addView(label(
                    "温控 ${profile.thermalSummary()}",
                    11f,
                    COLOR_SUBTLE,
                    Typeface.NORMAL,
                ))
                setOnClickListener {
                    selectedPackage = profile.packageName
                    if (modeSpinner.selectedItemPosition == profile.mode.ordinal) {
                        updatePerformanceForm()
                        renderPerformanceProfiles()
                    } else {
                        modeSpinner.setSelection(profile.mode.ordinal)
                    }
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
        selectedPackageView.text = pkg?.let { "$it · ${selectedMode().title}" } ?: "未选择应用"
        loadSelectedProfile()
        performanceSummary.text = performanceXmlStatusText()
    }

    private fun performanceXmlStatusText(): String {
        val xmlState = setting(ZuiControlContract.KEY_XML_STATE)
        val last = setting(ZuiControlContract.KEY_STATUS_LAST)
        val daemon = setting(ZuiControlContract.KEY_DAEMON_STATUS_TEXT)
        val errorLine = listOf(xmlState, last, daemon)
            .flatMap { it.lineSequence() }
            .firstOrNull {
                it.contains("failed", ignoreCase = true) ||
                    it.contains("error", ignoreCase = true)
            }
        if (errorLine != null) {
            return "XML 异常：$errorLine"
        }
        return if (xmlState.startsWith("state=mounted")) {
            "XML 已启用"
        } else {
            "XML 未启用"
        }
    }

    private fun loadSelectedProfile() {
        if (!::littleMaxInput.isInitialized) {
            return
        }
        val pkg = selectedPackage ?: return
        val profile = performanceProfiles["$pkg|${selectedMode().id}"]
        loadingPerformanceForm = true
        try {
            if (profile == null) {
                setDefaultThermalForm()
            } else {
                loadThermalForm(profile)
            }
        } finally {
            loadingPerformanceForm = false
        }
        updateThermalPreview()
    }

    private fun savePerformanceProfile() {
        val pkg = selectedPackage ?: return toast("请先添加应用")
        val stages = buildThermalStages(showToast = true) ?: return
        val base = stages.first()
        val profile = PerformanceProfile(
            pkg,
            selectedMode(),
            base.littleMaxKHz,
            base.littleMinKHz,
            base.bigMaxKHz,
            base.bigMinKHz,
            base.titanMaxKHz,
            base.titanMinKHz,
            base.megaMaxKHz,
            base.megaMinKHz,
            base.gpuMaxKHz,
            base.gpuMinKHz,
            stages,
        )
        performanceProfiles[profile.key] = profile
        renderPerformanceProfiles()
        sendCommand("正在保存并应用", settleDelayMs = LONG_COMMAND_DELAY_MS) {
            ZuiControlRequest.send(
                this,
                ZuiControlContract.CMD_SET_PERFORMANCE_PROFILE_STAGED,
                pkg = pkg,
                mode = profile.mode.id,
                stagePayload = profile.stagePayload(),
            )
        }
    }

    private fun setDefaultThermalForm() {
        val defaults = defaultFrequencyBundle()
        thermalDrafts.clear()
        ThermalZone.entries.forEach { zone ->
            thermalDrafts[zone] = defaults
        }
        warmStartInput.setText("42")
        hotStartInput.setText("48")
        currentThermalZone = ThermalZone.NORMAL
        if (::thermalZoneSpinner.isInitialized &&
            thermalZoneSpinner.selectedItemPosition != currentThermalZone.ordinal) {
            thermalZoneSpinner.setSelection(currentThermalZone.ordinal)
        }
        loadThermalZoneFields(currentThermalZone)
    }

    private fun loadThermalForm(profile: PerformanceProfile) {
        val defaultStage = profile.stages.firstOrNull { it.thresholdLevel == TEMP_DEFAULT_LEVEL }
            ?: PerformanceStage(
                TEMP_DEFAULT_LEVEL,
                profile.littleMaxKHz,
                profile.littleMinKHz,
                profile.bigMaxKHz,
                profile.bigMinKHz,
                profile.titanMaxKHz,
                profile.titanMinKHz,
                profile.megaMaxKHz,
                profile.megaMinKHz,
                profile.gpuMaxKHz,
                profile.gpuMinKHz,
            )
        val thermalStages = profile.stages.filter { it.thresholdLevel != TEMP_DEFAULT_LEVEL }
        val midStage = thermalStages.firstOrNull()
        val hotStage = when {
            thermalStages.size >= 2 -> thermalStages.last()
            else -> null
        }
        thermalDrafts.clear()
        thermalDrafts[ThermalZone.NORMAL] = bundleFromStage(defaultStage)
        thermalDrafts[ThermalZone.MID] = bundleFromStage(midStage ?: defaultStage)
        thermalDrafts[ThermalZone.HOT] = bundleFromStage(hotStage ?: midStage ?: defaultStage)
        warmStartInput.setText((midStage?.temperatureCelsius() ?: 42).toString())
        hotStartInput.setText((hotStage?.temperatureCelsius() ?: 48).toString())
        currentThermalZone = ThermalZone.NORMAL
        if (::thermalZoneSpinner.isInitialized &&
            thermalZoneSpinner.selectedItemPosition != currentThermalZone.ordinal) {
            thermalZoneSpinner.setSelection(currentThermalZone.ordinal)
        }
        loadThermalZoneFields(currentThermalZone)
    }

    private fun bindThermalPreviewUpdates() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!loadingPerformanceForm) {
                    updateThermalPreview()
                }
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        }
        listOf(
            warmStartInput,
            hotStartInput,
            littleMaxInput,
            littleMinInput,
            bigMaxInput,
            bigMinInput,
            titanMaxInput,
            titanMinInput,
            megaMaxInput,
            megaMinInput,
            gpuMaxInput,
            gpuMinInput,
        ).forEach { it.addTextChangedListener(watcher) }
    }

    private fun updateThermalPreview() {
        if (!::thermalPreview.isInitialized) {
            return
        }
        val thresholds = parseThermalThresholds(showToast = false)
        if (thresholds == null) {
            thermalPreview.text = "温区等待有效起点：35-50℃，且中温起点 < 高温起点"
            return
        }
        thermalPreview.text = "当前温区：${currentThermalZone.title}（" +
            "${zoneRangeText(currentThermalZone, thresholds)}）\n" +
            ThermalZone.entries.joinToString(" · ") { zone ->
                "${zone.shortTitle} ${zoneRangeText(zone, thresholds)}"
            }
    }

    private fun switchThermalZone(position: Int) {
        val next = ThermalZone.entries.getOrElse(position) { ThermalZone.NORMAL }
        if (next == currentThermalZone) {
            updateThermalPreview()
            return
        }
        saveVisibleThermalDraft(showToast = false)
        currentThermalZone = next
        loadingPerformanceForm = true
        try {
            loadThermalZoneFields(next)
        } finally {
            loadingPerformanceForm = false
        }
        updateThermalPreview()
    }

    private fun loadThermalZoneFields(zone: ThermalZone) {
        setFrequencyFields(thermalDrafts[zone] ?: defaultFrequencyBundle())
    }

    private fun setFrequencyFields(bundle: FrequencyBundle) {
        littleMaxInput.setText(formatFreq(bundle.littleMax))
        littleMinInput.setText(formatFreq(bundle.littleMin))
        bigMaxInput.setText(formatFreq(bundle.bigMax))
        bigMinInput.setText(formatFreq(bundle.bigMin))
        titanMaxInput.setText(formatFreq(bundle.titanMax))
        titanMinInput.setText(formatFreq(bundle.titanMin))
        megaMaxInput.setText(formatFreq(bundle.megaMax))
        megaMinInput.setText(formatFreq(bundle.megaMin))
        gpuMaxInput.setText(formatFreq(bundle.gpuMax))
        gpuMinInput.setText(formatFreq(bundle.gpuMin))
    }

    private fun saveVisibleThermalDraft(showToast: Boolean): Boolean {
        val bundle = currentFrequencyBundle(showToast) ?: return false
        thermalDrafts[currentThermalZone] = bundle
        return true
    }

    private fun buildThermalStages(showToast: Boolean): List<PerformanceStage>? {
        if (!saveVisibleThermalDraft(showToast)) {
            return null
        }
        val thresholds = parseThermalThresholds(showToast) ?: return null
        return listOf(
            stageFromBundle(TEMP_DEFAULT_LEVEL, thermalDrafts[ThermalZone.NORMAL] ?: defaultFrequencyBundle()),
            stageFromBundle(thresholds.warmLevel, thermalDrafts[ThermalZone.MID] ?: defaultFrequencyBundle()),
            stageFromBundle(thresholds.hotLevel, thermalDrafts[ThermalZone.HOT] ?: defaultFrequencyBundle()),
        )
    }

    private fun stageFromBundle(
        thresholdLevel: Int,
        bundle: FrequencyBundle,
    ): PerformanceStage {
        return PerformanceStage(
            thresholdLevel,
            bundle.littleMax,
            bundle.littleMin,
            bundle.bigMax,
            bundle.bigMin,
            bundle.titanMax,
            bundle.titanMin,
            bundle.megaMax,
            bundle.megaMin,
            bundle.gpuMax,
            bundle.gpuMin,
        )
    }

    private fun bundleFromStage(stage: PerformanceStage): FrequencyBundle {
        return FrequencyBundle(
            stage.littleMaxKHz,
            stage.littleMinKHz,
            stage.bigMaxKHz,
            stage.bigMinKHz,
            stage.titanMaxKHz,
            stage.titanMinKHz,
            stage.megaMaxKHz,
            stage.megaMinKHz,
            stage.gpuMaxKHz,
            stage.gpuMinKHz,
        )
    }

    private fun defaultFrequencyBundle(): FrequencyBundle {
        return FrequencyBundle(
            LITTLE_FREQS.last(),
            LITTLE_FREQS.first(),
            BIG_FREQS.last(),
            BIG_FREQS.first(),
            TITAN_FREQS.last(),
            TITAN_FREQS.first(),
            MEGA_FREQS.last(),
            MEGA_FREQS.first(),
            GPU_FREQS.first(),
            GPU_FREQS.last(),
        )
    }

    private fun zoneRangeText(zone: ThermalZone, thresholds: ThermalThresholds): String {
        val warm = thresholds.warmLevel + TEMP_LEVEL_OFFSET
        val hot = thresholds.hotLevel + TEMP_LEVEL_OFFSET
        return when (zone) {
            ThermalZone.NORMAL -> "<${warm}℃"
            ThermalZone.MID -> "${warm}-${hot - 1}℃"
            ThermalZone.HOT -> "≥${hot}℃"
        }
    }

    private fun parseThermalThresholds(showToast: Boolean): ThermalThresholds? {
        val warm = parseTempLevel(warmStartInput.text.toString(), "中温起点", showToast) ?: return null
        val hot = parseTempLevel(hotStartInput.text.toString(), "高温起点", showToast) ?: return null
        if (warm >= hot) {
            if (showToast) {
                toast("温度必须递增：中温起点 < 高温起点")
            }
            return null
        }
        return ThermalThresholds(warm, hot)
    }

    private fun parseTempLevel(value: String, label: String, showToast: Boolean): Int? {
        val temp = value.trim().removeSuffix("℃").removeSuffix("C")
            .removeSuffix("c").trim().toDoubleOrNull()
        val celsius = temp?.let { Math.round(it).toInt() }
        if (celsius == null || celsius !in 35..50) {
            if (showToast) {
                toast("$label 温度范围为 35-50℃")
            }
            return null
        }
        return celsius - TEMP_LEVEL_OFFSET
    }

    private fun currentFrequencyBundle(showToast: Boolean): FrequencyBundle? {
        val littleMax = parseFrequencyField(littleMaxInput, LITTLE_FREQS, true, "Little 上限", showToast)
            ?: return null
        val littleMin = parseFrequencyField(littleMinInput, LITTLE_FREQS, false, "Little 下限", showToast)
            ?: return null
        val bigMax = parseFrequencyField(bigMaxInput, BIG_FREQS, true, "Big 上限", showToast)
            ?: return null
        val bigMin = parseFrequencyField(bigMinInput, BIG_FREQS, false, "Big 下限", showToast)
            ?: return null
        val titanMax = parseFrequencyField(titanMaxInput, TITAN_FREQS, true, "Titan 上限", showToast)
            ?: return null
        val titanMin = parseFrequencyField(titanMinInput, TITAN_FREQS, false, "Titan 下限", showToast)
            ?: return null
        val megaMax = parseFrequencyField(megaMaxInput, MEGA_FREQS, true, "Mega 上限", showToast)
            ?: return null
        val megaMin = parseFrequencyField(megaMinInput, MEGA_FREQS, false, "Mega 下限", showToast)
            ?: return null
        val gpuMax = parseFrequencyField(gpuMaxInput, GPU_FREQS, true, "GPU 上限", showToast)
            ?: return null
        val gpuMin = parseFrequencyField(gpuMinInput, GPU_FREQS, false, "GPU 下限", showToast)
            ?: return null
        if (littleMax < littleMin || bigMax < bigMin || titanMax < titanMin ||
            megaMax < megaMin || gpuMax < gpuMin) {
            if (showToast) {
                toast("上限不能低于下限")
            }
            return null
        }
        return FrequencyBundle(
            littleMax,
            littleMin,
            bigMax,
            bigMin,
            titanMax,
            titanMin,
            megaMax,
            megaMin,
            gpuMax,
            gpuMin,
        )
    }

    private fun parseFrequencyField(
        field: EditText,
        available: IntArray,
        preferHigh: Boolean,
        label: String,
        showToast: Boolean,
    ): Int? {
        val parsed = parseFreq(field.text.toString(), available, preferHigh)
        if (parsed == null && showToast) {
            toast("$label 不在可用档位")
        }
        return parsed
    }

    private fun removePerformanceProfile() {
        val pkg = selectedPackage ?: return toast("请先选择应用")
        val key = "$pkg|${selectedMode().id}"
        performanceProfiles.remove(key)
        renderPerformanceProfiles()
        sendCommand("正在删除性能配置") {
            ZuiControlRequest.send(
                this,
                ZuiControlContract.CMD_REMOVE_PERFORMANCE_PROFILE,
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
                sendCommand("正在刷新", refreshNotification = true) {
                    ZuiControlRequest.send(this@MainActivity, ZuiControlContract.CMD_STATUS)
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
        val status = setting(ZuiControlContract.KEY_DAEMON_STATUS_TEXT)
            .ifBlank { setting(ZuiControlContract.KEY_STATUS_TEXT) }
        val xml = setting(ZuiControlContract.KEY_XML_STATE).ifBlank { "未挂载" }
        val last = setting(ZuiControlContract.KEY_STATUS_LAST).ifBlank { "无" }
        val gpu = setting(ZuiControlContract.KEY_GPU_STATE).ifBlank { "KGSL 等待回读" }
        val daemonRefresh = setting(ZuiControlContract.KEY_DAEMON_REFRESH_STATE)
            .ifBlank { "refresh_owner=system; daemon refresh disabled" }
        systemStatus.text = "守护服务 运行中\nXML $xml\nGPU $gpu\n刷新 $daemonRefresh\n最近操作 ${commandName(last)}\n$status"
        asoulStatus.text = setting(ZuiControlContract.KEY_ASOUL_HEALTH)
            .ifBlank { "正在读取 AsoulOpt 状态" }
    }

    private fun exportLogs() {
        sendCommand("正在整理日志", settleDelayMs = EXPORT_COMMAND_DELAY_MS) {
            ZuiControlRequest.send(this, ZuiControlContract.CMD_EXPORT_LOGS)
        }
        handler.postDelayed({
            pendingExportText = setting(ZuiControlContract.KEY_LOG_EXPORT)
            if (pendingExportText.isBlank()) {
                toast("日志尚未准备好")
                return@postDelayed
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "ZuiControl_logs_$stamp.txt")
                },
                REQUEST_EXPORT_LOG,
            )
        }, EXPORT_COMMAND_DELAY_MS + 300)
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

    private fun showPackagePicker(
        title: String = "选择应用",
        onSelected: ((PackageEntry) -> Unit)? = null,
    ) {
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
            .setTitle(title)
            .setView(root)
            .setNegativeButton("取消", null)
            .create()
        val adapter = PackagePickerAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            adapter.getEntry(position)?.let {
                labelCache[it.info.packageName] = it.label()
                if (onSelected == null) {
                    selectedPackage = it.info.packageName
                }
                dialog.dismiss()
                if (onSelected == null) {
                    updatePerformanceForm()
                } else {
                    onSelected.invoke(it)
                }
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
        reloadRefreshProfiles()
        performanceProfiles.clear()
        setting(ZuiControlContract.KEY_PERFORMANCE_PROFILES_TEXT).lineSequence()
            .mapNotNull { PerformanceProfile.parse(it) }
            .forEach { performanceProfiles[it.key] = it }
    }

    private fun reloadRefreshProfiles() {
        val serviceState = ZuiControlClient.stateText()
        serviceState.lineSequence()
            .filter { it.startsWith("profile=") }
            .forEach { line ->
                val parts = line.substringAfter('=').split("|")
                val pkg = parts.getOrNull(1).orEmpty()
                val rate = parts.getOrNull(2)?.toIntOrNull()
                if (parts.size >= 5 && PackageNames.isValid(pkg) &&
                    rate != null && rate in ZuiControlContract.rates &&
                    rate != RefreshSceneController.BASE_REFRESH_RATE) {
                    refreshRules[pkg] = rate
                }
            }
        if (refreshRules.isNotEmpty()) {
            return
        }
    }

    private fun sendCommand(
        message: String?,
        settleDelayMs: Long = SHORT_COMMAND_DELAY_MS,
        refreshNotification: Boolean = false,
        block: () -> Unit,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (commandInFlight || now - lastCommandAt < 180) {
            if (message != null) toast("操作处理中")
            return
        }
        commandInFlight = true
        lastCommandAt = now
        if (message != null) toast(message)
        Thread {
            val result = runCatching { block() }
            handler.post {
                handler.postDelayed({
                    commandInFlight = false
                    if (result.isFailure && message != null) {
                        toast("命令发送失败")
                    }
                    reloadState()
                    renderCurrentPage()
                    if (refreshNotification) {
                        ZuiControlQuickService.start(this)
                    }
                }, settleDelayMs)
            }
        }.start()
    }

    private fun selectedMode(): PerformanceMode {
        val position = if (::modeSpinner.isInitialized) modeSpinner.selectedItemPosition else 0
        return PerformanceMode.entries.getOrElse(position) { PerformanceMode.BALANCED }
    }

    private fun parseFreq(value: String, available: IntArray, preferHigh: Boolean): Int? {
        val normalized = value.trim()
            .lowercase(Locale.US)
            .removeSuffix("mhz")
            .removeSuffix("ghz")
            .trim()
        if (normalized.isBlank()) {
            return null
        }
        available.firstOrNull { it.toString() == normalized }?.let { return it }
        val requested = normalized.toDoubleOrNull() ?: return null
        val exactKHz = when {
            requested >= 10_000.0 -> Math.round(requested).toInt()
            requested >= 100.0 -> Math.round(requested * 1_000.0).toInt()
            else -> Math.round(requested * 1_000_000.0).toInt()
        }
        available.firstOrNull { kotlin.math.abs(it - exactKHz) <= 5_000 }?.let { return it }
        val minDistance = available.minOf { kotlin.math.abs(it - exactKHz) }
        val nearest = available.filter { kotlin.math.abs(it - exactKHz) == minDistance }
        val selected = if (preferHigh) nearest.maxOrNull() else nearest.minOrNull()
            ?: return null
        return if (minDistance <= 80_000) {
            selected
        } else {
            null
        }
    }

    private fun formatFreq(khz: Int): String =
        String.format(Locale.US, "%.2f", khz / 1_000_000.0)
            .trimEnd('0')
            .trimEnd('.')

    private fun frequencyHelp(title: String, available: IntArray): String =
        available.map { formatFreq(it) }
            .distinct()
            .chunked(8)
            .joinToString("\n", prefix = "$title\nGHz: ") { row ->
                row.joinToString("    ")
            }

    private fun showFrequencyHelp(title: String, available: IntArray) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(frequencyHelp(title, available))
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun labelForPackage(pkg: String): String {
        return labelCache.getOrPut(pkg) {
            runCatching {
                packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
            }.getOrDefault(pkg)
        }
    }

    private fun setting(key: String): String =
        Settings.System.getString(contentResolver, key).orEmpty().takeUnless { it == "null" }.orEmpty()

    private fun stateValue(state: String, key: String): String {
        return state.lineSequence()
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            .orEmpty()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun String.cleanSetting(): String = removeSuffix(".0")

    private fun commandName(value: String): String = when (value) {
        ZuiControlContract.CMD_SET_PERFORMANCE_PROFILE_STAGED -> "保存温控性能配置"
        ZuiControlContract.CMD_SET_PERFORMANCE_PROFILE -> "保存性能配置"
        ZuiControlContract.CMD_REMOVE_PERFORMANCE_PROFILE -> "删除性能配置"
        ZuiControlContract.CMD_APPLY_PERFORMANCE -> "应用性能调度"
        ZuiControlContract.CMD_RESTORE_ZUIPP -> "恢复基线调度"
        ZuiControlContract.CMD_RESTORE_LAST_GOOD -> "恢复 last_good"
        ZuiControlContract.CMD_RESTORE_OFFICIAL_ORIGINAL -> "恢复官方原始表"
        "restore_baked_baseline" -> "恢复基线调度"
        ZuiControlContract.CMD_EXPORT_LOGS -> "导出日志"
        ZuiControlContract.CMD_STATUS -> "刷新状态"
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

    private fun freqRow(title: String, maxField: EditText, minField: EditText, available: IntArray) =
        vertical().apply {
            val header = horizontalRow().apply {
                background = null
                setPadding(0, 0, 0, dp(6))
                addView(fieldTitle(title), LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ))
                addView(helpButton { showFrequencyHelp(title, available) },
                    LinearLayout.LayoutParams(dp(30), dp(30)))
            }
            addView(header)
            val row = horizontalRow().apply {
                background = null
                setPadding(0, 0, 0, 0)
                addView(fieldBox("上限", maxField), LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(fieldBox("下限", minField), LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(10), 0, 0, 0)
                })
            }
            addView(row)
        }

    private fun freqPairRow(left: View, right: View) = horizontalRow().apply {
        background = null
        setPadding(0, 0, 0, 0)
        addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(14), 0, 0, 0)
        })
    }

    private fun actionPair(left: TextView, right: TextView) = horizontalRow().apply {
        background = null
        setPadding(0, 0, 0, 0)
        addView(left, LinearLayout.LayoutParams(0, dp(48), 1f))
        addView(right, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            setMargins(dp(12), 0, 0, 0)
        })
    }

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

    private fun helpButton(action: () -> Unit) =
        label("!", 13f, COLOR_ACCENT, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = rounded(COLOR_FIELD, dp(15), COLOR_STROKE)
            setOnClickListener { action() }
        }

    private fun infoPanel() = label("", 13f, COLOR_TEXT, Typeface.NORMAL).apply {
        setPadding(dp(13), dp(11), dp(13), dp(11))
        background = rounded(COLOR_FIELD, dp(8), COLOR_STROKE)
    }

    private fun compactNote(text: String) = label(text, 12f, COLOR_SUBTLE, Typeface.NORMAL).apply {
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = rounded(COLOR_NOTE, dp(7), COLOR_STROKE)
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

    private fun buttonMargins() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        setMargins(0, dp(12), 0, 0)
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

    private data class FrequencyBundle(
        val littleMax: Int,
        val littleMin: Int,
        val bigMax: Int,
        val bigMin: Int,
        val titanMax: Int,
        val titanMin: Int,
        val megaMax: Int,
        val megaMin: Int,
        val gpuMax: Int,
        val gpuMin: Int,
    )

    private data class ThermalThresholds(
        val warmLevel: Int,
        val hotLevel: Int,
    )

    private enum class ThermalZone(
        val title: String,
        val shortTitle: String,
    ) {
        NORMAL("低温", "低温"),
        MID("中温", "中温"),
        HOT("高温", "高温"),
    }

    private enum class Page(val title: String) {
        REFRESH("刷新率"),
        PERFORMANCE("性能调度"),
        SYSTEM("系统状态"),
    }

    companion object {
        private const val REQUEST_EXPORT_LOG = 901
        private const val APP_VERSION_NAME = "0.18.0"
        private const val SHORT_COMMAND_DELAY_MS = 720L
        private const val LONG_COMMAND_DELAY_MS = 6500L
        private const val EXPORT_COMMAND_DELAY_MS = 1800L
        private val COLOR_BG = Color.rgb(244, 247, 250)
        private val COLOR_FIELD = Color.rgb(238, 242, 246)
        private val COLOR_NOTE = Color.rgb(250, 252, 244)
        private val COLOR_SELECTED = Color.rgb(226, 239, 255)
        private val COLOR_STROKE = Color.rgb(211, 220, 230)
        private val COLOR_TEXT = Color.rgb(28, 34, 42)
        private val COLOR_SUBTLE = Color.rgb(91, 101, 114)
        private val COLOR_ACCENT = Color.rgb(35, 102, 207)
        private val COLOR_GREEN = Color.rgb(32, 132, 99)
        private val LITTLE_FREQS = intArrayOf(
            364800, 460800, 556800, 672000, 787200, 902400, 1017600, 1132800,
            1248000, 1344000, 1459200, 1574400, 1689600, 1804800, 1920000,
            2035200, 2150400, 2265600,
        )
        private val BIG_FREQS = intArrayOf(
            499200, 614400, 729600, 844800, 960000, 1075200, 1190400, 1286400,
            1401600, 1497600, 1612800, 1708800, 1824000, 1920000, 2035200,
            2131200, 2188800, 2246400, 2323200, 2380800, 2438400, 2515200,
            2572800, 2630400, 2707200, 2764800, 2841600, 2899200, 2956800,
            3014400, 3072000, 3148800,
        )
        private val TITAN_FREQS = intArrayOf(
            499200, 614400, 729600, 844800, 960000, 1075200, 1190400, 1286400,
            1401600, 1497600, 1612800, 1708800, 1824000, 1920000, 2035200,
            2131200, 2188800, 2246400, 2323200, 2380800, 2438400, 2515200,
            2572800, 2630400, 2707200, 2764800, 2841600, 2899200, 2956800,
        )
        private val MEGA_FREQS = intArrayOf(
            480000, 576000, 672000, 787200, 902400, 1017600, 1132800, 1248000,
            1363200, 1478400, 1593600, 1708800, 1824000, 1939200, 2035200,
            2112000, 2169600, 2246400, 2304000, 2380800, 2438400, 2496000,
            2553600, 2630400, 2688000, 2745600, 2803200, 2880000, 2937600,
            2995200, 3052800, 3110400, 3187200, 3244800, 3302400,
        )
        private val GPU_FREQS = intArrayOf(
            903000, 834000, 770000, 720000, 680000, 629000,
            578000, 500000, 422000, 366000, 310000, 231000,
        )
    }
}
