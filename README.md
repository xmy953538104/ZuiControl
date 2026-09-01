# ZuiControl

ZuiControl 是 TB321FU / ZUI 16.1.11.072 的 ROM 内置系统控制工具，核心能力是刷新率、Uperf CPU/power-model 调度和 asoulOpt per-task 线程放置。App 只提供 UI、QS、通知快捷控制和配置入口，核心调度不依赖 App 长期存活。

## 当前入口

新会话按以下顺序读取：

1. [`AGENTS.md`](AGENTS.md)
2. [`CURRENT_PROJECT_STATE.md`](CURRENT_PROJECT_STATE.md)
3. 本文件
4. 当前生产源码
5. [`CURRENT_EVIDENCE_INDEX.md`](CURRENT_EVIDENCE_INDEX.md)

V20.3B 阶段已经关闭，persistent daemon retirement architecture = PASS。健康的最近完整生产基线是 V20.4 Runtime Correction RunId `20260831170720`，其Refresh host/build/final-artifact/Boot Hard Gate和Targeted Device Gate均PASS，工作包为 **CLOSED WITH EXPLICIT BOUNDARIES**。当前设备实际运行Uperf失败候选RunId `20260901174600`：Android与system_server正常，但Uperf快速崩溃3次后`fail-safe=1/stopped`；它不是健康基线。App 仍是 versionCode 49 / versionName 0.21.12。

第二工作包 **V20.4 Uperf Architecture & Upstream Rebase** 使用framework top-resumed Activity作为Uperf exact-rule authority，并以event-driven FIFO/init生命周期替代旧5秒wrapper polling。定向修正RunId `20260901174600`的旧`proc_uptime`和`scheduler_active` denial已消失，但新`performanced → surfaceflinger_exec:file read` blocking AVC使Startup Runtime Gate **FAIL**；10分钟稳定观察和完整矩阵未执行。当前结论见 [`V20_4_UPERF_STARTUP_RUNTIME_DECISION.md`](V20_4_UPERF_STARTUP_RUNTIME_DECISION.md)。

旧 RunId `20260831094239` 刷前拒绝；`20260831104317` 因ART `VerifyError` Boot FAIL并完成恢复；`20260831134511` Boot PASS但device因kill switch、null-gap和OEM分类三项为PARTIAL；`20260901120647` framework boot正常但Uperf startup Gate FAIL。它们只保留为lineage，均不得再次刷写。Refresh权威结论见 [`V20_4_REFRESH_RUNTIME_DECISION.md`](V20_4_REFRESH_RUNTIME_DECISION.md) 与 [`08_BOOT_GATE.md`](V20_4_REFRESH_RUNTIME_CORRECTION/08_BOOT_GATE.md) 至 [`13_FINAL_RUNTIME_STATE.md`](V20_4_REFRESH_RUNTIME_CORRECTION/13_FINAL_RUNTIME_STATE.md)。

