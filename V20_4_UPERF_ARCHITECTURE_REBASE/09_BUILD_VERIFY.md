# 09 Build and final-artifact verification

## Required chain

The candidate is acceptable only when one exact source commit and one successful CI run pass:

1. host suites and official Android `host_init_verifier`;
2. Java compile and D8 for the service extension;
3. apktool decode plus smali reassembly, including the unique top-resumed hook;
4. isolated payload application, framework patch, PackSuper and AVB signing;
5. reverse extraction of the final `super.img` and marker/provenance/hash verifier;
6. final extracted services DEX ART/dex2oat verification on the recovered target device using only
   `/data/local/tmp`;
7. final extracted CIL compilation with `secilc` using only `/data/local/tmp`.

No source-tree APK, JAR, CIL or init file is accepted as a substitute for the final reverse-
extracted artifact. The target-device gates are verifier-only: they do not install the candidate,
change a partition or start device validation.

## Result

Pre-build local host gates are PASS and recorded in `08_HOST_TESTS.md`. The exact CI run, RunId,
candidate hashes, reverse-verifier result, ART result and CIL result are populated only after the
isolated build completes. Until then this document deliberately records the final artifact gate as
PENDING, not PASS.
