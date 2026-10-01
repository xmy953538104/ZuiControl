#!/system/bin/sh

DATA_ROOT=/data/vendor/zui_control
UPERF_DIR=$DATA_ROOT/uperf
LOG_DIR=$DATA_ROOT/log
SYSTEM_UPERF=/system/etc/zui_control/uperf-sm8650.json
SYSTEM_PERAPP=/system/etc/zui_control/default_uperf_perapp.txt
GLOBAL_MODE=$UPERF_DIR/cur_powermode.txt
EFFECTIVE_MODE=$UPERF_DIR/effective_powermode.txt
PERAPP=$UPERF_DIR/perapp_powermode.txt

valid_preset() {
    case "$1" in
        powersave|balance|performance|fast) return 0 ;;
        *) return 1 ;;
    esac
}

mkdir -p "$UPERF_DIR" || exit 1
AUTH=$UPERF_DIR/.validated_runtime.sha256
rm -f "$AUTH" "$AUTH.tmp" || exit 1
# Preserve finite bootstrap diagnostics; failure must never start stale config.
mkdir -p "$LOG_DIR" || exit 1
# Bounded completed bootstrap diagnostics; never truncate the current invocation.
if [ -f "$LOG_DIR/bootstrap.log" ] && [ "$(stat -c %s "$LOG_DIR/bootstrap.log")" -gt 262144 ]; then
    mv -f "$LOG_DIR/bootstrap.log" "$LOG_DIR/bootstrap.log.1" || exit 1
fi
exec >> "$LOG_DIR/bootstrap.log" 2>&1
trap 'setprop sys.zui_control.uperf_fail_safe 1' EXIT
# Persist the virgin-store seed intent before either legacy-compatible seed write.
# A power loss between those writes and Settings publication must be resumable.
SEED_INTENT=$UPERF_DIR/.policy_factory_seed
if [ ! -f "$UPERF_DIR/policy-projection/active.json" ]; then
if [ ! -e "$GLOBAL_MODE" ] && [ ! -e "$PERAPP" ] && [ ! -e "$SEED_INTENT" ]; then
    (umask 077; sha256sum "$SYSTEM_PERAPP" > "$SEED_INTENT.tmp") || exit 1
    sync "$SEED_INTENT.tmp" || exit 1
    mv "$SEED_INTENT.tmp" "$SEED_INTENT" || exit 1
    sync "$UPERF_DIR" || exit 1
fi
if [ ! -e "$GLOBAL_MODE" ]; then
    printf 'balance\n' > "$GLOBAL_MODE" || exit 1
fi
global_mode="$(tr -d '\r\n ' < "$GLOBAL_MODE")"
if ! valid_preset "$global_mode"; then
    echo "legacy saved Uperf invalid; preserve for recovery" >&2
    exit 1
fi

if [ ! -e "$PERAPP" ]; then
    cp "$SYSTEM_PERAPP" "$PERAPP" || exit 1
fi
property_mode="${1:-}"
if valid_preset "$property_mode"; then
    effective_mode="$property_mode"
else
    effective_mode="$global_mode"
fi
printf '%s\n' "$effective_mode" > "$EFFECTIVE_MODE.tmp" || exit 1
chmod 0644 "$EFFECTIVE_MODE.tmp"
mv -f "$EFFECTIVE_MODE.tmp" "$EFFECTIVE_MODE" || exit 1

chmod 0644 "$EFFECTIVE_MODE"
restorecon_recursive "$DATA_ROOT" >/dev/null 2>&1 || true

seed_recovery=0
if [ -f "$SEED_INTENT" ] && [ "$global_mode" = balance ] && cmp -s "$PERAPP" "$SYSTEM_PERAPP"; then
    if [ "$(cat "$SEED_INTENT")" = "$(sha256sum "$SYSTEM_PERAPP")" ]; then seed_recovery=1; fi
fi
# Legacy inputs are used only before durable cutover. Canonical bootstrap below
# validates and projects the current mode after cutover, including screen-off.
    # Seed projections only with newly created factory stores. Existing Settings
    # must reach the strict migration comparison unchanged, including disagreements.
    if [ "$seed_recovery" = 1 ] && [ "$(settings --user 0 get system zui_control_uperf_mode)" = null ]; then
        settings --user 0 put system zui_control_uperf_mode "$global_mode" >/dev/null 2>&1 || exit 1
    fi
    rules_text="$(awk 'NF == 2 && $1 != "-" && $1 != "*" {if(length(out))out=out "\n";out=out $1 "|" $2} END {print out}' "$PERAPP")"
    if [ "$seed_recovery" = 1 ] && [ "$(settings --user 0 get system zui_control_uperf_rules_text)" = null ]; then
        settings --user 0 put system zui_control_uperf_rules_text "$rules_text" >/dev/null 2>&1 || exit 1
    fi
fi
CLASSPATH=/system/framework/services.jar /system/bin/app_process /system/bin com.zui.server.control.PolicyCommand bootstrap - || exit 1
CLASSPATH=/system/framework/services.jar /system/bin/app_process /system/bin com.zui.server.control.PolicyCommand uperf-startup - || exit 1
# uperf-startup durably publishes config + binary authorization using AtomicFile,
# file/directory fsync and exact readback, after successful policy bootstrap.
[ -f "$AUTH" ] && [ ! -L "$AUTH" ] || exit 1
rm -f "$UPERF_DIR/.service_ready_uptime" || exit 1
setprop zui_control.scheduler prepared || exit 1
CLASSPATH=/system/framework/services.jar /system/bin/app_process /system/bin com.zui.server.control.PolicyCommand uperf-observe-startup - || exit 1
trap - EXIT
# No legacy store, failed receipt or imported artifact is retired in this migration.
exit 0
