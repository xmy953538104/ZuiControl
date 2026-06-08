# ZuiperfCtl

ZuiperfCtl is a system app and init daemon prototype for ZUI performance control.

It keeps the official ZuiPP and game helper packages installed, then adds:

- `com.zui.perfctl`: privileged Android app UI.
- `/system/bin/zui_perfctld`: root init daemon for XML bind mounts, refresh-rate commands, and AsoulOpt control.
- Embedded `AsoulOpt` service copied from the known working 187 payload.
- Runtime config under `/data/local/tmp/zui_perfctl` for the current shell-domain prototype.

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

This is still a prototype. The app sends commands through `Settings.System` keys (`zui_perfctl_request_id`, `zui_perfctl_cmd`, `zui_perfctl_rate`, `zui_perfctl_package`) so the shell-domain daemon does not need to read app-private data. Automatic refresh switching is disabled by default until rules are added from the app or to `/data/local/tmp/zui_perfctl/refresh/rules.prop`.
