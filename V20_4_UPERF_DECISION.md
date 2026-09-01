# V20.4 Uperf Architecture & Upstream Rebase Decision

Date: 2026-09-01

RunId: `20260901120647`
Decision: **DEVICE BOOT HARD GATE FAIL; WORK PACKAGE OPEN / HUMAN REVIEW REQUIRED.**

The human-approved fixed-seven package was flashed once. qdl-rs completed successfully and Android
booted, but the Uperf service immediately entered an SELinux-driven restart storm. The mandatory
hard-fail rule stopped the device matrix in Phase 1. No production change, rebuild, runtime repair,
recovery flash, or extra reboot followed.

## Gate facts

- Source: `72fd3ef5ab3d5d6a2b477a9ba2781ee9503d2d30`; CI: `33468476491`.
- `super.img`: `4eab12c796eba74f98db7a851cdeb24687077c97c70f6eab7045ea2c70608a06`.
- `boot.img`: `e7e85b5cd2806b8c27adf4925e05ee169072a79a43502effc34c97fb27ee8371`.
- `vbmeta_system.img`: `95a0154d62e8170b89212b665a620f80ab6bc51b65ca025216740a650cb757c3`.
- `vbmeta.img`: `c1f4ea68ea52bae62e464ddc245dadd740569ba3ac3376ee5d23a40204a744f7`.
- Rawprogram SHA-256: `10840bb75283ab3527aae2286c2b63444b165a7217ca7118113ae6ccfe49784a`;
  exact allowlist was `super`, `vbmeta_system_a/b`, `boot_a/b`, `vbmeta_a/b`.
- Pre-flash safety gate: PASS. Fixed-seven qdl/read-back flow: PASS, `All went well!`, driver RC `0`.
- Android boot: `sys.boot_completed=1`; system_server `2660/983` stable in the three samples taken
  before abort; Binder found; Launcher resumed; SELinux Enforcing; asoulOpt running.
- Live `services.jar` SHA-256:
  `f7575f5ca50fdba040e814229063beecf99203b9d25fc117401268c62b2c82fd`, matching the approved
  final artifact. No current-boot framework VerifyError was found.
- Boot Hard Gate: FAIL because `init.svc.zui_uperf=restarting`,
  `sys.init.updatable_crashing=1`, process name `zui_uperf`, while ZuiControl fail-safe stayed `0`.

## Device root cause

This failure is independent of ART verification and framework bootability.

1. `/system/bin/zui_uperf_service` runs in `u:r:performanced:s0` and reads `/proc/uptime` before its
   FIFO startup wait. Live SELinux rejects `performanced → proc_uptime:file read`; the wrapper exits
   status `1`.
2. Init restarts the service at `restart_period 5`. The early dmesg capture already contains 56
   starts, 56 status-1 exits, and 49 generic “updatable components” threshold messages.
3. `onrestart` launches `zui_uperf_crash_gate.sh` in `u:r:shell:s0`. Its guard reads
   `sys.zui_control.scheduler_active`, but live SELinux rejects
   `shell → zui_control_scheduler_active_prop:file read`. The script therefore exits before
   recording rapid deaths or setting fail-safe.
4. The result is generic init escalation without the intended explicit degraded state:
   `sys.init.updatable_crashing=1`, `sys.zui_control.uperf_fail_safe=0`.

The static and final-artifact gates remain valid as records of what they tested, but they did not
cover these two required runtime policy accesses. They are not production proof.

## Required 20 device answers

1. **Boot PASS/FAIL:** FAIL. Android booted, but the combined Boot Hard Gate failed on persistent
   Uperf restart and `sys.init.updatable_crashing=1`.
2. **Is top-resumed a reliable scene authority?** NOT VALIDATED on this candidate. Static design
   only.
3. **Does Game→Home/Video return to global 100%?** NOT EXECUTED.
4. **Does QS avoid mode flap?** NOT EXECUTED.
5. **Freeform/split/PiP:** NOT EXECUTED.
6. **Screen off/on:** NOT EXECUTED.
7. **Has old five-second wrapper polling disappeared?** The old steady-state polling loop is absent
   statically, but production runtime still showed an approximately five-second init restart
   cadence because the wrapper could not start. Therefore the device-level idle objective is FAIL/
   NOT PROVEN.
8. **Idle CPU/wakeup/process churn improvement:** NOT MEASURED; the restart storm is a regression
   and invalidates an idle comparison.
9. **Normal worker crash 10/10 and latency:** NOT EXECUTED; no stable service tree existed.
10. **Rapid worker storm fail-safe:** PLANNED INJECTION NOT EXECUTED. The natural startup storm
    failed to set fail-safe and reached generic init updatable-crashing instead.
11. **Whole-service storm safety:** FAIL in the naturally occurring whole-service startup-death
    path; bounded explicit fail-safe was not reached.
12. **Explicit stop remains stopped:** NOT EXECUTED.
13. **v1.0.6 idle tuning first-interaction response:** NOT EXECUTED.
14. **sfanalysis stability:** NOT EXECUTED.
15. **core_ctl/input_boost/cpuset ownership:** UNPROVEN; no transition/writer trace was run.
16. **balance/performance/fast real frame-time benefit:** NOT EXECUTED.
17. **Power/temperature cost:** NOT EXECUTED.
18. **Thermal always retained:** Static boundary unchanged; workload runtime proof NOT EXECUTED.
19. **Refresh/asoulOpt regression:** asoulOpt was observed running and framework/Binder/Launcher
    booted, but the requested smoke matrix was NOT EXECUTED; no full no-regression claim.
20. **Can Uperf Architecture & Upstream Rebase close?** **NO.** It remains open at a device Boot
    Hard Gate failure pending human review and a separately approved correction candidate.

## Evidence

- Device report: [`V20_4_UPERF_DEVICE_RESULTS/01_BOOT_GATE.md`](V20_4_UPERF_DEVICE_RESULTS/01_BOOT_GATE.md).
- Preserved raw root: `V20_4_UPERF_DEVICE_RESULTS/raw/device_run_20260901120647/` (distributed in
  the RAR; ignored by Git).
- Static/build evidence: [`09_BUILD_VERIFY.md`](V20_4_UPERF_ARCHITECTURE_REBASE/09_BUILD_VERIFY.md).
- Test plan: [`10_DEVICE_TEST_PLAN.md`](V20_4_UPERF_ARCHITECTURE_REBASE/10_DEVICE_TEST_PLAN.md).

```text
V20_4_UPERF_SOURCE_HOST=PASS
V20_4_UPERF_FINAL_ARTIFACT=PASS
V20_4_UPERF_FLASHED=YES
V20_4_UPERF_ANDROID_BOOT=PASS
V20_4_UPERF_BOOT_HARD_GATE=FAIL
V20_4_UPERF_DEVICE_VALIDATION=ABORTED_AT_PHASE_1
V20_4_UPERF_WORK_PACKAGE=OPEN_HUMAN_REVIEW_REQUIRED
```
