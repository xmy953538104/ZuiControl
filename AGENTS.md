# ZuiControl 项目规则

## 0. 权威性与当前阶段

本文件是 ZuiControl 的当前长期规则。用户在当前会话中的明确要求优先于本文件；当前生产源码决定“实际实现了什么”，当前最终真机证据决定“实际证明了什么”。如果三者冲突，先报告冲突，不得为了符合文档而强改源码，也不得用旧报告覆盖新证据。

当前设备/系统：TB321FU / ZUI 16.1.11.072。

当前工程阶段：**V20.4 — Final Stability & Efficiency**。当前第一工作包是 **V20.4 Refresh Correctness / State Machine**；尚未生成或验证新的 V20.4 生产包。

最近一次完整真机基线：V20.3B，RunId `20260830181816`。V20.3B persistent daemon retirement architecture = **PASS**，V20.3B 阶段已经关闭。历史 decision 中的 `PARTIAL / HOLD FOR HUMAN REVIEW` 是当时的阶段转换 gate，现已解除，不得再用它阻止 V20.4，也不得要求重做 V20.3B。rapid Uperf crash storm、T8 request-ID、ZuiControl transient 等未闭环发现没有变成 PASS，而是按归属正式 carry forward 到 V20.4 工作包或后续 backlog。

当前生产 App 源码仍是 versionCode 49 / versionName 0.21.12 / `ZuiControlV49`，Binder version 仍返回 19。这些是已验证候选的制品标识，不是当前工程阶段号，不得据此把规则退回 V19。

新会话默认只读：

1. `AGENTS.md`
2. `CURRENT_PROJECT_STATE.md`
3. `README.md`
4. 当前生产源码
5. `CURRENT_EVIDENCE_INDEX.md`

