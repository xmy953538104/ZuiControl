# V21 Phase 1 Portable Workspace Final

V21 Phase 1 is **CLOSED**. V20.4 production remains frozen at
`29f23f8d590b88f0d472c12373366a9ef14e8330`; this finalization changed only
documentation, host tooling references and workspace organization.

The canonical Windows workspace is rooted at `D:\3.VScode\Mi`:

- `ZuiControl`: Git repository;
- `zui072（9008）`: immutable original 072 package;
- `zui072（flash）`: work/cache/evidence/temp and sealed output;
- `Edit tools`: portable dependencies, drivers and private signing store;
- `script`: physical portable workflow entry points;
- `Review packages`: the only Gate archive destination.

`Mi\script` must never be a junction or depend on a temporary worktree. The
portable setup changes only the current PowerShell process. Git scripts permit
fast-forward pull and normal push only. Flash and physical readback are separate
explicit-confirmation workflows.

Qualcomm 9008 USB driver installation and a reviewed SELinux compile environment
remain external requirements. No unknown binary was downloaded.

```text
PRODUCTION_RUNTIME_CHANGED=NO
ROM_BUILT=NO
DEVICE_FLASHED=NO
UPERF_CORE=V20.4_CLOSED
WORKER_FAULT=OPTIONAL_HARDENING
V21_PHASE1_STATUS=CLOSED
V21_PHASE2_STATUS=NOT_STARTED
```
