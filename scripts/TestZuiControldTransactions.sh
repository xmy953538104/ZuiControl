#!/usr/bin/env bash
set -eo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DAEMON="$ROOT/payload/system/bin/zui_controld"

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

setup_test_state() {
    TEST_ROOT="$(mktemp -d)"
    DATA_ROOT="$TEST_ROOT/data"
    CONTROL_DIR="$DATA_ROOT/zuicontrol"
    LOG_DIR="$DATA_ROOT/log"
    PERFORMANCE_DIR="$DATA_ROOT/performance"
    APPOPT_DIR="$DATA_ROOT/appopt"
    ZUI_DIR="$DATA_ROOT/zuipp"
    ZUI_ACTIVE_DIR="$ZUI_DIR/active"
    ZUI_STAGING_DIR="$ZUI_DIR/staging"
    ZUI_LAST_GOOD_DIR="$ZUI_DIR/last_good"
    ZUI_STATE_DIR="$ZUI_DIR/state"
    ACTIVE_GAME_POLICY="$ZUI_ACTIVE_DIR/game_policy.xml"
    ACTIVE_PERF_CONFIG="$ZUI_ACTIVE_DIR/performanceconfig.xml"
    STAGING_GAME_POLICY="$ZUI_STAGING_DIR/game_policy.xml"
    STAGING_PERF_CONFIG="$ZUI_STAGING_DIR/performanceconfig.xml"
    LAST_GOOD_GAME_POLICY="$ZUI_LAST_GOOD_DIR/game_policy.xml"
    LAST_GOOD_PERF_CONFIG="$ZUI_LAST_GOOD_DIR/performanceconfig.xml"
    PERFORMANCE_PROFILES="$PERFORMANCE_DIR/profiles.prop"
    PERFORMANCE_SUMMARY="$PERFORMANCE_DIR/summary.txt"
    PERFORMANCE_TXN_BACKUP="$PERFORMANCE_DIR/profiles.prop.txn_backup"
    PERFORMANCE_TXN_MARKER="$PERFORMANCE_DIR/profile_txn.prop"
    APPOPT_CONFIG="$APPOPT_DIR/applist.conf"
    APPOPT_ENABLED="$APPOPT_DIR/enabled.flag"
    APPOPT_UPDATE_BACKUP="$APPOPT_DIR/applist.conf.update_backup"
    DEFAULT_APPLIST="$TEST_ROOT/default_applist.conf"
    APPOPT_LOG="$LOG_DIR/appopt.log"
    XML_LAST_ERROR_FILE="$ZUI_STATE_DIR/last_error.txt"
    XML_STATUS_FILE="$ZUI_STATE_DIR/status.prop"
    XML_ACTIVE_SHA_FILE="$ZUI_STATE_DIR/active.sha256"
    ZUIPP_LAST_RELOADED_HASH_FILE="$ZUI_STATE_DIR/last_reloaded_hash"
    ZUIPP_APPLY_LOCK_DIR="$ZUI_STATE_DIR/apply.lock"
    ZUI_ENABLED="$ZUI_DIR/enabled.flag"
    STATUS_FILE="$CONTROL_DIR/status.prop"
    LAST_REQUEST_FILE="$CONTROL_DIR/last_processed_settings_request_text"
    LAST_REQUEST_RECEIPT_FILE="$CONTROL_DIR/last_processed_settings_request_receipt"
    LOG_FILE="$LOG_DIR/controld.log"
    mkdir -p "$CONTROL_DIR" "$LOG_DIR" "$PERFORMANCE_DIR" "$APPOPT_DIR" \
        "$ZUI_ACTIVE_DIR" "$ZUI_STAGING_DIR" "$ZUI_LAST_GOOD_DIR" \
        "$ZUI_STATE_DIR"

    printf '<AppPolicy>A</AppPolicy>\n' > "$ACTIVE_GAME_POLICY"
    printf '<GameLimitConfig>A</GameLimitConfig>\n' > "$ACTIVE_PERF_CONFIG"
    cp "$ACTIVE_GAME_POLICY" "$LAST_GOOD_GAME_POLICY"
    cp "$ACTIVE_PERF_CONFIG" "$LAST_GOOD_PERF_CONFIG"
    printf 'com.example.old|balanced|v2|-1000,100,50,200,100,300,150,400,200,500,250\n' \
        > "$PERFORMANCE_PROFILES"
    printf '# AppOpt test config\n' > "$DEFAULT_APPLIST"
    cp "$DEFAULT_APPLIST" "$APPOPT_CONFIG"
    printf '%s\n' "$(xml_hash_pair)" > "$ZUIPP_LAST_RELOADED_HASH_FILE"

    declare -gA TEST_SETTINGS=()
    declare -ga ACK_HISTORY=()
    settings_get_clean() {
        printf '%s\n' "${TEST_SETTINGS[$1]-}"
    }
    settings_put_quiet() {
        TEST_SETTINGS["$1"]="$2"
        if [[ "$1" == "$REQUEST_ACK_KEY" ]]; then
            ACK_HISTORY+=("$2")
        fi
    }
    log_line() { :; }
    sync() { :; }
    fsync() { :; }
    pm() {
        [[ "$1" == path ]] || return 1
        printf 'package:/data/app/%s/base.apk\n' "$2"
    }
    service() {
        [[ "$1" == call && "$2" == "$ZUI_MODE_SERVICE" ]] || return 1
        printf "Result: Parcel( 00000000 00000001 )\n"
    }
    package_has_process() { return 1; }
}

