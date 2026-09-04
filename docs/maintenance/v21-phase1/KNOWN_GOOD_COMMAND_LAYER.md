# Known-good device command layer

Canonical helpers live in `scripts/device/`.

| Helper | Contract |
| --- | --- |
| `Invoke-ZuiRoot.ps1` | One executable plus tokenized argument list; no caller-composed compound shell expression. |
| `Push-ZuiScript.ps1` | Push one local `.sh` below `/data/local/tmp`, then root `chmod 0700`. |
| `Run-ZuiDeviceScript.ps1` | Push → one root execute → exact cleanup. Complex Android logic belongs here. |
| `Pull-ZuiEvidence.ps1` | Pull only an exact path below `/data/local/tmp`. |
| `Get-ZuiProtectedProperty.ps1` | Root `getprop` with validated property name. |
| `Get-ZuiProcessState.ps1` | Runs local `get_zui_process_state.sh`; avoids `$PID` and host `$()` expansion. |

PowerShell never interpolates Android compound logic into `su -c`. POSIX token
escaping is centralized in `ZuiDevice.Common.psm1`. A complex capture is a
checked-in `.sh`, not a model-time string.

Host fixture `scripts/device/tests/Test-ZuiDeviceTooling.ps1` proves:

- literal `$()`, semicolon, wildcard, spaces and quotes remain one shell token;
- no helper assigns PowerShell's reserved `$PID` variable;
- protected ZuiControl property access uses the root path;
- the scene parser ignores task-local `topResumedActivity=` fields.

Device execution was deliberately not performed in this phase.

```text
KNOWN_GOOD_COMMAND_LAYER=PASS_HOST
DEVICE_MUTATION=NO
```
