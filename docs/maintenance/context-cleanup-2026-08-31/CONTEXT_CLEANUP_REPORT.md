# Active Repository Context Cleanup Report

日期：2026-08-31  
范围：只清理 active repository context；不是 V21 Production Cleanup；没有开始 V20.4 生产实现。

## 1. 结果

清理完成。97 个经路径验证的 move operation 把 1,750 个文件、363,351,799 B（346.52 MiB）移到仓库外：

`D:\3.VScode\Mi\ZuiControl_Archive\`

Archive 与生产仓库不相互嵌套，移动前逐项验证 source、destination、碰撞和边界，移动后 `missing destination=0`、`remaining source=0`。所有移动均可恢复。

## 2. 移动了什么

- workspace 根的 `CODEX_NEW_SESSION_HANDOFF_V20_3B.md`、`TAKEOVER_REPORT.md` → `ZuiControl_Archive/handoffs/`；
- tracked `AI_AUDIT_PACKAGE/` 23 个旧 AI audit/source snapshot → external archive；
- `docs/` 中 14 个旧 AI handoff、V1–V19 实现记录、XML/AppOpt/P2/P3 历史指南 → external archive；
- V20 baseline、V20.1、V20.2、V20.2.1、V20.3A 的报告、patch、raw、derived、device results → 按原阶段结构归档；
- V20.3B 的 01–13 报告、decision、manifest、199,828,438 B（190.57 MiB）raw/trace/log → `ZuiControl_Archive/V20_3B_DAEMON_RETIREMENT/`；
- 仓库根的 V20 RAR、sidecar、生成 log 和 `V20_2_1_DECISION.md` → `ZuiControl_Archive/packages/`。

接管前唯一 modified 的旧 AI 交接文档 `docs/AI交接记录_ZuiControl_2026-07-21_当前主入口.txt` 没有覆盖或丢弃；其当时字节内容原样进入 archive。

## 3. 删除了什么

没有删除文件。

5 个 top-level RAR/ZIP 的 SHA-256 均不同。完整 archive 另含 6 个历史 TAR；本轮没有足够依据把任何压缩/容器文件判为可删除的字节级重复。V20.3A/V20.3B 完整结果 RAR 虽与 exploded tree 内容对应，但承担冻结包/provenance 角色；两个小 RAR 是不同 pre-final snapshot。因此本轮选择外移而不是删除。

证据内部的 exact duplicates 理论只节省约 1.54 MiB，逐文件去重会破坏采集现场结构，未执行。

## 4. 文档更新

- `D:\3.VScode\Mi\ZuiControl\AGENTS.md`
  - V20.3B stage=CLOSED；daemon-retirement architecture=PASS；历史 PARTIAL/HOLD 不再阻止 V20.4；
  - transient、kill switch、AppRequest/vote/peak 释放、applied success 语义合并为 V20.4 Refresh Correctness / State Machine；
  - 旧 handoff 降级为 archive 历史材料；
  - 默认禁止扫描 external archive。
- `D:\3.VScode\Mi\ZuiControl\CURRENT_PROJECT_STATE.md`
  - 同步阶段语义、当前工作包、carry-forward backlog、archive 与新入口。
- `README.md`
  - 删除 v19/persistent scheduler control-plane 旧入口；
  - 写明 system_server refresh/Uperf scene owner、init-native fence、oneshot command 和按需 health。
- `payload/README.txt`
  - 写明 `zui_control_request` disabled+oneshot、`zui_controld --oneshot-request` only；
  - OEM fence 只宣称已验证的 `vendor.perfservice`；
  - 保留 Uperf wrapper 5 秒 self-check 事实；
  - 不再把 ZuiControl transient 写成已完成。
- `.gitignore`
  - 新增 stage raw/device results、Perfetto、root V-stage archive 和 AI audit 生成目录规则；
  - 没有使用全局 `raw/` 或忽略整个 `V20_*`，避免误伤 Android `res/raw` 与测试源码。

新增：

- `CURRENT_EVIDENCE_INDEX.md`
- `docs/maintenance/context-cleanup-2026-08-31/ACTIVE_CONTEXT_CENSUS.md`
- `docs/maintenance/context-cleanup-2026-08-31/CONTEXT_CLEANUP_REPORT.md`
- external `ZuiControl_Archive/README.md`

## 5. 完全未触碰的生产/测试范围

以下路径内容没有修改、移动或删除：

- `app/**`
- `framework_patch/**`
- `framework-stubs/**`
- `payload/system/**`
- `scripts/**`
- `tools/**`
- `.github/**`
- 所有保留的 `tests/**`、runbook、analyzer 和 device probe
- Uperf/asoulOpt binaries 与配置
- AppOpt、XML/ZuiPP、fpsCap、KGSL、thermal 和历史 compatibility 生产逻辑
- ROM image、final-super、9008 与 flash 工具/产物

`payload/README.txt` 只是说明文件；`payload/system/**` 运行内容零 diff。

## 6. Git 状态

以下是 context cleanup 完成、repository hygiene commit 之前的 `git status --short --untracked-files=all` 快照：

- modified：`.gitignore`、`README.md`、`payload/README.txt`；
- deleted：tracked `AI_AUDIT_PACKAGE/**` 23 files、历史 `docs/**` 14 files；这些内容已在 external archive；
- untracked：72 files，其中本轮 5 个 canonical/maintenance context 文件，其余 67 个是接管前已有且按用户要求保留的阶段测试/验证源码；
- production runtime/build/test source diff：0。

Porcelain 总计：112 entries = 3 modified + 37 deleted + 72 untracked。

`git diff --stat`（不包含 untracked 新文件）：

```text
40 files changed, 103 insertions(+), 5430 deletions(-)
```

受保护路径检查：

```text
git diff --name-only -- app framework_patch framework-stubs payload/system scripts tools .github V20_*/tests
<empty>
```

## 7. 新的默认阅读入口

未来 Codex 默认只读：

1. `D:\3.VScode\Mi\ZuiControl\AGENTS.md`
2. `D:\3.VScode\Mi\ZuiControl\CURRENT_PROJECT_STATE.md`
3. `D:\3.VScode\Mi\ZuiControl\README.md`
4. 当前生产源码
5. `D:\3.VScode\Mi\ZuiControl\CURRENT_EVIDENCE_INDEX.md`

不要默认读取 external archive、旧 handoff、旧 device packages 或 raw traces。只有具体结论受到质疑时才按 evidence index 定向打开最小文件。

## 8. 验证

- `TestV20_3BPolicy.py`：5/5 PASS；
- README/AGENTS/STATE/index、maintenance 与 archive index 相对链接：44 checked，0 broken；
- 四个主要 RAR 在移动后重新计算 SHA-256，全部与移动前/sidecar 一致；
- archived modified AI handoff 存在且仍为 13,977 B；
- external archive 当前为 1,751 files / 363,355,033 B，其中多出的 1 file / 3,234 B 是新 archive index；
- `git diff --check` 无错误；只有 Windows 工作树的 LF→CRLF 提示；
- `.gitignore` 验证：`V20_3B_DAEMON_RETIREMENT/raw/future.pftrace` 被忽略，`app/src/main/res/raw/future.bin` 不被忽略。

## 9. 停止边界

本轮没有修改任何运行逻辑，没有构建 ROM，没有刷机，没有开始 transient、kill switch 或其它 V20.4 修复。V21 Production Cleanup 也没有开始。
