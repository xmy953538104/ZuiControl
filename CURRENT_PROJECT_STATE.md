# ZuiControl Current Project State

更新时间：2026-09-04

## 1. 当前结论

V20.4 已通过 Final Closure Gate，并冻结为 **Golden / CLOSED WITH
EXPLICIT BOUNDARIES**。唯一 Golden source、镜像与真机绑定信息见
[`V20_4_GOLDEN_BASELINE.md`](V20_4_GOLDEN_BASELINE.md) 和
[`V20_4_GOLDEN_BASELINE.json`](V20_4_GOLDEN_BASELINE.json)：

```text
BUILD_RUN_ID=20260903144915
SOURCE_COMMIT=29f23f8d590b88f0d472c12373366a9ef14e8330
CI_RUN=33724674012
DEVICE_GATE_RUN_ID=20260903153438
V20_4_GATE=PASS
V20_4_STATUS=CLOSED_WITH_EXPLICIT_BOUNDARIES
```

当前状态是 **V21 Phase 1 portable engineering baseline 已关闭；Phase 2 未开始**。
Phase 1 仅变更文档、host tooling、host tests、archive metadata 和明确
allowlist 的 workspace cleanup；V20.4 production runtime 保持零差异，未构建
新 ROM，未刷机。任何后续 production 工作必须显式声明
`BASELINE_SOURCE` 和 `BASELINE_IMAGE_HASHES`，不得从旧失败候选或“最新目录”
推断 baseline。

设备/系统：TB321FU / ZUI 16.1.11.072。V20.3B persistent daemon
retirement architecture 仍为 PASS，阶段已经关闭；其残余问题已经进入 backlog，
不得要求重做 V20.3B。

## 2. Golden 架构

| 能力 | 唯一 owner / 当前路径 |
| --- | --- |
| Refresh | system_server focused Window → `ZuiControlService` → DisplayManagerInternal / ROM display policy |
| Uperf scene | system_server display-global top-resumed Activity/screen edge → protected property → init → effective mode file |
| Uperf lifetime | regular startup log readiness → native subreaper → blocking `waitpid(-1)` |
| CPU/power model | `/system/bin/uperf`; `powersave|balance|performance|fast` |
| per-task placement | asoulOpt；Uperf `sched.enable=false` |
| OEM fence | init-native + `scheduler_active` gate |
| Command | authenticated Binder → disabled+oneshot request → `zui_controld --oneshot-request` → durable ACK |
| Health | 按需 Binder；无 persistent zui_controld heartbeat |

`persistent zui_controld` 已退休。Uperf output 是 regular file；readiness 是
native startup-only checker；READY 后关闭日志 FD/parser；进程生命周期由
`PR_SET_CHILD_SUBREAPER` 与 blocking `waitpid` 负责。steady state 没有 FIFO、
shell、日志轮询、第二 restart owner 或新增 watchdog。

Uperf exact-rule authority 是 framework 的 display-global top-resumed Activity。
合法 package 后紧随的 transitional null 先进入 64ms pending/revalidation；后续
合法 package 可取消它，持续 null 才按当前 authority 确认。Final Gate 的三次
冷启动和 15 行矩阵均未出现 global performance dip。

Refresh authority 仍是 focused Window，不得与 Uperf top-resumed authority 混写。
Foreground-only 语义不变：真实非空 SystemUI/ZuiControl/IME 等 transient 前台
使用 default120；`lastNonTransientScenePackage` 只用于配置对象；空 Window edge
保留最后非空 policy，等待下一非空 edge。

## 3. Final Closure 事实

- transitional-null 3/3 PASS；global dip、stale override、unexpected property
  write 均为 0；
- display-global scene matrix 15/15 PASS，覆盖 game/home/video、QS、freeform、
  split、PiP、screen off/on；
- 610 秒 mixed-use soak / 55 samples PASS；system_server、Uperf supervisor、
  Uperf workers、asoulOpt 均稳定；
- Uperf/Refresh/asoulOpt regression PASS；new blocking AVC=0；
- fixed-seven physical read-back 7/7 exact length/hash PASS；
- 唯一一次 read-back 后 reset 返回 Launcher，boot/scheduler smoke PASS。

最终闭环包：`D:\3.VScode\Mi\Review packages\V20.4\V20_4_FINAL_CLOSURE_GATE.zip`，SHA-256
`582c1fcfaf5d4eac629c95f723b57d0a5492825d2e1408e6c29250608694a490`。
默认先读 [`CURRENT_EVIDENCE_INDEX.md`](CURRENT_EVIDENCE_INDEX.md)，只有结论受
质疑时才打开闭环包中的 raw。

## 4. Golden hashes

