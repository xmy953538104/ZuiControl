package com.zui.zuicontrol

object ZuiControlContract {
    const val CMD_STATUS = "status"
    const val CMD_SET_UPERF_MODE = "set_uperf_mode"
    const val CMD_SET_UPERF_APP = "set_uperf_app"
    const val CMD_REMOVE_UPERF_APP = "remove_uperf_app"
    const val CMD_RESTART_SCHEDULER = "restart_scheduler"
    const val CMD_START_ASOUL = "start_asoul"
    const val CMD_STOP_ASOUL = "stop_asoul"
    const val CMD_EXPORT_LOGS = "export_logs"

    const val KEY_REQUEST_TEXT = "zui_control_request_text"
    const val KEY_REQUEST_ACK = "zui_control_request_ack"
    const val KEY_ACTIVE_REFRESH = "zui_control_active_refresh"
    const val KEY_SCENE_EVENT_TEXT = "zui_control_scene_event_text"
    const val KEY_STATUS_TEXT = "zui_control_status_text"
    const val KEY_ASOUL_HEALTH = "zui_control_asoul_health"
    const val KEY_UPERF_HEALTH = "zui_control_uperf_health"
    const val KEY_UPERF_MODE = "zui_control_uperf_mode"
    const val KEY_UPERF_RULES_TEXT = "zui_control_uperf_rules_text"
    const val KEY_LOG_EXPORT = "zui_control_log_export"

    const val ACTION_REFRESH_NOTIFICATION = "com.zui.zuicontrol.action.REFRESH_NOTIFICATION"
    const val ACTION_SET_60 = "com.zui.zuicontrol.action.SET_60"
    const val ACTION_SET_90 = "com.zui.zuicontrol.action.SET_90"
    const val ACTION_SET_120 = "com.zui.zuicontrol.action.SET_120"
    const val ACTION_SET_144 = "com.zui.zuicontrol.action.SET_144"
    const val ACTION_SET_165 = "com.zui.zuicontrol.action.SET_165"
    const val EXTRA_RATE = "rate"

    val rates = listOf(60, 90, 120, 144, 165)
}
