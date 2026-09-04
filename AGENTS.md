# ZuiControl 项目规则

## 0. 权威性与当前阶段

本文件是 ZuiControl 的当前长期规则。用户在当前会话中的明确要求优先于本文件；当前生产源码决定“实际实现了什么”，当前最终真机证据决定“实际证明了什么”。如果三者冲突，先报告冲突，不得为了符合文档而强改源码，也不得用旧报告覆盖新证据。

当前设备/系统：TB321FU / ZUI 16.1.11.072。

当前工程状态：**V21 Phase 1 Closure Fix 已完成并关闭；Phase 2 未开始**。
V20.4 Final Closure 已 PASS，状态为 **CLOSED WITH EXPLICIT BOUNDARIES**。
唯一 Golden source 是 commit `29f23f8d590b88f0d472c12373366a9ef14e8330`，
Build RunId `20260903144915`，CI `33724674012`，Device Gate RunId
`20260903153438`。权威镜像/runtime hash、closure boundaries 与 backlog 见
[`V20_4_GOLDEN_BASELINE.md`](V20_4_GOLDEN_BASELINE.md) 和机器可读
[`V20_4_GOLDEN_BASELINE.json`](V20_4_GOLDEN_BASELINE.json)。

本工程基线继续冻结全部 V20.4 production runtime。Phase 1 只包含 docs、host
tooling、host tests、archive metadata、portable environment 与显式 allowlist workspace cleanup，未修改
App/framework/payload/native production runtime，未构建 ROM，未刷机。任何后续 production 工作
必须先从 Golden commit 建立独立 worktree，并显式声明 `BASELINE_SOURCE` 和
`BASELINE_IMAGE_HASHES`。禁止从旧失败候选、目录时间或旧 canonical B072 推断
baseline。

V20.3B persistent daemon retirement architecture = **PASS**，阶段已经关闭。
历史 `PARTIAL / HOLD` 只表示当时的过渡 Gate；rapid Uperf crash storm、T8
request-ID 等残余项保持 backlog，不得改写成 PASS，也不得要求重做 V20.3B。

旧 lineage 必须保留但不得覆盖 Golden：`20260831104317` 因 ART VerifyError
Boot FAIL；`20260831134511` 为 device PARTIAL；`20260901120647`、
`20260901174600`、`20260902150110` 与 `20260903081352` 均在 Uperf startup/
lifetime Gate FAIL；`20260903110608` 是 regular-log development PASS，但被最终
transitional-null Golden 取代。状态分类见
[`docs/maintenance/v21-phase1/ARCHIVE_CLASSIFICATION.md`](docs/maintenance/v21-phase1/ARCHIVE_CLASSIFICATION.md)。

当前生产 App 源码仍是 versionCode 49 / versionName 0.21.12 / `ZuiControlV49`，Binder version 仍返回 19。这些是已验证候选的制品标识，不是当前工程阶段号，不得据此把规则退回 V19。

新会话默认只读：

1. `AGENTS.md`
2. `CURRENT_PROJECT_STATE.md`
3. `README.md`
4. 当前生产源码
5. `CURRENT_EVIDENCE_INDEX.md`
6. `V20_4_GOLDEN_BASELINE.md` / `.json`

Mi workspace 的长期入口是根目录 `README_MI.txt` 与 physical `script`；工具只认
`Edit tools`，review archive 只认 `Review packages\V20.4|V21`，签名材料只认
`Edit tools\Signing`。禁止把 Gate 包放入 Git repo/Mi 根，禁止重新建立指向临时
worktree 的 `Mi\script` junction。

Host 路径必须通过 physical `script\Mi.Common.psm1` / `Get-MiPaths` 动态解析；
canonical script source 禁止写入特定盘符下的 Mi 路径或用户 profile。ADB/fastboot
唯一 canonical copy 是 `Edit tools\android-build\android-sdk\platform-tools`。
`New-MiWork.ps1` 默认 engineering base 是所选 repository 当前 HEAD；Golden
runtime commit 只用于 production diff，不得再作为默认工程 worktree 起点。