`CODEX_NEW_SESSION_HANDOFF_V20_3B.md` 与 `TAKEOVER_REPORT.md` 已完成使命，位于仓库外 `D:\3.VScode\Mi\ZuiControl_Archive\handoffs\`，只作历史追溯。旧阶段报告、raw、device package、Perfetto 和 logcat 也已移到 `D:\3.VScode\Mi\ZuiControl_Archive\`。默认不得扫描 archive；只有 `CURRENT_EVIDENCE_INDEX.md` 指向的具体结论受到质疑时，才定向读取对应最小文件。

不要为了“了解项目”通读 V19、V20.1 Native Auto、旧 daemon、AppOpt/XML/ZuiPP 或全部失败 device run。

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

scene/screen event
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

`/system/bin/zui_uperf_service` 有 5 秒一次的本 service-cgroup 自检，失败后退出交给 init 恢复。它不是 Settings/Binder health publisher，也不是已退休的控制面 heartbeat；文档不得把“无 health heartbeat”扩大成“整个执行面绝无任何周期自检”。

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
- `currentScenePackage`：当前业务场景；
- `lastNonTransientScenePackage`：最近真实业务场景；
- `appliedScenePackage`：当前实现的“最近尝试应用 profile 标签”，不能自动等同成功物理场景。

SystemUI、ZuiControl、android 伪包、PermissionController、PackageInstaller、Resolver/Chooser、IME、overlay、null/空包名应视为 transient。Launcher 不得盲目过滤。

QS/App 只发 Binder 命令，由 system_server 使用 `lastNonTransientScenePackage` 决定修改对象；不得学习或写入 SystemUI/ZuiControl profile。

当前已确认缺陷：`com.zui.zuicontrol` 在通用 transient 判断前走 `controlPanel` 特判并应用自身/default profile。非 120 业务场景下源码会尝试切回默认 120，且之后的 QS 可能继续复用被污染的 `appliedScenePackage`。它是 V20.4 Refresh Correctness / State Machine 的第一优先级，必须用 60/90 等非 120 profile 真机闭环，不能写成纯诊断字段问题。

## 5. Uperf 与 asoulOpt

### 5.1 Uperf

- 只有 `powersave`、`balance`、`performance`、`fast` 四档；不得增加 `auto` 生产入口。
- 决策优先级固定：screen off powersave > exact user-app rule > global mode。
- exact rule 当前契约只接受 `/data/app/*` 用户 App；是否允许 system App 是产品决策，不得把 Settings 被拒绝描述成现有 bug。
- Uperf Native Auto 不作为生产决策 owner；scene/screen mode 由 system_server event-driven 决定。
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

预期：全局 disable/refresh disable 停止刷新率策略，但不能误停 Uperf/asoulOpt。当前源码没有 property observer，只有后续 focus/refresh/peak 事件才重新读取；disabled 路径清 priority-8 render vote，但尚未用真机证明完整清除既有 `setDisplayProperties()` AppRequest。kill switch 的触发事件、priority-8 vote、AppRequest 和 peak bridge 释放语义统一归入 V20.4 Refresh Correctness / State Machine；在闭环前不得宣称即时或完整释放。

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
- 当前 profile 文件加载路径未完整验证非法 package/package existence，多用户 UI 仍固定 userId=0；不得宣称这两项已闭环。

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

- 不能只检查源码、payload 或 `work/unpack`，必须反向抽查最终 `【B刷机】072/super.img`；
- 每个新增/修改 context 和 sepolicy 规则都要在最终 super 抽出的真实文件中命中；
- 修改动态分区内容后确认对应 `work/img/*.img` 重新生成并被 PackSuper 使用；
- `SignNoFec` 改写 footer 后必须重新 PackSuper，再复查最终 super 内容和 SHA-256；
- Windows 无法做 secilc 编译级验证时必须明确边界，刷后以 dmesg/logcat AVC 继续验收。

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

### 10.1 Refresh Correctness / State Machine（当前第一工作包）

按当前事实排序：

1. 修复 ZuiControl `controlPanel` 特判，使自身真正按 transient 处理；用非 120 profile 验证 App → ZuiControl → QS → 返回 App。
2. 定义 `rawFocusedPackage`、`currentScenePackage`、`lastNonTransientScenePackage`、`appliedScenePackage` 的状态不变量；`applied` 不得在 apply/skip/fail/disabled 后伪装成 physical success。
3. 保证 QS Tile、通知 QuickService 和 App 修改的始终是上一个真实业务场景，不学习 SystemUI/ZuiControl。
4. 为两个 refresh kill switch 定义即时触发/恢复事件，并完整释放或重建 priority-8 vote、`setDisplayProperties()` AppRequest 与 peak compatibility bridge。
5. 把 partial apply、unsupported mode、skipSame、disabled、失败回退和防抖纳入同一状态机与可观测结果。
6. 回归 profile 校验、AtomicFile 损坏恢复、当前场景 apply、userId 和 IME/PermissionController/Resolver/SystemUI/ZuiControl transient 矩阵。
7. 在 60/90/120/144/165 下记录 current/last/applied、target/actual、vote/AppRequest、peak 和 profile hash；transient 仍是第一优先级。

本工作包不得修改 command transaction、Uperf/asoulOpt 生产策略、120 hard-lock 决策或 Binder 安全契约，不得引入 App/daemon 第二 owner、polling 或 watchdog。

### 10.2 其它 carry-forward backlog

- command durable transaction latency：目标普通本地命令 P95 约 300–500ms，同时保持 at-most-once/crash safety；
- Uperf rapid crash storm fail-safe；
- T8 request-ID 并发可观测性；
- 120 hard-lock A/B 决策；
- Uperf core_ctl/input boost/cpuset owner 审计；
- asoulOpt 真实游戏 affinity/WALT/frame-time 效果；
- 24h/72h soak、极端回归、功耗与日志增长 baseline。

需要产品确认但不是当前 bug：system App 是否允许 Uperf exact rule。

## 11. V21 / V22 边界与禁止回退

本轮 Active Repository Context Cleanup 只外移文档/证据，不是 V21。V21 Production Cleanup 只在 V20.4 架构稳定后进行，集中处理 AppOpt、XML/ZuiPP bridge、未交付 FPS cap、旧 KGSL 实验、retired daemon compatibility、旧命名和生产历史逻辑；必要 migration history 要保留。

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
