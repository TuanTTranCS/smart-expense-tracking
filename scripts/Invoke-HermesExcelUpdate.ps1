[CmdletBinding()]
param(
    [string]$OneDriveRoot,
    [string]$InboxRelativePath = 'Documents\2_Others\Expenses_finance\logs',
    [string]$WorkbookRelativePath = 'Documents\2_Others\Expenses_finance\Canada plan.xlsx',
    [string]$StatePath = (Join-Path $env:LOCALAPPDATA 'SmartExpenseTracking\Hermes\processed-expenses.json'),
    [string]$LogPath = (Join-Path $env:LOCALAPPDATA 'SmartExpenseTracking\Hermes\processor.jsonl'),
    [switch]$DryRun,
    [ValidateSet('Json','Hermes')][string]$OutputFormat = 'Json'
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'SmartExpense.Excel.psm1') -Force -DisableNameChecking

$started = [datetimeoffset]::UtcNow
$result = $null
try {
    $resolvedRoot = Get-HermesOneDriveRoot -ExplicitRoot $OneDriveRoot
    $result = Invoke-HermesProcessor -OneDriveRoot $resolvedRoot -InboxRelativePath $InboxRelativePath -WorkbookRelativePath $WorkbookRelativePath `
        -StatePath $StatePath -LogPath $LogPath -DryRun:$DryRun
} catch {
    $result = [pscustomobject]@{
        schemaVersion = 1
        startedAt = $started.ToString('o')
        completedAt = [datetimeoffset]::UtcNow.ToString('o')
        outcome = 'run_failure'
        counts = [pscustomobject]@{ discovered=0; inserted=0; exactDuplicate=0; review=0; invalidError=0; deferred=0; failed=1 }
        items = @([pscustomobject]@{ fileName=$null; outcome='failed'; reasonCode='run_failure'; message=$_.Exception.Message })
    }
    if (-not $DryRun) {
        try {
            $logDirectory = Split-Path -Parent $LogPath
            New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
            ($result | ConvertTo-Json -Depth 12 -Compress) | Add-Content -LiteralPath $LogPath -Encoding utf8
        } catch { }
    }
}

Write-Output (Format-HermesOutput -Result $result -OutputFormat $OutputFormat)
if ($result.outcome -eq 'run_failure') { exit 1 }
