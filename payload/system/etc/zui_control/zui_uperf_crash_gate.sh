#!/system/bin/sh
set -eu

UPERF_DIR=/data/vendor/zui_control/uperf
READY_UPTIME=$UPERF_DIR/.service_ready_uptime
CRASH_STATE=$UPERF_DIR/.service_rapid_crashes
FAIL_SAFE_PROP=sys.zui_control.uperf_fail_safe

[ "$(getprop sys.zui_control.scheduler_active)" = 1 ] || exit 0
IFS=' ' read -r uptime _rest < /proc/uptime
now="${uptime%%.*}"
ready=
[ ! -r "$READY_UPTIME" ] || IFS= read -r ready < "$READY_UPTIME"
case "$ready" in ''|*[!0-9]*) rapid=1 ;; *)
    runtime=$((now - ready))
    [ "$runtime" -ge 0 ] && [ "$runtime" -le 2 ] && rapid=1 || rapid=0
    ;;
esac

count=0
[ ! -r "$CRASH_STATE" ] || IFS= read -r count < "$CRASH_STATE"
case "$count" in ''|*[!0-9]*) count=0 ;; esac
if [ "$rapid" -eq 1 ]; then
    count=$((count + 1))
else
    count=0
fi
printf '%s\n' "$count" > "$CRASH_STATE.tmp"
chmod 0600 "$CRASH_STATE.tmp"
mv -f "$CRASH_STATE.tmp" "$CRASH_STATE"
rm -f "$READY_UPTIME"

[ "$count" -lt 3 ] || setprop "$FAIL_SAFE_PROP" 1
exit 0
