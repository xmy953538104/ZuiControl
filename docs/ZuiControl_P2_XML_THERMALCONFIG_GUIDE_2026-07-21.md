# ZuiControl P2 XML 与 ThermalConfig 读法 - 2026-07-21

## 0. 2026-08-20 当前生产规则

本节覆盖本文后面关于“三个用户 profile、daemon 选择 gameMode、GameModeProvider 重入”和旧固定延时保存流程的说明；后文 XML 字段、ThermalConfig 和原厂热控原理仍有效。

当前成品 App 为 `versionCode=37` / `versionName=0.21.0`，生产 commit 为 `36d6b26c05e5c0ecfca04ae78120aede51f1d8a2`，GitHub Actions run 为 `32266515192`。最终 `super.img` SHA256 为 `b43375c2c3c53ae5df8f23f6f658ae97cc7b76b5501012fec93cf1c60db173ae`，成品反抽 verifier 已返回 `ok=true`。设备已经通过最小化 7 项 9008 XML 持久刷入 0.21.0；正常重启后 boot bind 触发 ZuiPP `4620 -> 6636` 并得到 `state=done;reason=boot_active;stableSeconds=3`，亮屏重入鸣潮又闭合 `onGameAppStart -> GameHelper -> notifyPerfStatus -> writeSavageMode`。V37 同值保存的真实 UI 操作约 9.8 秒并合法 `skipped;same_hash`；输入改变 XML 时仍执行新 PID 与 3 秒稳定窗。当前不再是临时 daemon 验证状态。

当前生产模型：

```text
App 发送唯一 requestId，等待同 ID/同 command 的 terminal ACK
-> 保存前查询目标是否已在 ZUI Game Assistant；若没有则加入自定义游戏列表
-> daemon 备份旧 profile、旧 active XML 与事务 marker
-> 每个 package 生成一份 canonical profile
-> 每个温区分配一个共享 CPU level ID
-> Little/Big/Titan/Mega 四个 Type 用同一 ID、各存自己的频率值
-> 同一 LimitConfig 镜像到 balanced / powersave / savage
-> 校验、promote、bind active XML
-> ZuiPP 在运行时先停止四个原厂 service，再受控重启并等待新 PID 稳定 3 秒；未运行则记录合法 skipped
-> 同步 Game Assistant membership；新增 profile 时添加、删除 profile 时删除自定义条目
-> 原子提交 membership + profile + runtime + terminal ACK；失败则恢复整笔旧状态
-> 保存成功后自动停止当时正在运行的目标；未运行则跳过
-> 用户从桌面重新进入 ZUI 已识别的游戏
-> 原厂 onGameAppStart / GameHelper 选择任一模式，得到相同配置
```

0.20.1 起沿用的可靠性规则：

- App 不再用 720ms/13s 定时器猜完成，也不乐观改 UI 列表。ACK 固定为四段：`id|processing|cmd|`、`id|done|cmd|detail`、`id|failed|cmd|reason`；只有 exact terminal ACK 才允许下一条请求覆盖 Settings.System 单槽。
- daemon 把原请求和终态 ACK 写成两行原子 receipt，可在重启后 replay；profile、active XML、mount/reload 是一笔事务。任何阶段失败都必须恢复旧 profile、旧 active 并真实 reload 旧 runtime，不能只报错却留下三层不一致。
- 每次 daemon 启动都清除旧 reload receipt。即使 active hash 与上次相同，boot bind 后若 ZuiPP 正在运行，也必须确认新 PID 稳定 3 秒；若尚未运行，允许 `skipped;reason=zuipp_not_running`，其首次启动会读取已 bind 的 active XML。不能用跨开机 remembered hash 跳过一次本应执行的 reload。3 秒仍是必要的新进程立即崩溃观察窗，不能只看到 PID 变化就报成功。
- `last_good` 保存 promote 前的旧 active；成功后不被新 active 覆盖。
- 0.20.2 实机发现裸 SIGTERM 会使原厂 `OverHeatCleanService` 在 persistent 进程重启时收到空 Intent 并 NPE。0.20.3 在 SIGTERM 前精确停止 OverHeatStats、OverHeatClean、StubbornStats 和 MainService，但错误地相信 `am stop-service` 退出码，且把 daemon 日志 FD 传入 Binder；本机成功/no-op 的退出码均为 255，继承 FD 又会触发 system_server append AVC/Failed transaction。0.20.4 改为先捕获输出、再由 daemon 写日志，仅接受 `Service stopped` 或 `Service not stopped: was not running.`，瞬时失败重试最多 5 次；没有扩大 SELinux 权限。

