[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$LocalScript,
    [string[]]$DeviceArgumentList = @(),
    [string]$Serial,
    [string]$AdbPath = 'D:\3.VScode\Mi\Edit tools\adb-fastboot\adb.exe',
    [switch]$KeepRemote,
    [switch]$AllowFailure
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$pushScript = Join-Path $PSScriptRoot 'Push-ZuiScript.ps1'
$invokeRoot = Join-Path $PSScriptRoot 'Invoke-ZuiRoot.ps1'
$remoteName = [IO.Path]::GetFileNameWithoutExtension($LocalScript) + '-' + [guid]::NewGuid().ToString('N') + '.sh'
$remote = & $pushScript -LocalScript $LocalScript -RemoteName $remoteName -Serial $Serial -AdbPath $AdbPath
try {
    & $invokeRoot -AdbPath $AdbPath -Serial $Serial -Executable $remote -ArgumentList $DeviceArgumentList -AllowFailure:$AllowFailure
} finally {
    if (-not $KeepRemote) {
        $null = @(& $invokeRoot -AdbPath $AdbPath -Serial $Serial -Executable '/system/bin/rm' -ArgumentList @('-f', '--', $remote) -AllowFailure)
    }
}
