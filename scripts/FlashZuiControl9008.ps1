param(
    [ValidateSet('Preflight', 'EnterEdl', 'Flash')]
    [string]$Mode = 'Preflight',
    [string]$SourceDir = '',
    [string]$PlatformDir = '',
    [string]$SafePackageDir = "D:\3.VScode\Mi\flash\ZuiControl_9008_SAFE_072",
    [string]$QdlPath = "D:\3.VScode\Mi\flash\Binaries\Qcom\qdl-rs.exe",
    [string]$AdbPath = "D:\3.VScode\Mi\work\android-sdk\platform-tools\adb.exe",
    [string]$ConfirmAdbSerial = 'HA25HSZM',
    [string]$ExpectedBuildRegex = 'TB321FU.*16\.1\.11\.072',
    [int]$EdlTimeoutSeconds = 60,
    [int]$BootTimeoutSeconds = 240
)

$ErrorActionPreference = "Stop"
$prepare = Join-Path $PSScriptRoot 'PrepareZuiControl9008Package.ps1'
& $prepare -SourceDir $SourceDir -PlatformDir $PlatformDir -OutputDir $SafePackageDir
$verify = Join-Path $PSScriptRoot 'VerifyZuiControlFlashPackage.ps1'
if ([string]::IsNullOrWhiteSpace($SourceDir)) {
    & $verify
} else {
    & $verify -FlashDir $SourceDir
}
if ($Mode -eq 'Preflight') {
    Write-Host 'Preflight only: the device was not rebooted and nothing was flashed.'
    return
}

function Invoke-Adb([string[]]$Arguments, [switch]$AllowDisconnect) {
    $output = & $AdbPath -s $ConfirmAdbSerial @Arguments 2>&1
    $exit = $LASTEXITCODE
    if ($exit -ne 0 -and -not $AllowDisconnect) {
        throw "adb failed ($exit): $($output -join [Environment]::NewLine)"
    }
    return @($output)
}

function Find-EdlPorts {
    $devices = @(Get-CimInstance Win32_PnPEntity | Where-Object {
        $_.PNPDeviceID -match 'VID_05C6&PID_9008' -or
        $_.Name -match 'Qualcomm.*9008.*\(COM\d+\)'
    })
    @($devices | ForEach-Object {
        if ($_.Name -match '(COM\d+)') { $Matches[1] }
    } | Sort-Object -Unique)
}

function Wait-EdlPort([int]$TimeoutSeconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        Start-Sleep -Milliseconds 500
        $found = @(Find-EdlPorts)
    } until ($found.Count -gt 0 -or [DateTime]::UtcNow -ge $deadline)
    if ($found.Count -ne 1) {
        throw "Expected exactly one Qualcomm 9008 COM port; found: $($found -join ', ')"
    }
    return $found[0]
}

if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw "adb not found: $AdbPath"
}
if ($Mode -eq 'Flash') {
    if (-not (Test-Path -LiteralPath $QdlPath -PathType Leaf)) {
        throw "Qualcomm qdl-rs binary not found: $QdlPath"
    }
    $qdlVersion = & $QdlPath --version 2>&1
    if ($LASTEXITCODE -ne 0 -or ($qdlVersion -join ' ') -notmatch '^qdl-rs\s') {
        throw "Unexpected qdl-rs binary: $($qdlVersion -join ' ')"
    }
}
$adbDevices = @(& $AdbPath devices | Select-String -Pattern '^\S+\s+device$' | ForEach-Object {
    ($_ -split '\s+')[0]
})
if ($adbDevices.Count -ne 1 -or $adbDevices[0] -ne $ConfirmAdbSerial) {
    throw "Expected exactly adb device $ConfirmAdbSerial; found: $($adbDevices -join ', ')"
}
$model = (Invoke-Adb @('shell', 'getprop', 'ro.product.model') | Select-Object -Last 1).Trim()
$build = (Invoke-Adb @('shell', 'getprop', 'ro.build.display.id') | Select-Object -Last 1).Trim()
if ($model -ne 'TB321FU' -or $build -notmatch $ExpectedBuildRegex) {
    throw "Device identity mismatch: model=$model build=$build"
}
$rootProbe = (Invoke-Adb @('shell', 'su', '-c', 'id') | Out-String)
if ($rootProbe -notmatch 'uid=0') {
    throw 'Root probe failed; refusing to enter EDL.'
}

