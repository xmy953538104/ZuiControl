# Idle Regression

## 结论

```text
ENABLED_IDLE_REGRESSION=PASS
DISABLED_IDLE_PREBOOT=PASS
PERSISTENT_ZUI_CONTROLD_ROWS=0
ZUI_CONTROL_REQUEST_ROWS=0
ZUI_WORKER_PROC_CPU=0
ZUI_WORKER_PERFETTO_CPU=0
```

本轮在最终 enabled Launcher/default120 现场先静置 `321.548s`，再执行同口径 `/proc` 60 秒窗口和 Perfetto 90 秒窗口。观察期间没有刷新率 edge，前后 `refreshApplyCount=2`、`skipSameCount=5` 均不增长，raw/desired/applied/physical 状态保持 Launcher/120。

静置与前后状态证据：[`settle.txt`](raw/device_run_20260831170720/40_idle/settle.txt)、[`idle_60s_zui_delta.txt`](raw/device_run_20260831170720/40_idle/idle_60s_zui_delta.txt)、[`perfetto_zui_delta.txt`](raw/device_run_20260831170720/40_idle/perfetto_zui_delta.txt)。

## `/proc` 60.6 秒窗口

`/proc` 实际窗口为 `60.6s`：

- persistent `zui_controld` process row=`0`；
- `zui_control_request` oneshot process row=`0`；
- system_server 内 `ZuiControl` worker TID `2975` 的 self ticks=`0`、children ticks=`0`、单核 CPU=`0.0000%`；
- worker PID/starttime 稳定，system_server PID `2714` 也稳定；
- Uperf parent、Uperf worker、asoulOpt 均保持同一 PID/starttime；
- ZuiControl `refreshApplyCount` 与 `skipSameCount` 前后无变化。

这里 system_server 的总 CPU 是全平台工作量，不能归因给 ZuiControl；专用 `ZuiControl` worker 的线程级计数才是本 gate 的控制面指标。

证据：[`idle_60s_analysis.txt`](raw/device_run_20260831170720/40_idle/idle_60s_analysis.txt)、[`idle_60s_proc.txt`](raw/device_run_20260831170720/40_idle/idle_60s_proc.txt)、[`zui_before_idle60.txt`](raw/device_run_20260831170720/40_idle/zui_before_idle60.txt)、[`zui_after_idle60.txt`](raw/device_run_20260831170720/40_idle/zui_after_idle60.txt)。

## Perfetto 89.984732 秒窗口

trace 有效时长为 `89.984732s`。专用线程查询得到 `ZuiControl` worker：

| 指标 | 结果 |
|---|---:|
| sched slices | `0` |
| CPU time | `0.000000s` |
| worker Binder events | `0 rows` |

控制面查询的以下类别也全部为 `0 rows`：persistent `zui_controld`、`zui_control_request`、kill-switch `service call`、kill-switch named process、ZuiControl Settings get/put、health heartbeat process、周期 ZuiControl shell/service。trace writer packet loss=`0`，各 CPU 的 ftrace dropped-event delta=`0`。

Perfetto diagnostics 同时报告 `ftrace_setup_errors=2`。该值没有被删除或解释为零；因此本报告不声称采集配置“完全无告警”。在 packet loss/drop 均为0、目标 sched 数据可查询且 `/proc` 独立窗口同样得到 worker CPU0 的前提下，它作为明确的采集边界保留，不改变本次 targeted idle gate 结论。

证据：[`idle_90s.pftrace`](raw/device_run_20260831170720/40_idle/idle_90s.pftrace)、[`perfetto_capture_transcript.txt`](raw/device_run_20260831170720/40_idle/perfetto_capture_transcript.txt)、[`perfetto_query_results.txt`](raw/device_run_20260831170720/40_idle/perfetto_query_results.txt)、[`perfetto_zui_worker_query.txt`](raw/device_run_20260831170720/40_idle/perfetto_zui_worker_query.txt)、[`40_idle_perfetto.sql`](raw/device_run_20260831170720/40_idle_perfetto.sql)。

## Uperf 5 秒 self-check 边界

trace 中仍可见 `/system/bin/zui_uperf_service` wrapper 发起的 Uperf log `grep`，与当前架构中保留的约5秒 self-check一致。这些短进程不是 ZuiControl Settings/Binder health publisher，也不是已退休 refresh/command control plane；本轮没有删除、隐藏或错误归零它们。

## Kill-switch 稳态交叉验证

- enabled 无 edge 状态另连续观察 `367.132s`：system_server PID无变化，apply/skip计数不增长，exact notifier process命中0、`zui_controld`命中0；
- refresh disabled 预启动状态另观察约60秒：mask2、`refreshApplyCount=333`不变、短进程命中0、system_server PID `2700`稳定，render/AppRequest ownership保持释放；
- 随后的 persisted-disabled boot 首个有效 state 已为mask2、apply0、无 ownership；恢复enabled并启动后为mask0，Launcher ownership正常重建。

证据：[`29_kill_notifier_5min_observation.txt`](raw/device_run_20260831170720/29_kill_notifier_5min_observation.txt)、[`34_disabled_preboot_60s.txt`](raw/device_run_20260831170720/34_disabled_preboot_60s.txt)、[`35_disabled_boot_persistence.txt`](raw/device_run_20260831170720/35_disabled_boot_persistence.txt)、[`37_enabled_boot_persistence.txt`](raw/device_run_20260831170720/37_enabled_boot_persistence.txt)、[`35_37_boot_persistence_analysis.txt`](raw/device_run_20260831170720/35_37_boot_persistence_analysis.txt)。

本结论证明本次定向观察窗内没有重新引入 persistent daemon、周期 kill-switch notifier、Settings poller或ZuiControl worker空闲负载；不扩大为24h/72h soak，也不把保留的Uperf wrapper self-check写成已删除。