历史 handoff、失败镜像和大型 raw/trace/log 不是当前上下文。它们的
可重用因果已压缩到 `scripts/docs/`；默认不得扫描历史目录或使用
已失败 candidate。只有 `CURRENT_EVIDENCE_INDEX.md` 指向的具体 Golden 数字
受到质疑时，才定向读取 closure evidence。

不要为了“了解项目”通读 V19、V20.1 Native Auto、旧 daemon、AppOpt/XML/ZuiPP 或全部失败 device run。

### 0.1 改相关代码前必读

| 触发主题 | 必读 canonical 文档 |
| --- | --- |
| Refresh / focused Window / freeform / split / PiP / empty focus / OEM transient | `scripts/docs/01_Refresh_最终架构与踩坑.md` |
| Uperf / FIFO / sfanalysis / supervisor / waitpid / top-resumed / worker | `scripts/docs/02_Uperf_最终架构与踩坑.md` |
| asoulOpt / affinity / cpuset / scheduler | `scripts/docs/03_asoulOpt_最终架构与踩坑.md` |
| framework.jar / services.jar / system_server / DEX / ART / smali | `scripts/docs/04_Framework_ART_踩坑与永久Gate.md` |
| SELinux / sepolicy / CIL / AVC / domain | `scripts/docs/05_SELinux_踩坑与永久Gate.md` |
| 9008 / EDL / qdl / firehose / rawprogram / flash / read-back | `scripts/docs/06_9008刷机_踩坑与永久Gate.md` |

不得依赖模型“记得”历史；上表是开工前 Gate。

## 1. 命名规则

新增内容统一使用：

- 产品/App：ZuiControl
- App 包名：`com.zui.zuicontrol`
- Binder service：`zui_control`
- system_server 服务：`ZuiControlService` 或 `ZuiControlRefreshController`
- App Binder 客户端：`ZuiControlClient`
- oneshot 请求 service：`zui_control_request`
- oneshot 命令执行文件：`zui_controld`
- 系统配置目录：`/data/system/zui_control/`
- vendor/运行时目录：`/data/vendor/zui_control/`
- 刷新率 profile：`/data/system/zui_control/profiles.prop`
- 线程放置模块：asoulOpt

`zui_controld` 只是保留文件名，不再代表常驻 daemon。新代码和文档不得把它描述成 persistent scheduler daemon、health daemon 或 refresh owner。

不要给新增组件继续使用：

- ZuiperfCtl / zuiperfctl
- zui_perfctl / zui_perfctld
- AsoulOptManager
- AsoulOptService（如果它指整个产品或系统管理器）

ZuiControl 是产品总名；asoulOpt 只负责线程放置、线程亲和度和 WALT per-task boost，不是 App、Binder 或整个项目名。

## 2. 产品目标与长期原则

ZuiControl 是 ROM 内置系统控制能力，只保留三个核心功能：

1. 可控刷新率和场景切换
2. Uperf 性能调度
3. asoulOpt 线程放置/亲和度调度

长期原则：

- 不依赖 Magisk、su 前端、Accessibility 或 App 常驻维持核心调度；
- idle 控制面无 persistent `zui_controld`、无周期 Settings/shell/health publisher；
- 状态变化走 system_server/init 原生事件链；
- 一个能力和一个 knob 只能有一个 owner；
- App 是控制面，不是 refresh、scene、Uperf 或 thread-placement owner；
- 编译通过、进程存活或单次快照不等于真机行为已证明；
- 性能、功耗和稳定性改动必须有同口径前后 A/B。

App 的 `START_STICKY` QuickService/前台通知属于快捷控制面。它可以存在，但核心 refresh/Uperf/asoulOpt 不能依赖它存活。

## 3. 当前真实架构

### 3.1 App 控制面

App 负责：

- UI、QS Tile、通知栏快捷按钮；
- refresh profile 编辑；
- Uperf 四档全局模式和精确用户 App 规则；
- asoulOpt 启停/状态入口；当前 App 未交付 `asopt.conf` 编辑器；
- 按需 Binder 状态和日志导出；
- device-protected pending request 的安全恢复。

App 不得：

- 自己猜前台 App；
- 用 Accessibility、前台服务或 root shell 循环作为 refresh 主路径；
- 直接写显示 sysfs/settings 抢 refresh owner；
- 成为 Uperf scene detector 或 thread-placement owner。