Write-Host "Verified adb device: $ConfirmAdbSerial / $model / $build"
[void](Invoke-Adb @('shell', 'su', '-c', 'sync; reboot edl') -AllowDisconnect)

$port = Wait-EdlPort $EdlTimeoutSeconds
Write-Host "Qualcomm 9008 detected on $port"
if ($Mode -eq 'EnterEdl') {
    Write-Host 'Device is in 9008 mode. No flash command was sent.'
    return
}

$loader = Join-Path $SafePackageDir 'prog_firehose_ddr-TB321FC.elf'
$xml = Join-Path $SafePackageDir 'rawprogram_zuicontrol.xml'
$logDir = 'D:\3.VScode\Mi\flash\Log'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null
$timestamp = [DateTime]::Now.ToString('yyyy-MM-dd_HH-mm-ss')
$log = Join-Path $logDir ("ZuiControl_qdlrs_{0}.log" -f $timestamp)
$arguments = @(
    '--backend', 'serial',
    '--dev-path', $port,
    '--loader-path', $loader,
    '--storage-type', 'ufs',
    '--reset-mode', 'edl',
    '--read-back-verify',
    '--print-firehose-log',
    'flasher', '-p', $xml
)
Write-Host "Flashing the fixed 7-entry allowlist with Qualcomm qdl-rs ($port)..."
& $QdlPath @arguments 2>&1 | Tee-Object -FilePath $log
if ($LASTEXITCODE -ne 0) {
    throw "qdl-rs failed with exit code $LASTEXITCODE. Log: $log"
}
if ((Get-Content -LiteralPath $log -Raw) -notmatch 'All went well!') {
    throw "qdl-rs did not report its success marker. Log: $log"
}

# qdl-rs 0.1.0 only forwards --read-back-verify as a Firehose <program>
# attribute.  It does not return bytes to the host for comparison.  Treat that
# flag as supplementary and prove every fixed-seven write by an explicit
# physical partition dump, byte length check, and host SHA-256 comparison.
[xml]$rawprogram = Get-Content -Raw -LiteralPath $xml
$programs = @($rawprogram.data.program)
$expectedLabels = @('super', 'vbmeta_system_a', 'vbmeta_system_b', 'boot_a', 'boot_b', 'vbmeta_a', 'vbmeta_b')
$actualLabels = @($programs | ForEach-Object { [string]$_.label })
if ($programs.Count -ne 7 -or ($actualLabels -join "`n") -ne ($expectedLabels -join "`n")) {
    throw "Physical read-back requires the exact fixed-seven labels: $($actualLabels -join ', ')"
}
$readbackDir = Join-Path $logDir ("ZuiControl_readback_{0}" -f $timestamp)
if (Test-Path -LiteralPath $readbackDir) { throw "Fresh read-back directory required: $readbackDir" }
New-Item -ItemType Directory -Path $readbackDir | Out-Null
$largestImage = @($programs | ForEach-Object {
    (Get-Item -LiteralPath (Join-Path $SafePackageDir ([string]$_.filename))).Length
} | Measure-Object -Maximum).Maximum
$readbackDrive = Get-PSDrive -Name ([IO.Path]::GetPathRoot($readbackDir).Substring(0, 1))
if ($readbackDrive.Free -lt ($largestImage + 2GB)) {
    throw "Insufficient temporary space for physical read-back: need at least $($largestImage + 2GB) bytes."
}

