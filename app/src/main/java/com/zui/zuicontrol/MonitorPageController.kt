package com.zui.zuicontrol

import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import android.zui.ZuiControlManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Stateless presentation; Activity/session own data, transactions and subscriptions. */
internal fun MainActivity.renderMonitorPage() {
    val r = selectedRecord
    if(r!=null && r.has("package")){
        val actions=owner.row().apply{
            addView(owner.button("导出记录",small=true,icon=R.drawable.owner_export){export(r.toString(2).toByteArray(),"ZuiControl_record.json")})
            addView(owner.button("删除","danger",true,R.drawable.owner_trash){confirm("删除记录？","仅删除此应用最近一次记录。"){
                session.work("已删除",feature="records"){val reply=PerformanceMonitor.command("recordDelete",session.selected);check(reply.startsWith("ok=1")){reply};selectedRecord=null}
            }},LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(8)})
        }
        detail.addView(owner.title(selectedThread?.optString("name") ?: if(recordThreads)"线程运行记录" else name(session.selected),"${whenRecorded(r.optLong("wall"))} · ${duration(r.optLong("duration"))} · ${if(r.optBoolean("complete"))"已结束" else r.optString("terminalReason","未完成")}",leading=if(recordThreads)owner.back(if(selectedThread!=null)"返回线程记录" else "返回记录详情"){if(selectedThread!=null){selectedThread=null;render()}else readRecord(session.selected)}else null,trailing=if(recordThreads)null else actions))
    }else if(recordDetailLoading)heading(name(session.selected),"正在读取记录…")
    recordBanner = row().apply {
        background = shape(field, 14); recordLabel = label("", 13f, orange, true)
        addView(recordLabel, LinearLayout.LayoutParams(0, -2, 1f)); addView(button("停止") { session.work("记录已停止",feature="records") {
            val reply = session.gateway.stopRecord(); check(reply.ok) { reply.text }
        } }); visibility = View.GONE
    }; detail.addView(recordBanner, gap())
    if (r == null || !r.has("package")) {
        val waiting=note("正在读取记录…")
        val empty=owner.column().apply{addView(owner.empty("暂无应用监测","开启性能监视悬浮窗后可生成记录"),gap());addView(button("查看指引"){monitorGuidance()})}
        detail.addView(waiting);detail.addView(empty)
        bindPresentation{waiting.visibility=if(recordDetailLoading || threadDetailLoading)View.VISIBLE else View.GONE;empty.visibility=if(waiting.visibility==View.VISIBLE)View.GONE else View.VISIBLE}
        return
    }
    selectedThread?.let{thread->
        detail.addView(card().apply{
            addView(owner.section("CPU 时间线","入榜期间单核百分比；缺失样本保留断档"))
            addView(RecordChart(this@renderMonitorPage,thread.optJSONArray("detail") ?: JSONArray(),1,thread.optLong("duration")),LinearLayout.LayoutParams(-1,dp(208)))
            addView(note(thread.optString("key")));addView(note("无已保存 Top15 样本不代表 CPU=0。"))
        },gap());return
    }
    if (recordThreads) {
        detail.addView(note("Top15 入榜线程；入榜均值不是整段平均。时间线断档表示无已保存的 Top15 样本，不代表 CPU=0。"), gap())
        detail.addView(owner.cpuThreadTable(r,cpuSparklines){key,name->readThread(key,name)},gap());return
    }
    val policy = r.optJSONObject("policySnapshot")
    detail.addView(note(if (policy == null || policy.length() == 0) "记录开始时未保存策略快照" else
        "本次策略：${snapshotValue(policy, "refreshHz")} Hz · ${modeTitle(policy.optString("uperfMode"))} · GPU ${snapshotValue(policy, "gpuMinMHz")}–${snapshotValue(policy, "gpuMaxMHz")} MHz · ${when (policy.optString("gpuPolicy")) { "DEFAULT_FOR_MODE" -> "默认"; "CUSTOM" -> "自定义"; else -> "未知" }}\n策略 generation ${snapshotValue(policy, "policyGeneration")} · ZUIopt ${policy.optString("zuioptGeneration")} · ${policy.optString("profileSummaryValidity")}"), gap())
    val scalars = r.optJSONArray("scalars") ?: JSONArray(); val stats = r.optJSONArray("stats")?.optJSONArray(0) ?: JSONArray()
    fun chart(index:Int,title:String,height:Int)=card().apply{
        setPadding(dp(16),dp(14),dp(16),dp(10))
        addView(owner.row().apply{
            addView(View(this@renderMonitorPage).apply{background=owner.shape(when(index){1->owner.tiers[0];2->owner.tiers[2];else->owner.accent},99f)},LinearLayout.LayoutParams(dp(8),dp(8)).apply{marginEnd=dp(8)})
            addView(owner.label(title,13f,owner.text,800),LinearLayout.LayoutParams(0,-2,1f))
            val prefix=if(index==0)"最低 ${number(stats.optDouble(index*3,Double.NaN))} · " else ""
            addView(owner.label("${prefix}平均 ${number(stats.optDouble(index*3+1,Double.NaN))} · 最高 ${number(stats.optDouble(index*3+2,Double.NaN))}",10f,owner.muted,600))
        },LinearLayout.LayoutParams(-1,dp(22)))
        addView(RecordChart(this@renderMonitorPage,scalars,index+1,r.optLong("duration")),LinearLayout.LayoutParams(-1,dp(height)))
    }
    detail.addView(chart(0,"帧率 · FPS",155),gap())
    detail.addView(owner.row().apply{
        addView(chart(1,"功耗 · W",120),LinearLayout.LayoutParams(0,-2,1f))
        addView(chart(2,"温度 · quiet ℃",120),LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=dp(12)})
    },gap())
    detail.addView(note("功耗统计不含插电/不可用时段，曲线断档保留缺失；FPS 为 DISPLAY_MEASURED_FPS。"))
    // #viewMonitor .card.row overrides the generic row padding to 12×18.
    // Native stroke is inset, so include the Owner 1px border in content inset.
    detail.addView(card().apply{setPadding(dp(19),dp(13),dp(19),dp(13));addView(owner.domainRow("线程运行记录","Top15 入榜线程 · 点击查看 CPU 时间线",R.drawable.owner_thread_list){readRecord(session.selected,true)})},gap())
}