原因和边界：

- 设备实测 ZuiPP 的 CPU Type 读取顺序不是 XML 文档顺序。旧版为四簇分配不同 level ID 时，错误 Type 查找可能缺少 level，TAssistant 会放弃整条策略。共享 CPU ID 消除了顺序依赖。
- daemon 直接调用 `GameModeProvider/contact` 会出现“命令成功但 ZuiPP 没有目标游戏状态”的假成功，现已删除。OEM GameHelper 本来就会在真实 `onGameAppStart` 时调用 provider；provider rows=1 也只能表示模式值被接受，不能单独证明 LimitConfig 已下发。
- ZuiControl 保存 P2 前会通过 `zuimode` 检查 Game Assistant；目标不在其中时自动添加自定义条目。删除 ZuiControl P2 profile 时同步删除对应自定义条目。这个同步严格只从 ZuiControl 指向 Game Assistant：Game Assistant 单独添加或删除都不会反向创建或删除 P2 profile。系统内建识别不是自定义条目，不做伪删除。membership/profile/XML/runtime 是同一事务，任一步失败都会恢复旧状态。
- ZuiPP 被重启后，TAssistant/原厂 game callback 要到下一次真实游戏启动才重新建立。保存成功后 daemon 会自动停止当时正在运行的目标；未运行时不做无意义的停止。UI done 后用户直接从桌面重新打开即可，不再要求去系统设置手动强停。
- “息屏启动”只指旧测试曾在屏幕关闭时用 adb 拉起 App，不是产品流程。正常亮屏从桌面启动才是交付路径；仅有 Activity 焦点而没有 `onGameAppStart` 不能判为 ZUI 游戏态。
- P2 添加器只列已安装、可启动的 `/data/app` 用户 App；保存又会确保 Game Assistant membership，因此不再依赖用户手工两地添加。
- XML 的 CPU/GPU 上下限是 OEM 性能请求，不是不可被覆盖的硬上限。全局 thermal、厂商性能服务或更高优先级请求仍可能改变最终节点值；节点不瞬时等于输入值不能单独判为失败。
- 不恢复 daemon direct cpufreq/KGSL sysfs，不进入 FPS cap。

当前排查顺序：

1. 请求 ID 是否收到同 ID、同 command 的 `done`；处理中应看到真实 stage（如 `generating_xml`、`reloading_zuipp`、`syncing_game_assistant`、`committing`）。若为 `failed`，先看 reason，禁止继续覆盖请求槽。
2. `profiles.prop` 中每个 package 是否只有一条 canonical `balanced` 记录。
3. `xml_state=state=mounted`，active 与 `/system/etc` 是否同 hash 且确为 bind mount。
4. 目标 App 的三个 LimitConfig mode block 是否完全一致。
5. 每个温区 Little/Big/Titan/Mega 四个 ID 是否相同，并在四个 Type 中都存在。
6. ZuiPP 正在运行时，四个原厂 service 是否先成功停止，reload 是否 `state=done`、`stableSeconds=3`、`needsAppReenter=1`，并且只有一次干净 PID 切换、没有 `OverHeatCleanService` fatal/NPE；未运行时允许 `state=skipped;reason=zuipp_not_running`。只有输入/输出 hash 确实未变时才接受 `skipped;same_hash`，而每次 daemon 新启动不能用上次开机 receipt 跳过一次本应执行的 reload。
7. 确认 Game Assistant membership 与操作方向一致，运行中的目标已自动停止或返回 `target=not_running`，再从桌面重新进入；按顺序检查同一 package 的 `onGameAppStart`、GameHelper、ZuiPP `notifyGameAppStateChanged`、非空 LimitConfig/TAssistant 下发。
8. 再读 CPU/GPU 最终节点，并区分用户请求、厂商覆盖和真实高温降频。
9. 检查 dmesg 与全 buffer logcat 中相关 AVC。

## 1. 两份 XML 各负责什么

`game_policy.xml` 是“游戏条目和策略选择表”：

