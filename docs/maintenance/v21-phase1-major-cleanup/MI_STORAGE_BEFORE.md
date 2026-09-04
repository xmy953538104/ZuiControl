# Mi storage before V21 Phase 1 major cleanup

Measured: `2026-09-03T21:29:48+08:00`

```text
BEFORE_BYTES=109843594220
BEFORE_FILES=103849
```

## Top-level inventory

| Path | Bytes | Files | Classification |
| --- | ---: | ---: | --- |
| `flash` | 71,410,955,928 | 167 | one Golden + tools to migrate; failed candidates/logs delete |
| `【A官方】072` | 16,413,558,242 | 103 | KEEP_PERMANENT; migrate byte-identically |
| `【B刷机】072` | 14,063,878,816 | 8 | DELETE_DUPLICATE; superseded by exact Golden |
| `work` | 5,408,783,836 | 92,814 | SDK/cache/key exceptions migrate or retain; other scratch delete |
| `重要文件` | 1,268,968,087 | 151 | DELETE_AFTER_KNOWLEDGE_EXTRACTION; legacy 187/old payload copies |
| `ZuiControl` | 435,920,996 | 5,479 | KEEP_PERMANENT Git repo; dirty-repo safety exception |
| `ZuiControl_Archive` | 363,355,053 | 1,751 | DELETE_AFTER_KNOWLEDGE_EXTRACTION |
| `tools` | 169,620,108 | 1,980 | canonical tools migrate; key directory stays in place |
| `072必刷镜像` | 101,695,879 | 4 | KEEP_CACHE; migrate as rollback template with hashes |
| `boot.img` | 100,663,296 | 1 | DELETE_DUPLICATE |
| `Linux` | 49,377,505 | 30 | tools migrate; PEM directory stays in place |
| `worktrees` | 23,028,780 | 1,040 | active V21 worktree retained; obsolete clean worktrees removable |
| `avb` | 19,037,202 | 48 | scripts/tools migrate; PEM directory stays in place |
| `evidence` | 5,782,060 | 105 | DELETE_AFTER_KNOWLEDGE_EXTRACTION |
| Root historical ZIPs | 8,049,058 | 11 | Closure ZIP migrate; superseded diagnostics delete |

Largest files were seven distinct `super.img` entries of 13,958,643,712 bytes each.
Only the immutable original and V20.4 Golden are permanent. Full enumerations are retained as
`top_100_files.tsv` and `top_100_directories.tsv` in the Phase 1 Gate evidence.

## Safety exceptions

The following suspected private material is excluded from all move/delete plans:

- `tools\pem\`
- `avb\tools\pem\`
- `Linux\pem\`
- `work\zuiperfctl-temp-release.jks`
- `work\scene_vtools\app\omoarea-test.jks` (the containing tree is retained)

The existing `ZuiControl` repo is dirty with user changes and remains in place. The active V21
worktree remains separate so Git/worktree metadata is not damaged.
