#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
DAEMON="${1:-$ROOT/payload/system/bin/zui_controld}"
DEFAULT_PERAPP="${2:-$ROOT/payload/system/etc/zui_control/default_uperf_perapp.txt}"

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

setup_state() {
    TEST_ROOT="$(mktemp -d)"
    DATA_ROOT="$TEST_ROOT/data"
    CONTROL_DIR="$DATA_ROOT/control"
    UPERF_DIR="$DATA_ROOT/uperf"
    ASOUL_DIR="$DATA_ROOT/asoul"
    LOG_DIR="$DATA_ROOT/log"
    LOG_FILE="$LOG_DIR/controld.log"
    UPERF_LOG="$LOG_DIR/uperf.log"
    STATUS_FILE="$CONTROL_DIR/status.prop"
    LAST_REQUEST_RECEIPT="$CONTROL_DIR/last_receipt"
    UPERF_MODE="$UPERF_DIR/cur_powermode.txt"
    UPERF_EFFECTIVE_MODE="$UPERF_DIR/effective_powermode.txt"
    UPERF_PERAPP="$UPERF_DIR/perapp_powermode.txt"
    ASOUL_CONFIG="$ASOUL_DIR/asopt.conf"
    mkdir -p "$CONTROL_DIR" "$UPERF_DIR" "$ASOUL_DIR" "$LOG_DIR"
    cp "$DEFAULT_PERAPP" "$UPERF_PERAPP"
    printf 'balance\n' > "$UPERF_MODE"
    printf 'balance\n' > "$UPERF_EFFECTIVE_MODE"
    printf 'mode=0\nrt=0\nopt=0xDEADBEEF\n' > "$ASOUL_CONFIG"
    : > "$UPERF_LOG"
    : > "$LOG_FILE"
    TEST_SCENE=com.kurogame.mingchao
    TEST_SCREEN=1
    TEST_REQUEST=
    TEST_ACK=
    TEST_MODE_STATE=
    TEST_RULES_STATE=

    settings_get_clean() {
        case "$1" in
            "$UPERF_SCENE_KEY") printf '%s\n' "$TEST_SCENE" ;;
            "$UPERF_SCREEN_KEY") printf '%s\n' "$TEST_SCREEN" ;;
            "$REQ_TEXT_KEY") printf '%s\n' "$TEST_REQUEST" ;;
            "$REQUEST_ACK_KEY") printf '%s\n' "$TEST_ACK" ;;
            *) printf '\n' ;;
        esac
    }
    settings_put_quiet() {
        case "$1" in
            "$REQUEST_ACK_KEY") TEST_ACK="$2" ;;
            "$UPERF_MODE_KEY") TEST_MODE_STATE="$2" ;;
            "$UPERF_RULES_KEY") TEST_RULES_STATE="$2" ;;
        esac
    }
    log_line() { :; }
    sync() { :; }
    sleep() { :; }
    pm() {
        [ "$1" = path ] && [ "$2" != com.android.systemui ] || return 1
        printf 'package:/data/app/%s/base.apk\n' "$2"
    }
}

assert_default_policy() {
    grep -qx 'com.kurogame.mingchao performance' "$DEFAULT_PERAPP" ||
        fail 'Mingchao default missing'
    grep -qx 'com.kurogame.wutheringwaves.global performance' "$DEFAULT_PERAPP" ||
        fail 'global Wuthering Waves default missing'
    grep -qx -- '- powersave' "$DEFAULT_PERAPP" || fail 'screen-off rule missing'
    if grep -q '^\* ' "$DEFAULT_PERAPP"; then fail 'per-app file still owns global fallback'; fi
}

