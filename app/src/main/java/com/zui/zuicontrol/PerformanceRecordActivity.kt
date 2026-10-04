package com.zui.zuicontrol

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Native application grid -> latest detail -> thread list -> one thread chart. */
class PerformanceRecordActivity : Activity() {
    private val density get() = resources.displayMetrics.density
    private fun dp(n: Int) = (n * density).toInt()
    private val pkg get() = intent.getStringExtra("package").orEmpty()
    private val threadPage get() = intent.getBooleanExtra("threads", false)
    private val threadKey get() = intent.getStringExtra("thread").orEmpty()
    private fun label(text: String, size: Float = 15f) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(getColor(R.color.ui_text))
        setPadding(0, dp(8), 0, dp(8))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        val theme = getSharedPreferences("frontend", MODE_PRIVATE).getString("theme", "system")
        if (theme != "system") applyOverrideConfiguration(android.content.res.Configuration().apply {
            uiMode = if (theme == "dark") android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
        })
        super.onCreate(savedInstanceState)
        window.statusBarColor = getColor(R.color.ui_field)
        window.navigationBarColor = getColor(R.color.ui_field)
    }
    override fun onResume() { super.onResume(); load() }
    private fun load() {
        setContentView(label("正在读取应用记录…"))
        Thread {
            val data = runCatching {
                JSONObject(if (pkg.isEmpty()) PerformanceMonitor.command("recordList") else
                    PerformanceMonitor.command("recordRead", JSONObject().put("package", pkg)
                        .put("threads", threadPage).put("thread", threadKey).toString()))
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                data.onSuccess { show(it) }.onFailure { setContentView(label("记录暂不可读，请稍后重试")) }
            }
        }.start()
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun card() = column().apply {
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = UiControls.shape(this@PerformanceRecordActivity, R.color.ui_surface,
            resources.getDimension(R.dimen.ui_card_radius))
    }
    private fun addCard(parent: LinearLayout, child: View) = parent.addView(child,
        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
    private fun identity(data: JSONObject): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        runCatching { packageManager.getApplicationIcon(data.getString("package")) }.onSuccess { icon ->
            addView(ImageView(this@PerformanceRecordActivity).apply {
                setImageDrawable(icon); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        }
        addView(label(data.optString("label", data.optString("package")), 19f), LinearLayout.LayoutParams(0, -2, 1f))
    }
    private fun whenRecorded(data: JSONObject) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(data.optLong("wall")))
    private fun navigate(packageName: String = pkg, threads: Boolean = false, key: String = "", name: String = "") {
        startActivity(Intent(this, PerformanceRecordActivity::class.java).putExtra("package", packageName)
            .putExtra("threads", threads).putExtra("thread", key).putExtra("name", name))
    }
    private fun show(data: JSONObject) {
        val content = column().apply { setPadding(dp(24), dp(20), dp(24), dp(24)) }
        val title = when { pkg.isEmpty() -> "应用记录"; threadKey.isNotEmpty() -> intent.getStringExtra("name").orEmpty(); threadPage -> "线程记录"; else -> "最近一次记录" }
        content.addView(label("‹  $title", 24f).apply { setOnClickListener { finish() } })
        when {
            pkg.isEmpty() -> showApps(content, data.optJSONArray("records") ?: JSONArray())
            !data.has("package") -> content.addView(label("该应用暂无记录"))
            threadKey.isNotEmpty() -> {
                addCard(content, card().apply {
                    addView(label("入榜期间 CPU 时间线 · 单核百分比", 18f))
                    addView(label(threadKey, 12f))
                    addView(label("断档表示无已保存 Top15 样本，不代表 CPU=0。", 12f))
                    addView(RecordChart(this@PerformanceRecordActivity, data.optJSONArray("detail") ?: JSONArray(), 1, data.optLong("duration")), LinearLayout.LayoutParams(-1, dp(208)))
                })
            }
            threadPage -> showThreads(content, data)
            else -> showDetail(content, data)
        }
        val max = if (pkg.isEmpty()) 880 else 760
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.ui_field))
            addView(scroll, FrameLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels, dp(max)), -1, Gravity.CENTER_HORIZONTAL))
        })
    }
    private fun showApps(content: LinearLayout, records: JSONArray) {
        if (records.length() == 0) {
            content.addView(label("暂无性能记录\n开启监视器后，轻触长条切换圆形，双击圆形开始。")); return
        }
        val columns = UiControls.gridColumns(minOf(resources.configuration.screenWidthDp, 880) - 48)
        for (start in 0 until records.length() step columns) {
            val row = LinearLayout(this)
            repeat(columns) { index ->
                val record = records.optJSONObject(start + index)
                row.addView(if (record == null) View(this) else card().apply {
                    addView(identity(record))
                    addView(label(whenRecorded(record), 12f))
                    addView(label("${record.optLong("duration") / 1000} 秒  ·  平均 ${number(record.optDouble("avgFps", Double.NaN))} FPS", 12f))
                    setOnClickListener { navigate(record.getString("package")) }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(12) })
            }
            addCard(content, row)
        }
    }
    private fun showDetail(content: LinearLayout, data: JSONObject) {
        val duration = data.optLong("duration")
        val state = if (data.optBoolean("active")) "录制中" else if (data.optBoolean("complete")) "已结束" else "未完成（中断记录）"
        addCard(content, card().apply {
            addView(identity(data))
            addView(label("${whenRecorded(data)}\n时长 ${duration / 1000} 秒 · $state", 14f))
        })
        content.addView(label("FPS 为合格应用呈现数据；W 为设备电池侧功率，外接电源时不可用。曲线空白表示来源不可用。", 12f))
        val scalars = data.optJSONArray("scalars") ?: JSONArray()
        val stats = data.optJSONArray("stats")?.optJSONArray(0) ?: JSONArray()
        listOf("FPS", "Power W", "quiet-therm °C").forEachIndexed { index, title ->
            addCard(content, card().apply {
                addView(label(title, 18f))
                addView(label("最低 ${number(stats.optDouble(index * 3, Double.NaN))}    平均 ${number(stats.optDouble(index * 3 + 1, Double.NaN))}    最高 ${number(stats.optDouble(index * 3 + 2, Double.NaN))}", 14f))
                addView(RecordChart(this@PerformanceRecordActivity, scalars, index + 1, duration), LinearLayout.LayoutParams(-1, dp(208)))
            })
        }
        addCard(content, card().apply {
            addView(label("线程记录   ›", 18f)); setOnClickListener { navigate(threads = true) }
        })
        content.addView(label("删除该应用记录", 14f).apply { setOnClickListener { deleteRecord() } })
    }
    private fun showThreads(content: LinearLayout, data: JSONObject) {
        content.addView(identity(data))
        content.addView(label("Top15 入榜线程", 20f))
        content.addView(label("每约 3 秒采集 Top15。入榜均值不代表整个运行期间的均值；不同进程和线程代次分别统计。", 12f))
        val threads = data.optJSONArray("threads") ?: JSONArray()
        if (threads.length() == 0) content.addView(label("暂无可用线程增量样本"))
        for (i in 0 until threads.length()) {
            val row = threads.getJSONArray(i)
            addCard(content, card().apply {
                addView(label(row.optString(1), 17f))
                addView(label("入榜均值 ${number(row.optDouble(2))}% · 峰值 ${number(row.optDouble(3))}% · 有效样本 ${row.optLong(4)} 次", 13f))
                addView(label(row.optString(0), 11f))
                setOnClickListener { navigate(key = row.optString(0), name = row.optString(1)) }
            })
        }
    }
    private fun deleteRecord() {
        val dialog = AlertDialog.Builder(this).setTitle("删除该应用记录？")
            .setMessage("仅删除此应用的最近一次记录，其他应用记录会保留。")
            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                Thread {
                    val reply = PerformanceMonitor.command("recordDelete", pkg)
                    runOnUiThread {
                        if (reply.startsWith("ok=1")) finish()
                        else Toast.makeText(this, "请先结束录制后重试", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }.create()
        UiControls.styleDialog(dialog); dialog.show()
    }
    private fun number(value: Double) = if (!value.isFinite() || value < 0) "--" else String.format(Locale.ROOT, "%.1f", value)
}
