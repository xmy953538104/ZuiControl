# 安全与权限审计

## P0/P1 结论

### SEC-001：性能调度请求通道缺少调用方鉴权（P0）

- 文件/方法：`app/.../ZuiControlRequest.send()` 写 `Settings.System`；`payload/system/bin/zui_controld::process_settings_request()` 读取并执行。
- 问题：刷新率 Binder 会校验 UID 对应包名和证书摘要；性能请求只相信全局 Settings 字符串。任何能写该 setting 的 shell/root/高权限 App 都可伪造 requestId 和命令。
- 可执行动作：切换全局/应用档、启停 asoul、重启调度核心、请求日志等，具体以 daemon case 为准。
- 后果：系统性能、功耗和服务可被高权限相邻组件操纵。普通第三方 App 默认没有 WRITE_SETTINGS 自动授权，但这是系统 ROM 的高权限边界，仍需认证。
- 方向：迁移到受签名权限/UID+证书校验的 Binder；Settings 只作为只读状态兼容层。

### SEC-002：root daemon 使用宽泛 shell domain（P1）

- 文件：`payload/system/etc/init/zui_controld.rc` 第 14 行 `seclabel u:r:shell:s0`。
- 问题：uid 0 加通用 shell SELinux domain，不是最小权限专用 domain。
- 后果：一旦输入解析或可写文件边界出错，攻击面与审计难度扩大。
- 方向：建立 `zui_controld` 独立 domain、只授予 Settings/status/init/指定文件权限。

### SEC-003：全局 relabel `/system/bin/dumpsys`（P1）

- 文件：`ApplyZuiControlPayload.py` 约 496 行、`plat_file_contexts_add.txt`、`plat_sepolicy_zui_control.cil`。
- 问题：为 AsoulOpt 执行需求把系统 dumpsys 标为 `toolbox_exec`，影响面不是仅项目私有文件。
- 后果：可能改变其他 domain 的 execute transition/allow 关系。
- 方向：验证是否可用专用 wrapper/domain/精确 type，避免改变通用系统二进制标签。

## Binder 安全

`ZuiControlService.enforceCaller()` 根据 calling UID 取包名，必须包含 `com.zui.zuicontrol`，再计算签名 SHA-256 命中允许列表。安装路径只作附加检查。该方向正确，优于只信 platform uid 或路径。

风险：

- Debug digest 被允许时必须确保只出现在 debug/测试镜像；Release 必须只含 release digest。
- 手写 transaction 1–11 缺 AIDL 自动接口约束；10/11 是占位实现，扩大攻击面。
- `ZuiControlClient` 的 `ok=0` 误判是客户端正确性问题，不绕过服务端鉴权，但会隐藏拒绝。
- 没有 Binder death handling；属于稳定性而非权限绕过。

## exported component

| 组件 | 保护 | 判断 |
| --- | --- | --- |
| `MainActivity` exported | Launcher，无敏感 Intent 参数 | 风险低；外部可打开面板但敏感动作仍走系统接口 |
| 两个 TileService exported | `BIND_QUICK_SETTINGS_TILE` signature 权限 | 合理 |
| `BootReceiver` exported | 仅系统保护 boot actions | 一般安全，但可设 `exported=false` 的兼容性需验证后收窄 |
| QuickService | exported=false | 合理 |

未发现 provider、任意文件 URI、WORLD_READABLE/WORLD_WRITEABLE、外部存储配置、Runtime.exec 或 `su`。日志分享需检查生成文件 URI/内容脱敏，当前没有把设备 dump 放入审查包。

## 文件权限和数据边界

- system profile：`/data/system/zui_control`，应由 system_server 专用 label 控制。
- scheduler：`/data/vendor/zui_control`，daemon/Uperf/asoul 共享；可编辑配置不等于 world writable。
- symlink：`/data/vendor/asopt.conf` → 持久 conf，避免 `/data/adb` root 痕迹。
- 默认配置从只读 system 复制；Uperf 核心 JSON 每次 restart 被覆盖，不是用户持久配置。

需要最终 super 反向检查 contexts 和真机 AVC。Windows 未做 secilc 编译，属于明确验证边界。

## 供应链与敏感信息

- 仓库敏感模式扫描没有发现可提交的 keystore、私钥、API key、密码或 token；CI 只引用 GitHub Secrets，审查包不含值。
- GitHub Actions 使用浮动 major tag（如 `actions/checkout@v4`）而非不可变 commit SHA，有供应链风险。
- Uperf/AsoulOpt 只有 stripped ELF，无 LICENSE、source commit、SBOM 或可复现构建说明。Uperf strings 指向公开项目不等于当前二进制可溯源；AsoulOpt 来源更不透明。

## 设置/属性/进程能力

daemon 能读写 Settings、property、init ctl 并检查/重启进程，这是高权限系统能力。当前输入做了 mode/package 校验，但安全边界首先应是可信调用者，而不是仅校验字符串。禁止恢复 direct CPU/GPU sysfs 或 su 路径是正确约束；若下一阶段增加 GPU owner，必须先完成权限模型设计。
