package com.zui.zuicontrol

import android.app.PendingIntent
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import java.util.Locale
import android.view.View
import android.widget.RemoteViews

/** Applies every visual/action property; no remembered selection or mutable RemoteViews cache. */
internal object NotificationQuickControlHelper {
    data class Snapshot(
        val pkg: String, val currentHz: Int, val currentMode: UperfMode,
        val isFloatActive: Boolean, val refreshEnabled: Boolean, val uperfEnabled: Boolean,
        val quietC: Double = -1.0, val powerW: Double = Double.NaN, val dark:Boolean=false,
        val refreshStatus:QuickControlState.Presentation?=null,
        val uperfStatus:QuickControlState.Presentation?=null,
        val monitorStatus:QuickControlState.Presentation?=null
    )

    fun updateRemoteViews(
        views: RemoteViews, snapshot: Snapshot,
        monitorIntent: PendingIntent, refreshIntent: (Int) -> PendingIntent,
        modeIntent: (UperfMode) -> PendingIntent
    ) = with(views) {
        setInt(R.id.quick_root, "setBackgroundResource", R.drawable.notify_card)
        for (id in listOf(R.id.quick_root, R.id.quick_controls, R.id.refresh_row, R.id.uperf_row,
                R.id.monitor_toggle, R.id.monitor_icon, R.id.quick_metrics,
                R.id.notification_quiet, R.id.notification_power)) {
            setViewVisibility(id, View.VISIBLE)
        }
        setInt(R.id.monitor_toggle, "setBackgroundResource",
            if(snapshot.monitorStatus?.phase in listOf(QuickControlState.Phase.PENDING,QuickControlState.Phase.VERIFYING)) R.drawable.notify_monitor_pending else R.drawable.notify_monitor_active)
        val monitorLabel=statusLabel(snapshot.monitorStatus)
        setTextViewText(R.id.monitor_status,monitorLabel)
        setViewVisibility(R.id.monitor_status,if(monitorLabel.isEmpty())View.GONE else View.VISIBLE)
        setBoolean(R.id.monitor_toggle, "setEnabled", true)
        setImageViewResource(R.id.monitor_icon, R.drawable.notify_monitor_ecg)
        setInt(R.id.monitor_icon, "setColorFilter",
            Color.WHITE)
        setImageViewResource(R.id.monitor_indicator, R.drawable.notify_monitor_indicator)
        setViewVisibility(R.id.monitor_indicator, if (snapshot.isFloatActive) View.VISIBLE else View.GONE)
        setContentDescription(R.id.monitor_toggle, "监视器 ${if (snapshot.isFloatActive) "开启" else "关闭"} $monitorLabel")
        setOnClickPendingIntent(R.id.monitor_toggle, monitorIntent)
        metric(views, R.id.notification_quiet, snapshot.quietC,
            "°C", "quiet-therm 温度",snapshot.dark)
        metric(views, R.id.notification_power, snapshot.powerW,
            " W", "净功率，正为充入、负为放出；输入模式显示外部输入",snapshot.dark,true)

        listOf(R.id.refresh_60, R.id.refresh_90, R.id.refresh_120, R.id.refresh_144, R.id.refresh_165)
            .zip(ZuiControlContract.rates).forEach { (id, value) ->
                button(views, id, value.toString(), value == snapshot.currentHz,
                    R.drawable.notify_rate_selected, snapshot.refreshEnabled, refreshIntent(value),
                    "${value}Hz",snapshot.dark,snapshot.refreshStatus?.takeIf{it.value==value.toString()})
            }
        val backgrounds = listOf(R.drawable.notify_mode_powersave, R.drawable.notify_mode_balance,
            R.drawable.notify_mode_performance, R.drawable.notify_mode_fast)
        listOf(R.id.mode_powersave, R.id.mode_balance, R.id.mode_performance, R.id.mode_fast)
            .zip(UperfMode.entries).forEach { (id, value) ->
                val title = value.title
                button(views, id, title, value == snapshot.currentMode, backgrounds[value.ordinal],
                    snapshot.uperfEnabled, modeIntent(value), title,snapshot.dark,snapshot.uperfStatus?.takeIf{it.value==value.id})
            }
    }

    internal fun availabilityChanged(oldQuiet: Double, newQuiet: Double, oldPower: Double, newPower: Double): Boolean =
        (oldQuiet > 0 && newQuiet < 0) || (oldPower.isFinite() != newPower.isFinite()) || oldPower*newPower<0

    fun metricNumber(value: Double): String =
        if (value.isFinite() && value > 0.0) String.format(Locale.US, "%.1f", value) else "--"

    private fun metric(views: RemoteViews, id: Int, value: Double, unit: String, description: String,dark:Boolean,power:Boolean=false) {
        val valid = value.isFinite() && (power || value > 0.0)
        val number = if(power)LivePower.number(value) else metricNumber(value)
        val text = SpannableString(number + unit)
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        views.setTextColor(id, if(valid)if(dark)Color.rgb(232,237,246)else Color.rgb(15,23,42) else if(dark)Color.rgb(108,120,144)else Color.rgb(142,155,174))
        if (valid) {
            text.setSpan(ForegroundColorSpan(if(dark)Color.rgb(91,140,255)else Color.rgb(53,98,198)), number.length, text.length, flags)
            text.setSpan(RelativeSizeSpan(9f / 11f), number.length, text.length, flags)
        }
        views.setTextViewText(id, text)
        views.setInt(id, "setBackgroundResource", R.drawable.notify_capsule_track)
        views.setBoolean(id, "setEnabled", true)
        views.setContentDescription(id, if (valid) "$description $text" else "$description 暂不可用")
    }

    private fun statusLabel(status:QuickControlState.Presentation?):String=when(status?.phase){
        QuickControlState.Phase.PENDING->"应用中"
        QuickControlState.Phase.VERIFYING->"核对中"
        QuickControlState.Phase.UNKNOWN->"结果未知"
        QuickControlState.Phase.FAILED->"失败"
        else->""
    }
    private fun button(views: RemoteViews, id: Int, text: String, selected: Boolean,
                       activeBackground: Int, enabled: Boolean, intent: PendingIntent, description: String,dark:Boolean,
                       status:QuickControlState.Presentation?=null) {
        val label=statusLabel(status)
        val pending=status?.phase in listOf(QuickControlState.Phase.PENDING,QuickControlState.Phase.VERIFYING)
        val title=SpannableString(if(label.isEmpty())text else "$text\n$label")
        if(label.isNotEmpty())title.setSpan(RelativeSizeSpan(.65f),text.length+1,title.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        views.setInt(id,"setMaxLines",2)
        views.setTextViewText(id, title)
        views.setTextColor(id, if(pending)Color.rgb(91,140,255) else if(selected && enabled)Color.WHITE else if(enabled)if(dark)Color.rgb(165,176,195)else Color.rgb(71,85,105) else if(dark)Color.rgb(108,120,144)else Color.rgb(142,155,174))
        views.setInt(id, "setBackgroundResource", if(pending)R.drawable.notify_pending else if (selected && enabled) activeBackground else R.drawable.notify_rate_normal)
        views.setViewVisibility(id, View.VISIBLE)
        views.setBoolean(id, "setEnabled", enabled)
        views.setContentDescription(id, "$description ${if (selected) "已选择" else "未选择"} ${if (enabled) "可用" else "不可配置"} $label")
        views.setOnClickPendingIntent(id, intent)
    }
}
