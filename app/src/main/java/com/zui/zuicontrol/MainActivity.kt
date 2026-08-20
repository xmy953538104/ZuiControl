package com.zui.zuicontrol

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun hasP2StateError(xmlState: String, reloadState: String): Boolean =
    sequenceOf(xmlState, reloadState)
        .flatMap { it.lineSequence() }
        .any {
            it.contains("failed", ignoreCase = true) ||
                it.contains("error", ignoreCase = true)
        }

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val performanceProfiles = linkedMapOf<String, PerformanceProfile>()
    private val appOptRules = linkedMapOf<String, AppOptRule>()
    private val refreshRules = linkedMapOf<String, Int>()
    private val labelCache = linkedMapOf<String, String>()
    private val appOptTemplates by lazy { AppOptConfig.readTemplates(this) }

    private lateinit var contentHost: FrameLayout
    private lateinit var tabButtons: Map<Page, TextView>
    private lateinit var settingsButton: ImageView
    private lateinit var headerStatus: TextView
    private lateinit var refreshRulesHost: LinearLayout
    private lateinit var performanceListHost: LinearLayout
    private lateinit var selectedAppTitle: TextView
    private lateinit var selectedPackageView: TextView
    private lateinit var policySpinner: Spinner
    private lateinit var framePolicySpinner: Spinner
    private lateinit var policySummary: TextView
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
    private lateinit var systemStatus: TextView
    private lateinit var appOptRulesHost: LinearLayout

    private var currentPage = Page.REFRESH
    private var performanceEditorOpen = false
    private var selectedPackage: String? = null
    private var commandInFlight = false
    private var lastCommandAt = 0L
    private var pendingExportText = ""
    private var loadingPerformanceForm = false
    private var currentThermalZone = ThermalZone.NORMAL
    private val thermalDrafts = mutableMapOf<ThermalZone, FrequencyBundle>()

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
        selectedPackage = savedInstanceState?.getString(STATE_PERFORMANCE_PACKAGE)
        val initialAppOptRules = LinkedHashMap(appOptRules)
        Thread { runCatching { AppOptConfig.ensure(this, initialAppOptRules) } }.start()
        setContentView(buildRoot())
        val restoredPage = savedInstanceState?.getString(STATE_PAGE)?.let { name ->
            Page.entries.firstOrNull { it.name == name }
        } ?: Page.REFRESH
        showPage(restoredPage)
        if (restoredPage == Page.PERFORMANCE &&
            savedInstanceState?.getBoolean(STATE_PERFORMANCE_EDITOR) == true &&
            selectedPackage != null
        ) {
            performanceEditorOpen = true
            renderCurrentPage()
        }
        if (!BuildConfig.DEBUG) {
            handler.postDelayed({ ZuiControlQuickService.start(this) }, 250)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PAGE, currentPage.name)
        outState.putBoolean(STATE_PERFORMANCE_EDITOR, performanceEditorOpen)
        outState.putString(STATE_PERFORMANCE_PACKAGE, selectedPackage)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::contentHost.isInitialized) {
            reloadState()
            renderCurrentPage()
        }
    }

    private fun buildRoot(): View {
        val portrait = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG)
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        root.addView(header(), matchWrap())
        contentHost = FrameLayout(this)
        if (portrait) {
            root.addView(contentHost, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
            root.addView(pageTabs(horizontal = true), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(70),
            ).apply {
                setMargins(0, dp(8), 0, 0)
            })
        } else {
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(pageTabs(horizontal = false), LinearLayout.LayoutParams(
                    dp(104),
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ).apply {
                    setMargins(0, 0, dp(12), 0)
                })
                addView(contentHost, LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1f,
                ))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
        }
        return root
    }

    private fun header(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
            val titleBox = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("ZuiControl", 28f, COLOR_TEXT, Typeface.BOLD))
                headerStatus = label(headerStatusText(), 12f, COLOR_SUBTLE, Typeface.NORMAL)
                addView(headerStatus)
            }
            addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(iconButton(R.drawable.ic_action_refresh, "刷新状态") {
                sendCommand("正在刷新", refreshNotification = true) {
                    ZuiControlRequest.send(this@MainActivity, ZuiControlContract.CMD_STATUS)
                }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            settingsButton = iconButton(R.drawable.ic_nav_system, "设置") {
                showPage(Page.SYSTEM)
            }
            addView(settingsButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                setMargins(dp(8), 0, 0, 0)
            })
        }
    }

    private fun pageTabs(horizontal: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(COLOR_BG)
        }
        val map = linkedMapOf<Page, TextView>()
        Page.entries.filter { it != Page.SYSTEM }.forEach { page ->
            val tab = label(page.title, 12f, COLOR_SUBTLE, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
                compoundDrawablePadding = dp(4)
                setPadding(dp(10), dp(7), dp(10), dp(6))
                setCompoundDrawablesRelativeWithIntrinsicBounds(0, page.iconRes, 0, 0)
                setOnClickListener { showPage(page) }
            }
            map[page] = tab
            row.addView(tab, LinearLayout.LayoutParams(
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
        performanceEditorOpen = false
        tabButtons.forEach { (item, view) ->
            val selected = item == page
            val color = if (selected) COLOR_ACCENT else COLOR_SUBTLE
            view.setTextColor(color)
            view.compoundDrawableTintList = ColorStateList.valueOf(color)
            view.background = rounded(
                if (selected) COLOR_SELECTED else Color.TRANSPARENT,
                dp(18),
                Color.TRANSPARENT,
            )
        }
        if (::settingsButton.isInitialized) {
            val selected = page == Page.SYSTEM
            val color = if (selected) COLOR_ACCENT else COLOR_SUBTLE
            settingsButton.imageTintList = ColorStateList.valueOf(color)
            settingsButton.background = rounded(
                if (selected) COLOR_SELECTED else COLOR_FIELD,
                dp(22),
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
            Page.PERFORMANCE -> if (performanceEditorOpen) {
                buildPerformanceEditorPage()
            } else {
                buildPerformancePage()
            }
            Page.APPOPT -> buildAppOptPage()
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
        return "v${appVersionName()} · ${displayHz}Hz"
    }

    private fun appVersionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun updateHeaderStatus() {
        if (::headerStatus.isInitialized) {
            headerStatus.text = headerStatusText()
        }
    }

    private fun buildRefreshPage(): View {
        val root = FrameLayout(this)
        refreshRulesHost = vertical()
        val scroll = ScrollView(this).apply {
            addView(refreshRulesHost)
            clipToPadding = false
            setPadding(0, 0, 0, dp(76))
        }
        root.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        root.addView(floatingButton("+") {
            showPackagePicker("选择刷新率应用") { entry ->
                labelCache[entry.info.packageName] = entry.label()
                showRefreshRateDialog(entry.info.packageName, refreshRules[entry.info.packageName])
            }
        }, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.END or Gravity.BOTTOM).apply {
            setMargins(0, 0, dp(6), dp(8))
        })
        renderRefreshState()
        return root
    }

    private fun renderRefreshState() {
        if (!::refreshRulesHost.isInitialized) {
            return
        }
        refreshRulesHost.removeAllViews()
        if (refreshRules.isEmpty()) {
            refreshRulesHost.addView(emptyText("点击右下角 + 添加应用"), matchWrap())
            return
        }
        val columns = if (isWideLayout()) 3 else 2
        refreshRules.entries.chunked(columns).forEach { entries ->
            val row = horizontalRow().apply {
                background = null
                elevation = 0f
                setPadding(0, 0, 0, 0)
            }
            entries.forEachIndexed { index, (pkg, rate) ->
                val card = horizontalRow().apply {
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = rounded(Color.WHITE, dp(20), Color.TRANSPARENT)
                    elevation = 0f
                    addView(appIcon(pkg), LinearLayout.LayoutParams(dp(42), dp(42)))
                    addView(label(labelForPackage(pkg), 12f, COLOR_TEXT, Typeface.BOLD).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        maxLines = 1
                    }, LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f,
                    ).apply { setMargins(dp(10), 0, dp(8), 0) })
                    addView(rateBadge("${rate}Hz"), LinearLayout.LayoutParams(dp(78), dp(38)).apply {
                        setMargins(0, 0, 0, 0)
                    })
                    setOnClickListener { showRefreshRateDialog(pkg, rate) }
                }
                row.addView(card, LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply {
                    if (index > 0) setMargins(dp(8), 0, 0, 0)
                })
            }
            repeat(columns - entries.size) { index ->
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply {
                    if (entries.isNotEmpty() || index > 0) setMargins(dp(8), 0, 0, 0)
                })
            }
            refreshRulesHost.addView(row, cardMargins())
        }
    }

    private fun showRefreshRateDialog(pkg: String, currentRate: Int?) {
        val labels = ZuiControlContract.rates.map { rate -> "${rate}Hz" }
        val ratePicker = traySpinner(labels) {}
        ratePicker.setSelection(
            ZuiControlContract.rates.indexOf(currentRate).takeIf { it >= 0 }
                ?: ZuiControlContract.rates.indexOf(RefreshSceneController.BASE_REFRESH_RATE),
        )
        val content = vertical().apply {
            setPadding(dp(20), dp(6), dp(20), 0)
            addView(appIdentity(pkg))
            addView(fieldTitle("刷新率"), fieldMargins())
            addView(ratePicker, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { setMargins(0, dp(6), 0, 0) })
        }
        AlertDialog.Builder(this)
            .setTitle(if (currentRate == null) "添加刷新率" else "编辑刷新率")
            .setView(content)
            .setPositiveButton("保存") { _, _ ->
                setRefreshProfile(pkg, ZuiControlContract.rates[ratePicker.selectedItemPosition])
            }
            .apply {
                if (currentRate != null) {
                    setNeutralButton("删除") { _, _ -> removeRefreshProfile(pkg) }
                }
            }
            .setNegativeButton("取消", null)
            .showStyled()
    }

    private fun setRefreshProfile(pkg: String, rate: Int) {
        val message = "正在设置 ${rate}Hz"
        sendCommand(message, refreshNotification = true) {
            val reply = ZuiControlClient.setPackageDisplayHz(pkg, rate)
            if (!reply.ok) {
                throw IllegalStateException(reply.text)
            }
            ZuiControlRequest.send(this, ZuiControlContract.CMD_SYNC_XML_REFRESH)
        }
    }

    private fun removeRefreshProfile(pkg: String) {
        sendCommand("正在移除规则", refreshNotification = true) {
            val reply = ZuiControlClient.removePackageProfile(pkg)
            if (!reply.ok) {
                throw IllegalStateException(reply.text)
            }
            ZuiControlRequest.send(this, ZuiControlContract.CMD_SYNC_XML_REFRESH)
        }
    }

    private fun buildPerformancePage(): View {
        val root = FrameLayout(this)
        performanceListHost = vertical()
        root.addView(ScrollView(this).apply {
            addView(performanceListHost)
            clipToPadding = false
            setPadding(0, 0, 0, dp(76))
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        root.addView(floatingButton("+") {
            showPackagePicker(
                title = "选择游戏",
                userAppsOnly = true,
                launchableOnly = true,
                onSelected = { entry -> openPerformanceEditor(entry.info.packageName) },
            )
        }, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.END or Gravity.BOTTOM).apply {
            setMargins(0, 0, dp(6), dp(8))
        })
        renderPerformanceProfiles()
        return root
    }

    private fun openPerformanceEditor(pkg: String) {
        selectedPackage = pkg
        performanceEditorOpen = true
        renderCurrentPage()
    }

    private fun buildPerformanceEditorPage(): View {
        val tablet = isWideLayout()
        val root = vertical()

        val formPanel = panel().apply {
            orientation = LinearLayout.VERTICAL
            selectedAppTitle = label("选择或添加应用", 20f, COLOR_TEXT, Typeface.BOLD)
            selectedPackageView = label("", 12f, COLOR_SUBTLE, Typeface.NORMAL)
            addView(horizontalRow().apply {
                background = null
                setPadding(0, 0, 0, dp(4))
                addView(commandButton("‹") {
                    performanceEditorOpen = false
                    renderCurrentPage()
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                selectedPackage?.let {
                    addView(appIcon(it), LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                        setMargins(dp(10), 0, dp(10), 0)
                    })
                }
                addView(vertical().apply {
                    addView(selectedAppTitle)
                    addView(selectedPackageView)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })

            policySpinner = Spinner(this@MainActivity).apply {
                adapter = SimpleTextAdapter(GamePolicyMode.entries.map { it.title })
            }

            addView(fieldTitle("游戏帧率"), fieldMargins())
            framePolicySpinner = traySpinner(
                listOf("性能 120Hz / 省电 60Hz", "统一 60Hz", "跟随应用刷新率"),
            ) {
                if (!loadingPerformanceForm) updatePolicySummary()
            }
            addView(framePolicySpinner, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { setMargins(0, dp(6), 0, 0) })
            policySummary = compactNote("")
            addView(policySummary, fieldMargins())

            addView(fieldTitle("温度起始点"), fieldMargins())
            addView(compactNote("同一套 CPU/GPU 三温区会镜像到均衡、节能、野兽模式。"),
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
                addView(fieldBox("中温起始点 ℃", warmStartInput), LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(fieldBox("高温起始点 ℃", hotStartInput), LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(10), 0, 0, 0)
                })
            }, fieldMargins())

            addView(fieldTitle("温区频率配置"), fieldMargins())
            thermalZoneSpinner = traySpinner(ThermalZone.entries.map { it.title }) { position ->
                if (!loadingPerformanceForm && ::littleMaxInput.isInitialized) {
                    switchThermalZone(position)
                }
            }
            addView(thermalZoneSpinner, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { setMargins(0, dp(6), 0, 0) })

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
            val littleRow = freqRow("Little cpu0-cpu1", littleMaxInput, littleMinInput, LITTLE_FREQS)
            val bigRow = freqRow("Big cpu2-cpu4", bigMaxInput, bigMinInput, BIG_FREQS)
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

            thermalPreview = compactNote("")
            addView(thermalPreview, fieldMargins())
            addView(commandButton("当前频率同步到全部温区") {
                syncCurrentThermalZoneToAll()
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44),
            ).apply {
                setMargins(0, dp(10), 0, 0)
            })
            bindThermalPreviewUpdates()

            addView(actionPair(
                primaryButton("保存并应用") { savePerformanceProfile() },
                dangerButton("删除并应用") { removePerformanceProfile() },
            ), buttonMargins())
        }

        root.addView(ScrollView(this).apply { addView(formPanel) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
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
        ensureSelectedPerformanceProfile()
        performanceProfiles.values.forEach { profile ->
            val row = horizontalRow().apply {
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(Color.WHITE, dp(18), Color.TRANSPARENT)
                elevation = dp(1).toFloat()
                addView(appIcon(profile.packageName), LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(vertical().apply {
                    addView(label(
                        labelForPackage(profile.packageName),
                        15f,
                        COLOR_TEXT,
                        Typeface.BOLD,
                    ))
                    addView(label(
                        "三温区 · ${frameSummary(profile.packageName, profile.framePolicy)}",
                        11f,
                        COLOR_SUBTLE,
                        Typeface.NORMAL,
                    ))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(12), 0, dp(12), 0)
                })
                addView(rateBadge("编辑"), LinearLayout.LayoutParams(dp(70), dp(38)))
                setOnClickListener {
                    openPerformanceEditor(profile.packageName)
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
        ensureSelectedPerformanceProfile()
        val pkg = selectedPackage
        selectedAppTitle.text = pkg?.let { labelForPackage(it) } ?: "选择或添加应用"
        selectedPackageView.text = pkg ?: "未选择应用"
        loadSelectedProfile()
        updatePolicySummary()
    }

    private fun loadSelectedProfile() {
        if (!::littleMaxInput.isInitialized) {
            return
        }
        val pkg = selectedPackage ?: return
        val profile = performanceProfiles.values.firstOrNull { it.packageName == pkg }
        loadingPerformanceForm = true
        try {
            if (profile == null) {
                setDefaultThermalForm()
                setPolicySelection(GamePolicyMode.INDEPENDENT)
                setFramePolicySelection(defaultFramePolicyFor(pkg))
            } else {
                loadThermalForm(profile)
                setPolicySelection(profile.gamePolicy)
                setFramePolicySelection(profile.framePolicy)
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
        val gamePolicy = selectedGamePolicy()
        val framePolicy = if (gamePolicy == GamePolicyMode.DEFAULT) {
            FramePolicy.DEFAULT
        } else {
            selectedFramePolicy()
        }
        val profile = PerformanceProfile(
            pkg,
            PerformanceMode.BALANCED,
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
            gamePolicy,
            framePolicy,
        )
        if (performanceProfiles[profile.key] == profile) {
            performanceEditorOpen = false
            renderCurrentPage()
            toast("配置没有变化，无需重新应用")
            return
        }
        sendCommand("正在保存性能配置", onTerminal = { ack ->
            if (ack.succeeded) {
                performanceEditorOpen = false
                toast(targetResultText(ack.detail, "游戏"))
                renderCurrentPage()
            }
        }) {
            ZuiControlRequest.send(
                this,
                ZuiControlContract.CMD_SET_PERFORMANCE_PROFILE_STAGED,
                pkg = pkg,
                mode = profile.mode.id,
                stagePayload = profile.stagePayload(),
                gamePolicy = profile.gamePolicy.id,
                framePolicy = profile.framePolicy.id,
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
            thermalPreview.text = "温区等待有效起始点：35-50℃，且中温起始点 < 高温起始点"
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

    private fun syncCurrentThermalZoneToAll() {
        val bundle = currentFrequencyBundle(showToast = true) ?: return
        ThermalZone.entries.forEach { zone ->
            thermalDrafts[zone] = bundle
        }
        setFrequencyFields(bundle)
        updateThermalPreview()
        toast("已同步到全部温区")
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
        val warm = parseTempLevel(warmStartInput.text.toString(), "中温起始点", showToast) ?: return null
        val hot = parseTempLevel(hotStartInput.text.toString(), "高温起始点", showToast) ?: return null
        if (warm >= hot) {
            if (showToast) {
                toast("温度必须递增：中温起始点 < 高温起始点")
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
        if (!performanceProfiles.containsKey(pkg)) return toast("该应用没有性能配置")
        AlertDialog.Builder(this)
            .setTitle("删除性能配置")
            .setMessage(
                "将删除 ${labelForPackage(pkg)} 的自定义 XML，并同步移出游戏助手用户自定义列表。" +
                    "系统内置游戏识别不会被删除。若目标正在运行，成功后会自动关闭。",
            )
            .setPositiveButton("删除并应用") { _, _ ->
                sendCommand("正在删除性能配置", onTerminal = { ack ->
                    if (ack.succeeded) {
                        performanceEditorOpen = false
                        toast(targetResultText(ack.detail, "游戏"))
                        renderCurrentPage()
                    }
                }) {
                    ZuiControlRequest.send(
                        this,
                        ZuiControlContract.CMD_REMOVE_PERFORMANCE_PROFILE,
                        pkg = pkg,
                        mode = PerformanceMode.BALANCED.id,
                    )
                }
            }
            .setNegativeButton("取消", null)
            .showStyled()
    }

    private fun buildAppOptPage(): View {
        val root = FrameLayout(this)
        appOptRulesHost = vertical()
        root.addView(ScrollView(this).apply {
            addView(appOptRulesHost)
            clipToPadding = false
            setPadding(0, 0, 0, dp(76))
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        root.addView(floatingButton("+") {
            showPackagePicker(
                title = "选择 AppOpt 应用",
                onSelected = { entry -> showAppOptPresetDialog(entry.info.packageName) },
                userAppsOnly = true,
                launchableOnly = true,
            )
        }, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.END or Gravity.BOTTOM).apply {
            setMargins(0, 0, dp(6), dp(8))
        })
        renderAppOptState()
        return root
    }

    private fun buildSystemPage(): View {
        val scroll = ScrollView(this)
        val root = vertical().apply { setPadding(0, 0, 0, dp(20)) }
        scroll.addView(root)

        systemStatus = compactNote("")
        root.addView(systemStatus)

        root.addView(sectionTitle("工具"), sectionMargins())
        root.addView(settingsAction(R.drawable.ic_action_logs, "导出运行日志", "排查刷新率、性能与 AppOpt") {
            exportLogs()
        }, settingsActionMargins())
        root.addView(settingsAction(R.drawable.ic_action_import, "导入 AppOpt 备份", AppOptConfig.DISPLAY_PATH) {
            importAppOptConfig()
        }, settingsActionMargins(spaced = true))
        root.addView(settingsAction(R.drawable.ic_action_export, "导出 AppOpt 备份", AppOptConfig.DISPLAY_PATH) {
            syncAppOptConfig(showToast = true)
        }, settingsActionMargins(spaced = true))
        root.addView(settingsAction(R.drawable.ic_action_stop, "停止 AppOpt", "保留规则，仅停止线程放置") {
            confirmStopAppOpt()
        }, settingsActionMargins(spaced = true))
        renderSystemState()
        return scroll
    }

    private fun renderSystemState() {
        if (!::systemStatus.isInitialized) {
            return
        }
        systemStatus.text = "系统服务正常 · ${conciseXmlState()} · ${conciseZuippReloadState()}"
    }

    private fun renderAppOptState() {
        renderAppOptRules()
    }

    private fun renderAppOptRules() {
        if (!::appOptRulesHost.isInitialized) {
            return
        }
        appOptRulesHost.removeAllViews()
        if (appOptRules.isEmpty()) {
            appOptRulesHost.addView(emptyText("点击右下角 + 添加应用"), matchWrap())
            return
        }
        appOptRules.values.forEach { rule ->
            val userApp = isInstalledUserApp(rule.packageName)
            val card = horizontalRow().apply {
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(Color.WHITE, dp(18), Color.TRANSPARENT)
                elevation = dp(1).toFloat()
                addView(appIcon(rule.packageName), LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(vertical().apply {
                    addView(label(labelForPackage(rule.packageName), 15f, COLOR_TEXT, Typeface.BOLD))
                    addView(label(
                        when {
                            !userApp -> "应用已卸载 · 点击删除"
                            rule.threadRules.isEmpty() -> "整包自定义 · ${rule.preset.cpuSet}"
                            else -> "8 Gen 3 优化 · ${rule.totalRules} 条规则"
                        },
                        11f,
                        COLOR_SUBTLE,
                        Typeface.NORMAL,
                    ))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(12), 0, dp(12), 0)
                })
                addView(rateBadge(if (rule.threadRules.isEmpty()) "整包" else "优化"),
                    LinearLayout.LayoutParams(dp(70), dp(38)))
                setOnClickListener {
                    if (userApp) showAppOptPresetDialog(rule.packageName, rule.preset)
                    else confirmRemoveAppOptRule(rule)
                }
            }
            appOptRulesHost.addView(card, cardMargins())
        }
    }

    private fun showAppOptPresetDialog(
        pkg: String,
        current: AppOptPreset? = appOptRules[pkg]?.preset,
    ) {
        if (!isInstalledUserApp(pkg)) {
            toast("AppOpt 只允许已安装用户 App")
            return
        }
        val template = appOptTemplates[pkg]
        val modes = if (template == null) listOf("整包自定义") else listOf("8 Gen 3 优化", "整包自定义")
        val modePicker = traySpinner(modes) {}
        val summary = compactNote("")
        val selectedCores = linkedSetOf<Int>()
        (current ?: AppOptPreset.GAME_BACKGROUND).cpuSet.split('-')
            .mapNotNull { it.toIntOrNull() }
            .take(2)
            .forEach(selectedCores::add)
        val coreButtons = mutableListOf<TextView>()
        val coreSummary = compactNote("")
        val coreControls = vertical().apply {
            addView(fieldTitle("核心选项"))
            addView(horizontalRow().apply {
                background = null
                elevation = 0f
                setPadding(0, 0, 0, 0)
                (0..7).forEach { core ->
                    val button = chip(core.toString()).apply {
                        contentDescription = "CPU$core"
                        setOnClickListener {
                            if (!selectedCores.remove(core)) {
                                if (selectedCores.size == 2) selectedCores.clear()
                                selectedCores += core
                            }
                            updateCoreRangeButtons(coreButtons, selectedCores, coreSummary)
                        }
                    }
                    coreButtons += button
                    addView(button, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                        if (core > 0) setMargins(dp(5), 0, 0, 0)
                    })
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, dp(8), 0, 0) })
            addView(coreSummary, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, dp(8), 0, 0) })
        }
        updateCoreRangeButtons(coreButtons, selectedCores, coreSummary)
        fun updateMode(position: Int) {
            val useTemplate = template != null && position == 0
            coreControls.visibility = if (useTemplate) View.GONE else View.VISIBLE
            summary.visibility = if (useTemplate) View.VISIBLE else View.GONE
            summary.text = if (useTemplate) {
                val preview = template!!.threadRules.take(4).joinToString(" · ") {
                    "${it.pattern}→${it.preset.cpuSet}"
                }
                "整包兜底 ${template.preset.cpuSet} · $preview" +
                    if (template.threadRules.size > 4) " · 另 ${template.threadRules.size - 4} 条" else ""
            } else {
                ""
            }
        }
        modePicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateMode(position)
            }
        }
        val currentRule = appOptRules[pkg]
        modePicker.setSelection(if (template != null && currentRule?.threadRules?.isNotEmpty() == true) 0 else modes.lastIndex)
        updateMode(modePicker.selectedItemPosition)
        val content = vertical().apply {
            setPadding(dp(20), dp(6), dp(20), 0)
            addView(appIdentity(pkg))
            addView(fieldTitle("放置方式"), fieldMargins())
            addView(modePicker, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { setMargins(0, dp(6), 0, 0) })
            addView(summary, fieldMargins())
            addView(coreControls, fieldMargins())
        }
        AlertDialog.Builder(this)
            .setTitle(if (currentRule == null) "添加 AppOpt" else "编辑 AppOpt")
            .setView(content)
            .setPositiveButton("应用") { _, _ ->
                if (template != null && modePicker.selectedItemPosition == 0) {
                    val rules = LinkedHashMap(appOptRules)
                    rules[pkg] = template
                    applyAppOptRules(rules, "正在应用线程模板", "线程模板已应用")
                } else {
                    val endpoints = selectedCores.sorted()
                    val preset = endpoints.firstOrNull()?.let { first ->
                        AppOptPreset.fromEndpoints(first, endpoints.getOrElse(1) { first })
                    }
                    if (preset == null) toast("请至少选择一个核心") else setAppOptRule(pkg, preset)
                }
            }
            .apply {
                if (currentRule != null) {
                    setNeutralButton("删除") { _, _ -> confirmRemoveAppOptRule(currentRule) }
                }
            }
            .setNegativeButton("取消", null)
            .showStyled()
    }

    private fun setAppOptRule(pkg: String, preset: AppOptPreset) {
        if (appOptRules[pkg] == AppOptRule(pkg, preset)) {
            toast("配置没有变化，无需重新应用")
            return
        }
        sendCommand("正在保存 AppOpt 规则", onTerminal = { ack ->
            if (ack.succeeded) {
                syncAppOptConfig()
                toast(targetResultText(ack.detail, "App"))
            }
        }) {
            ZuiControlRequest.send(
                this,
                ZuiControlContract.CMD_SET_APPOPT_RULE,
                pkg = pkg,
                mode = preset.cpuSet,
            )
        }
    }

    private fun confirmRemoveAppOptRule(rule: AppOptRule) {
        AlertDialog.Builder(this)
            .setTitle("删除 AppOpt 规则")
            .setMessage("${labelForPackage(rule.packageName)}\n若目标 App 正在运行，删除成功后会自动关闭，使旧 affinity 不再残留。")
            .setPositiveButton("删除") { _, _ ->
                sendCommand("正在删除 AppOpt 规则", onTerminal = { ack ->
                    if (ack.succeeded) {
                        syncAppOptConfig()
                        toast(targetResultText(ack.detail, "App"))
                    }
                }) {
                    ZuiControlRequest.send(
                        this,
                        ZuiControlContract.CMD_REMOVE_APPOPT_RULE,
                        pkg = rule.packageName,
                    )
                }
            }
            .setNegativeButton("取消", null)
            .showStyled()
    }

    private fun confirmStopAppOpt() {
        AlertDialog.Builder(this)
            .setTitle("停止 AppOpt")
            .setMessage("停止不会删除规则。成功后会自动关闭当前正在运行的受管 App，使旧 affinity 不再残留；重新保存任一规则可再次启动 AppOpt。")
            .setPositiveButton("停止") { _, _ ->
                sendCommand("正在停止 AppOpt", onTerminal = { ack ->
                    if (ack.succeeded) toast(appOptStopResultText(ack.detail))
                }) {
                    ZuiControlRequest.send(this, ZuiControlContract.CMD_STOP_APPOPT)
                }
            }
            .setNegativeButton("取消", null)
            .showStyled()
    }

    private fun importAppOptConfig() {
        Thread {
            val result = runCatching { validateAppOptText(AppOptConfig.read(this)) }
            handler.post {
                val rules = result.getOrElse {
                    toast(it.message ?: "共享配置读取失败")
                    return@post
                }
                AlertDialog.Builder(this)
                    .setTitle("导入 AppOpt 配置")
                    .setMessage(
                        "将用共享文件中的 ${rules.size} 个 App、" +
                            "${AppOptConfig.totalRuleCount(rules)} 条规则替换当前列表。" +
                            "成功后会自动关闭正在运行的受影响 App。",
                    )
                    .setPositiveButton("导入并应用") { _, _ ->
                        applyAppOptRules(rules, "正在导入 AppOpt 配置", "配置已导入")
                    }
                    .setNegativeButton("取消", null)
                    .showStyled()
            }
        }.start()
    }

    private fun validateAppOptText(text: String): LinkedHashMap<String, AppOptRule> {
        val rules = AppOptConfig.parse(text)
        val invalid = rules.keys.firstOrNull { !isInstalledUserApp(it) }
        require(invalid == null) { "$invalid 不是已安装用户 App" }
        return rules
    }

    private fun applyAppOptRules(
        rules: Map<String, AppOptRule>,
        message: String,
        successText: String,
    ) {
        if (rules == appOptRules) {
            toast("配置没有变化，无需重新应用")
            return
        }
        sendCommand(message, onTerminal = { ack ->
            if (ack.succeeded) {
                syncAppOptConfig()
                toast(appOptStopResultText(ack.detail, successText))
            }
        }) {
            ZuiControlRequest.send(
                this,
                ZuiControlContract.CMD_REPLACE_APPOPT_RULES,
                appOptPayload = AppOptConfig.payload(rules),
            )
        }
    }

    private fun syncAppOptConfig(showToast: Boolean = false) {
        val snapshot = LinkedHashMap(appOptRules)
        Thread {
            val result = runCatching { AppOptConfig.write(this, snapshot) }
            if (showToast || result.isFailure) {
                handler.post {
                    if (result.isSuccess) {
                        toast("配置已写入 ${AppOptConfig.DISPLAY_PATH}")
                    } else {
                        toast(result.exceptionOrNull()?.message ?: "共享配置写入失败")
                    }
                }
            }
        }.start()
    }

    private fun targetResultText(detail: String, targetName: String): String {
        val gameText = when {
            detail.contains("game=user_added") -> "，已同步加入游戏助手"
            detail.contains("game=user_removed") -> "，已同步移出游戏助手自定义列表"
            detail.contains("game=already_absent") -> "，游戏助手自定义条目已不存在"
            detail.contains("game=system_existing") -> "，系统内置游戏识别已保留"
            detail.contains("game=") -> "，游戏助手已识别"
            else -> ""
        }
        return when {
            detail.contains("target=stopped") -> "操作成功${gameText}，已自动关闭目标${targetName}"
            detail.contains("target=not_running") -> "操作成功${gameText}，目标${targetName}未运行，下次打开时生效"
            detail.contains("target=stop_failed") -> "配置已保存${gameText}，但自动关闭失败，请手动强停一次"
            else -> "操作成功${gameText}"
        }
    }

    private fun appOptStopResultText(detail: String, prefix: String = "AppOpt 已停止"): String {
        val stopped = Regex("stoppedApps=(\\d+)").find(detail)?.groupValues?.get(1) ?: "0"
        val failed = Regex("stopFailed=(\\d+)").find(detail)?.groupValues?.get(1) ?: "0"
        return if (failed == "0") "${prefix}；已自动关闭 $stopped 个运行中 App"
        else "${prefix}；关闭 $stopped 个 App，另有 $failed 个需手动强停"
    }

    private fun isInstalledUserApp(pkg: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        val info = packageManager.getApplicationInfo(pkg, 0)
        info.flags and ApplicationInfo.FLAG_SYSTEM == 0 &&
            info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
    }.getOrDefault(false)

    private fun conciseXmlState(): String {
        val xml = setting(ZuiControlContract.KEY_XML_STATE)
        return when {
            xml.startsWith("state=mounted") -> "XML 正常"
            hasP2StateError(xml, "") -> "XML 异常"
            xml.isBlank() -> "XML 未启用"
            else -> "XML 检查中"
        }
    }

    private fun conciseZuippReloadState(): String {
        val reload = setting(ZuiControlContract.KEY_ZUIPP_RELOAD_STATE)
        return when {
            reload.isBlank() -> "ZuiPP 等待重载"
            hasErrorLine(reload) -> "ZuiPP 重载异常"
            reload.contains("state=done") -> "ZuiPP 已重载"
            reload.contains("state=skipped") -> "ZuiPP 无需重载"
            else -> "ZuiPP 重载检查中"
        }
    }

    private fun hasErrorLine(value: String): Boolean =
        value.lineSequence().any {
            it.contains("failed", ignoreCase = true) ||
                it.contains("error", ignoreCase = true)
        }

    private fun exportLogs() {
        sendCommand("正在整理日志", onTerminal = { ack ->
            if (ack.succeeded) {
                openExportDocument()
            }
        }) {
            ZuiControlRequest.send(this, ZuiControlContract.CMD_EXPORT_LOGS)
        }
    }

    private fun openExportDocument() {
        pendingExportText = setting(ZuiControlContract.KEY_LOG_EXPORT)
        if (pendingExportText.isBlank()) {
            toast("日志尚未准备好")
            return
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
        userAppsOnly: Boolean = false,
        launchableOnly: Boolean = false,
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
        tabs.addView(userTab, LinearLayout.LayoutParams(0, dp(44), 1f))
        if (!userAppsOnly) {
            tabs.addView(systemTab, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                setMargins(dp(8), 0, 0, 0)
            })
        }
        root.addView(tabs)

        val search = EditText(this).apply {
            hint = "搜索应用或包名"
            setSingleLine(true)
            textSize = 14f
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(COLOR_FIELD, dp(22), Color.TRANSPARENT)
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
            .createStyled()
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
                    renderPerformanceProfiles()
                } else {
                    onSelected.invoke(it)
                }
            }
        }
        fun selectSystem(system: Boolean) {
            if (userAppsOnly && system) {
                return
            }
            adapter.systemApps = system
            adapter.applyFilter(search.text.toString())
            styleChip(userTab, !system)
            styleChip(systemTab, system)
        }
        userTab.setOnClickListener { selectSystem(false) }
        if (!userAppsOnly) {
            systemTab.setOnClickListener { selectSystem(true) }
        }
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
                .filter { !userAppsOnly || !it.system }
                .filter {
                    !launchableOnly || packageManager.getLaunchIntentForPackage(
                        it.info.packageName,
                    ) != null
                }
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
        appOptRules.clear()
        appOptRules.putAll(AppOptRules.parse(
            setting(ZuiControlContract.KEY_APPOPT_RULES_TEXT),
        ))
        performanceProfiles.clear()
        val parsed = setting(ZuiControlContract.KEY_PERFORMANCE_PROFILES_TEXT).lineSequence()
            .mapNotNull { PerformanceProfile.parse(it) }
            .toList()
        parsed.groupBy { it.packageName }.forEach { (_, profiles) ->
            val selected = profiles.firstOrNull { it.mode == PerformanceMode.BALANCED }
                ?: profiles.first()
            val canonical = selected.copy(mode = PerformanceMode.BALANCED)
            performanceProfiles[canonical.key] = canonical
        }
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
        refreshNotification: Boolean = false,
        onTerminal: ((ZuiControlRequest.Ack) -> Unit)? = null,
        block: () -> String,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (commandInFlight || now - lastCommandAt < 180) {
            if (message != null) toast("操作处理中")
            return
        }
        commandInFlight = true
        lastCommandAt = now
        val progressUi = message?.let { commandProgressDialog(it) }
        progressUi?.first?.show()
        Thread {
            val result = runCatching {
                val requestId = block()
                ZuiControlRequest.awaitTerminalAck(this, requestId) { ack ->
                    handler.post {
                        if (progressUi?.first?.isShowing == true) {
                            progressUi.second.text = ZuiControlRequest.progressLabel(ack.detail)
                        }
                    }
                }
            }
            handler.post {
                commandInFlight = false
                runCatching { progressUi?.first?.dismiss() }
                val ack = result.getOrNull()
                when {
                    result.isFailure && message != null ->
                        toast(result.exceptionOrNull()?.message ?: "命令发送失败")
                    ack != null && !ack.succeeded ->
                        toast("操作失败${ack.detail.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}")
                    message != null && onTerminal == null -> toast("操作完成")
                }
                reloadState()
                renderCurrentPage()
                if (ack?.succeeded == true && refreshNotification && !BuildConfig.DEBUG) {
                    ZuiControlQuickService.start(this)
                }
                if (ack != null) {
                    onTerminal?.invoke(ack)
                }
            }
        }.start()
    }

    private fun commandProgressDialog(title: String): Pair<AlertDialog, TextView> {
        val progressText = label(
            "正在等待系统接收请求",
            14f,
            COLOR_TEXT,
            Typeface.NORMAL,
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(12))
            addView(ProgressBar(this@MainActivity).apply { isIndeterminate = true },
                LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(progressText, LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { setMargins(dp(16), 0, 0, 0) })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(row)
            .setCancelable(false)
            .createStyled()
        dialog.setCanceledOnTouchOutside(false)
        return dialog to progressText
    }

    private fun AlertDialog.Builder.showStyled(): AlertDialog =
        createStyled().also(AlertDialog::show)

    private fun AlertDialog.Builder.createStyled(): AlertDialog = create().apply {
        setOnShowListener { styleDialog(this) }
    }

    private fun styleDialog(dialog: AlertDialog) {
        val window = dialog.window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount = 0.34f }
        window.decorView.apply {
            background = rounded(COLOR_SURFACE, dp(30), Color.TRANSPARENT)
            clipToOutline = true
            elevation = dp(8).toFloat()
        }
        val width = (resources.displayMetrics.widthPixels - dp(32)).coerceAtMost(dp(520))
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 13f
            includeFontPadding = false
            background = rounded(COLOR_ACCENT, dp(19), COLOR_ACCENT)
            setPadding(dp(14), 0, dp(14), 0)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            layoutParams = layoutParams.apply {
                this.width = dp(78)
                this.height = dp(38)
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
            isAllCaps = false
            setTextColor(COLOR_ACCENT)
            background = ColorDrawable(Color.TRANSPARENT)
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
            isAllCaps = false
            setTextColor(COLOR_DANGER)
            background = ColorDrawable(Color.TRANSPARENT)
        }
        val titleId = resources.getIdentifier("alertTitle", "id", "android")
        dialog.findViewById<TextView>(titleId)?.apply {
            setTextColor(COLOR_TEXT)
            textSize = 20f
        }
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            setTextColor(COLOR_SUBTLE)
            textSize = 14f
            setLineSpacing(dp(3).toFloat(), 1f)
        }
    }

    private fun ensureSelectedPerformanceProfile() {
        if (selectedPackage != null || performanceProfiles.isEmpty()) {
            return
        }
        val profile = performanceProfiles.values.first()
        selectedPackage = profile.packageName
    }

    private fun selectedGamePolicy(): GamePolicyMode {
        val position = if (::policySpinner.isInitialized) policySpinner.selectedItemPosition else 0
        return GamePolicyMode.entries.getOrElse(position) { GamePolicyMode.INDEPENDENT }
    }

    private fun selectedFramePolicy(): FramePolicy {
        val position = if (::framePolicySpinner.isInitialized) framePolicySpinner.selectedItemPosition else 0
        return FramePolicy.entries.getOrElse(position) { FramePolicy.DEFAULT }
    }

    private fun setPolicySelection(policy: GamePolicyMode) {
        if (::policySpinner.isInitialized && policySpinner.selectedItemPosition != policy.ordinal) {
            policySpinner.setSelection(policy.ordinal)
        }
    }

    private fun setFramePolicySelection(policy: FramePolicy) {
        if (::framePolicySpinner.isInitialized && framePolicySpinner.selectedItemPosition != policy.ordinal) {
            framePolicySpinner.setSelection(policy.ordinal)
        }
    }

    private fun updatePolicySummary() {
        if (!::policySummary.isInitialized) {
            return
        }
        val pkg = selectedPackage
        if (pkg == null) {
            policySummary.text = "未选择应用"
            return
        }
        val framePolicy = selectedFramePolicy()
        if (::framePolicySpinner.isInitialized) {
            framePolicySpinner.isEnabled = selectedGamePolicy() == GamePolicyMode.INDEPENDENT
            framePolicySpinner.alpha = if (framePolicySpinner.isEnabled) 1f else 0.55f
        }
        policySummary.text = when (selectedGamePolicy()) {
            GamePolicyMode.INDEPENDENT ->
                when (framePolicy) {
                    FramePolicy.DEFAULT -> "均衡/野兽 120Hz · 节能 60Hz"
                    FramePolicy.FIXED_60 -> "均衡/节能/野兽均为 60Hz"
                    FramePolicy.FOLLOW_DISPLAY -> {
                        val refreshHz = refreshTargetFor(pkg)
                        "均衡/野兽跟随 ${refreshHz}Hz · 节能 ${powerSaveRefreshFor(refreshHz)}Hz"
                    }
                }
            GamePolicyMode.DEFAULT ->
                "使用系统默认游戏条目"
        }
    }

    private fun frameSummary(pkg: String, policy: FramePolicy): String {
        return when (policy) {
            FramePolicy.DEFAULT -> "120 / 60Hz"
            FramePolicy.FIXED_60 -> "60Hz"
            FramePolicy.FOLLOW_DISPLAY -> {
                val refreshHz = refreshTargetFor(pkg)
                "跟随 ${refreshHz}Hz"
            }
        }
    }

    private fun defaultFramePolicyFor(pkg: String): FramePolicy =
        if (refreshRules.containsKey(pkg)) FramePolicy.FOLLOW_DISPLAY else FramePolicy.DEFAULT

    private fun refreshTargetFor(pkg: String): Int =
        refreshRules[pkg] ?: RefreshSceneController.BASE_REFRESH_RATE

    private fun powerSaveRefreshFor(refreshHz: Int): Int =
        if (refreshHz <= 60) refreshHz else 60

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
            .showStyled()
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

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun String.cleanSetting(): String = removeSuffix(".0")

    private fun vertical() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun horizontalRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = rounded(Color.WHITE, dp(18), Color.TRANSPARENT)
        elevation = dp(1).toFloat()
    }

    private fun panel() = LinearLayout(this).apply {
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(COLOR_SURFACE, dp(24), Color.TRANSPARENT)
        elevation = 0f
    }

    private fun sectionTitle(text: String) = label(text, 18f, COLOR_TEXT, Typeface.BOLD)

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
        background = rounded(COLOR_FIELD, dp(22), Color.TRANSPARENT)
    }

    private fun helpButton(action: () -> Unit) =
        label("?", 13f, COLOR_ACCENT, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            contentDescription = "查看支持频率"
            background = rounded(COLOR_FIELD, dp(15), Color.TRANSPARENT)
            setOnClickListener { action() }
        }

    private fun infoPanel() = label("", 13f, COLOR_TEXT, Typeface.NORMAL).apply {
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(Color.WHITE, dp(18), Color.TRANSPARENT)
        elevation = dp(1).toFloat()
    }

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

    private fun primaryButton(text: String, action: () -> Unit) =
        label(text, 13f, Color.WHITE, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = rounded(COLOR_ACCENT, dp(22), COLOR_ACCENT)
            setOnClickListener { action() }
        }

    private fun dangerButton(text: String, action: () -> Unit) =
        label(text, 13f, COLOR_DANGER, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = rounded(COLOR_FIELD, dp(22), Color.TRANSPARENT)
            setOnClickListener { action() }
        }

    private fun iconButton(iconRes: Int, description: String, action: () -> Unit) =
        ImageView(this).apply {
            contentDescription = description
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(COLOR_SUBTLE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(COLOR_FIELD, dp(22), Color.TRANSPARENT)
            setOnClickListener { action() }
        }

    private fun floatingButton(text: String, action: () -> Unit) =
        label(text, 28f, Color.WHITE, Typeface.NORMAL).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            contentDescription = "添加"
            background = rounded(COLOR_ACCENT, dp(27), COLOR_ACCENT)
            elevation = 0f
            setOnClickListener { action() }
        }

    private fun traySpinner(items: List<String>, onSelected: (Int) -> Unit) =
        Spinner(this).apply {
            adapter = SimpleTextAdapter(items)
            background = rounded(COLOR_FIELD, dp(22), Color.TRANSPARENT)
            setPopupBackgroundDrawable(rounded(COLOR_SURFACE, dp(24), Color.TRANSPARENT))
            dropDownVerticalOffset = dp(4)
            setPadding(dp(6), 0, dp(6), 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    onSelected(position)
                }
            }
        }

    private fun appIcon(pkg: String) = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setImageDrawable(runCatching {
            packageManager.getApplicationInfo(pkg, 0).loadIcon(packageManager)
        }.getOrElse { getDrawable(android.R.drawable.sym_def_app_icon) })
        contentDescription = labelForPackage(pkg)
    }

    private fun appIdentity(pkg: String) = horizontalRow().apply {
        background = null
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
            background = rounded(COLOR_FIELD, dp(18), Color.TRANSPARENT)
            elevation = 0f
            minimumHeight = dp(68)
            addView(ImageView(this@MainActivity).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(COLOR_SUBTLE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = rounded(COLOR_SURFACE, dp(18), Color.TRANSPARENT)
            }, LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                setMargins(0, 0, dp(12), 0)
            })
            addView(vertical().apply {
                addView(label(title, 14f, COLOR_TEXT, Typeface.BOLD))
                addView(label(subtitle, 11f, COLOR_SUBTLE, Typeface.NORMAL))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label("›", 22f, COLOR_SUBTLE, Typeface.NORMAL),
                LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_VERTICAL
                })
            setOnClickListener { action() }
        }

    private fun chip(text: String) = label(text, 13f, COLOR_TEXT, Typeface.BOLD).apply {
        gravity = Gravity.CENTER
        background = rounded(COLOR_FIELD, dp(22), Color.TRANSPARENT)
    }

    private fun styleChip(view: TextView, selected: Boolean) {
        view.setTextColor(if (selected) Color.WHITE else COLOR_TEXT)
        view.background = rounded(
            if (selected) COLOR_ACCENT else COLOR_FIELD,
            dp(22),
            if (selected) COLOR_ACCENT else Color.TRANSPARENT,
        )
    }

    private fun updateCoreRangeButtons(
        buttons: List<TextView>,
        selectedCores: Set<Int>,
        summary: TextView,
    ) {
        buttons.forEachIndexed { core, button -> styleChip(button, core in selectedCores) }
        val endpoints = selectedCores.sorted()
        summary.text = when (endpoints.size) {
            0 -> "至少选择一个核心"
            1 -> "使用 CPU${endpoints[0]}"
            else -> "连续范围 CPU${endpoints[0]}–CPU${endpoints[1]}（包含中间核心）"
        }
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

    private fun settingsActionMargins(spaced: Boolean = false) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(68),
    ).apply {
        setMargins(0, if (spaced) dp(8) else 0, 0, 0)
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
            val icon = row.getChildAt(0) as ImageView
            val text = row.getChildAt(1) as LinearLayout
            val title = text.getChildAt(0) as TextView
            val pkg = text.getChildAt(1) as TextView
            icon.setImageDrawable(entry.info.loadIcon(packageManager))
            title.text = entry.label()
            pkg.text = entry.info.packageName
            labelCache[entry.info.packageName] = title.text.toString()
            row.background = rounded(
                if (position % 2 == 0) COLOR_FIELD else Color.TRANSPARENT,
                dp(18),
                Color.TRANSPARENT,
            )
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
                setPadding(dp(16), 0, dp(16), 0)
                background = rounded(COLOR_FIELD, dp(22), Color.TRANSPARENT)
            }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup?): View =
            label(items[position], 14f, COLOR_TEXT, Typeface.NORMAL).apply {
                minHeight = dp(48)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(12), dp(18), dp(12))
                background = ColorDrawable(Color.TRANSPARENT)
            }
    }

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

    private enum class Page(val title: String, val iconRes: Int) {
        REFRESH("刷新率", R.drawable.ic_nav_display_rate),
        PERFORMANCE("性能", R.drawable.ic_nav_performance),
        APPOPT("AppOpt", R.drawable.ic_nav_appopt),
        SYSTEM("系统", R.drawable.ic_nav_system),
    }

    companion object {
        private const val REQUEST_EXPORT_LOG = 901
        private const val STATE_PAGE = "page"
        private const val STATE_PERFORMANCE_EDITOR = "performance_editor"
        private const val STATE_PERFORMANCE_PACKAGE = "performance_package"
        private val COLOR_BG = Color.rgb(248, 247, 252)
        private val COLOR_SURFACE = Color.rgb(250, 249, 253)
        private val COLOR_FIELD = Color.rgb(240, 241, 247)
        private val COLOR_NOTE = Color.rgb(245, 247, 241)
        private val COLOR_SELECTED = Color.rgb(224, 230, 248)
        private val COLOR_TEXT = Color.rgb(34, 35, 42)
        private val COLOR_SUBTLE = Color.rgb(87, 89, 99)
        private val COLOR_ACCENT = Color.rgb(68, 91, 139)
        private val COLOR_DANGER = Color.rgb(168, 62, 67)
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
