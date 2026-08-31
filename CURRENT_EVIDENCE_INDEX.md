# ZuiControl Current Evidence Index

更新时间：2026-08-31  
当前真机冻结基线：V20.3B / RunId `20260830181816` / source `30fe138a7ef531aeffbcf951e9113f4ae0d17cfe`

当前未刷机候选：V20.4 Refresh Correctness / RunId `20260831094239` / source `3865cf9c99cb89a8df2b705b9b3dbb2711b311ec` / CI `33348269219` / final-super PASS / device PENDING

本文件是未来会话的默认证据入口。先读结论和最小证据；只有数字受到质疑时，才打开对应 raw。不要递归扫描 `D:\3.VScode\Mi\ZuiControl_Archive\`。

V20.3B 阶段已关闭；daemon-retirement architecture = PASS。历史 `PARTIAL / HOLD` 不再是进入 V20.4 的 gate；rapid Uperf storm与 T8 request-ID仍是 carry-forward。历史 transient缺陷已进入 V20.4 candidate，但未刷机前不能改写成真机 PASS。

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

当前结论：source/host/CI/ROM build/final-super = PASS；device validation = PENDING。foreground-only语义、configuration/physical两轴、kill-switch property edge、desired/attempted/applied/physical状态和 owner-safe cleanup已进入 candidate。`controlPanel` marker在最终 super中必须不存在。shared AppRequest没有 owner token，只能请求 WindowManager traversal并观察 handoff，因此完整释放仍是明确 device gate。

最小当前证据：

- [`V20_4_REFRESH_DECISION.md`](V20_4_REFRESH_DECISION.md)
- [`05_HOST_TESTS.md`](V20_4_REFRESH_CORRECTNESS/05_HOST_TESTS.md)
- [`06_BUILD_VERIFY.md`](V20_4_REFRESH_CORRECTNESS/06_BUILD_VERIFY.md)
- [`build_result.json`](V20_4_REFRESH_CORRECTNESS/raw/build_20260831094239/build_result.json)
- [`ci_run_provenance.json`](V20_4_REFRESH_CORRECTNESS/raw/build_20260831094239/ci_run_provenance.json)
- [`candidate_sha256.txt`](V20_4_REFRESH_CORRECTNESS/raw/build_20260831094239/candidate_sha256.txt)
- [`final_super_verifier.log`](V20_4_REFRESH_CORRECTNESS/raw/build_20260831094239/final_super_verifier.log)

V20.3B 历史源码审计确认：ZuiControl 曾通过 `controlPanel` 分支拥有独立 profile/apply语义，并与 current/applied/config target混在一起。当前产品语义是 foreground-only：ZuiControl/SystemUI/IME等真正前台时 physical target为 neutral/default 120；`lastNonTransientScenePackage`只保留为配置对象。历史证据只用于证明字段分裂与旧特判，不再用于主张 transient应继承业务 App Hz。

最小 raw：

- [`transient_before_settings.txt`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_supplement_final/transient_before_settings.txt)
- [`transient_zuicontrol_open.txt`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_supplement_final/transient_zuicontrol_open.txt)
- [`transient_qs_open.txt`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_supplement_final/transient_qs_open.txt)
- [`transient_after_back.txt`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_supplement_final/transient_after_back.txt)
- [`TRANSIENT_CLASSIFICATION.txt`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/raw/device_validation_20260830_194022/phase_supplement_final/TRANSIENT_CLASSIFICATION.txt)

这些文件只证明 V20.3B 默认 120场景下的字段分裂；它们不验证新的 foreground-only非 120往返语义。60/90/144/165 → transient 120 → 返回恢复、kill switch release/handoff与 physical Hz仍需 V20.4 candidate真机矩阵。

## Final runtime 边界

最终快照为 Launcher / target 120 / actual 120 / balance / Uperf+asoulOpt running：[`13_FINAL_RUNTIME_STATE.md`](../ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/13_FINAL_RUNTIME_STATE.md)。这只是最终现场，不证明 120 全时 hard-lock；已分类 AVC 也不等于 AVC=0。

完整冻结包：[`V20_3B_DEVICE_RESULTS.rar`](../ZuiControl_Archive/packages/V20_3B_DEVICE_RESULTS.rar)，SHA-256 `632fa29933b79ed97cd236eb67c2c874c6c96e0c3cd5d5af389601a35961262a`。只有需要验证 archive 完整性或重算未索引数字时才解包。
