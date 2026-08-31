# ZuiControl

ZuiControl 是 TB321FU / ZUI 16.1.11.072 的 ROM 内置系统控制工具，核心能力是刷新率、Uperf CPU/power-model 调度和 asoulOpt per-task 线程放置。App 只提供 UI、QS、通知快捷控制和配置入口，核心调度不依赖 App 长期存活。

## 当前入口

新会话按以下顺序读取：

1. [`AGENTS.md`](AGENTS.md)
2. [`CURRENT_PROJECT_STATE.md`](CURRENT_PROJECT_STATE.md)
3. 本文件
4. 当前生产源码
5. [`CURRENT_EVIDENCE_INDEX.md`](CURRENT_EVIDENCE_INDEX.md)

V20.3B 阶段已经关闭，persistent daemon retirement architecture = PASS。最近真机基线仍是 RunId `20260830181816`；V20.4 Refresh Correctness / State Machine 已生成未刷机 candidate RunId `20260831094239`，source/host/CI/ROM build/final-super PASS，device validation PENDING。App 仍是 versionCode 49 / versionName 0.21.12。

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

同一工作包统一处理：

- raw/current/last 与 desired/attempted/applied/physical 状态不变量；
- 删除 `controlPanel` 独立 profile-owner 特判；QS/QuickService 始终修改上一个真实业务场景，transient 前台只保存；
- 两个 refresh kill switch 通过 property callback 投递到 HandlerThread，并按最新 focus snapshot 恢复；
- disabled 后定向清 priority-8 vote、compare/restore peak，并请求 shared AppRequest 的 WindowManager traversal handoff；该无 token AppRequest 的实际完成时序留真机验证；
- apply/skip/fail/disabled 后 `appliedScenePackage` 的真实语义；
- 60/90/120/144/165 的 transient 与 physical Hz 矩阵。

当前 `displayVote=adaptiveRender`，target=120 时静止 physical actual 可以降到 60；尚未交付 120 hard-lock。`fpsCap` 仍是未交付兼容字段。

## Repository layout

- `app/`：privileged Android App。
- `framework_patch/`：`android.zui.ZuiControlManager`、`ZuiControlService` 和 WM focus hook。
- `framework-stubs/`：App 编译期 framework API。
- `payload/`：注入 system image 的 APK、init、binary、config 和 SELinux payload。
- `scripts/`：build、payload 应用、framework 注入和 final-package 验证。
- `V20_3B_DAEMON_RETIREMENT/tests/`：当前 host/device policy 与回归工具；测试代码保留原位。
- `V20_4_REFRESH_CORRECTNESS/`：refresh 状态模型、host/build 证据、final-super verifier 结果与 device plan。
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

当前未刷机候选：`D:\3.VScode\Mi\work\v20_4_candidate_20260831094239`。它绑定 source commit `3865cf9c99cb89a8df2b705b9b3dbb2711b311ec` 与 CI run [`33348269219`](https://github.com/xmy953538104/ZuiControl/actions/runs/33348269219)；最终 `super.img` SHA-256 为 `9774b6aa8e72b5dc6c0514c366786da453bf509106a09946be9356969e43d3d9`，基础与 V20.4 final-super verifier 均 PASS。该结论只允许进入 device matrix，不等于真机验证完成。

## 当前禁止

V20.4 Refresh Correctness 不混入 AppOpt/XML/ZuiPP/fpsCap/KGSL 生产代码清理、thermal 大改、Uperf/asoulOpt 无证据升级、新 persistent daemon/watchdog、Accessibility/App refresh owner。生产代码级历史清理留 V21，GPU ownership 留 V22。
