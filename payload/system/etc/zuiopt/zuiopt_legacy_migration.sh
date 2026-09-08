#!/system/bin/sh
# MIGRATION_ONLY: these retired path literals cannot start an optimizer.
# Runs before boot completion in shell, never grants ZUIopt legacy-data access.
[ "$(id -u)" = 0 ] || exit 126
unexpected() { echo LEGACY_ASOUL_PATH_UNEXPECTED >&2; return 1; }
legacy_link=/data/vendor/asopt.conf
legacy_dir=/data/vendor/zui_control/asoul
legacy_target=/data/vendor/zui_control/asoul/asopt.conf
legacy_sha=69a73f9bedb3a5f3e07d8f74d3ab9d18f8ab97ff48e02c74a378333fa3b1b75e
case "${1:-}" in prepare|finish) ;; *) exit 2 ;; esac
case "$(/system/bin/ZUIopt --migration-state)" in DONE) exit 0 ;; PENDING) ;; *) exit 1 ;; esac
if [ "$1" = finish ]; then
    case "$(getprop sys.zui_control.legacy_migration)" in CLEAN|UNLINK_VERIFIED) ;; *) exit 1 ;; esac
    [ ! -e "$legacy_link" ] && [ ! -L "$legacy_link" ] &&
        [ ! -e "$legacy_dir" ] && [ ! -L "$legacy_dir" ] || { unexpected; exit 1; }
    /system/bin/ZUIopt --migration-done
    exit $?
fi
setprop sys.zui_control.legacy_migration FAILED || exit 1

# Exact 072 ancestors: Android system owns /data, root owns the vendor boundary.
for ancestor in /data /data/vendor /data/vendor/zui_control; do
    [ -d "$ancestor" ] && [ ! -L "$ancestor" ] || { unexpected; exit 1; }
done
[ "$(stat -c %u:%g:%a /data)" = 1000:1000:771 ] &&
    [ "$(stat -c %u:%g:%a /data/vendor)" = 0:0:771 ] &&
    [ "$(stat -c %u:%g:%a /data/vendor/zui_control)" = 0:0:755 ] || { unexpected; exit 1; }
if [ -e "$legacy_link" ] || [ -L "$legacy_link" ]; then
    [ -L "$legacy_link" ] && [ "$(readlink "$legacy_link")" = "$legacy_target" ] &&
        [ "$(stat -c %u:%g "$legacy_link")" = 0:0 ] || { unexpected; exit 1; }
fi

if [ -e "$legacy_dir" ] || [ -L "$legacy_dir" ]; then
    [ -d "$legacy_dir" ] && [ ! -L "$legacy_dir" ] || { unexpected; exit 1; }
    case "$(stat -c %u:%g:%a "$legacy_dir")" in
        0:2000:775) original_mode=0775 ;;
        0:2000:700) original_mode=0700 ;; # Resume an interrupted frozen migration.
        *) unexpected; exit 1 ;;
    esac
    # Freeze the historically shell-writable directory before inspecting entries.
    # On validation failure restore its original mode; no contents were removed.
    chmod 0700 "$legacy_dir" || exit 1
    valid=1
    for entry in "$legacy_dir"/* "$legacy_dir"/.[!.]* "$legacy_dir"/..?*; do
        [ -e "$entry" ] || [ -L "$entry" ] || continue
        case "${entry##*/}" in asopt.conf|asopt.conf.tmp) ;; *) valid=0; break ;; esac
        [ -f "$entry" ] && [ ! -L "$entry" ] &&
            [ "$(stat -c %u:%g:%a:%h:%s "$entry")" = 0:2000:644:1:103 ] || { valid=0; break; }
        sum="$(sha256sum "$entry")" || { valid=0; break; }
        [ "${sum%% *}" = "$legacy_sha" ] || { valid=0; break; }
    done
    if [ "$valid" != 1 ]; then chmod "$original_mode" "$legacy_dir"; unexpected; exit 1; fi
    # Names and bytes have all been validated. Only these individual files.
    for entry in "$legacy_dir/asopt.conf" "$legacy_dir/asopt.conf.tmp"; do
        [ ! -e "$entry" ] || rm "$entry" || exit 1
    done
    rmdir "$legacy_dir" || exit 1
fi
if [ -L "$legacy_link" ]; then
    [ "$(readlink "$legacy_link")" = "$legacy_target" ] || { unexpected; exit 1; }
    # init already has vendor-directory removal permission; shell does not.
    setprop sys.zui_control.legacy_migration UNLINK_VERIFIED
else
    setprop sys.zui_control.legacy_migration CLEAN
fi
