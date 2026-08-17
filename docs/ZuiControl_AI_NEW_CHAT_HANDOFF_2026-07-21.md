# ZuiControl 新聊天完整交接 - 2026-07-21

## 0. 权威入口

新聊天按以下顺序读取：

1. `D:\3.VScode\Mi\AGENTS.md`
2. 本文件
3. 需要理解 P2 XML 时读取 `docs/ZuiControl_P2_XML_THERMALCONFIG_GUIDE_2026-07-21.md`
4. 需要追溯实测证据时读取：
   - `docs/ZuiControl_HANDOFF_P3_APPOPT_CLOUD_P2FIX_2026-06-24.md`
   - `docs/ZuiControl_HANDOFF_P2_MINGCHAO_XML_FIX_2026-06-24.md`

旧的 `D:\3.VScode\Mi\docs\ZuiControl_CONTEXT.md`、`ZuiControl_V19_VERIFY_AND_NEXT.md`、`ZuiControl_HANDOFF.md`、`ZuiControl_ROADMAP.md` 保存完整历史，但其中“当前阶段”“推荐包”“下一步”若与本文件冲突，以本文件为准。

### 0.2 2026-08-17 P2/云控重构（当前最新）

本节覆盖后面的旧 P2 三模式/provider 重入、云控实现、28/0.19.9 推荐包和对应验证步骤。P1 相机崩溃修复保持不变，没有进入 FPS cap。

当前目标 App 为 `versionCode=29` / `versionName=0.20.0`。本轮依据设备实测完成以下收敛：

- 云控功能删除。`/system/etc/hosts` 恢复为从官方 ZUI 16.1.11.187 `system_a.img` 抽出的 56 字节默认文件；删除 boot/property 触发、iptables 脚本、daemon cloud 目录和日志导出。升级脚本只做一次旧 setting/runtime 清理。
- 每个 package 只保存一份当前性能 profile；旧数据迁移时优先采用 `balanced`，没有 balanced 才采用第一份旧 profile。新保存统一写 canonical `balanced` 记录。
- 生成的同一份 LimitConfig 镜像到 balanced/powersave/savage 三个原厂槽位，因此不再由 daemon 猜当前 GameMode，也不存在 balanced 抢优先级。
- 每个温区的 Little/Big/Titan/Mega 使用同一个 synthetic CPU level ID（从 900000 起），但四个 Type 下分别保存该簇自己的频率值。这是针对设备实测 ZuiPP HashMap 读取顺序 `LittleCore, MegaCore, BigCore, TitanCore, GPU` 的兼容修复，避免 CPU Type/level 错配导致整条 TAssistant policy 中止。
- 删除 daemon 的 `GameModeProvider/contact` 直接调用和场景轮询。Provider 曾能返回成功但并不证明 ZuiPP 已有目标 game state，属于假成功源。
- 保存并完成 XML promote/ZuiPP 稳定重启后，用户需要退出并重新进入游戏，由原厂 `onGameAppStart` 链路应用 XML。三个模式内容相同，所以原厂选择哪个模式都得到同一配置。
- 只对 ZUI/GameHelper 已识别为游戏的应用承诺触发；给普通 App 生成 XML 条目不等于原厂会触发游戏链路。App UI 已明确提示此边界。
- CPU/GPU 是原厂性能 profile 请求，不是硬 cap。厂商热控或其他更高优先级请求仍可进一步压低或覆盖；禁止因此恢复 direct cpufreq/KGSL sysfs。
- ZuiPP reload 在新 PID 出现后连续稳定 10 秒才发布 done，若进程重启则重新计时；状态继续带 `needsAppReenter=1`。
- baked baseline 使用 payload 模板双 SHA256 版本戳；模板变化会刷新旧 baseline 并自动用保留的用户 profiles 重建 active XML，修复升级后继续沿用旧 `200 100 300` baseline 的问题。
- 正常 promote 前先备份旧 active 到 `last_good`，成功后不再用新 active 覆盖它；回滚点现在确实代表上一次配置。

针对生成器新增可执行测试 `scripts/TestXmlProfileGenerator.py`，会验证旧多模式数据只选一份、三个 OEM mode block 完全相同、四个 CPU ID 相同且各 Type 频率值正确。

