# V20.4 Host Tests

更新时间：2026-08-31。下列 gate 先在工作树执行，随后由 isolated builder 在 source commit `3865cf9c99cb89a8df2b705b9b3dbb2711b311ec` 的 fresh clone 中重复；原始摘要在 [`raw/HOST_TEST_RESULTS.txt`](raw/HOST_TEST_RESULTS.txt)。

| Gate | Result | Notes |
| --- | --- | --- |
| V20.4 executable state model + production binding | PASS, 19/19 | foreground-only、unknown window、IME restore、five rates、failure、kill switch、ownership markers |
| V20.3B policy regression | PASS, 5/5 | daemon retirement / health / transaction ownership不回归 |
| Java 8 framework source + stubs | PASS | 18 generated classes；新增 hidden API stub只作 classpath |
| D8 API 35 | PASS | `zui_control_services.dex` 49,756 bytes（当前工作树） |
| App unit/lint/debug assemble | PASS | Gradle 9.3.1，53 tasks；无新增 error |
| B072 DisplayContent smali injection + apktool rebuild | PASS | window focus与IME hook marker均注入；probe jar成功重建 |
| Python compile / `git diff --check` | PASS | patcher/test syntax与 whitespace gate通过 |
| PowerShell builder/verifier parser and route smoke | PASS | V20.3B default route保留；V20.4 wrapper/allowlist/final marker route通过 |
| Exact CI + isolated candidate route | PASS | CI run `33348269219` success；fresh clone 中 V20.3B 5/5、V20.4 19/19；official 072 → SignNoFec → PackSuper → reverse verify |

Host model不能证明 panel physical Hz、异步 WindowManager traversal完成、UDFPS local/global vote合并的运行时结果或外部 writer竞态。它们保留在 device matrix。
