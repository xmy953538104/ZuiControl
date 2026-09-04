# FIFO historical audit

## Golden contract

```text
Uperf output=regular file
readiness=native startup-only regular-log checker
lifetime=PR_SET_CHILD_SUBREAPER + blocking waitpid(-1)
steady FIFO=none
```

RunId `20260903085341` proved that Uperf unlinks the supplied FIFO pathname and
creates a regular file at the same path. The old FIFO FD therefore cannot carry
readiness or process lifetime. Golden source removes `.service_log_pipe` and
`mkfifo`; Final Gate proves startup readiness and steady health on that design.

## Classification

| Location | Classification | Action |
| --- | --- | --- |
| `payload/`, `native/`, active README/state/rules | ACTIVE | Checked: no positive FIFO production assumption. |
| `V20_4_UPERF_ARCHITECTURE_REBASE/04_UPERF_LIFECYCLE.md` | HISTORICAL ONLY | Added explicit superseded-design banner. |
| `V20_4_UPERF_DEVICE_RESULTS/05_CRASH_LIFECYCLE.md` | HISTORICAL ONLY | Added explicit failed-candidate banner. |
| `V20_4_UPERF_READY_MARKER_SELINUX_CORRECTION/tests/TestUperfReadyMarkerSelinux.py` | HISTORICAL ONLY | Preserves the shell-owned ready-marker correction fixture; it is expected to fail against the later native-supervisor Golden wrapper and is excluded from the canonical suite. |
| `V20_3A_COMMAND_WAKEUP/tests/TestV20_3APolicy.py` | HISTORICAL ONLY | Preserves an earlier command-wakeup source snapshot; it is expected to fail against later V20.3B/V20.4 production and is excluded from the canonical suite. |
| Regular-log host tests mentioning `mkfifo` | ACTIVE NEGATIVE FIXTURE | Retained: they prove FIFO input is rejected/not reused. |
| final verifier checks for absence of `mkfifo` | ACTIVE SAFETY GATE | Retained. |
| `scripts/verify/VerifyUperfRuntimeAccess.py` and `scripts/verify/VerifyZuiControlFlashPackage.ps1` | ACTIVE | Removed positive FIFO transport/policy requirements. Legacy unused CIL permissions remain frozen production compatibility, not a runtime contract. |
| archived device logs/reports | HISTORICAL EVIDENCE | Retained, read only by targeted reference. |

`FIFO EOF regression` in old reports is not a current required gate. Worker
fault lifecycle and `3/20s` storm remain backlog, but their future test design
must observe real process descendants and init/supervisor state, not FIFO text
or EOF.

```text
FIFO_ACTIVE_ASSUMPTIONS_REMAINING=0
FIFO_HISTORICAL_EVIDENCE_DELETED=NO
```
