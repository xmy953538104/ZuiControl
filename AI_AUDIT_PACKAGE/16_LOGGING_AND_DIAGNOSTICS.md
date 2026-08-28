# 日志与诊断

## 主要日志源

| 来源/TAG | 正常应看到 | 异常应看到 | 位置 |
| --- | --- | --- | --- |
| logcat `ZuiControl` | `zui_control service published`、显示 apply/scene 状态 | `failed to publish`、`apply display failed`、observer unavailable | `ZuiControlService.java` |
| daemon 文件日志 | `zui_controld v49 start`、`Uperf effective=... source=...`、request/ACK | init action failed、health restart、OEM perf bridge escaped fence | `/data/vendor/zui_control/log`；`zui_controld::log_line()` |
| Settings 状态 | raw/current/last、active refresh、daemon/Uperf/asoul health、last request | stale timestamp、failed ACK、missing process | `ZuiControlContract.kt`、daemon 常量 |
| init/service | 三个 service running、PID 稳定 | crash/restart loop、wrong context | `init.svc.*`、`ps -AZ`、logcat init |
| SELinux | 无相关 denial | `avc: denied` 涉及 zui_control/performanced/shell | dmesg/logcat audit |
| OEM GameHelper/ZuiPP | 游戏 start/exit、LimitConfig | 与期望 owner 冲突的 CPU/GPU limit | logcat `com.zui.pp`/PerformanceConnect/GameHelper |
| KGSL/sysfs | governor、freq、busy、pwrlevel 与场景一致 | max cap/pwrlevel 与 cooling state 不一致 | `/sys/class/kgsl/kgsl-3d0/*`、thermal cooling devices |

## 本轮关键真机诊断事实

- `zui_controld`、两层 Uperf（wrapper/core）和一个 AsoulOpt init 子进程均存在；SELinux Enforcing。
- 全局/精确命令均获得终态 ACK；有效档变化约 0.97–2.07 秒。
- 鸣潮精确 `performance` 会覆盖 global `fast`；临时 exact `powersave` 可生效，测试结束已恢复 performance/global balance。
- KGSL governor 为 `msm-adreno-tz`，频表含 903 至 231 MHz；桌面低温时 current 231、max_clock 720、thermal_pwrlevel 3，而两个标准 GPU cooling device 都为 state 0。
- 启动鸣潮时 OEM PerformanceConnect 明确下发 `GPUMax=5/GPUMin=9`，对应约 629/366 MHz；GameHelper 也打开 savage mode，thermal-engine 切入游戏 case。

这些日志把问题定位到“原厂游戏性能链仍活跃”，而不是仅凭温度猜测。

## 推荐只读采样组合

后续冷态 A/B 应同时记录：

1. 时间戳、focus/raw/current/last scene、global/exact/effective/source。
2. 每核 `scaling_cur_freq`/policy min/max/governor，不只看叠加层单个 3.3 GHz 数字。
3. KGSL current/min/max/available、`gpu_busy_percentage`、pwrlevel、标准 cooling state。
4. `com.zui.pp`/GameHelper/thermal-engine 同时间段日志。
5. 退出游戏后所有限制是否恢复。

不主动制造高温；先在设备自然降温、同版本、同场景短窗口采样。

## 日志充分性判断

当前日志能定位进程存在、请求事务、有效 Uperf 档和刷新率状态，但不足以独立定位 GPU owner 和 CPU/GPU 时间序列：

- Uperf state 没有记录实际写过哪些 sysfs/node 及最终读回值。
- daemon 的 OEM fence 只检测 `vendor.perfservice`，没有把 `com.zui.pp` GameHelper LimitConfig 标为冲突。
- `publishState()` 失败无日志，会导致最难查的静默旧状态。
- 没有统一 requestId 跨 App/daemon/Uperf/OEM 日志关联。
- 日志导出必须避免包含用户 App 清单、私人路径和完整设备 dump；本审查 zip 未收录设备原始日志。
