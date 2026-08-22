param(
    [string]$Configuration = "Debug",
    [string]$ProjectDir = ".",
    [string]$PayloadDir = "payload",
    [string]$StoreFile = $env:KEYSTORE_FILE,
    [string]$StorePassword = $env:KEYSTORE_PASSWORD,
    [string]$KeyAlias = $env:KEY_ALIAS,
    [string]$KeyPassword = $env:KEY_PASSWORD
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$project = Join-Path $root $ProjectDir
$payload = Join-Path $root $PayloadDir
$privAppDir = Join-Path $payload "system/priv-app"
$apkOutDir = Join-Path $privAppDir "ZuiControlV44"
$legacyApkOutDir = Join-Path $privAppDir "ZuiControl"
$previousApkOutDirs = @('ZuiControlV30', 'ZuiControlV31', 'ZuiControlV32', 'ZuiControlV33', 'ZuiControlV34', 'ZuiControlV35', 'ZuiControlV36', 'ZuiControlV37', 'ZuiControlV38', 'ZuiControlV39', 'ZuiControlV40', 'ZuiControlV41', 'ZuiControlV42', 'ZuiControlV43') |
    ForEach-Object { Join-Path $privAppDir $_ }

if (-not (Test-Path $project)) {
    throw "Missing project directory: $project"
}

$gradleCandidates = @(
    (Join-Path $project "gradlew.bat"),
    "C:\Users\XMY\.gradle\wrapper\dists\gradle-9.3.1-bin\23ovyewtku6u96viwx3xl3oks\gradle-9.3.1\bin\gradle.bat",
    "gradle"
)

$gradle = $null
foreach ($candidate in $gradleCandidates) {
    $cmd = Get-Command $candidate -ErrorAction SilentlyContinue
    if ($cmd) {
        $gradle = $cmd.Source
        break
    }
    if (Test-Path $candidate) {
        $gradle = $candidate
        break
    }
}

if (-not $gradle) {
    throw "Gradle was not found."
}

$isRelease = $Configuration.ToLowerInvariant() -eq "release"
$task = if ($isRelease) { ":app:assembleRelease" } else { ":app:assembleDebug" }
$gradleArgs = @("-p", $project, $task)
if ($isRelease) {
    if (-not $StoreFile -or -not $StorePassword -or -not $KeyAlias -or -not $KeyPassword) {
        throw "Release build requires StoreFile, StorePassword, KeyAlias, and KeyPassword."
    }
    $gradleArgs += @(
        "-Pzuicontrol.storeFile=$StoreFile",
        "-Pzuicontrol.storePassword=$StorePassword",
        "-Pzuicontrol.keyAlias=$KeyAlias",
        "-Pzuicontrol.keyPassword=$KeyPassword"
    )
}

& $gradle @gradleArgs
if ($LASTEXITCODE -ne 0) {
    throw "Gradle build failed with exit code $LASTEXITCODE"
}

$apkName = if ($isRelease) { "app-release.apk" } else { "app-debug.apk" }
$builtApk = Join-Path $project "app/build/outputs/apk/$($Configuration.ToLowerInvariant())/$apkName"
if (-not (Test-Path $builtApk)) {
    throw "Built APK not found: $builtApk"
}

if (Test-Path -LiteralPath $legacyApkOutDir) {
    Remove-Item -LiteralPath $legacyApkOutDir -Recurse -Force
}
foreach ($previousApkOutDir in $previousApkOutDirs) {
    if (Test-Path -LiteralPath $previousApkOutDir) {
        Remove-Item -LiteralPath $previousApkOutDir -Recurse -Force
    }
}
New-Item -ItemType Directory -Force -Path $apkOutDir | Out-Null
Copy-Item -LiteralPath $builtApk -Destination (Join-Path $apkOutDir "ZuiControl.apk") -Force
Write-Host "Copied APK to $apkOutDir/ZuiControl.apk"
