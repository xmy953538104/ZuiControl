# ZuiControl P2 鸣潮 XML 生效修复交接 - 2026-06-24

## 结论

本次修复的是 P2 XML 已被 ZuiPP 读取、但鸣潮实际 GPU 仍被系统热控 user case 压低的问题。

根因不是 bind mount 失效，也不是 profile 没写进去；现场日志证明 ZuiPP 已读取 `LimitConfig`。真正冲突点是鸣潮 `game_policy.xml` App 条目里 `ThermalConfig=200 100 300` 会触发 vendor thermal user case，进而把 KGSL GPU cap 压到 310/231MHz 附近，覆盖了用户 XML 里 GPU 500MHz 以上的预期。

另一个问题是 daemon 为了避免重复触发 ZuiPP provider，缓存了同一 package/mode/xml 的触发状态。退出再进同一游戏时，ZuiPP/热控状态可能已经被系统重置，但 daemon 可能因为缓存不重发 provider。

## 本次代码改动

提交：

```text
6e698a1 Fix Mingchao XML thermal application
```

改动：

- `XmlProfileGenerator.java`
  - 用户生成的独立游戏 App 条目统一写入 `ThermalConfig=0 0 0`。
  - 作用：不再让生成 XML 时继承默认 `200 100 300`，避免 vendor thermal user case 重新压 GPU。
- `payload/system/etc/zui_control/default_game_policy.xml`
  - 鸣潮 `com.kurogame.mingchao` 默认模板改为 `ThermalConfig=0 0 0`。
  - 作用：没有用户 profile 时，默认模板也不再触发鸣潮的压 GPU user case。
- `payload/system/bin/zui_controld`
  - 前台场景切到目标游戏时改为 `apply_pp_mode_for_scene "$top" force`。
  - 作用：每次重新进入有 P2 profile 的游戏时都重发一次 ZuiPP provider；仍然没有轮询，也不会在同一前台 App 内反复刷。
- `VerifyZuiControlFlashPackage.ps1`
  - 新增最终包检查：
    - daemon 必须包含场景进入强制重发 provider。
    - final super 里鸣潮模板 `ThermalConfig` 必须是 `0 0 0`。

## 现场验证证据

设备：

```text
TB321FU / ZUI 16.1.11.187
adb connected
su available
```

现场发现：

- `/system/etc/game_policy.xml` 与 `/data/vendor/zui_control/zuipp/active/game_policy.xml` hash 一致，bind mount 正常。
- 鸣潮 profile 已写入 `/data/vendor/zui_control/performance/profiles.prop`。
- ZuiPP provider 调用成功，log 中能看到 `GPUMax=3 GPUMin=7` 这类从 XML 解析出的值。
- 原始 `ThermalConfig=200 100 300` 时，KGSL 实际被压到约 `max_gpuclk=310000000`、`thermal_pwrlevel=10`。
- 临时把鸣潮 `ThermalConfig` 改为 `0 0 0` 并 reload 后，KGSL 不再被该 user case 压住，`max_gpuclk=903000000`、`thermal_pwrlevel=0`。

离线生成器验证：

- 使用设备当前鸣潮 `profiles.prop` 运行 `XmlProfileGenerator`。
- 输出的鸣潮条目为 `ThermalConfig=0 0 0`。
- 输出 `LimitConfig` 仍保留用户设置的 GPU 500MHz 以上档位。

## CI 和 artifact

GitHub Actions：

```text
run_id=28089289333
workflow=Build ZuiControl
head=6e698a1693e64e457c66e289a7ab032149e0fd5e
status=success
```

artifact 目录：

```text
D:\3.VScode\Mi\work\ci_artifacts\zuicontrol_28089289333
```

APK 校验：

```text
package=com.zui.zuicontrol
versionCode=28
versionName=0.19.9
release cert SHA-256=3fecf3a72ca0e0f24991d49e7306ef4a711711f48a66070755eb0237ecb3ed94
apk SHA-256=89293b21225588e5b9ae8c6d4cec1d8b99b6b89fbdde07a6564cd234bb426aea
```

## 刷机包

最终目录：

```text
D:\3.VScode\Mi\【B刷机】187
```

最终 SHA256：

```text
5fc24c5b36ba7394125b87b681b62e38575172057c78417a0fa24746815be76b  boot.img
4d0d7fe55d8ed37d7fbf1220a39c3baed6631c77e5d5955b61f3936d3eb4f82d  super.img
9db326d3d605885c4afdac6b0883dc3f9c0bc2b9b1b3766cc4808945015967f5  vbmeta.img
f3caa64f53a130ddafcf31d09e03c5ad9d33988ccc318ba0c6b7df5d0d364762  vbmeta_system.img
89293b21225588e5b9ae8c6d4cec1d8b99b6b89fbdde07a6564cd234bb426aea  ZuiControl-v19-system.apk
89293b21225588e5b9ae8c6d4cec1d8b99b6b89fbdde07a6564cd234bb426aea  ZuiControl-v19-release.apk
```

最终验证：

```text
VerifyZuiControlFlashPackage.ps1 -FlashDir "D:\3.VScode\Mi\【B刷机】187"
ok=true
```

验证器已从 final `super.img` 反向抽查：

- embedded APK 与 sidecar APK hash 一致。
- APK 为 release cert。
- daemon 包含 P2 provider direct bridge。
- daemon 包含场景进入强制重发 provider。
- final super 中鸣潮默认模板 `ThermalConfig=0 0 0`。
- AppOpt / cloud-block / SELinux / file_context / service_context 关键文本仍命中。

Windows 侧边界：

- 本次仍未做 `secilc` 编译级验证。
- 刷后继续以 `dmesg/logcat` AVC 作为最终 SELinux 验证。

## 刷后重点验证

刷入后先确认版本和服务：

```powershell
adb shell dumpsys package com.zui.zuicontrol | findstr "versionCode versionName"
adb shell service list | findstr zui_control
adb shell dumpsys zui_control
```

检查 XML 和鸣潮条目：

```powershell
adb shell su -c "grep -A6 -n 'com.kurogame.mingchao' /system/etc/game_policy.xml"
adb shell su -c "grep -A6 -n 'com.kurogame.mingchao' /data/vendor/zui_control/zuipp/active/game_policy.xml"
```

预期：

```text
<Attribute name="ThermalConfig">0 0 0</Attribute>
```

打开鸣潮后检查：

```powershell
adb shell settings get system zui_control_pp_mode_state
adb shell su -c "cat /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel"
adb shell su -c "cat /sys/class/kgsl/kgsl-3d0/max_gpuclk"
adb shell su -c "cat /sys/class/kgsl/kgsl-3d0/devfreq/max_freq"
adb shell su -c "cat /sys/class/kgsl/kgsl-3d0/devfreq/min_freq"
adb shell su -c "dmesg | grep -i 'avc: denied' | grep -Ei 'zui|zuipp|appopt|performanced|kgsl'"
```

预期：

- `zui_control_pp_mode_state` 进入鸣潮后刷新为当前时间的 `state=triggered;stage=provider_direct;package=com.kurogame.mingchao`。
- 不再出现因为鸣潮 `ThermalConfig=200/100/300` 导致的固定 `thermal_pwrlevel=10`、`max_gpuclk=310000000`。
- 若设备真实温度很高，系统全局热控仍可能降频；这不是本次 P2 XML 链路失效。

