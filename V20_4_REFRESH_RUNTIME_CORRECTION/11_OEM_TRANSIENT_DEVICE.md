# OEM / Non-empty Transient Device Gate

## 结论

```text
NONEMPTY_TRANSIENT_REGRESSION=PASS
LENOVO_SCREEN_SPLIT_TRANSIENT=PASS
ZUI_FREEFORM_SIDEBAR_TRANSIENT=PASS
MULTI_WINDOW_SMOKE=PASS
FIVE_MODE_SMOKE=PASS
```

空 Window 修复没有改变产品的 foreground-only 语义：null 不是 owner，但真实非空 transient Window 仍是 neutral/default120 owner；`currentScenePackage`、`lastNonTransientScenePackage`、`editableScenePackage` 保留进入控制 UI 前的真实业务 App。

## 通用 transient 回归

| 流程 | transient raw package | transient target/applied | business 对象 | 返回 |
|---|---|---|---|---|
| Notes90 → SystemUI | `com.android.systemui` | 120/120，physical120 | Notes | Notes90 |
| Notes90 → ZuiControl | `com.zui.zuicontrol` | 120/120，physical120 | Notes | Notes90 |
| Notes90 → Resolver | `com.zui.resolver` | 120/120，physical120 | Notes | Notes90 |
| Notes90 → Permission UI | `com.android.permissioncontroller` | 120/120，physical120 | Notes | Notes90 |
| Settings90 → Sogou IME | `com.sohu.inputmethod.sogou.oem` | 120/120 | Settings | hidden 后 Settings90 |

IME 样本中面板实际可自适应到60；当前 `displayVote=adaptiveRender` 下这不否定 target/applied120，不能把 default120 误写成 hard-lock。

证据：[`12_systemui_open_120_zui.txt`](raw/device_run_20260831170720/12_systemui_open_120_zui.txt)、[`13_zuicontrol_open_120_zui.txt`](raw/device_run_20260831170720/13_zuicontrol_open_120_zui.txt)、[`14_resolver_open_120_zui.txt`](raw/device_run_20260831170720/14_resolver_open_120_zui.txt)、[`15_permission_open_120_zui.txt`](raw/device_run_20260831170720/15_permission_open_120_zui.txt)、[`16_ime_open_120_zui.txt`](raw/device_run_20260831170720/16_ime_open_120_zui.txt)、[`16_ime_hidden_settings90_zui.txt`](raw/device_run_20260831170720/16_ime_hidden_settings90_zui.txt)。

## Lenovo screensplit

通过 Recents 的真实分屏入口进入 selector，focus 命中 `com.lenovo.screensplit`：

- `rawFocusTransient=true`；
- target/applied/physical=`120/120/120`；
- current/last/editable 均为进入前的 `com.zui.notes`；
- 未创建 screensplit profile。

证据：[`17_screensplit_open_ui.xml`](raw/device_run_20260831170720/17_screensplit_open_ui.xml)、[`17_screensplit_focused_window.txt`](raw/device_run_20260831170720/17_screensplit_focused_window.txt)、[`17_screensplit_focused_zui.txt`](raw/device_run_20260831170720/17_screensplit_focused_zui.txt)、[`26_profiles_after_oem_multiwindow.txt`](raw/device_run_20260831170720/26_profiles_after_oem_multiwindow.txt)。

## Zui freeform sidebar

sidebar 的一次性引导已经在设备上完成，普通 split 流程不再自然弹出。为复现同一个生产入口，本轮使用 production SystemUI `StageTaskListener` 实际发送的同款 package-scoped broadcast：

```text
am broadcast --user current \
  -a com.zui.freeform.sidebar.SPLIT_FIRST_GUIDE \
  -p com.zui.freeform.sidebar
```

这是对 production receiver 的等价触发，不是 direct Activity start；没有清 App data，也没有新增测试代码。窗口命中 `com.zui.freeform.sidebar` 后：

