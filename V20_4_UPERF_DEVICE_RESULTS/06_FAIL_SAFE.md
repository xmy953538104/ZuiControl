# Fail-safe

Result: **FAIL in the naturally occurring startup-storm path.**

The planned three-worker/20-second and three-whole-service-deaths tests were not executed. A more
fundamental startup storm occurred automatically:

- `zui_uperf` repeatedly died within a fraction of a second;
- init reached `sys.init.updatable_crashing=1` for `zui_uperf`;
- the ZuiControl fail-safe remained `sys.zui_control.uperf_fail_safe=0`;
- the crash-gate's shell-domain read of `sys.zui_control.scheduler_active` was denied by SELinux.

Thus the candidate did not enter its intended degraded/stopped state before Android's generic init
crash threshold. This is a hard failure, not a substitute PASS for the planned fault-injection
matrix.

Evidence: [properties](raw/device_run_20260901120647/phase1_flash_boot/hard_fail_capture/init_zui_properties.txt),
[live crash gate](raw/device_run_20260901120647/phase1_flash_boot/hard_fail_capture/live_crash_gate_script.txt),
and [dmesg](raw/device_run_20260901120647/phase1_flash_boot/hard_fail_capture/dmesg.txt).
