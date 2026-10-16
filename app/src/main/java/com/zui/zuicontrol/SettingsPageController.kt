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
internal fun MainActivity.renderSettingsPage() {
    when (session.settingsModule) {
        0 -> {
            heading("监测与显示","悬浮窗权限、通知状态与界面主题")
            val nm=getSystemService(NotificationManager::class.java)
            val enabled=nm.areNotificationsEnabled() && nm.getNotificationChannel("zui_control_monitor_v1")?.importance!=NotificationManager.IMPORTANCE_NONE
            detail.addView(card().apply{
                setPadding(dp(18),dp(14),dp(18),dp(14));addView(owner.label("权限与通知",11.5f,owner.muted,800),owner.gap(4))
                val granted=Settings.canDrawOverlays(this@renderSettingsPage)
                addView(owner.settingsRow("悬浮窗权限","性能监视悬浮窗与记录开始 / 停止手势依赖此权限",if(granted)owner.chip("已授权",1,true).apply{isFocusable=true;contentDescription="悬浮窗权限已授权";setOnClickListener{overlayPermission()}}else owner.button("去授权",small=true){overlayPermission()}))
                addView(View(this@renderSettingsPage).apply{setBackgroundColor(owner.line)},LinearLayout.LayoutParams(-1,dp(1)))
                addView(owner.settingsRow("通知","故障保护与监测记录状态提醒",owner.row().apply{
                    addView(owner.chip(if(enabled)"已开启" else "已关闭",if(enabled)1 else 0,true))
                    if(!enabled)addView(owner.button("系统通知设置",small=true){ownedActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))},LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(8)})
                }))
            },gap())
            detail.addView(card().apply{
                setPadding(dp(18),dp(14),dp(18),dp(14));addView(owner.label("显示",11.5f,owner.muted,800),owner.gap(4))
                val values=listOf("system","dark","light")
                val themeSegment=segment(listOf("跟随系统", "深色", "浅色"),values.indexOf(prefs.getString("theme","system"))){theme(values[it])} as OwnerSegment
                addView(owner.settingsRow("界面主题","侧边栏按钮可快速切换深色 / 浅色",themeSegment.apply{layoutParams=LinearLayout.LayoutParams(dp(220),dp(34))}))
                bindPresentation{themeSegment.showSelection(values.indexOf(prefs.getString("theme","system")))}
            },gap())
        }
        1 -> gpuDefaultsPage()
        2 -> {
            heading("数据与维护", "配置备份、恢复与系统维护")
            val rows=mutableListOf<View>()
            rows+=actionRow("立即备份", prefs.getString("backup", "保存到你选择的位置").orEmpty()) {
                read("backupExport",shared=true,fetch={SettingsBackup.export(applicationContext)},parse={it}){backupBytes=it;session.pendingDocument=103;presentPending()}
            }
            rows+=actionRow("从备份恢复", "校验 → 摘要 → 确认") { document(Intent.ACTION_OPEN_DOCUMENT, 104, "application/zip") }
            rows+=actionRow("恢复出厂配置", "保留监测记录与上游基线") { confirm("恢复出厂配置？", "清除本用户应用策略，恢复全局档位、GPU 默认与偏好；线程规则恢复当前上游基线。保留监测记录、上游版本与诊断记录。") {
                session.work("已恢复出厂配置",feature="restore") { SettingsBackup.factoryReset(applicationContext) }
            } }
            rows+=actionRow("导出运行日志", "可能包含应用与使用记录") { confirm("导出运行日志？", "日志可能包含已安装应用和使用信息，请妥善保存。") {
                read("logsExport",shared=true,fetch={val id=ZuiControlRequest.send(applicationContext,ZuiControlContract.CMD_EXPORT_LOGS);val ack=ZuiControlRequest.awaitTerminalAck(applicationContext,id);check(ack.succeeded){ack.detail};ZuiControlClient.utilityValue("result","$id|logs").toByteArray()},parse={it}){exportBytes=it;session.pendingDocument=101;presentPending()}
            } }
            rows+=actionRow("重启调度核心", "Uperf 与 ZUIopt；已保存配置保留") { confirm("重启调度核心？", "短暂恢复 Android 调度后重新加载已保存配置。") {
                session.work("调度核心已重启",feature="diagnostics") { val id = ZuiControlRequest.send(applicationContext, ZuiControlContract.CMD_RESTART_SCHEDULER); val ack = ZuiControlRequest.awaitTerminalAck(applicationContext, id); check(ack.succeeded) { ack.detail } }
            } }
            listOf("备份" to rows.take(1),"恢复" to rows.subList(1,3),"维护" to rows.takeLast(2)).forEach{(title,items)->
                detail.addView(card().apply{
                    setPadding(dp(18),dp(14),dp(18),dp(14));addView(owner.label(title,11.5f,owner.muted,800))
                    items.forEachIndexed{i,v->if(i>0)addView(View(this@renderSettingsPage).apply{setBackgroundColor(owner.line)},LinearLayout.LayoutParams(-1,dp(1)));addView(v)}
                },gap())
            }
        }
        else -> {
            heading("关于","版本信息与使用帮助")
            detail.addView(card().apply{
                addView(owner.row().apply{
                    addView(owner.column().apply{addView(owner.label("ZUI",22f,owner.accent,900));addView(owner.label("CONTROL",9f,owner.muted,700))},LinearLayout.LayoutParams(dp(64),-2).apply{marginEnd=dp(14)})
                    addView(owner.column().apply{addView(owner.label("ZuiControl",17f,owner.text,800));addView(note("").also{label->bindPresentation{label.text=value(caps,"releaseVersion").ifEmpty{"暂不可用"}}})})
                })
            },gap())
            detail.addView(card().apply{
                addView(owner.label("版本信息",11f,owner.muted,800))
                listOf("Release" to "releaseVersion","Build" to "sourceBuild","Integration" to "integrationSchema","AppPolicy" to "appPolicySchema","ZUIopt" to "zuioptSchema").forEach{(title,key)->addView(owner.formRow(title,note("").also{label->bindPresentation{label.text=value(caps,key).ifEmpty{"暂不可用"}}}))}
            },gap())
            detail.addView(card().apply{
            },gap())
            detail.addView(card().apply{addView(actionRow("使用帮助","功能说明与监测记录指引"){monitorGuidance()})},gap())
        }
    }
}
