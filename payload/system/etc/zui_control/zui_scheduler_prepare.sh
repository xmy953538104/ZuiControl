#!/system/bin/sh

DATA_ROOT=/data/vendor/zui_control
UPERF_DIR=$DATA_ROOT/uperf
ASOUL_DIR=$DATA_ROOT/asoul
SYSTEM_UPERF=/system/etc/zui_control/uperf-sm8650.json
SYSTEM_PERAPP=/system/etc/zui_control/default_uperf_perapp.txt
SYSTEM_ASOUL=/system/etc/zui_control/default_asopt.conf
AUTO_MODE_MIGRATION=$UPERF_DIR/.auto_mode_v46
ROM_FRONTEND_MIGRATION=$UPERF_DIR/.rom_frontend_v47
REQUESTED_MODE=$UPERF_DIR/cur_powermode.txt
EFFECTIVE_MODE=$UPERF_DIR/effective_powermode.txt

mkdir -p "$UPERF_DIR" "$ASOUL_DIR" || exit 1

cp "$SYSTEM_UPERF" "$UPERF_DIR/uperf.json.tmp" || exit 1
chmod 0644 "$UPERF_DIR/uperf.json.tmp"
mv -f "$UPERF_DIR/uperf.json.tmp" "$UPERF_DIR/uperf.json" || exit 1

if [ ! -s "$UPERF_DIR/perapp_powermode.txt" ]; then
    cp "$SYSTEM_PERAPP" "$UPERF_DIR/perapp_powermode.txt" || exit 1
fi
if [ ! -s "$REQUESTED_MODE" ]; then
    printf 'auto\n' > "$REQUESTED_MODE" || exit 1
elif [ ! -e "$AUTO_MODE_MIGRATION" ] &&
    [ "$(tr -d '\r\n ' < "$REQUESTED_MODE")" = "balance" ]; then
    # V45 incorrectly used a fixed balance mode, which disabled per-app rules.
    printf 'auto\n' > "$REQUESTED_MODE" || exit 1
fi
if [ ! -e "$AUTO_MODE_MIGRATION" ]; then
    : > "$AUTO_MODE_MIGRATION" || exit 1
fi

requested_mode="$(tr -d '\r\n ' < "$REQUESTED_MODE")"
case "$requested_mode" in
    auto|powersave|balance|performance|fast) ;;
    *)
        requested_mode=auto
        printf 'auto\n' > "$REQUESTED_MODE" || exit 1
        ;;
esac

if [ ! -e "$ROM_FRONTEND_MIGRATION" ]; then
    awk '
        $1 == "*" {
            if (!written) print "* balance"
            written=1
            next
        }
        { print }
        END { if (!written) print "* balance" }
    ' "$UPERF_DIR/perapp_powermode.txt" > "$UPERF_DIR/perapp_powermode.txt.tmp" || exit 1
    mv -f "$UPERF_DIR/perapp_powermode.txt.tmp" "$UPERF_DIR/perapp_powermode.txt" || exit 1
    : > "$ROM_FRONTEND_MIGRATION" || exit 1
fi

effective_mode="$requested_mode"
[ "$effective_mode" != "auto" ] || effective_mode=balance
printf '%s\n' "$effective_mode" > "$EFFECTIVE_MODE.tmp" || exit 1
chmod 0644 "$EFFECTIVE_MODE.tmp"
mv -f "$EFFECTIVE_MODE.tmp" "$EFFECTIVE_MODE" || exit 1

cp "$SYSTEM_ASOUL" "$ASOUL_DIR/asopt.conf.tmp" || exit 1
chmod 0644 "$ASOUL_DIR/asopt.conf.tmp"
mv -f "$ASOUL_DIR/asopt.conf.tmp" "$ASOUL_DIR/asopt.conf" || exit 1

chmod 0644 "$UPERF_DIR/perapp_powermode.txt" \
    "$REQUESTED_MODE" "$EFFECTIVE_MODE" "$AUTO_MODE_MIGRATION" \
    "$ROM_FRONTEND_MIGRATION"

restorecon_recursive "$DATA_ROOT" >/dev/null 2>&1 || true

# AppOpt and the old ZuiPP XML owner must not run beside the new scheduler.
setprop zui_control.appopt stop
setprop zui_control.zuipp restore
exit 0
