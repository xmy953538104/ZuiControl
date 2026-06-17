ZuiControl v19 payload

System components:
- /system/priv-app/ZuiControl/ZuiControl.apk
- /system/bin/zui_controld
- /system/bin/AsoulOpt
- /system/etc/init/zui_controld.rc
- /system/etc/init/zui_asoulopt.rc

Framework patch:
- scripts/ApplyZuiControlPayload.py calls scripts/PatchZuiControlFramework.py.
- framework.jar gets android.zui.ZuiControlManager.
- services.jar gets zui_control Binder service and DisplayContent focus hook.

Runtime data:
- /data/system/zui_control/profiles.prop
- /data/vendor/zui_control/performance/profiles.prop
- /data/vendor/zui_control/zuipp/baked_baseline/game_policy.xml
- /data/vendor/zui_control/zuipp/baked_baseline/performanceconfig.xml
- /data/vendor/zui_control/zuipp/active/game_policy.xml
- /data/vendor/zui_control/zuipp/active/performanceconfig.xml
- /data/vendor/zui_control/zuipp/staging/
- /data/vendor/zui_control/zuipp/last_good/
- /data/vendor/zui_control/zuipp/state/
- /data/vendor/zui_control/log/controld.log
- /data/vendor/zui_control/log/asoulopt.log

Behavior:
- Refresh owner is system_server through the zui_control Binder service.
- App package is com.zui.zuicontrol and the UI name is ZuiControl.
- App has no accessibility service and does not write peak/min refresh settings.
- Notification buttons call android.zui.ZuiControlManager to update the current
  last non-transient scene profile.
- Daemon refresh commands are compatibility no-ops when owner=system.
- Daemon treats current /system/etc XML as the baked baseline, generates ZuiPP
  staging XML from App profiles, promotes validated staging to active, remounts
  active on /system/etc, and rolls back through last_good or baked_baseline.
- Payload default XML files are templates/fallbacks, not automatically labeled as
  official originals. An official-original restore is available only if such a
  pair is explicitly saved under the runtime official_original directory.
- Daemon keeps XML generation/bind mount, GPU node fallback, SafeCenter one-shot
  keepalive, asoulOpt preparation, and log export.
- SystemUI, ZuiControl, permission UI, resolver/chooser, installer, input method,
  and overlays are transient scenes. Launcher is a valid configurable scene.
- 144/165 are displayHz lock targets only in v19; generic UID FPS cap is a later
  phase after SurfaceFlinger/GameManager verification.
