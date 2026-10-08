$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$env:SMART_EXPENSE_RUN_LOCK_NAME='Global\SmartExpenseTracking.HermesExcelUpdate.BatchTest.'+[guid]::NewGuid().ToString('N')
Import-Module (Join-Path $PSScriptRoot 'SmartExpense.Excel.psm1') -Force -DisableNameChecking
$repo=Split-Path -Parent $PSScriptRoot
$testRoot=Join-Path ([IO.Path]::GetTempPath()) ('smart-expense-batch-tests-'+[guid]::NewGuid().ToString('N'))
$passed=0
function Assert-Batch([bool]$Condition,[string]$Message){if(-not $Condition){throw $Message};$script:passed++}
function New-BatchTree {
    $root=Join-Path $testRoot ([guid]::NewGuid().ToString('N'))
    $inbox=Join-Path $root 'logs'
    New-Item -ItemType Directory -Path $inbox -Force | Out-Null
    return [pscustomobject]@{Root=$root;Inbox=$inbox;State=(Join-Path $root 'state/processed.json');Log=(Join-Path $root 'state/log.jsonl')}
}
function New-TypedExpense {
    param([string]$Id='99999999-1234-4123-8123-123456789012')
    $p=Get-Content -Raw -LiteralPath (Join-Path $repo 'fixtures/handoff/valid/expense_20261005_120000_99999999.json') | ConvertFrom-Json -DateKind String
    $p.expenseId=$Id
    return $p
}
function New-ImageExpense($Tree){
    $p=New-TypedExpense '88888888-1234-4123-8123-123456789012'
    $p.schemaVersion=2;$p.inputSource='image';$p.hasReceiptImage=$true;$p.extractionStatus='confirmed'
    $p.receiptImageRelativePath=Get-HermesExpectedImagePath ([datetimeoffset]$p.createdAt) ([guid]$p.expenseId)
    $path=Join-Path $Tree.Root $p.receiptImageRelativePath
    New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
    [IO.File]::WriteAllBytes($path,[byte[]]@(255,216,255,217))
    return $p
}
function Write-Batch($Tree,[object]$Payload,[string]$Name='expense_batch_20261007_140000_aaaaaaaa.json'){
    $path=Join-Path $Tree.Inbox $Name
    ConvertTo-Json -InputObject $Payload -Depth 100 | Set-Content -LiteralPath $path -NoNewline
    return Get-Item -LiteralPath $path
}
$script:updaterCalls=0;$script:rows=0
$updater={param($path,$work,$dry)
    $script:updaterCalls++
    $results=@(foreach($item in $work){
        if(-not $dry){$script:rows++}
        [pscustomobject]@{FileName=$item.FileName;ExpenseId=$item.Validation.ExpenseId;Outcome=if($dry){'eligible'}else{'inserted'};ReasonCode='test';Message='test';Sheet=$item.Validation.ReceiptMonth;Row=12;Candidates=@()}
    })
    return [pscustomobject]@{Saved=(-not $dry);Items=$results}
}
function Invoke-TestProcessor($Tree,[switch]$DryRun,[scriptblock]$Updater=$updater){
    return Invoke-HermesProcessor -OneDriveRoot $Tree.Root -InboxRelativePath 'logs' -WorkbookRelativePath 'fake.xlsx' -StatePath $Tree.State -LogPath $Tree.Log -WorkbookUpdater $Updater -DryRun:$DryRun
}
try{
    # Mixed schemas, deterministic offset-preserving names, immutable source archive.
    $tree=New-BatchTree;$image=New-ImageExpense $tree;$typed=New-TypedExpense
    $batch=Write-Batch $tree @($image,$typed)
    $result=Invoke-TestProcessor $tree
    Assert-Batch ($result.counts.inserted -eq 2 -and $result.counts.discovered -eq 2) 'Mixed batch did not process both expenses.'
    Assert-Batch (Test-Path -LiteralPath (Join-Path $tree.Inbox 'receipt_batches_expanded/expense_batch_20261007_140000_aaaaaaaa.json')) 'Source list was not archived.'
    Assert-Batch (Test-Path -LiteralPath (Join-Path $tree.Inbox 'receipt_jsons_done/expense_20261005_120000_99999999.json')) 'Local timezone filename was not retained.'
    Assert-Batch ((Read-HermesState $tree.State).records.Count -eq 2) 'Expense id state was not recorded.'
    $calls=$script:updaterCalls;$rows=$script:rows
    $batch=Write-Batch $tree @($image,$typed)
    $result=Invoke-TestProcessor $tree
    Assert-Batch ($script:updaterCalls -eq $calls -and $script:rows -eq $rows) 'Batch replay wrote duplicate workbook rows.'
    Assert-Batch (-not (Test-Path -LiteralPath $batch.FullName)) 'Identical replay source was not archived safely.'

    # Whole-array validation rejects later invalid members without partial publication.
    foreach($kind in @('invalid','duplicate','empty','object','null','filename','missing_image')){
        $tree=New-BatchTree;$first=New-TypedExpense;$second=New-TypedExpense '77777777-1234-4123-8123-123456789012'
        $payload=@($first,$second);$name='expense_batch_20261007_140000_aaaaaaaa.json'
        switch($kind){
            'invalid'{$second.totalAmount=-1}
            'duplicate'{$second.expenseId=$first.expenseId}
            'empty'{$payload=@()}
            'object'{$payload=$first}
            'null'{$payload=@($first,$null)}
            'filename'{$name='expense_batch_20261307_140000_aaaaaaaa.json'}
            'missing_image'{$second.schemaVersion=2;$second.inputSource='image';$second.hasReceiptImage=$true;$second.extractionStatus='confirmed';$second.receiptImageRelativePath=Get-HermesExpectedImagePath ([datetimeoffset]$second.createdAt) ([guid]$second.expenseId)}
        }
        $batch=Write-Batch $tree $payload $name;$hash=Get-HermesFileSha256 $batch.FullName;$calls=$script:updaterCalls
        $result=Invoke-TestProcessor $tree
        Assert-Batch ($result.counts.invalidError -eq 1) "Invalid $kind batch was not rejected."
        Assert-Batch (@(Get-HermesOrdinalFiles $tree.Inbox).Count -eq 0 -and $calls -eq $script:updaterCalls) "Invalid $kind batch published members or called the workbook updater."
        $quarantine=Join-Path (Join-Path $tree.Inbox 'receipt_jsons_error') $name
        Assert-Batch ((Test-Path -LiteralPath $quarantine) -and (Get-HermesFileSha256 $quarantine) -ceq $hash -and -not (Test-Path -LiteralPath $batch.FullName)) 'Invalid source must be quarantined with original bytes preserved.'
        $sidecar=Join-Path (Split-Path -Parent $quarantine) (([IO.Path]::GetFileNameWithoutExtension($name))+'.result.json')
        Assert-Batch ((Get-Content -Raw -LiteralPath $sidecar | ConvertFrom-Json).reasonCode -eq $result.items[0].reasonCode) 'Invalid batch quarantine must retain its actionable reason.'
    }

    # Single-item arrays stay arrays; dry run receives virtual per-expense input.
    $tree=New-BatchTree;$batch=Write-Batch $tree @(New-TypedExpense)
    $before=@(Get-ChildItem -LiteralPath $tree.Root -File -Recurse | ForEach-Object {$_.FullName+':'+(Get-HermesFileSha256 $_.FullName)})
    $result=Invoke-TestProcessor $tree -DryRun
    $after=@(Get-ChildItem -LiteralPath $tree.Root -File -Recurse | ForEach-Object {$_.FullName+':'+(Get-HermesFileSha256 $_.FullName)})
    Assert-Batch ($result.counts.discovered -eq 1 -and $result.items[0].outcome -eq 'eligible') 'Dry run did not report single array member.'
    Assert-Batch (($before -join '|') -ceq ($after -join '|') -and -not (Test-Path (Join-Path $tree.Root 'state'))) 'Dry run changed files or created state.'

    $tree=New-BatchTree;$invalid=New-TypedExpense;$invalid.totalAmount=-1
    $batch=Write-Batch $tree @($invalid);$hash=Get-HermesFileSha256 $batch.FullName
    $result=Invoke-TestProcessor $tree -DryRun
    Assert-Batch ($result.counts.invalidError -eq 1 -and (Test-Path -LiteralPath $batch.FullName) -and (Get-HermesFileSha256 $batch.FullName) -ceq $hash) 'Invalid dry run changed or quarantined the source.'
    Assert-Batch (@(Get-ChildItem -LiteralPath $tree.Inbox).Count -eq 1 -and -not (Test-Path (Join-Path $tree.Root 'state'))) 'Invalid dry run staged members or wrote state.'

    $tree=New-BatchTree;$batch=Write-Batch $tree @(New-TypedExpense)
    [IO.File]::WriteAllText($batch.FullName,'[{malformed json]')
    $hash=Get-HermesFileSha256 $batch.FullName;$result=Invoke-TestProcessor $tree
    $quarantine=Join-Path $tree.Inbox ('receipt_jsons_error/'+$batch.Name)
    Assert-Batch ($result.items[0].reasonCode -eq 'invalid_batch_json' -and (Get-HermesFileSha256 $quarantine) -ceq $hash -and @(Get-HermesOrdinalFiles $tree.Inbox).Count -eq 0) 'Malformed batch JSON did not quarantine intact without staging.'

    # Resume a crash after the first singleton commit; whitespace/order is irrelevant.
    $tree=New-BatchTree;$first=New-TypedExpense;$second=New-TypedExpense '77777777-1234-4123-8123-123456789012'
    $existing=Join-Path $tree.Inbox 'expense_20261005_120000_99999999.json'
    $first | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $existing -NoNewline
    $batch=Write-Batch $tree @($first,$second)
    $result=Invoke-TestProcessor $tree
    Assert-Batch ($result.counts.inserted -eq 2) 'Partial batch staging was not resumed.'

    # Collision on the later member blocks all writes and protects original bytes.
    $tree=New-BatchTree;$first=New-TypedExpense;$second=New-TypedExpense '77777777-1234-4123-8123-123456789012'
    $changed=New-TypedExpense $second.expenseId;$changed.totalAmount=500
    $existing=Join-Path $tree.Inbox 'expense_20261005_120000_77777777.json'
    $changed | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $existing -NoNewline
    $hash=Get-HermesFileSha256 $existing;$calls=$script:updaterCalls
    $batch=Write-Batch $tree @($first,$second);$result=Invoke-TestProcessor $tree
    Assert-Batch ($result.counts.failed -eq 1 -and $result.items[0].reasonCode -eq 'batch_member_conflict') 'Conflicting member was not identified.'
    Assert-Batch ($calls -eq $script:updaterCalls -and (Get-HermesFileSha256 $existing) -ceq $hash) 'Collision overwrote data or reached the workbook.'
    Assert-Batch (-not (Test-Path -LiteralPath (Join-Path $tree.Inbox 'expense_20261005_120000_99999999.json'))) 'Collision preflight published the earlier member.'

    # Archive failure after staging cannot process a partial expansion. Retry resumes.
    $tree=New-BatchTree;$first=New-TypedExpense;$second=New-TypedExpense '77777777-1234-4123-8123-123456789012'
    $obstruction=Join-Path $tree.Inbox 'receipt_batches_expanded'
    [IO.File]::WriteAllText($obstruction,'test archive obstruction')
    $batch=Write-Batch $tree @($first,$second);$calls=$script:updaterCalls
    $result=Invoke-TestProcessor $tree
    Assert-Batch ($result.counts.failed -eq 1 -and $result.items[0].reasonCode -eq 'batch_expansion_failed') 'Post-staging archive failure was not reported.'
    Assert-Batch ($calls -eq $script:updaterCalls -and (Test-Path -LiteralPath $batch.FullName) -and @(Get-HermesOrdinalFiles $tree.Inbox).Count -eq 2) 'Incomplete expansion processed members or lost its source.'
    Remove-Item -LiteralPath $obstruction
    $result=Invoke-TestProcessor $tree
    Assert-Batch ($result.counts.inserted -eq 2) 'Retry after archive failure did not resume safely.'

    # Existing review policy applies per member, without workbook writes for USD.
    $tree=New-BatchTree;$typed=New-TypedExpense;$typed.currency='USD'
    $batch=Write-Batch $tree @($typed);$calls=$script:updaterCalls
    $result=Invoke-TestProcessor $tree
    Assert-Batch ($result.counts.review -eq 1 -and $calls -eq $script:updaterCalls) 'Non-CAD member bypassed existing review policy.'
    $batch=Write-Batch $tree @($typed);$result=Invoke-TestProcessor $tree
    Assert-Batch ($result.counts.failed -eq 0 -and $calls -eq $script:updaterCalls) 'Replayed review member conflicted or reached workbook.'

    # Singleton processing remains available beside batch files.
    $tree=New-BatchTree;$typed=New-TypedExpense
    $typed | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath (Join-Path $tree.Inbox 'expense_20261005_120000_99999999.json') -NoNewline
    $result=Invoke-TestProcessor $tree
    Assert-Batch ($result.counts.inserted -eq 1) 'Existing singleton workflow changed.'

    # Workbook outage leaves published singletons retryable after list archival.
    $tree=New-BatchTree;$batch=Write-Batch $tree @(New-TypedExpense)
    $result=Invoke-TestProcessor $tree -Updater {throw 'workbook_locked'}
    Assert-Batch ($result.counts.deferred -eq 1 -and @(Get-HermesOrdinalFiles $tree.Inbox).Count -eq 1) 'Workbook outage lost a staged expense.'
    $result=Invoke-TestProcessor $tree
    Assert-Batch ($result.counts.inserted -eq 1) 'Staged expense was not processed on retry.'
    Write-Host "Batch handoff unit tests passed: $passed assertions."
}finally{
    $full=[IO.Path]::GetFullPath($testRoot)
    $temp=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')+'\'
    if($full.StartsWith($temp,[StringComparison]::OrdinalIgnoreCase) -and (Split-Path -Leaf $full) -like 'smart-expense-batch-tests-*'){
        if(Test-Path -LiteralPath $full){Remove-Item -LiteralPath $full -Recurse -Force}
    }
}
