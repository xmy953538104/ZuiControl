# V20.4 Refresh State Model

状态：implementation contract，先于生产代码修改建立。
产品语义修订：2026-08-31 的 foreground-only 要求覆盖此前的 transient inheritance 要求。
范围：只定义 refresh scene、apply、diagnostics 与 kill-switch；不改变 Uperf/asoulOpt、command、120 adaptive-render、GPU 或 thermal。

## 1. 两条互不混用的状态轴

Refresh physical target 与 configuration target 是两个概念：

- **physical target** 只由当前 `rawFocusedPackage` 决定。当前 focus 是可配置业务 App 时读取该 App profile；未配置业务 App 使用 default 120；SystemUI、ZuiControl、IME、权限 UI、Resolver、临时 overlay 或空 focus 使用 default 120。
- **configuration target** 使用 `lastNonTransientScenePackage`。它让 QS/ZuiControl 在自身位于前台时仍能修改刚才的业务 App，但不得让该业务 App profile 反向限制当前 transient 屏幕。

因此 `lastNonTransientScenePackage` 绝不参与 physical target 继承。

## 2. 字段唯一含义

| 字段 | 唯一含义 | 更新时机 | 禁止语义 |
| --- | --- | --- | --- |
| `rawFocusedPackage` | default display 上最近一次有效 focused-window owner；window signal 尚未出现时才以 focused Activity fallback；允许为空或 configuration-transient | 每次 window/activity/IME event | 不能直接当 profile 编辑对象 |
| `currentScenePackage` | 最近一次真正成为 foreground 的可配置业务 scene；transient 期间保留 | 仅业务 focus | 不能表示当前 physical target 的 owner |
| `lastNonTransientScenePackage` | 最近一次有效业务 scene，供 QS/ZuiControl 等配置入口定向 | 仅业务 focus | 不能参与 transient 的 physical target 计算 |
| `desiredScenePackage` | 当前 physical policy key：业务 focus 时为业务 package；transient/空 focus 时为字面 `default` | 每次 focus、profile 变化、refresh enable/recompute | 不代表平台已接受；`default` 不是 SystemUI/ZuiControl profile |
| `attemptedScenePackage` | 最近一次真正进入 platform apply 的 physical policy key | platform apply 前 | disabled 与纯 dedup 不算新 attempt |
| `appliedScenePackage` | 最近一次 platform request 全部成功，或与成功状态严格同 target 的 physical policy key | apply success；严格 dedup 可重标为等价 key | 不能在尝试开始、失败或 disabled 时预写 |
| `targetDisplayHz` | `desiredScenePackage` 对应的 desired display Hz | desired policy 确定时 | 不等于 physical Hz |
| `attemptedDisplayHz` | 最近一次 platform apply attempt 的 Hz | platform apply 开始时 | 不代表成功 |
| `appliedDisplayHz` | 最近一次完整 platform apply 成功的 Hz | apply success；干净 disable/release 后为 `0` | 不等于持续 physical Hz |
| `physicalDisplayHz` | `Display.getMode().getRefreshRate()` 的独立观测值 | Binder/dumpsys state snapshot | target 120 时仍可能因既有 adaptive semantics 降至 60 |
| `lastApplyReason` | 最近一次 reconcile/release 的 outcome 与触发源，如 `focus:applied`、`focusTransient:skipSame`、`propertyDisable:releaseRequested` | 每次 refresh reconcile/release | 不能替代错误字段 |
| `lastApplyError` | 最近一次 refresh apply/release failure；成功、dedup 或干净 release 后为空 | apply/release outcome | 不能混入 profile I/O、Uperf 或 command error |

兼容字段：`actualDisplayHz` 保留为 `physicalDisplayHz` alias；`lastApply` 保留为 `lastApplyReason` alias；`lastError` 继续承载旧 profile/general error。

## 3. Configuration-transient 分类

业务 scene 包括 Launcher、普通用户 App，以及具有 profile 的真实业务 App。业务 focus 更新 `currentScenePackage`、`lastNonTransientScenePackage` 和业务 user/display。

configuration-transient 至少包括空/null、SystemUI、ZuiControl、`android` pseudo package、PermissionController、PackageInstaller、Resolver/Chooser、IME/keyboard、temporary overlay 和已确认的 GameHelper overlay。它们：

