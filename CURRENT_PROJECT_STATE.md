# ZuiControl Current Project State

更新时间：2026-08-31

## 1. 当前结论

V20.3B 阶段已经关闭；persistent daemon retirement architecture = **PASS**。历史 decision 的 `PARTIAL / HOLD` 是当时的阶段转换 gate，现已解除。rapid Uperf crash storm、T8 request-ID 等未闭环发现保持原结论并 carry forward，不得改写成 PASS，也不得再阻止或要求重做 V20.3B。

当前工程阶段是 **V20.4 — Final Stability & Efficiency**。第一工作包 **Refresh Correctness / State Machine** 的 fixed candidate 已通过 source/host/CI/ROM build/final-super、final-artifact ART 与 Boot Hard Gate；device validation 已执行，结论为 **PARTIAL**。

当前必须区分两层：设备仍运行上述fixed RunId `20260831134511`，其三个runtime blocker仍是device FAIL；未刷入的定向correction候选 RunId `20260831170720` 已冻结为source `146e096c6a6bc8b3fee60349b856990fd9fb68d2`，V20.4 host `39/39`与V20.3B回归`5/5`、CI、final-super reverse/56-marker、final-artifact ART、split CIL及官方host init exact-file gate均PASS，技术状态为 `PRE_FLASH_READY=YES / FLASHED=NO`。它的Boot Hard Gate和刷后device结论不得继承旧候选的PASS。

设备/系统：TB321FU / ZUI 16.1.11.072。

最近关闭的完整基线：V20.3B RunId `20260830181816`。当前设备运行 V20.4 fixed RunId `20260831134511`，refresh source commit `3c5cd809d5465828fe14356cbd079d45d00347b7`，CI run `33361319072`；App 制品仍为 versionCode 49 / versionName 0.21.12 / `ZuiControlV49`。工程阶段号与 App/Binder 版本号是不同概念。

候选 lineage：`20260831094239` 在刷前因 event-order漏洞被拒绝；`20260831104317` 静态 gate通过但刷后触发 ART `VerifyError`、Boot Gate FAIL，随后按验证流程恢复 V20.3B；`20260831134511` 对最终 super 中 `services.jar` 增加目标设备 ART/dex2oat gate并通过，经授权 fixed-seven刷入后Boot Gate PASS；`20260831170720` 只修正三个已证实runtime blocker并已完成技术pre-flash gate，尚未刷入。旧前两个候选均不得再次刷写。

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

这是当前第一工作包，统一处理四个原本分散的问题：ZuiControl transient、kill switch 非即时、disabled 后 AppRequest/vote/peak 是否完整释放，以及 `appliedScenePackage` 不代表 physical success。下列主体设计已进入当前已刷fixed candidate；其三个device FAIL已在未刷入的source `146e096...` / RunId `20260831170720` 中做定向correction。旧device结果、pre-flash candidate与未来刷后结果必须分开记录：

1. **第一优先级：foreground-only transient。** `controlPanel` 独立 profile-owner 特判已删除。业务 App 有自定义 profile 时按自身 Hz；SystemUI、ZuiControl、IME、权限/Resolver/overlay 等真正前台时使用 neutral/default 120；返回业务 App 后恢复其 profile。不得继承 last business Hz。
2. **状态不变量与 event order。** 明确定义 raw/current/last、desired/attempted/applied/physical；apply、skipSame、fail、disabled 后的 `appliedScenePackage` 不得伪装成成功物理场景。真实非空focused Window是physical authority；Window已建立权威后Activity只补metadata。空/null Window是 `EMPTY_FOCUS_TRANSITION`，不是default120 owner；correction保留最后非空policy等待下一非空edge，不用sleep/debounce。
3. **QS/QuickService。** 永远修改上一个真实业务场景，不学习或写入 SystemUI/ZuiControl；transient 前台修改只保存，等目标业务 App 回到 foreground 才应用。
4. **Kill switch 释放。** 真机已证明raw `setprop`不会自动report system_server进程内callback；标准 `SYSPROPS_TRANSACTION` 后59.772ms从mask0到mask2，恢复poke 56.350ms重建。correction使用严格签名认证TX10直接persist+transition，raw engineering property由init edge-only短进程发送标准poke；无polling/常驻notifier。worker仍以两个property的最新truth收敛。`setDisplayProperties()` 是无owner token的shared AppRequest，只能请求WindowManager traversal并报告handoff pending；最终candidate的release时序、idle和poke wake边界仍须真机确认。
5. **Apply 与 profile 边界。** 纳入 unsupported mode、partial apply、失败回退、防抖和 AtomicFile保存失败回滚；Binder写入校验与 current-user路由已实现。profile-file load的非法 package/package existence验证及多用户真机切换仍待闭环。
6. **Transient 与档位矩阵。** 覆盖 IME、PermissionController、Resolver、SystemUI、ZuiControl，以及 `60/90/144/165 → neutral 120 → 原业务 Hz`、target/physical、vote/AppRequest、peak、profile hash。

