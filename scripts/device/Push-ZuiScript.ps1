[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$LocalScript,
    [string]$RemoteName,
    [string]$RemoteDirectory = '/data/local/tmp',
    [string]$Serial,
    [string]$AdbPath = 'D:\3.VScode\Mi\Edit tools\adb-fastboot\adb.exe'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ZuiDevice.Common.psm1') -Force

$local = (Resolve-Path -LiteralPath $LocalScript).Path
if ([IO.Path]::GetExtension($local) -ne '.sh') { throw "Device script must end in .sh: $local" }
if (-not $RemoteName) { $RemoteName = [IO.Path]::GetFileName($local) }
if ($RemoteName -notmatch '^[A-Za-z0-9._-]+\.sh$') { throw "Unsafe remote script name: $RemoteName" }
if ($RemoteDirectory -notmatch '^/data/local/tmp(?:/[A-Za-z0-9._-]+)*$') {
    throw "Remote directory must stay below /data/local/tmp: $RemoteDirectory"
}

$resolvedSerial = Resolve-ZuiDeviceSerial -AdbPath $AdbPath -Serial $Serial
$remote = $RemoteDirectory.TrimEnd('/') + '/' + $RemoteName
$push = Invoke-ZuiAdbCommand -AdbPath $AdbPath -ArgumentList @('-s', $resolvedSerial, 'push', $local, $remote)
$null = $push
$invokeRoot = Join-Path $PSScriptRoot 'Invoke-ZuiRoot.ps1'
$null = @(& $invokeRoot -AdbPath $AdbPath -Serial $resolvedSerial -Executable '/system/bin/chmod' -ArgumentList @('0700', $remote))
$remote
