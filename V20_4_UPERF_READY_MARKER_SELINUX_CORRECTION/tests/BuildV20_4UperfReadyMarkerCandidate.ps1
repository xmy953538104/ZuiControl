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
$builder = Join-Path $PSScriptRoot '..\..\V20_3B_DAEMON_RETIREMENT\tests\BuildV20_3BCandidate.ps1'
if (-not (Test-Path -LiteralPath $builder -PathType Leaf)) {
    throw "Missing shared candidate builder: $builder"
}

& $builder @PSBoundParameters -Phase V20_4_UPERF_READY_MARKER
