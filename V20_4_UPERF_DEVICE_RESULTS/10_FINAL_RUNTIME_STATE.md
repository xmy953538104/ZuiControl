# Final Runtime State

Date captured: 2026-09-01 13:47 CST

State: **PRESERVED HARD-FAIL STATE; no final restore attempted**

```text
sys.boot_completed=1
system_server PID=2660
zui_control Binder=found
SELinux=Enforcing
Launcher=com.zui.launcher/.drawer.NormalLauncher
init.svc.zui_uperf=restarting
init.svc.zui_asoulopt=running
sys.init.updatable_crashing=1
sys.init.updatable_crashing_process_name=zui_uperf
sys.zui_control.uperf_fail_safe=0
active factory-reset transition=none observed
live services.jar=f7575f5ca50fdba040e814229063beecf99203b9d25fc117401268c62b2c82fd
```

The requested healthy final-state restoration was not possible without changing runtime state.
The hard-fail instruction required preserving evidence, avoiding repeated reboot/repair, and
waiting for human review. Accordingly no scheduler stop/start, property clear, recovery flash, or
reboot was issued.

Full snapshot: [boot runtime](raw/device_run_20260901120647/phase1_flash_boot/hard_fail_capture/boot_runtime_snapshot.txt),
[final read-only status](raw/device_run_20260901120647/phase1_flash_boot/hard_fail_capture/final_readonly_status.txt),
[services.jar SHA](raw/device_run_20260901120647/phase1_flash_boot/hard_fail_capture/services_jar_sha256.txt),
[all logcat](raw/device_run_20260901120647/phase1_flash_boot/hard_fail_capture/logcat_all_post_gate.txt),
[dmesg](raw/device_run_20260901120647/phase1_flash_boot/hard_fail_capture/dmesg.txt),
[getprop](raw/device_run_20260901120647/phase1_flash_boot/hard_fail_capture/getprop.txt),
[ps -AZ](raw/device_run_20260901120647/phase1_flash_boot/hard_fail_capture/ps_AZ.txt), and
[mounts](raw/device_run_20260901120647/phase1_flash_boot/hard_fail_capture/mount.txt).
