param(
    [string]$FlashDir = "",
    [string]$WorkDir = "",
    [switch]$KeepWork
)

$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $PSScriptRoot
$WorkspaceRoot = Split-Path -Parent $RepoRoot
if (-not $FlashDir) { $FlashDir = Join-Path $WorkspaceRoot '【B刷机】072' }
if (-not $WorkDir) { $WorkDir = Join-Path $WorkspaceRoot 'work\verify_flash_zui_control' }

$ToolsDir = Join-Path $WorkspaceRoot 'tools'
$AndroidSdkDir = Join-Path $WorkspaceRoot 'work\android-sdk'
$Python = Join-Path $ToolsDir 'python-3.8.0\python.exe'
$LpUnpack = Join-Path $ToolsDir 'lpunpack.py'
$ExtractErofs = Join-Path $ToolsDir 'AMD64\extract.erofs.exe'
$Apktool = Join-Path $ToolsDir 'apktool.jar'
$Avbtool = Join-Path $ToolsDir 'downloaded\avbtool_aosp_c0af371_1.2.0.py'
$ReleaseCertSha256 = '3fecf3a72ca0e0f24991d49e7306ef4a711711f48a66070755eb0237ecb3ed94'
$ExpectedVersionCode = '49'
$ExpectedVersionName = '0.21.12'
$ExpectedUperfSha256 = 'f1265757009ff0c85dd8587d9e7bfcf5e51d10d36fe5e1341688215ae1fb49d8'
$ExpectedAsoulSha256 = '7a2ee5d67ba7c057066176334eca9256e376427916429d66b7593cbb5538ec86'
$ExpectedBootSha256 = 'e7e85b5cd2806b8c27adf4925e05ee169072a79a43502effc34c97fb27ee8371'
$ExpectedBuildFingerprintMarker = 'ZUI_16.1.11.072_241118_PRC'
$ForbiddenBuildFingerprintMarker = 'ZUI_16.1.11.187_250227_PRC'
$MinimumAvbRollbackIndex = [int64]1736035200
$OfficialB072Dir = Join-Path $WorkspaceRoot '【A官方】072'

