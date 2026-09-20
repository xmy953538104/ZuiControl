# ROM notification controller

ZuiControl retains target SDK 35. The TB321FU notification template reserves an
application icon tile even for direct/custom RemoteViews. The ROM applies one
Owner-authorized exception in Notification.Builder.fullyCustomViewRequiresDecoration:
package `com.zui.zuicontrol`, FLAG_SYSTEM and channel `zui_control_monitor_v1`
must all match. Other packages/channels retain the original SDK check.

`scripts/build/NotificationProofTransforms.py` accepts only the qualified original
Builder class; `BuildNotificationFramework.py` reassembles classes.dex and compares
every class and every other Builder method after decoding again. The transformation
does not change SystemUI, target SDK, package identity, permission checks, service
lifecycle or the required status-bar small icon. RemoteViews content is still a
single compact controller; notification presence does not enable the Monitor.

The terminal manifest binds the exact original/patched DEX, transformation source
and qualification report. Final-super and firstboot verification must retain that
binding. Source tests and the exact guard VM matrix do not replace device rendering
and click/synchronization acceptance.
