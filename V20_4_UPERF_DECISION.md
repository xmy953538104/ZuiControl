# V20.4 Uperf Architecture & Upstream Rebase Decision

Date: 2026-09-01

Decision: **STATIC/HOST/BUILD/FINAL-ARTIFACT PASS; READY FOR HUMAN PRE-FLASH GATE.**

This is not a Production PASS. Candidate RunId `20260901120647` has not been flashed and device
scene, lifecycle, idle, knob-ownership, thermal and performance validation has not started.

## Required answers

1. **Upstream module version:** `v1.0.6（Stable version）`, versionCode `260826`. The frozen ZIP
   SHA-256 is `00b19294e4efc202fd794decb5526b5ad903dca3a15c9af3cfc335edab2b5fcc`.
2. **Did the upstream binary change?** No. Upstream and production are both 1,461,512 bytes with
   SHA-256 `f1265757009ff0c85dd8587d9e7bfcf5e51d10d36fe5e1341688215ae1fb49d8` and embedded version
   `v3(22.09.04)`.
3. **Should production replace the binary?** No. The byte-identical binary remains unchanged.
4. **Adopted SM8650 tuning:** `modules.sfanalysis.enable=true`; balance idle sample/slack
   `1.0/0.5`; powersave idle sample/slack `1.5/0.8`. No whole-config overwrite was performed.
5. **Explicitly rejected Magisk content:** Magisk/data-adb mounting and installer ownership,
   per-app wildcard/game whitelist and Native Auto scene ownership, sched/thread rules, OEM perf
   service killing, thermal disable/HAL changes, blanket hotplug/input-boost/devfreq/bus/cpuset
   takeover, GPU/KGSL takeover, YC manager, and bundled asoulOpt.
6. **Final exact-scene authority:** the framework's actual `mTopResumedActivity` change event,
   delivered event-driven to `UperfScenePolicy`; screen-off powersave remains the higher override.
   Refresh continues to use focused Window and does not drive Uperf.
7. **Game to Video/Home:** yes, once the game is no longer top-resumed its exact rule loses
   authority and an unconfigured Video/Home workload returns to the configured global mode.
8. **QS overlay mode flap:** the policy keeps the game's exact mode when QS does not replace the
   game as top-resumed, avoiding modeled `fast→global→fast` churn. Runtime proof is still required.
9. **Multi-window arbitration:** framework's single current top-resumed Activity wins. Other
   resumed/visible freeform, split or PiP activities do not gain exact authority merely by being
   visible.
10. **Uperf Native Auto:** still disabled as a production owner. The retained binary/perapp
    capability is not claimed deleted; production never selects `auto`.
11. **Uperf sched:** still `modules.sched.enable=false`; asoulOpt remains the only per-task
    affinity/context-scheduler owner.
12. **Five-second wrapper polling:** removed in the candidate. One bounded startup FIFO read is
    followed by blocking event reads; there is no periodic process count, grep, timer, watchdog or
    long-lived helper child.
13. **Normal crash recovery:** Uperf's internal SIGCHLD/wait manager recovers a worker. Whole
    writer-tree EOF exits the wrapper and delegates recovery to Android init lifecycle semantics.
14. **Rapid crash fail-safe:** three worker crash events within 20 seconds, or three consecutive
    whole-service deaths before two seconds, set `sys.zui_control.uperf_fail_safe=1` and stop the
    service in an explicit degraded state. Device storm behavior remains a mandatory test.
15. **`core_ctl` / input boost / cpuset ownership:** not proven. `core_ctl` and input boost have
    historical values contradicting config declarations; cpuset is SHARED/UNPROVEN against OEM
    policy. Declarations are retained without claiming ownership pending transition time-series and
    writer-trace A/B.
16. **GPU/thermal boundary:** unchanged. No KGSL/Adreno/devfreq owner was added, thermal safety
    remains enabled and OEM-owned, and asoulOpt production logic is untouched.
17. **Expected idle overhead:** normal steady state should have zero five-second shell/grep wakeups,
    forks and wrapper child churn because it blocks on FIFO events. This is an expectation, not a
    device measurement; the planned 60-second `/proc` and 90-second Perfetto gates remain required.
18. **Host tests:** Uperf `31/31`, frozen Refresh `39/39`, V20.3B retirement regression `5/5`, and
    shell/JSON/Python/PowerShell/diff gates PASS. GitHub Actions run `33468476491` succeeded at the
    exact candidate source; official Android 14 `host_init_verifier` returned exit `0`.
19. **Final artifact verifier:** reverse extraction and provenance PASS with `62` markers; final
    extracted `services.jar` SHA-256 is
    `f7575f5ca50fdba040e814229063beecf99203b9d25fc117401268c62b2c82fd`; target ART returned
    `DEX_RC=0/GATE_RC=0` with empty output; eight final CIL host/device hashes matched and target
    `secilc` returned `SECILC_RC=0/GATE_RC=0` with empty stderr.
20. **New RunId and hashes:** RunId `20260901120647`, source
    `72fd3ef5ab3d5d6a2b477a9ba2781ee9503d2d30`, CI `33468476491`; `super.img`
    `4eab12c796eba74f98db7a851cdeb24687077c97c70f6eab7045ea2c70608a06`; `boot.img`
    `e7e85b5cd2806b8c27adf4925e05ee169072a79a43502effc34c97fb27ee8371`; `vbmeta_system.img`
    `95a0154d62e8170b89212b665a620f80ab6bc51b65ca025216740a650cb757c3`; `vbmeta.img`
    `c1f4ea68ea52bae62e464ddc245dadd740569ba3ac3376ee5d23a40204a744f7`.
21. **Human Pre-Flash Gate:** yes. The static/build/final-artifact evidence is complete enough for
    human review. Flashing and device validation require separate explicit approval.

## Candidate and evidence

- Candidate: `D:\3.VScode\Mi\work\v20_4_uperf_candidate_20260901120647`
- Work package: `V20_4_UPERF_ARCHITECTURE_REBASE/`
- Build/final verification: `V20_4_UPERF_ARCHITECTURE_REBASE/09_BUILD_VERIFY.md`
- Device test plan: `V20_4_UPERF_ARCHITECTURE_REBASE/10_DEVICE_TEST_PLAN.md`

```text
V20_4_UPERF_SOURCE_HOST=PASS
V20_4_UPERF_FINAL_ARTIFACT=PASS
V20_4_UPERF_PRE_FLASH_READY=YES
V20_4_UPERF_FLASHED=NO
V20_4_UPERF_DEVICE_VALIDATION=NOT_STARTED
```
