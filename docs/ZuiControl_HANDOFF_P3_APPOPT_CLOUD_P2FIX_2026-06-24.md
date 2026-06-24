# ZuiControl 2026-06-24 交接：P3 AppOpt + 云控域名屏蔽 + P2 鸣潮 XML 修复

## 0.1 2026-06-24 17:05 覆盖更新

本段覆盖下方旧结论。下方 `0. 本次结论` 里的 `4d351b6 / run=28082837934 / apk_sha256=4a6a... / super_sha256=61d...` 是上一版刷机包；该包刷后确认 P1/P2 基础可用，但暴露两个真实问题：

- AppOpt 服务反复重启：`performanced` 域无法读取 `/data/vendor/zui_control/appopt/applist.conf`，AVC 包含 `dac_override` 和 `zui_control_data_file search`。
- 云控脚本不能由 property 自动触发：rc 用 `u:r:init:s0` 执行 `/system/bin/sh`，设备侧出现 `init execute_no_trans shell_exec` AVC；手动 `su -c sh zui_cloud_block.sh apply` 可以成功。

已修复并重新生成刷机包。当前应刷入的包仍在：

```text
D:\3.VScode\Mi\【B刷机】187
```

当前有效源码提交：

```text
3460dfa Fix AppOpt and cloud-block SELinux startup
```

当前有效 GitHub Actions：

```text
run=28086640070
artifact=ZuiControl-release-apk
artifact=zui-control-v19-payload
```

当前有效最终包反抽验证：

```text
VerifyZuiControlFlashPackage.ps1 -FlashDir D:\3.VScode\Mi\【B刷机】187
ok=true
apk_sha256=64244b25de3908c355f9759dd3bd437e08bef748c0a34f8cc7f987d6a0045680
boot_sha256=5fc24c5b36ba7394125b87b681b62e38575172057c78417a0fa24746815be76b
super_sha256=bf584660338c66b96584123e436b63bcc20095d52528983276f80a0c3a52d39a
vbmeta_sha256=9db326d3d605885c4afdac6b0883dc3f9c0bc2b9b1b3766cc4808945015967f5
vbmeta_system_sha256=a25d4e43e5974e393b4741663f415d6af4c823a6ee6dc89aa168a6041be25884
```

本次新增修复点：

- `zui_appopt.rc` 中 cloud block 的 `apply/restore/boot` 改为 `exec u:r:shell:s0 root shell -- /system/bin/sh ...`。
- `zui_controld.rc` 中 `clear_package_cache.sh` 改为 `exec u:r:shell:s0 root shell -- /system/bin/sh ...`。
- `/system/bin/AppOpt` 增加 `u:object_r:performanced_exec:s0` file_context。
- `performanced` 增加读取 `/data/vendor/zui_control/appopt` 所需的 `dac_override` 和 `zui_control_data_file` 读/search/map 权限。
- `zui_appopt_prepare.sh` 去掉 `[ -x /system/bin/AppOpt ]` 误判，只负责准备运行目录和配置。
- `VerifyZuiControlFlashPackage.ps1` 已增加这些规则的最终 super 反抽检查。

刷后优先确认：

```powershell
adb shell getprop init.svc.zui_appopt
adb shell settings get system zui_control_cloud_block_state
adb shell su -c "cat /data/vendor/zui_control/cloud/status.txt"
adb shell su -c "ip6tables -w -S zui_cloud_block; iptables -w -S zui_cloud_block"
adb shell su -c "dmesg | grep -i 'avc: denied' | grep -Ei 'AppOpt|zui_appopt|zui_cloud|zui_controld|zui_control'"
```

## 0. 本次结论

已生成可刷测试包，位于：

`D:\3.VScode\Mi\【B刷机】187`

最终反抽验证已通过：

```text
ok=true
apk_sha256=4a6a48a92a32c64721b16b3e3069a826eed5a73918125e59260db76bad6449f6
boot_sha256=5fc24c5b36ba7394125b87b681b62e38575172057c78417a0fa24746815be76b
super_sha256=61d25a441e872c823d5201e18bf59a6f411116d5e13af5d13156c3341ece70ec
vbmeta_sha256=9db326d3d605885c4afdac6b0883dc3f9c0bc2b9b1b3766cc4808945015967f5
vbmeta_system_sha256=d766568ac94c62dd79cb22ef30ebbc54f61e363a3e389dc518b12251c2740bcb
```

对应源码提交：

```text
4d351b6 Replace asoulOpt with AppOpt and fix P2 mode trigger
```

GitHub Actions：

```text
run=28082837934
artifact=ZuiControl-release-apk
artifact=zui-control-v19-payload
```