1. 可以更新 `rawFocusedPackage`；
2. 不更新 current/last business；
3. 不可创建或加载自身用户 profile；
4. physical policy 必须切到 `default`，通常 target 120；
5. 不得继承 last business profile。

“transient”在本模型中只描述配置 ownership，不表示 physical refresh no-op。

focused window 是 physical raw authority。window edge 到达时，owner 为空或命中已知 transient classifier才按 transient/default 120；其它业务 package按自身 profile处理。Activity/window package暂时不一致本身不再是 transient条件，否则 Android 的 Activity-first / window-first 合法事件顺序会制造错误 default 120。

Activity hook只在尚无 window signal时作为 fallback。一旦 `mLatestWindowFocusSeen=true`，Activity变化只更新不可变 metadata snapshot；它不能改写当前 non-IME window的 package/transient分类，也不能触发 physical apply。若 window先到、随后同 package Activity metadata到达，只补全 uid/user tuple，不重新分类或重复 apply。SystemUI、Permission、Resolver与已知 vendor overlay已在真实 window edge被分类为 transient，因此背后 Activity变化不会把它们升级成业务 scene。IME单独覆盖 physical raw；关闭IME时恢复最近 non-IME focused-window snapshot，不能盲目恢复背后的 Activity。

hook线程只分配/替换 immutable `FocusSnapshot` 并向 ZuiControl HandlerThread post；不做 I/O、Settings、package scan、DisplayManager apply、sleep、polling或timer。Activity、non-IME window与effective focus各以单个 volatile snapshot发布，避免 package/user/transient的撕裂读取。

当前产品只面向 TB321FU 内屏；非 default display 的 focus/IME hook 不进入状态机，mode lookup 也不跨 display fallback。global priority-8 的平台作用域仍会覆盖所有 display，外接屏/desktop mode 保留为未验证边界。host 模型只证明当前 active user；window-first 时 uid/user metadata 的跨用户切换结果必须由 device matrix 验证。

## 4. 状态转换

| Event | current/last business | desired physical key/target | platform action | configuration action |
| --- | --- | --- | --- | --- |
| configured business A focus | 更新为 A | A / profile Hz | target 变化或 ownership 缺失时 apply | none |
| unconfigured business focus | 更新为该 App | App / default 120 | 需要时 apply 或 strict dedup | none |
| SystemUI/ZuiControl/IME/overlay focus | 保持 | `default` / 120 | 从非 120 target 进入时 apply；same target 可 dedup | none |
| transient 期间 QS/ZuiControl 修改 | 保持 | 仍为 `default` / 120 | 不为后台业务 App apply | 保存到 last business |
| 返回业务 A | 更新/确认 A | A / 最新 profile Hz | 立即 apply 或 strict dedup | none |
| window=A 时 Activity metadata A→B | 保持 A | 保持 A / A profile Hz | none；等待真实 window edge | none |
| Activity-first 后 window A→B | 更新为 B | B / B profile Hz | 一次 B apply；无 intermediate default | none |
| window-first A→B 后 Activity A→B | window edge已更新为 B | 保持 B / B profile Hz | Activity metadata only；不重复 apply | none |
| explicit edit 当前 raw business | 保持 | 该业务 App / 新 Hz | 立即 apply | 保存该 App |
| explicit edit非当前 raw package | 保持 | 不变 | none | 只保存 |
| refresh/global disable edge | 保持 | 保持，便于诊断 | 停止写入并安全 handoff/restore | none |
| refresh/global enable edge | 保持 | 从当前 raw focus 重新计算 | 无需 focus，立即 reconcile | none |
| unsupported Hz | 保持 | 当前 physical desired 不变 | none | 拒绝保存并报告错误 |

例：A=90 → SystemUI 时，`currentScenePackage=A`、`lastNonTransientScenePackage=A`、`desiredScenePackage=default`、`targetDisplayHz=120`。QS 把 A 改为 144 时当前 target 仍为 120；收起 QS、raw focus 回到 A 后才 apply 144。

## 5. Apply、ownership 与 physical truth

ROM 反查确认 per-display priority 8 是 `PRIORITY_AUTH_OPTIMIZER_RENDER_FRAME_RATE`，会被 UDFPS 使用，不能继续作为 ZuiControl 的 per-display slot。V20.4 改用 `VotesStorage.updateGlobalVote(8, ...)`：当前 ROM 无其它 global priority-8 writer；合并时 per-display vote 优先于同 priority global vote，因此 UDFPS local vote 可覆盖 Zui global fallback，UDFPS 删除 local vote 后 global 自动恢复。disable 只删除 global priority 8，不删除任何 display-local vote。