function Require-File([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing file: $Path" }
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

function File-Sha256([string]$Path) {
    Require-File $Path
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
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
        throw 'Bundled Uperf must leave SurfaceFlinger injection off and delegate thread placement to A-SOUL'
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
try {
    if (Test-Path -LiteralPath $WorkDir) { Remove-Item -LiteralPath $WorkDir -Recurse -Force }
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
    $SystemImageContexts = Join-Path $ExtractDir 'config\system_a_file_contexts'
    $System = Join-Path $SystemRoot 'system'
    $PlatSelinux = Join-Path $System 'etc\selinux'
    $VendorSelinux = Join-Path $VendorRoot 'etc\selinux'
    $AppApk = Join-Path $System 'priv-app\ZuiControlV49\ZuiControl.apk'
    $Daemon = Join-Path $System 'bin\zui_controld'
    $Uperf = Join-Path $System 'bin\uperf'
    $UperfService = Join-Path $System 'bin\zui_uperf_service'
    $Asoul = Join-Path $System 'bin\AsoulOpt'
    $SchedulerRc = Join-Path $System 'etc\init\zui_scheduler.rc'
    $DaemonRc = Join-Path $System 'etc\init\zui_controld.rc'
    $SchedulerPrepare = Join-Path $System 'etc\zui_control\zui_scheduler_prepare.sh'
    $UperfConfig = Join-Path $System 'etc\zui_control\uperf-sm8650.json'
    $UperfPerApp = Join-Path $System 'etc\zui_control\default_uperf_perapp.txt'
    $AsoulConfig = Join-Path $System 'etc\zui_control\default_asopt.conf'
    $PrivPermissions = Join-Path $System 'etc\permissions\privapp-permissions-zui-control.xml'
    $ZuippPower = Join-Path $System 'etc\zuipp_powercfg.xml'
    $MemCleaner = Join-Path $System 'etc\ZuiMemCleanerConfig.xml'
    $PowerPolicy = Join-Path $System 'etc\ZuiPowerPolicyConfig.xml'
    $AutoRun = Join-Path $System 'etc\motorola\bgintents\com.zui.safecenter.autorun.xml'
    foreach ($file in @($AppApk, $Daemon, $Uperf, $UperfService, $Asoul, $SchedulerRc, $DaemonRc, $SchedulerPrepare, $UperfConfig, $UperfPerApp, $AsoulConfig, $PrivPermissions, $ZuippPower, $MemCleaner, $PowerPolicy, $AutoRun)) {
        Require-File $file
    }

    if ((File-Sha256 $Uperf) -ne $ExpectedUperfSha256) { throw 'Embedded Uperf core hash is not approved' }
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
    foreach ($bridge in @('performance', 'poweropt-service', 'perf2-hal-1-0')) {
        Assert-NotContains $SchedulerRc "    stop $bridge" 'OEM telemetry service stop action'
        Assert-NotContains $SchedulerRc "    start $bridge" 'OEM telemetry service ownership action'
    }
    Assert-Contains $SchedulerRc 'symlink /data/vendor/zui_control/asoul/asopt.conf /data/vendor/asopt.conf' 'canonical A-SOUL config symlink'
    Assert-NotContains $SchedulerRc 'write /data/vendor/zui_control/asoul/asopt.conf' 'boot-time A-SOUL config truncation'
    Assert-NotContains $SchedulerRc '/data/adb' 'retired Magisk config path'
    Assert-Contains $SchedulerRc 'trigger zui-scheduler-start' 'scheduler boot trigger'
    Assert-Contains $SchedulerRc 'on property:zui_control.scheduler=fence' 'OEM perf bridge re-entry fence'
    Assert-NotContains $SchedulerRc 'seclabel u:r:shell:s0' 'shell-domain scheduler service'
    Assert-NotContains $DaemonRc 'zui_control.zuipp' 'retired XML property action'
    Assert-Contains $UperfService '/proc/self/cgroup' 'init-owned Uperf cgroup discovery'
    Assert-Contains $UperfService 'cgroup.procs' 'Uperf cgroup health source'
    Assert-Contains $UperfService 'uperf_process_count' 'Uperf process-count health check'
    Assert-Contains $UperfService "grep -q ' I Uperf is running`$'" 'Uperf ready-log health check'
    Assert-Contains $UperfService "grep -q ' I Failed to start uperf'" 'Uperf failed-start rejection'
    Assert-NotContains $UperfService 'pidof uperf' 'cross-domain proc scanner in Uperf supervisor'
    Assert-NotContains $UperfService 'killall' 'cross-domain process scanner in Uperf supervisor'
    Assert-Contains $UperfService 'echo $$ > /dev/cpuset/background/tasks' 'background placement for Uperf itself'
    Assert-Contains $SchedulerPrepare "printf 'balance\n'" 'balanced global default'
    Assert-Contains $SchedulerPrepare 'effective_powermode.txt' 'effective Uperf mode preparation'
    Assert-Contains $SchedulerPrepare '$1 != "*"' 'retired per-app global fallback removal'
    Assert-Contains $SchedulerPrepare 'mode == 1 && rt == 1 && opt == 1' 'persistent A-SOUL config validation'
    Assert-Contains $SchedulerPrepare 'safecenter_keepalive_backup.flag' 'retired SafeCenter data cleanup'
    Assert-Contains $SchedulerPrepare '.rom_frontend_v47' 'retired Uperf frontend marker cleanup'
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
    Assert-Contains $Daemon 'UPERF_SCENE_KEY=zui_control_top_package' 'system_server scene source'
    Assert-Contains $Daemon 'UPERF_SCREEN_KEY=zui_control_screen_on' 'system_server screen source'
    Assert-Contains $Daemon 'sync_uperf_frontend()' 'ROM Uperf frontend'
    Assert-Contains $Daemon 'effective_powermode.txt' 'ROM effective-mode output'
    Assert-Contains $Daemon 'if [ "$screen" = "0" ]; then' 'screen-off first resolution order'
    Assert-Contains $Daemon 'effective="$(uperf_rule_for_scene "$scene")"' 'exact-app second resolution order'
    Assert-Contains $Daemon 'effective="$global_mode"' 'global fallback resolution order'
    Assert-Contains $Daemon "grep -q ' I Uperf is running`$'" 'strict Uperf daemon health check'
    Assert-NotContains $Daemon 'ps -AZ' 'cross-domain health scanner'
    Assert-Contains $Daemon '调度围栏：vendor.perfservice=' 'narrow QTI scheduler fence health state'
    Assert-Contains $Daemon 'OEM 遥测：$(oem_telemetry_state)' 'OEM telemetry health states'
    Assert-Contains $Daemon 'OEM perf bridge escaped fence' 'OEM perf bridge supervision'
    Assert-NotContains $Daemon 'for bridge in vendor.perfservice performance poweropt-service perf2-hal-1-0' 'broad OEM telemetry fence'
    Assert-Contains $Daemon '线程参数：$(tr' 'runtime A-SOUL mode state'
    Assert-Contains $Daemon 'GPU：原厂 KGSL DVFS 与热保护保留' 'honest GPU ownership statement'
    foreach ($forbidden in @('/sys/class/kgsl/kgsl-3d0', '/sys/devices/system/cpu/cpufreq', 'provider_direct', 'GameModeProvider/contact', 'zui_control.cloud_block', 'cloud_block.log')) {
        Assert-NotContains $Daemon $forbidden 'retired direct/provider/cloud runtime'
    }
    foreach ($retired in @('AppOpt', 'APPOPT_', 'ZUIPP_', 'XML_STATE', 'SafeCenter', 'safecenter', '/data/adb', 'su ')) {
        Assert-NotContains $Daemon $retired 'retired scheduler implementation'
    }
    Assert-Contains $Daemon 'refresh_owner=system;daemon refresh disabled' 'system_server refresh owner state'
    Assert-Contains $Daemon 'LAST_REQUEST_RECEIPT=$CONTROL_DIR/last_request_receipt' 'durable request receipt'

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
    Assert-Contains $serviceSmali[0].FullName 'Landroid/os/HandlerThread;' 'asynchronous focus worker'
    Assert-Contains $serviceSmali[0].FullName 'forRenderFrameRates' 'adaptive render refresh vote'
    Assert-Contains $serviceSmali[0].FullName 'displayVote=adaptiveRender' 'adaptive render state marker'
    Assert-Contains $serviceSmali[0].FullName 'zui_control_screen_on' 'system_server screen-state publication'
    Assert-Contains $serviceSmali[0].FullName 'registerScreenObserver' 'system_server screen observer'
    Assert-NotContains $serviceSmali[0].FullName 'forPhysicalRefreshRates' 'unsafe physical refresh vote'

    $FileContexts = Join-Path $PlatSelinux 'plat_file_contexts'
    Assert-Contains $FileContexts '/system/bin/uperf u:object_r:performanced_exec:s0' 'Uperf file context'
    Assert-Contains $FileContexts '/system/bin/zui_uperf_service u:object_r:performanced_exec:s0' 'Uperf supervisor file context'
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

    $PlatPolicy = Join-Path $PlatSelinux 'plat_sepolicy.cil'
    foreach ($rule in @(
        '(genfscon proc "/sys/walt/input_boost" (u object_r zui_scheduler_proc ((s0) (s0))))',
        '(genfscon proc "/sys/walt/sched_per_task_boost" (u object_r zui_scheduler_proc ((s0) (s0))))',
        '(allow performanced activity_service (service_manager (find)))',
        '(allow system_server performanced (fd (use)))',
        '(allow system_server performanced (fifo_file (write)))',
        '(allow system_server performanced (binder (call)))',
        '(allow performanced self (capability (chown dac_override fowner kill)))',
        '(allow performanced self (file (getattr open read)))',
        '(allow performanced appdomain (dir (getattr open read search)))',
        '(allow performanced appdomain (file (getattr open read)))',
        '(allow performanced appdomain (process (getsched setsched signull)))',
        '(allow performanced sysfs_devices_system_cpu (file (getattr open read write append setattr)))',
        '(allow performanced cgroup (file (ioctl read write create getattr setattr lock append map open unlink)))',
        '(allow performanced cgroup_v2 (dir (getattr open read search)))',
        '(allow performanced cgroup_v2 (file (getattr open read)))',
        '(allow performanced zui_scheduler_proc (file (getattr open read write append setattr)))',
        '(allow performanced input_device (dir (ioctl read getattr lock open watch watch_reads search)))',
        '(allow performanced input_device (chr_file (ioctl read getattr lock map open)))',
        '(allow performanced toolbox_exec (file (read getattr map execute open execute_no_trans)))',
        '(allow performanced zui_control_data_file (file (getattr open read write create append map watch watch_reads setattr unlink)))',
        '(allow performanced zui_control_data_file (lnk_file (getattr read)))'
    )) { Assert-Contains $PlatPolicy $rule 'scheduler SELinux rule' }
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

    $VendorPolicy = Join-Path $VendorSelinux 'vendor_sepolicy.cil'
    Assert-Contains $VendorPolicy '(allow performanced_34_0 vendor_sysfs_msm_perf (file (ioctl read write getattr setattr lock append map open)))' 'Uperf msm_performance vendor rule'
    Assert-NotContains $VendorPolicy '(allow shell_34_0 vendor_sysfs_kgsl (' 'legacy shell KGSL permission'
    Assert-NotContains $VendorPolicy '(allow performanced_34_0 vendor_sysfs_kgsl (' 'unsupported Uperf KGSL permission'

    $hashes = [ordered]@{
        boot = File-Sha256 $Boot
        super = File-Sha256 $Super
        vbmeta = File-Sha256 $Vbmeta
        vbmeta_system = File-Sha256 $VbmetaSystem
        apk = File-Sha256 $AppApk
        sidecar_apk = File-Sha256 $SidecarApk
        release_sidecar_apk = File-Sha256 $ReleaseSidecarApk
        uperf = File-Sha256 $Uperf
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
        boot_rollback_index = $BootRollbackIndex
        vbmeta_system_rollback_index = $VbmetaSystemRollbackIndex
    } | ConvertTo-Json -Depth 4
} finally {
    if (-not $KeepWork -and (Test-Path -LiteralPath $WorkDir)) {
        Remove-Item -LiteralPath $WorkDir -Recurse -Force
    } elseif (-not $ok) {
        Write-Warning "Verification workspace kept for debugging: $WorkDir"
    }
}