APK：

```text
package=com.zui.zuicontrol
versionCode=28
versionName=0.19.9
release cert sha256=3fecf3a72ca0e0f24991d49e7306ef4a711711f48a66070755eb0237ecb3ed94
apk sha256=4a6a48a92a32c64721b16b3e3069a826eed5a73918125e59260db76bad6449f6
```

## 1. P2 修复点

鸣潮 XML 失灵的核心原因不是 XML 没生成，也不是 bind mount 没生效。现场已经看到：

- `/system/etc/game_policy.xml` 和 `/data/vendor/zui_control/zuipp/active/game_policy.xml` hash 一致。
- 鸣潮 `com.kurogame.mingchao` 的 LimitConfig 已写入 active XML。
- 问题在 daemon 触发 ZuiPP `GameModeProvider/contact` 时，旧映射把 `balanced` 送成了 `gameMode=1`。

本机 ZUI 187 实测顺序以 XML 三段为准：

```text
balanced  -> gameMode=0 -> 第 1 段
powersave -> gameMode=1 -> 第 2 段
savage    -> gameMode=2 -> 第 3 段
```

已修改：

- `payload/system/bin/zui_controld`
- `profile_mode_code_for_pkg()` 改为 `balanced=0 / powersave=1 / savage=2`。
- `zui_control_pp_mode_state` 增加 `xml=<game_policy_sha>:<performanceconfig_sha>`。
- 同包同 mode 但 XML hash 变化时会重新触发 ZuiPP provider，避免“刚改了 XML，但因为 package/mode 没变而跳过”。

刷后验证重点：

```powershell
adb shell settings get system zui_control_pp_mode_state
adb shell su -c "cat /data/vendor/zui_control/zuipp/state/active.sha256"
adb shell su -c "grep -n com.kurogame.mingchao /system/etc/game_policy.xml | head"
```

期望 `zui_control_pp_mode_state` 包含：

```text
state=triggered;stage=provider_direct;package=com.kurogame.mingchao;mode=0;xml=...
```

如果打开的是省电或野兽档，则 mode 应分别为 `1` 或 `2`。

## 2. P3 AppOpt 替换 asoulOpt

本版系统镜像不再内置 asoulOpt 二进制和 `asopt.conf` bind mount。

已删除旧项：

```text
/system/bin/AsoulOpt
/system/etc/asopt.conf
/system/etc/init/zui_asoulopt.rc
/system/etc/zui_control/asopt.conf
/system/etc/zui_control/default_asopt.conf
/system/etc/zui_control/zui_asoulopt.sh
```

新增 AppOpt：

```text
/system/bin/AppOpt
/system/etc/init/zui_appopt.rc
/system/etc/zui_control/default_applist.conf
/system/etc/zui_control/zui_appopt_prepare.sh
/data/vendor/zui_control/appopt/applist.conf
```

启动逻辑：

- boot completed 后执行 `zui_appopt_prepare.sh`。
- 默认配置从 `/system/etc/zui_control/default_applist.conf` 复制到 `/data/vendor/zui_control/appopt/applist.conf`。
- 启动 `zui_appopt /system/bin/AppOpt -c /data/vendor/zui_control/appopt/applist.conf -s 2`。
- daemon 内部仍保留 `apply_asoul/restore_asoul` 兼容命令名，但实际后端已经是 AppOpt，UI 状态文案为“后端：AppOpt”。

刷后验证：

```powershell
adb shell getprop init.svc.zui_appopt
adb shell su -c "pidof AppOpt; ps -AZ | grep AppOpt"
adb shell su -c "cat /data/vendor/zui_control/appopt/applist.conf"
adb shell settings get system zui_control_asoul_health
```

## 3. 云控屏蔽策略

本版不再走“断 UID1000”路线。镜像内置两层策略：

1. `/system/etc/hosts` 静态屏蔽已知 Lenovo/ZUI 云控域名。
2. `zui_cloud_block.sh` 继续用 iptables/ip6tables 阻断少数独立 UID 云控包。

hosts 是静态文件，不需要后台抓包、轮询或常驻解析，所以几乎没有耗电成本。

主要命中域名包括：

```text
zui.lenovo.com
zui-test.lenovo.com
apizui.lenovomm.com
susapi.lenovomm.com
passport.lenovo.com
fw.zui.com
push-rest.zui.com
servicezui.lenovo.com.cn
tbdata.lenovo.com
cloud.lenovo.com
uss.lenovomm.com
```

边界：