run_profile_failure_case() (
    export ZUI_CONTROLD_TEST_MODE=1
    # shellcheck source=/dev/null
    source "$DAEMON"
    setup_test_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    cp "$PERFORMANCE_PROFILES" "$TEST_ROOT/original_profiles"
    generate_performance_xml() { return 1; }

    case "$1" in
        legacy)
            set_performance_profile com.example.game balanced \
                100 50 200 100 300 150 400 200 500 250 &&
                fail 'legacy set unexpectedly succeeded'
            ;;
        staged)
            set_performance_profile_staged com.example.game balanced \
                '-1000,100,50,200,100,300,150,400,200,500,250' \
                independent default && fail 'staged set unexpectedly succeeded'
            ;;
        remove)
            printf 'com.example.game|balanced|v2|-1000,100,50,200,100,300,150,400,200,500,250\n' \
                >> "$PERFORMANCE_PROFILES"
            cp "$PERFORMANCE_PROFILES" "$TEST_ROOT/original_profiles"
            remove_performance_profile com.example.game balanced &&
                fail 'remove unexpectedly succeeded'
            ;;
    esac

    cmp -s "$PERFORMANCE_PROFILES" "$TEST_ROOT/original_profiles" ||
        fail "$1 failure did not restore profiles.prop"
    [[ ! -e "$PERFORMANCE_TXN_MARKER" ]] || fail "$1 left transaction marker"
)

test_profile_failures() {
    run_profile_failure_case legacy
    run_profile_failure_case staged
    run_profile_failure_case remove
}

test_profile_success() (
    export ZUI_CONTROLD_TEST_MODE=1
    # shellcheck source=/dev/null
    source "$DAEMON"
    setup_test_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    generate_performance_xml() { return 0; }
    promote_staging_to_active() {
        printf '<AppPolicy>B</AppPolicy>\n' > "$ACTIVE_GAME_POLICY"
        printf '<GameLimitConfig>B</GameLimitConfig>\n' > "$ACTIVE_PERF_CONFIG"
        atomic_write_text "$ZUIPP_LAST_RELOADED_HASH_FILE" "$(xml_hash_pair)"
    }
    set_performance_profile_staged com.example.game balanced \
        '-1000,100,50,200,100,300,150,400,200,500,250' \
        independent default || fail 'successful staged set failed'
    grep -q '^com.example.game|balanced|v4|' "$PERFORMANCE_PROFILES" ||
        fail 'successful staged set did not commit profile'
    [[ ! -e "$PERFORMANCE_TXN_MARKER" ]] || fail 'commit left transaction marker'
    [[ ! -e "$PERFORMANCE_TXN_BACKUP" ]] || fail 'commit left transaction backup'
    [[ "$(last_reloaded_hash_pair)" == "$(xml_hash_pair)" ]] ||
        fail 'successful transaction did not close runtime hash'
)

test_reload_failure_propagates() (
    export ZUI_CONTROLD_TEST_MODE=1
    # shellcheck source=/dev/null
    source "$DAEMON"
    setup_test_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    LAST_STATUS=
    reload_zuipp_if_needed() { return 1; }
    write_active_sha() { :; }
    write_xml_status_file() { :; }
    publish_xml_state() { :; }
    set_xml_error() { :; }
    set_status() { LAST_STATUS="$*"; }
    mark_zuipp_apply_success test && fail 'reload failure was swallowed'
    [[ "$LAST_STATUS" == *'last=apply_zuipp_failed'* ]] ||
        fail 'reload failure did not publish failed status'
    [[ ! -e "$ZUI_ENABLED" ]] || fail 'reload failure marked ZuiPP enabled'
)

test_remount_propagates_reload_failure() (
    export ZUI_CONTROLD_TEST_MODE=1
    # shellcheck source=/dev/null
    source "$DAEMON"
    setup_test_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    trigger_init_action() { return 0; }
    wait_prop_value() { return 0; }
    wait_zuipp_mount_ready() { return 0; }
    verify_zuipp_mount_core() { return 0; }
    verify_system_xml_contexts() { return 0; }
    mark_zuipp_apply_success() { return 1; }
    if remount_active_xml test; then
        fail 'remount swallowed reload failure'
    fi
)

test_reload_invalidates_remembered_hash() (
    export ZUI_CONTROLD_TEST_MODE=1
    # shellcheck source=/dev/null
    source "$DAEMON"
    setup_test_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    printf '<AppPolicy>B</AppPolicy>\n' > "$ACTIVE_GAME_POLICY"
    printf '<GameLimitConfig>B</GameLimitConfig>\n' > "$ACTIVE_PERF_CONFIG"
    zuipp_mount_ready() { return 0; }
    zuipp_pid() { printf '123\n'; }
    proc_cmdline_clean() { printf 'com.zui.pp\n'; }
    proc_start_time() { printf '1\n'; }
    am() {
        printf 'Service stopped\n'
        return 255
    }
    kill() { return 1; }
    publish_zuipp_reload_state() { :; }
    reload_zuipp_if_needed test && fail 'simulated SIGTERM failure unexpectedly succeeded'
    [[ ! -e "$ZUIPP_LAST_RELOADED_HASH_FILE" ]] ||
        fail 'reload failure left stale remembered hash'
)

