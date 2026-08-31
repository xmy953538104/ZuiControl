# Kill Switch Event Design

状态：以下设计已冻结在 source commit `146e096c6a6bc8b3fee60349b856990fd9fb68d2`。final candidate `RunId 20260831170720` 的 host、CI、final-super、ART、split CIL 与官方 `host_init_verifier` exact-file gate均已通过；候选尚未刷入，刷后device验证仍待人工授权。

## 候选双入口

### Normal product/control path

复用既有、已认证的 Binder TX10：

```text
预留给签名ZuiControl App的Manager API
→ setModuleEnabled("refresh", enabled)
→ enforceCommandCallerAllowed()
→ package + certificate验证（没有SYSTEM_UID bypass）
→ system_server 写 persist.zui_control.refresh.disable
→ 同一调用内直接执行 mask transition
→ 返回 requested/effective/persistent 状态
```

TX10只接受 exact module `refresh`；其它 module 返回 `unsupported_module`。它不依赖 generic callback，不等待 init poke，也不经 shell。若 global disable bit 仍为1，返回会诚实区分 `requestedEnabled=true` 与 `refreshDisabled=true/mask=1`。当前release App UI尚未接入kill-switch控件，因此这是reserved signed-App API；不能把normal UI路径写成已真机执行。

### Engineering/emergency path

保留两条既有 raw property 单命令入口：

```text
persist.zui_control.disable
persist.zui_control.refresh.disable
```

最终super反向提取的 [`zui_refresh_kill_switch.rc`](raw/final_policy_gate_20260831170720/final_extracted_zui_refresh_kill_switch.rc) 对每个 property edge 仅执行一次：

```text
exec_background u:r:shell:s0 shell shell -- /system/bin/sh -c "exec /system/bin/service call zui_control 1599295570"
```

它通过具有 `shell_exec:entrypoint` 的系统shell进入显式 `u:r:shell:s0`，随后 `exec` 系统自带 Binder CLI；直接把 `/system/bin/service` 作为该domain入口会被目标SELinux entrypoint边界拒绝，因此不得恢复旧写法。它不枚举所有服务，不新增二进制或命名daemon。设计上每次edge只产生一个短进程，direct Binder 后到达的init poke由现有mask dedup消化。final split CIL已用目标 `/system/bin/secilc` 按Android 14 r75 boot argv编译通过；短进程退出、idle零进程/零CPU和不产生第二次apply仍须刷后真机验证。

## Boot persistence

服务构造器在注册 callback 和取得 refresh ownership 前直接读取两个 persist property。因此：

- property edge 早于 `zui_control` Binder发布时，即使一次 poke找不到服务，也不会丢失持久状态；
- `system_server` 重启后会按持久 mask 初始化；
- disabled boot 不会先取得 refresh ownership再等待通知。

## SELinux边界

两个 exact bool property 从宽泛 `shell_prop` 映射到专用 `zui_control_refresh_disable_prop`：

- `system_server`：set + read，用于认证 Binder 的持久化；
- `shell`：set + read，保留 ADB engineering入口；
- `priv_app` / `untrusted_app`：没有直接 property write；
- 明确禁止给 `system_server` 整个 `shell_prop:set`。

标准 poke本身不携带状态 mutation；未获 property write权限的调用者不能改变kill truth，写权限仍是状态变更边界。但 `SYSPROPS_TRANSACTION` 会唤醒system_server进程中全部已注册的sysprop callbacks，并非只唤醒ZuiControl。任何能find/call该Binder的主体可能制造额外callback wakeup，因此新候选安全gate必须验证未授权poke不改变状态、callback工作有界且rapid poke不形成持续storm。

## 收敛与边界

- stable disable/enable：host contract为TX10同步收敛、raw property由edge poke收敛；真机结果待新候选。
- rapid toggle：每次 poke只作为 wakeup；worker始终重读两个property的最终truth。
- Uperf/asoulOpt/command：不读取refresh mask，不被停止。
- AppRequest：仍是 `sharedNoToken`，只能诚实报告 traversal handoff requested/pending；不能把同步调用写成物理释放完成。

明确没有引入 polling、`postDelayed`、固定timer、常驻进程或恢复 persistent `zui_controld`。
