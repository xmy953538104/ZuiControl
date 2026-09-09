# ZuiControl

ZuiControl is an Android system-integration project for the TB321FU ZUI
16.1.11.072 ROM. It provides a privileged control app, `system_server`
integration, an init-managed command/runtime layer, and the payload needed to
assemble those pieces into an unpacked ROM image.

## Source layout

- `app/`: privileged Android application and JVM unit tests.
- `framework-stubs/`: compile-time Android framework API stubs.
- `framework_patch/`: framework and services source injected during ROM build.
- `native/`: native Uperf supervisor and ZUIopt task owner/rule manager.
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
python tests/zuiopt/test_rule_pack.py
python tests/zuiopt/TestProductionContracts.py
python tests/zuiopt/TestCanonicalDocs.py
python tests/zuiopt/TestTerminalContracts.py
python tests/zuiopt/TestPuritySource.py
python tests/zuiopt/TestSceneAuthority.py
python tests/zuiopt/TestSchedulerHealth.py
sudo python host_retirement/TestRetirement.py
clang++ -std=c++17 -O1 -Wall -Wextra -Werror tests/zuiopt/ZUIoptTest.cpp -lz -o /tmp/zuiopt-fixture
sudo /tmp/zuiopt-fixture payload/system/etc/zuiopt/factory_rules.conf
python tests/zuiopt/TestNativeRuleParity.py /tmp/zuiopt-fixture
clang++ -std=c++17 -O1 -Wall -Wextra -Werror -I tests/zuiopt/fixtures/binder_ndk tests/zuiopt/ZUIoptBinderTest.cpp -lz -o /tmp/zuiopt-binder-fixture
/tmp/zuiopt-binder-fixture
clang++ -std=c++17 -O1 -Wall -Wextra -Werror -pthread -I tests/zuiopt/fixtures/binder_ndk tests/zuiopt/ZUIoptSceneTest.cpp -lz -ldl -Wl,--export-dynamic -o /tmp/zuiopt-scene-fixture
sudo /tmp/zuiopt-scene-fixture
```

Stable host artifacts may be reused only through the verified content cache. Its
key binds the operation version, source SHA-256, tool SHA-256/version, and
relevant options; every hit re-verifies output byte hashes:

```text
python scripts/build/VerifiedContentCache.py lpunpack --source-super <super.img> --lpunpack-tool <lpunpack.py> --python <python> --cache-root <cache> --partition system_a
python scripts/build/VerifiedContentCache.py ci-artifact --run-id <run> --artifact-id <artifact> --digest sha256:<digest> --source-archive <artifact.zip> --cache-root <cache>
```

The same canonical paths are enforced by `.github/workflows/build.yml`.
The native journal fixture requires root and creates/removes only its own
exclusive temporary directory; it never starts an owner or changes cpusets.
The Binder fixture executes the production reply parser with typed mock Parcel
calls; it is not proof of device wire bytes or SELinux permissions.

## ROM integration

The terminal candidate rebuilds the services extension from exact current source.
The reviewed Golden framework.jar and unmodified services DEX members remain
byte-identical. A source-bound terminal manifest is required for payload application:

```powershell
python scripts/build/ApplyZuiControlPayload.py --root . --unpack <unpacked-image-root> --payload <ci-payload> --original-base-manifest <approved-original-manifest.json> --terminal-framework-manifest <terminal-jars.json> --dry-run
python scripts/build/ApplyZuiControlPayload.py --root . --unpack <unpacked-image-root> --payload <ci-payload> --original-base-manifest <approved-original-manifest.json> --terminal-framework-manifest <terminal-jars.json>
```

Build outputs, ROM images, device evidence, review packages, and local project
history do not belong in this repository. Runtime ownership and payload details
are summarized in `payload/README.txt`.

ZUIopt is the sole per-task/per-thread owner and starts automatically while the
scheduler is active and no persistent failure exists. Rule imports use the existing authenticated command plane;
new packs are disabled. `/data/vendor/zui_control/zuiopt/effective.conf` atomically
selects a private generation containing packs, user rules and last-good state.
The manager keeps two generations; validation/pre-commit failures preserve the
current generation. After a commit/ACK I/O uncertainty, refresh the reported state
instead of assuming rollback or automatically repeating the mutation.
`owner_state.v1` remains exclusively the accepted crash-recovery journal.
Three crashes in 60 seconds stop ZUIopt and recover Android default scheduling.
The failure persists across boots. The authenticated App reset clears only failure
and crash history, enabling a retry at the next reboot, never the current boot.
Device upgrade preparation and rollback are separate host-only utilities.
The ROM performs no predecessor migration. Device runtime/AVC validation
requires a separately authorized post-flash gate.

Init creates the root-owned 0755 `/dev/cpuset/ZUIopt` scaffold at post-fs-data.
ZUIopt fails closed if it is absent/unsafe, recovers the existing journal before
initialization, and removes only its mask children during cleanup. The scaffold
is never created or removed by the daemon; no `dac_override` is granted.

Startup diagnostics use private atomic `startup.v1` / `fatal.v1` files under
the existing ZUIopt store (0600, each below 1KB). Completed stages end at READY;
a fatal records its active stage and an allowlisted reason or numeric Binder
status before release. There are no steady-state receipt writes, and diagnostic
I/O failure cannot interrupt the existing fail-safe. Old-boot fatal evidence is
retained, not mistaken for a current-boot failure. Binder snapshots that cannot
be managed USER0 apps are rejected before any proc identity/cmdline/UID read.

Binder callbacks only validate arguments, queue raw events and notify eventfd.
The reactor validates current PID generation/UID and package authority before
acquisition; current ActivityManager snapshots, not delayed callback values,
decide foreground state. Death events cannot discard a live matching generation.
Permission failures produce a private, bounded `runtime_blocker.v1` diagnostic:
at most one write attempt per allowlisted reason per daemon lifetime, no process
or package names. It is retained evidence, not a continuous health indicator.

The independent `zuiopt` domain is an MLS trusted subject because one daemon
must access apps with different categories and the target process `setsched`
constraint requires equal levels or a trusted subject. This adds no direct TE
allows and does not transfer OEM `performanced` permissions to ZUIopt.

ProcessObserver remains the primary process/lifecycle source. An independent
one-way private scene callback from the existing accepted top-resumed authority
coalesces the latest sequence into the same reactor. Registration always replays
the current sequence; callback death clears the registration. The callback reads
no proc/package/snapshot and performs no placement or ACK. The reactor uses the
same authority validation and reconcile path at 0/100/250/500ms, at most four
attempts; newer sequences cancel the previous burst. ACK follows burst completion.
SEQ/ACK are system_server memory, not properties or files. Status shows
`zuioptSceneSeq`, `zuioptSceneAck`, and `zuioptSceneSync=ok|pending`; pending alone
does not change scheduler health. With no managed process or scene burst, the
reactor blocks indefinitely. There is no watcher thread or idle polling timer.