test_resolution_order() (
    ZUI_CONTROLD_TEST_MODE=1
    export ZUI_CONTROLD_TEST_MODE
    # shellcheck source=/dev/null
    . "$DAEMON"
    setup_state
    trap 'rm -rf "$TEST_ROOT"' EXIT

    sync_uperf_frontend || fail 'initial frontend sync failed'
    [ "$(tr -d '\r\n ' < "$UPERF_EFFECTIVE_MODE")" = performance ] ||
        fail 'exact app did not override global mode'
    [ "$UPERF_FRONTEND_SOURCE" = app:com.kurogame.mingchao ] ||
        fail 'exact-app source not reported'

    TEST_SCREEN=0
    sync_uperf_frontend || fail 'screen-off sync failed'
    [ "$(tr -d '\r\n ' < "$UPERF_EFFECTIVE_MODE")" = powersave ] ||
        fail 'screen-off did not win over exact app'
    [ "$UPERF_FRONTEND_SOURCE" = screen_off ] || fail 'screen-off source not reported'

    TEST_SCREEN=1
    TEST_SCENE=com.example.unknown
    set_uperf_mode fast || fail 'valid global mode rejected'
    [ "$(tr -d '\r\n ' < "$UPERF_EFFECTIVE_MODE")" = fast ] ||
        fail 'global fallback not applied to unknown app'
    if set_uperf_mode auto; then fail 'retired auto mode accepted'; fi
)

test_custom_app_lifecycle() (
    ZUI_CONTROLD_TEST_MODE=1
    export ZUI_CONTROLD_TEST_MODE
    . "$DAEMON"
    setup_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    TEST_SCENE=com.example.game

    set_uperf_app_mode com.example.game fast || fail 'custom app rejected'
    grep -qx 'com.example.game fast' "$UPERF_PERAPP" || fail 'custom app not persisted'
    [ "$(tr -d '\r\n ' < "$UPERF_EFFECTIVE_MODE")" = fast ] ||
        fail 'custom app not applied immediately'
    if set_uperf_app_mode com.android.systemui fast; then fail 'system app accepted'; fi

    remove_uperf_app_mode com.example.game || fail 'custom app removal failed'
    if grep -q '^com.example.game ' "$UPERF_PERAPP"; then fail 'custom app survived removal'; fi
    [ "$(tr -d '\r\n ' < "$UPERF_EFFECTIVE_MODE")" = balance ] ||
        fail 'removed app did not return to global mode'
)

test_effective_inode_is_stable() (
    ZUI_CONTROLD_TEST_MODE=1
    export ZUI_CONTROLD_TEST_MODE
    . "$DAEMON"
    setup_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    before="$(stat -c %i "$UPERF_EFFECTIVE_MODE")"
    UPERF_FRONTEND_SOURCE=test
    write_uperf_effective_mode fast || fail 'effective write failed'
    after="$(stat -c %i "$UPERF_EFFECTIVE_MODE")"
    [ "$before" = "$after" ] || fail 'effective mode inode was replaced'
)

test_minimal_request_and_receipt() (
    ZUI_CONTROLD_TEST_MODE=1
    export ZUI_CONTROLD_TEST_MODE
    . "$DAEMON"
    setup_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    TEST_SCENE=com.example.unknown
    TEST_REQUEST='id-1|set_uperf_mode|||performance'
    LAST_SETTINGS_REQUEST=

    process_settings_request || fail 'settings request failed'
    [ "$TEST_ACK" = 'id-1|done|set_uperf_mode|global=performance;effective=performance' ] ||
        fail 'terminal ACK mismatch'
    [ "$(sed -n '1p' "$LAST_REQUEST_RECEIPT")" = "$TEST_REQUEST" ] ||
        fail 'request receipt missing request'
    [ "$(sed -n '2p' "$LAST_REQUEST_RECEIPT")" = "$TEST_ACK" ] ||
        fail 'request receipt missing ACK'
)

assert_default_policy
test_resolution_order
test_custom_app_lifecycle
test_effective_inode_is_stable
test_minimal_request_and_receipt
printf 'PASS: zui_controld Uperf/A-SOUL tests\n'