test_daemon_start_forces_same_hash_reload() (
    export ZUI_CONTROLD_TEST_MODE=1
    # shellcheck source=/dev/null
    source "$DAEMON"
    setup_test_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    previous_hash="$(xml_hash_pair)"
    [[ "$(last_reloaded_hash_pair)" == "$previous_hash" ]] ||
        fail 'same-hash test setup is invalid'
    invalidate_zuipp_reload_receipt_on_start ||
        fail 'daemon start did not invalidate reload receipt'
    [[ ! -e "$ZUIPP_LAST_RELOADED_HASH_FILE" ]] ||
        fail 'daemon start left a cross-boot reload receipt'

    KILL_CALLS=0
    KILL_CALLED=0
    : > "$TEST_ROOT/am_calls"
    zuipp_mount_ready() { return 0; }
    zuipp_pid() {
        if [ "$KILL_CALLED" = 1 ]; then
            printf '456\n'
        else
            printf '123\n'
        fi
    }
    proc_cmdline_clean() { printf 'com.zui.pp\n'; }
    proc_start_time() {
        if [ "$1" = 456 ]; then printf '2\n'; else printf '1\n'; fi
    }
    am() {
        call_count="$(wc -l < "$TEST_ROOT/am_calls")"
        printf '%s\n' "$*" >> "$TEST_ROOT/am_calls"
        if [ "$call_count" = 0 ]; then
            printf 'cmd: Failure calling service activity: Failed transaction (2147483646)\n'
        elif [[ "$*" == *OverHeatCleanService* ]]; then
            printf 'Service not stopped: was not running.\n'
        else
            printf 'Service stopped\n'
        fi
        return 255
    }
    kill() {
        KILL_CALLS=$((KILL_CALLS + 1))
        KILL_CALLED=1
        return 0
    }
    sleep() { :; }
    publish_zuipp_reload_state() { :; }

    reload_zuipp_if_needed boot_active ||
        fail 'same-hash XML was not reloaded after daemon start'
    [[ "$KILL_CALLS" = 1 ]] || fail 'same-hash boot reload did not signal ZuiPP'
    [[ "$(wc -l < "$TEST_ROOT/am_calls")" = 5 ]] ||
        fail 'same-hash boot reload did not retry a transient stop then stop all ZuiPP services'
    [[ "$(tail -n 1 "$TEST_ROOT/am_calls")" == *'com.zui.pp/.service.MainService' ]] ||
        fail 'same-hash boot reload did not stop MainService last'
    [[ "$(last_reloaded_hash_pair)" == "$previous_hash" ]] ||
        fail 'same-hash boot reload did not remember the new runtime state'
)

test_reload_stop_service_failure_prevents_signal() (
    export ZUI_CONTROLD_TEST_MODE=1
    # shellcheck source=/dev/null
    source "$DAEMON"
    setup_test_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    printf '<AppPolicy>B</AppPolicy>\n' > "$ACTIVE_GAME_POLICY"
    printf '<GameLimitConfig>B</GameLimitConfig>\n' > "$ACTIVE_PERF_CONFIG"
    zuipp_mount_ready() { return 0; }
    zuipp_pid() { printf '123\n'; }
    proc_cmdline_clean() { printf 'com.zui.pp\n'; }
    proc_start_time() { printf '1\n'; }
    am() {
        printf 'cmd: Failure calling service activity: Failed transaction (2147483646)\n'
        return 255
    }
    sleep() { :; }
    KILL_CALLS=0
    kill() {
        KILL_CALLS=$((KILL_CALLS + 1))
        return 0
    }
    publish_zuipp_reload_state() { :; }

    reload_zuipp_if_needed test &&
        fail 'reload continued after ZuiPP service stop failed'
    [[ "$KILL_CALLS" = 0 ]] ||
        fail 'reload signalled ZuiPP after service stop failed'
    [[ ! -e "$ZUIPP_LAST_RELOADED_HASH_FILE" ]] ||
        fail 'service stop failure left stale remembered hash'
)

test_transaction_recovery() (
    export ZUI_CONTROLD_TEST_MODE=1
    # shellcheck source=/dev/null
    source "$DAEMON"
    setup_test_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    cp "$PERFORMANCE_PROFILES" "$TEST_ROOT/original_profiles"
    original_active="$(xml_hash_pair)"
    begin_performance_transaction || fail 'begin transaction failed'
    printf 'com.example.new|balanced|v2|-1000,100,50,200,100,300,150,400,200,500,250\n' \
        > "$PERFORMANCE_PROFILES"
    printf '<AppPolicy>B</AppPolicy>\n' > "$ACTIVE_GAME_POLICY"
    printf '<GameLimitConfig>B</GameLimitConfig>\n' > "$ACTIVE_PERF_CONFIG"
    atomic_write_text "$ZUIPP_LAST_RELOADED_HASH_FILE" "$(xml_hash_pair)"
    promote_staging_with_lock() {
        cp "$STAGING_GAME_POLICY" "$ACTIVE_GAME_POLICY"
        cp "$STAGING_PERF_CONFIG" "$ACTIVE_PERF_CONFIG"
        atomic_write_text "$ZUIPP_LAST_RELOADED_HASH_FILE" "$(xml_hash_pair)"
    }
    recover_performance_transaction || fail 'transaction recovery failed'
    cmp -s "$PERFORMANCE_PROFILES" "$TEST_ROOT/original_profiles" ||
        fail 'recovery did not restore profiles.prop'
    [[ "$(xml_hash_pair)" == "$original_active" ]] ||
        fail 'recovery did not restore active XML'
    [[ "$(last_reloaded_hash_pair)" == "$original_active" ]] ||
        fail 'recovery did not restore runtime hash'
    [[ ! -e "$PERFORMANCE_TXN_MARKER" ]] || fail 'recovery left marker'
)

