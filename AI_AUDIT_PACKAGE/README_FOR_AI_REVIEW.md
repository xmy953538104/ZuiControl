# ZuiControl AI 审查入口

## 1. 项目是什么

ZuiControl V49 是 TB321FU / ZUI 16.1.11.072 的 ROM 内置控制系统：system_server 负责刷新率，init 内置 Uperf 负责 CPU/WALT/core_ctl，asoulOpt 负责线程亲和，App 只做 UI/QS/通知和命令入口。

## 2. 当前实现状态

- 刷新率：system_server owner，未配置默认 120 Hz，profile 持久化和 QS 场景逻辑已实现。
- Uperf：四档与 exact 优先级已实现，切档实测约 1–2.1 秒，不是瞬时事件回调。
- asoulOpt：已去 `/data/adb`/Magisk 依赖并由 init 托管，但仍是无源码旧二进制、规则内嵌。
- GPU：**未被当前 Uperf 配置接管**。真实鸣潮启动时 OEM `com.zui.pp`/GameHelper 仍下发 GPU 366–629 MHz LimitConfig，是当前第一 P0。
- 测试：Debug 构建、5 个 JVM 单测、Lint、shell transaction、配置语法和最小 framework javac 通过；长稳/故障注入/instrumentation 未完成。

## 3. Android/ROM 环境

目标为 TB321FU ZUI 16.1.11.072，Android compile/target 35、min 29，AGP 9.0.0、Gradle 9.3.1、JDK 17。App 必须 platform/priv-app 集成；framework/services、init、SELinux 和系统配置均是功能组成部分，不能把 APK 单独安装当完整产品。

## 4. 核心架构

刷新率链：WindowManager focus → Hook → `ZuiControlService` → profile → DMI/vote。

性能链：App Settings 请求 → `zui_controld` 1 秒轮询 → screen-off/exact/global → Uperf。

线程链：init prepare/symlink → AsoulOpt 内嵌规则 → affinity/boost。

冲突链：游戏启动 → OEM GameHelper/`com.zui.pp` → stock LimitConfig/thermal case；当前与 Uperf 并存。

## 5. 最重要的代码文件

1. `framework_patch/src/services/com/zui/server/control/ZuiControlService.java`
2. `payload/system/bin/zui_controld`
3. `payload/system/etc/zui_control/uperf-sm8650.json`
4. `payload/system/etc/init/zui_scheduler.rc`
5. `payload/system/etc/zui_control/zui_scheduler_prepare.sh`
6. `app/src/main/java/com/zui/zuicontrol/ZuiControlRequest.kt`
7. `app/src/main/java/com/zui/zuicontrol/ZuiControlClient.kt`
8. `app/src/main/java/com/zui/zuicontrol/MainActivity.kt`
9. `scripts/ApplyZuiControlPayload.py`
10. `scripts/VerifyZuiControlFlashPackage.ps1`

详细职责见 `05_CORE_CODE_INDEX.md`，调用图见 `17_CALL_GRAPH.md`。

## 6. 系统调用方式

- 刷新率：认证手写 Binder `zui_control`，App 经 `android.zui.ZuiControlManager` 调用。
- 性能/asoul：当前用 `Settings.System` 单槽 request/ACK；daemon 以 init root shell 执行 settings/property/init/file 操作。
- 显示：DisplayManagerInternal + DisplayModeDirector 私有 vote fallback。
- 调度：Uperf native ELF；asoulOpt native ELF；OEM ZuiPP 游戏链仍存在。

## 7. 系统权限

App 声明 boot、WRITE_SETTINGS、WRITE_SECURE_SETTINGS、QUERY_ALL_PACKAGES、FGS、通知权限；两个 Tile 受系统绑定权限保护。Binder 校验 UID package + SHA-256。性能 Settings 命令未做等价鉴权，daemon 运行在 root + shell SELinux domain，是首要安全整改点。

## 8. 当前已知问题

最优先阅读 `18_RISK_REGISTER.md`。前三项：

