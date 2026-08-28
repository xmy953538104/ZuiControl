# Android 与 ROM 集成

## AndroidManifest 组件

| 类型 | 组件/属性 | exported | directBootAware | 说明 |
| --- | --- | --- | --- | --- |
| Application | ZuiControl | — | true | 禁止备份和清数据；无 sharedUserId、persistent、独立 process |
| Activity | `MainActivity` | true | 继承 App | Launcher 入口，排除最近任务 |
| Service | `ZuiControlQuickService` | false | true | specialUse 前台通知服务 |
| TileService | `ZuiControlTileService` | true | true | 受 `BIND_QUICK_SETTINGS_TILE` 保护 |
| TileService | `DolbyTileService` | true | true | 受 `BIND_QUICK_SETTINGS_TILE` 保护 |
| Receiver | `BootReceiver` | true | true | 监听 BOOT_COMPLETED/LOCKED_BOOT_COMPLETED |

没有 provider。两个 exported Tile 只能由持有系统绑定权限的 SystemUI 调用。BootReceiver 只声明受保护系统广播，但仍可收窄 exported 面。

## 权限

| 权限 | 用途/调用位置 | 必要性 | 普通 App 可获得 | 需要系统/平台能力 |
| --- | --- | --- | --- | --- |
| `RECEIVE_BOOT_COMPLETED` | `BootReceiver` 启动通知 | 通知功能需要，核心不需要 | 是 | 否 |
| `WRITE_SETTINGS` | `ZuiControlRequest` 请求/ACK通道、Dolby | 当前设计需要 | 需用户特殊授权 | 内置后可预授予 |
| `WRITE_SECURE_SETTINGS` | 系统设置/状态集成 | 部分调用需要 | 否 | signature/privileged |
| `QUERY_ALL_PACKAGES` | `MainActivity.showPackagePicker()` | 当前全应用选择器需要 | 受政策限制 | ROM 内置可授予 |
| `FOREGROUND_SERVICE` | QuickService | 需要 | 是 | 否 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | specialUse FGS | 需要 | 受审查 | 内置场景 |
| `POST_NOTIFICATIONS` | 常驻通知 | Android 13+ 需要 | 是 | 否 |
| `BIND_QUICK_SETTINGS_TILE` | 两个 Tile 的 component permission | 防止任意 App 绑定 | 否 | 系统持有 |

`privapp-permissions-zui-control.xml` 和 default-permissions XML 负责 ROM 预授予。未发现声明但完全无调用依据的危险权限；`WRITE_SECURE_SETTINGS` 的范围比多数实际操作更宽，应在后续用调用清单继续收敛。

## System API 分类

- Public API：Activity/Service/Tile/Broadcast/PackageManager/Settings/Notification/Handler/Thread。
- System/hidden API：编译期 `android.zui.ZuiControlManager`；privileged Settings 权限。
- Internal API：`DisplayManagerInternal`、WindowManager `ActivityRecord`、system_server `LocalServices`。
- Reflection：`ZuiControlService` 读取 DMS 的 `mDisplayModeDirector`/`mVotesStorage` 并构造 vote。
- Binder：自定义 service `zui_control`，手写 transaction 1–11，不使用 AIDL。
- Shell/系统命令：daemon 的 `settings`、`getprop/setprop`、`start/stop`、`pidof/ps`、`dumpsys`、文件操作。
- root/su：没有 `su` 或 Magisk 运行依赖；但 init daemon 本身以 uid 0 运行，不能把“无 su”描述成“无高权限”。

## ROM 集成资产

| 资产 | 路径 | 用途 |
| --- | --- | --- |
| priv-app APK | 构建时进入 `/system/priv-app/ZuiControlV49` | 平台签名系统 App |
| framework/services patch | `framework_patch` + `PatchZuiControlFramework.py` | 注入 manager、service、focus hook |
| init rc | `zui_controld.rc`、`zui_scheduler.rc` | 托管 daemon/Uperf/asoul |
| SELinux | `payload/patches/*.cil`、contexts | Binder service、数据目录、执行权限 |
| permissions | `default-permissions`、`privapp-permissions` | 权限预授予 |
| 配置 | `payload/system/etc/zui_control/*` | 默认 profile/Uperf/asoul 配置 |
| 二进制 | `uperf`、`AsoulOpt` | 调度核心 |
| 系统镜像脚本 | `ApplyZuiControlPayload.py`、验包/9008 脚本 | 合入、签名、PackSuper、验证 |

仓库没有 `Android.bp`、`Android.mk`、overlay、product makefile 或 AIDL；集成是对预编译 ROM 解包后 patch/repack。

## APK 单独安装会失效什么

普通安装无法获得平台签名 allowlist 和 privileged 权限，也没有 framework `zui_control`、init services、SELinux、system/vendor 配置。因此刷新率 Binder、Uperf/asoul 控制和可靠日志导出都会失效；最多保留普通 UI 和部分公开 Settings 能力。单装 APK 不是受支持部署方式。

## SELinux 边界

文本规则、最终包反向抽查脚本和真机 Enforcing/AVC 检查已经存在；Windows 环境未做 `secilc` 编译级验证。`zui_controld` 使用 `shell` domain、以及把系统 `/system/bin/dumpsys` 标为 `toolbox_exec` 都是需要专门复审的高权限面。
