[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$scriptPath = Join-Path $repo 'scripts\cleanup\Cleanup-ZuiControlWorkspace.ps1'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('zui-cleanup-' + [guid]::NewGuid().ToString('N'))
$target = Join-Path $testRoot 'zui072（flash）\work\temp\scratch-case'
$null = New-Item -ItemType Directory -Path $target -Force
[IO.File]::WriteAllText((Join-Path $target 'probe.txt'), 'test', [Text.UTF8Encoding]::new($false))

function TreeHash([string]$Root) {
    $file = Get-Item -LiteralPath (Join-Path $Root 'probe.txt')
    $line = "$((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant())  probe.txt`n"
    $sha = [Security.Cryptography.SHA256]::Create()
    try { ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($line)))).Replace('-','').ToLowerInvariant() } finally { $sha.Dispose() }
}

try {
    $baseline = Join-Path $testRoot 'baseline.json'
    $plan = Join-Path $testRoot 'plan.json'
    $dryReceipt = Join-Path $testRoot 'dry.json'
    $executeReceipt = Join-Path $testRoot 'execute.json'
    [pscustomobject]@{ artifacts = @{ golden_package = (Join-Path $testRoot 'golden'); closure_zip = (Join-Path $testRoot 'closure.zip') } } |
        ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $baseline -Encoding utf8
    [pscustomobject]@{ schema=1; targets=@([pscustomobject]@{ path=$target; category='temp'; reason='host fixture'; approved=$true; expected_bytes=4; expected_file_count=1; expected_tree_sha256=(TreeHash $target) }) } |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $plan -Encoding utf8
    $null = @(& $scriptPath -WorkspaceRoot $testRoot -PlanPath $plan -BaselineManifest $baseline -ReceiptPath $dryReceipt)
    $dry = Get-Content -LiteralPath $dryReceipt -Raw | ConvertFrom-Json
    if ($dry.mode -ne 'DryRun' -or $dry.bytes_reclaimed -ne 0 -or -not (Test-Path -LiteralPath $target)) { throw 'DryRun contract failed.' }
    $null = @(& $scriptPath -WorkspaceRoot $testRoot -PlanPath $plan -BaselineManifest $baseline -ReceiptPath $executeReceipt -Execute)
    $executed = Get-Content -LiteralPath $executeReceipt -Raw | ConvertFrom-Json
    if ($executed.mode -ne 'Execute' -or $executed.bytes_reclaimed -ne 4 -or (Test-Path -LiteralPath $target)) { throw 'Execute contract failed.' }
    'CLEANUP_EXACT_PLAN=PASS'
    'CLEANUP_DRYRUN_DEFAULT=PASS'
    'CLEANUP_RECEIPT_AND_RECLAIMED_BYTES=PASS'
} finally {
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
