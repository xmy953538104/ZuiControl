# V20.4 Refresh Runtime Correction — Final Decision

日期：2026-08-31

范围：只修正并验证 kill-switch event transport、null Window transition gap、Lenovo/ZUI control UI classification；本轮刷机和真机 Gate 没有修改生产代码，也没有修改 Uperf/asoulOpt production logic。

## 1. 最终结论

RunId `20260831170720` 已按人工授权使用唯一 fixed-seven package 刷入 TB321FU / ZUI 16.1.11.072。Boot Hard Gate 与 Targeted Device Gate 均通过；三个旧 fixed candidate runtime blocker 已在当前真机闭环。

```text
V20_4_REFRESH_RUNTIME_CORRECTION=PASS
V20_4_REFRESH_WORK_PACKAGE=CLOSED_WITH_EXPLICIT_BOUNDARIES
```

当前权威结果是本文件与 [`08_BOOT_GATE.md`](V20_4_REFRESH_RUNTIME_CORRECTION/08_BOOT_GATE.md) 至 [`13_FINAL_RUNTIME_STATE.md`](V20_4_REFRESH_RUNTIME_CORRECTION/13_FINAL_RUNTIME_STATE.md)。旧 RunId `20260831134511` 的 `PARTIAL` 和三项 FAIL 只保留为被替代 lineage，不得覆盖当前结果。

## 2. 候选与刷机锚点

```text
RunId=20260831170720
source_commit=146e096c6a6bc8b3fee60349b856990fd9fb68d2
ci_run_id=33375509612
super_sha256=dc4fd4bc3e288aa26e80cf382db62211f488e1b74c7cb8767b2d3f9f5f2c269d
boot_sha256=e7e85b5cd2806b8c27adf4925e05ee169072a79a43502effc34c97fb27ee8371
vbmeta_system_sha256=5c72c2d63deef95ddba41c825c271866a3041d48a6b5ca1cfd50bc4bc6cc2dda
vbmeta_sha256=c1f4ea68ea52bae62e464ddc245dadd740569ba3ac3376ee5d23a40204a744f7
services_jar_sha256=0b7bb46c644c5559173f72b06579131e82597366fdcc114d3fb30aabb544e8a3
fixed_seven_package=D:\3.VScode\Mi\flash\ZuiControl_9008_V20_4_RUNTIME_20260831170720
flashed=true
```

刷机前已通过 host `39/39`、V20.3B regression `5/5`、apktool/smali rebuild、final-super reverse/56-marker、final-artifact ART/dex2oat、split SELinux CIL、official host init exact-file 和 fixed-seven Preflight。实际刷机只写 super、vbmeta_system_a/b、boot_a/b、vbmeta_a/b，read-back verify 成功；没有 patch XML、GPT、userdata、persist、FRP 或 modemst。

## 3. Boot Hard Gate

`sys.boot_completed=1` 后继续观察 `187.743s`，19 个不超过 10.5 秒间隔的样本中 system_server PID/starttime 始终为 `2700/988`。Binder published、Launcher usable、SELinux Enforcing、boot animation exited；当前 boot 的 VerifyError、`FATAL EXCEPTION IN SYSTEM PROCESS`、system_server crash/restart 与 RescueParty escalation 均为0。详见 [`08_BOOT_GATE.md`](V20_4_REFRESH_RUNTIME_CORRECTION/08_BOOT_GATE.md)。

## 4. Kill switch

两个 raw property 都不需要人工 `service call` 或第二次 poke：

- `persist.zui_control.refresh.disable`：20次 disable + 20次 enable，20/20；disable host-observed convergence mean/P50/P95/max 为 `173.330/174.536/203.710/207.303ms`，enable为 `176.274/176.230/203.848/203.903ms`；
- `persist.zui_control.disable`：20+20，20/20；disable为 `170.993/169.268/194.488/195.524ms`，enable为 `173.237/177.715/196.964/199.814ms`；
- disable 不增加 refresh apply；Notes90 re-enable 每次恰 `+1` apply；Uperf、asoulOpt 与 command plane保持运行；
- rapid toggle 两个property共80个commanded edge，所有组最终100%收敛；refresh/global apply delta分别19/17，均不超过有效enable上限，未见重复实际apply storm。

