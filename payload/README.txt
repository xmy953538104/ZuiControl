ZuiperfCtl v2 payload

Goal:
- Keep official ZuiPP and ZuiGameHelper installed.
- Add our own privileged app: com.zui.perfctl.
- Add root init daemon: /system/bin/zui_perfctld.
- Add embedded AsoulOpt service from the proven 187 payload.
- Runtime data lives under /data/local/tmp/zui_perfctl for the current shell-domain prototype.

Data layout after boot:
- /data/local/tmp/zui_perfctl/zuipp/game_policy.xml
- /data/local/tmp/zui_perfctl/zuipp/performanceconfig.xml
- /data/local/tmp/zui_perfctl/asoul/asopt.conf
- /data/local/tmp/zui_perfctl/refresh/rules.prop
- /data/local/tmp/zui_perfctl/perfctl/request.prop
- /data/local/tmp/zui_perfctl/perfctl/status.prop
- /data/local/tmp/zui_perfctl/log/perfctld.log

Request format:
  id=optional-number
  cmd=apply_zuipp
  package=optional.package.name
  rate=60|90|120|144

Supported commands:
- apply_zuipp
- restore_zuipp
- restart_zuipp
- apply_asoul
- restore_asoul
- restart_asoul
- set_refresh with rate=60, 90, 120, or 144
- restore_refresh
- set_refresh_rule with package=package.name and rate=60, 90, 120, or 144
- remove_refresh_rule with package=package.name
- enable_auto_refresh
- disable_auto_refresh
- status

Important:
- XML changes are applied through bind mount. ZuiPP/GameHelper still need restart to reload cached XML.
- Automatic refresh switching is disabled by default. Add rules to /data/zui_perfctl/refresh/rules.prop and enable it explicitly.
- The APK is built from the root app module and should be copied to:
  system/priv-app/ZuiperfCtl/ZuiperfCtl.apk
