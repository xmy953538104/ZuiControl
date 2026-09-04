# asoulOpt 最终架构与踩坑

## 当前最终架构

asoulOpt 是唯一 per-task affinity/context-scheduler owner。ZuiControl 通过既有配置/事件
路径驱动；Uperf 只执行 CPU/power-model，`sched.enable=false`。Golden Gate 中
asoulOpt PID/starttime 稳定，且 whole Uperf tree death 未破坏 asoulOpt。

## Owner / Authority

- per-task 线程放置：asoulOpt。
- global CPU/power model：Uperf。
- OEM GPU/thermal：OEM/thermal，本阶段不宣称接管。

## 已淘汰设计

Uperf 与 asoulOpt 同时写 per-task affinity；为了统一而移除 asoulOpt binary/config；
把 Android 普通后台 App crash 当作 asoulOpt/Uperf worker fault。

## 为什么错

双 owner 会互相覆盖 mask，且无法归因性能变化。普通 App crash 由
ActivityManager 处理，不证明 native scheduler worker 容错。

## 正确做法

保持现有 ownership boundary。未来 CPU knob audit 先列出每个节点/写入者，再做
定向 A/B；本阶段不替换 binary/config，不设计新 supervisor。

## 禁止重新引入

`Uperf sched.enable=true`, `dual affinity owner`, `remove AsoulOpt`,
`app crash equals worker fault`, `unreviewed binary/config upgrade`。

## 必须阅读触发关键词

`asoulOpt`, `AsoulOpt`, `affinity`, `cpuset`, `scheduler`, `thread placement`,
`worker fault`, `sched.enable`。
