# Boot Hard Gate

## 结论

```text
RUN_ID=20260831170720
FIXED_SEVEN_FLASH=PASS
BOOT_HARD_GATE=PASS
```

本轮只刷入获批目录 `D:\3.VScode\Mi\flash\ZuiControl_9008_V20_4_RUNTIME_20260831170720`，未修改 userdata、GPT、persist、FRP、modemst，未使用 patch XML。

## 刷前与刷机

刷前设备为 TB321FU / ZUI 16.1.11.072，`sys.boot_completed=1`、`zui_control` Binder found、SELinux Enforcing，`sys.attempting_factory_reset` 为空。包内镜像与授权值一致：

| 镜像 | SHA-256 |
|---|---|
| `super.img` | `dc4fd4bc3e288aa26e80cf382db62211f488e1b74c7cb8767b2d3f9f5f2c269d` |
| `boot.img` | `e7e85b5cd2806b8c27adf4925e05ee169072a79a43502effc34c97fb27ee8371` |
| `vbmeta_system.img` | `5c72c2d63deef95ddba41c825c271866a3041d48a6b5ca1cfd50bc4bc6cc2dda` |
| `vbmeta.img` | `c1f4ea68ea52bae62e464ddc245dadd740569ba3ac3376ee5d23a40204a744f7` |

rawprogram 精确包含 `super`、`vbmeta_system_a/b`、`boot_a/b`、`vbmeta_a/b` 七项，`patch_xml_count=0`。9008 写入及 read-back verify 完成并正常重启。QDL 在成功写完分区后打印的 `Trying to free an already freed buffer 0` 是该工具既有尾部信息；写入流程、read-back 与随后启动均成功，未据此掩盖分区失败。

证据：[`00_gate0_preflash_transcript.txt`](raw/device_run_20260831170720/00_gate0_preflash_transcript.txt)、[`01_flash_host_transcript.txt`](raw/device_run_20260831170720/01_flash_host_transcript.txt)。

## 启动观察

- 在 240 秒硬门内达到 `sys.boot_completed=1`；boot id 为 `7683ff75-02b3-4a4e-a4fa-23e246968f8c`。
- 随后连续观察 `187.743s`，共 19 个样本；相邻采样约 10.4 秒。
- `system_server` 始终为 PID `2700`、starttime `988`，无重启。
- 每个样本均为 Binder found、SELinux Enforcing、bootanim stopped，factory-reset transition 为空。
- Launcher 已解锁且可用；App 制品为 versionCode 49 / versionName 0.21.12。
- 当前 boot log 中 `VerifyError`、`FATAL EXCEPTION IN SYSTEM PROCESS`、`FatalSystemServer`、`RescueParty`、system_server watchdog/crash marker 均为 0。
- live `/system/framework/services.jar` SHA-256 为 `0b7bb46c644c5559173f72b06579131e82597366fdcc114d3fb30aabb544e8a3`；`/system/etc/init/zui_refresh_kill_switch.rc` 为 `0161a9980777b4313d0a5935b0e861e1afea86c5b4eb07ef5b88e44f20143c62`。

证据：[`02_boot_gate_180s_samples.txt`](raw/device_run_20260831170720/02_boot_gate_180s_samples.txt)、[`03_boot_gate_postcheck.txt`](raw/device_run_20260831170720/03_boot_gate_postcheck.txt)、[`03_boot_gate_logcat_all.txt`](raw/device_run_20260831170720/03_boot_gate_logcat_all.txt)、[`03_boot_gate_logcat_crash.txt`](raw/device_run_20260831170720/03_boot_gate_logcat_crash.txt)、[`03_boot_gate_launcher_unlocked.png`](raw/device_run_20260831170720/03_boot_gate_launcher_unlocked.png)。

Boot Hard Gate 完整通过，因而允许进入本候选的 targeted device gate。本结论只证明本候选可启动和该观察窗稳定，不扩大为长期 soak 结论。
