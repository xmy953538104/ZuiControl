# ROM notification controller

ZuiControl deliberately targets SDK 30 while compiling with SDK 35. One production
package uses the platform legacy custom RemoteViews surface on the qualified ROM.
No Notification.Builder or SystemUI decoration exception is applied. The R7-only
transform is retired; the original classes.dex payload must remain exact.

One persistent foreground-service notification uses a single content RemoteViews,
the required small status icon, explicit immutable service PendingIntents and the
existing controller channel. No decorated style, BigContentView or secondary panel.
Notification existence does not enable sampling. Controls read editableScene and
persisted profiles; no optimistic state store. Uperf still uses authenticated
ZuiControlRequest/daemon persistence and existing system-server/backend application.
Settings publication immediately refreshes the controller independently of the
terminal-command waiter. Backend completion is separate from accepted UI state.

All creation and observer/lifecycle updates converge on
`ZuiControlQuickService.renderNotification(snapshot)`. Every call allocates fresh
`notification_quick_control` RemoteViews and fully applies every control through
`NotificationQuickControlHelper`; no remembered visual selection is authoritative.
The fixed384×88dp native card has a48dp Monitor control and two30dp rows separated
by12dp. Refresh uses blue selection; Uperf retains green, balanceblue, orange and
pink semantic colors. The notification labels fast as“极速”; its existing mode ID
and backend meaning remain unchanged. The yellow dot follows desired Monitor ON,
including while its window is temporarily hidden.

Opt-in diagnostics (`dumpsys activity service com.zui.zuicontrol/.ZuiControlQuickService
--trace-seconds=600`) retain at most sixteen action timelines and ninety-six overlay
events in memory for up to ten minutes. They never select product state or write a
database. T3 is NotificationManager notify return (logical state); actual SystemUI
render latency requires separate device evidence. No steady timing log/poller.

Target SDK/package/permission/boot/FGS/component/overlay and real visual behavior
must be qualified on the exact clean-ROM candidate, not by a cache/install workaround.
