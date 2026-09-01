# 01 — SFAnalysis Root Cause

## Conclusion

`CONFIRMED`

The two `performanced → surfaceflinger_exec:file read` denials are directly caused by activating
`modules.sfanalysis.enable=true` in the pinned Uperf v1.0.6 configuration.

## Evidence chain

1. The pre-rebase production configuration at commit `056dfa1` used `sfanalysis=false`.
2. Frozen upstream `upstream/uperf/1.0.6/config/sdm8g3.json` uses `true`; the failing candidate
   adopted that single device-gated field.
3. Static analysis of the byte-identical pinned binary shows the config node `sfanalysis`, the
   `SfAnalysisListener` vtable/source marker, and the disabled branch text `{} disabled by config`.
4. The activated listener function at `0x1372f4` passes `/system/bin/surfaceflinger`,
   `/system/lib64/libandroidfw.so`, and `/system/lib64/libandroid.so` to the same helper at
   `0x159128`; that helper calls `inotify_add_watch`.
5. On the failing boot, both startup workers (PIDs 4170 and 7314) received the same blocking
   `{ read }` denial on the exact `/system/bin/surfaceflinger` object labeled
   `surfaceflinger_exec`, at 22:47:59.074 and 22:48:09.086.
6. The old `proc_uptime` and `scheduler_active` denials were both zero on that boot, leaving this
   as the only observed Uperf startup blocker.

The causal chain is therefore:

`sfanalysis.enable=true → SfAnalysisListener activated → SurfaceFlinger executable watch/analysis → surfaceflinger_exec read → worker exit`

## Why the module reads this file

The directly proven behavior is file observation of SurfaceFlinger and two framework libraries.
The presence of the file-watch helper and symbol/ELF-related resource set supports the inference
that SFAnalysis locates or refreshes SurfaceFlinger analysis metadata used for render-idle hints.
The exact higher-level algorithm is not recoverable from the stripped closed-source binary and is
not required for ZuiControl's four macro presets.

## Full-access investigation

| Potential resource | Finding | Confidence |
|---|---|---|
| `/system/bin/surfaceflinger` / `surfaceflinger_exec` | Direct string, direct call into watch helper, exact matching runtime AVC | Proven |
| `libandroidfw.so`, `libandroid.so` | Direct strings passed to the same helper | Proven static; runtime access not yet observed |
| SurfaceFlinger PID discovery | General process discovery exists elsewhere in Uperf; attribution to SFAnalysis is not closed | Possible |
| `/proc/<pid>` / `status` / `stat` / `task` | General Uperf strings exist; no SFAnalysis-specific call chain proved | Possible cascade |
| `/proc/<pid>/maps` | No direct string/static evidence found | Unproven |
| `/proc/<pid>/exe` | No direct string/static evidence found | Unproven |
| Binder / service manager | Uperf already uses Activity service for other behavior; no SFAnalysis-specific edge proved | Unproven |
| Other sysfs/proc paths | General CPU/scheduler modules use typed paths; no additional SFAnalysis edge proved | Unproven |

Because the closed-source transitive access graph is incomplete, adding only
`allow performanced surfaceflinger_exec:file read` could expose a second denial on the next boot.
This is a material reason not to choose the permission-expansion option.

Minimal evidence: [`raw/sfanalysis_static_audit.txt`](raw/sfanalysis_static_audit.txt),
[`raw/config_state_comparison.txt`](raw/config_state_comparison.txt), and
[`raw/runtime_failure_excerpt.txt`](raw/runtime_failure_excerpt.txt).
