[CmdletBinding()]
param(
    [ValidateSet('inventory','retire','rollback')][string]$Stage='inventory',
    [Parameter(Mandatory)][string]$AdbPath,
    [Parameter(Mandatory)][string]$PythonPath,
    [Parameter(Mandatory)][string]$TransactionDir,
    [string]$ApprovedInventorySha256='',
    [switch]$Execute
)
$ErrorActionPreference='Stop'
$arguments=@('-B',(Join-Path $PSScriptRoot 'retirement.py'),$Stage,'--adb',$AdbPath,'--transaction',$TransactionDir)
if($Execute){$arguments+='--execute'}
if($ApprovedInventorySha256){$arguments+=@('--approved-inventory-sha256',$ApprovedInventorySha256)}
& $PythonPath @arguments
if($LASTEXITCODE -ne 0){throw "Retirement stage failed: $LASTEXITCODE. Preserve transaction and inspect rollback receipt."}
