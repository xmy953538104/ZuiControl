# 优化路线（仅建议，未执行）

本路线遵守当前禁止项：不进入 FPS cap、不改 thermal conf/P1、不恢复 direct CPU/GPU sysfs、provider_direct、云控、旧 AppOpt/XML/ZuiPP bridge 或 Scene。若要让 Uperf 接管 GPU，必须先由用户明确改变现阶段架构授权；不能暗中加 KGSL 写入。

## Phase 0：建立稳定性测试基线

1. 固定设备版本、V49 hash、global/exact 配置和自然冷却起始温度。
2. 做短时非烧机 A/B：Launcher、鸣潮前台、ZuiControl 前台修改 exact、返回游戏、退出游戏。
3. 同步采样 scene/global/exact/effective、每核 freq/policy、KGSL freq/busy/pwrlevel/cooling state、OEM GameHelper/ZuiPP/thermal 日志。
4. 建立切档 P50/P95、丢请求率、进程 PID/restart、显示模式、待机唤醒基线。
5. 补配置损坏、App force-stop、daemon/Uperf/asoul kill、system_server 重启、Doze、低内存手测矩阵。

阶段门：确认 R-001 的 OEM LimitConfig 可重复出现，并由用户选择唯一 GPU owner：

- 方案 A：接受 OEM 管 GPU，产品清楚标注 Uperf 只接管 CPU/WALT，避免“全面接管”承诺；或
- 方案 B：批准下一阶段 Uperf/KGSL 原生 GPU 策略，并设计只关闭 OEM 性能限制、保留游戏助手 telemetry 的最小 fence。

在这个决定前不写 GPU 修补。

## Phase 1：删除明显冗余

1. 先修 `ZuiControlClient` 精确解析 `ok=1`，加 `ok=0` 单测。
2. 证明无调用后删除 Binder transaction 10/11 和重复 export/module API。
3. 更新 `payload/README.txt` 与 OEM bridge 实际状态。
4. 删除确认未用的 3 个 drawable。
5. 评估迁移窗口后，停止每次 boot 清旧 XML/AppOpt Settings key。

每一项单独提交和回归；不顺便重构 UI。

## Phase 2：降低运行时资源占用

1. 把性能命令从 1 Hz Settings polling 改为事件驱动认证通道。
2. health 检查从每秒业务同步中拆出，按状态变化和较低频率运行；连续失败指数退避。
3. QuickService 通知改为用户可选/按需，不作为核心存活依赖。
4. package picker 延迟加载 icon，避免一次性构建全部 drawable。

先量化再改；每次用 Phase 0 的待机/切档基线判断收益。

## Phase 3：整理线程和生命周期

1. 用一个与 Activity lifecycle 绑定、可取消的最小 executor/scope 替换每次裸 `Thread`。
2. UI 命令串行化或禁用重复按钮，明确并发策略。
3. Tile 请求保留短任务，并在 service 销毁后不回调旧实例。
4. 给 package picker 和 dialog 增加 destroy/cancel 检查。

不需要引入大型架构库；标准 Android/Kotlin 生命周期能力足够。

## Phase 4：统一系统调用层

1. 为性能/线程命令提供 system_server 或专用 daemon Binder，复用包名+证书鉴权思想。
2. Settings 只保留可观察状态，不再做可写命令队列。
3. 统一 requestId、错误码、有效档来源、owner 和时间戳。
4. 为 `zui_controld` 建专用 SELinux domain，移除对通用 shell domain 的依赖。
5. 重新评估 `dumpsys` 全局 relabel，改成最小专用执行路径。

这是高风险阶段：先保持旧通道只读兼容，再切调用者，最后删除旧写入，禁止一次性切断回退。

## Phase 5：异常恢复

1. `publishState()` 失败必须限频日志、stale health 和有上限重试。
2. profile/config 加 schema、校验、坏文件隔离和 last-known-good。
3. Uperf/asoul crash loop 加退避/熔断，并尊重 kill switch。
4. 启动自检输出 Binder、display apply、有效 scheduler owner、SELinux/context 和配置版本。
5. system_server/display API 失败必须在 UI/日志显示真实失败，不能 `ok=0` 假成功。

## Phase 6：测试覆盖

1. JVM：Reply 解析、profile/package/mode 校验、优先级、并发 requestId。
2. shell：daemon 原子写、损坏配置、失败/重试、kill switch、OEM fence 状态。
3. instrumentation：Activity 销毁时命令、QS/SystemUI scene、boot/direct boot、权限拒绝。
4. ROM 集成：Linux CI secilc、framework patch 输入 hash、最终 super 反向抽查。
5. 真机：冷态短 A/B、Doze/待机、低内存、进程/system_server 重启。

## Phase 7：最终压测

1. 24–72 小时常规使用稳定性，不主动高温烧机。
2. 高频真实 App/Launcher/SystemUI 切换，确认 scene、刷新率和 Uperf source 不漂移。
3. 多轮游戏进入/退出，确认 OEM/GPU owner 唯一、限制完整恢复、温控保护仍在。
4. 并发 UI/QS 命令、服务 kill/restart、配置损坏恢复。
5. 统计 P95 切档时延、crash/restart、AVC、功耗和日志完整性后才标记 production stable。

## 绝对不应一次性大改

- system_server 场景状态机与 display vote。
- Settings → Binder 迁移、SELinux domain 和 GPU owner 三件事。
- MainActivity UI 风格与后端协议。
- Uperf power model、OEM fence 和 thermal policy。
- AsoulOpt 二进制替换与线程配置格式。

每次只改变一个 owner/边界，保留可逆开关和同一基线；否则无法知道性能改善来自哪里，也容易再次刷入不可启动或不可诊断的包。
