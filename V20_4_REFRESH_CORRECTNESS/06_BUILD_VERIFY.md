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

## Current result

结果：**CI / isolated ROM build / final-super reverse verifier = PASS；device validation = PENDING。**

- Source commit：`c4f5ad8d57d21508469e72ff5e4b18adcc2e8c65`
- Source patch SHA-256：`1a6cda5cc067fdc68bebdb775940b4a9a42adb28f3dcb0f7e386abcf5afc2928`
- GitHub Actions：run [`33351448572`](https://github.com/xmy953538104/ZuiControl/actions/runs/33351448572)，workflow `Build ZuiControl`，exact head SHA，conclusion `success`
- Artifact：`ZuiControl-release-apk` ID `9743771329`、`zui-control-v19-payload` ID `9743771548`；下载 ZIP 的 length/SHA-256 均与 GitHub API receipt 一致
- Candidate RunId：`20260831104317`
- Candidate：`D:\3.VScode\Mi\work\v20_4_candidate_20260831104317`
- CI/payload APK SHA-256：`3bd2dab7292bab3f0fdcac2c72ea444d14bda3971319a5d5af2bdf40fbec9018`
- `super.img` SHA-256：`059e910359c39f585ed280b623bde0d8d97d6d8dd12b1efdfd2df5281b629757`
- `boot.img` SHA-256：`e7e85b5cd2806b8c27adf4925e05ee169072a79a43502effc34c97fb27ee8371`（命中固定 official 072 boot）
- `vbmeta.img` SHA-256：`c1f4ea68ea52bae62e464ddc245dadd740569ba3ac3376ee5d23a40204a744f7`
- `vbmeta_system.img` SHA-256：`a92596dd1ab7546a8a937b9663524c6b188ac7ddd60e3a840e9d839232b0a9aa`

Isolated build在 fresh clone 中重跑 V20.3B policy 5/5 与 V20.4 state model 27/27；framework Java 8/D8、B072 smali injection、EROFS rebuild、实际 `SignNoFec`、签名后的 `PackSuper` 均成功。最终 candidate `super.img` 被重新拆出七个逻辑分区；既有 flash-package verifier返回 `ok=true`，V20.4 verifier返回 `ok=true`、`marker_count=48`，并明确命中 immutable `ZuiControlService$FocusSnapshot`、Activity/window/non-IME snapshot 与 event-order negative marker。

最小证据：[`build_result.json`](raw/build_20260831104317/build_result.json)、[`ci_run_provenance.json`](raw/build_20260831104317/ci_run_provenance.json)、[`candidate_sha256.txt`](raw/build_20260831104317/candidate_sha256.txt)、[`final_super_verifier.log`](raw/build_20260831104317/final_super_verifier.log)。构建器记录 `production_b072_unchanged=true`、`flashed=false`；未生成或写入 9008 包。

旧候选 RunId `20260831094239` 因未覆盖 Activity/window event-order 漏洞被人工 Gate 拒绝并已删除其 `work` candidate；旧 raw 只留审计，不得刷写。当前 pre-flash correction 的 production diff 只涉及 `ZuiControlService.java`，其余 correction commit 内容为 host test 与 final-super verifier。

Windows 无 `secilc` 且本轮未刷机，因此 Enforcing/AVC、physical mode、IME动画边界和异步 AppRequest handoff仍必须由 device matrix验收。候选可用于该矩阵，但不能据此把 V20.4 device validation写成 PASS。

FLASH_CANDIDATE_READY=YES
DEVICE_VALIDATION=PENDING
