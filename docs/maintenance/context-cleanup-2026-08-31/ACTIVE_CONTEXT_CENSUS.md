# Active Repository Context Census

盘点时间：2026-08-31  
生产仓库：`D:\3.VScode\Mi\ZuiControl`  
盘点基线：branch `v20-3b-gate-20260830`，HEAD `30fe138a7ef531aeffbcf951e9113f4ae0d17cfe`

本 census 按“逻辑产物树”列项；`raw/`、trace 和 logcat 不逐一展开成 1,000 多行。每一行的 size 是该 path 递归总量，覆盖用户指定的 V19/V20/AI/handoff/takeover/raw/device results/trace/log/archive/历史 Markdown 范围。

分类：A = 当前默认入口；B = V20.4 可能定向引用；C = 纯历史证据；D = 临时、重复或可再生。本轮所有 archive 动作都是同盘移动，可恢复；没有删除文件。

## 盘点摘要

- 清理前：118 tracked、1740 untracked、另有 1 个 tracked modified；仓库内 97 个 Markdown。
- 名称以 `V19` 开头的候选：0。
- raw trees：1595 files / 265,535,784 B。
- device test results：34 files / 16,994,431 B。
- Perfetto traces：5 files / 63,283,008 B。
- logcat dumps：89 files / 132,930,310 B。
- RAR：4 files / 78,498,740 B；ZIP：1 file / 840,447 B。
- 已外移：1750 files / 363,351,799 B（346.52 MiB）到 `D:\3.VScode\Mi\ZuiControl_Archive\`。

## A. 当前默认入口必须保留

| path | size | tracked/untracked | stage | current relevance | recommended action | reason |
| --- | ---: | --- | --- | --- | --- | --- |
| `AGENTS.md` | 19,207 B | tracked by hygiene commit | V20.4 | 最高项目规则 | Keep active | 新会话第一入口 |
| `CURRENT_PROJECT_STATE.md` | 8,546 B | tracked by hygiene commit | V20.4 | 当前短 handoff | Keep active | 新会话第二入口 |
| `README.md` | 4,968 B | tracked, modified | V20.3B/V20.4 | 当前架构入口 | Keep active | 已移除 persistent-daemon 旧描述 |
| `CURRENT_EVIDENCE_INDEX.md` | 8,615 B | tracked by hygiene commit | V20.3B baseline | 最小证据入口 | Keep active | 避免默认读取 195 MiB raw |

Workspace 根 `D:\3.VScode\Mi\AGENTS.md` 只保留指向 `ZuiControl/AGENTS.md` 的短 pointer，不是第二份规则。

## B. 当前 V20.4 可能需要定向引用

| path | size | tracked/untracked | stage | current relevance | recommended action | reason |
| --- | ---: | --- | --- | --- | --- | --- |
| `V20_3B_DAEMON_RETIREMENT/tests/` | 316,686 B / 25 files | 2 tracked + 23 untracked/ignored | V20.3B/V20.4 regression | 当前 policy/device/transaction 工具 | Keep active; never default-scan outputs | CI 与当前回归仍使用 |
| `payload/README.txt` | 3,175 B | tracked, modified | V20.3B payload | 当前 payload 架构 | Keep active | 只描述当前 owner/oneshot/fence/health |
| `docs/ZuiControl_9008_AUTOMATION_GUIDE_2026-08-18.md` | 10,683 B | tracked | release verification | final-package/9008 定向参考 | Keep active | 属于验证流程，不是 AI handoff |
| `D:\3.VScode\Mi\ZuiControl_Archive\V20_3B_DAEMON_RETIREMENT\` | 200,138,358 B / 846 files | source was untracked | V20.3B final evidence | 最近完整真机基线 | Keep external; read via index only | 报告、raw、Perfetto、logcat 可定向追溯 |
| `...\phase_supplement_final\transient_*` | 442,807 B / text+PNG | source was untracked | V20.3B → V20.4 | Refresh Correctness 直接证据 | Keep external; text first | 默认 120 样本证明字段分裂，不证明非 120 |
| `D:\3.VScode\Mi\ZuiControl_Archive\packages\V20_3B_DEVICE_RESULTS.rar` | 75,377,875 B | source was untracked | V20.3B frozen package | 完整冻结包 | Keep external | SHA-256 `632fa2…1262a`；仅完整性复算时解包 |
| `D:\3.VScode\Mi\ZuiControl_Archive\packages\V20_3B_DEVICE_RESULTS.rar.sha256` | 103 B | source was untracked | V20.3B | archive provenance | Keep with package | 校验 sidecar |

## C. 纯历史证据

| path | size | tracked/untracked | stage | current relevance | recommended action | reason |
| --- | ---: | --- | --- | --- | --- | --- |
| `AI_AUDIT_PACKAGE/` → `ZuiControl_Archive/AI_AUDIT_PACKAGE/` | 945,982 B / 23 files | source 23 tracked | pre-V20 AI audit | 历史审计快照 | Archived | 内容已被当前源码/规则淘汰；Git history 仍保留 |
| `docs/*` historical → `ZuiControl_Archive/docs/` | 260,102 B / 14 files | source 14 tracked；其中旧 AI 主入口接管前 modified | V1–V19/P2/P3 | 旧实现记录、AI handoff、XML/AppOpt 指南 | Archived; preserve modified bytes | 防止旧路线成为默认入口；9008 guide 未移动 |
| workspace `CODEX_NEW_SESSION_HANDOFF_V20_3B.md` + `TAKEOVER_REPORT.md` → `ZuiControl_Archive/handoffs/` | 22,392 B / 2 files | outside Git | V20.3B transition | 已完成使命 | Archived | 当前状态已进入 AGENTS/STATE/index |
| `V20_BASELINE_PACKAGE/` evidence → archive | 17,091,565 B / 48 files | source untracked | V20 baseline | 历史 baseline | Archived; `scripts/` kept | 只外移报告/device results，保留验证脚本 |
| `V20_1_CONTROL_PLANE/` evidence → archive | 19,272,380 B / 134 files | source untracked | V20.1 | ACK/Native Auto 历史 | Archived; `tests/` kept | Native Auto 非当前生产 owner |
| `V20_2_EVENT_DRIVEN/` evidence → archive | 10,106,649 B / 116 files | source untracked/ignored | V20.2 | 被 3A/3B 取代 | Archived; `tests/` kept | 保留测试代码，外移 raw/report/patch |
| `V20_2_1_FIX_PACKAGE/` → archive | 896,129 B / 49 files | source untracked/ignored | V20.2.1 fix | 历史修复证据 | Archived | 不参与当前 build/runtime |
| `V20_2_1_DEVICE_RESULTS/` evidence → archive | 15,503,795 B / 184 files | source untracked/ignored | V20.2.1 | 历史 device results | Archived; `tests/` kept | 保留验证源码，外移 raw/derived/report |
| `V20_3A_COMMAND_WAKEUP/` evidence → archive | 20,555,829 B / 322 files | moved part untracked/ignored | V20.3A | 直接前代 | Archived; `tests/` kept | 两个 tracked tests 与全部验证工具仍在原位 |
| root V20 RAR/sidecar/log/decision Markdown → `ZuiControl_Archive/packages/` | 78,558,618 B / 12 files | source untracked/ignored | V20.2.1–V20.3B | 冻结包/provenance | Archived | 含 4 RAR、5 sidecar、2 log、1 decision；仓库根不再被大包污染 |
| `docs/maintenance/context-cleanup-2026-08-31/ACTIVE_CONTEXT_CENSUS.md` | maintenance record | tracked by hygiene commit | context cleanup | 审计记录，非默认入口 | Keep under maintenance | 仅追溯本次盘点时读取 |
| `docs/maintenance/context-cleanup-2026-08-31/CONTEXT_CLEANUP_REPORT.md` | maintenance record | tracked by hygiene commit | context cleanup | 审计记录，非默认入口 | Keep under maintenance | 仅追溯本次执行时读取 |

仍在 active repo 的历史测试/验证源码因用户硬规则保留，不是默认阅读入口：

| path | size | tracked/untracked | stage | current relevance | recommended action | reason |
| --- | ---: | --- | --- | --- | --- | --- |
| `V20_BASELINE_PACKAGE/scripts/` | 13,441 B | untracked | V20 | verification source | Keep in place | 禁止移动测试/验证代码 |
| `V20_1_CONTROL_PLANE/tests/` | 24,437 B | untracked | V20.1 | historical tests | Keep in place; ignore by default | 同上 |
| `V20_2_EVENT_DRIVEN/tests/` | 75,873 B | untracked/ignored | V20.2 | historical tests | Keep in place; ignore by default | 同上 |
| `V20_2_1_DEVICE_RESULTS/tests/` | 58,710 B | untracked/ignored | V20.2.1 | historical tests | Keep in place; ignore by default | 同上 |
| `V20_3A_COMMAND_WAKEUP/tests/` | 174,443 B | 2 tracked + untracked/ignored | V20.3A | predecessor tests | Keep in place | 禁止移动；部分工具仍被后续 runbook 引用 |

## D. 临时、重复或可再生产物

| path | size | tracked/untracked | stage | current relevance | recommended action | reason |
| --- | ---: | --- | --- | --- | --- | --- |
| `.gradle/` | 762,835 B / 13 files | ignored | build cache | 无上下文价值 | Leave ignored; regenerate as needed | 本轮不做磁盘缓存清理 |
| `.kotlin/` | 0 B | ignored | build cache | 无 | Leave ignored | 已加入 ignore |
| `app/build/` | 9,460,848 B / 458 files | ignored | local build | 可再生 | Leave ignored | 不与 context cleanup 混做 build cleanup |
| `work/` | 4,537,856 B / 597 files | ignored | build/package work | 可能供当前 packaging 使用 | Keep untouched | 用户禁止碰 build/ROM packaging 路径 |
| `**/__pycache__/*.pyc` | 101,189 B / 5 files | ignored | generated | 无 | Leave ignored; safe future delete | 体积小，不值得本轮扩大删除范围 |
| exact duplicate files inside archived evidence | 约 1,610,502 B / 74 hash groups | archived | multi-run capture | 保留现场结构 | Do not deduplicate | 收益小，逐文件删除会破坏证据结构 |

## Ignore policy

本轮新增的 evidence ignore 规则仅忽略 top-level V-stage raw/device evidence、Perfetto 和根级 V-stage archive；没有使用全局 `raw/`，避免误伤 Android `res/raw`。也没有忽略整个 `V20_*`，因此 tests/report 源码仍可见。

本轮未删除 AppOpt、XML/ZuiPP、fpsCap、KGSL、Uperf/asoulOpt、runtime/build/payload/framework injection/tests/verification 的任何生产或测试代码。