$readbackResults = @()
foreach ($program in $programs) {
    $label = [string]$program.label
    $imagePath = Join-Path $SafePackageDir ([string]$program.filename)
    $expectedLength = [int64]$program.num_partition_sectors * [int64]$program.SECTOR_SIZE_IN_BYTES
    if ((Get-Item -LiteralPath $imagePath).Length -ne $expectedLength) {
        throw "Image does not exactly fill programmed extent for ${label}: $imagePath"
    }
    $port = Wait-EdlPort $EdlTimeoutSeconds
    $readLog = Join-Path $readbackDir ("${label}.qdl.log")
    $dumpArguments = @(
        '--backend', 'serial',
        '--dev-path', $port,
        '--loader-path', $loader,
        '--storage-type', 'ufs',
        '--phys-part-idx', [string]$program.physical_partition_number,
        '--reset-mode', 'edl',
        '--print-firehose-log',
        'dump-part', '-o', $readbackDir, $label
    )
    Write-Host "Reading back physical partition $label from UFS LUN $($program.physical_partition_number)..."
    & $QdlPath @dumpArguments 2>&1 | Tee-Object -FilePath $readLog
    if ($LASTEXITCODE -ne 0 -or (Get-Content -Raw -LiteralPath $readLog) -notmatch 'All went well!') {
        throw "Physical read-back failed for $label. Device remains in EDL. Log: $readLog"
    }
    $dumpPath = Join-Path $readbackDir $label
    if (-not (Test-Path -LiteralPath $dumpPath -PathType Leaf)) {
        throw "qdl-rs did not create the expected physical dump: $dumpPath"
    }
    $actualLength = (Get-Item -LiteralPath $dumpPath).Length
    $expectedHash = (Get-FileHash -LiteralPath $imagePath -Algorithm SHA256).Hash.ToLowerInvariant()
    $actualHash = (Get-FileHash -LiteralPath $dumpPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualLength -ne $expectedLength -or $actualHash -ne $expectedHash) {
        throw "Physical read-back mismatch for $label. Device remains in EDL. expected_length=$expectedLength actual_length=$actualLength expected_sha256=$expectedHash actual_sha256=$actualHash"
    }
    $readbackResults += [pscustomobject][ordered]@{
        label = $label
        physical_partition_number = [int]$program.physical_partition_number
        bytes = $actualLength
        source_image = [string]$program.filename
        source_sha256 = $expectedHash
        physical_readback_sha256 = $actualHash
        result = 'PASS'
    }
    Remove-Item -LiteralPath $dumpPath -Force
}
$readbackManifest = Join-Path $readbackDir 'physical_readback_manifest.json'
[pscustomobject][ordered]@{
    qdl_version = ($qdlVersion -join ' ').Trim()
    qdl_flag_only_accepted_as_proof = $false
    method = 'qdl-rs dump-part to host; exact byte length and SHA-256 comparison; temporary dumps deleted after verification'
    fixed_seven = $readbackResults
    result = 'PHYSICAL_PARTITION_READBACK=PASS'
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $readbackManifest -Encoding UTF8
Write-Host "PHYSICAL_PARTITION_READBACK=PASS Manifest: $readbackManifest"

$port = Wait-EdlPort $EdlTimeoutSeconds
$resetArguments = @(
    '--backend', 'serial',
    '--dev-path', $port,
    '--loader-path', $loader,
    '--storage-type', 'ufs',
    '--reset-mode', 'system',
    'nop'
)
& $QdlPath @resetArguments 2>&1 | Tee-Object -FilePath (Join-Path $readbackDir 'reset_system.qdl.log')
if ($LASTEXITCODE -ne 0) {
    throw "Physical read-back passed, but qdl-rs could not reset the device to system. Device may remain in EDL."
}

$deadline = [DateTime]::UtcNow.AddSeconds($BootTimeoutSeconds)
do {
    Start-Sleep -Seconds 2
    $booted = $null
    try {
        $online = @(& $AdbPath devices 2>$null | Select-String -Pattern "^$([regex]::Escape($ConfirmAdbSerial))\s+device$")
        if ($online.Count -eq 1) {
            $booted = (& $AdbPath -s $ConfirmAdbSerial shell getprop sys.boot_completed 2>$null |
                Select-Object -Last 1)
        }
    } catch {
        $booted = $null
    }
} until ($booted -eq '1' -or [DateTime]::UtcNow -ge $deadline)
if ($booted -ne '1') {
    throw "Flash succeeded but Android did not finish booting within $BootTimeoutSeconds seconds. Log: $log"
}
$package = & $AdbPath -s $ConfirmAdbSerial shell dumpsys package com.zui.zuicontrol 2>&1
if (($package -join "`n") -notmatch 'versionCode=49\b' -or
    ($package -join "`n") -notmatch 'versionName=0\.21\.12\b') {
    throw "Device rebooted, but ZuiControl is not 49/0.21.12. Log: $log"
}
Write-Host "9008 flash and reboot passed; device reports ZuiControl 49/0.21.12. Log: $log"
