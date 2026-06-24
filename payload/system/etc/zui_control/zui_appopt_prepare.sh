#!/system/bin/sh

DATA_ROOT=/data/vendor/zui_control
APPOPT_DIR=$DATA_ROOT/appopt
LOG_DIR=$DATA_ROOT/log
LOG=$LOG_DIR/appopt.log
CFG=$APPOPT_DIR/applist.conf
DEFAULT_CFG=/system/etc/zui_control/default_applist.conf

ts() {
    date '+%Y-%m-%d %H:%M:%S'
}

log_msg() {
    mkdir -p "$LOG_DIR"
    printf '%s %s\n' "$(ts)" "$*" >> "$LOG"
}

copy_if_missing() {
    src="$1"
    dst="$2"
    if [ -s "$dst" ]; then
        return 0
    fi
    if [ -s "$src" ]; then
        cp "$src" "$dst" && chmod 0664 "$dst"
        return $?
    fi
    : > "$dst"
    chmod 0664 "$dst"
}

mkdir -p "$APPOPT_DIR" "$LOG_DIR"
chmod 0775 "$DATA_ROOT" "$APPOPT_DIR" "$LOG_DIR" 2>/dev/null || true
restorecon_recursive "$DATA_ROOT" >/dev/null 2>&1 || true

copy_if_missing "$DEFAULT_CFG" "$CFG"
chmod 0664 "$CFG" 2>/dev/null || true

if command -v killall >/dev/null 2>&1; then
    killall -15 AsoulOpt 2>/dev/null || true
fi
umount /system/etc/asopt.conf >/dev/null 2>&1 || true

log_msg "prepared AppOpt runtime: cfg=$CFG"
