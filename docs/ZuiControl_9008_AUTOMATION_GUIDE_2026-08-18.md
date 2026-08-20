# ZuiControl TB321FU 9008 自动刷入 - 2026-08-18

## 1. 目标与安全边界

本流程只用于把已经通过 `VerifyZuiControlFlashPackage.ps1` 的 ZuiControl 成品写入 TB321FU / ZUI 16.1.11.187。

原始 `【B刷机】187` 中有 6 份 rawprogram 和 6 份 patch XML，共 99 个 program 项。全量刷机会涉及 GPT、persist、FRP、modemst、userdata 等设备状态或设备唯一数据；ZuiControl 更新不需要它们。因此自动化绝不把整个目录通配给刷机工具，而是先生成一个独立的最小包。

允许写入的固定集合只有：

| LUN | 分区 | 文件 |
|---:|---|---|
| 0 | super | super.img |
| 0 | vbmeta_system_a | vbmeta_system.img |
| 0 | vbmeta_system_b | vbmeta_system.img |
| 4 | boot_a | boot.img |
| 4 | boot_b | boot.img |
| 4 | vbmeta_a | vbmeta.img |
| 4 | vbmeta_b | vbmeta.img |

没有 GPT program、没有 patch XML、没有 erase、没有 persist/FRP/modemst/userdata。

## 2. 工具选择

`D:\3.VScode\Mi\flash\GeekFlashTool.exe` 是 GUI、未签名且要求管理员权限，没有可审计的非交互 CLI。用按键/坐标模拟 GUI 不适合作为无人值守刷机路径。

