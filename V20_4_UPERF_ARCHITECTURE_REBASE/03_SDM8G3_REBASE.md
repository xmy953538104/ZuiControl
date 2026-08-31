# 03 SM8650/8 Gen 3 rebase

## Semantic diff

The importer flattens both JSON documents. It found 362 equal leaves, 13 changed leaves,
16 production-only leaves and eight upstream-only leaves.

### Adopted

| Field | Production | v1.0.6 | Final |
|---|---:|---:|---:|
| `modules.sfanalysis.enable` | false | true | true |
| `presets.balance.idle.cpu.baseSampleTime` | 0.04 | 1.0 | 1.0 |
| `presets.balance.idle.cpu.baseSlackTime` | 0.08 | 0.5 | 0.5 |
| `presets.powersave.idle.cpu.baseSampleTime` | 0.04 | 1.5 | 1.5 |
| `presets.powersave.idle.cpu.baseSlackTime` | 0.08 | 0.8 | 0.8 |

The four idle values are the only SM8650 config changes between the retained older tuning and
v1.0.6. `sfanalysis` is also adopted because it belongs to Uperf's internal touch/render/load
refinement. It does not select the macro preset. Both require device latency/idle/thermal A/B
before Production PASS.

### Adapted, not copied

- `modules.switcher.switchInode` stays
  `/data/vendor/zui_control/uperf/effective_powermode.txt`.
- `modules.switcher.perapp` stays under `/data/vendor/zui_control/uperf/`; it is configuration
  storage only while the effective mode is never `auto`.
- `crazy` is not copied.

### Rejected or deferred

- Five sched changes are irrelevant while `modules.sched.enable=false`; the disabled rule body is
  left for V21 to avoid a broad cleanup diff.
- The upstream Magisk/zygisk regex is not copied.
- Production-only governor, WALT input-boost and msm-performance declarations remain unchanged.
  Historical reads show declaration is not ownership proof; removal waits for the writer-trace A/B
  in `05_CPU_KNOB_OWNERSHIP.md`.

The exact generated field diff is `raw/upstream_audit/SM8650_CONFIG_DIFF.json`.
