# V21 Phase 1 host test results

Date: 2026-09-03 (Asia/Shanghai)

## Canonical Python suites

Runtime: bundled Python 3.12.13.

| Suite | Result | Tests |
| --- | --- | ---: |
| V20.3B daemon retirement policy | PASS | 5 |
| V20.4 refresh correctness | PASS | 39 |
| V20.4 Uperf architecture/current Golden contract | PASS | 31 |
| V20.4 Uperf SELinux/startup access | PASS | 18 |
| V20.4 Uperf SFAnalysis correction | PASS | 22 |
| V20.4 Uperf process-lifetime fixture | PASS, Linux-only fixture skipped on Windows | 0 |
| V20.4 Uperf regular-log fixture | PASS, Linux-only fixture skipped on Windows | 0 |
| V20.4 top-resumed transitional-null | PASS | 2 |

Result: 8/8 suites successful, 117 tests passed, 2 Linux-only suite fixtures
skipped, 0 canonical failures.

The transitional-null fixture emitted a localized Java compiler warning about
the historical source level; compilation and both tests passed.

## Phase 1 PowerShell suites

| Suite | Assertions | Result |
| --- | ---: | --- |
| Device command layer + scene fixture | 4 | PASS |
| Exact workspace cleanup | 3 | PASS |
| qdl progress compaction | 3 | PASS |

Result: 3/3 suites and 10/10 assertions passed.

## Additional gates

- Golden `super.img`, `boot.img`, `vbmeta_system.img`, `vbmeta.img`, and final
  closure ZIP SHA-256: 5/5 exact match.
- Baseline JSON parse/schema/source binding: PASS.
- Entry/README relative-link scan: 6 files, 0 broken links.
- `git diff --check`: PASS.
- Production runtime diff against Golden source commit: 0 files.
- Cleanup DryRun: PASS, six exact targets, 10,321,375 bytes eligible, zero
  bytes removed.

## Historical-only audit

An all-history exploratory run also executed two superseded suites. The V20.3A
command-wakeup snapshot reported three expected source-shape mismatches, and the
old shell-owned ready-marker correction reported one expected mismatch against
the later native-supervisor wrapper. They are preserved as historical evidence,
explicitly excluded from the canonical Golden suite, and were not changed to
manufacture a pass.

```text
HOST_TEST_GATE=PASS
CANONICAL_FAILURES=0
```
