[CmdletBinding()]
param(
    [string]$WorkspaceRoot = 'D:\3.VScode\Mi',
    [Parameter(Mandatory)]
    [ValidatePattern('^\d{14}$')]
    [string]$RunId,
    [Parameter(Mandatory)]
    [ValidatePattern('^[0-9a-fA-F]{40}$')]
    [string]$SourceCommit,
    [Parameter(Mandatory)]
    [ValidatePattern('^\d{6,20}$')]
    [string]$CiRunId,
    [Parameter(Mandatory)]
    [string]$CiArtifactPath,
    [switch]$KeepScratch,
    [switch]$Execute
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $Execute) {
    throw 'Preview guard: re-run with -Execute. This builds and signs an isolated V20.3A candidate; it never prepares or flashes a 9008 package.'
}

function Full([string]$Path) { [IO.Path]::GetFullPath($Path).TrimEnd('\') }
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Require-File([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing file: $Path" }
}
function Require-Directory([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { throw "Missing directory: $Path" }
}
function Invoke-Checked([string]$Exe, [Parameter(ValueFromRemainingArguments)][object[]]$Arguments) {
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed ($LASTEXITCODE): $Exe $($Arguments -join ' ')" }
}
function Invoke-Captured([string]$Exe, [Parameter(ValueFromRemainingArguments)][object[]]$Arguments) {
    $lines = @(& $Exe @Arguments 2>&1)
    $nativeExit = $LASTEXITCODE
    $text = ($lines | Out-String -Width 4096).Trim()
    if ($nativeExit -ne 0) { throw "Command failed ($nativeExit): $Exe $($Arguments -join ' ')`n$text" }
    return $text
}
function Invoke-CheckedLogged([string]$Exe, [Parameter(ValueFromRemainingArguments)][object[]]$Arguments) {
    & $Exe @Arguments *>&1 | Tee-Object -FilePath $buildLog -Append
    $nativeExit = $LASTEXITCODE
    if ($nativeExit -ne 0) { throw "Command failed ($nativeExit): $Exe $($Arguments -join ' ')" }
}

$workspace = Full $WorkspaceRoot
$repo = Join-Path $workspace 'ZuiControl'
$workRoot = Join-Path $workspace 'work'
$scratch = Join-Path $workRoot "v20_3a_scratch_$RunId"
$scratchRepo = Join-Path $scratch 'repo'
$candidate = Join-Path $workRoot "v20_3a_candidate_$RunId"
$evidenceRoot = Join-Path $repo 'V20_3A_COMMAND_WAKEUP\raw'
$evidence = Join-Path $evidenceRoot "build_$RunId"
$official = Join-Path $workspace '【A官方】072'
$template = Join-Path $workspace '072必刷镜像'
$production = Join-Path $workspace '【B刷机】072'
$ciRoot = Join-Path $workRoot 'ci_artifacts'
$declaredCiArtifact = Full $CiArtifactPath
$ciArtifact = Join-Path $scratch "ci_artifacts\zuicontrol_$CiRunId"
$ciApk = Join-Path $ciArtifact 'ZuiControl-release-apk\app-release.apk'
$ciPayload = Join-Path $ciArtifact 'zui-control-v19-payload'
$ciPayloadApk = Join-Path $ciPayload 'system\priv-app\ZuiControlV49\ZuiControl.apk'
$workShell = Join-Path $workspace 'scripts\WorkShell.ps1'
$python = Join-Path $workspace 'tools\python-3.8.0\python.exe'
$extractErofs = Join-Path $workspace 'tools\AMD64\extract.erofs.exe'
$pemSource = Join-Path $workspace 'Linux\pem'
$sourcePatch = Join-Path $evidence 'candidate_source.patch'
$buildLog = Join-Path $evidence 'build.log'
$verifyLog = Join-Path $evidence 'final_super_verifier.log'
$releaseCertSha256 = '3fecf3a72ca0e0f24991d49e7306ef4a711711f48a66070755eb0237ecb3ed94'
$expectedBootSha256 = 'e7e85b5cd2806b8c27adf4925e05ee169072a79a43502effc34c97fb27ee8371'
$SourceCommit = $SourceCommit.ToLowerInvariant()

foreach ($path in @($repo, $workRoot, $official, $template, $production, $ciRoot, $declaredCiArtifact, $pemSource)) {
    Require-Directory $path
}
foreach ($path in @($workShell, $python, $extractErofs)) { Require-File $path }
if (-not [IO.Path]::IsPathRooted($CiArtifactPath)) { throw 'CiArtifactPath must be an explicit absolute path.' }
if (-not $declaredCiArtifact.StartsWith((Full $ciRoot) + '\', [StringComparison]::OrdinalIgnoreCase) -or
    (Split-Path -Leaf $declaredCiArtifact) -ne "zuicontrol_$CiRunId") {
    throw "CiArtifactPath must be the exact work\ci_artifacts\zuicontrol_$CiRunId directory."
}
$ciLinks = @(Get-ChildItem -LiteralPath $declaredCiArtifact -Force -Recurse -Attributes ReparsePoint -ErrorAction SilentlyContinue)
if ((Get-Item -LiteralPath $declaredCiArtifact -Force).LinkType -or $ciLinks.Count) {
    throw 'CI artifact directory must not contain links or reparse points.'
}
foreach ($fresh in @($scratch, $candidate, $evidence)) {
    if (Test-Path -LiteralPath $fresh) { throw "Fresh path required: $fresh" }
}
if (-not (Full $scratch).StartsWith((Full $workRoot) + '\v20_3a_scratch_', [StringComparison]::OrdinalIgnoreCase) -or
    -not (Full $candidate).StartsWith((Full $workRoot) + '\v20_3a_candidate_', [StringComparison]::OrdinalIgnoreCase) -or
    -not (Full $evidence).StartsWith((Full $evidenceRoot) + '\build_', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'A generated path escaped the V20.3A approved roots.'
}
if ((Get-PSDrive D).Free -lt 80GB) { throw 'At least 80 GiB free is required before the isolated build.' }

$trackedBuildPaths = @(
    '.github/workflows/build.yml',
    'app/src/main/java/com/zui/zuicontrol/BootReceiver.kt',
    'app/src/main/java/com/zui/zuicontrol/MainActivity.kt',
    'app/src/main/java/com/zui/zuicontrol/ZuiControlClient.kt',
    'app/src/main/java/com/zui/zuicontrol/ZuiControlRequest.kt',
    'app/src/test/java/com/zui/zuicontrol/ZuiControlRequestTest.kt',
    'framework-stubs/src/main/java/android/zui/ZuiControlManager.java',
    'framework_patch/src/framework/android/zui/ZuiControlManager.java',
    'framework_patch/src/services/com/zui/server/control/ZuiControlService.java',
    'framework_patch/stubs/android/os/SystemProperties.java',
    'payload/patches/plat_property_contexts_add.txt',
    'payload/patches/plat_sepolicy_zui_control.cil',
    'payload/system/bin/zui_controld',
    'payload/system/etc/init/zui_controld.rc',
    'payload/system/etc/init/zui_scheduler.rc',
    'payload/system/etc/zui_control/zui_scheduler_prepare.sh',
    'scripts/ApplyZuiControlPayload.py',
    'scripts/PatchZuiControlFramework.py',
    'scripts/TestZuiControldTransactions.sh',
    'scripts/VerifyZuiControlFlashPackage.ps1',
    'V20_3A_COMMAND_WAKEUP/tests/TestV20_3APolicy.py',
    'V20_3A_COMMAND_WAKEUP/tests/BuildV20_3ACandidate.ps1'
)

function Snapshot-Files([string]$Root, [string]$ExcludePattern = '') {
    $rootFull = Full $Root
    $prefix = $rootFull + '\'
    return @(Get-ChildItem -LiteralPath $rootFull -Recurse -File | Sort-Object FullName | ForEach-Object {
        $full = Full $_.FullName
        if (-not $full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "File escaped inventory root: $full"
        }
        $relative = $full.Substring($prefix.Length).Replace('\', '/')
        if ($ExcludePattern -and $relative -match $ExcludePattern) { return }
        [pscustomobject][ordered]@{
            path = $relative
            length = $_.Length
            sha256 = Hash $full
        }
    })
}
function Snapshot-Production {
    $required = @('boot.img', 'super.img', 'vbmeta_system.img', 'vbmeta.img',
        'ZuiControl-v19-release.apk', 'ZuiControl-v19-system.apk')
    foreach ($name in $required) { Require-File (Join-Path $production $name) }
    $prefix = (Full $production) + '\'
    return @(Get-ChildItem -LiteralPath $production -Recurse -File | Sort-Object FullName | ForEach-Object {
        $full = Full $_.FullName
        if (-not $full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Protected file escaped production root: $full"
        }
        [pscustomobject][ordered]@{
            path = $full.Substring($prefix.Length).Replace('\', '/')
            length = $_.Length
            mtime_utc = $_.LastWriteTimeUtc.ToString('o')
            sha256 = Hash $full
        }
    })
}
function Record-Space([string]$Label) {
    $drive = Get-PSDrive D
    ("{0}`t{1:o}`tfree_bytes={2}`tfree_gib={3}" -f $Label, (Get-Date), $drive.Free,
        [math]::Round($drive.Free / 1GB, 3)) |
        Add-Content -LiteralPath (Join-Path $evidence 'disk_timeline.tsv') -Encoding UTF8
}
function Find-AndroidTool([string]$Name) {
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) { return $(if ($cmd.Source) { $cmd.Source } else { $cmd.FullName }) }
    $file = Get-ChildItem -LiteralPath (Join-Path $workRoot 'android-sdk\build-tools') -Recurse -Filter $Name -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $file) { throw "Missing Android tool: $Name" }
    return $file.FullName
}
function Assert-ReleaseApk([string]$ApkPath) {
    $apkSigner = Find-AndroidTool 'apksigner.bat'
    $certs = Invoke-Captured $apkSigner verify --print-certs $ApkPath
    if ($certs -notmatch [regex]::Escape("Signer #1 certificate SHA-256 digest: $releaseCertSha256")) {
        throw 'CI APK is not signed with the approved release certificate.'
    }
    $aapt2 = Find-AndroidTool 'aapt2.exe'
    $badging = Invoke-Captured $aapt2 dump badging $ApkPath
    if ($badging -notmatch "package: name='com\.zui\.zuicontrol' versionCode='49' versionName='0\.21\.12'") {
        throw 'CI APK package/version does not match the current production contract.'
    }
    if ($badging -match '(?m)^application-debuggable') {
        throw 'CI release APK must not be debuggable.'
    }
}
function Expand-TrustedArtifactZip([string]$ZipPath, [string]$Destination) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $destinationFull = Full $Destination
    New-Item -ItemType Directory -Path $destinationFull | Out-Null
    $archive = [IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        foreach ($entry in $archive.Entries) {
            $relative = $entry.FullName.Replace('/', '\')
            if (-not $relative -or $relative -match '^(?:[\\/]|[A-Za-z]:)' -or
                $relative -match '(?:^|\\)\.\.(?:\\|$)') {
                throw "Unsafe CI artifact ZIP entry: $($entry.FullName)"
            }
            $target = Full (Join-Path $destinationFull $relative)
            if ($target -ne $destinationFull -and
                -not $target.StartsWith($destinationFull + '\', [StringComparison]::OrdinalIgnoreCase)) {
                throw "CI artifact ZIP entry escaped destination: $($entry.FullName)"
            }
        }
    } finally {
        $archive.Dispose()
    }
    Expand-Archive -LiteralPath $ZipPath -DestinationPath $destinationFull
    $links = @(Get-ChildItem -LiteralPath $destinationFull -Force -Recurse -Attributes ReparsePoint -ErrorAction SilentlyContinue)
    if ($links.Count) { throw "CI artifact created a reparse point: $($links[0].FullName)" }
}
function Save-GitHubArtifactZip([string]$ArtifactId, [string]$Destination, [string]$Token) {
    $headers = @{
        Accept = 'application/vnd.github+json'
        Authorization = "Bearer $Token"
        'X-GitHub-Api-Version' = '2022-11-28'
    }
    $uri = "https://api.github.com/repos/xmy953538104/ZuiControl/actions/artifacts/$ArtifactId/zip"
    Invoke-WebRequest -UseBasicParsing -Headers $headers -Uri $uri -OutFile $Destination
}
function Set-VendorErofsProductionCompression {
    $fsOptions = Join-Path $scratch 'work\unpack\config\vendor_a_fs_options'
    Require-File $fsOptions
    $beforeHash = Hash $fsOptions
    $beforeText = [IO.File]::ReadAllText($fsOptions, [Text.Encoding]::UTF8)
    $matches = [regex]::Matches($beforeText, '(?<!\S)-zlz4hc(?!\S|,)')
    if ($matches.Count -ne 1) {
        throw "Expected exactly one plain -zlz4hc option in vendor fs options; found $($matches.Count)."
    }
    $afterText = $beforeText.Remove($matches[0].Index, $matches[0].Length).
        Insert($matches[0].Index, '-zlz4hc,level=12')
    [IO.File]::WriteAllText($fsOptions, $afterText, [Text.UTF8Encoding]::new($false))
    [ordered]@{
        partition = 'vendor_a'
        reference = 'validated B072 vendor_a EROFS blocks'
        reference_erofs_size = 1473019904
        filesystem = 'EROFS'
        codec = 'lz4hc'
        level = 12
        semantics = 'Compression density only; files, metadata, ownership, modes, SELinux contexts, UUID, and timestamp remain payload-controlled.'
        before_sha256 = $beforeHash
        effective_option = '-zlz4hc,level=12'
        after_sha256 = Hash $fsOptions
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $evidence 'vendor_erofs_pack_method.json') -Encoding UTF8
}
function Remove-ScratchChild([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $resolved = Full (Resolve-Path -LiteralPath $Path).Path
    $scratchFull = Full $scratch
    if ($resolved -eq $scratchFull -or
        -not $resolved.StartsWith($scratchFull + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing scratch-child delete: $resolved"
    }
    $reparse = @(Get-ChildItem -LiteralPath $resolved -Force -Recurse -Attributes ReparsePoint -ErrorAction SilentlyContinue)
    if ($reparse.Count) { throw "Refusing delete with nested reparse point: $($reparse[0].FullName)" }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
function Remove-ExpectedJunction([string]$Path, [string]$Target) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $item = Get-Item -LiteralPath $Path -Force
    if ($item.LinkType -ne 'Junction' -or (Full @($item.Target)[0]) -ne (Full $Target)) {
        throw "Junction cleanup guard failed: $Path"
    }
    Remove-Item -LiteralPath $Path -Force
}
function Remove-OwnedTree([string]$Path, [string]$Parent, [string]$LeafPrefix) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $resolved = Full (Resolve-Path -LiteralPath $Path).Path
    $parentFull = Full $Parent
    if ($resolved -eq $parentFull -or
        -not $resolved.StartsWith($parentFull + '\', [StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path -Leaf $resolved) -notlike "$LeafPrefix*") {
        throw "Owned-tree cleanup guard failed: $resolved"
    }
    $links = @(Get-ChildItem -LiteralPath $resolved -Force -Recurse -Attributes ReparsePoint -ErrorAction SilentlyContinue)
    if ($links.Count) { throw "Unexpected reparse point remains: $($links[0].FullName)" }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
function Remove-ScratchTree {
    Remove-ExpectedJunction (Join-Path $scratch 'tools') (Join-Path $workspace 'tools')
    Remove-ExpectedJunction (Join-Path $scratch 'work\android-sdk') (Join-Path $workRoot 'android-sdk')
    Remove-ExpectedJunction (Join-Path $scratch '【A官方】072') $official
    Remove-OwnedTree $scratch $workRoot 'v20_3a_scratch_'
}

try {
    New-Item -ItemType Directory -Path $scratch, $candidate, $evidence | Out-Null
    $before = @(Snapshot-Production)
    $before | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $evidence 'protected_before.json') -Encoding UTF8
    Record-Space before

    $resolvedCommit = Invoke-Captured git -C $repo rev-parse "$SourceCommit^{commit}"
    if ($resolvedCommit -ne $SourceCommit) { throw "SourceCommit did not resolve exactly: $resolvedCommit" }
    $sourceParent = Invoke-Captured git -C $repo rev-parse "$SourceCommit^"
    Invoke-Checked git -C $repo diff --check $sourceParent $SourceCommit -- @trackedBuildPaths
    & git -C $repo diff --binary "--output=$sourcePatch" $sourceParent $SourceCommit -- @trackedBuildPaths
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $sourcePatch -PathType Leaf) -or
        (Get-Item -LiteralPath $sourcePatch).Length -eq 0) {
        throw 'The allowlisted V20.3A source patch is empty or failed.'
    }
    $changedPaths = @((Invoke-Captured git -C $repo diff --name-only $sourceParent $SourceCommit --).Split("`n") |
        ForEach-Object { $_.Trim() } | Where-Object { $_ })
    $allowSet = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($path in $trackedBuildPaths) { [void]$allowSet.Add($path) }
    $unexpectedProduction = @($changedPaths | Where-Object {
        ($_ -match '^(?:\.github/workflows/build\.yml|app/|framework-stubs/|framework_patch/|payload/|scripts/)') -and
        -not $allowSet.Contains($_)
    })
    if ($unexpectedProduction.Count) {
        throw "SourceCommit changes production paths outside the V20.3A allowlist: $($unexpectedProduction -join ', ')"
    }
    [ordered]@{
        source_commit = $SourceCommit
        parent_commit = $sourceParent
        allowlist = $trackedBuildPaths
        changed_paths = $changedPaths
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $evidence 'source_allowlist.json') -Encoding UTF8

    $gh = Get-Command gh -ErrorAction SilentlyContinue
    if (-not $gh) { throw 'GitHub CLI is required to bind CiRunId to SourceCommit.' }
    $runJson = Invoke-Captured $gh.Source api "repos/xmy953538104/ZuiControl/actions/runs/$CiRunId"
    $run = $runJson | ConvertFrom-Json
    if ([string]$run.id -ne $CiRunId -or
        [string]$run.head_sha -ne $SourceCommit -or
        [string]$run.status -ne 'completed' -or
        [string]$run.conclusion -ne 'success' -or
        [string]$run.name -ne 'Build ZuiControl' -or
        [string]$run.head_repository.full_name -ne 'xmy953538104/ZuiControl') {
        throw 'CI run is not a successful Build ZuiControl run for the exact SourceCommit.'
    }
    $artifactJson = Invoke-Captured $gh.Source api "repos/xmy953538104/ZuiControl/actions/runs/$CiRunId/artifacts"
    $runArtifacts = @((($artifactJson | ConvertFrom-Json).artifacts))
    $requiredArtifacts = foreach ($name in @('ZuiControl-release-apk', 'zui-control-v19-payload')) {
        $match = @($runArtifacts | Where-Object { [string]$_.name -eq $name })
        if ($match.Count -ne 1 -or [bool]$match[0].expired) {
            throw "CI run must expose one non-expired $name artifact."
        }
        $digestMatch = [regex]::Match([string]$match[0].digest, '^sha256:([0-9a-fA-F]{64})$')
        if (-not $digestMatch.Success -or [int64]$match[0].size_in_bytes -le 0 -or
            [string]$match[0].id -notmatch '^\d+$') {
            throw "CI artifact metadata is incomplete or malformed for $name."
        }
        [ordered]@{
            name = [string]$match[0].name
            id = [string]$match[0].id
            digest = [string]$match[0].digest
            digest_sha256 = $digestMatch.Groups[1].Value.ToLowerInvariant()
            size_in_bytes = [int64]$match[0].size_in_bytes
            expired = [bool]$match[0].expired
            archive_download_url = [string]$match[0].archive_download_url
        }
    }
    $githubToken = (Invoke-Captured $gh.Source auth token).Trim()
    if (-not $githubToken) { throw 'GitHub CLI returned an empty authentication token.' }
    New-Item -ItemType Directory -Path $ciArtifact -Force | Out-Null
    foreach ($artifact in $requiredArtifacts) {
        $zipPath = Join-Path $scratch "ci_artifacts\$($artifact['name']).zip"
        Save-GitHubArtifactZip $artifact['id'] $zipPath $githubToken
        $zip = Get-Item -LiteralPath $zipPath
        $zipSha256 = Hash $zipPath
        if ($zip.Length -ne $artifact['size_in_bytes'] -or $zipSha256 -ne $artifact['digest_sha256']) {
            throw "Downloaded CI artifact ZIP failed API length/digest verification: $($artifact['name'])"
        }
        $artifact['actual_zip_length'] = [int64]$zip.Length
        $artifact['actual_zip_sha256'] = $zipSha256
        Expand-TrustedArtifactZip $zipPath (Join-Path $ciArtifact $artifact['name'])
    }
    $githubToken = $null
    foreach ($path in @($ciArtifact, $ciPayload)) { Require-Directory $path }
    foreach ($path in @($ciApk, $ciPayloadApk)) { Require-File $path }
    $downloadedInventory = @(Snapshot-Files $ciArtifact)
    $declaredInventory = @(Snapshot-Files $declaredCiArtifact)
    if (($downloadedInventory | ConvertTo-Json -Depth 4 -Compress) -ne
        ($declaredInventory | ConvertTo-Json -Depth 4 -Compress)) {
        throw 'Declared CI artifact directory differs from the API-digest-verified download.'
    }
    [ordered]@{
        run_id = [string]$run.id
        head_sha = [string]$run.head_sha
        status = [string]$run.status
        conclusion = [string]$run.conclusion
        workflow_name = [string]$run.name
        repository = [string]$run.head_repository.full_name
        url = [string]$run.html_url
        declared_artifact_path = $declaredCiArtifact
        authoritative_artifact_path = $ciArtifact
        artifacts = @($requiredArtifacts)
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $evidence 'ci_run_provenance.json') -Encoding UTF8

    Assert-ReleaseApk $ciApk
    if ((Hash $ciApk) -ne (Hash $ciPayloadApk)) {
        throw 'CI release APK and the APK staged in the same run payload differ.'
    }

    Invoke-Checked git clone --no-hardlinks --no-checkout $repo $scratchRepo
    Invoke-Checked git -C $scratchRepo config core.autocrlf false
    Invoke-Checked git -C $scratchRepo config core.eol lf
    Invoke-Checked git -C $scratchRepo checkout --detach $SourceCommit
    if ((Invoke-Captured git -C $scratchRepo rev-parse HEAD) -ne $SourceCommit) {
        throw 'Scratch source commit mismatch.'
    }
    Invoke-Checked $python (Join-Path $scratchRepo 'V20_3A_COMMAND_WAKEUP\tests\TestV20_3APolicy.py')
    $sourcePayloadInventory = @(Snapshot-Files (Join-Path $scratchRepo 'payload') '^system/priv-app/[^/]+/[^/]+\.apk$')
    $ciPayloadInventory = @(Snapshot-Files $ciPayload '^system/priv-app/[^/]+/[^/]+\.apk$')
    if (($sourcePayloadInventory | ConvertTo-Json -Depth 4 -Compress) -ne
        ($ciPayloadInventory | ConvertTo-Json -Depth 4 -Compress)) {
        throw 'CI payload content does not match SourceCommit (excluding the CI-built release APK).'
    }

    New-Item -ItemType Junction -Path (Join-Path $scratch 'tools') -Target (Join-Path $workspace 'tools') | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $scratch 'work') -Force | Out-Null
    New-Item -ItemType Junction -Path (Join-Path $scratch 'work\android-sdk') -Target (Join-Path $workRoot 'android-sdk') | Out-Null
    New-Item -ItemType Junction -Path (Join-Path $scratch '【A官方】072') -Target $official | Out-Null

    # AVB chain arguments cannot contain a Windows drive colon. Keep real key
    # copies at the validated relative Linux/pem path inside this scratch only.
    $scratchPem = Join-Path $scratch 'Linux\pem'
    New-Item -ItemType Directory -Path $scratchPem -Force | Out-Null
    foreach ($name in @('testkey_rsa2048.pem', 'testkey_rsa2048_pub.bin',
            'testkey_rsa4096.pem', 'testkey_rsa4096_pub.bin')) {
        $sourceKey = Join-Path $pemSource $name
        $scratchKey = Join-Path $scratchPem $name
        Require-File $sourceKey
        Copy-Item -LiteralPath $sourceKey -Destination $scratchKey
        if ((Hash $scratchKey) -ne (Hash $sourceKey)) { throw "AVB key copy mismatch: $name" }
    }

    @(
        "run_id=$RunId",
        "source_commit=$SourceCommit",
        "source_patch_sha256=$(Hash $sourcePatch)",
        "ci_run_id=$CiRunId",
        "declared_ci_artifact_path=$declaredCiArtifact",
        "authoritative_ci_artifact_path=$ciArtifact",
        "ci_apk_sha256=$(Hash $ciApk)",
        'artifact_provenance=release APK and payload downloaded by artifact ID from the successful CI run whose head SHA exactly equals source_commit; each raw ZIP length and SHA256 match the GitHub API receipt',
        'partitions=system_a and vendor_a are the only extracted/rebuilt payload partitions',
        'safety=no 9008 package preparation, adb, fastboot, Firehose, qdl-rs, or device write is invoked'
    ) | Set-Content -LiteralPath (Join-Path $evidence 'provenance.txt') -Encoding UTF8

    & $workShell -Root $scratch -Action CheckTools *>&1 | Tee-Object -FilePath $buildLog -Append
    & $workShell -Root $scratch -Action InitOfficial -OfficialDir $official *>&1 | Tee-Object -FilePath $buildLog -Append
    $superConfig = Get-Content -Raw -LiteralPath (Join-Path $scratch 'work\config\super_official_187.json') | ConvertFrom-Json
    if ((Full $superConfig.source_image) -ne (Full (Join-Path $official 'super.img')) -or
        [int64]$superConfig.file_size -ne (Get-Item -LiteralPath (Join-Path $official 'super.img')).Length) {
        throw 'WorkShell metadata is not from the explicit official A072 super.'
    }
    Record-Space after_init

    & $workShell -Root $scratch -Action UnpackSuper *>&1 | Tee-Object -FilePath $buildLog -Append
    $allPartitions = @('system_a', 'vendor_a', 'product_a', 'system_ext_a', 'odm_a', 'vendor_dlkm_a', 'system_dlkm_a')
    foreach ($name in $allPartitions) { Require-File (Join-Path $scratch "work\img\$name.img") }
    $untouchedPartitions = @('product_a', 'system_ext_a', 'odm_a', 'vendor_dlkm_a', 'system_dlkm_a')
    $untouchedBefore = [ordered]@{}
    foreach ($name in $untouchedPartitions) { $untouchedBefore[$name] = Hash (Join-Path $scratch "work\img\$name.img") }
    Record-Space after_unpack_super
    $scratchSuper = Join-Path $scratch 'work\img\super.img'
    if ((Full $scratchSuper).StartsWith((Full $scratch) + '\', [StringComparison]::OrdinalIgnoreCase) -and
        (Get-Item -LiteralPath $scratchSuper).Length -eq (Get-Item -LiteralPath (Join-Path $official 'super.img')).Length) {
        Remove-Item -LiteralPath $scratchSuper -Force
    } else { throw 'Refusing to remove unexpected scratch super.' }
    Record-Space after_delete_scratch_super

    $unpack = Join-Path $scratch 'work\unpack'
    New-Item -ItemType Directory -Path $unpack -Force | Out-Null
    foreach ($name in @('system_a', 'vendor_a')) {
        Invoke-Checked -Exe $extractErofs -Arguments @(
            '-i', (Join-Path $scratch "work\img\$name.img"), '-o', $unpack, '-x', '-f')
    }
    $unpackedNames = @(Get-ChildItem -LiteralPath $unpack -Directory | Where-Object Name -ne 'config' |
        Select-Object -ExpandProperty Name | Sort-Object)
    if (Compare-Object $unpackedNames @('system_a', 'vendor_a')) {
        throw "Only system_a/vendor_a may be unpacked: $($unpackedNames -join ', ')"
    }
    $vendorApkBefore = @(Snapshot-Files (Join-Path $unpack 'vendor_a') '^(?!.*\.apk$).*$')
    if ($vendorApkBefore.Count -ne 17) { throw "Expected exactly 17 vendor APKs before apply; found $($vendorApkBefore.Count)." }
    Record-Space after_extract

    $apply = Join-Path $scratchRepo 'scripts\ApplyZuiControlPayload.py'
    Invoke-CheckedLogged $python $apply --root $scratchRepo --unpack $unpack --payload $ciPayload --dry-run
    Invoke-CheckedLogged $python $apply --root $scratchRepo --unpack $unpack --payload $ciPayload
    Copy-Item -LiteralPath (Join-Path $scratch 'work\config\zui_control_payload_latest.json') -Destination (Join-Path $evidence 'zui_control_payload_actual.json')
    $vendorApkAfter = @(Snapshot-Files (Join-Path $unpack 'vendor_a') '^(?!.*\.apk$).*$')
    if ($vendorApkAfter.Count -ne 17 -or
        ($vendorApkBefore | ConvertTo-Json -Depth 4 -Compress) -ne
        ($vendorApkAfter | ConvertTo-Json -Depth 4 -Compress)) {
        throw 'Vendor APK 17/17 preservation gate failed.'
    }
    [ordered]@{
        unchanged = $true
        count = $vendorApkAfter.Count
        before_count = $vendorApkBefore.Count
        after_count = $vendorApkAfter.Count
        apks = $vendorApkAfter
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidence 'vendor_apk_preservation.json') -Encoding UTF8
    Set-VendorErofsProductionCompression
    Record-Space after_apply

    & $workShell -Root $scratch -Action PackImgAll -SkipImageBackup *>&1 | Tee-Object -FilePath $buildLog -Append
    foreach ($name in @('system_a', 'vendor_a')) { Require-File (Join-Path $scratch "work\img\$name.img") }
    foreach ($name in $untouchedPartitions) {
        if ((Hash (Join-Path $scratch "work\img\$name.img")) -ne $untouchedBefore[$name]) {
            throw "System/vendor-only gate failed; $name changed before signing."
        }
    }
    $vendorMeta = Get-Content -Raw -LiteralPath (Join-Path $scratch 'work\config\images\vendor_a.json') | ConvertFrom-Json
    if ([string]$vendorMeta.erofs_compression -ne '-zlz4hc,level=12') {
        throw "Vendor EROFS level-12 gate failed: $($vendorMeta.erofs_compression)"
    }
    if ([int]$vendorMeta.original_link_count -ne 269 -or
        [int]$vendorMeta.generated_fs_config_entries -ne 0 -or
        [int]$vendorMeta.generated_file_context_entries -ne 0) {
        throw ('Vendor metadata gate failed: links={0}, generated_fs={1}, generated_contexts={2}' -f
            $vendorMeta.original_link_count, $vendorMeta.generated_fs_config_entries,
            $vendorMeta.generated_file_context_entries)
    }
    $vendorUnsigned = Join-Path $scratch 'work\img\vendor_a.img'
    $vendorUnsignedSize = (Get-Item -LiteralPath $vendorUnsigned).Length
    if ($vendorUnsignedSize -ne 1473019904) {
        throw "Rebuilt vendor EROFS size differs from validated B072: $vendorUnsignedSize"
    }
    [ordered]@{
        image = 'vendor_a.img'
        erofs_compression = [string]$vendorMeta.erofs_compression
        reference_erofs_size = 1473019904
        actual_erofs_size = $vendorUnsignedSize
        reference_size_match = $true
        original_link_count = [int]$vendorMeta.original_link_count
        generated_fs_config_entries = [int]$vendorMeta.generated_fs_config_entries
        generated_file_context_entries = [int]$vendorMeta.generated_file_context_entries
        sha256 = Hash $vendorUnsigned
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $evidence 'vendor_erofs_pack_result.json') -Encoding UTF8
    Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $scratch 'work\img\system_a.img'), (Join-Path $scratch 'work\img\vendor_a.img') |
        Format-Table -AutoSize | Out-String | Set-Content -LiteralPath (Join-Path $evidence 'rebuilt_partition_hashes.txt') -Encoding UTF8
    Record-Space after_pack_images
    Remove-ScratchChild $unpack
    Record-Space after_delete_unpack

    & $workShell -Root $scratch -Action SignNoFecDryRun -OfficialDir $official -TemplateDir $template -BootImage (Join-Path $template 'boot.img') *>&1 |
        Tee-Object -FilePath $buildLog -Append
    & $workShell -Root $scratch -Action SignNoFec -OfficialDir $official -TemplateDir $template -BootImage (Join-Path $template 'boot.img') *>&1 |
        Tee-Object -FilePath $buildLog -Append
    Copy-Item -LiteralPath (Join-Path $scratch 'work\config\avb_nofec_latest.json') -Destination (Join-Path $evidence 'avb_nofec_actual.json')
    $signedVendor = Join-Path $scratch 'work\img\vendor_a.img'
    Require-File $signedVendor
    $signedVendorHash = Hash $signedVendor
    [ordered]@{
        image = 'vendor_a.img'
        signed_size = (Get-Item -LiteralPath $signedVendor).Length
        signed_sha256 = $signedVendorHash
    } | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $evidence 'vendor_signed_image.json') -Encoding UTF8
    if ((Hash (Join-Path $scratch 'work\flash_img\boot.img')) -ne $expectedBootSha256) {
        throw 'Signed candidate does not use the approved high-rollback B072 boot.'
    }
    Record-Space after_sign
    Remove-ScratchChild (Join-Path $scratch 'work\official')

    # PackSuper must remain after SignNoFec because signing rewrites partition footers.
    & $workShell -Root $scratch -Action PackSuper -OfficialDir $official -SuperOutputPath (Join-Path $candidate 'super.img') -SkipSuperBackup *>&1 |
        Tee-Object -FilePath $buildLog -Append
    foreach ($name in @('boot.img', 'vbmeta_system.img', 'vbmeta.img')) {
        Copy-Item -LiteralPath (Join-Path $scratch "work\flash_img\$name") -Destination (Join-Path $candidate $name)
    }
    Copy-Item -LiteralPath $ciApk -Destination (Join-Path $candidate 'ZuiControl-v19-release.apk')
    Copy-Item -LiteralPath $ciApk -Destination (Join-Path $candidate 'ZuiControl-v19-system.apk')
    Record-Space after_pack_super
    Remove-ScratchChild (Join-Path $scratch 'work\img')

    $finalNames = @('boot.img', 'super.img', 'vbmeta_system.img', 'vbmeta.img',
        'ZuiControl-v19-release.apk', 'ZuiControl-v19-system.apk')
    $sumLines = foreach ($name in $finalNames) {
        $path = Join-Path $candidate $name
        Require-File $path
        $item = Get-Item -LiteralPath $path
        if ($item.LinkType) { throw "Candidate file is unexpectedly linked: $path" }
        '{0}  {1}' -f (Hash $path), $name
    }
    [IO.File]::WriteAllLines((Join-Path $candidate 'SHA256SUMS_ZuiControl_v19.txt'), $sumLines, [Text.UTF8Encoding]::new($false))
    Copy-Item -LiteralPath (Join-Path $candidate 'SHA256SUMS_ZuiControl_v19.txt') -Destination (Join-Path $evidence 'candidate_sha256.txt')

    $verifier = Join-Path $scratchRepo 'scripts\VerifyZuiControlFlashPackage.ps1'
    & $verifier -FlashDir $candidate -WorkDir (Join-Path $scratch 'work\verify_v20_3a_final') `
        -ExpectedVendorImageSha256 $signedVendorHash `
        -ExpectedVendorApkInventoryPath (Join-Path $evidence 'vendor_apk_preservation.json') *>&1 |
        Tee-Object -FilePath $verifyLog
    if ($LASTEXITCODE -ne 0 -or -not (Select-String -LiteralPath $verifyLog -SimpleMatch '"ok": true' -Quiet)) {
        throw 'Final candidate reverse verifier did not report ok=true.'
    }
    Record-Space after_verifier

    $after = @(Snapshot-Production)
    $after | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $evidence 'protected_after.json') -Encoding UTF8
    if (($before | ConvertTo-Json -Depth 4 -Compress) -ne ($after | ConvertTo-Json -Depth 4 -Compress)) {
        throw 'Protected production B072 changed during candidate build.'
    }

    [ordered]@{
        ok = $true
        candidate = $candidate
        run_id = $RunId
        source_commit = $SourceCommit
        source_patch_sha256 = Hash $sourcePatch
        ci_run_id = $CiRunId
        declared_ci_artifact_path = $declaredCiArtifact
        authoritative_ci_artifact_path = $ciArtifact
        ci_apk_sha256 = Hash $ciApk
        super_sha256 = Hash (Join-Path $candidate 'super.img')
        verifier_log = $verifyLog
        production_b072_unchanged = $true
        flashed = $false
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $evidence 'build_result.json') -Encoding UTF8

    if (-not $KeepScratch) { Remove-ScratchTree }
    Record-Space complete
    Get-Content -Raw -LiteralPath (Join-Path $evidence 'build_result.json')
} catch {
    if (Test-Path -LiteralPath $evidence -PathType Container) {
        $_ | Out-String | Add-Content -LiteralPath $buildLog -Encoding UTF8
    }
    if (-not $KeepScratch) {
        try {
            Remove-ScratchTree
            Remove-OwnedTree $candidate $workRoot 'v20_3a_candidate_'
            Write-Warning "Build failed; scratch/candidate cleaned, compact evidence retained: $evidence"
        } catch {
            if (Test-Path -LiteralPath $evidence -PathType Container) {
                $_ | Out-String | Add-Content -LiteralPath $buildLog -Encoding UTF8
            }
            Write-Warning "Build failed and safe cleanup could not complete; inspect exact RunId $RunId."
        }
    } else {
        Write-Warning "Build failed; -KeepScratch retained: $scratch ; $candidate"
    }
    throw
}
