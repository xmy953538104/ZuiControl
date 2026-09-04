# Framework / ART 踩坑与永久 Gate

## 当前最终架构

`framework.jar`/`services.jar` 修改在 source 中维护，通过定向 DEX 注入进入
system image。Golden `services.jar` SHA-256 为
`245b4f2c55d5ed8b99ecba8bd473d1d76eb40c55d67116a477299cc9d8b62000`。

## Owner / Authority

- Java/source semantics：Git source。
- bootability authority：最终 super 反向提取到的 exact JAR/DEX，不是中间目录。
- runtime acceptance：Boot Hard Gate + system_server 稳定观察。

## 已淘汰设计

把 `apktool build PASS` 或 `smali assemble PASS` 当作 framework boot proof；只验证
中间 DEX；忽略 final-super 里实际嵌入的 classpath artifact。

## 为什么错

RunId `20260831104317` 在 host assemble 成功后仍触发 ART `VerifyError`，使
system_server 无法完成启动。语法可组装不等于 verifier/type/register/control-flow
正确，也不证明最终镜像嵌入正确。

## 正确做法

所有 boot/system_server classpath DEX 候选必须同时通过：

1. host tests；
2. apktool/smali rebuild；
3. final-super reverse extraction；
4. final artifact ART/dex2oat verifier；
5. marker/provenance/hash verifier。

Host 不能完成 ART Gate 时，只能在已恢复的目标设备上对最终 super 提取物
做只读/临时 `/data/local/tmp` 验证。

## 禁止重新引入

`apktool PASS therefore bootable`, `smali assemble only`, `intermediate dex proof`,
`skip dex2oat`, `skip final-super reverse extraction`。

## 必须阅读触发关键词

`framework.jar`, `services.jar`, `system_server`, `smali`, `DEX`, `ART`, `dex2oat`,
`VerifyError`, `classpath`, `framework/services`。
