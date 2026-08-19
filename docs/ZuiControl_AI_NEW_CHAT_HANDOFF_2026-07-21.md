# ZuiControl 新聊天完整交接 - 2026-07-21

## 0. 权威入口

新聊天按以下顺序读取：

1. `D:\3.VScode\Mi\AGENTS.md`
2. 本文件
3. 需要理解 P2 XML 时读取 `docs/ZuiControl_P2_XML_THERMALCONFIG_GUIDE_2026-07-21.md`
4. 需要追溯实测证据时读取：
   - `docs/ZuiControl_HANDOFF_P3_APPOPT_CLOUD_P2FIX_2026-06-24.md`
   - `docs/ZuiControl_HANDOFF_P2_MINGCHAO_XML_FIX_2026-06-24.md`

旧的 `D:\3.VScode\Mi\docs\ZuiControl_CONTEXT.md`、`ZuiControl_V19_VERIFY_AND_NEXT.md`、`ZuiControl_HANDOFF.md`、`ZuiControl_ROADMAP.md` 保存完整历史，但其中“当前阶段”“推荐包”“下一步”若与本文件冲突，以本文件为准。

### 0.9 2026-08-20 0.21.0 统一 UI、线程级 AppOpt 与最终刷后验收（当前最新）

本节覆盖 0.8 及更早章节中的当前版本、AppOpt 只支持整包预设、最终基线和下一步。设备 `HA25HSZM` 已通过安全 7 项 9008 流程持久刷入 `versionCode=37` / `versionName=0.21.0`，随后完成正常重启、真实亮屏游戏重入、内核线程亲和、相机、P1、P2、AppOpt、SELinux 和连续稳定性验收。当前成品不是临时 bind 或待刷包。

#### 当前成品与发布事实

- 生产 commit：`36d6b26c05e5c0ecfca04ae78120aede51f1d8a2`
- GitHub Actions run：`32266515192`，结论 `success`：<https://github.com/xmy953538104/ZuiControl/actions/runs/32266515192>
- CI artifact：`D:\3.VScode\Mi\work\release_0.21.0_run_32266515192`
- package：`com.zui.zuicontrol`；版本：37/0.21.0；system 路径：`/system/priv-app/ZuiControlV37/ZuiControl.apk`
- release 证书 SHA-256：`3fecf3a72ca0e0f24991d49e7306ef4a711711f48a66070755eb0237ecb3ed94`
- CI、payload、system 内嵌和两个 sidecar APK SHA-256：`459db275dd33272ed229ac4a7adfac180f314d3345dca4c30d4b9f37b7fe7fef`
- 最终刷机目录：`D:\3.VScode\Mi\【B刷机】187`
- 安全自动刷机目录：`D:\3.VScode\Mi\flash\ZuiControl_9008_SAFE`
- 最终成品反抽 verifier：`ok=true`

```text
5fc24c5b36ba7394125b87b681b62e38575172057c78417a0fa24746815be76b  boot.img
b43375c2c3c53ae5df8f23f6f658ae97cc7b76b5501012fec93cf1c60db173ae  super.img
9db326d3d605885c4afdac6b0883dc3f9c0bc2b9b1b3766cc4808945015967f5  vbmeta.img
fc3efa5dcb96406d211362a348f5ba775961b2c2af960c6290e26e5a5c827559  vbmeta_system.img
459db275dd33272ed229ac4a7adfac180f314d3345dca4c30d4b9f37b7fe7fef  ZuiControl-v19-system.apk
459db275dd33272ed229ac4a7adfac180f314d3345dca4c30d4b9f37b7fe7fef  ZuiControl-v19-release.apk
```

自动刷写日志：`D:\3.VScode\Mi\flash\Log\ZuiControl_qdlrs_2026-08-19_23-25-02.log`。脚本从正常 Android 自动进入 COM3/9008，只写 super、boot_a/b、vbmeta_a/b、vbmeta_system_a/b，Firehose 返回 `All went well!`，自动复位后确认同一 ADB serial、`boot_completed=1` 和 37/0.21.0。禁止使用原始 99 项全量 XML 做日常更新。

#### 0.21.0 当前产品与 UI

- App 仍是原生 Kotlin + Android Views，没有引入 Compose、Rust UI 或新运行时依赖。四个主页面、卡片、按钮、间距、标题、状态和进度框已统一；横竖屏及小窗口均真实截图检查。这样比重写技术栈更轻、更容易随 ROM 内置维护。
- P1 刷新率页保留 system_server 唯一 owner；P2 性能页负责 XML/游戏助手；AppOpt 页负责线程亲和；系统页负责健康状态、日志与导入导出。各页不再混用大小不一的按钮和杂乱分隔线。
- P2 与 AppOpt 长操作都显示不可取消的真实阶段进度框，只在 exact requestId、exact command 的 terminal ACK 后关闭；Activity 重建不会覆盖正在执行的单槽请求。

#### P2 当前语义与 V37 实测

- P2 添加/删除继续严格单向同步 Game Assistant 自定义列表：ZuiControl 新增则同步添加，ZuiControl 删除则同步移出；Game Assistant 自己的增删不反向改 ZuiControl。membership、profile、active XML、mount/reload 和终态 ACK 是一笔可回滚事务。
- 保存/删除后只在目标确实运行时自动 `force-stop`；未运行返回 `target=not_running`。用户不需要进系统设置手动强停。ZuiPP 重载也完全由 daemon 处理，用户不手动操作。
- 输入导致 XML hash 改变时，daemon 安全停止四个原厂 service、重启 ZuiPP 并观察新 PID 稳定 3 秒；输入未改变最终 XML 时允许 `skipped;same_hash`，不做无意义重启。V37 对鸣潮同值保存的直接请求约 8.9 秒，真实 UI 操作约 9.8 秒；改 hash 的操作仍需加上受控重启和 3 秒稳定窗，通常约 12–15 秒。
- 正常重启后，boot bind 使 ZuiPP `4620 -> 6636`，终态 `state=done;reason=boot_active;stableSeconds=3`。亮屏从桌面重入鸣潮后，日志依次出现同包名 `onGameAppStart`、GameHelper `initGameHelper`、ZuiPP `notifyPerfStatus : 11 17` 和 `writeSavageMode::open::1,status::0`；三档 JSON 与当前 XML 的四簇 CPU/GPU 值一致。
- 息屏时用 adb/monkey 拉进程只能证明 Activity/进程存在，系统任务为 `isSleeping=true` 时不会产生正常 Game Assistant 游戏态；这不是用户正常入口，也不能作为 P2 成功证据。亮屏从桌面启动才是交付路径。
- 最终只保留鸣潮 profile。active/system game hash 均为 `cf787905a1a2d9a3afae69b7a48272ff0d86ff0e35c99d9a305e3dc31b634c1d`，performance hash 均为 `447277c22ba0d2cd378b05e2c99bc80d89d4fb57717deb78ac5ca760407e7a92`，两处均为 bind mount。鸣潮 `ThermalConfig=0 0 0`、三个 LimitConfig 段相同，四簇共享 CPU level ID 且四个 Type 都存在。

#### AppOpt 当前语义与 V37 实测

- AppOpt 现在支持包级 fallback 加精确线程名规则，不再只能把整个包放到一组 CPU。内置 8 Gen 3 目录来自用户提供的 AsoulOpt 配置，共 326 个 package、1205 条规则，其中 879 条为线程规则；目录仅用于给用户选择目标 App 后生成模板，不会启动时全量扫描目标进程，因此数量不会造成明显运行时开销。
- 鸣潮当前模板和最终权威配置为：

```text
com.kurogame.mingchao=2-6
com.kurogame.mingchao{RenderThread}=2-4
com.kurogame.mingchao{GameThread}=7
```

- fallback 是必填安全网；线程名采用精确匹配。实机重开鸣潮后，主进程/未命中线程为 `2-6`，`RenderThread` 为 `2-4`，所有 `GameThread` 为 `7`。这证明当前 AppOpt 真正操作了内核 `Cpus_allowed_list`，不是只把文本写进配置。
- UI 支持选 App、选择内置模板、预览确认、单项修改/删除、完整文本编辑、严格校验并应用，以及 `Download/ZuiControl/AppOpt.conf` 导入/导出。自由文本也必须通过同一 parser：仅用户 App、必须有 fallback、拒绝重复项/非法 CPU 集合，整表上限 16 KiB；通过后才原子替换权威 `/data/vendor/zui_control/appopt/applist.conf`。
- 运行中应用模板实测约 9.8–11.35 秒，终态 `rules=3;stoppedApps=1;stopFailed=0`；目标未运行时约 11.5 秒，终态 `stoppedApps=0`。导入同一配置约 9.3 秒。daemon 自动停止受影响的运行中 App，并重启 AppOpt；用户完成后重新打开目标即可。
- P2 CPU 上下限与 AppOpt 线程亲和是互补关系：前者限制 OEM 对各 CPU cluster 的性能请求，后者决定线程允许落在哪些 CPU。它们不直接互相覆盖，但过窄亲和、过低频率上限或错误线程名都可能造成卡顿，模板不能被当作所有版本通用的绝对最优值。

#### 最终刷后回归与已知边界

- P1：普通 shell Binder 被拒绝；Settings 可逆 60Hz/144Hz 规则的 target、actual 和 active mode 都正确，清理测试规则后 profile 逐字恢复为默认 120，QS 未学习 SystemUI。最终 Launcher 为 `targetDisplayHz=120` / `actualDisplayHz=120`、`refreshOwner=system`、`daemonRefreshDisabled=true`。没有修改 P1，也没有进入 FPS cap。
- P1 仍保留一个需要以后单独处理的边界：本轮较早曾在相机/时钟临时场景后观察到一次 `target=120`、`actual=60` 且 focus 事件 `skipSame`，显式 refreshNow 恢复；最终重启后的相机回归没有复现并正常回到 120/120。因本轮明确禁止改 P1，不能把这个低频边界写成已修复。
- 相机在持久镜像和正常重启后重复通过 `STILL_IMAGE_CAMERA` 与第三方 `IMAGE_CAPTURE`：boot ID 不变，SurfaceFlinger `1655:390`、cameraserver `1954:420` 的 PID/starttime 均不变，tombstone 无新增，没有 `No matching frame rate modes`、DEAD_OBJECT、Fatal 或系统重启。
- dmesg 与全 buffer logcat 中按 `zui_control`、`zui_controld`、`AppOpt`、`performanced` 和 `com.zui.zuicontrol` 精确筛选的 AVC 均为 0；没有相关 fatal/ANR。原厂相机/HAL 的历史 denial 不应通过扩大项目策略来消日志。
- 重启后第一次过早读取 AppOpt 健康 setting 曾短暂看到上次 PID，20 秒周期内自动纠正为真实 PID 7273；service 和亲和执行未中断。这是状态镜像短暂滞后，不是功能失败，暂不为几秒显示延迟增加启动同步复杂度。
- 12 轮、每轮间隔 45 秒的最终观察中，boot ID 不变，daemon 始终 running，ZuiPP 始终 PID 6636，AppOpt 始终 PID 7273，P1 始终 120/120，两处 mount 始终存在，四个 active/system hash 始终逐字一致。
- 云控继续为完全删除状态。FPS cap 仍未交付；用户当前讨论的是 displayHz、P2 XML 和 AppOpt，并没有提出必须实现 UID FPS cap。CPU/GPU XML 是 OEM 性能请求而非不可覆盖 hard cap，高温或更高优先级厂商策略仍可能改变最终节点。

#### 新聊天当前最短下一步

1. 先只读确认设备仍为 37/0.21.0、V37、P1 owner、当前鸣潮 P2 profile 和三条 AppOpt 规则；不要无原因重复全套写入测试。
2. 用户若报告 P2 不响应，记录 exact ACK 最后阶段、Game Assistant membership、profile、active/system hash、ZuiPP reload 和下一次亮屏真实 `onGameAppStart`；不要只看瞬时 CPU/GPU 节点。
3. 用户若报告 AppOpt 不响应，记录 exact ACK、权威配置、AppOpt PID/service 和新启动目标的实际线程名及 `/proc/<tid>/status`；先判断线程名是否精确匹配，再调整模板。
4. 只有出现可复现的新缺陷才制作下一包。仍不进入 FPS cap，不改 P1，不恢复 direct CPU/GPU sysfs、provider_direct、云控或旧 AsoulOpt。

