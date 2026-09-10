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
release delay becomes a local blocker after the finite window, with no steady
retry timer. Fatal receipts include bounded primary and cleanup substages/reasons.
After probation the existing cache remains; no idle polling or new steady
physical scan is introduced. Device timing acceptance is a separate gate.
