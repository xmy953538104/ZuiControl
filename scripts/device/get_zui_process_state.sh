#!/system/bin/sh

read_value() {
    if [ -r "$1" ]; then
        tr -d '\r\n' < "$1"
    fi
}

printf 'system_server_process_id=%s\n' "$(pidof system_server 2>/dev/null)"
printf 'uperf_service=%s\n' "$(getprop init.svc.zui_uperf)"
printf 'uperf_supervisor_process_id=%s\n' "$(pidof zui_uperf_supervisor 2>/dev/null)"
printf 'uperf_process_ids=%s\n' "$(pidof uperf 2>/dev/null)"
printf 'asoulopt_service=%s\n' "$(getprop init.svc.zui_asoulopt)"
printf 'asoulopt_process_ids=%s\n' "$(pidof AsoulOpt 2>/dev/null)"
printf 'uperf_fail_safe=%s\n' "$(getprop sys.zui_control.uperf_fail_safe)"
printf 'uperf_mode=%s\n' "$(getprop sys.zui_control.uperf_mode)"
printf 'uperf_ready_uptime=%s\n' "$(read_value /data/vendor/zui_control/uperf/.service_ready_uptime)"
