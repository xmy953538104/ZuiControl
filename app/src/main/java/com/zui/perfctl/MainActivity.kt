package com.zui.perfctl

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
        reloadState()
        setContentView(buildRoot())
        showPage(Page.REFRESH)
        handler.postDelayed({ PerfCtlQuickService.start(this) }, 250)
        sendCommand(null, refreshNotification = true) {
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
                addView(label("ZuiperfCtl", 23f, COLOR_TEXT, Typeface.BOLD))
                headerStatus = label(headerStatusText(), 12f, COLOR_SUBTLE, Typeface.NORMAL)
                addView(headerStatus)
            }
            addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(commandButton("刷新") {
                sendCommand("正在刷新", refreshNotification = true) {
                    PerfCtlRequest.send(this@MainActivity, PerfCtlContract.CMD_STATUS)
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
        val peak = setting(PerfCtlContract.KEY_ACTIVE_REFRESH)
            .ifBlank { setting("peak_refresh_rate").cleanSetting() }
            .ifBlank { "120" }
        val last = setting(PerfCtlContract.KEY_STATUS_LAST).ifBlank { "init" }
        return "v$APP_VERSION_NAME · ${peak}Hz · ${commandName(last)}"
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
        root.addView(sectionTitle("已学习规则"), sectionMargins())

        refreshRulesHost = vertical()
        root.addView(refreshRulesHost, matchWrap())

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(commandButton("恢复全局 120Hz") {
                sendCommand("正在恢复 120Hz", refreshNotification = true) {
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
        val active = setting(PerfCtlContract.KEY_ACTIVE_REFRESH).ifBlank { peak }
        refreshStatus.text = "当前 ${active}Hz · peak ${peak}Hz · min ${min}Hz\n基线 120Hz"

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
                sendCommand("正在移除规则", refreshNotification = true) {
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
                        loadSelectedProfile()
                    }
                }
            }
            addView(modeSpinner, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48),
            ))
            addView(compactNote("模式对应 game_policy.xml 的三段 LimitConfig；生成时只替换当前模式的常用温区，最后一个高温保护段保留官方策略。"),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    setMargins(0, dp(8), 0, 0)
                })

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

            performanceSummary = infoPanel()
            addView(performanceSummary, fieldMargins())

            addView(sectionTitle("操作"), fieldMargins())
            addView(actionPair(
                primaryButton("保存模式") { savePerformanceProfile() },
                commandButton("删除模式") { removePerformanceProfile() },
            ), buttonMargins())
            addView(actionPair(
                primaryButton("生成并应用调度") {
                    sendCommand("正在生成并应用", settleDelayMs = LONG_COMMAND_DELAY_MS) {
                        PerfCtlRequest.send(
                            this@MainActivity,
                            PerfCtlContract.CMD_APPLY_PERFORMANCE,
                        )
                    }
                },
                commandButton("恢复官方调度") {
                    sendCommand("正在恢复官方调度", settleDelayMs = LONG_COMMAND_DELAY_MS) {
                        PerfCtlRequest.send(
                            this@MainActivity,
                            PerfCtlContract.CMD_RESTORE_ZUIPP,
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
        selectedPackageView.text = pkg?.let { "$it · ${selectedMode().title}" } ?: "未选择应用"
        loadSelectedProfile()
        val xmlState = setting(PerfCtlContract.KEY_XML_STATE).ifBlank { "未挂载，当前使用官方 XML" }
        val summary = setting(PerfCtlContract.KEY_PERFORMANCE_SUMMARY)
            .ifBlank { "工作 XML 尚未生成；保存后点“生成并应用调度”才会改写 ZuiPP 工作 XML。" }
        performanceSummary.text = "$xmlState\n$summary"
    }

    private fun loadSelectedProfile() {
        if (!::littleMaxInput.isInitialized) {
            return
        }
        val pkg = selectedPackage ?: return
        val profile = performanceProfiles["$pkg|${selectedMode().id}"]
        if (profile == null) {
            littleMaxInput.setText(formatFreq(LITTLE_FREQS.last()))
            littleMinInput.setText(formatFreq(LITTLE_FREQS.first()))
            bigMaxInput.setText(formatFreq(BIG_FREQS.last()))
            bigMinInput.setText(formatFreq(BIG_FREQS.first()))
            titanMaxInput.setText(formatFreq(TITAN_FREQS.last()))
            titanMinInput.setText(formatFreq(TITAN_FREQS.first()))
            megaMaxInput.setText(formatFreq(MEGA_FREQS.last()))
            megaMinInput.setText(formatFreq(MEGA_FREQS.first()))
            gpuMaxInput.setText(formatFreq(GPU_FREQS.first()))
            gpuMinInput.setText(formatFreq(GPU_FREQS.last()))
        } else {
            littleMaxInput.setText(formatFreq(profile.littleMaxKHz))
            littleMinInput.setText(formatFreq(profile.littleMinKHz))
            bigMaxInput.setText(formatFreq(profile.bigMaxKHz))
            bigMinInput.setText(formatFreq(profile.bigMinKHz))
            titanMaxInput.setText(formatFreq(profile.titanMaxKHz))
            titanMinInput.setText(formatFreq(profile.titanMinKHz))
            megaMaxInput.setText(formatFreq(profile.megaMaxKHz))
            megaMinInput.setText(formatFreq(profile.megaMinKHz))
            gpuMaxInput.setText(formatFreq(profile.gpuMaxKHz))
            gpuMinInput.setText(formatFreq(profile.gpuMinKHz))
        }
        renderPerformanceProfiles()
    }

    private fun savePerformanceProfile() {
        val pkg = selectedPackage ?: return toast("请先添加应用")
        val littleMax = parseFreq(littleMaxInput.text.toString(), LITTLE_FREQS, preferHigh = true)
            ?: return toast("Little 上限不在可用档位")
        val littleMin = parseFreq(littleMinInput.text.toString(), LITTLE_FREQS, preferHigh = false)
            ?: return toast("Little 下限不在可用档位")
        val bigMax = parseFreq(bigMaxInput.text.toString(), BIG_FREQS, preferHigh = true)
            ?: return toast("Big 上限不在可用档位")
        val bigMin = parseFreq(bigMinInput.text.toString(), BIG_FREQS, preferHigh = false)
            ?: return toast("Big 下限不在可用档位")
        val titanMax = parseFreq(titanMaxInput.text.toString(), TITAN_FREQS, preferHigh = true)
            ?: return toast("Titan 上限不在可用档位")
        val titanMin = parseFreq(titanMinInput.text.toString(), TITAN_FREQS, preferHigh = false)
            ?: return toast("Titan 下限不在可用档位")
        val megaMax = parseFreq(megaMaxInput.text.toString(), MEGA_FREQS, preferHigh = true)
            ?: return toast("Mega 上限不在可用档位")
        val megaMin = parseFreq(megaMinInput.text.toString(), MEGA_FREQS, preferHigh = false)
            ?: return toast("Mega 下限不在可用档位")
        val gpuMax = parseFreq(gpuMaxInput.text.toString(), GPU_FREQS, preferHigh = true)
            ?: return toast("GPU 上限不在可用档位")
        val gpuMin = parseFreq(gpuMinInput.text.toString(), GPU_FREQS, preferHigh = false)
            ?: return toast("GPU 下限不在可用档位")
        if (littleMax < littleMin || bigMax < bigMin || titanMax < titanMin ||
            megaMax < megaMin || gpuMax < gpuMin) {
            return toast("上限不能低于下限")
        }
        val profile = PerformanceProfile(
            pkg,
            selectedMode(),
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
        performanceProfiles[profile.key] = profile
        renderPerformanceProfiles()
        sendCommand("正在保存性能配置", settleDelayMs = LONG_COMMAND_DELAY_MS) {
            PerfCtlRequest.send(
                this,
                PerfCtlContract.CMD_SET_PERFORMANCE_PROFILE,
                pkg = pkg,
                mode = profile.mode.id,
                littleMax = littleMax,
                littleMin = littleMin,
                bigMax = bigMax,
                bigMin = bigMin,
                titanMax = titanMax,
                titanMin = titanMin,
                megaMax = megaMax,
                megaMin = megaMin,
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
                sendCommand("正在刷新", refreshNotification = true) {
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
        val gpu = setting(PerfCtlContract.KEY_GPU_STATE).ifBlank { "KGSL 等待回读" }
        systemStatus.text = "守护服务 运行中\nXML $xml\nGPU $gpu\n最近操作 ${commandName(last)}\n$status"
        asoulStatus.text = setting(PerfCtlContract.KEY_ASOUL_HEALTH)
            .ifBlank { "正在读取 AsoulOpt 状态" }
    }

    private fun exportLogs() {
        sendCommand("正在整理日志", settleDelayMs = EXPORT_COMMAND_DELAY_MS) {
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
                        PerfCtlQuickService.start(this)
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
        if (normalized.isBlank()) {
            return null
        }
        available.firstOrNull { it.toString() == normalized }?.let { return it }
        val requestedGhz = normalized.toDoubleOrNull() ?: return null
        val exactKHz = Math.round(requestedGhz * 1_000_000.0).toInt()
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

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun String.cleanSetting(): String = removeSuffix(".0")

    private fun commandName(value: String): String = when (value) {
        PerfCtlContract.CMD_LEARN_REFRESH -> "记忆刷新率"
        "learn_refresh_no_target" -> "未找到前台场景"
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

    private enum class Page(val title: String) {
        REFRESH("刷新率"),
        PERFORMANCE("性能调度"),
        SYSTEM("系统状态"),
    }

    companion object {
        private const val REQUEST_EXPORT_LOG = 901
        private const val APP_VERSION_NAME = "0.14.0"
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
