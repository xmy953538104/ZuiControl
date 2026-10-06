package com.zui.zuicontrol

import android.app.Activity
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
    private val owner by lazy{OwnerUi(this)}
    private var modal:OwnerModal?=null
    private val cpuSparklines=OwnerCpuSparklineLoader()
    private val density get() = resources.displayMetrics.density
    private fun dp(n: Int) = (n * density).toInt()
    private val pkg get() = intent.getStringExtra("package").orEmpty()
    private val threadPage get() = intent.getBooleanExtra("threads", false)
    private val threadKey get() = intent.getStringExtra("thread").orEmpty()
    private fun label(text:String,size:Float=13f)=owner.label(text,size,if(size>=16)owner.text else owner.muted,if(size>=16)800 else 400).apply{
        setSingleLine(false);setLineSpacing(0f,1.4f);setPadding(0,dp(4),0,dp(4))
    }
    override fun attachBaseContext(base:android.content.Context){super.attachBaseContext(OwnerWindow.themed(base))}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);OwnerWindow.fullscreen(this)}
    override fun onResume() { super.onResume(); load() }
    override fun onPause(){cpuSparklines.cancel();super.onPause()}
    override fun onDestroy(){cpuSparklines.close();super.onDestroy()}
    private fun load() {
        cpuSparklines.cancel()
        showShell(owner.empty("正在读取应用记录…","读取当前用户最近一次记录"))
        Thread {
            val data = runCatching {
                JSONObject(if (pkg.isEmpty()) PerformanceMonitor.command("recordList") else
                    PerformanceMonitor.command("recordRead", JSONObject().put("package", pkg)
                        .put("threads", threadPage).put("thread", threadKey).toString()))
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                data.onSuccess { show(it) }.onFailure { showShell(owner.empty("记录暂不可读","请稍后重试",true)) }
            }
        }.start()
    }
    private fun column() = owner.column()
    private fun card() = owner.card()
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
        content.addView(owner.title(title,"当前用户已保存记录",leading=owner.back(){finish()}))
        when {
            pkg.isEmpty() -> showApps(content, data.optJSONArray("records") ?: JSONArray())
            !data.has("package") -> content.addView(label("该应用暂无记录"))
            threadKey.isNotEmpty() -> {
                addCard(content, card().apply {
                    addView(OwnerCpuSparklineView(this@PerformanceRecordActivity).apply{
                        bind(data.optJSONArray("detail") ?: JSONArray(),data.optLong("duration"))
                    },LinearLayout.LayoutParams(dp(88),dp(22)))
                    addView(owner.row().apply{addView(owner.icon(R.drawable.owner_pulse,owner.accent,16),LinearLayout.LayoutParams(dp(16),dp(16)).apply{marginEnd=dp(8)});addView(label("入榜期间 CPU 时间线 · 单核百分比",14f))})
                    addView(label(threadKey, 12f))
                    addView(label("断档表示无已保存 Top15 样本，不代表 CPU=0。", 12f))
                    addView(RecordChart(this@PerformanceRecordActivity, data.optJSONArray("detail") ?: JSONArray(), 1, data.optLong("duration")), LinearLayout.LayoutParams(-1, dp(208)))
                })
            }
            threadPage -> showThreads(content, data)
            else -> showDetail(content, data)
        }
        showShell(content)
    }
    private fun showShell(content:View){
        val root=owner.row().apply{setBackgroundColor(owner.detail)}
        val rail=owner.column().apply{
            setBackgroundColor(owner.rail);gravity=Gravity.CENTER_HORIZONTAL;setPadding(0,dp(22),0,dp(18))
            addView(owner.column().apply{
                gravity=Gravity.CENTER_HORIZONTAL
                addView(owner.label("ZUI",17f,owner.accent,900).apply{letterSpacing=.03f},LinearLayout.LayoutParams(-2,dp(17)))
                addView(owner.label("CONTROL",8f,owner.muted,700).apply{letterSpacing=.04f},LinearLayout.LayoutParams(-2,dp(8)).apply{topMargin=dp(4)})
            },LinearLayout.LayoutParams(-2,dp(29)).apply{topMargin=dp(6);bottomMargin=dp(30)})
            listOf(R.drawable.owner_tune to "调控",R.drawable.owner_chip to "线程",R.drawable.owner_pulse to "监测").forEach{(icon,title)->
                val selected=title=="监测";val tone=if(selected)owner.accent else owner.muted
                addView(FrameLayout(this@PerformanceRecordActivity).apply{
                    addView(owner.column().apply{
                        gravity=Gravity.CENTER;background=owner.shape(if(selected)owner.soft(owner.accent)else android.graphics.Color.TRANSPARENT,14f)
                        addView(owner.icon(icon,tone,20),LinearLayout.LayoutParams(dp(20),dp(20)))
                        addView(owner.label(title,10.5f,tone,700),LinearLayout.LayoutParams(-2,dp(13)).apply{topMargin=dp(4)})
                        contentDescription=title
                    },FrameLayout.LayoutParams(dp(52),dp(52),Gravity.CENTER))
                    if(selected)addView(View(this@PerformanceRecordActivity).apply{background=owner.shape(owner.accent,3f)},FrameLayout.LayoutParams(dp(3),dp(24),Gravity.START or Gravity.CENTER_VERTICAL))
                },LinearLayout.LayoutParams(-1,dp(52)).apply{bottomMargin=dp(8)})
            }
            addView(View(this@PerformanceRecordActivity),LinearLayout.LayoutParams(1,0,1f))
            addView(owner.icon(R.drawable.owner_settings,owner.muted).apply{isFocusable=true;contentDescription="返回";setOnClickListener{finish()}},LinearLayout.LayoutParams(dp(20),dp(20)))
        }
        OwnerWindow.safeContent(rail,22f,18f)
        root.addView(owner.borderedColumn(rail,72),LinearLayout.LayoutParams(dp(72),-1))
        val master=owner.column().apply{
            setBackgroundColor(owner.master);setPadding(dp(18),dp(22),dp(18),dp(18))
            addView(owner.label("监测记录",19f,owner.text,800));addView(label("最近一次记录 · Top15 线程",11f))
            addView(owner.card().apply{
                addView(owner.label(if(threadKey.isNotEmpty())intent.getStringExtra("name").orEmpty()else if(threadPage)"线程记录" else "应用记录",13.5f,owner.text,800))
                addView(label(pkg,11f));addView(owner.chip("已选择",2))
            },LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(18)})
        }
        OwnerWindow.safeContent(master,22f)
        if(content is LinearLayout)OwnerWindow.safeContent(content,20f)
        root.addView(owner.borderedColumn(master,312),LinearLayout.LayoutParams(dp(312),-1))
        root.addView(ScrollView(this).apply{isVerticalScrollBarEnabled = false;addView(content)},LinearLayout.LayoutParams(0,-1,1f))
        val host=FrameLayout(this).apply{setBackgroundColor(owner.detail);clipToOutline=false;addView(root,FrameLayout.LayoutParams(-1,-1))}
        modal=OwnerModal(owner,host,root)
        setContentView(OwnerDesignLayout(this).apply{setBackgroundColor(owner.detail);addView(host);OwnerWindow.inset(this)})
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed(){if(modal?.isOpen==true)modal?.close()else super.onBackPressed()}
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
                addView(owner.row().apply{
                    addView(View(this@PerformanceRecordActivity).apply{background=owner.shape(when(index){1->owner.tiers[0];2->owner.tiers[2];else->owner.accent},99f)},LinearLayout.LayoutParams(dp(8),dp(8)).apply{marginEnd=dp(8)})
                    addView(label(title,14f))
                })
                addView(label("最低 ${number(stats.optDouble(index * 3, Double.NaN))}    平均 ${number(stats.optDouble(index * 3 + 1, Double.NaN))}    最高 ${number(stats.optDouble(index * 3 + 2, Double.NaN))}", 14f))
                addView(RecordChart(this@PerformanceRecordActivity, scalars, index + 1, duration), LinearLayout.LayoutParams(-1, dp(208)))
            })
        }
        addCard(content, card().apply {
            addView(owner.domainRow("线程运行记录","Top15 入榜线程 · 点击查看 CPU 时间线",R.drawable.owner_thread_list){navigate(threads = true)})
        })
        content.addView(label("删除该应用记录", 14f).apply { setOnClickListener { deleteRecord() } })
    }
    private fun showThreads(content: LinearLayout, data: JSONObject) {
        content.addView(identity(data))
        content.addView(label("Top15 入榜线程", 20f))
        content.addView(label("每约 3 秒采集 Top15。入榜均值只统计有效样本，不代表整个运行期间的均值；不同进程和线程代次分别统计。", 12f))
        addCard(content,owner.cpuThreadTable(data,cpuSparklines))
    }
    private fun deleteRecord(){
        modal?.open("删除该应用记录？","仅删除此应用的最近一次记录，其他应用记录会保留。",360,owner.column(),listOf(
            owner.button("取消"){modal?.close()},owner.button("删除","danger-fill"){
                modal?.close();Thread{
                    val reply=PerformanceMonitor.command("recordDelete",pkg)
                    runOnUiThread{if(reply.startsWith("ok=1"))finish()else Toast.makeText(this,"请先结束录制后重试",Toast.LENGTH_SHORT).show()}
                }.start()
            }))
    }
    private fun number(value: Double) = if (!value.isFinite() || value < 0) "--" else String.format(Locale.ROOT, "%.1f", value)
}
