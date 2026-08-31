# V20.4 Configuration-Transient Policy

## 定义

本工作包中的 transient 只表示“不能成为用户业务 profile owner”。它不表示刷新率保持不变，也不表示继承上一业务 App。

| raw focus 类型 | current/last business | physical profile source | configuration target |
| --- | --- | --- | --- |
| 已配置业务 App | 更新为该 App | 该 App profile | 该 App |
| 未配置业务 App | 更新为该 App | default 120 | 该 App |
| SystemUI / QS | 保持 | default 120 | last business |
| ZuiControl | 保持 | default 120 | last business（仅“当前场景”编辑入口） |
| IME / Permission / Resolver / PackageInstaller / overlay | 保持 | default 120 | 无隐式写入；显式控制入口仍可使用 last business |
| exact OEM control UI：`com.lenovo.screensplit` / `com.zui.freeform.sidebar` | 保持 | default 120 | 保持 last business，不写 profile |
| null / empty | 保持 | 保持最后非空 policy；不 apply | 保持 last business，不写 profile |

最小 classifier 保留明确 package heuristic，不引入大型 window taxonomy。default display 的非空 focused-window owner 是 raw authority：命中SystemUI、ZuiControl、IME、Permission/Resolver/PackageInstaller、overlay，以及 exact `com.lenovo.screensplit` / `com.zui.freeform.sidebar` 时为transient；Activity/window package暂时不一致本身不再改变当前window分类。`com.zui.zuicontrol`与`com.android.systemui`走同一路径，不再存在`controlPanel` refresh特判。未命中classifier的未知vendor窗口仍是需要真机补充具体package的边界，不能靠背后Activity变化追溯重分类。

空/null window 不属于 transient classifier，也不是 physical owner。`EMPTY_FOCUS_TRANSITION` 只记录诊断与计数，不覆盖最后非空 raw snapshot，不更新 desired/target，不触发 apply；最后非空 policy 保持有效，直到下一次真实非空 window edge。system_server 初始启动尚无非空 snapshot 时可以显示初始化 `default` / 120 诊断值，但该值不是 handoff owner，也不证明 AppRequest 或 physical ownership 已建立。

Activity focus只在尚未收到window signal时作为fallback；已有window snapshot后，Activity变化只更新metadata，绝不重新分类或apply当前window。A window仍foreground时Activity A→B保持A profile；SystemUI/Permission/overlay window仍foreground时保持neutral120；只有新的window-focus edge才能改变physical raw。IME显示期间保留non-IME window snapshot；IME关闭恢复该snapshot，而不是无条件恢复Activity。

## Foreground-only 例子

### ZuiControl

1. App A raw foreground，profile=90：physical target=90。
2. raw focus 切到 ZuiControl：current/last 仍为 A；desired=`default`；physical target=120。
3. 在 ZuiControl 把 A 改为 144：只保存 A；当前 ZuiControl 仍 target=120。
4. raw focus 返回 A：读取最新 A profile 并 apply 144。

### QS / SystemUI

1. Launcher raw foreground，profile=90：physical target=90。
2. 展开 QS，raw focus=SystemUI：current/last 仍为 Launcher；physical target=120。
3. QS 点击 165：修改 Launcher profile，不创建 SystemUI profile；QS 仍 target=120。
4. 收起 QS，raw focus=Launcher：physical target=165。

## Profile 写入边界

- `setCurrentSceneProfile` 始终选择 last/current business 作为保存对象。
- 只有保存对象同时是真正 raw foreground business package/user 时才立即 apply。
- 显式编辑后台 package 只保存。
- 对已知 transient package（包括 exact `com.lenovo.screensplit` / `com.zui.freeform.sidebar`）的新增/更新请求返回 `transient_package_not_configurable`。
- 加载配置时忽略 legacy SystemUI/ZuiControl/IME/overlay profile；remove API 仍可删除遗留项。
- profile 文件中不得新产生 `com.android.systemui` 或 `com.zui.zuicontrol`。
- AtomicFile 写入失败必须回滚内存 profile并返回失败；当前 foreground apply 失败时明确返回 `profileSaved=1` 与 apply error。

## 去重边界

- App A=90 → transient default=120 → App A=90 是两个必要的 platform transitions。
- App A=120 → transient default=120 可 strict dedup，但 applied policy key 可重标为 `default`。
- 相同 raw focus 的重复 hook event 不重复写 DisplayManager 或 peak。
- transient A→B 若二者均 default 120，不重复 apply；raw diagnostics 仍更新。
- empty/null edge 不参与 120 dedup：它根本不产生 desired/target/apply，只等待下一非空 owner。
