# Uperf 最终架构与踩坑

## 当前最终架构

Golden 冻结 upstream v1.0.6 binary，`sfanalysis=false`、`sched.enable=false`。system_server
以 display-global top-resumed Activity/screen event 发布 exact scene。Wrapper 完成既有
cpuset 放置后 `exec` native supervisor；supervisor 是 child subreaper，startup 最长
20s/100ms cadence 读取 regular log，看到完整 `I Uperf is running` 后原子
发布 ready，关闭日志 FD，steady state 只 blocking `waitpid(-1)`。Init 是唯一
restart owner。

## Owner / Authority

- scene：system_server display-global top-resumed event。
- CPU/power-model execution：Uperf。
- readiness/lifetime：native supervisor。
- restart/fail-safe：init，既有三次 sub-2s whole-service death/20s 语义。
- per-task affinity：asoulOpt，不是 Uperf。

## 已淘汰设计

logger FIFO、FIFO EOF 作 process lifetime、5 秒 shell/grep self-check、
`sfanalysis=true`、task-local top-resumed、立即把 transitional null 发布为 default、
解析 worker-crash 日志文字并创建第二 restart owner。

## 为什么错

真实 Uperf 会删除 `-o` pathname 并创建新 regular inode，因此 FIFO reader
等的是旧 inode，会假超时。`sfanalysis=true` 导致
`performanced -> surfaceflinger_exec:file read` blocking AVC。合法 package 后的
4.5–5.2ms null 是 transition，立即发布会覆盖正确 scene。多 restart owner 会产生
storm 和不可控状态。

## 正确做法

读 regular startup log 时处理 create/inode replacement/truncate/partial line；READY 后完全
停止日志观察。用 bounded top-resumed null revalidation，不允许 null 覆盖
刚发布的合法 scene。整棵 Uperf descendant death 回复已真机 PASS；worker-level
fault 是 `OPTIONAL_HARDENING`，先 no-build/no-flash 诊断，不预设新机制。

## 禁止重新引入

`mkfifo`, `FIFO EOF`, `logger`, `pgrep every 5s`, `sfanalysis=true`,
`immediate null publish`, `task-local topResumedActivity`, `second restart owner`,
`worker log observer`。

## 必须阅读触发关键词

`Uperf`, `FIFO`, `sfanalysis`, `supervisor`, `waitpid`, `top-resumed`, `worker`,
`readiness`, `scene`, `performanced`。
