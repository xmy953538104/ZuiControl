# Null Window Transition

## 真机失败模型

Notes90 ↔ Calculator60 的10次切换全部 `refreshApplyCount +2`。8次明确采到：

```text
old business
→ activityFocusedPackage = destination
→ rawFocusedPackage = ""
→ desired = default / target = 120 / apply +1
→ destination non-empty Window / apply +1
```

8个可见采样gap为 `37.731–44.771ms`；这是采样窗口，不宣称为WMS真实持续时长。另外2次未采到空行但apply仍+2。证据：[`focus_state_changes.csv`](raw/prior_device/m11_focus_order/focus_state_changes.csv)、[`focus_timeline.csv`](raw/prior_device/m11_focus_order/focus_timeline.csv)。这批raw证明ZuiControl Activity hook已收到目的包，但没有同步 top/resumed dumpsys，因此不得升级为“topResumed时序已证明”。

## 新状态语义

`EMPTY_FOCUS_TRANSITION` 与 `TRANSIENT_WINDOW` 分离：

- 空/`null mCurrentFocus`：不是owner，不创建default120，不覆盖任何最后非空Window snapshot；
- 非空 SystemUI/ZuiControl/IME/OEM control Window：是真实focused owner，physical仍为default120；
- 非空业务Window：按其profile；
- freeform/split仍由真实非空focused Window决定；PiP non-focusable不抢owner。

## 最小算法

`onFocusedWindowChanged()` 在任何 `windowSeen`、snapshot mutation或worker focus apply之前处理空包：

1. 首次空edge设 `latestWindowFocusEmpty=true`；
2. 轻量post只增加 `emptyFocusTransitionCount`，记录当时Activity与retain包；
3. 立即return，不apply、不改Uperf、不写配置target；
4. 下一非空Window清空flag并进入既有权威路径；即使返回同一包，也绕过入口same-owner dedup，以便kill enable期间ownership被释放后可以重建。

空gap期间 `refreshNow()`、foreground profile即时apply与kill-enable reconcile均拒绝使用陈旧Window；profile仍可保存。下一真实非空Window负责一次收敛。

没有固定sleep、debounce、polling或timeout。当前选择保留最后已证明非空Window policy，而不做Activity provisional physical apply：Activity在window authority存在后只是metadata；若transition取消或下一真实owner为SystemUI/OEM control，抢先应用Activity会新建错误状态轴。首次从未见过非空Window时，既有Activity bootstrap fallback仍保留。

## 100轮目标模型

A=60、B=90，100个A↔B往返：

- 200个真实business Window edge；
- `emptyFocusTransitionCount` 单独统计；
- normal handoff中 `default120 intermediate count=0`；
- `refreshApplyCount` 目标为约+200，而不是+400；
- 同Hz dedup或平台物理idle变化必须单独解释，不能把target apply与physical mode采样混写。
