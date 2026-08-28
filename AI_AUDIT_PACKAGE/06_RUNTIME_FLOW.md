# 运行时流程

## App/系统第一次启动

1. init 根据 `zui_scheduler.rc` 运行 `zui_scheduler_prepare.sh`，创建 `/data/vendor/zui_control/{uperf,asoul,log}`，复制默认配置并建立 `/data/vendor/asopt.conf` symlink。
2. init 启动 `zui_uperf_service`、Uperf 和 AsoulOpt，再启动 `zui_controld`。
3. system_server 初始化 `ZuiControlService`，`loadProfiles()` 读取 `/data/system/zui_control/profiles.prop`，注册 settings observer 并发布 `zui_control`。
4. App 第一次打开只读取 Binder/Settings 状态构建 UI，不是核心启动条件。

失败点：目录/label/二进制任一失败可能触发 init 重启；system_server service 注入失败则所有刷新率 Binder 失效。当前有日志和 health state，但没有端到端自动开机断言。

## Boot Completed / 用户解锁

`BootReceiver.onReceive()` 接收 `LOCKED_BOOT_COMPLETED` 或 `BOOT_COMPLETED`，启动 `ZuiControlQuickService`。Application 和服务 direct-boot aware，所以凭据加密存储尚未解锁时也可运行。核心 init/system_server 链不依赖广播。

用户解锁没有专用 receiver 或迁移逻辑；当前没有额外处理。

## App 被系统杀死

Activity 消失不影响 system_server、daemon、Uperf 和 asoulOpt。QuickService 若被系统回收会因 `START_STICKY` 请求重建；QS 由 SystemUI 管理。未完成的 `MainActivity.runCommand()` 裸线程没有 lifecycle 取消，回调可能落到已销毁 Activity。

## Service 被杀死

- QuickService：系统可重建；不是核心保活条件。
- `zui_controld`/Uperf/AsoulOpt：init 根据 rc 重启；daemon 每 20 个循环检查另外两个服务。
- system_server 的 `ZuiControlService`：与 system_server 同生共死；没有单独重启。system_server 重启后重新 load profile。
- Binder client：没有 DeathRecipient；下一次调用重新获取 manager/service。

## App 被 force-stop

App receiver/service/Activity 停止，QS/通知可能不可用，正在等待的 UI 命令终止；system_server 和 init 核心仍运行，现有 profile/调度配置继续生效。当前没有专门检测 force-stop，也不需要用 App 保活核心。

## SystemUI / Launcher / 前台 App 变化

`DisplayContent.setFocusedApp()` 注入 Hook → `ZuiControlService.onFocusedAppChanged()`：

- SystemUI、输入法、ZuiControl、自身临时 UI 等只更新 raw，不污染业务 scene。
- Launcher 是有效场景，可独立配置，不是全局默认的来源。
- `lastNonTransientScenePackage` 供 QS 修改上一个真实业务场景。
- service 发布 raw/current/last 到 Settings；daemon 读取 current scene 选择 Uperf 精确覆盖。

真机短测：鸣潮 → ZuiControl 时，焦点变为 ZuiControl，但 business scene 保持鸣潮，因此修改鸣潮精确档可以立即作用于该 scene；退出游戏回桌面后 scene 才回 Launcher。

## 屏幕开关

system_server 发布 screen 状态；daemon `sync_uperf_frontend()` 在熄屏时优先匹配 `- powersave`，优先级高于精确应用和全局。亮屏后重新按 exact/global 解析。现有 shell transaction test 覆盖优先级，真机仅做过短时检查，没有长时间息屏/Doze 功耗测试。

## Doze / 待机

init 和 system_server 仍存在；daemon 的一秒 shell 轮询是否被定时器/系统挂起以及唤醒成本未做正式功耗测试。没有 WorkManager/Alarm 特殊处理，也没有 Doze 白名单逻辑。当前应标记“未验证”，不能推断长稳无问题。

## 低内存

核心 system_server/init 服务受系统保护；App Activity/QuickService 可能被杀。代码没有 `onTrimMemory()`、缓存上限或低内存专项恢复。package picker 会一次加载所有可启动 App 的 label/icon，是主要 App 内存峰值来源。当前没有内存压力测试。

## 系统服务异常

- `zui_control` 不存在：`ZuiControlClient` 返回 manager unavailable；但错误判定 bug 可能造成部分 `ok=0` 误报成功。
- DisplayManagerInternal/vote 失败：service 捕获并设置 last error；反射路径可能因 ROM 字段变化失效。
- Settings 发布失败：`publishState()` 捕获 `Throwable` 后无日志/恢复，daemon 可能继续使用旧 scene/screen。
- system_server 重启：重新初始化；没有自动验证 profile/显示状态恢复的测试。

## 权限缺失

App Binder 调用由 system_server 校验 UID package 和 SHA-256；不匹配返回失败。Settings 性能请求依赖写设置权限，但 daemon 无法识别命令真实调用者。privapp 权限/SELinux 缺失时，功能可能只部分失效。缺权限故障注入未执行。

## root / shell 命令失败

产品运行时没有 `su`/Magisk 依赖，但 daemon 本身是 init root shell。外部命令失败时大多返回非零、记录 daemon log/ACK 并继续下一循环；健康检查可重启 Uperf/asoul。没有指数退避，持续失败可每秒重复 fork/记录。App 侧会在 ACK 超时后报告失败。
