ZuiControl v19 payload

System components:
- /system/priv-app/ZuiControlV43/ZuiControl.apk
- /system/bin/zui_controld
- /system/bin/AppOpt
- /system/etc/init/zui_controld.rc
- /system/etc/init/zui_appopt.rc
- /system/etc/zui_control/AppOpt.json
- /AppOpt.json -> /system/etc/zui_control/AppOpt.json

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
- /data/vendor/zui_control/appopt/applist.conf
- /storage/emulated/0/Download/ZuiControl/AppOpt.conf (editable import/export copy)
- /data/vendor/zui_control/log/controld.log
- /data/vendor/zui_control/log/appopt.log

Behavior:
- Refresh owner is system_server through the zui_control Binder service.
- App package is com.zui.zuicontrol and the UI name is ZuiControl.
- App has no accessibility service and does not write peak/min refresh settings.
- Notification buttons call android.zui.ZuiControlManager to update the current
  last non-transient scene profile.
- Daemon refresh commands are compatibility no-ops when owner=system.
- Daemon versions the payload XML templates as the baked baseline, regenerates
  active XML after a payload baseline upgrade, generates ZuiPP
  staging XML from App profiles, promotes validated staging to active, remounts
  active on /system/etc, and rolls back through last_good or baked_baseline.
- Payload default XML files are templates/fallbacks, not automatically labeled as
  official originals. An official-original restore is available only if such a
  pair is explicitly saved under the runtime official_original directory.
- Each game has one current performance profile. Saving a user game also adds it
  to ZUI Game Assistant's custom list if needed; deleting that ZuiControl
  profile removes the user custom-game entry in the same recoverable transaction.
  Game Assistant-side edits never create or delete ZuiControl profiles. Its LimitConfig is mirrored to
  all three OEM mode slots, and one CPU level ID is shared by the four CPU Type
  maps for each thermal stage. Daemon-side GameModeProvider forcing is removed;
  the next game start uses the OEM chain and mounted XML. A running target is
  force-stopped after a successful save. CPU/GPU values remain OEM performance
  requests, not hard caps.
- Daemon keeps XML generation/bind mount, controlled ZuiPP reload after active
  XML hash verification, SafeCenter one-shot keepalive, AppOpt preparation, and
  log export. Cloud blocking is removed and /system/etc/hosts matches the
  official ZUI 16.1.11.187 default. The daemon does not write KGSL or cpufreq
  nodes in production.
- AppOpt stays in the OEM performanced domain. Added SELinux access is limited
  to config file watching, process getsched/signull probes, dac_override/kill,
  and a directory-getattr dontaudit; it adds no setsched or stronger appdomain
  signal permission.
- The shipped AppOpt default contains only commented fallback/thread examples
  and enables no rule. The App APK carries opt-in Snapdragon 8 Gen 3 templates;
  only games explicitly selected by the user are written to the active config.
- AppOpt keeps its validated canonical config under /data/vendor. The App UI can
  edit the complete list in-app or export and strictly validate/import an editable
  Download/ZuiControl/AppOpt.conf copy; either batch path restarts AppOpt once and
  stops affected running apps.
- SystemUI, ZuiControl, permission UI, resolver/chooser, installer, input method,
  and overlays are transient scenes. Launcher is a valid configurable scene.
- 144/165 are displayHz lock targets only in v19; generic UID FPS cap is a later
  phase after SurfaceFlinger/GameManager verification.
