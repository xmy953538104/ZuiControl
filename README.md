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
python tests/gpu/TestGpuTouch.py
powershell -NoProfile -File scripts/build/BuildZuiControl.ps1 -Configuration Debug
```

Release signing values are supplied through the parameters documented by
`scripts/build/BuildZuiControl.ps1`; signing material is never stored here.

## Host regression tests

Run from the repository root:

```text
python tests/command_plane/TestCommandPlaneArchitecture.py
python tests/gpu/TestGpuProof.py
python tests/monitor/TestMonitor.py
python tests/monitor/TestUiPolish.py
python tests/monitor/TestProductR6.py
python tests/monitor/TestProductR7.py
python tests/monitor/TestNotificationRenderer.py
python tests/monitor/TestDropdownSelection.py
bash tests/uperf/TestConfigurableAppPolicy.sh
python tests/monitor/TestRecordSql.py
python tests/monitor/TestPackageIdentity.py
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
clang++ -std=c++17 -O1 -Wall -Wextra -Werror -I tests/zuiopt/fixtures/binder_ndk tests/zuiopt/ZUIoptAcquisitionTest.cpp -lz -Wl,--wrap=open,--wrap=close,--wrap=write,--wrap=stat,--wrap=lstat,--wrap=access,--wrap=opendir,--wrap=mkdir,--wrap=rmdir,--wrap=sched_getaffinity,--wrap=sched_setaffinity -o /tmp/zuiopt-acquisition-fixture
sudo /tmp/zuiopt-acquisition-fixture
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
The acquisition fixture keeps only synthetic proc/cpuset files on `/dev/shm`
tmpfs; its journal remains on disk-backed `/tmp`, with real fsync, atomic rename,
locks and SIGKILL recovery. All stress cases still run. Each suite prints flushed
wall/CPU/block-I/O measurements; CI bounds acquisition to five minutes and the
complete native integration step to ten minutes, failing rather than skipping.
The Binder fixture executes the production reply parser with typed mock Parcel
calls; it is not proof of device wire bytes or SELinux permissions.

## ROM integration

The performance monitor uses one 1000ms system-server scalar collector and
one-way snapshots for an FPS/device-power/quiet-therm bar or circle. Its behavioral reference is
helloklf/vtools tag 4.7.3, commit `6c66b8de7d29b19ff3a16cd7bce86cb430717066`
(GPLv3 upstream); no upstream source/assets are copied. FPS means display-driver
measured FPS, not game present FPS or display refresh. Power is battery-side V*I
only with DISCHARGING status and no external power, using broadcast mV and the
magnitude of API microamps (vendor sign is not direction). Live power uses a
three-sample median; recordings retain raw watts. External power is unavailable. Quiet is resolved by type
once per collector generation. No Monitor KGSL access is made.
The compact notification controls Monitor, Refresh and per-app Uperf through the
existing editable scene authority. Its presence does not enable sampling; Monitor
defaults OFF and is enabled manually. Target SDK 30 deliberately selects the native
legacy custom notification behavior, with compile SDK 35 and no framework exception;
see `docs/NOTIFICATION_CONTROLLER.md`. Display-only never enumerates tasks or writes storage.
A full two-second circle hold replaces the bound app's latest SQLite recording;
recording-only Top15 thread deltas use one core=100% at approximately three seconds.
Leaving the bound app and screen-off pause rather than finalize. HOME is eligible
for live display and its own recording; the original app's record stays paused
while HOME is foreground. Explicit circle tap stops; callback death preserves incomplete data.
The native per-app latest-record page
shows scalar charts and generation-qualified thread details. GPU/Uperf/ZUIopt/
thermal ownership is unchanged.

The inherited Uperf picker filters enabled, launchable apps under the qualified
`/data/app/` or `/system/preinstall/` roots. Unified AppPolicy additionally treats
ZuiControl itself as an ordinary editable app and routes HOME quick actions to
Global. Native unversioned policy writers reject after this cutover; the older
`docs/UPERF_CONFIGURABLE_APP.md` retains the picker/parser compatibility contract.

The terminal candidate rebuilds the services extension from exact current source.
Unmodified services DEX members remain byte-identical. The GPU lane additionally
binds the qualified TAssistent-only Java filter and exact CI framework manager
extension; every unrelated framework member remains byte-identical. A source-bound
terminal manifest is required for payload application:

