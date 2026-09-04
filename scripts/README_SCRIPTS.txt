ZuiControl canonical scripts

build\BuildZuiControl.ps1 | Build/sign App and copy it to payload | powershell -File script\build\BuildZuiControl.ps1 | canonical
build\ApplyZuiControlPayload.py | Apply payload to an explicit unpack tree | python script\build\ApplyZuiControlPayload.py --unpack <path> | canonical
build\PatchZuiControlFramework.py | Patch framework/services artifacts | python script\build\PatchZuiControlFramework.py ... | canonical
build\ImportUperfUpstream.py | Audit a supplied upstream Uperf ZIP | python script\build\ImportUperfUpstream.py <zip> | canonical
flash\PrepareZuiControl9008Package.ps1 | Build an explicit fixed-seven package; no candidate auto-discovery | powershell -File script\flash\PrepareZuiControl9008Package.ps1 -SourceDir <accepted> -PlatformDir <original> | canonical
flash\FlashZuiControl9008.ps1 | Preflight/authorized EDL fixed-seven flash | powershell -File script\flash\FlashZuiControl9008.ps1 -Mode Preflight | canonical; never run Flash without approval
verify\VerifyZuiControlFlashPackage.ps1 | Reverse-extract and verify final package | powershell -File script\verify\VerifyZuiControlFlashPackage.ps1 -FlashDir <path> | canonical
verify\VerifyZuiControlFinalSuper.ps1 | V20.4 semantic verifier layered on final-package verification | powershell -File script\verify\VerifyZuiControlFinalSuper.ps1 -FlashDir <path> | canonical
verify\VerifyZuiControl9008Readback.py | Verify physical partition dumps | python script\verify\VerifyZuiControl9008Readback.py ... | canonical
verify\VerifyUperfRuntimeAccess.py | Verify Uperf runtime access graph | python script\verify\VerifyUperfRuntimeAccess.py ... | canonical
device\*.ps1 + *.sh | Known-good push/root-execute/pull helpers and scene harness | see script\device\README.md | canonical
cleanup\Cleanup-ZuiControlWorkspace.ps1 | Exact-plan cleanup with dry-run default | powershell -File script\cleanup\Cleanup-ZuiControlWorkspace.ps1 -PlanPath <json> | canonical
cleanup\Measure-MiStorage.ps1 | Stable workspace size/top-N inventory | powershell -File script\cleanup\Measure-MiStorage.ps1 -OutputDirectory <path> | canonical
tools\Convert-ZuiQdlProgressLog.ps1 | Compact qdl progress while retaining errors/completion | powershell -File script\tools\Convert-ZuiQdlProgressLog.ps1 ... | canonical

Image build shell | No canonical copy exists after Phase 1 cleanup | supply and review an explicit path in a future production work package | intentionally not inferred; deleted legacy paths are forbidden

The repository `scripts` tree remains the source-level build/test/verifier layer. The workspace-level
`D:\3.VScode\Mi\script` path is a physical portable operations directory, not a junction, symlink,
or temporary-worktree dependency. Common environment/Git/flash/readback/cleanup/Gate entry points live there.
