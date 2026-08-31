# ZuiControl V20.4 Refresh Pre-Flash Correction Decision

日期：2026-08-31

## Decision

V20.4 Work Package 1 的 pre-flash correction 已完成 source、host regression、exact CI、isolated ROM build 与 final-super reverse verification。当前候选具备执行完整 device matrix 的静态资格，但本轮按要求没有刷机；结论是 **READY FOR DEVICE MATRIX**，不是 refresh production/device PASS。

本次修正不重写状态机。唯一 production 文件是 `ZuiControlService.java`：focused window 继续作为 physical raw authority；真实 window signal 出现后，Activity 事件只更新 metadata，不能追溯重分类当前 window 或触发 physical apply。测试与 verifier 随之补强，没有改 command、Uperf/asoulOpt、payload runtime、120 adaptive-render、GPU/thermal 或 V21 compatibility code。

旧候选 RunId `20260831094239` 未覆盖 Activity/window event-order 漏洞，人工 Gate 已拒绝并由本候选替代，**不得刷写**。

```text
SOURCE_IMPLEMENTATION=PASS
HOST_REGRESSION=PASS
CI_ROM_BUILD=PASS
FINAL_SUPER_VERIFIER=PASS
DEVICE_VALIDATION=PENDING
FLASH_CANDIDATE_READY=YES
FLASHED=NO
```

## 15 项回答

1. **foreground-only 语义是否正确：YES（source/host/final-super），device PENDING。** 当前真实 foreground/window package 有用户 profile 时使用其 Hz；未配置业务 App 与 SystemUI、ZuiControl、IME、Permission/Resolver/overlay/null 使用 neutral/default 120。`lastNonTransientScenePackage` 不再向当前物理屏幕继承 Hz。
2. **Activity-first / Window-first event ordering 是否都 PASS：YES（host）。** 两种顺序最终都选择新的 window owner；已有 window 后 Activity 只补 metadata，不重新分类、不重复 apply。当前保证范围是 TB321FU default display / 当前 active user；多用户切换仍需真机。
3. **是否消除了 App A→B 的错误 intermediate default 120：YES（source/host）。** A window 仍 foreground 时，Activity A→B 保持 A profile；只有 window A→B edge 才切到 B profile。host timeline 不产生 intermediate default apply。
4. **SystemUI/overlay 背后 Activity 变化是否保持 neutral 120：YES（source/host）。** SystemUI、Permission/Resolver 与已知 vendor overlay 在真实 window edge 分类后保持 transient/default 120，直到新业务 window 获得 focus。未知 vendor package 仍须在 device matrix 记录并补分类证据。
5. **`controlPanel` 独立 profile owner 是否不存在：YES。** 生产源码没有该特判；final-super verifier把 marker 缺失作为必过条件。ZuiControl 真正 foreground时走与其它 transient相同的 default 120 physical policy。
6. **QS/ZuiControl 是否只修改 last business：YES（source/host）。** editable target 是 `lastNonTransientScenePackage`；transient 前台修改只保存业务 App profile，不创建 transient profile，也不把后台业务 Hz立即应用到当前屏幕。
7. **普通 disable/enable 是否即时：YES（事件驱动 source/host），device timing PENDING。** 单独稳定 disable 与 enable 均由 property callback 唤醒 worker，无需切 App、polling、timer 或 daemon；真实端到端时延及 physical/AppRequest结果必须真机测量。
8. **rapid toggle 是否最终正确收敛：YES（source/host），device PENDING。** callback 参数只作 wakeup hint，worker 重读两个 property 的真实最新 mask；host 覆盖最终 enabled、最终 disabled 与 stale-hint case。不宣称 generic property notification 必达每个极短 intermediate value。
9. **global priority-8 设计是否保持：YES。** ZuiControl 继续使用 global priority-8；平台/UDFPS display-local 同优先级 vote 保持覆盖关系。disable 只删除 Zui global vote，不清 display-local vote。本轮没有重新设计 owner。
10. **UDFPS 真实测试状态：DEVICE PENDING。** 必须在 60/90/144/165 的代表场景观察 local vote 出现/覆盖/消失、global vote 保留、profile 恢复及无 stuck Hz；无法稳定触发时必须记录 `NOT OBSERVED`，不得伪造 PASS。
11. **AppRequest traversal completion 状态：DEVICE PENDING。** `requestTraversalFromDisplayManager()` 只是 handoff request，没有同步 clear 或 completion callback；`handoffPending/releaseRequested` 表述保留。必须由连续 `dumpsys display` 证明回到平台当前窗口真实 AppRequest。
12. **host tests 数量和结果：V20.4 27/27 PASS；V20.3B 5/5 PASS。** 另有 Java 8/D8、Gradle、B072 smali injection/rebuild、Python/PowerShell parser 与 diff gates PASS；host 不能代替 physical Hz、UDFPS 或 traversal completion。
13. **final-super verifier：PASS。** 既有完整 flash-package verifier为 `ok=true`；V20.4 专用 verifier为 `ok=true`、`marker_count=48`，并从签名后、重新 PackSuper 后的最终 `super.img` 反向检查 event-order snapshot 与 negative marker。
14. **新 candidate RunId / SHA-256：RunId `20260831104317`；`super.img` SHA-256 `059e910359c39f585ed280b623bde0d8d97d6d8dd12b1efdfd2df5281b629757`。** Exact source/CI/APK/hash 见下方 provenance。
15. **是否可以刷机执行完整 device matrix：YES，作为测试候选。** 它已通过 source/host/CI/isolated build/final-super gate；这不等于 device PASS。本轮没有自动刷机，等待人工 Gate Review 与明确刷机授权。