test_apply_failure_rolls_back_all_layers() (
    export ZUI_CONTROLD_TEST_MODE=1
    # shellcheck source=/dev/null
    source "$DAEMON"
    setup_test_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    cp "$PERFORMANCE_PROFILES" "$TEST_ROOT/original_profiles"
    original_active="$(xml_hash_pair)"
    generate_performance_xml() { return 0; }
    promote_staging_to_active() {
        printf '<AppPolicy>B</AppPolicy>\n' > "$ACTIVE_GAME_POLICY"
        printf '<GameLimitConfig>B</GameLimitConfig>\n' > "$ACTIVE_PERF_CONFIG"
        rm -f "$ZUIPP_LAST_RELOADED_HASH_FILE"
        return 1
    }
    promote_staging_with_lock() {
        cp "$STAGING_GAME_POLICY" "$ACTIVE_GAME_POLICY"
        cp "$STAGING_PERF_CONFIG" "$ACTIVE_PERF_CONFIG"
        atomic_write_text "$ZUIPP_LAST_RELOADED_HASH_FILE" "$(xml_hash_pair)"
    }
    set_performance_profile_staged com.example.game balanced \
        '-1000,100,50,200,100,300,150,400,200,500,250' \
        independent default && fail 'failed promote unexpectedly committed'
    cmp -s "$PERFORMANCE_PROFILES" "$TEST_ROOT/original_profiles" ||
        fail 'failed promote did not restore profiles.prop'
    [[ "$(xml_hash_pair)" == "$original_active" ]] ||
        fail 'failed promote did not restore active XML'
    [[ "$(last_reloaded_hash_pair)" == "$original_active" ]] ||
        fail 'failed promote did not restore runtime hash'
)

test_reload_failure_rolls_back_request_transaction() (
    export ZUI_CONTROLD_TEST_MODE=1
    # shellcheck source=/dev/null
    source "$DAEMON"
    setup_test_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    cp "$PERFORMANCE_PROFILES" "$TEST_ROOT/original_profiles"
    original_active="$(xml_hash_pair)"
    generate_performance_xml() {
        printf '<AppPolicy>B</AppPolicy>\n' > "$STAGING_GAME_POLICY"
        printf '<GameLimitConfig>B</GameLimitConfig>\n' > "$STAGING_PERF_CONFIG"
    }
    trigger_init_action() {
        if [ "$2" = promote_active ]; then
            cp "$STAGING_GAME_POLICY" "$ACTIVE_GAME_POLICY"
            cp "$STAGING_PERF_CONFIG" "$ACTIVE_PERF_CONFIG"
        fi
        return 0
    }
    wait_prop_value() { return 0; }
    wait_zuipp_mount_ready() { return 0; }
    verify_zuipp_mount_core() { return 0; }
    verify_system_xml_contexts() { return 0; }
    RELOAD_CALLS=0
    reload_zuipp_if_needed() {
        RELOAD_CALLS=$((RELOAD_CALLS + 1))
        if [ "$RELOAD_CALLS" = 1 ]; then
            rm -f "$ZUIPP_LAST_RELOADED_HASH_FILE"
            return 1
        fi
        atomic_write_text "$ZUIPP_LAST_RELOADED_HASH_FILE" "$(xml_hash_pair)"
    }
    payload='-1000,100,50,200,100,300,150,400,200,500,250'
    TEST_SETTINGS["$REQ_TEXT_KEY"]="req_reload|set_performance_profile_staged|$payload|com.example.game|balanced|independent|default"
    process_atomic_settings_request && fail 'reload-failed request unexpectedly succeeded'
    cmp -s "$PERFORMANCE_PROFILES" "$TEST_ROOT/original_profiles" ||
        fail 'reload-failed request did not restore profiles.prop'
    [[ "$(xml_hash_pair)" == "$original_active" ]] ||
        fail 'reload-failed request did not restore active XML'
    [[ "$(last_reloaded_hash_pair)" == "$original_active" ]] ||
        fail 'reload-failed request did not restore runtime hash'
    [[ "$RELOAD_CALLS" == 2 ]] || fail 'rollback did not reload previous XML'
    [[ "${TEST_SETTINGS[$REQUEST_ACK_KEY]}" == \
        'req_reload|failed|set_performance_profile_staged|command_failed' ]] ||
        fail 'reload-failed request did not publish terminal failed ACK'
)

test_runtime_rollback_is_forced() (
    export ZUI_CONTROLD_TEST_MODE=1
    # shellcheck source=/dev/null
    source "$DAEMON"
    setup_test_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    begin_performance_transaction || fail 'begin transaction failed'
    printf 'candidate\n' > "$PERFORMANCE_PROFILES"
    rm -f "$ZUIPP_LAST_RELOADED_HASH_FILE"
    REMOUNT_CALLS=0
    remount_active_with_lock() {
        REMOUNT_CALLS=$((REMOUNT_CALLS + 1))
        atomic_write_text "$ZUIPP_LAST_RELOADED_HASH_FILE" "$(xml_hash_pair)"
    }
    rollback_performance_transaction || fail 'runtime rollback failed'
    [[ "$REMOUNT_CALLS" == 1 ]] || fail 'runtime rollback skipped forced reload'
)

