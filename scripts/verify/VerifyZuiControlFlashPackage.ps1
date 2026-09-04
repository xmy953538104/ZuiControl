param(
    [string]$FlashDir = "",
    [string]$WorkDir = "",
    [string]$ExpectedVendorImageSha256 = "",
    [string]$ExpectedVendorApkInventoryPath = "",
    [switch]$KeepWork
)

$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$WorkspaceRoot = Split-Path -Parent $RepoRoot
if ((Split-Path -Leaf $WorkspaceRoot) -ieq 'worktrees') { $WorkspaceRoot = Split-Path -Parent $WorkspaceRoot }
if (-not $FlashDir) { $FlashDir = Join-Path $WorkspaceRoot 'zui072（flash）\out\V20.4_Golden_20260903144915' }
if (-not $WorkDir) { $WorkDir = Join-Path $WorkspaceRoot 'zui072（flash）\work\temp\verify_flash_zui_control' }

$ToolsDir = Join-Path $WorkspaceRoot 'Edit tools'
$AndroidSdkDir = Join-Path $ToolsDir 'android-build\android-sdk'
$Python = Join-Path $ToolsDir 'Python\python-3.8.0\python.exe'
$LpUnpack = Join-Path $ToolsDir 'super-tools\lpunpack.py'
$ExtractErofs = Join-Path $ToolsDir 'super-tools\AMD64\extract.erofs.exe'
$Apktool = Join-Path $ToolsDir 'smali-apk\apktool.jar'
$Avbtool = Join-Path $ToolsDir 'avb\downloaded\avbtool_aosp_c0af371_1.2.0.py'
$ReleaseCertSha256 = '3fecf3a72ca0e0f24991d49e7306ef4a711711f48a66070755eb0237ecb3ed94'
$ExpectedVersionCode = '49'
$ExpectedVersionName = '0.21.12'
$ExpectedUperfSha256 = 'f1265757009ff0c85dd8587d9e7bfcf5e51d10d36fe5e1341688215ae1fb49d8'
$ExpectedAsoulSha256 = '7a2ee5d67ba7c057066176334eca9256e376427916429d66b7593cbb5538ec86'
$ExpectedBootSha256 = 'e7e85b5cd2806b8c27adf4925e05ee169072a79a43502effc34c97fb27ee8371'
$ExpectedBuildFingerprintMarker = 'ZUI_16.1.11.072_241118_PRC'
$ForbiddenBuildFingerprintMarker = 'ZUI_16.1.11.187_250227_PRC'
$MinimumAvbRollbackIndex = [int64]1736035200
$OfficialB072Dir = Join-Path $WorkspaceRoot 'zui072（9008）'

