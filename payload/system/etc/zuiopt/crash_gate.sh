#!/system/bin/sh
# Three failures in 60 seconds stop this owner; there is no same-boot replacement.
[ "$(id -u)" = 0 ] || exit 126
[ "$(getprop sys.zui_control.zuiopt_failed)" = 0 ] || exit 0
failed="$(/system/bin/ZUIopt --crash)" || failed=1
case "$failed" in 0) exit 0 ;; esac
setprop sys.zui_control.zuiopt_failed 1
