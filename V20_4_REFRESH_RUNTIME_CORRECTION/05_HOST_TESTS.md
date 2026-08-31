# Host Tests

## 当前结果

```text
Refresh policy/model/production binding: 39/39 PASS
V20.3B regression policy: 5/5 PASS
javac framework sources: PASS
javac services sources: PASS
d8 framework classes6.dex: PASS
d8 services classes4.dex: PASS
PowerShell verifier parse: PASS
Python compile: PASS
```

最新冻结源码为 `146e096c6a6bc8b3fee60349b856990fd9fb68d2`。final-super中的注入DEX为 framework `classes6.dex` SHA-256 `279d180fe925c0027990395fa8f09f80d2fa627a6920d12a4de51f2170674c7c`、services `classes4.dex` SHA-256 `ae961e05b96bafbc9b89ac16e21490748864b493c8b653271b07bcad68097158`。最终commit上重新执行的收据：[`host_tests_and_tooling.txt`](raw/final_host_gate_146e096/host_tests_and_tooling.txt)、[`host_javac_d8.txt`](raw/final_host_gate_146e096/host_javac_d8.txt)；完整CI/build provenance见 [`build_20260831170720/`](raw/build_20260831170720/) 。

## 新增精确模型

新增或修正的覆盖包括：

1. raw setprop只改变property truth，不假定Java callback；
2. 标准sysprop poke消费最终truth；
3. persisted disabled在构造/boot时直接成为service mask；
4. authenticated TX10在返回前持久化并直接转换；
5. unknown module拒绝与既有caller auth绑定；
6. Activity B → Window null → Window B无default120；
7. Activity未知 + Window null保留最后policy；
8. null → same owner绕过入口dedup；
9. SystemUI/IME非空transient仍default120；
10. `screensplit`与`freeform.sidebar` exact transient；
11. OEM control不覆盖last/editable，业务系统App不被广泛误分类；
12. freeform/split focused-window、PiP/non-focusable既有语义不变；
13. 100轮/200 edge模型无120 intermediate、每owner约一次apply；
14. rapid raw property最终poke收敛；
15. init transport只含两个edge `exec_background`，无daemon/timer/polling；
16. dedicated property context与writer边界；
17. final-super verifier必须反查新rc、policy、TX10、null与OEM markers。

39/39与5/5仍是host gate，不替代刷后真机100轮、kill latency、AVC与idle验收。
