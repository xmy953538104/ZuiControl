# V20.4 Host Tests

更新时间：2026-08-31。下列 gate 先在工作树执行，随后由 isolated builder 在 fixed source commit `3c5cd809d5465828fe14356cbd079d45d00347b7` 的 fresh clone 中重复；原始摘要在 [`raw/HOST_TEST_RESULTS.txt`](raw/HOST_TEST_RESULTS.txt)，当前ROM/build过程在 [`raw/build_20260831134511/build.log`](raw/build_20260831134511/build.log)。

| Gate | Result | Notes |
| --- | --- | --- |
| V20.4 executable state model + production binding | PASS, 27/27 | foreground-only、Activity-first/window-first、SystemUI/Permission/known vendor overlay、IME restore、five rates、failure、stable/rapid kill switch、stale callback hint、ownership markers |
| V20.3B policy regression | PASS, 5/5 | daemon retirement / health / transaction ownership不回归 |
| Java 8 framework source + stubs | PASS | 18 generated classes；新增 hidden API stub只作 classpath |
| D8 API 35 | PASS | `zui_control_services.dex` 49,304 bytes（correction source） |
| App unit/lint/debug assemble | PASS | Gradle 9.3.1，53 tasks；无新增 error |
| B072 DisplayContent smali injection + apktool rebuild | PASS | window focus与IME hook marker均注入；probe jar成功重建 |
| Python compile / `git diff --check` | PASS | patcher/test syntax与 whitespace gate通过 |
| PowerShell builder/verifier parser and route smoke | PASS | V20.3B default route保留；V20.4 wrapper/allowlist/final marker route通过 |
| Exact CI + isolated candidate route | PASS | CI run `33361319072` success；fresh clone 中 V20.3B 5/5、V20.4 27/27；official 072 → SignNoFec → PackSuper → reverse verify |
| Final-artifact ART/dex2oat（host之后的永久gate） | PASS | final-super `services.jar` SHA-256 `389ac1cf...24ff`；已恢复目标设备只读验证 `DEX_RC=0 / GATE_RC=0`，随后fixed candidate Boot Gate PASS |

Host model不能证明 panel physical Hz、异步 WindowManager traversal完成、UDFPS local/global vote合并的运行时结果、未知 vendor window、多用户切换或外部 writer竞态。RunId `20260831104317` 还证明了apktool/smali/marker PASS不能证明ART bootability，因此所有boot/system_server classpath DEX候选永久增加final-artifact ART gate；证据见 [`PREFLASH_GATE_FIXED_20260831134511.md`](../../work/v20_4_device_validation_20260831114341/PREFLASH_GATE_FIXED_20260831134511.md)。

RunId `20260831134511` 的device matrix还暴露host模型漏项：稳定kill-switch source test虽然PASS，真机property edge没有收敛；Activity/window顺序host test没有包含真实的temporary null-window handoff，真机每次App-to-App切换多一次default120 apply。Host 27/27仍是source-model gate，不是runtime acceptance。完整结果见 [`08_DEVICE_RESULTS.md`](08_DEVICE_RESULTS.md)。
