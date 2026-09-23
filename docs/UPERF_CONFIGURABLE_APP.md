# R11 narrow own-App and HOME qualification

The existing canonical /data/app and /system/preinstall policy remains. The actual PackageManager default HOME and com.zui.zuicontrol additionally qualify for canonical /system/app or /system/priv-app APK paths, only with enabled/exported user-facing activity. HOME uses the resolved HOME activity; all other apps retain MAIN/LAUNCHER qualification. SystemUI and android remain rejected. UI, notification and real Android daemon parser agree; no blanket system-app permission.

Quick Uperf writes the existing per-app store, never global mode. App delete returns to global inheritance. This CPU eligibility change does not qualify own-App/HOME for GPU overrides.

## Earlier policy context

# Uperf configurable application

The picker, quick notification and authenticated daemon use this same contract.
It changes per-app admission only; global Uperf and foreground authority are unchanged.

- A dotted PackageNames-valid package, excluding android, com.android.systemui
  and com.zui.zuicontrol.
- An enabled, exported MAIN/LAUNCHER activity belonging to that enabled package,
  resolved by PackageManager for the active Android user.
- At least one existing APK; every base/split APK must have its unchanged
  canonical full path under exactly /data/app/ or /system/preinstall/.
- Paths contain only ASCII letters/digits and _ . / + = ~ -; end with .apk;
  no repeated slash, dot/traversal segment, empty result, extra output or mixed
  allowed/disallowed split paths. Symlink aliases are rejected.
- /system/app/, /system/priv-app/, /vendor/ and other roots remain rejected.
  Existence or a syntactically valid package name alone is insufficient.

UperfAppPolicy is shared by App and notification, including the action-time
guard. The shell implementation is bound to it by exact host fixture/contract
tests. Package queries are bounded to five seconds each and run only during an
explicit per-app command, never a sampler. The production shell parser must also
pass the real Android mksh gate before a ROM build.
