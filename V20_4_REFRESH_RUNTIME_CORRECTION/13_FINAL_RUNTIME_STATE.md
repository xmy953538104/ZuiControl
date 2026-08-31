# Final Runtime State

## 最终现场

```text
RUN_ID=20260831170720
FINAL_RUNTIME_STATE=PASS_RESTORED_BASELINE
FINAL_BOOT_ID=7ee8cab7-3cff-405d-a1f0-3132962f9075
SYSTEM_SERVER_PID=2714
SYS_BOOT_COMPLETED=1
ZUI_CONTROL_BINDER=FOUND
SELINUX=ENFORCING
APP_UI_TX10=NOT_EXECUTED
SIGNED_APP_TX10_DEVICE_PATH_NOT_AVAILABLE
NO_MORE_REBOOT_AFTER_ENABLED_BOOT=YES
```

最终设备停在 Launcher，真实前台为 `com.zui.launcher`，refresh target/applied/physical=`120/120/120`，`refreshDisableMask=0`。这是 final snapshot；当前 vote 仍为 `adaptiveRender`，不能把该120样本扩大为120 hard-lock证明。

## 基线恢复清单

| 项目 | 最终值 |
|---|---|
| global / refresh disable | `0 / 0` |
| profile | 仅 `default|0|120|0|DISPLAY_ONLY` |
| profile file | `64B`，`system:system` mode `0600`，`system_data_file` |
| profile SHA-256 | `7410c52143590460fc0350992785a2ffe7d1b4f833c385a797a6a44087437221` |
| render vote | owned，120Hz |
| AppRequest | owned，shared-no-token active |
| peak bridge | 120Hz无需持有，`peakBridgeOwned=false` |
| Uperf | balance / running |
| asoulOpt | running |
| scheduler | active，health ok |
| persistent `zui_controld` | retired / 无进程 |
| factory-reset property | empty |
| request-service property | empty |

profile 内容、大小、hash以及空property均由最终独立采集确认：[`41_final_profile_and_empty_props.txt`](raw/device_run_20260831170720/41_final_profile_and_empty_props.txt)。最终完整 service/runtime 快照见 [`41_final_runtime_state.txt`](raw/device_run_20260831170720/41_final_runtime_state.txt)。

## 启动、稳定性与制品身份

- final boot 的 system_server PID=`2714`，`sys.boot_completed=1`，`zui_control` Binder found，SELinux Enforcing；
- live `/system/framework/services.jar` SHA-256=`0b7bb46c644c5559173f72b06579131e82597366fdcc114d3fb30aabb544e8a3`，与本候选 Boot Gate 采集一致；
- `/data` mounted，最终快照可用空间约 `406G`；
- final current-boot 汇总为 all AVC=`0`、candidate-relevant AVC=`0`、crash/rescue marker=`0`；
- crash buffer 文件并非空文件，保留两条旧 init SIGABRT 记录；它们不应被改写成当前 system_server crash。当前 final runtime marker扫描为0。

证据：[`41_final_runtime_state.txt`](raw/device_run_20260831170720/41_final_runtime_state.txt)、[`41_final_dmesg.txt`](raw/device_run_20260831170720/41_final_dmesg.txt)、[`41_final_logcat_all.txt`](raw/device_run_20260831170720/41_final_logcat_all.txt)、[`41_final_logcat_crash.txt`](raw/device_run_20260831170720/41_final_logcat_crash.txt)。

## Reboot 边界

本轮 persistence gate只执行两次获批的受控重启：一次以refresh disable=1验证disabled boot，一次恢复property=0后验证enabled boot。disabled boot首个有效state为mask2且未取得refresh ownership；enabled boot首个有效state为mask0，Launcher出现后正常取得default120 ownership。最终 boot id 为 `7ee8cab7-3cff-405d-a1f0-3132962f9075`，此后没有再次reboot。

证据：[`35_disabled_boot_persistence.txt`](raw/device_run_20260831170720/35_disabled_boot_persistence.txt)、[`37_enabled_boot_persistence.txt`](raw/device_run_20260831170720/37_enabled_boot_persistence.txt)、[`35_37_boot_persistence_analysis.txt`](raw/device_run_20260831170720/35_37_boot_persistence_analysis.txt)。

## TX10 设备边界

release UI没有TX10控件，现场不存在可安全调用TX10的现成signed-App Manager/API入口。遵守“不临时增加测试App/代码、不用shell Binder冒充签名App”的约束，本轮保持：

```text
APP_UI_TX10=NOT_EXECUTED
SIGNED_APP_TX10_DEVICE_PATH_NOT_AVAILABLE
TX10_UNKNOWN_MODULE_DEVICE=NOT_EXECUTED
TX10_UNAUTHORIZED_CALLER_DEVICE=NOT_EXECUTED
```

因此 runtime correction 的 raw-property kill-switch真机PASS不能扩大成TX10 device PASS；TX10仍只有source/host/final-artifact安全证明。

## 交付现场边界

最终 profile、properties、Launcher/default120、Uperf/asoulOpt和scheduler health均已恢复；空闲观察结束后没有再次改变设备状态，也没有更多reboot。本文件只记录最终现场与本次定向gate，不替代长期soak、UDFPS local-vote、secondary user、external display或TX10 device测试。
