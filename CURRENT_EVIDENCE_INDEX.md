# ZuiControl Current Evidence Index

更新时间：2026-09-01
最近关闭的完整基线：V20.3B / RunId `20260830181816` / source `30fe138a7ef531aeffbcf951e9113f4ae0d17cfe`
当前设备：V20.4 Runtime Correction RunId `20260831170720` / source `146e096c6a6bc8b3fee60349b856990fd9fb68d2` / CI `33375509612`

本文件是未来会话的默认证据入口。先读结论与最小报告；只有数字受到质疑时才打开对应 raw 或结果RAR。不要递归扫描 `D:\3.VScode\Mi\ZuiControl_Archive\`。

V20.3B 阶段已关闭；daemon-retirement architecture = PASS。rapid Uperf storm与T8 request-ID仍是carry-forward，未被改写成PASS。V20.4 Refresh Correctness / State Machine已由当前Runtime Correction真机Gate关闭，状态为 **PASS / CLOSED WITH EXPLICIT BOUNDARIES**。

V20.4 Uperf Architecture & Upstream Rebase已完成source/host/build/final-artifact Gate，RunId `20260901120647` 为 **PRE-FLASH READY**，但不是device/Production PASS。upstream审计、scene/lifecycle/ownership设计、host与final结果分别见[`01_UPSTREAM_AUDIT.md`](V20_4_UPERF_ARCHITECTURE_REBASE/01_UPSTREAM_AUDIT.md)、[`07_SCENE_STATE_MODEL.md`](V20_4_UPERF_ARCHITECTURE_REBASE/07_SCENE_STATE_MODEL.md)、[`04_UPERF_LIFECYCLE.md`](V20_4_UPERF_ARCHITECTURE_REBASE/04_UPERF_LIFECYCLE.md)、[`08_HOST_TESTS.md`](V20_4_UPERF_ARCHITECTURE_REBASE/08_HOST_TESTS.md)和[`09_BUILD_VERIFY.md`](V20_4_UPERF_ARCHITECTURE_REBASE/09_BUILD_VERIFY.md)。候选未刷，不得把它覆盖到下方当前设备基线。

Archive根：[`../ZuiControl_Archive/README.md`](../ZuiControl_Archive/README.md)

## V20.3B 最小证据映射

| 项目 | 结论 | 关键数字 | 最小报告 | 必要 raw |
| --- | --- | --- | --- | --- |
| Daemon retirement | PASS | persistent `zui_controld` service/start=0；idle persistent/request row=0 | [`V20_3B_DECISION.md`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/V20_3B_DECISION.md), [`09_IDLE_NO_DAEMON.md`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/09_IDLE_NO_DAEMON.md) | [`services_verified.txt`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/final_runtime_post_reboot/services_verified.txt) |
| OEM fence | PASS | active20/20最终stopped；host P95≈120ms；500 quiet samples无storm；inactive60s同PID/starttime running | [`02_INIT_NATIVE_OEM_FENCE.md`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/02_INIT_NATIVE_OEM_FENCE.md) | [`matrix.tsv`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_oem_fence_final2/03_oem_fence/matrix.tsv), [`storm_summary.txt`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_oem_fence_final2/03_oem_fence/storm_summary.txt) |
| Uperf normal recovery | PASS | 10/10；mean3046ms；P50 3380ms；P95/max5500ms | [`03_COMPONENT_RECOVERY_10X.md`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/03_COMPONENT_RECOVERY_10X.md) | [`uperf_10x_and_storm_partial_recovered.tsv`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_recovery/04_component_recovery/uperf_10x_and_storm_partial_recovered.tsv) |
| Uperf rapid storm | PARTIAL / carry forward | #1 PASS；#2触发`sys.init.updatable_crashing=1`；#3未执行 | [`03_COMPONENT_RECOVERY_10X.md`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/03_COMPONENT_RECOVERY_10X.md) | [`RECOVERY_PHASE_FAILURE_NOTE.md`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_recovery/04_component_recovery/RECOVERY_PHASE_FAILURE_NOTE.md) |
| asoulOpt recovery | PASS | normal10/10 mean98ms P95/max140ms；bounded storm3/3；explicit stop60s | [`03_COMPONENT_RECOVERY_10X.md`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/03_COMPONENT_RECOVERY_10X.md) | [`asoul_10x_and_storm.tsv`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_recovery_asoul/04_component_recovery/asoul_10x_and_storm.tsv) |
| Command latency | PASS with backlog | 50/50；T0→T9 P95 `1022.100ms`；T4→T5 mean `574.2ms`；action mean `12.4ms` | [`07_COMMAND_LATENCY_BREAKDOWN.md`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/07_COMMAND_LATENCY_BREAKDOWN.md) | [`latency_summary.json`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_latency_final2/02_latency_50/analysis/latency_summary.json) |
| Idle overhead | PASS | 61.13s worker `0.0000%` single-core；89.988s周期Settings/heartbeat/Zui shell=0 | [`09_IDLE_NO_DAEMON.md`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/09_IDLE_NO_DAEMON.md) | [`idle_60s_analysis.txt`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_idle_final2/08_idle_no_daemon/idle_60s_analysis.txt) |
| Boot/App regression | PASS | BootReceiver、QuickService、通知、QS、permissions与cold launch通过 | [`12_APP_BOOT_PERMISSION_REGRESSION.md`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/12_APP_BOOT_PERMISSION_REGRESSION.md) | [`03_on_demand_binder_status_unlocked.txt`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_20260830_192550_run_20260830181816/03_on_demand_binder_status_unlocked.txt) |
| Transaction security | PASS | unauthorized denied；ID/SHA/tamper fail closed；duplicate once；crash/reboot pending收敛 | [`08_SECURITY_REGRESSION.md`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/08_SECURITY_REGRESSION.md) | [`unauthorized_binder.txt`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_security_final4/06_security/unauthorized_binder.txt) |

## V20.4 当前最小结论

最终decision：[`V20_4_REFRESH_RUNTIME_DECISION.md`](V20_4_REFRESH_RUNTIME_DECISION.md)

| Gate | 当前结论 | 关键数字 | 最小报告 | 最小 raw |
| --- | --- | --- | --- | --- |
| Build/final artifact | PASS | host39/39 + regression5/5；marker56；ART/CIL/init gate PASS | [`05_HOST_TESTS.md`](V20_4_REFRESH_RUNTIME_CORRECTION/05_HOST_TESTS.md), [`06_ART_FINAL_GATE.md`](V20_4_REFRESH_RUNTIME_CORRECTION/06_ART_FINAL_GATE.md) | [`final_super_receipt_20260831170720.txt`](V20_4_REFRESH_RUNTIME_CORRECTION/raw/final_super_receipt_20260831170720.txt) |
| Flash/Boot | PASS | exact-seven read-back；boot后187.743s；19 samples；PID/starttime2700/988 stable | [`08_BOOT_GATE.md`](V20_4_REFRESH_RUNTIME_CORRECTION/08_BOOT_GATE.md) | [`02_boot_gate_180s_samples.txt`](V20_4_REFRESH_RUNTIME_CORRECTION/raw/device_run_20260831170720/02_boot_gate_180s_samples.txt) |
| Raw refresh disable | PASS | 20/20 no manual poke；disable mean/P95 173.330/203.710ms；enable176.274/203.848ms | [`09_KILL_SWITCH_DEVICE.md`](V20_4_REFRESH_RUNTIME_CORRECTION/09_KILL_SWITCH_DEVICE.md) | [`06_refresh_disable_20cycles.txt`](V20_4_REFRESH_RUNTIME_CORRECTION/raw/device_run_20260831170720/06_refresh_disable_20cycles.txt) |
| Raw global disable | PASS | 20/20 no manual poke；disable mean/P95 170.993/194.488ms；enable173.237/196.964ms | [`09_KILL_SWITCH_DEVICE.md`](V20_4_REFRESH_RUNTIME_CORRECTION/09_KILL_SWITCH_DEVICE.md) | [`07_global_disable_20cycles.txt`](V20_4_REFRESH_RUNTIME_CORRECTION/raw/device_run_20260831170720/07_global_disable_20cycles.txt) |
| Edge process/dedup | PASS with instrumentation boundary | captured edge各1个约37–39ms executor+1 transaction；rapid80 edges全收敛，apply19/17；callback entry count未instrument | [`09_KILL_SWITCH_DEVICE.md`](V20_4_REFRESH_RUNTIME_CORRECTION/09_KILL_SWITCH_DEVICE.md) | [`32_36_atrace_lifecycle_analysis.txt`](V20_4_REFRESH_RUNTIME_CORRECTION/raw/device_run_20260831170720/32_36_atrace_lifecycle_analysis.txt), [`10_rapid_toggle_both_properties.txt`](V20_4_REFRESH_RUNTIME_CORRECTION/raw/device_run_20260831170720/10_rapid_toggle_both_properties.txt) |
| Boot persistence | PASS | disabled首个有效state mask2/ownersfalse/apply0；enabled boot恢复Launcher/default120；只2次reboot | [`09_KILL_SWITCH_DEVICE.md`](V20_4_REFRESH_RUNTIME_CORRECTION/09_KILL_SWITCH_DEVICE.md) | [`35_37_boot_persistence_analysis.txt`](V20_4_REFRESH_RUNTIME_CORRECTION/raw/device_run_20260831170720/35_37_boot_persistence_analysis.txt) |
| Null Window 100x | PASS | 200真实edge；applyΔ200；emptyΔ200；1270 null samples；observed intermediate120=0 | [`10_NULL_WINDOW_100X.md`](V20_4_REFRESH_RUNTIME_CORRECTION/10_NULL_WINDOW_100X.md) | [`08_focus_100_roundtrips.txt`](V20_4_REFRESH_RUNTIME_CORRECTION/raw/device_run_20260831170720/08_focus_100_roundtrips.txt) |
| Same owner | PASS | warm relaunch10/10；emptyΔ10；applyΔ0；intermediate120=0 | [`10_NULL_WINDOW_100X.md`](V20_4_REFRESH_RUNTIME_CORRECTION/10_NULL_WINDOW_100X.md) | [`11b_null_same_owner_relaunch.txt`](V20_4_REFRESH_RUNTIME_CORRECTION/raw/device_run_20260831170720/11b_null_same_owner_relaunch.txt) |
| Non-empty transient/OEM | PASS | SystemUI/ZuiControl/IME/Resolver/Permission default120；screensplit/sidebar exact transient且business不污染 | [`11_OEM_TRANSIENT_DEVICE.md`](V20_4_REFRESH_RUNTIME_CORRECTION/11_OEM_TRANSIENT_DEVICE.md) | [`17_screensplit_focused_zui.txt`](V20_4_REFRESH_RUNTIME_CORRECTION/raw/device_run_20260831170720/17_screensplit_focused_zui.txt), [`30_sidebar_broadcast_and_zui.txt`](V20_4_REFRESH_RUNTIME_CORRECTION/raw/device_run_20260831170720/30_sidebar_broadcast_and_zui.txt) |
| Multi-window/five modes | PASS | freeform/split/PiP；60/90/120/144/165 physical smoke | [`11_OEM_TRANSIENT_DEVICE.md`](V20_4_REFRESH_RUNTIME_CORRECTION/11_OEM_TRANSIENT_DEVICE.md) | [`23_split_summary.txt`](V20_4_REFRESH_RUNTIME_CORRECTION/raw/device_run_20260831170720/23_split_summary.txt), [`27_notes144_physical_samples.txt`](V20_4_REFRESH_RUNTIME_CORRECTION/raw/device_run_20260831170720/27_notes144_physical_samples.txt), [`28_notes165_physical_samples.txt`](V20_4_REFRESH_RUNTIME_CORRECTION/raw/device_run_20260831170720/28_notes165_physical_samples.txt) |
| Idle/SELinux | PASS | settle321.548s；/proc60.6s worker0；Perfetto89.985s worker0 slices/CPU；blocking AVC0 | [`12_IDLE_REGRESSION.md`](V20_4_REFRESH_RUNTIME_CORRECTION/12_IDLE_REGRESSION.md) | [`idle_60s_analysis.txt`](V20_4_REFRESH_RUNTIME_CORRECTION/raw/device_run_20260831170720/40_idle/idle_60s_analysis.txt), [`perfetto_query_results.txt`](V20_4_REFRESH_RUNTIME_CORRECTION/raw/device_run_20260831170720/40_idle/perfetto_query_results.txt) |
| Final restore | PASS | Launcher/default120；props0；profile64B SHA`7410c5…7221`；PID2714/Binder/Enforcing/boot1 | [`13_FINAL_RUNTIME_STATE.md`](V20_4_REFRESH_RUNTIME_CORRECTION/13_FINAL_RUNTIME_STATE.md) | [`41_final_profile_and_empty_props.txt`](V20_4_REFRESH_RUNTIME_CORRECTION/raw/device_run_20260831170720/41_final_profile_and_empty_props.txt) |

约170ms kill-switch统计是host-observed ADB端到端收敛，不是native callback/physical latency。100-roundtrip harness证明state/apply event order和采样内120为0，不宣称每条edge physical settle；五档physical另有独立证据。AppRequest为`sharedNoToken`，只能证明本地ownership释放和traversal handoff，不虚构同步clear callback。

## V20.4 Uperf Pre-Flash 最小证据

最终静态决策：[`V20_4_UPERF_DECISION.md`](V20_4_UPERF_DECISION.md)

| Gate | 当前结论 | 关键数字 | 最小报告 | 最小 raw |
| --- | --- | --- | --- | --- |
| Upstream/binary | PASS | v1.0.6；ZIP `00b192…fcc`；upstream=production Uperf `f12657…49d8` | [`01_UPSTREAM_AUDIT.md`](V20_4_UPERF_ARCHITECTURE_REBASE/01_UPSTREAM_AUDIT.md) | [`UPSTREAM_AUDIT.json`](V20_4_UPERF_ARCHITECTURE_REBASE/raw/upstream_audit/UPSTREAM_AUDIT.json) |
| Host/CI/init | PASS | Uperf31/31；Refresh39/39；V20.3B5/5；CI `33468476491` success；official init exit0 | [`08_HOST_TESTS.md`](V20_4_UPERF_ARCHITECTURE_REBASE/08_HOST_TESTS.md) | [`uperf_host_tests.txt`](V20_4_UPERF_ARCHITECTURE_REBASE/raw/host_tests/uperf_host_tests.txt), [`ci_run_provenance.json`](V20_4_UPERF_ARCHITECTURE_REBASE/raw/build_20260901120647/ci_run_provenance.json) |
| Build/reverse | PASS | RunId `20260901120647`；marker62；vendor APK17；`super` `4eab12…8a06` | [`09_BUILD_VERIFY.md`](V20_4_UPERF_ARCHITECTURE_REBASE/09_BUILD_VERIFY.md) | [`build_result.json`](V20_4_UPERF_ARCHITECTURE_REBASE/raw/build_20260901120647/build_result.json), [`final_super_verifier.log`](V20_4_UPERF_ARCHITECTURE_REBASE/raw/build_20260901120647/final_super_verifier.log) |
| Final ART | PASS | final services `f7575f…c82fd`；DEX/GATE RC0；stdout/stderr empty；PID2714 stable | [`09_BUILD_VERIFY.md`](V20_4_UPERF_ARCHITECTURE_REBASE/09_BUILD_VERIFY.md) | [`art_gate_transcript.txt`](V20_4_UPERF_ARCHITECTURE_REBASE/raw/final_artifact_gate_20260901120647/art_gate_transcript.txt) |
| Final CIL | PASS | 8/8 host/device SHA match；SECILC/GATE RC0；stderr empty；compiled policy1,256,792B | [`09_BUILD_VERIFY.md`](V20_4_UPERF_ARCHITECTURE_REBASE/09_BUILD_VERIFY.md) | [`policy_gate_transcript.txt`](V20_4_UPERF_ARCHITECTURE_REBASE/raw/final_policy_gate_20260901120647/policy_gate_transcript.txt), [`final_cil_input_manifest.txt`](V20_4_UPERF_ARCHITECTURE_REBASE/raw/final_policy_gate_20260901120647/final_cil_input_manifest.txt) |
| Flash/device validation | NOT STARTED | `flashed=false`; no install/reboot/partition write | [`10_DEVICE_TEST_PLAN.md`](V20_4_UPERF_ARCHITECTURE_REBASE/10_DEVICE_TEST_PLAN.md) | none |

## 被替代 lineage

- RunId `20260831134511`：Boot PASS，但kill switch不收敛、App-to-App intermediate120与两个OEM package误分类，device=`PARTIAL`。历史报告：[`08_DEVICE_RESULTS.md`](V20_4_REFRESH_CORRECTNESS/08_DEVICE_RESULTS.md)。
- RunId `20260831104317`：刷后ART `VerifyError`，Boot Gate FAIL并完成V20.3B恢复。
- RunId `20260831094239`：刷前因event-order漏洞被拒绝。

以上历史FAIL不得覆盖当前RunId的PASS，也不得再次刷写旧package。

## 显式非阻断边界

```text
APP_UI_TX10=NOT_EXECUTED
SIGNED_APP_TX10_DEVICE_PATH_NOT_AVAILABLE
UDFPS_LOCAL_VOTE_RUNTIME=NOT_OBSERVED
FAULT_INJECTION_DEVICE_PATH=NOT_EXECUTED
SECONDARY_USER_EXTERNAL_DISPLAY=NOT_VALIDATED
```

这些边界没有变成PASS；它们只是不阻止当前TB321FU/default-display/active-user targeted work package关闭。`displayVote=adaptiveRender`，target120时静止physical可能降到60；当前结果不等于120 hard-lock。

最终冻结包：`V20_4_REFRESH_RUNTIME_DEVICE_RESULTS.rar`，完整性见同名`.sha256` sidecar。需要复核raw数字时才解包。