test_request_ack_and_replay() (
    export ZUI_CONTROLD_TEST_MODE=1
    # shellcheck source=/dev/null
    source "$DAEMON"
    setup_test_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    publish_request_ack req1 processing 'bad|cmd' $'line1\nline2'
    [[ "${TEST_SETTINGS[$REQUEST_ACK_KEY]}" == 'req1|processing|bad cmd|line1 line2' ]] ||
        fail 'processing ACK format/sanitization mismatch'
    CURRENT_REQUEST_ID=req1
    CURRENT_REQUEST_CMD=set_performance_profile_staged
    publish_request_progress generating_xml
    [[ "${TEST_SETTINGS[$REQUEST_ACK_KEY]}" == \
        'req1|processing|set_performance_profile_staged|generating_xml' ]] ||
        fail 'processing stage ACK mismatch'

    request='req2|status|||||||||||||'
    finish_atomic_settings_request "$request" req2 status 0 ok ||
        fail 'terminal success ACK failed'
    [[ "${TEST_SETTINGS[$REQUEST_ACK_KEY]}" == 'req2|done|status|ok' ]] ||
        fail 'success ACK format mismatch'
    unset 'TEST_SETTINGS[zui_control_request_ack]'
    replay_last_request_ack
    [[ "${TEST_SETTINGS[$REQUEST_ACK_KEY]}" == 'req2|done|status|ok' ]] ||
        fail 'terminal ACK receipt was not replayed'

    printf '%s\n%s\n' "$request" 'req2|done|wrong_command|ok' \
        > "$LAST_REQUEST_RECEIPT_FILE"
    load_last_request_receipt && fail 'mismatched receipt command was accepted'
    printf '%s\n%s\n%s\n' "$request" 'req2|done|status|ok' extra \
        > "$LAST_REQUEST_RECEIPT_FILE"
    load_last_request_receipt && fail 'multi-line receipt was accepted'

    finish_atomic_settings_request 'req3|status' req3 status 1 $'why|bad\nline' &&
        fail 'failed request returned success'
    [[ "${TEST_SETTINGS[$REQUEST_ACK_KEY]}" == 'req3|failed|status|why bad line' ]] ||
        fail 'failed ACK format/sanitization mismatch'
)

test_legacy_request_receipt_migration() (
    export ZUI_CONTROLD_TEST_MODE=1
    # shellcheck source=/dev/null
    source "$DAEMON"
    setup_test_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    legacy='legacy1|set_performance_profile_staged|-1000,100,50,200,100,300,150,400,200,500,250|com.example.game|balanced|independent|default'
    printf '%s\n' "$legacy" > "$LAST_REQUEST_FILE"
    TEST_SETTINGS["$REQ_TEXT_KEY"]="$legacy"
    init_request_state || fail 'legacy receipt migration failed'
    [[ "$LAST_SETTINGS_REQUEST_TEXT" == "$legacy" ]] ||
        fail 'legacy request was not marked processed'
    [[ "${TEST_SETTINGS[$REQUEST_ACK_KEY]}" == \
        'legacy1|failed|set_performance_profile_staged|migrated_legacy' ]] ||
        fail 'legacy request did not receive terminal migration ACK'
    [[ ! -e "$LAST_REQUEST_FILE" ]] || fail 'legacy request file was not cleaned'
    [[ "$(sed -n '1p' "$LAST_REQUEST_RECEIPT_FILE")" == "$legacy" ]] ||
        fail 'single receipt did not contain migrated request'
    [[ "$(sed -n '2p' "$LAST_REQUEST_RECEIPT_FILE")" == \
        'legacy1|failed|set_performance_profile_staged|migrated_legacy' ]] ||
        fail 'single receipt did not contain migrated ACK'
    HANDLE_CALLS=0
    handle_request() { HANDLE_CALLS=$((HANDLE_CALLS + 1)); }
    process_atomic_settings_request
    [[ "$HANDLE_CALLS" == 0 ]] || fail 'legacy request was re-executed'
)

test_single_receipt_crash_retry() (
    export ZUI_CONTROLD_TEST_MODE=1
    # shellcheck source=/dev/null
    source "$DAEMON"
    setup_test_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    old_request='old1|status|||||||||||||'
    old_ack='old1|done|status|ok'
    persist_settings_request_completion "$old_request" "$old_ack" ||
        fail 'initial receipt persistence failed'
    cp "$LAST_REQUEST_RECEIPT_FILE" "$TEST_ROOT/old_receipt"

    atomic_write_text_real() {
        target="$1"
        value="$2"
        tmp="$target.tmp"
        rm -f "$tmp"
        printf '%s\n' "$value" > "$tmp" && chmod 0664 "$tmp" && mv "$tmp" "$target"
    }
    FAIL_RECEIPT_WRITE=1
    atomic_write_text() {
        if [ "$1" = "$LAST_REQUEST_RECEIPT_FILE" ] &&
            [ "$FAIL_RECEIPT_WRITE" = 1 ]; then
            return 1
        fi
        atomic_write_text_real "$@"
    }
    HANDLE_CALLS=0
    handle_request() { HANDLE_CALLS=$((HANDLE_CALLS + 1)); return 0; }
    TEST_SETTINGS["$REQ_TEXT_KEY"]='new1|status|||||||||||||'
    process_atomic_settings_request && fail 'receipt write failure reported success'
    cmp -s "$LAST_REQUEST_RECEIPT_FILE" "$TEST_ROOT/old_receipt" ||
        fail 'failed receipt write corrupted previous atomic receipt'
    [[ "$HANDLE_CALLS" == 1 ]] || fail 'new request was not initially executed'

    LAST_SETTINGS_REQUEST_TEXT=
    init_request_state || fail 'restart could not load previous receipt'
    FAIL_RECEIPT_WRITE=0
    process_atomic_settings_request || fail 'uncommitted request was not retried'
    [[ "$HANDLE_CALLS" == 2 ]] || fail 'uncommitted request was swallowed after restart'
    [[ "${TEST_SETTINGS[$REQUEST_ACK_KEY]}" == 'new1|done|status|ok' ]] ||
        fail 'retried request did not publish terminal ACK'
)

