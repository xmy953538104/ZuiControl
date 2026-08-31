# V20.4 Refresh Correctness Device Test Plan

状态：本文件保留为已执行的acceptance spec。RunId `20260831094239`在刷前被拒绝；`20260831104317`刷入后因ART `VerifyError` Boot Gate FAIL并完成V20.3B恢复；fixed RunId `20260831134511`通过final-artifact ART与Boot Gate，device matrix结论为PARTIAL。权威结果见 [`08_DEVICE_RESULTS.md`](08_DEVICE_RESULTS.md)。
语义基线：foreground-only。所有断言同时记录 raw/current/last/desired/applied/target/physical，不能只看 App UI。

## 1. 稳定 transition 与最终状态采集

- `dumpsys zui_control`
- `dumpsys display` 中 active mode、AppRequest 与 votes
- `settings get system peak_refresh_rate` 与 `min_refresh_rate`
- `getprop persist.zui_control.disable`
- `getprop persist.zui_control.refresh.disable`
- 过滤 `ZuiControl` 的 logcat
- `/data/system/zui_control/profiles.prop`

记录字段：`rawFocusedPackage`、`currentScenePackage`、`lastNonTransientScenePackage`、`desiredScenePackage`、`attemptedScenePackage`、`appliedScenePackage`、`targetDisplayHz`、`attemptedDisplayHz`、`appliedDisplayHz`、`physicalDisplayHz`、AppRequest handoff、priority-8 vote、peak/min、`refreshApplyCount`、`skipSameCount`、`lastApplyReason`、`lastApplyError`。

同时记录 `rawFocusTransient`、`nonImeFocusedPackage`、`windowFocusSeen`、`appRequestHandoffPending` 与 `refreshDisplayScope`，用来区分 Activity fallback、真实 window owner 和异步 handoff。单独 disable/enable 记录 property 写入 T0、callback/reconcile log、首个稳定 dumpsys 与实际延迟；不得靠切 App 才生效。Rapid toggle 在最终 property 保持稳定后连续复采，只验最终收敛，不要求每个极短 intermediate mask 都被 callback 观察。

## 2. Foreground-only 核心矩阵

