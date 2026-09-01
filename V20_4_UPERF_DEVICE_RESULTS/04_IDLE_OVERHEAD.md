# Idle Overhead

Result: **NOT EXECUTED — prerequisite failure.**

The required five-minute settle, 60-second `/proc` window, 90-second Perfetto capture, and
10-minute process/fork observation were not started. The boot failure itself produced an observed
approximately five-second init restart cadence for `zui_uperf`; therefore this candidate cannot
claim removal of five-second runtime churn, regardless of the wrapper's static event-driven design.

Evidence: [gate failure summary](raw/device_run_20260901120647/phase1_flash_boot/gate_failure_summary.txt)
and [dmesg](raw/device_run_20260901120647/phase1_flash_boot/hard_fail_capture/dmesg.txt).
