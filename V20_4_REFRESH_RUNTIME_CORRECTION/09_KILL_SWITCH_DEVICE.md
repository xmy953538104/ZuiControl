# Kill Switch Device Gate

## 结论

```text
RAW_REFRESH_DISABLE_20X=PASS
RAW_GLOBAL_DISABLE_20X=PASS
MANUAL_SYSPROPS_POKE_USED=NO
RAPID_TOGGLE=PASS
DISABLED_BOOT_PERSISTENCE=PASS
SIGNED_APP_TX10_DEVICE_PATH_NOT_AVAILABLE
APP_UI_TX10=NOT_EXECUTED
```

旧候选中已经闭环的根因得到新候选反证：本轮每次只执行一次 raw `setprop`，均由 init bridge 自动触发标准 `SYSPROPS_TRANSACTION`；没有手工补发 `service call`。

## 20 次稳定 disable/enable

Notes profile 为 90Hz，初态 mask0、render/AppRequest owned。每个 property 各做 20 次 disable + 20 次 enable，全部在 2 秒门内收敛；disable 不增加 `refreshApplyCount`，enable 对当前真实 Notes owner 重建一次、`applyDelta=1`。

| property | edge | N | min ms | P50 ms | P95 ms | max ms | 结果 |
|---|---|---:|---:|---:|---:|---:|---|
| `persist.zui_control.refresh.disable` | disable | 20 | 133.181 | 174.536 | 203.710 | 207.303 | 20/20 PASS |
| 同上 | enable | 20 | 139.280 | 176.230 | 203.848 | 203.903 | 20/20 PASS |
| `persist.zui_control.disable` | disable | 20 | 125.792 | 169.268 | 194.488 | 195.524 | 20/20 PASS |
| 同上 | enable | 20 | 143.259 | 177.715 | 196.964 | 199.814 | 20/20 PASS |

refresh disable 收敛到 mask2，global disable 收敛到 mask1。两者均释放 ZuiControl priority-8 render vote、停止持有 AppRequest，并把 applied package/Hz 清空；重新 enable 后以最新非空 Notes Window 重建 90Hz。global property 的 40 个 edge 中 Uperf/asoulOpt 始终 running、scheduler health 始终 ok，证明隔离范围仍只含 refresh。

证据：[`06_refresh_disable_20cycles.txt`](raw/device_run_20260831170720/06_refresh_disable_20cycles.txt)、[`07_global_disable_20cycles.txt`](raw/device_run_20260831170720/07_global_disable_20cycles.txt)。

## AppRequest 与重复 apply

独立 handoff 快照显示：

- enabled：render/AppRequest owned，Notes target/applied 90，DisplayModeSpecs 的 render/AppRequest 为 `0..90`；
- disabled：mask2，render released，`appRequestOwned=false`，`appliedDisplayHz=0`，disable `applyDelta=0`；外部 WindowManager 接管后 DisplayModeSpecs 为 `0..120`；
- restored：render/AppRequest owned，Notes applied90，enable `applyDelta=1`，DisplayModeSpecs 回到 `0..90`。

这里的 AppRequest API 是共享无 owner-token 的 `setDisplayProperties()`：`appRequestOwnership=sharedNoToken`。`appRequestHandoff=requested:propertyDisable` 与 `appRequestHandoffPending=true` 只表示 traversal/handoff 已请求；API 没有同步 clear 完成回调，因此不能把该字段写成“同步物理释放已完成”，也不能把 pending 本身写成失败。外部 DisplayModeSpecs 的接管变化是独立观测。

证据：[`09_apprequest_handoff_summary.txt`](raw/device_run_20260831170720/09_apprequest_handoff_summary.txt)、[`09_apprequest_enabled_display.txt`](raw/device_run_20260831170720/09_apprequest_enabled_display.txt)、[`09_apprequest_disabled_display.txt`](raw/device_run_20260831170720/09_apprequest_disabled_display.txt)、[`09_apprequest_restored_display.txt`](raw/device_run_20260831170720/09_apprequest_restored_display.txt)。

## Rapid toggle 与短进程

两个 property 各执行 5 组 final-enabled 与 5 组 final-disabled，共发出 80 个 property edge：

| property | commanded edges | apply delta | 单组最大 apply delta | 最终结果 |
|---|---:|---:|---:|---|
| refresh disable | 40 | 19 | 2 | property0 / mask0 / Notes90 |
| global disable | 40 | 17 | 2 | property0 / mask0 / Notes90 |

