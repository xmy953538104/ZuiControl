[CmdletBinding()]
param(
    [string]$Serial,
    [string]$AdbPath = 'D:\3.VScode\Mi\Edit tools\adb-fastboot\adb.exe',
    [switch]$AsJson
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$runner = Join-Path $PSScriptRoot 'Run-ZuiDeviceScript.ps1'
$collector = Join-Path $PSScriptRoot 'get_zui_process_state.sh'
$values = [ordered]@{}
foreach ($line in @(& $runner -LocalScript $collector -Serial $Serial -AdbPath $AdbPath)) {
    if ([string]$line -match '^([^=]+)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
}
$state = [pscustomobject]$values
if ($AsJson) { $state | ConvertTo-Json -Depth 3 } else { $state }
