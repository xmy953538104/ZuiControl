# Targeted Device Plan

本文件只供新候选通过人工刷机审核并取得明确授权后执行。`RunId 20260831170720` 已通过技术Gate与fixed-seven package Preflight；唯一允许交授权的包为 `D:\3.VScode\Mi\flash\ZuiControl_9008_V20_4_RUNTIME_20260831170720`。当前轮明确不刷机；这不等于Boot Hard Gate或device correction PASS。设备在实验后仍运行 fixed RunId `20260831134511`，disable properties=0、Launcher/default120、`system_server` PID2635。

## Boot hard gate

- 核对人工批准明确指向上述新RunId、版本化package与四个镜像SHA；只刷其fixed seven；禁止旧RunId、userdata/GPT/persist/patch XML。
- `sys.boot_completed=1` 后继续观察；system_server PID每10秒记录，至少2分钟稳定。
- Binder published、Launcher可用、SELinux enforcing、无VerifyError/FATAL/system_server restart/RescueParty escalation。
- FAIL即停止、单次取证并按已批准恢复流程处理；不连续reboot，不自动生成下一候选。

## Kill switch targeted gate

1. Reserved signed-App Binder TX10 disable/enable：响应前mask与ownership已转换，property持久化正确；unknown module/unauthorized caller拒绝。当前UI未接线，UI路径记为`NOT_EXECUTED`，可用精确App UID/certificate调用验证Manager transaction本身。
2. Raw ADB property单命令：init edge短进程启动并退出；stable disable/enable即时收敛。
3. 两个property分别测试；rapid toggle以最终truth验收。
4. disable：global priority-8 released、peak compare/restore、AppRequest handoff requested并追踪物理接管；enable按最新真实non-empty Window重建。
5. disabled reboot一次验证boot persisted truth；恢复0并再次验证正常boot。
6. Uperf/asoulOpt/command始终不受refresh disable影响；SELinux无新增AVC。
7. enabled/disabled idle验证无常驻notifier、无周期getprop、无worker tick增长。
8. 未授权标准sysprop poke不得改变property或refresh状态；bounded callback与rapid poke不得形成持续wake/storm。

## Null/OEM targeted gate

- Notes90 ↔ Calculator60，100轮、200真实business edge；统计null edge、default120 intermediate、apply delta和physical mode transition。
- 目标：normal handoff intermediate default120=0；apply约每真实owner一次。
- null→same owner、transition取消、SystemUI、ZuiControl、IME、Permission/Resolver分别回归。
- `com.lenovo.screensplit`与`com.zui.freeform.sidebar` focused时physical120且current/last/editable不被覆盖。
- freeform focused Window 60/120/90、split focused pane60/90、PiP non-focusable回归。

完整矩阵仍保留先前已PASS的五档、dedup、peak observer、Binder security；不得修改或扩展Uperf/asoulOpt production logic。