旧 handoff、AI 报告、阶段报告和 raw/trace/log 位于仓库外 `D:\3.VScode\Mi\ZuiControl_Archive\`。默认不要扫描 archive；只按 evidence index 定向读取。

## 当前架构

| 能力 | 当前 owner / 路径 |
| --- | --- |
| Refresh scene 与执行 | `system_server` / `ZuiControlService` → DisplayManagerInternal / ROM display policy |
| Uperf scene/screen mode | `system_server` top-resumed Activity/screen event → protected property → init → `effective_powermode.txt` → Uperf |
| CPU/power-model 执行 | `/system/bin/uperf`，四档 `powersave|balance|performance|fast` |
| per-task affinity/context scheduling | asoulOpt；Uperf `sched.enable=false` |
| OEM `vendor.perfservice` fence | Android init，`scheduler_active=1` gate |
| Command | authenticated Binder kick → disabled+oneshot `zui_control_request` → `zui_controld --oneshot-request` → durable receipt/ACK → exit |
| Health/status | 按需 Binder `getState()`；没有 persistent zui_controld heartbeat |

`persistent zui_controld` 已退休：生产 init 中没有 `zui_controld` service/start。`/system/bin/zui_controld` 只保留为 `--oneshot-request` command executor，没有 refresh、scene detector 或后台 health publisher 职责。

当前Uperf架构的 `/system/bin/zui_uperf_service` 不再做5秒process/grep轮询：startup只有一次bounded FIFO event wait，steady state阻塞等待Uperf自身事件/EOF。RunId `20260901174600`已证明旧`proc_uptime`和`scheduler_active` denial消失，但暴露新`surfaceflinger_exec:file read` access-graph缺口，尚未到达可证明的steady state；不得宣称零polling真机PASS。

OEM GPU/thermal 继续保留安全和频率裁决；当前不宣称接管 Adreno KGSL。Uperf 的全局 cpuset 写入与更广义 knob ownership 留后续独立审计。

## 当前 Refresh Correctness 工作包

第一优先级已经按 foreground-only state machine 实现：业务 App 真正 foreground 时使用自身 profile；SystemUI、ZuiControl、IME、权限/Resolver/overlay 等 transient 真正 foreground 时使用 neutral/default 120，返回业务 App 后恢复。`lastNonTransientScenePackage` 只用于 QS/ZuiControl 编辑对象，不再用于继承当前物理 Hz。

真实非空focused Window是physical authority：真实window signal出现后，背后的Activity metadata只补充元数据，不得追溯重分类当前window或触发physical apply。空edge是 `EMPTY_FOCUS_TRANSITION`，保留最后非空policy等待下一非空edge，不作为default120 owner。它不改变SystemUI/ZuiControl/IME等真实非空transient的default120语义。

同一工作包统一处理：

- raw/current/last 与 desired/attempted/applied/physical 状态不变量；
- 删除 `controlPanel` 独立 profile-owner 特判；QS/QuickService 始终修改上一个真实业务场景，transient 前台只保存；
- kill truth仍由两个persist property组成；raw setprop不会自动report system_server的process-local callback。correction中reserved signed-App TX10直接persist+transition，engineering raw property由init edge-only标准sysprop poke唤醒；
- disabled 后定向清 priority-8 vote、compare/restore peak，并请求 shared AppRequest 的 WindowManager traversal handoff；真机已证明本地ownership释放和外部DMS接管，但该无token API没有同步completion callback；
- apply/skip/fail/disabled 后 `appliedScenePackage` 的真实语义；
- 60/90/120/144/165 的 transient 与 physical Hz 矩阵。

当前RunId已证明foreground-only主路径、五档、IME/Resolver/Permission、QS编辑对象、dedup、freeform/split/PiP、kill-switch无人工poke20/20、disabled/enabled boot persistence、App-to-App 100往返0个observed intermediate120，以及两个exact OEM transient不污染business/profile。最终idle worker仍为0。TX10 signed-App device path不可达、UDFPS为`NOT_OBSERVED`、fault injection未执行、多用户/外接屏未验证；这些是保留的非阻断边界，不得写成PASS。旧 [`08_DEVICE_RESULTS.md`](V20_4_REFRESH_CORRECTNESS/08_DEVICE_RESULTS.md) 仅记录被替代RunId的历史PARTIAL。

当前 `displayVote=adaptiveRender`，target=120 时静止 physical actual 可以降到 60；尚未交付 120 hard-lock。`fpsCap` 仍是未交付兼容字段。

## Repository layout

- `app/`：privileged Android App。
- `framework_patch/`：`android.zui.ZuiControlManager`、`ZuiControlService` 和 WM focus hook。
- `framework-stubs/`：App 编译期 framework API。
- `payload/`：注入 system image 的 APK、init、binary、config 和 SELinux payload。
- `scripts/`：build、payload 应用、framework 注入和 final-package 验证。
- `V20_3B_DAEMON_RETIREMENT/tests/`：当前 host/device policy 与回归工具；测试代码保留原位。
- `V20_4_REFRESH_CORRECTNESS/`：refresh 状态模型、host/build/ART/Boot证据、device plan与真机结果。
- `V20_4_REFRESH_RUNTIME_CORRECTION/`：三个runtime blocker的根因、定向设计、host/final gate与当前真机结果。
- `V20_4_UPERF_ARCHITECTURE_REBASE/`：upstream冻结审计、top-resumed scene、Uperf生命周期、knob ownership、host/build与device plan。
- `V20_4_UPERF_SELINUX_STARTUP_CORRECTION/`：失败根因、完整runtime access graph、最小SELinux修正、semantic Gate、最终构建与下一次窄Boot计划。
- `upstream/uperf/1.0.6/`：不可变最小upstream快照；不是production payload。
- `CURRENT_EVIDENCE_INDEX.md`：当前基线的最小证据入口。

## Build 与验证

本地 payload 入口：

```bash
python scripts/ApplyZuiControlPayload.py --unpack /path/to/work/unpack
```

关键验证锚点：

- `service list | grep zui_control`
- `dumpsys zui_control`
- `dumpsys display`
- `ps -AZ | grep -E 'zui_controld|zui_control_request|uperf|AsoulOpt'`
- `logcat -b all | grep -i ZuiControl`
- `/data/vendor/zui_control/uperf/{cur_powermode,perapp_powermode,effective_powermode}.txt`
- `/data/vendor/zui_control/asoul/asopt.conf`

Steady-state 预期包含：refresh owner=`system`、persistent `zui_controld` PID=0、idle `zui_control_request` PID=0、Uperf/asoulOpt 由 init 托管。Scheduler health 从按需 Binder/dumpsys 读取，不再使用旧 `zui_control_uperf_health` Settings heartbeat。

任何 ROM 交付仍必须按 `AGENTS.md` 做 final-super 反向内容、context、SHA-256 和刷后 AVC 验证。

已失败的exact候选：`D:\3.VScode\Mi\work\v20_4_uperf_correction_candidate_20260901174600`。它绑定source `511f31483243107cff76bb7ed75c0417e574d98a`与CI `33490157865`；`super.img` SHA-256为`e10593227adc27a0f56622ee6e9e1aafe46dd2a44b88e58b69501f08bb8075dc`。刷前final-super 62-marker、semantic access graph、目标ART与split CIL均PASS，但真机Startup Runtime Gate FAIL，不得再次刷写或继续完整矩阵。`super`仍固定13,958,643,712 bytes，旧/新`system_a` EROFS尺寸相同，没有因system增长而需要额外删除预装App。

## 当前禁止

V20.4 Refresh Correctness 不混入 AppOpt/XML/ZuiPP/fpsCap/KGSL 生产代码清理、thermal 大改、Uperf/asoulOpt 无证据升级、新 persistent daemon/watchdog、Accessibility/App refresh owner。生产代码级历史清理留 V21，GPU ownership 留 V22。