### 0.8 2026-08-19 0.20.7 真单向同步、内置编辑器、进度反馈（历史，已被 0.9 覆盖）

本节覆盖 0.7 及更早章节中的当前版本、P2 删除不移出游戏助手、AppOpt 必须先导出再编辑、操作期间只能等待等表述。设备 `HA25HSZM` 已通过安全 7 项 9008 流程持久刷入 `versionCode=36` / `versionName=0.20.7`；发布、自动刷写和刷后可逆实测均已闭合，设备最终恢复到原有鸣潮 P2 profile、AppOpt 0 规则的基线。

#### 当前成品与发布事实

- 生产 commit：`0d4b75c32496ce8767c0421c13bdc56b0045f63c`
- GitHub Actions run：`32218280953`，结论 `success`：<https://github.com/xmy953538104/ZuiControl/actions/runs/32218280953>
- CI artifact：`D:\3.VScode\Mi\work\release_0207_32218280953`
- package：`com.zui.zuicontrol`；版本：36/0.20.7；system 路径：`/system/priv-app/ZuiControlV36/ZuiControl.apk`
- release 证书 SHA-256：`3fecf3a72ca0e0f24991d49e7306ef4a711711f48a66070755eb0237ecb3ed94`
- CI、payload、system 内嵌和两个 sidecar APK SHA-256：`07a715c59730fabc2a09ab258c1eff5121da1b7719184970814661d1735de09f`
- 最终刷机目录：`D:\3.VScode\Mi\【B刷机】187`
- 安全自动刷机目录：`D:\3.VScode\Mi\flash\ZuiControl_9008_SAFE`
- 最终成品反抽 verifier：`ok=true`

```text
5fc24c5b36ba7394125b87b681b62e38575172057c78417a0fa24746815be76b  boot.img
93f5e7dffb76b06b725962b7c6a8d7d788c558992d47f7ea511255c4f3c54515  super.img
9db326d3d605885c4afdac6b0883dc3f9c0bc2b9b1b3766cc4808945015967f5  vbmeta.img
4635484b6469fe4db9fb9c78aa5a5f2c410aef514eda0db7b3f34d5ece053a9f  vbmeta_system.img
07a715c59730fabc2a09ab258c1eff5121da1b7719184970814661d1735de09f  ZuiControl-v19-system.apk
07a715c59730fabc2a09ab258c1eff5121da1b7719184970814661d1735de09f  ZuiControl-v19-release.apk
```

自动刷写日志：`D:\3.VScode\Mi\flash\Log\ZuiControl_qdlrs_2026-08-19_13-26-33.log`。脚本从正常 Android 自动进入 COM3/9008，只写 super、boot_a/b、vbmeta_a/b、vbmeta_system_a/b，自动复位并确认同一 ADB serial、`boot_completed=1` 和 36/0.20.7。禁止使用原始 99 项全量 XML 做日常更新。

#### 0.20.7 当前用户语义

P2：

- ZuiPP 重启完全由 ZuiControl 的 daemon 自动完成。保存或删除时，daemon 会生成和校验 XML、promote/bind、先停止四个已知原厂 service、受控重启 ZuiPP、确认新 PID 稳定 3 秒，再发布完成；用户不应手动重启 ZuiPP。
- 现在是严格的单向镜像：在 ZuiControl 新增 P2 profile，会同步添加目标到 Game Assistant 自定义列表；在 ZuiControl 删除该 profile，会同步删除由自定义列表表示的目标；在 Game Assistant 单独新增或删除，不会反向创建或删除 ZuiControl profile。系统内建游戏识别不是“自定义条目”，不能也不需要伪造删除。
- Game Assistant membership、profile、active XML 和 runtime 位于同一事务提交边界。任一步失败都恢复旧 membership/profile/XML/runtime，不允许出现“游戏列表改了但 XML 没改”或反过来的半成功。
- 保存或删除成功后，只在目标当时有运行进程时自动 `force-stop`；未运行时返回 `target=not_running`，不做无意义操作。完成后用户直接从桌面重新打开即可。
- 实机新增 `com.xmy.ap` 用时 14.766 秒，逐阶段看到 validating、generating_xml、reloading_zuipp、stopping_zuipp_services、waiting_zuipp、syncing_game_assistant、committing、stopping_target，终态 `game=user_added;target=not_running`。运行该 App 后删除 profile 用时 12.788 秒，终态 `game=user_removed;target=stopped`；membership=false、profile 消失、目标 PID 消失，XML hash 逐字恢复，ZuiPP PID 15754→23762 且稳定 3 秒。

AppOpt：

- 系统状态页现在有“编辑配置清单”：打开原生多行编辑器，内容预填当前 canonical 配置；点击“校验并应用”后，只有全部行通过校验才一次性替换权威配置。错误会留在编辑器中并明确显示，不会部分应用。
- 每条活动规则仍严格为 `普通用户包名=预设`，预设仅 `0-7`、`0-4`、`5-7`、`0-1`；不开放系统 App、线程名或任意 CPU 集合。已有的单 App 图形化添加/修改/删除和共享文件导入/导出继续保留。
- 权威配置仍是 `/data/vendor/zui_control/appopt/applist.conf`，但用户无需直接访问它。编辑器和导入都通过同一个 daemon 校验/事务接口写入，因此改规则不需要重刷镜像。`Download/ZuiControl/AppOpt.conf` 只是可导入/导出的共享副本，不是 daemon 热读源。
- 保存、删除、停止或整表替换成功后，daemon 自动停止正在运行的受影响 App；未运行则跳过。规则只有在目标下一次新进程启动时完整生效。
- 实机已打开编辑器、读取 canonical 零规则文本并点击应用；非取消式进度框出现，显示“正在应用 AppOpt 配置”和真实阶段，exact ACK 为 `done|replace_appopt_rules|rules=0;stoppedApps=0;stopFailed=0`，弹窗随后自动关闭。最终仍为 0 活动规则、service stopped、无 AppOpt PID。

进度反馈：

- P2、AppOpt 及相关长操作现在使用非取消式原生进度弹窗，不再靠固定 13 秒或界面乐观更新。弹窗只跟踪当前 requestId 的四字段 ACK，显示 daemon 的真实阶段；收到同 ID/同 command 的 `done` 或 `failed` 才关闭并刷新状态。
- P2 的 12–15 秒主要来自生成/校验、ZuiPP service 安全停止和新 PID 的 3 秒稳定窗，不是 App 卡死。AppOpt 通常约 5–7 秒；具体时间取决于受影响 App 数和 AppOpt 重启/停止。

#### 36/0.20.7 持久刷后真实结果

- 身份：系统为 ZUI 16.1.11.187；PackageManager 36/0.20.7，路径 `/system/priv-app/ZuiControlV36`，APK hash 与发布 hash一致，旧 V35 不存在。
- P1：system_server 仍是唯一刷新率 owner，daemon refresh disabled；未授权 shell Binder 被拒绝。对 Settings 做可逆 60Hz profile 时 target/actual 和 display active mode 都到 60，删除后回到 120，原 profile 未污染。没有改 P1，也没有进入 FPS cap。
- 开机 P2：active XML bind 后 ZuiPP 4614→6479，`state=done;reason=boot_active;stableSeconds=3`，证明重启由系统自动完成。P2 新增/删除的 membership、profile、hash、mount、新 PID和 target stop 均闭合；失败注入没有改变旧 membership/profile/hash/PID。
- 最终只剩鸣潮 profile。active/system game hash 均为 `cf787905a1a2d9a3afae69b7a48272ff0d86ff0e35c99d9a305e3dc31b634c1d`，performance hash 均为 `447277c22ba0d2cd378b05e2c99bc80d89d4fb57717deb78ac5ca760407e7a92`，两个路径保持 bind mount。鸣潮 `ThermalConfig=0 0 0`，三个 LimitConfig 段完全一致，四簇共享 level ID `900000` 且四个 Type 都存在。
- AppOpt：内置编辑器和 exact progress/ACK 通过；最终 `applist.conf` 0 活动规则、`init.svc.zui_appopt=stopped`、无 AppOpt PID、无 enabled flag。
- 相机：`STILL_IMAGE_CAMERA` 和模拟第三方 `IMAGE_CAPTURE` 都打开成功；SurfaceFlinger 始终 PID 1626/starttime 403，cameraserver 始终 PID 1919/starttime 429，无新增 tombstone、`No matching frame rate modes`、abort、fatal 或 CameraProvider DEAD_OBJECT。
- 项目精确路径未发现 AVC。仍有原厂 cameraserver 读取 `vendor_display_prop`、相机属性设置和厂商 HAL denial，以及相机 DSP unmap 错误；本轮功能成功且相关进程未重启，禁止把这些原厂日志误当 ZuiControl 缺权限而扩大策略。
- 云控继续为删除状态；官方 hosts 仍是 56 字节/`425c3e713d5bae19b031bc8639c20c6a23e311a54647ba1824cbf45969a11ff4`，旧脚本、runtime、setting 和 iptables/ip6tables 链不存在。

#### 新聊天当前最短下一步

1. 先只读确认设备仍为 36/0.20.7、V36、P1 owner 和本节最终 P2/AppOpt 基线；不要无原因重复全套写入测试。
2. 用户若报告 P2 不响应，记录 exact request ACK 的最后阶段、Game Assistant membership、target stop 结果、profile、active/system hash、ZuiPP PID/reload 和下一次真实 `onGameAppStart`。CPU/GPU XML 是 OEM 性能请求而非 hard cap，节点瞬时不同不能单独判失败。
3. 用户若报告 AppOpt 不响应，记录编辑器校验错误或 exact ACK、权威配置、AppOpt PID/service、目标 stop 结果，并在重新打开后读 `/proc/<pid>/status`。不要绕开 UI/daemon 直接写权威文件。
4. 只有出现可复现的新缺陷才制作下一包。仍不进入 FPS cap，不改 P1，不恢复 direct CPU/GPU sysfs、provider_direct、云控或旧 AsoulOpt。

### 0.7 2026-08-19 0.20.6 工作流优化、持久实测与交接（历史，已被 0.8 覆盖）

本节覆盖 0.6 及更早章节中的当前版本、hash、10 秒等待、手动强停、P2 两地添加和 AppOpt 无公开配置等表述。设备 `HA25HSZM` 已通过安全 7 项 9008 流程持久刷入 `versionCode=35` / `versionName=0.20.6`，不是临时 bind；成品、运行时状态和测试后的基线均已复核。

#### 当前成品与发布事实

- 当前生产 commit：`c1d8978a70fecd25163fae1ef6eb157d413a960e`
- GitHub Actions run：`32212847833`，结论 `success`：<https://github.com/xmy953538104/ZuiControl/actions/runs/32212847833>
- CI artifact：`D:\3.VScode\Mi\work\release_0206_32212847833`
- package：`com.zui.zuicontrol`；版本：35/0.20.6；system 路径：`/system/priv-app/ZuiControlV35/ZuiControl.apk`
- release 证书 SHA-256：`3fecf3a72ca0e0f24991d49e7306ef4a711711f48a66070755eb0237ecb3ed94`
- CI、payload、system 内嵌和 sidecar APK SHA-256：`a33e7fb38d9de3567bcd1544878c87ef0626ef8be8c4a4384e2a2d0bc72b85a7`
- 最终刷机目录：`D:\3.VScode\Mi\【B刷机】187`
- 安全自动刷机目录：`D:\3.VScode\Mi\flash\ZuiControl_9008_SAFE`
- 最终反抽 verifier 工作目录：`D:\3.VScode\Mi\work\verify_flash_zui_control_0206`；结论 `ok=true`