test_process_ack_order() (
    export ZUI_CONTROLD_TEST_MODE=1
    # shellcheck source=/dev/null
    source "$DAEMON"
    setup_test_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    TEST_SETTINGS["$REQ_TEXT_KEY"]='req4|status|||||||||||||'
    handle_request() { return 0; }
    process_atomic_settings_request || fail 'atomic request processing failed'
    [[ "${ACK_HISTORY[0]-}" == 'req4|processing|status|validating' ]] ||
        fail 'processing ACK was not first'
    [[ "${ACK_HISTORY[-1]-}" == 'req4|done|status|ok' ]] ||
        fail 'terminal ACK was not last'
)

test_appopt_rule_commands() (
    export ZUI_CONTROLD_TEST_MODE=1
    # shellcheck source=/dev/null
    source "$DAEMON"
    setup_test_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    pm() {
        [[ "$(readlink /proc/self/fd/0)" == /dev/null ]] ||
            fail 'PackageManager inherited protected AppOpt config stdin'
        [[ "$1" == path ]] || return 1
        case "$2" in
            com.example.user|com.example.other)
                printf 'package:/data/app/%s/base.apk\n' "$2"
                ;;
            com.android.systemui)
                printf 'package:/system/priv-app/SystemUI/SystemUI.apk\n'
                ;;
            *) return 1 ;;
        esac
    }
    apply_appopt() {
        : > "$APPOPT_ENABLED"
        return 0
    }
    STOP_CALLS=0
    stop_appopt() {
        STOP_CALLS=$((STOP_CALLS + 1))
        rm -f "$APPOPT_ENABLED"
        return 0
    }

    set_appopt_rule com.example.user 0-1 || fail 'valid AppOpt rule was rejected'
    grep -qx 'com.example.user=0-1' "$APPOPT_CONFIG" ||
        fail 'valid AppOpt rule was not written canonically'
    [[ "${TEST_SETTINGS[$APPOPT_RULES_KEY]-}" == 'com.example.user=0-1' ]] ||
        fail 'AppOpt rules state was not published'
    set_appopt_rule com.example.user 3-6 || fail 'custom contiguous AppOpt range was rejected'
    grep -qx 'com.example.user=3-6' "$APPOPT_CONFIG" ||
        fail 'custom contiguous AppOpt range was not written canonically'

    cp "$APPOPT_CONFIG" "$TEST_ROOT/appopt_before_reject"
    set_appopt_rule com.android.systemui 0-1 && fail 'system AppOpt package was accepted'
    set_appopt_rule com.example.user 6-3 && fail 'reversed AppOpt range was accepted'
    cmp -s "$APPOPT_CONFIG" "$TEST_ROOT/appopt_before_reject" ||
        fail 'rejected AppOpt request changed config'

    remove_appopt_rule com.example.user || fail 'valid AppOpt removal failed'
    ! grep -q '^com.example.user=' "$APPOPT_CONFIG" ||
        fail 'AppOpt removal left active rule'
    [[ "$STOP_CALLS" == 1 ]] || fail 'last AppOpt rule removal did not stop service'
    [[ -z "${TEST_SETTINGS[$APPOPT_RULES_KEY]-}" ]] ||
        fail 'empty AppOpt state was not published'
    remove_appopt_rule com.example.user || fail 'absent AppOpt rule replay was not idempotent'
    [[ "$STOP_CALLS" == 2 ]] || fail 'absent AppOpt rule replay did not reconcile stopped state'
)

test_appopt_update_rollback() (
    export ZUI_CONTROLD_TEST_MODE=1
    # shellcheck source=/dev/null
    source "$DAEMON"
    setup_test_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    printf 'com.example.user=0-7\n' >> "$APPOPT_CONFIG"
    cp "$APPOPT_CONFIG" "$TEST_ROOT/original_appopt"
    pm() {
        [[ "$(readlink /proc/self/fd/0)" == /dev/null ]] ||
            fail 'PackageManager inherited protected AppOpt config stdin'
        printf 'package:/data/app/%s/base.apk\n' "$2"
    }
    apply_appopt() { return 1; }
    stop_appopt() { return 0; }
    set_appopt_rule com.example.user 0-1 && fail 'failed AppOpt apply unexpectedly succeeded'
    cmp -s "$APPOPT_CONFIG" "$TEST_ROOT/original_appopt" ||
        fail 'failed AppOpt apply did not restore config'
)

