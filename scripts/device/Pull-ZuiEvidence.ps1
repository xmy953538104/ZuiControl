[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$RemotePath,
    [Parameter(Mandatory)]
    [string]$Destination,
    [string]$Serial,
    [string]$AdbPath = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ZuiDevice.Common.psm1') -Force

if ($RemotePath -notmatch '^/data/local/tmp/[A-Za-z0-9._/-]+$' -or $RemotePath -match '(^|/)\.\.(/|$)') {
    throw "Evidence path must be an exact path below /data/local/tmp: $RemotePath"
}
$destinationPath = [IO.Path]::GetFullPath($Destination)
$destinationParent = Split-Path -Parent $destinationPath
if (-not (Test-Path -LiteralPath $destinationParent -PathType Container)) {
    $null = New-Item -ItemType Directory -Path $destinationParent -Force
}
$resolvedSerial = Resolve-ZuiDeviceSerial -AdbPath $AdbPath -Serial $Serial
$result = Invoke-ZuiAdbCommand -AdbPath $AdbPath -ArgumentList @('-s', $resolvedSerial, 'pull', $RemotePath, $destinationPath)
$result.Output