```powershell
python scripts/build/ApplyZuiControlPayload.py --root . --unpack <unpacked-image-root> --payload <ci-payload> --original-base-manifest <approved-original-manifest.json> --terminal-framework-manifest <terminal-jars.json> --dry-run
python scripts/build/ApplyZuiControlPayload.py --root . --unpack <unpacked-image-root> --payload <ci-payload> --original-base-manifest <approved-original-manifest.json> --terminal-framework-manifest <terminal-jars.json>
```

Build outputs, ROM images, device evidence, review packages, and local project
history do not belong in this repository. Runtime ownership and payload details
are summarized in `payload/README.txt`.

Refresh, Uperf and GPU app settings share `policy-v2.json`, owned by
system_server. Each `(userId, packageName)` row contains refresh Hz, Uperf mode
and both GPU endpoints. Explicit rows remain snapshots when Global changes.
Selecting or reselecting a mode resets that app GPU range to the configured mode
default. HOME quick actions change Global; ZuiControl remains editable. Requests
carry policy generation and accepted scene identity. Screen-off powersave remains
runtime-only. Old native policy writers and unversioned Binder mutations reject.
Strict migration retains original stores and hashes, stages owner projections,
commits an AtomicFile generation and waits for owner ACKs. Recovery reconciles
actual file hashes; owner failure rolls back the whole previous generation or
holds new mutations until recovery.

New absent/reset GPU defaults are powersave231–366, balance231–578,
performance422–903 and fast629–903MHz. Migration first snapshots app rows using
OLD fallbacks and preserves every explicit global default. The same twelve OPPs,
QTI/system_server owner, release rules and OEM hard thermal protection apply.

Uperf keeps the immutable `/system/etc/zui_control/uperf-sm8650.json` and unchanged
binary. The existing authenticated command owner accepts `ui_begin`, `ui_chunk`,
`ui_commit`, `ui_state` and `ui_reset` for bounded config-only imports. Exact
binary/factory/current-base/SoC hashes, strict ZIP/JSON, ROM qualification and
field-specific envelopes must pass before activation. `uperf-compatibility.json`
is deliberately empty of qualified profiles/payloads: no new CPU package is
currently authorized. A controlled existing-supervisor restart must become ready;
failure selects last-good or factory once. Startup revalidates the selection.
Accepted and rejected imports remain retained. No additional runtime owner exists.

ZUIopt retains its accepted task engine, lifecycle and failsafe. Its one runtime
ruleset remains `/data/vendor/zui_control/zuiopt/effective.conf`. Store metadata
`ZUIOPT_CANONICAL_STATE_V2` binds generation, source evidence, rules hash and prior
generation. Migration validates the V1 merge once and canonicalizes its effective
semantics; factory/packs/user files are thereafter retained provenance, not live
layers. Manual edits and rollback require current generation CAS. Success after a
reload requires a boot/PID-generation/hash-bound receipt from the existing daemon.
An uncertain commit is queried, never blindly repeated. Legacy generations and
failed uploads are retained; old pack enable/disable commands reject.

Host `scripts/rules/ZUIOPT_rule_pack.py canonical` normalizes canonical, APPopt or
the qualified recovered CSV format (`--format recovered-csv`). It reuses the
retained static converter and never executes uploaded scripts/binaries. Clean,
Old and New produce the same canonical import format; Compare produces evidence
only. Manifests bind exact old canonical hash and source/normalizer identity.

`owner_state.v1` remains the unchanged crash-recovery journal. Three crashes in
60 seconds stop ZUIopt and recover Android scheduling. The authenticated reset
clears only crash/failure state for the next boot. Device runtime and Android
shell parser gates remain separate from these host implementation checks.

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
acquisition. Accepted system_server top-resumed scene identity decides foreground
ownership; ActivityManager remains the process-discovery source and may lag.
The private registered-owner-only scene query runs on events or physical revoke,
never periodically. Death events cannot discard a live matching generation.
Ownership is an explicit Android/acquiring/owned/revoke-pending/releasing/locally
blocked lifecycle. Observed physical takeover forbids new placement before scene
arbitration. Release preserves new Android groups/masks and cleans only proven
last-applied affinity residue. Same-scene reacquisition first closes the old
journal lease and repeats the temporal baseline, with two episodes per scene.
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

