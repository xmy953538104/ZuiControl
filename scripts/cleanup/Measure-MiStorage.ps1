[CmdletBinding()]
param(
    [string]$Root = '',
    [Parameter(Mandatory)][string]$OutputDirectory,
    [int]$Top = 100
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..')).TrimEnd('\') }

$rootPath = (Resolve-Path -LiteralPath $Root).Path.TrimEnd('\')
$output = [IO.Path]::GetFullPath($OutputDirectory)
$null = New-Item -ItemType Directory -Path $output -Force
$files = @(Get-ChildItem -LiteralPath $rootPath -Recurse -Force -File -ErrorAction Stop)
$directoryBytes = @{}
$topLevel = @{}
[int64]$totalBytes = 0

foreach ($file in $files) {
    [int64]$length = $file.Length
    $totalBytes += $length
    $relative = $file.FullName.Substring($rootPath.Length).TrimStart('\')
    $first = ($relative -split '\\', 2)[0]
    if (-not $topLevel.ContainsKey($first)) {
        $topLevel[$first] = [pscustomobject][ordered]@{ path=$first; bytes=[int64]0; file_count=0 }
    }
    $topLevel[$first].bytes += $length
    $topLevel[$first].file_count++

    $parent = $file.Directory
    while ($null -ne $parent -and $parent.FullName.StartsWith($rootPath + '\', [StringComparison]::OrdinalIgnoreCase)) {
        if (-not $directoryBytes.ContainsKey($parent.FullName)) { $directoryBytes[$parent.FullName] = [int64]0 }
        $directoryBytes[$parent.FullName] += $length
        $parent = $parent.Parent
    }
}

$topFiles = @($files | Sort-Object Length -Descending | Select-Object -First $Top | ForEach-Object {
    [pscustomobject][ordered]@{ path=$_.FullName; bytes=[int64]$_.Length }
})
$topDirectories = @($directoryBytes.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First $Top | ForEach-Object {
    [pscustomobject][ordered]@{ path=$_.Key; bytes=[int64]$_.Value }
})
$topRows = @($topLevel.Values | Sort-Object bytes -Descending)
$summary = [pscustomobject][ordered]@{
    schema = 1
    measured_at = (Get-Date).ToString('o')
    root = $rootPath
    bytes = $totalBytes
    file_count = $files.Count
    top_level = $topRows
}

$summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $output 'storage_summary.json') -Encoding utf8
$topFiles | Export-Csv -LiteralPath (Join-Path $output 'top_100_files.tsv') -Delimiter "`t" -NoTypeInformation -Encoding utf8
$topDirectories | Export-Csv -LiteralPath (Join-Path $output 'top_100_directories.tsv') -Delimiter "`t" -NoTypeInformation -Encoding utf8
"MI_BYTES=$totalBytes"
"MI_FILES=$($files.Count)"
"OUTPUT=$output"
