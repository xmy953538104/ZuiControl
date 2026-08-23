#!/usr/bin/env bash
set -eo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DAEMON="$ROOT/payload/system/bin/zui_controld"
DEFAULT_PERAPP="$ROOT/payload/system/etc/zui_control/default_uperf_perapp.txt"

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

setup_state() {
    TEST_ROOT="$(mktemp -d)"
    DATA_ROOT="$TEST_ROOT/data"
    UPERF_DIR="$DATA_ROOT/uperf"
    ASOUL_DIR="$DATA_ROOT/asoul"
    LOG_DIR="$DATA_ROOT/log"
    CONTROL_DIR="$DATA_ROOT/control"
    UPERF_MODE="$UPERF_DIR/cur_powermode.txt"
    UPERF_PERAPP="$UPERF_DIR/perapp_powermode.txt"
    UPERF_LOG="$LOG_DIR/uperf.log"
    APPOPT_ENABLED="$DATA_ROOT/appopt/enabled.flag"
    LAST_REQUEST_FILE="$CONTROL_DIR/last_request"
    LAST_REQUEST_RECEIPT_FILE="$CONTROL_DIR/last_receipt"
    XML_STATE_KEY=zui_control_xml_state
    UPERF_HEALTH_KEY=zui_control_uperf_health
    UPERF_MODE_KEY=zui_control_uperf_mode
    UPERF_RULES_KEY=zui_control_uperf_rules_text
    ASOUL_HEALTH_KEY=zui_control_asoul_health
    APPOPT_RULES_KEY=zui_control_appopt_rules_text
    REQUEST_RESULT_DETAIL=
    mkdir -p "$UPERF_DIR" "$ASOUL_DIR" "$LOG_DIR" "$CONTROL_DIR" "$(dirname "$APPOPT_ENABLED")"
    cp "$DEFAULT_PERAPP" "$UPERF_PERAPP"
    printf 'balance\n' > "$UPERF_MODE"
    : > "$UPERF_LOG"

    declare -gA TEST_SETTINGS=()
    settings_get_clean() { printf '%s\n' "${TEST_SETTINGS[$1]-}"; }
    settings_put_quiet() { TEST_SETTINGS["$1"]="$2"; }
    log_line() { :; }
    sync() { :; }
    sleep() { :; }
    pm() {
        [[ "$1" == path && "$2" != com.android.systemui ]] || return 1
        printf 'package:/data/app/%s/base.apk\n' "$2"
    }
}

assert_default_policy() {
    grep -qx 'com.kurogame.mingchao performance' "$DEFAULT_PERAPP" ||
        fail 'Mingchao is not performance by default'
    grep -qx 'com.kurogame.wutheringwaves.global performance' "$DEFAULT_PERAPP" ||
        fail 'global Wuthering Waves is not performance by default'
    grep -qx -- '- powersave' "$DEFAULT_PERAPP" || fail 'screen-off powersave rule missing'
    grep -qx '\* balance' "$DEFAULT_PERAPP" || fail 'global balance fallback missing'
    [[ "$(grep -Ec '^[^#[:space:]]+[[:space:]]+(powersave|balance|performance|fast)$' "$DEFAULT_PERAPP")" == 4 ]] ||
        fail 'unexpected active default rule count'
}

test_mode_updates() (
    export ZUI_CONTROLD_TEST_MODE=1
    # shellcheck source=/dev/null
    source "$DAEMON"
    setup_state
    trap 'rm -rf "$TEST_ROOT"' EXIT

    set_uperf_mode performance || fail 'performance mode rejected'
    [[ "$(tr -d '\r\n ' < "$UPERF_MODE")" == performance ]] || fail 'mode file not updated'
    grep -qx '\* performance' "$UPERF_PERAPP" || fail 'global per-app fallback not updated'
    grep -qx -- '- powersave' "$UPERF_PERAPP" || fail 'screen-off rule changed with global mode'
    [[ "${TEST_SETTINGS[$UPERF_MODE_KEY]}" == performance ]] || fail 'mode state not published'
    cp "$UPERF_MODE" "$TEST_ROOT/mode.before"
    set_uperf_mode crazy && fail 'unsupported crazy mode accepted'
    cmp -s "$UPERF_MODE" "$TEST_ROOT/mode.before" || fail 'invalid mode changed file'
)