Acquisition is distinct from managed ownership: INACTIVE -> ACQUIRING_BASELINE
-> MANAGED. Each pending request probes a fresh generation/UID-checked common
Android cpuset/affinity baseline at 0/100/250/500/750/1000ms, then bounded
SELF_HEAL_SETTLE at 1250/1500/2000/2500/3000ms. The 1000ms performance deadline
never disables runtime acquisition or writes a baseline blocker. A uniform first pass is
only an in-memory candidate; complete fresh probes must confirm the same
generation/UID/group/mask for at least 250ms. Changes or loss of uniformity reset
the candidate; task-set changes alone do not. Pending probes perform
no journal, placement or ownership writes. The temporally confirmed pass commits the
inheritance floor/lease and original task records durably before placement.
Background, death and stale generation cancel pending work without release.
Candidate confirmation may run at firstStableAt+250ms. At/after 3000ms a real
probe either commits, blocks, or grants exactly one valid pending candidate its
final confirmation, scheduled no later than 3500ms from acquisition start.
Timer lateness never substitutes DEFER for a real probe; expired intermediate
deadlines are skipped without a catch-up storm. OS scheduling lateness cannot
be given a wall-clock guarantee, but cannot extend or re-arm the final probe.
Persistent heterogeneity through this functional cap stays Android-owned and records the bounded private
`inheritance_baseline_unstable` blocker; duplicate foreground events cannot
restart an exhausted window. A subsequent background/foreground transition can.
The acquisition deadline is local to the reactor, independent of scene retries,
and disappears when no acquisition is pending. Committed recovery/release stays
strict. The acquisition fixture intercepts only synthetic proc/cpuset paths in
its exclusive temporary tree; it never writes real host or device scheduling.

Each commit and accepted foreground authority event arms physical coherence
verification at implicit commit T0, then 100/250/500/1000/1500/2000ms and every
500ms through 6000ms. Detection plus a new 250ms baseline confirmation remains
bounded to 750ms logically, excluding execution overhead. Successful isolated
repair adds finite 100/250/500/1000ms confirmation relative to completed placement,
including repair at the last checkpoint. Both schedules share the existing next
deadline; repair neither shortens the original horizon nor resets episode budget.
Duplicate same-state ProcessObserver callbacks do not re-arm; fresh accepted
scene sequences may. Baseline dwell remains 250ms; pre-commit functional
settlement is separate from this unchanged post-commit verification window.
During this finite window applied-mask cache hits cannot hide external cpuset
or affinity drift. One isolated drift may be repaired; broad or repeated drift
freezes placement, preflights every live task and safely relinquishes the old
epoch before temporal reacquisition. At most two repair/reacquisition episodes
are allowed per foreground epoch (authority duplicates do not replenish them).
Exhaustion safely releases to Android and deduplicates `ownership_contested`.
External affinities containing the entire saved mask are left untouched. Only
a proven last-applied residue in the exact saved group may be restored there;
unproven external narrowing and unknown/busy owned tasks remain fail-closed.
Journal clearing follows a fresh complete zero-owned/zero-residue check. Normal
crash recovery retains its existing strict semantics. Normal background release
uses a separate durable, idempotent lane at 0/50/100/250/500ms. It cancels placement
and acquisition, releases only journal/lease-authorized physical ownership, leaves
Android-owned cpusets untouched, and restores only exact known affinity residue
constrained by the current Android group. Mixed Android baselines are allowed;
unknown live ownership and corrupt journals remain fail-closed. A known safe
release delay parks after the finite window, without a timer or durable blocker.
A fresh foreground edge or new scene authority can resume the old release once
at 0/50/100/250/500ms; duplicate callbacks cannot extend that window. Completion
immediately enables normal acquisition. Only a failed foreground rearm records
the deduplicated local `background_release_blocked` receipt, retaining the journal.
Fatal receipts include bounded primary and cleanup substages/reasons.
After probation the existing cache remains; no idle polling or new steady
physical scan is introduced. Device timing acceptance is a separate gate.

Foreground scans are fenced by the last reconciled in-memory event epoch.
New scene sequences and primary callbacks invalidate an in-flight scan without
consuming its notification. Fences surround coherence, durable preparation and
each task write. Drift alone allows one fresh authoritative snapshot; a normal
background transition returns to the existing release lane. Same-authority
foreground corruption stays strict. No Binder ABI, watcher or polling is added.
