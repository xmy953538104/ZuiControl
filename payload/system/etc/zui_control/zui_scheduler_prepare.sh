#!/system/bin/sh

DATA_ROOT=/data/vendor/zui_control
UPERF_DIR=$DATA_ROOT/uperf
ASOUL_DIR=$DATA_ROOT/asoul
SYSTEM_UPERF=/system/etc/zui_control/uperf-sm8650.json
SYSTEM_PERAPP=/system/etc/zui_control/default_uperf_perapp.txt
SYSTEM_ASOUL=/system/etc/zui_control/default_asopt.conf
GLOBAL_MODE=$UPERF_DIR/cur_powermode.txt
EFFECTIVE_MODE=$UPERF_DIR/effective_powermode.txt
PERAPP=$UPERF_DIR/perapp_powermode.txt
ASOUL_CONFIG=$ASOUL_DIR/asopt.conf

valid_preset() {
    case "$1" in
        powersave|balance|performance|fast) return 0 ;;
        *) return 1 ;;
    esac
}

mkdir -p "$UPERF_DIR" "$ASOUL_DIR" || exit 1
cp "$SYSTEM_UPERF" "$UPERF_DIR/uperf.json.tmp" || exit 1
chmod 0644 "$UPERF_DIR/uperf.json.tmp"
mv -f "$UPERF_DIR/uperf.json.tmp" "$UPERF_DIR/uperf.json" || exit 1

if [ ! -s "$GLOBAL_MODE" ]; then
    printf 'balance\n' > "$GLOBAL_MODE" || exit 1
fi
global_mode="$(tr -d '\r\n ' < "$GLOBAL_MODE")"
if ! valid_preset "$global_mode"; then
    global_mode=balance
    printf 'balance\n' > "$GLOBAL_MODE" || exit 1
fi

if [ ! -s "$PERAPP" ]; then
    cp "$SYSTEM_PERAPP" "$PERAPP" || exit 1
fi
awk '
    function valid(mode) {
        return mode == "powersave" || mode == "balance" ||
            mode == "performance" || mode == "fast"
    }
    NF == 2 && $1 == "-" && valid($2) {
        if (!screen) print "- " $2
        screen=1
        next
    }
    NF == 2 && $1 != "*" && $1 ~ /^[A-Za-z0-9_.]+$/ && valid($2) {
        print $1 " " $2
    }
    END { if (!screen) print "- powersave" }
' "$PERAPP" > "$PERAPP.tmp" || exit 1
mv -f "$PERAPP.tmp" "$PERAPP" || exit 1

printf '%s\n' "$global_mode" > "$EFFECTIVE_MODE.tmp" || exit 1
chmod 0644 "$EFFECTIVE_MODE.tmp"
mv -f "$EFFECTIVE_MODE.tmp" "$EFFECTIVE_MODE" || exit 1

if ! awk '
    /^[[:space:]]*($|#)/ {next}
    /^mode=[0-9]+$/ {mode++; next}
    /^rt=[01]$/ {rt++; next}
    /^opt=0x[0-9A-Fa-f]+$/ {opt++; next}
    {bad=1}
    END {exit !(bad == 0 && mode == 1 && rt == 1 && opt == 1)}
' "$ASOUL_CONFIG" 2>/dev/null; then
    cp "$SYSTEM_ASOUL" "$ASOUL_CONFIG.tmp" || exit 1
    chmod 0644 "$ASOUL_CONFIG.tmp"
    mv -f "$ASOUL_CONFIG.tmp" "$ASOUL_CONFIG" || exit 1
fi

chmod 0644 "$UPERF_DIR/uperf.json" "$GLOBAL_MODE" "$EFFECTIVE_MODE" \
    "$PERAPP" "$ASOUL_CONFIG"
restorecon_recursive "$DATA_ROOT" >/dev/null 2>&1 || true
restorecon /data/vendor/asopt.conf >/dev/null 2>&1 || true

# One-release migration cleanup: no retired scheduler remains active or visible.
rm -rf "$DATA_ROOT/appopt" "$DATA_ROOT/zuipp" \
    "$DATA_ROOT/performance" "$DATA_ROOT/refresh"
for key in zui_control_performance_profiles_text zui_control_performance_summary \
    zui_control_zuipp_reload_state zui_control_appopt_rules_text zui_control_xml_state; do
    settings delete system "$key" >/dev/null 2>&1 || true
done
exit 0
