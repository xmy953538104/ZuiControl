# V20.4 candidate classification

This table is the current lineage-status authority; it does not assert that a historical
candidate package or raw evidence directory still exists. Scripts must select production
baseline only from `V20_4_GOLDEN_BASELINE.json`; status, recency, path names,
and previously accepted flash directories are never selectors.

| Build / device run | Source / CI | Status | Reason and action |
| --- | --- | --- | --- |
| `20260903144915` / `20260903153438` | `29f23f8` / `33724674012` | **GOLDEN** | Final closure PASS; only production baseline. Protect source, fixed-seven images, closure ZIP and evidence. |
| `20260831170720` | `146e096` / `33375509612` | CLOSED_REFERENCE | Refresh Runtime Correction PASS; incorporated into Golden. Keep compressed knowledge, never select independently. |
| `20260903103038` / `20260903110608` | `e43fe4c` / `33707669658` | CLOSED_REFERENCE | Regular-log readiness/lifetime Development Gate PASS; superseded by transitional-null Golden. |
| `20260831094239` | `3865cf9` lineage | FAILED | Pre-flash event-order rejection; evidence only, never flash. |
| `20260831104317` | pre-ART correction lineage | FAILED | ART `VerifyError`, Boot Gate FAIL; never flash. |
| `20260901120647` | `72fd3ef` / `33468476491` | FAILED | Uperf startup SELinux Gate FAIL; never flash. |
| `20260901174600` | `511f314` / `33490157865` | FAILED | SurfaceFlinger blocking AVC, fail-safe/stopped; never flash. |
| `20260902095453` | SFAnalysis correction lineage | FAILED | Ready-marker rename denial runtime failure; evidence only. |
| `20260902110516` / `20260902150110` | `d9b4dd3` / `33584792015` | FAILED | Startup stopped/fail-safe; FIFO steady not established; never flash. |
| `20260903065226` / `20260903081352` | `adf81d6` / `33692142456` | FAILED | Uperf regular-file behavior invalidated FIFO readiness channel; never flash. |
| `20260831134511` | pre-runtime-correction lineage | SUPERSEDED | Boot PASS but kill-switch/null-gap/OEM classification PARTIAL. |
| `20260902080413` | `6894c9f` / `33573565557` | SUPERSEDED | SFAnalysis static/pre-flash correction only; later candidates and Golden supersede it. |
| `20260903085341` | running `adf81d6` candidate | DIAGNOSTIC_ONLY | Read-only diagnostic proved regular-file output contract. |
| `20260903123509` | running `e43fe4c` candidate | DIAGNOSTIC_ONLY | Proved package→transitional-null overwrite root cause; no build/flash. |

Failure causes and permanent gates are retained in canonical docs/tests. The failed candidate
images, redundant unpack trees, raw diagnostics and duplicate archives were removed through
the executed exact cleanup plan after path, byte count, file count and tree SHA-256 checks.
The following current assets are protected regardless of size:

- `D:\3.VScode\Mi\zui072（flash）\out\V20.4_Golden_20260903144915`
- `D:\3.VScode\Mi\zui072（flash）\work\evidence\V20_4_FINAL_CLOSURE_GATE.zip`
- Golden source commit `29f23f8d590b88f0d472c12373366a9ef14e8330`

No pre-Golden failed candidate package remains in the active workspace.
