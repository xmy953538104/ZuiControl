# V21/V22 backlog priority

Phase 1 engineering cleanup is complete. No production backlog item was implemented, no ROM
was built, and no device was flashed.

| Order | Work package | Status / boundary |
| ---: | --- | --- |
| 1 | V21 Phase 2 — engineering speed | Build-cache enablement, duplicate-extraction removal, qdl 41–45MB/s root cause, progress integration; device/EDL work still needs its own approval. |
| 2 | V21 Phase 3 — CPU knob ownership | Audit `core_ctl`, input boost, cpuset and restore contracts before performance claims. |
| 3 | V21 Phase 4 — performance / thermal A/B | Measure frame time, power and temperature only after ownership is established. |
| Optional | Uperf worker fault hardening | Worker-child failure/storm injection is `OPTIONAL_HARDENING`, not a core Uperf blocker; begin with Golden no-build/no-flash diagnostic. |
| Later | Adaptive Refresh response tuning | Correctness is closed; tuning remains separate from current Golden semantics. |
| V22 | GPU optional research | Audit KGSL/GameHelper/ZuiPP/PowerHAL/thermal ownership before choosing any GPU owner. |

```text
WORKER_CRASH_LIFECYCLE=OPTIONAL_HARDENING
WORKER_STORM_3_20S=OPTIONAL_HARDENING
CORE_CTL_OWNERSHIP_DEEP_AUDIT=BACKLOG
INPUT_BOOST_OWNERSHIP_DEEP_AUDIT=BACKLOG
CPUSET_OWNERSHIP_DEEP_AUDIT=BACKLOG
PERFORMANCE_AB=BACKLOG
THERMAL_AB=BACKLOG
ADAPTIVE_REFRESH_RESPONSE_TUNING=BACKLOG
QDL_SPEED=41–45MB/s_UNRESOLVED
FIREHOSE_DIGEST=NOT_APPROVED
```
