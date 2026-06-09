# ZuiperfCtl

ZuiperfCtl is a system app and init daemon prototype for ZUI performance control.

It keeps the official ZuiPP and game helper packages installed, then adds:

- `com.zui.zuiperfctl`: privileged Android app UI. The Kotlin source package is still `com.zui.perfctl`, but the APK package name is new to avoid the old signature-mismatch state on flashed devices.
- `PerfCtlQuickService`: ongoing notification with direct 60/90/120/144Hz controls.
- `/system/bin/zui_perfctld`: root init daemon for XML generation/bind mounts and foreground refresh-rate learning.
- Embedded `AsoulOpt` service copied from the known working 187 payload.
- Runtime config under `/data/local/tmp/zui_perfctl`.

## Layout

- `app/`: Android app source.
- `payload/`: files to inject into `system_a/system`.
- `scripts/BuildZuiperfCtl.ps1`: local Windows build helper.
- `scripts/ApplyZuiperfCtlPayload.py`: copies payload into an unpacked image tree and updates metadata.
- `.github/workflows/build.yml`: GitHub Actions build and script checks.
- `docs/`: design and first-version implementation notes.

## CI

Run the `Build ZuiperfCtl` workflow manually. It will:

1. Check Python and shell scripts.
2. Decode the release keystore from GitHub Actions secrets.
3. Build signed `app-release.apk`.
4. Stage it into `payload/system/priv-app/ZuiperfCtl/ZuiperfCtl.apk`.
5. Upload both the APK and staged payload as artifacts.

Required repository secrets:

- `KEYSTORE`: base64 encoded keystore.
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

## Payload Usage

After the APK exists in `payload/system/priv-app/ZuiperfCtl/ZuiperfCtl.apk`, apply the payload to an unpacked image tree:

```bash
python scripts/ApplyZuiperfCtlPayload.py --unpack /path/to/work/unpack
```

Runtime logs on device:

- `/data/local/tmp/zui_perfctl/log/perfctld.log`
- `/data/local/tmp/zui_perfctl/log/asoulopt.log`

## Notes

The app sends each command as one atomic `Settings.System` payload in
`zui_perfctl_request_text`. Refresh-rate exceptions are learned when a rate is
selected from the notification while an app is in the foreground. The global
baseline is a hard 120Hz lock, and selecting 120Hz removes that app's exception.

Performance profiles are independent from refresh-rate rules and are persisted
under `/data/local/tmp/zui_perfctl/performance/profiles.prop`. Each profile
contains a ZuiPP mode plus CPU/GPU upper and lower limits. The APK's
`XmlProfileGenerator` converts these profiles into valid `game_policy.xml` and
`performanceconfig.xml` work files before the daemon applies them.

AsoulOpt is not configured per app. It remains an independent system service;
the app reports its service/domain health and exports its log.

XML bind mounts are requested by the daemon through `zui_perfctl.*` properties and executed by Android init. `scripts/ApplyZuiperfCtlPayload.py` adds the matching property context and the minimal init `mounton` SELinux rule, then updates the platform sepolicy hash so the device recompiles split sepolicy at boot.
