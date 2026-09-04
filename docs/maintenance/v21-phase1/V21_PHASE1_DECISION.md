# V21 Phase 1 preliminary engineering decision

> Historical pre-major-cleanup record. The executed Mi migration/deletion decision and
> receipts are under `../v21-phase1-major-cleanup/` and in
> `V21_PHASE1_MI_MAJOR_CLEANUP_GATE.zip`; those newer records take precedence.

## Decision

**PASS — V20.4 Golden was frozen and the host-only cleanup design was ready for the later
major-cleanup execution.**

The sole production baseline is Build RunId `20260903144915`, source commit
`29f23f8d590b88f0d472c12373366a9ef14e8330`, CI run `33724674012`, and Device
Gate RunId `20260903153438`. Image and closure hashes were independently
recomputed in this phase and match the baseline manifest.

## What changed

- Current entry documentation now selects only the Golden manifest/evidence.
- V20.4 candidate history was classified before the later knowledge-extraction cleanup.
- Superseded FIFO descriptions are historical-only; active verifiers no longer
  require FIFO transport/policy as a current contract.
- Host-only device helpers and a canonical display-global scene harness were
  added with escaping/parser fixtures.
- Exact-plan, protected-path, DryRun-default workspace cleanup was added and first exercised
  in DryRun; the later major-cleanup record documents its separately verified execution.
- A hash-keyed build/extraction cache design and a host-only qdl log compactor
  were added. Neither is integrated into the release build path.
- Firehose digest research concluded NOT_APPROVED; no EDL experiment occurred.

## Frozen boundary

No file under production app/framework/native/payload runtime paths differs from
the Golden source commit. No ROM was built, no device was flashed, no candidate
was generated, and V21 Phase 2 was not started. Legacy unused FIFO CIL
permissions remain byte-for-byte frozen compatibility code; they are not an
active runtime assumption and are deferred to a separately reviewed production
cleanup.

The eight explicitly deferred V20.4 backlog items remain BACKLOG exactly as
recorded in the Golden manifest.

```text
V20_4_GOLDEN_FROZEN=YES
PRODUCTION_RUNTIME_CHANGED=NO
FIFO_ACTIVE_ASSUMPTIONS_REMAINING=0
KNOWN_GOOD_COMMAND_LAYER=PASS
SCENE_HARNESS_CANONICAL=PASS
WORKSPACE_CLEANUP_AUTOMATED=DRYRUN_PASS
BUILD_CACHE_PLAN=DESIGNED_NOT_ENABLED
QDL_PROGRESS_LOGGING_PLAN=HOST_COMPACTOR_PASS_NOT_INTEGRATED
FIREHOSE_DIGEST_STATUS=NOT_APPROVED

ROM_BUILT=NO
DEVICE_FLASHED=NO

READY_FOR_CHATGPT_V21_PHASE1_REVIEW=YES
```
