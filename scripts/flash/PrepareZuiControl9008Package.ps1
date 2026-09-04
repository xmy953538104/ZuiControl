param(
    [string]$SourceDir = '',
    [string]$PlatformDir = '',
    [string]$OutputDir = ''
)

$ErrorActionPreference = "Stop"
$miRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..')).TrimEnd('\')
if (-not $SourceDir) { $SourceDir = Join-Path $miRoot 'zui072（flash）\out\V20.4_Golden_20260903144915' }
if (-not $PlatformDir) { $PlatformDir = Join-Path $miRoot 'zui072（9008）' }
if (-not $OutputDir) { $OutputDir = Join-Path $miRoot 'zui072（flash）\work\current\fixed-seven' }

function Require-File([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing required file: $Path"
    }
}

if ([string]::IsNullOrWhiteSpace($SourceDir) -or [string]::IsNullOrWhiteSpace($PlatformDir)) {
    throw 'SourceDir and PlatformDir must be explicit; candidate discovery by directory name is forbidden.'
}
$source = [IO.Path]::GetFullPath($SourceDir)
$platform = [IO.Path]::GetFullPath($PlatformDir)
$output = [IO.Path]::GetFullPath($OutputDir)
if ($source.TrimEnd('\') -eq $output.TrimEnd('\')) {
    throw "OutputDir must be a dedicated directory, not the full flash package."
}

$imageNames = @('super.img', 'vbmeta_system.img', 'boot.img', 'vbmeta.img')
$loaderName = 'prog_firehose_ddr-TB321FC.elf'
$checksumName = 'SHA256SUMS_ZuiControl_v19.txt'
$generatedNames = @(
    $loaderName,
    $checksumName,
    'rawprogram_zuicontrol.xml',
    'README_SAFE_9008.txt'
) + $imageNames

foreach ($name in $imageNames + @($checksumName)) {
    Require-File (Join-Path $source $name)
}
Require-File (Join-Path $platform $loaderName)

$sourceXmlFiles = Get-ChildItem -LiteralPath $platform -Filter 'rawprogram*.xml' -File |
    Where-Object { $_.Name -match '^rawprogram[0-5]\.xml$' } |
    Sort-Object Name
if ($sourceXmlFiles.Count -ne 6) {
    throw "Expected exactly rawprogram0.xml through rawprogram5.xml in $platform"
}

$expected = [ordered]@{
    super = @{ File = 'super.img'; Lun = '0' }
    vbmeta_system_a = @{ File = 'vbmeta_system.img'; Lun = '0' }
    vbmeta_system_b = @{ File = 'vbmeta_system.img'; Lun = '0' }
    boot_a = @{ File = 'boot.img'; Lun = '4' }
    boot_b = @{ File = 'boot.img'; Lun = '4' }
    vbmeta_a = @{ File = 'vbmeta.img'; Lun = '4' }
    vbmeta_b = @{ File = 'vbmeta.img'; Lun = '4' }
}

$allPrograms = foreach ($xmlFile in $sourceXmlFiles) {
    $doc = [xml](Get-Content -LiteralPath $xmlFile.FullName -Raw)
    foreach ($program in $doc.data.program) {
        $program
    }
}

$selected = foreach ($label in $expected.Keys) {
    $matches = @($allPrograms | Where-Object { $_.label -eq $label })
    if ($matches.Count -ne 1) {
        throw "Expected exactly one rawprogram entry for $label, found $($matches.Count)."
    }

    $program = $matches[0]
    $rule = $expected[$label]
    if ($program.filename -ne $rule.File -or
        $program.physical_partition_number -ne $rule.Lun -or
        $program.SECTOR_SIZE_IN_BYTES -ne '4096' -or
        $program.file_sector_offset -ne '0' -or
        $program.sparse -ne 'false' -or
        $program.start_sector -notmatch '^\d+$' -or
        $program.num_partition_sectors -notmatch '^\d+$') {
        throw "Unsafe or unexpected rawprogram fields for $label."
    }

    $imageLength = (Get-Item -LiteralPath (Join-Path $source $rule.File)).Length
    $capacity = [int64]$program.num_partition_sectors * [int64]$program.SECTOR_SIZE_IN_BYTES
    if ($imageLength -ne $capacity) {
        throw "$($rule.File) length $imageLength does not exactly match $label capacity $capacity."
    }
    $program
}

$manifestHashes = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $source $checksumName)) {
    if ($line -match '^([0-9a-fA-F]{64})\s+(.+)$') {
        $manifestHashes[$Matches[2].Trim()] = $Matches[1].ToLowerInvariant()
    }
}
foreach ($name in $imageNames) {
    if (-not $manifestHashes.ContainsKey($name)) {
        throw "$checksumName has no hash for $name."
    }
    $actual = (Get-FileHash -LiteralPath (Join-Path $source $name) -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $manifestHashes[$name]) {
        throw "SHA-256 mismatch for $name."
    }
}