```text
5fc24c5b36ba7394125b87b681b62e38575172057c78417a0fa24746815be76b  boot.img
f2b49a1670b28fbe43b1a9bc91db5486668b3c1d4c0c8c0a2b7a5cc9f1dead47  super.img
9db326d3d605885c4afdac6b0883dc3f9c0bc2b9b1b3766cc4808945015967f5  vbmeta.img
b359b1010da64fd05306910133acee2ab4d5f81048d1d2ccd91caac48300d3b0  vbmeta_system.img
a33e7fb38d9de3567bcd1544878c87ef0626ef8be8c4a4384e2a2d0bc72b85a7  ZuiControl-v19-system.apk
a33e7fb38d9de3567bcd1544878c87ef0626ef8be8c4a4384e2a2d0bc72b85a7  ZuiControl-v19-release.apk
```

最终自动刷写日志为 `D:\3.VScode\Mi\flash\Log\ZuiControl_qdlrs_2026-08-19_11-51-18.log`。脚本从正常 Android 自动进入 9008，只写 super、boot_a/b、vbmeta_a/b、vbmeta_system_a/b，自动复位并确认同一 ADB serial、`boot_completed=1` 和 35/0.20.6。禁止把原始 99 项 XML 用于日常 ZuiControl 更新。

#### 0.20.6 当前用户语义

P2 性能调度：

- “游戏”不再靠 ZuiControl 猜。P2 添加器只列出已安装、可启动的 `/data/app` 用户 App；保存前查询 ZUI Game Assistant 自定义列表，不存在时单向自动加入，再提交 XML 事务。
- 单向同步是有意设计：ZuiControl 添加 P2 配置会加入 Game Assistant；在 Game Assistant 单独添加不会创建 ZuiControl profile；删除 P2 profile 也不会删除 Game Assistant 条目。这样只进游戏列表的 App 继续走 OEM 默认调度，只有在 ZuiControl 保存 profile 的 App 才走自定义 XML。
- 保存成功后 daemon 只在目标 App 当时确实有运行进程时自动 `force-stop`；未运行则返回 `target=not_running`，不做多余操作。用户无需去系统设置手动强停，保存完成后从桌面重新打开即可。
- ZuiPP 缓存进程必须重启，因为它不会可靠热读新 XML；不能删除“新 PID”校验。稳定观察由 10 秒缩短到 3 秒，用于确认新进程没有立即崩溃。鸣潮修改和恢复实测分别为 13.882 秒、14.209 秒，均得到 exact ACK、一次新 PID、`stableSeconds=3`、profile/XML/hash/mount 闭合。
- “息屏启动”只是旧自动化测试曾在屏幕关闭时用 adb 拉起 App 的异常测试场景，不是正常用户流程或产品入口。正常亮屏从桌面打开即可；只看到 Activity 焦点但没有 ZUI `onGameAppStart` 时不能误称游戏态已建立。

AppOpt：

- 只支持普通用户 App 的整包预设 `0-7`、`0-4`、`5-7`、`0-1`；不开放系统 App、线程名或自由 CPU 集合。
- 保存、删除、停止和批量导入成功后，daemon 自动关闭当时正在运行的受影响 App；没运行则跳过。用户只需在完成提示后重新打开目标 App。
- daemon 的权威配置仍是 `/data/vendor/zui_control/appopt/applist.conf`。公开可编辑副本是 `Download/ZuiControl/AppOpt.conf`；UI“导出共享配置”把权威配置同步到该文件，“导入共享配置”严格校验后一次性替换全部规则。公开文件不是 root daemon 实时读取源，避免半写文件、任意语法和共享存储权限问题。
- 规则保存实测 6.510 秒；批量清空实测 6.209 秒；从 UI 导入零规则文件实测 5.770 秒。`com.xmy.ap=0-1` 重开后 `/proc/<pid>/status` 为 `Cpus_allowed_list: 0-1`，清空后 service stopped、无 AppOpt PID、0 规则。
- 0.20.5 曾用 `text/plain` 写 MediaStore，系统会把文件改名为 `AppOpt.conf.txt`；0.20.6 改为 `application/octet-stream` 并已实机确认精确文件名 `AppOpt.conf`，没有 `.txt` 或重复副本。

#### 35/0.20.6 持久刷后真实结果

- 身份：PackageManager 为 35/0.20.6，路径 `/system/priv-app/ZuiControlV35`，系统 APK hash 与发布 hash 一致，旧 V34 不存在。
- P1：普通 shell Binder 被拒绝；Launcher 最终 `raw/current/last/applied` 一致，target/actual 均 120Hz，`refreshOwner=system`、`systemServiceAlive=true`、`daemonRefreshDisabled=true`、`displayVote=adaptiveRender`。没有改 P1，也没有进入 FPS cap。
- 开机 P2：ZuiPP PID 4636→6534，`state=done;reason=boot_active;stableSeconds=3`。鸣潮现有 profile 的首温区 GPU 720000→710000 后，运行中的目标被自动停止，ZuiPP 6534→11673，active/system 两对 hash 同步改变；恢复 720000 时目标未运行，ZuiPP 11673→13292，profile 逐字和两对 hash 均恢复。
- P2 最终 game hash：`cf787905a1a2d9a3afae69b7a48272ff0d86ff0e35c99d9a305e3dc31b634c1d`；performance hash：`447277c22ba0d2cd378b05e2c99bc80d89d4fb57717deb78ac5ca760407e7a92`。active 与 `/system/etc` 相同且保持 bind mount。Game Assistant 查询鸣潮返回 true。
- AppOpt：`com.xmy.ap=0-1` 后运行中的目标自动停止，重开后主进程 CPU mask 为 0-1；批量清空后目标再次自动停止。最终共享文件只含注释，权威配置 0 活动规则，service stopped、无 AppOpt PID。
- UI：性能页明确显示自动加入游戏助手/自动关闭语义；系统页的添加、停止、导入和导出入口均可见。真实点击导出生成精确 `Download/ZuiControl/AppOpt.conf`；真实点击“导入并应用”收到 exact done ACK，耗时 5.77 秒。
- 相机：系统 `STILL_IMAGE_CAMERA` 与模拟第三方 `IMAGE_CAPTURE` 都打开成功；SurfaceFlinger 始终 PID 1644/starttime 396，cameraserver 始终 PID 1870/starttime 417，没有新增 tombstone、`No matching frame rate modes`、CameraProvider DEAD_OBJECT 或 fatal。
- 项目相关 AVC 未发现。原厂 `system_server dac_read_search` 和 `cameraserver` 读取 `vendor_display_prop` denial 仍存在；它们在旧稳定包也存在且本次未造成进程重启，禁止为消日志扩大项目权限。

#### 新聊天当前最短下一步

1. 先只读确认设备仍为 35/0.20.6、V35、上述 APK/super hash、P1 owner 和 P2/AppOpt 最终基线；不要无原因重复全套写入测试。
2. 用户若报告 P2 不响应，记录 exact ACK、Game Assistant membership、目标运行状态、profile、active/system hash、ZuiPP 旧/新 PID 与真实 `onGameAppStart`；CPU/GPU 节点不是 hard cap，不能单独判失败。
3. 用户若报告 AppOpt 不响应，记录 exact ACK、权威配置、AppOpt PID/service、目标是否被自动停止，并在重新打开后读取 `/proc/<pid>/status`；共享文件必须通过 UI 导入，不是保存即热生效。
4. 只有出现可复现的新缺陷才制作下一包。仍不进入 FPS cap，不改 P1，不恢复 direct CPU/GPU sysfs、provider_direct、云控或旧 AsoulOpt。

### 0.6 2026-08-18 0.20.3 刷后根因、0.20.4 成品与 9008 自动化（历史，已被 0.7 覆盖）

本节覆盖 0.5 的版本、hash、“0.20.3 待刷”和下一步。设备已经通过新的最小化 9008 自动流程持久刷入 33/0.20.4，并完成开机、P2 修改/恢复、真实游戏重入、P1、相机、AppOpt、云控删除和 AVC 全功能回归。0.20.3 的 graceful stop 判断问题已经在 0.20.4 中修复；当前不再存在“临时 daemon bind”或“待刷验证”状态。

#### 0.20.3 刷后发现的真实问题

0.20.3 开机后 `zui_control_zuipp_reload_state` 为：

```text
state=error;stage=stop_services;oldPid=4750
```

daemon 日志同时出现：

```text
cmd: Failure calling service activity: Failed transaction (2147483646)
cannot stop ZuiPP service before reload: com.zui.pp/...OverHeatStatsService
```

根因有两部分：

1. 本机 ZUI 的 `am stop-service` 即使打印 `Service stopped`，退出码仍为 255；目标未运行时打印 `Service not stopped: was not running.`，退出码同样为 255。0.20.3 用 shell 退出码判断成功，因此把真实成功误判为失败。
2. 0.20.3 把 daemon 的 `/data/vendor/zui_control/log/controld.log` 文件描述符直接继承给 `am/cmd`。该描述符经 Binder 进入 system_server 后触发 system_server 对 shell-owned 日志的 append AVC，进而出现 `Failed transaction`。这不是缺少一条应该放宽的 SELinux 权限，而是不该跨 Binder 传入该 FD。

0.20.4 不改四个精确 service 的顺序，也不扩大 SELinux。它先在命令替换中捕获 `am stop-service` 输出，再由 daemon 自己写日志；只接受上述两种明确成功文本。瞬时 Binder 失败最多重试 5 次；5 次都没有明确成功文本才发布 `stage=stop_services` 并禁止 SIGTERM。

#### 0.20.4 已完成的修复前临时验证（历史证据）

- 开机 active reload：ZuiPP 从 `11699:115086` 切换为 `23574:153977`，四个 service 均得到明确 stop/no-op 文本，终态 `state=done;reason=boot_active;stableSeconds=10`，没有 fatal/NPE。
- P2 修改：把鸣潮首温区 GPU 请求从 720000 改为 710000，exact ACK done，profile、active/system XML、bind mount 和 hash 全部闭合，ZuiPP 只从 23574 切到 25079 一次并稳定 10 秒。
- P2 恢复：恢复 720000 后 exact ACK done，ZuiPP 只切到 26547 一次；原始 game/performance hash 分别恢复为 `cf787905a1a2d9a3afae69b7a48272ff0d86ff0e35c99d9a305e3dc31b634c1d` / `447277c22ba0d2cd378b05e2c99bc80d89d4fb57717deb78ac5ca760407e7a92`。
- 鸣潮重入：看到同一 package 的 `onGameAppStart`、GameHelper `initGameHelper`、ZuiPP `notifyPerfStatus` 和 `writeSavageMode::open::1,status::0`，证明不是只停在 provider 返回值。
- P1 回归：system_server owner、daemon refresh disabled、未授权 Binder 拒绝、60Hz 可逆 profile 测试和恢复默认 120 全部通过；最终 profile 只有 default 120。
- 相机回归：系统相机、`IMAGE_CAPTURE`、`VIDEO_CAPTURE` 均通过；boot ID、SurfaceFlinger/cameraserver PID+starttime 和 tombstone 列表不变，无 `No matching frame rate modes`、DEAD_OBJECT 或 fatal。
- AppOpt 回归：测试 App 设为 `0-1` 后 34 个存活线程全部命中，删除规则 exact ACK done；最终 0 规则、service stopped、无 PID、无 enabled flag。
- 云控继续为 null/不存在。修复后没有新增项目 AVC；旧 0.20.3 的 system_server append denial 是本次根因证据。原厂 cameraserver/vendor_display_prop 等 denial 仍不是项目问题，禁止扩大权限。

以上 live 验证当时使用 `/data/local/tmp` 到 `/system/bin/zui_controld` 的临时 bind，用于在重封前证明修复逻辑成立。后续已经通过 9008 把同一生产内容持久写入系统分区，并在全新开机后重复验证；不要再把当前设备描述为临时 bind。

#### 33/0.20.4 发布事实

