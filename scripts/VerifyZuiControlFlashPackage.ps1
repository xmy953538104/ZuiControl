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
$ExpectedVersionCode = '45'
$ExpectedVersionName = '0.21.8'
$ExpectedUperfSha256 = 'f1265757009ff0c85dd8587d9e7bfcf5e51d10d36fe5e1341688215ae1fb49d8'
$ExpectedAsoulSha256 = '7ed7beb2a2680ac91a61dad6b7d64de2eb7eb5711d09c85fc21050b62a3243bd'
$ExpectedBootSha256 = 'e7e85b5cd2806b8c27adf4925e05ee169072a79a43502effc34c97fb27ee8371'
$ExpectedBuildFingerprintMarker = 'ZUI_16.1.11.072_241118_PRC'
$MinimumAvbRollbackIndex = [int64]1736035200

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

function Read-Xml([string]$Path) {
    Require-File $Path
    $xml = New-Object System.Xml.XmlDocument
    $xml.PreserveWhitespace = $true
    $xml.Load($Path)
    return $xml
}

function Assert-ZuiXmlBaseline([string]$GamePath, [string]$PerfPath) {
    $game = Read-Xml $GamePath
    $perf = Read-Xml $PerfPath
    foreach ($typeName in @('LittleCore', 'BigCore', 'TitanCore', 'MegaCore', 'GPU')) {
        if ($null -eq $perf.SelectSingleNode("//GameLimitConfig/Type[@name='$typeName']")) {
            throw "performanceconfig.xml is missing $typeName"
        }
    }
    $mingchao = $game.SelectSingleNode("//AppList[@type='game']/App[@pkg='com.kurogame.mingchao']")
    if ($null -eq $mingchao) { throw 'game_policy.xml is missing Mingchao' }
    $thermal = $mingchao.SelectSingleNode("./Attribute[@name='ThermalConfig']")
    if ($null -eq $thermal -or (($thermal.InnerText.Trim() -replace '\s+', ' ') -ne '0 0 0')) {
        throw 'Mingchao ThermalConfig changed; this release must not change thermal configuration'
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

function Assert-UperfConfig([string]$Path) {
    $raw = Get-Content -Raw -LiteralPath $Path
    $json = $raw | ConvertFrom-Json
    if ($json.modules.switcher.switchInode -ne '/data/vendor/zui_control/uperf/cur_powermode.txt' -or
        $json.modules.switcher.perapp -ne '/data/vendor/zui_control/uperf/perapp_powermode.txt') {
        throw 'Uperf switcher does not use the canonical persistent paths'
    }
    if ($json.modules.sfanalysis.enable -ne $false -or $json.modules.sched.enable -ne $false) {
        throw 'Bundled Uperf must leave SurfaceFlinger injection off and delegate thread placement to A-SOUL'
    }
    foreach ($governor in @('governor0', 'governor2', 'governor5', 'governor7')) {
        if ($json.initials.sysfs.$governor -ne 'walt') { throw "Uperf $governor is not walt" }
    }
    if ($json.modules.sysfs.knob.gpuMin -ne '/sys/class/kgsl/kgsl-3d0/min_clock_mhz' -or
        $json.modules.sysfs.knob.gpuMax -ne '/sys/class/kgsl/kgsl-3d0/max_clock_mhz') {
        throw 'Uperf is missing the bounded KGSL integration'
    }
    $expectedGpu = @{
        balance = @(231, 903)
        powersave = @(231, 500)
        performance = @(231, 903)
        fast = @(231, 903)
    }
    foreach ($mode in $expectedGpu.Keys) {
        $all = $json.presets.$mode.'*'
        if ($all.'sysfs.gpuMin' -ne $expectedGpu[$mode][0] -or $all.'sysfs.gpuMax' -ne $expectedGpu[$mode][1]) {
            throw "Unsafe or unexpected GPU bounds in Uperf mode: $mode"
        }
    }
    foreach ($forbidden in @('thermal_pwrlevel', 'max_pwrlevel', 'min_pwrlevel', 'thermal-engine', 'mi_thermald')) {
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
$VbmetaSystemAvbInfo = Read-AvbInfo $VbmetaSystem
if (-not $BootAvbInfo.Contains($ExpectedBuildFingerprintMarker)) {
    throw 'boot.img is not from the target B072 build'
}
if (-not $VbmetaSystemAvbInfo.Contains($ExpectedBuildFingerprintMarker)) {
    throw 'vbmeta_system.img is not from the target B072 build'
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
    $System = Join-Path $SystemRoot 'system'
    $PlatSelinux = Join-Path $System 'etc\selinux'
    $VendorSelinux = Join-Path $VendorRoot 'etc\selinux'
    $AppApk = Join-Path $System 'priv-app\ZuiControlV45\ZuiControl.apk'
    $Daemon = Join-Path $System 'bin\zui_controld'
    $Uperf = Join-Path $System 'bin\uperf'
    $UperfService = Join-Path $System 'bin\zui_uperf_service'
    $Asoul = Join-Path $System 'bin\AsoulOpt'
    $SchedulerRc = Join-Path $System 'etc\init\zui_scheduler.rc'
    $SchedulerPrepare = Join-Path $System 'etc\zui_control\zui_scheduler_prepare.sh'
    $UperfConfig = Join-Path $System 'etc\zui_control\uperf-sm8650.json'
    $UperfPerApp = Join-Path $System 'etc\zui_control\default_uperf_perapp.txt'
    $AsoulConfig = Join-Path $System 'etc\zui_control\default_asopt.conf'
    $PrivPermissions = Join-Path $System 'etc\permissions\privapp-permissions-zui-control.xml'
    $GameTemplate = Join-Path $System 'etc\zui_control\default_game_policy.xml'
    $PerfTemplate = Join-Path $System 'etc\zui_control\default_performanceconfig.xml'
    foreach ($file in @($AppApk, $Daemon, $Uperf, $UperfService, $Asoul, $SchedulerRc, $SchedulerPrepare, $UperfConfig, $UperfPerApp, $AsoulConfig, $PrivPermissions, $GameTemplate, $PerfTemplate)) {
        Require-File $file
    }

    if ((File-Sha256 $Uperf) -ne $ExpectedUperfSha256) { throw 'Embedded Uperf core hash is not approved' }
    if ((File-Sha256 $Asoul) -ne $ExpectedAsoulSha256) { throw 'Embedded Shiroko A-SOUL hash is not approved' }
    foreach ($path in @(
        (Join-Path $System 'bin\AppOpt'),
        (Join-Path $System 'bin\AppOpt-ebpf'),
        (Join-Path $System 'etc\init\zui_appopt.rc'),
        (Join-Path $System 'etc\zui_control\AppOpt.json'),
        (Join-Path $System 'etc\zui_control\zui_appopt_prepare.sh'),
        (Join-Path $SystemRoot 'AppOpt.json')
    )) { Assert-Missing $path 'retired AppOpt runtime' }
    Assert-Missing (Join-Path $System 'etc\zui_control\zui_cloud_block.sh') 'cloud-control block script'
    foreach ($old in 30..44) { Assert-Missing (Join-Path $System "priv-app\ZuiControlV$old") 'previous ZuiControl version directory' }
    Assert-Missing (Join-Path $System 'priv-app\ZuiControl') 'legacy unversioned ZuiControl directory'

    Assert-UperfConfig $UperfConfig
    Assert-AsoulConfig $AsoulConfig
    $activeRules = @(Get-Content -LiteralPath $UperfPerApp | ForEach-Object { $_.Trim() } | Where-Object { $_ -and -not $_.StartsWith('#') })
    $expectedRules = @('com.kurogame.mingchao performance', 'com.kurogame.wutheringwaves.global performance', '- powersave', '* balance')
    if (Compare-Object $activeRules $expectedRules) { throw "Unexpected default Uperf rules: $($activeRules -join '; ')" }

    Assert-Contains $SchedulerRc 'service zui_uperf /system/bin/zui_uperf_service' 'supervised Uperf init service'
    Assert-NotContains $SchedulerRc '    oneshot' 'untracked daemonized Uperf service'
    Assert-Contains $SchedulerRc 'service zui_asoulopt /system/bin/AsoulOpt' 'A-SOUL init service'
    Assert-Contains $SchedulerRc '    stop vendor.perfservice' 'QTI userspace perf bridge ownership fence'
    Assert-Contains $SchedulerRc '    start vendor.perfservice' 'QTI userspace perf bridge rollback'
    Assert-Contains $SchedulerRc 'write /data/adb/naki/asopt.conf ""' 'init-owned A-SOUL bind target creation'
    Assert-Contains $SchedulerRc 'mount none /data/vendor/zui_control/asoul/asopt.conf /data/adb/naki/asopt.conf bind' 'canonical A-SOUL config bind'
    Assert-Contains $SchedulerRc 'trigger zui-scheduler-start' 'scheduler boot trigger'
    Assert-NotContains $SchedulerRc 'seclabel u:r:shell:s0' 'shell-domain scheduler service'
    Assert-Contains $UperfService 'while pidof uperf' 'Uperf supervisor health loop'
    Assert-Contains $UperfService 'killall -15 uperf' 'exclusive Uperf ownership fence'
    Assert-Contains $UperfService 'echo $$ > /dev/cpuset/background/tasks' 'background placement for Uperf itself'
    Assert-Contains $SchedulerPrepare 'setprop zui_control.appopt stop' 'retired AppOpt stop fence'
    Assert-Contains $SchedulerPrepare 'setprop zui_control.zuipp restore' 'stock ZuiPP XML restore fence'
    Assert-NotContains $SchedulerPrepare '/data/adb' 'shell-domain access to protected Magisk data'
    foreach ($forbidden in @('killall', 'thermal', '/sdcard', 'busybox', 'zui_cloud')) {
        Assert-NotContains $SchedulerPrepare $forbidden 'copied third-party unsafe setup logic'
    }

    Assert-Contains $Daemon 'scheduler owner=uperf; restoring stock ZuiPP XML and stopping AppOpt' 'Uperf boot ownership'
    Assert-Contains $Daemon 'state=retired;owner=uperf' 'retired P2 state'
    Assert-Contains $Daemon 'set_uperf_mode)' 'Uperf global mode command'
    Assert-Contains $Daemon 'set_uperf_app)' 'Uperf per-app command'
    Assert-Contains $Daemon 'restart_scheduler)' 'scheduler restart command'
    Assert-Contains $Daemon '线程参数：mode=0 · rt=0' 'verified A-SOUL mode state'
    Assert-Contains $Daemon 'KGSL DVFS 与热保护保留' 'bounded GPU ownership statement'
    foreach ($forbidden in @('/sys/class/kgsl/kgsl-3d0', '/sys/devices/system/cpu/cpufreq', 'provider_direct', 'GameModeProvider/contact', 'zui_control.cloud_block', 'cloud_block.log')) {
        Assert-NotContains $Daemon $forbidden 'retired direct/provider/cloud runtime'
    }
    Assert-Contains $Daemon 'refresh_owner=$REFRESH_OWNER' 'system_server refresh owner state'
    Assert-Contains $Daemon 'LAST_REQUEST_RECEIPT_FILE=$CONTROL_DIR/last_processed_settings_request_receipt' 'durable request receipt'

    Assert-ZuiXmlBaseline $GameTemplate $PerfTemplate
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
    Assert-NotContains $serviceSmali[0].FullName 'forPhysicalRefreshRates' 'unsafe physical refresh vote'

    $FileContexts = Join-Path $PlatSelinux 'plat_file_contexts'
    Assert-Contains $FileContexts '/system/bin/uperf u:object_r:performanced_exec:s0' 'Uperf file context'
    Assert-Contains $FileContexts '/system/bin/zui_uperf_service u:object_r:performanced_exec:s0' 'Uperf supervisor file context'
    Assert-Contains $FileContexts '/system/bin/AsoulOpt u:object_r:performanced_exec:s0' 'A-SOUL file context'
    Assert-Contains $FileContexts '/data/adb/naki(/.*)? u:object_r:zui_control_data_file:s0' 'A-SOUL compatibility data context'
    Assert-Contains $FileContexts '/data/vendor/zui_control(/.*)? u:object_r:zui_control_data_file:s0' 'scheduler data context'
    Assert-NotContains $FileContexts '/system/bin/AppOpt ' 'retired AppOpt file context'
    Assert-Contains (Join-Path $PlatSelinux 'plat_service_contexts') 'zui_control                               u:object_r:zui_control_service:s0' 'zui_control service context'

    $PlatPolicy = Join-Path $PlatSelinux 'plat_sepolicy.cil'
    foreach ($rule in @(
        '(genfscon proc "/sys/walt/input_boost" (u object_r zui_scheduler_proc ((s0) (s0))))',
        '(genfscon proc "/sys/walt/sched_per_task_boost" (u object_r zui_scheduler_proc ((s0) (s0))))',
        '(allow performanced sysfs_devices_system_cpu (file (getattr open read write append setattr)))',
        '(allow performanced cgroup (file (ioctl read write create getattr setattr lock append map open unlink)))',
        '(allow performanced zui_scheduler_proc (file (getattr open read write append)))',
        '(allow performanced input_device (chr_file (ioctl read getattr lock map open)))',
        '(allow performanced toolbox_exec (file (read getattr map execute open execute_no_trans)))',
        '(allow performanced zui_control_data_file (file (getattr open read write create append map watch watch_reads setattr unlink)))'
    )) { Assert-Contains $PlatPolicy $rule 'scheduler SELinux rule' }

    $VendorPolicy = Join-Path $VendorSelinux 'vendor_sepolicy.cil'
    Assert-Contains $VendorPolicy '(allow performanced_34_0 vendor_sysfs_kgsl (file (ioctl read write getattr lock append map open)))' 'Uperf KGSL vendor rule'
    Assert-Contains $VendorPolicy '(allow performanced_34_0 vendor_sysfs_msm_perf (file (ioctl read write getattr lock append map open)))' 'Uperf msm_performance vendor rule'
    Assert-NotContains $VendorPolicy '(allow shell_34_0 vendor_sysfs_kgsl (' 'legacy shell KGSL permission'

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
