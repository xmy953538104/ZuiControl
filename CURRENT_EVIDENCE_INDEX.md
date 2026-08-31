# ZuiControl Current Evidence Index

更新时间：2026-08-31  
最近关闭的完整基线：V20.3B / RunId `20260830181816` / source `30fe138a7ef531aeffbcf951e9113f4ae0d17cfe`

当前设备：V20.4 Refresh Correctness fixed RunId `20260831134511` / source `3c5cd809d5465828fe14356cbd079d45d00347b7` / CI `33361319072` / host 27/27 + 5/5 / final-super `marker_count=48` PASS / final-artifact ART PASS / Boot Gate PASS / device validation **PARTIAL**

本文件是未来会话的默认证据入口。先读结论和最小证据；只有数字受到质疑时，才打开对应 raw。不要递归扫描 `D:\3.VScode\Mi\ZuiControl_Archive\`。

V20.3B 阶段已关闭；daemon-retirement architecture = PASS。历史 `PARTIAL / HOLD` 不再是进入 V20.4 的 gate；rapid Uperf storm与 T8 request-ID仍是 carry-forward。V20.4 foreground-only 主路径已有真机PASS证据，但三个runtime blocker使整体只能为PARTIAL。

Archive 根：[`../ZuiControl_Archive/README.md`](../ZuiControl_Archive/README.md)

## 最小证据映射

| 项目 | 结论 | 关键数字 | 最小报告 | 必要 raw |
| --- | --- | --- | --- | --- |
| Daemon retirement | PASS | persistent `zui_controld` service/start=0；idle persistent/request process row=0 | [`V20_3B_DECISION.md`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/V20_3B_DECISION.md), [`09_IDLE_NO_DAEMON.md`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/09_IDLE_NO_DAEMON.md) | [`services_verified.txt`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/final_runtime_post_reboot/services_verified.txt), [`perfetto_query_results_compatible_replay.txt`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_idle_final2/08_idle_no_daemon/perfetto_query_results_compatible_replay.txt) |
| OEM fence | PASS | active 20/20 最终 stopped；host-observed P95≈120ms；500 quiet samples 无 storm；inactive 60s 同 PID/starttime running | [`02_INIT_NATIVE_OEM_FENCE.md`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/02_INIT_NATIVE_OEM_FENCE.md) | [`matrix.tsv`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_oem_fence_final2/03_oem_fence/matrix.tsv), [`storm_summary.txt`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_oem_fence_final2/03_oem_fence/storm_summary.txt), [`timeline.tsv`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_oem_inactive_60/03_oem_inactive_60/timeline.tsv) |
| Uperf normal recovery | PASS | 10/10；mean 3046ms；P50 3380ms；P95/max 5500ms | [`03_COMPONENT_RECOVERY_10X.md`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/03_COMPONENT_RECOVERY_10X.md) | [`uperf_10x_and_storm_partial_recovered.tsv`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_recovery/04_component_recovery/uperf_10x_and_storm_partial_recovered.tsv) |
| Uperf rapid storm | PARTIAL / carry forward | #1 PASS；#2 未满足稳定窗口并触发 `sys.init.updatable_crashing=1`；#3 未执行 | [`03_COMPONENT_RECOVERY_10X.md`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/03_COMPONENT_RECOVERY_10X.md) | [`RECOVERY_PHASE_FAILURE_NOTE.md`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_recovery/04_component_recovery/RECOVERY_PHASE_FAILURE_NOTE.md), [`post_failure_dmesg.txt`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_recovery/04_component_recovery/post_failure_dmesg.txt) |
| asoulOpt recovery | PASS | normal 10/10，mean 98ms，P95/max 140ms；bounded storm 3/3；`stop_asoul` 60s 保持 stopped | [`03_COMPONENT_RECOVERY_10X.md`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/03_COMPONENT_RECOVERY_10X.md) | [`asoul_10x_and_storm.tsv`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_recovery_asoul/04_component_recovery/asoul_10x_and_storm.tsv), [`stop timeline.tsv`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_stop_asoul_final/05_stop_asoul_60s/timeline.tsv) |
| Command latency | PASS with performance backlog | 50/50；T0→T9 P95 `1022.100ms`；T4→T5 mean `574.2ms`；action mean `12.4ms` | [`07_COMMAND_LATENCY_BREAKDOWN.md`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/07_COMMAND_LATENCY_BREAKDOWN.md) | [`latency_summary.json`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_latency_final2/02_latency_50/analysis/latency_summary.json), [`latency_samples.csv`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_latency_final2/02_latency_50/analysis/latency_samples.csv), [`controld_log.txt`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_latency_final2/02_latency_50/controld_log.txt) |
| Idle overhead | PASS | 61.13s ZuiControl worker `0.0000%` 单核；89.988s 周期 Settings get/put、heartbeat、ZuiControl shell fork=0 | [`09_IDLE_NO_DAEMON.md`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/09_IDLE_NO_DAEMON.md) | [`idle_60s_analysis.txt`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_idle_final2/08_idle_no_daemon/idle_60s_analysis.txt), [`idle_90s.pftrace`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_idle_final2/08_idle_no_daemon/idle_90s.pftrace) |
| Boot/App regression | PASS | 删除 daemon grant/appops/FGS 兜底后，BootReceiver、QuickService、通知、QS、privileged permissions 和 cold launch 通过 | [`12_APP_BOOT_PERMISSION_REGRESSION.md`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/12_APP_BOOT_PERMISSION_REGRESSION.md) | [`01_no_app_5m_app_services.txt`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_20260830_192550_run_20260830181816/01_no_app_5m_app_services.txt), [`01_no_app_5m_qs_tiles.txt`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_20260830_192550_run_20260830181816/01_no_app_5m_qs_tiles.txt), [`03_on_demand_binder_status_unlocked.txt`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_20260830_192550_run_20260830181816/03_on_demand_binder_status_unlocked.txt) |
| Transaction security | PASS | unauthorized Binder/property denied；wrong ID/SHA/tamper fail closed；duplicate action once；crash/force-stop/reboot pending 收敛；receipt 0664→0600 migration | [`08_SECURITY_REGRESSION.md`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/08_SECURITY_REGRESSION.md), [`06_TRANSACTION_FIXES.md`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/06_TRANSACTION_FIXES.md) | [`unauthorized_binder.txt`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_security_final4/06_security/unauthorized_binder.txt), [`transaction_matrix/`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_security_final4/06_security/transaction_matrix/) |

## V20.4 Refresh Correctness 直接证据

当前结论：source/host/CI/ROM build/final-super/final-artifact ART/Boot Hard Gate = PASS；device validation = **PARTIAL**。foreground-only主路径与大部分矩阵通过，但kill switch未收敛、真实App-to-App handoff出现intermediate default120、两个vendor window误分类。不得用host model PASS覆盖device FAIL。

最小当前证据：

- [`08_DEVICE_RESULTS.md`](V20_4_REFRESH_CORRECTNESS/08_DEVICE_RESULTS.md)
- [`05_HOST_TESTS.md`](V20_4_REFRESH_CORRECTNESS/05_HOST_TESTS.md)
- [`06_BUILD_VERIFY.md`](V20_4_REFRESH_CORRECTNESS/06_BUILD_VERIFY.md)
- [`build_result.json`](V20_4_REFRESH_CORRECTNESS/raw/build_20260831134511/build_result.json)
- [`ci_run_provenance.json`](V20_4_REFRESH_CORRECTNESS/raw/build_20260831134511/ci_run_provenance.json)
- [`candidate_sha256.txt`](V20_4_REFRESH_CORRECTNESS/raw/build_20260831134511/candidate_sha256.txt)
- [`final_super_verifier.log`](V20_4_REFRESH_CORRECTNESS/raw/build_20260831134511/final_super_verifier.log)
- [`PREFLASH_GATE_FIXED_20260831134511.md`](../work/v20_4_device_validation_20260831114341/PREFLASH_GATE_FIXED_20260831134511.md)
- [`phase2_180s_observation_summary.txt`](../work/v20_4_device_validation_20260831114341/03_fixed_candidate_flash_20260831_142025/phase2_180s_observation_summary.txt)

最小失败证据：

| Gate | Status | Key evidence |
| --- | --- | --- |
| Stable/rapid kill switch | `FAIL_NOT_CONVERGED` | [`refresh_disable_latency.csv`](../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m8_kill_switch/refresh_disable_latency.csv), [`global_disable_no_convergence.csv`](../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m8_kill_switch/global_disable_no_convergence.csv), [`rapid_toggle_ending_disabled.csv`](../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m8_kill_switch/rapid_toggle_ending_disabled.csv) |
| Activity/window order | `FAIL_INTERMEDIATE_DEFAULT` | [`focus_timeline.csv`](../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m11_focus_order/focus_timeline.csv), [`focus_state_changes.csv`](../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m11_focus_order/focus_state_changes.csv) |
| Vendor overlay classification | `FAIL_CLASSIFICATION` | [`split_selector_zui.txt`](../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m13_split/split_selector_zui.txt), [`split_active_zui.txt`](../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m13_split/split_active_zui.txt) |

边界：UDFPS=`NOT_OBSERVED`；fault injection、kill-switch下游release/reenable与disabled idle=`NOT_EXECUTED`；secondary user/external display=`NOT_VALIDATED`。Enabled idle最小证据：[`idle_60s_analysis.txt`](../work/v20_4_device_validation_20260831114341/06_idle_enabled/idle_60s_analysis.txt)、[`perfetto_query_results.txt`](../work/v20_4_device_validation_20260831114341/06_idle_enabled/perfetto_query_results.txt)。

被替代候选 `20260831094239` 只保留为审计历史：它的 host 19/19 与 `marker_count=44` 是当时构建事实，但未覆盖 Activity/window event-order 漏洞，人工 Gate 已拒绝刷写。对应 raw 位于 [`raw/build_20260831094239/`](V20_4_REFRESH_CORRECTNESS/raw/build_20260831094239/)；未来默认不得把它当作当前候选或当前源码证据。

RunId `20260831104317` 同样只保留为历史：它的host/static marker结果不能证明bootability，实际刷入后因ART `VerifyError` Boot Gate FAIL并完成V20.3B恢复。历史诊断：[`BOOT_GATE_FAILURE_DIAGNOSIS.md`](../work/v20_4_device_validation_20260831114341/BOOT_GATE_FAILURE_DIAGNOSIS.md)。旧pre-flash decision的provenance仍保留，但已被fixed RunId与本device result取代。

V20.3B 历史源码审计确认：ZuiControl 曾通过 `controlPanel` 分支拥有独立 profile/apply语义，并与 current/applied/config target混在一起。当前产品语义是 foreground-only：ZuiControl/SystemUI/IME等真正前台时 physical target为 neutral/default 120；`lastNonTransientScenePackage`只保留为配置对象。历史证据只用于证明字段分裂与旧特判，不再用于主张 transient应继承业务 App Hz。

最小 raw：

- [`transient_before_settings.txt`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_supplement_final/transient_before_settings.txt)
- [`transient_zuicontrol_open.txt`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_supplement_final/transient_zuicontrol_open.txt)
- [`transient_qs_open.txt`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_supplement_final/transient_qs_open.txt)
- [`transient_after_back.txt`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_supplement_final/transient_after_back.txt)
- [`TRANSIENT_CLASSIFICATION.txt`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_supplement_final/TRANSIENT_CLASSIFICATION.txt)

这些文件只证明 V20.3B 默认 120场景下的字段分裂。新的 foreground-only非120往返已由fixed candidate真机证明；kill switch release/handoff则因service未进入disabled而未能执行，结论仍不得提升。

## Final runtime 边界

最终快照为 Launcher / target 120 / actual 120 / balance / Uperf+asoulOpt running：[`13_FINAL_RUNTIME_STATE.md`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/13_FINAL_RUNTIME_STATE.md)。这只是最终现场，不证明 120 全时 hard-lock；已分类 AVC 也不等于 AVC=0。

完整冻结包：[`V20_3B_DEVICE_RESULTS.rar`](../ZuiControl_Archive/packages/V20_3B_DEVICE_RESULTS.rar)，SHA-256 `632fa29933b79ed97cd236eb67c2c874c6c96e0c3cd5d5af389601a35961262a`。只有需要验证 archive 完整性或重算未索引数字时才解包。
