# ZuiControl Current Project State

更新时间：2026-08-31

## 1. 当前结论

V20.3B 阶段已经关闭；persistent daemon retirement architecture = **PASS**。历史 decision 的 `PARTIAL / HOLD` 是当时的阶段转换 gate，现已解除。rapid Uperf crash storm、T8 request-ID 等未闭环发现保持原结论并 carry forward，不得改写成 PASS，也不得再阻止或要求重做 V20.3B。历史 ZuiControl transient 已在 V20.4 源码中修复，但尚未完成真机矩阵。

当前工程阶段是 **V20.4 — Final Stability & Efficiency**。第一工作包 **Refresh Correctness / State Machine** 已完成 source/host/CI/ROM build/final-super 静态闭环，生成了未刷机 candidate；设备仍运行 V20.3B/V49 候选，V20.4 device matrix尚未执行。

设备/系统：TB321FU / ZUI 16.1.11.072。

最近完整真机基线：V20.3B RunId `20260830181816`。当前 V20.4 未刷机候选为 RunId `20260831094239`，refresh source commit `3865cf9c99cb89a8df2b705b9b3dbb2711b311ec`，CI run `33348269219`；App 制品仍为 versionCode 49 / versionName 0.21.12 / `ZuiControlV49`。工程阶段号与 App/Binder 版本号是不同概念。

## 2. 当前真实架构

| 能力 | 决策 owner | 执行/生命周期 | App 角色 |
| --- | --- | --- | --- |
| 刷新率 | system_server / `ZuiControlService` | DisplayManagerInternal / ROM display policy | Binder UI/QS/profile 编辑 |
| Uperf mode | system_server `UperfScenePolicy`，scene/screen event-driven | init property trigger → effective file → Uperf | 四档与 exact user-app 配置 |
| per-task 线程放置 | asoulOpt | init `zui_asoulopt` | 启停/状态入口；conf 编辑未交付 |
| OEM fence | init，`scheduler_active` gated | stop/start `vendor.perfservice` | 无 owner 职责 |
| command | Binder 认证 + durable transaction | disabled+oneshot `zui_control_request` → `zui_controld --oneshot-request` | 发请求、等 ACK、恢复 DP pending |
| health/status | 按需 Binder `getState()` | system_server 内存/SystemProperties | 打开页面或主动 status 时读取 |

persistent `zui_controld` 已退休；当前 init 中不存在其 service/start。`zui_controld` 文件只保留为 oneshot command executor。核心调度不依赖 MainActivity、QuickService 或 App 进程长期存活。

Uperf 自带 context/thread scheduler 为 `sched.enable=false`，asoulOpt 是唯一 per-task affinity owner；Uperf sysfs 模块仍写全局 cpuset mask，广义 topology/knob ownership 留后续独立 scheduler 工作包。生产控制面没有 `auto` 档，Uperf mode 由 system_server 决策；配置仍保留 `switcher.perapp` 路径和二进制相关能力，所以不能宣称 Native Auto 代码已删除，只能说没有证据表明它在当前候选中成为第二决策 owner。

`zui_uperf_service` 内有每 5 秒一次的本 cgroup/日志自检，失败就退出交给 init 恢复。它不是 Settings/Binder health heartbeat；“无周期 health publisher”成立，“整个运行时绝无周期自检”不成立。

## 3. 源码 sanity check

| 检查项 | 结论 |
| --- | --- |
| persistent `zui_controld` init service/start | 0 / 0，符合当前架构 |
| `zui_control_request` | `disabled` + `oneshot`，符合 |
| `zui_controld` 生产入口 | 仅 `--oneshot-request`，符合 |
| refresh owner | 仅 system_server，符合 |
| Uperf scene/screen 决策 | system_server event-driven，符合 |
| Uperf Native Auto | 无 `auto` 入口、ContextScheduler disabled；保留 perapp 能力，PASS with residual risk |
| per-task thread placement | Uperf sched off，asoulOpt 唯一 owner，符合 |
| OEM fence | init-native + `scheduler_active=1` gate，符合 |
| health/status | 无 zui_controld heartbeat，按需 Binder；Uperf wrapper 自检单列 |
| App 存活依赖 | 核心无依赖；QuickService 只保留快捷通知 |

