# ZuiControl Current Project State

更新时间：2026-09-01

## 1. 当前结论

V20.3B 阶段已经关闭；persistent daemon retirement architecture = **PASS**。历史 decision 的 `PARTIAL / HOLD` 是当时的阶段转换 gate，现已解除。rapid Uperf crash storm、T8 request-ID 等未闭环发现保持原结论并 carry forward，不得改写成 PASS，也不得再阻止或要求重做 V20.3B。

当前工程阶段是 **V20.4 — Final Stability & Efficiency**。第一工作包 **Refresh Correctness / State Machine** 已由 Runtime Correction RunId `20260831170720` 完成针对性真机Gate，结论为 **PASS / CLOSED WITH EXPLICIT BOUNDARIES**。当前第二工作包是 **Uperf Architecture & Upstream Rebase**；源码/设计已进入静态与候选构建门禁，尚未刷机或device validation，不得提前写成Production PASS。不要继续返回旧Refresh blocker重做，也不要自动开始V21。

当前设备运行 RunId `20260831170720`，source `146e096c6a6bc8b3fee60349b856990fd9fb68d2`，CI `33375509612`。host `39/39`、V20.3B regression `5/5`、final-super reverse/56-marker、final-artifact ART、split CIL、official host init exact-file、fixed-seven Preflight、实际fixed-seven read-back flash、Boot Hard Gate与Targeted Device Gate均PASS。最终 `services.jar` SHA-256 为 `0b7bb46c644c5559173f72b06579131e82597366fdcc114d3fb30aabb544e8a3`。

设备/系统：TB321FU / ZUI 16.1.11.072。

最近关闭的完整基线：V20.3B RunId `20260830181816`。当前生产 App 制品仍为 versionCode 49 / versionName 0.21.12 / `ZuiControlV49`；工程阶段号、RunId与 App/Binder 版本号是不同概念。

候选 lineage：`20260831094239` 在刷前因 event-order漏洞被拒绝；`20260831104317` 静态 gate通过但刷后触发 ART `VerifyError`、Boot Gate FAIL，随后按验证流程恢复 V20.3B；`20260831134511` 加入目标ART gate并刷入，Boot PASS但device结果因三个runtime blocker为PARTIAL；`20260831170720` 定向修正三项并已刷入、验证PASS。前三个旧候选均不得再次刷写。

## 2. 当前真实架构

| 能力 | 决策 owner | 执行/生命周期 | App 角色 |
| --- | --- | --- | --- |
| 刷新率 | system_server / `ZuiControlService` | DisplayManagerInternal / ROM display policy | Binder UI/QS/profile 编辑 |
| Uperf mode | system_server `UperfScenePolicy`，top-resumed Activity/screen event-driven | init property trigger → effective file → Uperf | 四档与 exact user-app 配置 |
| per-task 线程放置 | asoulOpt | init `zui_asoulopt` | 启停/状态入口；conf 编辑未交付 |
| OEM fence | init，`scheduler_active` gated | stop/start `vendor.perfservice` | 无 owner 职责 |
| command | Binder 认证 + durable transaction | disabled+oneshot `zui_control_request` → `zui_controld --oneshot-request` | 发请求、等 ACK、恢复 DP pending |
| health/status | 按需 Binder `getState()` | system_server 内存/SystemProperties | 打开页面或主动 status 时读取 |

persistent `zui_controld` 已退休；当前 init 中不存在其 service/start。`zui_controld` 文件只保留为 oneshot command executor。核心调度不依赖 MainActivity、QuickService 或 App 进程长期存活。

Uperf 自带 context/thread scheduler 为 `sched.enable=false`，asoulOpt 是唯一 per-task affinity owner；Uperf sysfs 模块仍写全局 cpuset mask，广义 topology/knob ownership必须由刷后time-series/writer-trace证明。生产控制面没有 `auto` 档，Uperf mode 由system_server依据真正top-resumed Activity决策；配置仍保留 `switcher.perapp` 路径和二进制相关能力，所以不能宣称 Native Auto 代码已删除，只能说没有production入口。Refresh的focused Window authority保持不变。

