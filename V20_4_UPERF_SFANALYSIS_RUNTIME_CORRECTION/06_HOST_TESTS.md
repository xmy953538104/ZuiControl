# 06 — Host Tests

Focused suite: `tests/TestUperfSfanalysisCorrection.py`

Final local and GitHub CI result: **22/22 PASS**.

Coverage includes all 18 required invariants and three additional checks:

1. config/static/runtime SFAnalysis causal fixture;
2. final `sfanalysis=false`;
3. four idle fields unchanged;
4. `proc_uptime` correction unchanged;
5. shell `scheduler_active` access remains removed;
6. fail-safe design unchanged;
7. no SurfaceFlinger allow;
8. no broad proc allow;
9. FIFO polling not restored;
10. top-resumed sources byte-identical;
11. Uperf binary byte-identical;
12. Uperf sched disabled;
13. Native Auto not production owner;
14. Refresh artifact byte-identical;
15. asoulOpt binary byte-identical;
16. qdl flag alone cannot pass;
17. real byte/hash evidence is required and mismatch fails;
18. previous failed runtime fixture remains detectable;
19. all five config module reviews are mandatory;
20. large read-back dumps are removed after hashing;
21. frozen upstream true/current production false state is explicit;
22. the SurfaceFlinger policy rejection is exact to `surfaceflinger_exec` and does not falsely
    reject an unrelated frozen OEM `surfaceflinger` type rule.

The isolated build reran every retained suite:

- V20.3B: **5/5 PASS**;
- Refresh Correctness: **39/39 PASS**;
- Uperf Architecture: **31/31 PASS**;
- Uperf SELinux/Startup Correction: **16/16 PASS**;
- SFAnalysis Runtime Correction: **22/22 PASS**;
- total: **113/113 PASS**.

GitHub Actions run `33573565557` completed `success` at exact head
`6894c9fb4b96493058829be7d91cbec8ed4234b0`. Its three downloaded ZIPs were also matched to the
API-reported length and SHA-256 before use. Exact binding is in
[`raw/build_20260902080413/ci_run_provenance.json`](raw/build_20260902080413/ci_run_provenance.json).
