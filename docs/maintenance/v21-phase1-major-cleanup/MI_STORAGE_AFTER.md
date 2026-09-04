# Mi storage after V21 Phase 1 major cleanup

Measured: `2026-09-03T22:11:58+08:00` after migration and deletion, before creating the
small final review ZIP.

```text
BEFORE_BYTES=109843594220
AFTER_BYTES=34316155982
RECLAIMED_BYTES=75527438238
AFTER_FILES=29107
```

The exact deletion receipts total 75,723,521,721 bytes. That number is larger than the
before/after delta because a fresh 195,932,160-byte cache-verification extraction was created
after the before snapshot and then deleted after it proved the retained cache byte-identical.
Small canonical metadata/tooling files created during the task account for the remaining delta.

## Top-level result

| Path | Bytes | Files | State |
| --- | ---: | ---: | --- |
| `zui072（9008）` | 16,413,566,879 | 105 | immutable original + 2 metadata files |
| `zui072（flash）` | 14,361,732,974 | 24 | Golden out + cache + closure/current evidence |
| `Edit tools` | 3,376,955,905 | 21,585 | canonical tools |
| `ZuiControl` | 140,886,170 | 5,407 | existing dirty Git repo exception |
| `work` | 16,180,105 | 1,624 | protected JKS/scene_vtools exception only |
| `worktrees` | 6,813,556 | 348 | active V21 worktree exception |
| `tools` | 7,553 | 5 | protected PEM exception only |
| `Linux` | 7,553 | 5 | protected PEM exception only |
| `avb` | 4,969 | 2 | protected PEM exception only |

`script` is a junction to the Git-tracked V21 worktree `scripts` directory and is intentionally
not counted a second time. The only remaining files over 100 MiB are original ROM assets,
the accepted Golden, the verified reusable cache, or canonical Android SDK/NDK tools.

```text
FAILED_CANDIDATE_LARGE_FILES_RETAINED=0
FAILED_DIAGNOSTIC_LARGE_FILES_RETAINED=0
```
