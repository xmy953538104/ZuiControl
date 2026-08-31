# 05 CPU knob ownership

`config says` is only an expected writer declaration. Current ownership is therefore classified as
CONFIRMED, SHARED, UNPROVEN or REJECTED; no single read is accepted as proof.

| Knob/group | Preset expectation | Historical actual | Current conclusion | Candidate action |
|---|---|---|---|---|
| `core_ctl` cpu5 enable/min/max | preset/global CPU envelope | enable was observed as 0 while config declared 1 | UNPROVEN / overwritten | retain declaration; writer trace required |
| WALT `input_boost_ms/freq` | production initial 0 | 40 ms observed | UNPROVEN / overwritten | retain current, do not claim owner |
| cpuset top-app/fg/bg/system-bg/restricted | global CPU envelope by preset | no time-series owner proof | SHARED/UNPROVEN versus OEM | upstream-equal values retained; device A/B required |
| per-task affinity/WALT task boost | n/a to Uperf | asoulOpt production contract | asoulOpt CONFIRMED owner | Uperf sched remains disabled |
| CPU frequency governor/min/max | WALT/OEM plus Uperf initial declarations | no writer trace | UNPROVEN | no upstream change adopted |
| CPU power model/preset controller | balance/performance/fast/powersave | Uperf preset transitions observed | Uperf macro execution owner | keep |
| KGSL/Adreno/GPU devfreq | none | outside package | REJECTED | no production change |
| thermal ceiling/thermal daemon | OEM safety | enabled baseline | REJECTED for Uperf | no production change |

## Required device evidence

For each retained CPU knob, sample before/during/after at least 20 transitions per preset, including
screen-off powersave and exact-rule handoff. Correlate monotonic time with Uperf mode property,
effective inode, Uperf preset log and value. Add ftrace/eBPF writer evidence where the kernel permits;
otherwise use controlled service stop/start and same-load A/B. A stable value alone is not owner
proof. For cpuset, separately record the global mask and representative asoulOpt-managed task
affinity so envelope and per-thread policy are not conflated.
