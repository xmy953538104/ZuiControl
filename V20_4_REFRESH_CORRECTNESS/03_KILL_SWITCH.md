# V20.4 Refresh Kill Switch

## Device status — FAIL_NOT_CONVERGED

RunId `20260831134511` 的真机结果没有达到下述source contract：

- `persist.zui_control.refresh.disable=1` 外部读回稳定，但 30/30 样本直到 `1360.697ms` 仍为 `refreshDisabled=false`、mask 0、render/peak/AppRequest owned；继续等待总计超过6秒仍无变化。
- `persist.zui_control.disable=1` 的 40/40 样本直到 `5148.825ms` 同样保持 mask 0/false/owned。
- rapid toggle最终稳定disabled后在 `5094.098ms` 仍为 mask0、disabled false、render owned。
- 两项property均已恢复0；Uperf/asoulOpt全程running，没有被refresh开关误停。

`refresh_disable_latency_summary.txt` 的 `first_observed_disabled_ms=0` 是parser把null错误转换为0，**不得引用**。由于service从未进入disabled，AppRequest/vote/peak release、enable rebuild、external-CAS-on-disable与disabled idle都是downstream `NOT_EXECUTED`，不是PASS，也不能按未到达路径写成独立FAIL。原始证据见 [`refresh_disable_latency.csv`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m8_kill_switch/refresh_disable_latency.csv)、[`global_disable_no_convergence.csv`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m8_kill_switch/global_disable_no_convergence.csv) 和 [`rapid_toggle_ending_disabled.csv`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m8_kill_switch/rapid_toggle_ending_disabled.csv)。

## Source contract

`persist.zui_control.disable` 与 `persist.zui_control.refresh.disable` 合并成 bit mask：bit 0 是 global disable，bit 1 是 refresh disable。任意非零值只关闭 refresh owner；本工作包不停止 Uperf、asoulOpt 或 command/control plane。

`SystemProperties.addChangeCallback()`在property callback中读取两个布尔值形成wakeup hint并post到ZuiControl HandlerThread；worker消费时重读两个真实property，避免较旧callback快照在跨线程乱序时覆盖最终truth。注册后立即补读一次，关闭constructor read/register窗口。单独稳定的disable与enable必须各自无需切App而事件驱动生效；generic property notification不承诺观察极短`0 → 2 → 0`中的每一个intermediate mask，rapid toggle只要求最终收敛到两个property的真实最新mask。没有polling、timer loop、daemon或App foreground owner。

## Disable edge

顺序固定为：

1. 标记 refresh disabled，使新的 focus/profile event 只能更新 desired/target，不能继续 platform apply；apply path 还会直接读取真实 property mask，覆盖 callback 尚未消费的短窗口。
2. 删除 ZuiControl 的 global priority-8 render vote。per-display priority 8 属于 UDFPS 等平台 owner，不触碰。
3. 对 peak bridge 做 compare-and-restore：只有 current raw value 仍等于 ZuiControl last-written value 时才恢复 baseline；外部已修改则保留外部值。永不写 `min_refresh_rate`。
4. 对 shared AppRequest 调用 `WindowManagerInternal.requestTraversalFromDisplayManager()`，让 WindowManager 重发当前窗口请求。不得写 zero/reset request。
5. 清空无法继续证明的 applied state；失败保留 `lastApplyError` 与各 ownership/release status。

render/peak/handoff 任一同步步骤失败时，只安排一个 bounded immediate retry；之后仍失败则保持 partial diagnostics，绝不新增循环。mask 在 disabled 状态从 1↔3/2 变化，或后续真实 focus/profile event 到达时，可各自重新安排一次 bounded retry。

## AppRequest honesty boundary

ROM API 只排队 traversal，没有 completion callback。`appRequestHandoffPending=true`、`appRequestHandoff=requested:*` 与 `lastApplyReason=*:releaseRequested` 只表示 handoff 已请求，不宣称共享 AppRequest 已同步清除。最终释放必须在真机用 `dumpsys display` 证明。

快速disable→enable时，迟到的WM traversal可能覆盖shared AppRequest；ZuiControl global render vote只能提供render-rate约束，不能证明AppRequest base mode或physical target已经恢复。最终状态必须由device matrix复采`dumpsys display`验证。

## Enable edge

mask 回到 0 后，只有 latest focus snapshot 与 worker raw state 一致才立即 reconcile；若 focus event 尚在队列中，投递一次队尾 reconcile，由 focus event先更新 raw。当前 raw 是业务 App则恢复其 profile；当前 raw 是 SystemUI/ZuiControl/IME/unknown window/null则应用 neutral/default 120。无需用户切 App。

Rapid toggle分别覆盖最终enabled与最终disabled；只断言稳定后的真实property mask、owner、desired/applied/physical与AppRequest稳态，不要求每个极短intermediate mask都留下callback reason或计数。

## Fixed-ROM ownership limits

- global priority 8 没有 owner token；当前 072 ROM 反查无其它 global-p8 writer，且 UDFPS per-display vote优先于 global fallback。此结论不外推到其它 ROM。
- peak Settings 没有 CAS；compare/read 与 restore write之间仍有不可消除的 TOCTOU 窗口，同值外部 takeover 也无法识别。
- global vote作用全部 display。本状态机只接受 default display focus/mode；外接屏/desktop mode未验证。