这些约170ms数字是 host T0 到首个收敛 dumpsys 的ADB端到端时间，不是native callback或physical display latency。shared `setDisplayProperties()` 没有owner token；disabled只可证明本地 `appRequestOwned=false`、traversal handoff requested/pending以及外部DMS回到WM范围，不能虚构同步clear callback。详见 [`09_KILL_SWITCH_DEVICE.md`](V20_4_REFRESH_RUNTIME_CORRECTION/09_KILL_SWITCH_DEVICE.md)。

## 5. Event transport与进程生命周期

旧 fixed candidate 上的可逆 sysprop poke实验已确认根因：raw setprop只写property area，不会自动report system_server进程内callback；标准 `SYSPROPS_TRANSACTION` 会立即触发process-local report。correction因此保留两个入口：

- reserved signed-App TX10：严格package+certificate认证，在同一Binder调用内persist并直接transition；
- engineering raw property：init edge-only `exec_background` 通过shell entrypoint启动短命 `/system/bin/service call zui_control 1599295570`。

disable/enable边沿atrace各只出现一个init-origin child和一个 `0x5f535052` transaction。child创建到退出分别 `38.593ms` 与 `37.367ms`；transaction到system_server reply分别 `0.803ms` 与 `12.302ms`。trace证明transaction到达并唤醒ZuiControl worker，但没有Java callback entry marker，因此不伪造worker callback精确计数。稳定态 `367.132s` 观察中PID/apply/skip均不变，notifier/daemon sampled hit=0。

## 6. Boot persistence

refresh.disable=1 后只重启一次。最早有效 state（`23.077s`）已经是mask2、render/peak/AppRequest全false、apply0；boot完成后60秒仍为同一disabled state和system_server PID2750，没有“先获得90Hz ownership再释放”的可观察窗口。

恢复property=0后只再重启一次。最早有效 state mask0；Launcher/default120随后建立所需render/AppRequest ownership，system_server PID2714连续60秒稳定。全程没有多余reboot。disabled/enabled boot persistence均PASS。

## 7. Null Window与App handoff

Notes90↔Calculator60执行100个往返，即200个真实business owner edge：

```text
TOTAL_EDGES=200
REFRESH_APPLY_DELTA=200
EMPTY_FOCUS_TRANSITION_DELTA=200
OBSERVED_NULL_SAMPLES=1270
OBSERVED_INTERMEDIATE_DEFAULT_120=0
```

每条edge的desired/attempted/applied package与Hz均正确；ADB采样未见90→120→60或60→120→90。该harness不以physical mode作为完成条件，所以它证明state/apply event order与“observed 120=0”，不宣称200条edge每次都完成physical settle。独立五档smoke证明60/90/120/144/165均可达到真实Display.Mode。

有效 null→same-owner warm relaunch 10/10：empty delta +10、apply delta 0、intermediate120=0。rotation尝试没有产生null，记为N/A而不是失败。详见 [`10_NULL_WINDOW_100X.md`](V20_4_REFRESH_RUNTIME_CORRECTION/10_NULL_WINDOW_100X.md)。

## 8. Non-empty transient、OEM与多窗口

真实非空SystemUI、ZuiControl、Resolver、PermissionController、IME仍是foreground physical owner并使用default120；返回业务App恢复其profile。IME在target120时physical可因adaptive render降到60，不等于继承业务profile。

`com.lenovo.screensplit` 与 `com.zui.freeform.sidebar` focused时均为 `rawFocusTransient=true`、target120，同时current/last/editable保持进入前的Notes。sidebar用生产SystemUI同款package-scoped `SPLIT_FIRST_GUIDE` broadcast触发receiver/dialog，没有直启Activity、清数据或重启包；guide settings与status-bar disable精确恢复。profile文件没有两个OEM package。