- hosts 只能屏蔽精确域名，不支持通配 `*.lenovo.com`。
- 不能屏蔽硬编码 IP、私有 DoH，或未来新增但未列入的子域名。
- 本版没有阻断 UID1000，因此 `com.zui.pp`、`com.zui.cores`、`com.zui.safecenter` 不会因为共享 system UID 被一刀切断网。
- 旧实验如果已经在设备 `/data` 内留下 UID1000 联网禁用，刷入不清 data 时可能继续存在；恢复命令见下。

独立 UID 阻断目标：

```text
com.zui.game.service
com.zui.engine
com.lenovo.tbengine
com.lenovo.leos.cloud.sync
com.tblenovo.tabpushout
com.tblenovo.center
```

刷后验证：

```powershell
adb shell settings get system zui_control_cloud_block_state
adb shell su -c "cat /data/vendor/zui_control/cloud/status.txt"
adb shell su -c "tail -n 120 /data/vendor/zui_control/log/cloud_block.log"
adb shell su -c "grep -n 'zui.lenovo.com\|apizui.lenovomm.com\|passport.lenovo.com' /system/etc/hosts"
adb shell su -c "iptables -S OUTPUT | grep zui_cloud_block"
adb shell su -c "ip6tables -S OUTPUT | grep zui_cloud_block"
```

如果需要恢复之前 live 设备上手工做过的 UID1000 Wi-Fi 禁用：

```powershell
adb shell "su 1000 -c 'service call zui_network_management 4 s16 wlan+'"
```

这条不是本镜像启动脚本的一部分。

## 4. 已执行验证

本地和设备侧：

```text
Android sh -n: zui_controld passed
Android sh -n: zui_cloud_block.sh passed
Android sh -n: clear_package_cache.sh passed
Android sh -n: promote_zuipp_xml.sh passed
Android sh -n: zui_appopt_prepare.sh passed
Gradle debug build: passed
Release build: GitHub Actions passed
```

本地 release 构建失败是预期边界：本机没有 release keystore；CI 已用 secrets 注入并成功签名。

封包流程：

```text
ApplyZuiControlPayload.py: passed, warnings=[]
PackSystemA: passed, LINK count=268
SignNoFec: passed
PackSuper: passed
VerifyZuiControlFlashPackage.ps1 -FlashDir D:\3.VScode\Mi\【B刷机】187: ok=true
```

最终 verifier 已从 `【B刷机】187/super.img` 反抽真实 system/vendor 内容并检查：

- APK version/cert/hash。
- AppOpt 文件、rc、默认配置存在。
- asoulOpt 旧文件不存在。
- `/system/etc/hosts` 含 Lenovo/ZUI 域名屏蔽项。
- `zui_cloud_block.sh` 不含 `--uid-owner 1000`。
- daemon 不含旧 P2-G bridge。
- daemon 不含生产直写 KGSL/cpufreq 逻辑。
- daemon 含 `balanced=0 / powersave=1 / savage=2`。
- daemon 状态含 active XML stamp。
- zui_control service/property/file_context/sepolicy 文本命中。

## 5. 仍需刷后确认

Windows 侧没有执行 `secilc` 完整编译验证；最终 super 文本反抽已通过，但真实设备仍要看 AVC。

刷入后优先跑：

```powershell
adb shell getenforce
adb shell service list | findstr zui_control
adb shell dumpsys zui_control
adb shell getprop init.svc.zui_controld
adb shell getprop init.svc.zui_appopt
adb shell settings get system zui_control_pp_mode_state
adb shell settings get system zui_control_cloud_block_state
adb shell su -c "dmesg | grep -i 'avc: denied' | grep -Ei 'zui_control|zui_controld|AppOpt|zui_appopt|zui_cloud|game_policy|performanceconfig'"
```

鸣潮重点：

1. 打开 ZuiControl 给 `com.kurogame.mingchao` 保存目标 XML。
2. 确认 active/system XML hash 一致。
3. 打开鸣潮。
4. 看 `zui_control_pp_mode_state` 是否为 `mode=0` 且带 `xml=`。
5. 如 GPU 仍不对，优先抓 ZuiPP/GameHelper/TAssistent 日志，不先怀疑 XML bind。

## 6. 当前包文件

```text
D:\3.VScode\Mi\【B刷机】187\super.img
D:\3.VScode\Mi\【B刷机】187\boot.img
D:\3.VScode\Mi\【B刷机】187\vbmeta.img
D:\3.VScode\Mi\【B刷机】187\vbmeta_system.img
D:\3.VScode\Mi\【B刷机】187\ZuiControl-v19-system.apk
D:\3.VScode\Mi\【B刷机】187\ZuiControl-v19-release.apk
D:\3.VScode\Mi\【B刷机】187\SHA256SUMS_ZuiControl_v19.txt
```
