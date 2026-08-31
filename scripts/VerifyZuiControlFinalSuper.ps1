[CmdletBinding()]
param(
    [string]$FlashDir = '',
    [string]$WorkDir = '',
    [string]$ExpectedVendorImageSha256 = '',
    [string]$ExpectedVendorApkInventoryPath = '',
    [switch]$KeepWork
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$RepoRoot = Split-Path -Parent $PSScriptRoot
$WorkspaceRoot = Split-Path -Parent $RepoRoot
if (-not $FlashDir) { $FlashDir = Join-Path $WorkspaceRoot '【B刷机】072' }
if (-not $WorkDir) { $WorkDir = Join-Path $WorkspaceRoot 'work\verify_final_super_v20_4' }
$BaseVerifier = Join-Path $PSScriptRoot 'VerifyZuiControlFlashPackage.ps1'

function Full([string]$Path) { [IO.Path]::GetFullPath($Path).TrimEnd('\') }
function Require-SingleFile([string]$Root, [string]$Name) {
    $matches = @(Get-ChildItem -LiteralPath $Root -Recurse -File -Filter $Name)
    if ($matches.Count -ne 1) {
        throw "Expected one $Name under $Root; found $($matches.Count)."
    }
    return $matches[0].FullName
}
function Assert-Contains([string]$Path, [string]$Needle) {
    if (-not (Select-String -LiteralPath $Path -SimpleMatch -Pattern $Needle -Quiet)) {
        throw "Missing V20.4 final-super marker in $Path`: $Needle"
    }
}
function Assert-NotContains([string]$Path, [string]$Needle) {
    if (Select-String -LiteralPath $Path -SimpleMatch -Pattern $Needle -Quiet) {
        throw "Forbidden pre-V20.4 marker remains in $Path`: $Needle"
    }
}
function Remove-VerificationWork {
    if (-not (Test-Path -LiteralPath $WorkDir)) { return }
    $workRoot = Full (Join-Path $WorkspaceRoot 'work')
    $expected = Full $WorkDir
    if ($expected -eq $workRoot -or
        -not $expected.StartsWith($workRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path -Leaf $expected) -notlike 'verify_*') {
        throw "Refusing V20.4 verification cleanup outside work/verify_*: $expected"
    }
    $item = Get-Item -LiteralPath $WorkDir -Force
    $resolved = Full (Resolve-Path -LiteralPath $WorkDir).Path
    if ($item.LinkType -or $resolved -ne $expected) {
        throw "Verification WorkDir is linked or resolves unexpectedly: $expected -> $resolved"
    }
    $links = @(Get-ChildItem -LiteralPath $resolved -Force -Recurse -Attributes ReparsePoint -ErrorAction SilentlyContinue)
    if ($links.Count) { throw "Verification WorkDir contains a reparse point: $($links[0].FullName)" }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

if (-not (Test-Path -LiteralPath $BaseVerifier -PathType Leaf)) {
    throw "Missing base final-package verifier: $BaseVerifier"
}

$ok = $false
try {
    $baseArgs = @{
        FlashDir = $FlashDir
        WorkDir = $WorkDir
        KeepWork = $true
    }
    if ($ExpectedVendorImageSha256) {
        $baseArgs.ExpectedVendorImageSha256 = $ExpectedVendorImageSha256
    }
    if ($ExpectedVendorApkInventoryPath) {
        $baseArgs.ExpectedVendorApkInventoryPath = $ExpectedVendorApkInventoryPath
    }
    $baseOutput = @(& $BaseVerifier @baseArgs)
    $baseOutput | Write-Output
    if (($baseOutput | Out-String) -notmatch '"ok"\s*:\s*true') {
        throw 'Base final-package verifier did not report ok=true.'
    }

    $services = Join-Path $WorkDir 'services_decode'
    $app = Join-Path $WorkDir 'app_decode'
    $serviceSmali = Require-SingleFile $services 'ZuiControlService.smali'
    $focusSnapshotSmali = Require-SingleFile $services 'ZuiControlService$FocusSnapshot.smali'
    $hooksSmali = Require-SingleFile $services 'ZuiControlHooks.smali'
    $displayContentSmali = Require-SingleFile $services 'DisplayContent.smali'
    $wmInternalSmali = Require-SingleFile $services 'WindowManagerInternal.smali'
    $clientSmali = Require-SingleFile $app 'ZuiControlClient.smali'
    $tileSmali = Require-SingleFile $app 'ZuiControlTileService.smali'
    $quickSmali = Require-SingleFile $app 'ZuiControlQuickService.smali'

    $markers = @(
        @{ Path = $serviceSmali; Needle = '\neditableScenePackage=' },
        @{ Path = $serviceSmali; Needle = '\neditableDisplayHz=' },
        @{ Path = $serviceSmali; Needle = '\nrawFocusTransient=' },
        @{ Path = $serviceSmali; Needle = 'mLatestFocus:Lcom/zui/server/control/ZuiControlService$FocusSnapshot;' },
        @{ Path = $serviceSmali; Needle = 'mLatestActivityFocus:Lcom/zui/server/control/ZuiControlService$FocusSnapshot;' },
        @{ Path = $serviceSmali; Needle = 'mLatestNonImeFocus:Lcom/zui/server/control/ZuiControlService$FocusSnapshot;' },
        @{ Path = $serviceSmali; Needle = 'mLatestWindowFocusSeen:Z' },
        @{ Path = $serviceSmali; Needle = 'latestFocusMatchesRaw' },
        @{ Path = $focusSnapshotSmali; Needle = '.field final packageName:Ljava/lang/String;' },
        @{ Path = $focusSnapshotSmali; Needle = '.field final uid:I' },
        @{ Path = $focusSnapshotSmali; Needle = '.field final userId:I' },
        @{ Path = $focusSnapshotSmali; Needle = '.field final displayId:I' },
        @{ Path = $focusSnapshotSmali; Needle = '.field final transientFocus:Z' },
        @{ Path = $serviceSmali; Needle = '\nnonImeFocusedPackage=' },
        @{ Path = $serviceSmali; Needle = '\nwindowFocusSeen=' },
        @{ Path = $serviceSmali; Needle = '\ndesiredScenePackage=' },
        @{ Path = $serviceSmali; Needle = '\nattemptedScenePackage=' },
        @{ Path = $serviceSmali; Needle = '\nappliedScenePackage=' },
        @{ Path = $serviceSmali; Needle = '\nattemptedDisplayHz=' },
        @{ Path = $serviceSmali; Needle = '\nappliedDisplayHz=' },
        @{ Path = $serviceSmali; Needle = '\nphysicalDisplayHz=' },
        @{ Path = $serviceSmali; Needle = '\nrefreshApplyCount=' },
        @{ Path = $serviceSmali; Needle = '\nskipSameCount=' },
        @{ Path = $serviceSmali; Needle = '\nlastApplyReason=' },
        @{ Path = $serviceSmali; Needle = '\nlastApplyError=' },
        @{ Path = $serviceSmali; Needle = '\nappRequestHandoffPending=' },
        @{ Path = $serviceSmali; Needle = '\nrefreshDisplayScope=defaultDisplayOnly' },
        @{ Path = $serviceSmali; Needle = 'transient_package_not_configurable' },
        @{ Path = $serviceSmali; Needle = 'profileSaved=1' },
        @{ Path = $serviceSmali; Needle = 'neutralProfile' },
        @{ Path = $serviceSmali; Needle = 'Landroid/os/SystemProperties;->addChangeCallback(Ljava/lang/Runnable;)V' },
        @{ Path = $serviceSmali; Needle = 'enqueueRefreshDisableMask' },
        @{ Path = $serviceSmali; Needle = 'propertyDisableRetry' },
        @{ Path = $serviceSmali; Needle = 'releaseRefreshOwnership' },
        @{ Path = $serviceSmali; Needle = 'updateGlobalVote' },
        @{ Path = $serviceSmali; Needle = 'requestTraversalFromDisplayManager' },
        @{ Path = $serviceSmali; Needle = 'externalPreserved' },
        @{ Path = $serviceSmali; Needle = 'failedBeforeMutation' },
        @{ Path = $serviceSmali; Needle = 'failedAfterMutation' },
        @{ Path = $hooksSmali; Needle = 'onFocusedWindowChanged' },
        @{ Path = $hooksSmali; Needle = 'onImeVisibilityChanged' },
        @{ Path = $displayContentSmali; Needle = 'Lcom/zui/server/control/ZuiControlHooks;->onFocusedWindowChanged(Ljava/lang/String;I)V' },
        @{ Path = $displayContentSmali; Needle = 'Lcom/zui/server/control/ZuiControlHooks;->onImeVisibilityChanged(Ljava/lang/String;ZI)V' },
        @{ Path = $wmInternalSmali; Needle = '.method public abstract requestTraversalFromDisplayManager()V' },
        @{ Path = $clientSmali; Needle = 'editableDisplayHz' },
        @{ Path = $tileSmali; Needle = '->editableDisplayHz()Ljava/lang/Integer;' },
        @{ Path = $quickSmali; Needle = '->editableDisplayHz()Ljava/lang/Integer;' }
    )
    foreach ($marker in $markers) {
        Assert-Contains $marker.Path $marker.Needle
    }
    Assert-NotContains $serviceSmali 'controlPanel'

    $ok = $true
    [pscustomobject]@{
        ok = $true
        phase = 'V20_4'
        flash_dir = (Full $FlashDir)
        base_verifier = $BaseVerifier
        marker_count = $markers.Count + 1
    } | ConvertTo-Json -Depth 3
} finally {
    if ($ok -and -not $KeepWork) {
        Remove-VerificationWork
    } elseif (-not $ok -and (Test-Path -LiteralPath $WorkDir)) {
        Write-Warning "V20.4 verification workspace kept for debugging: $WorkDir"
    }
}
