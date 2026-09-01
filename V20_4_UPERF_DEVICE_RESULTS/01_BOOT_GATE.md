# V20.4 Uperf Device Validation — Boot Gate

Date: 2026-09-01

RunId: `20260901120647`
Decision: **FAIL — downstream validation stopped**

## Candidate and flash

- Authorized source: `72fd3ef5ab3d5d6a2b477a9ba2781ee9503d2d30`.
- Package: `D:\3.VScode\Mi\flash\ZuiControl_9008_V20_4_UPERF_20260901120647`.
- Rawprogram SHA-256: `10840bb75283ab3527aae2286c2b63444b165a7217ca7118113ae6ccfe49784a`.
- Pre-flash safety gate passed: boot complete, system_server PID `2714`, Binder found,
  SELinux Enforcing, `/data` healthy, and no active factory-reset transition.
- The rawprogram contained only `super`, `vbmeta_system_a/b`, `boot_a/b`, and `vbmeta_a/b`.
- qdl-rs completed the fixed-seven write/read-back flow and printed `All went well! Resetting to
  system`; the flash driver returned `RC=0`.

Evidence: [pre-flash state](raw/device_run_20260901120647/phase0_preflash/preflash_state.txt),
[candidate/hash assertion](raw/device_run_20260901120647/phase0_preflash/candidate_hash_assertion.txt),
[fixed-seven manifest](raw/device_run_20260901120647/phase0_preflash/fixed_seven_manifest.txt),
[qdl transcript](raw/device_run_20260901120647/phase1_flash_boot/qdl_readback_verify.log).

## Boot result

Android reached `sys.boot_completed=1` within the 240-second limit. The following checks passed
before the hard failure was detected:

- system_server PID/starttime stayed `2660/983` in all three samples;
- `zui_control` Binder was found;
- SELinux remained Enforcing;
- boot animation was stopped;
- Launcher was resumed (`com.zui.launcher/.drawer.NormalLauncher`);
- asoulOpt was running;
- live `services.jar` SHA-256 was
  `f7575f5ca50fdba040e814229063beecf99203b9d25fc117401268c62b2c82fd`, matching the approved
  final artifact;
- no current-boot `VerifyError`, `FATAL EXCEPTION IN SYSTEM PROCESS`, or system_server restart was
  observed in the captured log.

The mandatory 180-second observation was deliberately aborted after 31 seconds because every
sample showed:

```text
init.svc.zui_uperf=restarting
sys.init.updatable_crashing=1
sys.init.updatable_crashing_process_name=zui_uperf
sys.zui_control.uperf_fail_safe=0
```

This is a Boot Hard Gate failure even though Android and system_server remained usable.
A final read-only check at device uptime 1224 seconds still had system_server PID `2660`, Launcher,
Binder and Enforcing intact, while the same Uperf/updatable-crashing/fail-safe failure tuple persisted.

## Root cause closed on device

The new wrapper runs in `u:r:performanced:s0`. Its first call to `uptime_seconds()` reads
`/proc/uptime`; SELinux rejects that read against `u:object_r:proc_uptime:s0`. The wrapper exits
with status `1`, and init restarts it every approximately five seconds.

The `onrestart` crash gate runs in `u:r:shell:s0`. Its first guard reads
`sys.zui_control.scheduler_active`; SELinux rejects access to
`zui_control_scheduler_active_prop`, so the script exits without incrementing the rapid-crash
counter or setting fail-safe. Consequently init logged at least 56 start/status-1 cycles in the
early dmesg capture and set the generic updatable-crashing property, while ZuiControl's own
fail-safe remained `0`.

Minimal evidence: [gate failure summary](raw/device_run_20260901120647/phase1_flash_boot/gate_failure_summary.txt),
[boot samples](raw/device_run_20260901120647/phase1_flash_boot/boot_stability_180s.tsv),
[dmesg](raw/device_run_20260901120647/phase1_flash_boot/hard_fail_capture/dmesg.txt),
[post-gate logcat](raw/device_run_20260901120647/phase1_flash_boot/hard_fail_capture/logcat_all_post_gate.txt),
[properties](raw/device_run_20260901120647/phase1_flash_boot/hard_fail_capture/init_zui_properties.txt),
[live crash gate](raw/device_run_20260901120647/phase1_flash_boot/hard_fail_capture/live_crash_gate_script.txt),
[final read-only status](raw/device_run_20260901120647/phase1_flash_boot/hard_fail_capture/final_readonly_status.txt).

## Stop condition

No reboot, recovery flash, runtime repair, production edit, or new build was performed after the
failure. Phases 2–16 were not entered. The device was left in the captured booted state for human
review, as required by the approval.

```text
V20_4_UPERF_FLASH=PASS_FIXED_SEVEN
V20_4_UPERF_ANDROID_BOOT=PASS
V20_4_UPERF_BOOT_HARD_GATE=FAIL
V20_4_UPERF_DEVICE_VALIDATION=ABORTED_AT_PHASE_1
```
