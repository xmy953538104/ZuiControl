# V20.4 Uperf SFAnalysis Runtime Decision

## Decision

Root-cause confidence is **CONFIRMED**. The failed RunId `20260901174600` activated
`modules.sfanalysis.enable=true`; the pinned Uperf binary's `SfAnalysisListener` then watched/read
`/system/bin/surfaceflinger`, and both failing worker PIDs received the exact
`performanced → surfaceflinger_exec:file read` blocking AVC. Production now restores
`sfanalysis=false`; no SurfaceFlinger SELinux permission is added.

New unflashed review candidate: RunId `20260902080413`, source
`6894c9fb4b96493058829be7d91cbec8ed4234b0`, CI `33573565557`.

## Required decision answers

1. **Is the `surfaceflinger_exec` read related to SFAnalysis?** Yes, confirmed by old/current config,
   listener construction, the listener-to-inotify call graph, exact path and matching runtime AVCs.
2. **Why does Uperf need the access?** Only the optional SFAnalysis module needs it to observe/analyze
   SurfaceFlinger and related libraries for render-state hints. The four ZuiControl presets do not.
3. **Final `sfanalysis` value?** `false`.
4. **Why?** It removes the confirmed startup blocker, preserves least privilege and idle simplicity,
   and no device benefit for the module has been proved.
5. **Any new SurfaceFlinger SELinux permissions?** No.
6. **Could access cascade?** Yes. Two libraries are directly in the same static path; PID/proc or
   Binder resources are possible but unproved. The stripped closed-source graph is partial, which is
   another reason not to expand policy.
7. **Are the four v1.0.6 idle fields retained?** Yes: balance `1.0/0.5`, powersave `1.5/0.8`.
8. **Whole-service fail-safe conclusion?** Natural startup storm reached counter 3, fail-safe 1 and
   stopped: `DEVICE PASS`. This does not prove the separate worker-crash 3/20s case.
9. **FIFO conclusion?** `NOT YET PROVEN`; failure occurred before steady-state proof.
10. **Why did qdl read-back not occur?** qdl-rs 0.1.0 only put a verify attribute on program XML,
    wrote bytes and waited for ACK. It never invoked its storage-read path or returned bytes/hashes.
11. **How will the next run prove read-back?** Stay in EDL after programming; `dump-part` each exact
    fixed-seven label/LUN; compare full byte length and SHA-256 to the approved image; reset only
    after seven PASS entries. Temporary dumps are deleted after hashing.
12. **Production runtime diff?** Exactly `modules.sfanalysis.enable: true → false`. No other runtime
    behavior, binary, framework, init lifecycle, Uperf/asoulOpt logic or SELinux policy changed.
13. **Host tests?** 22/22 focused, and 113/113 across all retained suites; exact GitHub CI run passed.
14. **Final-super/CIL/ART?** Reverse extraction, 62-marker and semantic gates PASS. All final
    framework/services DEX payloads and all eight CIL inputs are byte-identical to the preceding
    target-device-verified ART/CIL artifacts, so both permanent gates inherit PASS on exact bytes.
15. **New RunId and hashes?** RunId `20260902080413`; super `69870ac2…e43f7`, boot
    `e7e85b5c…e8371`, vbmeta_system `a4481dc1…a49cab`, vbmeta `c1f4ea68…44f7`.
16. **Ready for human Pre-Flash Gate?** **YES**. This is readiness for review only; no flash is
    authorized or performed.

## Boundaries

```text
READY_FOR_HUMAN_PRE_FLASH_GATE=YES
READY_FOR_FULL_UPERF_DEVICE_VALIDATION=NO
FLASHED=NO
DEVICE_TOUCHED=NO
```

Full evidence and next-gate plan:
[`V20_4_UPERF_SFANALYSIS_RUNTIME_CORRECTION/`](V20_4_UPERF_SFANALYSIS_RUNTIME_CORRECTION/).