test_per_app_updates() (
    export ZUI_CONTROLD_TEST_MODE=1
    # shellcheck source=/dev/null
    source "$DAEMON"
    setup_state
    trap 'rm -rf "$TEST_ROOT"' EXIT

    set_uperf_app_mode com.example.game fast || fail 'valid app mode rejected'
    [[ "$(grep -c '^com.example.game fast$' "$UPERF_PERAPP")" == 1 ]] || fail 'app rule not inserted once'
    first_special="$(grep -nE '^(-|\*) ' "$UPERF_PERAPP" | head -n1 | cut -d: -f1)"
    app_line="$(grep -n '^com.example.game ' "$UPERF_PERAPP" | cut -d: -f1)"
    (( app_line < first_special )) || fail 'app rule must precede special fallbacks'

    set_uperf_app_mode com.example.game powersave || fail 'existing app rule update failed'
    [[ "$(grep -c '^com.example.game powersave$' "$UPERF_PERAPP")" == 1 ]] || fail 'updated rule is not canonical'
    [[ "$(grep -c '^com.example.game ' "$UPERF_PERAPP")" == 1 ]] || fail 'duplicate app rule left behind'
    [[ "${TEST_SETTINGS[$UPERF_RULES_KEY]}" == *'com.example.game|powersave'* ]] || fail 'rules state not published'

    cp "$UPERF_PERAPP" "$TEST_ROOT/perapp.before"
    set_uperf_app_mode com.android.systemui fast && fail 'system package accepted'
    set_uperf_app_mode com.example.game crazy && fail 'unsupported app mode accepted'
    cmp -s "$UPERF_PERAPP" "$TEST_ROOT/perapp.before" || fail 'rejected rule changed config'

    remove_uperf_app_mode com.example.game || fail 'app rule removal failed'
    ! grep -q '^com.example.game ' "$UPERF_PERAPP" || fail 'app rule remains after removal'
    grep -qx -- '- powersave' "$UPERF_PERAPP" || fail 'screen-off fallback was damaged'
    grep -qx '\* balance' "$UPERF_PERAPP" || fail 'global fallback was damaged'
)

test_owner_routing() (
    export ZUI_CONTROLD_TEST_MODE=1
    # shellcheck source=/dev/null
    source "$DAEMON"
    setup_state
    trap 'rm -rf "$TEST_ROOT"' EXIT

    handle_request test set_uperf_mode '' '' performance || fail 'new Uperf command failed'
    [[ "$REQUEST_RESULT_DETAIL" == mode=performance ]] || fail 'new command detail missing'
    REQUEST_RESULT_DETAIL=
    handle_request test sync_xml_refresh || fail 'retired XML refresh should be an idempotent success'
    [[ "$REQUEST_RESULT_DETAIL" == owner=uperf\;p2=retired ]] || fail 'retired XML owner detail wrong'

    for old in set_performance_profile set_appopt_rule replace_appopt_rules stop_appopt; do
        REQUEST_RESULT_DETAIL=
        handle_request test "$old" && fail "retired command accepted: $old"
        [[ "$REQUEST_RESULT_DETAIL" == *owner=uperf* ]] || fail "retired command did not identify Uperf owner: $old"
    done
)

test_scheduler_lifecycle() (
    export ZUI_CONTROLD_TEST_MODE=1
    # shellcheck source=/dev/null
    source "$DAEMON"
    setup_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    declare -a ACTIONS=()
    trigger_init_action() { ACTIONS+=("$1=$2"); return 0; }
    pidof() {
        case "$1" in uperf) printf '101\n' ;; AsoulOpt) printf '102\n' ;; *) return 1 ;; esac
    }
    ps() { printf 'u:r:performanced:s0 root 101 1 uperf\nu:r:performanced:s0 root 102 1 AsoulOpt\n'; }

    restart_scheduler || fail 'scheduler restart failed with both services present'
    [[ "${ACTIONS[*]}" == *'zui_control.scheduler=restart'* ]] || fail 'scheduler restart property not triggered'
    [[ "$REQUEST_RESULT_DETAIL" == uperf=running\;asoul=running ]] || fail 'scheduler restart detail wrong'

    ACTIONS=()
    boot_restore
    [[ "${ACTIONS[*]}" == *'zui_control.zuipp=restore'* ]] || fail 'stock ZuiPP restore not triggered'
    [[ "${ACTIONS[*]}" == *'zui_control.appopt=stop'* ]] || fail 'AppOpt stop not triggered'
    [[ "${TEST_SETTINGS[$XML_STATE_KEY]}" == state=retired\;owner=uperf ]] || fail 'retired XML state not published'
)

assert_default_policy
test_mode_updates
test_per_app_updates
test_owner_routing
test_scheduler_lifecycle
printf 'zui_controld Uperf ownership and scheduler transactions: OK\n'
