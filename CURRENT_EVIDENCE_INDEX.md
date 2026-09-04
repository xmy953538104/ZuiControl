# ZuiControl Current Evidence Index

更新时间：2026-09-04

当前唯一生产基线：V20.4 Golden / Build RunId `20260903144915` / source
`29f23f8d590b88f0d472c12373366a9ef14e8330` / CI `33724674012` / Device Gate
RunId `20260903153438`。

权威 baseline：[`V20_4_GOLDEN_BASELINE.md`](V20_4_GOLDEN_BASELINE.md) 与
[`V20_4_GOLDEN_BASELINE.json`](V20_4_GOLDEN_BASELINE.json)。最终闭环包位于
`D:\3.VScode\Mi\Review packages\V20.4\V20_4_FINAL_CLOSURE_GATE.zip`，SHA-256
`582c1fcfaf5d4eac629c95f723b57d0a5492825d2e1408e6c29250608694a490`。

本文件是未来会话的默认证据入口。先读下表的最小结论；只有某个数字受到质疑
时，才打开对应 evidence 文件或 ZIP 内 raw。不得按目录时间或文件名自动选择
candidate，也不要递归扫描历史失败产物。

## V20.4 Golden 最小证据

| 项目 | 结论 | 关键数字 | 最小证据 |
| --- | --- | --- | --- |
| Exact lineage | PASS | source `29f23f8`; CI `33724674012`; build `20260903144915` | ZIP `EXACT_LINEAGE.txt` |
| Boot Hard Gate | PASS | system_server PID/starttime `2706/992`，13 samples / 120s invariant | ZIP `BOOT/BOOT_GATE_SUMMARY.md` |
| Transitional null | PASS | 3/3 cold game progressions；global dip/stale override/unexpected write=`0/0/0` | ZIP `TOP_RESUMED_NULL/TRANSITIONAL_NULL_DECISION.md` |
| Top-resumed matrix | PASS | 15/15；display-global `ResumedActivity:` authority；QS/freeform/split/PiP/screen | ZIP `FINAL_SCENE_MATRIX.tsv`, `FINAL_SCENE_MATRIX_DECISION.md` |
| Transition latency | PASS | event→property mean `0.400ms`; event→ack mean `2.401ms`, max `8.372ms` | ZIP `TRANSITION_LATENCY/TRANSITION_SUMMARY.md` |
| Ten-minute soak | PASS | 610s / 55 samples；core process identities invariant | ZIP `FINAL_SOAK/SOAK_SUMMARY.md` |
| Uperf health | PASS | supervisor blocking `do_wait`; ready/log inode stable；fail-safe=0 | ZIP `UPERF_HEALTH/UPERF_HEALTH_SUMMARY.md` |
| Refresh/asoulOpt | PASS | target/applied semantics intact；asoulOpt PID/starttime invariant | ZIP `REGRESSION/REGRESSION_SUMMARY.md` |
| SELinux | PASS | relevant AVC=0 | ZIP `SELINUX/SELINUX_SUMMARY.md` |
| Physical seal | PASS | fixed-seven full read-back 7/7 exact length/SHA；program command=NO | ZIP `FINAL_PHYSICAL_READBACK/READBACK_SUMMARY.md` |
| Post-seal boot | PASS | system_server 7 samples / 62s invariant；Launcher/Binder/Enforcing healthy | ZIP `POST_READBACK_BOOT/POST_READBACK_SUMMARY.md` |

闭环证据默认只读上述 ZIP 内的最小报告；已解包工作副本已清理。
Golden images 只认 baseline manifest 中的 exact path/hash。

## Golden artifact identity

```text
super=6124e7ddcdc8e656bda893158575ed22c4f240943a8b56c82b98546a666ba6c4
boot=e7e85b5cd2806b8c27adf4925e05ee169072a79a43502effc34c97fb27ee8371
vbmeta_system=9479cf42e908615517d585aee01c4b803706f50253fdf0ac8d5238cc65ec22fb
vbmeta=c1f4ea68ea52bae62e464ddc245dadd740569ba3ac3376ee5d23a40204a744f7
services.jar=245b4f2c55d5ed8b99ecba8bd473d1d76eb40c55d67116a477299cc9d8b62000
uperf=f1265757009ff0c85dd8587d9e7bfcf5e51d10d36fe5e1341688215ae1fb49d8
supervisor=810d58821ff906e337e06701ae99226e3016699398d46880086168b8d7a4a655
asoulOpt=7a2ee5d67ba7c057066176334eca9256e376427916429d66b7593cbb5538ec86
uperf-config-live=fc719b55087f3a1309c2a19bc6442ce2a98276aa0aedc55e7c501654bb268dd8
```

## 历史 lineage 入口

候选状态与旧/新路径见
[`docs/maintenance/v21-phase1/ARCHIVE_CLASSIFICATION.md`](docs/maintenance/v21-phase1/ARCHIVE_CLASSIFICATION.md)。
旧候选只允许 `CLOSED_REFERENCE / FAILED / SUPERSEDED / DIAGNOSTIC_ONLY`，不得
覆盖 Golden。

V20.3B daemon retirement、OEM fence、normal recovery、command latency、idle、
Boot/App 和 transaction security 的可重用结论已压缩到 `AGENTS.md` 第 9 节。
V20.3B 过往原始包已在知识压缩后清理；该阶段已关闭。rapid Uperf
storm 与 T8 request-ID 仍是 backlog，并未变成 PASS。

## 明确保留边界

```text
TRUE_PERSISTENT_NULL_INTERACTIVE=NOT_EXERCISED
WORKER_CRASH_LIFECYCLE=OPTIONAL_HARDENING
WORKER_STORM_3_20S=OPTIONAL_HARDENING
CORE_CTL_OWNERSHIP_DEEP_AUDIT=BACKLOG
INPUT_BOOST_OWNERSHIP_DEEP_AUDIT=BACKLOG
CPUSET_OWNERSHIP_DEEP_AUDIT=BACKLOG
PERFORMANCE_AB=BACKLOG
THERMAL_AB=BACKLOG
ADAPTIVE_REFRESH_RESPONSE_TUNING=BACKLOG
```

`displayVote=adaptiveRender`；target120 静止时 physical actual 可以降到60。
V20.4 不等于120Hz hard-lock。
