# V20.4 Refresh Correctness Device Results

日期：2026-08-31

设备：TB321FU / ZUI 16.1.11.072

最终刷入候选：RunId `20260831134511` / source `3c5cd809d5465828fe14356cbd079d45d00347b7` / CI `33361319072`

## Decision

固定候选通过 final-artifact ART gate 和 Boot Hard Gate，foreground-only 主语义、五档刷新率、IME/Resolver、QS 编辑目标、freeform/split/PiP、dedup、profile 拒绝、peak observer、enabled idle 与 Binder 安全均取得有效真机证据。但 kill switch 不收敛、App-to-App 焦点切换产生 intermediate default 120，以及两个 Lenovo/ZUI vendor window 被误分类为业务 App。因此本工作包真机结论是 **PARTIAL**，不得写成 PASS。

```text
V20_4_REFRESH_SOURCE_HOST_BUILD=PASS
V20_4_REFRESH_FIXED_CANDIDATE_BOOT=PASS
V20_4_REFRESH_DEVICE_VALIDATION=PARTIAL
V20_4_REFRESH_KILL_SWITCH_DEVICE=FAIL_NOT_CONVERGED
V20_4_REFRESH_ACTIVITY_WINDOW_ORDER=FAIL_INTERMEDIATE_DEFAULT
V20_4_REFRESH_VENDOR_OVERLAY=FAIL_CLASSIFICATION
UDFPS_LOCAL_VOTE_RUNTIME=NOT_OBSERVED
FAULT_INJECTION_DEVICE_PATH=NOT_EXECUTED
SECONDARY_USER_EXTERNAL_DISPLAY=NOT_VALIDATED
```

## Candidate identity and lineage

| Artifact | SHA-256 |
| --- | --- |
| `super.img` | `4f01c64d8a3a5860c34967d944510f3768f4e6748bb843eef0345a5c6685800d` |
| `boot.img` | `e7e85b5cd2806b8c27adf4925e05ee169072a79a43502effc34c97fb27ee8371` |
| `vbmeta_system.img` | `fe007d5d1d298d773bc3a41879f9995984491abfd51f4608b3044a5cde549259` |
| `vbmeta.img` | `c1f4ea68ea52bae62e464ddc245dadd740569ba3ac3376ee5d23a40204a744f7` |
| release/system APK | `86348f7767cf9d0651e226af947c3de60f52a0268adc519fc3a90521f7037e82` |
| final-super `services.jar` | `389ac1cff8e79705a8dd9e5220f3c70ccef5c84f0681ac3c6c993f81ddcd24ff` |

RunId `20260831104317` 通过了当时的 static/final-super marker gate，但刷入后因 `DisplayContent` smali register type merge 触发 ART `VerifyError`，Boot Gate FAIL；设备随后按已验证流程恢复 V20.3B。固定 RunId `20260831134511` 在刷前对最终 super 反解出的 `services.jar` 执行目标设备 dex2oat/ART verifier，得到 `DEX_RC=0`、`GATE_RC=0`，再经授权按 fixed-seven allowlist 刷入。最小证据：[`BOOT_GATE_FAILURE_DIAGNOSIS.md`](../../work/v20_4_device_validation_20260831114341/BOOT_GATE_FAILURE_DIAGNOSIS.md)、[`PREFLASH_GATE_FIXED_20260831134511.md`](../../work/v20_4_device_validation_20260831114341/PREFLASH_GATE_FIXED_20260831134511.md)、[`build_result.json`](raw/build_20260831134511/build_result.json)。

## Boot Hard Gate

`sys.boot_completed=1` 后继续观察 `188.563s`，共 `19` 个样本；system_server 只有 PID `2635` / starttime `994`，Binder 全程存在，SELinux 全程 Enforcing，boot animation 全程 stopped。当前 boot 的 `VerifyError`、`FATAL EXCEPTION IN SYSTEM PROCESS`、system_server restart、RescueParty 计数均为 0。历史 Dropbox 中仍保留旧失败候选的 12:02–12:07 crash 条目，不属于固定候选新 crash。证据：[`phase2_180s_observation_summary.txt`](../../work/v20_4_device_validation_20260831114341/03_fixed_candidate_flash_20260831_142025/phase2_180s_observation_summary.txt)、[`phase2_log_marker_counts.csv`](../../work/v20_4_device_validation_20260831114341/03_fixed_candidate_flash_20260831_142025/phase2_log_marker_counts.csv)、[`phase2_live_fixed_seven_sha256.txt`](../../work/v20_4_device_validation_20260831114341/03_fixed_candidate_flash_20260831_142025/phase2_live_fixed_seven_sha256.txt)。

