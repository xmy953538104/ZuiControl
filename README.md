# ZuiControl

ZuiControl 是 TB321FU / ZUI 16.1.11.072 的 ROM 内置系统控制能力，核心是
刷新率、Uperf CPU/power-model 调度和 asoulOpt per-task 线程放置。App 只负责
UI、QS、通知快捷控制和配置入口，核心调度不依赖 App 长期存活。

## 当前入口与状态

新会话按以下顺序读取：

1. [`AGENTS.md`](AGENTS.md)
2. [`CURRENT_PROJECT_STATE.md`](CURRENT_PROJECT_STATE.md)
3. 本文件
4. 当前生产源码
5. [`CURRENT_EVIDENCE_INDEX.md`](CURRENT_EVIDENCE_INDEX.md)
6. [`V20_4_GOLDEN_BASELINE.md`](V20_4_GOLDEN_BASELINE.md)

V20.4 Final Closure 已 PASS，状态为 **CLOSED WITH EXPLICIT BOUNDARIES**。
唯一 Golden source 是 commit
`29f23f8d590b88f0d472c12373366a9ef14e8330`；Build RunId
`20260903144915`，CI `33724674012`，Device Gate RunId `20260903153438`。
V21 Phase 1 engineering cleanup 已完成，Phase 2 未开始。本工程基线不得
修改 production runtime、build ROM 或 flash device。

## 当前架构

| 能力 | owner / 路径 |
| --- | --- |
| Refresh | system_server focused Window → DisplayManagerInternal / ROM display policy |
| Uperf scene | system_server display-global top-resumed Activity/screen edge → protected property → init |
| Uperf readiness/lifetime | regular startup log → native startup-only checker → subreaper + blocking `waitpid` |
| CPU/power model | Uperf 四档 `powersave|balance|performance|fast` |
| per-task affinity | asoulOpt；Uperf `sched.enable=false` |
| OEM fence | init-native + `scheduler_active` gate |
| Command | authenticated Binder → disabled+oneshot executor → durable ACK |
| Health | 按需 Binder |

`persistent zui_controld` 已退休；`zui_controld` 只接受
`--oneshot-request`。Uperf steady state 没有 FIFO、shell、日志轮询或第二 restart
owner。Refresh 与 Uperf authority 分开：前者使用 focused Window，后者使用
display-global top-resumed Activity。

Foreground-only refresh 语义不变：业务 App 真正前台时使用自己的 profile；
SystemUI、ZuiControl、IME、权限/Resolver/overlay 等非空 transient 前台使用
default120；`lastNonTransientScenePackage` 只作为配置对象。空 Window edge 不成为
default120 owner，而是保留最后非空 policy 等待下一非空 edge。

## Golden baseline

Golden fixed-seven package：
`D:\3.VScode\Mi\zui072（flash）\out\V20.4_Golden_20260903144915`。
四个唯一镜像哈希和 runtime hashes 见
[`V20_4_GOLDEN_BASELINE.json`](V20_4_GOLDEN_BASELINE.json)。最终闭环 ZIP 是
`D:\3.VScode\Mi\zui072（flash）\work\evidence\V20_4_FINAL_CLOSURE_GATE.zip`，SHA-256
`582c1fcfaf5d4eac629c95f723b57d0a5492825d2e1408e6c29250608694a490`。

以后任何 production 工作必须先声明：

```text
BASELINE_SOURCE=29f23f8d590b88f0d472c12373366a9ef14e8330
BASELINE_IMAGE_HASHES=<copy exactly from V20_4_GOLDEN_BASELINE.json>
```

禁止按修改时间、目录名称或“最近候选”自动选择 baseline。

## Repository layout

- `app/`：privileged Android App；
- `framework_patch/`：framework/system_server 注入；
- `framework-stubs/`：App 编译期 framework API；
- `native/`：native supervisor 源码；
- `payload/`：ROM payload；
- `scripts/`：build、verify、device helper、workspace cleanup；
- `docs/maintenance/v21-phase1/`：V21 Phase 1 工程审计；
- `CURRENT_EVIDENCE_INDEX.md`：Golden 最小证据入口。

旧 handoff、失败候选报告和 raw/trace/log 的可重用知识已压缩到
`scripts/docs/`；不得默认扫描或重用历史失败产物。

## 当前禁止

V21 Phase 1 不做 AppOpt/XML/ZuiPP/fpsCap/KGSL production cleanup，不做
worker fault、CPU knob ownership、adaptive refresh tuning 或 performance/thermal
A/B，不修改 Uperf/asoulOpt/runtime policy，不构建 ROM，不刷机。后续工作顺序
需先经独立 review。