| Artifact | SHA-256 |
| --- | --- |
| `super.img` | `6124e7ddcdc8e656bda893158575ed22c4f240943a8b56c82b98546a666ba6c4` |
| `boot.img` | `e7e85b5cd2806b8c27adf4925e05ee169072a79a43502effc34c97fb27ee8371` |
| `vbmeta_system.img` | `9479cf42e908615517d585aee01c4b803706f50253fdf0ac8d5238cc65ec22fb` |
| `vbmeta.img` | `c1f4ea68ea52bae62e464ddc245dadd740569ba3ac3376ee5d23a40204a744f7` |
| final `services.jar` | `245b4f2c55d5ed8b99ecba8bd473d1d76eb40c55d67116a477299cc9d8b62000` |
| Uperf | `f1265757009ff0c85dd8587d9e7bfcf5e51d10d36fe5e1341688215ae1fb49d8` |
| supervisor | `810d58821ff906e337e06701ae99226e3016699398d46880086168b8d7a4a655` |
| asoulOpt | `7a2ee5d67ba7c057066176334eca9256e376427916429d66b7593cbb5538ec86` |
| deployed Uperf config | `fc719b55087f3a1309c2a19bc6442ce2a98276aa0aedc55e7c501654bb268dd8` |

## 5. 显式边界与 backlog

Final Closure 不宣称 interactive true-persistent-null 真机覆盖、worker crash/storm
闭环、全 CPU/GPU/thermal knob ownership、performance/thermal A/B、120Hz hard
lock、多用户或外接屏。以下状态保持不变：

```text
WORKER_CRASH_LIFECYCLE=OPTIONAL_HARDENING
WORKER_STORM_3_20S=OPTIONAL_HARDENING
CORE_CTL_OWNERSHIP_DEEP_AUDIT=BACKLOG
INPUT_BOOST_OWNERSHIP_DEEP_AUDIT=BACKLOG
CPUSET_OWNERSHIP_DEEP_AUDIT=BACKLOG
PERFORMANCE_AB=BACKLOG
THERMAL_AB=BACKLOG
ADAPTIVE_REFRESH_RESPONSE_TUNING=BACKLOG
```

## 6. V21 Phase 1 工程状态（CLOSED）

- Mi 工作区已收敛为 `ZuiControl` / original / flash work+out / `Edit tools` /
  physical `script` / `Review packages`；不再依赖临时 worktree 或 junction；
- Python 3.8、Temurin JDK 17、Git、Android SDK、ADB/fastboot、qdl-rs、镜像工具、
  AVB 与 smali/apktool 已置于 `Edit tools`；Qualcomm 9008 driver 仍需 Windows 安装；
- 签名/private material 的唯一位置是 `Edit tools\Signing`，manifest 仅记录哈希和用途，
  不记录内容或密码，也不得进入 Git/Review package；
- Gate archive 的唯一位置是 `Review packages\V20.4|V21`；
- Golden baseline manifest 是唯一 baseline selector；
- V20.4 候选按 `GOLDEN / CLOSED_REFERENCE / FAILED / SUPERSEDED /
  DIAGNOSTIC_ONLY` 分类；
- active FIFO production assumptions 必须为 0；历史 FIFO 文档只作
  `HISTORICAL ONLY`；
- 复杂设备逻辑走 `scripts/device/*.sh` → push → 单次 root execute → pull；
- canonical Scene harness 只解析 `^\s*ResumedActivity:`，protected property
  在同次 root snapshot 获取；
- workspace cleanup 只接受显式 plan、tree SHA、protected whitelist，默认 DryRun；
- build/extraction cache 只缓存可复用输入展开，不得跳过每次 framework 改动的
  final ART gate；
- qdl progress 先以 host compactor 收敛至 64MiB milestone 并保留错误/终态；
  Firehose device digest 仍为 `NOT_APPROVED`，不能替代 full read-back。
- Phase 1 没有建立新的 canonical image build shell；后续 production build 必须在
  独立工作包中显式提供并 review，不得回退到已删除的旧路径或自动猜测。

## 7. 默认入口

新会话默认只读：`AGENTS.md` → 本文件 → `README.md` → 当前源码 →
`CURRENT_EVIDENCE_INDEX.md` → Golden baseline manifest。不要默认扫描 archive、
旧 handoff、旧 raw 或历史 device package。

```text
V20_4_GOLDEN_FROZEN=YES
PRODUCTION_RUNTIME_CHANGED=NO
ROM_BUILT=NO
DEVICE_FLASHED=NO
V21_PHASE=PHASE1_CLOSED_PHASE2_NOT_STARTED
V21_PHASE1_STATUS=CLOSED
```