自动化使用 Qualcomm 官方开源 [qualcomm/qdlrs](https://github.com/qualcomm/qdlrs)。它原生支持 Windows 串口后端和 `COM4` 形式的设备路径，适配当前已经工作的 Qualcomm HS-USB QDLoader 9008 驱动，不需要把设备改成 WinUSB。固定源码：

```text
qualcomm/qdlrs@412f90bc08cc3a687d552ff599da29043c4f54f4
```

仓库 workflow `.github/workflows/build-qdlrs-windows.yml` 在 GitHub 的 `windows-latest` 上用 `cargo build --release --locked --package qdl-rs` 构建。下载后放到：

```text
D:\3.VScode\Mi\flash\Binaries\Qcom\qdl-rs.exe
```

同时保留 artifact 中的 `LICENSE-qdlrs.txt` 和 `SOURCE_COMMIT.txt`。

## 3. 先生成安全包

在仓库执行：

```powershell
cd D:\3.VScode\Mi\ZuiControl
.\scripts\FlashZuiControl9008.ps1 -Mode Preflight
```

Preflight 会：

1. 读取 `SHA256SUMS_ZuiControl_v19.txt` 并重新计算 boot/super/vbmeta/vbmeta_system 的 SHA-256；
2. 从原始 rawprogram0..5 中精确寻找 7 个 allowlist 项；
3. 要求 sector size=4096、offset=0、非 sparse、LUN/文件名匹配，且镜像长度严格等于分区容量；
4. 在 `D:\3.VScode\Mi\flash\ZuiControl_9008_SAFE` 生成唯一 `rawprogram_zuicontrol.xml`；
5. 确认最终 XML 恰好 7 项且目录没有 patch XML 或意外文件。

四个大镜像使用同盘 NTFS hard link，不额外复制 14GB；每次重封新包后必须重新运行 Preflight，不能沿用旧安全目录。

## 4. 自动进入 9008 并刷入

设备正常开机、ADB 和 root 可用时执行：

```powershell
cd D:\3.VScode\Mi\ZuiControl
.\scripts\FlashZuiControl9008.ps1 -Mode Flash -ConfirmAdbSerial HA25HSZM
```

脚本的顺序是：

```text
安全包/hash/XML 预检
-> qdl-rs 文件和版本预检
-> 唯一 ADB serial + TB321FU + 187 + root 校验
-> sync; reboot edl
-> 等待唯一 VID_05C6&PID_9008 COM 口
-> qdl-rs serial / UFS / reset-mode=system / 唯一 rawprogram XML
-> 要求 All went well 成功标记
-> Firehose reset 到 system
-> 等待同一 ADB serial boot_completed=1
-> 要求 PackageManager 为当前脚本声明的 38/0.21.1
```

只想验证能否自动进入 EDL、不写入时可用 `-Mode EnterEdl`。注意它会把设备留在 9008；没有准备好恢复工具时不要单独使用。

### 4.1 首次与当前真实自动刷写结果

2026-08-18 已用上述命令从正常开机状态完整跑通一次，不需要人工操作 GeekFlashTool：

- ADB serial：`HA25HSZM`
- 自动进入端口：`COM3`，Qualcomm HS-USB QDLoader 9008
- qdl-rs 构建 run：`32149002631`，源码 commit：`412f90bc08cc3a687d552ff599da29043c4f54f4`
- `qdl-rs.exe` SHA-256：`54330234768a651540eabc108d72cad506c6f3511ebb94e04fec90ca7844332a`
- super 写入速度约 42–43 MB/s，随后写完 boot_a/b、vbmeta_a/b、vbmeta_system_a/b
- Firehose 终态：`All went well! Resetting to system`
- 自动复位后：同一设备回到 Android，`boot_completed=1`，PackageManager 报 33/0.20.4
- 日志：`D:\3.VScode\Mi\flash\Log\ZuiControl_qdlrs_2026-08-18_22-36-35.log`

2026-08-19 已用相同安全路径刷入当时的 35/0.20.6（历史）：

- 当前 commit：`c1d8978a70fecd25163fae1ef6eb157d413a960e`
- App build run：`32212847833`，结论 `success`
- `super.img` SHA-256：`f2b49a1670b28fbe43b1a9bc91db5486668b3c1d4c0c8c0a2b7a5cc9f1dead47`
- APK SHA-256：`a33e7fb38d9de3567bcd1544878c87ef0626ef8be8c4a4384e2a2d0bc72b85a7`
- 自动刷写日志：`D:\3.VScode\Mi\flash\Log\ZuiControl_qdlrs_2026-08-19_11-51-18.log`
- 自动复位后确认同一 `HA25HSZM`、`boot_completed=1`、PackageManager 35/0.20.6 和 `/system/priv-app/ZuiControlV35`

2026-08-19 随后又用同一安全路径刷入当时的 36/0.20.7（历史）：

- 当前生产 commit：`0d4b75c32496ce8767c0421c13bdc56b0045f63c`
- App build run：`32218280953`，结论 `success`
- `super.img` SHA-256：`93f5e7dffb76b06b725962b7c6a8d7d788c558992d47f7ea511255c4f3c54515`
- APK SHA-256：`07a715c59730fabc2a09ab258c1eff5121da1b7719184970814661d1735de09f`
- 自动刷写日志：`D:\3.VScode\Mi\flash\Log\ZuiControl_qdlrs_2026-08-19_13-26-33.log`
- 自动复位后确认同一 `HA25HSZM`、`boot_completed=1`、PackageManager 36/0.20.7 和 `/system/priv-app/ZuiControlV36`

2026-08-19 深夜继续用同一安全路径刷入当时的 37/0.21.0（历史）：

- 当前生产 commit：`36d6b26c05e5c0ecfca04ae78120aede51f1d8a2`
- App build run：`32266515192`，结论 `success`
- `super.img` SHA-256：`b43375c2c3c53ae5df8f23f6f658ae97cc7b76b5501012fec93cf1c60db173ae`
- APK SHA-256：`459db275dd33272ed229ac4a7adfac180f314d3345dca4c30d4b9f37b7fe7fef`
- 自动刷写日志：`D:\3.VScode\Mi\flash\Log\ZuiControl_qdlrs_2026-08-19_23-25-02.log`
- 自动进入 `COM3`，Firehose 返回 `All went well!`；自动复位后确认同一 `HA25HSZM`、`boot_completed=1`、PackageManager 37/0.21.0 和 `/system/priv-app/ZuiControlV37`
- 正常重启后的 P1/P2/AppOpt、亮屏游戏重入、相机、精确 AVC 与 12 轮稳定性检查均已完成；这是当时 37/0.21.0 的功能基线，现由下述 38/0.21.1 覆盖。

2026-08-20 中午继续用同一安全路径刷入当前 38/0.21.1：

- 当前生产代码 commit：`84f3c97bf27f0cec7c8335aa0d164baf49e2b376`
- App build run：`32330351987`，结论 `success`
- `super.img` SHA-256：`eaa6e5ea230fdff34fe2935a3ffe3d63c61521ed22826ce93a82cf7c8055cbce`
- APK SHA-256：`5780bda644ce920c6072d6fa73f9dde486cc58f4037aba5ba271da0a38496d62`
- 自动刷写日志：`D:\3.VScode\Mi\flash\Log\ZuiControl_qdlrs_2026-08-20_12-19-00.log`
- 自动进入 `COM3`，Firehose 返回 `All went well! Resetting to system`；自动复位后确认同一 `HA25HSZM`、`boot_completed=1`、PackageManager 38/0.21.1 和 `/system/priv-app/ZuiControlV38`
- 最终 super 反抽 verifier 为 `ok=true`。真实横竖屏、P1、P2 游戏重入、AppOpt 内核线程亲和、相机、云控删除和项目 AVC 均已刷后验收。

loader 在每个 program 完成后打印过 `Trying to free an already freed buffer 0`，但没有中断命令，7 项均写完且最终返回正式成功标记。这个文本当前只按 loader 日志噪声记录；脚本仍以 qdl-rs 退出码、`All went well`、Android 回连和版本检查共同判断成功，不能单独忽略真正的非零退出或写入中断。

## 5. 失败边界与恢复

- 在 `reboot edl` 前失败：设备没有离开 Android，也没有写入。
- 已进入 9008、但 Sahara/loader 前失败：重新运行 qdl-rs 或已知可用的 GeekFlashTool 即可，不能拔线后假设 Android会自动回来。
- loader 已进入 Firehose、某个 program 写入失败：不要改用全量 XML碰设备唯一分区。修复连接后用同一 7 项安全包重刷；boot/super/vbmeta 双槽集合可重复写。
- qdl-rs 报成功但 ADB 超时：先等设备启动；仍不启动时保留 `D:\3.VScode\Mi\flash\Log\ZuiControl_qdlrs_*.log`，再用同一安全包重刷。不要清 persist/FRP/modemst。
- 任何时候若 Preflight 发现 hash、镜像大小、XML 字段或额外文件不符，必须停下重新重封/验证，禁止通过关闭检查继续。

## 6. 刷后验证

自动脚本只把“写入成功、reset、Android完成启动、PackageManager 版本正确”作为传输层闭环。功能层仍按主交接当前章节验证：

1. V38/0.21.1 和 APK hash；
2. 开机 P2 reload 必须 done/stableSeconds=3，无 Failed transaction、OverHeatClean fatal/NPE；
3. 一次可恢复的 P2 修改/恢复和鸣潮重入；
4. P1、相机、AppOpt 快速回归；
5. dmesg 与全 buffer logcat 项目 AVC。

`qdl-rs` 是低层刷写工具，不负责证明 P1/P2/AppOpt 运行正确。
