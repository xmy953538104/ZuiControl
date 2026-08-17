# ZuiControl P2 XML 与 ThermalConfig 读法 - 2026-07-21

## 0. 2026-08-17 当前生产规则

本节覆盖本文后面关于“三个用户 profile、daemon 选择 gameMode、GameModeProvider 重入”的旧说明；后文 XML 字段、ThermalConfig 和原厂热控原理仍有效。

该模型已进入 `versionCode=29` / `versionName=0.20.0`，生产代码 commit 为 `28f1f8b56628ebbaf5cdbb570fe10e1b250e5b6f`。对应待刷 `super.img` SHA256 为 `ddfa388d1df10f6b609337af4b07ed5ca52a6d4602aebaab1ba88fec0a3970ef`。

当前生产模型：

```text
每个 package 一份用户 profile
-> 每个温区分配一个共享 CPU level ID
-> Little/Big/Titan/Mega 四个 Type 用同一 ID、各存自己的频率值
-> 同一 LimitConfig 镜像到 balanced / powersave / savage
-> 校验、promote、bind active XML
-> hash 变化时受控重启 ZuiPP，并等待新进程稳定 10 秒
-> 用户退出并重新进入 ZUI 已识别的游戏
-> 原厂 onGameAppStart 选择任一模式，得到相同配置
```

原因和边界：

- 设备实测 ZuiPP 的 CPU Type 读取顺序不是 XML 文档顺序。旧版为四簇分配不同 level ID 时，错误 Type 查找可能缺少 level，TAssistant 会放弃整条策略。共享 CPU ID 消除了顺序依赖。
- daemon 直接调用 `GameModeProvider/contact` 会出现“命令成功但 ZuiPP 没有目标游戏状态”的假成功，现已删除。
- 普通 App 即使被写入 XML，也可能没有 GameHelper/ZuiPP 的 `onGameAppStart`，因此只承诺 ZUI 已识别的游戏。
- XML 的 CPU/GPU 上下限是 OEM 性能请求，不是不可被覆盖的硬上限。全局 thermal、厂商性能服务或更高优先级请求仍可能改变最终节点值。
- `last_good` 保存 promote 前的旧 active；成功后不会再被新 active 覆盖，因此失败回滚和手动恢复有真实的上一版本。
- 不恢复 daemon direct cpufreq/KGSL sysfs，不进入 FPS cap。

当前排查顺序：

1. profiles.prop 中每个 package 是否只有一条 canonical `balanced` 记录。
2. active 与 `/system/etc` 是否同 hash 且已 bind mount。
3. 目标 App 的三个 LimitConfig mode block 是否完全一致。
4. 每个温区 Little/Big/Titan/Mega 四个 ID 是否相同，并在四个 Type 中都存在。
5. ZuiPP reload 是否 `state=done`、`stableSeconds=10`、`needsAppReenter=1`。
6. 退出并重新进入 ZUI 已识别的游戏，检查原厂 `onGameAppStart`、TAssistant/LimitConfig 日志。
7. 再读 CPU/GPU 最终节点，并区分用户请求、厂商覆盖和真实高温降频。
8. 检查相关 AVC。

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
