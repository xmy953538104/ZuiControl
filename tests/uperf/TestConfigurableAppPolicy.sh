#!/bin/sh
# Pure production parser fixtures; real PM/filesystem integration is the device mksh gate.
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
ZUI_CONTROLD_TEST_MODE=1
export ZUI_CONTROLD_TEST_MODE
. "$ROOT/payload/system/bin/zui_controld"
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
for path in /data/app/game/base.apk /data/app/~~abc==/game-abc==/split_config.arm64.apk /system/preinstall/Calculator/Calculator.apk; do
    uperf_apk_path_allowed "$path" || fail "valid path $path"
done
for path in '' /data/application/base.apk /system/app/base.apk /system/priv-app/base.apk /vendor/app/base.apk /data/app/../x.apk /data/app/./x.apk /data/app//x.apk '/data/app/space name.apk' /data/app/.apk /data/app/x.apk/extra; do
    if uperf_apk_path_allowed "$path"; then fail "invalid path $path"; fi
done
query='ActivityInfo:
  packageName=com.example.game
  enabled=true exported=true
  ApplicationInfo:
    packageName=com.example.game
    enabled=true minSdkVersion=21'
printf '%s\n' "$query" | uperf_launchable_query_allowed com.example.game || fail launchable
for mutation in disabled private mismatch missing duplicate empty; do
    case "$mutation" in
        disabled) value="$(printf '%s\n' "$query" | sed s/enabled=true/enabled=false/g)" ;;
        private) value="$(printf '%s\n' "$query" | sed s/exported=true/exported=false/)" ;;
        mismatch) value="$(printf '%s\n' "$query" | sed s/com.example.game/com.example.other/g)" ;;
        missing) value="$(printf '%s\n' "$query" | sed /exported=/d)" ;;
        duplicate) value="$query
$query" ;;
        empty) value='' ;;
    esac
    if printf '%s\n' "$value" | uperf_launchable_query_allowed com.example.game; then fail "$mutation"; fi
done
printf 'UPERF_CONFIGURABLE_APP_PARSER=PASS\n'

for path in /system/app/Home/base.apk /system/priv-app/ZuiControlV71/ZuiControl.apk; do
    if uperf_apk_path_allowed "$path" 0; then fail "unqualified system root"; fi
    uperf_apk_path_allowed "$path" 1 || fail "qualified system root"
done
for path in /vendor/app/core.apk /system/priv-app/../core.apk; do
    if uperf_apk_path_allowed "$path" 1; then fail "special identity cannot waive path $path"; fi
done