New-Item -ItemType Directory -Path $output -Force | Out-Null
$unexpected = @(Get-ChildItem -LiteralPath $output -Force | Where-Object {
    $_.Name -notin $generatedNames
})
if ($unexpected.Count -gt 0) {
    throw "Refusing to use a non-dedicated output directory; unexpected item: $($unexpected[0].FullName)"
}
foreach ($name in $generatedNames) {
    $target = Join-Path $output $name
    if (Test-Path -LiteralPath $target) {
        Remove-Item -LiteralPath $target -Force
    }
}

Copy-Item -LiteralPath (Join-Path $platform $loaderName) -Destination (Join-Path $output $loaderName)
Copy-Item -LiteralPath (Join-Path $source $checksumName) -Destination (Join-Path $output $checksumName)
foreach ($name in $imageNames) {
    New-Item -ItemType HardLink -Path (Join-Path $output $name) -Target (Join-Path $source $name) | Out-Null
}

$outDoc = New-Object System.Xml.XmlDocument
$declaration = $outDoc.CreateXmlDeclaration('1.0', 'utf-8', $null)
[void]$outDoc.AppendChild($declaration)
$root = $outDoc.CreateElement('data')
[void]$outDoc.AppendChild($root)
[void]$root.AppendChild($outDoc.CreateComment(' ZuiControl safe 9008 package: exactly 7 writes; no GPT, patch, erase, userdata or device-unique partitions. '))
foreach ($program in $selected) {
    [void]$root.AppendChild($outDoc.ImportNode($program, $true))
}
$xmlSettings = New-Object System.Xml.XmlWriterSettings
$xmlSettings.Encoding = New-Object System.Text.UTF8Encoding($false)
$xmlSettings.Indent = $true
$xmlPath = Join-Path $output 'rawprogram_zuicontrol.xml'
$writer = [System.Xml.XmlWriter]::Create($xmlPath, $xmlSettings)
try {
    $outDoc.Save($writer)
} finally {
    $writer.Dispose()
}

$readme = @"
ZuiControl SAFE 9008 package

Source: $source
Platform metadata/loader: $platform
Generated: $([DateTime]::Now.ToString('yyyy-MM-dd HH:mm:ss zzz'))

This directory intentionally contains one rawprogram XML and no patch XML.
It writes exactly these seven targets:
  LUN0 super
  LUN0 vbmeta_system_a
  LUN0 vbmeta_system_b
  LUN4 boot_a
  LUN4 boot_b
  LUN4 vbmeta_a
  LUN4 vbmeta_b

It does not write GPT, persist, FRP, modemst, userdata or any other partition.
The four image files are NTFS hard links to the verified source package.
Run scripts/flash/FlashZuiControl9008.ps1; do not add full rawprogram or patch XML here.
"@
[IO.File]::WriteAllText((Join-Path $output 'README_SAFE_9008.txt'), $readme, (New-Object Text.UTF8Encoding($false)))

$finalDoc = [xml](Get-Content -LiteralPath $xmlPath -Raw)
$finalPrograms = @($finalDoc.data.program)
if ($finalPrograms.Count -ne 7) {
    throw "Generated XML has $($finalPrograms.Count) writes instead of 7."
}
$finalLabels = @($finalPrograms | ForEach-Object { $_.label } | Sort-Object)
$expectedLabels = @($expected.Keys | Sort-Object)
if (Compare-Object $expectedLabels $finalLabels) {
    throw "Generated XML labels do not match the fixed allowlist."
}

Write-Host "SAFE 9008 preflight passed: $output"
$finalPrograms | ForEach-Object {
    Write-Host ("  LUN{0} {1} <- {2}" -f $_.physical_partition_number, $_.label, $_.filename)
}
