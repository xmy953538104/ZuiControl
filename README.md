# ZuiControl

## Current Handoff

- `docs/ZuiControl_AI_NEW_CHAT_HANDOFF_2026-07-21.md`: current project state, package identity, boundaries, and post-flash verification order.
- `docs/ZuiControl_P2_XML_THERMALCONFIG_GUIDE_2026-07-21.md`: retained P2 XML/ThermalConfig reference; P2 is not the current scheduler owner.

ZuiControl is the v19 system-server refresh-rate and performance control plane.

The app is now a privileged UI/QS/config client. Foreground scene detection and
refresh-rate policy live in `system_server` through the `zui_control` Binder
service. Performance ownership is now split between the ROM-integrated Uperf
core and Shiroko A-SOUL thread placement. P2 XML is restored to the stock ZUI
files and AppOpt is removed from the production payload.

## Runtime Split

- `com.zui.zuicontrol`: privileged Android app UI and quick notification.
- `android.zui.ZuiControlManager`: thin framework client used by the app.
- `zui_control`: Binder service published from `system_server`.
- `ZuiControlService`: service-side display-mode policy owner.
- `/system/bin/zui_controld`: scheduler control plane, persistent Uperf profiles, health checks, and logs.
- `/system/bin/uperf`: CPU power-model scheduler driven by the ROM scene frontend.
- `/system/bin/AsoulOpt`: thread placement with the verified `mode=0`, `rt=0` policy.
- `/data/system/zui_control/profiles.prop`: refresh scene profiles.
- `/data/vendor/zui_control/`: daemon runtime state, XML work files, and logs.

The Uperf UI supports a global fallback mode plus validated per-app overrides.
The production defaults use global `balance`, screen-off `powersave`, and
`performance` for both Mingchao package names. A-SOUL owns game-thread affinity;
Uperf's own thread scheduler remains disabled to prevent double placement.

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

1. Run the daemon/Uperf ownership tests and script syntax checks.
2. Run Android unit tests and lint.
3. Decode the release keystore from GitHub Actions secrets.
4. Build signed `app-release.apk`.
5. Stage it into `payload/system/priv-app/ZuiControlV45/ZuiControl.apk`.
6. Upload both the APK and staged payload as artifacts.

Required repository secrets:

- `KEYSTORE`: base64 encoded keystore.
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

## Payload Usage

After the APK exists in `payload/system/priv-app/ZuiControlV45/ZuiControl.apk`,
apply the payload to an unpacked image tree:

```bash
python scripts/ApplyZuiControlPayload.py --unpack /path/to/work/unpack
```

Runtime logs on device:

- `/data/vendor/zui_control/log/controld.log`
- `/data/vendor/zui_control/log/uperf.log`

## Validation Anchors

- `service list | grep zui_control`
- `dumpsys activity service zui_control` or `dumpsys zui_control` if available
- `settings get system zui_control_top_package`
- `settings get system zui_control_active_refresh`
- `dumpsys display` active mode versus the selected scene profile
- `logcat -b all | grep -i ZuiControl`
- `ps -AZ | grep -E 'uperf|AsoulOpt'`
- `settings get system zui_control_uperf_health`

The expected steady state is `refreshOwner=system`,
`daemonRefreshDisabled=true`, Uperf/A-SOUL in `performanced`, and
`zui_control_xml_state=state=retired;owner=uperf`.
