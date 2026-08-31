# V20.4 Build and Final-Super Verification

## Reproducible route

V20.4 使用 [`tests/BuildV20_4Candidate.ps1`](tests/BuildV20_4Candidate.ps1) 薄入口，复用经过 V20.3B 验证的 isolated builder，并强制 `Phase=V20_4`。默认 V20.3B 路径仍保持旧 phase、目录、test 与 verifier，不被 V20.4 改名。

Builder 必须绑定：14 位 RunId、40 位 source commit、GitHub Actions run ID 与下载到本机的 exact CI artifact。它验证 run/head/artifact digest，clone exact commit，重跑 V20.3B+V20.4 host tests，对比 source/CI payload，然后在 isolated official 072副本中应用 payload和 framework patch。

动态分区按既有生产顺序处理：rebuild image → `SignNoFec` explicit official/template → 签名后重新 `PackSuper`。不得用 debug APK替代签名 CI APK，不得复用旧 super。

## Final reverse verifier

[`scripts/VerifyZuiControlFinalSuper.ps1`](../scripts/VerifyZuiControlFinalSuper.ps1) 先完整调用既有 B072 flash-package verifier，保留七项包结构、hash、AVB/footer、APK、SELinux/context与 vendor preservation gate；随后从最终 super反解的真实 services/app smali检查：

- foreground-only desired/attempted/applied/physical 与 editable字段；
- raw transient、non-IME window authority和 default-display-only markers；
- property callback edge queue、bounded release retry、global priority-8、WM handoff pending；
- failure semantics、profileSaved与 peak external-preserve；
- DisplayContent window/IME hook、Hooks class与 App editable target；
- `controlPanel` marker必须不存在。

Windows 无 `secilc`，因此 static/final-super policy检查仍不能替代刷后 Enforcing/AVC验证。

任何修改 `framework.jar`、`services.jar` 或其它boot/system_server classpath DEX的候选，还必须对**final super反解出的最终JAR/DEX**运行ART/dex2oat verifier。apktool rebuild或smali assemble只证明语法/编码可重建，不证明ART控制流类型验证可通过；marker verifier继续负责内容与provenance，不能替代ART gate。host无法提供匹配OEM bootclasspath时，允许在已恢复目标设备上只读使用最终JAR并仅写`/data/local/tmp`临时输出。

## Current result

结果：**CI / isolated ROM build / final-super reverse verifier / final-artifact ART / Boot Hard Gate = PASS；device validation = PARTIAL。**

- Source commit：`3c5cd809d5465828fe14356cbd079d45d00347b7`
- Source patch SHA-256：`0203e00693a177c09c11a7550ea2cd3610b77f641eabbe4348181da6db9aecbf`
- GitHub Actions：run [`33361319072`](https://github.com/xmy953538104/ZuiControl/actions/runs/33361319072)，workflow `Build ZuiControl`，exact head SHA，conclusion `success`
- Artifact：`ZuiControl-release-apk` ID `9746803984`、`zui-control-v19-payload` ID `9746804236`；下载 ZIP 的 length/SHA-256 均与 GitHub API receipt 一致
- Candidate RunId：`20260831134511`
- Candidate：`D:\3.VScode\Mi\work\v20_4_candidate_20260831134511`
- CI/payload APK SHA-256：`86348f7767cf9d0651e226af947c3de60f52a0268adc519fc3a90521f7037e82`
- `super.img` SHA-256：`4f01c64d8a3a5860c34967d944510f3768f4e6748bb843eef0345a5c6685800d`
- `boot.img` SHA-256：`e7e85b5cd2806b8c27adf4925e05ee169072a79a43502effc34c97fb27ee8371`（命中固定 official 072 boot）
- `vbmeta.img` SHA-256：`c1f4ea68ea52bae62e464ddc245dadd740569ba3ac3376ee5d23a40204a744f7`
- `vbmeta_system.img` SHA-256：`fe007d5d1d298d773bc3a41879f9995984491abfd51f4608b3044a5cde549259`
- final-super `services.jar` SHA-256：`389ac1cff8e79705a8dd9e5220f3c70ccef5c84f0681ac3c6c993f81ddcd24ff`

Isolated build在 fresh clone 中重跑 V20.3B policy 5/5 与 V20.4 state model 27/27；framework Java 8/D8、B072 smali injection、EROFS rebuild、实际 `SignNoFec`、签名后的 `PackSuper` 均成功。最终 candidate `super.img` 被重新拆出七个逻辑分区；既有 flash-package verifier返回 `ok=true`，V20.4 verifier返回 `ok=true`、`marker_count=48`。final-super JAR在已恢复目标设备的匹配boot/systemserver classpath下执行ART/dex2oat verify，`DEX_RC=0 / GATE_RC=0`；授权刷入后Boot Gate 19样本/188.563秒PASS。

最小证据：[`build_result.json`](raw/build_20260831134511/build_result.json)、[`ci_run_provenance.json`](raw/build_20260831134511/ci_run_provenance.json)、[`candidate_sha256.txt`](raw/build_20260831134511/candidate_sha256.txt)、[`final_super_verifier.log`](raw/build_20260831134511/final_super_verifier.log)、[`PREFLASH_GATE_FIXED_20260831134511.md`](../../work/v20_4_device_validation_20260831114341/PREFLASH_GATE_FIXED_20260831134511.md)、[`phase2_180s_observation_summary.txt`](../../work/v20_4_device_validation_20260831114341/03_fixed_candidate_flash_20260831_142025/phase2_180s_observation_summary.txt)。构建器记录 `production_b072_unchanged=true`、`flashed=false`；`flashed=false`是构建完成时的receipt，fixed-seven包后来已按单独人工授权刷入。

旧候选 RunId `20260831094239` 因未覆盖Activity/window event-order漏洞被人工Gate拒绝。其后 RunId `20260831104317` 的static/final-super marker gate曾PASS，但实际刷入后`DisplayContent` smali register type merge触发ART `VerifyError`并导致Boot Gate FAIL；设备已恢复V20.3B。诊断见 [`BOOT_GATE_FAILURE_DIAGNOSIS.md`](../../work/v20_4_device_validation_20260831114341/BOOT_GATE_FAILURE_DIAGNOSIS.md)。两者只留审计，不得刷写。

Fixed candidate的Enforcing、physical mode、IME/Resolver、多窗口与enabled idle已取得真机证据；但kill switch、null-window event order和vendor overlay分类失败，UDFPS/fault injection/secondary user/external display等仍有明确边界。权威真机结果见 [`08_DEVICE_RESULTS.md`](08_DEVICE_RESULTS.md)。

FIXED_CANDIDATE_FLASHED=YES
FINAL_ARTIFACT_ART_GATE=PASS
BOOT_HARD_GATE=PASS
DEVICE_VALIDATION=PARTIAL