- 修复生产 commit：`523990b4062f06d8bf5ee27713ac55e5348458ad`
- 9008 自动化 commit：`362ae7f65dcd3a12b95cebaefc08ef88b4d4f138`
- GitHub Actions App build run：`32147587045`，结论 `success`：<https://github.com/xmy953538104/ZuiControl/actions/runs/32147587045>
- GitHub Actions qdl-rs Windows build run：`32149002631`，结论 `success`：<https://github.com/xmy953538104/ZuiControl/actions/runs/32149002631>
- 下载并用于 payload 的 CI artifact：`D:\3.VScode\Mi\work\ci_32147587045`
- package：`com.zui.zuicontrol`；版本：33/0.20.4；system 路径：`/system/priv-app/ZuiControlV33/ZuiControl.apk`
- release 证书 SHA-256：`3fecf3a72ca0e0f24991d49e7306ef4a711711f48a66070755eb0237ecb3ed94`
- CI APK、payload APK、最终 system 内嵌 APK和两个 sidecar APK SHA-256：`3534bf88224cf7c61b317e35720dc7ae4ca9ec18de70091a66ad94adc24cb18f`
- 最终刷机目录：`D:\3.VScode\Mi\【B刷机】187`

```text
5fc24c5b36ba7394125b87b681b62e38575172057c78417a0fa24746815be76b  boot.img
8b31b4e742794e3e8c3c5d05dd2fe7307f53c7aeaa76a10833c810c48fd3c80c  super.img
9db326d3d605885c4afdac6b0883dc3f9c0bc2b9b1b3766cc4808945015967f5  vbmeta.img
2baf3432bda31c74cc421932b8c7292f38e4750e36bc09a6bdfb838b457a27f5  vbmeta_system.img
3534bf88224cf7c61b317e35720dc7ae4ca9ec18de70091a66ad94adc24cb18f  ZuiControl-v19-system.apk
3534bf88224cf7c61b317e35720dc7ae4ca9ec18de70091a66ad94adc24cb18f  ZuiControl-v19-release.apk
```

发布顺序为同一 CI run 的 release APK/payload → Apply payload → 重建 `system_a.img` → `SignNoFecDryRun` → `SignNoFec` → 签名后重新 `PackSuper`。最终 `VerifyZuiControlFlashPackage.ps1` 从刷机目录的成品 super 反抽全部动态分区和 system EROFS、解码成品 `services.jar`，返回 `ok=true`；33/0.20.4、V33、release 证书、daemon 输出判断/重试、P1/P2/AppOpt/SELinux 边界和 sidecar hash 全部通过。

#### 9008 自动化已完成并通过首次真实刷写

- 现有 GeekFlashTool 全量目录包含 99 个 program 项，会写 GPT、persist、FRP、modemst 等与 ZuiControl 更新无关的高风险区域，自动化禁止直接使用这些 XML。
- `scripts/PrepareZuiControl9008Package.ps1` 校验最终 SHA256、镜像长度和原始 XML 的 sector/LUN 字段，然后在 `D:\3.VScode\Mi\flash\ZuiControl_9008_SAFE` 生成独立包。该包只有一个 `rawprogram_zuicontrol.xml`、没有 patch XML，只写 7 项：LUN0 super、vbmeta_system_a/b；LUN4 boot_a/b、vbmeta_a/b。
- `scripts/FlashZuiControl9008.ps1` 默认只做 Preflight。`-Mode Flash` 会先校验唯一 ADB 设备 `HA25HSZM`、TB321FU、187 和 root，再 `reboot edl`，只接受一个 `05C6:9008` COM 口，调用 Qualcomm 官方开源 `qdl-rs` 串口后端执行上述 7 项，要求成功标记后自动 reset system，并等待 Android boot completed 和 33/0.20.4。
- qdl-rs 固定使用 Qualcomm `qdlrs` commit `412f90bc08cc3a687d552ff599da29043c4f54f4`。Windows 构建 workflow 是 `.github/workflows/build-qdlrs-windows.yml`；详细步骤和恢复边界见 `docs/ZuiControl_9008_AUTOMATION_GUIDE_2026-08-18.md`。

2026-08-18 首次真实自动刷写已成功：脚本从正常 Android 自动执行 `reboot edl`，识别唯一 `COM3`，完成 Sahara/Firehose/UFS 配置，以约 42–43 MB/s 写完 13 GB super 和其余 6 个双槽 boot/vbmeta 项，qdl-rs 返回 `All went well! Resetting to system`。设备自动回到 Android，脚本确认同一 ADB serial `HA25HSZM`、`boot_completed=1` 和 PackageManager 33/0.20.4。完整日志：

```text
D:\3.VScode\Mi\flash\Log\ZuiControl_qdlrs_2026-08-18_22-36-35.log
```

qdl-rs Windows 二进制 SHA-256：

```text
54330234768a651540eabc108d72cad506c6f3511ebb94e04fec90ca7844332a
```

#### 0.20.4 持久刷后验证（当前设备真实状态）

- 设备/系统：`HA25HSZM`，TB321FU，`TB321FU_CN_OPEN_USER_Q00040.0_U_ZUI_16.1.11.187_ST_250227`；App 来自 `/system/priv-app/ZuiControlV33/ZuiControl.apk`，PackageManager 为 33/0.20.4。
- 开机 P2：active XML 在 ZuiPP 启动后 bind，reload 从 PID 4646 切到 6972，`state=done;reason=boot_active;stableSeconds=10`；没有 0.20.3 的 `Failed transaction` 或 `stage=stop_services`。
- P1：普通 shell Binder 调用被拒绝；system UID 对 Settings 做 60Hz 可逆规则时 target/actual 都为 60，删除规则后都回到 120；最终 `refreshOwner=system`、`daemonRefreshDisabled=true`，原 profile 未被污染。
- P2 事务：鸣潮首温区 GPU 720000→710000 得到 exact done ACK，ZuiPP 6972→12039 并稳定 10 秒；恢复 720000 再得到 exact done ACK，ZuiPP 12039→12819，profile 逐字恢复，active/system 两对 hash 回到本节发布 hash。
- 鸣潮重入：第一次熄屏启动被 ZuiMode 正确判为非游戏态，因此不计成功；唤醒后从桌面重入，看到同一 package 的 `onGameAppStart`、GameHelper `initGameHelper`、TAssistant、ZuiPP `notifyPerfStatus`、`writeSavageMode::open::1,status::0`，并输出恢复后 CPU/GPU 三温区。这个对照证明“App 在前台”不等于“ZUI 游戏态已进入”。
- 相机：系统相机、`IMAGE_CAPTURE`、`VIDEO_CAPTURE` 全部打开；boot ID 不变，SurfaceFlinger PID 1639、cameraserver PID 1938 不变，没有新 tombstone 或匹配 fatal/DEAD_OBJECT。
- AppOpt：鸣潮 `0-1` 预设 exact done，服务运行且主进程 209 个线程全部为 `Cpus_allowed_list: 0-1`；删除 exact done 后强停目标 App，最终 0 规则、service stopped、无 AppOpt PID。
- 云控：hosts 仍为官方 56 字节/`425c3e713d5bae19b031bc8639c20c6a23e311a54647ba1824cbf45969a11ff4`，旧 Settings/iptables/文件链不存在。
- 最终 XML：active/system game hash 均为 `cf787905a1a2d9a3afae69b7a48272ff0d86ff0e35c99d9a305e3dc31b634c1d`，performance hash 均为 `447277c22ba0d2cd378b05e2c99bc80d89d4fb57717deb78ac5ca760407e7a92`。
- 没有新增项目 AVC。仍有原厂 system_server DAC、cameraserver 读取 vendor_display_prop 等 denial；功能成功时同样出现，禁止为其扩大 ZuiControl 权限。唯一匹配 NPE 来自联想应用商店 `ImeiHelper/MiitSDKTool`，不是 ZuiControl/ZuiPP/相机。

#### 新聊天当前最短下一步

1. 先只读确认当前仍是 `HA25HSZM`、33/0.20.4、V33 路径和四个最终 hash；这些已在 2026-08-18 持久刷后全部通过，不要再次制造无意义的全套写入测试。
2. 用户若报告具体体验问题，先复现并同时记录屏幕唤醒/ZuiMode 游戏态、exact ACK、XML hash、ZuiPP PID/reload 和真实原厂重入链；不能只看频率节点或 App 焦点就下结论。
3. 下一版本只有在出现可复现的新缺陷时再制作。9008 日常更新只允许使用 `FlashZuiControl9008.ps1` 生成的 7 项安全包，禁止调用原始 99 项全量 XML。
4. 仍不进入 FPS cap、不改 P1、不恢复 direct CPU/GPU sysfs、provider_direct、云控或旧 AsoulOpt。

### 0.5 2026-08-18 0.20.2 实机全功能闭环与 0.20.3 P2 reload 修复包（历史，已被 0.6 覆盖）

本节覆盖 0.4 的“待刷”状态、版本、hash 和下一步。设备已经刷入并完整验证 31/0.20.2；本轮唯一新增的真实缺陷是 ZuiPP XML reload 会让原厂 `OverHeatCleanService` 以空 Intent 重启并崩溃一次。该问题已用实机对照实验定位并修复为 32/0.20.3。0.20.3 已完成 CI、签名、重封和最终 super 反向校验，但尚未刷入，所以不能提前宣称新 reload 路径已持久实机通过。

#### 31/0.20.2 的真实刷后结果

- 身份与安全状态通过：PackageManager 和 `/system/priv-app/ZuiControlV31/ZuiControl.apk` 均为 31/0.20.2；ADB、`su` 可用，SELinux Enforcing；旧 V30、旧 `ZuiControl` 目录和 cache helper 不存在。
- P1 完整通过：未授权 Binder 调用被拒绝；system_server 是唯一刷新率 owner，daemon refresh disabled，场景 `raw/current/last/applied` 与 profile 记忆正确；60/90/120 的目标和物理 mode 一致。144/165 在持续触控/渲染时能达到目标，空闲时会被 adaptive display 降回较低 mode，这是“目标/上限”语义，不是规则失效。测试后已恢复默认 120，未生成 SystemUI profile。
- 相机完整通过：系统相机、外部 `IMAGE_CAPTURE` 和 `VIDEO_CAPTURE` 均没有设备重启；SurfaceFlinger/cameraserver PID 与 starttime 不变，无新增 tombstone、`No matching frame rate modes`、CameraProvider `DEAD_OBJECT` 或 fatal。
- P2 事务与运行链通过：对鸣潮把 GPU 首温区请求 720000 改为 710000，exact ACK、profile、active XML、bind hash、共享 CPU level ID、ZuiPP 新 PID 稳定 10 秒均闭合；重新进入鸣潮后看到真实 system game-start 和 ZuiPP 游戏识别；随后已恢复原 profile 和原 XML hash。畸形请求失败且旧配置保持不变。CPU/GPU 是 OEM 请求，不是 hard cap；热控仍可把运行值压低。
- AppOpt 完整通过：测试用户 App 的 `0-1`、`0-4`、`5-7`、`0-7` 四个预设均在强停重开后使全部存活线程落入对应 CPU 集合；系统 App 和非法 preset 被拒绝；修改、删除、重复删除、全局停止和再次启动均正确。最终已恢复 0 规则、service stopped、无 PID、无 enabled flag。
- 云控删除继续通过：官方 hosts 为 56 字节两行 localhost，旧脚本、runtime、setting 和 iptables/ip6tables 链均不存在。
- 项目相关 AVC 为零。相机日志仍有原厂 `cameraserver` 查询 `vendor_display_prop` 的 denial，但不属于 ZuiControl、没有造成相机故障，禁止为此扩大项目 SELinux 权限。

#### 实机发现的 ZuiPP reload 缺陷和最小修复

0.20.2 每次 P2 保存会直接向 `com.zui.pp` 发送 SIGTERM。原厂 persistent 进程重启时，`OverHeatCleanService` 收到空 Intent 并在 `OverHeatCleanService.java:139` 触发一次 NPE；系统随后再次拉起进程并稳定，因此最终 P2 看似成功，但中间存在一次真实应用崩溃和额外重启。

