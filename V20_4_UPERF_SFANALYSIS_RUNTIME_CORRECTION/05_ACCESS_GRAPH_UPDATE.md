# 05 — Access Graph Update

`VerifyUperfRuntimeAccess.py` now reports two distinct assurance boundaries:

- wrapper/init/crash-gate explicit code: `COMPLETE_STATIC_REVIEW`;
- closed-source config-enabled modules: `PARTIAL_STATIC_REVIEW`.

It no longer implies that static validation can discover every closed-source Uperf access.

## Config-activated module review

| Module | Enabled | Known resources / impact | Current device proof |
|---|---:|---|---|
| sfanalysis | false | SurfaceFlinger executable plus two libraries; no new allow because module is disabled | Previous `true` startup failure proved; corrected candidate pending |
| input | true | `/dev/input/**`, existing typed `input_device` rules | Corrected candidate pending |
| switcher | implicit/active | canonical effective/per-app mode files in `zui_control_data_file` | Corrected candidate pending |
| sysfs | true | declared cpuset, CPU frequency, WALT and msm_performance knobs; existing typed policy | Corrected candidate pending |
| sched | false | per-task proc/scheduling resources intentionally not owned by Uperf | Disabled by architecture; asoulOpt owns placement |

The gate also rejects any new `performanced` SurfaceFlinger allow, broad proc file allow,
permissive domain, broad shell property write, or neverallow-bypass marker. Final-super reverse
verification loads the extracted final config and pinned Uperf binary, so source-only state cannot
satisfy the final gate.
