# 07 — Build and Final Artifact Verification

## Exact source and CI

- source commit: `6894c9fb4b96493058829be7d91cbec8ed4234b0`;
- source patch SHA-256: `6687c1385ba06bb8541d3d36e36f8547aa10beb0e2372aaecf44d97a40b76502`;
- review `git_diff.patch` SHA-256: `e176365e1679b40db30bdab7d9dc180c8eef3f9516360c2ecaef947d8f1edf01`
  (implementation/test/tooling only, base `511f314` through source `6894c9f`);
- GitHub Actions run: `33573565557`, exact head SHA, `completed/success`;
- CI release APK SHA-256: `5b89f961f70c180e4a32a16f06d4d750d32fad166d706d7196b865161bfd3797`;
- host suites: **113/113 PASS**; host init gate: **PASS**.

## Build attempts

RunId `20260902074419` reached final-super verification but stopped on a verifier false positive:
an over-broad pattern confused an unrelated frozen OEM `surfaceflinger:file` rule with the forbidden
`surfaceflinger_exec:file` grant. No flash package was approved, and its generated candidate/scratch
was removed. The verifier was narrowed to the exact executable type and covered by test 22.

RunId `20260902080413` is the successful isolated build. It rebuilt only `system_a` and `vendor_a`,
preserved the protected B072 source package, and did not invoke adb, EDL, qdl or any device write.

## Final-super gates

- reverse extraction: **PASS**;
- base final package verifier: **PASS**;
- V20.4 phase verifier: **PASS**, marker count `62`;
- final Uperf semantic access graph: **PASS**;
- final config: `sfanalysis=false`, `sched=false`, input/sysfs active, canonical switcher;
- no new `performanced → surfaceflinger_exec` permission;
- Uperf binary `f1265757…49d8` and asoulOpt binary `7a2ee5d6…ec86` unchanged;
- framework/top-resumed and Refresh DEX content unchanged.

The current `framework.jar` and `services.jar` ZIP containers differ from RunId `20260901174600`
because of non-DEX container metadata/order, but every final DEX payload is byte-identical. The
reference candidate passed target-device dex2oat (`DEX_RC=0`, `GATE_RC=0`) with stable
system_server PID. Therefore the current final ART gate is inherited **PASS** from exact DEX-byte
equivalence, not from JAR-container equality. Evidence:
[`raw/final_equivalence_20260902080413/art_dex_equivalence.txt`](raw/final_equivalence_20260902080413/art_dex_equivalence.txt).

All eight final split-CIL inputs are also byte-identical to RunId `20260901174600`, whose target
device `secilc` gate passed (`SECILC_RC=0`, `GATE_RC=0`, compiled policy 1,256,804 bytes). The current
final CIL gate is inherited **PASS** on that exact input basis. Evidence:
[`raw/final_equivalence_20260902080413/cil_equivalence.txt`](raw/final_equivalence_20260902080413/cil_equivalence.txt).

## Candidate and fixed-seven package

Canonical unflashed package:
`D:\3.VScode\Mi\flash\ZuiControl_9008_V20_4_UPERF_SFANALYSIS_20260902080413`

| Image | SHA-256 |
|---|---|
| `super.img` | `69870ac20222b3433efba7b46eefbd31b775e82cf29bd85528704dc0063e43f7` |
| `boot.img` | `e7e85b5cd2806b8c27adf4925e05ee169072a79a43502effc34c97fb27ee8371` |
| `vbmeta_system.img` | `a4481dc181dccc13342682fd5f15dc36ac87b6080eded92445a292d4d5a49cab` |
| `vbmeta.img` | `c1f4ea68ea52bae62e464ddc245dadd740569ba3ac3376ee5d23a40204a744f7` |

The XML is exactly `super`, `vbmeta_system_a/b`, `boot_a/b`, `vbmeta_a/b`, on LUNs
`0,0,0,4,4,4,4`. It contains no GPT, userdata, persist, FRP, modemst, patch XML, erase or
allowlist-external write. Receipt:
[`raw/final_equivalence_20260902080413/fixed_seven_package_receipt.txt`](raw/final_equivalence_20260902080413/fixed_seven_package_receipt.txt).

Physical partition read-back is correctly **PENDING FLASH**. A pre-flash build cannot claim it.
After an authorized write, the flasher must remain in EDL, `dump-part` all seven targets, compare
full length and SHA-256, and reset only after the seven-entry manifest says PASS. The old qdl flag
alone remains `NOT_PROVEN`.

```text
PRODUCTION_B072_UNCHANGED=YES
DEVICE_TOUCHED=NO
FLASHED=NO
READY_FOR_HUMAN_PRE_FLASH_GATE=YES
```
