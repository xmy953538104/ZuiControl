#!/system/bin/sh

ZUI_DIR=/data/vendor/zui_control/zuipp
STAGING_DIR=$ZUI_DIR/staging
ACTIVE_DIR=$ZUI_DIR/active
STATE_DIR=$ZUI_DIR/state
STATUS_FILE=$STATE_DIR/promote_helper.status

write_status() {
    mkdir -p "$STATE_DIR"
    echo "$(date '+%F %T') $*" > "$STATUS_FILE"
    chmod 0664 "$STATUS_FILE" 2>/dev/null || true
}

GAME_SRC=$STAGING_DIR/game_policy.xml
PERF_SRC=$STAGING_DIR/performanceconfig.xml
GAME_DST=$ACTIVE_DIR/game_policy.xml
PERF_DST=$ACTIVE_DIR/performanceconfig.xml
GAME_TMP=$ACTIVE_DIR/game_policy.xml.tmp
PERF_TMP=$ACTIVE_DIR/performanceconfig.xml.tmp

mkdir -p "$ACTIVE_DIR" "$STATE_DIR" || {
    write_status "failed mkdir active"
    exit 1
}

[ -s "$GAME_SRC" ] && [ -s "$PERF_SRC" ] || {
    write_status "missing staging XML"
    exit 1
}

rm -f "$GAME_TMP" "$PERF_TMP"

if cp "$GAME_SRC" "$GAME_TMP" &&
    cp "$PERF_SRC" "$PERF_TMP" &&
    chmod 0664 "$GAME_TMP" "$PERF_TMP" &&
    mv "$GAME_TMP" "$GAME_DST" &&
    mv "$PERF_TMP" "$PERF_DST"; then
    write_status "ok"
    exit 0
fi

rm -f "$GAME_TMP" "$PERF_TMP"
write_status "copy failed"
exit 1