实机对照结果：只停 `OverHeatCleanService` 仍会复现；在 SIGTERM 前依次停止下列四个原厂 service，则 ZuiPP 只发生一次干净 PID 切换，无 fatal，并重新读取 PerformanceConfig：

1. `com.zui.pp/com.zui.power.overheat.OverHeatStatsService`
2. `com.zui.pp/com.zui.power.overheat.OverHeatCleanService`
3. `com.zui.pp/com.zui.power.overheat.StubbornStatsService`
4. `com.zui.pp/.service.MainService`

0.20.3 因而只在既有 reload 前加入这四个精确 `am stopservice`。任一步停止失败都会发布 `state=error;stage=stop_services` 并禁止发送 SIGTERM；没有恢复 provider_direct、direct CPU/GPU sysfs、daemon 刷新率或 FPS cap，也没有修改 P1。

#### 32/0.20.3 发布事实

- 生产代码 commit：`8c4ae5327af59006a738a13d41103c80f82d40c3`
- GitHub Actions run：`32141595553`，结论 `success`：<https://github.com/xmy953538104/ZuiControl/actions/runs/32141595553>
- 下载并用于 payload 的 CI artifact：`D:\3.VScode\Mi\work\release_0.20.3_32141595553`
- package：`com.zui.zuicontrol`；版本：32/0.20.3；system 路径：`/system/priv-app/ZuiControlV32/ZuiControl.apk`
- release 证书 SHA-256：`3fecf3a72ca0e0f24991d49e7306ef4a711711f48a66070755eb0237ecb3ed94`
- CI APK、payload APK、最终 system 内嵌 APK 和两个旁载 APK SHA-256：`9c5c7f104607cda4f38c4a1930a139d33a22d60966c1154d0b40e0f74370a985`
- 最终刷机目录：`D:\3.VScode\Mi\【B刷机】187`

```text
5fc24c5b36ba7394125b87b681b62e38575172057c78417a0fa24746815be76b  boot.img
58342f892641faa1e1a80535a5ab67b131bdcc60876505f32c255222b30ffcc2  super.img
9db326d3d605885c4afdac6b0883dc3f9c0bc2b9b1b3766cc4808945015967f5  vbmeta.img
4f228e1987403b9aaec46eadf2bce28b8fb1eea4a49945646423affae1938550  vbmeta_system.img
9c5c7f104607cda4f38c4a1930a139d33a22d60966c1154d0b40e0f74370a985  ZuiControl-v19-system.apk
9c5c7f104607cda4f38c4a1930a139d33a22d60966c1154d0b40e0f74370a985  ZuiControl-v19-release.apk
```

发布顺序为：下载同一 CI run 的 release APK/payload → Apply payload → 重建 `system_a.img` → `SignNoFecDryRun` → `SignNoFec` → 签名后重新 `PackSuper`。最终 `VerifyZuiControlFlashPackage.ps1` 从刷机目录的成品 super 反抽动态分区/system EROFS、解码成品 `services.jar`，返回 `ok=true`；V32、签名、四个 graceful stop 组件和失败门禁、P1/P2/AppOpt/SELinux 边界、AVB 镜像均通过。

#### 刷入 0.20.3 后只需完成的最终确认

1. 确认 PackageManager 和 `/system/priv-app/ZuiControlV32/ZuiControl.apk` 为 32/0.20.3，旧 V31/V30/旧 `ZuiControl` 目录不存在。
2. 做一次可恢复的 P2 保存：要求 exact ACK done、四个 service stop 成功、ZuiPP 只有一次新 PID 切换并稳定 10 秒；全 buffer logcat 中不得再出现 `OverHeatCleanService` fatal/NPE。随后强停并重进游戏，确认原厂 game-start/ZuiPP 识别，再恢复原 profile。
3. 快速回归 P1 目标/actual、相机一次和 AppOpt 一个预设，并检查项目相关 AVC。0.20.3 没改这些链路，0.20.2 的完整结果仍是有效基线。

当前明确边界：FPS cap 未交付；144/165 是 adaptive display 目标/上限，空闲会降频；P2 是原厂 XML/OEM 请求而不是硬 cap，只对 ZUI 识别的游戏承诺重入链；AppOpt 只支持普通用户 App 的整包 CPU 预设，修改后必须强停重开目标 App；云控已删除且无用户操作入口。

### 0.4 2026-08-18 0.20.1 实机闭环与 0.20.2 AppOpt 修复包（历史，已被 0.5 覆盖）

本节覆盖 0.3 的“待刷”状态、版本、hash 和下一步。当前设备已刷并完整验证 30/0.20.1；由实测发现的 AppOpt 包校验问题已经修复为 31/0.20.2，并完成 CI 签名、镜像重建和最终 super 反向抽查。0.20.2 尚未刷入，不能把临时 bind 验证写成新镜像的持久实机验证。

#### 30/0.20.1 的真实刷后结果

- 身份闭合：PackageManager 与 `/system/priv-app/ZuiControlV30/ZuiControl.apk` 均为 30/0.20.1，APK SHA-256 为 `7e60f25103ec29521652c6b3e827dc05e7a6150d9836aa1c5d808fd37bebeac8`；旧 `/system/priv-app/ZuiControl` 和旧 cache helper 均不存在。ADB、`su` 可用，SELinux 为 Enforcing。
- P1 通过：`raw/current/last/applied`、默认 120、system owner、daemon refresh disabled、adaptive render 均正确；未授权 shell Binder 调用被拒绝。用 system UID 可逆测试 60Hz 时 target、actual 和 active mode 一致，删除测试规则后已恢复默认 120；没有生成 SystemUI profile。
- 相机通过：分别启动系统相机、外部 `IMAGE_CAPTURE` 和 `VIDEO_CAPTURE`，boot ID、SurfaceFlinger/cameraserver PID 与 starttime 均未变化，没有新增 tombstone、`No matching frame rate modes`、`DEAD_OBJECT` 或 fatal。旧“点相机像重启”的 SurfaceFlinger 崩溃链未复现。
- P2 事务和运行链通过：鸣潮原 profile、active XML 与 `/system/etc` bind hash 先保存；把首温区 GPU 请求从 720000 改到 710000 后，exact ACK done、profile 和两份 XML hash 同步变化、LimitConfig ID 变化、ZuiPP PID 更新并稳定 10 秒。强停并重进鸣潮后看到真实 `onGameAppStart`、GameHelper/provider 连接及 ZuiPP 游戏态；随后恢复原 profile 和原 hash。畸形请求返回 failed，profile/XML hash 不变。再次重启设备时，即使 hash 未变，boot bind 后仍将 ZuiPP 从旧 PID 4730 重载到新 PID 6978，并得到 `state=done;reason=boot_active;stableSeconds=10`。
- P2 的 CPU max 节点能反映请求；测试时 GPU 因约 70°C、`thermal_pwrlevel=7` 被原厂热控压到 500MHz，因此节点不严格等于输入不能单独判失败。这里交付的是 XML/OEM 性能请求，不是 direct sysfs 硬 cap，也不恢复自建 provider bridge。
- 云控删除继续通过：hosts 为官方 56 字节两行 localhost，旧脚本、runtime、setting 和 iptables/ip6tables 链均不存在。

#### AppOpt 实机发现、根因与修复

0.20.1 首次给用户 App `com.xmy.ap` 保存 `0-1` 时，ACK 正确从 processing 进入 failed，配置回滚也正确，但规则无法生效。根因不是亲和度二进制，而是 daemon 在读取 `applist.conf` 的 `while ... done < file` 循环中调用 `pm path`：PackageManager 子进程继承了受保护配置文件作为标准输入，触发 system_server 对 `/data/vendor/zui_control/appopt/applist.conf` 的 SELinux 读取拒绝，最终把正常用户 App 误判为不允许。

最小修复为 `pm path "$1" </dev/null`，切断无关文件描述符继承；没有扩大 SELinux 权限，也没有放宽 set 的“已安装 `/data/app` 用户 App”门槛。同时把空的 `init.svc.zui_appopt` 健康值规范为 stopped，并让 0 活动规则升级时恢复当前安全的仅注释模板。

在 0.20.1 设备上用重启即失效的临时 bind 替换修复版 daemon/prepare 后，已真实验证：

- `0-1`：采样 34 个线程，全部落在 CPU 0-1；
- `0-4`：稳定重采样 28 个线程，全部落在 CPU 0-4；
- `5-7`：采样 36 个线程，全部落在 CPU 5-7；
- `0-7`：采样 39 个线程，全部落在 CPU 0-7；
- AppOpt 运行域为 `u:r:performanced:s0`，没有新增 AppOpt AVC；系统 App set 被拒绝且原规则不变；删除最后规则及重复删除均 done；最终已恢复 0 规则、service stopped、无 PID、无 enabled flag。

第一次 `0-4` 采样中有一个线程恰好退出，导致单次线程数变化；目标进程稳定后重采样全部符合。这是 `/proc` 线程生命周期采样竞争，不是规则越界。

#### 31/0.20.2 发布事实

- 生产代码 commit：`259ab36314d580be63f3382f388c6f99e85cf297`
- GitHub Actions run：`32134948150`，结论 `success`：<https://github.com/xmy953538104/ZuiControl/actions/runs/32134948150>
- 下载并用于 payload 的 CI artifact：`D:\3.VScode\Mi\work\release_0.20.2_32134948150`
- package：`com.zui.zuicontrol`；版本：31/0.20.2；system 路径：`/system/priv-app/ZuiControlV31/ZuiControl.apk`
- release 证书 SHA-256：`3fecf3a72ca0e0f24991d49e7306ef4a711711f48a66070755eb0237ecb3ed94`
- CI APK、payload APK、最终 system 内嵌 APK和两个旁载 APK SHA-256：`b59b4d0bba284bbb4416ef2f854ddf9e9680601c1c09ce6b10e87b1e456fa374`
- 最终刷机目录：`D:\3.VScode\Mi\【B刷机】187`

```text
5fc24c5b36ba7394125b87b681b62e38575172057c78417a0fa24746815be76b  boot.img
78de34e36e3244c2c188e8547de164165f6c956e222efc0a4be7a209ecd78ce1  super.img
9db326d3d605885c4afdac6b0883dc3f9c0bc2b9b1b3766cc4808945015967f5  vbmeta.img
88811d64351dd449b8addad4775b045e44e48352fa8893a7e9ac910c7d784002  vbmeta_system.img
b59b4d0bba284bbb4416ef2f854ddf9e9680601c1c09ce6b10e87b1e456fa374  ZuiControl-v19-system.apk
b59b4d0bba284bbb4416ef2f854ddf9e9680601c1c09ce6b10e87b1e456fa374  ZuiControl-v19-release.apk
```

实际发布顺序为：下载同一 run 的 release APK/payload → Apply payload → 重建 `system_a.img` → `SignNoFecDryRun` → `SignNoFec` → 签名后重新 `PackSuper`。最终 `VerifyZuiControlFlashPackage.ps1` 从刷机目录的成品 super 反抽动态分区和 system EROFS、解码成品 `services.jar`，返回 `ok=true`；V31 版本/签名、daemon 修复、AppOpt 默认配置和权限边界、P1/P2 生产标记、AVB 镜像均通过，旧 V30/旧 app 目录和 cache helper 被拒绝。

#### 刷入 0.20.2 后只需完成的持久确认

1. 确认 PackageManager 和 `/system/priv-app/ZuiControlV31/ZuiControl.apk` 为 31/0.20.2，旧 V30 与旧 `ZuiControl` 目录不存在。
2. 在 AppOpt 中对普通用户 App 依次保存 `0-1`、`0-4`、`5-7`、`0-7`；每次等 exact ACK done，强停并重开目标 App，再抽查全部存活线程的 affinity。系统 App必须继续被拒绝。最后删除规则，确认 stopped、无 PID、0 规则和无新增 AVC。
3. P1/相机和 P2 已在相同 0.20.1 基线完整通过；0.20.2 未改这些架构，只做回归抽查即可。仍应查看 dmesg 和全 buffer logcat AVC，以确认修复持久落盘后的设备策略表现。

