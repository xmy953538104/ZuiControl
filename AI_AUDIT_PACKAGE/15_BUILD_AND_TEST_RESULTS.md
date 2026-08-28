# 构建与静态验证结果

执行日期：2026-08-28。所有命令均在不修改产品源码的前提下运行；生成的 build 产物不进入源码压缩包。

## Gradle

命令：

```text
gradle -p . :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

结果：成功，28 秒，53 tasks（9 executed）。

- unit test：5 tests，0 failures，0 errors，0 skipped，约 0.029 秒。
- Lint：0 errors，17 warnings。
- Debug APK：2,725,529 bytes；SHA-256 `ab9b20d1f2c048546cac35e791c1fb0cce5dfbf5dac76712486fee1035688cbb`。

主要 Lint warning：compile/target SDK 版本提示、backup rules、obsolete SDK check、3 个未使用图标、通知硬编码字符串、RTL padding。它们不阻断构建，但未使用资源和 RTL 应进入 P3 清理。

## 依赖报告

命令：

```text
gradle -p . :app:dependencies --configuration debugRuntimeClasspath
```

结果：成功。运行依赖仅 Kotlin stdlib 2.2.10 和 JetBrains annotations 13.0；测试另有 JUnit 4.13.2。

## 脚本/配置静态检查

执行并通过：

```text
python -m py_compile scripts/ApplyZuiControlPayload.py scripts/PatchZuiControlFramework.py tools/PatchAsoulOptConfigPath.py
bash -n payload/system/bin/zui_controld
bash -n payload/system/bin/zui_uperf_service
bash -n payload/system/etc/zui_control/zui_scheduler_prepare.sh
bash scripts/TestZuiControldTransactions.sh
python -X utf8 -m json.tool payload/system/etc/zui_control/uperf-sm8650.json
```

源 XML 共 19 个，受限于 `app/src/main/res`、Manifest 和 payload 权限 XML 的解析全部通过。第一次把生成的 Lint 中间 XML 也纳入扫描导致工具性失败，收窄到源码 XML 后通过；这不是产品 XML 错误。第一次 JSON 检查受 Windows GBK 解码 emoji 失败，显式 UTF-8 后通过。

## Framework 最小编译验证

用 Android 35 `android.jar`、仓库 6 个 stub 编译 1 个 framework manager、2 个 services 源文件：成功，生成 20 个 class；只有 Java 8 source/target 的工具链提示。临时目录验证后已删除。

这只证明 Java 语法/可见签名能编译，不证明完整 072 services.jar 注入、hidden internal ABI 或运行反射必然正确。

## 仓库卫生/敏感扫描

- `TODO/FIXME/HACK/XXX`：当前生产代码无命中。
- 跟踪的 `build/.gradle/local.properties/*.jks/*.p12/*.pfx`：无。
- 密钥/token/password/private-key 模式：无实际秘密；`Binder.clearCallingIdentity` 的局部变量 `token` 是误报。
- Python/JSON/shell/源 XML 均通过静态检查。

## 未执行及原因

- instrumentation/UI test：项目没有对应测试源，且本轮不临时改代码补测试。
- release build/signature：本地没有 release keystore，不能泄露/伪造 CI secret。
- 完整 framework/SELinux 编译：当前 Windows 工具链没有 072 全源码和 `secilc`。
- 最终 super verifier、PackSuper、9008 刷写：本轮是审计，用户明确要求先定位问题；不制作镜像、不刷包。
- 主动长时间高温压测：与当前禁止主动烧机的约束冲突。