#### 0.20.0 发布事实

- 生产代码 commit：`28f1f8b56628ebbaf5cdbb570fe10e1b250e5b6f`
- GitHub Actions run：`32036170577`，结论 `success`：<https://github.com/xmy953538104/ZuiControl/actions/runs/32036170577>
- 下载并用于 payload 的 CI artifact：`D:\3.VScode\Mi\work\ci_artifacts\zuicontrol_32036170577`
- CI release APK 与最终 system 内嵌 APK：`versionCode=29` / `versionName=0.20.0`
- release 证书 SHA-256：`3fecf3a72ca0e0f24991d49e7306ef4a711711f48a66070755eb0237ecb3ed94`
- 最终刷机目录：`D:\3.VScode\Mi\【B刷机】187`

```text
5fc24c5b36ba7394125b87b681b62e38575172057c78417a0fa24746815be76b  boot.img
ddfa388d1df10f6b609337af4b07ed5ca52a6d4602aebaab1ba88fec0a3970ef  super.img
9db326d3d605885c4afdac6b0883dc3f9c0bc2b9b1b3766cc4808945015967f5  vbmeta.img
16b41e21917b6f8168570ebf1d788fe8ef6ff3b2673dbc9f2fdb1a94d4b4b19a  vbmeta_system.img
66488caf0f2570770f0a6b50d3d74efded47dd172f4334c2469c996495590ee6  ZuiControl-v19-system.apk
66488caf0f2570770f0a6b50d3d74efded47dd172f4334c2469c996495590ee6  ZuiControl-v19-release.apk
```

发布只使用上述 CI release artifact。重建 `system_a.img` 后执行 `SignNoFec`，签名后重新执行 `PackSuper`。最终 verifier 从成品 `super.img` 反抽 system/vendor、解码成品 `services.jar` 并返回 `ok=true`；官方 hosts、云控脚本缺失、版本/证书、P1 注入、P2 daemon 标记和 SELinux/context 静态检查均命中。Windows 侧仍未进行 `secilc` 编译级验证，刷后必须检查真实 AVC。

这仍是待刷包：在真实设备完成刷后验证前，不得把相机、P1、P2 或 AVC 写成实机通过。此前 28/0.19.9 和旧 hash 仅作历史。

### 0.1 2026-08-17 P1/AppOpt 覆盖说明（已被 0.2 的包版本覆盖）

本节记录 0.19.9 阶段的 P1/AppOpt 修复事实；其包版本、hash、旧 P2/provider、云控和下一步均已被 0.2 覆盖。P1 相机修复思路和 AppOpt 空规则门槛由 0.20.0 继续保留。

#### 设备实际状态

2026-08-17 已连接 TB321FU，ADB 已授权，`su` 可用且 SELinux 为 Enforcing。设备 App 虽然显示 `versionCode=28` / `versionName=0.19.9`，但系统内嵌 APK SHA256 是：

```text
4a6a48a92a32c64721b16b3e3069a826eed5a73918125e59260db76bad6449f6
```

它来自 2026-06-24 的旧构建，不是当时 2026-07-21 推荐包，也不是本节下面的新修复包。以后不能只用版本号判断是否刷到最新包，必须同时比较 APK 或 `super.img` hash。

旧设备上的只读检查结果：

- `zui_control` 存在；P1 状态为 `refreshOwner=system`、`daemonRefreshDisabled=true`，桌面目标和实际刷新率为 120Hz。
- P2 active XML 与 `/system/etc` 当前 bind 内容 hash 相同；鸣潮 active 条目已经是 `ThermalConfig=0 0 0`，但 system 默认模板仍是旧的 `200 100 300`，说明当前设备不是最新自动修复镜像。
- AppOpt 配置只有注释和示例，活动规则数为 0；旧 init 仍每约 5 秒重启 AppOpt，实际被 SELinux 拒绝 `dac_override` 及读取 `zui_control_data_file` 后退出。
- 已通过项目已有可逆开关执行 `setprop zui_control.appopt stop`，确认 `init.svc.zui_appopt=stopped` 且无 AppOpt PID。没有改 P1、P2 或用户 AppOpt 配置。