Windows 侧仍没有单独完成完整 `secilc` 编译；最终镜像策略已被 verifier 反抽检查，0.20.1 live SELinux 也通过，但 0.20.2 的最终结论仍以刷后 AVC 为准。

### 0.3 2026-08-18 实机闭环与 0.20.1 待刷包（历史，已被 0.4 覆盖）

本节覆盖 0.2 的包版本、hash、发布事实和“下一步”，也覆盖后文旧 provider_direct、云控、AppOpt 只读状态与固定延时请求流程。P1 没有进入 FPS cap，也没有恢复 direct CPU/GPU sysfs 或旧 AsoulOpt。

#### 已在 0.20.0 设备上得到的真实结果

TB321FU 已连接，ADB、`su` 可用且 SELinux 为 Enforcing。对当时已刷的 0.20.0 做完了只读与可逆实测：

- P1/Binder/QS/刷新率链路通过。`zui_control` 存在，system_server 是唯一刷新率 owner，daemon 显示 refresh disabled；未授权 shell Binder 调用被拒绝，真实场景/current/last、profile 记忆、QS 不学习 SystemUI、目标与实际 mode 均闭合。
- 相机连续执行 8 轮系统相机和外部 capture 入口，没有设备重启，没有 SurfaceFlinger/cameraserver PID 起始时间变化，没有新增 tombstone，也没有再出现 `No matching frame rate modes`。因此 61a4b26 的 adaptive render vote 根因修复已被实机确认；本轮不再改 P1。
- 云控删除实机通过：hosts 是官方 56 字节两行 localhost 文件；没有旧脚本、runtime 目录、setting、iptables/ip6tables chain 或 jump。云控不再是产品功能，不恢复旧链。
- P2 active XML 与 `/system/etc` bind 内容 hash 一致，鸣潮三槽、`ThermalConfig=0 0 0`、共享 CPU level ID 和四簇频率字典均通过结构检查。保存后“数值不响应”不是一个单点：旧 App 会在固定 13 秒后解锁且共享单槽可被覆盖；XML promote 成功也可能吞掉 ZuiPP reload 失败；而 ZuiPP 重启后，TAssistant/原厂 game state 必须等下一次真实 `onGameAppStart` 才重新连接。所以必须等待真实终态，再彻底退出并重进游戏。
- AppOpt 用临时 live SELinux 规则对用户 App `com.xmy.ap` 验证了四个整包预设：`0-7`、`0-4`、`5-7`、`0-1` 均使目标进程全部线程落到对应 CPU 集合，没有新增相关 AVC。测试后已恢复 0 条规则、service stopped、无 PID。线程名规则和自由文本没有验证，不交付。
- 设备 system APK 已是 0.20.0，但 PackageManager 仍报告 28/0.19.9，根因是同一 `/system/priv-app/ZuiControl` codePath 的旧扫描状态，并非 APK 内容错误。0.20.1 改用新目录 `ZuiControlV30`，同时删除旧目录；禁止通过删除 PackageManager cache 解决。

#### 0.20.1 的最小修复

当前 App 为 `versionCode=30` / `versionName=0.20.1`：

- App 删除 `onCreate` 自动写 `status`，不再用 720ms/13s 定时器或乐观修改列表。每个请求使用唯一 ID，并只接受同 ID、同 command 的四字段终态 ACK：`id|done|cmd|detail` 或 `id|failed|cmd|reason`；等待上限 120 秒，pending 请求不能被新请求覆盖。
- daemon 将请求和终态 ACK 作为两行原子 receipt 持久化，重启可安全 replay；旧单行 receipt 只迁移为失败，不会把历史命令再执行一次。
- P2 profile、active XML、bind/reload 成为一笔可恢复事务。生成、promote、mount 或 ZuiPP 稳定重载任一步失败，都会恢复旧 profile、旧 active 和旧 runtime；reload 失败不再被 `|| true` 吞掉。
- daemon 每次启动都先使旧 `last_reloaded_hash` 失效。即使 XML hash 与上次开机相同，boot bind 后若 ZuiPP 正在运行，仍会真实重启并确认新 PID 稳定 10 秒；若 ZuiPP 尚未运行，允许终态 `skipped;reason=zuipp_not_running`，其首次启动会读取已经 bind 的 active XML。这样不会让旧跨开机 receipt 跳过一次本应执行的 reload。
- 不恢复自建 GameModeProvider bridge。保存成功后由原厂 GameHelper 在真实游戏重入时调用 provider；provider 返回 rows=1 只表示模式值被接纳，不能冒充 XML 已应用。
- AppOpt 增加最小 UI：只可选择已安装用户 App，只提供 `0-7`、`0-4`、`5-7`、`0-1` 四个整包预设，可修改、删除或全局停止。set 严格要求 `/data/app`；删除允许清理已卸载的 stale 规则，并具备崩溃重放幂等性。默认配置 0 条活动规则，service stopped。
- AppOpt SELinux 只加入实机证明需要的 `dac_override kill`、配置文件 `watch/watch_reads`、目标进程 `getsched/signull` 和目录探测 dontaudit；成品 verifier 拒绝更宽的 signal/sigkill 或额外 performanced 调度授权。
- App 安装路径改为 `/system/priv-app/ZuiControlV30/ZuiControl.apk`；成品中必须不存在旧 `ZuiControl` 目录、`clear_package_cache.sh` 及 rc cache mutation。

#### 0.20.1 发布事实

- 生产代码 commit：`767a4f964d7e3530bd1e05cdd97ca94a5a234f17`
- GitHub Actions run：`32119890097`，结论 `success`：<https://github.com/xmy953538104/ZuiControl/actions/runs/32119890097>
- CI artifact：`D:\3.VScode\Mi\work\ci_artifacts\zuicontrol_32119890097`
- package：`com.zui.zuicontrol`；版本：30/0.20.1
- release 证书 SHA-256：`3fecf3a72ca0e0f24991d49e7306ef4a711711f48a66070755eb0237ecb3ed94`
- CI APK 与 payload/最终 system APK SHA-256：`7e60f25103ec29521652c6b3e827dc05e7a6150d9836aa1c5d808fd37bebeac8`
- 最终刷机目录：`D:\3.VScode\Mi\【B刷机】187`

```text
5fc24c5b36ba7394125b87b681b62e38575172057c78417a0fa24746815be76b  boot.img
e755cb3544314cef8ecc764dc2325cd2b0fa45d523d499c721afe2a096d99b2d  super.img
9db326d3d605885c4afdac6b0883dc3f9c0bc2b9b1b3766cc4808945015967f5  vbmeta.img
e47c5a7973e3d89e2d96e2827869c3953f3582ffad907cfb967c18d9d5ccc064  vbmeta_system.img
7e60f25103ec29521652c6b3e827dc05e7a6150d9836aa1c5d808fd37bebeac8  ZuiControl-v19-system.apk
7e60f25103ec29521652c6b3e827dc05e7a6150d9836aa1c5d808fd37bebeac8  ZuiControl-v19-release.apk
```

发布只使用上述同一 CI run 的 release APK/payload。实际执行顺序是 Apply payload → 重建 `system_a.img` → `SignNoFecDryRun` → `SignNoFec` → 签名后重新 `PackSuper`。最终 `VerifyZuiControlFlashPackage.ps1` 已从成品 super 反抽全部动态分区和 system EROFS、解码成品 `services.jar`，并返回 `ok=true`。Windows 仍没有完成完整 `secilc` 编译；新包尚未刷入，因此 0.20.1 的持久 SELinux 和开机/runtime 行为仍需刷后验证，不能把静态通过写成新包实机通过。

#### 用户操作与刷后最短验证

1. 刷后先确认 PackageManager 与 `/system/priv-app/ZuiControlV30/ZuiControl.apk` 都是 30/0.20.1，旧 `/system/priv-app/ZuiControl` 不存在。
2. 刷新率：在“刷新率”页选择 App 和 60/90/120/144/165；120 表示默认并删除显式规则。QS 修改上一个真实场景，不要把 fpsCap 字段当成已交付能力。
3. P2：选择 ZUI 已识别的游戏，设置各温区后保存；必须等 UI 明确显示请求 done、XML mounted 和 reload 终态。若 ZuiPP 正在运行，终态应有 `stableSeconds=10`；若当时未运行，允许 `skipped;reason=zuipp_not_running`，首次游戏启动会读取已 bind 的 active XML。随后完全强停/退出目标游戏并重新进入。节点值是 OEM 请求结果证据，不要求瞬时严格等于输入值。
4. AppOpt：系统页添加用户 App，选择四个预设之一并等待 done；随后强停并重开目标 App。修改、删除规则或“全局停止”后也要重启目标 App，旧进程线程不会自动重建。默认 0 规则时保持 stopped。
5. 刷后检查 dmesg 与全 buffer logcat 的 AVC，并复测 P1/相机、P2 失败回滚/开机同 hash reload、AppOpt 四预设与停止。不要测试已经删除的云控/provider_direct 链。

剩余边界：P2 依赖原厂识别游戏和 GameHelper/TAssistant 链，厂商热控或更高优先级请求仍可覆盖，因而不是硬 cap；Settings.System 仍是高权限单请求槽，App 已用精确 ACK 串行化，但长期可考虑收口到签名校验 Binder；AppOpt 只交付整包 CPU 集合，不交付线程名规则。以上边界不构成本轮恢复 direct sysfs/provider 或扩大 SELinux 权限的理由。

### 0.2 2026-08-17 P2/云控重构（历史，已被 0.3 覆盖）

本节覆盖后面的旧 P2 三模式/provider 重入、云控实现、28/0.19.9 推荐包和对应验证步骤。P1 相机崩溃修复保持不变，没有进入 FPS cap。

当前目标 App 为 `versionCode=29` / `versionName=0.20.0`。本轮依据设备实测完成以下收敛：

- 云控功能删除。`/system/etc/hosts` 恢复为从官方 ZUI 16.1.11.187 `system_a.img` 抽出的 56 字节默认文件；删除 boot/property 触发、iptables 脚本、daemon cloud 目录和日志导出。升级脚本只做一次旧 setting/runtime 清理。
- 每个 package 只保存一份当前性能 profile；旧数据迁移时优先采用 `balanced`，没有 balanced 才采用第一份旧 profile。新保存统一写 canonical `balanced` 记录。
- 生成的同一份 LimitConfig 镜像到 balanced/powersave/savage 三个原厂槽位，因此不再由 daemon 猜当前 GameMode，也不存在 balanced 抢优先级。
- 每个温区的 Little/Big/Titan/Mega 使用同一个 synthetic CPU level ID（从 900000 起），但四个 Type 下分别保存该簇自己的频率值。这是针对设备实测 ZuiPP HashMap 读取顺序 `LittleCore, MegaCore, BigCore, TitanCore, GPU` 的兼容修复，避免 CPU Type/level 错配导致整条 TAssistant policy 中止。
- 删除 daemon 的 `GameModeProvider/contact` 直接调用和场景轮询。Provider 曾能返回成功但并不证明 ZuiPP 已有目标 game state，属于假成功源。
- 保存并完成 XML promote/ZuiPP 稳定重启后，用户需要退出并重新进入游戏，由原厂 `onGameAppStart` 链路应用 XML。三个模式内容相同，所以原厂选择哪个模式都得到同一配置。
- 只对 ZUI/GameHelper 已识别为游戏的应用承诺触发；给普通 App 生成 XML 条目不等于原厂会触发游戏链路。App UI 已明确提示此边界。
- CPU/GPU 是原厂性能 profile 请求，不是硬 cap。厂商热控或其他更高优先级请求仍可进一步压低或覆盖；禁止因此恢复 direct cpufreq/KGSL sysfs。
- ZuiPP reload 在新 PID 出现后连续稳定 10 秒才发布 done，若进程重启则重新计时；状态继续带 `needsAppReenter=1`。
- baked baseline 使用 payload 模板双 SHA256 版本戳；模板变化会刷新旧 baseline 并自动用保留的用户 profiles 重建 active XML，修复升级后继续沿用旧 `200 100 300` baseline 的问题。
- 正常 promote 前先备份旧 active 到 `last_good`，成功后不再用新 active 覆盖它；回滚点现在确实代表上一次配置。

