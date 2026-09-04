[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "D:\3.VScode\Mi",
    [string]$KeepCiRun = "32809284592",
    [switch]$Execute
)

$ErrorActionPreference = "Stop"

$workspace = (Resolve-Path -LiteralPath $WorkspaceRoot).Path.TrimEnd("\")
$workRoot = (Resolve-Path -LiteralPath (Join-Path $workspace "work")).Path.TrimEnd("\")
$releaseFolder = -join @(
    [char]0x3010,
    "B",
    [char]0x5237,
    [char]0x673A,
    [char]0x3011,
    "072"
)
$releaseDir = Join-Path $workspace $releaseFolder
$keptCiDir = Join-Path $workRoot "ci_artifacts\zuicontrol_$KeepCiRun"

foreach ($required in @(
    (Join-Path $releaseDir "super.img"),
    (Join-Path $releaseDir "SHA256SUMS_ZuiControl_v19.txt"),
    $keptCiDir
)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Required release evidence is missing: $required"
    }
}

$relativeTargets = @(
    "unpack",
    "img",
    "flash_img",
    "super.img",
    "removed_for_space\QQMusic_v49_release_20260825",
    "ci_artifacts\zuicontrol_32805453769",
    "ci_artifacts\zuicontrol_32806374946",
    "zui_control_framework_patch",
    "verify_services_61a4b26",
    "gamehelper_v49_audit"
)

$targets = foreach ($relative in $relativeTargets) {
    $candidate = Join-Path $workRoot $relative
    if (-not (Test-Path -LiteralPath $candidate)) {
        continue
    }
    $resolved = (Resolve-Path -LiteralPath $candidate).Path
    if (-not $resolved.StartsWith($workRoot + "\", [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing path outside work root: $resolved"
    }
    if ($resolved -eq $workRoot -or $resolved -eq $keptCiDir) {
        throw "Refusing protected path: $resolved"
    }
    $resolved
}

if (-not $Execute) {
    $targets | ForEach-Object { "WOULD_REMOVE $_" }
    "Preview only. Re-run with -Execute after reviewing the allowlist."
    exit 0
}

foreach ($target in $targets) {
    "REMOVE $target"
    Remove-Item -LiteralPath $target -Recurse -Force
}

"CLEANUP_DONE"
Get-PSDrive -Name ([System.IO.Path]::GetPathRoot($workspace).TrimEnd(":\")) |
    Select-Object Name,
        @{Name = "FreeGiB"; Expression = { [math]::Round($_.Free / 1GB, 3) }},
        @{Name = "UsedGiB"; Expression = { [math]::Round($_.Used / 1GB, 3) }}