#### 相机导致“重启”的已确认根因

`/data/tombstones` 中至少有两组 2026-06-30 的相同崩溃链。真正先崩的是 SurfaceFlinger，不是相机：

```text
No matching frame rate modes for primary range.
physical=[120.00 Hz, 120.00 Hz]
render=[30.00 Hz, 60.00 Hz]
```

调用栈位于：

```text
RefreshRateSelector::constructAvailableRefreshRates
-> RefreshRateSelector::setPolicy
-> SurfaceFlinger::setDesiredDisplayModeSpecsInternal
-> abort
```

随后 CameraProvider 因 SurfaceFlinger Binder 死亡而以 `DEAD_OBJECT` 退出。因此“打开相机就重启”是 P1 刷新率硬投票制造无交集策略导致的系统图形栈崩溃，相机只是触发了 30–60 FPS 的合法约束。其他会提出不同渲染区间的 App 也可能触发同类问题。

旧实现还存在两个结构性问题：

1. 在优先级 8（ZUI 名称实际是 `PRIORITY_AUTH_OPTIMIZER_RENDER_FRAME_RATE`）写入了 `Vote.forPhysicalRefreshRates(hz, hz)`，投票类型与优先级语义不匹配。
2. `DisplayContent.setFocusedApp` 的同步 hook 直接执行 profile、Settings、反射和显示服务调用，违反焦点路径只能轻量上报事件的规则，也解释了除刷新率表面切换外其他联动显得慢和不稳定。

#### 2026-08-17 P1/AppOpt 修复

代码提交：

```text
61a4b26 Fix refresh vote crash and gate empty AppOpt
```

P1 只做根因级最小修复，没有进入 FPS cap：

- 删除 `forPhysicalRefreshRates(hz, hz)` 和 GameHelper 十秒 yield 补丁逻辑。
- 统一使用 `Vote.forRenderFrameRates(0, targetHz)`，配合 ROM 的目标 mode 和 `max=targetHz`；允许相机/视频的 30–60 render 约束与 120Hz 物理显示共存。
- `onFocusedAppChanged` 只提取 package/uid/user/display 原始值并投递到独立 `HandlerThread("ZuiControl")`；profile I/O、Settings、反射和 apply 不再阻塞 WM 焦点关键路径。
- 状态新增 `displayVote=adaptiveRender`。

稳定性优先带来的明确边界：新策略是“目标 mode + render 上限/偏好”，不再承诺在所有相机、视频、热控或系统冲突场景中物理面板绝对硬锁。60/90/120/144/165 的真实 active mode 仍需刷后逐项验证。

AppOpt 当前没有源码和 UI 规则编辑器，不应冒充完整交付。新包的默认行为改为：

- boot completed 只准备配置，不再无条件 `start zui_appopt`。
- daemon 统计有效 `key=value` 规则；0 条规则时拒绝启动、清理 enabled 标记并发布明确状态。
- 有规则时启动后必须连续确认 init 状态为 running 且 PID 存在，失败则停止服务并清理 enabled 标记。
- 外部兼容命令仍叫 `apply_asoul` / `restore_asoul`，内部实际函数和状态统一按 AppOpt 命名；不恢复旧 AsoulOpt。

#### 2026-08-17 最新待刷包

GitHub Actions：

```text
run_id=32013336830
workflow=Build ZuiControl
head=61a4b2622d7579036fdd2a7b04faa8983986bccc
status=success
artifact_dir=D:\3.VScode\Mi\work\ci_artifacts\zuicontrol_32013336830
package=com.zui.zuicontrol
versionCode=28
versionName=0.19.9
release_cert_sha256=3fecf3a72ca0e0f24991d49e7306ef4a711711f48a66070755eb0237ecb3ed94
```

最终刷机目录：

```text
D:\3.VScode\Mi\【B刷机】187
```

最终 SHA256：