## Candidate provenance

- Source commit：`c4f5ad8d57d21508469e72ff5e4b18adcc2e8c65`
- Source correction patch：[`git_diff.patch`](V20_4_REFRESH_CORRECTNESS/git_diff.patch)（zero-context；校验时使用 `git apply --unidiff-zero`）
- Builder-captured source patch：[`candidate_source.patch`](V20_4_REFRESH_CORRECTNESS/raw/build_20260831104317/candidate_source.patch)，SHA-256 `1a6cda5cc067fdc68bebdb775940b4a9a42adb28f3dcb0f7e386abcf5afc2928`
- CI run：[`33351448572`](https://github.com/xmy953538104/ZuiControl/actions/runs/33351448572)，workflow `Build ZuiControl`，exact head SHA，conclusion `success`
- Candidate RunId：`20260831104317`
- Candidate path：`D:\3.VScode\Mi\work\v20_4_candidate_20260831104317`
- APK SHA-256：`3bd2dab7292bab3f0fdcac2c72ea444d14bda3971319a5d5af2bdf40fbec9018`
- `super.img` SHA-256：`059e910359c39f585ed280b623bde0d8d97d6d8dd12b1efdfd2df5281b629757`
- `boot.img` SHA-256：`e7e85b5cd2806b8c27adf4925e05ee169072a79a43502effc34c97fb27ee8371`
- `vbmeta.img` SHA-256：`c1f4ea68ea52bae62e464ddc245dadd740569ba3ac3376ee5d23a40204a744f7`
- `vbmeta_system.img` SHA-256：`a92596dd1ab7546a8a937b9663524c6b188ac7ddd60e3a840e9d839232b0a9aa`
- Build result：[`build_result.json`](V20_4_REFRESH_CORRECTNESS/raw/build_20260831104317/build_result.json)
- CI provenance：[`ci_run_provenance.json`](V20_4_REFRESH_CORRECTNESS/raw/build_20260831104317/ci_run_provenance.json)
- Candidate hashes：[`candidate_sha256.txt`](V20_4_REFRESH_CORRECTNESS/raw/build_20260831104317/candidate_sha256.txt)
- Final verifier：[`final_super_verifier.log`](V20_4_REFRESH_CORRECTNESS/raw/build_20260831104317/final_super_verifier.log)

Builder 记录 `production_b072_unchanged=true`、`flashed=false`；未生成或写入 9008 包。

## Remaining device gates

- Activity-first/window-first 与 SystemUI/ZuiControl/IME/Permission/Resolver/known and unknown vendor overlay 的真实 focus timeline。
- 60/90/144/165 → transient neutral 120 → 返回原 App Hz；target=120 只验证现有 adaptive-render 语义未回归。
- 稳定 disable/enable 时延、rapid toggle 最终 mask、global/local priority-8、UDFPS、shared AppRequest traversal handoff、peak compare/restore 与外部 writer 竞态。
- 5min idle settle + 60s `/proc` + 约 90s Perfetto，确认无 polling、periodic refresh work、property-trigger churn、无意义 DisplayManager apply 或 shell/process churn。
- Enforcing/AVC、physical Display.Mode、IME 动画/硬键盘、default-display/multi-user 边界与 100 次 dedup。

本工作包到此停止；不开始 command latency、Uperf rapid storm、Uperf knob、asoulOpt、120 hard-lock、GPU/thermal、V21 或 V22。
