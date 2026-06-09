ZuiperfCtl v7 payload

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
  id|cmd|rate|package|mode|cpu_max_khz|cpu_min_khz|gpu_max_khz|gpu_min_khz

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
- A notification rate click learns the current foreground package.
- Choosing 120Hz removes that package's exception rule.
- Performance profiles are converted into ZuiPP XML by XmlProfileGenerator.
- Applying performance regenerates both XML files, bind mounts them through init,
  and restarts ZuiPP/game helper packages.
- AsoulOpt is not configured per app. The UI only reports service health and
  exports its log.
