# Canonical scene matrix harness

The canonical capture is `scripts/device/Capture-ZuiSceneMatrixRow.ps1` plus
`capture_zui_scene_snapshot.sh` and `ZuiSceneHarness.psm1`.

The device script obtains, in one root execution:

- `dumpsys activity activities`;
- `dumpsys zui_control`;
- protected `sys.zui_control.uperf_mode`;
- effective/current mode files and process health.

The parser requires exactly one display-global line matching
`^\s*ResumedActivity:` and never parses task-local `topResumedActivity=`.
The protected property must be present and one of the four valid modes before a
TSV row is written. Raw snapshot and canonical TSV are emitted by the same
capture; later manual backfill is not part of the workflow.

The multi-window fixture contains two conflicting task-local fields and a game
display-global authority. Host test selects the game and validates protected
`performance` mode.

```text
SCENE_HARNESS_CANONICAL=PASS_HOST
MANUAL_TSV_BACKFILL_ALLOWED=NO
```