针对生成器新增可执行测试 `scripts/TestXmlProfileGenerator.py`，会验证旧多模式数据只选一份、三个 OEM mode block 完全相同、四个 CPU ID 相同且各 Type 频率值正确。

#### 0.20.0 发布事实

- 生产代码 commit：`28f1f8b56628ebbaf5cdbb570fe10e1b250e5b6f`
- GitHub Actions run：`32036170577`，结论 `success`：<https://github.com/xmy953538104/ZuiControl/actions/runs/32036170577>
- 下载并用于 payload 的 CI artifact：`D:\3.VScode\Mi\work\ci_artifacts\zuicontrol_32036170577`
- CI release APK 与最终 system 内嵌 APK：`versionCode=29` / `versionName=0.20.0`
- release 证书 SHA-256：`3fecf3a72ca0e0f24991d49e7306ef4a711711f48a66070755eb0237ecb3ed94`
- 最终刷机目录：`D:\3.VScode\Mi\【B刷机】187`

```text
5fc24c5b36ba7394125b87b681b62e38575172057c78417a0fa24746815be76b  boot.img
ddfa388d1df10f6b609337af4b07ed5ca52a6d4602aebaab1ba88fec0a3970ef  super.img
9db326d3d605885c4afdac6b0883dc3f9c0bc2b9b1b3766cc4808945015967f5  vbmeta.img
16b41e21917b6f8168570ebf1d788fe8ef6ff3b2673dbc9f2fdb1a94d4b4b19a  vbmeta_system.img
66488caf0f2570770f0a6b50d3d74efded47dd172f4334c2469c996495590ee6  ZuiControl-v19-system.apk
66488caf0f2570770f0a6b50d3d74efded47dd172f4334c2469c996495590ee6  ZuiControl-v19-release.apk
```

发布只使用上述 CI release artifact。重建 `system_a.img` 后执行 `SignNoFec`，签名后重新执行 `PackSuper`。最终 verifier 从成品 `super.img` 反抽 system/vendor、解码成品 `services.jar` 并返回 `ok=true`；官方 hosts、云控脚本缺失、版本/证书、P1 注入、P2 daemon 标记和 SELinux/context 静态检查均命中。Windows 侧仍未进行 `secilc` 编译级验证，刷后必须检查真实 AVC。

这在 2026-08-17 当时仍是待刷包（历史）：当时不得把相机、P1、P2 或 AVC 写成实机通过。此后的真实结果和当前 0.20.1 待刷包以第 0.3 节为准。

### 0.1 2026-08-17 P1/AppOpt 覆盖说明（已被 0.2 的包版本覆盖）

本节记录 0.19.9 阶段的 P1/AppOpt 修复事实；其包版本、hash、旧 P2/provider、云控和下一步均已被 0.2 覆盖。P1 相机修复思路和 AppOpt 空规则门槛由 0.20.0 继续保留。

#### 设备实际状态

2026-08-17 已连接 TB321FU，ADB 已授权，`su` 可用且 SELinux 为 Enforcing。设备 App 虽然显示 `versionCode=28` / `versionName=0.19.9`，但系统内嵌 APK SHA256 是：

```text
4a6a48a92a32c64721b16b3e3069a826eed5a73918125e59260db76bad6449f6
```

它来自 2026-06-24 的旧构建，不是当时 2026-07-21 推荐包，也不是本节下面的新修复包。以后不能只用版本号判断是否刷到最新包，必须同时比较 APK 或 `super.img` hash。

旧设备上的只读检查结果：

- `zui_control` 存在；P1 状态为 `refreshOwner=system`、`daemonRefreshDisabled=true`，桌面目标和实际刷新率为 120Hz。
- P2 active XML 与 `/system/etc` 当前 bind 内容 hash 相同；鸣潮 active 条目已经是 `ThermalConfig=0 0 0`，但 system 默认模板仍是旧的 `200 100 300`，说明当前设备不是最新自动修复镜像。
- AppOpt 配置只有注释和示例，活动规则数为 0；旧 init 仍每约 5 秒重启 AppOpt，实际被 SELinux 拒绝 `dac_override` 及读取 `zui_control_data_file` 后退出。
- 已通过项目已有可逆开关执行 `setprop zui_control.appopt stop`，确认 `init.svc.zui_appopt=stopped` 且无 AppOpt PID。没有改 P1、P2 或用户 AppOpt 配置。

#### 相机导致“重启”的已确认根因

`/data/tombstones` 中至少有两组 2026-06-30 的相同崩溃链。真正先崩的是 SurfaceFlinger，不是相机：

```text
No matching frame rate modes for primary range.
physical=[120.00 Hz, 120.00 Hz]
render=[30.00 Hz, 60.00 Hz]
```

调用栈位于：

```text
RefreshRateSelector::constructAvailableRefreshRates
-> RefreshRateSelector::setPolicy
-> SurfaceFlinger::setDesiredDisplayModeSpecsInternal
-> abort
```

随后 CameraProvider 因 SurfaceFlinger Binder 死亡而以 `DEAD_OBJECT` 退出。因此“打开相机就重启”是 P1 刷新率硬投票制造无交集策略导致的系统图形栈崩溃，相机只是触发了 30–60 FPS 的合法约束。其他会提出不同渲染区间的 App 也可能触发同类问题。

旧实现还存在两个结构性问题：

1. 在优先级 8（ZUI 名称实际是 `PRIORITY_AUTH_OPTIMIZER_RENDER_FRAME_RATE`）写入了 `Vote.forPhysicalRefreshRates(hz, hz)`，投票类型与优先级语义不匹配。
2. `DisplayContent.setFocusedApp` 的同步 hook 直接执行 profile、Settings、反射和显示服务调用，违反焦点路径只能轻量上报事件的规则，也解释了除刷新率表面切换外其他联动显得慢和不稳定。

#### 2026-08-17 P1/AppOpt 修复

代码提交：

```text
61a4b26 Fix refresh vote crash and gate empty AppOpt
```

P1 只做根因级最小修复，没有进入 FPS cap：

- 删除 `forPhysicalRefreshRates(hz, hz)` 和 GameHelper 十秒 yield 补丁逻辑。
- 统一使用 `Vote.forRenderFrameRates(0, targetHz)`，配合 ROM 的目标 mode 和 `max=targetHz`；允许相机/视频的 30–60 render 约束与 120Hz 物理显示共存。
- `onFocusedAppChanged` 只提取 package/uid/user/display 原始值并投递到独立 `HandlerThread("ZuiControl")`；profile I/O、Settings、反射和 apply 不再阻塞 WM 焦点关键路径。
- 状态新增 `displayVote=adaptiveRender`。

稳定性优先带来的明确边界：新策略是“目标 mode + render 上限/偏好”，不再承诺在所有相机、视频、热控或系统冲突场景中物理面板绝对硬锁。60/90/120/144/165 的真实 active mode 仍需刷后逐项验证。

AppOpt 当前没有源码和 UI 规则编辑器，不应冒充完整交付。新包的默认行为改为：

- boot completed 只准备配置，不再无条件 `start zui_appopt`。
- daemon 统计有效 `key=value` 规则；0 条规则时拒绝启动、清理 enabled 标记并发布明确状态。
- 有规则时启动后必须连续确认 init 状态为 running 且 PID 存在，失败则停止服务并清理 enabled 标记。
- 外部兼容命令仍叫 `apply_asoul` / `restore_asoul`，内部实际函数和状态统一按 AppOpt 命名；不恢复旧 AsoulOpt。

#### 2026-08-17 当时待刷包（历史）

GitHub Actions：

```text
run_id=32013336830
workflow=Build ZuiControl
head=61a4b2622d7579036fdd2a7b04faa8983986bccc
status=success
artifact_dir=D:\3.VScode\Mi\work\ci_artifacts\zuicontrol_32013336830
package=com.zui.zuicontrol
versionCode=28
versionName=0.19.9
release_cert_sha256=3fecf3a72ca0e0f24991d49e7306ef4a711711f48a66070755eb0237ecb3ed94
```

最终刷机目录：

```text
D:\3.VScode\Mi\【B刷机】187
```

最终 SHA256：

```text
5fc24c5b36ba7394125b87b681b62e38575172057c78417a0fa24746815be76b  boot.img
34de9656be5f7473ffef6ec81070a8c5a409b3ad6a18af9a715eebed290adf2d  super.img
9db326d3d605885c4afdac6b0883dc3f9c0bc2b9b1b3766cc4808945015967f5  vbmeta.img
6d5bc0db0d9ed46aeef05b25c58b496e93e8569980189dbdd40af0b0aa1eab6f  vbmeta_system.img
3f6f09ca6452cd8b881fdd1ebf335405b9a1e9d8ad126783107d5f2f70113e5c  ZuiControl-v19-system.apk
3f6f09ca6452cd8b881fdd1ebf335405b9a1e9d8ad126783107d5f2f70113e5c  ZuiControl-v19-release.apk
```

发布过程只使用上述 CI release APK/payload；重建 `system_a` 后执行 `SignNoFec`，签名后重新执行 `PackSuper`。最终 verifier 已从成品 `super.img` 反抽 system/vendor 并得到 `ok=true`，还解码了成品 `services.jar`，确认：

- 存在 `HandlerThread`、`forRenderFrameRates`、`displayVote=adaptiveRender`。
- 不存在 `forPhysicalRefreshRates`。
- AppOpt init 不再无条件启动，daemon 含 0 规则门槛。
- APK 内嵌/两个 sidecar hash 一致，版本为 28/0.19.9 且为 release 签名。
- P2 鸣潮模板仍为 `ThermalConfig=0 0 0`，XML 五段引用、SELinux/context 和无 direct CPU/GPU sysfs 规则继续通过。

#### 2026-08-17 当时的下一步（历史）

新包尚未刷到真实设备，不能把“最终镜像静态通过”写成“相机问题实机已修复”。下一步只刷上述 hash 的包，然后按以下顺序验证：

1. 先比较设备 `/system/priv-app/ZuiControl/ZuiControl.apk` SHA256 必须为 `3f6f09...13e5c`，再确认 28/0.19.9。
2. 开机稳定性：确认 SystemUI/SurfaceFlinger/system_server 无崩溃循环，`zui_control` 存在，状态含 `displayVote=adaptiveRender`、`refreshOwner=system`、`daemonRefreshDisabled=true`。
3. P1：桌面和多个普通 App 默认 120；分别保存并测试 60/90/120/144/165，观察 target、actual active mode 和重复 apply 防抖。
4. 相机：先打开系统相机，再从第三方 App 调用相机；全程同步抓 SurfaceFlinger/CameraProvider tombstone、logcat 和 active mode，确认没有 `No matching frame rate modes`。
5. QS/SystemUI：下拉 QS 修改上一个真实场景，确认不会生成 SystemUI profile。
6. AppOpt：默认 0 规则时应为 stopped 且无重启/AVC；只有写入真实有效规则并明确启用后才应 running。由于暂无 UI 编辑器和源代码，先只验证门槛和稳定性，不宣称调度效果已交付。
7. P2：核对 active XML 与 `/system/etc` hash、鸣潮 `ThermalConfig=0 0 0`；进入、退出、再进入鸣潮，provider_direct 时间戳每次更新，并结合 ZuiPP/Thermal/KGSL 日志判断实际应用。
8. 云控：核对 hosts、iptables/ip6tables 独立 UID 规则，确认无 UID1000 全断规则。
9. AVC：检查 zui_control、system_server、AppOpt/performanced、shell/init、ZuiPP 和云控相关 denial。

上述验证通过前，不再改 P1，不进入 FPS cap，不恢复 direct CPU/GPU sysfs，也不重新引入旧 AsoulOpt。

