# 死代码、历史兼容与复杂度

本文件只列未来候选，不执行删除。按 ponytail/YAGNI 原则，优先删除已证实无调用或只有占位回显的接口，不为了“统一架构”大改可工作的刷新率核心。

## 可精简候选

| 等级 | 位置 | 候选 | 依据/替代 |
| --- | --- | --- | --- |
| P1 | `ZuiControlManager`/`ZuiControlService` transaction 10 | `setModuleEnabled()` | App 无调用；service 只回显模块状态，不是真正控制。删除或接入唯一可信接口，不保留假能力 |
| P1 | 同上 transaction 11 | `exportLog()` | App 日志走 daemon Settings 命令；Binder `exportLog()` 只返回 state。删除重复协议 |
| P2 | `ZuiControlService` | cycle/refresh/module/export 的未用 manager API | 逐个以引用搜索和兼容测试确认后收窄手写 Binder 面 |
| P2 | `payload/.../zui_scheduler_prepare.sh` 86–87 | 清理 `zuipp_reload/appopt/xml/performance_profiles` 旧 Settings key | 这是一次性迁移清理，不应永久每次启动执行；先确认生产设备均迁移完成 |
| P2 | `RefreshSceneController.currentRate()` | `peak_refresh_rate` UI fallback | system_server state 应是唯一真源；保留会掩盖 Binder/状态发布失败 |
| P2 | `ZuiControlQuickService` | 开机自动 `START_STICKY` FGS | 核心不依赖 App；可改用户可选通知或按需启动 |
| P2 | `MainActivity.kt` | 919 行单类程序化四页面 | 不是需要引入框架的理由；只按现有页面拆小 controller/view builder，降低修改互相影响 |
| P2 | `payload/README.txt` | 停止 OEM bridge 的旧说明 | 与实际 rc 只 stop `vendor.perfservice` 不一致，应删除错误陈述 |
| P3 | drawables | `ic_action_export.xml`、`ic_action_import.xml`、`ic_nav_refresh.xml` | Lint/引用搜索确认未使用，可删除 |
| P3 | QuickService | 删除 v3–v6 notification channel 的迁移代码 | 老版本用户迁移完成后可按版本窗口删除；当前是有意兼容，不是立即死代码 |
| P3 | release build | `isMinifyEnabled=false` | 非死代码；若启用需先补 keep/反射验证，小 APK 下收益低 |

## 重复/包装层

- 刷新率存在 `ZuiControlClient` → framework `ZuiControlManager` → 手写 Binder，这是合理跨进程分层，不建议为了少一层直接 Parcel 散落到 UI。
- Uperf 控制同时有 App contract、Settings 文本协议、daemon 文件和 Uperf frontend。这里的层数来自跨权限边界，但 Settings 既当命令队列又当状态库导致复杂；应统一为认证 Binder，而不是再加 manager。
- `framework-stubs` 和 `framework_patch/src/framework` 两份 `ZuiControlManager.java` 是编译 stub/运行时实现的必要重复，但容易协议漂移，应由构建校验对比 public method/transaction。

## 历史/临时痕迹

- 当前生产源码未发现 AppOpt、XML bind、ZuiPP reload、SafeCenter、su、`/data/adb/naki` 运行路径；只在迁移清理 key、历史文档和禁止回退说明中出现。
- 没有 TODO/FIXME/HACK/XXX 注释或被注释的大块代码。
- 旧 P2/P3 文档和 2026-06 记录占 15 个 docs 中的大部分，必须保留历史但不可被自动入口误当生产事实。主交接与当前入口应显式优先。

## 不应为精简而一次性重写

不要同时改 Binder 协议、display vote、daemon 通道和 App UI；这会丢失现有真机基线。先修假成功和鉴权，再替换命令通道，最后才处理 UI/通知常驻。
