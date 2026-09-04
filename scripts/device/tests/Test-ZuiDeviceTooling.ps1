[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$deviceRoot = Split-Path -Parent $PSScriptRoot
Import-Module (Join-Path $deviceRoot 'ZuiDevice.Common.psm1') -Force
Import-Module (Join-Path $deviceRoot 'ZuiSceneHarness.psm1') -Force

function Assert-Equal([object]$Expected, [object]$Actual, [string]$Name) {
    if ([string]$Expected -cne [string]$Actual) { throw "$Name expected=<$Expected> actual=<$Actual>" }
}

$dangerousLiteral = '$() ; * '' " space'
$quoted = ConvertTo-ZuiShellToken $dangerousLiteral
if (-not ($quoted.StartsWith("'") -and $quoted.EndsWith("'") -and $quoted.Contains("'`"'`"'"))) {
    throw "POSIX token quoting failed: $quoted"
}
$rootCommand = New-ZuiRootCommand -Executable '/system/bin/getprop' -ArgumentList @('sys.zui_control.uperf_mode', $dangerousLiteral)
if ($rootCommand -notmatch "^'/system/bin/getprop' ") { throw "Root command is not tokenized: $rootCommand" }

$fixture = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'fixtures\multi_window_snapshot.txt') -Raw
$scene = ConvertFrom-ZuiSceneSnapshot -Text $fixture
Assert-Equal 'com.kurogame.mingchao' $scene.top_resumed_package 'display-global authority'
Assert-Equal 'performance' $scene.protected_uperf_mode 'protected property'
Assert-Equal 'com.kurogame.mingchao' $scene.uperfScenePackage 'ZuiControl scene'

$powershellFiles = Get-ChildItem -LiteralPath $deviceRoot -File -Filter '*.ps1'
foreach ($file in $powershellFiles) {
    $text = Get-Content -LiteralPath $file.FullName -Raw
    if ($text -match '(?im)^\s*\$pid\s*=') { throw "Reserved PID variable assignment remains: $($file.Name)" }
    if ($text -match '(?i)\bshell\s+getprop\s+sys\.zui_control') { throw "Plain-shell protected-property read remains: $($file.Name)" }
}

'ROOT_TOKEN_ESCAPING=PASS'
'RESERVED_PID_ASSIGNMENT=ABSENT'
'PROTECTED_PROPERTY_ROOT_PATH=PASS'
'DISPLAY_GLOBAL_RESUMED_ACTIVITY_FIXTURE=PASS'
