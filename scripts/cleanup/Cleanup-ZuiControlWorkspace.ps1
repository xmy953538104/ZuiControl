[CmdletBinding()]
param(
    [string]$WorkspaceRoot = 'D:\3.VScode\Mi',
    [Parameter(Mandatory)][string]$PlanPath,
    [string]$BaselineManifest = '',
    [string]$ReceiptPath,
    [switch]$MigrationCleanup,
    [switch]$AllowCachePrune,
    [switch]$Execute
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Full([string]$Path) { [IO.Path]::GetFullPath($Path).TrimEnd('\') }
function Is-Within([string]$Child, [string]$Parent) {
    $childPath = Full $Child
    $parentPath = Full $Parent
    return $childPath.StartsWith($parentPath + '\', [StringComparison]::OrdinalIgnoreCase)
}
function Sha256-Text([string]$Text) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text)))).Replace('-', '').ToLowerInvariant()
    } finally { $sha.Dispose() }
}
function Get-TreeIdentity([string]$Path) {
    $root = Full $Path
    $item = Get-Item -LiteralPath $root -Force
    $links = @($item) + @(Get-ChildItem -LiteralPath $root -Recurse -Force -Attributes ReparsePoint -ErrorAction SilentlyContinue)
    if (@($links | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count) {
        throw "Refusing reparse point in cleanup target: $root"
    }
    $files = @(if ($item.PSIsContainer) {
        @(Get-ChildItem -LiteralPath $root -Recurse -Force -File | Sort-Object FullName)
    } else { @($item) })
    [int64]$bytes = 0
    $lines = @(foreach ($file in $files) {
        $bytes += $file.Length
        $relative = if ($item.PSIsContainer) { $file.FullName.Substring($root.Length).TrimStart('\').Replace('\', '/') } else { $file.Name }
        "$((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant())  $relative"
    })
    $treeText = if ($lines.Count) { ($lines -join "`n") + "`n" } else { '' }
    return [pscustomobject][ordered]@{
        bytes = $bytes
        file_count = $files.Count
        tree_sha256 = Sha256-Text $treeText
    }
}

$workspace = Full (Resolve-Path -LiteralPath $WorkspaceRoot).Path
$planFile = Full (Resolve-Path -LiteralPath $PlanPath).Path
if (-not $BaselineManifest) {
    $repoRoot = (& git -C $PSScriptRoot rev-parse --show-toplevel 2>$null | Select-Object -First 1)
    if (-not $repoRoot) { throw 'Cannot resolve Git repo for default Golden baseline manifest.' }
    $BaselineManifest = Join-Path $repoRoot.Trim() 'V20_4_GOLDEN_BASELINE.json'
}
$baselineFile = Full (Resolve-Path -LiteralPath $BaselineManifest).Path
$plan = Get-Content -LiteralPath $planFile -Raw | ConvertFrom-Json
$baseline = Get-Content -LiteralPath $baselineFile -Raw | ConvertFrom-Json
if ($plan.schema -ne 1 -or -not $plan.targets) { throw 'Cleanup plan schema/targets are invalid.' }

$managed = Join-Path $workspace 'zui072（flash）\work'
$tempRoot = Full (Join-Path $managed 'temp')
$currentRoot = Full (Join-Path $managed 'current')
$cacheRoot = Full (Join-Path $managed 'cache')
$allowedRoots = @($tempRoot)
if ($MigrationCleanup) { $allowedRoots += $workspace }
if ($AllowCachePrune) { $allowedRoots += $cacheRoot }
$allowedRoots = @($allowedRoots | Select-Object -Unique)
$protected = @(
    (Join-Path $workspace 'ZuiControl'),
    (Join-Path $managed 'git-worktrees'),
    (Join-Path $workspace 'zui072（9008）'),
    (Join-Path $workspace 'zui072（flash）\out'),
    (Join-Path $workspace 'Edit tools'),
    (Join-Path $workspace 'script'),
    (Join-Path $managed 'evidence'),
    (Join-Path $workspace 'Review packages'),
    (Join-Path $workspace 'Edit tools\Signing'),
    [string]$baseline.artifacts.golden_package,
    [string]$baseline.artifacts.closure_zip
) | Where-Object { $_ } | ForEach-Object { Full $_ } | Select-Object -Unique

if (-not $ReceiptPath) {
    $receiptRoot = Join-Path $managed 'evidence\workspace_cleanup'
    $null = New-Item -ItemType Directory -Path $receiptRoot -Force
    $ReceiptPath = Join-Path $receiptRoot ("cleanup_{0}.json" -f (Get-Date -Format 'yyyyMMddHHmmss'))
}
$receipt = Full $ReceiptPath
$receiptParent = Split-Path -Parent $receipt
$null = New-Item -ItemType Directory -Path $receiptParent -Force

$seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$rows = [Collections.Generic.List[object]]::new()
[int64]$eligibleBytes = 0
foreach ($entry in $plan.targets) {
    if ($entry.approved -ne $true) { throw "Target is not explicitly approved: $($entry.path)" }
    $category = [string]$entry.category
    if ($category -notin @('temp','obsolete_current','cache_prune','DELETE_AFTER_KNOWLEDGE_EXTRACTION','DELETE_DUPLICATE','DELETE_REGENERABLE')) {
        throw "Invalid cleanup category: $category"
    }
    $target = Full ([string]$entry.path)
    if (-not $seen.Add($target)) { throw "Duplicate cleanup target: $target" }
    foreach ($prior in $seen) {
        if ($prior -ne $target -and ((Is-Within $prior $target) -or (Is-Within $target $prior))) {
            throw "Cleanup targets overlap: $target <-> $prior"
        }
    }
    $allowed = $false
    if ($category -eq 'temp') {
        $allowed = Is-Within $target $tempRoot
    } elseif ($category -eq 'obsolete_current') {
        $allowed = Is-Within $target $currentRoot
    } elseif ($category -eq 'cache_prune') {
        $allowed = $AllowCachePrune -and (Is-Within $target $cacheRoot)
    } elseif ($category -like 'DELETE_*') {
        $allowed = $MigrationCleanup -and (Is-Within $target $workspace)
    }
    if (-not $allowed) { throw "Target/category is outside enabled cleanup policy: $category $target" }
    foreach ($protectedPath in $protected) {
        if ($target -eq $protectedPath -or (Is-Within $target $protectedPath) -or (Is-Within $protectedPath $target)) {
            throw "Target overlaps protected path: $target <-> $protectedPath"
        }
    }
    if (-not (Test-Path -LiteralPath $target)) {
        $rows.Add([pscustomobject][ordered]@{ path=$target; category=$entry.category; reason=$entry.reason; status='MISSING'; bytes=0; file_count=0; tree_sha256='' })
        continue
    }
    $identity = Get-TreeIdentity $target
    if ([int64]$entry.expected_bytes -ne $identity.bytes -or [int]$entry.expected_file_count -ne $identity.file_count -or [string]$entry.expected_tree_sha256 -cne $identity.tree_sha256) {
        throw "Cleanup target identity changed: $target"
    }
    $eligibleBytes += $identity.bytes
    $rows.Add([pscustomobject][ordered]@{ path=$target; category=$entry.category; reason=$entry.reason; status=if($Execute){'REMOVE'}else{'WOULD_REMOVE'}; bytes=$identity.bytes; file_count=$identity.file_count; tree_sha256=$identity.tree_sha256 })
}

$driveName = [IO.Path]::GetPathRoot($workspace).TrimEnd(':','\')
$freeBefore = (Get-PSDrive -Name $driveName).Free
if ($Execute) {
    foreach ($row in $rows | Where-Object status -eq 'REMOVE') {
        Remove-Item -LiteralPath $row.path -Recurse -Force
        if (Test-Path -LiteralPath $row.path) { throw "Cleanup target still exists: $($row.path)" }
        $row.status = 'REMOVED'
    }
}
$freeAfter = (Get-PSDrive -Name $driveName).Free
$record = [pscustomobject][ordered]@{
    schema = 1
    timestamp = (Get-Date).ToString('o')
    mode = if($Execute){'Execute'}else{'DryRun'}
    migration_cleanup = [bool]$MigrationCleanup
    cache_prune = [bool]$AllowCachePrune
    workspace_root = $workspace
    plan = $planFile
    baseline_manifest = $baselineFile
    allowed_roots = $allowedRoots
    protected_paths = $protected
    target_count = $rows.Count
    bytes_eligible = $eligibleBytes
    bytes_reclaimed = if($Execute){$eligibleBytes}else{0}
    free_space_delta = $freeAfter - $freeBefore
    targets = $rows
}
$record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $receipt -Encoding utf8
"CLEANUP_MODE=$($record.mode)"
"TARGET_COUNT=$($record.target_count)"
"BYTES_ELIGIBLE=$eligibleBytes"
"BYTES_RECLAIMED=$($record.bytes_reclaimed)"
"RECEIPT=$receipt"
