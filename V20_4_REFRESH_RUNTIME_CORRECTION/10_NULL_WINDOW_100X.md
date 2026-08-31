# Null Window 100× Device Gate

## 结论

```text
APP_TO_APP_ROUNDTRIPS=100
REAL_BUSINESS_EDGES=200
INTERMEDIATE_DEFAULT_120=0
REFRESH_APPLY_DELTA=200
NULL_WINDOW_GATE=PASS
NULL_TO_SAME_OWNER=PASS
```

## Notes90 ↔ Calculator60

从 Notes90 开始，执行 100 个 Notes ↔ Calculator 往返，即 200 个真实新 business Window edge。每条 edge 都同时验收 raw/desired/applied package、target/applied Hz 与 apply delta。

| 指标 | 结果 |
|---|---:|
| 真实 business edge | 200 |
| `refreshApplyCount` delta | 200 |
| `emptyFocusTransitionCount` delta | 200 |
| 高频采样捕获的 null 样本 | 1270 |
| intermediate default120 样本 | 0 |
| 最大端到端 edge latency | 781.515ms |

每条 edge 都只有一次业务 apply：Notes90 → Calculator60 或 Calculator60 → Notes90，没有旧候选中的 `90→120→60` / `60→120→90`。空 Window 只增加 `EMPTY_FOCUS_TRANSITION` 计数并保留最后非空策略；下一非空 Window 才成为状态机的新业务 owner。

完整逐 edge 证据：[`08_focus_100_roundtrips.txt`](raw/device_run_20260831170720/08_focus_100_roundtrips.txt)。

## null → same owner

第一次用旋转构造 Notes → null → Notes 时，设备实际没有产生 null：`empty_transition_delta=0`、`observed_null_samples=0`、`apply_delta=0`。原始 harness 尾行写 `NULL_TO_SAME_OWNER=FAIL`，准确解释应为“fixture 未触发目标事件 / NOT_APPLICABLE”，不是产品状态机失败，也不作为 PASS 证据。

证据：[`11_null_same_owner_rotation.txt`](raw/device_run_20260831170720/11_null_same_owner_rotation.txt)。

随后改用 Notes warm relaunch 构造有效 same-owner 空窗口，连续 10 次均得到：

- 每次 `emptyDelta=1`；
- 每次 `applyDelta=0`；
- raw/desired/applied 始终收敛 Notes90；
- aggregate empty delta=10、apply delta=0、intermediate default120=0。

这证明入口 same-owner dedup 不会把空gap当成default owner，也不会制造重复 apply。kill enable 后需要重建 ownership 的路径则已由 kill-switch 20-cycle 中每次 enable `applyDelta=1` 单独证明，不能用本 same-owner dedup 结果替代。

证据：[`11b_null_same_owner_relaunch.txt`](raw/device_run_20260831170720/11b_null_same_owner_relaunch.txt)。

## 结论边界

本 gate 证明 TB321FU default display / current user 上的真实 Notes90 ↔ Calculator60 状态/apply handoff 与有效 null→same-owner 流程。原 harness 的完成条件不包含 physical mode；200 edge 中终点采样有 75 次恰好命中目标 physical Hz，因此这里不宣称每条 edge 都完成物理 settle。它也不把采样到的 null 次数解释为 WMS 精确持续时间，不扩大到 secondary user、external display 或所有未知 vendor Window；60/90/120/144/165 的物理命中由独立五档 smoke 证明。
