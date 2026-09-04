# SELinux 踩坑与永久 Gate

## 当前最终架构

Uperf supervisor 沿用 `performanced` domain，没有新 domain；wrapper/supervisor 只使用
已审核资源。Golden startup/soak 相关 AVC=0，SELinux 保持 enforcing。

## Owner / Authority

- policy source：已审核 payload CIL/sepolicy。
- runtime truth：该 boot 的 `logcat -b all`/kernel AVC 与实际 process domain。
- permission 最小化：只允许已证明的必需边。

## 已淘汰设计

`sfanalysis=true` 后为了读 SurfaceFlinger binary 随意加 allow；看到 denial 就
audit2allow 整包放行；用 permissive 当 PASS；只做 static CIL compile 不做 runtime AVC
Gate。

## 为什么错

RunId `20260901174600` 的
`performanced -> surfaceflinger_exec:file read` 是 blocking AVC，根因是可选
SFAnalysis 功能越界，不是缺一条必须权限。扩权会隐藏错误 ownership。

## 正确做法

先确认触发功能是否必需；非必需功能关闭（Golden 保持
`sfanalysis=false`）。如确需权限，先列全 source/target/class/perm 路径，通过
CIL compile/semantic graph/final-super context 检查，再在 enforcing 设备上做相关 AVC=0
验收。

## 禁止重新引入

`audit2allow everything`, `permissive pass`, `sfanalysis=true`,
`surfaceflinger_exec read allow`, `static-only SELinux proof`。

## 必须阅读触发关键词

`SELinux`, `sepolicy`, `CIL`, `AVC`, `allow`, `domain`, `context`, `performanced`,
`surfaceflinger_exec`。
