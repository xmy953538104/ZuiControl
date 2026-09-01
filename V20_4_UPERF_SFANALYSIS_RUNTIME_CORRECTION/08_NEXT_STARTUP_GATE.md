# 08 — Next Startup Gate

This is a plan for the next human-approved candidate; this correction task does not flash it.

1. Confirm the exact candidate RunId, source commit, CI run, four image hashes and fixed-seven XML.
2. Record the current safe degraded device state without starting Uperf, clearing fail-safe/counter,
   changing config/policy, disabling SELinux, or rebooting experimentally.
3. Flash only fixed-seven. Keep qdl in EDL after programming.
4. Physically `dump-part` all seven target partitions. Require exact size and SHA-256 equality for
   each label; a flag/ACK/log marker alone is FAIL. Delete each temporary dump after hashing.
5. Reset system only after the seven-entry physical manifest says PASS.
6. Run the Android boot hard gate and stable system_server observation.
7. Run Uperf startup stability. Require no `surfaceflinger_exec`, `proc_uptime`, or
   `scheduler_active` blocking AVC; fail-safe must remain clear during normal startup.
8. Prove FIFO steady state and ten-minute no-polling/no-restart behavior.
9. Run `TUNING_RESPONSE`: with SFAnalysis disabled, verify first real touch/swipe/load recovers CPU
   response acceptably through the input/load paths.
10. Only then continue the previously defined normal recovery, worker 3/20s, ownership, idle and
    full device matrix.

On any boot/startup failure: stop, do not repeatedly reboot, collect the approved failure bundle,
and do not auto-generate another candidate.