### 3.2 system_server

system_server 是刷新率决策/执行的唯一 owner，也是 Uperf mode 的场景决策 owner。

刷新率链：

`DisplayContent.setFocusedApp(ActivityRecord)`
→ `ZuiControlHooks.onFocusedAppChanged()`
→ `ZuiControlService` 专用 HandlerThread
→ scene/profile
→ DisplayManagerInternal / ROM display policy
→ physical Display.Mode

焦点 hook 中只允许轻量投递，不能做文件 I/O、复杂策略或耗时调用。

Uperf 链：

`ActivityTaskSupervisor.mTopResumedActivity` change / screen event
→ `ZuiControlService.UperfScenePolicy`
→ `sys.zui_control.uperf_mode`
→ Android init property trigger
→ init builtin 写 `effective_powermode.txt`
→ Uperf 执行

### 3.3 init 与执行面

Android init 负责：

- 托管 `zui_uperf` 和 `zui_asoulopt`；
- scheduler start/restart/stop；
- `scheduler_active` ownership 状态；
- init-native OEM `vendor.perfservice` fence；
- 按 property 启动 `zui_control_request` oneshot。

Uperf 是 CPU/power-model 执行 owner；asoulOpt 是唯一 per-task affinity/context-scheduler owner；OEM/thermal 继续保留 GPU/热安全裁决边界。Uperf sysfs 模块仍会写全局 cpuset mask，因此更广义的 topology/knob ownership 必须在后续独立 scheduler-ownership 工作包审计，不能混入当前 refresh state-machine 修改。

V20.4 Uperf架构已删除 `/system/bin/zui_uperf_service` 的5秒process/grep自检和logger FIFO。真机RunId `20260903085341`证明Uperf会替换`-o`路径并写普通文件；Golden wrapper只做既有cpuset放置后`exec`同`performanced`域的`zui_uperf_supervisor`，steady state没有shell。native supervisor使用`PR_SET_CHILD_SUBREAPER`和blocking `waitpid(-1)`监督真实Uperf descendant tree，并只在startup以100ms cadence、最长20秒读取`/data/vendor/zui_control/log/uperf.log`；完整`I Uperf is running`后原子发布`.service_ready_uptime`、关闭日志FD/释放parser，之后零日志轮询。Final Gate已证明startup readiness、610秒steady health和一次真实descendant-tree death恢复；worker-crash文字observer已移除，worker-level fault/storm为`OPTIONAL_HARDENING`，不是V21 blocker。三次连续sub-2s whole-service death的既有init fail-safe不变。没有inotify、新domain、第二restart owner或新增SELinux allow。

### 3.4 command 与 health

命令链：

App 保存 DP pending + Settings request
→ authenticated `zui_control` Binder kick
→ 受保护 command property
→ `zui_control_request`（`disabled` + `oneshot`）
→ `zui_controld --oneshot-request <id> <sha256>`
→ durable claim/action/receipt/terminal ACK
→ exit

硬规则：

- 不得存在 persistent `zui_controld` init service 或 start；
- `zui_controld` 生产 CLI 只接受 `--oneshot-request`；
- terminal request 不得重放 action；
- latency 优化不能破坏 at-most-once、fail-closed、crash-window、force-stop pending 或 reboot pending 安全；
- `getState()` 直接读取 system_server 内存/SystemProperties，是按需 health 主路径；
- 用户主动 `status` 命令可以短启 oneshot，但不得恢复后台 health publisher/heartbeat。

## 4. 刷新率与场景规则

- system_server 是唯一 refresh owner；daemon/App/Accessibility 不得成为第二 owner。
- 未配置场景默认 target 120Hz；Launcher/Desktop 是普通有效场景，不是默认 120 的原因。
- 当前 `displayVote=adaptiveRender`：target=120 时静止 physical actual 可降到 60。不得宣称 120 hard-lock 已完成。
- 是否 hard-lock 必须在后续独立决策中先做流畅度、显示功耗、温度、触控/动画和 144/165 bridge A/B；当前 Refresh Correctness 工作包不改变该策略。
- 144/165 所需 peak compatibility bridge 不是第二 owner；peak/min settings 不能恢复为 daemon 主控制路径。
- target Hz 必须映射到真实 Display.Mode；不能假设 modeId 等于 Hz。
- 不支持的 Hz 必须拒绝或明确 fallback；同目标避免重复 apply；物理切换要防抖。
- 当前只交付 displayHz 语义；fpsCap 仍是兼容字段，`fpsCapPhase=not_delivered`。