当前候选的 `zui_uperf_service` 已用一次bounded startup FIFO event wait + steady blocking read替代5秒cgroup/log轮询。正常worker crash留给Uperf内建manager，完整writer-tree EOF交init限速恢复，rapid worker/whole-service storm进入显式fail-safe；这仍是待final gate与刷后验证的候选语义。

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
| health/status | 无 zui_controld heartbeat，按需 Binder；Uperf候选暴露event-driven fail-safe状态 |
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

该第一工作包统一处理 ZuiControl transient、kill switch即时性、disabled后的AppRequest/vote/peak释放，以及desired/attempted/applied/physical语义。RunId `20260831170720` 已刷入并通过Targeted Device Gate；工作包现已关闭：

1. **第一优先级：foreground-only transient。** `controlPanel` 独立 profile-owner 特判已删除。业务 App 有自定义 profile 时按自身 Hz；SystemUI、ZuiControl、IME、权限/Resolver/overlay 等真正前台时使用 neutral/default 120；返回业务 App 后恢复其 profile。不得继承 last business Hz。
2. **状态不变量与 event order。** 明确定义 raw/current/last、desired/attempted/applied/physical；apply、skipSame、fail、disabled 后的 `appliedScenePackage` 不得伪装成成功物理场景。真实非空focused Window是physical authority；Window已建立权威后Activity只补metadata。空/null Window是 `EMPTY_FOCUS_TRANSITION`，不是default120 owner；correction保留最后非空policy等待下一非空edge，不用sleep/debounce。
3. **QS/QuickService。** 永远修改上一个真实业务场景，不学习或写入 SystemUI/ZuiControl；transient 前台修改只保存，等目标业务 App 回到 foreground 才应用。
4. **Kill switch 释放。** correction使用严格签名认证TX10直接persist+transition，raw engineering property由init edge-only短进程发送标准poke；无polling/常驻notifier。两个raw property各20次disable/enable均20/20且无需人工poke；disable释放本地render/peak/AppRequest ownership，enable按当前focus重建。`setDisplayProperties()`仍是无owner token的shared AppRequest，只能称WindowManager traversal handoff requested/pending，不能宣称同步clear callback。
5. **Apply 与 profile 边界。** 纳入 unsupported mode、partial apply、失败回退、防抖和 AtomicFile保存失败回滚；Binder写入校验与 current-user路由已实现。profile-file load的非法 package/package existence验证及多用户真机切换仍待闭环。
6. **Transient 与档位矩阵。** 覆盖 IME、PermissionController、Resolver、SystemUI、ZuiControl，以及 `60/90/144/165 → neutral 120 → 原业务 Hz`、target/physical、vote/AppRequest、peak、profile hash。

本包不修改 command transaction、Uperf/asoulOpt 策略、120 hard-lock 决策或 Binder 安全契约，不引入第二 owner、polling 或 watchdog。

Host/build：V20.4 `39/39`、V20.3B `5/5`、CI `33375509612`、final-super 56-marker、final-artifact ART、split CIL、official init exact-file与fixed-seven Preflight均PASS。当前候选路径为 `D:\3.VScode\Mi\work\v20_4_candidate_20260831170720`；`super.img` SHA-256 `dc4fd4bc3e288aa26e80cf382db62211f488e1b74c7cb8767b2d3f9f5f2c269d`。构建receipt的`flashed=false`只描述当时；该package随后已授权刷入。

当前correction真机已证明：

- Boot Gate：19样本/187.743秒，system_server PID/starttime `2700/988`稳定，Binder/Launcher/Enforcing/bootanim正确，相关crash/RescueParty marker为0；
- raw refresh/global disable各20/20，无人工poke；rapid两个property共80 commanded edge全部收敛，无gross duplicate apply；短命executor atrace生命周期约37–39ms，367.132秒无边沿观察无残留/周期自启；
- disabled boot最早有效state已经mask2/owners false/apply0；enabled boot恢复Launcher/default120 ownership；只执行批准的两次persistence reboot；
- Notes90↔Calculator60 100往返=200真实edge，apply delta200、empty delta200、1270个null样本、observed intermediate120=0；same-owner warm relaunch10/10且apply delta0；
- SystemUI、ZuiControl、IME、Resolver、PermissionController与两个exact OEM control package保持non-empty transient default120；screensplit/sidebar不覆盖current/last/editable且不创建profile；
- freeform/split/PiP无回归；60/90/120/144/165 physical smoke PASS；
- final `/proc` 60.6秒与Perfetto89.985秒中ZuiControl worker为0 tick/0 slice/0 CPU，refresh apply/skip不变；blocking AVC=0；profile精确回到仅default的64-byte baseline。