部分相邻 truth 被事件链合并，因此 apply 少于理论 enable edge；没有组超过其 enable edge 数，也没有额外重复 apply。全部最终状态与 property truth 一致，Uperf/asoulOpt/health 正常，5 秒后残留 notifier/process 为 0。

稳定 enabled 状态另观察 `367.132s`：system_server PID 无变化、applyCount/skipSameCount 无增长，exact notifier 命中0、`zui_controld` 命中0。专用 atrace 在代表性 edge 中捕获 init fork 的短命 `sh`/`service`、标准 sysprops Binder transaction与进程退出；稳定后无残留。

| 代表性 edge | init fork / PID | rename `sh` | exec/rename `service` | `0x5f535052` transaction | process exit | fork→exit |
|---|---|---|---|---|---|---:|
| disable | trace `3037.487317` / 29510 | `3037.492403` | `3037.505377` | `3037.523773` | `3037.525910` | 38.593ms |
| enable | trace `97.273062` / 7435 | `97.279033` | `97.288971` | `97.297284` | `97.310429` | 37.367ms |

证据：[`10_rapid_toggle_both_properties.txt`](raw/device_run_20260831170720/10_rapid_toggle_both_properties.txt)、[`29_kill_notifier_5min_observation.txt`](raw/device_run_20260831170720/29_kill_notifier_5min_observation.txt)、[`32_refresh_disable_edge.atrace`](raw/device_run_20260831170720/32_refresh_disable_edge.atrace)、[`36_refresh_enable_edge.atrace`](raw/device_run_20260831170720/36_refresh_enable_edge.atrace)。

### 时间线与仪器化边界

disable 代表性 edge：raw `setprop` 返回 209.461ms，首次观测 mask2 和全部 ZuiControl ownership released 均为 T0+302.522ms，稳定样本 T0+1135.167ms。enable 代表性 edge：`setprop` 返回 181.030ms，首次观测 mask0、render/AppRequest rebuilt 为 T0+257.687ms，稳定样本 T0+5435.114ms。

生产制品没有导出 init action counter 或 worker callback counter。atrace 可证明代表性短进程的 fork/exec/exit与 Binder transaction，但不能可靠地把调度事件换算为“每个 edge 恰好几个 callback”。因此 T1/T2 精确 host-relative 数字、80-edge init action count、worker callback count均记为 `NOT_INSTRUMENTED`，不伪造计数。行为 gate 由 20/20 outcome、apply delta、rapid final truth、代表性 trace及 5 分钟无残留共同验收。

摘要：[`32_refresh_disable_edge_summary.txt`](raw/device_run_20260831170720/32_refresh_disable_edge_summary.txt)、[`36_refresh_enable_edge_summary.txt`](raw/device_run_20260831170720/36_refresh_enable_edge_summary.txt)。

## Persisted disabled truth

refresh disable=1 后先做 60 秒 disabled 观察：mask2、applyCount 不变，短进程命中0，system_server PID稳定。只重启一次后，首次有效 service state 已是 mask2、render/peak/AppRequest 全部未持有、`refreshApplyCount=0`；没有“先获得90Hz ownership再释放”的启动窗口。boot completed 后继续 60 秒仍保持该状态。

恢复 property=0 后立即重建 enabled ownership，再只重启一次；首次 state 为 mask0，Launcher Window 出现后 render/AppRequest owned，60 秒内 PID稳定。两次启动 Binder found、SELinux Enforcing。

证据：[`34_disabled_preboot_60s.txt`](raw/device_run_20260831170720/34_disabled_preboot_60s.txt)、[`35_disabled_boot_persistence.txt`](raw/device_run_20260831170720/35_disabled_boot_persistence.txt)、[`36_refresh_enable_edge_summary.txt`](raw/device_run_20260831170720/36_refresh_enable_edge_summary.txt)、[`37_enabled_boot_persistence.txt`](raw/device_run_20260831170720/37_enabled_boot_persistence.txt)。

## TX10 边界

release UI 没有 TX10 控件，现场也没有可安全调用该 transaction 的现成 signed-App Manager/API 入口。遵守“不临时增加测试 App/代码、不用 shell Binder 冒充签名 App”的约束，本轮记录：

```text
APP_UI_TX10=NOT_EXECUTED
SIGNED_APP_TX10_DEVICE_PATH_NOT_AVAILABLE
TX10_UNKNOWN_MODULE_DEVICE=NOT_EXECUTED
TX10_UNAUTHORIZED_CALLER_DEVICE=NOT_EXECUTED
```

raw kill-switch device gate 的 PASS 不扩大为 TX10 device PASS；TX10 当前仍只有 source/host/final-artifact 安全证明。
