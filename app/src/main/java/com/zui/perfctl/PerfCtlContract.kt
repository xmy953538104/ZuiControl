package com.zui.perfctl

object PerfCtlContract {
    const val CMD_APPLY_ZUIPP = "apply_zuipp"
    const val CMD_RESTORE_ZUIPP = "restore_zuipp"
    const val CMD_RESTART_ASOUL = "restart_asoul"
    const val CMD_SET_REFRESH = "set_refresh"
    const val CMD_RESTORE_REFRESH = "restore_refresh"
    const val CMD_LEARN_REFRESH = "learn_refresh"
    const val CMD_REMOVE_REFRESH_RULE = "remove_refresh_rule"
    const val CMD_STATUS = "status"
    const val CMD_SET_PERFORMANCE_PROFILE = "set_performance_profile"
    const val CMD_REMOVE_PERFORMANCE_PROFILE = "remove_performance_profile"
    const val CMD_APPLY_PERFORMANCE = "apply_performance"
    const val CMD_EXPORT_LOGS = "export_logs"

    const val KEY_REQUEST_TEXT = "zui_perfctl_request_text"
    const val KEY_STATUS_TIME = "zui_perfctl_status_time"
    const val KEY_STATUS_LAST = "zui_perfctl_status_last"
    const val KEY_STATUS_TEXT = "zui_perfctl_status_text"
    const val KEY_RULES_TEXT = "zui_perfctl_refresh_rules_text"
    const val KEY_PERFORMANCE_PROFILES_TEXT = "zui_perfctl_performance_profiles_text"
    const val KEY_PERFORMANCE_SUMMARY = "zui_perfctl_performance_summary"
    const val KEY_TOP_PACKAGE = "zui_perfctl_top_package"
    const val KEY_ACTIVE_REFRESH = "zui_perfctl_active_refresh"
    const val KEY_GPU_STATE = "zui_perfctl_gpu_state"
    const val KEY_ASOUL_HEALTH = "zui_perfctl_asoul_health"
    const val KEY_XML_STATE = "zui_perfctl_xml_state"
    const val KEY_LOG_EXPORT = "zui_perfctl_log_export"

    const val ACTION_REFRESH_NOTIFICATION = "com.zui.zuiperfctl.action.REFRESH_NOTIFICATION"
    const val ACTION_SET_60 = "com.zui.zuiperfctl.action.SET_60"
    const val ACTION_SET_90 = "com.zui.zuiperfctl.action.SET_90"
    const val ACTION_SET_120 = "com.zui.zuiperfctl.action.SET_120"
    const val ACTION_SET_144 = "com.zui.zuiperfctl.action.SET_144"
    const val ACTION_SET_165 = "com.zui.zuiperfctl.action.SET_165"
    const val EXTRA_RATE = "rate"

    val rates = listOf(60, 90, 120, 144, 165)
}
