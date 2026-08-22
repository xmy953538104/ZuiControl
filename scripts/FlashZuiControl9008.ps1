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

$deadline = [DateTime]::UtcNow.AddSeconds($EdlTimeoutSeconds)
do {
    Start-Sleep -Milliseconds 500
    $ports = @(Find-EdlPorts)
} until ($ports.Count -gt 0 -or [DateTime]::UtcNow -ge $deadline)
if ($ports.Count -ne 1) {
    throw "Expected exactly one Qualcomm 9008 COM port; found: $($ports -join ', ')"
}
$port = $ports[0]
Write-Host "Qualcomm 9008 detected on $port"
if ($Mode -eq 'EnterEdl') {
    Write-Host 'Device is in 9008 mode. No flash command was sent.'
    return
}

$loader = Join-Path $SafePackageDir 'prog_firehose_ddr-TB321FC.elf'
$xml = Join-Path $SafePackageDir 'rawprogram_zuicontrol.xml'
$logDir = 'D:\3.VScode\Mi\flash\Log'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null
$log = Join-Path $logDir ("ZuiControl_qdlrs_{0}.log" -f [DateTime]::Now.ToString('yyyy-MM-dd_HH-mm-ss'))
$arguments = @(
    '--backend', 'serial',
    '--dev-path', $port,
    '--loader-path', $loader,
    '--storage-type', 'ufs',
    '--reset-mode', 'system',
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
if (($package -join "`n") -notmatch 'versionCode=42\b' -or
    ($package -join "`n") -notmatch 'versionName=0\.21\.5\b') {
    throw "Device rebooted, but ZuiControl is not 42/0.21.5. Log: $log"
}
Write-Host "9008 flash and reboot passed; device reports ZuiControl 42/0.21.5. Log: $log"