场景至少区分：

- `rawFocusedPackage`：系统真实焦点；
- `currentScenePackage`：最近真正成为 foreground 的业务场景，transient 期间保留；
- `lastNonTransientScenePackage`：最近真实业务场景，只作为 QS/ZuiControl 的配置对象；
- `desiredScenePackage` / `attemptedScenePackage` / `appliedScenePackage`：分别表示当前物理策略、最近平台尝试、最近完整成功；
- `targetDisplayHz` / `appliedDisplayHz` / `physicalDisplayHz`：分别表示期望、平台请求成功和面板观测，三者不得混写。

SystemUI、ZuiControl、android 伪包、PermissionController、PackageInstaller、Resolver/Chooser、IME、overlay，以及exact OEM control UI `com.lenovo.screensplit` / `com.zui.freeform.sidebar` 的**非空focused Window**应视为 transient。Launcher 不得盲目过滤，也不得把全部 `com.zui.*` 或全部system App归为transient。

**Foreground-only 产品语义：** physical target 永远由当前真实、非空 foreground/window focus决定。业务App有profile时使用其Hz；未配置业务App及SystemUI、ZuiControl、IME、权限/Resolver/overlay等真实非空transient Window使用neutral/default120。transient不得继承 `lastNonTransientScenePackage` 的Hz。`null`/空focused Window不是owner，也不是default120 transient；App-to-App临时空gap必须保留最后已证明的非空Window policy，直到下一非空edge。

focused window 是 physical raw authority。Activity focus只在尚未收到window signal时作为fallback；一旦真实window已出现，后续Activity metadata变化不得追溯重分类当前window，也不得触发physical apply。空window edge只记录 `EMPTY_FOCUS_TRANSITION`，不覆盖最后非空snapshot、不apply、不改Uperf/business/config；下一非空window edge才改变physical scene。IME关闭恢复最近non-IME window snapshot。当前保证范围是TB321FU default display / 当前active user，多用户切换和未知vendor window package仍是device gate。

QS/当前场景快捷入口只发 Binder命令，由 system_server使用 `lastNonTransientScenePackage`决定修改对象；Main显式 package editor按用户实际选择的业务包操作。两条路径都不得学习或写入 SystemUI/ZuiControl profile。QS/ZuiControl前台时修改后台业务 App只保存配置，当前 physical target仍为 120；该业务 App再次 foreground时才应用新 Hz。

历史 `controlPanel` 缺陷的正确表述是：ZuiControl 曾拥有独立 profile/apply 特判并污染 applied/config 语义。该特判已从 V20.4 源码删除，final-super verifier也要求 marker不存在；但 ZuiControl 真正 foreground 时切到 neutral/default 120 是当前产品要求，不再把 `90 → 120 → 90` 本身写成缺陷。

## 5. Uperf 与 asoulOpt

### 5.1 Uperf

- 只有 `powersave`、`balance`、`performance`、`fast` 四档；不得增加 `auto` 生产入口。
- 决策优先级固定：screen off powersave > exact user-app rule > global mode。
- exact rule 当前契约只接受 `/data/app/*` 用户 App；是否允许 system App 是产品决策，不得把 Settings 被拒绝描述成现有 bug。
- Uperf Native Auto 不作为生产决策 owner；scene/screen mode 由 system_server event-driven 决定。
- exact-rule authority是framework当前真正的top-resumed Activity；Refresh仍使用focused Window，两套authority不得混用。Game离开top-resumed后必须回global；QS未改变top-resumed时不得产生fast→global→fast；freeform/split由framework唯一top-resumed仲裁，visible/PiP不等于authority。
- 当前 Uperf 配置仍保留 `switcher.perapp` 路径，二进制也保留相关能力；只能说生产控制面未选择 auto、真机日志显示 `ContextScheduler disabled` 且 preset 由 inode 驱动，不能说相关代码/能力已经删除。改动后必须回归没有第二 scene owner。
- Uperf 自带 `sched.enable=false`；per-task affinity/context scheduling 只能由 asoulOpt 拥有。
- 当前不宣称接管 Adreno KGSL，不移除 thermal 安全裁决。
- `core_ctl`、input boost 等 knob 只有在持续真机证据成立后才能宣称归 Uperf；被 OEM/kernel 覆盖的无效声明应删除或改 owner。

