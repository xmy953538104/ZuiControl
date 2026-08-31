# ZuiControl

ZuiControl 是 TB321FU / ZUI 16.1.11.072 的 ROM 内置系统控制工具，核心能力是刷新率、Uperf CPU/power-model 调度和 asoulOpt per-task 线程放置。App 只提供 UI、QS、通知快捷控制和配置入口，核心调度不依赖 App 长期存活。

## 当前入口

新会话按以下顺序读取：

1. [`AGENTS.md`](AGENTS.md)
2. [`CURRENT_PROJECT_STATE.md`](CURRENT_PROJECT_STATE.md)
3. 本文件
4. 当前生产源码
5. [`CURRENT_EVIDENCE_INDEX.md`](CURRENT_EVIDENCE_INDEX.md)

V20.3B 阶段已经关闭，persistent daemon retirement architecture = PASS。当前设备运行 V20.4 fixed RunId `20260831134511`：source/host/CI/ROM build/final-super、final-artifact ART 与 Boot Hard Gate PASS，device validation **PARTIAL**。旧 RunId `20260831094239` 已在刷前拒绝；`20260831104317` 刷后因 ART `VerifyError` Boot Gate FAIL并完成V20.3B恢复，两者均不得再次刷写。App 仍是 versionCode 49 / versionName 0.21.12。

旧 handoff、AI 报告、阶段报告和 raw/trace/log 位于仓库外 `D:\3.VScode\Mi\ZuiControl_Archive\`。默认不要扫描 archive；只按 evidence index 定向读取。

## 当前架构

| 能力 | 当前 owner / 路径 |
| --- | --- |
| Refresh scene 与执行 | `system_server` / `ZuiControlService` → DisplayManagerInternal / ROM display policy |
| Uperf scene/screen mode | `system_server` event-driven → protected property → init → `effective_powermode.txt` → Uperf |
| CPU/power-model 执行 | `/system/bin/uperf`，四档 `powersave|balance|performance|fast` |
| per-task affinity/context scheduling | asoulOpt；Uperf `sched.enable=false` |
| OEM `vendor.perfservice` fence | Android init，`scheduler_active=1` gate |
| Command | authenticated Binder kick → disabled+oneshot `zui_control_request` → `zui_controld --oneshot-request` → durable receipt/ACK → exit |
| Health/status | 按需 Binder `getState()`；没有 persistent zui_controld heartbeat |

`persistent zui_controld` 已退休：生产 init 中没有 `zui_controld` service/start。`/system/bin/zui_controld` 只保留为 `--oneshot-request` command executor，没有 refresh、scene detector 或后台 health publisher 职责。

`/system/bin/zui_uperf_service` 仍每 5 秒检查自身 service cgroup 与 Uperf 日志，失败后退出并交给 init 恢复。它是执行面 self-check，不是 Settings/Binder health heartbeat，不能写成已删除。

OEM GPU/thermal 继续保留安全和频率裁决；当前不宣称接管 Adreno KGSL。Uperf 的全局 cpuset 写入与更广义 knob ownership 留后续独立审计。

## 当前 Refresh Correctness 工作包

第一优先级已经按 foreground-only state machine 实现：业务 App 真正 foreground 时使用自身 profile；SystemUI、ZuiControl、IME、权限/Resolver/overlay 等 transient 真正 foreground 时使用 neutral/default 120，返回业务 App 后恢复。`lastNonTransientScenePackage` 只用于 QS/ZuiControl 编辑对象，不再用于继承当前物理 Hz。

focused window 是 physical authority：真实 window signal 出现后，背后的 Activity metadata 变化只补充元数据，不得追溯重分类当前 window 或触发 physical apply。host 的 Activity-first/window-first 模型已覆盖，但真机发现 App-to-App 临时 null-window 被当成 authoritative empty owner，制造 intermediate default120；这项运行时契约尚未通过。

同一工作包统一处理：

- raw/current/last 与 desired/attempted/applied/physical 状态不变量；
- 删除 `controlPanel` 独立 profile-owner 特判；QS/QuickService 始终修改上一个真实业务场景，transient 前台只保存；
- 两个 refresh kill switch 通过 property callback 投递到 HandlerThread，并按最新 focus snapshot 恢复；
- disabled 后定向清 priority-8 vote、compare/restore peak，并请求 shared AppRequest 的 WindowManager traversal handoff；该无 token AppRequest 的实际完成时序留真机验证；
- apply/skip/fail/disabled 后 `appliedScenePackage` 的真实语义；
- 60/90/120/144/165 的 transient 与 physical Hz 矩阵。

真机已证明 foreground-only 主路径、五档、IME/Resolver、QS编辑对象、dedup、freeform/split/PiP、profile拒绝、peak observer、enabled idle与Binder边界。三项 blocker 必须保留：kill switch property edge不收敛到service mask；App-to-App每次出现额外default120 apply；`com.lenovo.screensplit`与`com.zui.freeform.sidebar`被误分类为业务App。UDFPS为`NOT_OBSERVED`，fault injection及kill-switch下游release/reenable未执行，多用户/外接屏未验证。权威结果见 [`08_DEVICE_RESULTS.md`](V20_4_REFRESH_CORRECTNESS/08_DEVICE_RESULTS.md)。

当前 `displayVote=adaptiveRender`，target=120 时静止 physical actual 可以降到 60；尚未交付 120 hard-lock。`fpsCap` 仍是未交付兼容字段。

## Repository layout

- `app/`：privileged Android App。
- `framework_patch/`：`android.zui.ZuiControlManager`、`ZuiControlService` 和 WM focus hook。
- `framework-stubs/`：App 编译期 framework API。
- `payload/`：注入 system image 的 APK、init、binary、config 和 SELinux payload。
- `scripts/`：build、payload 应用、framework 注入和 final-package 验证。
- `V20_3B_DAEMON_RETIREMENT/tests/`：当前 host/device policy 与回归工具；测试代码保留原位。
- `V20_4_REFRESH_CORRECTNESS/`：refresh 状态模型、host/build/ART/Boot证据、device plan与真机结果。
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

当前已刷 fixed candidate：`D:\3.VScode\Mi\work\v20_4_candidate_20260831134511`。它绑定 source commit `3c5cd809d5465828fe14356cbd079d45d00347b7` 与 CI run [`33361319072`](https://github.com/xmy953538104/ZuiControl/actions/runs/33361319072)；最终 `super.img` SHA-256 为 `4f01c64d8a3a5860c34967d944510f3768f4e6748bb843eef0345a5c6685800d`，final-super verifier `marker_count=48`，最终 `services.jar` ART gate和刷后Boot Gate均PASS。`build_result.json`中的`flashed=false`是构建完成时的receipt，不表示当前设备未刷。device acceptance仍因上述三项blocker为PARTIAL。

## 当前禁止

V20.4 Refresh Correctness 不混入 AppOpt/XML/ZuiPP/fpsCap/KGSL 生产代码清理、thermal 大改、Uperf/asoulOpt 无证据升级、新 persistent daemon/watchdog、Accessibility/App refresh owner。生产代码级历史清理留 V21，GPU ownership 留 V22。
