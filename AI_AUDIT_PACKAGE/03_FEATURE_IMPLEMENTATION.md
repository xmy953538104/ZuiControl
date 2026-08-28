# 功能实现逐项审计

## 刷新率锁定与场景切换

- 目的：用可配置、可记忆的固定刷新率替代 ZUI 智能刷新。
- 触发条件：焦点 App 变化、profile 修改、QS 切换、系统服务启动。
- 完整调用链：`DisplayContent.setFocusedApp()` → `ZuiControlHooks.onFocusedAppChanged()` → `ZuiControlService.onFocusedAppChanged()` → `resolveSceneLocked()/resolveTargetModeLocked()` → `applyTargetLocked()` → `DisplayManagerInternal.setDisplayProperties()` 或反射 vote。
- 主要文件：`framework_patch/src/services/.../ZuiControlService.java`、`ZuiControlHooks.java`、`android/zui/ZuiControlManager.java`、App `ZuiControlClient.kt`。
- 权限/API：自定义 Binder、system_server internal API、反射 DisplayModeDirector；调用方须包名和签名摘要命中。
- 平台要求：需要 framework/services.jar 注入、platform/priv-app 集成；普通 APK 单独安装不可用。
- 线程：focus 回调来自 system_server；文件 I/O 和状态逻辑在 service 的锁/调用路径内。Hook 本身只转发。
- 长期驻留：随 system_server。
- 失败行为：不支持的 Hz 拒绝/回退；DMI 失败后尝试 vote；异常写入 `lastError`。
- 潜在问题：反射易随 ROM 变化；`publishState()` 吞掉 `Throwable`；兼容 `peak_refresh_rate` 观察/写入形成第二入口；缺系统故障和损坏文件测试。

## Uperf 全局四档与精确应用覆盖

- 目的：让 ROM 内置 Uperf 管理 CPU power-model、WALT governor、core_ctl 等。
- 触发条件：UI 切全局档、增删精确规则、屏幕/业务场景变化、daemon/Uperf 重启。
- 调用链：`MainActivity.runCommand()` → `ZuiControlRequest.send()` → `Settings.System.zui_control_request` → `zui_controld.process_settings_request()` → `set_global_mode()/set_perapp_mode()` → `sync_uperf_frontend()` → `/data/vendor/uperf/cur_powermode.txt` → Uperf watcher。
- 文件：`MainActivity.kt`、`ZuiControlRequest.kt`、`payload/system/bin/zui_controld`、`zui_uperf_service`、`uperf-sm8650.json`。
- 权限/API：App 使用 `WRITE_SETTINGS/WRITE_SECURE_SETTINGS`；daemon 通过 `settings`、文件和 init/property 命令。没有 su/Magisk 依赖，但 daemon 本身是 root init service。
- 平台要求：必须内置二进制、init、SELinux 和 vendor 数据目录。
- 线程：App 每次命令新建 `Thread`，ACK 每 200 ms 查询；daemon 无限循环每秒一次。
- 长期驻留：daemon、wrapper、Uperf 常驻；App Activity 不需要驻留。
- 失败行为：命令有终态 ACK；核心进程健康检查并请求 init 重启；文件损坏有默认回退。
- 实测语义：全局切换约 1.0–2.1 秒；精确应用规则优先于全局。鸣潮配置为 `performance` 时，切全局 `fast` 后有效档仍为 `performance`；把鸣潮精确规则临时改为 `powersave` 后约 2.07 秒生效，随后已恢复。
- 潜在问题：请求通道无 Binder 调用方鉴权；单槽轮询有延迟/覆盖风险；每秒外部命令和文件同步有常驻成本；UI 主页面未清晰显示“有效档来源”。

## GPU 调度现状

- 目的声明边界：本版本不宣称直接接管 Adreno KGSL。
- 实际链：`uperf-sm8650.json` 没有 KGSL 节点；鸣潮启动 → OEM GameHelper `onGameAppStart` → `com.zui.pp` PerformanceConnect → 原厂 LimitConfig → CPU/GPU 边界和 thermal case。
- 真机证据：低温启动鸣潮时，OEM 日志下发 `GPUMax=5`、`GPUMin=9`；当前频表索引对应约 629 MHz 与 366 MHz。桌面时 KGSL 可用频率包含 903/834/770/...，但 `max_clock_mhz=720`、`thermal_pwrlevel=3`，两个标准 GPU cooling device 均为 state 0。
- 结论：500–629 MHz 不是 Uperf “性能档”的预期输出，而是 OEM 游戏链仍在限制 GPU。Uperf 同时可把 CPU 策略推高，造成 CPU/GPU 调度归属不一致。
- 潜在问题：这是 P0 架构问题。当前禁止 direct KGSL、旧 XML 和 thermal 修改，故本轮不能靠临时 sysfs 修补；下一阶段必须明确唯一 GPU owner。

## asoulOpt 线程放置

- 目的：硬亲和、线程放置和 WALT per-task boost。
- 触发条件：init 启动、配置保存/模块启停、应用线程出现。
- 调用链：`zui_scheduler_prepare.sh` → symlink `/data/vendor/asopt.conf` → init service `/system/bin/AsoulOpt` → 内嵌包/线程表 → scheduler/affinity 系统调用。
- 文件：`zui_scheduler.rc`、`default_asopt.conf`、`tools/PatchAsoulOptConfigPath.py`、ELF `AsoulOpt`、App asoul 页面。
- 权限/API：专用 SELinux 规则、proc/task 访问；不依赖 Magisk 路径或 App 长驻。
- 线程/驻留：一个 init 子进程长期驻留。
- 失败行为：daemon 健康检查并通过 ctl 启动；配置可恢复默认。
- 潜在问题：二进制无仓库源码/版本许可；应用与线程匹配表内嵌，未知新应用不能像 AppOpt 那样只编辑外部 conf 扩展。`mode=0, rt=0` 只控制已有逻辑，不创造通用规则。

## QS、通知与日志

- QS 刷新率链：`ZuiControlTileService.onClick()` → `ZuiControlClient.cycleLastSceneRate()` → Binder → system_server 修改上一个真实业务场景。
- Dolby QS：`DolbyTileService` 读写 ZUI 设置项；与核心调度独立。
- 通知：`BootReceiver` → `ZuiControlQuickService`，返回 `START_STICKY` 并显示快捷控制；不是核心保活必需。
- 日志：`MainActivity.exportLogs()` → daemon `export_logs` 请求 → 分享结果。
- 风险：QS 用裸线程；QuickService 造成 UI 进程长期驻留；`ZuiControlClient.call()` 把以 `ok=0` 开头的错误误判成成功。