### 5.2 asoulOpt

- asoulOpt 唯一负责游戏线程 per-task affinity、placement 和 WALT per-task boost。
- 当前配置 `mode=0`、`rt=0`。
- `/data/vendor/asopt.conf` 由 init symlink 到 `/data/vendor/zui_control/asoul/asopt.conf`；不得恢复 `/data/adb/naki`。
- 用户显式 `stop_asoul` 时必须保持 stopped；不得由新 watchdog 误拉起。
- normal recovery/单实例已证明不等于真实游戏效果已证明；效果结论必须包含关键线程 affinity/WALT 时间线和 frame-time A/B。

## 6. Kill switch 与失败语义

当前有效 refresh 属性：

- `persist.zui_control.disable`
- `persist.zui_control.refresh.disable`

`persist.zui_control.daemon_refresh.disable` 只属于已退休 daemon 的历史兼容语义；新代码不得重新依赖它。

全局disable/refresh disable只停止刷新率策略，不能误停Uperf/asoulOpt。`SystemProperties.addChangeCallback`注册的是system_server进程内callback；raw `setprop`只改property area，不会自动向该进程report。runtime-correction source commit `146e096c6a6bc8b3fee60349b856990fd9fb68d2` 为TX10增加严格package+certificate认证、property持久化和同调用直接mask transition，并为raw property edge增加init `exec_background u:r:shell:s0 ... /system/bin/sh -c "exec /system/bin/service call zui_control 1599295570"` 标准 `SYSPROPS_TRANSACTION` poke；没有polling、timer或常驻notifier。必须经 `shell_exec` entrypoint进入shell domain，不得退回直接以该domain执行 `system_file` 的旧命令。当前App UI未接TX10控件，该接口是reserved signed-App API。disabled路径仍只清ZuiControl priority-8 vote、compare/restore peak，并请求WindowManager traversal接管shared `setDisplayProperties()` AppRequest。该AppRequest API没有owner token或同步clear，因此只能报告 `sharedNoToken` / `releaseRequested` / `appRequestHandoffPending`，不得宣称同步物理释放完成。

旧RunId `20260831134511` 的可逆probe固定了根因：raw setprop只写property area，不会自动report system_server进程内callback；标准transaction后才收敛。当前RunId `20260831170720` 的新transport已真机证明：refresh/global raw property各20次disable/enable均20/20且无需人工poke；rapid共80 commanded edge全部收敛；disable/enable边沿各只有一个约37–39ms的init-origin executor和一个标准transaction；367.132秒无边沿观察无残留或周期自启；disabled/enabled boot persistence PASS。约170ms统计是host-observed ADB端到端收敛，不是native callback或physical latency。TX10 UI仍未执行，shared AppRequest仍只能报告handoff requested/pending，不能写成同步clear完成。

Uperf rapid crash storm 当前是 carry-forward 未闭环项。后续稳定性工作包要定义 fail-safe、UI/health 可见性、安全 balance 或 OEM fallback；不得用 persistent watchdog 对抗 Android init，也不得为测试扩大 SELinux。它不属于当前 refresh state-machine 修改。

## 7. Binder 授权与存储

敏感 Binder 操作的当前边界：

- 非 system UID 必须由 UID 包列表包含 `com.zui.zuicontrol`，且签名 SHA-256 命中允许列表；
- release digest：`3fecf3a72ca0e0f24991d49e7306ef4a711711f48a66070755eb0237ecb3ed94`；
- debug digest：`b4cecd3923c11c203931c44e571e95b3d4208937617c6791d94215c077c043a9`，仅 debuggable APK 可用；
- command kick 始终走 App 包名+签名校验，没有 system UID bypass；
- 当前普通 state/profile 交易允许 SYSTEM_UID 作为平台内部例外，version/capabilities 不鉴权，`dump()` 走平台诊断边界。不得扩展这些例外；是否进一步收紧属于显式安全决策。

