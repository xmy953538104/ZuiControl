ZuiControl production payload — App V59 target

Build/package target:
- /system/priv-app/ZuiControlV59/ZuiControl.apk
- /system/bin/zui_controld
- /system/bin/uperf
- /system/bin/zui_uperf_service
- /system/bin/zui_uperf_supervisor
- /system/bin/ZUIopt
- /system/etc/zuiopt/factory_rules.conf
- /system/etc/init/zui_controld.rc
- /system/etc/init/zui_scheduler.rc
- /system/etc/zui_control/uperf-sm8650.json
- /system/etc/zui_control/default_uperf_perapp.txt

Framework integration:
- Terminal integration uses a source-bound rebuilt services extension manifest.
- Exact Golden framework.jar and unchanged services DEX members are preserved.
- services.jar classes4.dex is rebuilt from current framework_patch services sources.

Current runtime ownership:
- system_server / ZuiControlService is the sole refresh owner.
- Uperf scene and screen mode are decided event-by-event in system_server.
- Android init transports Uperf mode, owns scheduler lifecycle, and applies the vendor.perfservice fence only while scheduler_active=1.
- ZUIopt is the sole per-task owner, automatically started unless persistent failure exists. Uperf sched.enable=false.
- Fail-safe returns to ANDROID_DEFAULT_FAILSAFE; there is no alternate optimizer. Authenticated App reset retries only after reboot.
- Init creates /dev/cpuset/ZUIopt 0755 root root at post-fs-data, before owner selection/start. ZUIopt requires this scaffold, recovers journaled tasks, manages mask children and keeps the root on cleanup. ZUIopt has sys_nice, not dac_override.
- OEM GPU and thermal services remain active safety/clock authorities. This payload does not claim direct KGSL ownership or replace thermal policy.

Command plane:
- zui_controld.rc has no persistent zui_controld service or start.
- zui_control_request is disabled + oneshot.
- /system/bin/zui_controld accepts only --oneshot-request and exits after durable claim/action/receipt/ACK.
- zui_controld has no refresh writer, scene detector, periodic health publisher, or watchdog role.

Health:
- App/status health is read on demand through the zui_control Binder service.
- No persistent zui_controld Settings/Binder heartbeat exists.
- The Uperf wrapper has no 5-second process/grep loop or logger FIFO handling; it execs the native supervisor. The supervisor checks the regular Uperf log every 100ms for at most 20 seconds, atomically publishes readiness, closes the log, and enters PR_SET_CHILD_SUBREAPER + blocking waitpid tree supervision. No shell or log polling remains in steady state. Init remains the sole restart owner and existing whole-service fail-safe semantics remain unchanged.

Runtime data:
- /data/system/zui_control/profiles.prop
- /data/vendor/zui_control/uperf/uperf.json
- /data/vendor/zui_control/uperf/perapp_powermode.txt
- /data/vendor/zui_control/uperf/cur_powermode.txt
- /data/vendor/zui_control/uperf/effective_powermode.txt
- /data/vendor/zui_control/log/
- /data/vendor/zui_control/zuiopt/ (private rules, owner_state.v1 recovery journal, failure.v1, crashes.v1)
- startup.v1 and fatal.v1 are atomic 0600 startup/fatal receipts, each below 1KB. They record boot/stage and allowlisted reason/status only, never task/package identities. No steady-state receipt writes; write failure never blocks owner release/fail-safe.

Current refresh contract and boundaries:
- Physical refresh is foreground-only. SystemUI, ZuiControl, permission UI, resolver/chooser, installer, input methods, and overlays use default120 when they own a real non-empty focused Window; Launcher remains configurable.
- lastNonTransientScenePackage is a configuration target only. Transient UI never inherits the previous business App's physical Hz.
- Empty focused-Window transitions retain the last proven non-empty policy until the next non-empty edge; they do not become a default120 owner.
- Kill-switch raw-property transport and state convergence passed device validation. Shared AppRequest has no owner token or synchronous clear, so release is reported as requested/pending rather than synchronous physical success.
- target 120 remains adaptive-render behavior; physical actual may fall to 60 while static.
- 144/165 are displayHz targets only; generic UID fpsCap is not delivered.

No production path may restore persistent zui_controld, daemon refresh_tick/learn_refresh, Accessibility/App refresh ownership, Uperf Native Auto scene ownership, or a second per-task scheduler.

Device upgrade preparation is a separate host-only utility, never a ROM boot action.
