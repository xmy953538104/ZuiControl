# V20.4 Refresh Kill Switch

## Contract

`persist.zui_control.disable` 与 `persist.zui_control.refresh.disable` 合并成 bit mask：bit 0 是 global disable，bit 1 是 refresh disable。任意非零值只关闭 refresh owner；本工作包不停止 Uperf、asoulOpt 或 command/control plane。

`SystemProperties.addChangeCallback()` 在 property callback 中只读取两个布尔值。每个不同 mask 的快照按顺序 post 到 ZuiControl HandlerThread，避免快速 `0 → 2 → 0` 被折叠成最终 `0`。注册后立即补读一次，关闭 constructor read/register 窗口。没有 polling、timer loop、daemon 或 App foreground owner。

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

快速 disable→enable 时，迟到的 WM traversal 可能覆盖 shared AppRequest；ZuiControl 的 global render vote仍维持 refresh target，但这不能替代 device matrix 对 AppRequest 的验证。

## Enable edge

mask 回到 0 后，只有 latest focus snapshot 与 worker raw state 一致才立即 reconcile；若 focus event 尚在队列中，投递一次队尾 reconcile，由 focus event先更新 raw。当前 raw 是业务 App则恢复其 profile；当前 raw 是 SystemUI/ZuiControl/IME/unknown window/null则应用 neutral/default 120。无需用户切 App。

## Fixed-ROM ownership limits

- global priority 8 没有 owner token；当前 072 ROM 反查无其它 global-p8 writer，且 UDFPS per-display vote优先于 global fallback。此结论不外推到其它 ROM。
- peak Settings 没有 CAS；compare/read 与 restore write之间仍有不可消除的 TOCTOU 窗口，同值外部 takeover 也无法识别。
- global vote作用全部 display。本状态机只接受 default display focus/mode；外接屏/desktop mode未验证。