```text
5fc24c5b36ba7394125b87b681b62e38575172057c78417a0fa24746815be76b  boot.img
34de9656be5f7473ffef6ec81070a8c5a409b3ad6a18af9a715eebed290adf2d  super.img
9db326d3d605885c4afdac6b0883dc3f9c0bc2b9b1b3766cc4808945015967f5  vbmeta.img
6d5bc0db0d9ed46aeef05b25c58b496e93e8569980189dbdd40af0b0aa1eab6f  vbmeta_system.img
3f6f09ca6452cd8b881fdd1ebf335405b9a1e9d8ad126783107d5f2f70113e5c  ZuiControl-v19-system.apk
3f6f09ca6452cd8b881fdd1ebf335405b9a1e9d8ad126783107d5f2f70113e5c  ZuiControl-v19-release.apk
```

发布过程只使用上述 CI release APK/payload；重建 `system_a` 后执行 `SignNoFec`，签名后重新执行 `PackSuper`。最终 verifier 已从成品 `super.img` 反抽 system/vendor 并得到 `ok=true`，还解码了成品 `services.jar`，确认：

- 存在 `HandlerThread`、`forRenderFrameRates`、`displayVote=adaptiveRender`。
- 不存在 `forPhysicalRefreshRates`。
- AppOpt init 不再无条件启动，daemon 含 0 规则门槛。
- APK 内嵌/两个 sidecar hash 一致，版本为 28/0.19.9 且为 release 签名。
- P2 鸣潮模板仍为 `ThermalConfig=0 0 0`，XML 五段引用、SELinux/context 和无 direct CPU/GPU sysfs 规则继续通过。

#### 唯一正确的下一步

新包尚未刷到真实设备，不能把“最终镜像静态通过”写成“相机问题实机已修复”。下一步只刷上述 hash 的包，然后按以下顺序验证：

1. 先比较设备 `/system/priv-app/ZuiControl/ZuiControl.apk` SHA256 必须为 `3f6f09...13e5c`，再确认 28/0.19.9。
2. 开机稳定性：确认 SystemUI/SurfaceFlinger/system_server 无崩溃循环，`zui_control` 存在，状态含 `displayVote=adaptiveRender`、`refreshOwner=system`、`daemonRefreshDisabled=true`。
3. P1：桌面和多个普通 App 默认 120；分别保存并测试 60/90/120/144/165，观察 target、actual active mode 和重复 apply 防抖。
4. 相机：先打开系统相机，再从第三方 App 调用相机；全程同步抓 SurfaceFlinger/CameraProvider tombstone、logcat 和 active mode，确认没有 `No matching frame rate modes`。
5. QS/SystemUI：下拉 QS 修改上一个真实场景，确认不会生成 SystemUI profile。
6. AppOpt：默认 0 规则时应为 stopped 且无重启/AVC；只有写入真实有效规则并明确启用后才应 running。由于暂无 UI 编辑器和源代码，先只验证门槛和稳定性，不宣称调度效果已交付。
7. P2：核对 active XML 与 `/system/etc` hash、鸣潮 `ThermalConfig=0 0 0`；进入、退出、再进入鸣潮，provider_direct 时间戳每次更新，并结合 ZuiPP/Thermal/KGSL 日志判断实际应用。
8. 云控：核对 hosts、iptables/ip6tables 独立 UID 规则，确认无 UID1000 全断规则。
9. AVC：检查 zui_control、system_server、AppOpt/performanced、shell/init、ZuiPP 和云控相关 denial。

上述验证通过前，不再改 P1，不进入 FPS cap，不恢复 direct CPU/GPU sysfs，也不重新引入旧 AsoulOpt。

> **历史区提示：** 以下第 1～8 节保留 2026-07-21/0.19.9 的调查证据和旧实现说明。凡涉及当前包、P2 三模式/provider、云控或下一步，不得直接执行，必须以第 0.2 节为准。

## 1. 2026-07-21 历史快照

```text
工作区：D:\3.VScode\Mi
当前 App 仓库：D:\3.VScode\Mi\ZuiControl
历史旧仓库：D:\3.VScode\Mi\ZuiperfCtl（不要作为当前代码入口）
设备/系统：TB321FU / ZUI 16.1.11.187
分支：main
remote：git@github.com:xmy953538104/ZuiControl.git
仓库交接前 HEAD：7195c2651eb38da9b3fecd7178298be7957e0092
当前 App：versionCode=28 / versionName=0.19.9
```

