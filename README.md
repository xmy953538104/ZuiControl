# ZuiperfCtl

ZuiperfCtl is a first-version system app and init daemon prototype for ZUI performance control.

It keeps the official ZuiPP and game helper packages installed, then adds:

- `com.zui.perfctl`: privileged Android app UI.
- `/system/bin/zui_perfctld`: root init daemon for XML bind mounts, refresh-rate commands, and AsoulOpt control.
- Embedded `AsoulOpt` service copied from the known working 187 payload.
- Runtime config under `/data/zui_perfctl`.

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
2. Build `app-debug.apk`.
3. Stage it into `payload/system/priv-app/ZuiperfCtl/ZuiperfCtl.apk`.
4. Upload both the APK and staged payload as artifacts.

## Payload Usage

After the APK exists in `payload/system/priv-app/ZuiperfCtl/ZuiperfCtl.apk`, apply the payload to an unpacked image tree:

```bash
python scripts/ApplyZuiperfCtlPayload.py --unpack /path/to/work/unpack
```

Runtime logs on device:

- `/data/zui_perfctl/log/perfctld.log`
- `/data/zui_perfctl/log/asoulopt.log`

## Notes

This is still a v1 prototype. ZuiPP XML changes are applied through bind mount, and ZuiPP/GameHelper processes are restarted so they reload cached XML. Automatic refresh switching is disabled by default until rules are added to `/data/zui_perfctl/refresh/rules.prop`.