本包不修改 command transaction、Uperf/asoulOpt 策略、120 hard-lock 决策或 Binder 安全契约，不引入第二 owner、polling 或 watchdog。

Host/build：V20.4 27/27、V20.3B 5/5、Java 8/D8、Gradle、B072 smali、CI `33361319072`、isolated official 072、final-super 48-marker均PASS。Fixed candidate路径：`D:\3.VScode\Mi\work\v20_4_candidate_20260831134511`；`super.img` SHA-256 `4f01c64d8a3a5860c34967d944510f3768f4e6748bb843eef0345a5c6685800d`。最终 `services.jar` SHA-256 `389ac1cff8e79705a8dd9e5220f3c70ccef5c84f0681ac3c6c993f81ddcd24ff`，目标设备 ART gate `DEX_RC=0 / GATE_RC=0`。构建时的 `flashed=false` 只是 packaging receipt；该fixed package后来已经授权刷入。

真机已证明：

- foreground-only 主路径：60/90/120/144/165业务App → ZuiControl/SystemUI/IME/Resolver default120 → 返回恢复；QS/ZuiControl只保存上一业务App；
- profile 负例、dedup、freeform、split、PiP、peak observer、enabled idle、Binder授权和单用户边界；
- Boot Gate：19样本/188.563秒，system_server PID2635稳定，Binder/Enforcing/bootanim状态正确，当前boot无VerifyError、system-process FATAL、restart或RescueParty。

真机明确失败：

- **kill switch不收敛**：refresh/global property=1稳定超过6秒/5.1秒，service仍mask0/disabled false且ownership未释放；rapid-final-disabled同样失败；
- **App-to-App intermediate default**：10次60↔90启动每次apply `+2`，8次明确采到临时空window→120→目的profile；
- **vendor overlay误分类**：`com.lenovo.screensplit`、`com.zui.freeform.sidebar`覆盖current/last/editable。

定向runtime-correction已实现：empty Window不apply且不覆盖snapshot；上述两个OEM package以exact registry归为configuration-transient；TX10直接转换，raw property edge以init短进程经shell entrypoint发送标准poke。RunId `20260831170720` 绑定source `146e096...`、CI `33375509612`；host 39/39+5/5、final-super 56-marker、最终 `services.jar` SHA-256 `0b7bb46c644c5559173f72b06579131e82597366fdcc114d3fb30aabb544e8a3`、目标ART `DEX_RC=0/GATE_RC=0`、split CIL `SECILC_RC=0/GATE_RC=0`。当前App UI未接TX10控件，该路径是reserved signed-App API，记为`NOT_EXECUTED`。候选尚未刷入；Boot Gate与刷后100轮/device matrix仍pending。

边界：UDFPS因无fingerprint sensor/service为`NOT_OBSERVED`；fault injection、kill-switch下游AppRequest/vote/peak release与reenable、disabled idle未执行；secondary user不存在，external display未验证；PermissionController/PackageInstaller实际路径未完整覆盖。完整权威结果见 [`08_DEVICE_RESULTS.md`](V20_4_REFRESH_CORRECTNESS/08_DEVICE_RESULTS.md)。

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
V20_4_REFRESH_FIXED_CANDIDATE_BOOT=PASS
V20_4_REFRESH_DEVICE_VALIDATION=PARTIAL
V20_4_REFRESH_KILL_SWITCH_DEVICE=FAIL_NOT_CONVERGED
V20_4_REFRESH_ACTIVITY_WINDOW_ORDER=FAIL_INTERMEDIATE_DEFAULT
V20_4_REFRESH_VENDOR_OVERLAY=FAIL_CLASSIFICATION
V20_4_RUNTIME_CORRECTION_PRE_FLASH_READY=YES
V20_4_RUNTIME_CORRECTION_FLASHED=NO
V20_4_RUNTIME_CORRECTION_BOOT_HARD_GATE=PENDING_POST_FLASH
V20_4_RUNTIME_CORRECTION_DEVICE_VALIDATION=PENDING
V20_4_RUNTIME_CORRECTION_APP_UI_TX10=NOT_EXECUTED
UDFPS_LOCAL_VOTE_RUNTIME=NOT_OBSERVED
FAULT_INJECTION_DEVICE_PATH=NOT_EXECUTED
SECONDARY_USER_EXTERNAL_DISPLAY=NOT_VALIDATED
TAKEOVER_READY=YES
