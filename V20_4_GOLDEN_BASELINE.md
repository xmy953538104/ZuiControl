# ZuiControl V20.4 Golden Baseline

V20.4 is frozen as **CLOSED WITH EXPLICIT BOUNDARIES**. This file and
[`V20_4_GOLDEN_BASELINE.json`](V20_4_GOLDEN_BASELINE.json) are the only
authoritative baseline selectors for later production work. A failed or
superseded candidate must never be selected by recency or directory name.

## Exact lineage

| Field | Value |
| --- | --- |
| Build RunId | `20260903144915` |
| Source commit | `29f23f8d590b88f0d472c12373366a9ef14e8330` |
| GitHub CI | `33724674012` |
| Device Gate RunId | `20260903153438` |
| Golden package | `D:\3.VScode\Mi\zui072（flash）\out\V20.4_Golden_20260903144915` |
| Final closure ZIP | `D:\3.VScode\Mi\zui072（flash）\work\evidence\V20_4_FINAL_CLOSURE_GATE.zip` |
| Closure ZIP SHA-256 | `582c1fcfaf5d4eac629c95f723b57d0a5492825d2e1408e6c29250608694a490` |

## Image and runtime hashes

| Artifact | SHA-256 |
| --- | --- |
| `super.img` | `6124e7ddcdc8e656bda893158575ed22c4f240943a8b56c82b98546a666ba6c4` |
| `boot.img` | `e7e85b5cd2806b8c27adf4925e05ee169072a79a43502effc34c97fb27ee8371` |
| `vbmeta_system.img` | `9479cf42e908615517d585aee01c4b803706f50253fdf0ac8d5238cc65ec22fb` |
| `vbmeta.img` | `c1f4ea68ea52bae62e464ddc245dadd740569ba3ac3376ee5d23a40204a744f7` |
| final `services.jar` | `245b4f2c55d5ed8b99ecba8bd473d1d76eb40c55d67116a477299cc9d8b62000` |
| `/system/bin/uperf` | `f1265757009ff0c85dd8587d9e7bfcf5e51d10d36fe5e1341688215ae1fb49d8` |
| `/system/bin/zui_uperf_supervisor` | `810d58821ff906e337e06701ae99226e3016699398d46880086168b8d7a4a655` |
| `/system/bin/AsoulOpt` | `7a2ee5d67ba7c057066176334eca9256e376427916429d66b7593cbb5538ec86` |
| `uperf-sm8650.json` source CRLF | `b5a9b63f07f4479bd1913610f1e08ca09bdd6afa3b0b0200591d1b4c662d241b` |
| deployed LF-normalized Uperf config | `fc719b55087f3a1309c2a19bc6442ce2a98276aa0aedc55e7c501654bb268dd8` |
| `default_asopt.conf` | `69a73f9bedb3a5f3e07d8f74d3ab9d18f8ab97ff48e02c74a378333fa3b1b75e` |

## Closed result

- Transitional-null game progression: 3/3 PASS; global dip, stale override,
  and unexpected property write counts are all zero.
- Display-global top-resumed matrix: 15/15 PASS, including freeform, split,
  PiP, QS, and screen off/on.
- Mixed-use soak: 610 seconds / 55 samples PASS with invariant system_server,
  supervisor, Uperf, and asoulOpt processes.
- New blocking AVC: 0.
- Fixed-seven physical read-back: 7/7 exact length and SHA-256 PASS.
- Post-readback boot and scheduler smoke: PASS.

## Explicit boundaries and backlog

The closure does not claim interactive true-persistent-null coverage,
performance/thermal improvement, deep ownership for every CPU/GPU/thermal
knob, worker-crash/storm closure, 120 Hz hard lock, secondary-user behavior,
or external-display behavior.

The worker items are `OPTIONAL_HARDENING`, not V21 blockers and not PASS. Other items remain
`BACKLOG` and must not be rewritten as PASS:

```text
WORKER_CRASH_LIFECYCLE=OPTIONAL_HARDENING
WORKER_STORM_3_20S=OPTIONAL_HARDENING
CORE_CTL_OWNERSHIP_DEEP_AUDIT
INPUT_BOOST_OWNERSHIP_DEEP_AUDIT
CPUSET_OWNERSHIP_DEEP_AUDIT
PERFORMANCE_AB
THERMAL_AB
ADAPTIVE_REFRESH_RESPONSE_TUNING
```

Every future production task must start by declaring:

```text
BASELINE_SOURCE=29f23f8d590b88f0d472c12373366a9ef14e8330
BASELINE_IMAGE_HASHES=super:6124e7ddcdc8e656bda893158575ed22c4f240943a8b56c82b98546a666ba6c4;boot:e7e85b5cd2806b8c27adf4925e05ee169072a79a43502effc34c97fb27ee8371;vbmeta_system:9479cf42e908615517d585aee01c4b803706f50253fdf0ac8d5238cc65ec22fb;vbmeta:c1f4ea68ea52bae62e464ddc245dadd740569ba3ac3376ee5d23a40204a744f7
```
