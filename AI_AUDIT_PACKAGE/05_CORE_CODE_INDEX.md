# 核心代码索引

## 关键文件

| 优先级 | 文件 | 类/组件 | 作用 | 为什么重要 |
| --- | --- | --- | --- | --- |
| P0 | `framework_patch/src/services/com/zui/server/control/ZuiControlService.java` | `ZuiControlService` | 刷新率状态机、Binder、profile、display vote | system_server 核心和唯一刷新率 owner |
| P0 | `framework_patch/src/services/com/zui/server/control/ZuiControlHooks.java` | `ZuiControlHooks` | WindowManager 焦点入口 | 所有场景变化入口 |
| P0 | `framework_patch/src/framework/android/zui/ZuiControlManager.java` | `ZuiControlManager` | App 到 Binder 的 framework API | 手写 Binder 协议边界 |
| P0 | `payload/system/bin/zui_controld` | shell daemon | 调度请求、Uperf/asoul 健康和状态 | 性能控制面核心 |
| P0 | `payload/system/etc/zui_control/uperf-sm8650.json` | Uperf model | CPU/WALT/core_ctl 策略 | 四档真实性来源；当前无 GPU 模块 |
| P0 | `payload/system/etc/init/zui_scheduler.rc` | init services | Uperf/AsoulOpt 生命周期 | ROM 内置、不依赖 Magisk 的基础 |
| P0 | `payload/system/etc/zui_control/zui_scheduler_prepare.sh` | prepare | 持久目录/默认配置/symlink | 启动可恢复性基础 |
| P0 | `app/src/main/java/com/zui/zuicontrol/ZuiControlRequest.kt` | `ZuiControlRequest` | Settings 请求和 ACK | UI 到 daemon 协议 |
| P0 | `app/src/main/java/com/zui/zuicontrol/ZuiControlClient.kt` | `ZuiControlClient` | 刷新率 Binder client | 成功/失败判定入口，当前有 `ok=0` bug |
| P1 | `app/src/main/java/com/zui/zuicontrol/MainActivity.kt` | `MainActivity` | 全部页面与命令交互 | 919 行单体 UI、线程生命周期风险 |
| P1 | `app/src/main/java/com/zui/zuicontrol/ZuiControlQuickService.kt` | QuickService | 常驻通知/快捷控制 | `START_STICKY` 常驻成本 |
| P1 | `app/src/main/java/com/zui/zuicontrol/ZuiControlTileService.kt` | TileService | QS 切换 | 业务场景 profile 修改入口 |
| P1 | `scripts/ApplyZuiControlPayload.py` | payload patcher | 向分区镜像合入全部系统资产 | 文件上下文和系统改动边界 |
| P1 | `scripts/PatchZuiControlFramework.py` | smali patcher | services/framework 注入 | 与指定 ROM 紧耦合 |
| P1 | `scripts/VerifyZuiControlFlashPackage.ps1` | verifier | 最终 super 反向验证 | 防止旧分区/旧上下文误刷 |
| P1 | `payload/patches/*.cil` | SELinux policy | system_server/daemon/asoul 权限 | 出错会导致服务静默失效或扩大权限 |
| P2 | `framework-stubs/.../ZuiControlManager.java` | compile stub | App 编译 | 运行时不打包 |
| P2 | `RefreshSceneController.kt` | display view model | UI 状态读取 | 兼容 peak 设置路径 |
| P2 | `BootReceiver.kt` | Receiver | 启动通知 | 核心服务不依赖它 |

## P0 详细索引

### `ZuiControlService.java`

- 职责：维护 raw/current/last scene、加载和保存 profile、映射显示模式、应用 vote、发布 Binder 和 Settings 状态。
- 关键方法：`onFocusedAppChanged()`（Hook 调用）；`resolveSceneLocked()`；`applyTargetLocked()`；`loadProfiles()/saveProfiles()`；`publishState()`；`onTransact()`。
- 调用者/下游：WindowManager Hook、App `ZuiControlManager` → DMI/DisplayModeDirector、AtomicFile、Settings。
- 线程/频率：system_server 焦点事件和 Binder 线程；焦点变化频繁时可高频调用。
- 开销：模式枚举、反射、Settings 写入和 profile I/O；有去重/防抖但仍需压测。
- 异常/生命周期：随 system_server；多个路径 catch 后转 `lastError`，`publishState()` 的 `Throwable` 被静默吞掉。
- 风险：反射兼容、锁内工作、状态发布静默失败、userId 未建模；构造一次，不应重复监听，当前未见重复注册保护之外的问题。

