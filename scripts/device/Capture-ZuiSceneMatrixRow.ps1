[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Label,
    [Parameter(Mandatory)][string]$TsvPath,
    [Parameter(Mandatory)][string]$RawDirectory,
    [string]$Note = '',
    [string]$Serial,
    [string]$AdbPath = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ZuiSceneHarness.psm1') -Force

function OneLine([object]$Value) {
    if ($null -eq $Value) { return '' }
    return (([string]$Value -replace "`t", ' ') -replace "`r?`n", ' ').Trim()
}

$runner = Join-Path $PSScriptRoot 'Run-ZuiDeviceScript.ps1'
$collector = Join-Path $PSScriptRoot 'capture_zui_scene_snapshot.sh'
$hostUtc = [DateTime]::UtcNow.ToString('o')
$snapshotText = @(& $runner -LocalScript $collector -Serial $Serial -AdbPath $AdbPath) -join "`n"
$state = ConvertFrom-ZuiSceneSnapshot -Text $snapshotText
$columns = @('label','host_utc','device_epoch','resumed_activity','top_resumed_package','uperfTopResumedRawPackage','uperfTopResumedStablePackage','uperfTopResumedPendingNull','uperfScenePackage','uperfDesiredMode','uperfLastAppliedMode','uperfApplyCount','uperfLastReason','protected_uperf_mode','effective_powermode','cur_powermode','uperf_service','uperf_fail_safe','system_server_process_id','note')
$values = @($Label,$hostUtc,$state.device_epoch,$state.resumed_activity,$state.top_resumed_package,$state.uperfTopResumedRawPackage,$state.uperfTopResumedStablePackage,$state.uperfTopResumedPendingNull,$state.uperfScenePackage,$state.uperfDesiredMode,$state.uperfLastAppliedMode,$state.uperfApplyCount,$state.uperfLastReason,$state.protected_uperf_mode,$state.effective_powermode,$state.cur_powermode,$state.uperf_service,$state.uperf_fail_safe,$state.system_server_process_id,$Note) | ForEach-Object { OneLine $_ }
$tsv = [IO.Path]::GetFullPath($TsvPath)
$raw = [IO.Path]::GetFullPath($RawDirectory)
$null = New-Item -ItemType Directory -Path (Split-Path -Parent $tsv), $raw -Force
$header = $columns -join "`t"
if (Test-Path -LiteralPath $tsv) {
    if ((Get-Content -LiteralPath $tsv -TotalCount 1) -ne $header) { throw "TSV schema mismatch: $tsv" }
} else {
    [IO.File]::WriteAllText($tsv, $header + "`n", [Text.UTF8Encoding]::new($false))
}
[IO.File]::AppendAllText($tsv, ($values -join "`t") + "`n", [Text.UTF8Encoding]::new($false))
$safeLabel = $Label -replace '[^A-Za-z0-9_.-]', '_'
[IO.File]::WriteAllText((Join-Path $raw ($safeLabel + '.txt')), $snapshotText + "`n", [Text.UTF8Encoding]::new($false))
"SCENE_CAPTURED=$Label authority=$($state.top_resumed_package) mode=$($state.protected_uperf_mode)"