test_appopt_stale_rule_removal() (
    export ZUI_CONTROLD_TEST_MODE=1
    # shellcheck source=/dev/null
    source "$DAEMON"
    setup_test_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    pm() {
        [[ "$(readlink /proc/self/fd/0)" == /dev/null ]] ||
            fail 'PackageManager inherited protected AppOpt config stdin'
        if [ "$2" = com.example.user ]; then
            printf 'package:/data/app/com.example.user/base.apk\n'
            return 0
        fi
        return 1
    }
    stop_appopt() { rm -f "$APPOPT_ENABLED"; return 0; }
    apply_appopt() { fail 'AppOpt started with remaining stale rule'; }

    printf 'com.example.stale=0-1\n' > "$APPOPT_CONFIG"
    remove_appopt_rule com.example.stale || fail 'uninstalled stale rule could not be removed'
    [[ "$(appopt_rules_count)" == 0 ]] || fail 'stale rule removal left a rule'

    printf 'com.example.user=0-7\ncom.example.stale=0-1\n' > "$APPOPT_CONFIG"
    TEST_SETTINGS["$REQ_TEXT_KEY"]='stale_remove|remove_appopt_rule||com.example.user|||||||||||'
    process_atomic_settings_request || fail 'safe removal with remaining stale rule failed'
    grep -qx 'com.example.stale=0-1' "$APPOPT_CONFIG" ||
        fail 'safe removal changed the wrong stale rule'
    ! grep -q '^com.example.user=' "$APPOPT_CONFIG" ||
        fail 'safe removal left requested rule'
    [[ "${TEST_SETTINGS[$REQUEST_ACK_KEY]}" == \
        'stale_remove|done|remove_appopt_rule|remaining_invalid_stopped;target=not_running' ]] ||
        fail 'remaining stale rule did not publish stopped detail'
)

test_appopt_same_pid_stability() (
    export ZUI_CONTROLD_TEST_MODE=1
    # shellcheck source=/dev/null
    source "$DAEMON"
    setup_test_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    printf '0\n' > "$TEST_ROOT/pid_calls"
    sleep() { :; }
    getprop() { printf 'running\n'; }
    pidof() {
        count="$(cat "$TEST_ROOT/pid_calls")"
        count=$((count + 1))
        printf '%s\n' "$count" > "$TEST_ROOT/pid_calls"
        if [ "$count" -le 2 ]; then
            printf '111\n'
        else
            printf '222\n'
        fi
    }
    wait_appopt_running || fail 'stable AppOpt PID was not accepted'
    [[ "$(cat "$TEST_ROOT/pid_calls")" == 5 ]] ||
        fail 'AppOpt stability did not reset after PID change'
)

test_zui_game_sync_and_target_stop() (
    export ZUI_CONTROLD_TEST_MODE=1
    # shellcheck source=/dev/null
    source "$DAEMON"
    setup_test_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    service() {
        tx="$3"
        case "$tx" in
            14)
                : > "$TEST_ROOT/user_game"
                printf 'Result: Parcel( 00000000 00000001 )\n'
                ;;
            15)
                rm -f "$TEST_ROOT/user_game"
                printf 'Result: Parcel( 00000000 00000001 )\n'
                ;;
            16)
                if [ -e "$TEST_ROOT/user_game" ]; then
                    printf 'Result: Parcel( 00000000 00000001 )\n'
                else
                    printf 'Result: Parcel( 00000000 00000000 )\n'
                fi
                ;;
            17) printf 'Result: Parcel( 00000000 00000000 )\n' ;;
            *) return 1 ;;
        esac
    }

    ensure_zui_game_managed com.example.game || fail 'custom game sync failed'
    [[ "$ZUI_GAME_SYNC_RESULT" == user_added ]] ||
        fail 'new custom game was not reported as added'
    ensure_zui_game_managed com.example.game || fail 'existing custom game query failed'
    [[ "$ZUI_GAME_SYNC_RESULT" == user_existing ]] ||
        fail 'existing custom game was not detected'
    remove_zui_game_managed com.example.game || fail 'custom game removal failed'
    [[ "$ZUI_GAME_SYNC_RESULT" == user_removed && ! -e "$TEST_ROOT/user_game" ]] ||
        fail 'custom game was not removed'
    remove_zui_game_managed com.example.game || fail 'absent custom game removal failed'
    [[ "$ZUI_GAME_SYNC_RESULT" == already_absent ]] ||
        fail 'absent custom game state was not reported'

    sync_zui_game_for_performance set com.example.game ||
        fail 'transactional game add failed'
    [[ "$ZUI_GAME_SYNC_UNDO" == remove && -e "$TEST_ROOT/user_game" ]] ||
        fail 'transactional game add did not record rollback action'
    rollback_zui_game_sync com.example.game || fail 'transactional game add rollback failed'
    [[ ! -e "$TEST_ROOT/user_game" ]] || fail 'game add rollback left membership'
    : > "$TEST_ROOT/user_game"
    sync_zui_game_for_performance remove com.example.game ||
        fail 'transactional game remove failed'
    [[ "$ZUI_GAME_SYNC_UNDO" == add && ! -e "$TEST_ROOT/user_game" ]] ||
        fail 'transactional game remove did not record rollback action'
    rollback_zui_game_sync com.example.game || fail 'transactional game remove rollback failed'
    [[ -e "$TEST_ROOT/user_game" ]] || fail 'game remove rollback did not restore membership'

    : > "$TEST_ROOT/running"
    package_has_process() { [ -e "$TEST_ROOT/running" ]; }
    am() { rm -f "$TEST_ROOT/running"; return 0; }
    sleep() { :; }
    force_stop_package_if_running com.example.game || fail 'running target was not stopped'
    [[ "$PACKAGE_STOP_RESULT" == stopped ]] || fail 'target stop result was not reported'
    force_stop_package_if_running com.example.game || fail 'stopped target check failed'
    [[ "$PACKAGE_STOP_RESULT" == not_running ]] || fail 'not-running target was not skipped'
)

