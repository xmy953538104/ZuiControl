#!/system/bin/sh

DATA_ROOT=/data/vendor/zui_control
UPERF_DIR=$DATA_ROOT/uperf
ASOUL_DIR=$DATA_ROOT/asoul
SYSTEM_UPERF=/system/etc/zui_control/uperf-sm8650.json
SYSTEM_PERAPP=/system/etc/zui_control/default_uperf_perapp.txt
SYSTEM_ASOUL=/system/etc/zui_control/default_asopt.conf
AUTO_MODE_MIGRATION=$UPERF_DIR/.auto_mode_v46

mkdir -p "$UPERF_DIR" "$ASOUL_DIR" || exit 1

cp "$SYSTEM_UPERF" "$UPERF_DIR/uperf.json.tmp" || exit 1
chmod 0644 "$UPERF_DIR/uperf.json.tmp"
mv -f "$UPERF_DIR/uperf.json.tmp" "$UPERF_DIR/uperf.json" || exit 1

if [ ! -s "$UPERF_DIR/perapp_powermode.txt" ]; then
    cp "$SYSTEM_PERAPP" "$UPERF_DIR/perapp_powermode.txt" || exit 1
fi
if [ ! -s "$UPERF_DIR/cur_powermode.txt" ]; then
    printf 'auto\n' > "$UPERF_DIR/cur_powermode.txt" || exit 1
elif [ ! -e "$AUTO_MODE_MIGRATION" ] &&
    [ "$(tr -d '\r\n ' < "$UPERF_DIR/cur_powermode.txt")" = "balance" ]; then
    # V45 incorrectly used a fixed balance mode, which disabled per-app rules.
    printf 'auto\n' > "$UPERF_DIR/cur_powermode.txt" || exit 1
fi
if [ ! -e "$AUTO_MODE_MIGRATION" ]; then
    : > "$AUTO_MODE_MIGRATION" || exit 1
fi

cp "$SYSTEM_ASOUL" "$ASOUL_DIR/asopt.conf.tmp" || exit 1
chmod 0644 "$ASOUL_DIR/asopt.conf.tmp"
mv -f "$ASOUL_DIR/asopt.conf.tmp" "$ASOUL_DIR/asopt.conf" || exit 1

chmod 0644 "$UPERF_DIR/perapp_powermode.txt" \
    "$UPERF_DIR/cur_powermode.txt" "$AUTO_MODE_MIGRATION"

restorecon_recursive "$DATA_ROOT" >/dev/null 2>&1 || true

# AppOpt and the old ZuiPP XML owner must not run beside the new scheduler.
setprop zui_control.appopt stop
setprop zui_control.zuipp restore
exit 0
