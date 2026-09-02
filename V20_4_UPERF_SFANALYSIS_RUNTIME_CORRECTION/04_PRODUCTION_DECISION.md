# 04 — Production Decision

Production runtime change is exactly one JSON boolean:

```diff
-      "enable": true,
+      "enable": false,
```

This is `modules.sfanalysis.enable`. No SurfaceFlinger SELinux rule is added.

Preserved without change:

- Uperf binary SHA-256 `f1265757009ff0c85dd8587d9e7bfcf5e51d10d36fe5e1341688215ae1fb49d8`;
- balance idle sample/slack `1.0 / 0.5`;
- powersave idle sample/slack `1.5 / 0.8`;
- `performanced → proc_uptime` narrow allow;
- removal of the shell `scheduler_active` read;
- top-resumed scene authority and exact/global/screen-off semantics;
- FIFO/init lifecycle and both 3-in-20s / three-short-service fail-safe thresholds;
- Uperf `sched=false`, Native Auto without a production entry, and asoulOpt ownership;
- Refresh, GPU, thermal and command planes.

The prior natural startup storm proves only:

`WHOLE-SERVICE STARTUP-STORM FAIL-SAFE=DEVICE PASS`

It does not prove the separately requested worker-crash 3/20s path. FIFO steady state also remains
`NOT YET PROVEN`, because the wrapper cleaned the FIFO after the startup failure.

Tooling/test changes do not alter Android runtime logic: semantic module review, physical 9008
read-back enforcement, a manifest verifier, and 22 focused host tests.
