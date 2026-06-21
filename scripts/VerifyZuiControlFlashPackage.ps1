param(
    [string]$FlashDir = "",
    [string]$WorkDir = "",
    [switch]$KeepWork
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$WorkspaceRoot = Split-Path -Parent $RepoRoot
if (-not $FlashDir) {
    $FlashDir = Join-Path $WorkspaceRoot '【B刷机】187'
}
if (-not $WorkDir) {
    $WorkDir = Join-Path $WorkspaceRoot 'work\verify_flash_zui_control'
}

$ToolsDir = Join-Path $WorkspaceRoot 'tools'
$AndroidSdkDir = Join-Path $WorkspaceRoot 'work\android-sdk'
$Python = Join-Path $ToolsDir 'python-3.8.0\python.exe'
$LpUnpack = Join-Path $ToolsDir 'lpunpack.py'
$ExtractErofs = Join-Path $ToolsDir 'AMD64\extract.erofs.exe'
$ReleaseCertSha256 = '3fecf3a72ca0e0f24991d49e7306ef4a711711f48a66070755eb0237ecb3ed94'
$ExpectedVersionCode = '27'
$ExpectedVersionName = '0.19.8'

function Require-File([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing file: $Path"
    }
}

function Invoke-Checked(
    [string]$Exe,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Args
) {
    & $Exe @Args
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed: $Exe $($Args -join ' ')"
    }
}

function Assert-Contains([string]$Path, [string]$Needle, [string]$Label) {
    Require-File $Path
    $match = Select-String -LiteralPath $Path -SimpleMatch -Pattern $Needle -Quiet
    if (-not $match) {
        throw "Missing $Label in $Path`: $Needle"
    }
}

function Assert-NotContains([string]$Path, [string]$Needle, [string]$Label) {
    Require-File $Path
    $match = Select-String -LiteralPath $Path -SimpleMatch -Pattern $Needle -Quiet
    if ($match) {
        throw "Unexpected $Label in $Path`: $Needle"
    }
}

