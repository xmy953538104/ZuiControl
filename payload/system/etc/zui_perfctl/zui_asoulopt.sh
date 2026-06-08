#!/system/bin/sh

LOG_DIR=/data/zui_perfctl/log
LOG=$LOG_DIR/asoulopt.log
CFG=/system/etc/asopt.conf

log_msg() {
    echo "$(date '+%F %T') $*" >> "$LOG"
}

lock_val() {
    value="$1"
    shift
    for node in "$@"; do
        [ -e "$node" ] || continue
        chown 0:0 "$node" 2>/dev/null
        chmod +w "$node" 2>/dev/null
        if ! echo "$value" > "$node" 2>/dev/null; then
            log_msg "failed to set $node=$value"
        fi
        chmod a-w "$node" 2>/dev/null
    done
}

mkdir -p "$LOG_DIR"
chmod 0755 /data/zui_perfctl "$LOG_DIR" 2>/dev/null

until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done
sleep 5

rm -rf /data/adb/modules/ct_module 2>/dev/null

lock_val 0 /sys/module/migt/parameters/*cluster

killall -15 AsoulOpt 2>/dev/null

if [ -r "$CFG" ]; then
    log_msg "AsoulOpt config: $(tr '\n' ' ' < "$CFG")"
else
    log_msg "AsoulOpt config missing: $CFG"
fi

log_msg "prepared AsoulOpt runtime; init will start /system/bin/AsoulOpt"

exit 0