安装路径只能作附加检查，不能替代签名校验。任何新增 transaction 默认按敏感操作处理，除非明确证明可公开。

存储路径：

- `/data/system/zui_control/profiles.prop`
- `/data/vendor/zui_control/uperf/cur_powermode.txt`
- `/data/vendor/zui_control/uperf/perapp_powermode.txt`
- `/data/vendor/zui_control/uperf/effective_powermode.txt`
- `/data/vendor/zui_control/asoul/asopt.conf`
- `/data/vendor/zui_control/zuicontrol/active_request_claim`
- `/data/vendor/zui_control/zuicontrol/last_request_receipt`

规则：

- profile 写入使用 AtomicFile；配置损坏不能导致 system_server 崩溃；
- 保存前校验 package、Display.Mode、displayHz、fpsCap 和 mode；
- claim/receipt 保持 root:root 0600 与正确 SELinux context；
- 当前 profile文件加载路径仍未完整验证非法 package/package existence。V20.4显式 API已从 calling UID派生 current user，Main也按当前用户过滤；但多用户切换和真机路由尚未验收，不得宣称 load validation或 multi-user整体闭环。

## 8. SELinux 与 final-super 硬门

新增/修改 Binder service、property、目录、文件、daemon/service、二进制或 socket 时，必须同步检查 service_contexts、property_contexts、file_contexts 与 plat/vendor sepolicy。

优先检查：

- `zui_control` service_context；
- `/data/system/zui_control` 与 `/data/vendor/zui_control` file_context；
- command、Uperf mode、scheduler_active property_context；
- system_server add/find、priv_app find；
- `zui_control_request`、Uperf、asoulOpt 与 OEM fence AVC。

有功能阻断 AVC 时先修策略，不得先绕过权限、改 permissive 或扩大测试域。

刷机包硬规则：

- 不能只检查源码、payload 或临时 unpack，必须反向抽查当前显式候选目录中的最终 `super.img`；
- 每个新增/修改 context 和 sepolicy 规则都要在最终 super 抽出的真实文件中命中；
- 修改动态分区内容后确认 `zui072（flash）/work/current/<candidate>/` 中对应逻辑分区镜像重新生成并被 PackSuper 使用；
- `SignNoFec` 改写 footer 后必须重新 PackSuper，再复查最终 super 内容和 SHA-256；
- Windows 无法做 secilc 编译级验证时必须明确边界，刷后以 dmesg/logcat AVC 继续验收。

任何修改 `framework.jar`、`services.jar` 或其它 boot/system_server classpath DEX 的候选，在刷机授权前必须同时通过：host tests、apktool/smali rebuild、final-super reverse extraction、**final artifact ART/dex2oat verifier**、marker/provenance/hash verifier。`apktool build PASS` 或 smali assemble PASS 永远不是 bootability proof。host 无法完成 ART gate 时，必须在当前已恢复目标设备上只读使用最终 super 反解的 DEX/JAR，并只写临时 `/data/local/tmp` 输出进行验证；验证对象 hash 必须与最终 super provenance绑定。

## 9. 已关闭的 V20.3B 证据基线

V20.3B 阶段已关闭；persistent daemon retirement architecture = PASS。下列 PARTIAL 或未覆盖边界保留原证据语义，并已迁移到 V20.4 或后续 backlog，不再构成 HOLD gate。

