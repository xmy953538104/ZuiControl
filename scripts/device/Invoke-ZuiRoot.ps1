[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Executable,
    [string[]]$ArgumentList = @(),
    [string]$Serial,
    [string]$AdbPath = 'D:\3.VScode\Mi\Edit tools\adb-fastboot\adb.exe',
    [switch]$AllowFailure
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ZuiDevice.Common.psm1') -Force

$resolvedSerial = Resolve-ZuiDeviceSerial -AdbPath $AdbPath -Serial $Serial
$command = New-ZuiRootCommand -Executable $Executable -ArgumentList $ArgumentList
$result = Invoke-ZuiAdbCommand -AdbPath $AdbPath -ArgumentList @(
    '-s', $resolvedSerial, 'shell', 'su', '-c', $command
) -AllowFailure:$AllowFailure
$result.Output
$global:LASTEXITCODE = $result.ExitCode
