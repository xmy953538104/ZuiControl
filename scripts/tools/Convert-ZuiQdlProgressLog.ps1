[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$LogPath,
    [Parameter(Mandatory)][string]$OutputPath,
    [int64]$ProgressIntervalBytes = 64MB,
    [switch]$VerifiedOperation,
    [switch]$DeleteOriginal
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($ProgressIntervalBytes -lt 1MB) { throw 'ProgressIntervalBytes must be at least 1 MiB.' }
$source = (Resolve-Path -LiteralPath $LogPath).Path
$output = [IO.Path]::GetFullPath($OutputPath)
if ($source -eq $output) { throw 'OutputPath must differ from LogPath.' }

function ProgressBytes([string]$Line) {
    if ($Line -notmatch '(?i)(?:Sending|Reading) partition [^:]+:\s+([0-9.]+)\s+(B|KB|MB|GB)\s+/') { return $null }
    $scale = switch ($Matches[2].ToUpperInvariant()) { 'B'{1}; 'KB'{1KB}; 'MB'{1MB}; 'GB'{1GB} }
    return [int64]([double]$Matches[1] * $scale)
}

$sourceItem = Get-Item -LiteralPath $source
$sourceHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
$retained = [Collections.Generic.List[string]]::new()
$lastProgress = @{}
$sawSuccess = $false
foreach ($rawLine in [IO.File]::ReadLines($source)) {
    $line = $rawLine -replace "$([char]27)\[[0-9;?]*[ -/]*[@-~]", ''
    $progress = ProgressBytes $line
    if ($null -ne $progress) {
        $name = if ($line -match '(?i)(?:Sending|Reading) partition ([^:]+):') { $Matches[1] } else { 'unknown' }
        $previous = if ($lastProgress.ContainsKey($name)) { [int64]$lastProgress[$name] } else { [int64]-1 }
        if ($previous -lt 0 -or $progress - $previous -ge $ProgressIntervalBytes -or $line -match '100\.00\s*%') {
            $retained.Add($line)
            $lastProgress[$name] = $progress
        }
        continue
    }
    if ($line -match '^(qdl-rs|Using a default|Chip serial|OEM Private|Loader sent|LOG:)' -or $line -match '(?i)\b(error|failed|failure|NAK|timeout|success)\b|All went well') {
        $retained.Add($line)
    }
    if ($line -match 'All went well') { $sawSuccess = $true }
}
$header = @(
    "ORIGINAL_LOG=$([IO.Path]::GetFileName($source))"
    "ORIGINAL_BYTES=$($sourceItem.Length)"
    "ORIGINAL_SHA256=$sourceHash"
    "PROGRESS_INTERVAL_BYTES=$ProgressIntervalBytes"
    "VERIFIED_OPERATION=$($VerifiedOperation.IsPresent)"
    '--- RETAINED QDL OUTPUT ---'
)
$null = New-Item -ItemType Directory -Path (Split-Path -Parent $output) -Force
[IO.File]::WriteAllLines($output, [string[]](@($header) + @($retained)), [Text.UTF8Encoding]::new($false))
$outputHash = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
$manifest = [pscustomobject][ordered]@{ source=$source; source_bytes=$sourceItem.Length; source_sha256=$sourceHash; compact=$output; compact_bytes=(Get-Item -LiteralPath $output).Length; compact_sha256=$outputHash; progress_interval_bytes=$ProgressIntervalBytes; saw_success_marker=$sawSuccess; source_deleted=$false }
if ($DeleteOriginal) {
    if (-not $VerifiedOperation -or -not $sawSuccess) { throw 'Original deletion requires -VerifiedOperation and a retained success marker.' }
    Remove-Item -LiteralPath $source -Force
    $manifest.source_deleted = -not (Test-Path -LiteralPath $source)
}
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath ($output + '.json') -Encoding utf8
"QDL_COMPACT_LOG=$output"
"QDL_SOURCE_BYTES=$($manifest.source_bytes)"
"QDL_COMPACT_BYTES=$($manifest.compact_bytes)"
"QDL_SOURCE_DELETED=$($manifest.source_deleted)"
