#!/system/bin/sh

echo '__ZUI_META_BEGIN__'
printf 'device_epoch=%s\n' "$(date +%s.%N)"
echo '__ZUI_META_END__'
echo '__ZUI_ACTIVITY_BEGIN__'
dumpsys activity activities
echo '__ZUI_ACTIVITY_END__'
echo '__ZUI_CONTROL_BEGIN__'
dumpsys zui_control
echo '__ZUI_CONTROL_END__'
echo '__ZUI_PROPERTIES_BEGIN__'
printf 'sys.zui_control.uperf_mode=%s\n' "$(getprop sys.zui_control.uperf_mode)"
printf 'effective_powermode=%s\n' "$(tr -d '\r\n' < /data/vendor/zui_control/uperf/effective_powermode.txt 2>/dev/null)"
printf 'cur_powermode=%s\n' "$(tr -d '\r\n' < /data/vendor/zui_control/uperf/cur_powermode.txt 2>/dev/null)"
printf 'uperf_service=%s\n' "$(getprop init.svc.zui_uperf)"
printf 'uperf_fail_safe=%s\n' "$(getprop sys.zui_control.uperf_fail_safe)"
printf 'system_server_process_id=%s\n' "$(pidof system_server 2>/dev/null)"
echo '__ZUI_PROPERTIES_END__'
