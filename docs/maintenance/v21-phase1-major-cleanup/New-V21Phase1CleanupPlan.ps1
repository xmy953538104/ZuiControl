[CmdletBinding()]
param(
    [string]$WorkspaceRoot = 'D:\3.VScode\Mi',
    [Parameter(Mandatory)][string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Full([string]$Path) { [IO.Path]::GetFullPath($Path).TrimEnd('\') }
function Sha256-Text([string]$Text) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text)))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
}
function Identity([string]$Path) {
    $root = Full $Path
    $item = Get-Item -LiteralPath $root -Force
    $links = @($item) + @(Get-ChildItem -LiteralPath $root -Recurse -Force -Attributes ReparsePoint -ErrorAction SilentlyContinue)
    if (@($links | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count) { throw "Reparse target: $root" }
    $files = @(if ($item.PSIsContainer) { Get-ChildItem -LiteralPath $root -Recurse -Force -File | Sort-Object FullName } else { $item })
    [int64]$bytes = 0
    $lines = @(foreach ($file in $files) {
        $bytes += $file.Length
        $relative = if ($item.PSIsContainer) { $file.FullName.Substring($root.Length).TrimStart('\').Replace('\','/') } else { $file.Name }
        "$((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant())  $relative"
    })
    $treeText = if ($lines.Count) { ($lines -join "`n") + "`n" } else { '' }
    [pscustomobject]@{ bytes=$bytes; file_count=$files.Count; tree_sha256=(Sha256-Text $treeText) }
}

$workspace = Full (Resolve-Path -LiteralPath $WorkspaceRoot).Path
$specs = [Collections.Generic.List[object]]::new()
function Add-Spec([string]$Relative, [string]$Category, [string]$Reason) {
    $path = Full (Join-Path $workspace $Relative)
    if (Test-Path -LiteralPath $path) {
        $specs.Add([pscustomobject]@{ path=$path; category=$Category; reason=$Reason })
    }
}

# One-time, human-reviewed V21 Phase 1 migration targets. Canonical roots and keys are absent.
Add-Spec 'flash' 'DELETE_AFTER_KNOWLEDGE_EXTRACTION' 'Golden/tools migrated; remainder is failed packages and raw logs'
Add-Spec '【B刷机】072' 'DELETE_DUPLICATE' 'Superseded by hash-pinned V20.4 Golden'
Add-Spec '重要文件' 'DELETE_AFTER_KNOWLEDGE_EXTRACTION' 'Legacy 187 and duplicated old payload assets'
Add-Spec 'ZuiControl_Archive' 'DELETE_AFTER_KNOWLEDGE_EXTRACTION' 'Historical knowledge consolidated into canonical docs'
Add-Spec 'boot.img' 'DELETE_DUPLICATE' 'Loose duplicate; original and Golden remain'
Add-Spec 'evidence' 'DELETE_AFTER_KNOWLEDGE_EXTRACTION' 'Superseded root evidence'
Add-Spec 'docs' 'DELETE_AFTER_KNOWLEDGE_EXTRACTION' 'Superseded workspace docs'
Add-Spec 'ZuiControl_Workspace' 'DELETE_REGENERABLE' 'Superseded lifecycle layout'
Add-Spec 'tools\__pycache__' 'DELETE_REGENERABLE' 'Generated Python bytecode'

$rootFiles = @(
    'V20_4_UPERF_READY_MARKER_SELINUX_CORRECTION.zip',
    'V20_4_UPERF_READY_MARKER_SELINUX_CORRECTION.zip.sha256',
    'V20_4_UPERF_TOP_RESUMED_ATTRIBUTION_DIAGNOSTIC.zip',
    'V20_4_UPERF_TOP_RESUMED_TRANSITIONAL_NULL_CORRECTION.zip',
    'V20_4_UPERF_TOP_RESUMED_TRANSITIONAL_NULL_CORRECTION.zip.sha256',
    'V20_4_UPERF_READY_MARKER_DEVICE_STARTUP_RUNTIME_GATE.zip',
    'V20_4_UPERF_READY_MARKER_DEVICE_STARTUP_RUNTIME_GATE.zip.sha256',
    'V20_4_UPERF_STARTUP_ROOT_CAUSE_AND_WORKFLOW_AUDIT.zip',
    'V20_4_UPERF_PROCESS_LIFETIME_SUPERVISOR_DEVICE_GATE.zip',
    'V20_4_UPERF_REAL_OUTPUT_CONTRACT_DIAGNOSTIC.zip',
    'V20_4_UPERF_PROCESS_LIFETIME_SUPERVISOR_CORRECTION.zip',
    'V21_PHASE1_GOLDEN_FREEZE_ENGINEERING_CLEANUP.zip',
    'V21_PHASE1_GOLDEN_FREEZE_ENGINEERING_CLEANUP.zip.sha256',
    'WorkShell.cmd'
)
foreach ($name in $rootFiles) { Add-Spec $name 'DELETE_AFTER_KNOWLEDGE_EXTRACTION' 'Superseded root diagnostic/helper' }

$workRoot = Join-Path $workspace 'work'
$workProtected = @(
    (Full (Join-Path $workRoot 'scene_vtools')),
    (Full (Join-Path $workRoot 'zuiperfctl-temp-release.jks'))
)
foreach ($item in Get-ChildItem -LiteralPath $workRoot -Force | Sort-Object FullName) {
    $path = Full $item.FullName
    if ($workProtected -notcontains $path) {
        $specs.Add([pscustomobject]@{ path=$path; category='DELETE_REGENERABLE'; reason='Old extraction/smali/readback/CI/diagnostic scratch after retained assets migrated' })
    }
}

foreach ($item in Get-ChildItem -LiteralPath (Join-Path $workspace 'avb\tools') -Force | Where-Object Name -ne 'pem' | Sort-Object FullName) {
    $specs.Add([pscustomobject]@{ path=(Full $item.FullName); category='DELETE_DUPLICATE'; reason='Duplicate tool copy; canonical version is in Edit tools' })
}

$targets = [Collections.Generic.List[object]]::new()
foreach ($spec in $specs) {
    $identity = Identity $spec.path
    $targets.Add([pscustomobject][ordered]@{
        path = $spec.path
        category = $spec.category
        reason = $spec.reason
        approved = $true
        expected_bytes = $identity.bytes
        expected_file_count = $identity.file_count
        expected_tree_sha256 = $identity.tree_sha256
    })
}

[pscustomobject][ordered]@{
    schema = 1
    purpose = 'V21 Phase 1 one-time reviewed migration cleanup'
    generated_at = (Get-Date).ToString('o')
    targets = $targets
} | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath $OutputPath -Encoding utf8

"TARGETS=$($targets.Count)"
"BYTES=$([long](($targets | Measure-Object expected_bytes -Sum).Sum))"
"PLAN=$OutputPath"
