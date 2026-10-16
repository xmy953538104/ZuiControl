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
internal fun MainActivity.renderThreadPage() {
    val runningChip=owner.modeChip("",0)
    val statusChip=owner.chip("",0,true)
    val statusHost=FrameLayout(this).apply{addView(runningChip);addView(statusChip)}
    bindPresentation{
    val health=BackendHealth.components(state).firstOrNull{it.component=="ZUIopt"}
    val status=when {
        health?.reason=="DISABLED_BY_OWNER" -> "已关闭"
        health?.reason=="FAILSAFE" -> "故障保护"
        health?.state==BackendHealth.State.OK -> "运行中"
        health?.state==BackendHealth.State.FAILED -> "异常"
        health?.state==BackendHealth.State.DEGRADED -> "需关注"
        else -> "状态不可用"
    }
    val running=health?.state==BackendHealth.State.OK && health.reason!="DISABLED_BY_OWNER"
    runningChip.visibility=if(running)View.VISIBLE else View.GONE
    statusChip.visibility=if(running)View.GONE else View.VISIBLE
    runningChip.background=owner.shape(owner.chipBg[1],999f)
    (runningChip.getChildAt(0) as OwnerStatusDot).setTone(owner.tiers[0])
    (runningChip.getChildAt(1) as TextView).apply{text=status;setTextColor(owner.chipFg[1])}
    val tone=when(health?.state){BackendHealth.State.FAILED->4;BackendHealth.State.DEGRADED->3;else->0}
    statusChip.text=status;statusChip.setTextColor(owner.chipFg[tone]);statusChip.background=owner.shape(owner.chipBg[tone],6f)
    statusHost.contentDescription="$status · ${health?.reason.orEmpty()}"
    }
    detail.addView(owner.title("线程调度","ZUIopt · 按线程 / 任务的 CPU 放置（cpuset / affinity）",trailing=statusHost))
    val errorBox=owner.column().apply{
        addView(owner.empty("规则暂不可用",ruleError,true))
        addView(owner.button("重试",small=true){loadRules()}.apply{tag="rules-retry"},gap())
    };detail.addView(errorBox,gap())
    bindPresentation{errorBox.visibility=if(ruleError.isEmpty())View.GONE else View.VISIBLE;owner.caption(errorBox,ruleError)}
    val failure=actionRow("ZUIopt 已进入故障保护", "") {
        if(rulesQualified)confirm("下次开机重新启用？", "本次开机继续由 Android 调度，不会立即重启。") { if(rulesQualified)session.work("已安排") { ZuioptRules.command(applicationContext, "reset") } }
    }.apply{tag="owner-control"};detail.addView(failure,gap())
    bindPresentation{failure.visibility=if(ZuioptRules.field(ruleState,"failure")=="1")View.VISIBLE else View.GONE;owner.caption(failure,ZuioptRules.field(ruleState,"failure_reason")+" · 下次开机重新启用")}
    detail.addView(card().apply {
        val sourceSection=owner.section("当前规则集","",owner.row().apply {
            val sourceChip=owner.chip("正在校验",0,true)
            bindPresentation { val evidence=this@renderThreadPage.baseline?.metadata?.optString("sourceEvidence").orEmpty()
                sourceChip.text=if(!rulesQualified)"正在校验" else Regex("publisher-revision:([0-9]+)").matchEntire(evidence)?.let{"云端 revision ${it.groupValues[1]}"} ?: "本地规则基线" }
            addView(sourceChip,LinearLayout.LayoutParams(-2,dp(22)).apply{marginEnd=dp(10)})
        addView(owner.button("查看原文",small=true,icon=R.drawable.owner_code){rawView()})

        });addView(sourceSection)
        bindPresentation{owner.caption(sourceSection,this@renderThreadPage.baseline?.metadata?.let{listOf(it.optString("source").ifBlank{"来源未提供"},it.optString("sourceVersion")).filter{it.isNotBlank()}.joinToString(" · ")} ?: ruleLoadStage)}
        val metadata=note("");addView(metadata)
        bindPresentation{metadata.text=if(!rulesQualified)listOfNotNull(snapshot?.generation?.let{"上次校验 generation $it"},ruleLoadStage).joinToString(" · ") else this@renderThreadPage.baseline?.let {
            listOf(it.metadata.optString("sourceDate"),it.metadata.optString("sourceCommit"),"生效 generation ${it.generation}").filter{it.isNotBlank()}.joinToString(" · ")
        }.orEmpty()}
        addView(owner.row().apply {
            listOf("规则应用","特殊线程规则","我的修改 / 新建").forEachIndexed{i,title->
                if(i>0)addView(View(this@renderThreadPage).apply{setBackgroundColor(owner.line)},LinearLayout.LayoutParams(dp(1),dp(50)))
                addView(owner.column().apply {
                    setPadding(if(i>0)dp(18)else 0,dp(2),dp(18),dp(2))
                    addView(owner.row().apply{
                        val count=owner.label("--",24f,owner.text,800);addView(count)
                        bindPresentation{
                            val apps=installed.filter { model?.appProfile(it.packageName)!=null }
                            val value=when(i){0->apps.size;1->model?.profiles?.values?.sumOf{it.rules.size} ?: 0;else->apps.count{provenance(it.packageName) in setOf("USER_MODIFIED","USER_CREATED")}}
                            count.text=if(model==null)"--" else value.toString()
                        }
                        addView(owner.label(if(i==1)"条" else "个",12f,owner.sub,700),LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(3);topMargin=dp(8)})
                    },LinearLayout.LayoutParams(-1,dp(24)))
                    addView(owner.label(title,11f,owner.muted),LinearLayout.LayoutParams(-1,dp(16)).apply{topMargin=dp(6)})
                },LinearLayout.LayoutParams(0,-2,1f))
            }
        },LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(16)})
    },gap())
    detail.addView(card().apply {
        setPadding(dp(18),dp(12),dp(18),dp(12))
        addView(owner.label("规则库",11.5f,owner.muted,800),LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(4)})
        val defaults=owner.domainRow("全局调度默认","",R.drawable.owner_settings,"zo",true){if(rulesQualified&&!session.busy)runtimeDefaultsDialog()}.apply{tag="owner-control"}
        addView(defaults);bindPresentation{owner.caption(defaults,"mode ${model?.mode ?: 0} · RT ${model?.rt ?: 0}")}
        addView(owner.domainRow("检查规则更新","仅检查清单，选择同步后才下载正文",R.drawable.owner_undo,"zo",true){if(rulesQualified&&!session.busy)checkUpdates()}.apply{tag="owner-control"})
        val sync=owner.domainRow("同步规则库","",R.drawable.owner_code,"zo",true){if(rulesQualified&&!session.busy)syncUpstream()}.apply{tag="owner-control"}
        addView(sync);bindPresentation{sync.visibility=if(latest==null)View.GONE else View.VISIBLE;owner.caption(sync,"revision ${latest?.revision} · 下载并预览，确认后提交")}
        val rollback=owner.domainRow("回退规则版本","",R.drawable.owner_undo,"warn",true){if(rulesQualified)confirm("回退规则版本？","上游基线和生效规则一起回退。"){
            if(rulesQualified)session.work("已回退"){ZuioptRules.command(applicationContext,"rollback",checkNotNull(snapshot).generation)}
        }}.apply{tag="owner-control"};addView(rollback)
        bindPresentation{val previous=ZuioptRules.field(ruleState,"previous_generation").matches(Regex("g[0-9a-f]{24}"));owner.caption(rollback,if(previous)"回到上一代完整规则集" else "暂无可回退版本");rollback.isEnabled=previous&&rulesQualified;rollback.alpha=if(previous)1f else .4f}
        addView(View(this@renderThreadPage).apply{setBackgroundColor(owner.line)},LinearLayout.LayoutParams(-1,dp(1)))
        addView(owner.domainRow("高级兼容导入 · AppOpt","兼容转换与差异预览后确认",R.drawable.owner_appopt,"mute",true){if(rulesQualified && !session.busy)document(Intent.ACTION_OPEN_DOCUMENT,102,"*/*")}.apply{tag="owner-control"})
        addView(View(this@renderThreadPage).apply{setBackgroundColor(owner.line)},LinearLayout.LayoutParams(-1,dp(1)))
        addView(owner.domainRow("规则集导出","导出当前生效规则集",R.drawable.owner_export,"zo",true){snapshot?.let{export(it.text.toByteArray(),"ZuiControl_rules.conf")}})
        addView(note("GitHub 主源 · Gitee 备用 · 仅手动检查与同步"))
        val status=note("");addView(status);bindPresentation{status.text=updateStatus;status.visibility=if(RuleRemoteConfig.configured && updateStatus.isNotEmpty())View.VISIBLE else View.GONE}
    },gap())
}