| Matrix | 操作 | 必须观察到 |
| --- | --- | --- |
| 1 | App A=60 → ZuiControl → App A | `60 → default 120 → 60`；current/last 始终保留 A；无 ZuiControl profile |
| 2 | App B=90 → ZuiControl → QS → ZuiControl → App B | `90 → 120 → 120 → 120 → 90`；QS/ZuiControl 均不成为配置 owner |
| 3 | App B=90 → ZuiControl 中改 B=144 → 返回 B | 编辑期间仍 120，只保存 B；返回 B 后 target/applied/physical=144 |
| 4 | App B=90 → QS 改 B=165 → 收起 | QS 期间仍 120；profile owner=B；返回 B 后 target/applied/physical=165 |
| 5 | App B=90 → 默认 IME open/close | raw=IME 时 default 120；返回 raw=B 后 90；current/last 保持 B |
| 6 | App B=90 → Permission UI、Resolver/Chooser 或 PackageInstaller、常见 overlay → 返回 | 每个真实 transient focus 为 default 120，返回 B 为 90；不产生 transient profile |
| 7 | App B=90 前台时单独设置并保持 refresh disable | 无新 focus也事件驱动收敛；只删除Zui global priority-8、WM handoff被请求、peak仅恢复自身override；记录实际延迟；UDFPS local priority-8不被删除 |
| 8 | App B仍raw foreground时单独清除并保持 refresh disable | 无需切App即事件驱动恢复target/applied=90；记录实际延迟并严格验证physical 90 |
| 9 | App B=90 时设置 global disable | refresh 行为同 Matrix 7；Uperf/asoulOpt/command 状态不被误停 |
| 10 | SystemUI/Permission window 保持前台时背后 Activity 变化，再开关 IME | physical 始终 default 120；IME hide 恢复原 transient window，不误切背后 App profile |
| 11 | Rapid toggle：分别以 enabled 与 disabled 结束 | 最终稳定mask必须等于两个真实property；最终enabled恢复当前raw profile，最终disabled无Zui vote/owned peak且handoff已请求；不要求每个intermediate mask都有记录 |
| 12 | A=90/window=A，Activity metadata先A→B，window仍A | raw/non-IME/desired/target保持A/90，`refreshApplyCount`不增加，apply timeline无intermediate default120；随后window→B才应用B profile |
| 13 | A=90 → SystemUI window/default120，背后Activity A→B | SystemUI/transient/default120保持不变，current/last仍A；window真正→B后才应用B profile |
| 14 | A=90 → Permission/Resolver/vendor overlay window/default120，背后Activity A→B | 每个当前window的transient分类保持，不能被Activity metadata追溯升级；window真正→B后才应用B profile |
| 15 | 两种顺序：Activity B→window B；window B→Activity B | 最终均为B profile、last business=B、无错误scene learning；Activity-first路径无default120 apply，window-first后的Activity只补metadata不重复apply |

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
- 在App 60/90/144/165中的代表场景执行真实指纹authentication/unlock。每次保存`mVotesByDisplay[-1]`的Zui global priority-8和display 0的local `PRIORITY_AUTH_OPTIMIZER_RENDER_FRAME_RATE`前/中/后快照：认证期间两者共存且local覆盖global；认证结束local消失、global仍在、App profile自动恢复；不得stuck 60/120/max。refresh disable只删除`[-1]` global，不删除display-local vote；enable恢复global。若多次真实认证仍无法稳定触发local vote，记录`NOT OBSERVED`与路径边界，不得伪造UDFPS PASS。
- 当前产品为单内屏；外接屏/desktop mode 下 global priority-8 的作用范围必须标为未验证，不扩大本轮测试声明。
- disable 前外部修改 peak：release 不得覆盖外部值。
- disable前后记录display 0三个`PRIORITY_APP_REQUEST_*` vote tuple：WM-only baseline → Zui profile tuple → disable/traversal后回到当前窗口的WM baseline；连续复采以排除迟到traversal。不得使用zero/reset清共享request。
- `appRequestHandoffPending`没有completion callback，不要求该字段自动清零；handoff闭环依据是外部`dumpsys display`回到平台当前窗口真实状态。分别测试稳定disable→enable与rapid toggle最终收敛，host/static PASS不得写成同步完整释放。
- default display 外的虚拟/外接 display 不得驱动本机 raw scene；global p8 对外接屏的作用仍标为未验证。
- 在当前 user 与切换后的 secondary user 各执行至少一次 window-first/Activity-first、profile读写与返回路径；记录 uid/userId tuple。当前 host 只证明单 active-user 模型，跨 user 不得先写 PASS。

## 6. Idle overhead regression

复用V20.3B同口径采集，并分别覆盖refresh enabled/disabled稳定态：

1. 屏幕与场景稳定后idle settle 5分钟；
2. 采集60秒`/proc`，记录system_server总CPU、ZuiControl worker TID CPU/context switch与进程/线程快照；
3. 采集约90秒Perfetto，统计ZuiControl worker sched slices/wakeup、property callback相关activity、DisplayManager不必要apply、Binder/shell/fork/process churn与重复日志；
4. 对照V20.3B worker `0.0000% single-core`基线，但不硬编码必须精确为0；若明显升高，必须定位并解释来源。

通过要求是无polling、无periodic refresh work、无持续property-trigger churn、无无意义DisplayManager apply或shell/process churn。全局property callback执行不只体现在ZuiControl worker，因此system_server总体A/B也必须记录。

## 7. 通过条件

全部矩阵、event-order、profile-file negative check、UDFPS真实路径、AppRequest traversal、idle overhead、ownership与60/90/144/165 physical check通过后，才能把device validation标为PASS。fixed RunId实际没有满足该条件：

| Area | Result |
| --- | --- |
| ART + Boot Hard Gate | PASS |
| foreground-only、五档、IME/Resolver、QS编辑目标、dedup、freeform/split/PiP、profile negative、peak observer、enabled idle、Binder security | PASS with recorded boundaries |
| stable/rapid kill switch | `FAIL_NOT_CONVERGED` |
| App-to-App Activity/window order | `FAIL_INTERMEDIATE_DEFAULT` |
| Lenovo/ZUI vendor overlay classification | `FAIL_CLASSIFICATION` |
| UDFPS local vote | `NOT_OBSERVED`：fingerprint service不存在，biometric Sensors为空 |
| fault injection；kill-switch下游release/reenable；disabled idle | `NOT_EXECUTED` |
| secondary user / external display | `NOT_VALIDATED` |
| PermissionController / PackageInstaller实际路径 | 未完整覆盖 |

```text
V20_4_REFRESH_DEVICE_VALIDATION=PARTIAL
```
