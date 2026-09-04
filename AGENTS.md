# ZuiControl repository instructions

## Scope

This Git repository contains only ZuiControl source, build/CI logic, permanent
regression tests, and minimal developer documentation.

## Safety

- Do not change `app`, `framework_patch`, `native`, `payload`, Uperf, Refresh,
  asoulOpt, SELinux, or init runtime semantics without an explicit production
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
