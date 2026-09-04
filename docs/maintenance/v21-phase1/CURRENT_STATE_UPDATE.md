# Preliminary current-state update

> Historical snapshot before the executed Mi major cleanup. Current results and exact
> receipts are under `../v21-phase1-major-cleanup/`; current project state is authoritative.

## Before Phase 1

The repository contained valid V20.4 closure evidence alongside several failed,
superseded and diagnostic candidates. Some historical documents still described
FIFO readiness/lifetime as though it were the current Uperf contract, and device
gates repeatedly rebuilt fragile inline `su -c` commands.

## After Phase 1

- V20.4 is frozen at source commit
  `29f23f8d590b88f0d472c12373366a9ef14e8330`, Build RunId
  `20260903144915`, and Device Gate RunId `20260903153438`.
- `V20_4_GOLDEN_BASELINE.md` and `.json` are the sole current baseline
  selectors; main entry documents point to them.
- Every known V20.4 candidate is classified as GOLDEN, CLOSED_REFERENCE,
  FAILED, SUPERSEDED or DIAGNOSTIC_ONLY.
- Current Uperf output is a regular file; readiness is startup-only; lifetime is
  subreaper + waitpid; no steady FIFO exists.
- Reusable device helpers replace complex host-composed root shell commands.
- The canonical scene capture parses display-global `ResumedActivity:` and uses
  the protected-property root helper.
- Workspace cleanup was designed as exact-plan, identity-checked, protected-path guarded and
  DryRun by default. Its later execution is recorded by the major-cleanup receipts.
- Build/extraction caching and qdl improvements are plans/host tools only and are
  not wired into the production release path.
- Firehose digest remains NOT_APPROVED.

No production runtime file changed, no ROM was built, and no device was flashed.
V21 Phase 2 has not started.