freeform、split与PiP快速回归PASS；60/90/120/144/165 physical smoke PASS。详见 [`11_OEM_TRANSIENT_DEVICE.md`](V20_4_REFRESH_RUNTIME_CORRECTION/11_OEM_TRANSIENT_DEVICE.md)。

## 9. Idle、SELinux与最终恢复

- no-edge observer：`367.132s`，apply/skip不变、notifier/daemon hit0；
- final settle：`321.548s`；
- `/proc`：`60.6s`，ZuiControl worker self/children tick、voluntary/nonvoluntary context-switch delta全部0；persistent/request row0；
- Perfetto：`89.984732s`，ZuiControl worker `0` sched slices / `0.000000s` CPU；kill notifier/service、periodic Zui shell、Settings heartbeat、persistent/request rows均0；packet loss/drop0；
- trace仍有与旧baseline相同的 `ftrace_setup_errors=2`，因此短命进程零行只按trace可见边界陈述；Uperf wrapper约5秒自检grep仍存在，是明确保留的执行面，不是refresh polling；
- 当前boot相关AVC=0、VerifyError/system-process FATAL/RescueParty marker=0。

最终设备为Launcher/default120、两项disable=0、balance、Uperf/asoulOpt running、Binder found、SELinux Enforcing、boot_completed=1、system_server PID2714。profile精确恢复为64-byte baseline，SHA-256 `7410c52143590460fc0350992785a2ffe7d1b4f833c385a797a6a44087437221`，仅default记录；无OEM/test profile、notifier/daemon或pending handoff。详见 [`12_IDLE_REGRESSION.md`](V20_4_REFRESH_RUNTIME_CORRECTION/12_IDLE_REGRESSION.md) 与 [`13_FINAL_RUNTIME_STATE.md`](V20_4_REFRESH_RUNTIME_CORRECTION/13_FINAL_RUNTIME_STATE.md)。

## 10. TX10与显式边界

当前release UI没有TX10接线，也没有安全的现成signed-App Manager/API入口，因此没有临时增加测试App、代码或shell Binder调用：

```text
APP_UI_TX10=NOT_EXECUTED
SIGNED_APP_TX10_DEVICE_PATH_NOT_AVAILABLE
```

以下旧工作包边界未被改写成PASS，但经本次针对性Gate明确接受为非阻断范围边界：UDFPS=`NOT_OBSERVED`；fault injection=`NOT_EXECUTED`；secondary user/external display=`NOT_VALIDATED`；TX10 signed-App device path不可达。结论只覆盖当前TB321FU、default display、active user及已执行矩阵，不外推未验证硬件/用户场景。

## 11. 最终判定

```text
FLASHED=YES
BOOT_HARD_GATE=PASS
RAW_REFRESH_DISABLE_20X=PASS_NO_MANUAL_POKE
RAW_GLOBAL_DISABLE_20X=PASS_NO_MANUAL_POKE
RAPID_TOGGLE=PASS_FINAL_TRUTH_CONVERGED
DISABLED_BOOT_PERSISTENCE=PASS
ENABLED_BOOT_PERSISTENCE=PASS
NULL_WINDOW_100X=PASS_0_OBSERVED_INTERMEDIATE_120
OEM_TRANSIENT=PASS
MULTIWINDOW_REGRESSION=PASS
FIVE_MODE_SMOKE=PASS
SELINUX_BLOCKING_AVC=0
IDLE_REGRESSION=PASS
DEVICE_CORRECTION_VALIDATION=PASS
REFRESH_WORK_PACKAGE=CLOSE
```

最终证据包：`V20_4_REFRESH_RUNTIME_DEVICE_RESULTS.rar`。不开始其它V20.4工作包，不开始V21。