当前App UI未接TX10，且没有安全现成signed-App Manager/API路径，记为 `APP_UI_TX10=NOT_EXECUTED / SIGNED_APP_TX10_DEVICE_PATH_NOT_AVAILABLE`。UDFPS=`NOT_OBSERVED`、fault injection=`NOT_EXECUTED`、secondary user/external display=`NOT_VALIDATED` 保持显式非阻断边界，不得改写成PASS。完整当前结果见 [`V20_4_REFRESH_RUNTIME_DECISION.md`](V20_4_REFRESH_RUNTIME_DECISION.md) 与 [`08_BOOT_GATE.md`](V20_4_REFRESH_RUNTIME_CORRECTION/08_BOOT_GATE.md) 至 [`13_FINAL_RUNTIME_STATE.md`](V20_4_REFRESH_RUNTIME_CORRECTION/13_FINAL_RUNTIME_STATE.md)；旧 [`08_DEVICE_RESULTS.md`](V20_4_REFRESH_CORRECTNESS/08_DEVICE_RESULTS.md) 只记录被替代RunId的历史PARTIAL。

## 6. 其它 carry-forward backlog

- command latency：P95 约 1.02 秒，主要成本在 durable claim/fsync/Settings ACK；优化必须保持 at-most-once/crash safety；
- Uperf候选的rapid crash fail-safe、normal recovery、explicit stop与idle零polling必须刷后验证；host设计不覆盖rapid storm；
- T8 request ID：冻结候选真机仍输出空 `id=`，需并发可关联；
- 120 hard-lock 的流畅度、功耗、温度、触控和 144/165 bridge A/B；
- Uperf core_ctl/input boost/cpuset真机owner证明与performance A/B；
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
V20_4_REFRESH_WORK_PACKAGE=CLOSED_WITH_EXPLICIT_BOUNDARIES
V20_4_REFRESH_RUNTIME_SOURCE_HOST_BUILD=PASS
V20_4_REFRESH_RUNTIME_BOOT=PASS
V20_4_REFRESH_RUNTIME_DEVICE_VALIDATION=PASS
V20_4_REFRESH_KILL_SWITCH_DEVICE=PASS_NO_MANUAL_POKE
V20_4_REFRESH_ACTIVITY_WINDOW_ORDER=PASS_0_OBSERVED_INTERMEDIATE_DEFAULT
V20_4_REFRESH_VENDOR_OVERLAY=PASS_EXACT_CLASSIFICATION
V20_4_RUNTIME_CORRECTION_PRE_FLASH_READY=YES
V20_4_RUNTIME_CORRECTION_FLASHED=YES
V20_4_RUNTIME_CORRECTION_BOOT_HARD_GATE=PASS
V20_4_RUNTIME_CORRECTION_DEVICE_VALIDATION=PASS
V20_4_RUNTIME_CORRECTION_APP_UI_TX10=NOT_EXECUTED
V20_4_RUNTIME_CORRECTION_SIGNED_APP_TX10_DEVICE_PATH=NOT_AVAILABLE
V20_4_UPERF_SOURCE_HOST=PASS
V20_4_UPERF_FINAL_ARTIFACT=PENDING
V20_4_UPERF_FLASHED=NO
V20_4_UPERF_DEVICE_VALIDATION=NOT_STARTED
UDFPS_LOCAL_VOTE_RUNTIME=NOT_OBSERVED
FAULT_INJECTION_DEVICE_PATH=NOT_EXECUTED
SECONDARY_USER_EXTERNAL_DISPLAY=NOT_VALIDATED
TAKEOVER_READY=YES
