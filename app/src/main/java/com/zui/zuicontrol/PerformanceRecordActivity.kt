package com.zui.zuicontrol

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Read-only view of the one recording slot; Android Canvas, no WebView/chart dependency. */
class PerformanceRecordActivity : Activity() {
    private val density get()=resources.displayMetrics.density
    private fun dp(n:Int)=(n*density).toInt()
    private fun label(text:String,size:Float=15f)=TextView(this).apply {
        this.text=text;textSize=size;setTextColor(0xFF1C222A.toInt());setPadding(dp(12),dp(10),dp(12),dp(10))
    }
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);load()}
    private fun load(){
        setContentView(label("正在读取最近一次记录…"))
        Thread {
            val data=runCatching { JSONObject(PerformanceMonitor.command("recordRead")) }
            runOnUiThread {
                if(isFinishing||isDestroyed)return@runOnUiThread
                data.onSuccess { show(it) }.onFailure { setContentView(label("记录暂不可读，请稍后重试")) }
            }
        }.start()
    }
    private fun show(data:JSONObject){
        val content=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(24),dp(16),dp(24));setBackgroundColor(0xFFF5F7FC.toInt())}
        content.addView(label("‹  最近一次记录",24f).apply {setOnClickListener{finish()}})
        if(!data.has("package"))content.addView(label("暂无性能记录\n从通知开启监视器，轻触长条切换圆形，长按圆形 2 秒开始。"))
        else{
            val identity=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
            runCatching {packageManager.getApplicationIcon(data.getString("package"))}.onSuccess { icon ->
                identity.addView(ImageView(this).apply {setImageDrawable(icon);contentDescription="应用图标"},LinearLayout.LayoutParams(dp(52),dp(52)))
            }
            identity.addView(label(data.optString("label",data.optString("package")),20f));content.addView(identity)
            val duration=data.optLong("duration")
            val state=if(data.optBoolean("active"))"录制中 / 暂停中" else if(data.optBoolean("complete"))"已手动结束" else "未完成（中断记录）"
            content.addView(label("${DateFormat.getDateTimeInstance().format(Date(data.optLong("wall")))}\n时长 ${duration/1000} 秒 · $state"))
            content.addView(label("FPS 为屏幕显示驱动测量，非游戏逐帧统计。W 为设备电池侧功率估算，不是该应用独占功率；外接电源时无有效功率值。空白段表示暂停或来源不可用。" ,12f))
            val scalars=data.optJSONArray("scalars")?:JSONArray()
            listOf("屏幕 FPS","设备功率 W","quiet 温度 °C").forEachIndexed { i,title ->
                content.addView(label(title,18f));content.addView(RecordChart(scalars,i+1,duration),LinearLayout.LayoutParams(-1,dp(160)))
            }
            content.addView(label("热点线程",20f))
            content.addView(label("仅录制时每约 3 秒采集 Top15；入榜均值不是线程全生命周期平均。不同 PID/TID generation 分开统计。",12f))
            val threads=data.optJSONArray("threads")?:JSONArray()
            if(threads.length()==0)content.addView(label("尚无可用线程增量样本"))
            for(i in 0 until threads.length()){
                val row=threads.getJSONArray(i)
                val line=label("${row.optString(1)}\n入榜均值 ${num(row,2)}% · 峰值 ${num(row,3)}%\n有效样本 ${row.optLong(4)} 次 · ${row.optString(0)}")
                line.setOnClickListener{detail(row.optString(0),row.optString(1),duration)}
                content.addView(line)
            }
        }
        setContentView(ScrollView(this).apply {addView(content)})
    }
    private fun detail(key:String,name:String,duration:Long){
        Thread {
            val data=runCatching {JSONObject(PerformanceMonitor.command("recordRead",key))}
            runOnUiThread {
                if(isFinishing||isDestroyed)return@runOnUiThread
                data.onSuccess {
                    AlertDialog.Builder(this).setTitle("$name · CPU 单核%")
                        .setView(RecordChart(it.optJSONArray("detail")?:JSONArray(),1,duration).apply {minimumHeight=dp(220)})
                        .setPositiveButton("完成",null).show()
                }
            }
        }.start()
    }
    private fun num(row:JSONArray,column:Int)=if(row.isNull(column))"--" else String.format(Locale.ROOT,"%.1f",row.optDouble(column))
    private inner class RecordChart(private val rows:JSONArray,private val column:Int,private val duration:Long):View(this@PerformanceRecordActivity){
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        init {contentDescription="时间曲线；${rows.length()} 个汇总点"}
        override fun onDraw(canvas:Canvas){
            super.onDraw(canvas)
            val left=dp(42).toFloat();val top=dp(16).toFloat();val right=width-dp(12).toFloat();val bottom=height-dp(28).toFloat()
            paint.color=0xFFBBC6D8.toInt();paint.strokeWidth=density
            canvas.drawLine(left,top,left,bottom,paint);canvas.drawLine(left,bottom,right,bottom,paint)
            val values=(0 until rows.length()).map {rows.getJSONArray(it)}.filter {!it.isNull(column)&&it.optDouble(column)>=0}
            val max=(values.maxOfOrNull {it.optDouble(column)}?:1.0).coerceAtLeast(1.0)
            paint.color=0xFF65738A.toInt();paint.textSize=10*density
            canvas.drawText(String.format(Locale.ROOT,"%.1f",max),0f,top+10*density,paint)
            canvas.drawText("0",left,bottom+18*density,paint)
            canvas.drawText("${duration/1000}s",(right-45*density).coerceAtLeast(left),bottom+18*density,paint)
            if(values.isEmpty()){canvas.drawText("暂无有效值",left+20*density,(top+bottom)/2,paint);return}
            val path=Path();var connected=false;var previous=-1.0
            val gapLimit=maxOf(4000.0,duration/600.0*2.5)
            for(i in 0 until rows.length()){
                val row=rows.getJSONArray(i);val t=row.optDouble(0)
                if(row.isNull(column)||row.optDouble(column)<0){connected=false;continue}
                val x=left+(right-left)*(t/duration.coerceAtLeast(1)).toFloat()
                val y=bottom-(bottom-top)*(row.optDouble(column)/max).toFloat()
                if(!connected||t-previous>gapLimit)path.moveTo(x,y) else path.lineTo(x,y)
                connected=true;previous=t
            }
            paint.style=Paint.Style.STROKE;paint.strokeWidth=2*density;paint.color=0xFF436AAB.toInt();canvas.drawPath(path,paint);paint.style=Paint.Style.FILL
        }
    }
}