function File-Sha256([string]$Path) {
    Require-File $Path
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Find-ApkSigner {
    $cmd = Get-Command 'apksigner.bat' -ErrorAction SilentlyContinue
    if (-not $cmd) {
        $cmd = Get-Command 'apksigner' -ErrorAction SilentlyContinue
    }
    if (-not $cmd) {
        $cmd = Get-ChildItem -LiteralPath (Join-Path $AndroidSdkDir 'build-tools') -Recurse -Filter 'apksigner.bat' -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending |
            Select-Object -First 1
    }
    if (-not $cmd) {
        throw 'Missing apksigner in PATH'
    }
    if ($cmd.Source) { return $cmd.Source }
    return $cmd.FullName
}

function Find-Aapt2 {
    $cmd = Get-Command 'aapt2.exe' -ErrorAction SilentlyContinue
    if (-not $cmd) {
        $cmd = Get-Command 'aapt2' -ErrorAction SilentlyContinue
    }
    if (-not $cmd) {
        $cmd = Get-ChildItem -LiteralPath (Join-Path $AndroidSdkDir 'build-tools') -Recurse -Filter 'aapt2.exe' -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending |
            Select-Object -First 1
    }
    if (-not $cmd) {
        throw 'Missing aapt2 in PATH'
    }
    if ($cmd.Source) { return $cmd.Source }
    return $cmd.FullName
}

function Assert-ApkReleaseCert([string]$ApkPath, [string]$Label) {
    $apkSigner = Find-ApkSigner
    $text = & $apkSigner verify --print-certs $ApkPath | Out-String -Width 4096
    if ($LASTEXITCODE -ne 0) {
        throw "apksigner failed for $Label`: $ApkPath"
    }
    if ($text -notmatch [regex]::Escape("Signer #1 certificate SHA-256 digest: $ReleaseCertSha256")) {
        throw "$Label is not signed with the release certificate: $ApkPath"
    }
}

function Assert-ZuiControlApkMetadata([string]$ApkPath, [string]$Label) {
    $aapt2 = Find-Aapt2
    $badging = & $aapt2 dump badging $ApkPath | Out-String -Width 4096
    if ($LASTEXITCODE -ne 0) {
        throw "aapt2 badging failed for $Label`: $ApkPath"
    }
    if ($badging -notmatch "package: name='com\.zui\.zuicontrol' versionCode='$ExpectedVersionCode' versionName='$ExpectedVersionName'") {
        throw "$Label has wrong package or version: expected com.zui.zuicontrol $ExpectedVersionCode/$ExpectedVersionName"
    }
    $permissions = & $aapt2 dump permissions $ApkPath | Out-String -Width 4096
    if ($LASTEXITCODE -ne 0) {
        throw "aapt2 permissions failed for $Label`: $ApkPath"
    }
    if ($permissions -match "uses-permission: name='com\.zui\.performance\.permission\.gamemode'") {
        throw "$Label still requests stale com.zui.performance.permission.gamemode"
    }
}

function Read-XmlDocument([string]$Path) {
    Require-File $Path
    $xml = New-Object System.Xml.XmlDocument
    $xml.PreserveWhitespace = $true
    $xml.Load($Path)
    return $xml
}

function Assert-ZuiLimitXml([string]$GamePath, [string]$PerfPath) {
    $game = Read-XmlDocument $GamePath
    $perf = Read-XmlDocument $PerfPath
    $requiredTypes = @('LittleCore', 'BigCore', 'TitanCore', 'MegaCore', 'GPU')
    $typeLevels = @{}
    foreach ($typeName in $requiredTypes) {
        $type = $perf.SelectSingleNode("//GameLimitConfig/Type[@name='$typeName']")
        if ($null -eq $type) {
            throw "performanceconfig.xml is missing GameLimitConfig Type: $typeName"
        }
        $levels = New-Object 'System.Collections.Generic.HashSet[string]'
        foreach ($freq in $type.SelectNodes('./Freq')) {
            [void]$levels.Add($freq.GetAttribute('level'))
        }
        $typeLevels[$typeName] = $levels
    }
    foreach ($app in $game.SelectNodes("//AppList[@type='game']/App")) {
        $pkg = $app.GetAttribute('pkg')
        if (-not $pkg) {
            $pkg = $app.GetAttribute('name')
        }
        $limit = $app.SelectSingleNode("./Attribute[@name='LimitConfig']")
        if ($null -eq $limit) {
            throw "game_policy.xml App is missing LimitConfig: $pkg"
        }
        $text = ($limit.InnerText.Trim() -replace '\s+', ' ')
        $modes = if ($text) { $text -split ' ' } else { @() }
        if ($modes.Count -ne 3) {
            throw "LimitConfig mode count invalid for ${pkg}: $($modes.Count)"
        }
        for ($modeIndex = 0; $modeIndex -lt $modes.Count; $modeIndex++) {
            foreach ($segment in ($modes[$modeIndex] -split '\|')) {
                $parts = $segment -split ':', 2
                if ($parts.Count -ne 2 -or -not ($parts[0] -match '^-?\d+$')) {
                    throw "LimitConfig thermal segment invalid for ${pkg}: $segment"
                }
                $ids = $parts[1] -split '_'
                if ($ids.Count -ne 5) {
                    throw "LimitConfig must have Little/Big/Titan/Mega/GPU ids for ${pkg}: $segment"
                }
                for ($i = 0; $i -lt $requiredTypes.Count; $i++) {
                    $id = $ids[$i]
                    if (-not ($id -match '^-?\d+$')) {
                        throw "LimitConfig id is not numeric for ${pkg}: $segment"
                    }
                    if ($id.StartsWith('-')) {
                        continue
                    }
                    $typeName = $requiredTypes[$i]
                    if (-not $typeLevels[$typeName].Contains($id)) {
                        throw "LimitConfig references missing $typeName level $id for ${pkg}: $segment"
                    }
                }
            }
        }
    }
}

$FlashDir = (Resolve-Path -LiteralPath $FlashDir).Path
$Super = Join-Path $FlashDir 'super.img'
$Boot = Join-Path $FlashDir 'boot.img'
$Vbmeta = Join-Path $FlashDir 'vbmeta.img'
$VbmetaSystem = Join-Path $FlashDir 'vbmeta_system.img'
$SidecarApk = Join-Path $FlashDir 'ZuiControl-v19-system.apk'
$ReleaseSidecarApk = Join-Path $FlashDir 'ZuiControl-v19-release.apk'

Require-File $Python
Require-File $LpUnpack
Require-File $ExtractErofs
Require-File $Super
Require-File $Boot
Require-File $Vbmeta
Require-File $VbmetaSystem
Require-File $SidecarApk
Require-File $ReleaseSidecarApk

$ok = $false
try {
    if (Test-Path -LiteralPath $WorkDir) {
        Remove-Item -LiteralPath $WorkDir -Recurse -Force
    }
    $ImageDir = Join-Path $WorkDir 'img'
    $ExtractDir = Join-Path $WorkDir 'extract'
    New-Item -ItemType Directory -Path $ImageDir, $ExtractDir | Out-Null

    Invoke-Checked $Python $LpUnpack $Super 'ALL' $ImageDir

    foreach ($partition in @('system_a', 'vendor_a')) {
        $image = Join-Path $ImageDir "$partition.img"
        Require-File $image
        Invoke-Checked $ExtractErofs '-i' $image '-o' $ExtractDir '-x' '-f'
    }

    $SystemRoot = Join-Path $ExtractDir 'system_a'
    $VendorRoot = Join-Path $ExtractDir 'vendor_a'
    $PlatSelinux = Join-Path $SystemRoot 'system\etc\selinux'
    $VendorSelinux = Join-Path $VendorRoot 'etc\selinux'

    $AppApk = Join-Path $SystemRoot 'system\priv-app\ZuiControl\ZuiControl.apk'
    $Daemon = Join-Path $SystemRoot 'system\bin\zui_controld'
    $DaemonRc = Join-Path $SystemRoot 'system\etc\init\zui_controld.rc'
    $AsoulRc = Join-Path $SystemRoot 'system\etc\init\zui_asoulopt.rc'
    $ClearPackageCache = Join-Path $SystemRoot 'system\etc\zui_control\clear_package_cache.sh'
    $PrivAppPermissions = Join-Path $SystemRoot 'system\etc\permissions\privapp-permissions-zui-control.xml'
    $GameTemplate = Join-Path $SystemRoot 'system\etc\zui_control\default_game_policy.xml'
    $PerfTemplate = Join-Path $SystemRoot 'system\etc\zui_control\default_performanceconfig.xml'
    foreach ($required in @($AppApk, $Daemon, $DaemonRc, $AsoulRc, $ClearPackageCache, $PrivAppPermissions, $GameTemplate, $PerfTemplate)) {
        Require-File $required
    }
    Assert-Contains $DaemonRc 'clear_package_cache.sh' 'ZuiControl package cache clear action'
    Assert-Contains $ClearPackageCache 'ZuiControl-*' 'targeted ZuiControl package cache pattern'
    Assert-NotContains $PrivAppPermissions 'com.zui.performance.permission.gamemode' 'stale P2-G gamemode privileged permission'
    $daemonText = Get-Content -Raw -LiteralPath $Daemon
    if ($daemonText -like '*chcon u:object_r:system_file:s0*') {
        throw 'daemon still attempts shell-domain active XML chcon'
    }
    foreach ($legacy in @('/sys/class/kgsl/kgsl-3d0', '/sys/devices/system/cpu/cpufreq', 'zui_control_cpu_state', 'zui_control_gpu_state', 'apply_gpu_limits_for_pkg')) {
        if ($daemonText -like "*$legacy*") {
            throw "daemon still contains direct runtime performance legacy: $legacy"
        }
    }
    foreach ($staleBridge in @('APPLY_PP_MODE', 'ZuiPpModeReceiver')) {
        if ($daemonText.Contains($staleBridge)) {
            throw "daemon still contains stale P2-G PP broadcast bridge: $staleBridge"
        }
    }
    foreach ($providerDirect in @('GameModeProvider/contact', 'content update --uri "$PP_GAME_MODE_URI"', 'stage=provider_direct', 'zui_control_pp_mode_state')) {
        if (-not $daemonText.Contains($providerDirect)) {
            throw "daemon is missing P2-I direct PP provider marker: $providerDirect"
        }
    }
    Assert-Contains $Daemon 'state=triggered;stage=provider_direct' 'P2-I PP mode direct provider triggered state'
    Assert-NotContains $Daemon 'retry ZuiPP mode after non-done state' 'stale P2-G done-state retry wording'
    Assert-ZuiLimitXml $GameTemplate $PerfTemplate

    Assert-Contains (Join-Path $PlatSelinux 'plat_service_contexts') 'zui_control                               u:object_r:zui_control_service:s0' 'zui_control service_context'
    Assert-Contains (Join-Path $PlatSelinux 'plat_property_contexts') 'persist.zui_control. u:object_r:shell_prop:s0' 'persist.zui_control property_context'
    Assert-Contains (Join-Path $PlatSelinux 'plat_property_contexts') 'zui_control. u:object_r:shell_prop:s0' 'zui_control property_context'
    Assert-Contains (Join-Path $PlatSelinux 'plat_file_contexts') '/data/system/zui_control(/.*)? u:object_r:system_data_file:s0' 'data system file_context'
    Assert-Contains (Join-Path $PlatSelinux 'plat_file_contexts') '/data/vendor/zui_control/zuipp/active/game_policy\.xml u:object_r:system_file:s0' 'active game_policy file_context'
    Assert-Contains (Join-Path $PlatSelinux 'plat_file_contexts') '/data/vendor/zui_control/zuipp/active/performanceconfig\.xml u:object_r:system_file:s0' 'active performanceconfig file_context'
    Assert-Contains (Join-Path $PlatSelinux 'plat_file_contexts') '/data/vendor/zui_control(/.*)? u:object_r:zui_control_data_file:s0' 'vendor data file_context'

    $PlatPolicy = Join-Path $PlatSelinux 'plat_sepolicy.cil'
    Assert-Contains $PlatPolicy '(type zui_control_service)' 'zui_control_service type'
    Assert-Contains $PlatPolicy '(allow system_server zui_control_service (service_manager (add find)))' 'system_server service allow'
    Assert-Contains $PlatPolicy '(allow priv_app zui_control_service (service_manager (find)))' 'priv_app service allow'
    Assert-Contains $PlatPolicy '(allow init system_file (file (mounton)))' 'init mounton allow'
    Assert-Contains $PlatPolicy '(allow init zui_control_data_file (file (getattr open read write create unlink setattr relabelfrom relabelto)))' 'init zui data file allow'
    Assert-Contains $PlatPolicy '(allow shell self (capability (kill)))' 'shell CAP_KILL allow for ZuiPP reload'
    Assert-Contains $PlatPolicy '(allow shell system_app (process (signal)))' 'shell to system_app SIGTERM allow'
    Assert-Contains $PlatPolicy '(allow shell platform_app (process (signal)))' 'shell to platform_app SIGTERM allow'

    $VendorPolicy = Join-Path $VendorSelinux 'vendor_sepolicy.cil'
    Assert-NotContains $VendorPolicy '(allow shell_34_0 vendor_sysfs_kgsl (dir ' 'legacy vendor KGSL dir allow'
    Assert-NotContains $VendorPolicy '(allow shell_34_0 vendor_sysfs_kgsl (file ' 'legacy vendor KGSL file allow'
    Assert-NotContains $VendorPolicy '(allow shell_34_0 vendor_sysfs_kgsl (lnk_file ' 'legacy vendor KGSL link allow'

    $hashes = [ordered]@{
        boot = File-Sha256 $Boot
        super = File-Sha256 $Super
        vbmeta = File-Sha256 $Vbmeta
        vbmeta_system = File-Sha256 $VbmetaSystem
        apk = File-Sha256 $AppApk
        sidecar_apk = File-Sha256 $SidecarApk
        release_sidecar_apk = File-Sha256 $ReleaseSidecarApk
    }
    if ($hashes.apk -ne $hashes.sidecar_apk) {
        throw "Embedded system APK hash does not match sidecar APK: $($hashes.apk) != $($hashes.sidecar_apk)"
    }
    if ($hashes.apk -ne $hashes.release_sidecar_apk) {
        throw "Embedded system APK hash does not match release sidecar APK: $($hashes.apk) != $($hashes.release_sidecar_apk)"
    }
    Assert-ApkReleaseCert $AppApk 'embedded system APK'
    Assert-ApkReleaseCert $SidecarApk 'sidecar APK'
    Assert-ApkReleaseCert $ReleaseSidecarApk 'release sidecar APK'
    Assert-ZuiControlApkMetadata $AppApk 'embedded system APK'
    Assert-ZuiControlApkMetadata $SidecarApk 'sidecar APK'
    Assert-ZuiControlApkMetadata $ReleaseSidecarApk 'release sidecar APK'
    $ok = $true
    [pscustomobject]@{
        ok = $true
        flash_dir = $FlashDir
        apk_sha256 = $hashes.apk
        boot_sha256 = $hashes.boot
        super_sha256 = $hashes.super
        vbmeta_sha256 = $hashes.vbmeta
        vbmeta_system_sha256 = $hashes.vbmeta_system
    } | ConvertTo-Json -Depth 4
} finally {
    if (-not $KeepWork -and (Test-Path -LiteralPath $WorkDir)) {
        Remove-Item -LiteralPath $WorkDir -Recurse -Force
    } elseif (-not $ok) {
        Write-Warning "Verification workspace kept for debugging: $WorkDir"
    }
}
