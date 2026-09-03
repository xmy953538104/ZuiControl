ZuiControl production payload — V20.3B verified baseline / App V49 target

Build/package target:
- /system/priv-app/ZuiControlV49/ZuiControl.apk
- /system/bin/zui_controld
- /system/bin/uperf
- /system/bin/zui_uperf_service
- /system/bin/zui_uperf_supervisor
- /system/bin/AsoulOpt
- /system/etc/init/zui_controld.rc
- /system/etc/init/zui_scheduler.rc
- /system/etc/zui_control/uperf-sm8650.json
- /system/etc/zui_control/default_uperf_perapp.txt
- /system/etc/zui_control/default_asopt.conf

Framework integration:
- scripts/ApplyZuiControlPayload.py calls scripts/PatchZuiControlFramework.py.
- framework.jar receives android.zui.ZuiControlManager.
- services.jar receives the zui_control Binder service and DisplayContent focus hook.

Current runtime ownership:
- system_server / ZuiControlService is the sole refresh owner.
- Uperf scene and screen mode are decided event-by-event in system_server.
- Android init transports Uperf mode, owns scheduler lifecycle, and applies the vendor.perfservice fence only while scheduler_active=1.
- asoulOpt is the sole per-task affinity/context-scheduler owner; Uperf sched.enable=false.
- OEM GPU and thermal services remain active safety/clock authorities. This payload does not claim direct KGSL ownership or replace thermal policy.

Command plane:
- zui_controld.rc has no persistent zui_controld service or start.
- zui_control_request is disabled + oneshot.
- /system/bin/zui_controld accepts only --oneshot-request and exits after durable claim/action/receipt/ACK.
- zui_controld has no refresh writer, scene detector, periodic health publisher, or watchdog role.

Health:
- App/status health is read on demand through the zui_control Binder service.
- No persistent zui_controld Settings/Binder heartbeat exists.
- The V20.4 Uperf wrapper removes the 5-second process/grep loop and all logger FIFO handling, then execs the native supervisor. The supervisor checks the regular Uperf log every 100ms for at most 20 seconds, atomically publishes readiness, closes the log, and enters PR_SET_CHILD_SUBREAPER + blocking waitpid tree supervision. No shell or log polling remains in steady state. Init remains the sole restart owner and existing whole-service fail-safe semantics remain unchanged. Worker recovery/storm and steady-state behavior still require device validation.

Runtime data:
- /data/system/zui_control/profiles.prop
- /data/vendor/zui_control/uperf/uperf.json
- /data/vendor/zui_control/uperf/perapp_powermode.txt
- /data/vendor/zui_control/uperf/cur_powermode.txt
- /data/vendor/zui_control/uperf/effective_powermode.txt
- /data/vendor/zui_control/asoul/asopt.conf
- /data/vendor/asopt.conf -> /data/vendor/zui_control/asoul/asopt.conf
- /data/vendor/zui_control/log/

Known V20.4 refresh boundary:
- SystemUI, ZuiControl, permission UI, resolver/chooser, installer, input methods, and overlays are intended transient scenes; Launcher remains configurable.
- Current source still has a ZuiControl controlPanel special case that can apply its own/default profile. V20.4 Refresh Correctness / State Machine must fix and verify this with non-120 profiles.
- Kill-switch release of render vote, AppRequest, and peak bridge is not yet proven complete or immediate.
- target 120 remains adaptive-render behavior; physical actual may fall to 60 while static.
- 144/165 are displayHz targets only; generic UID fpsCap is not delivered.

No production path may restore persistent zui_controld, daemon refresh_tick/learn_refresh, Accessibility/App refresh ownership, Uperf Native Auto scene ownership, or a second per-task scheduler.