- persistent `zui_controld` retirement：PASS；idle persistent/request process row 均 0。
- 61.13 秒窗口 ZuiControl worker：0.0000% 单核；89.988 秒 Perfetto 的周期 Settings get/put、health heartbeat、ZuiControl shell fork 均 0。
- OEM fence：20/20 最终 stopped；主机观测 P95 约 120ms；500 quiet samples 无 storm；inactive 60 秒 OEM PID/starttime 稳定 running。
- Uperf normal crash recovery：10/10 PASS；rapid storm PARTIAL，并触发过 `sys.init.updatable_crashing=1`。
- asoulOpt normal recovery：10/10 PASS；bounded storm 3/3 PASS；显式 stop 60 秒保持 stopped。
- Boot/App/权限、transaction security/crash/pending、receipt 0664→0600 migration：PASS。
- command T0→T9 P95 `1022.100ms`；最大瓶颈 T4→T5 durable claim mean `574.2ms`；业务 action mean `12.4ms`。
- 最终受控重启快照 Launcher/target 120/actual 120/balance/Uperf+asoulOpt running，只是最终现场，不证明 120 全时 hard-lock。
- 已分类 AVC 不等于 AVC=0；没有观察到新的 ZuiControl/Uperf/asoulOpt 功能阻断 AVC。

冷启动从未运行过 `zui_control_request` 时 init property 可以为空，不一定字面为 `stopped`；判断 idle 应看 PID、process row、start record 和 trace。

## 10. V20.4 工作包

### 10.1 Refresh Correctness / State Machine（已关闭）

Runtime Correction source `146e096c6a6bc8b3fee60349b856990fd9fb68d2` / RunId `20260831170720` 已通过完整pre-flash与Targeted Device Gate，当前结论为 **PASS / CLOSED WITH EXPLICIT BOUNDARIES**：

1. Boot：19个样本/187.743秒，system_server PID/starttime `2700/988`稳定；Binder/Launcher/Enforcing正确，相关VerifyError/FATAL/RescueParty marker为0。
2. Kill switch：refresh/global raw property各20/20且无人工poke；rapid两prop共80 commanded edge全部最终收敛；无重复apply storm；disabled/enabled boot persistence均PASS。
3. Null Window：Notes90↔Calculator60 100往返=200真实edge，apply delta200、empty delta200、1270个null样本、observed intermediate120=0；same-owner warm relaunch10/10且apply delta0。
4. Transient/OEM：SystemUI、ZuiControl、IME、Permission、Resolver与两个exact OEM control package保持non-empty transient default120；screensplit/sidebar不再覆盖current/last/editable且不创建profile。
5. Regression：freeform/split/PiP与60/90/120/144/165 physical smoke PASS；final `/proc`60.6秒和Perfetto89.985秒的ZuiControl worker为0 tick/0 slice/0 CPU；blocking AVC=0。
6. 最终恢复：Launcher/default120、两项disable=0、仅default 64-byte profile、balance、Uperf/asoulOpt running、PID2714/Binder/Enforcing/boot1。

明确边界：`APP_UI_TX10=NOT_EXECUTED / SIGNED_APP_TX10_DEVICE_PATH_NOT_AVAILABLE`；`UDFPS_LOCAL_VOTE_RUNTIME=NOT_OBSERVED`；fault injection=`NOT_EXECUTED`；secondary user/external display=`NOT_VALIDATED`。这些不是PASS，但已作为当前硬件/targeted Gate的非阻断范围边界接受。

本工作包不得修改 command transaction、Uperf/asoulOpt 生产策略、120 hard-lock 决策或 Binder 安全契约，不得引入 App/daemon 第二 owner、polling 或 watchdog。

```text
V20_4_REFRESH_SOURCE_HOST_BUILD=PASS
V20_4_REFRESH_FIXED_CANDIDATE_BOOT=PASS
V20_4_REFRESH_DEVICE_VALIDATION=PASS
V20_4_REFRESH_KILL_SWITCH_DEVICE=PASS_NO_MANUAL_POKE
V20_4_REFRESH_ACTIVITY_WINDOW_ORDER=PASS_0_OBSERVED_INTERMEDIATE_DEFAULT
V20_4_REFRESH_VENDOR_OVERLAY=PASS_EXACT_CLASSIFICATION
V20_4_REFRESH_WORK_PACKAGE=CLOSED_WITH_EXPLICIT_BOUNDARIES
UDFPS_LOCAL_VOTE_RUNTIME=NOT_OBSERVED
FAULT_INJECTION_DEVICE_PATH=NOT_EXECUTED
SECONDARY_USER_EXTERNAL_DISPLAY=NOT_VALIDATED
```

### 10.2 Uperf Architecture & Upstream Rebase（已由 Golden Final Gate 关闭）

