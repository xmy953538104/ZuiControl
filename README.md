# ZuiControl

ZuiControl is the v19 system-server refresh-rate and performance control plane.

The app is now a privileged UI/QS/config client. Foreground scene detection and
refresh-rate policy live in `system_server` through the `zui_control` Binder
service. The daemon remains for XML generation, bind mounts, controlled ZuiPP
reload after XML hash verification, SafeCenter one-time keepalive, logs, and
AsoulOpt orchestration.

## Runtime Split

- `com.zui.zuicontrol`: privileged Android app UI and quick notification.
- `android.zui.ZuiControlManager`: thin framework client used by the app.
- `zui_control`: Binder service published from `system_server`.
- `ZuiControlService`: service-side display-mode policy owner.
- `/system/bin/zui_controld`: root daemon for XML/ZuiPP reload/asoul/log work only.
- `/data/system/zui_control/profiles.prop`: refresh scene profiles.
- `/data/vendor/zui_control/`: daemon runtime state, XML work files, and logs.

Refresh-rate ownership is intentionally not shared: notification clicks call
`zui_control`; the daemon does not learn refresh rules, restore 120Hz, or write
`peak_refresh_rate` / `min_refresh_rate`.

## Layout

- `app/`: Android app source.
- `framework-stubs/`: compile-only `android.zui` API for the app build.
- `framework_patch/`: Java sources and stubs injected into framework/services jars.
- `payload/`: files to inject into `system_a/system`.
- `scripts/BuildZuiControl.ps1`: local Windows build helper.
- `scripts/ApplyZuiControlPayload.py`: copies payload, patches SELinux metadata,
  and invokes the framework/services jar patcher.
- `scripts/PatchZuiControlFramework.py`: injects `android.zui.ZuiControlManager`,
  `ZuiControlService`, and the WM focus hook.

## CI

Run the `Build ZuiControl` workflow manually. It will:

1. Check Python and shell scripts.
2. Decode the release keystore from GitHub Actions secrets.
3. Build signed `app-release.apk`.
4. Stage it into `payload/system/priv-app/ZuiControl/ZuiControl.apk`.
5. Upload both the APK and staged payload as artifacts.

Required repository secrets:

- `KEYSTORE`: base64 encoded keystore.
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

## Payload Usage

After the APK exists in `payload/system/priv-app/ZuiControl/ZuiControl.apk`,
apply the payload to an unpacked image tree:

```bash
python scripts/ApplyZuiControlPayload.py --unpack /path/to/work/unpack
```

Runtime logs on device:

- `/data/vendor/zui_control/log/zuicontrold.log`
- `/data/vendor/zui_control/log/asoulopt.log`

## Validation Anchors

- `service list | grep zui_control`
- `dumpsys activity service zui_control` or `dumpsys zui_control` if available
- `settings get system zui_control_top_package`
- `settings get system zui_control_active_refresh`
- `dumpsys display` active mode versus the selected scene profile
- `logcat -b all | grep -i ZuiControl`

The expected steady state is `refreshOwner=system_server` and
`daemonRefreshDisabled=true`.
