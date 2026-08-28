# 性能分析

以下按代码和真机证据排序；没有把“可以运行”当成性能正确。

## P0

### PERF-001：CPU 与 GPU 存在两个性能策略所有者

- 文件/方法：`payload/system/etc/zui_control/uperf-sm8650.json` 的 modules/power-model；`payload/system/bin/zui_controld::sync_uperf_frontend()`；ROM 外部运行链 GameHelper → `com.zui.pp` PerformanceConnect。
- 原因：Uperf 调 CPU/WALT/core_ctl，但没有 KGSL 模块；OEM 游戏链仍在启动鸣潮时下发 CPU/GPU LimitConfig。
- 发生条件：进入 OEM 识别的游戏，如 `com.kurogame.mingchao`。
- 真机影响：OEM 下发 `GPUMax=5/GPUMin=9`，对应当前频表约 629/366 MHz；同时 Uperf 精确 `performance` 可把 CPU 策略推高，表现为 CPU 激进而 GPU 在 500–629 MHz。
- 结论：当前不能称为单一 Uperf 接管，也不能用 thermal cooling state=0 排除 OEM 限制。

## P1

### PERF-002：daemon 每秒轮询并大量 fork

- 文件/方法：`payload/system/bin/zui_controld::main_loop()` 571–590、`process_settings_request()`、`sync_uperf_frontend()`。
- 原因：每秒调用 `settings/getprop/pidof/ps` 等外部工具并解析文件，每 20 tick 额外健康检查。
- 条件：设备整个开机周期。
- 影响：常驻 CPU 唤醒、process fork、I/O 和电量成本；持续错误时没有 backoff。

### PERF-003：Settings 命令通道引入 1–2 秒响应

- 文件/方法：`ZuiControlRequest.send()/awaitTerminalAck()`；daemon `main_loop()`。
- 原因：daemon 固定 1 秒 tick，App 再以 200 ms 查 ACK；外部 adb 测量还包含 host 开销。
- 实测：可逆真机测试终态约 0.97–2.07 秒；不是毫秒级即时。
- 影响：用户切回游戏过快时可能先看到旧档；并发请求可更差。

### PERF-004：package picker 全量加载应用图标

- 文件/方法：`MainActivity.showPackagePicker()` 496 起。
- 原因：`QUERY_ALL_PACKAGES` 后枚举可启动 App，构建 label/icon 列表。
- 条件：打开自定义应用选择器。
- 影响：瞬时内存、PackageManager I/O、GC 和较慢弹窗；低内存设备更明显。

## P2

### PERF-005：非核心 sticky FGS 常驻 UI 进程

- 文件/方法：`ZuiControlQuickService.onStartCommand()` 第 58 行返回 `START_STICKY`；`BootReceiver` 开机启动。
- 原因：通知快捷控制，而核心已由 system_server/init 托管。
- 影响：常驻通知、App 内存和后台资源；应评估改成用户可选，而不是核心依赖。

### PERF-006：system_server 焦点路径有 Settings/反射工作

- 文件/方法：`ZuiControlService.onFocusedAppChanged()` → `applyTargetLocked()`/`publishState()`。
- 原因：前台高频变化触发场景计算、display apply 和状态发布。
- 影响：极端弹窗/快速切换时增加 system_server 压力；已有同目标去重和防抖，故定为 P2，需 trace 验证。

### PERF-007：运行时配置重复覆盖与解析

- 文件/方法：`zui_scheduler_prepare.sh`、`zui_controld.sync_uperf_frontend()`。
- 原因：scheduler restart 从 system 默认重新复制 Uperf JSON；daemon 每 tick 解析状态/模式文本。
- 影响：少量 I/O；也让核心 model 无法像用户模式文件一样持久编辑。

## P3

- `MainActivity` 919 行全程程序化创建 view，大页面重建会产生较多短命对象，但没有证据表明是当前瓶颈。
- release `isMinifyEnabled=false` 会增加 APK 体积；当前 Debug APK 约 2.73 MB，实际影响低。
- 未发现数据库、SharedPreferences 高频写、Rx/Handler 队列无限增长或广播风暴。

## CPU 3.3 GHz 观察的解释边界

用户在叠加层看到 CPU 3.3 GHz，但本轮没有在同一真实游戏负载下持续采样每核 `scaling_cur_freq`，不能直接断言所有 CPU 核持续锁 3.3 GHz。叠加层可能展示集群能力上限或瞬时峰值。然而 OEM LimitConfig 与 Uperf CPU 策略重叠已经由日志确认，足以说明 CPU 策略归属不干净；后续应做低温、同场景、同版本的 per-core 与 GPU busy/freq 时间序列，而不是主动高温烧机。
