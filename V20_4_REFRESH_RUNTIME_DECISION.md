# V20.4 Refresh Runtime Correction — Final Decision

日期：2026-08-31

范围：仅修正 kill-switch event transport、null Window transition gap、Lenovo/ZUI control UI classification。

当前设备：仍运行 `RunId 20260831134511`；本轮没有刷机、重启或回滚。

## 1. Kill switch根因

**YES，已在当前真机可逆闭环。** 固定结论：

```text
KILL_SWITCH_ROOT_CAUSE_CONFIRMED=RAW_SETPROP_DOES_NOT_REPORT_PROCESS_SYSPROP_CHANGE
```

对 `persist.zui_control.refresh.disable` 的实测中，raw setprop后末样本 `2127.214ms` 仍为mask0；标准 `IBinder.SYSPROPS_TRANSACTION` 后首样本 `59.772ms` 成为mask2并释放refresh ownership；property恢复0后仍需第二次poke，首样本 `56.350ms` 重建。实验前后system_server PID均为2635，临时profile和property均已恢复。global property共享同一callback/worker设计，但本次可逆poke只对refresh property逐阶段计时，不把未执行的对称实验写成已测。详见 [`01_KILL_SWITCH_ROOT_CAUSE.md`](V20_4_REFRESH_RUNTIME_CORRECTION/01_KILL_SWITCH_ROOT_CAUSE.md)。

## 2. Raw setprop为什么不触发

`SystemProperties.set()` / raw setprop写共享property area；`SystemProperties.addChangeCallback()`注册的是当前进程内的 `report_sysprop_change` callback。property值跨进程可读不等于system_server已经调用process-local report。标准 transaction `0x5f535052` / `1599295570` 才会在目标Binder进程触发report，因此poke后立即收敛，而raw write alone不收敛。

## 3. 新 event transport

正常产品入口是strict-auth Binder TX10：exact signed App package+certificate检查、无SYSTEM_UID bypass，随后system_server写持久property并在同一调用内直接执行mask transition；不绕shell、不等待callback。当前release App UI没有接线，故 `APP_UI_TX10=NOT_EXECUTED`。

工程/应急入口保留两个raw property。每个property edge由init只启动一次短进程：

```text
exec_background u:r:shell:s0 shell shell -- /system/bin/sh -c "exec /system/bin/service call zui_control 1599295570"
```

使用 `/system/bin/sh` 是目标SELinux entrypoint要求；不得退回直接把 `system_file` 类型的 `/system/bin/service` 当作shell domain入口。worker被唤醒后重读两个property的最终truth并dedup。详见 [`02_KILL_SWITCH_EVENT_DESIGN.md`](V20_4_REFRESH_RUNTIME_CORRECTION/02_KILL_SWITCH_EVENT_DESIGN.md)。

## 4. ADB emergency与boot persistence

**仍支持。** `persist.zui_control.disable` 与 `persist.zui_control.refresh.disable` 都保留为exact bool engineering接口，shell拥有定向set/read权限；App domain没有property write。服务构造时直接读取persisted mask，因此早于Binder发布的edge或system_server重启不会丢失最终truth。

## 5. Idle成本

没有新增polling、periodic getprop、timer loop、watchdog或persistent notifier，也没有恢复persistent `zui_controld`。每个raw property edge最多产生一个短生命周期shell/service进程。刷后短进程退出、rapid poke有界性与enabled/disabled idle仍属于device matrix，当前不写PASS。

## 6. Null Window处理

空/`null mCurrentFocus` 被定义为 `EMPTY_FOCUS_TRANSITION`，不是owner：保留最后一个已证明的非空Window physical policy，不apply default120，不改Uperf、business/current/last/editable/config target，也不覆盖最后非空snapshot。下一真实非空Window是权威edge并绕过same-owner入口dedup后收敛。真实非空SystemUI、ZuiControl、IME和OEM control Window仍是foreground owner并使用default120。没有sleep、固定debounce、timeout或polling。详见 [`03_NULL_WINDOW_TRANSITION.md`](V20_4_REFRESH_RUNTIME_CORRECTION/03_NULL_WINDOW_TRANSITION.md)。

## 7. 100次App切换预期模型

A=60、B=90的100个往返应产生200个真实business Window edge；normal handoff的intermediate default120计数应为0；apply delta目标约+200，而不是旧实现约+400。empty edge另计，不能当owner。host模型已通过；刷后100轮、physical mode transition和真实apply统计仍为pending。

## 8. OEM control UI

registry只增加两个有真机证据的exact package：`com.lenovo.screensplit` 与 `com.zui.freeform.sidebar`。它们focused时physical=default120，但不得覆盖current/last/editable business，也不得创建profile。没有使用 `com.zui.*`、system-app、UID1000或安装目录泛化；Launcher/Notes/Calculator/Settings等仍可作为业务App。source/host/final marker已通过，刷后污染回归仍pending。详见 [`04_OEM_TRANSIENT_REGISTRY.md`](V20_4_REFRESH_RUNTIME_CORRECTION/04_OEM_TRANSIENT_REGISTRY.md)。

## 9. Production diff

基线 `0ea3c44eeca1256590b56a6e12c02f60bb67f493` 到最终source `146e096c6a6bc8b3fee60349b856990fd9fb68d2` 共有8个unique path：