### `ZuiControlHooks.java`

- 职责/方法：`onFocusedAppChanged(ActivityRecord,int)` 取得 package 并转发给 service。
- 调用：smali 注入的 `DisplayContent.setFocusedApp()` → Hook → singleton service。
- 线程/频率：WindowManager 焦点事件线程，高频；设计上必须轻量。
- 风险：注入点依赖特定 ROM smali；异常不能拖垮 WindowManager。类本身无循环/监听/持有 Activity。

### `ZuiControlManager.java`

- 职责：封装 service lookup、Parcel 和 transaction。
- 关键方法：`transact()`、`getState()`、`setSceneProfile()`、`cycleLastSceneRate()`。
- 调用：App client → ServiceManager `zui_control` → system_server。
- 线程：调用者线程；可能是 UI/QS 裸线程。
- 开销/风险：每次 lookup/transact；无 DeathRecipient；transaction 10/11 是占位协议，API 面大于实现。

### `payload/system/bin/zui_controld`

- 职责：每秒处理 Settings 请求、同步有效 Uperf 档位，每 20 tick 健康检查，并发布 owner/进程/错误状态。
- 关键函数：`process_settings_request()`（约 516 行）；`sync_uperf_frontend()`（约 155 行）；`publish_scheduler_health()`；`main_loop()`（约 571 行）。
- 调用/下游：init → daemon → `settings/getprop/setprop/start/stop/pidof`、vendor 文件、Uperf frontend。
- 线程/频率：单 shell 进程无限循环，`sleep 1`；不可动态取消，只能 init stop。
- 开销：持续 fork 外部命令、Settings I/O、文件解析；同步写配置。
- 异常/恢复：命令有 ACK、许多函数 fail closed，循环用 `|| true` 继续；健康检查重启核心。
- 风险：Settings 通道未鉴权、单槽覆盖、root+shell domain、轮询成本、OEM 调度链未 fence 完整。

### `uperf-sm8650.json`

- 职责：定义 SM8650 CPU power model、WALT governor、core_ctl/cpuset/msm_performance 和四个模式。
- 调用：prepare 复制到 vendor runtime → Uperf 解析 → sysfs/系统接口。
- 生命周期：Uperf 启动读取；scheduler restart 会从 system 默认覆盖 runtime JSON。
- 风险：没有 Adreno/KGSL GPU 模块；注释/文档若称“完整性能接管”会误导。模型改错可造成持续性能/功耗问题，必须 A/B 验证。

### `zui_scheduler.rc` 与 `zui_scheduler_prepare.sh`

- 职责：创建目录/默认值、启动 Uperf wrapper 和 AsoulOpt、声明 user/group/capability/重启策略。
- 调用：Android init 在 post-fs-data/boot 阶段。
- 频率：prepare 每次服务启动；核心常驻。
- 风险：启动顺序、SELinux 和文件 owner 任一错误都会使服务循环重启；AsoulOpt 是不可重编译的预编译 ELF。

### `ZuiControlRequest.kt`

- 职责：生成 requestId、写 Settings 请求、每 200 ms 等终态 ACK。
- 关键方法：`send()`、`awaitTerminalAck()`。
- 调用：MainActivity/QuickService/Tile 的工作线程 → daemon。
- 生命周期：调用线程同步等待，超时结束；不与 Activity lifecycle 绑定。
- 风险：多个并发请求共享单 Settings 槽；调用方线程不可取消；需要协议级并发/超时测试。

### `ZuiControlClient.kt`

- 职责：获取 framework manager 并转换返回值。
- 关键方法：`call()` 第 46–53 行。
- 风险：第 51 行使用 `reply.startsWith("ok")`，`ok=0` 同样返回成功；会把鉴权、参数或 service 错误显示成成功。无内存泄漏，但错误语义属于 P1。
