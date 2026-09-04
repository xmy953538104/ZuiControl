# Build and extraction cache audit

Repeated candidates currently redo `lpunpack` of the same 13GB super, EROFS
system/vendor extraction, services.jar apktool decode, CIL extraction and ART
preparation. Candidate directories encode run IDs, not input identity, so equal
inputs cannot safely reuse results.

## Phase 1 disk census

Read-only totals on 2026-09-03:

| Root | Bytes | Approx. GiB | Interpretation |
| --- | ---: | ---: | --- |
| `D:\3.VScode\Mi\flash` | 71,410,955,928 | 66.50 | Contains protected stock/current/Golden flash assets as well as historical candidates; size alone is not deletion authority. |
| `D:\3.VScode\Mi\work` | 5,408,783,836 | 5.04 | Mixed evidence, downloaded CI artifacts, and rebuildable host/extraction outputs. |
| `D:\3.VScode\Mi\【A官方】072` | 16,413,558,242 | 15.29 | Protected official baseline. |
| `D:\3.VScode\Mi\【B刷机】072` | 14,063,878,816 | 13.10 | Protected accepted/canonical packaging input. |

The exact Phase 1 cleanup plan identifies only 10,321,375 bytes of proven
rebuildable probe/host-compile output. It was exercised in DryRun and nothing was
deleted. Large directories were not guessed from age or name. A future archive
pass must first bind every historical candidate to the classification table and
prove that unique evidence and the previous accepted candidate remain.

## Cache key

```text
SHA256(input artifact) + tool identity/version + operation version
```

Each cache entry must contain input SHA, exact tool path/hash/version, operation
schema, output manifest and output hashes. A missing field invalidates the
entry. Cache creation is atomic; partial entries are never used.

| Operation | Classification | Reuse boundary |
| --- | --- | --- |
| super GPT/LP metadata parse | CACHEABLE | Exact super hash + lpunpack hash/version + schema |
| dynamic partition extraction | CACHEABLE | Exact super hash + lpunpack identity + operation schema |
| EROFS system/vendor unpack | CACHEABLE | Exact partition image hash + extract.erofs identity + schema |
| unchanged services.jar extraction/decode | CACHEABLE | Exact jar hash + apktool identity + decode schema |
| unchanged split CIL extraction/preparation | CACHEABLE | Exact CIL inputs + tool identity + schema |
| host source unit tests | MANDATORY_PER_CHANGE | Run for every source/tool change. |
| final-super reverse verification | MANDATORY_PER_CANDIDATE | Must inspect the newly packed final super. |
| framework/services ART/dex2oat verifier | MANDATORY_PER_FRAMEWORK_CHANGE | Cache may prepare tools/unchanged inputs, never substitute the target artifact gate. |
| marker/provenance/hash verifier | MANDATORY_PER_CANDIDATE | Bind exact source/CI/final artifact. |
| CIL/sepolicy compile gate | MANDATORY_PER_POLICY_CHANGE | Cache may supply immutable tools only. |

The plan is documented but not implemented in this phase because wiring it
into production build/verification could affect release artifact selection and
requires its own review.

```text
BUILD_CACHE_PLAN=DESIGNED_NOT_ENABLED
```
