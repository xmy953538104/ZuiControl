# 构建环境

## 当前配置

| 项目 | 值 |
| --- | --- |
| compileSdk / targetSdk / minSdk | 35 / 35 / 29 |
| AGP | 9.0.0 |
| Gradle | 9.3.1（本机/CI 外部安装，无 wrapper） |
| Java | Temurin 17.0.18；source/target 17 |
| Kotlin | 构建插件 2.2.21；runtime stdlib 2.2.10 |
| Android build-tools | CI 安装 35.0.0 |
| App ABI | APK 为 Kotlin/Java，无 App JNI；ROM 二进制为 arm64-v8a/AArch64 |
| NDK | App 不使用 NDK；Uperf 字符串标记 r24，AsoulOpt 标记 r29 |
| buildTypes | debug、release；无 flavor |
| signing | release 从 Gradle property 或 CI environment/Secrets 读取 |
| R8/ProGuard | release minify 关闭 |
| platform API | `compileOnly(:framework-stubs)`；运行时由 patch 后 framework 提供 |
| framework dependency | `android.zui.ZuiControlManager`、system_server patch、072 内部 API |

## 从零构建 App

前提：JDK 17、Gradle 9.3.1、Android SDK platform 35/build-tools 35.0.0；仓库不带 wrapper。

```powershell
gradle -p D:\3.VScode\Mi\ZuiControl :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Release 需要由安全环境提供 keystore 文件路径、密码和 alias 的 Gradle property 或环境变量，再执行 `:app:assembleRelease`。本报告不记录、复制或打包任何值。

## 从源码到 ROM 的额外步骤

1. CI 构建 platform-signed release APK 并 stage 到 `payload/system/priv-app/ZuiControlV49`。
2. `PatchZuiControlFramework.py` 对指定 072 framework/services 解包结果注入 API、service 和焦点 hook。
3. `ApplyZuiControlPayload.py` 合入 init、二进制、配置、权限和 SELinux 文本。
4. 动态分区重新生成、AVB `SignNoFec` 后重新 `PackSuper`。
5. `VerifyZuiControlFlashPackage.ps1` 必须从最终 `super.img` 反向抽查内容/context/hash。
6. 只使用固定七入口 9008 包和已有自动化流程刷写。

本轮没有执行上述 ROM 制作/刷写；这不是当前审查需求，也避免改变已连接设备。

## 环境缺口

- Windows 无 `secilc` 编译级验证环境；文本和真机 AVC 不能完全替代 policy 编译。
- 本地无 release keystore，未生成/验证 release 签名 APK。
- 没有 Gradle wrapper，`gradle` 版本靠外部约定。
- Framework 独立 javac 只验证源码/签名级可编译，不等同于完整 Android framework 构建。