- runtime：`ZuiControlService.java`、两个property context/CIL patch、`zui_refresh_kill_switch.rc`；
- packaging：candidate builder增加本轮RC；
- host test：V20.4 policy/model test；
- verifier：final package与final-super verifier。

没有修改App runtime、framework manager、Uperf、asoulOpt、command、刷新档位映射、ROM baseline、fixed-seven flasher或历史compatibility production code。完整可复核差异：[`git_diff.patch`](V20_4_REFRESH_RUNTIME_CORRECTION/git_diff.patch)；分类收据：[`SOURCE_DIFF_SCOPE.txt`](V20_4_REFRESH_RUNTIME_CORRECTION/raw/SOURCE_DIFF_SCOPE.txt)。

## 10. Host tests

最终source SHA上：V20.4 `39/39` PASS，V20.3B regression `5/5` PASS，Java 8/javac与D8 PASS，Python compile PASS，两个PowerShell verifier parser PASS，`git diff --check` PASS。CI `33375509612` 成功且head SHA精确等于最终source。详见 [`05_HOST_TESTS.md`](V20_4_REFRESH_RUNTIME_CORRECTION/05_HOST_TESTS.md) 与 [`final host receipts`](V20_4_REFRESH_RUNTIME_CORRECTION/raw/final_host_gate_146e096/)。

## 11. Final-artifact gates

- apktool/smali rebuild：PASS；
- final-super reverse extraction：PASS；base verifier `ok=true`，V20.4 `marker_count=56`；
- final `services.jar`：主机与设备暂存SHA均为 `0b7bb46c644c5559173f72b06579131e82597366fdcc114d3fb30aabb544e8a3`；其中注入 `classes4.dex` 为 `ae961e05b96bafbc9b89ac16e21490748864b493c8b653271b07bcad68097158`；
- 目标ART/dex2oat：`DEX_RC=0 / GATE_RC=0`，stdout/stderr为空；logcat显示ISA/zipalign warning以及live boot oat与候选新增framework DEX不匹配后转入imageless verify，没有VerifyError、verification rejection或hard/soft verifier failure；
- final split CIL：目标live secilc与final-super secilc SHA相同；AOSP r75 boot-equivalent argv得到 `SECILC_RC=0 / GATE_RC=0`、stderr为空；system sidecar与ODM precompiled sidecar不等，故boot不会静默沿用旧policy；
- final init exact-file：Android CI BuildId `16200779` 官方 `host_init_verifier`，final RC SHA `0161a998...`，GHA run `33378426672`，exit 0、stdout/stderr为空，`HOST_INIT_VERIFIER_EXACT_FILE=PASS`。覆盖只限最终RC单文件grammar/builtin/user/property-context解析，不冒充五分区全树PASS。

证据：[`06_ART_FINAL_GATE.md`](V20_4_REFRESH_RUNTIME_CORRECTION/06_ART_FINAL_GATE.md)、[`final-super receipt`](V20_4_REFRESH_RUNTIME_CORRECTION/raw/final_super_receipt_20260831170720.txt)、[`ART raw`](V20_4_REFRESH_RUNTIME_CORRECTION/raw/final_art_gate_20260831170720/)、[`policy raw`](V20_4_REFRESH_RUNTIME_CORRECTION/raw/final_policy_gate_20260831170720/)、[`init gate`](V20_4_REFRESH_RUNTIME_CORRECTION/raw/final_init_gate_20260831170720/)。

## 12. 最终候选锚点

```text
RunId=20260831170720
source_commit=146e096c6a6bc8b3fee60349b856990fd9fb68d2
ci_run_id=33375509612
apk_sha256=9147d3344c93fbc448bba5ed60451c7cc5c1fdb9ce94a32c758cdf02c4488b51a
super_sha256=dc4fd4bc3e288aa26e80cf382db62211f488e1b74c7cb8767b2d3f9f5f2c269d
boot_sha256=e7e85b5cd2806b8c27adf4925e05ee169072a79a43502effc34c97fb27ee8371
vbmeta_system_sha256=5c72c2d63deef95ddba41c825c271866a3041d48a6b5ca1cfd50bc4bc6cc2dda
vbmeta_sha256=c1f4ea68ea52bae62e464ddc245dadd740569ba3ac3376ee5d23a40204a744f7
services_jar_sha256=0b7bb46c644c5559173f72b06579131e82597366fdcc114d3fb30aabb544e8a3
flashed=false
```

候选目录：`D:\3.VScode\Mi\work\v20_4_candidate_20260831170720`。完整compact build/provenance：[`build_20260831170720/`](V20_4_REFRESH_RUNTIME_CORRECTION/raw/build_20260831170720/)。

## 13. Pre-Flash决策

```text
PRE_FLASH_READY=YES
FLASHED=NO
BOOT_HARD_GATE=PENDING_POST_FLASH
DEVICE_CORRECTION_VALIDATION=PENDING
APP_UI_TX10=NOT_EXECUTED
```

全部技术gate已PASS，候选可以交人工Pre-Flash Gate。`PRE_FLASH_READY=YES` 不是刷机授权，也不是Boot或device PASS。取得人工批准后只按 [`07_TARGETED_DEVICE_PLAN.md`](V20_4_REFRESH_RUNTIME_CORRECTION/07_TARGETED_DEVICE_PLAN.md) 执行；不得自动刷机或进入其它V20.4 work package。
