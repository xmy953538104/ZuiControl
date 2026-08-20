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
$Apktool = Join-Path $ToolsDir 'apktool.jar'
$ReleaseCertSha256 = '3fecf3a72ca0e0f24991d49e7306ef4a711711f48a66070755eb0237ecb3ed94'
$ExpectedVersionCode = '40'
$ExpectedVersionName = '0.21.3'

function Require-File([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing file: $Path"
    }
}

function Assert-MissingFile([string]$Path, [string]$Label) {
    if (Test-Path -LiteralPath $Path) {
        throw "Unexpected $Label remains: $Path"
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
Require-File $Apktool
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

    $Java = Get-Command 'java.exe' -ErrorAction SilentlyContinue
    if (-not $Java) {
        $Java = Get-Command 'java' -ErrorAction SilentlyContinue
    }
    if (-not $Java) {
        throw 'Missing java in PATH'
    }
    $ServicesJar = Join-Path $SystemRoot 'system\framework\services.jar'
    $ServicesDecode = Join-Path $WorkDir 'services_decode'
    Require-File $ServicesJar
    Invoke-Checked $Java.Source '-jar' $Apktool 'd' '-f' '-o' $ServicesDecode $ServicesJar
    $ZuiServiceSmali = @(Get-ChildItem -LiteralPath $ServicesDecode -Recurse -File -Filter 'ZuiControlService.smali')
    if ($ZuiServiceSmali.Count -ne 1) {
        throw "Expected one decoded ZuiControlService.smali, found $($ZuiServiceSmali.Count)"
    }
    $ZuiServiceSmali = $ZuiServiceSmali[0].FullName
    Assert-Contains $ZuiServiceSmali 'Landroid/os/HandlerThread;' 'asynchronous focus worker'
    Assert-Contains $ZuiServiceSmali 'forRenderFrameRates' 'adaptive render refresh vote'
    Assert-Contains $ZuiServiceSmali 'displayVote=adaptiveRender' 'adaptive render state marker'
    Assert-NotContains $ZuiServiceSmali 'forPhysicalRefreshRates' 'unsafe hard physical refresh vote'

    $AppApk = Join-Path $SystemRoot 'system\priv-app\ZuiControlV40\ZuiControl.apk'
    $LegacyAppDir = Join-Path $SystemRoot 'system\priv-app\ZuiControl'
    $PreviousAppDirs = @(
        (Join-Path $SystemRoot 'system\priv-app\ZuiControlV30'),
        (Join-Path $SystemRoot 'system\priv-app\ZuiControlV31'),
        (Join-Path $SystemRoot 'system\priv-app\ZuiControlV32'),
        (Join-Path $SystemRoot 'system\priv-app\ZuiControlV33'),
        (Join-Path $SystemRoot 'system\priv-app\ZuiControlV34'),
        (Join-Path $SystemRoot 'system\priv-app\ZuiControlV35'),
        (Join-Path $SystemRoot 'system\priv-app\ZuiControlV36'),
        (Join-Path $SystemRoot 'system\priv-app\ZuiControlV37'),
        (Join-Path $SystemRoot 'system\priv-app\ZuiControlV38'),
        (Join-Path $SystemRoot 'system\priv-app\ZuiControlV39')
    )
    $Daemon = Join-Path $SystemRoot 'system\bin\zui_controld'
    $AppOpt = Join-Path $SystemRoot 'system\bin\AppOpt'
    $DaemonRc = Join-Path $SystemRoot 'system\etc\init\zui_controld.rc'
    $AppOptRc = Join-Path $SystemRoot 'system\etc\init\zui_appopt.rc'
    $ClearPackageCache = Join-Path $SystemRoot 'system\etc\zui_control\clear_package_cache.sh'
    $AppOptPrepare = Join-Path $SystemRoot 'system\etc\zui_control\zui_appopt_prepare.sh'
    $CloudBlock = Join-Path $SystemRoot 'system\etc\zui_control\zui_cloud_block.sh'
    $Hosts = Join-Path $SystemRoot 'system\etc\hosts'
    $DefaultAppList = Join-Path $SystemRoot 'system\etc\zui_control\default_applist.conf'
    $PrivAppPermissions = Join-Path $SystemRoot 'system\etc\permissions\privapp-permissions-zui-control.xml'
    $GameTemplate = Join-Path $SystemRoot 'system\etc\zui_control\default_game_policy.xml'
    $PerfTemplate = Join-Path $SystemRoot 'system\etc\zui_control\default_performanceconfig.xml'
    foreach ($required in @($AppApk, $Daemon, $AppOpt, $DaemonRc, $AppOptRc, $AppOptPrepare, $Hosts, $DefaultAppList, $PrivAppPermissions, $GameTemplate, $PerfTemplate)) {
        Require-File $required
    }
    Assert-MissingFile $LegacyAppDir 'legacy ZuiControl priv-app directory'
    foreach ($PreviousAppDir in $PreviousAppDirs) {
        Assert-MissingFile $PreviousAppDir 'previous ZuiControl versioned priv-app directory'
    }
    Assert-MissingFile $ClearPackageCache 'obsolete package-cache helper'
    Assert-MissingFile $CloudBlock 'removed cloud-block script'
    foreach ($legacyPath in @(
        (Join-Path $SystemRoot 'system\bin\AsoulOpt'),
        (Join-Path $SystemRoot 'system\etc\init\zui_asoulopt.rc'),
        (Join-Path $SystemRoot 'system\etc\zui_control\zui_asoulopt.sh'),
        (Join-Path $SystemRoot 'system\etc\zui_control\default_asopt.conf'),
        (Join-Path $SystemRoot 'system\etc\zui_control\asopt.conf'),
        (Join-Path $SystemRoot 'system\etc\asopt.conf')
    )) {
        Assert-MissingFile $legacyPath 'asoulOpt legacy file'
    }
    Assert-NotContains $DaemonRc 'clear_package_cache.sh' 'obsolete package cache clear action'
    Assert-NotContains $DaemonRc '/data/system/package_cache' 'package cache mutation'
    Assert-Contains $DaemonRc '/data/vendor/zui_control/appopt' 'AppOpt runtime directory'
    Assert-NotContains $DaemonRc '/data/vendor/zui_control/cloud' 'removed cloud-block runtime directory'
    Assert-Contains $AppOptRc 'service zui_appopt /system/bin/AppOpt -c /data/vendor/zui_control/appopt/applist.conf -s 2' 'AppOpt init service'
    Assert-NotContains $AppOptRc '    start zui_appopt' 'unconditional AppOpt boot start'
    Assert-NotContains $AppOptRc 'zui_cloud_block' 'removed cloud-block init action'
    Assert-Contains $Daemon 'pm path "$1" </dev/null' 'AppOpt PackageManager stdin isolation'
    Assert-Contains $AppOptPrepare 'killall -15 AsoulOpt' 'legacy AsoulOpt cleanup'
    Assert-Contains $AppOptPrepare "grep -q '^[[:space:]]*[^#[:space:]]'" 'zero-rule AppOpt template migration'
    Assert-Contains $AppOptPrepare 'settings delete system zui_control_cloud_block_state' 'one-way cloud state cleanup'
    Assert-Contains $AppOptPrepare 'settings delete system zui_control_pp_mode_state' 'one-way stale PP mode state cleanup'
    $ActiveDefaultAppOptRules = @(
        Get-Content -LiteralPath $DefaultAppList |
            Where-Object { $_.Trim() -and -not $_.Trim().StartsWith('#') }
    )
    if ($ActiveDefaultAppOptRules.Count -ne 0) {
        throw "Default AppOpt config must not contain active rules: $($ActiveDefaultAppOptRules -join '; ')"
    }
    Assert-Contains $DefaultAppList '# Supported CPU sets: one core 0..7, or a contiguous X-Y range such as 2-7' 'validated AppOpt CPU sets'
    Assert-Contains $DefaultAppList '# com.kurogame.mingchao=2-6' 'commented AppOpt fallback example'
    Assert-Contains $DefaultAppList '# com.kurogame.mingchao{RenderThread}=2-4' 'commented AppOpt render-thread example'
    Assert-Contains $DefaultAppList '# com.kurogame.mingchao{GameThread}=7' 'commented AppOpt game-thread example'

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $ApkArchive = [System.IO.Compression.ZipFile]::OpenRead($AppApk)
    try {
        $AppOptCatalogEntry = $ApkArchive.GetEntry('assets/appopt_sm8650_profiles.conf')
        if ($null -eq $AppOptCatalogEntry) {
            throw 'APK is missing Snapdragon 8 Gen 3 AppOpt catalog'
        }
        $CatalogReader = [System.IO.StreamReader]::new($AppOptCatalogEntry.Open())
        try {
            $CatalogText = $CatalogReader.ReadToEnd()
        } finally {
            $CatalogReader.Dispose()
        }
    } finally {
        $ApkArchive.Dispose()
    }
    foreach ($catalogMarker in @(
        'com.kurogame.mingchao=2-6',
        'com.kurogame.mingchao{RenderThread}=2-4',
        'com.kurogame.mingchao{GameThread}=7'
    )) {
        if (-not $CatalogText.Contains($catalogMarker)) {
            throw "AppOpt catalog is missing marker: $catalogMarker"
        }
    }
    if (($CatalogText -split "`n" | Where-Object { $_ -match '^[a-zA-Z0-9_.]+=' }).Count -lt 300) {
        throw 'AppOpt catalog does not contain the expected package coverage'
    }
    if ((File-Sha256 $Hosts) -ne '425c3e713d5bae19b031bc8639c20c6a23e311a54647ba1824cbf45969a11ff4') {
        throw 'system hosts does not match the official ZUI 16.1.11.187 default'
    }
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
    foreach ($staleAsoul in @('/system/bin/AsoulOpt', 'init.svc.zui_asoulopt', '/system/etc/asopt.conf', 'zui_control.asoul=apply')) {
        if ($daemonText.Contains($staleAsoul)) {
            throw "daemon still contains stale asoulOpt runtime marker: $staleAsoul"
        }
    }
    foreach ($appOptMarker in @('APPOPT_DIR=$DATA_ROOT/appopt', 'APPOPT_CONFIG=$APPOPT_DIR/applist.conf', 'init.svc.zui_appopt', 'pidof AppOpt', 'appopt_rules_count()', 'AppOpt not started: no active rules', '后端：AppOpt')) {
        if (-not $daemonText.Contains($appOptMarker)) {
            throw "daemon is missing AppOpt marker: $appOptMarker"
        }
    }
    foreach ($removedMarker in @('GameModeProvider/contact', 'content update --uri "$PP_GAME_MODE_URI"', 'stage=provider_direct', 'zui_control_pp_mode_state', 'scene_event_tick', 'zui_control.cloud_block', 'CLOUD_DIR=', 'cloud_block.log')) {
        if ($daemonText.Contains($removedMarker)) {
            throw "daemon still contains removed cloud/provider marker: $removedMarker"
        }
    }
    Assert-Contains $Daemon 'profile_mode=balanced' 'single active performance profile canonical mode'
    Assert-Contains $Daemon 'rewrite_performance_without_package "$profile_pkg"' 'single performance profile per package rewrite'
    Assert-Contains $Daemon 'performance profiles migrated to one canonical profile per package' 'legacy multi-mode profile migration'
    Assert-Contains $Daemon 'ZUIPP_STABLE_SECONDS=3' 'bounded ZuiPP stability window'
    Assert-Contains $Daemon 'stableSeconds=$ZUIPP_STABLE_SECONDS' 'stable ZuiPP restart readiness marker'
    Assert-Contains $Daemon 'stop_zuipp_services_for_reload' 'graceful ZuiPP service shutdown before process reload'
    Assert-Contains $Daemon 'prepare_game_helper_for_zuipp_reload' 'GameHelper lifecycle preparation before ZuiPP reload'
    Assert-Contains $Daemon 'prewarm_game_helper_after_zuipp_reload' 'GameHelper listener prewarm after ZuiPP reload'
    Assert-Contains $Daemon 'initialize_zuipp_performance_connect' 'ZuiPP PerformanceConnect initialization before GameHelper'
    Assert-Contains $Daemon 'com.zui.pp/com.zui.performance.clientcenter.PerformanceConnect' 'OEM ZuiPP PerformanceConnect component'
    Assert-Contains $Daemon 'com.zui.game.service.action.GAME_MODE_START_SERVICE' 'OEM GameHelper prewarm action'
    Assert-Contains $Daemon 'state=error;stage=prewarm_game_helper' 'GameHelper prewarm failure state'
    Assert-Contains $Daemon 'state=error;stage=performance_connect' 'ZuiPP PerformanceConnect failure state'
    Assert-Contains $Daemon 'com.zui.pp/com.zui.power.overheat.OverHeatCleanService' 'OverHeatCleanService null-Intent crash prevention'
    Assert-Contains $Daemon 'Service not stopped: was not running.' 'ZUI am stop-service successful no-op handling'
    Assert-Contains $Daemon 'attempts=5' 'transient boot Binder stop-service retry'
    Assert-Contains $Daemon 'state=error;stage=stop_services' 'ZuiPP service shutdown failure state'
    Assert-Contains $Daemon 'invalidate_zuipp_reload_receipt_on_start || return 1' 'boot-time ZuiPP reload receipt invalidation'
    Assert-Contains $Daemon 'LAST_REQUEST_RECEIPT_FILE=$CONTROL_DIR/last_processed_settings_request_receipt' 'persistent request receipt'
    Assert-Contains $Daemon 'REQUEST_ACK_KEY=zui_control_request_ack' 'exact terminal request acknowledgement'
    Assert-Contains $Daemon 'PERFORMANCE_TXN_MARKER=$PERFORMANCE_DIR/profile_txn.prop' 'recoverable performance transaction marker'
    Assert-Contains $Daemon 'ZUI_MODE_TX_ADD_USER_GAME=14' 'one-way ZUI Game Assistant custom-game sync'
    Assert-Contains $Daemon 'ZUI_MODE_TX_REMOVE_USER_GAME=15' 'P2 delete custom-game sync'
    Assert-Contains $Daemon 'ensure_zui_game_managed' 'P2 Game Assistant membership gate'
    Assert-Contains $Daemon 'rollback_zui_game_sync' 'P2 Game Assistant transaction rollback'
    Assert-Contains $Daemon 'publish_request_progress' 'long command processing stages'
    Assert-Contains $Daemon 'force_stop_package_if_running' 'running target auto-stop'
    Assert-Contains $Daemon 'replace_appopt_rules' 'atomic AppOpt batch import'
    Assert-Contains $Daemon 'APPOPT_STABLE_SECONDS=3' 'bounded AppOpt stability window'
    Assert-Contains $Daemon 'durable_sync_paths' 'targeted transaction durability sync'
    Assert-Contains $Daemon 'BAKED_TEMPLATE_SHA_FILE=$ZUI_BAKED_DIR/template.sha256' 'versioned payload baseline stamp'
    Assert-Contains $Daemon 'ZuiPP active XML regenerated after payload baseline refresh' 'payload baseline upgrade regeneration'
    if ($daemonText -match 'if promote_staging_with_init "promote_staging"; then\s+backup_active_to_last_good') {
        throw 'daemon overwrites last_good with the new active XML after successful promote'
    }
    Assert-ZuiLimitXml $GameTemplate $PerfTemplate
    Assert-Contains $PerfTemplate 'ZuiControl SM8650 generator writes floorIndex_ceilingIndex' 'SM8650 GPU direction baseline refresh marker'
    $gameXml = Read-XmlDocument $GameTemplate
    $mingchao = $gameXml.SelectSingleNode("//AppList[@type='game']/App[@pkg='com.kurogame.mingchao']")
    if ($null -eq $mingchao) {
        throw 'default_game_policy.xml is missing WutheringWaves App entry'
    }
    $mingchaoThermal = $mingchao.SelectSingleNode("./Attribute[@name='ThermalConfig']")
    if ($null -eq $mingchaoThermal -or (($mingchaoThermal.InnerText.Trim() -replace '\s+', ' ') -ne '0 0 0')) {
        throw 'WutheringWaves ThermalConfig must stay 0 0 0 to avoid vendor thermal user-case GPU caps'
    }

    Assert-Contains (Join-Path $PlatSelinux 'plat_service_contexts') 'zui_control                               u:object_r:zui_control_service:s0' 'zui_control service_context'
    Assert-Contains (Join-Path $PlatSelinux 'plat_property_contexts') 'persist.zui_control. u:object_r:shell_prop:s0' 'persist.zui_control property_context'
    Assert-Contains (Join-Path $PlatSelinux 'plat_property_contexts') 'zui_control. u:object_r:shell_prop:s0' 'zui_control property_context'
    Assert-Contains (Join-Path $PlatSelinux 'plat_file_contexts') '/data/system/zui_control(/.*)? u:object_r:system_data_file:s0' 'data system file_context'
    Assert-Contains (Join-Path $PlatSelinux 'plat_file_contexts') '/system/bin/AppOpt u:object_r:performanced_exec:s0' 'AppOpt performanced file_context'
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
    Assert-Contains $PlatPolicy '(allow performanced self (capability (dac_override kill)))' 'AppOpt performanced dac_override and kill allow'
    Assert-Contains $PlatPolicy '(allow performanced zui_control_data_file (dir (getattr open read search)))' 'AppOpt zui_control data dir read allow'
    Assert-Contains $PlatPolicy '(allow performanced zui_control_data_file (file (getattr open read map watch watch_reads)))' 'AppOpt zui_control data file read and watch allow'
    Assert-Contains $PlatPolicy '(allow performanced appdomain (process (getsched signull)))' 'AppOpt minimal app process probe allow'
    Assert-Contains $PlatPolicy '(dontaudit performanced domain (dir (getattr)))' 'AppOpt process scan getattr dontaudit'

    $OemPerformancedSetSchedRules = @(
        '(allow performanced appdomain (process (setsched)))',
        '(allow performanced bufferhubd (process (setsched)))',
        '(allow performanced kernel (process (setsched)))',
        '(allow performanced surfaceflinger (process (setsched)))'
    )
    foreach ($rule in $OemPerformancedSetSchedRules) {
        Assert-Contains $PlatPolicy $rule 'existing OEM AppOpt setsched allow'
    }
    $AppDomainProcessRules = @(
        Get-Content -LiteralPath $PlatPolicy |
            Where-Object { $_ -match '^\(allow performanced appdomain \(process \([^)]*\)\)\)\s*$' } |
            ForEach-Object { $_.Trim() }
    )
    $AllowedAppDomainProcessRules = @(
        $OemPerformancedSetSchedRules[0],
        '(allow performanced appdomain (process (getsched signull)))'
    )
    $UnexpectedAppDomainProcessRules = @($AppDomainProcessRules | Where-Object { $_ -notin $AllowedAppDomainProcessRules })
    if ($AppDomainProcessRules.Count -ne $AllowedAppDomainProcessRules.Count -or $UnexpectedAppDomainProcessRules.Count -ne 0) {
        throw "Unexpected AppOpt appdomain process permissions: $($AppDomainProcessRules -join '; ')"
    }
    $PerformancedSetSchedRules = @(
        Get-Content -LiteralPath $PlatPolicy |
            Where-Object { $_ -match '^\(allow performanced \S+ \(process \([^)]*\bsetsched\b[^)]*\)\)\)\s*$' } |
            ForEach-Object { $_.Trim() }
    )
    $UnexpectedSetSchedRules = @($PerformancedSetSchedRules | Where-Object { $_ -notin $OemPerformancedSetSchedRules })
    if ($PerformancedSetSchedRules.Count -ne $OemPerformancedSetSchedRules.Count -or $UnexpectedSetSchedRules.Count -ne 0) {
        throw "Unexpected new AppOpt setsched permission: $($PerformancedSetSchedRules -join '; ')"
    }

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