2026-07-21 整理交接时设备未连接，因此本文件没有把 2026-06-24 的最终镜像冒充成“已经刷后验证通过”。最新镜像已完成本地构建、签名、PackSuper 和 final super 反抽验证；刷后实机状态仍需新窗口重新确认。

## 2. 历史刷机包（已被 0.20.0 替代）

目录：

```text
D:\3.VScode\Mi\【B刷机】187
```

最新功能代码提交：

```text
6e698a1 Fix Mingchao XML thermal application
```

后续文档提交：

```text
7195c26 Document Mingchao XML thermal fix
```

GitHub Actions：

```text
run_id=28089289333
workflow=Build ZuiControl
head=6e698a1693e64e457c66e289a7ab032149e0fd5e
status=success
artifact_dir=D:\3.VScode\Mi\work\ci_artifacts\zuicontrol_28089289333
```

APK：

```text
package=com.zui.zuicontrol
versionCode=28
versionName=0.19.9
release cert SHA-256=3fecf3a72ca0e0f24991d49e7306ef4a711711f48a66070755eb0237ecb3ed94
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

验证器已从最终 `super.img` 反抽真实 system/vendor 内容，不只是检查源码或 `work/unpack`。

## 3. P1 刷新率状态

P1 已经完成 system_server ownership 迁移并经过旧包实机验证。本轮交接没有修改 P1。

主链路：

```text
DisplayContent.setFocusedApp(ActivityRecord)
-> ZuiControlHooks.onFocusedAppChanged(record, displayId)
-> ZuiControlService
-> /data/system/zui_control/profiles.prop + 内存 profile
-> DisplayManagerInternal display vote
-> active display mode
```

必须保持：

- `system_server` 是唯一刷新率 owner。
- 未配置场景默认 120Hz。
- QS 修改 `lastNonTransientScenePackage`，不能学习 SystemUI。
- daemon 不恢复 `refresh_tick`、`learn_refresh`、peak/min settings 抢写或开机强写 120。
- v19 只承诺 displayHz lock，不把 FPS cap 当作已交付能力。

## 4. P2 XML 当前实现

生产主链路：

```text
ZuiControl 保存性能 profile
-> zui_controld 写 /data/vendor/zui_control/performance/profiles.prop
-> XmlProfileGenerator 生成 staging game_policy.xml/performanceconfig.xml
-> 校验并 promote 到 /data/vendor/zui_control/zuipp/active/
-> bind mount 到 /system/etc/game_policy.xml 和 performanceconfig.xml
-> hash 闭合后受控 reload com.zui.pp
-> system_server 场景事件进入目标 App
-> daemon 直接 content update ZuiPP GameModeProvider/contact
-> ZuiPP 按当前 gameMode 重新应用 XML LimitConfig
```

边界：

- CPU/GPU 都走 XML + ZuiPP/GameHelper 原厂链路。
- daemon 不生产直写 CPU cpufreq 或 KGSL GPU sysfs。
- `balanced=0`、`powersave=1`、`savage=2`。
- 每次重新进入有 profile 的 App，`apply_pp_mode_for_scene "$top" force` 重发一次 provider；不常驻轮询。

### 鸣潮最新修复

鸣潮包名：

```text
com.kurogame.mingchao
```

已确认旧问题不是 XML 没生成或 bind mount 失败。ZuiPP 已读到用户 `LimitConfig`，但同一 App 条目的 `ThermalConfig=200 100 300` 会选择 vendor thermal user case；现场 `200 -> battery_2/gpu_0` 后，KGSL 曾被压到约 `thermal_pwrlevel=10`、`max_gpuclk=310000000`。

当前修复：

- `XmlProfileGenerator` 对用户生成的独立游戏条目统一写 `ThermalConfig=0 0 0`。
- 默认模板中鸣潮条目也是 `ThermalConfig=0 0 0`。
- 这只避免主动切到 100/200/300 游戏热控 user case，不关闭系统全局过热保护。
- final verifier 会拒绝鸣潮模板不是 `0 0 0` 的刷机包。

2026-06-24 现场临时验证：改为 `0 0 0` 并 reload 后，观察到 `thermal_pwrlevel=0`、`max_gpuclk=903000000`。这证明旧 user case 是冲突源，但最终 2026-06-24 镜像仍需刷后重新验证完整自动链路。

## 5. P3 线程放置当前实现

AGENTS.md 把产品功能称为 `asoulOpt` 线程放置/亲合度模块；当前实际系统后端已经替换为 AppOpt。新窗口必须区分“功能名/兼容命令名”和“真实运行二进制”。

当前真实后端：

```text
/system/bin/AppOpt
/system/etc/init/zui_appopt.rc
/system/etc/zui_control/default_applist.conf
/system/etc/zui_control/zui_appopt_prepare.sh
/data/vendor/zui_control/appopt/applist.conf
service=zui_appopt
```

已从镜像删除旧项：

```text
/system/bin/AsoulOpt
/system/etc/asopt.conf
/system/etc/init/zui_asoulopt.rc
/system/etc/zui_control/asopt.conf
/system/etc/zui_control/default_asopt.conf
/system/etc/zui_control/zui_asoulopt.sh
```

兼容遗留：

- daemon 请求名仍有 `apply_asoul` / `restore_asoul`，实际操作 AppOpt。
- UI 仍有 `AsoulOpt` 文案，状态正文会显示“后端：AppOpt”。
- `zui_appopt_prepare.sh` 会清理可能残留的旧 `AsoulOpt` 进程。
- 不要因为兼容名字重新把旧 AsoulOpt 二进制和三份 conf 加回来。

当前 SELinux 修复：

- `/system/bin/AppOpt` 标记为 `performanced_exec`。
- `performanced` 获得读取 `/data/vendor/zui_control/appopt/applist.conf` 所需的目录/file 权限和 `dac_override`。
- Windows 侧没有 `secilc` 完整编译验证，刷后仍必须检查 AVC。

## 6. 云控屏蔽历史实现（0.20.0 已删除）

当前不再一刀切 UID1000，也不冻结 `com.zui.pp`。

两层实现：

1. `/system/etc/hosts` 静态屏蔽已知 Lenovo/ZUI 云控域名。
2. `zui_cloud_block.sh` 用 iptables/ip6tables 阻断少数独立 UID 云控包。

独立 UID 目标包括：

```text
com.zui.game.service
com.zui.engine
com.lenovo.tbengine
com.lenovo.leos.cloud.sync
com.tblenovo.tabpushout
com.tblenovo.center
```

明确边界：

- 不含 `--uid-owner 1000`。
- `com.zui.pp`、`com.zui.cores`、`com.zui.safecenter` 不被共享 UID 一刀切。
- hosts 是静态规则，不做后台抓包或持续网络扫描。
- hosts 只匹配明确域名，不支持通配所有未来子域名或硬编码 IP。

## 7. 0.19.9 历史验证步骤（不要用于 0.20.0）

先确认设备是否已经刷入上述最终包，再做刷后验证。2026-07-21 当前设备未连接，不能跳过这一步。

连接设备后先运行：

```powershell
$adb = "D:\3.VScode\Mi\tools\adb\adb.exe"
& $adb get-state
& $adb shell su -c id
& $adb shell dumpsys package com.zui.zuicontrol | findstr "versionCode versionName"
& $adb shell service list | findstr zui_control
& $adb shell dumpsys zui_control
```

预期 App 为 `28 / 0.19.9`。如果不是，不要拿旧包状态判断新修复，应先刷 `D:\3.VScode\Mi\【B刷机】187`。

刷后按顺序验证：

1. P1：`zui_control` 存在，`refreshOwner=system`，`daemonRefreshDisabled=true`，场景切换仍正常。
2. P3：`init.svc.zui_appopt=running`，`pidof AppOpt` 有 PID，配置可读，无 AppOpt/performanced AVC。
3. 云控：状态键、hosts 和 iptables/ip6tables 链存在，确认没有 UID1000 全断规则。
4. P2：active XML 与 `/system/etc` hash 一致，鸣潮条目 `ThermalConfig=0 0 0`。
5. 进入鸣潮：`zui_control_pp_mode_state` 更新为当前时间、`state=triggered;stage=provider_direct;package=com.kurogame.mingchao;mode=0;xml=...`。
6. 退出再进鸣潮：时间戳再次更新，证明 scene-enter force retrigger 生效。
7. 读取 ZuiPP/Thermal/KGSL 日志和节点，区分 XML 没应用、vendor user case 限制和真实高温全局降频。
8. 检查 `dmesg/logcat` 中与 zui_control、AppOpt、cloud block、ZuiPP 相关的 AVC。

未完成上述刷后验证前：

- 不进入 FPS cap。
- 不修改 P1。
- 不恢复 direct CPU/GPU sysfs。
- 不重新引入旧 AsoulOpt 二进制/conf。
- 不根据旧 0.19.8/P2-I 文档判断当前 0.19.9 包。

## 8. 关键代码入口

```text
P1 system_server:
ZuiControl/framework_patch/src/services/com/zui/server/control/ZuiControlService.java
ZuiControl/framework_patch/src/services/com/zui/server/control/ZuiControlHooks.java

