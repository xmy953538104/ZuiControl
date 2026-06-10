ZuiperfCtl v14 payload

System components:
- /system/priv-app/ZuiperfCtl/ZuiperfCtl.apk
- /system/bin/zui_perfctld
- /system/bin/AsoulOpt
- /system/etc/init/zui_perfctld.rc
- /system/etc/init/zui_asoulopt.rc

Runtime data:
- /data/local/tmp/zui_perfctl/refresh/rules.prop
- /data/local/tmp/zui_perfctl/performance/profiles.prop
- /data/local/tmp/zui_perfctl/zuipp/game_policy.xml
- /data/local/tmp/zui_perfctl/zuipp/performanceconfig.xml
- /data/local/tmp/zui_perfctl/log/perfctld.log
- /data/local/tmp/zui_perfctl/log/asoulopt.log

App request channel:
- Settings.System key: zui_perfctl_request_text
- Atomic format:
  id|cmd|rate|package|mode|little_max_khz|little_min_khz|big_max_khz|big_min_khz|titan_max_khz|titan_min_khz|mega_max_khz|mega_min_khz|gpu_max_khz|gpu_min_khz

User-facing commands:
- status
- learn_refresh
- restore_refresh
- remove_refresh_rule
- set_performance_profile
- remove_performance_profile
- apply_performance
- restore_zuipp
- export_logs

Maintenance commands kept for ADB diagnostics:
- apply_zuipp
- restart_zuipp
- apply_asoul
- restore_asoul
- restart_asoul

Behavior:
- Refresh-rate baseline is a hard 120Hz lock.
- Foreground polling always resolves the active scene package, then applies that
  package's learned rule or the 120Hz baseline.
- Notification clicks optimistically lock peak/min/active refresh values from
  the app process before the daemon records the scene rule, avoiding a visible
  fallback to 120Hz while the daemon is still polling.
- The daemon re-checks peak_refresh_rate and min_refresh_rate even when the
  foreground scene has not changed, so ZUI drift is locked back to the learned
  scene value.
- SystemUI and ZuiperfCtl are treated as transient overlays. Notification clicks
  learn the most recent real foreground scene, so pulling the shade over a game
  records the game, not SystemUI or ZuiperfCtl.
- Choosing 120Hz removes the current scene's exception rule.
- If no scene target can be resolved, the click is ignored instead of creating a
  global manual override.
- Performance profiles are converted into ZuiPP XML by XmlProfileGenerator.
- CPU/GPU level IDs use 1-based lowIndex*100+highIndex inside each hardware type.
- Saving or deleting a performance profile regenerates the working XML files.
- Applying performance regenerates both XML files, bind mounts them through init,
  and restarts ZuiPP/game helper packages.
- If ZuiPP does not enforce a foreground profile's GPU range, the daemon also
  applies the same range to KGSL pwrlevel nodes and publishes the live GPU state.
- Leaving a profiled app restores both KGSL pwrlevel and devfreq min/max limits.
- AsoulOpt is not configured per app. The UI only reports service health and
  exports its log.
