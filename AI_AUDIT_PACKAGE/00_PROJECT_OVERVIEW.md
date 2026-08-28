# 项目概览

审计基线：2026-08-28，Git `bcc036e`，目标设备 TB321FU / ZUI 16.1.11.072，产品版本 versionCode 49 / versionName 0.21.12 / ZuiControlV49。本轮是只读审计、构建验证和短时可逆设备诊断，没有修改产品代码、thermal conf、P1 或 FPS cap，也没有制作或刷写镜像。

## 1. 这个 App 是干什么的

ZuiControl 是 ROM 内置的系统控制面板，核心能力分为三条：

1. 由 `system_server` 内的 `ZuiControlService` 识别前台场景并锁定显示刷新率。
2. 由 init 托管的 Uperf 根据“熄屏 > 精确应用 > 全局四档”调度 CPU/WALT/core_ctl。
3. 由 init 托管的 asoulOpt 进行线程亲和、线程放置和 WALT per-task boost。

App 自身不是核心常驻执行器；它通过 framework Binder 控制刷新率，通过 `Settings.System` 请求/应答通道控制 Uperf/asoulOpt，并提供 QS、通知和日志导出入口。

## 2. 当前已经实现的功能

| 功能 | 实现位置 | 当前事实 |
| --- | --- | --- |
| 刷新率场景识别 | `ZuiControlHooks.onFocusedAppChanged()` → `ZuiControlService` | system_server 为唯一 owner；未配置场景默认 120 Hz；过滤 SystemUI 等临时场景 |
| profile 持久化 | `ZuiControlService.loadProfiles()/saveProfiles()` | `/data/system/zui_control/profiles.prop`，使用 `AtomicFile` |
| 刷新率应用 | `ZuiControlService.applyTargetLocked()` | 先尝试 DisplayManagerInternal，再反射 DisplayModeDirector vote |
| Uperf 全局四档 | `MainActivity` → `ZuiControlRequest` → `zui_controld` | powersave/balance/performance/fast；实测终态约 1.0–2.1 秒 |
| 精确应用覆盖 | `perapp_powermode.txt` + `zui_controld.sync_uperf_frontend()` | 精确应用覆盖全局；熄屏规则优先级最高 |
| asoulOpt | init `zui_scheduler.rc` + `zui_scheduler_prepare.sh` | 一个 init 子进程；配置持久路径 `/data/vendor/zui_control/asoul/asopt.conf` |
| QS/常驻通知 | `ZuiControlTileService`、`ZuiControlQuickService` | QS 调 Binder；通知服务仍为 `START_STICKY` |
| Dolby QS | `DolbyTileService` | 通过 Settings 控制 ZUI Dolby 开关 |
| 日志导出 | `MainActivity.exportLogs()` → daemon 请求 | 汇总状态/日志后从 App 分享 |

明确未交付：完整 UID FPS cap、thermal conf 改造、P1 改动、Uperf 对 Adreno KGSL 频率的直接控制。

## 3. 当前整体成熟度

结论：**刷新率链和 ROM 内置服务框架已达到可继续验收的集成阶段，但“Uperf 全面接管性能调度”尚不能判定稳定完成。**

- 刷新率：架构、权限、持久化和显示 vote 路径完整，已有真机证据；仍需补系统服务故障、配置损坏、Doze 和长时间切换测试。
- Uperf CPU：init 托管、四档解析、精确覆盖和进程健康检查均存在，真机请求能生效。
- Uperf GPU：当前 `uperf-sm8650.json` 没有 KGSL/Adreno 频率模块，不能宣称 Uperf 接管 GPU。
- OEM 冲突：真机启动鸣潮时，`com.zui.pp`/GameHelper 仍下发原厂 LimitConfig，其中 GPU 范围为索引 9–5（约 366–629 MHz）。这与 Uperf 的 CPU 策略同时存在，是本审计的 P0 架构缺口。
- asoulOpt：旧闭源/无源码二进制已稳定内置，但新应用线程规则不能仅靠外部 conf 任意扩展，且缺源码和版本许可溯源。
- 测试：现有 5 个 JVM 单测通过，Lint 0 error/17 warning，Debug 构建通过；没有 instrumentation/UI/长稳测试。

因此，“设备能开机、三个进程存在、叠加层能读数”不能等同于“性能调度已单一归属并稳定正确”。
