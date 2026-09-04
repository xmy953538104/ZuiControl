#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
DAEMON="${1:-$ROOT/payload/system/bin/zui_controld}"
DEFAULT_PERAPP="${2:-$ROOT/payload/system/etc/zui_control/default_uperf_perapp.txt}"
SCHEDULER_RC="$ROOT/payload/system/etc/init/zui_scheduler.rc"
CONTROL_RC="$ROOT/payload/system/etc/init/zui_controld.rc"
SCHEDULER_PREPARE="$ROOT/payload/system/etc/zui_control/zui_scheduler_prepare.sh"
PROPERTY_CONTEXTS="$ROOT/payload/patches/plat_property_contexts_add.txt"
PLAT_SEPOLICY="$ROOT/payload/patches/plat_sepolicy_zui_control.cil"
SERVICE_SOURCE="$ROOT/framework_patch/src/services/com/zui/server/control/ZuiControlService.java"
MANAGER_SOURCE="$ROOT/framework_patch/src/framework/android/zui/ZuiControlManager.java"
APP_REQUEST_SOURCE="$ROOT/app/src/main/java/com/zui/zuicontrol/ZuiControlRequest.kt"
APP_BOOT_SOURCE="$ROOT/app/src/main/java/com/zui/zuicontrol/BootReceiver.kt"

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
    ACTIVE_REQUEST_CLAIM="$CONTROL_DIR/active_claim"
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
    TEST_REQUEST=
    TEST_ACK=
    TEST_ACTIONS=0
    TEST_ACK_PUT_ATTEMPTS=0
    TEST_TERMINAL_ACK_PUT_ATTEMPTS=0
    TEST_TERMINAL_ACK_PUT_FAILURES=0
    TEST_ACK_PUTS=0
    TEST_TERMINAL_ACK_PUTS=0
    TEST_MODE_STATE=
    TEST_RULES_STATE=
    TEST_CONFIG_PUTS=0
    TEST_CONFIG_PUT_FAILURES=0
    TEST_TIMING=

    settings_get_clean() {
        case "$1" in
            "$REQ_TEXT_KEY") printf '%s\n' "$TEST_REQUEST" ;;
            "$REQUEST_ACK_KEY") printf '%s\n' "$TEST_ACK" ;;
            *) printf '\n' ;;
        esac
    }
    settings_put_quiet() {
        case "$1" in
            "$REQUEST_ACK_KEY")
                TEST_ACK_PUT_ATTEMPTS=$((TEST_ACK_PUT_ATTEMPTS + 1))
                TEST_ACK_PUTS=$((TEST_ACK_PUTS + 1))
                case "$2" in *'|done|'*|*'|failed|'*)
                    TEST_TERMINAL_ACK_PUT_ATTEMPTS=$((TEST_TERMINAL_ACK_PUT_ATTEMPTS + 1))
                    if [ "$TEST_TERMINAL_ACK_PUT_FAILURES" -gt 0 ]; then
                        TEST_TERMINAL_ACK_PUT_FAILURES=$((TEST_TERMINAL_ACK_PUT_FAILURES - 1))
                        TEST_ACK_PUTS=$((TEST_ACK_PUTS - 1))
                        return 1
                    fi
                    TEST_TERMINAL_ACK_PUTS=$((TEST_TERMINAL_ACK_PUTS + 1))
                    ;;
                esac
                TEST_ACK="$2"
                ;;
            "$UPERF_MODE_KEY"|"$UPERF_RULES_KEY")
                TEST_CONFIG_PUTS=$((TEST_CONFIG_PUTS + 1))
                if [ "$TEST_CONFIG_PUT_FAILURES" -gt 0 ]; then
                    TEST_CONFIG_PUT_FAILURES=$((TEST_CONFIG_PUT_FAILURES - 1))
                    return 1
                fi
                if [ "$1" = "$UPERF_MODE_KEY" ]; then
                    TEST_MODE_STATE="$2"
                else
                    TEST_RULES_STATE="$2"
                fi
                ;;
        esac
    }
    log_line() { :; }
    timing_mark() {
        TEST_TIMING="${TEST_TIMING}${1}|${2}|${3:-unknown}
"
    }
    sync() { :; }
    sleep() { :; }
    pidof() { return 0; }
    getprop() { return 0; }
    pm() {
        [ "$1" = path ] && [ "$2" != com.android.systemui ] || return 1
        printf 'package:/data/app/%s/base.apk\n' "$2"
    }
    id() {
        [ "${1:-}" = "-u" ] && printf '0\n'
    }
    authenticated_oneshot() {
        auth_id="$(request_field "$TEST_REQUEST" 1)"
        auth_sha256="$(request_sha256 "$TEST_REQUEST")"
        oneshot_request "$auth_id" "$auth_sha256"
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

assert_event_transport_policy() {
    [ "$(grep -c '^on property:sys\.zui_control\.uperf_mode=' "$SCHEDULER_RC")" -eq 4 ] ||
        fail 'init does not have exactly four fixed Uperf mode triggers'
    for mode in powersave balance performance fast; do
        grep -Fqx "on property:sys.zui_control.uperf_mode=$mode" "$SCHEDULER_RC" ||
            fail "missing init trigger for $mode"
        grep -Fqx "    write /data/vendor/zui_control/uperf/effective_powermode.txt $mode" "$SCHEDULER_RC" ||
            fail "init trigger does not write $mode"
    done
    if grep -F '    write /data/vendor/zui_control/uperf/effective_powermode.txt ' "$SCHEDULER_RC" |
        grep -Eq '\\n|"'; then
        fail 'init builtin Uperf write contains a quoted or literal backslash-n value'
    fi
    if grep -Fq 'on property:sys.zui_control.uperf_mode=*' "$SCHEDULER_RC"; then
        fail 'wildcard Uperf mode trigger is forbidden'
    fi
    grep -Fqx 'sys.zui_control.uperf_mode u:object_r:zui_control_uperf_mode_prop:s0 exact enum powersave balance performance fast' "$PROPERTY_CONTEXTS" ||
        fail 'Uperf transport property is not an exact enum'
    grep -Fqx '(allow system_server zui_control_uperf_mode_prop (property_service (set)))' "$PLAT_SEPOLICY" ||
        fail 'system_server cannot set the Uperf transport property'
    grep -Fqx '(allow system_server zui_control_uperf_mode_prop (file (getattr map open read)))' "$PLAT_SEPOLICY" ||
        fail 'system_server cannot read the Uperf transport property'
    if grep -Eq '^\(allow (shell|priv_app|untrusted_app) zui_control_uperf_mode_prop ' "$PLAT_SEPOLICY"; then
        fail 'non-system_server domain can write/read the Uperf transport property'
    fi
    grep -Fq 'property_mode="${1:-}"' "$SCHEDULER_PREPARE" ||
        fail 'prepare does not consume the init-supplied property value'
    grep -Fq 'effective_mode="$property_mode"' "$SCHEDULER_PREPARE" ||
        fail 'prepare does not prefer a valid property mode'
    grep -Fq 'effective_mode="$global_mode"' "$SCHEDULER_PREPARE" ||
        fail 'prepare does not recover from the durable global mode'
    [ "$(grep -Fc '${sys.zui_control.uperf_mode:-unset}' "$SCHEDULER_RC")" -eq 2 ] ||
        fail 'boot and restart do not both pass the property mode to prepare'
    if grep -Eq 'UPERF_(SCENE|SCREEN)_KEY|UPERF_FRONTEND|sync_uperf_frontend|write_uperf_effective_mode|uperf_rule_for_scene' "$DAEMON"; then
        fail 'daemon still contains the retired scene/screen frontend'
    fi
}

assert_command_wakeup_policy() {
    [ "$(grep -c '^on property:sys\.zui_control\.command_seq=\*$' "$CONTROL_RC")" -eq 1 ] ||
        fail 'command wakeup does not have exactly one wildcard property trigger'
    [ "$(grep -Fxc '    start zui_control_request' "$CONTROL_RC")" -eq 1 ] ||
        fail 'only the command property may start the oneshot request service'
    grep -Fqx 'service zui_control_request /system/bin/sh /system/bin/zui_controld --oneshot-request ${sys.zui_control.command_id:-unset} ${sys.zui_control.command_sha256:-unset}' "$CONTROL_RC" ||
        fail 'oneshot request service command mismatch'
    grep -Fqx '    oneshot' "$CONTROL_RC" || fail 'request service is not oneshot'
    grep -Fqx 'sys.zui_control.command_seq u:object_r:zui_control_command_seq_prop:s0 exact string' "$PROPERTY_CONTEXTS" ||
        fail 'command wakeup property is not exact string typed'
    for name in command_id command_sha256; do
        grep -Fqx "sys.zui_control.$name u:object_r:zui_control_command_auth_prop:s0 exact string" "$PROPERTY_CONTEXTS" ||
            fail "command authentication property $name is not exact string typed"
    done
    grep -Fqx '(allow system_server zui_control_command_seq_prop (property_service (set)))' "$PLAT_SEPOLICY" ||
        fail 'system_server cannot ring the command doorbell'
    grep -Fqx '(allow system_server zui_control_command_auth_prop (property_service (set)))' "$PLAT_SEPOLICY" ||
        fail 'system_server cannot bind authenticated command metadata'
    if grep -Eq '^\(allow (shell|priv_app|untrusted_app) zui_control_command_seq_prop .*property_service.*set' "$PLAT_SEPOLICY"; then
        fail 'non-system_server domain can set the command doorbell property'
    fi
    if grep -Eq '^\(allow (shell|priv_app|untrusted_app) zui_control_command_auth_prop .*property_service.*set' "$PLAT_SEPOLICY"; then
        fail 'non-system_server domain can set command authentication properties'
    fi
    grep -Fq 'TX_NOTIFY_CONTROL_REQUEST = 12' "$SERVICE_SOURCE" ||
        fail 'system service command doorbell transaction missing'
    grep -Fq 'case TX_NOTIFY_CONTROL_REQUEST:' "$SERVICE_SOURCE" ||
        fail 'system service command transaction missing'
    grep -Fq 'enforceCommandCallerAllowed();' "$SERVICE_SOURCE" ||
        fail 'command doorbell does not require strict App authentication'
    grep -Fq 'enforceZuiControlCaller(Binder.getCallingUid());' "$SERVICE_SOURCE" ||
        fail 'command doorbell retains a UID 1000 bypass'
    grep -Fq 'ApplicationInfo.FLAG_DEBUGGABLE' "$SERVICE_SOURCE" ||
        fail 'debug certificate is not restricted to debuggable builds'
    grep -Fq 'SystemProperties.set(PROP_COMMAND_SEQ, token);' "$SERVICE_SOURCE" ||
        fail 'system service does not ring the init property doorbell'
    grep -Fq 'SystemProperties.set(PROP_COMMAND_ID, id);' "$SERVICE_SOURCE" ||
        fail 'system service does not bind request ID before ringing the doorbell'
    grep -Fq 'SystemProperties.set(PROP_COMMAND_SHA256, sha256);' "$SERVICE_SOURCE" ||
        fail 'system service does not bind request digest before ringing the doorbell'
    grep -Fq 'request_payload_mismatch' "$SERVICE_SOURCE" ||
        fail 'system service does not validate the Settings payload digest'
    grep -Fq 'notifyControlRequest(final String requestId, final String requestSha256)' "$MANAGER_SOURCE" ||
        fail 'framework manager doorbell API missing'
    grep -Fq 'INITIAL_KICK_RETRY_MS = 1_000L' "$APP_REQUEST_SOURCE" ||
        fail 'App command retry does not begin near one second'
    grep -Fq 'MAX_KICK_RETRIES = 6' "$APP_REQUEST_SOURCE" ||
        fail 'App command retry is not bounded'
    grep -Fq 'createDeviceProtectedStorageContext()' "$APP_REQUEST_SOURCE" ||
        fail 'App pending authentication record is not direct-boot durable'
    grep -Fq 'savePending(context, pending)' "$APP_REQUEST_SOURCE" ||
        fail 'App does not persist trusted request metadata before kick'
    grep -Fq 'ZuiControlRequest.kickPending(context)' "$APP_BOOT_SOURCE" ||
        fail 'boot receiver does not safely re-kick a trusted pending request'
    grep -Fqx '    mkdir /data/vendor/zui_control/zuicontrol 0700 root root' "$CONTROL_RC" ||
        fail 'transaction directory is writable outside root'
    grep -Fqx '    mkdir /data/vendor/zui_control 0755 root root' "$CONTROL_RC" ||
        fail 'command rc leaves the transaction parent renameable by shell'
    grep -Fqx '    mkdir /data/vendor/zui_control 0755 root root' "$SCHEDULER_RC" ||
        fail 'scheduler rc leaves the transaction parent renameable by shell'
    grep -Fq 'chmod 0755 "$DATA_ROOT"' "$DAEMON" ||
        fail 'daemon does not preserve root-only mutation of the transaction parent'

    oneshot_body="$(sed -n '/^oneshot_request() {/,/^}/p' "$DAEMON")"
    if grep -Eq '^service[[:space:]]+zui_controld([[:space:]]|$)' "$CONTROL_RC"; then
        fail 'persistent zui_controld init service still exists'
    fi
    if grep -Eq '^[[:space:]]+start[[:space:]]+zui_controld([[:space:]]|$)' "$CONTROL_RC"; then
        fail 'boot or another init action still starts persistent zui_controld'
    fi
    if grep -Fq 'main_loop()' "$DAEMON" || grep -Fq 'publish_scheduler_health' "$DAEMON"; then
        fail 'retired persistent health loop remains in zui_controld'
    fi
    printf '%s\n' "$oneshot_body" | grep -Fq 'init_request_state' ||
        fail 'oneshot request path does not recover transaction state'
    printf '%s\n' "$oneshot_body" | grep -Fq 'process_settings_request' ||
        fail 'oneshot request path does not process one request'
    printf '%s\n' "$oneshot_body" | grep -Fq 'captured_sha256="$(request_sha256 "$captured_request")"' ||
        fail 'oneshot request path does not bind the captured payload digest'
    grep -Fq 'atomic_write_text "$ACTIVE_REQUEST_CLAIM" "$1" 0600' "$DAEMON" ||
        fail 'active request claim is not root-private'
    grep -Fq '$terminal_ack" 0600 || return 1' "$DAEMON" ||
        fail 'terminal receipt is not root-private'
}

assert_receipt_dac_policy() {
    for file in last_request_receipt active_request_claim; do
        grep -Fqx "    chown root root /data/vendor/zui_control/zuicontrol/$file" "$CONTROL_RC" ||
            fail "$file owner is not repaired during post-fs-data"
        grep -Fqx "    chmod 0600 /data/vendor/zui_control/zuicontrol/$file" "$CONTROL_RC" ||
            fail "$file mode is not repaired during post-fs-data"
        grep -Fqx "    restorecon /data/vendor/zui_control/zuicontrol/$file" "$CONTROL_RC" ||
            fail "$file SELinux context is not repaired during post-fs-data"
    done
    [ "$(grep -Fc 'atomic_write_text "$ACTIVE_REQUEST_CLAIM" "$1" 0600' "$DAEMON")" -eq 1 ] ||
        fail 'active claim is not atomically created with mode 0600'
    [ "$(grep -Fc 'atomic_write_text "$LAST_REQUEST_RECEIPT"' "$DAEMON")" -eq 1 ] ||
        fail 'terminal receipt does not have one atomic writer'
}

assert_timing_policy() {
    for phase in T4 T5 T6 T7 T8; do
        [ "$(grep -Ec "timing_mark .* ${phase}( |\")" "$DAEMON")" -eq 1 ] ||
            fail "$phase must have exactly one shell timing mark"
    done
    oneshot_body="$(sed -n '/^oneshot_request() {/,/^}/p' "$DAEMON")"
    process_body="$(sed -n '/^process_settings_request() {/,/^}/p' "$DAEMON")"
    finish_body="$(sed -n '/^finish_request() {/,/^}/p' "$DAEMON")"
    ack_body="$(sed -n '/^publish_pending_terminal_ack() {/,/^}/p' "$DAEMON")"
    printf '%s\n' "$oneshot_body" | grep -Eq 'timing_mark .* T4([[:space:]]|$)' ||
        fail 'T4 does not mark authenticated oneshot entry'
    printf '%s\n' "$process_body" | grep -Fq 'timing_mark "$id" T5' ||
        fail 'T5 does not follow the durable claim path'
    printf '%s\n' "$process_body" | grep -Fq 'timing_mark "$id" T6' ||
        fail 'T6 does not mark action completion'
    printf '%s\n' "$finish_body" | grep -Fq 'timing_mark "$id" T7' ||
        fail 'T7 does not mark durable terminal receipt completion'
    printf '%s\n' "$ack_body" | grep -Eq 'timing_mark .* T8([[:space:]]|$)' ||
        fail 'T8 does not mark successful terminal ACK publication'
}

assert_oem_fence_ownership_policy() {
    grep -Fqx 'sys.zui_control.scheduler_active u:object_r:zui_control_scheduler_active_prop:s0 exact enum 0 1' "$PROPERTY_CONTEXTS" ||
        fail 'scheduler ownership property is not an exact 0/1 enum'
    grep -Fqx '(allow init zui_control_scheduler_active_prop (property_service (set)))' "$PLAT_SEPOLICY" ||
        fail 'init cannot set scheduler ownership'
    grep -Fqx '(allow system_server zui_control_scheduler_active_prop (file (getattr map open read)))' "$PLAT_SEPOLICY" ||
        fail 'system_server cannot read scheduler ownership'
    if grep -Eq '^\(allow (system_server|shell|priv_app|untrusted_app) zui_control_scheduler_active_prop .*property_service.*set' "$PLAT_SEPOLICY"; then
        fail 'a non-init domain can set scheduler ownership'
    fi
    [ "$(grep -Fxc 'on property:init.svc.vendor.perfservice=running && property:sys.zui_control.scheduler_active=1' "$SCHEDULER_RC")" -eq 1 ] ||
        fail 'OEM fence is not exactly conditional on active scheduler ownership'
    if grep -F 'on property:init.svc.vendor.perfservice=running' "$SCHEDULER_RC" |
        grep -Fvq '&& property:sys.zui_control.scheduler_active=1'; then
        fail 'an unconditional OEM perfservice fence remains'
    fi
    grep -Fqx 'on property:zui_control.scheduler=fence && property:sys.zui_control.scheduler_active=1' "$SCHEDULER_RC" ||
        fail 'manual compatibility fence ignores scheduler ownership'
    grep -Fqx 'on property:zui_control.asoul=start && property:sys.zui_control.scheduler_active=1' "$SCHEDULER_RC" ||
        fail 'A-SOUL start bypasses inactive scheduler ownership'

    stop_body="$(sed -n '/^on property:zui_control.scheduler=stop$/,/^$/p' "$SCHEDULER_RC")"
    printf '%s\n' "$stop_body" | grep -Fq '    setprop sys.zui_control.scheduler_active 0' ||
        fail 'scheduler stop does not release ownership'
    printf '%s\n' "$stop_body" | grep -Fq '    start vendor.perfservice' ||
        fail 'scheduler stop does not restore OEM perfservice'
    release_line="$(printf '%s\n' "$stop_body" | grep -nF 'setprop sys.zui_control.scheduler_active 0' | cut -d: -f1)"
    restore_line="$(printf '%s\n' "$stop_body" | grep -nF 'start vendor.perfservice' | cut -d: -f1)"
    [ "$release_line" -lt "$restore_line" ] ||
        fail 'scheduler ownership is not released before OEM perfservice starts'
}

test_noarg_entrypoint_is_closed() {
    set +e
    sh "$DAEMON" >/dev/null 2>&1
    result=$?
    set -e
    [ "$result" -eq 2 ] || fail "no-argument zui_controld returned $result instead of 2"
}

test_control_plane_does_not_write_effective() (
    ZUI_CONTROLD_TEST_MODE=1
    export ZUI_CONTROLD_TEST_MODE
    # shellcheck source=/dev/null
    . "$DAEMON"
    setup_state
    trap 'rm -rf "$TEST_ROOT"' EXIT

    before_inode="$(stat -c %i "$UPERF_EFFECTIVE_MODE")"
    before_sum="$(cksum "$UPERF_EFFECTIVE_MODE")"
    set_uperf_mode fast || fail 'valid global mode rejected'
    [ "$(tr -d '\r\n ' < "$UPERF_MODE")" = fast ] || fail 'global mode not persisted'
    [ "$TEST_MODE_STATE" = fast ] || fail 'global mode event was not published'
    [ "$(stat -c %i "$UPERF_EFFECTIVE_MODE")" = "$before_inode" ] ||
        fail 'daemon replaced the effective-mode inode'
    [ "$(cksum "$UPERF_EFFECTIVE_MODE")" = "$before_sum" ] ||
        fail 'daemon wrote the effective-mode file'
    if set_uperf_mode auto; then fail 'retired auto mode accepted'; fi
    [ "$(tr -d '\r\n ' < "$UPERF_MODE")" = fast ] ||
        fail 'invalid mode changed the global mode'
)

test_custom_app_lifecycle() (
    ZUI_CONTROLD_TEST_MODE=1
    export ZUI_CONTROLD_TEST_MODE
    . "$DAEMON"
    setup_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    before_inode="$(stat -c %i "$UPERF_EFFECTIVE_MODE")"
    before_sum="$(cksum "$UPERF_EFFECTIVE_MODE")"

    set_uperf_app_mode com.example.game fast || fail 'custom app rejected'
    grep -qx 'com.example.game fast' "$UPERF_PERAPP" || fail 'custom app not persisted'
    printf '%s\n' "$TEST_RULES_STATE" | grep -qx 'com.example.game|fast' ||
        fail 'custom app event was not published'
    [ "$(stat -c %i "$UPERF_EFFECTIVE_MODE")" = "$before_inode" ] ||
        fail 'custom app update replaced the effective-mode inode'
    [ "$(cksum "$UPERF_EFFECTIVE_MODE")" = "$before_sum" ] ||
        fail 'custom app update wrote the effective-mode file'
    if set_uperf_app_mode com.android.systemui fast; then fail 'system app accepted'; fi

    remove_uperf_app_mode com.example.game || fail 'custom app removal failed'
    if grep -q '^com.example.game ' "$UPERF_PERAPP"; then fail 'custom app survived removal'; fi
    if printf '%s\n' "$TEST_RULES_STATE" | grep -q '^com.example.game|'; then
        fail 'removed custom app survived the published event'
    fi
    [ "$(stat -c %i "$UPERF_EFFECTIVE_MODE")" = "$before_inode" ] ||
        fail 'custom app removal replaced the effective-mode inode'
    [ "$(cksum "$UPERF_EFFECTIVE_MODE")" = "$before_sum" ] ||
        fail 'custom app removal wrote the effective-mode file'
)

test_minimal_request_and_receipt() (
    ZUI_CONTROLD_TEST_MODE=1
    export ZUI_CONTROLD_TEST_MODE
    . "$DAEMON"
    setup_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    TEST_REQUEST='id-1|set_uperf_mode|||performance'
    LAST_SETTINGS_REQUEST=
    LAST_COMPLETED_REQUEST_ID=
    TERMINAL_ACK_PENDING=0

    process_settings_request || fail 'settings request failed'
    [ "$TEST_ACK" = 'id-1|done|set_uperf_mode|global=performance' ] ||
        fail 'terminal ACK mismatch'
    [ "$(tr -d '\r\n ' < "$UPERF_EFFECTIVE_MODE")" = balance ] ||
        fail 'request path wrote the effective-mode file'
    [ "$(sed -n '1p' "$LAST_REQUEST_RECEIPT")" = "$TEST_REQUEST" ] ||
        fail 'request receipt missing request'
    [ "$(sed -n '2p' "$LAST_REQUEST_RECEIPT")" = "$TEST_ACK" ] ||
        fail 'request receipt missing ACK'
    mode_probe="$TEST_ROOT/mode_probe"
    : > "$mode_probe"
    chmod 0600 "$mode_probe"
    if [ "$(stat -c %a "$mode_probe")" = 600 ]; then
        [ "$(stat -c %a "$LAST_REQUEST_RECEIPT")" = 600 ] ||
            fail 'request receipt was not created with mode 0600'
    fi
)

test_config_publication_failure_is_terminal_and_recoverable() (
    ZUI_CONTROLD_TEST_MODE=1
    export ZUI_CONTROLD_TEST_MODE
    . "$DAEMON"
    setup_state
    trap 'rm -rf "$TEST_ROOT"' EXIT

    TEST_REQUEST='publish-fail|set_uperf_mode|||performance'
    TEST_CONFIG_PUT_FAILURES=1
    LAST_SETTINGS_REQUEST=
    LAST_COMPLETED_REQUEST_ID=
    TERMINAL_ACK_PENDING=0

    if process_settings_request; then fail 'Settings publication failure returned success'; fi
    [ "$TEST_ACK" = 'publish-fail|failed|set_uperf_mode|settings_publish_failed' ] ||
        fail 'Settings publication failure ACK mismatch'
    [ "$(tr -d '\r\n ' < "$UPERF_MODE")" = performance ] ||
        fail 'durable mode was lost after publication failure'
    [ "$UPERF_MODE_DIRTY" -eq 1 ] || fail 'failed publication was not left pending'

    process_settings_request || fail 'terminal failed receipt did not deduplicate'
    [ "$TEST_CONFIG_PUTS" -eq 1 ] || fail 'duplicate failed request retried its action'

    publish_uperf_mode_state || fail 'pending mode publication did not recover'
    [ "$UPERF_MODE_DIRTY" -eq 0 ] || fail 'recovered publication remained dirty'
    [ "$TEST_MODE_STATE" = performance ] || fail 'recovered publication used the wrong mode'
    publish_uperf_rules_state || fail 'startup rules publication did not succeed'
)

test_timing_phase_sequence() (
    ZUI_CONTROLD_TEST_MODE=1
    export ZUI_CONTROLD_TEST_MODE
    . "$DAEMON"
    setup_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    ensure_request_dirs() { :; }

    TEST_REQUEST='timing-id|status|||'
    authenticated_oneshot || fail 'timing request failed'
    phases="$(printf '%s' "$TEST_TIMING" | awk -F '|' '
        NF { if (out != "") out=out ","; out=out $2 }
        END { print out }
    ')"
    [ "$phases" = 'T4,T5,T6,T7,T8' ] ||
        fail "shell timing sequence mismatch: $phases"
    [ "$TEST_ACK" = 'timing-id|done|status|state=binder' ] ||
        fail 'timing request terminal ACK mismatch'
)

test_terminal_request_dedup_and_recovery() (
    ZUI_CONTROLD_TEST_MODE=1
    export ZUI_CONTROLD_TEST_MODE
    . "$DAEMON"
    setup_state
    trap 'rm -rf "$TEST_ROOT"' EXIT

    handle_command() {
        TEST_ACTIONS=$((TEST_ACTIONS + 1))
        REQUEST_RESULT_DETAIL="action=$TEST_ACTIONS"
        [ "$1" != fail_test ]
    }

    TEST_REQUEST='id-1|status|||'
    LAST_SETTINGS_REQUEST=
    LAST_COMPLETED_REQUEST_ID=
    TERMINAL_ACK_PENDING=0
    process_settings_request || fail 'new request failed'
    [ "$TEST_ACTIONS" -eq 1 ] || fail 'new request action count was not one'
    [ "$TEST_TERMINAL_ACK_PUTS" -eq 1 ] || fail 'new request terminal ACK count was not one'
    first_ack="$TEST_ACK"

    loops=0
    while [ "$loops" -lt 10 ]; do
        process_settings_request || fail 'duplicate request loop failed'
        loops=$((loops + 1))
    done
    [ "$TEST_ACTIONS" -eq 1 ] || fail 'same request repeated its action'
    [ "$TEST_TERMINAL_ACK_PUTS" -eq 1 ] || fail 'same request repeated terminal ACK put'

    # Simulate a daemon crash after the durable receipt was committed but before
    # the terminal Settings ACK became visible.
    TEST_ACK=
    TEST_ACK_PUTS=0
    TEST_TERMINAL_ACK_PUTS=0
    LAST_SETTINGS_REQUEST=
    LAST_COMPLETED_REQUEST_ID=
    TERMINAL_ACK_PENDING=0
    init_request_state
    [ "$TEST_ACK" = "$first_ack" ] || fail 'restart did not recover terminal ACK'
    [ "$TEST_TERMINAL_ACK_PUTS" -eq 1 ] || fail 'restart recovery did not publish exactly once'
    loops=0
    while [ "$loops" -lt 10 ]; do
        process_settings_request || fail 'post-restart duplicate loop failed'
        loops=$((loops + 1))
    done
    [ "$TEST_ACTIONS" -eq 1 ] || fail 'restart repeated old action'
    [ "$TEST_TERMINAL_ACK_PUTS" -eq 1 ] || fail 'restart repeated recovered terminal ACK'

    # A restart with an already-visible terminal ACK must not write it again.
    LAST_SETTINGS_REQUEST=
    LAST_COMPLETED_REQUEST_ID=
    TERMINAL_ACK_PENDING=0
    init_request_state
    [ "$TEST_TERMINAL_ACK_PUTS" -eq 1 ] || fail 'restart rewrote an existing terminal ACK'

    TEST_REQUEST='id-2|status|||'
    process_settings_request || fail 'new request ID failed after recovery'
    [ "$TEST_ACTIONS" -eq 2 ] || fail 'new request ID did not execute'
    [ "$TEST_TERMINAL_ACK_PUTS" -eq 2 ] || fail 'new request ID terminal ACK missing'

    # Reusing a completed ID with different text must not repeat an action.
    TEST_REQUEST='id-2|status||com.example.changed|'
    process_settings_request || fail 'same-ID altered request handling failed'
    [ "$TEST_ACTIONS" -eq 2 ] || fail 'same request ID repeated an action'
    [ "$TEST_TERMINAL_ACK_PUTS" -eq 2 ] || fail 'same request ID rewrote terminal ACK'

    TEST_REQUEST='id-fail|fail_test|||'
    if process_settings_request; then fail 'failing handler unexpectedly succeeded'; fi
    [ "$TEST_ACTIONS" -eq 3 ] || fail 'failed request action count mismatch'
    [ "$TEST_ACK" = 'id-fail|failed|fail_test|action=3' ] || fail 'failed request ACK mismatch'
    [ "$TEST_TERMINAL_ACK_PUTS" -eq 3 ] || fail 'failed terminal ACK count mismatch'
    loops=0
    while [ "$loops" -lt 10 ]; do
        process_settings_request || fail 'failed receipt duplicate loop failed'
        loops=$((loops + 1))
    done
    [ "$TEST_ACTIONS" -eq 3 ] || fail 'failed receipt repeated its action'
    [ "$TEST_TERMINAL_ACK_PUTS" -eq 3 ] || fail 'failed receipt repeated terminal ACK'

    TEST_ACK=
    LAST_SETTINGS_REQUEST=
    LAST_COMPLETED_REQUEST_ID=
    TERMINAL_ACK_PENDING=0
    init_request_state
    [ "$TEST_ACK" = 'id-fail|failed|fail_test|action=3' ] || fail 'failed receipt restart ACK recovery mismatch'
    [ "$TEST_ACTIONS" -eq 3 ] || fail 'failed receipt restart repeated its action'
    [ "$TEST_TERMINAL_ACK_PUTS" -eq 4 ] || fail 'failed receipt restart recovery did not publish once'
)

test_new_request_survives_old_receipt_on_restart() (
    ZUI_CONTROLD_TEST_MODE=1
    export ZUI_CONTROLD_TEST_MODE
    . "$DAEMON"
    setup_state
    trap 'rm -rf "$TEST_ROOT"' EXIT

    handle_command() {
        TEST_ACTIONS=$((TEST_ACTIONS + 1))
        REQUEST_RESULT_DETAIL="action=$TEST_ACTIONS"
        return 0
    }

    TEST_REQUEST='old-id|status|||'
    process_settings_request || fail 'old request setup failed'
    old_ack="$TEST_ACK"
    TEST_REQUEST='new-id|status|||'
    LAST_SETTINGS_REQUEST=
    LAST_COMPLETED_REQUEST_ID=
    TERMINAL_ACK_PENDING=0
    init_request_state
    [ "$TEST_ACK" = "$old_ack" ] || fail 'restart unexpectedly rewrote ACK for a new pending request'
    [ "$TEST_TERMINAL_ACK_PUTS" -eq 1 ] || fail 'old receipt was replayed over a new pending request'
    process_settings_request || fail 'new pending request failed after restart'
    [ "$TEST_ACTIONS" -eq 2 ] || fail 'new pending request did not execute'
    [ "$TEST_ACK" = 'new-id|done|status|action=2' ] || fail 'new pending request ACK mismatch'
)

test_late_settings_visibility_recovers_once() (
    ZUI_CONTROLD_TEST_MODE=1
    export ZUI_CONTROLD_TEST_MODE
    . "$DAEMON"
    setup_state
    trap 'rm -rf "$TEST_ROOT"' EXIT

    handle_command() {
        TEST_ACTIONS=$((TEST_ACTIONS + 1))
        REQUEST_RESULT_DETAIL="action=$TEST_ACTIONS"
        return 0
    }

    TEST_REQUEST='late-id|status|||'
    process_settings_request || fail 'late visibility setup failed'
    expected_ack="$TEST_ACK"
    TEST_REQUEST=
    TEST_ACK=
    LAST_SETTINGS_REQUEST=
    LAST_COMPLETED_REQUEST_ID=
    init_request_state
    [ -z "$LAST_SETTINGS_REQUEST" ] || fail 'empty Settings request was marked as seen'
    TEST_REQUEST='late-id|status|||'
    process_settings_request || fail 'late Settings request recovery failed'
    [ "$TEST_ACK" = "$expected_ack" ] || fail 'late Settings request ACK recovery mismatch'
    [ "$TEST_ACTIONS" -eq 1 ] || fail 'late Settings request repeated its action'
    recovered_puts="$TEST_TERMINAL_ACK_PUTS"
    process_settings_request || fail 'late Settings duplicate loop failed'
    [ "$TEST_TERMINAL_ACK_PUTS" -eq "$recovered_puts" ] || fail 'late Settings ACK recovered more than once'
)

test_terminal_ack_put_retry_without_action_replay() (
    ZUI_CONTROLD_TEST_MODE=1
    export ZUI_CONTROLD_TEST_MODE
    . "$DAEMON"
    setup_state
    trap 'rm -rf "$TEST_ROOT"' EXIT

    handle_command() {
        TEST_ACTIONS=$((TEST_ACTIONS + 1))
        REQUEST_RESULT_DETAIL="action=$TEST_ACTIONS"
        return 0
    }

    TEST_REQUEST='retry-id|status|||'
    TEST_TERMINAL_ACK_PUT_FAILURES=1
    LAST_SETTINGS_REQUEST=
    LAST_COMPLETED_REQUEST_ID=
    TERMINAL_ACK_PENDING=0
    process_settings_request || fail 'request failed when terminal ACK transport failed'
    [ "$TEST_ACTIONS" -eq 1 ] || fail 'ACK transport failure changed action count'
    [ "$TEST_TERMINAL_ACK_PUT_ATTEMPTS" -eq 1 ] || fail 'first terminal ACK attempt count mismatch'
    [ "$TEST_TERMINAL_ACK_PUTS" -eq 0 ] || fail 'failed terminal ACK attempt was counted as success'
    [ "$TERMINAL_ACK_PENDING" -eq 1 ] || fail 'failed terminal ACK was not left pending'

    process_settings_request || fail 'pending terminal ACK retry failed'
    [ "$TEST_ACTIONS" -eq 1 ] || fail 'terminal ACK retry repeated action'
    [ "$TEST_TERMINAL_ACK_PUT_ATTEMPTS" -eq 2 ] || fail 'terminal ACK was not retried exactly once'
    [ "$TEST_TERMINAL_ACK_PUTS" -eq 1 ] || fail 'terminal ACK retry did not succeed'
    [ "$TEST_ACK" = 'retry-id|done|status|action=1' ] || fail 'retried terminal ACK mismatch'
    [ "$TERMINAL_ACK_PENDING" -eq 0 ] || fail 'successful terminal ACK remained pending'

    loops=0
    while [ "$loops" -lt 10 ]; do
        process_settings_request || fail 'post-retry duplicate loop failed'
        loops=$((loops + 1))
    done
    [ "$TEST_ACTIONS" -eq 1 ] || fail 'post-retry loop repeated action'
    [ "$TEST_TERMINAL_ACK_PUT_ATTEMPTS" -eq 2 ] || fail 'post-retry loop repeated terminal ACK put'
)

test_oneshot_duplicate_kicks_and_stress() (
    ZUI_CONTROLD_TEST_MODE=1
    export ZUI_CONTROLD_TEST_MODE
    . "$DAEMON"
    setup_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    ensure_request_dirs() { :; }
    handle_command() {
        TEST_ACTIONS=$((TEST_ACTIONS + 1))
        REQUEST_RESULT_DETAIL="action=$TEST_ACTIONS"
        return 0
    }

    TEST_REQUEST='kick-id|status|||'
    authenticated_oneshot || fail 'first oneshot kick failed'
    loops=0
    while [ "$loops" -lt 10 ]; do
        authenticated_oneshot || fail 'duplicate oneshot kick failed'
        loops=$((loops + 1))
    done
    [ "$TEST_ACTIONS" -eq 1 ] || fail 'ten duplicate kicks repeated the action'
    [ "$TEST_TERMINAL_ACK_PUTS" -eq 1 ] || fail 'ten duplicate kicks repeated terminal ACK'

    index=1
    while [ "$index" -le 100 ]; do
        TEST_REQUEST="stress-$index|status|||"
        authenticated_oneshot || fail "stress command $index failed"
        index=$((index + 1))
    done
    [ "$TEST_ACTIONS" -eq 101 ] || fail '100 unique oneshot commands did not each execute once'
    [ "$TEST_TERMINAL_ACK_PUTS" -eq 101 ] || fail '100 unique oneshot commands did not each ACK once'
    [ ! -e "$ACTIVE_REQUEST_CLAIM" ] || fail 'successful commands left an active claim'
)

test_oneshot_requires_authenticated_exact_payload() (
    ZUI_CONTROLD_TEST_MODE=1
    export ZUI_CONTROLD_TEST_MODE
    . "$DAEMON"
    setup_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    ensure_request_dirs() { :; }
    handle_command() {
        TEST_ACTIONS=$((TEST_ACTIONS + 1))
        REQUEST_RESULT_DETAIL="action=$TEST_ACTIONS"
        return 0
    }

    trusted_request='bound-id|status|||'
    trusted_id="$(request_field "$trusted_request" 1)"
    trusted_sha256="$(request_sha256 "$trusted_request")"
    TEST_REQUEST='bound-id|status||com.example.tampered|'
    oneshot_request "$trusted_id" "$trusted_sha256" ||
        fail 'payload mismatch should fail closed without a service error'
    [ "$TEST_ACTIONS" -eq 0 ] || fail 'stale digest authorized altered request text'
    [ -z "$TEST_ACK" ] || fail 'rejected payload emitted an ACK'
    [ ! -e "$ACTIVE_REQUEST_CLAIM" ] || fail 'rejected payload created an active claim'
    [ ! -e "$LAST_REQUEST_RECEIPT" ] || fail 'rejected payload created a receipt'

    actual_sha256="$(request_sha256 "$TEST_REQUEST")"
    id() { printf '2000\n'; }
    if oneshot_request "$trusted_id" "$actual_sha256"; then
        fail 'non-root direct invocation entered the command transaction'
    fi
    [ "$TEST_ACTIONS" -eq 0 ] || fail 'non-root direct invocation executed an action'
    id() { printf '0\n'; }

    oneshot_request "$trusted_id" "$actual_sha256" ||
        fail 'matching authenticated payload was rejected'
    [ "$TEST_ACTIONS" -eq 1 ] || fail 'matching authenticated payload did not execute once'
    [ "$TEST_ACK" = 'bound-id|done|status|action=1' ] ||
        fail 'matching authenticated payload ACK mismatch'
)

test_oneshot_failure_windows() (
    ZUI_CONTROLD_TEST_MODE=1
    export ZUI_CONTROLD_TEST_MODE
    . "$DAEMON"
    setup_state
    trap 'rm -rf "$TEST_ROOT"' EXIT
    ensure_request_dirs() { :; }
    handle_command() {
        TEST_ACTIONS=$((TEST_ACTIONS + 1))
        REQUEST_RESULT_DETAIL="action=$TEST_ACTIONS"
        return 0
    }

    # Service failure before reading/claiming a request is safely retryable.
    TEST_REQUEST='before-claim|status|||'
    ensure_request_dirs() { return 1; }
    if authenticated_oneshot; then fail 'blocked oneshot unexpectedly succeeded'; fi
    [ "$TEST_ACTIONS" -eq 0 ] || fail 'blocked oneshot executed an action'
    [ ! -e "$ACTIVE_REQUEST_CLAIM" ] || fail 'blocked oneshot created a claim'
    ensure_request_dirs() { :; }
    authenticated_oneshot || fail 're-kick after service failure did not recover'
    [ "$TEST_ACTIONS" -eq 1 ] || fail 're-kick after service failure action count mismatch'

    # A crash after the durable claim but before the action must never replay it.
    TEST_REQUEST='after-claim-before-action|status|||'
    persist_request_claim "$TEST_REQUEST" || fail 'could not stage pre-action crash claim'
    TEST_ACK=
    LAST_SETTINGS_REQUEST=
    LAST_COMPLETED_REQUEST_ID=
    TERMINAL_ACK_PENDING=0
    authenticated_oneshot || true
    [ "$TEST_ACTIONS" -eq 1 ] || fail 'pre-action claimed request executed after recovery'
    [ "$TEST_ACK" = 'after-claim-before-action|failed|status|indeterminate_after_claim' ] ||
        fail 'pre-action crash did not receive indeterminate terminal ACK'

    # A crash after the action but before terminal receipt has the same safe,
    # deliberately conservative recovery: report uncertainty, never replay.
    TEST_REQUEST='after-action-before-receipt|status|||'
    persist_request_claim "$TEST_REQUEST" || fail 'could not stage post-action crash claim'
    handle_command status '' '' || fail 'staged action failed'
    [ "$TEST_ACTIONS" -eq 2 ] || fail 'staged post-action count mismatch'
    TEST_ACK=
    LAST_SETTINGS_REQUEST=
    LAST_COMPLETED_REQUEST_ID=
    TERMINAL_ACK_PENDING=0
    authenticated_oneshot || true
    [ "$TEST_ACTIONS" -eq 2 ] || fail 'post-action crash recovery replayed the action'
    [ "$TEST_ACK" = 'after-action-before-receipt|failed|status|indeterminate_after_claim' ] ||
        fail 'post-action crash did not receive indeterminate terminal ACK'
    [ ! -e "$ACTIVE_REQUEST_CLAIM" ] || fail 'failure recovery left an active claim'
)

assert_default_policy
assert_event_transport_policy
assert_command_wakeup_policy
assert_receipt_dac_policy
assert_timing_policy
assert_oem_fence_ownership_policy
test_noarg_entrypoint_is_closed
test_control_plane_does_not_write_effective
test_custom_app_lifecycle
test_minimal_request_and_receipt
test_config_publication_failure_is_terminal_and_recoverable
test_timing_phase_sequence
test_terminal_request_dedup_and_recovery
test_new_request_survives_old_receipt_on_restart
test_late_settings_visibility_recovers_once
test_terminal_ack_put_retry_without_action_replay
test_oneshot_duplicate_kicks_and_stress
test_oneshot_requires_authenticated_exact_payload
test_oneshot_failure_windows
printf 'PASS: zui_controld Uperf/A-SOUL tests\n'