只读 host policy test：`TestV20_3BPolicy.py` 5/5 PASS。

## 4. 已关闭的 V20.3B 证据基线

- daemon retirement：PASS；idle persistent/request process row=0。
- ZuiControl worker：61.13 秒 `/proc` 窗口 0.0000% 单核；89.988 秒 Perfetto 中周期 Settings get/put、health heartbeat、ZuiControl shell fork 均为 0。
- OEM fence：active start 20/20 最终 stopped，主机观测 P95 约 120ms；500 quiet samples 无 storm；inactive 60 秒保持 OEM 同 PID/starttime running。
- Uperf：normal recovery 10/10 PASS；rapid crash storm PARTIAL，并触发过 `sys.init.updatable_crashing=1`。
- asoulOpt：normal recovery 10/10 PASS；bounded storm 3/3；显式 stop 60 秒保持 stopped。
- Boot/App/权限和 transaction security/crash/pending/receipt migration：PASS。
- command T0→T9 P95 1022.100ms；T4→T5 durable claim mean 574.2ms；业务 action mean 12.4ms。
- 最终重启现场为 Launcher / target 120 / actual 120 / balance / Uperf+asoulOpt running。该快照不证明 120 hard-lock。

V20.3B 阶段已经关闭。关闭不等于所有补充矩阵 PASS；未闭环项已迁移到 V20.4 或后续 backlog，不再作为 HOLD gate。

## 5. V20.4 Refresh Correctness / State Machine

这是当前第一工作包，统一处理四个原本分散的问题：ZuiControl transient、kill switch 非即时、disabled 后 AppRequest/vote/peak 是否完整释放，以及 `appliedScenePackage` 不代表 physical success。源码实现与 host/build gate已完成；以下语义已经写入 candidate，但仍需真机验证：

1. **第一优先级：foreground-only transient。** `controlPanel` 独立 profile-owner 特判已删除。业务 App 有自定义 profile 时按自身 Hz；SystemUI、ZuiControl、IME、权限/Resolver/overlay 等真正前台时使用 neutral/default 120；返回业务 App 后恢复其 profile。不得继承 last business Hz。
2. **状态不变量。** 明确定义 raw/current/last、desired/attempted/applied/physical；apply、skipSame、fail、disabled 后的 `appliedScenePackage` 不得伪装成成功物理场景。
3. **QS/QuickService。** 永远修改上一个真实业务场景，不学习或写入 SystemUI/ZuiControl；transient 前台修改只保存，等目标业务 App 回到 foreground 才应用。
4. **Kill switch 释放。** `SystemProperties.addChangeCallback` 已实现属性 edge；priority-8 vote与 owned peak做定向清理。`setDisplayProperties()` 是无 owner token的 shared AppRequest，只能请求 WindowManager traversal并显式报告 handoff pending；完整运行时释放时序必须真机确认。
5. **Apply 与 profile 边界。** 纳入 unsupported mode、partial apply、失败回退、防抖和 AtomicFile保存失败回滚；Binder写入校验与 current-user路由已实现。profile-file load的非法 package/package existence验证及多用户真机切换仍待闭环。
6. **Transient 与档位矩阵。** 覆盖 IME、PermissionController、Resolver、SystemUI、ZuiControl，以及 `60/90/144/165 → neutral 120 → 原业务 Hz`、target/physical、vote/AppRequest、peak、profile hash。

本包不修改 command transaction、Uperf/asoulOpt 策略、120 hard-lock 决策或 Binder 安全契约，不引入第二 owner、polling 或 watchdog。

