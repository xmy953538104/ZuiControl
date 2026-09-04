Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-ZuiCanonicalAdbPath {
    $moduleCandidates = @(
        (Join-Path $PSScriptRoot '..\..\Mi.Common.psm1'),
        (Join-Path $PSScriptRoot '..\..\..\script\Mi.Common.psm1')
    )
    if (-not [string]::IsNullOrWhiteSpace($env:ADB)) {
        return [IO.Path]::GetFullPath($env:ADB)
    }
    foreach ($candidate in $moduleCandidates) {
        $module = [IO.Path]::GetFullPath($candidate)
        if (Test-Path -LiteralPath $module -PathType Leaf) {
            Import-Module $module -Force
            return [string](Get-MiPaths).Adb
        }
    }
    throw 'Cannot locate Mi.Common.psm1; run Setup-MiEnvironment.ps1 or pass -AdbPath explicitly.'
}

function Resolve-ZuiAdbPath {
    param([string]$AdbPath)
    if ([string]::IsNullOrWhiteSpace($AdbPath)) { $AdbPath = Get-ZuiCanonicalAdbPath }
    if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
        throw "adb not found: $AdbPath"
    }
    return (Resolve-Path -LiteralPath $AdbPath).Path
}

function Invoke-ZuiAdbCommand {
    param(
        [string]$AdbPath,
        [string[]]$ArgumentList,
        [switch]$AllowFailure
    )
    $resolvedAdb = Resolve-ZuiAdbPath $AdbPath
    $output = @(& $resolvedAdb @ArgumentList 2>&1)
    $exitCode = $LASTEXITCODE
    $result = [pscustomobject][ordered]@{
        ExitCode = $exitCode
        Output = $output
        Arguments = $ArgumentList
    }
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "adb failed with exit code ${exitCode}: $($output -join [Environment]::NewLine)"
    }
    return $result
}

function Resolve-ZuiDeviceSerial {
    param(
        [string]$AdbPath,
        [string]$Serial
    )
    $result = Invoke-ZuiAdbCommand -AdbPath $AdbPath -ArgumentList @('devices')
    $online = @($result.Output | ForEach-Object {
        if ([string]$_ -match '^([^\s]+)\s+device(?:\s|$)') { $Matches[1] }
    })
    if ($Serial) {
        if (@($online | Where-Object { $_ -eq $Serial }).Count -ne 1) {
            throw "Requested adb device is not uniquely online: $Serial"
        }
        return $Serial
    }
    if ($online.Count -ne 1) {
        throw "Expected exactly one online adb device, found $($online.Count)"
    }
    return $online[0]
}

function ConvertTo-ZuiShellToken {
    param([AllowEmptyString()][string]$Value)
    $singleQuoteEscape = "'" + '"' + "'" + '"' + "'"
    return "'" + $Value.Replace("'", $singleQuoteEscape) + "'"
}

function New-ZuiRootCommand {
    param(
        [string]$Executable,
        [string[]]$ArgumentList = @()
    )
    if ([string]::IsNullOrWhiteSpace($Executable)) { throw 'Executable is required.' }
    return (@($Executable) + @($ArgumentList) | ForEach-Object {
        ConvertTo-ZuiShellToken ([string]$_)
    }) -join ' '
}

Export-ModuleMember -Function Get-ZuiCanonicalAdbPath, Resolve-ZuiAdbPath, Invoke-ZuiAdbCommand, Resolve-ZuiDeviceSerial, ConvertTo-ZuiShellToken, New-ZuiRootCommand
