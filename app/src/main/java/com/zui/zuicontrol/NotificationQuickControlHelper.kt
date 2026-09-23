package com.zui.zuicontrol

import android.app.PendingIntent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews

/** Applies every visual/action property; no remembered selection or mutable RemoteViews cache. */
internal object NotificationQuickControlHelper {
    data class Snapshot(
        val pkg: String, val currentHz: Int, val currentMode: UperfMode,
        val isFloatActive: Boolean, val refreshEnabled: Boolean, val uperfEnabled: Boolean
    )

    fun updateRemoteViews(
        views: RemoteViews, snapshot: Snapshot,
        monitorIntent: PendingIntent, refreshIntent: (Int) -> PendingIntent,
        modeIntent: (UperfMode) -> PendingIntent
    ) = with(views) {
        setInt(R.id.quick_root, "setBackgroundResource", R.drawable.notify_card)
        for (id in listOf(R.id.quick_root, R.id.quick_controls, R.id.refresh_row, R.id.uperf_row,
                R.id.monitor_toggle, R.id.monitor_icon)) {
            setViewVisibility(id, View.VISIBLE)
        }
        setInt(R.id.monitor_toggle, "setBackgroundResource",
            R.drawable.notify_monitor_active)
        setBoolean(R.id.monitor_toggle, "setEnabled", true)
        setImageViewResource(R.id.monitor_icon, R.drawable.ic_tool_monitor)
        setInt(R.id.monitor_icon, "setColorFilter",
            Color.WHITE)
        setImageViewResource(R.id.monitor_indicator, R.drawable.notify_monitor_indicator)
        setViewVisibility(R.id.monitor_indicator, if (snapshot.isFloatActive) View.VISIBLE else View.GONE)
        setContentDescription(R.id.monitor_toggle, "监视器 ${if (snapshot.isFloatActive) "开启" else "关闭"}")
        setOnClickPendingIntent(R.id.monitor_toggle, monitorIntent)

        listOf(R.id.refresh_60, R.id.refresh_90, R.id.refresh_120, R.id.refresh_144, R.id.refresh_165)
            .zip(ZuiControlContract.rates).forEach { (id, value) ->
                button(views, id, value.toString(), value == snapshot.currentHz,
                    R.drawable.notify_rate_selected, snapshot.refreshEnabled, refreshIntent(value),
                    "${value}Hz")
            }
        val backgrounds = listOf(R.drawable.notify_mode_powersave, R.drawable.notify_mode_balance,
            R.drawable.notify_mode_performance, R.drawable.notify_mode_fast)
        listOf(R.id.mode_powersave, R.id.mode_balance, R.id.mode_performance, R.id.mode_fast)
            .zip(UperfMode.entries).forEach { (id, value) ->
                val title = if (value == UperfMode.FAST) "极速" else value.title
                button(views, id, title, value == snapshot.currentMode, backgrounds[value.ordinal],
                    snapshot.uperfEnabled, modeIntent(value), title)
            }
    }

    private fun button(views: RemoteViews, id: Int, text: String, selected: Boolean,
                       activeBackground: Int, enabled: Boolean, intent: PendingIntent, description: String) {
        views.setTextViewText(id, text)
        views.setTextColor(id, if (selected) Color.WHITE else Color.rgb(51, 65, 85))
        views.setInt(id, "setBackgroundResource", if (selected) activeBackground else R.drawable.notify_rate_normal)
        views.setViewVisibility(id, View.VISIBLE)
        views.setBoolean(id, "setEnabled", enabled)
        views.setContentDescription(id, "$description ${if (selected) "已选择" else "未选择"}")
        views.setOnClickPendingIntent(id, intent)
    }
}
