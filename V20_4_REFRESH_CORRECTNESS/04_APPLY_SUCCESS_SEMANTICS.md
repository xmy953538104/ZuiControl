# V20.4 Apply Success Semantics

## State order

每次 reconcile 先更新 `desiredScenePackage` 与 `targetDisplayHz`。只有 mode 和平台服务已通过前置检查、即将发生平台 mutation时，才更新 `attemptedScenePackage` / `attemptedDisplayHz`。

完整成功顺序为：

1. peak compatibility bridge；
2. ZuiControl global priority-8 render vote；
3. `DisplayManagerInternal.setDisplayProperties()` AppRequest kick；
4. 三步均成功返回后才更新 `appliedScenePackage` / `appliedDisplayHz`，并递增 `refreshApplyCount`。

`applied*` 表示“本服务最近一次完整平台请求成功返回”，不是 physical truth。physical 只读 `Display.getMode().getRefreshRate()` 并输出为 `physicalDisplayHz`；兼容字段 `actualDisplayHz` 是其 alias。

## Dedup

只有已持有 render vote、已有 active AppRequest、display/mode/Hz 与 desired 全部相同，且不是 force apply时才 `skipSame`。dedup 可把等价的 policy key 从业务 App 120重标为 `default`，但不递增 apply count。

foreground-only 语义下，A=90 → transient default=120 → A=90 是两次真实 target change，不得 dedup。A=120 → transient default=120 可以 dedup。

## Failure

- mode lookup或 DMI 不可用发生在 mutation 前：保留上一份已证明的 applied state，写 `failedBeforeMutation`。
- peak/vote/AppRequest 阶段失败：best-effort 清 global vote、release peak、请求 WM handoff，随后清空无法证明的 applied state，写 `failedAfterMutation` 与具体 cleanup error。
- global vote 在调用 `updateGlobalVote` 前先进入 conservative owned/applyPending 状态，覆盖 ROM“map 已修改、listener 后抛异常”的 mutation-after-throw 窗口。
- disabled reconcile只更新 desired/target；已有 releasePartial error不被后续 focus覆盖。

## Profile transaction

profile 先修改内存、再用 AtomicFile 持久化。保存失败时恢复原内存 entry并返回 `ok=0`，不 apply。保存成功但当前 foreground apply 失败时配置仍持久化，响应为 `ok=0`、`profileSaved=1`、`applyError=*`；客户端不能把它当 physical success。后台业务 App或 transient 控制界面编辑只返回 `savedOnly`。

已知 transient package 的显式 profile 写入被拒绝；加载 legacy profile时跳过 transient entries。neutral/default profile固定为 120/0/DISPLAY_ONLY，不能由历史文件改写。
