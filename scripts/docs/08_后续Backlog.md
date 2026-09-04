# 后续 Backlog

## 当前最终架构

V20.4 已关闭。V21 Phase 1 只处理 Mi workspace canonical layout、工具、脚本、
文档、host tests 和 metadata，production runtime diff 必须为 0。

## Owner / Authority

- Phase 2：engineering speed（build cache enablement、去重 extract、qdl 41–45MB/s）。
- Phase 3：CPU knob ownership audit。
- Phase 4：performance/thermal A/B。
- Optional：Uperf worker fault hardening。
- Later：Adaptive Refresh Response tuning。
- V22：GPU optional。

## 已淘汰设计

把 worker fault 当作 Uperf 核心未完成项；未诊断就加 production watchdog；在
Phase 1 顺手清理 AppOpt/XML/ZuiPP/fpsCap/KGSL 生产代码。

## 为什么错

whole Uperf descendant-tree death recovery 已 device PASS；worker-level fault 是额外容错，
不是 Android 普通 App crash。混入 production cleanup 会破坏 Golden freeze 和可归因性。

## 正确做法

按阶段独立建 worktree/review。Worker fault 若要做，先在 Golden 设备执行一次
no-build/no-flash diagnostic，再决定是否需要任何 production 机制。

## 禁止重新引入

`worker fault blocker`, `design watchdog first`, `Phase1 production cleanup`,
`App crash equals Uperf worker fault`。

## 必须阅读触发关键词

`V21`, `backlog`, `worker fault`, `cache`, `qdl speed`, `CPU knob`, `thermal A/B`,
`adaptive refresh`, `V22 GPU`。
