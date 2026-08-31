# V20.4 Refresh Correctness Device Test Plan

状态：candidate 生成后执行；本工作包不自动刷机。
语义基线：foreground-only。所有断言同时记录 raw/current/last/desired/applied/target/physical，不能只看 App UI。

## 1. 每个 edge 的采集

- `dumpsys zui_control`
- `dumpsys display` 中 active mode、AppRequest 与 votes
- `settings get system peak_refresh_rate` 与 `min_refresh_rate`
- `getprop persist.zui_control.disable`
- `getprop persist.zui_control.refresh.disable`
- 过滤 `ZuiControl` 的 logcat
- `/data/system/zui_control/profiles.prop`

记录字段：`rawFocusedPackage`、`currentScenePackage`、`lastNonTransientScenePackage`、`desiredScenePackage`、`attemptedScenePackage`、`appliedScenePackage`、`targetDisplayHz`、`attemptedDisplayHz`、`appliedDisplayHz`、`physicalDisplayHz`、AppRequest handoff、priority-8 vote、peak/min、`refreshApplyCount`、`skipSameCount`、`lastApplyReason`、`lastApplyError`。

同时记录 `rawFocusTransient`、`nonImeFocusedPackage`、`windowFocusSeen`、`appRequestHandoffPending` 与 `refreshDisplayScope`，用来区分 Activity fallback、真实 window owner 和异步 handoff。

## 2. Foreground-only 核心矩阵

| Matrix | 操作 | 必须观察到 |
| --- | --- | --- |
| 1 | App A=60 → ZuiControl → App A | `60 → default 120 → 60`；current/last 始终保留 A；无 ZuiControl profile |
| 2 | App B=90 → ZuiControl → QS → ZuiControl → App B | `90 → 120 → 120 → 120 → 90`；QS/ZuiControl 均不成为配置 owner |
| 3 | App B=90 → ZuiControl 中改 B=144 → 返回 B | 编辑期间仍 120，只保存 B；返回 B 后 target/applied/physical=144 |
| 4 | App B=90 → QS 改 B=165 → 收起 | QS 期间仍 120；profile owner=B；返回 B 后 target/applied/physical=165 |
| 5 | App B=90 → 默认 IME open/close | raw=IME 时 default 120；返回 raw=B 后 90；current/last 保持 B |
| 6 | App B=90 → Permission UI、Resolver/Chooser 或 PackageInstaller、常见 overlay → 返回 | 每个真实 transient focus 为 default 120，返回 B 为 90；不产生 transient profile |
| 7 | App B=90 前台时设置 refresh disable | 无新 focus 也触发 edge；只删除 Zui global priority-8、WM handoff 被请求、peak 仅恢复自身 override；UDFPS local priority-8 不被删除 |
| 8 | App B 仍 raw foreground时清除 refresh disable | 无需切 App，立即 target/applied=90；physical 严格验证 90 |
| 9 | App B=90 时设置 global disable | refresh 行为同 Matrix 7；Uperf/asoulOpt/command 状态不被误停 |
| 10 | SystemUI/Permission window 保持前台时背后 Activity 变化，再开关 IME | physical 始终 default 120；IME hide 恢复原 transient window，不误切背后 App profile |
| 11 | 快速 refresh disable→enable | 两个 property mask edge 均有记录；disable release request 不得被最终 enabled mask 折叠 |

## 3. 144/165 与 120 边界

- A=144 → ZuiControl 120 → QS 120 → A 144。
- A=165 → ZuiControl 120 → QS 120 → A 165。
- 144/165 必须检查 peak compatibility bridge；它不能成为第二 refresh owner。
- target=120 只验证 request/state machine 未回归；静止时 physical 可能降至 60，不能判为本工作包失败。
- 60/90/144/165 应严格核对 physical Display.Mode。

## 4. Dedup / churn

- 对同一 raw focus 重放 100 次 hook：`refreshApplyCount` 不增，`skipSameCount` 增；DisplayManager/peak 不重复写。
- App A=120 与 ZuiControl/default=120 往返 100 次：允许 scene/raw diagnostics 更新，但 platform apply 应接近 0。
- App A=90 与 ZuiControl/default=120 往返 100 次：预期约每个真实 edge 一次 apply；不得再使用旧 inheritance 的“接近 0 apply”标准。
- App A=90 与 QS/default=120 同理；每个进入/返回 edge 是产品要求的真实 target change。

## 5. Failure 与 ownership 注入

- 用 host/fault build 模拟 mode lookup、peak write、DisplayManager call failure：desired 可变化，applied 不得预写成成功，`lastApplyError` 必须非空。
- 在 UDFPS 可写 per-display priority 8 的场景检查：local vote 覆盖 Zui global vote；disable 只清 global，不清 local；enable 后 global fallback 恢复。
- 当前产品为单内屏；外接屏/desktop mode 下 global priority-8 的作用范围必须标为未验证，不扩大本轮测试声明。
- disable 前外部修改 peak：release 不得覆盖外部值。
- disable 后确认 WindowManager 在 traversal 中重发当前窗口 AppRequest；不得使用 zero/reset 清共享 request。
- `appRequestHandoffPending` 只能在 device evidence 证明 traversal 后闭环；host/static PASS 不得写成同步完整释放。
- default display 外的虚拟/外接 display 不得驱动本机 raw scene；global p8 对外接屏的作用仍标为未验证。

## 6. 通过条件

全部矩阵、profile-file negative check、ownership check、60/90/144/165 physical check 通过后，才能把 device validation 标为 PASS。在此之前 host/build/final-super PASS 只表示候选可刷测试，不表示真机 refresh correctness 已验收。
