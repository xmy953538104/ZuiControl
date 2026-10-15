package com.zui.zuicontrol

import org.json.JSONObject
import java.util.Locale

/** Presentation only: never smooth, sample or replace missing data with zero. */
internal object LivePower {
    fun value(snapshot: JSONObject, input: Boolean = false): Double =
        if (snapshot.optLong("elapsedMs") > 0)
            snapshot.optDouble(if (input) "inputPowerW" else "batteryNetPowerW", Double.NaN)
        else Double.NaN

    fun number(watts: Double): String =
        if (watts.isFinite()) String.format(Locale.ROOT, "%.1f", if (watts == 0.0) 0.0 else watts) else "--"

    fun reason(snapshot: JSONObject, input: Boolean = false): String = when(snapshot.optString(if(input)"inputPowerValidity" else "batteryNetValidity")) {
        "DRIVER_READING" -> "驱动读值 · 测量时刻未知"
        "WAITING_SOURCE_UPDATE" -> "等待传感器更新"
        "WAITING_POWER_STATE" -> "等待供电状态更新"
        "DISCONNECTED" -> "未连接外部电源"
        "UNQUALIFIED_PROTOCOL_INPUT_CURRENT" -> "当前快充通路电流尚未验证"
        "UNAVAILABLE_PORT_MAPPING" -> "端口不可读或双口同时连接"
        "UNAVAILABLE_SOURCE_RANGE" -> "电压/电流无有效读数"
        else -> "当前不可读"
    }
}
