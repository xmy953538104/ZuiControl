# Refresh 最终架构与踩坑

## 当前最终架构

自定义刷新率只在配置包真正 foreground/focused 时生效。SystemUI、
ZuiControl、IME、Permission/Resolver/overlay 无自身 profile 时用 default120。
返回业务 App 后恢复该 App profile。真实非空 focused Window 是 physical
authority；empty-focus 只是过渡，保留最后非空 policy 等下一个非空 edge。

## Owner / Authority

- physical target：当前 focused Window package。
- configuration/QS target：`lastNonTransientScenePackage`。
- apply：system_server 的 DisplayManagerInternal / ROM display policy。
- `desired/attempted/applied/physical` 必须分离，`appliedScenePackage` 只在成功后更新。

## 已淘汰设计

- transient 继承上一业务 App Hz。
- empty focus 直接 apply default120。
- Activity metadata 在真实 Window 后追溯重分类 physical target。
- `controlPanel` 成为独立 profile owner。
- 只 `setprop` 就假定 system_server 立即看到 kill switch。

## 为什么错

继承违反 foreground-only 产品语义；empty-focus 会在 App 切换间制造
intermediate120；Activity 回填会覆盖已到达的 Window truth；raw sysprop 不会
自动触发 process-local callback。

## 正确做法

非空 Window edge 决定 physical Hz；transient 仅表示不学习/不覆盖配置对象。
QS 修改上一业务 App profile，当前 SystemUI 仍保持 120。Kill switch 走签名
App transaction 或 init edge-only sysprop poke，disabled 后释放本地 vote 并请求
WindowManager traversal handoff。多窗口证据只认 display-global `ResumedActivity:`。

## 禁止重新引入

`inherit transient Hz`, `empty -> default120`, `task-local topResumedActivity`,
`Activity overrides Window`, `setprop implies callback`, `controlPanel profile owner`。

## 必须阅读触发关键词

`refresh`, `focused window`, `freeform`, `split`, `PiP`, `IME`, `empty focus`,
`OEM transient`, `SystemUI`, `QS`, `kill switch`, `AppRequest`。
