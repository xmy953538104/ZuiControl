package com.zui.perfctl

import android.Manifest
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.Collator
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var appAdapter: AppAdapter
    private lateinit var statusView: TextView
    private lateinit var selectedTitle: TextView
    private lateinit var selectedPackage: TextView
    private lateinit var ruleSummary: TextView
    private lateinit var refreshCheck: CheckBox
    private lateinit var zuippCheck: CheckBox
    private lateinit var asoulCheck: CheckBox
    private lateinit var rateButtons: Map<Int, TextView>
    private lateinit var filterButtons: Map<AppFilter, TextView>
    private lateinit var listHint: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val apps = mutableListOf<InstalledApp>()
    private val visibleApps = mutableListOf<InstalledApp>()
    private val profiles = linkedMapOf<String, AppProfile>()

    private var selectedPackageName: String? = null
    private var selectedRate = 120
    private var changingDetail = false
    private var currentFilter = AppFilter.USER
    private var currentKeyword = ""
    private var appsLoaded = false
    private var commandInFlight = false
    private var lastCommandAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        reloadProfiles()
        setContentView(buildContent())
        refreshStatus(sendDaemonStatus = true)
        loadAppsAsync()
        handler.postDelayed({ PerfCtlQuickService.start(this) }, 350)
    }

    private fun buildContent(): View {
        val tablet = resources.configuration.screenWidthDp >= 700
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG)
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }

        root.addView(header(), matchWrap())

        val content = LinearLayout(this).apply {
            orientation = if (tablet) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }
        root.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        val listPanel = panel().apply {
            orientation = LinearLayout.VERTICAL
            addView(searchBox(), matchWrap())
            addView(appList(), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (tablet) 0 else dp(360),
                if (tablet) 1f else 0f,
            ))
        }
        val detailPanel = panel().apply {
            orientation = LinearLayout.VERTICAL
            addView(detailContent(), matchWrap())
        }

        if (tablet) {
            content.addView(listPanel, LinearLayout.LayoutParams(dp(380), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                setMargins(0, 0, dp(12), 0)
            })
            content.addView(detailPanel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        } else {
            content.addView(listPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(12))
            })
            content.addView(detailPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        return root
    }

    private fun header(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14))
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titles.addView(label("Zui 性能控制", 24f, COLOR_TEXT, Typeface.BOLD))
        titles.addView(label("应用档案 / 刷新率 / XML 调度 / AsoulOpt", 13f, COLOR_SUBTLE, Typeface.NORMAL))
        box.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(textButton("静音通知") { PerfCtlQuickService.start(this); toast("通知已刷新") })
        return box
    }

    private fun searchBox(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        box.addView(label("应用列表", 17f, COLOR_TEXT, Typeface.BOLD))
        box.addView(filterTabs(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
            setMargins(0, dp(10), 0, 0)
        })
        box.addView(EditText(this).apply {
            hint = "搜索应用或包名"
            setSingleLine(true)
            textSize = 14f
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(COLOR_FIELD, dp(8), COLOR_STROKE)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun afterTextChanged(s: Editable?) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    filterApps(s?.toString().orEmpty())
                }
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
            setMargins(0, dp(8), 0, dp(10))
        })
        listHint = label("正在读取应用...", 12f, COLOR_SUBTLE, Typeface.NORMAL).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), dp(8))
        }
        box.addView(listHint, matchWrap())
        return box
    }

    private fun filterTabs(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val buttons = linkedMapOf<AppFilter, TextView>()
        AppFilter.values().forEachIndexed { index, filter ->
            val tab = chip(filter.title) {
                currentFilter = filter
                updateFilterButtons()
                applyAppFilter()
                if (visibleApps.none { it.packageName == selectedPackageName }) {
                    selectApp(visibleApps.firstOrNull()?.packageName)
                }
            }
            buttons[filter] = tab
            row.addView(tab, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                setMargins(0, 0, if (index == AppFilter.values().lastIndex) 0 else dp(6), 0)
            })
        }
        filterButtons = buttons
        updateFilterButtons()
        return row
    }

    private fun appList(): View {
        appAdapter = AppAdapter()
        return ListView(this).apply {
            adapter = appAdapter
            divider = null
            cacheColorHint = Color.TRANSPARENT
            setOnItemClickListener { _, _, position, _ ->
                visibleApps.getOrNull(position)?.let { selectApp(it.packageName) }
            }
        }
    }

    private fun detailContent(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(root)

        selectedTitle = label("选择一个应用", 21f, COLOR_TEXT, Typeface.BOLD)
        selectedPackage = label("", 13f, COLOR_SUBTLE, Typeface.NORMAL)
        root.addView(selectedTitle)
        root.addView(selectedPackage)

        ruleSummary = statusPanel()
        root.addView(ruleSummary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(14), 0, dp(14))
        })

        root.addView(label("档案开关", 16f, COLOR_TEXT, Typeface.BOLD))
        refreshCheck = check("刷新率规则")
        zuippCheck = check("XML 调度档案")
        asoulCheck = check("AsoulOpt 线程档案")
        root.addView(refreshCheck)
        root.addView(zuippCheck)
        root.addView(asoulCheck)

        root.addView(label("刷新率", 16f, COLOR_TEXT, Typeface.BOLD).apply {
            setPadding(0, dp(18), 0, dp(8))
        })
        val rateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val rateViews = linkedMapOf<Int, TextView>()
        PerfCtlContract.rates.forEachIndexed { index, rate ->
            val chip = chip("${rate}Hz") {
                selectedRate = rate
                updateRateButtons()
            }
            rateViews[rate] = chip
            rateRow.addView(chip, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                setMargins(0, 0, if (index == PerfCtlContract.rates.lastIndex) 0 else dp(8), 0)
            })
        }
        rateButtons = rateViews
        root.addView(rateRow)

        root.addView(label("操作", 16f, COLOR_TEXT, Typeface.BOLD).apply {
            setPadding(0, dp(18), 0, dp(8))
        })
        row(root,
            textButton("保存档案") { saveSelectedProfile() },
            textButton("移除档案") { removeSelectedProfile() },
        )
        row(root,
            textButton("开启自动") { sendSimple(PerfCtlContract.CMD_ENABLE_AUTO_REFRESH) },
            textButton("关闭自动") { sendSimple(PerfCtlContract.CMD_DISABLE_AUTO_REFRESH) },
        )
        row(root,
            textButton("立即锁定") { sendSimple(PerfCtlContract.CMD_SET_REFRESH, selectedRate) },
            textButton("恢复默认") { sendSimple(PerfCtlContract.CMD_RESTORE_REFRESH) },
        )

        root.addView(label("系统状态", 16f, COLOR_TEXT, Typeface.BOLD).apply {
            setPadding(0, dp(18), 0, dp(8))
        })
        statusView = statusPanel()
        root.addView(statusView, matchWrap())

        row(root,
            textButton("刷新状态") { refreshStatus(sendDaemonStatus = true) },
            textButton("重启 AsoulOpt") { sendSimple(PerfCtlContract.CMD_RESTART_ASOUL) },
        )
        row(root,
            textButton("应用 XML") { sendSimple(PerfCtlContract.CMD_APPLY_ZUIPP) },
            textButton("恢复 XML") { sendSimple(PerfCtlContract.CMD_RESTORE_ZUIPP) },
        )
        return scroll
    }

    private fun saveSelectedProfile() {
        val pkg = selectedPackageName ?: return toast("先选择应用")
        val profile = AppProfile(
            packageName = pkg,
            rate = selectedRate,
            refreshEnabled = refreshCheck.isChecked,
            zuippEnabled = zuippCheck.isChecked,
            asoulEnabled = asoulCheck.isChecked,
        )
        if (!profile.enabled) {
            removeSelectedProfile()
            return
        }
        profiles[pkg] = profile
        updateSelectedDetail()
        appAdapter.notifyDataSetChanged()
        sendCommand("已保存档案") {
            PerfCtlRequest.send(
                this,
                PerfCtlContract.CMD_SET_APP_PROFILE,
                rate = profile.rate,
                pkg = profile.packageName,
                refresh = profile.refreshEnabled,
                zuipp = profile.zuippEnabled,
                asoul = profile.asoulEnabled,
            )
        }
    }

    private fun removeSelectedProfile() {
        val pkg = selectedPackageName ?: return toast("先选择应用")
        profiles.remove(pkg)
        updateSelectedDetail()
        appAdapter.notifyDataSetChanged()
        sendCommand("已移除档案") {
            PerfCtlRequest.send(this, PerfCtlContract.CMD_REMOVE_APP_PROFILE, pkg = pkg)
        }
    }

    private fun sendSimple(cmd: String, rate: Int? = null) {
        sendCommand("已发送") {
            PerfCtlRequest.send(this, cmd, rate = rate)
        }
    }

    private fun sendCommand(message: String, block: () -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (commandInFlight || now - lastCommandAt < 350) {
            toast("操作处理中")
            return
        }
        commandInFlight = true
        lastCommandAt = now
        toast(message)
        Thread {
            runCatching { block() }
            handler.post {
                commandInFlight = false
                updateSelectedDetail()
                if (::appAdapter.isInitialized) {
                    appAdapter.notifyDataSetChanged()
                }
                handler.postDelayed({ refreshStatus() }, 800)
                handler.postDelayed({ refreshStatus() }, 2400)
            }
        }.start()
    }

    private fun refreshStatus(sendDaemonStatus: Boolean = false) {
        if (sendDaemonStatus) {
            PerfCtlRequest.send(this, PerfCtlContract.CMD_STATUS)
        }
        reloadProfiles()
        applyAppFilter(notify = false)
        updateSelectedDetail()
        if (::appAdapter.isInitialized) {
            appAdapter.notifyDataSetChanged()
        }

        val resolver = contentResolver
        val status = Settings.System.getString(resolver, PerfCtlContract.KEY_STATUS_TEXT).orEmpty()
        val last = Settings.System.getString(resolver, PerfCtlContract.KEY_STATUS_LAST).orEmpty()
        val time = Settings.System.getString(resolver, PerfCtlContract.KEY_STATUS_TIME).orEmpty()
        val peak = Settings.System.getString(resolver, "peak_refresh_rate").cleanSetting()
        val min = Settings.System.getString(resolver, "min_refresh_rate").cleanSetting()
        val auto = Settings.System.getString(resolver, PerfCtlContract.KEY_AUTO_REFRESH).cleanSetting()
        statusView.text = buildString {
            append("守护服务: ")
            append(if (status.contains("daemon=running") || last.isNotBlank()) "运行中" else "等待状态")
            append("\n最近操作: ").append(commandName(last.ifBlank { "无" }))
            if (time.isNotBlank()) append("\n状态时间: ").append(time)
            append("\n刷新率: 最高 ").append(peak.ifBlank { "系统默认" })
            append(" / 最低 ").append(min.ifBlank { "系统默认" })
            append("\n自动切换: ").append(if (auto == "1") "已开启" else "已关闭")
            if (status.isNotBlank()) {
                append("\n\n").append(status.replace(';', '\n').trim())
            }
        }
    }

    private fun reloadProfiles() {
        profiles.clear()
        val profileText = Settings.System.getString(contentResolver, PerfCtlContract.KEY_PROFILES_TEXT).orEmpty()
        profileText.lineSequence()
            .mapNotNull { AppProfile.parse(it) }
            .forEach { profiles[it.packageName] = it }

        val rulesText = Settings.System.getString(contentResolver, PerfCtlContract.KEY_RULES_TEXT).orEmpty()
        rulesText.lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split("=", limit = 2)
                if (parts.size != 2 || !AppProfile.isValidPackageName(parts[0])) {
                    null
                } else {
                    val rate = parts[1].toIntOrNull()?.takeIf { it in PerfCtlContract.rates } ?: 120
                    parts[0] to rate
                }
            }
            .forEach { (pkg, rate) ->
                profiles.putIfAbsent(pkg, AppProfile(pkg, rate, refreshEnabled = true))
            }
    }

    private fun loadAppsAsync() {
        appsLoaded = false
        updateListHint()
        Thread {
            val loaded = queryInstalledApps()
            handler.post {
                apps.clear()
                apps.addAll(loaded)
                appsLoaded = true
                applyAppFilter(notify = false)
                selectInitialApp()
                updateListHint()
                if (::appAdapter.isInitialized) {
                    appAdapter.notifyDataSetChanged()
                }
            }
        }.start()
    }

    private fun queryInstalledApps(): List<InstalledApp> {
        val collator = Collator.getInstance(Locale.CHINA)
        @Suppress("DEPRECATION")
        val installed = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        return installed
            .map { info ->
                val pkg = info.packageName
                val label = info.loadLabel(packageManager)?.toString()?.trim().orEmpty().ifBlank { pkg }
                val icon = runCatching { info.loadIcon(packageManager) }.getOrNull()
                val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                InstalledApp(label, pkg, icon, isSystem)
            }
            .distinctBy { it.packageName }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    private fun filterApps(keyword: String) {
        currentKeyword = keyword
        applyAppFilter()
        updateListHint()
    }

    private fun applyAppFilter(notify: Boolean = true) {
        val lower = currentKeyword.trim().lowercase(Locale.ROOT)
        visibleApps.clear()
        visibleApps.addAll(apps.filter {
            matchesCurrentTab(it) && (lower.isBlank() ||
                it.label.lowercase(Locale.ROOT).contains(lower) ||
                it.packageName.lowercase(Locale.ROOT).contains(lower))
        })
        if (::appAdapter.isInitialized && notify) {
            appAdapter.notifyDataSetChanged()
        }
        updateListHint()
    }

    private fun updateListHint() {
        if (!::listHint.isInitialized) {
            return
        }
        listHint.text = if (!appsLoaded) {
            "正在读取应用..."
        } else {
            "显示 ${visibleApps.size} / 共 ${apps.size} 个应用"
        }
    }

    private fun matchesCurrentTab(app: InstalledApp): Boolean {
        return when (currentFilter) {
            AppFilter.USER -> !app.isSystem
            AppFilter.SYSTEM -> app.isSystem
            AppFilter.CONFIGURED -> profiles[app.packageName]?.enabled == true
            AppFilter.ALL -> true
        }
    }

    private fun updateFilterButtons() {
        if (!::filterButtons.isInitialized) {
            return
        }
        filterButtons.forEach { (filter, view) ->
            val selected = filter == currentFilter
            view.setTextColor(if (selected) Color.WHITE else COLOR_TEXT)
            view.background = rounded(if (selected) COLOR_ACCENT else COLOR_FIELD, dp(8), if (selected) COLOR_ACCENT else COLOR_STROKE)
        }
    }

    private fun selectInitialApp() {
        val firstProfile = profiles.keys.firstOrNull()
        val firstApp = visibleApps.firstOrNull()?.packageName ?: apps.firstOrNull()?.packageName
        selectApp(firstProfile ?: firstApp)
    }

    private fun selectApp(pkg: String?) {
        selectedPackageName = pkg
        val profile = pkg?.let { profiles[it] }
        selectedRate = profile?.rate ?: selectedRate
        updateSelectedDetail()
        if (::appAdapter.isInitialized) {
            appAdapter.notifyDataSetChanged()
        }
    }

    private fun updateSelectedDetail() {
        val pkg = selectedPackageName
        val app = apps.firstOrNull { it.packageName == pkg }
        val profile = pkg?.let { profiles[it] }
        changingDetail = true
        selectedTitle.text = app?.label ?: pkg ?: "选择一个应用"
        selectedPackage.text = pkg.orEmpty()
        selectedRate = profile?.rate ?: selectedRate
        refreshCheck.isChecked = profile?.refreshEnabled ?: true
        zuippCheck.isChecked = profile?.zuippEnabled ?: false
        asoulCheck.isChecked = profile?.asoulEnabled ?: false
        ruleSummary.text = if (profile?.enabled == true) {
            "档案: ${profile.rate}Hz / ${profile.tags()}"
        } else {
            "档案: 未启用"
        }
        changingDetail = false
        updateRateButtons()
    }

    private fun updateRateButtons() {
        rateButtons.forEach { (rate, view) ->
            val selected = rate == selectedRate
            view.setTextColor(if (selected) Color.WHITE else COLOR_TEXT)
            view.background = rounded(if (selected) COLOR_ACCENT else COLOR_FIELD, dp(8), if (selected) COLOR_ACCENT else COLOR_STROKE)
        }
    }

    private fun check(text: String): CheckBox {
        return CheckBox(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(COLOR_TEXT)
            minHeight = dp(42)
            setOnCheckedChangeListener { _, _ ->
                if (!changingDetail) {
                    ruleSummary.text = "档案: 待保存"
                }
            }
        }
    }

    private fun row(root: LinearLayout, vararg views: View) {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        views.forEachIndexed { index, view ->
            line.addView(view, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                setMargins(0, 0, if (index == views.lastIndex) 0 else dp(8), dp(8))
            })
        }
        root.addView(line, matchWrap())
    }

    private fun textButton(text: String, onClick: () -> Unit): TextView {
        return label(text, 13f, COLOR_TEXT, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = rounded(COLOR_FIELD, dp(8), COLOR_STROKE)
            setOnClickListener { onClick() }
        }
    }

    private fun chip(text: String, onClick: () -> Unit): TextView {
        return textButton(text, onClick)
    }

    private fun panel(): LinearLayout {
        return LinearLayout(this).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(Color.WHITE, dp(8), COLOR_STROKE)
        }
    }

    private fun statusPanel(): TextView {
        return label("", 13f, COLOR_TEXT, Typeface.NORMAL).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(COLOR_FIELD, dp(8), COLOR_STROKE)
        }
    }

    private fun label(value: String, size: Float, color: Int, style: Int): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.DEFAULT_BOLD.takeIf { style == Typeface.BOLD } ?: Typeface.DEFAULT
            includeFontPadding = true
        }
    }

    private fun rounded(color: Int, radius: Int, stroke: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            setStroke(dp(1), stroke)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 187)
        }
    }

    private fun AppProfile.tags(): String {
        val tags = mutableListOf<String>()
        if (refreshEnabled) tags += "刷新率"
        if (zuippEnabled) tags += "XML"
        if (asoulEnabled) tags += "线程"
        return tags.joinToString(" / ").ifBlank { "未启用" }
    }

    private fun commandName(value: String): String {
        return when (value) {
            PerfCtlContract.CMD_SET_REFRESH -> "手动锁刷新率"
            PerfCtlContract.CMD_RESTORE_REFRESH -> "恢复系统默认"
            PerfCtlContract.CMD_ENABLE_AUTO_REFRESH -> "开启自动切换"
            PerfCtlContract.CMD_DISABLE_AUTO_REFRESH -> "关闭自动切换"
            PerfCtlContract.CMD_SET_APP_PROFILE -> "保存应用档案"
            PerfCtlContract.CMD_REMOVE_APP_PROFILE -> "移除应用档案"
            PerfCtlContract.CMD_APPLY_ZUIPP -> "应用 XML"
            PerfCtlContract.CMD_RESTORE_ZUIPP -> "恢复 XML"
            PerfCtlContract.CMD_RESTART_ASOUL -> "重启 AsoulOpt"
            PerfCtlContract.CMD_STATUS -> "刷新状态"
            "init" -> "初始化"
            "无" -> "无"
            else -> value
        }
    }

    private fun String?.cleanSetting(): String {
        if (this == null || this == "null") {
            return ""
        }
        return removeSuffix(".0")
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount(): Int = visibleApps.size
        override fun getItem(position: Int): Any = visibleApps[position]
        override fun getItemId(position: Int): Long = visibleApps[position].packageName.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val holder: RowHolder
            val row: LinearLayout
            if (convertView == null) {
                row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                val check = CheckBox(this@MainActivity)
                val icon = ImageView(this@MainActivity)
                val texts = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }
                val title = label("", 14f, COLOR_TEXT, Typeface.BOLD)
                val sub = label("", 12f, COLOR_SUBTLE, Typeface.NORMAL)
                val badge = label("", 12f, COLOR_ACCENT, Typeface.NORMAL)
                texts.addView(title)
                texts.addView(sub)
                texts.addView(badge)
                row.addView(check, LinearLayout.LayoutParams(dp(44), dp(44)))
                row.addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                    setMargins(0, 0, dp(10), 0)
                })
                row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                holder = RowHolder(check, icon, title, sub, badge)
                row.tag = holder
            } else {
                row = convertView as LinearLayout
                holder = row.tag as RowHolder
            }

            val app = visibleApps[position]
            val profile = profiles[app.packageName]
            val selected = app.packageName == selectedPackageName
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = profile?.enabled == true
            holder.check.setOnClickListener {
                selectApp(app.packageName)
                if (holder.check.isChecked) {
                    saveSelectedProfile()
                } else {
                    removeSelectedProfile()
                }
            }
            holder.icon.setImageDrawable(app.icon)
            holder.title.text = app.label
            holder.sub.text = app.packageName
            holder.badge.text = buildString {
                append(if (app.isSystem) "系统应用" else "用户应用")
                append(" · ")
                append(profile?.let { "${it.rate}Hz / ${it.tags()}" } ?: "未启用")
            }
            row.background = rounded(if (selected) COLOR_SELECTED else Color.TRANSPARENT, dp(8), Color.TRANSPARENT)
            return row
        }
    }

    private data class InstalledApp(
        val label: String,
        val packageName: String,
        val icon: Drawable?,
        val isSystem: Boolean,
    )

    private data class RowHolder(
        val check: CheckBox,
        val icon: ImageView,
        val title: TextView,
        val sub: TextView,
        val badge: TextView,
    )

    private enum class AppFilter(val title: String) {
        USER("用户"),
        SYSTEM("系统"),
        CONFIGURED("已配置"),
        ALL("全部"),
    }

    companion object {
        private val COLOR_BG = Color.rgb(245, 247, 251)
        private val COLOR_FIELD = Color.rgb(239, 243, 248)
        private val COLOR_SELECTED = Color.rgb(229, 239, 255)
        private val COLOR_STROKE = Color.rgb(218, 226, 236)
        private val COLOR_TEXT = Color.rgb(24, 31, 42)
        private val COLOR_SUBTLE = Color.rgb(91, 103, 120)
        private val COLOR_ACCENT = Color.rgb(36, 101, 220)
    }
}
