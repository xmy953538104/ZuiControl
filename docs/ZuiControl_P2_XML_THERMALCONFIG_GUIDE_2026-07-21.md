# ZuiControl P2 XML 与 ThermalConfig 读法 - 2026-07-21

## 0. 2026-08-21 当前生产规则

本节覆盖本文后面关于“三个用户 profile、daemon 选择 gameMode、GameModeProvider 重入”和旧固定延时保存流程的说明；后文 XML 字段、ThermalConfig 和原厂热控原理仍有效。

当前成品 App 为 `versionCode=40` / `versionName=0.21.3`，生产代码 commit 为 `1b58816`，GitHub Actions run 为 `32392494646`。最终 `super.img` SHA256 为 `25b8d9f0c80bed77c660200abd3cca92dff67db5549d19a0c94892d150ac6f3b`，成品反抽 verifier 已返回 `ok=true`。设备 `HA25HSZM` 已通过安全 7 项 9008 流程持久刷入 V40，并完成 P2 可逆修改/恢复、正常游戏重入和温控实测。真实改变保存实测 13.03–21.35 秒；目标运行时自动停止，未运行时返回 `target=not_running`。App 仍先比较 canonical 配置：完全同值时小于 1 秒返回，不写请求槽、不重启 ZuiPP、不停止目标；真实改变 XML 时执行受控重入。

### 0.1 2026-08-21 GPU/温控追加现场

- `8_0_-1` 仍是 SM8650 上用户 422–903MHz 的正确 XML 方向。本轮两次正常重入均闭合 OEM 四段链，ZuiPP JSON 也精确读到 `GPUMax=8/GPUMin=0`；不要因 KGSL 最终被压到 578/422MHz 就把 XML 改回旧的 `0_8`。
- GPU 并非硬锁 903：governor 为 `msm-adreno-tz`、三个 force 开关为 0，`trans_stat` 记录所有 12 档和 1885 次切换。必须同时采样频率和 `gpu_busy_percentage`；高频且高忙碌率是正常跑满，高忙碌率但低上限才是瓶颈。
- 高负载现场出现 CPU/GPU junction 95C、skin 46.617C severe；KGSL 从 578MHz 降到 422MHz且忙碌率 83–86%。当时 quiet 约 40C，低于 `gpu_0.conf` 的 44C 第一档，因此 422 不是该文本表直接触发。
- 退出并完全冷却到 skin/quiet/back 约 33C 后，标准 GPU cooling state 已为 0，但 `thermal_pwrlevel=6/max=578MHz` 仍未释放；随后再次正常游戏重入、P2 链完整也未恢复 903。当前应按“热限释放滞后或其他策略残留”继续定位，先做经用户确认的冷机重启 A/B，禁止直接改 XML 或 sysfs掩盖。
- `thermal-engine-v2` 二进制内置 `SS-GPU-SKIN`、`SS-GPU-SKIN-TEMP`、`SS-SKIN-GPU-LOW/HIGH`、`GPU-TSKIN-SENSOR` 等非文本 profile，并直接持有 `max_gpuclk` 路径；Thermal HAL 另有 skin 46.5C、CPU/GPU junction 95C 静态阈值。因此三份 case 0 文本 conf 只是一层，不拥有最终安全上限。
- case 0 的 CPU 文本档位从 quiet 40C 开始，GPU文本档位从44C开始；`battery_0` 只是映射 vendor virtual cooling state。本轮 battery state 从12降到10/7而GPU仍为578，不能把它当成唯一写入者。

当前生产模型：