function Full([string]$Path) { [IO.Path]::GetFullPath($Path).TrimEnd('\') }

function Assert-VerificationWorkBoundary {
    $workRoot = Full (Join-Path $WorkspaceRoot 'zui072（flash）\work\temp')
    $workFull = Full $WorkDir
    $flashFull = Full $FlashDir
    if ($workFull -eq $workRoot -or
        -not $workFull.StartsWith($workRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path -Leaf $workFull) -notlike 'verify_*') {
        throw "Verification WorkDir escaped the dedicated workspace work/verify_* boundary: $workFull"
    }
    if ($workFull -eq $flashFull -or
        $workFull.StartsWith($flashFull + '\', [StringComparison]::OrdinalIgnoreCase) -or
        $flashFull.StartsWith($workFull + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Verification WorkDir overlaps FlashDir: $workFull ; $flashFull"
    }
}

function Remove-VerificationWork {
    if (-not (Test-Path -LiteralPath $WorkDir)) { return }
    Assert-VerificationWorkBoundary
    $expected = Full $WorkDir
    $item = Get-Item -LiteralPath $WorkDir -Force
    $resolved = Full (Resolve-Path -LiteralPath $WorkDir).Path
    if ($item.LinkType -or $resolved -ne $expected) {
        throw "Verification WorkDir is linked or resolves unexpectedly: $expected -> $resolved"
    }
    $links = @(Get-ChildItem -LiteralPath $resolved -Force -Recurse -Attributes ReparsePoint -ErrorAction SilentlyContinue)
    if ($links.Count) { throw "Verification WorkDir contains a reparse point: $($links[0].FullName)" }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

function Require-File([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing file: $Path" }
}

function Snapshot-Apks([string]$Root) {
    $rootFull = Full $Root
    $prefix = $rootFull + '\'
    return @(Get-ChildItem -LiteralPath $rootFull -Recurse -File -Filter '*.apk' |
        Sort-Object FullName | ForEach-Object {
            $full = Full $_.FullName
            if (-not $full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
                throw "APK escaped inventory root: $full"
            }
            [pscustomobject][ordered]@{
                path = $full.Substring($prefix.Length).Replace('\', '/')
                length = $_.Length
                sha256 = File-Sha256 $full
            }
        })
}

function Canonical-ApkInventory([object[]]$Apks) {
    return @($Apks | Sort-Object { [string]$_.path } | ForEach-Object {
        '{0}|{1}|{2}' -f [string]$_.path, [int64]$_.length, ([string]$_.sha256).ToLowerInvariant()
    })
}

function Assert-Missing([string]$Path, [string]$Label) {
    if (Test-Path -LiteralPath $Path) { throw "Unexpected $Label remains: $Path" }
}

function Invoke-Checked([string]$Exe, [Parameter(ValueFromRemainingArguments = $true)][string[]]$Args) {
    & $Exe @Args
    if ($LASTEXITCODE -ne 0) { throw "Command failed: $Exe $($Args -join ' ')" }
}

function Assert-Contains([string]$Path, [string]$Needle, [string]$Label) {
    Require-File $Path
    if (-not (Select-String -LiteralPath $Path -SimpleMatch -Pattern $Needle -Quiet)) {
        throw "Missing $Label in $Path`: $Needle"
    }
}

function Assert-NotContains([string]$Path, [string]$Needle, [string]$Label) {
    Require-File $Path
    if (Select-String -LiteralPath $Path -SimpleMatch -Pattern $Needle -Quiet) {
        throw "Unexpected $Label in $Path`: $Needle"
    }
}

function Get-InitServiceBlock([string]$Path, [string]$ServiceName) {
    Require-File $Path
    $lines = [IO.File]::ReadAllLines($Path)
    $start = -1
    for ($i = 0; $i -lt $lines.Length; $i++) {
        if ($lines[$i] -match ('^service\s+' + [regex]::Escape($ServiceName) + '(?:\s|$)')) {
            if ($start -ge 0) { throw "Duplicate init service block: $ServiceName in $Path" }
            $start = $i
        }
    }
    if ($start -lt 0) { throw "Missing init service block: $ServiceName in $Path" }
    $block = [Collections.Generic.List[string]]::new()
    $block.Add($lines[$start])
    for ($i = $start + 1; $i -lt $lines.Length; $i++) {
        if ($lines[$i] -match '^\S') { break }
        $block.Add($lines[$i])
    }
    return @($block)
}

function Get-SmaliMethodBlock([string]$Path, [string]$MethodName) {
    Require-File $Path
    $lines = [IO.File]::ReadAllLines($Path)
    $starts = @()
    for ($i = 0; $i -lt $lines.Length; $i++) {
        if ($lines[$i] -match ('^\.method\b.*\s' + [regex]::Escape($MethodName) + '\(')) {
            $starts += $i
        }
    }
    if ($starts.Count -ne 1) {
        throw "Expected one smali method $MethodName in $Path; found $($starts.Count)"
    }
    $block = [Collections.Generic.List[string]]::new()
    for ($i = $starts[0]; $i -lt $lines.Length; $i++) {
        $block.Add($lines[$i])
        if ($lines[$i] -ceq '.end method') { return @($block) }
    }
    throw "Unterminated smali method $MethodName in $Path"
}

function Get-ShellFunctionBlock([string]$Path, [string]$FunctionName) {
    Require-File $Path
    $text = Get-Content -Raw -LiteralPath $Path
    $match = [regex]::Match($text,
        ('(?ms)^' + [regex]::Escape($FunctionName) + '\(\) \{.*?^\}'))
    if (-not $match.Success) { throw "Missing shell function $FunctionName in $Path" }
    return $match.Value
}

function Assert-OrderedText([string]$Text, [string[]]$Needles, [string]$Label) {
    $position = -1
    foreach ($needle in $Needles) {
        $next = $Text.IndexOf($needle, $position + 1, [StringComparison]::Ordinal)
        if ($next -lt 0) { throw "Missing or out-of-order $Label marker: $needle" }
        $position = $next
    }
}

function Assert-SmaliDispatchAuthorized(
        [string]$Text,
        [string]$AuthNeedle,
        [string]$DispatchNeedle,
        [string]$Label) {
    $dispatch = $Text.IndexOf($DispatchNeedle, [StringComparison]::Ordinal)
    if ($dispatch -lt 0 -or
            $Text.IndexOf($DispatchNeedle, $dispatch + 1, [StringComparison]::Ordinal) -ge 0) {
        throw "Final services.jar must contain exactly one $Label dispatch."
    }
    $arm = $Text.LastIndexOf(':pswitch_', $dispatch, [StringComparison]::Ordinal)
    $auth = $Text.LastIndexOf($AuthNeedle, $dispatch, [StringComparison]::Ordinal)
    if ($arm -lt 0 -or $auth -lt $arm) {
        throw "Final services.jar $Label dispatch is not strictly authorized in its switch arm."
    }
}

function Get-ExactAsciiLineCount([byte[]]$Bytes, [string]$Line) {
    $expected = [Text.Encoding]::ASCII.GetBytes($Line)
    $count = 0
    $start = 0
    for ($i = 0; $i -le $Bytes.Length; $i++) {
        if ($i -ne $Bytes.Length -and $Bytes[$i] -ne 0x0A) { continue }
        $end = $i
        if ($end -gt $start -and $Bytes[$end - 1] -eq 0x0D) { $end-- }
        if (($end - $start) -eq $expected.Length) {
            $same = $true
            for ($j = 0; $j -lt $expected.Length; $j++) {
                if ($Bytes[$start + $j] -ne $expected[$j]) {
                    $same = $false
                    break
                }
            }
            if ($same) { $count++ }
        }
        $start = $i + 1
    }
    return $count
}

function Test-ByteSequence([byte[]]$Bytes, [byte[]]$Needle) {
    if ($Needle.Length -eq 0 -or $Needle.Length -gt $Bytes.Length) { return $false }
    for ($i = 0; $i -le $Bytes.Length - $Needle.Length; $i++) {
        $same = $true
        for ($j = 0; $j -lt $Needle.Length; $j++) {
            if ($Bytes[$i + $j] -ne $Needle[$j]) {
                $same = $false
                break
            }
        }
        if ($same) { return $true }
    }
    return $false
}

function File-Sha256([string]$Path) {
    Require-File $Path
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Files-ConcatenatedSha256([string[]]$Paths) {
    $hash = [Security.Cryptography.IncrementalHash]::CreateHash(
        [Security.Cryptography.HashAlgorithmName]::SHA256)
    try {
        $buffer = New-Object byte[] (1024 * 1024)
        foreach ($path in $Paths) {
            Require-File $path
            $stream = [IO.File]::OpenRead($path)
            try {
                while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                    $hash.AppendData($buffer, 0, $read)
                }
            } finally {
                $stream.Dispose()
            }
        }
        return ([BitConverter]::ToString($hash.GetHashAndReset()) -replace '-', '').ToLowerInvariant()
    } finally {
        $hash.Dispose()
    }
}

function Read-AvbInfo([string]$Path) {
    $text = & $Python $Avbtool info_image --image $Path | Out-String -Width 4096
    if ($LASTEXITCODE -ne 0) { throw "Invalid AVB image: $Path" }
    return $text
}

function Read-AvbRollbackIndex([string]$Info, [string]$Label) {
    $match = [regex]::Match($Info, 'Rollback Index:\s+(\d+)')
    if (-not $match.Success) { throw "Missing rollback index in $Label" }
    return [int64]$match.Groups[1].Value
}

function Read-AvbHashDigest([string]$Info, [string]$Partition, [string]$Label) {
    $blocks = [regex]::Split($Info, '(?m)(?=^\s*(?:Hash|Hashtree|Chain Partition) descriptor:)')
    foreach ($block in $blocks) {
        if ($block -notmatch '(?m)^\s*Hash descriptor:\s*$') { continue }
        $partitionMatch = [regex]::Match($block, '(?m)^\s*Partition Name:\s+([^\r\n]+)\s*$')
        if (-not $partitionMatch.Success -or $partitionMatch.Groups[1].Value.Trim() -ne $Partition) { continue }
        $digestMatch = [regex]::Match($block, '(?m)^\s*Digest:\s+([0-9a-fA-F]+)\s*$')
        if (-not $digestMatch.Success) { throw "Missing digest for $Partition in $Label" }
        return $digestMatch.Groups[1].Value.ToLowerInvariant()
    }
    throw "Missing hash descriptor for $Partition in $Label"
}

function Find-AndroidTool([string]$Name) {
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) { return $(if ($cmd.Source) { $cmd.Source } else { $cmd.FullName }) }
    $file = Get-ChildItem -LiteralPath (Join-Path $AndroidSdkDir 'build-tools') -Recurse -Filter $Name -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $file) { throw "Missing Android tool: $Name" }
    return $file.FullName
}

function Assert-Apk([string]$ApkPath, [string]$Label) {
    $apkSigner = Find-AndroidTool 'apksigner.bat'
    $certs = & $apkSigner verify --print-certs $ApkPath | Out-String -Width 4096
    if ($LASTEXITCODE -ne 0 -or $certs -notmatch [regex]::Escape("Signer #1 certificate SHA-256 digest: $ReleaseCertSha256")) {
        throw "$Label is not signed with the release certificate: $ApkPath"
    }
    $aapt2 = Find-AndroidTool 'aapt2.exe'
    $badging = & $aapt2 dump badging $ApkPath | Out-String -Width 4096
    if ($LASTEXITCODE -ne 0 -or $badging -notmatch "package: name='com\.zui\.zuicontrol' versionCode='$ExpectedVersionCode' versionName='$ExpectedVersionName'") {
        throw "$Label has the wrong package or version"
    }
    if ($badging -match '(?m)^application-debuggable') {
        throw "$Label must not be debuggable"
    }
    $permissions = & $aapt2 dump permissions $ApkPath | Out-String -Width 4096
    if ($permissions -match 'com\.zui\.performance\.permission\.gamemode') {
        throw "$Label still requests the retired P2 GameMode permission"
    }
    if ($permissions -notmatch 'android\.permission\.WRITE_SECURE_SETTINGS') {
        throw "$Label is missing the Dolby tile permission"
    }
    $manifest = & $aapt2 dump xmltree $ApkPath --file AndroidManifest.xml | Out-String -Width 4096
    if ($manifest -notmatch 'com\.zui\.zuicontrol\.DolbyTileService') {
        throw "$Label is missing DolbyTileService"
    }
}

function Assert-AsoulConfig([string]$Path) {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        $text = $line.Trim()
        if (-not $text -or $text.StartsWith('#')) { continue }
        $parts = $text -split '=', 2
        if ($parts.Count -ne 2) { throw "Invalid A-SOUL config line: $line" }
        $values[$parts[0].Trim()] = $parts[1].Trim()
    }
    if ($values.Count -ne 3 -or $values.mode -ne '0' -or $values.rt -ne '0' -or $values.opt -ne '0xDEADBEEF') {
        throw 'A-SOUL must use the verified mode=0, rt=0, opt=0xDEADBEEF configuration'
    }
}

function Assert-BinaryContains([string]$Path, [string]$Needle, [bool]$Expected, [string]$Label) {
    Require-File $Path
    $text = [Text.Encoding]::ASCII.GetString([IO.File]::ReadAllBytes($Path))
    if ($text.Contains($Needle) -ne $Expected) {
        throw "Unexpected binary string state for $Label in $Path`: $Needle"
    }
}

function Assert-UperfConfig([string]$Path) {
    $raw = Get-Content -Raw -LiteralPath $Path
    $json = $raw | ConvertFrom-Json
    if ($json.modules.switcher.switchInode -ne '/data/vendor/zui_control/uperf/effective_powermode.txt') {
        throw 'Uperf switcher does not use the ROM frontend effective-mode path'
    }
    if ($json.modules.switcher.perapp -ne '/data/vendor/zui_control/uperf/perapp_powermode.txt') {
        throw 'Uperf parser-compatible per-app path is not canonical'
    }
    if ($json.modules.sfanalysis.enable -ne $false -or $json.modules.sched.enable -ne $false) {
        throw 'Bundled Uperf must disable unneeded SF analysis and delegate thread placement to A-SOUL'
    }
    if ($json.presets.balance.idle.'cpu.baseSampleTime' -ne 1.0 -or
        $json.presets.balance.idle.'cpu.baseSlackTime' -ne 0.5 -or
        $json.presets.powersave.idle.'cpu.baseSampleTime' -ne 1.5 -or
        $json.presets.powersave.idle.'cpu.baseSlackTime' -ne 0.8) {
        throw 'Uperf SM8650 v1.0.6 idle sampling rebase is incomplete'
    }
    $presets = @($json.presets.psobject.Properties.Name | Sort-Object)
    $expectedPresets = @('balance', 'fast', 'performance', 'powersave')
    if (Compare-Object $presets $expectedPresets) {
        throw "Uperf must expose exactly four presets: $($presets -join ', ')"
    }
    foreach ($governor in @('governor0', 'governor2', 'governor5', 'governor7')) {
        if ($json.initials.sysfs.$governor -ne 'walt') { throw "Uperf $governor is not walt" }
    }
    foreach ($forbidden in @('/sys/class/kgsl', 'sysfs.gpuMin', 'sysfs.gpuMax', 'thermal_pwrlevel', 'max_pwrlevel', 'min_pwrlevel', 'thermal-engine', 'mi_thermald')) {
        if ($raw.Contains($forbidden)) { throw "Uperf config contains forbidden thermal/direct-pwrlevel marker: $forbidden" }
    }
}

$FlashDir = (Resolve-Path -LiteralPath $FlashDir).Path
$Super = Join-Path $FlashDir 'super.img'
$Boot = Join-Path $FlashDir 'boot.img'
$Vbmeta = Join-Path $FlashDir 'vbmeta.img'
$VbmetaSystem = Join-Path $FlashDir 'vbmeta_system.img'
$SidecarApk = Join-Path $FlashDir 'ZuiControl-v19-system.apk'
$ReleaseSidecarApk = Join-Path $FlashDir 'ZuiControl-v19-release.apk'
foreach ($file in @($Python, $LpUnpack, $ExtractErofs, $Apktool, $Avbtool, $Super, $Boot, $Vbmeta, $VbmetaSystem, $SidecarApk, $ReleaseSidecarApk)) {
    Require-File $file
}
if ($ExpectedVendorApkInventoryPath) { Require-File $ExpectedVendorApkInventoryPath }

$BootHash = File-Sha256 $Boot
if ($BootHash -ne $ExpectedBootSha256) {
    throw "Unexpected B072 boot image: $BootHash"
}
$BootAvbInfo = Read-AvbInfo $Boot
$VbmetaAvbInfo = Read-AvbInfo $Vbmeta
$VbmetaSystemAvbInfo = Read-AvbInfo $VbmetaSystem
if (-not $BootAvbInfo.Contains($ExpectedBuildFingerprintMarker)) {
    throw 'boot.img is not from the target B072 build'
}
if (-not $VbmetaSystemAvbInfo.Contains($ExpectedBuildFingerprintMarker)) {
    throw 'vbmeta_system.img is not from the target B072 build'
}
if ($VbmetaAvbInfo.Contains($ForbiddenBuildFingerprintMarker) -or
    $VbmetaSystemAvbInfo.Contains($ForbiddenBuildFingerprintMarker)) {
    throw 'AVB metadata contains 187 descriptors even though the fixed flash allowlist leaves those partitions on B072'
}
foreach ($entry in @(
    @{ Partition = 'dtbo'; ParentInfo = $VbmetaAvbInfo; ParentLabel = 'vbmeta.img' },
    @{ Partition = 'init_boot'; ParentInfo = $VbmetaAvbInfo; ParentLabel = 'vbmeta.img' },
    @{ Partition = 'vendor_boot'; ParentInfo = $VbmetaAvbInfo; ParentLabel = 'vbmeta.img' },
    @{ Partition = 'pvmfw'; ParentInfo = $VbmetaSystemAvbInfo; ParentLabel = 'vbmeta_system.img' }
)) {
    $officialImage = Join-Path $OfficialB072Dir ($entry.Partition + '.img')
    Require-File $officialImage
    $expectedDigest = Read-AvbHashDigest (Read-AvbInfo $officialImage) $entry.Partition "official B072 $($entry.Partition).img"
    $actualDigest = Read-AvbHashDigest $entry.ParentInfo $entry.Partition $entry.ParentLabel
    if ($actualDigest -ne $expectedDigest) {
        throw "$($entry.ParentLabel) points $($entry.Partition) at a non-B072 digest: $actualDigest != $expectedDigest"
    }
}
$BootRollbackIndex = Read-AvbRollbackIndex $BootAvbInfo 'boot.img'
$VbmetaSystemRollbackIndex = Read-AvbRollbackIndex $VbmetaSystemAvbInfo 'vbmeta_system.img'
if ($BootRollbackIndex -lt $MinimumAvbRollbackIndex) {
    throw "boot.img rollback index is below the device floor: $BootRollbackIndex < $MinimumAvbRollbackIndex"
}
if ($VbmetaSystemRollbackIndex -lt $MinimumAvbRollbackIndex) {
    throw "vbmeta_system.img rollback index is below the device floor: $VbmetaSystemRollbackIndex < $MinimumAvbRollbackIndex"
}

$ok = $false
Assert-VerificationWorkBoundary
try {
    Remove-VerificationWork
    $ImageDir = Join-Path $WorkDir 'img'
    $ExtractDir = Join-Path $WorkDir 'extract'
    New-Item -ItemType Directory -Path $ImageDir, $ExtractDir | Out-Null
    Invoke-Checked $Python $LpUnpack $Super 'ALL' $ImageDir
    foreach ($partition in @('system_a', 'vendor_a')) {
        $image = Join-Path $ImageDir "$partition.img"
        Require-File $image
        Invoke-Checked $ExtractErofs '-i' $image '-o' $ExtractDir '-x' '-f'
    }
    $VendorImageSha256 = File-Sha256 (Join-Path $ImageDir 'vendor_a.img')
    if ($ExpectedVendorImageSha256 -and
        $VendorImageSha256 -ne $ExpectedVendorImageSha256.ToLowerInvariant()) {
        throw "Final super vendor_a hash mismatch: $VendorImageSha256 != $ExpectedVendorImageSha256"
    }

    $SystemRoot = Join-Path $ExtractDir 'system_a'
    $VendorRoot = Join-Path $ExtractDir 'vendor_a'
    $VendorApkCount = 0
    $VendorApkInventorySha256 = ''
    if ($ExpectedVendorApkInventoryPath) {
        $expectedVendorRecord = Get-Content -Raw -LiteralPath $ExpectedVendorApkInventoryPath | ConvertFrom-Json
        if (-not [bool]$expectedVendorRecord.unchanged) {
            throw 'Expected vendor APK inventory was not marked unchanged.'
        }
        $expectedVendorApks = @($expectedVendorRecord.apks)
        $actualVendorApks = @(Snapshot-Apks $VendorRoot)
        $expectedCanonical = @(Canonical-ApkInventory $expectedVendorApks)
        $actualCanonical = @(Canonical-ApkInventory $actualVendorApks)
        if ([int]$expectedVendorRecord.count -ne $expectedVendorApks.Count -or
            $expectedVendorApks.Count -ne $actualVendorApks.Count -or
            (Compare-Object $expectedCanonical $actualCanonical)) {
            throw ('Final super vendor APK inventory mismatch: expected={0}, actual={1}' -f
                $expectedVendorApks.Count, $actualVendorApks.Count)
        }
        $VendorApkCount = $actualVendorApks.Count
        $VendorApkInventorySha256 = File-Sha256 $ExpectedVendorApkInventoryPath
    }
    $SystemImageContexts = Join-Path $ExtractDir 'config\system_a_file_contexts'
    $System = Join-Path $SystemRoot 'system'
    $PlatSelinux = Join-Path $System 'etc\selinux'
    $VendorSelinux = Join-Path $VendorRoot 'etc\selinux'
    $AppApk = Join-Path $System 'priv-app\ZuiControlV49\ZuiControl.apk'
    $Daemon = Join-Path $System 'bin\zui_controld'
    $Uperf = Join-Path $System 'bin\uperf'
    $UperfService = Join-Path $System 'bin\zui_uperf_service'
    $UperfSupervisor = Join-Path $System 'bin\zui_uperf_supervisor'
    $Asoul = Join-Path $System 'bin\AsoulOpt'
    $SchedulerRc = Join-Path $System 'etc\init\zui_scheduler.rc'
    $DaemonRc = Join-Path $System 'etc\init\zui_controld.rc'
    $RefreshKillRc = Join-Path $System 'etc\init\zui_refresh_kill_switch.rc'
    $SchedulerPrepare = Join-Path $System 'etc\zui_control\zui_scheduler_prepare.sh'
    $UperfCrashGate = Join-Path $System 'etc\zui_control\zui_uperf_crash_gate.sh'
    $UperfConfig = Join-Path $System 'etc\zui_control\uperf-sm8650.json'
    $UperfPerApp = Join-Path $System 'etc\zui_control\default_uperf_perapp.txt'
    $AsoulConfig = Join-Path $System 'etc\zui_control\default_asopt.conf'
    $PrivPermissions = Join-Path $System 'etc\permissions\privapp-permissions-zui-control.xml'
    $ZuippPower = Join-Path $System 'etc\zuipp_powercfg.xml'
    $MemCleaner = Join-Path $System 'etc\ZuiMemCleanerConfig.xml'
    $PowerPolicy = Join-Path $System 'etc\ZuiPowerPolicyConfig.xml'
    $AutoRun = Join-Path $System 'etc\motorola\bgintents\com.zui.safecenter.autorun.xml'
    foreach ($file in @($AppApk, $Daemon, $Uperf, $UperfService, $UperfSupervisor, $Asoul, $SchedulerRc, $DaemonRc, $RefreshKillRc, $SchedulerPrepare, $UperfCrashGate, $UperfConfig, $UperfPerApp, $AsoulConfig, $PrivPermissions, $ZuippPower, $MemCleaner, $PowerPolicy, $AutoRun)) {
        Require-File $file
    }

    if ((File-Sha256 $Uperf) -ne $ExpectedUperfSha256) { throw 'Embedded Uperf core hash is not approved' }
    Assert-BinaryContains $UperfSupervisor 'ZUI_UPERF_SUPERVISOR_EXEC_FAILED' $true 'native Uperf supervisor exec-failure marker'
    Assert-BinaryContains $UperfSupervisor 'supervised Uperf descendant tree is gone' $true 'native Uperf supervisor tree-lifetime marker'
    Assert-BinaryContains $UperfSupervisor 'I Uperf is running' $true 'native regular-log readiness marker'
    Assert-BinaryContains $UperfSupervisor 'Uperf readiness timed out after' $true 'bounded native startup timeout marker'
    Assert-BinaryContains $UperfSupervisor '.tmp' $true 'atomic ready marker temporary suffix'
    if ((File-Sha256 $Asoul) -ne $ExpectedAsoulSha256) { throw 'Embedded Shiroko A-SOUL hash is not approved' }
    Assert-BinaryContains $Asoul '/data/vendor/asopt.conf' $true 'ROM A-SOUL config path'
    Assert-BinaryContains $Asoul '/data/adb/naki/asopt.conf' $false 'retired Magisk config path'
    foreach ($path in @(
        (Join-Path $System 'bin\AppOpt'),
        (Join-Path $System 'bin\AppOpt-ebpf'),
        (Join-Path $System 'etc\init\zui_appopt.rc'),
        (Join-Path $System 'etc\zui_control\AppOpt.json'),
        (Join-Path $System 'etc\zui_control\zui_appopt_prepare.sh'),
        (Join-Path $SystemRoot 'AppOpt.json')
    )) { Assert-Missing $path 'retired AppOpt runtime' }
    Assert-Missing (Join-Path $System 'etc\zui_control\zui_cloud_block.sh') 'cloud-control block script'
    Assert-Missing (Join-Path $System 'etc\zui_control\promote_zuipp_xml.sh') 'retired ZuiPP promotion script'
    Assert-Missing (Join-Path $System 'preinstall\QQMusic') 'removed third-party QQ Music preinstall'
    foreach ($old in 30..48) { Assert-Missing (Join-Path $System "priv-app\ZuiControlV$old") 'previous ZuiControl version directory' }
    Assert-Missing (Join-Path $System 'priv-app\ZuiControl') 'legacy unversioned ZuiControl directory'

    Assert-UperfConfig $UperfConfig
    Assert-AsoulConfig $AsoulConfig
    $activeRules = @(Get-Content -LiteralPath $UperfPerApp | ForEach-Object { $_.Trim() } | Where-Object { $_ -and -not $_.StartsWith('#') })
    $expectedRules = @('com.kurogame.mingchao performance', 'com.kurogame.wutheringwaves.global performance', '- powersave')
    if (Compare-Object $activeRules $expectedRules) { throw "Unexpected default Uperf rules: $($activeRules -join '; ')" }

    Assert-Contains $SchedulerRc 'service zui_uperf /system/bin/zui_uperf_service' 'supervised Uperf init service'
    Assert-NotContains $SchedulerRc '    oneshot' 'untracked daemonized Uperf service'
    Assert-Contains $SchedulerRc 'service zui_asoulopt /system/bin/AsoulOpt' 'A-SOUL init service'
    Assert-Contains $SchedulerRc '    stop vendor.perfservice' 'QTI userspace perf bridge ownership fence'
    Assert-Contains $SchedulerRc '    start vendor.perfservice' 'QTI userspace perf bridge rollback'
    Assert-Contains $SchedulerRc '    setprop sys.zui_control.scheduler_active 1' 'init-owned active scheduler state'
    Assert-Contains $SchedulerRc '    setprop sys.zui_control.scheduler_active 0' 'init-owned inactive scheduler state'
    Assert-Contains $SchedulerRc 'on property:init.svc.vendor.perfservice=running && property:sys.zui_control.scheduler_active=1' 'init-native conditional OEM fence'
    Assert-Contains $SchedulerRc 'on property:zui_control.asoul=start && property:sys.zui_control.scheduler_active=1' 'active-only A-SOUL start action'
    Assert-Contains $SchedulerRc 'on property:zui_control.scheduler=fence && property:sys.zui_control.scheduler_active=1' 'active-only compatibility OEM fence'
    Assert-Contains $SchedulerRc 'on property:sys.zui_control.uperf_fail_safe=1' 'Uperf bounded fail-safe trigger'
    Assert-Contains $SchedulerRc '    setprop sys.zui_control.uperf_fail_safe 0' 'explicit Uperf fail-safe reset'
    Assert-Contains $SchedulerRc '    restart_period 5' 'rate-limited Uperf whole-service recovery'
    Assert-Contains $SchedulerRc '    onrestart exec u:r:shell:s0 root shell -- /system/bin/sh /system/etc/zui_control/zui_uperf_crash_gate.sh' 'event-driven Uperf service crash gate'
    Assert-NotContains $SchedulerRc "on property:zui_control.scheduler=fence`n    stop vendor.perfservice" 'unconditional legacy OEM fence'
    foreach ($bridge in @('performance', 'poweropt-service', 'perf2-hal-1-0')) {
        Assert-NotContains $SchedulerRc "    stop $bridge" 'OEM telemetry service stop action'
        Assert-NotContains $SchedulerRc "    start $bridge" 'OEM telemetry service ownership action'
    }
    Assert-Contains $SchedulerRc 'symlink /data/vendor/zui_control/asoul/asopt.conf /data/vendor/asopt.conf' 'canonical A-SOUL config symlink'
    Assert-NotContains $SchedulerRc 'write /data/vendor/zui_control/asoul/asopt.conf' 'boot-time A-SOUL config truncation'
    Assert-NotContains $SchedulerRc '/data/adb' 'retired Magisk config path'
    Assert-Contains $SchedulerRc 'trigger zui-scheduler-start' 'scheduler boot trigger'
    $schedulerRcBytes = [IO.File]::ReadAllBytes($SchedulerRc)
    $schedulerRcText = [Text.Encoding]::UTF8.GetString($schedulerRcBytes).Replace("`r`n", "`n")
    $schedulerRcModeLines = [ordered]@{}
    foreach ($mode in @('powersave', 'balance', 'performance', 'fast')) {
        $triggerLine = "on property:sys.zui_control.uperf_mode=$mode"
        $writeLine = "    write /data/vendor/zui_control/uperf/effective_powermode.txt $mode"
        if ((Get-ExactAsciiLineCount $schedulerRcBytes $triggerLine) -ne 1) {
            throw "Final super init rc does not have exactly one $mode trigger"
        }
        if ((Get-ExactAsciiLineCount $schedulerRcBytes $writeLine) -ne 1) {
            throw "Final super init rc does not have the exact bare $mode write bytes"
        }
        $actionBlock = "$triggerLine`n$writeLine"
        if ([regex]::Matches($schedulerRcText, [regex]::Escape($actionBlock)).Count -ne 1) {
            throw "Final super init rc does not pair the $mode trigger with its exact write action"
        }
        $badValue = [Text.Encoding]::ASCII.GetBytes(('"' + $mode + '\n"'))
        if (Test-ByteSequence $schedulerRcBytes $badValue) {
            throw "Final super init rc still contains quoted literal backslash-n bytes for $mode"
        }
        $writeBytes = [Text.Encoding]::ASCII.GetBytes($writeLine)
        $schedulerRcModeLines[$mode] = [ordered]@{
            text = $writeLine
            hex = (($writeBytes | ForEach-Object { $_.ToString('x2') }) -join '')
        }
    }
    Assert-NotContains $SchedulerRc 'on property:sys.zui_control.uperf_mode=*' 'unbounded Uperf property trigger'
    Assert-NotContains $SchedulerRc 'exec sh' 'shell-based Uperf property handler'
    Assert-NotContains $SchedulerRc 'seclabel u:r:shell:s0' 'shell-domain scheduler service'
    Assert-NotContains $DaemonRc 'zui_control.zuipp' 'retired XML property action'
    Assert-Contains $DaemonRc 'on property:sys.zui_control.command_seq=*' 'event-triggered command doorbell'
    Assert-Contains $DaemonRc 'service zui_control_request /system/bin/sh /system/bin/zui_controld --oneshot-request ${sys.zui_control.command_id:-unset} ${sys.zui_control.command_sha256:-unset}' 'authenticated oneshot command service'
    Assert-NotContains $DaemonRc 'service zui_controld /system/bin/sh /system/bin/zui_controld' 'retired persistent daemon service'
    Assert-NotContains $DaemonRc 'start zui_controld' 'retired persistent daemon boot start'
    Assert-Contains $DaemonRc 'mkdir /data/vendor/zui_control 0755 root root' 'root-owned transaction parent'
    Assert-Contains $SchedulerRc 'mkdir /data/vendor/zui_control 0755 root root' 'root-owned scheduler data parent'
    Assert-Contains $DaemonRc 'mkdir /data/vendor/zui_control/zuicontrol 0700 root root' 'root-private transaction directory'
    Assert-Contains $RefreshKillRc 'on property:persist.zui_control.disable=*' 'global disable edge trigger'
    Assert-Contains $RefreshKillRc 'on property:persist.zui_control.refresh.disable=*' 'refresh disable edge trigger'
    $pokeLine = '    exec_background u:r:shell:s0 shell shell -- /system/bin/sh -c "exec /system/bin/service call zui_control 1599295570"'
    if ((Get-ExactAsciiLineCount ([IO.File]::ReadAllBytes($RefreshKillRc)) $pokeLine) -ne 2) {
        throw 'Final super refresh kill switch must contain exactly two standard sysprop poke actions.'
    }
    foreach ($forbidden in @('zui_controld', 'postDelayed', 'while ', 'sleep ', 'Timer')) {
        Assert-NotContains $RefreshKillRc $forbidden 'polling or persistent refresh kill transport'
    }
    foreach ($line in @(
        '    chown root root /data/vendor/zui_control/zuicontrol/last_request_receipt',
        '    chmod 0600 /data/vendor/zui_control/zuicontrol/last_request_receipt',
        '    restorecon /data/vendor/zui_control/zuicontrol/last_request_receipt',
        '    chown root root /data/vendor/zui_control/zuicontrol/active_request_claim',
        '    chmod 0600 /data/vendor/zui_control/zuicontrol/active_request_claim',
        '    restorecon /data/vendor/zui_control/zuicontrol/active_request_claim'
    )) { Assert-Contains $DaemonRc $line 'proactive transaction metadata repair' }
    $commandServiceBlock = @(Get-InitServiceBlock $DaemonRc 'zui_control_request')
    foreach ($line in @('    class late_start', '    disabled', '    oneshot', '    user root',
            '    group root system shell readproc', '    seclabel u:r:shell:s0')) {
        if (@($commandServiceBlock | Where-Object { $_ -ceq $line }).Count -ne 1) {
            throw "Final super command service is missing exact scoped directive: $line"
        }
    }
    if (@($commandServiceBlock | Where-Object { $_ -match '(?:^|\s)graphics(?:\s|$)' }).Count) {
        throw 'Final super command service unexpectedly has the graphics group.'
    }
    Assert-Contains $UperfService 'LOG=/data/vendor/zui_control/log/uperf.log' 'regular Uperf startup log path'
    Assert-Contains $UperfService 'exec "$SUPERVISOR" "$CONFIG" "$LOG" "$READY_UPTIME"' 'shell-to-native supervisor exec'
    Assert-NotContains $UperfService 'LOG_PIPE' 'retired Uperf FIFO path'
    Assert-NotContains $UperfService 'mkfifo' 'retired Uperf FIFO creation'
    Assert-NotContains $UperfService 'drain_uperf_log' 'retired steady worker log observer'
    Assert-NotContains $UperfService 'wait "$supervisor_pid"' 'retired background supervisor launch'
    Assert-NotContains $UperfService 'terminated unexpectedly, try to get tombstone' 'retired worker crash text observer'
    Assert-NotContains $UperfService 'setprop ' 'no fail-safe writer in wrapper'
    Assert-NotContains $UperfService 'uperf_process_count' 'retired periodic process-count health check'
    Assert-NotContains $UperfService 'grep ' 'retired periodic log scanner'
    Assert-NotContains $UperfService 'sleep ' 'retired periodic wrapper timer'
    Assert-NotContains $UperfService 'pidof uperf' 'cross-domain proc scanner in Uperf supervisor'
    Assert-NotContains $UperfService 'killall' 'cross-domain process scanner in Uperf supervisor'
    Assert-Contains $UperfService 'echo $$ > /dev/cpuset/background/tasks' 'background placement for Uperf itself'
    Assert-NotContains $UperfService '< /proc/uptime' 'native supervisor owns startup timing'
    Assert-Contains $UperfCrashGate '[ "$runtime" -ge 0 ] && [ "$runtime" -le 2 ]' 'rapid whole-service lifetime classification'
    Assert-Contains $UperfCrashGate '[ "$count" -lt 3 ] || setprop "$FAIL_SAFE_PROP" 1' 'bounded whole-service crash fail-safe'
    Assert-Contains $UperfCrashGate '< /proc/uptime' 'monotonic whole-service lifetime source'
    Assert-NotContains $UperfCrashGate 'sys.zui_control.scheduler_active' 'unnecessary broad-shell scheduler-active guard'
    Assert-NotContains $UperfCrashGate 'while ' 'no crash-gate loop'
    Assert-NotContains $UperfCrashGate 'sleep ' 'no crash-gate timer'
    Assert-Contains $SchedulerPrepare "printf 'balance\n'" 'balanced global default'
    Assert-Contains $SchedulerPrepare 'effective_powermode.txt' 'effective Uperf mode preparation'
    Assert-Contains $SchedulerPrepare 'property_mode="${1:-}"' 'init-supplied Uperf property recovery input'
    Assert-Contains $SchedulerPrepare 'if valid_preset "$property_mode"; then' 'validated Uperf property recovery'
    Assert-Contains $SchedulerPrepare 'effective_mode="$property_mode"' 'Uperf property recovery preference'
    Assert-Contains $SchedulerPrepare 'effective_mode="$global_mode"' 'durable global recovery fallback'
    Assert-Contains $SchedulerPrepare '$1 != "*"' 'retired per-app global fallback removal'
    Assert-Contains $SchedulerPrepare 'mode == 1 && rt == 1 && opt == 1' 'persistent A-SOUL config validation'
    Assert-Contains $SchedulerPrepare 'safecenter_keepalive_backup.flag' 'retired SafeCenter data cleanup'
    Assert-Contains $SchedulerPrepare '.rom_frontend_v47' 'retired Uperf frontend marker cleanup'
    $schedulerPrepareText = Get-Content -Raw -LiteralPath $SchedulerPrepare
    foreach ($publication in @(
        'settings put system zui_control_uperf_mode "$global_mode"',
        'settings put system zui_control_uperf_rules_text "$rules_text"'
    )) {
        if ([regex]::Matches($schedulerPrepareText, [regex]::Escape($publication)).Count -ne 1) {
            throw "Scheduler prepare must publish exactly once per boot/restart: $publication"
        }
    }
    foreach ($healthKey in @('zui_control_uperf_health', 'zui_control_asoul_health',
            'zui_control_daemon_status_text')) {
        Assert-NotContains $SchedulerPrepare $healthKey 'retired periodic health publication'
    }
    Assert-NotContains $SchedulerPrepare 'setprop zui_control.appopt' 'retired AppOpt property'
    Assert-NotContains $SchedulerPrepare 'setprop zui_control.zuipp' 'retired XML property'
    Assert-NotContains $SchedulerPrepare '/data/adb' 'shell-domain access to protected Magisk data'
    foreach ($forbidden in @('killall', 'thermal', '/sdcard', 'busybox', 'zui_cloud')) {
        Assert-NotContains $SchedulerPrepare $forbidden 'copied third-party unsafe setup logic'
    }

    Assert-Contains $Daemon 'set_uperf_mode)' 'Uperf global mode command'
    Assert-Contains $Daemon 'set_uperf_app)' 'Uperf per-app command'
    Assert-Contains $Daemon 'restart_scheduler)' 'scheduler restart command'
    Assert-Contains $Daemon 'powersave|balance|performance|fast' 'four Uperf modes'
    Assert-NotContains $Daemon 'auto|powersave|balance|performance|fast' 'retired automatic frontend mode'
    Assert-Contains $Daemon 'valid_uperf_preset "$requested_mode"' 'per-app preset validation'
    Assert-NotContains $Daemon 'UPERF_SCENE_KEY=zui_control_top_package' 'retired daemon scene polling source'
    Assert-NotContains $Daemon 'UPERF_SCREEN_KEY=zui_control_screen_on' 'retired daemon screen polling source'
    Assert-NotContains $Daemon 'sync_uperf_frontend()' 'retired daemon Uperf frontend'
    Assert-NotContains $Daemon 'write_uperf_effective_mode()' 'retired daemon effective-mode writer'
    Assert-NotContains $Daemon '> "$UPERF_EFFECTIVE_MODE"' 'daemon effective-mode write redirection'
    Assert-NotContains $Daemon 'ps -AZ' 'cross-domain health scanner'
    Assert-NotContains $Daemon 'for bridge in vendor.perfservice performance poweropt-service perf2-hal-1-0' 'broad OEM telemetry fence'
    Assert-Contains $Daemon 'persistentDaemon=retired' 'on-demand export persistent-daemon state'
    Assert-Contains $Daemon 'REQUEST_RESULT_DETAIL="state=binder"' 'Binder-owned status command'
    foreach ($retiredHealth in @(
        'main_loop()',
        'sleep 20',
        'publish_scheduler_health()',
        'ensure_scheduler_running()',
        'set_status()',
        'zui_control_status_time',
        'zui_control_status_last',
        'zui_control_daemon_status_text',
        'zui_control_uperf_health',
        'zui_control_asoul_health'
    )) { Assert-NotContains $Daemon $retiredHealth 'retired persistent health polling path' }
    foreach ($forbidden in @('/sys/class/kgsl/kgsl-3d0', '/sys/devices/system/cpu/cpufreq', 'provider_direct', 'GameModeProvider/contact', 'zui_control.cloud_block', 'cloud_block.log')) {
        Assert-NotContains $Daemon $forbidden 'retired direct/provider/cloud runtime'
    }
    foreach ($retired in @('AppOpt', 'APPOPT_', 'ZUIPP_', 'XML_STATE', 'SafeCenter', 'safecenter', '/data/adb', 'su ')) {
        Assert-NotContains $Daemon $retired 'retired scheduler implementation'
    }
    Assert-Contains $Daemon 'refreshOwner=system' 'system_server refresh owner state'
    Assert-Contains $Daemon 'LAST_REQUEST_RECEIPT=$CONTROL_DIR/last_request_receipt' 'durable request receipt'
    Assert-Contains $Daemon 'LAST_COMPLETED_REQUEST_ID=' 'V20.1 terminal request dedup state'
    Assert-Contains $Daemon 'publish_pending_terminal_ack()' 'V20.1 one-shot terminal ACK recovery'
    Assert-Contains $Daemon 'ACTIVE_REQUEST_CLAIM=$CONTROL_DIR/active_request_claim' 'pre-action durable claim'
    Assert-Contains $Daemon 'indeterminate_after_claim' 'at-most-once ambiguous-window recovery'
    Assert-Contains $Daemon 'oneshot_request()' 'one-request command entry point'
    Assert-Contains $Daemon '--oneshot-request) shift; oneshot_request "${1:-}" "${2:-}"' 'authenticated one-request argument dispatch'
    Assert-Contains $Daemon '[ "$(id -u 2>/dev/null)" = "0" ] || return 126' 'non-root direct invocation rejection'
    Assert-Contains $Daemon 'captured_request="$(settings_get_clean "$REQ_TEXT_KEY")"' 'single request capture'
    Assert-Contains $Daemon 'captured_sha256="$(request_sha256 "$captured_request")"' 'captured request digest binding'
    Assert-Contains $Daemon 'init_request_state "$captured_request"' 'single-capture recovery input'
    Assert-Contains $Daemon 'process_settings_request "$captured_request"' 'single-capture transaction input'
    Assert-Contains $Daemon 'atomic_write_text "$ACTIVE_REQUEST_CLAIM" "$1" 0600' 'root-private request claim'
    Assert-Contains $Daemon '$terminal_ack" 0600 || return 1' 'root-private terminal receipt'
    Assert-Contains $Daemon 'chmod 0755 "$DATA_ROOT"' 'root-owned transaction parent repair'
    Assert-Contains $Daemon 'atomic_mode="${3:-0644}"' 'non-sensitive atomic file default DAC'
    foreach ($timingMarker in @(
        'timing_mark "${trusted_id:-invalid}" T4 unknown',
        'timing_mark "$id" T5 "$cmd"',
        'timing_mark "$id" T6 "$cmd"',
        'timing_mark "$id" T7 "$cmd"',
        'timing_mark "$timing_ack_id" T8 "$timing_ack_cmd"'
    )) { Assert-Contains $Daemon $timingMarker 'command latency timing marker' }
    $processRequest = Get-ShellFunctionBlock $Daemon 'process_settings_request'
    Assert-OrderedText $processRequest @(
        'persist_request_claim "$request"',
        'timing_mark "$id" T5 "$cmd"',
        'handle_command "$cmd" "$pkg" "$mode"',
        'timing_mark "$id" T6 "$cmd"',
        'finish_request "$request" "$id" "$cmd" "$result"'
    ) 'claim/T5/action/T6/completion transaction order'
    $finishRequest = Get-ShellFunctionBlock $Daemon 'finish_request'
    Assert-OrderedText $finishRequest @(
        'persist_completion "$request" "$terminal_ack"',
        'timing_mark "$id" T7 "$cmd"',
        'clear_request_claim "$request"',
        'publish_pending_terminal_ack'
    ) 'receipt/T7/claim-clear/terminal-ACK order'
    $publishAck = Get-ShellFunctionBlock $Daemon 'publish_pending_terminal_ack'
    Assert-OrderedText $publishAck @(
        'settings_put_quiet "$REQUEST_ACK_KEY" "$replay_ack"',
        'timing_mark "$timing_ack_id" T8'
    ) 'terminal-ACK/T8 order'
    $oneshotRequest = Get-ShellFunctionBlock $Daemon 'oneshot_request'
    Assert-OrderedText $oneshotRequest @(
        'timing_mark "${trusted_id:-invalid}" T4 unknown',
        'captured_request="$(settings_get_clean "$REQ_TEXT_KEY")"',
        'process_settings_request "$captured_request"'
    ) 'T4/request-capture/dispatch order'
    Assert-Contains $Daemon '") exit 2 ;;' 'argument-less persistent daemon rejection'

    foreach ($config in @($ZuippPower, $MemCleaner, $PowerPolicy, $AutoRun)) {
        Assert-NotContains $config 'com.zui.zuicontrol' 'retired ZuiControl keepalive whitelist'
        Assert-NotContains $config 'com.zui.zuiperfctl' 'retired legacy keepalive whitelist'
    }
    Assert-Contains $PrivPermissions 'android.permission.WRITE_SECURE_SETTINGS' 'Dolby privileged permission'
    Assert-NotContains $PrivPermissions 'com.zui.performance.permission.gamemode' 'retired P2 GameMode permission'

    $ServicesJar = Join-Path $System 'framework\services.jar'
    $ServicesDecode = Join-Path $WorkDir 'services_decode'
    Require-File $ServicesJar
    $java = Get-Command 'java.exe' -ErrorAction SilentlyContinue
    if (-not $java) { $java = Get-Command 'java' -ErrorAction SilentlyContinue }
    if (-not $java) { throw 'Missing Java' }
    Invoke-Checked $java.Source '-jar' $Apktool 'd' '-f' '-o' $ServicesDecode $ServicesJar
    $serviceSmali = @(Get-ChildItem -LiteralPath $ServicesDecode -Recurse -File -Filter 'ZuiControlService.smali')
    if ($serviceSmali.Count -ne 1) { throw "Expected one ZuiControlService.smali, found $($serviceSmali.Count)" }
    $uperfPolicySmali = @(Get-ChildItem -LiteralPath $ServicesDecode -Recurse -File -Filter 'ZuiControlService$UperfScenePolicy.smali')
    if ($uperfPolicySmali.Count -ne 1) {
        throw "Expected one ZuiControlService`$UperfScenePolicy.smali, found $($uperfPolicySmali.Count)"
    }
    Assert-Contains $serviceSmali[0].FullName 'Landroid/os/HandlerThread;' 'asynchronous focus worker'
    Assert-Contains $serviceSmali[0].FullName 'forRenderFrameRates' 'adaptive render refresh vote'
    Assert-Contains $serviceSmali[0].FullName 'displayVote=adaptiveRender' 'adaptive render state marker'
    Assert-Contains $serviceSmali[0].FullName 'zui_control_screen_on' 'system_server screen-state publication'
    Assert-Contains $serviceSmali[0].FullName 'registerScreenObserver' 'system_server screen observer'
    Assert-Contains $serviceSmali[0].FullName 'setModuleEnabled' 'authenticated direct refresh kill transaction'
    Assert-Contains $serviceSmali[0].FullName 'unsupported_module' 'exact refresh module gate'
    Assert-Contains $serviceSmali[0].FullName 'refresh_property_set' 'persistent refresh kill write diagnostics'
    Assert-Contains $serviceSmali[0].FullName 'emptyFocusPolicy=retainLastNonEmptyWindow' 'empty window retain policy'
    Assert-Contains $serviceSmali[0].FullName 'latestWindowFocusEmpty=' 'empty window live state'
    Assert-Contains $serviceSmali[0].FullName 'emptyFocusTransitionCount=' 'empty window transition counter'
    Assert-Contains $serviceSmali[0].FullName 'com.lenovo.screensplit' 'Lenovo split selector exact transient'
    Assert-Contains $serviceSmali[0].FullName 'com.zui.freeform.sidebar' 'ZUI freeform sidebar exact transient'
    Assert-Contains $serviceSmali[0].FullName 'sys.zui_control.uperf_mode' 'event-driven Uperf transport property'
    Assert-Contains $serviceSmali[0].FullName 'sys.zui_control.command_seq' 'event-triggered command transport property'
    Assert-Contains $serviceSmali[0].FullName 'sys.zui_control.command_id' 'authenticated request ID property'
    Assert-Contains $serviceSmali[0].FullName 'sys.zui_control.command_sha256' 'authenticated request digest property'
    Assert-Contains $serviceSmali[0].FullName 'notifyControlRequest' 'authenticated command doorbell method'
    Assert-Contains $serviceSmali[0].FullName 'enforceCommandCallerAllowed' 'strict command doorbell authorization'
    Assert-Contains $serviceSmali[0].FullName 'enforceZuiControlCaller' 'package and certificate command authorization'
    Assert-Contains $serviceSmali[0].FullName 'control_request_kick' 'command doorbell observability'
    Assert-Contains $serviceSmali[0].FullName 'request_payload_mismatch' 'Binder payload digest rejection'
    Assert-Contains $serviceSmali[0].FullName 'SHA-256' 'Binder payload digest implementation'
    Assert-Contains $serviceSmali[0].FullName 'ZuiControlTiming' 'system_server command latency tag'
    Assert-Contains $serviceSmali[0].FullName 'phase=T1' 'Binder-entry latency marker'
    Assert-Contains $serviceSmali[0].FullName 'phase=T2' 'init-doorbell latency marker'
    foreach ($stateMarker in @(
        'sys.zui_control.scheduler_active',
        'init.svc.zui_uperf',
        'init.svc.zui_asoulopt',
        'schedulerActive=',
        'uperfServiceState=',
        'asoulServiceState=',
        'schedulerHealth=',
        'lastSchedulerError=',
        'daemonRetired=true'
    )) { Assert-Contains $serviceSmali[0].FullName $stateMarker 'on-demand scheduler health field' }
    $schedulerHealthMethod = (Get-SmaliMethodBlock $serviceSmali[0].FullName 'schedulerHealthStateLines') -join "`n"
    if (-not $schedulerHealthMethod.Contains('Landroid/os/SystemProperties;->get')) {
        throw 'On-demand scheduler health does not read init/system properties directly.'
    }
    foreach ($forbiddenHealthMechanism in @('Ljava/lang/Runtime;', 'Ljava/lang/ProcessBuilder;',
            'Ljava/io/File;', 'Ljava/lang/Thread;->sleep', 'Landroid/provider/Settings')) {
        if ($schedulerHealthMethod.Contains($forbiddenHealthMechanism)) {
            throw "On-demand scheduler health unexpectedly uses a shell/file/polling mechanism: $forbiddenHealthMechanism"
        }
    }
    $transactMethod = (Get-SmaliMethodBlock $serviceSmali[0].FullName 'onTransact') -join "`n"
    $strictAuthNeedle = '->enforceCommandCallerAllowed()V'
    if (([regex]::Matches($transactMethod, [regex]::Escape($strictAuthNeedle))).Count -ne 2) {
        throw 'Final services.jar must contain exactly two strict-auth dispatches (TX10 and TX12).'
    }
    Assert-SmaliDispatchAuthorized $transactMethod $strictAuthNeedle `
        '->setModuleEnabled(Ljava/lang/String;Z)Ljava/lang/String;' 'TX10'
    Assert-SmaliDispatchAuthorized $transactMethod $strictAuthNeedle `
        '->notifyControlRequest(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;' 'TX12'
    $moduleMethod = (Get-SmaliMethodBlock $serviceSmali[0].FullName 'setModuleEnabled') -join "`n"
    Assert-OrderedText $moduleMethod @(
        'Landroid/os/SystemProperties;->set',
        '->readRefreshDisableMask()I',
        '->onRefreshPropertiesChanged(I)V'
    ) 'TX10 persistent write before direct state transition'
    $commandAuthMethod = (Get-SmaliMethodBlock $serviceSmali[0].FullName 'enforceCommandCallerAllowed') -join "`n"
    Assert-OrderedText $commandAuthMethod @(
        'Landroid/os/Binder;->getCallingUid()I',
        '->enforceZuiControlCaller(I)V'
    ) 'sensitive Binder package/certificate authorization'
    if ($commandAuthMethod.Contains('Landroid/os/Process;') -or $commandAuthMethod.Contains('SYSTEM_UID')) {
        throw 'Sensitive Binder authorization contains a SYSTEM_UID bypass.'
    }
    $notifyMethod = (Get-SmaliMethodBlock $serviceSmali[0].FullName 'notifyControlRequest') -join "`n"
    foreach ($marker in @('Landroid/provider/Settings$System;->getString', 'request_payload_mismatch', '->sha256')) {
        if (-not $notifyMethod.Contains($marker)) { throw "TX12 notify method is missing: $marker" }
    }
    Assert-OrderedText $notifyMethod @(
        'phase=T1',
        'sys.zui_control.command_id',
        'sys.zui_control.command_sha256',
        'sys.zui_control.command_seq',
        'phase=T2'
    ) 'T1/authenticated property commit/T2 order'
    Assert-Contains $serviceSmali[0].FullName 'zui_control_uperf_mode' 'cached global Uperf setting'
    Assert-Contains $serviceSmali[0].FullName 'zui_control_uperf_rules_text' 'cached exact-app Uperf setting'
    Assert-Contains $uperfPolicySmali[0].FullName 'Landroid/os/SystemProperties;->set' 'system_server property actuator'
    foreach ($field in @(
        'uperfGlobalMode=',
        'uperfSceneMode=',
        'uperfDesiredMode=',
        'uperfLastAppliedMode=',
        'uperfApplyCount=',
        'uperfLastReason='
    )) { Assert-Contains $uperfPolicySmali[0].FullName $field 'Uperf dumpsys observability field' }
    Assert-NotContains $serviceSmali[0].FullName 'forPhysicalRefreshRates' 'unsafe physical refresh vote'

    $FrameworkJar = Join-Path $System 'framework\framework.jar'
    $FrameworkDecode = Join-Path $WorkDir 'framework_decode'
    Require-File $FrameworkJar
    Invoke-Checked $java.Source '-jar' $Apktool 'd' '-f' '-o' $FrameworkDecode $FrameworkJar
    $managerSmali = @(Get-ChildItem -LiteralPath $FrameworkDecode -Recurse -File -Filter 'ZuiControlManager.smali')
    if ($managerSmali.Count -ne 1) { throw "Expected one ZuiControlManager.smali, found $($managerSmali.Count)" }
    Assert-Contains $managerSmali[0].FullName 'notifyControlRequest' 'framework command doorbell API'
    Assert-Contains $managerSmali[0].FullName '0xc' 'framework command doorbell transaction 12'

    $AppDecode = Join-Path $WorkDir 'app_decode'
    Invoke-Checked $java.Source '-jar' $Apktool 'd' '-f' '-o' $AppDecode $AppApk
    $requestSmali = @(Get-ChildItem -LiteralPath $AppDecode -Recurse -File -Filter 'ZuiControlRequest.smali')
    $clientSmali = @(Get-ChildItem -LiteralPath $AppDecode -Recurse -File -Filter 'ZuiControlClient.smali')
    $bootSmali = @(Get-ChildItem -LiteralPath $AppDecode -Recurse -File -Filter 'BootReceiver.smali')
    $mainActivitySmali = @(Get-ChildItem -LiteralPath $AppDecode -Recurse -File -Filter 'MainActivity*.smali')
    if ($requestSmali.Count -ne 1 -or $clientSmali.Count -ne 1 -or $bootSmali.Count -ne 1 -or
        $mainActivitySmali.Count -lt 1) {
        throw 'Final APK command client classes are missing'
    }
    Assert-Contains $requestSmali[0].FullName 'kickPending' 'App launch pending-command reconciliation'
    Assert-Contains $requestSmali[0].FullName 'recoverPending' 'same-session pending-command completion'
    Assert-Contains $requestSmali[0].FullName 'retryDelayMs' 'App bounded command re-kick backoff'
    Assert-Contains $requestSmali[0].FullName 'zui_control_pending_command' 'App private trusted request record'
    Assert-Contains $requestSmali[0].FullName 'createDeviceProtectedStorageContext' 'direct-boot trusted request storage'
    Assert-Contains $requestSmali[0].FullName 'SHA-256' 'App exact request digest'
    Assert-Contains $requestSmali[0].FullName 'ZuiControlTiming' 'App command latency tag'
    Assert-Contains $requestSmali[0].FullName 'phase=T0' 'App durable-pending latency marker'
    Assert-Contains $requestSmali[0].FullName 'phase=T9' 'App terminal-observed latency marker'
    Assert-Contains $clientSmali[0].FullName 'notifyControlRequest' 'App Binder command doorbell call'
    Assert-Contains $bootSmali[0].FullName 'kickPending' 'boot pending-command reconciliation'
    if (-not (Select-String -LiteralPath @($mainActivitySmali.FullName) -SimpleMatch -Pattern 'recoverPending' -Quiet)) {
        throw 'Final APK MainActivity does not perform first cold-launch same-session recovery.'
    }
    $sendMethod = (Get-SmaliMethodBlock $requestSmali[0].FullName 'send') -join "`n"
    Assert-OrderedText $sendMethod @(
        '->savePending',
        'phase=T0',
        'Landroid/provider/Settings$System;->putString',
        'Lcom/zui/zuicontrol/ZuiControlClient;->notifyControlRequest'
    ) 'App private-pending/T0/request/Binder order'
    $kickMethod = (Get-SmaliMethodBlock $requestSmali[0].FullName 'kickTrusted') -join "`n"
    Assert-OrderedText $kickMethod @(
        'Landroid/provider/Settings$System;->getString',
        'Landroid/provider/Settings$System;->putString',
        'Lcom/zui/zuicontrol/ZuiControlClient;->notifyControlRequest'
    ) 'App trusted request restore before re-kick'
    $awaitMethod = (Get-SmaliMethodBlock $requestSmali[0].FullName 'awaitTerminalAck') -join "`n"
    foreach ($marker in @('->loadPending', '->retryDelayMs', '->kickTrusted',
            'Ljava/lang/Thread;->sleep', '->clearPending')) {
        if (-not $awaitMethod.Contains($marker)) { throw "App bounded ACK/retry method is missing: $marker" }
    }
    Assert-OrderedText $awaitMethod @(
        'phase=T9',
        '->clearPending'
    ) 'terminal-observed T9 before pending clear'
    $recoverMethod = (Get-SmaliMethodBlock $requestSmali[0].FullName 'recoverPending') -join "`n"
    Assert-OrderedText $recoverMethod @(
        '->kickPending',
        '->awaitTerminalAck'
    ) 'same-session pending command recovery'
    $appManifest = Join-Path $AppDecode 'AndroidManifest.xml'
    Assert-Contains $appManifest 'android:name="com.zui.zuicontrol.BootReceiver"' 'boot receiver manifest declaration'
    Assert-Contains $appManifest 'android:directBootAware="true"' 'direct-boot receiver/application declaration'
    Assert-Contains $appManifest 'android.intent.action.LOCKED_BOOT_COMPLETED' 'locked-boot pending reconciliation'
    Assert-Contains $appManifest 'android.intent.action.BOOT_COMPLETED' 'boot pending reconciliation'
    Assert-Contains $bootSmali[0].FullName '->goAsync()Landroid/content/BroadcastReceiver$PendingResult;' 'bounded async boot work'
    Assert-Contains $bootSmali[0].FullName 'Landroid/content/BroadcastReceiver$PendingResult;->finish' 'boot async completion'

    $FileContexts = Join-Path $PlatSelinux 'plat_file_contexts'
    Assert-Contains $FileContexts '/system/bin/uperf u:object_r:performanced_exec:s0' 'Uperf file context'
    Assert-Contains $FileContexts '/system/bin/zui_uperf_service u:object_r:performanced_exec:s0' 'Uperf supervisor file context'
    Assert-Contains $FileContexts '/system/bin/zui_uperf_supervisor u:object_r:performanced_exec:s0' 'native Uperf subreaper file context'
    Assert-Contains $SystemImageContexts '/system_a/system/bin/zui_uperf_supervisor u:object_r:performanced_exec:s0' 'final EROFS native Uperf subreaper inode context'
    Assert-Contains $FileContexts '/system/bin/AsoulOpt u:object_r:performanced_exec:s0' 'A-SOUL file context'
    Assert-Contains $FileContexts '/system/bin/dumpsys u:object_r:toolbox_exec:s0' 'bounded A-SOUL dumpsys execution context'
    Assert-Contains $SystemImageContexts '/system_a/system/bin/dumpsys u:object_r:toolbox_exec:s0' 'final EROFS dumpsys inode context'
    Assert-Contains $FileContexts '/data/vendor/asopt\.conf u:object_r:zui_control_data_file:s0' 'A-SOUL compatibility data context'
    Assert-Contains $FileContexts '/data/vendor/zui_control(/.*)? u:object_r:zui_control_data_file:s0' 'scheduler data context'
    Assert-NotContains $FileContexts '/data/adb/naki' 'retired Magisk data context'
    Assert-NotContains $FileContexts '/data/vendor/zui_control/zuipp/active/game_policy\.xml' 'retired ZuiPP game XML context'
    Assert-NotContains $FileContexts '/data/vendor/zui_control/zuipp/active/performanceconfig\.xml' 'retired ZuiPP performance XML context'
    Assert-NotContains $FileContexts '/system/bin/AppOpt ' 'retired AppOpt file context'
    Assert-Contains (Join-Path $PlatSelinux 'plat_service_contexts') 'zui_control                               u:object_r:zui_control_service:s0' 'zui_control service context'

    $PropertyContexts = Join-Path $PlatSelinux 'plat_property_contexts'
    Assert-Contains $PropertyContexts 'sys.zui_control.uperf_mode u:object_r:zui_control_uperf_mode_prop:s0 exact enum powersave balance performance fast' 'dedicated exact-enum Uperf property context'
    Assert-Contains $PropertyContexts 'sys.zui_control.uperf_fail_safe u:object_r:zui_control_uperf_fail_safe_prop:s0 exact bool' 'dedicated exact Uperf fail-safe property context'
    Assert-NotContains $PropertyContexts 'sys.zui_control.uperf_mode u:object_r:shell_prop:s0' 'shell-owned Uperf transport property'
    Assert-Contains $PropertyContexts 'sys.zui_control.command_seq u:object_r:zui_control_command_seq_prop:s0 exact string' 'dedicated exact command doorbell property context'
    Assert-NotContains $PropertyContexts 'sys.zui_control.command_seq u:object_r:shell_prop:s0' 'shell-owned command doorbell property'
    Assert-Contains $PropertyContexts 'sys.zui_control.command_id u:object_r:zui_control_command_auth_prop:s0 exact string' 'dedicated exact command ID property context'
    Assert-Contains $PropertyContexts 'sys.zui_control.command_sha256 u:object_r:zui_control_command_auth_prop:s0 exact string' 'dedicated exact command digest property context'
    Assert-NotContains $PropertyContexts 'sys.zui_control.command_id u:object_r:shell_prop:s0' 'shell-owned command ID property'
    Assert-NotContains $PropertyContexts 'sys.zui_control.command_sha256 u:object_r:shell_prop:s0' 'shell-owned command digest property'
    Assert-Contains $PropertyContexts 'sys.zui_control.scheduler_active u:object_r:zui_control_scheduler_active_prop:s0 exact enum 0 1' 'dedicated exact-enum scheduler ownership property context'
    Assert-NotContains $PropertyContexts 'sys.zui_control.scheduler_active u:object_r:shell_prop:s0' 'shell-owned scheduler ownership property'
    Assert-Contains $PropertyContexts 'persist.zui_control.disable u:object_r:zui_control_refresh_disable_prop:s0 exact bool' 'dedicated global disable property context'
    Assert-Contains $PropertyContexts 'persist.zui_control.refresh.disable u:object_r:zui_control_refresh_disable_prop:s0 exact bool' 'dedicated refresh disable property context'

    $PlatPolicy = Join-Path $PlatSelinux 'plat_sepolicy.cil'
    foreach ($rule in @(
        '(type zui_control_uperf_mode_prop)',
        '(roletype object_r zui_control_uperf_mode_prop)',
        '(typeattributeset property_type (zui_control_uperf_mode_prop))',
        '(typeattributeset system_property_type (zui_control_uperf_mode_prop))',
        '(typeattributeset system_internal_property_type (zui_control_uperf_mode_prop))',
        '(allow system_server zui_control_uperf_mode_prop (property_service (set)))',
        '(allow system_server zui_control_uperf_mode_prop (file (getattr map open read)))',
        '(type zui_control_uperf_fail_safe_prop)',
        '(roletype object_r zui_control_uperf_fail_safe_prop)',
        '(typeattributeset property_type (zui_control_uperf_fail_safe_prop))',
        '(typeattributeset system_property_type (zui_control_uperf_fail_safe_prop))',
        '(typeattributeset system_internal_property_type (zui_control_uperf_fail_safe_prop))',
        '(allow init zui_control_uperf_fail_safe_prop (property_service (set)))',
        '(allow shell zui_control_uperf_fail_safe_prop (property_service (set)))',
        '(allow performanced zui_control_uperf_fail_safe_prop (property_service (set)))',
        '(allow system_server zui_control_uperf_fail_safe_prop (file (getattr map open read)))',
        '(type zui_control_command_seq_prop)',
        '(roletype object_r zui_control_command_seq_prop)',
        '(typeattributeset property_type (zui_control_command_seq_prop))',
        '(typeattributeset system_property_type (zui_control_command_seq_prop))',
        '(typeattributeset system_internal_property_type (zui_control_command_seq_prop))',
        '(allow system_server zui_control_command_seq_prop (property_service (set)))',
        '(allow system_server zui_control_command_seq_prop (file (getattr map open read)))',
        '(type zui_control_command_auth_prop)',
        '(roletype object_r zui_control_command_auth_prop)',
        '(typeattributeset property_type (zui_control_command_auth_prop))',
        '(typeattributeset system_property_type (zui_control_command_auth_prop))',
        '(typeattributeset system_internal_property_type (zui_control_command_auth_prop))',
        '(allow system_server zui_control_command_auth_prop (property_service (set)))',
        '(allow system_server zui_control_command_auth_prop (file (getattr map open read)))',
        '(type zui_control_scheduler_active_prop)',
        '(roletype object_r zui_control_scheduler_active_prop)',
        '(typeattributeset property_type (zui_control_scheduler_active_prop))',
        '(typeattributeset system_property_type (zui_control_scheduler_active_prop))',
        '(typeattributeset system_internal_property_type (zui_control_scheduler_active_prop))',
        '(allow init zui_control_scheduler_active_prop (property_service (set)))',
        '(allow system_server zui_control_scheduler_active_prop (file (getattr map open read)))',
        '(type zui_control_refresh_disable_prop)',
        '(roletype object_r zui_control_refresh_disable_prop)',
        '(typeattributeset property_type (zui_control_refresh_disable_prop))',
        '(typeattributeset system_property_type (zui_control_refresh_disable_prop))',
        '(typeattributeset system_restricted_property_type (zui_control_refresh_disable_prop))',
        '(allow system_server zui_control_refresh_disable_prop (property_service (set)))',
        '(allow system_server zui_control_refresh_disable_prop (file (getattr map open read)))',
        '(allow shell zui_control_refresh_disable_prop (property_service (set)))',
        '(allow shell zui_control_refresh_disable_prop (file (getattr map open read)))',
        '(genfscon proc "/sys/walt/input_boost" (u object_r zui_scheduler_proc ((s0) (s0))))',
        '(genfscon proc "/sys/walt/sched_per_task_boost" (u object_r zui_scheduler_proc ((s0) (s0))))',
        '(allow performanced activity_service (service_manager (find)))',
        '(allow system_server performanced (fd (use)))',
        '(allow system_server performanced (binder (call)))',
        '(allow performanced self (capability (chown dac_override fowner kill)))',
        '(allow performanced self (file (getattr open read)))',
        '(allow performanced appdomain (dir (getattr open read search)))',
        '(allow performanced appdomain (file (getattr open read)))',
        '(allow performanced appdomain (process (getsched setsched signull)))',
        '(allow performanced proc_uptime (file (getattr open read)))',
        '(allow performanced sysfs_devices_system_cpu (file (getattr open read write append setattr)))',
        '(allow performanced cgroup (file (ioctl read write create getattr setattr lock append map open unlink)))',
        '(allow performanced cgroup_v2 (dir (getattr open read search)))',
        '(allow performanced cgroup_v2 (file (getattr open read)))',
        '(allow performanced zui_scheduler_proc (file (getattr open read write append setattr)))',
        '(allow performanced input_device (dir (ioctl read getattr lock open watch watch_reads search)))',
        '(allow performanced input_device (chr_file (ioctl read getattr lock map open)))',
        '(allow performanced toolbox_exec (file (read getattr map execute open execute_no_trans)))',
        '(allow performanced zui_control_data_file (file (getattr open read write create append map watch watch_reads setattr unlink rename)))',
        '(allow performanced zui_control_data_file (lnk_file (getattr read)))'
    )) { Assert-Contains $PlatPolicy $rule 'scheduler SELinux rule' }
    Assert-NotContains $PlatPolicy '(allow system_server shell_prop (property_service (set)))' 'broad system_server shell property write grant'
    foreach ($forbiddenWriter in @('priv_app', 'untrusted_app')) {
        Assert-NotContains $PlatPolicy "(allow $forbiddenWriter zui_control_refresh_disable_prop (property_service (set)))" 'unauthorized refresh disable property writer'
    }
    foreach ($retiredRule in @(
        '(allow performanced adb_data_file (dir (search)))'
    )) { Assert-NotContains $PlatPolicy $retiredRule 'retired scheduler SELinux rule' }
    foreach ($retiredProcAccess in @(
        '(allow performanced system_server (dir ',
        '(allow performanced system_server (file ',
        '(allow performanced system_server (lnk_file ',
        '(allow performanced system_server (process '
    )) {
        Assert-NotContains $PlatPolicy $retiredProcAccess 'retired Uperf top-app proc access'
    }
    foreach ($forbiddenWriter in @('shell', 'priv_app', 'untrusted_app')) {
        Assert-NotContains $PlatPolicy "(allow $forbiddenWriter zui_control_uperf_mode_prop (property_service (set)))" 'unauthorized Uperf property writer'
        Assert-NotContains $PlatPolicy "(allow $forbiddenWriter zui_control_command_seq_prop (property_service (set)))" 'unauthorized command property writer'
        Assert-NotContains $PlatPolicy "(allow $forbiddenWriter zui_control_command_auth_prop (property_service (set)))" 'unauthorized command authentication writer'
        Assert-NotContains $PlatPolicy "(allow $forbiddenWriter zui_control_scheduler_active_prop (property_service (set)))" 'unauthorized scheduler ownership writer'
    }
    Assert-NotContains $PlatPolicy '(allow system_server zui_control_scheduler_active_prop (property_service (set)))' 'system_server scheduler ownership writer'
    $schedulerWriterMatches = [regex]::Matches(
        (Get-Content -Raw -LiteralPath $PlatPolicy),
        '\(allow\s+([^\s()]+)\s+zui_control_scheduler_active_prop\s+\(property_service\s+\(set\)\)\)')
    if ($schedulerWriterMatches.Count -ne 1 -or
        $schedulerWriterMatches[0].Groups[1].Value -ne 'init') {
        throw 'sys.zui_control.scheduler_active must have init as its only SELinux writer.'
    }

    $MappingPolicy = Join-Path $PlatSelinux 'mapping\34.0.cil'
    $PlatMappingHash = Join-Path $PlatSelinux 'plat_sepolicy_and_mapping.sha256'
    $expectedPlatMappingHash = (Get-Content -Raw -LiteralPath $PlatMappingHash).Trim().ToLowerInvariant()
    $actualPlatMappingHash = Files-ConcatenatedSha256 @($PlatPolicy, $MappingPolicy)
    if ($actualPlatMappingHash -ne $expectedPlatMappingHash) {
        throw "Final plat sepolicy/mapping hash mismatch: $actualPlatMappingHash != $expectedPlatMappingHash"
    }

    $VendorPolicy = Join-Path $VendorSelinux 'vendor_sepolicy.cil'
    Assert-Contains $VendorPolicy '(allow performanced_34_0 vendor_sysfs_msm_perf (file (ioctl read write getattr setattr lock append map open)))' 'Uperf msm_performance vendor rule'
    Assert-NotContains $VendorPolicy 'zui_control_uperf_mode_prop' 'vendor access to system-internal Uperf property'
    Assert-NotContains $VendorPolicy 'zui_control_command_seq_prop' 'vendor access to system-internal command property'
    Assert-NotContains $VendorPolicy 'zui_control_command_auth_prop' 'vendor access to system-internal command authentication property'
    Assert-NotContains $VendorPolicy 'zui_control_scheduler_active_prop' 'vendor access to system-internal scheduler ownership property'
    Assert-NotContains $VendorPolicy '(allow shell_34_0 vendor_sysfs_kgsl (' 'legacy shell KGSL permission'
    Assert-NotContains $VendorPolicy '(allow performanced_34_0 vendor_sysfs_kgsl (' 'unsupported Uperf KGSL permission'

    $UperfAccessReport = Join-Path $WorkDir 'uperf_runtime_access_graph.json'
    Invoke-Checked $Python (Join-Path $RepoRoot 'scripts\verify\VerifyUperfRuntimeAccess.py') `
        --mode final `
        --system-root $System `
        --file-contexts $FileContexts `
        --property-contexts $PropertyContexts `
        --plat-policy $PlatPolicy `
        --vendor-policy $VendorPolicy `
        --report $UperfAccessReport
    Require-File $UperfAccessReport

    $hashes = [ordered]@{
        boot = File-Sha256 $Boot
        super = File-Sha256 $Super
        vbmeta = File-Sha256 $Vbmeta
        vbmeta_system = File-Sha256 $VbmetaSystem
        apk = File-Sha256 $AppApk
        sidecar_apk = File-Sha256 $SidecarApk
        release_sidecar_apk = File-Sha256 $ReleaseSidecarApk
        uperf = File-Sha256 $Uperf
        uperf_supervisor = File-Sha256 $UperfSupervisor
        asoul = File-Sha256 $Asoul
    }
    if ($hashes.apk -ne $hashes.sidecar_apk -or $hashes.apk -ne $hashes.release_sidecar_apk) {
        throw 'Embedded and sidecar APK hashes differ'
    }
    Assert-Apk $AppApk 'embedded APK'
    Assert-Apk $SidecarApk 'system sidecar APK'
    Assert-Apk $ReleaseSidecarApk 'release sidecar APK'
    $ok = $true
    [pscustomobject]@{
        ok = $true
        flash_dir = $FlashDir
        apk_sha256 = $hashes.apk
        uperf_sha256 = $hashes.uperf
        asoul_sha256 = $hashes.asoul
        boot_sha256 = $hashes.boot
        super_sha256 = $hashes.super
        vbmeta_sha256 = $hashes.vbmeta
        vbmeta_system_sha256 = $hashes.vbmeta_system
        vendor_image_sha256 = $VendorImageSha256
        vendor_apk_count = $VendorApkCount
        vendor_apk_inventory_sha256 = $VendorApkInventorySha256
        uperf_runtime_access_graph = 'PASS'
        boot_rollback_index = $BootRollbackIndex
        vbmeta_system_rollback_index = $VbmetaSystemRollbackIndex
        scheduler_rc_mode_lines = $schedulerRcModeLines
    } | ConvertTo-Json -Depth 4
} finally {
    if (-not $KeepWork -and (Test-Path -LiteralPath $WorkDir)) {
        Remove-VerificationWork
    } elseif (-not $ok) {
        Write-Warning "Verification workspace kept for debugging: $WorkDir"
    }
}