本包冻结upstream `v1.0.6`，ZIP SHA-256 `00b19294e4efc202fd794decb5526b5ad903dca3a15c9af3cfc335edab2b5fcc`。upstream与production Uperf binary均为SHA-256 `f1265757009ff0c85dd8587d9e7bfcf5e51d10d36fe5e1341688215ae1fb49d8`，byte-for-byte相同，不替换binary。保留SM8650的balance/powersave idle sample/slack四项值；可选`sfanalysis`因确认造成startup阻断而保持disabled。sched仍disabled、Native Auto仍无production入口、asoulOpt/GPU/thermal边界不变。

实现使用framework display-global top-resumed change event作为Uperf exact scene authority。RunId `20260903085341`确认FIFO output假设错误；Golden改为regular startup log readiness、native subreaper与blocking waitpid。RunId `20260903110608`通过regular-log development Gate，随后诊断确认合法package后的transitional null会在约4.5–5.2ms覆盖scene；source `29f23f8d590b88f0d472c12373366a9ef14e8330`加入bounded null revalidation。最终Build RunId `20260903144915` / CI `33724674012` / Device Gate `20260903153438`通过三次冷启动、15行scene matrix、610秒soak、相关AVC0、fixed-seven physical read-back 7/7和post-readback boot。Golden exact hashes只认`V20_4_GOLDEN_BASELINE.json`。

```text
V20_4_GOLDEN_SOURCE=29f23f8d590b88f0d472c12373366a9ef14e8330
V20_4_GOLDEN_BUILD_RUN_ID=20260903144915
V20_4_GOLDEN_DEVICE_GATE_RUN_ID=20260903153438
V20_4_UPERF_REGULAR_LOG_READINESS=PASS
V20_4_UPERF_TRANSITIONAL_NULL=PASS
V20_4_UPERF_SCENE_MATRIX=PASS_15_OF_15
V20_4_UPERF_TEN_MINUTE_SOAK=PASS
V20_4_UPERF_PHYSICAL_READBACK=PASS_7_OF_7
V20_4_UPERF_WORK_PACKAGE=CLOSED_WITH_EXPLICIT_BOUNDARIES
```

### 10.3 其它 carry-forward backlog

- command durable transaction latency：目标普通本地命令 P95 约 300–500ms，同时保持 at-most-once/crash safety；
- T8 request-ID 并发可观测性；
- 120 hard-lock A/B 决策；
- Uperf core_ctl/input boost/cpuset真机owner证明与performance A/B；
- asoulOpt 真实游戏 affinity/WALT/frame-time 效果；
- 24h/72h soak、极端回归、功耗与日志增长 baseline。

需要产品确认但不是当前 bug：system App 是否允许 Uperf exact rule。

## 11. V21 / V22 边界与禁止回退

V21 Phase 1 engineering cleanup / Golden freeze / portable workspace finalization 已 **CLOSED**，不得创建 Phase 1E。它不是 production cleanup。AppOpt、XML/ZuiPP bridge、未交付 FPS cap、旧 KGSL 实验、retired daemon compatibility、旧命名和生产历史逻辑的代码级清理仍须独立工作包与review；Phase 2 未开始，不得自动进入。

V22 GPU 单独研究 KGSL/devfreq、GameHelper/ZuiPP/PowerHAL/thermal ownership，再决定 Uperf CPU + OEM GPU 或 CPU+GPU。thermal 始终保留安全裁决；必须做帧时间、功耗、温度 A/B。

V20.4 禁止：

- 新 persistent `zui_controld`、watchdog 或常驻 command/health poller；
- Accessibility/App foreground refresh owner；
- App root-shell refresh loop、daemon refresh_tick/learn_refresh、daemon peak/min 抢写；
- Uperf Native Auto 作为生产 scene owner；
- 第二 per-task affinity/context-scheduler owner，或 Uperf sched 与 asoulOpt 并行；
- AppOpt/XML/ZuiPP/FPS cap 大清理；
- direct KGSL/GPU 正式接管或 thermal 大改；
- 无证据升级 Uperf/asoulOpt；
- `/data/adb/naki/asopt.conf` 或旧产品命名回流。
