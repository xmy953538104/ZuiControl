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
internal fun MainActivity.renderTunePage() {
    val tier=GpuDefaultsDraft.modes.indexOf(mode.displayed).coerceAtLeast(0)
    val modeChip=owner.row().apply{
        background=owner.shape(owner.chipBg[tier+1],999f);setPadding(owner.px(11),0,owner.px(13),0)
        minimumHeight=owner.px(30)
        addView(OwnerStatusDot(this@renderTunePage,owner.tiers[tier]),LinearLayout.LayoutParams(owner.px(7),owner.px(7)).apply{marginEnd=owner.px(8)})
        addView(owner.label("${modeTitle(mode.displayed)}模式",12f,owner.chipFg[tier+1],800));layoutParams=LinearLayout.LayoutParams(-2,owner.px(30))
    }
    bindControl(mode){
        val tier=GpuDefaultsDraft.modes.indexOf(mode.displayed).coerceAtLeast(0)
        modeChip.background=owner.shape(owner.chipBg[tier+1],999f)
        (modeChip.getChildAt(0) as OwnerStatusDot).setTone(owner.tiers[tier])
        (modeChip.getChildAt(1) as TextView).apply{val caption="${modeTitle(mode.displayed)}模式";if(text.toString()!=caption)text=caption;setTextColor(owner.chipFg[tier+1])}
    }
    detail.addView(owner.title("系统全局状态","系统关键性能参数与组件运行情况",trailing=modeChip))
    val stats=owner.row()
    listOf("温度","电池净功率","核心组件").forEachIndexed{i,title ->
        val box=owner.card(false).apply{setPadding(owner.px(17),owner.px(15),owner.px(17),owner.px(17))}
        if(i==1){box.setOnClickListener{powerDialog()};box.isFocusable=true;box.contentDescription="电池净功率与外部输入详情"}
        box.addView(owner.row().apply{
            addView(owner.icon(listOf(R.drawable.owner_temperature,R.drawable.owner_power,R.drawable.owner_health)[i],owner.muted,16),LinearLayout.LayoutParams(owner.px(16),owner.px(16)).apply{marginEnd=owner.px(7)})
            addView(owner.label(title,12f,owner.sub,700))
        },LinearLayout.LayoutParams(-1,owner.px(22)))
        val metric=owner.label("--",if(i==2)20f else 28f,owner.text,800).apply{if(i<2)fontFeatureSettings="tnum"}
        val values=owner.row().apply{
            if(i==2){
                val slot=FrameLayout(this@renderTunePage).apply{clipChildren=true;addView(metric,FrameLayout.LayoutParams(-1,-1))};coreSlot=slot
                addView(slot,LinearLayout.LayoutParams(-1,owner.px(32)))
            }else addView(metric,LinearLayout.LayoutParams(-2,owner.px(32)))
            if(i<2)addView(owner.label(if(i==0)"℃" else "W",13f,owner.sub,700).also{if(i==1)powerUnit=it},LinearLayout.LayoutParams(-2,-2).apply{marginStart=owner.px(3);topMargin=owner.px(9)})
            if(i==1){powerReason=owner.label("",11.5f,owner.muted,700).apply{setSingleLine(false);maxLines=2};addView(powerReason,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=owner.px(8)})}
        }
        box.addView(values,LinearLayout.LayoutParams(-1,owner.px(32)).apply{topMargin=owner.px(12);bottomMargin=owner.px(12)})
        val meter=when(i){
            0 -> OwnerMeter(this,owner,listOf(.6667f,.8333f)).also{ownerQuiet=it;quietLabel=metric}
            1 -> OwnerMeter(this,owner,listOf(.35f,.7f)).also{ownerPower=it;powerLabel=metric}
            else -> {
                val health=BackendHealth.components(state)
                val bad=health.firstOrNull{it.state in setOf(BackendHealth.State.FAILED,BackendHealth.State.DEGRADED)}
                metric.text=if(health.all{it.state==BackendHealth.State.OK})"5/5 正常" else bad?.component ?: "状态未知"
                coreLabel=metric;if(bad!=null)metric.setTextColor(if(bad.state==BackendHealth.State.FAILED)owner.inks[3] else owner.inks[2])
                handler.postDelayed(coreTicker,2000);box.setOnClickListener{coreHealth()};box.contentDescription="核心组件详情";box.isFocusable=true
                OwnerMeter(this,owner,segments=health.map{when(it.state){BackendHealth.State.OK->owner.accent;BackendHealth.State.DEGRADED->owner.tiers[2];BackendHealth.State.FAILED->owner.tiers[3];else->owner.line2}}).also{healthMeter->
                    bindPresentation{
                        val fresh=BackendHealth.components(state);val bad=fresh.firstOrNull{it.state in setOf(BackendHealth.State.FAILED,BackendHealth.State.DEGRADED)}
                        coreLabel?.text=if(fresh.all{it.state==BackendHealth.State.OK})"5/5 正常" else bad?.component ?: "状态未知"
                        coreLabel?.setTextColor(if(bad?.state==BackendHealth.State.FAILED)owner.inks[3] else if(bad!=null)owner.inks[2] else owner.text)
                        healthMeter.showSegments(fresh.map{when(it.state){BackendHealth.State.OK->owner.accent;BackendHealth.State.DEGRADED->owner.tiers[2];BackendHealth.State.FAILED->owner.tiers[3];else->owner.line2}})
                    }
                }
            }
        }
        box.addView(meter,LinearLayout.LayoutParams(-1,owner.px(6)))
        stats.addView(box,LinearLayout.LayoutParams(0,owner.px(118),if(i==2)1.34f else 1f).apply{if(i>0)marginStart=owner.px(12)})
    }
    detail.addView(stats,owner.gap())
    detail.addView(owner.card().apply{
        addView(owner.section("全局刷新率","全局默认屏幕刷新率档位模式"))
        val rates=supportedRates();val refreshSelector=ownerControl(OwnerSegment(this@renderTunePage,owner,rates.map{"$it Hz"},rates.indexOf(refresh.displayed)){i->
            supportedRates().getOrNull(i)?.let{optimistic(refresh,it){target->session.gateway.setGlobal("refresh",value=target)}}
        });addView(refreshSelector);bindControl(refresh){val current=supportedRates();refreshSelector.showChoices(current.map{"$it Hz"},current.indexOf(refresh.displayed))}
        val unknown=owner.label("全局档位尚未确认",11f,owner.inks[2]);addView(unknown)
        bindControl(refresh){unknown.visibility=if(refresh.confirmed==0)View.VISIBLE else View.GONE}
        addView(owner.divider());addView(owner.section("全局性能档位","日常系统调度激进程度"))
        addView(ownerControl(owner.tiers(mode.displayed,reconcile={bind->bindControl(mode){bind(mode.displayed)}}){id->optimistic(mode,id){target->session.gateway.setGlobal("mode",mode=target)}}))
    },owner.gap())
    detail.addView(owner.card(false).apply{
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(owner.px(19),owner.px(16),owner.px(19),owner.px(16))
        addView(owner.icon(R.drawable.owner_pulse,owner.accent).apply{background=owner.shape(owner.soft(owner.accent),12f);setPadding(owner.px(10),owner.px(10),owner.px(10),owner.px(10))},LinearLayout.LayoutParams(owner.px(40),owner.px(40)).apply{marginEnd=owner.px(14)})
        addView(owner.column().apply{
            addView(owner.label("性能监视悬浮窗",14f,owner.text,800),LinearLayout.LayoutParams(-1,owner.px(20)))
            addView(owner.label("在屏幕顶部居中实时浮动展示帧率、温度与功耗；通过悬浮窗开始 / 停止记录",11f,owner.muted),LinearLayout.LayoutParams(-1,owner.px(14)).apply{topMargin=owner.px(3)})
        },LinearLayout.LayoutParams(0,-2,1f))
        addView(FrameLayout(this@renderTunePage).apply{
            background=owner.shape(if(this@renderTunePage.overlay.displayed)owner.accent else owner.line2,13f)
            ownerSwitch=View(this@renderTunePage).apply{background=owner.shape(Color.WHITE,99f);elevation=owner.px(2).toFloat();translationX=owner.px(if(this@renderTunePage.overlay.displayed)18 else 0).toFloat()}
            addView(ownerSwitch,FrameLayout.LayoutParams(owner.px(20),owner.px(20)).apply{leftMargin=owner.px(3);topMargin=owner.px(3)})
        },LinearLayout.LayoutParams(owner.px(44),owner.px(26)).apply{marginStart=owner.px(14)})
        isEnabled=true;alpha=1f;isFocusable=true;setOnClickListener{toggleOverlay()}
        bindControl(this@renderTunePage.overlay){
            contentDescription="性能监视悬浮窗，${if(this@renderTunePage.overlay.displayed)"已开启" else "已关闭"}"
            (ownerSwitch?.parent as? View)?.background=owner.shape(if(this@renderTunePage.overlay.displayed)owner.accent else owner.line2,13f)
        }
        tag="owner-control"
    },owner.gap())
}