```text
App 先比较 canonical 输入与现有 profile；完全相同则本地完成，不发请求
否则：
App 发送唯一 requestId，等待同 ID/同 command 的 terminal ACK
-> 保存前查询目标是否已在 ZUI Game Assistant；若没有则加入自定义游戏列表
-> daemon 备份旧 profile、旧 active XML 与事务 marker
-> 每个 package 生成一份 canonical profile
-> 每个温区分配一个共享 CPU level ID
-> Little/Big/Titan/Mega 四个 Type 用同一 ID、各存自己的频率值
-> 同一 LimitConfig 镜像到 balanced / powersave / savage
-> 校验、promote、bind active XML
-> ZuiPP 在运行时先顺序停止 GameHelper 和 ZuiPP 已知 services，再受控重启 ZuiPP、等新 PID 稳定 3 秒、显式启动 PerformanceConnect、预热 GameHelper 并等新 PID 稳定 2 秒；未运行则记录合法 skipped
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
- 0.21.2 只等 ZuiPP 新 PID 的重载不够：GameHelper sticky services 可能错过重入，新 ZuiPP 的 `PerformanceConnectHelper` 在 `PerformanceConnect` service 创建前为 null，provider 会在 `sendLimitInner` NPE。0.21.3 必须把 GameHelper 停启、PerformanceConnect 初始化和 GameHelper 预热放进同一 reload gate，才能发 done。
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
6. ZuiPP 正在运行时，GameHelper/ZuiPP 原厂 services 是否先成功停止，reload 是否 `state=done`、PP `stableSeconds=3`、PerformanceConnect 已启动、GameHelper 新 PID 稳定 2 秒、`needsAppReenter=1`，并且没有 `OverHeatCleanService` fatal/NPE 或 `PerformanceConnectHelper` null NPE；任一 `stop_game_helper/performance_connect/prewarm_game_helper` error 都是失败。未运行时允许 `state=skipped;reason=zuipp_not_running`。只有输入/输出 hash 确实未变时才接受 `skipped;same_hash`，而每次 daemon 新启动不能用上次开机 receipt 跳过一次本应执行的 reload。
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

注意：用户界面/API 仍使用正常的“最低频率—最高频率”语义，但 SM8650 TAssistant 的实际消费方向与 XML 日志里 `GPUMax/GPUMin` 的表面名字相反。0.21.3 生成器因此必须写：

```text
XML = 用户最低频率的 index _ 用户最高频率的 index _ -1
```

例如用户设置 GPU 500–720MHz，生成器创建：

```xml
<Freq level="804">7_3_-1</Freq>
```

含义是：

```text
第一值 index 7 -> 用户最低 500MHz
第二值 index 3 -> 用户最高 720MHz
```

`804` 是 ZuiControl 生成的唯一 level ID，不等于 804MHz。

这不是仅凭名字猜测的兼容处理。设备可逆实验中，用户 422–903MHz 的旧 `0_8_-1` 会在冷机、thermal state=0 时把 KGSL 锁到 422MHz；换成 `8_0_-1` 后，ZuiPP 日志虽显示 `GPUMax=8,GPUMin=0`，但内核结果正确变成 `thermal_pwrlevel=0/max_gpuclk=903MHz`，实时频率可动态波动。

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

历史旧鸣潮问题曾有两条链路同时生效：`ThermalConfig=200` 会选择更激进的 `battery_2/gpu_0`；此外，0.21.2 以及更早生成器还将 GPU 索引方向写反，使用户设定的“最低频率”成为实际上限。因此 422/500MHz 长期锁频首先要查 XML 值顺序，然后才能根据当时 `quiet-therm` 判断 vendor thermal 是否又在叠加限制。

## 6. 为什么改成 0 0 0

当前策略：

```xml
<Attribute name="ThermalConfig">0 0 0</Attribute>
```

含义是三个 GameHelper 模式都选择 thermal user case 0，避免由游戏 XML 主动切到 100/200/300 的额外厂商策略。case 0 仍然组合 `sensor_0 + cpu_0 + gpu_0 + fan_0 + common_0 + battery_0`，所以它仍保留完整安全热控。

它不表示：

- 关闭所有温控。
- 让 GPU 永不降频。
- 绕过内核或硬件保护。
- 强制 GPU 一直跑最高频。

设备真实过热时，vendor `thermal-engine-v2`、Lenovo performance service 和 Qualcomm KGSL/HAL 仍然可以降频。`0 0 0` 只移除已经实测会与用户 XML 冲突的 100/200/300 游戏 thermal user-case 切换，不移除 case 0 本身的 `cpu_0/gpu_0/battery_0`。

温度判断必须区分三类传感器：

- ZuiControl/P2 温区选档用 `back_temp`（实机 thermal zone 55；zone 54 是 `front_temp`）。level 8/14 通过平台 +34 映射为 42/48C；当前鸣潮 profile 确实有默认、42C、48C 三档。
- 系统界面常见的 41.6–42C 是 `battery`（zone 85），不等于 P2 选档温度，也不等于 GPU 热控传感器。
- case 0 的 GPU/CPU 安全限制主要使用 `quiet-therm`（zone 61）和芯片结温。GPU 在 quiet 44/46/48/50C 时可被限制到 720/629/500/231MHz，CPU 相关文件从 quiet 40C 就可开始逐档限制。

## 7. 完整运行流程

```text
用户保存 profile
-> XmlProfileGenerator 生成两份 XML
-> zui_controld 校验 staging
-> promote active
-> bind 到 /system/etc
-> hash 闭合后 reload com.zui.pp
-> 用户从亮屏、解锁的桌面进入目标 App
-> system_server 发布真实 onGameAppStart
-> OEM GameHelper 读取其保存的 mode，并调用 GameModeProvider/contact
-> ZuiPP 对同一包找到 App 条目
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
5. 同一 package 是否出现真实 `onGameAppStart` 和 GameHelper `initGameHelper`。
6. 是否出现 ZuiPP `notifyPerfStatus 11/17` 以及 GameHelper `writeSavageMode open=1`；provider 返回值不能单独证明应用成功。
7. `notifyPerfStatus 11/17` JSON 是否解析到 Little/Big/Titan/Mega/GPU 五段，并与当前 active XML 一致。
8. `ThermalConfig` 选择了哪个 user case。
9. KGSL/cpufreq 的最终节点值是多少。
10. 当前是否是真实高温导致的全局降频，或存在 AVC 阻断。

不要在第 3 步通过后就直接宣布成功，也不要在第 9 步不符合预期时立刻回退到 daemon 直写 sysfs。
