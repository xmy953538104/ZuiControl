package com.zui.perfctl

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var rulesView: TextView
    private lateinit var packageInput: EditText
    private lateinit var rateSpinner: Spinner

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    private val rateOptions = listOf(60, 90, 120, 144)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        refreshStatus()
    }

    private fun buildContent(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
        }
        scroll.addView(root)

        root.addView(text("Zui 性能控制", 24f, 0xff111827.toInt()))
        root.addView(text("AsoulOpt、调度 XML、按应用锁刷新率", 14f, 0xff4b5563.toInt()).apply {
            setPadding(0, dp(4), 0, dp(14))
        })

        statusView = panelText()
        root.addView(statusView, matchWrap())

        section(root, "手动刷新率")
        row(root,
            button("60Hz") { sendRequest("set_refresh", rate = 60) },
            button("90Hz") { sendRequest("set_refresh", rate = 90) },
            button("120Hz") { sendRequest("set_refresh", rate = 120) },
            button("144Hz") { sendRequest("set_refresh", rate = 144) },
        )
        row(root, button("恢复系统默认") { sendRequest("restore_refresh") })

        section(root, "按应用自动切换")
        packageInput = EditText(this).apply {
            hint = "应用包名，例如 com.kurogame.mingchao"
            setSingleLine(true)
            textSize = 14f
        }
        root.addView(packageInput, matchWrap())

        val ruleLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        rateSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                rateOptions.map { "${it}Hz" },
            )
            setSelection(rateOptions.indexOf(120))
        }
        ruleLine.addView(rateSpinner, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            setMargins(0, 0, dp(8), dp(8))
        })
        ruleLine.addView(button("选择应用") { showAppPicker() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            setMargins(0, 0, 0, dp(8))
        })
        root.addView(ruleLine, matchWrap())

        row(root,
            button("保存规则") { saveRefreshRule() },
            button("删除规则") { removeRefreshRule() },
        )
        row(root,
            button("开启自动切换") { sendRequest("enable_auto_refresh") },
            button("关闭自动切换") { sendRequest("disable_auto_refresh") },
        )

        rulesView = panelText()
        root.addView(rulesView, matchWrap())

        section(root, "高级操作")
        row(root,
            button("刷新状态") { sendRequest("status") },
            button("重启 AsoulOpt") { sendRequest("restart_asoul") },
        )
        row(root,
            button("应用 Asoul 配置") { sendRequest("apply_asoul") },
            button("应用 XML") { sendRequest("apply_zuipp") },
            button("恢复 XML") { sendRequest("restore_zuipp") },
        )

        return scroll
    }

    private fun saveRefreshRule() {
        val pkg = packageInput.text.toString().trim()
        if (!isValidPackageName(pkg)) {
            toast("包名不正确")
            return
        }
        sendRequest("set_refresh_rule", rate = selectedRate(), pkg = pkg)
    }

    private fun removeRefreshRule() {
        val pkg = packageInput.text.toString().trim()
        if (!isValidPackageName(pkg)) {
            toast("先填写要删除的包名")
            return
        }
        sendRequest("remove_refresh_rule", pkg = pkg)
    }

    private fun sendRequest(cmd: String, rate: Int? = null, pkg: String? = null) {
        runCatching {
            val resolver = contentResolver
            Settings.System.putString(resolver, "zui_perfctl_cmd", cmd)
            Settings.System.putString(resolver, "zui_perfctl_rate", rate?.toString().orEmpty())
            Settings.System.putString(resolver, "zui_perfctl_package", pkg.orEmpty())
            Settings.System.putString(resolver, "zui_perfctl_request_id", "${System.currentTimeMillis()}_$cmd")
        }.onSuccess {
            val detail = listOfNotNull(pkg, rate?.let { "${it}Hz" }).joinToString(" ")
            statusView.text = buildString {
                append("已发送: ")
                append(commandName(cmd))
                if (detail.isNotBlank()) append(" ").append(detail)
                append("\n时间: ").append(timeFormat.format(Date()))
            }
            toast("已发送")
            mainHandler.postDelayed({ refreshStatus() }, 900)
            mainHandler.postDelayed({ refreshStatus() }, 2600)
        }.onFailure { error ->
            statusView.text = "发送失败: ${error.message ?: error.javaClass.simpleName}"
            toast("发送失败")
        }
    }

    private fun refreshStatus() {
        val resolver = contentResolver
        val status = Settings.System.getString(resolver, "zui_perfctl_status_text").orEmpty()
        val last = Settings.System.getString(resolver, "zui_perfctl_status_last").orEmpty()
        val time = Settings.System.getString(resolver, "zui_perfctl_status_time").orEmpty()
        val peak = Settings.System.getString(resolver, "peak_refresh_rate").cleanSetting()
        val min = Settings.System.getString(resolver, "min_refresh_rate").cleanSetting()
        val auto = Settings.System.getString(resolver, "zui_perfctl_auto_refresh").cleanSetting()
        val rules = Settings.System.getString(resolver, "zui_perfctl_refresh_rules_text").orEmpty()

        statusView.text = buildString {
            append("守护服务: ")
            append(if (status.contains("daemon=running") || last.isNotBlank()) "运行中" else "等待状态")
            append("\n最近操作: ")
            append(commandName(last.ifBlank { "无" }))
            if (time.isNotBlank()) append("\n状态时间: ").append(time)
            append("\n当前锁帧: ")
            append("最高 ").append(peak.ifBlank { "系统默认" })
            append(" / 最低 ").append(min.ifBlank { "系统默认" })
            append("\n自动切换: ")
            append(if (auto == "1") "已开启" else "已关闭")
            if (status.isNotBlank()) {
                append("\n\n原始状态:\n")
                append(status.replace(';', '\n').trim())
            }
        }
        rulesView.text = formatRules(rules)
    }

    private fun showAppPicker() {
        val apps = loadLaunchableApps()
        if (apps.isEmpty()) {
            toast("没有找到可启动应用")
            return
        }

        val visible = apps.toMutableList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, visible.map { it.display })
        val search = EditText(this).apply {
            hint = "搜索应用或包名"
            setSingleLine(true)
        }
        val list = ListView(this).apply {
            this.adapter = adapter
            dividerHeight = 1
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(search, matchWrap())
            addView(list, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(420),
            ))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("选择应用")
            .setView(box)
            .setNegativeButton("取消", null)
            .create()

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val keyword = s?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                visible.clear()
                visible.addAll(apps.filter {
                    keyword.isBlank() ||
                        it.label.lowercase(Locale.ROOT).contains(keyword) ||
                        it.packageName.lowercase(Locale.ROOT).contains(keyword)
                })
                adapter.clear()
                adapter.addAll(visible.map { it.display })
                adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        list.setOnItemClickListener { _, _, position, _ ->
            val app = visible.getOrNull(position) ?: return@setOnItemClickListener
            packageInput.setText(app.packageName)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun loadLaunchableApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val collator = Collator.getInstance(Locale.CHINA)
        return packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val activity = info.activityInfo ?: return@mapNotNull null
                val pkg = activity.packageName ?: return@mapNotNull null
                val label = info.loadLabel(packageManager)?.toString()?.trim().orEmpty()
                    .ifBlank { pkg }
                InstalledApp(label, pkg)
            }
            .distinctBy { it.packageName }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    private fun selectedRate(): Int {
        return rateOptions.getOrElse(rateSpinner.selectedItemPosition) { 120 }
    }

    private fun isValidPackageName(value: String): Boolean {
        if (value.isBlank() || value.startsWith(".") || value.endsWith(".") || value.contains("..")) {
            return false
        }
        return value.all { it.isLetterOrDigit() || it == '_' || it == '.' }
    }

    private fun formatRules(raw: String): String {
        val lines = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .toList()
        if (lines.isEmpty()) {
            return "当前规则: 暂无"
        }
        return buildString {
            append("当前规则:\n")
            lines.forEach { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) {
                    append(parts[0]).append(" -> ").append(parts[1]).append("Hz\n")
                } else {
                    append(line).append('\n')
                }
            }
        }.trimEnd()
    }

    private fun commandName(value: String): String {
        return when (value) {
            "set_refresh" -> "手动锁刷新率"
            "restore_refresh" -> "恢复系统默认刷新率"
            "enable_auto_refresh" -> "开启自动切换"
            "disable_auto_refresh" -> "关闭自动切换"
            "set_refresh_rule" -> "保存规则"
            "remove_refresh_rule" -> "删除规则"
            "apply_asoul" -> "应用 Asoul 配置"
            "restore_asoul" -> "恢复 Asoul 配置"
            "restart_asoul" -> "重启 AsoulOpt"
            "apply_zuipp" -> "应用 XML"
            "restore_zuipp" -> "恢复 XML"
            "restart_zuipp" -> "重启 ZuiPP"
            "status" -> "刷新状态"
            "init" -> "初始化"
            "无" -> "无"
            else -> value
        }
    }

    private fun section(root: LinearLayout, title: String) {
        root.addView(text(title, 16f, 0xff111827.toInt()).apply {
            setPadding(0, dp(22), 0, dp(8))
        })
    }

    private fun row(root: LinearLayout, vararg buttons: Button) {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        buttons.forEachIndexed { index, button ->
            line.addView(button, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                setMargins(0, 0, if (index == buttons.lastIndex) 0 else dp(8), dp(8))
            })
        }
        root.addView(line, matchWrap())
    }

    private fun button(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            setOnClickListener { action() }
        }
    }

    private fun text(value: String, size: Float, color: Int): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
        }
    }

    private fun panelText(): TextView {
        return TextView(this).apply {
            textSize = 13f
            setTextColor(0xff111827.toInt())
            setBackgroundColor(0xffeef2f7.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
    }

    private fun String?.cleanSetting(): String {
        if (this == null || this == "null") {
            return ""
        }
        return this
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private data class InstalledApp(
        val label: String,
        val packageName: String,
    ) {
        val display: String = "$label\n$packageName"
    }
}
