# 风险总表

| ID | 等级 | 类别 | 文件 | 方法/位置 | 问题 | 触发条件 | 后果 | 建议方向 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| R-001 | P0 | 架构/性能 | `uperf-sm8650.json`、`zui_controld`、OEM runtime | modules / `sync_uperf_frontend()` | Uperf CPU 与 OEM GameHelper/ZuiPP 同时调度；Uperf 无 GPU 模块 | 启动鸣潮等 OEM 游戏 | CPU 激进、GPU 被约束 366–629，档位语义失真 | 先选唯一 GPU/游戏性能 owner，保留 telemetry 后再设计 |
| R-002 | P0 | 安全 | `ZuiControlRequest.kt`、`zui_controld` | `send()` / `process_settings_request()` | Settings 命令通道没有 UID/证书认证 | shell/root/高权限 App 写 setting | 可改档、启停/重启系统调度服务 | 迁移认证 Binder，Settings 只发布状态 |
| R-003 | P1 | 正确性 | `ZuiControlClient.kt` | `call()` 46–53 | `ok=0` 被 startsWith("ok") 判成功 | Binder 拒绝/参数错误 | UI 假成功，配置未生效难发现 | 精确解析 `ok=1` 并加单测 |
| R-004 | P1 | 权限 | `zui_controld.rc` | `seclabel` | root daemon 运行在宽泛 shell domain | daemon 常驻 | 攻击面过宽、难以最小授权 | 专用 SELinux domain |
| R-005 | P1 | 系统集成 | `ApplyZuiControlPayload.py`、contexts | dumpsys relabel | 全局 `/system/bin/dumpsys` 改为 toolbox_exec | ROM 合入 | 影响其他 domain/执行语义 | 专用 wrapper/type，验证最小 allow |
| R-006 | P1 | 状态一致性 | `ZuiControlService.java` | `publishState()` 655–672 | catch Throwable 静默 | Settings provider/权限异常 | daemon 用旧 scene/screen，错误无日志 | 有限重试、结构化日志、health stale 标志 |
| R-007 | P1 | 数据恢复 | `ZuiControlService.java` | `loadProfiles()` 504 起 | 坏 profile 无 quarantine/备份验证 | 文件损坏/掉电 | 用户规则丢失或默认化 | 保留 bad copy、schema/checksum、故障测试 |
| R-008 | P1 | 并发 | Settings 单槽协议 | request/ack | 并发后写覆盖前写 | UI/QS 快速同时操作 | 前一命令超时/不执行 | Binder 队列或单写入串行化 |
| R-009 | P1 | 生命周期 | `MainActivity.kt` | `runCommand()` 595 起 | 裸线程持有 Activity/dialog，无取消 | 退出/旋转/并发点击 | 泄漏、过期回调、重复 dialog | lifecycle-aware scope 或最小 cancellable executor |
| R-010 | P1 | 兼容性 | `ZuiControlService.java` | `applyDisplayModeVote()` 581 起 | 反射 DMS 私有字段 | ROM/OTA 变化 | 刷新率 silently/fallback 失效 | 固定 072 验证；优先稳定 internal API；启动自检 |
| R-011 | P1 | 可复现性 | `payload/system/bin/uperf` | binary | 无源码/license/source commit/SBOM | 需要升级/审计 | 无法复现、安全/许可不明 | 固化上游 commit、许可、构建配方/hash |
| R-012 | P1 | 可扩展性 | `payload/system/bin/AsoulOpt` | embedded table | 闭源内嵌包/线程表，conf 不能扩展任意新应用 | 新游戏/未知 App | 不能按需新增真实线程规则 | 获得源码/开放规则格式，或保留受验证替代方案 |
| R-013 | P1 | 验证 | `payload/patches/*.cil` | policy | Windows 未 secilc 编译 | 新/改策略进入包 | 编译/加载错误可能只在设备暴露 | Linux CI secilc + final super + AVC 三层验证 |
| R-014 | P1 | 文档/决策 | `AGENTS.md` vs runtime | Uperf owner 声明 | 文档称生产切换 Uperf，但 OEM chain 活跃 | 游戏启动 | 后续 AI 误判、错误调参 | 把 OEM overlap 固化为当前 P0 事实 |
| R-015 | P1 | 性能 | `zui_controld` | `main_loop()` 571–590 | 每秒多次 fork/Settings/file I/O，无 backoff | 全开机周期 | 唤醒、电量、错误风暴 | 事件驱动 Binder/inotify，健康检查降频/退避 |
| R-016 | P1 | 多用户 | service/daemon Settings | scene/profile keys | 缺完整 userId 维度 | 多用户/工作资料 | 同包/状态串扰 | profile/request 引入 userId 并鉴权 |
| R-017 | P2 | 生命周期 | `ZuiControlQuickService.kt` | `START_STICKY` 58 | 非核心 FGS 开机常驻 | 每次开机 | UI 进程内存/电量 | 改用户可选或按需通知，不牵动核心 |
| R-018 | P2 | 性能 | `MainActivity.kt` | `showPackagePicker()` 496 起 | 枚举并加载全部 App icon | 打开选择器 | 卡顿、GC、内存峰值 | 分页/延迟图标/仅 launchable 缓存 |
| R-019 | P2 | 代码复杂度 | `MainActivity.kt` | 全类 919 行 | 页面、样式、协议交互单体 | 任意 UI 修改 | 容易破坏全局样式/间距，回归面大 | 小步拆页面 builder/state，不换 UI 框架 |
| R-020 | P2 | API 面 | Manager/Service | TX 10/11 | setModuleEnabled/exportLog 仅占位 | 被误用 | 返回假能力、维护协议负担 | 证明无调用后删除或实现唯一通道 |
| R-021 | P2 | 双路径 | `ZuiControlService.java`、`RefreshSceneController.kt` | peak observer/fallback | 主路径之外仍观察/写 peak setting | OEM/用户改 Settings | 可能与 vote 竞争或掩盖故障 | 明确兼容边界，逐步变只读/删除 |
| R-022 | P2 | 工具链 | `PatchZuiControlFramework.py` | smali patterns | 对 072 精确文本/寄存器形态依赖 | 输入 ROM 变化 | patch 失败或错误注入 | 继续 fail closed，增加输入 hash/反编译单测 |
| R-023 | P2 | 验包 | `VerifyZuiControlFlashPackage.ps1` | scheduler/daemon checks | 会禁止 scheduler shell domain，却未禁止 daemon shell domain | 验包 | 宽权限被误判合格 | 增加 daemon 专用 domain 断言（在策略改造后） |
| R-024 | P2 | 配置 | `zui_scheduler_prepare.sh` | copy Uperf JSON | restart 覆盖 runtime model | 用户/开发者改核心 model | 修改不持久、诊断混淆 | 明确只有 mode/perapp/conf 可编辑；model 版本化 |
| R-025 | P2 | 文档 | `payload/README.txt` | OEM bridge 说明 | 声称停多个 bridge，实际仅 vendor.perfservice stopped | 阅读部署说明 | 错估性能 owner | 按 rc/真机事实更新 |
| R-026 | P2 | 构建 | repo root | no Gradle wrapper | 外部 Gradle 约定 | 新机/CI 变化 | 构建不可复现 | 加 wrapper 或校验固定发行版 hash |
| R-027 | P2 | 供应链 | `.github/workflows/*.yml` | `uses: ...@v4/v3` | Action 未 pin commit SHA | 上游 tag 被移动/供应链事件 | CI 被替换 | 固定审计过的 commit SHA |
| R-028 | P2 | 启动面 | `BootReceiver` | exported=true | 组件暴露面大于必要 | 外部显式 Intent/未来 action 变化 | 无谓攻击面 | 验证可行后设 false 或 receiver permission |
| R-029 | P2 | 恢复 | Uperf wrapper/daemon | fixed retry | 崩溃时固定 1 s/20 s 重启，无熔断 | 持续配置/二进制故障 | crash loop、日志/功耗 | 有上限退避并尊重 kill switch |
| R-030 | P3 | 资源 | 3 个 drawable | unused | 永不引用 | APK/维护噪音 | 确认 release 引用后删除 |
| R-031 | P3 | Lint | resources/Manifest/build | 17 warnings | 构建 | RTL/字符串/未来 SDK 质量 | 分批清零，不与核心修复混做 |
| R-032 | P3 | 体积 | `app/build.gradle.kts` | release minify=false | release build | APK 略大 | 当前低优先级，补测试后再评估 |

## 优先级判断

先解决 R-001/R-002：前者决定性能功能是否名副其实，后者决定高权限控制面是否可信。R-003 是小改但高收益的确定性 bug。其余必须沿现有真机基线小步推进，不能把 GPU、SELinux、Binder、UI 一次性重写。
