# ZuiControl repository instructions

## Scope

This Git repository contains only ZuiControl source, build/CI logic, permanent
regression tests, and minimal developer documentation.

## Safety

- Do not change `app`, `framework_patch`, `native`, `payload`, Uperf, Refresh,
  thread scheduler, SELinux, or init runtime semantics without an explicit production
  work package and its required gates.
- Do not commit ROM images, generated artifacts, device evidence, Gate packages,
  candidate outputs, project history, AI handoffs, secrets, or local tooling.
- Do not rewrite Git history or select a candidate by recency.

## Canonical checks

Run the host tests listed in `README.md`, plus:

```text
gradle -p . :app:testDebugUnitTest :app:lintDebug
git diff --check
```

CI uses only `.github/workflows/build.yml` and the version-neutral `tests/`
layout.

## Local project context

Current architecture, Golden identity, evidence index, history, and AI handoff
are intentionally outside Git at `..\Project knowledge\README.md`. Read that
index only when the local Mi workspace is available; do not recreate project
memory inside this repository.

## CODEX WORK PACKAGE DISCIPLINE

- Read the authoritative files under the workspace's `Project knowledge/current/`:
  `CURRENT_ARCHITECTURE.md` (owner boundaries/invariants),
  `PROJECT_GOVERNANCE_AUDIT.md` (source/CI/Golden/evidence/branch governance),
  `CURRENT_PROJECT_STATE.md` (sole current operational state), and
  `CODEX_REPORT_SCHEMA.md` (final output only); read `ai/AI_HANDOFF_CURRENT.md`
  when present. Resolve these from the workspace, not a nested worktree's parent.
- Future prompts normally contain only Owner Decision, Delta/Scope, task-specific
  STOP conditions and results. Inherit unchanged permanent rules by reference.
  Explicit current Owner decisions authorize scoped deltas, never silent safety
  overrides. STOP on unauthorized scope expansion or actual source/device/evidence
  contradicting current state; report the contradiction, do not repair identity.
- Incremental/Slim Gate: changed component -> targeted proof. Do not reopen closed
  historical gates without direct evidence; historical receipts are immutable.
- Production Android shell parsing changes require
  `ANDROID_MKSH_COMPATIBILITY_GATE` on real `/system/bin/sh`, derived from the
  exact production parser, before ROM build; host shell success is insufficient.
- Final chat must directly print actual values for the report schema and all
  task-specific fields, including FAIL/HOLD. A report-file link alone is insufficient.
