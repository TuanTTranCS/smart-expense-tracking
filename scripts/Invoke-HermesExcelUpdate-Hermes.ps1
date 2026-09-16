[CmdletBinding()]
param(
    [string]$OneDriveRoot,
    [string]$InboxRelativePath = 'Documents\2_Others\Expenses_finance\logs',
    [string]$WorkbookRelativePath = 'Documents\2_Others\Expenses_finance\Canada plan.xlsx',
    [string]$StatePath = (Join-Path $env:LOCALAPPDATA 'SmartExpenseTracking\Hermes\processed-expenses.json'),
    [string]$LogPath = (Join-Path $env:LOCALAPPDATA 'SmartExpenseTracking\Hermes\processor.jsonl'),
    [switch]$DryRun
)

$entry = Join-Path $PSScriptRoot 'Invoke-HermesExcelUpdate.ps1'
$arguments = @{
    InboxRelativePath = $InboxRelativePath
    WorkbookRelativePath = $WorkbookRelativePath
    StatePath = $StatePath
    LogPath = $LogPath
    OutputFormat = 'Hermes'
}
if ($OneDriveRoot) { $arguments.OneDriveRoot = $OneDriveRoot }
if ($DryRun) { $arguments.DryRun = $true }
& $entry @arguments
exit $LASTEXITCODE