- `rawFocusTransient=true`；
- activity focus 仍为 Notes，raw focused Window 为 sidebar；
- current/last/editable 均保持 `com.zui.notes`；当时 Notes profile=165，`editableDisplayHz=165`；
- desired/applied/physical=`default/120/120`；
- 未创建 sidebar profile。

引导相关 secure/system settings 在前、中、后快照一致；语义按钮关闭后现场恢复。早期探索临时改过的 `ov_first_split_guide_finish` 也已从0精确恢复原值1。

证据：[`30_sidebar_broadcast_and_zui.txt`](raw/device_run_20260831170720/30_sidebar_broadcast_and_zui.txt)、[`30_sidebar_window.txt`](raw/device_run_20260831170720/30_sidebar_window.txt)、[`30_sidebar_ui.xml`](raw/device_run_20260831170720/30_sidebar_ui.xml)、[`30_sidebar.png`](raw/device_run_20260831170720/30_sidebar.png)、[`30_sidebar_snapshot_before.txt`](raw/device_run_20260831170720/30_sidebar_snapshot_before.txt)、[`30_sidebar_visible_settings.txt`](raw/device_run_20260831170720/30_sidebar_visible_settings.txt)、[`30_sidebar_snapshot_after.txt`](raw/device_run_20260831170720/30_sidebar_snapshot_after.txt)、[`20_sidebar_guide_setting_restore.txt`](raw/device_run_20260831170720/20_sidebar_guide_setting_restore.txt)。

## Multi-window 与五档 smoke

- split：Calculator pane 为60/physical60，Notes pane为90/physical90；focused pane 决定 policy。
- freeform：Calculator为60/physical60，ZuiControl真实非空 transient 为target/applied120，Notes为90/physical90。ZuiControl静态画面 physical60属于 adaptive render，不是业务60 profile继承。
- PiP：Youku处于 pinned mode，non-focusable PiP 不抢 owner；Notes保持90/physical90。
- 五档：Calculator60、Notes90、SystemUI/screensplit120均命中真实物理档；Notes144与Notes165各连续10/10样本命中 target/applied/physical 对应档位。

证据：[`23_split_focus_calculator_zui.txt`](raw/device_run_20260831170720/23_split_focus_calculator_zui.txt)、[`23_split_focus_notes_zui.txt`](raw/device_run_20260831170720/23_split_focus_notes_zui.txt)、[`24_freeform_calculator_zui.txt`](raw/device_run_20260831170720/24_freeform_calculator_zui.txt)、[`24_freeform_zuicontrol_zui.txt`](raw/device_run_20260831170720/24_freeform_zuicontrol_zui.txt)、[`24_freeform_notes_zui.txt`](raw/device_run_20260831170720/24_freeform_notes_zui.txt)、[`25_pip_over_notes_activity.txt`](raw/device_run_20260831170720/25_pip_over_notes_activity.txt)、[`25_pip_over_notes_zui.txt`](raw/device_run_20260831170720/25_pip_over_notes_zui.txt)、[`27_notes144_physical_samples.txt`](raw/device_run_20260831170720/27_notes144_physical_samples.txt)、[`28_notes165_physical_samples.txt`](raw/device_run_20260831170720/28_notes165_physical_samples.txt)。

## SELinux

设备全程 Enforcing。覆盖 init property trigger、shell entry、`service` sysprops transaction及 `zui_control` 状态切换后的当前收集窗口中，AVC 总数0、候选相关AVC 0；没有 permissive、临时 allow 或 supolicy。

证据：[`33_selinux_avc_summary.txt`](raw/device_run_20260831170720/33_selinux_avc_summary.txt)、[`33_selinux_dmesg_full.txt`](raw/device_run_20260831170720/33_selinux_dmesg_full.txt)、[`33_selinux_logcat_all.txt`](raw/device_run_20260831170720/33_selinux_logcat_all.txt)。

本文件只对上述真实复现路径与 exact package registry 下结论，不把 `com.zui.*`、system UID 或 system-app 身份泛化为 transient。
