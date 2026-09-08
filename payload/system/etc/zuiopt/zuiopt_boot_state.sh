#!/system/bin/sh
# Boot only: initialization errors leave Android default scheduling.
[ "$(id -u)" = 0 ] || exit 126
# No same-boot reset path: this helper is invoked only at post-fs-data.
state="$(/system/bin/ZUIopt --boot)" || state=FAILSAFE
case "$state" in
    READY_TO_START) setprop sys.zui_control.zuiopt_failed 0 ;;
    *) setprop sys.zui_control.zuiopt_failed 1 ;;
esac