Host regression：V20.4 19/19、V20.3B 5/5、Java 8/D8、Gradle test/lint/debug assemble、B072 smali probe均 PASS。Exact source commit经 CI成功构建签名 APK/payload；isolated official 072流程完成 `SignNoFec`、签名后 `PackSuper`，最终 `super.img` 的基础 verifier与 V20.4 44-marker verifier均为 `ok=true`。Candidate路径：`D:\3.VScode\Mi\work\v20_4_candidate_20260831094239`，`super.img` SHA-256 `9774b6aa8e72b5dc6c0514c366786da453bf509106a09946be9356969e43d3d9`；`production_b072_unchanged=true`、`flashed=false`。

因此当前结论是 **FLASH CANDIDATE READY / DEVICE VALIDATION PENDING**，不是 V20.4 refresh真机 PASS。待验证边界包括物理 mode、IME动画/硬键盘、disable→enable快速边、shared AppRequest traversal完成、外部 peak writer竞态、Enforcing/AVC以及 100次 dedup矩阵。

## 6. 其它 carry-forward backlog

- command latency：P95 约 1.02 秒，主要成本在 durable claim/fsync/Settings ACK；优化必须保持 at-most-once/crash safety；
- Uperf rapid crash fail-safe：normal 10/10 不覆盖 rapid storm；禁止新增 watchdog daemon；
- T8 request ID：冻结候选真机仍输出空 `id=`，需并发可关联；
- 120 hard-lock 的流畅度、功耗、温度、触控和 144/165 bridge A/B；
- Uperf core_ctl/input boost/cpuset owner 审计；
- asoulOpt 真实游戏 affinity/WALT/frame-time 效果；
- 24h/72h reboot/screen/scene/command/component crash、功耗和日志增长 soak；
- Binder SYSTEM_UID/version/capabilities/dump 是否收紧的安全决策。

产品决策：system App 是否允许 Uperf exact rule。当前 `userAppsOnly` + `/data/app/*` 拒绝 Settings 符合契约，不是已确认 bug。

## 7. 工作树状态

`D:\3.VScode\Mi` 本身不是 Git 仓库；生产仓库是 `D:\3.VScode\Mi\ZuiControl`。

Active Repository Context Cleanup 已把旧 AI/handoff、阶段报告、raw、Perfetto、logcat 和冻结压缩包移到仓库外 `D:\3.VScode\Mi\ZuiControl_Archive\`；没有删除文件。各阶段 active 顶层 `tests/`、验证脚本、build/packaging、`app/`、`payload/`、`framework_patch/`、Uperf/asoulOpt 二进制与配置均保留原位；raw snapshot 内嵌副本随 raw 归档。旧 AI 交接文档的接管前 modified 内容也原样进入 archive。

仓库 `README.md` 与 `payload/README.txt` 已改为当前 V20.3B/V20.4 架构入口。历史证据以 `CURRENT_EVIDENCE_INDEX.md` 定向索引；完整盘点见 `docs/maintenance/context-cleanup-2026-08-31/ACTIVE_CONTEXT_CENSUS.md`。

## 8. 边界与下一会话入口

V20.4 Refresh Correctness 不混入 GPU/KGSL 正式接管、thermal 大改、AppOpt/XML/ZuiPP/FPS cap 生产代码清理、无证据 Uperf/asoulOpt 升级、新 persistent daemon/watchdog。生产代码级历史清理仍留 V21，GPU ownership 留 V22。

下一会话默认只读：`AGENTS.md` → `CURRENT_PROJECT_STATE.md` → `README.md` → 当前生产源码 → `CURRENT_EVIDENCE_INDEX.md`。不要默认扫描 `D:\3.VScode\Mi\ZuiControl_Archive\`；质疑具体数字时才按 index 定向读取。

V20_3B_STAGE=CLOSED
V20_4_REFRESH_SOURCE_HOST_BUILD=PASS
V20_4_REFRESH_FLASH_CANDIDATE_READY=YES
V20_4_REFRESH_DEVICE_VALIDATION=PENDING
TAKEOVER_READY=YES
