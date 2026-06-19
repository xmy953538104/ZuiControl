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
$Python = Join-Path $ToolsDir 'python-3.8.0\python.exe'
$LpUnpack = Join-Path $ToolsDir 'lpunpack.py'
$ExtractErofs = Join-Path $ToolsDir 'AMD64\extract.erofs.exe'

function Require-File([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing file: $Path"
    }
}

function Invoke-Checked([string]$Exe, [string[]]$Args) {
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

function File-Sha256([string]$Path) {
    Require-File $Path
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

$FlashDir = (Resolve-Path -LiteralPath $FlashDir).Path
$Super = Join-Path $FlashDir 'super.img'
$Boot = Join-Path $FlashDir 'boot.img'
$Vbmeta = Join-Path $FlashDir 'vbmeta.img'
$VbmetaSystem = Join-Path $FlashDir 'vbmeta_system.img'

Require-File $Python
Require-File $LpUnpack
Require-File $ExtractErofs
Require-File $Super
Require-File $Boot
Require-File $Vbmeta
Require-File $VbmetaSystem

$ok = $false
try {
    if (Test-Path -LiteralPath $WorkDir) {
        Remove-Item -LiteralPath $WorkDir -Recurse -Force
    }
    $ImageDir = Join-Path $WorkDir 'img'
    $ExtractDir = Join-Path $WorkDir 'extract'
    New-Item -ItemType Directory -Path $ImageDir, $ExtractDir | Out-Null

    Invoke-Checked $Python @($LpUnpack, $Super, 'ALL', $ImageDir)

    foreach ($partition in @('system_a', 'vendor_a')) {
        $image = Join-Path $ImageDir "$partition.img"
        Require-File $image
        Invoke-Checked $ExtractErofs @('-i', $image, '-o', $ExtractDir, '-x', '-f')
    }

    $SystemRoot = Join-Path $ExtractDir 'system_a'
    $VendorRoot = Join-Path $ExtractDir 'vendor_a'
    $PlatSelinux = Join-Path $SystemRoot 'system\etc\selinux'
    $VendorSelinux = Join-Path $VendorRoot 'etc\selinux'

    $AppApk = Join-Path $SystemRoot 'system\priv-app\ZuiControl\ZuiControl.apk'
    $Daemon = Join-Path $SystemRoot 'system\bin\zui_controld'
    $DaemonRc = Join-Path $SystemRoot 'system\etc\init\zui_controld.rc'
    $AsoulRc = Join-Path $SystemRoot 'system\etc\init\zui_asoulopt.rc'
    $GameTemplate = Join-Path $SystemRoot 'system\etc\zui_control\default_game_policy.xml'
    $PerfTemplate = Join-Path $SystemRoot 'system\etc\zui_control\default_performanceconfig.xml'
    foreach ($required in @($AppApk, $Daemon, $DaemonRc, $AsoulRc, $GameTemplate, $PerfTemplate)) {
        Require-File $required
    }

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

    $VendorPolicy = Join-Path $VendorSelinux 'vendor_sepolicy.cil'
    Assert-Contains $VendorPolicy '(allow shell_34_0 vendor_sysfs_kgsl (dir ' 'vendor KGSL dir allow'
    Assert-Contains $VendorPolicy '(allow shell_34_0 vendor_sysfs_kgsl (file ' 'vendor KGSL file allow'
    Assert-Contains $VendorPolicy '(allow shell_34_0 vendor_sysfs_kgsl (lnk_file ' 'vendor KGSL link allow'

    $hashes = [ordered]@{
        boot = File-Sha256 $Boot
        super = File-Sha256 $Super
        vbmeta = File-Sha256 $Vbmeta
        vbmeta_system = File-Sha256 $VbmetaSystem
        apk = File-Sha256 $AppApk
    }
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
