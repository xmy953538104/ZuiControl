[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Name,
    [string]$Serial,
    [string]$AdbPath = 'D:\3.VScode\Mi\Edit tools\adb-fastboot\adb.exe'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($Name -notmatch '^[A-Za-z0-9_.-]+$') { throw "Invalid Android property name: $Name" }
$invokeRoot = Join-Path $PSScriptRoot 'Invoke-ZuiRoot.ps1'
(@(& $invokeRoot -AdbPath $AdbPath -Serial $Serial -Executable '/system/bin/getprop' -ArgumentList @($Name)) -join "`n").Trim()