- 哪个包是游戏。
- 均衡/省电/野兽分别选哪段策略。
- 每个温度档引用哪些 CPU/GPU level。
- 选择哪个 vendor thermal code。
- ZuiPP/GameHelper 相关刷新率字段。

`performanceconfig.xml` 是“level 字典”：

- 温度 level 对应多少摄氏度。
- Little/Big/Titan/Mega/GPU level 对应什么上下限。
- ZuiPP 通过哪种 thermal 接口下发。

运行时文件：

```text
/data/vendor/zui_control/zuipp/active/game_policy.xml
/data/vendor/zui_control/zuipp/active/performanceconfig.xml
```

它们 bind mount 到：

```text
/system/etc/game_policy.xml
/system/etc/performanceconfig.xml
```

模板位于：

```text
/system/etc/zui_control/default_game_policy.xml
/system/etc/zui_control/default_performanceconfig.xml
```

模板不是运行时 active 文件。ZuiControl 保存 profile 后，`XmlProfileGenerator` 会从模板/基线和用户 profile 生成 staging，再由 daemon 校验、promote、bind 和 reload ZuiPP。

## 2. 一个 App 条目怎么读

简化结构：

```xml
<App name="WutheringWaves" pkg="com.kurogame.mingchao">
    <Attribute name="ThermalConfig">0 0 0</Attribute>
    <Attribute name="RefreshRateConfig">60</Attribute>
    <Attribute name="SpecialBoostConfig">NoBoost NoBoost NoBoost</Attribute>
    <Attribute name="PowerSaveRefreshRateConfig">60</Attribute>
    <Attribute name="LimitConfig">均衡段 省电段 野兽段</Attribute>
</App>
```

`pkg` 是匹配包名。`LimitConfig` 的三大段用空格分隔，顺序固定：

```text
第 1 段：balanced / gameMode=0 / 均衡
第 2 段：powersave / gameMode=1 / 省电
第 3 段：savage / gameMode=2 / 野兽
```

`ThermalConfig` 同样按三个模式位置选择：

```text
ThermalConfig=200 100 300
均衡 -> thermal code 200
省电 -> thermal code 100
野兽 -> thermal code 300
```

这里的 200/100/300 不是温度，不是 MHz，也不是百分比；它们是 vendor BSP thermal user-case 编号。

## 3. LimitConfig 语法

每个模式段内部可以有多个温区，以 `|` 分隔：

```text
温度level:Little_Big_Titan_Mega_GPU|温度level:Little_Big_Titan_Mega_GPU
```

例如现场生成过的鸣潮均衡段：

```text
-1000:118_132_129_135_804|8:118_132_129_135_801|14:118_132_129_135_801
```

含义：

```text
-1000 默认温区 -> Little 118 / Big 132 / Titan 129 / Mega 135 / GPU 804
8      42C 温区 -> Little 118 / Big 132 / Titan 129 / Mega 135 / GPU 801
14     48C 温区 -> Little 118 / Big 132 / Titan 129 / Mega 135 / GPU 801
```

温度 level 在 `performanceconfig.xml` 中定义：

```xml
<Type name="TempLevel">
    <Temp level="-1000">default</Temp>
    <Temp level="8">42</Temp>
    <Temp level="14">48</Temp>
</Type>
```

下划线后的五个 ID 必须依次对应：

```text
LittleCore_BigCore_TitanCore_MegaCore_GPU
```

少一段 MegaCore 会导致 ZuiPP/TAssistent 拒绝策略；这也是旧 P2-H 曾修过的问题。

## 4. CPU/GPU level 怎么翻译

`game_policy.xml` 只引用 level ID，实际内容在 `performanceconfig.xml`：

```xml
<Type name="LittleCore">
    <Freq level="118">2265600_364800_-1</Freq>
</Type>
```

CPU 项可读作：

```text
level 118 -> max 2265600KHz / min 364800KHz / boost -1
```

GPU 使用频率索引。设备频率表从高到低：

```text
index 0  = 903MHz
index 1  = 834MHz
index 2  = 770MHz
index 3  = 720MHz
index 4  = 680MHz
index 5  = 629MHz
index 6  = 578MHz
index 7  = 500MHz
index 8  = 422MHz
index 9  = 366MHz
index 10 = 310MHz
index 11 = 231MHz
```

