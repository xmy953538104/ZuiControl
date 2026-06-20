#!/system/bin/sh

# System APK timestamps are normalized in the packed image. Drop only the
# ZuiControl parser cache so PackageManager reparses the current manifest.
for file in /data/system/package_cache/*/ZuiControl-*; do
    [ -e "$file" ] || continue
    rm -f "$file" 2>/dev/null || true
done
