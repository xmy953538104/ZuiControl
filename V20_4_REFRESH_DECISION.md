# ZuiControl V20.4 Refresh Correctness Decision

日期：2026-08-31

## Decision

V20.4 Work Package 1 已完成 source implementation、host regression、exact CI、isolated ROM build 与 final-super reverse verification。候选可刷入执行 device matrix，但本轮按要求没有刷机；因此结论是 **READY FOR DEVICE MATRIX**，不是 refresh production/device PASS。

2026-08-31 的产品语义纠正覆盖早期 transient-inheritance要求：physical refresh只由当前真实 foreground/window focus决定。业务 App有 profile时使用该 Hz；SystemUI、ZuiControl、IME、Permission UI、Resolver/Chooser、overlay或未知焦点使用 neutral/default 120。`lastNonTransientScenePackage`只作为 QS/ZuiControl的 configuration target，不再向当前物理屏幕继承 Hz。

SOURCE_IMPLEMENTATION=PASS
HOST_REGRESSION=PASS
CI_ROM_BUILD=PASS
FINAL_SUPER_VERIFIER=PASS
DEVICE_VALIDATION=PENDING
FLASH_CANDIDATE_READY=YES

## 14 项回答

1. **ZuiControl 是否已真正 transient：YES（source/final-super），device PENDING。** ZuiControl真正 foreground时 physical desired为 neutral/default 120；它不覆盖 last business configuration target，也不继承上一 App Hz。
2. **`controlPanel` refresh特判是否已删除/失效：YES。** 生产源码已删除该分支，final-super verifier要求 `controlPanel` marker不存在。
3. **QS 是否始终操作真实业务 scene：YES（source/host）。** QS/QuickService读取 editable target，即 `lastNonTransientScenePackage`；SystemUI前台时 physical仍为 120，修改只保存业务 App profile，待该 App回到 foreground才应用。
4. **SystemUI/ZuiControl profile 是否不再被学习：YES（source/host）。** 已知 transient显式写入被拒绝，legacy transient entries加载时跳过；不得创建这两个 package的业务 profile。
5. **kill switch 是否事件驱动即时生效：YES（设计与 host），device timing PENDING。** `SystemProperties.addChangeCallback`捕获每个 distinct mask edge并投递 HandlerThread，不依赖切 App或轮询；真实设备的 edge latency仍需测量。
6. **disable 是否完整释放 ZuiControl 自己的 vote/AppRequest/peak：PARTIAL / DEVICE GATE。** ZuiControl priority-8 vote会定向清理，owned peak采用 compare/restore；shared `setDisplayProperties()` AppRequest没有 owner token或同步 clear API，只能请求 WindowManager traversal并报告 `releaseRequested` / `appRequestHandoffPending`。源码没有虚报同步完成，真机必须确认 handoff后的最终 display state。
7. **enable 是否无需切 App即恢复：YES（source/host），device PENDING。** enable edge以 atomic latest focus snapshot核对 raw focus并立即重算，不要求用户切换 App。
8. **desired/applied/physical 是否已正确区分：YES。** 另有 attempted状态；target、attempted、applied与panel physical均分别发布。
9. **apply failure 是否不会污染 applied state：YES（host/final-super）。** attempted只在平台调用前更新，applied只在完整成功或严格同目标 dedup后更新；partial mutation失败会清理并把不确定 applied状态清空。AtomicFile保存失败回滚内存 profile。
10. **60/90/120/144/165 host regression 是否 PASS：YES，19/19。** unsupported Hz、非 120往返与 120 adaptive语义均纳入 host model；physical mode仍需真机。
11. **是否新增 polling/daemon：NO。** 只增加 WM/property event；无 timer loop、persistent daemon、Accessibility或 App refresh owner。
12. **production diff 是否严格限制在 refresh correctness：YES。** 生产改动只涉及 App refresh读取/编辑目标、system_server refresh service/hooks、framework patcher及所需 stub；没有修改 payload runtime、command transaction、Uperf/asoulOpt策略、120 hard-lock、GPU/KGSL、thermal或 V21历史兼容代码。其余改动为测试、构建、verifier与文档。
13. **final super verifier 是否 PASS：YES。** 既有完整 flash-package verifier为 `ok=true`；V20.4专用 verifier为 `ok=true`、`marker_count=44`，并显式验证 `ZuiControlService$FocusSnapshot`与字段。
14. **是否可以刷机执行 device matrix：YES。** 候选已绑定 exact source/CI、签名并通过 final-super gate；它只获得“可进入 device matrix”的资格，不代表 device validation已通过。本轮未刷机。

## Candidate provenance

- Source commit：`3865cf9c99cb89a8df2b705b9b3dbb2711b311ec`
- CI run：[`33348269219`](https://github.com/xmy953538104/ZuiControl/actions/runs/33348269219)，exact head SHA，conclusion `success`
- Candidate RunId：`20260831094239`
- Path：`D:\3.VScode\Mi\work\v20_4_candidate_20260831094239`
- APK SHA-256：`ad11f1525949d7392e458a2a68498a999212973fb4438c4bdcfa65a7fcb2e29b`
- `super.img` SHA-256：`9774b6aa8e72b5dc6c0514c366786da453bf509106a09946be9356969e43d3d9`
- `boot.img` SHA-256：`e7e85b5cd2806b8c27adf4925e05ee169072a79a43502effc34c97fb27ee8371`
- Build result：[`build_result.json`](V20_4_REFRESH_CORRECTNESS/raw/build_20260831094239/build_result.json)
- Hashes：[`candidate_sha256.txt`](V20_4_REFRESH_CORRECTNESS/raw/build_20260831094239/candidate_sha256.txt)
- Final verifier：[`final_super_verifier.log`](V20_4_REFRESH_CORRECTNESS/raw/build_20260831094239/final_super_verifier.log)

## Remaining device gates

- 60/90/144/165 → SystemUI/ZuiControl/IME/Permission/Resolver neutral 120 → 返回原 App Hz；120只确认未改变 adaptive-render语义。
- QS/ZuiControl前台编辑只保存 last business profile，当前 physical不跟随后台业务 Hz；返回业务 App再应用。
- disable/enable属性 edge时延、priority-8 vote、shared AppRequest traversal handoff、peak compare/restore与外部 writer竞态。
- IME动画取消/硬键盘、unknown focus、default-display-only边界、100次 dedup与 apply/skip计数。
- Enforcing/AVC、SELinux split-policy启动编译、最终 physical Display.Mode与完整 dumpsys/logcat证据。

本工作包到此停止；不开始 command latency、Uperf rapid storm、Uperf knob、asoulOpt、120 hard-lock、GPU/thermal、V21或 V22。