例如生成器创建：

```xml
<Freq level="804">3_7_-1</Freq>
```

含义是：

```text
GPUMax index 3 -> 720MHz
GPUMin index 7 -> 500MHz
```

`804` 是 ZuiControl 生成的唯一 level ID，不等于 804MHz。

## 5. ThermalConfig 后续系统逻辑

设备 `/vendor/etc/thermal_user_case.conf` 实测映射：

```text
0,sensor_0,cpu_0,gpu_0,fan_0,common_0,battery_0,
100,sensor_0,cpu_1,gpu_0,fan_0,common_0,battery_1,
200,sensor_0,cpu_2,gpu_0,fan_0,common_0,battery_2,
300,sensor_0,cpu_3,gpu_0,fan_0,common_0,battery_3,
700,sensor_0,cpu_4,gpu_1,fan_0,common_0,battery_3,
```

所以均衡模式选 code 200 时，thermal-engine 会组合：

```text
sensor_0 + cpu_2 + gpu_0 + fan_0 + common_0 + battery_2
```

设备 `/vendor/etc/thermal-engine_gpu_0.conf` 的关键档位：

```text
quiet-therm 44C -> GPU max 720MHz
quiet-therm 46C -> GPU max 629MHz
quiet-therm 48C -> GPU max 500MHz
quiet-therm 50C -> GPU max 231MHz
```

设备 `/vendor/etc/thermal-engine_battery_2.conf` 的关键档位：

```text
quiet-therm 30/32/34/36/38/40C
-> battery action 7/8/9/10/10/12
```

因此最终 GPU 不是只由 `LimitConfig` 决定：

```text
最终可用 GPU 范围
= ZuiPP/LimitConfig 请求
与 vendor thermal user case / 全局热控限制共同作用的结果
```

旧鸣潮问题就是两条链路同时生效：ZuiPP 已读取 `GPUMax=3 / GPUMin=7`，但 `ThermalConfig=200` 又选择 `battery_2/gpu_0`，最终 KGSL 被压到约 310/231MHz。

## 6. 为什么改成 0 0 0

当前策略：

```xml
<Attribute name="ThermalConfig">0 0 0</Attribute>
```

含义是三个 GameHelper 模式都选择 thermal user case 0，避免由游戏 XML 主动切到 100/200/300 的额外厂商策略。

它不表示：

- 关闭所有温控。
- 让 GPU 永不降频。
- 绕过内核或硬件保护。
- 强制 GPU 一直跑最高频。

设备真实过热时，系统全局 thermal/KGSL 仍然可以降频。`0 0 0` 只移除已经实测会与用户 XML 冲突的游戏 thermal user-case 切换。

## 7. 完整运行流程

```text
用户保存 profile
-> XmlProfileGenerator 生成两份 XML
-> zui_controld 校验 staging
-> promote active
-> bind 到 /system/etc
-> hash 闭合后 reload com.zui.pp
-> 进入目标 App
-> system_server 发布真实场景
-> daemon 以 gameMode=0/1/2 调 GameModeProvider/contact
-> ZuiPP 按包名找到 App 条目
-> 按 gameMode 选择 LimitConfig 第 1/2/3 段
-> 按同一模式位置选择 ThermalConfig code
-> LimitConfig 走 CPU/GPU 性能下发
-> ThermalConfig code 走 vendor thermal user case
-> KGSL/cpufreq 呈现两条限制共同作用后的实际值
```

## 8. 排查顺序

遇到“XML 没生效”时按顺序判断：

1. profile 是否存在且内容正确。
2. staging/active XML 是否生成成功。
3. active 与 `/system/etc` 是否同 hash、确实 bind mounted。
4. ZuiPP 是否完成受控 reload。
5. 当前 package 与 gameMode 是否正确。
6. `zui_control_pp_mode_state` 是否在每次重新进入时更新时间戳。
7. ZuiPP 日志是否解析到 Little/Big/Titan/Mega/GPU 五段。
8. `ThermalConfig` 选择了哪个 user case。
9. KGSL/cpufreq 的最终节点值是多少。
10. 当前是否是真实高温导致的全局降频，或存在 AVC 阻断。

不要在第 3 步通过后就直接宣布成功，也不要在第 9 步不符合预期时立刻回退到 daemon 直写 sysfs。
