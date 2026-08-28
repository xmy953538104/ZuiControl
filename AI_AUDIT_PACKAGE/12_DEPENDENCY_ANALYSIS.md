# 依赖分析

## Gradle 直接依赖

| 依赖 | 版本 | 范围 | 用途 | 实际调用 | 可替代/重量 |
| --- | --- | --- | --- | --- | --- |
| Android Gradle Plugin | 9.0.0 | build plugin | Android 构建、Lint、打包 | 是 | 必需 |
| Kotlin stdlib | 2.2.10（AGP/Kotlin 构建解析） | debug runtime | Kotlin 基础类型/扩展 | 是 | Kotlin 项目必需，轻量 |
| JetBrains annotations | 13.0（传递） | debug runtime | 注解元数据 | 间接 | 很小 |
| JUnit | 4.13.2 | testImplementation | 5 个 JVM 单测 | 是 | 现有简单协议测试足够，无需更重框架 |
| `:framework-stubs` | project compileOnly | 编译期 | 暴露 `android.zui.ZuiControlManager` | 是 | 运行时由 ROM framework 提供 |

没有 AndroidX、Compose、协程、网络库、JSON 库、数据库、依赖注入或日志第三方库。UI 使用 Android 原生 View，整体第三方依赖很小，不存在“换原生 API 可大幅瘦身”的空间。

## dependency report 摘要

执行 `gradle -p . :app:dependencies --configuration debugRuntimeClasspath`。结果只有 Kotlin stdlib 2.2.10 和传递的 JetBrains annotations 13.0；没有版本冲突或重复功能库。

## 非 Gradle 运行依赖

| 资产 | 形式 | 溯源/可重建性 | 风险 |
| --- | --- | --- | --- |
| `/system/bin/uperf` | stripped ELF64 AArch64，Android 21/NDK r24 标记 | SHA-256 `f1265757…f49d8`；仓库无源码/commit/license | 无法从仓库复现或逐行审计 |
| `/system/bin/AsoulOpt` | stripped ELF64 AArch64，Android 24/NDK r29 标记 | SHA-256 `7a2ee5d6…ec86`；无 section headers/源码/license | 内嵌规则不可扩展、许可和安全审计缺口 |
| Android framework/services | 指定 072 ROM | 由 patch 脚本注入 | 只支持特定 smali/内部字段 |
| shell/toolbox/settings/init | ROM 系统命令 | 设备固有 | 行为/SELinux 与 ROM 紧耦合 |

## 构建与供应链风险

- 没有 Gradle wrapper，开发机和 CI 通过外部 Gradle 9.3.1 约定，零构建复现性不足。
- 没有 dependency locking/verification metadata、SBOM 或第三方 LICENSE 清单。
- GitHub Actions 使用 `@v4/@v3` 浮动 tag，不是 commit SHA 固定。
- release keystore 仅通过 CI secret 注入，仓库未发现秘钥值；这是正确方向。
- 两个 native ELF 比 Java/Kotlin 依赖更关键，却缺源码和版本清单，是依赖治理第一优先级。
