[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$scriptPath = Join-Path $repo 'scripts\tools\Convert-ZuiQdlProgressLog.ps1'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('zui-qdl-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $testRoot
$raw = Join-Path $testRoot 'raw.log'
$compact = Join-Path $testRoot 'compact.txt'
try {
    $lines = [Collections.Generic.List[string]]::new()
    $lines.Add('qdl-rs 0.1.0')
    for ($index = 0; $index -le 256; $index++) {
        $percent = [math]::Round(($index / 256.0) * 100, 2)
        $lines.Add("Reading partition probe: $index.00 MB / 256.00 MB $percent %")
    }
    $lines.Add('LOG: ERROR: retained diagnostic')
    $lines.Add('All went well! Resetting to edl')
    [IO.File]::WriteAllLines($raw, $lines, [Text.UTF8Encoding]::new($false))
    $null = @(& $scriptPath -LogPath $raw -OutputPath $compact -ProgressIntervalBytes 64MB -VerifiedOperation)
    $text = Get-Content -LiteralPath $compact -Raw
    $progressRows = @(Get-Content -LiteralPath $compact | Where-Object { $_ -match '^Reading partition' })
    if ($progressRows.Count -gt 6 -or $text -notmatch '100(\.00)? %' -or $text -notmatch 'retained diagnostic' -or $text -notmatch 'All went well') {
        throw 'Compact progress contract failed.'
    }
    if (-not (Test-Path -LiteralPath $raw)) { throw 'Source log was deleted without -DeleteOriginal.' }
    'QDL_64MIB_PROGRESS=PASS'
    'QDL_ERROR_AND_COMPLETION_RETENTION=PASS'
    'QDL_DEFAULT_SOURCE_RETENTION=PASS'
} finally {
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
