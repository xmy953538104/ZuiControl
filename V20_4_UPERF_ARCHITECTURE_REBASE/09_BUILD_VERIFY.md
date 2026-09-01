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

All required pre-flash gates are complete for one exact lineage:

| Item | Result |
|---|---|
| Source commit | `72fd3ef5ab3d5d6a2b477a9ba2781ee9503d2d30` |
| Source patch SHA-256 | `b239f130d533466b7f4bb6f48b272f298afc47690030cd180a4ff2895be824b2` |
| GitHub Actions | `Build ZuiControl` run `33468476491`, exact head SHA, `success` |
| CI APK SHA-256 | `63296f816f65bc3f5ecd320083615ebb2168f7d50cac2255ea89c430a3187244` |
| Official Android 14 init verifier | PASS; exit `0`; verifier SHA-256 `b89a6d6351621a183b615e371f0390c38056644b640a8e829c4f4900379ec2e6` |
| Candidate RunId | `20260901120647` |
| Final-super reverse verifier | PASS; base `ok=true`; V20.4 `ok=true`; marker count `62` |
| Final extracted `services.jar` | SHA-256 `f7575f5ca50fdba040e814229063beecf99203b9d25fc117401268c62b2c82fd` |
| Target ART/dex2oat verifier | PASS; `DEX_RC=0`; `GATE_RC=0`; empty stdout/stderr |
| Final CIL compile | PASS; eight host/device input hashes match; `SECILC_RC=0`; `GATE_RC=0`; empty stderr |
| Production B072 source | unchanged |
| Flash/device validation | `flashed=false`; not started |

Candidate directory:
`D:\3.VScode\Mi\work\v20_4_uperf_candidate_20260901120647`

| Candidate file | SHA-256 |
|---|---|
| `super.img` | `4eab12c796eba74f98db7a851cdeb24687077c97c70f6eab7045ea2c70608a06` |
| `boot.img` | `e7e85b5cd2806b8c27adf4925e05ee169072a79a43502effc34c97fb27ee8371` |
| `vbmeta_system.img` | `95a0154d62e8170b89212b665a620f80ab6bc51b65ca025216740a650cb757c3` |
| `vbmeta.img` | `c1f4ea68ea52bae62e464ddc245dadd740569ba3ac3376ee5d23a40204a744f7` |

The final reverse verifier reports Uperf SHA-256
`f1265757009ff0c85dd8587d9e7bfcf5e51d10d36fe5e1341688215ae1fb49d8`, unchanged as designed;
AsoulOpt SHA-256 is
`7a2ee5d67ba7c057066176334eca9256e376427916429d66b7593cbb5538ec86` and its production logic is
unchanged. Vendor APK inventory remains 17 files with concatenated inventory SHA-256
`9d822d5897bbd8c39027821c3b4912401bd073e4ae7e43e36dfc0803d56a2153`.

## Evidence and verifier-only device use

- Build receipt and complete reverse log: `raw/build_20260901120647/`.
- Correct-hash independent KeepWork reverse log and ART evidence:
  `raw/final_artifact_gate_20260901120647/`.
- Final CIL manifests, ordinary-shell permission-denied attempt, and successful root-domain compile:
  `raw/final_policy_gate_20260901120647/`.

The first manual KeepWork invocation used a mistyped 65-character expected vendor SHA and was
correctly rejected before marker verification; its log and explanation are retained as
`final_super_keepwork_bad_expected_hash.log` and `KEEPWORK_ATTEMPT_NOTE.md`. The definitive rerun used the actual 64-character
vendor image SHA-256
`b932ab49e7bc5916b710675bd1ebfa35ba58b978aa55f893564b1cd82caffeae` and passed completely.

The target device was used only as an existing-runtime verifier. Candidate files and CIL inputs
were copied to unique `/data/local/tmp` directories, outputs stayed there, and both directories
were removed afterward. No APK/JAR was installed, no partition was written, no service was
restarted, and no device functional validation was performed. `system_server` stayed PID `2714`,
boot remained complete, Binder remained published, and SELinux remained Enforcing.

```text
V20_4_UPERF_SOURCE_HOST=PASS
V20_4_UPERF_FINAL_ARTIFACT=PASS
V20_4_UPERF_PRE_FLASH_READY=YES
V20_4_UPERF_FLASHED=NO
V20_4_UPERF_DEVICE_VALIDATION=NOT_STARTED
```
