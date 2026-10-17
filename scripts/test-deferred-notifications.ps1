$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$env:SMART_EXPENSE_RUN_LOCK_NAME='Global\SmartExpenseTracking.DeferredNotifications.Test.'+[guid]::NewGuid().ToString('N')
Import-Module (Join-Path $PSScriptRoot 'SmartExpense.Excel.psm1') -Force -DisableNameChecking
$passed=0
function Assert([bool]$Condition,[string]$Message){if(-not $Condition){throw $Message};$script:passed++}
$root=Join-Path ([IO.Path]::GetTempPath()) ('expense-deferred-notifications-'+[guid]::NewGuid().ToString('N'))
$inbox=Join-Path $root 'logs'
$statePath=Join-Path $root 'state.json'
New-Item -ItemType Directory -Force -Path $inbox | Out-Null
$payload=Get-Content -LiteralPath (Join-Path $PSScriptRoot '../fixtures/handoff/valid/expense_20261005_120000_99999999.json') -Raw | ConvertFrom-Json -DateKind String
$name=Get-HermesPayloadFileName $payload
$source=Join-Path $inbox $name
Write-HermesAtomicJson $source $payload
$probe=[pscustomobject]@{attempts=0;message='workbook_locked';outcome='deferred'}
$updater={param($workbook,$candidates,$dry)
    $probe.attempts++
    [pscustomobject]@{Saved=($probe.outcome -eq 'inserted');Items=@($candidates|ForEach-Object {
        [pscustomobject]@{FileName=$_.FileName;ExpenseId=$_.Validation.ExpenseId;Outcome=$probe.outcome;ReasonCode=$(if($probe.outcome -eq 'deferred'){'workbook_deferred'}else{'inserted'});Message=$probe.message;Sheet=$_.Validation.ReceiptMonth;Row=12;Candidates=@()}
    })}
}.GetNewClosure()
function Invoke-TestProcessor([switch]$DryRun){
    Invoke-HermesProcessor -OneDriveRoot $root -InboxRelativePath 'logs' -WorkbookRelativePath 'unused.xlsx' -StatePath $statePath -LogPath (Join-Path $root 'processor.jsonl') -WorkbookUpdater $updater -DryRun:$DryRun
}
try{
    $dry=Invoke-TestProcessor -DryRun
    Assert ($dry.counts.deferred -eq 1) 'Dry run hid an initial deferral'
    Assert (-not (Test-Path -LiteralPath "$statePath.notification-state.json")) 'Dry run persisted notification cache'
    $first=Invoke-TestProcessor
    Assert ($first.counts.deferred -eq 1 -and $first.counts.discovered -eq 1) 'First deferral was not reportable'
    Assert ((Format-HermesOutput $first Hermes) -match 'workbook_deferred') 'First deferral omitted actionable reason'
    $cacheHash=Get-HermesFileSha256 "$statePath.notification-state.json"
    $dryRetry=Invoke-TestProcessor -DryRun
    Assert ($dryRetry.counts.deferred -eq 1) 'Dry run suppressed a queued deferral'
    Assert ((Get-HermesFileSha256 "$statePath.notification-state.json") -ceq $cacheHash) 'Dry run changed notification cache'
    # Reload the module to exercise durable notification state rather than memory.
    Import-Module (Join-Path $PSScriptRoot 'SmartExpense.Excel.psm1') -Force -DisableNameChecking
    $quiet=Invoke-TestProcessor
    Assert ($quiet.items.Count -eq 0 -and $quiet.counts.deferred -eq 0 -and $quiet.counts.discovered -eq 0) 'Unchanged retry inflated reportable items/counts'
    Assert ((Format-HermesOutput $quiet Hermes) -ceq '[SILENT]') 'Unchanged retry sent a notification'
    Assert ($probe.attempts -eq 4 -and (Test-Path -LiteralPath $source)) 'Suppression prevented retry or removed queued file'
    $logged=Get-Content -LiteralPath (Join-Path $root 'processor.jsonl') -Tail 1 | ConvertFrom-Json
    Assert ($logged.items.Count -eq 0 -and $logged.counts.deferred -eq 0) 'JSONL fallback would repeat a deferred notification'
    $probe.message='workbook_read_only'
    $changed=Invoke-TestProcessor
    Assert ($changed.counts.deferred -eq 1) 'Changed dependency failure was suppressed'
    Assert ((Invoke-TestProcessor).counts.discovered -eq 0) 'Changed reason repeated on its next retry'
    $payload.notes='Changed expense content'
    Write-HermesAtomicJson $source $payload
    Assert ((Invoke-TestProcessor).counts.deferred -eq 1) 'Changed handoff bytes reused the previous deferral identity'
    $probe.outcome='inserted';$probe.message='Completed'
    $completed=Invoke-TestProcessor
    Assert ($completed.counts.inserted -eq 1 -and (Format-HermesOutput $completed Hermes) -cne '[SILENT]') 'Success after deferral was hidden'
    Assert ((Test-Path -LiteralPath (Join-Path $inbox "receipt_jsons_done/$name")) -and -not (Test-Path -LiteralPath $source)) 'Success did not archive the original'
    $cache=Get-Content -LiteralPath "$statePath.notification-state.json" -Raw | ConvertFrom-Json -AsHashtable
    Assert ($cache.deferrals.Count -eq 0) 'Terminal outcome retained stale deferral cache'

    # Filtering one job must preserve unrelated reportable results and counts.
    Write-HermesAtomicJson $source $payload
    $deferred=New-HermesResultItem $name $payload.expenseId 'deferred' 'waiting' 'Waiting for dependency' $null $null
    $success=New-HermesResultItem 'expense_other.json' $null 'inserted' 'inserted' 'Completed' $null $null
    $counts=[ordered]@{discovered=2;deferred=1;inserted=1}
    $null=Remove-HermesRepeatedDeferrals $inbox $statePath @($deferred,$success) $counts
    $counts=[ordered]@{discovered=2;deferred=1;inserted=1}
    $mixed=@(Remove-HermesRepeatedDeferrals $inbox $statePath @($deferred,$success) $counts)
    Assert ($mixed.Count -eq 1 -and $mixed[0].outcome -ceq 'inserted' -and $counts.discovered -eq 1 -and $counts.inserted -eq 1 -and $counts.deferred -eq 0) 'Quiet retry hid or miscounted another job'
    Write-Output "Deferred notification unit/integration tests passed: $passed assertions."
}finally{
    $resolved=[IO.Path]::GetFullPath($root)
    $tempRoot=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')+'\'
    if(-not $resolved.StartsWith($tempRoot,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $resolved) -notlike 'expense-deferred-notifications-*'){throw "Unsafe cleanup path: $resolved"}
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