test_performance_game_sync_commit_boundary() (
    export ZUI_CONTROLD_TEST_MODE=1
    # shellcheck source=/dev/null
    source "$DAEMON"
    setup_test_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    service() {
        case "$3" in
            14) : > "$TEST_ROOT/user_game"; printf 'Result: Parcel( 00000000 00000001 )\n' ;;
            15) rm -f "$TEST_ROOT/user_game"; printf 'Result: Parcel( 00000000 00000001 )\n' ;;
            16)
                if [ -e "$TEST_ROOT/user_game" ]; then
                    printf 'Result: Parcel( 00000000 00000001 )\n'
                else
                    printf 'Result: Parcel( 00000000 00000000 )\n'
                fi
                ;;
            17) printf 'Result: Parcel( 00000000 00000000 )\n' ;;
            *) return 1 ;;
        esac
    }
    generate_performance_xml() { return 0; }
    promote_staging_to_active() { return 0; }
    rollback_performance_transaction() { return 0; }
    commit_performance_transaction() { return 1; }

    complete_performance_transaction set com.example.game &&
        fail 'failed profile commit kept a game-list add'
    [ ! -e "$TEST_ROOT/user_game" ] || fail 'failed profile commit leaked game-list add'
    : > "$TEST_ROOT/user_game"
    complete_performance_transaction remove com.example.game &&
        fail 'failed profile removal commit succeeded'
    [ -e "$TEST_ROOT/user_game" ] || fail 'failed profile removal did not restore game membership'

    commit_performance_transaction() { return 0; }
    complete_performance_transaction set com.example.game || fail 'committed profile did not add game'
    [ -e "$TEST_ROOT/user_game" ] || fail 'committed profile lost game membership'
    complete_performance_transaction remove com.example.game || fail 'committed profile removal failed'
    [ ! -e "$TEST_ROOT/user_game" ] || fail 'committed profile removal kept custom game'
)

test_appopt_batch_replace() (
    export ZUI_CONTROLD_TEST_MODE=1
    # shellcheck source=/dev/null
    source "$DAEMON"
    setup_test_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    apply_appopt() { : > "$APPOPT_ENABLED"; return 0; }
    stop_appopt() { rm -f "$APPOPT_ENABLED"; return 0; }
    : > "$TEST_ROOT/running_batch_target"
    package_has_process() {
        [[ "$1" == com.example.user && -e "$TEST_ROOT/running_batch_target" ]]
    }
    am() { rm -f "$TEST_ROOT/running_batch_target"; return 0; }
    sleep() { :; }

    replace_appopt_rules 'com.example.user=2-6;com.example.user{RenderThread}=2-4;com.example.user{GameThread}=7;com.example.other=3-6' ||
        fail 'valid AppOpt batch was rejected'
    grep -qx 'com.example.user=2-6' "$APPOPT_CONFIG" ||
        fail 'batch fallback rule missing'
    grep -qx 'com.example.user{RenderThread}=2-4' "$APPOPT_CONFIG" ||
        fail 'batch render rule missing'
    grep -qx 'com.example.user{GameThread}=7' "$APPOPT_CONFIG" ||
        fail 'batch game rule missing'
    grep -qx 'com.example.other=3-6' "$APPOPT_CONFIG" ||
        fail 'second batch rule missing'
    [[ ! -e "$TEST_ROOT/running_batch_target" ]] ||
        fail 'batch did not stop a running target'
    [[ "$REQUEST_RESULT_DETAIL" == 'rules=4;stoppedApps=1;stopFailed=0' ]] ||
        fail 'batch result detail is wrong'

    cp "$APPOPT_CONFIG" "$TEST_ROOT/before_invalid_batch"
    replace_appopt_rules 'com.example.user=2-6;com.example.user{GameThread}=7;com.example.user{GameThread}=2-4' &&
        fail 'duplicate batch thread key was accepted'
    replace_appopt_rules 'com.example.user{GameThread}=7' &&
        fail 'thread-only batch without fallback was accepted'
    cmp -s "$APPOPT_CONFIG" "$TEST_ROOT/before_invalid_batch" ||
        fail 'invalid batch changed active rules'
)

test_profile_failures
test_profile_success
test_reload_failure_propagates
test_remount_propagates_reload_failure
test_reload_invalidates_remembered_hash
test_daemon_start_forces_same_hash_reload
test_reload_stop_service_failure_prevents_signal
test_transaction_recovery
test_apply_failure_rolls_back_all_layers
test_reload_failure_rolls_back_request_transaction
test_runtime_rollback_is_forced
test_request_ack_and_replay
test_legacy_request_receipt_migration
test_single_receipt_crash_retry
test_process_ack_order
test_appopt_rule_commands
test_appopt_update_rollback
test_appopt_stale_rule_removal
test_appopt_same_pid_stability
test_zui_game_sync_and_target_stop
test_performance_game_sync_commit_boundary
test_appopt_batch_replace

printf 'zui_controld transactions, request ACK, and AppOpt rules: OK\n'
