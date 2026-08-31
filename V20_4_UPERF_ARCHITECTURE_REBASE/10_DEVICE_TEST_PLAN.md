# 10 Device test plan

This plan starts only after human Pre-Flash approval. The current work package does not flash.
Retain monotonic timestamps and capture `dumpsys activity activities` only as evidence after each
event; it is never a production scene input.

## A. Exact-scene handoff — 100/200 transitions

Configure Game exact=`fast`, global=`balance`, then run Game ↔ Video and Game ↔ Home for 100 cycles
each (200 departures and 200 returns). On every edge record framework top-resumed Activity,
`uperfScenePackage`, desired/last-applied mode, `sys.zui_control.uperf_mode`, effective inode/file,
Uperf preset log and monotonic latency. Game must select fast; Video/Home must select global. Count
property writes and prove same-target dedup.

## B. QS/SystemUI overlay

With Game top-resumed and exact fast, open/close QS at least 100 times. Confirm Android retains Game
as top-resumed and the mode does not flap `fast -> global -> fast`. If the framework actually moves
top-resumed authority, preserve the trace and stop this subtest for design review rather than
special-casing SystemUI.

## C. Freeform, split and PiP

Exercise active-pane changes in freeform and split screen for at least 50 switches per layout.
Only the framework-selected top-resumed pane may own an exact rule. A visible inactive Game and a
non-focusable PiP Game must not retain exact authority. Include ZuiControl becoming a real
top-resumed Activity and prove it selects global.

## D. Screen off/on

From global, exact performance and exact fast scenes, run at least 30 screen-off/on cycles each.
Off must converge to powersave. On must recompute from the retained/current top-resumed workload,
without a stale Game mode after switching workloads while locked.

## E. Idle overhead

After boot stabilization capture 60 seconds of `/proc` samples and a 90-second Perfetto trace.
Compare the prior wrapper, new wrapper and Uperf worker for CPU time, wakeups, scheduling slices,
fork/exec count and child count. Explicitly search for shell/grep/tail/inotify process churn and a
five-second cadence. Expected idle behavior is a blocking FIFO read with zero periodic wrapper
wakeups; this expectation is not a result until measured.

## F. Crash recovery

Run ten isolated normal worker crashes and record restart result/latency without killing the
wrapper. Then run a controlled rapid storm: three worker crash events inside 20 seconds and three
whole-service deaths with readiness lifetime at most two seconds. Both paths must enter the
exposed fail-safe, stop Uperf, avoid `sys.init.updatable_crashing`/RescueParty escalation and remain
stopped. Verify explicit scheduler stop is never auto-restarted; explicit scheduler restart clears
the gate and starts exactly one service instance.

## G. CPU-knob ownership

For `core_ctl` enable/min/max, WALT input boost, every cpuset envelope and other active Uperf CPU
sysfs declaration, collect a time series across at least 20 transitions of each preset. Correlate
mode property, effective file, Uperf log and actual value. Add writer trace where supported and a
controlled same-load Uperf stop/start A/B. Record as CONFIRMED, SHARED or UNPROVEN; a single read is
not ownership evidence. Capture representative asoulOpt task affinities separately from global
cpuset envelopes.

## H. Performance and thermal A/B

Use at least one sustained high-load game under balance, performance and fast, with comparable
workload and ambient conditions. Record frame-time distribution including P95/P99, CPU frequency
and residency, power, battery current and temperature. Keep thermal services and ceilings enabled
and record throttling events. Revert any v1.0.6 tuning field that has no reproducible benefit or
causes latency, power or thermal regression.

Every section must retain raw commands, timestamps, hashes and failure evidence. A partial result
cannot promote the package to Production PASS.
