# 线程与异步模型

项目没有 coroutine、RxJava、WorkManager、Timer、Executor 或数据库异步层；主要使用裸 `Thread`、主线程 `Handler`、system_server Binder/WM 线程和 shell 无限循环。

| 位置 | 异步机制 | 生命周期 | 频率 | 可取消 | 风险 |
| --- | --- | --- | --- | --- | --- |
| `MainActivity.runCommand()` 595–630 | 每次命令 `Thread` + main Handler | 超出 Activity 生命周期 | 用户每次操作 | 否 | Activity/dialog 泄漏、销毁后回调、并发命令抢单槽 |
| `MainActivity.showPackagePicker()` 496 起 | 裸 `Thread` 后 post UI | 超出 Activity 生命周期 | 每次打开选择器 | 否 | 加载全部 icon/label，旋转/退出后回调 |
| `ZuiControlRequest.awaitTerminalAck()` | 工作线程内 200 ms polling/sleep | 到 ACK 或 timeout | 每个命令 | 仅超时 | Settings 高频读、不可主动取消 |
| `ZuiControlTileService.onClick()` 17 起 | 裸 `Thread` | TileService 回调之后 | 每次点击 | 否 | service 生命周期外回调、重复点击竞态 |
| `DolbyTileService.onClick()` 40 起 | 同步/Tile 更新 | TileService | 每次点击 | 否 | Settings 异常处理有限 |
| `ZuiControlQuickService` | main Handler/Service callbacks | sticky FGS | 长期 | stopService | UI 进程常驻，核心不需要它 |
| `ZuiControlService` | WM focus/Binder/ContentObserver | system_server | 焦点/设置变化 | 随 system_server | 锁内 I/O/反射；状态发布异常静默 |
| `zui_controld.main_loop()` 571–590 | `while true` + `sleep 1` | init service | 1 Hz；health 约 20 s | 仅 init stop | 持续 fork/settings/file I/O，无 backoff |
| `zui_uperf_service` | supervisor loop + `sleep 1` | init service | 子进程退出时循环 | 仅 init stop | 崩溃风暴时固定 1 秒重启 |
| Uperf ELF | native 常驻线程 | init service | 持续 | 仅 stop | 源码缺失，线程细节不可审计 |
| AsoulOpt ELF | native 常驻线程 | init service | 持续扫描/处理（实现不可见） | 仅 stop | 源码缺失，竞态/锁不可审计 |

## 锁与竞态

- `ZuiControlService` 用内部锁保护 scene/profile/apply 状态。焦点、Binder、observer 可跨线程进入；当前没有明显 ConcurrentModification，但锁内系统调用/文件工作会放大阻塞。
- Settings 请求是单全局槽，不是队列。两个 App 线程或 QS/UI 同时发命令时，后写可覆盖前写；requestId 只能识别 ACK，不能保证命令已被 daemon 读到。
- `runCommand()` 没有 UI 级互斥；按钮快速点击可并发线程、多个 dialog 和相互覆盖 ACK。
- daemon 单线程降低自身文件竞争，但与 App、Uperf watcher、init prepare 共享文件，依赖临时文件/rename 和约定，缺跨进程锁。
- profile 用 `AtomicFile`，优于调度文本文件；后者由脚本原子替换的覆盖度需逐函数确认。

## ANR/泄漏/死锁结论

- 未发现显式嵌套锁或可证明的死锁。
- App 大部分阻塞命令不在主线程，降低直接 ANR 风险。
- system_server focus 路径是更高风险区域：反射、Settings 发布或 profile 操作变慢会影响关键进程。
- 裸线程持有 Activity 和 dialog 是实际生命周期风险；没有 lifecycleScope/cancel token。
- daemon 固定 polling 是长期资源风险，不是 Java Handler 堆积。
