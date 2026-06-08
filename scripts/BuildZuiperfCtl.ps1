param(
    [string]$Configuration = "Debug",
    [string]$ProjectDir = ".",
    [string]$PayloadDir = "payload"
)

$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$project = Join-Path $root $ProjectDir
$payload = Join-Path $root $PayloadDir
$apkOutDir = Join-Path $payload "system/priv-app/ZuiperfCtl"

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

$task = if ($Configuration.ToLowerInvariant() -eq "release") { ":app:assembleRelease" } else { ":app:assembleDebug" }
& $gradle -p $project $task
if ($LASTEXITCODE -ne 0) {
    throw "Gradle build failed with exit code $LASTEXITCODE"
}

$apkName = if ($Configuration.ToLowerInvariant() -eq "release") { "app-release-unsigned.apk" } else { "app-debug.apk" }
$builtApk = Join-Path $project "app/build/outputs/apk/$($Configuration.ToLowerInvariant())/$apkName"
if (-not (Test-Path $builtApk)) {
    throw "Built APK not found: $builtApk"
}

New-Item -ItemType Directory -Force -Path $apkOutDir | Out-Null
Copy-Item -LiteralPath $builtApk -Destination (Join-Path $apkOutDir "ZuiperfCtl.apk") -Force
Write-Host "Copied APK to $apkOutDir/ZuiperfCtl.apk"