> **历史区提示：** 以下第 1～8 节保留 2026-07-21/0.19.9 的调查证据和旧实现说明。凡涉及当前包、P2 三模式/provider、云控或下一步，不得直接执行，必须以第 0.3 节为准。

## 1. 2026-07-21 历史快照

```text
工作区：D:\3.VScode\Mi
当前 App 仓库：D:\3.VScode\Mi\ZuiControl
历史旧仓库：D:\3.VScode\Mi\ZuiperfCtl（不要作为当前代码入口）
设备/系统：TB321FU / ZUI 16.1.11.187
分支：main
remote：git@github.com:xmy953538104/ZuiControl.git
仓库交接前 HEAD：7195c2651eb38da9b3fecd7178298be7957e0092
当前 App：versionCode=28 / versionName=0.19.9
```

2026-07-21 整理交接时设备未连接，因此本文件没有把 2026-06-24 的最终镜像冒充成“已经刷后验证通过”。最新镜像已完成本地构建、签名、PackSuper 和 final super 反抽验证；刷后实机状态仍需新窗口重新确认。

## 2. 历史刷机包（已被 0.20.0 替代）

目录：

```text
D:\3.VScode\Mi\【B刷机】187
```

最新功能代码提交：

```text
6e698a1 Fix Mingchao XML thermal application
```

后续文档提交：

```text
7195c26 Document Mingchao XML thermal fix
```

GitHub Actions：

```text
run_id=28089289333
workflow=Build ZuiControl
head=6e698a1693e64e457c66e289a7ab032149e0fd5e
status=success
artifact_dir=D:\3.VScode\Mi\work\ci_artifacts\zuicontrol_28089289333
```

APK：

```text
package=com.zui.zuicontrol
versionCode=28
versionName=0.19.9
release cert SHA-256=3fecf3a72ca0e0f24991d49e7306ef4a711711f48a66070755eb0237ecb3ed94
```

最终 SHA256：

```text
5fc24c5b36ba7394125b87b681b62e38575172057c78417a0fa24746815be76b  boot.img
4d0d7fe55d8ed37d7fbf1220a39c3baed6631c77e5d5955b61f3936d3eb4f82d  super.img
9db326d3d605885c4afdac6b0883dc3f9c0bc2b9b1b3766cc4808945015967f5  vbmeta.img
f3caa64f53a130ddafcf31d09e03c5ad9d33988ccc318ba0c6b7df5d0d364762  vbmeta_system.img
89293b21225588e5b9ae8c6d4cec1d8b99b6b89fbdde07a6564cd234bb426aea  ZuiControl-v19-system.apk
89293b21225588e5b9ae8c6d4cec1d8b99b6b89fbdde07a6564cd234bb426aea  ZuiControl-v19-release.apk
```

最终验证：

```text
VerifyZuiControlFlashPackage.ps1 -FlashDir "D:\3.VScode\Mi\【B刷机】187"
ok=true
```

验证器已从最终 `super.img` 反抽真实 system/vendor 内容，不只是检查源码或 `work/unpack`。

## 3. P1 刷新率状态

P1 已经完成 system_server ownership 迁移并经过旧包实机验证。本轮交接没有修改 P1。

主链路：

```text
DisplayContent.setFocusedApp(ActivityRecord)
-> ZuiControlHooks.onFocusedAppChanged(record, displayId)
-> ZuiControlService
-> /data/system/zui_control/profiles.prop + 内存 profile
-> DisplayManagerInternal display vote
-> active display mode
```

必须保持：

- `system_server` 是唯一刷新率 owner。
- 未配置场景默认 120Hz。
- QS 修改 `lastNonTransientScenePackage`，不能学习 SystemUI。
- daemon 不恢复 `refresh_tick`、`learn_refresh`、peak/min settings 抢写或开机强写 120。
- v19 只承诺 displayHz lock，不把 FPS cap 当作已交付能力。

## 4. P2 XML 当前实现

生产主链路：

```text
ZuiControl 保存性能 profile
-> zui_controld 写 /data/vendor/zui_control/performance/profiles.prop
-> XmlProfileGenerator 生成 staging game_policy.xml/performanceconfig.xml
-> 校验并 promote 到 /data/vendor/zui_control/zuipp/active/
-> bind mount 到 /system/etc/game_policy.xml 和 performanceconfig.xml
-> hash 闭合后受控 reload com.zui.pp
-> system_server 场景事件进入目标 App
-> daemon 直接 content update ZuiPP GameModeProvider/contact
-> ZuiPP 按当前 gameMode 重新应用 XML LimitConfig
```

边界：

- CPU/GPU 都走 XML + ZuiPP/GameHelper 原厂链路。
- daemon 不生产直写 CPU cpufreq 或 KGSL GPU sysfs。
- `balanced=0`、`powersave=1`、`savage=2`。
- 每次重新进入有 profile 的 App，`apply_pp_mode_for_scene "$top" force` 重发一次 provider；不常驻轮询。

### 鸣潮最新修复

鸣潮包名：

```text
com.kurogame.mingchao
```

已确认旧问题不是 XML 没生成或 bind mount 失败。ZuiPP 已读到用户 `LimitConfig`，但同一 App 条目的 `ThermalConfig=200 100 300` 会选择 vendor thermal user case；现场 `200 -> battery_2/gpu_0` 后，KGSL 曾被压到约 `thermal_pwrlevel=10`、`max_gpuclk=310000000`。

当前修复：

- `XmlProfileGenerator` 对用户生成的独立游戏条目统一写 `ThermalConfig=0 0 0`。
- 默认模板中鸣潮条目也是 `ThermalConfig=0 0 0`。
- 这只避免主动切到 100/200/300 游戏热控 user case，不关闭系统全局过热保护。
- final verifier 会拒绝鸣潮模板不是 `0 0 0` 的刷机包。

2026-06-24 现场临时验证：改为 `0 0 0` 并 reload 后，观察到 `thermal_pwrlevel=0`、`max_gpuclk=903000000`。这证明旧 user case 是冲突源，但最终 2026-06-24 镜像仍需刷后重新验证完整自动链路。

## 5. P3 线程放置当前实现

AGENTS.md 把产品功能称为 `asoulOpt` 线程放置/亲合度模块；当前实际系统后端已经替换为 AppOpt。新窗口必须区分“功能名/兼容命令名”和“真实运行二进制”。

当前真实后端：

```text
/system/bin/AppOpt
/system/etc/init/zui_appopt.rc
/system/etc/zui_control/default_applist.conf
/system/etc/zui_control/zui_appopt_prepare.sh
/data/vendor/zui_control/appopt/applist.conf
service=zui_appopt
```

已从镜像删除旧项：

```text
/system/bin/AsoulOpt
/system/etc/asopt.conf
/system/etc/init/zui_asoulopt.rc
/system/etc/zui_control/asopt.conf
/system/etc/zui_control/default_asopt.conf
/system/etc/zui_control/zui_asoulopt.sh
```

兼容遗留：

- daemon 请求名仍有 `apply_asoul` / `restore_asoul`，实际操作 AppOpt。
- UI 仍有 `AsoulOpt` 文案，状态正文会显示“后端：AppOpt”。
- `zui_appopt_prepare.sh` 会清理可能残留的旧 `AsoulOpt` 进程。
- 不要因为兼容名字重新把旧 AsoulOpt 二进制和三份 conf 加回来。

当前 SELinux 修复：

- `/system/bin/AppOpt` 标记为 `performanced_exec`。
- `performanced` 获得读取 `/data/vendor/zui_control/appopt/applist.conf` 所需的目录/file 权限和 `dac_override`。
- Windows 侧没有 `secilc` 完整编译验证，刷后仍必须检查 AVC。

## 6. 云控屏蔽历史实现（0.20.0 已删除）

当前不再一刀切 UID1000，也不冻结 `com.zui.pp`。

两层实现：

1. `/system/etc/hosts` 静态屏蔽已知 Lenovo/ZUI 云控域名。
2. `zui_cloud_block.sh` 用 iptables/ip6tables 阻断少数独立 UID 云控包。

独立 UID 目标包括：

```text
com.zui.game.service
com.zui.engine
com.lenovo.tbengine
com.lenovo.leos.cloud.sync
com.tblenovo.tabpushout
com.tblenovo.center
```

明确边界：

- 不含 `--uid-owner 1000`。
- `com.zui.pp`、`com.zui.cores`、`com.zui.safecenter` 不被共享 UID 一刀切。
- hosts 是静态规则，不做后台抓包或持续网络扫描。
- hosts 只匹配明确域名，不支持通配所有未来子域名或硬编码 IP。

## 7. 0.19.9 历史验证步骤（不要用于 0.20.0）

先确认设备是否已经刷入上述最终包，再做刷后验证。2026-07-21 当前设备未连接，不能跳过这一步。

连接设备后先运行：

```powershell
$adb = "D:\3.VScode\Mi\tools\adb\adb.exe"
& $adb get-state
& $adb shell su -c id
& $adb shell dumpsys package com.zui.zuicontrol | findstr "versionCode versionName"
& $adb shell service list | findstr zui_control
& $adb shell dumpsys zui_control
```

预期 App 为 `28 / 0.19.9`。如果不是，不要拿旧包状态判断新修复，应先刷 `D:\3.VScode\Mi\【B刷机】187`。

刷后按顺序验证：

1. P1：`zui_control` 存在，`refreshOwner=system`，`daemonRefreshDisabled=true`，场景切换仍正常。
2. P3：`init.svc.zui_appopt=running`，`pidof AppOpt` 有 PID，配置可读，无 AppOpt/performanced AVC。
3. 云控：状态键、hosts 和 iptables/ip6tables 链存在，确认没有 UID1000 全断规则。
4. P2：active XML 与 `/system/etc` hash 一致，鸣潮条目 `ThermalConfig=0 0 0`。
5. 进入鸣潮：`zui_control_pp_mode_state` 更新为当前时间、`state=triggered;stage=provider_direct;package=com.kurogame.mingchao;mode=0;xml=...`。
6. 退出再进鸣潮：时间戳再次更新，证明 scene-enter force retrigger 生效。
7. 读取 ZuiPP/Thermal/KGSL 日志和节点，区分 XML 没应用、vendor user case 限制和真实高温全局降频。
8. 检查 `dmesg/logcat` 中与 zui_control、AppOpt、cloud block、ZuiPP 相关的 AVC。

未完成上述刷后验证前：

- 不进入 FPS cap。
- 不修改 P1。
- 不恢复 direct CPU/GPU sysfs。
- 不重新引入旧 AsoulOpt 二进制/conf。
- 不根据旧 0.19.8/P2-I 文档判断当前 0.19.9 包。

## 8. 关键代码入口

```text
P1 system_server:
ZuiControl/framework_patch/src/services/com/zui/server/control/ZuiControlService.java
ZuiControl/framework_patch/src/services/com/zui/server/control/ZuiControlHooks.java

P2:
ZuiControl/app/src/main/java/com/zui/zuicontrol/XmlProfileGenerator.java
ZuiControl/app/src/main/java/com/zui/zuicontrol/PerformanceProfile.kt
ZuiControl/payload/system/bin/zui_controld
ZuiControl/payload/system/etc/zui_control/default_game_policy.xml
ZuiControl/payload/system/etc/zui_control/default_performanceconfig.xml

P3/AppOpt:
ZuiControl/payload/system/bin/AppOpt
ZuiControl/payload/system/etc/init/zui_appopt.rc
ZuiControl/payload/system/etc/zui_control/zui_appopt_prepare.sh
ZuiControl/payload/system/etc/zui_control/default_applist.conf

云控已删除；只保留官方默认文件：
ZuiControl/payload/system/etc/hosts

打包/验证：
ZuiControl/scripts/ApplyZuiControlPayload.py
ZuiControl/scripts/VerifyZuiControlFlashPackage.ps1
```

## 9. 新聊天首条消息

本节原先保存过 30/0.20.1 的复制文本，现已作废。新聊天不要复制历史段落；直接使用同目录的 `AI交接记录_ZuiControl_2026-07-21_当前主入口.txt`，它与本文第 0.8 节保持一致。
