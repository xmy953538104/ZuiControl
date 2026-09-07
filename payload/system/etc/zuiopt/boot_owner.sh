#!/system/bin/sh
# One synchronous init invocation, never a watchdog. ro.* is write-once per boot.
[ "$(id -u)" = 0 ] || exit 126
[ -z "$(getprop ro.zui_control.task_owner)" ] || exit 0
owner="$(/system/bin/ZUIopt --boot)" || owner=ASOULOPT
case "$owner" in ASOULOPT|ZUIOPT) ;; *) owner=ASOULOPT ;; esac
setprop ro.zui_control.task_owner "$owner"
