# 06 — Host Tests

Focused suite: `tests/TestUperfSfanalysisCorrection.py`

Initial local result: **21/21 PASS**.

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
21. frozen upstream true/current production false state is explicit.

The established 31-test Uperf architecture suite and 16-test SELinux/startup suite remain in the
CI/build gate. Final results and CI binding are recorded in `07_BUILD_VERIFY.md` after the isolated
candidate build.