## Runtime results that passed

| Area | Observed result | Minimal evidence |
| --- | --- | --- |
| Foreground-only / config target | Calculator `60 → ZuiControl 120 → 60`; Notes `90 → SystemUI 120 → 90`. ZuiControl 编辑 Notes `144/165` 与 QS 144 时，transient 屏保持 120，只保存上一业务 App，返回后才应用新 profile；没有 transient profile。 | [`m1_02_zuicontrol_default120`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m1_02_zuicontrol_default120/zui_control.txt), [`m4_02_qs_after_click_144_saved_still120`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m4_02_qs_after_click_144_saved_still120/zui_control.txt) |
| Five-rate matrix | Notes 在 `60/90/120/144/165` 下，每个业务场景的 target/applied 与受刺激 physical 精确命中；进入 ZuiControl 均为 120，返回恢复。120 idle 仍允许 adaptive render 下探。 | [`summary.csv`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m5_five_rate_matrix/summary.csv) |
| IME / Resolver | Settings90 打开真实 Sogou IME 时 raw=IME/transient/default120，关闭恢复 Settings90；真实 `com.zui.resolver` 为 120，关闭恢复 Notes90。 | [`ime_open_verified_zui.txt`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m6_ime/ime_open_verified_zui.txt), [`chooser_open_zui.txt`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m7_resolver_chooser/chooser_open_zui.txt) |
| Profile negative checks | 75Hz 及 SystemUI、ZuiControl、Sogou IME、Resolver 显式 profile 写入均被拒绝；profile 文件前后 hash 不变。 | [`before_profiles.txt`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m9_profile_negative/before_profiles.txt), [`after_profiles.txt`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m9_profile_negative/after_profiles.txt) |
| Dedup / churn | 120↔120：200 个真实 edge，apply `+0`、skip `+400`；90↔120：200 edge，apply `+200`、skip `+200`。SystemUI/QS 只统计实际观察到的 101 cycles / 202 edge，apply `+202`。 | [`same120_summary.csv`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m10_dedup/same120_summary.csv), [`target90_summary.csv`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m10_dedup/target90_summary.csv) |
| Multi-window / PiP | freeform 三个焦点精确为 Calculator60/ZuiControl120/Notes90，50 cycles/100 edge/apply `+100`；split 为 60/90，50/100/`+100`；真实 pinned PiP 不夺走底层 Notes90 焦点。 | [`freeform focus`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m12_freeform/focus_summary.csv), [`freeform stress`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m12_freeform/stress_summary.csv), [`split focus`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m13_split/focus_summary.csv), [`split stress`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m13_split/stress_summary.csv), [`PiP over Notes`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m14_pip/pip_over_notes_zui.txt) |
| Peak observer | Notes90 期间外部把 peak 改为165；首个观察样本约 `198.563ms` 已修回120，`refreshApplyCount` 不增加，min 未被写入。 | [`repair_timeline.csv`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m17_peak_observer/repair_timeline.csv) |
| Binder/user boundary | shell 的敏感 TX3/TX7/TX12 被拒绝；SYSTEM_UID TX3 允许。设备只有 user 0。 | [`shell_tx7.txt`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m16_security_users/shell_tx7.txt), [`system_tx3.txt`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m16_security_users/system_tx3.txt), [`users.txt`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m16_security_users/users.txt) |

## Runtime failures

### 1. Kill switch did not converge

在 Notes90 前台，`persist.zui_control.refresh.disable=1` 已可由外部稳定读回，但 30/30 样本直到 `1360.697ms` 仍为 `refreshDisabled=false`、mask 0、render/peak/AppRequest owned；继续等待总计超过 6 秒仍无变化。`persist.zui_control.disable=1` 的 40/40 样本直到 `5148.825ms` 同样保持 mask 0/false/owned。rapid toggle 最终稳定 disabled 后在 `5094.098ms` 仍为 mask 0、disabled false、render owned。两项 property 均已恢复 0，Uperf/asoulOpt 未被误停。

