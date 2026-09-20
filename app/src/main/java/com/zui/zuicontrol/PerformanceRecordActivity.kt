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
                    addView(label("CPU · 单核百分比", 18f))
                    addView(label(threadKey, 12f))
                    addView(RecordChart(data.optJSONArray("detail") ?: JSONArray(), 1, data.optLong("duration")), LinearLayout.LayoutParams(-1, dp(208)))
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
            content.addView(label("暂无性能记录\n开启监视器后，轻触长条切换圆形，长按圆形 2 秒开始。")); return
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
        val state = if (data.optBoolean("active")) "录制中 / 暂停中" else if (data.optBoolean("complete")) "已手动结束" else "未完成（中断记录）"
        addCard(content, card().apply {
            addView(identity(data))
            addView(label("${whenRecorded(data)}\n时长 ${duration / 1000} 秒 · $state", 14f))
        })
        content.addView(label("FPS 为屏幕测量；W 为设备电池侧功率，外接电源时不可用。曲线空白段表示暂停或来源不可用。", 12f))
        val scalars = data.optJSONArray("scalars") ?: JSONArray()
        val stats = data.optJSONArray("stats")?.optJSONArray(0) ?: JSONArray()
        listOf("FPS", "Power W", "quiet-therm °C").forEachIndexed { index, title ->
            addCard(content, card().apply {
                addView(label(title, 18f))
                addView(label("最低 ${number(stats.optDouble(index * 3, Double.NaN))}    平均 ${number(stats.optDouble(index * 3 + 1, Double.NaN))}    最高 ${number(stats.optDouble(index * 3 + 2, Double.NaN))}", 14f))
                addView(RecordChart(scalars, index + 1, duration), LinearLayout.LayoutParams(-1, dp(208)))
            })
        }
        addCard(content, card().apply {
            addView(label("线程记录   ›", 18f)); setOnClickListener { navigate(threads = true) }
        })
        content.addView(label("删除该应用记录", 14f).apply { setOnClickListener { deleteRecord() } })
    }
    private fun showThreads(content: LinearLayout, data: JSONObject) {
        content.addView(identity(data))
        content.addView(label("热点线程", 20f))
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
        dialog.setOnShowListener { UiControls.styleDialog(dialog) }; dialog.show()
    }
    private fun number(value: Double) = if (!value.isFinite() || value < 0) "--" else String.format(Locale.ROOT, "%.1f", value)
    private inner class RecordChart(private val rows: JSONArray, private val column: Int, private val duration: Long) : View(this@PerformanceRecordActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val values = (0 until rows.length()).map { rows.getJSONArray(it).optDouble(column, Double.NaN) }
        private val axis = RecordAxis.of(values)
        init { contentDescription = "时间曲线；${values.count { it.isFinite() && it >= 0 }} 个有效汇总点" }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val left = dp(48).toFloat(); val right = width - dp(16).toFloat()
            val top = dp(20).toFloat(); val bottom = height - dp(32).toFloat()
            if (right <= left || bottom <= top) return
            paint.textSize = 11 * resources.displayMetrics.scaledDensity; paint.strokeWidth = density
            axis.ticks.forEach { value ->
                val y = bottom - (bottom - top) * ((value - axis.low) / (axis.high - axis.low)).toFloat()
                paint.color = 0xFFE0E5ED.toInt(); canvas.drawLine(left, y, right, y, paint)
                paint.color = getColor(R.color.ui_secondary); paint.textAlign = Paint.Align.RIGHT
                canvas.drawText(axis.label(value), left - dp(8), y - (paint.ascent() + paint.descent()) / 2, paint)
            }
            for (i in 0..2) {
                val seconds = duration * i / 2000
                val text = if (duration < 120000) "${seconds}s" else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
                paint.textAlign = when (i) { 0 -> Paint.Align.LEFT; 2 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                canvas.drawText(text, left + (right - left) * i / 2, bottom + dp(22), paint)
            }
            if (values.none { it.isFinite() && it >= 0 }) {
                paint.textAlign = Paint.Align.CENTER; canvas.drawText("暂无有效值", (left + right) / 2, (top + bottom) / 2, paint); return
            }
            val path = Path(); var connected = false; var previous = -1.0
            val gapLimit = maxOf(4000.0, duration / 600.0 * 2.5)
            paint.color = getColor(R.color.ui_accent)
            for (i in 0 until rows.length()) {
                val t = rows.getJSONArray(i).optDouble(0); val value = values[i]
                if (!value.isFinite() || value < 0) { connected = false; continue }
                val x = left + (right - left) * (t / duration.coerceAtLeast(1)).toFloat()
                val y = bottom - (bottom - top) * ((value - axis.low) / (axis.high - axis.low)).toFloat()
                if (!connected || t - previous > gapLimit) path.moveTo(x, y) else path.lineTo(x, y)
                canvas.drawCircle(x, y, density, paint)
                connected = true; previous = t
            }
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.8f * density
            canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
        }
    }
}