P2:
ZuiControl/app/src/main/java/com/zui/zuicontrol/XmlProfileGenerator.java
ZuiControl/app/src/main/java/com/zui/zuicontrol/PerformanceProfile.kt
ZuiControl/payload/system/bin/zui_controld
ZuiControl/payload/system/etc/zui_control/default_game_policy.xml
ZuiControl/payload/system/etc/zui_control/default_performanceconfig.xml

P3/AppOpt:
ZuiControl/payload/system/bin/AppOpt
ZuiControl/payload/system/etc/init/zui_appopt.rc
ZuiControl/payload/system/etc/zui_control/zui_appopt_prepare.sh
ZuiControl/payload/system/etc/zui_control/default_applist.conf

云控：
ZuiControl/payload/system/etc/zui_control/zui_cloud_block.sh
ZuiControl/payload/system/etc/hosts

打包/验证：
ZuiControl/scripts/ApplyZuiControlPayload.py
ZuiControl/scripts/VerifyZuiControlFlashPackage.ps1
```

## 9. 新聊天首条消息

复制以下内容给新窗口：

```text
请先完整读取 D:\3.VScode\Mi\AGENTS.md 和 D:\3.VScode\Mi\ZuiControl\docs\ZuiControl_AI_NEW_CHAT_HANDOFF_2026-07-21.md；涉及 P2 XML/ThermalConfig 时再读取 D:\3.VScode\Mi\ZuiControl\docs\ZuiControl_P2_XML_THERMALCONFIG_GUIDE_2026-07-21.md。旧的 2026-06-21 P2-I/asoulOpt 入口和大篇历史文档只作历史，若与主交接冲突，以 AGENTS.md 和主交接第 0.2 节为准。

当前工作区是 D:\3.VScode\Mi，当前仓库是 D:\3.VScode\Mi\ZuiControl。先读本文件第 0.2 节的 2026-08-17 最新覆盖说明；后面的 2026-07-21 和 0.1 中的三模式/provider、云控、28/0.19.9 包 hash 与验证步骤均已被覆盖。最新待刷包在 D:\3.VScode\Mi\【B刷机】187，super SHA256 应为 ddfa388d1df10f6b609337af4b07ed5ca52a6d4602aebaab1ba88fec0a3970ef，内嵌 APK 应为 66488caf0f2570770f0a6b50d3d74efded47dd172f4334c2469c996495590ee6（29/0.20.0）。先只读确认设备是否已经刷入该包。刷后完整验证 P1 相机/active mode、P2 鸣潮单 profile/三槽镜像/共享 CPU ID/ZuiPP 稳定重启和退出重入、AppOpt 空规则门槛、系统默认 hosts/旧云控残留清理及 AVC；不要再测试已删除的 provider_direct/云控链。通过前不进入 FPS cap、不再改 P1、不恢复 direct CPU/GPU sysfs，也不重新引入旧 AsoulOpt。
```
