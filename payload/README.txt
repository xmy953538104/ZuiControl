ZuiControl v19 payload

System components:
- /system/priv-app/ZuiControlV46/ZuiControl.apk
- /system/bin/zui_controld
- /system/bin/uperf
- /system/bin/zui_uperf_service
- /system/bin/AsoulOpt
- /system/etc/init/zui_controld.rc
- /system/etc/init/zui_scheduler.rc
- /system/etc/zui_control/uperf-sm8650.json
- /system/etc/zui_control/default_uperf_perapp.txt
- /system/etc/zui_control/default_asopt.conf

Framework patch:
- scripts/ApplyZuiControlPayload.py calls scripts/PatchZuiControlFramework.py.
- framework.jar gets android.zui.ZuiControlManager.
- services.jar gets the zui_control Binder service and DisplayContent focus hook.

Runtime data:
- /data/system/zui_control/profiles.prop
- /data/vendor/zui_control/uperf/uperf.json
- /data/vendor/zui_control/uperf/perapp_powermode.txt
- /data/vendor/zui_control/uperf/cur_powermode.txt
- /data/vendor/zui_control/asoul/asopt.conf
- /data/vendor/zui_control/log/

Behavior:
- system_server remains the sole refresh-rate owner; P1 behavior is unchanged.
- Uperf owns CPU scheduling and bounded GPU policy while its supervised service runs.
- Qualcomm perfservice is stopped only while Uperf owns scheduling and is restored by the scheduler kill switch.
- KGSL DVFS and OEM thermal services remain active; this payload does not replace thermal configuration.
- Shiroko A-SOUL runs as the thread-placement companion with mode=0 and rt=0. Here rt=0 selects WALT per-task boost and does not assign real-time policy.
- AppOpt and the P2 XML scheduler owner are retired and cannot run in parallel with Uperf/A-SOUL.
- No production path directly writes CPU/GPU sysfs from zui_controld, calls provider_direct/GameModeProvider, or restores cloud blocking.
- SystemUI, ZuiControl, permission UI, resolver/chooser, installer, input methods, and overlays are transient refresh-rate scenes. Launcher remains configurable.
- 144/165 are displayHz lock targets only; generic UID FPS cap is not delivered.
