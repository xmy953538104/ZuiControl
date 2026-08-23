#!/system/bin/sh

DATA_ROOT=/data/vendor/zui_control
UPERF_DIR=$DATA_ROOT/uperf
ASOUL_DIR=$DATA_ROOT/asoul
SYSTEM_UPERF=/system/etc/zui_control/uperf-sm8650.json
SYSTEM_PERAPP=/system/etc/zui_control/default_uperf_perapp.txt
SYSTEM_ASOUL=/system/etc/zui_control/default_asopt.conf

mkdir -p "$UPERF_DIR" "$ASOUL_DIR" /data/adb/naki || exit 1

cp "$SYSTEM_UPERF" "$UPERF_DIR/uperf.json.tmp" || exit 1
chown root:root "$UPERF_DIR/uperf.json.tmp"
chmod 0644 "$UPERF_DIR/uperf.json.tmp"
mv -f "$UPERF_DIR/uperf.json.tmp" "$UPERF_DIR/uperf.json" || exit 1

if [ ! -s "$UPERF_DIR/perapp_powermode.txt" ]; then
    cp "$SYSTEM_PERAPP" "$UPERF_DIR/perapp_powermode.txt" || exit 1
fi
if [ ! -s "$UPERF_DIR/cur_powermode.txt" ]; then
    printf 'balance\n' > "$UPERF_DIR/cur_powermode.txt" || exit 1
fi

cp "$SYSTEM_ASOUL" "$ASOUL_DIR/asopt.conf.tmp" || exit 1
chown root:root "$ASOUL_DIR/asopt.conf.tmp"
chmod 0644 "$ASOUL_DIR/asopt.conf.tmp"
mv -f "$ASOUL_DIR/asopt.conf.tmp" "$ASOUL_DIR/asopt.conf" || exit 1

touch /data/adb/naki/asopt.conf
chown root:root "$UPERF_DIR/perapp_powermode.txt" \
    "$UPERF_DIR/cur_powermode.txt" /data/adb/naki/asopt.conf
chmod 0644 "$UPERF_DIR/perapp_powermode.txt" \
    "$UPERF_DIR/cur_powermode.txt" /data/adb/naki/asopt.conf

restorecon_recursive "$DATA_ROOT" >/dev/null 2>&1 || true
restorecon_recursive /data/adb/naki >/dev/null 2>&1 || true

# AppOpt and the old ZuiPP XML owner must not run beside the new scheduler.
setprop zui_control.appopt stop
setprop zui_control.zuipp restore
exit 0