1. Uperf CPU 与 OEM 游戏 LimitConfig 重叠，GPU 仍被 OEM 限制。
2. 性能命令 Settings 通道缺调用方认证。
3. `ZuiControlClient.call()` 把 `ok=0` 误判为成功。

不要根据“进程在、能编译、叠加层有数”宣布稳定。不要在当前规则下用 direct KGSL/thermal/XML 快速掩盖 R-001。

## 9. 当前测试情况

本轮通过：Gradle Debug assemble、5 JVM tests、Lint 0 error/17 warning、依赖报告、Python/JSON/source XML/shell 语法、daemon transaction、framework 最小 javac、短时真机切档/scene/OEM 日志诊断。

未通过的功能结论：鸣潮 GPU owner/频率不符合 Uperf 全面接管预期。

未验证：instrumentation/UI、长待机/Doze、低内存、system_server 故障、配置损坏、并发压力、冷重启多轮、secilc、完整 release/最终 super、本轮刷写。

## 10. 推荐阅读顺序

1. 仓库 `AGENTS.md`（最高优先级）
2. `docs/AI交接记录_ZuiControl_2026-07-21_当前主入口.txt`
3. `docs/ZuiControl_AI_NEW_CHAT_HANDOFF_2026-07-21.md` 当前顶部章节
4. 本文件
5. `00_PROJECT_OVERVIEW.md`
6. `02_ARCHITECTURE.md`
7. `17_CALL_GRAPH.md`
8. `18_RISK_REGISTER.md`
9. `03_FEATURE_IMPLEMENTATION.md`
10. `04_SYSTEM_INTEGRATION.md`
11. `05_CORE_CODE_INDEX.md`
12. `06_RUNTIME_FLOW.md` 至 `16_LOGGING_AND_DIAGNOSTICS.md`
13. `19_OPTIMIZATION_PLAN.md`
14. `SOURCE_FILE_INDEX.csv`

旧 2026-06 P2/P3/XML/AppOpt 文档只作历史；冲突时以 AGENTS、当前主入口和主交接顶部为准。涉及 P2/ThermalConfig 时必须先完整读取对应 guide，但当前禁止改 thermal。

## 源码压缩包

`AI_REVIEW_SOURCE.zip` 由 91 个当前 Git 跟踪文件和本审计目录的报告/CSV构成；为避免递归，zip 内不包含 zip 自身。排除了 `.git/.gradle/build/.idea/local.properties`、签名材料、秘密、设备 dump、私人数据和 APK 中间产物。

## Codex 审计覆盖声明

已完整阅读：

- 11 个 Kotlin 文件（含 1 个 JVM 测试）。
- 10 个 Java 文件（framework 实现、编译 stub、最小编译 stub）。
- 19 个 XML（Manifest、resources、权限 XML）。
- 4 个 Gradle KTS 文件。
- 4 个 shell 文件（含两个无扩展名 system/bin 脚本）。
- 12 个运行时 config 文件（2 rc、2 CIL、1 JSON、1 conf、1 prop、3 context patch、1 per-app 默认文本、1 hosts）。
- 5 个 PowerShell、3 个 Python、2 个 CI workflow、17 个 README/交接/历史文档和 2 个仓库元数据文件。

native 文件：仓库没有 native 源码；对 2 个 AArch64 ELF（Uperf、AsoulOpt）只完成 hash、ELF metadata 和 strings/运行行为检查，不能声称逐行读取实现。

未能读取：Uperf 与 AsoulOpt 对应源码、构建配方、完整许可和 source commit，因为仓库未提供。

未能验证：完整 072 framework 编译、`secilc`、release 签名、最终 super 反查、instrumentation/UI、长待机/Doze、低内存/system_server/权限/配置损坏故障注入、长时游戏稳定性和多轮冷启动。本轮未制包、未刷写、未主动高温压测。

不确定：CPU 叠加层 3.3 GHz 是每核持续实频还是上限/瞬时展示；AsoulOpt 内部线程扫描/锁/规则算法；OEM `thermal_pwrlevel=3` 在标准 cooling state 0 时的全部来源。已确认的是 OEM 游戏链实际下发 GPU 366–629 MHz 边界，且当前 Uperf JSON 无 KGSL 模块。
