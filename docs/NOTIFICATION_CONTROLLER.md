# R12 notification scalar capsules

The 384×88dp card has 16dp padding, a 56dp permanent blue ECG button, a 12dp gap,
52dp temperature/power capsules, a 10dp gap and 222dp control tracks. Rows are 26dp
high with a 4dp gap. Notification-only colors and resources follow the final SVG;
shared application mode tokens and all existing action IDs remain unchanged.

The sole PerformanceMonitor callback forwards the existing quiet-therm and
LivePower values. It never registers another collector or reads another sensor.
Scalar notification updates coalesce to 2 seconds; invalidation is immediate and
a one-shot 3.5-second freshness expiry prevents stale values. OFF/inactive/invalid
values show --°C / -- W. Valid units are blue, missing values entirely muted gray.
No database writes or thread scans are added. Full RemoteViews state is reapplied.

The known R11 desired-ON process-reconnect failure is inherited, not repaired by
this UI delta. Earlier design text below is historical, not a new recovery claim.

## R11 notification and scene delta

Single stateless384x88dp target30 notification. Padding14/14/11/11;44dp Monitor,22dp white ECG,6dp yellow dot/margin3; Monitor remains blue both ON/OFF, dot alone changes. Rightgroup gap14; rows28 with10gap; refresh5gap and Uperf8gap;12.5sp bold/no font padding. Rightedges align.

Own Activity and actual HOME are real editable scenes; SystemUI/shade/IME remain transient. Existing global Uperf inheritance and per-app quick overrides share the existing store. No second state authority or sampling clock. Monitor desired mode survives client death; no sampling without client; reconnect resumes desiredON, desiredOFF staysOFF. Crash-interrupted recording is retained incomplete, not automatically resumed.

Long-bar top uses max(originalTop, visible absolute StatusBar bottom+1dp); no caption offset or fixed70/90px. Native landscape/portrait input qualification remains required.

## Earlier design context

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
