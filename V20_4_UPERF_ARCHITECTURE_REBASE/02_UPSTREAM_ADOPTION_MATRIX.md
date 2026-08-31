# 02 Upstream adoption matrix

| Upstream item | Production owner | Decision | Reason |
|---|---|---|---|
| `bin/uperf` | Uperf execution plane | keep current | It is byte-identical; replacement would create noise only. |
| SM8650 power model | Uperf | keep | All model leaves are already equal. |
| balance/powersave idle sample and slack | Uperf internal CPU policy | adopt | v1.0.6's four intended SM8650 changes reduce idle sampling frequency. |
| `sfanalysis.enable=true` | Uperf internal render analysis | adopt, device-gated | It is internal preset refinement, not a second macro scene owner. |
| input detector and preset CPU parameters | Uperf | keep | Already equal to upstream. |
| `crazy` preset | none | reject | Production contract permits only powersave/balance/performance/fast. |
| upstream per-app list and wildcard | system_server + exact user rules | reference only | It would create a second scene owner and broaden the user-app contract. |
| Native Auto/switcher foreground owner | system_server | reject | Effective inode remains one of four explicit presets selected by system_server. |
| sched rules/affinity/prio | asoulOpt | reject/keep disabled | `modules.sched.enable=false`; asoulOpt remains the only per-task owner. |
| upstream cpuset envelope | Uperf global power policy, ownership unproven | retain current, device-gated | Values are already equal; no new takeover is introduced. |
| current extra governor/input-boost/msm-perf declarations | ownership unproven | retain pending A/B | Removing them without writer trace would be another unverified tuning change. |
| stop/kill OEM perf or power services | init-native fence | reject | The existing scheduler-active gate is the only OEM fence. |
| thermal disable/daemon kill | OEM thermal safety | reject | Thermal ceiling must remain enabled. |
| KGSL/Adreno/GPU/devfreq/bus changes | future GPU work | reject | Outside this work package and current owner boundary. |
| blanket hotplug/input boost/cpuset rewrites | mixed OEM/Uperf/asoul owners | reject | Magisk generic takeover is not a valid TB321FU owner proof. |
| Magisk mounts, `/data/adb`, BusyBox | none | reject | ROM payload has no Magisk/data-adb runtime dependency. |
| YC manager APK | ZuiControl UI | reject | Not part of the product. |
| bundled asoulOpt archive | existing asoulOpt production payload | reject | asoulOpt logic and binary are frozen in this package. |

Adoption is transplant-by-field only. No upstream script is executed or copied into a production
path.
