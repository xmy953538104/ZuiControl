# 02 — SFAnalysis Option Matrix

| Dimension | Option A: `sfanalysis=false` | Option B: `true` + SELinux |
|---|---|---|
| Startup stability | Removes the confirmed startup edge | Requires another flash to discover whether the first allow is sufficient |
| New SELinux surface | None | At least `surfaceflinger_exec:file`; libraries and transitive resources may follow |
| Cascade risk | Low | Material because the binary is stripped and the module graph is only partially recoverable |
| Idle cost | Avoids SF listener/watch work | Adds an always-enabled listener/watch path; target idle cost has not been measured |
| Theoretical response benefit | Input/load paths remain; possible render-idle hint benefit is forgone | Possible faster render-idle response, not yet measured on this device |
| Required for four presets | No | No |
| Required by ZuiControl scene authority | No; system_server remains the owner | No |
| Upstream relationship | One device-gated field differs | Matches upstream default |
| Proven device benefit | None | None |
| Maintenance | One config state plus a semantic assertion | Closed-source access audit and continuing SELinux/device maintenance |

Decision: **Option A**.

Stability, least privilege, low idle overhead, and evidence-backed behavior take priority over an
unmeasured upstream default. If the next device `TUNING_RESPONSE` test proves touch/load recovery
insufficient without SFAnalysis, it can be evaluated later as a separate feature with a complete
permission and benefit study.