该 owner 边界保留已验证档位所依赖的持久 render vote，同时避免旧实现与 UDFPS 互相覆盖/误清。global vote 会影响所有 display；TB321FU 当前单内屏适用，外接屏/desktop mode 列为未验证平台边界。

`setDisplayProperties()` 同样是 WindowManager/DMS 共享 AppRequest，没有 caller token。disable 不写 zero request、不清全局 state，而是停止 ZuiControl 写入并调用 `WindowManagerInternal.requestTraversalFromDisplayManager()`，要求 WindowManager 用当前窗口状态重发其 AppRequest。该动作是安全 handoff request，不虚构为同步清除所有系统 AppRequest。

因此 `appRequestHandoffPending=true` 表示 traversal 已请求、尚无 completion callback 可证明完成；disable 成功结果写作 `releaseRequested` 而不是同步 `released`。最终 AppRequest 是否被 WindowManager重发必须在device matrix用 `dumpsys display`验证。快速 disable→enable 时 global render vote只能继续提供 render-rate约束，不能证明 shared AppRequest或physical target已经正确恢复；迟到 traversal仍属于真机验证项。

peak bridge 只在 ZuiControl 实际改写时记录原值；release 仅在当前值仍等于 ZuiControl 最后写值时 compare-and-restore。若外部 owner 已改值，则保留外部值。`min_refresh_rate` 从不写。

`applied*` 只表示本服务发起的完整 platform request 成功返回，不表示 panel 已物理稳定命中。physical truth 只来自 `physicalDisplayHz` 与 `dumpsys display`。

## 6. Failure、dedup 与计数

- `refreshApplyCount` 只在完整 apply 成功后递增。
- `skipSameCount` 在 active request 的 display/mode/Hz 与 desired 严格相同时递增。
- apply 前先更新 desired；platform call 前才更新 attempted；成功后才更新 applied。
- validation/mode 查找失败发生在 platform mutation 前，保留上一份仍可证明的 applied state。
- platform mutation 发生后失败时执行 best-effort peak rollback 与 WindowManager handoff；不能证明完整旧状态时清空 applied 并写入 `lastApplyError`。
- `refreshNow` 强制按当前 raw focus 重算与 apply，不使用 applied 或 last business 作为 physical source。
- profile AtomicFile 保存失败时回滚内存 mutation并返回 `ok=0`；profile 已保存但当前 foreground apply 失败时返回 `ok=0`、`profileSaved=1`，不能让 UI 把 physical apply 伪装成成功。

foreground-only 语义意味着：A=90 ↔ ZuiControl/default=120 或 A=90 ↔ SystemUI/default=120 的每个真实 focus edge 都需要 apply；100 次往返不再以“apply 接近 0”为目标。去重目标是相同 raw focus 的重复事件、120-default ↔ 120-default，以及相同 display/mode/Hz 的重复请求。

## 7. Kill-switch edge

使用 `SystemProperties.addChangeCallback()`；全局callback读取两个属性形成wakeup hint并post到ZuiControl HandlerThread，worker消费时再读一次两个真实property作为最终truth；注册后立即补读一次关闭read/register窗口。单独稳定的disable和enable都必须无需focus切换而事件驱动收敛；generic property callback不承诺观察极短rapid toggle的每个intermediate value，rapid gate只要求最终可靠收敛到两个属性的真实最新mask。无timer、polling、daemon或App owner。

Disable 停止 refresh apply，只删除 ZuiControl 的 global priority-8 vote，compare-and-restore peak，再请求安全 handoff shared AppRequest；不删除 UDFPS 的 per-display priority-8 vote。一次 release 失败时只安排一个 bounded immediate retry，之后保留 partial/error；后续真实 focus/profile 或 disable-mask edge可再触发一次 bounded retry，但没有循环。Enable 必须在 atomic latest-focus snapshot 与 worker raw一致后立即重算：若 App B 仍 foreground 则恢复 B profile；若 SystemUI/ZuiControl 仍 foreground 则应用 default 120。Uperf/asoulOpt 与 command plane 不在该转换内。
