# OEM Transient Registry

本registry只收录当前TB321FU/ZUI真机有直接证据的 configuration-transient control UI。实现使用两个exact equality，不使用 `com.zui.*`、system-app标志或UID范围。

| package | 分类 | 证据 | 正确行为 |
|---|---|---|---|
| `com.lenovo.screensplit` | split selector/control UI | [`split_select_ui.xml`](raw/prior_device/m13_split/split_select_ui.xml) 显示“请选择分屏应用”；[`split_select_activity.txt`](raw/prior_device/m13_split/split_select_activity.txt) 显示 `/system/priv-app/ZuiScreenSplit`、UID1000及真实top/focus；[`split_selector_zui.txt`](raw/prior_device/m13_split/split_selector_zui.txt) 记录旧实现污染current/last/editable | focused时physical=default120；不得更新current/last/editable；不得创建profile |
| `com.zui.freeform.sidebar` | focusable freeform/sidebar control overlay | [`split_active_window.txt`](raw/prior_device/m13_split/split_active_window.txt) 显示sidebar为`mCurrentFocus`，背后`mFocusedApp=Notes`，UID1000、SYSTEM_ALERT且focusable；[`split_active_zui.txt`](raw/prior_device/m13_split/split_active_zui.txt) 记录旧实现污染business | focused时physical=default120；保留上一业务编辑对象；不得创建profile |

## 负向边界

本次m12/m13盘点没有第三个可证明的OEM control package。特别是：

- Recents真实owner为 `com.zui.launcher`，Launcher是可配置业务场景，不能加入registry；证据见 [`recents_window.txt`](raw/prior_device/m13_split/recents_window.txt) 与 [`recents_zui.txt`](raw/prior_device/m13_split/recents_zui.txt)。
- `com.zui.notes`、Calculator、Settings和其它预装/system app仍可能是业务App。
- 不根据 `com.zui.*`、shared UID1000、`FLAG_SYSTEM`或安装目录泛化。

现有freeform/split focused-pane PASS不被推翻：[`m12 focus_summary`](raw/prior_device/m12_freeform/focus_summary.csv)、[`m13 focus_summary`](raw/prior_device/m13_split/focus_summary.csv)。
