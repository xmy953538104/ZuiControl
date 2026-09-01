# 08 Host tests

## Local result

All pre-build host gates pass on the source candidate:

| Suite | Result | Evidence |
|---|---:|---|
| V20.4 Uperf scene/lifecycle/ownership/import/final-marker ownership | 31/31 PASS | `raw/host_tests/uperf_host_tests.txt` |
| V20.4 Refresh frozen regression | 39/39 PASS | `raw/host_tests/refresh_regression.txt` |
| V20.3B daemon-retirement regression | 5/5 PASS | `raw/host_tests/v20_3b_regression.txt` |
| shell syntax, JSON, Python compile, PowerShell parse, diff check | PASS | `raw/host_tests/local_static_gates.txt` |

The Uperf suite maps the requested cases 1–25 one-for-one. Case 20 is a separate assertion that
the wrapper contains no background helper, `inotifyd`, `tail`, periodic timer or long-lived child
churn. Two additional source-authority tests prove the top-resumed hook is unique/event-driven and
that Refresh focus/window/IME feeds no longer drive Uperf. A final-artifact regression also fixes
the D8 ownership boundary: scene-state strings are verified in `ZuiControlService$UperfScenePolicy`,
while the fail-safe marker remains in the outer service. Three contract tests cover the frozen
v1.0.6 fields, narrow property/FIFO policy and audit-only importer.

## CI-only init gate

The main workflow downloads the pinned Android 14 `host_init_verifier` from official Android CI,
checks verifier SHA256
`b89a6d6351621a183b615e371f0390c38056644b640a8e829c4f4900379ec2e6`, then parses the production
`zui_scheduler.rc` with typed property contexts. It uploads `zui-control-init-gate`; the isolated
candidate builder refuses a V20.4 Uperf build unless that exact-run artifact says PASS and has
exit code zero.

Host modeling proves selection and static lifecycle contracts. It does not claim device timing,
FIFO inheritance, knob ownership, thermal behavior or performance benefit; those remain in
`10_DEVICE_TEST_PLAN.md`.
