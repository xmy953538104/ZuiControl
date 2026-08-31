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

正式 CI/ROM build与 final-super reverse verifier结果将在 source commit固定后写入本节和 `raw/build_<RunId>/`。在此之前只能标为 `BUILD_PENDING`，不得写成可刷 PASS。
