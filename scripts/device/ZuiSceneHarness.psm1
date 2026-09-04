Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-ZuiTaggedSection {
    param([string]$Text, [string]$Name)
    $pattern = '(?ms)^__ZUI_' + [regex]::Escape($Name) + '_BEGIN__\r?\n(.*?)^__ZUI_' + [regex]::Escape($Name) + '_END__\s*$'
    $match = [regex]::Match($Text, $pattern)
    if (-not $match.Success) { throw "Snapshot section is missing: $Name" }
    return $match.Groups[1].Value
}

function Get-ZuiSnapshotValue {
    param([string]$Text, [string]$Name)
    $match = [regex]::Match($Text, '(?m)^' + [regex]::Escape($Name) + '=(.*)$')
    if ($match.Success) { return $match.Groups[1].Value.Trim() }
    return ''
}

function ConvertFrom-ZuiSceneSnapshot {
    param([Parameter(Mandatory)][string]$Text)
    $meta = Get-ZuiTaggedSection $Text 'META'
    $activity = Get-ZuiTaggedSection $Text 'ACTIVITY'
    $zui = Get-ZuiTaggedSection $Text 'CONTROL'
    $properties = Get-ZuiTaggedSection $Text 'PROPERTIES'
    $globalRows = @($activity -split "`r?`n" | Where-Object { $_ -match '^\s*ResumedActivity:' })
    if ($globalRows.Count -ne 1) {
        throw "Expected exactly one display-global ResumedActivity row, found $($globalRows.Count)"
    }
    $resumed = $globalRows[0].Trim()
    if ($resumed -notmatch '\su\d+\s+([^/\s}]+)') { throw "Cannot parse package from: $resumed" }
    $packageName = $Matches[1]
    $protectedMode = Get-ZuiSnapshotValue $properties 'sys.zui_control.uperf_mode'
    if ($protectedMode -notmatch '^(powersave|balance|performance|fast)$') {
        throw "Protected Uperf property is missing or invalid: $protectedMode"
    }
    return [pscustomobject][ordered]@{
        device_epoch = Get-ZuiSnapshotValue $meta 'device_epoch'
        resumed_activity = $resumed
        top_resumed_package = $packageName
        uperfTopResumedRawPackage = Get-ZuiSnapshotValue $zui 'uperfTopResumedRawPackage'
        uperfTopResumedStablePackage = Get-ZuiSnapshotValue $zui 'uperfTopResumedStablePackage'
        uperfTopResumedPendingNull = Get-ZuiSnapshotValue $zui 'uperfTopResumedPendingNull'
        uperfScenePackage = Get-ZuiSnapshotValue $zui 'uperfScenePackage'
        uperfDesiredMode = Get-ZuiSnapshotValue $zui 'uperfDesiredMode'
        uperfLastAppliedMode = Get-ZuiSnapshotValue $zui 'uperfLastAppliedMode'
        uperfApplyCount = Get-ZuiSnapshotValue $zui 'uperfApplyCount'
        uperfLastReason = Get-ZuiSnapshotValue $zui 'uperfLastReason'
        protected_uperf_mode = $protectedMode
        effective_powermode = Get-ZuiSnapshotValue $properties 'effective_powermode'
        cur_powermode = Get-ZuiSnapshotValue $properties 'cur_powermode'
        uperf_service = Get-ZuiSnapshotValue $properties 'uperf_service'
        uperf_fail_safe = Get-ZuiSnapshotValue $properties 'uperf_fail_safe'
        system_server_process_id = Get-ZuiSnapshotValue $properties 'system_server_process_id'
    }
}

Export-ModuleMember -Function ConvertFrom-ZuiSceneSnapshot