`refresh_disable_latency_summary.txt` 中的 `first_observed_disabled_ms=0` 是 parser 对 null 的错误输出，**不得引用**。由于 service 从未进入 disabled，AppRequest/vote/peak release、enable rebuild、disable 时 external-CAS 保护与 disabled idle 都是 downstream `NOT_EXECUTED`，不能写成 FAIL 或 PASS。证据：[`refresh_disable_latency.csv`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m8_kill_switch/refresh_disable_latency.csv)、[`global_disable_no_convergence.csv`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m8_kill_switch/global_disable_no_convergence.csv)、[`rapid_toggle_ending_disabled.csv`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m8_kill_switch/rapid_toggle_ending_disabled.csv)。

### 2. App-to-App transition inserted default 120

Notes90 与 Calculator60 交替启动 10 次，`refreshApplyCount` 从 `467` 到 `487`，每次恰好 `+2`。8/10 次明确采到 `raw/nonIme=""`、Activity 已是目的 App、current 仍为来源 App、desired/default120，随后才应用目的 60/90；另 2 次在约 45ms 采样间隔内直接跳 `+2`。这违反“直接应用目的 profile、无 intermediate default”的验收条件。当前证据指向 WindowManager 临时 `mCurrentFocus == null` 被当成 authoritative empty owner，而不是 Activity metadata apply。证据：[`focus_timeline.csv`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m11_focus_order/focus_timeline.csv)、[`focus_state_changes.csv`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m11_focus_order/focus_state_changes.csv)。

### 3. Vendor overlay classification failed

真实 split-screen 流程中的 `com.lenovo.screensplit` 与 `com.zui.freeform.sidebar` 均被记录为 `rawFocusTransient=false`，并覆盖 current/last/editable。它们当时只因未配置而碰巧使用 default120；该行为会污染业务配置 target，不能算 PASS。证据：[`split_selector_zui.txt`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m13_split/split_selector_zui.txt)、[`split_active_zui.txt`](../../work/v20_4_device_validation_20260831114341/04_refresh_matrix_20260831_143846/m13_split/split_active_zui.txt)。

## Idle, boundaries and final restore

Enabled idle 在 5 分钟 settle 后采集 `60.54s` `/proc`：ZuiControl worker self/children tick 0、context switch 0、persistent daemon/oneshot row 0，system_server 与全部关键 role PID/starttime稳定，apply/skip 均不增加。`89.973s` Perfetto 中 ZuiControl worker sched slice 0，六项 daemon/request/Settings/health/periodic-Zui-shell row 为 0，trace packet loss/drop 为 0；但 `ftrace_setup_errors=2`，所以短命进程结果只表示 trace 可见范围内为 0。trace 中仍可见 Uperf wrapper 的 5 秒自检 grep，这是当前明确保留的执行面 self-check，不是 refresh polling。证据：[`idle_60s_analysis.txt`](../../work/v20_4_device_validation_20260831114341/06_idle_enabled/idle_60s_analysis.txt)、[`perfetto_query_results.txt`](../../work/v20_4_device_validation_20260831114341/06_idle_enabled/perfetto_query_results.txt)、[`perfetto_zui_delta.txt`](../../work/v20_4_device_validation_20260831114341/06_idle_enabled/perfetto_zui_delta.txt)。disabled idle 因 kill switch 未进入 disabled 而未执行。

UDFPS 为 `NOT_OBSERVED`：设备没有 fingerprint service，biometric `Sensors:` 为空。fault injection 未执行；secondary user 不存在，external display未验证；PermissionController/PackageInstaller 实际路径未完整覆盖。SELinux 为 Enforcing，但 dmesg 含 OEM/system_server 通用 capability 与 vendor perf denials，不能写成 AVC=0；没有证据把这些 denial 归因于本次 refresh 功能失败。

最终现场已恢复：两项 disable property=0，测试 profile 全部删除，仅保留 default120；Launcher foreground，refresh enabled，Uperf balance/running，asoulOpt running，Binder 存在，system_server PID 仍为2635。证据：[`restored_zui.txt`](../../work/v20_4_device_validation_20260831114341/05_final_restore/restored_zui.txt)、[`restored_profiles.txt`](../../work/v20_4_device_validation_20260831114341/05_final_restore/restored_profiles.txt)、[`final_summary.txt`](../../work/v20_4_device_validation_20260831114341/07_final_runtime/final_summary.txt)。
