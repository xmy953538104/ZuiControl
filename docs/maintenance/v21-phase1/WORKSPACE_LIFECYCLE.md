# Workspace lifecycle

Managed root: `D:\3.VScode\Mi\zui072（flash）\work\`.

| Directory | Purpose | Automatic deletion |
| --- | --- | --- |
| `cache/` | hash-keyed rebuildable extraction caches | Exact approved plan only |
| `current/` | current reviewed work pointer | Never by cleanup |
| `evidence/` | cleanup receipts and unique evidence | Never |
| `temp/` | one-run temporary data | Exact approved plan only |

`scripts/cleanup/Cleanup-ZuiControlWorkspace.ps1` is DryRun by default. It accepts a
JSON plan containing exact absolute paths, category, reason, approved flag,
expected bytes, file count and tree SHA-256. Runtime directory scanning does not
guess targets. Any identity drift aborts the entire run.

Protected paths include repository/worktrees, `zui072（9008）`,
`zui072（flash）\out`, `Edit tools`, the canonical `script` junction, key/cert
material, `current`, `cache` and `evidence`. Automatic cleanup is confined to
`work\temp`; an obsolete current task requires a separate exact reviewed plan.
An execute receipt records every exact path, identity and reclaimed bytes.

Gate completion may execute only a reviewed plan for that gate's own scratch.
No automatic age/name heuristic is allowed.

The one-time Phase 1 migration/deletion used the same identity-bound two-stage contract and
has a separate execute receipt under `../v21-phase1-major-cleanup/`. That broad migration mode
is not the normal cleanup default.

```text
WORKSPACE_CLEANUP_AUTOMATED=PASS_DRYRUN_EXACT_PLAN
```
