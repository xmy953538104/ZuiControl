# ZuiControl

ZuiControl is an Android system-integration project for the TB321FU ZUI
16.1.11.072 ROM. It provides a privileged control app, `system_server`
integration, an init-managed command/runtime layer, and the payload needed to
assemble those pieces into an unpacked ROM image.

## Source layout

- `app/`: privileged Android application and JVM unit tests.
- `framework-stubs/`: compile-time Android framework API stubs.
- `framework_patch/`: framework and services source injected during ROM build.
- `native/`: native Uperf process supervisor source.
- `payload/`: files, policies, init services, binaries, and configuration copied
  into the system image.
- `upstream/uperf/`: pinned upstream inputs required to audit Uperf updates.
- `scripts/build/`: source transformation and final-artifact verification tools.
- `tests/`: version-neutral host regression contracts and minimal fixtures.

## Build

JDK 17, Android SDK 35, and Gradle 9.3.1 are expected.

```powershell
gradle -p . :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
powershell -NoProfile -File scripts/build/BuildZuiControl.ps1 -Configuration Debug
```

Release signing values are supplied through the parameters documented by
`scripts/build/BuildZuiControl.ps1`; signing material is never stored here.

## Host regression tests

Run from the repository root:

```text
python tests/command_plane/TestCommandPlaneArchitecture.py
bash tests/command_plane/TestZuiControldTransactions.sh
python tests/refresh/TestRefreshStateMachine.py
python tests/uperf/architecture/TestUperfArchitecture.py
python tests/uperf/startup/TestUperfStartupPolicy.py
python tests/uperf/startup/TestUperfStartupBoundaries.py
python tests/uperf/supervisor/TestUperfSupervisor.py
python tests/uperf/top_resumed/TestUperfTopResumedStateMachine.py
python tests/cache/TestVerifiedContentCache.py
```

Stable host artifacts may be reused only through the verified content cache. Its
key binds the operation version, source SHA-256, tool SHA-256/version, and
relevant options; every hit re-verifies output byte hashes:

```text
python scripts/build/VerifiedContentCache.py lpunpack --source-super <super.img> --lpunpack-tool <lpunpack.py> --python <python> --cache-root <cache> --partition system_a
python scripts/build/VerifiedContentCache.py ci-artifact --run-id <run> --artifact-id <artifact> --digest sha256:<digest> --source-archive <artifact.zip> --cache-root <cache>
```

The same canonical paths are enforced by `.github/workflows/build.yml`.

## ROM integration

Apply the payload and framework patch to an explicit unpacked image tree:

```powershell
python scripts/build/ApplyZuiControlPayload.py --root . --unpack <unpacked-image-root> --dry-run
python scripts/build/ApplyZuiControlPayload.py --root . --unpack <unpacked-image-root>
```

Build outputs, ROM images, device evidence, review packages, and local project
history do not belong in this repository. Runtime ownership and payload details
are summarized in `payload/README.txt`.
