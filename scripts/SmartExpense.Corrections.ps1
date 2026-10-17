# Loaded inside SmartExpense.Excel so the same run lock covers insertion and correction.
function Write-HermesAtomicJson {
    param([string]$Path,[object]$Value)
    $parent=Split-Path -Parent $Path
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    $temp=Join-Path $parent ('.'+[guid]::NewGuid().ToString('N')+'.tmp')
    try {$Value | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $temp -Encoding utf8NoBOM -NoNewline;Move-Item -LiteralPath $temp -Destination $Path -Force}
    finally {if(Test-Path -LiteralPath $temp){Remove-Item -LiteralPath $temp -Force}}
}

function Get-HermesTextSha256([string]$Value) {
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Value))).ToLowerInvariant()
}

function Get-HermesPayloadJson([object]$Payload) {return ConvertTo-Json -InputObject $Payload -Depth 100 -Compress}

function Test-HermesPayloadEqual([object]$Left,[object]$Right) {
    if($null -eq $Left -or $null -eq $Right){return $null -eq $Left -and $null -eq $Right}
    if($Left -is [string] -or $Right -is [string]){return $Left -is [string] -and $Right -is [string] -and $Left -ceq $Right}
    if($Left -is [bool] -or $Right -is [bool]){return $Left -is [bool] -and $Right -is [bool] -and $Left -eq $Right}
    if((Test-HermesJsonNumber $Left) -and (Test-HermesJsonNumber $Right)){
        try{return [decimal]$Left -eq [decimal]$Right}catch{return [double]$Left -eq [double]$Right}
    }
    if($Left -is [array] -or $Right -is [array]){
        if($Left -isnot [array] -or $Right -isnot [array] -or $Left.Count -ne $Right.Count){return $false}
        for($i=0;$i -lt $Left.Count;$i++){if(-not (Test-HermesPayloadEqual $Left[$i] $Right[$i])){return $false}}
        return $true
    }
    $leftObject=$Left -is [Collections.IDictionary] -or $Left -is [pscustomobject]
    $rightObject=$Right -is [Collections.IDictionary] -or $Right -is [pscustomobject]
    if($leftObject -or $rightObject){
        if(-not $leftObject -or -not $rightObject){return $false}
        $leftKeys=@(if($Left -is [Collections.IDictionary]){$Left.Keys}else{$Left.PSObject.Properties.Name})
        $rightKeys=@(if($Right -is [Collections.IDictionary]){$Right.Keys}else{$Right.PSObject.Properties.Name})
        if($leftKeys.Count -ne $rightKeys.Count){return $false}
        foreach($key in $leftKeys){
            if(@($rightKeys|Where-Object {$_ -ceq $key}).Count -ne 1){return $false}
            $leftValue=$null;$rightValue=$null
            if($Left -is [Collections.IDictionary]){$leftValue=$Left[$key]}else{$leftValue=$Left.PSObject.Properties[$key].Value}
            if($Right -is [Collections.IDictionary]){$rightValue=$Right[$key]}else{$rightValue=$Right.PSObject.Properties[$key].Value}
            if(-not (Test-HermesPayloadEqual $leftValue $rightValue)){return $false}
        }
        return $true
    }
    return $Left.GetType() -eq $Right.GetType() -and $Left -ceq $Right
}

function Get-HermesPayloadFileName([object]$Payload) {
    $created=[datetimeoffset]$Payload.createdAt;$short=([guid]$Payload.expenseId).ToString('N').Substring(0,8)
    return ('expense_{0}_{1}.json' -f $created.ToString('yyyyMMdd_HHmmss'),$short)
}

function Test-HermesCorrection {
    param([object]$Operation,[string]$FileName,[string]$OneDriveRoot)
    try {
        foreach($field in @('correctionProtocolVersion','operationId','expenseId','expectedRevision','expectedPayloadSha256','expectedPayloadJson','payload')){if(-not $Operation.PSObject.Properties[$field]){throw "Missing $field"}}
        if(-not (Test-HermesJsonInteger $Operation.correctionProtocolVersion) -or $Operation.correctionProtocolVersion -ne 1){throw 'Unsupported correctionProtocolVersion'}
        $operationId=[guid]::Empty;$expenseId=[guid]::Empty
        if($Operation.operationId -isnot [string] -or -not [guid]::TryParseExact($Operation.operationId,'D',[ref]$operationId) -or $operationId.ToString() -cne $Operation.operationId){throw 'Invalid operationId'}
        if($FileName -cne "expense_correction_$($Operation.operationId).json"){throw 'Operation filename mismatch'}
        if($Operation.expenseId -isnot [string] -or -not [guid]::TryParseExact($Operation.expenseId,'D',[ref]$expenseId) -or $expenseId.ToString() -cne $Operation.expenseId){throw 'Invalid expenseId'}
        if(-not (Test-HermesJsonInteger $Operation.expectedRevision) -or $Operation.expectedRevision -lt 0){throw 'Invalid expectedRevision'}
        if($Operation.expectedPayloadJson -isnot [string] -or $Operation.expectedPayloadSha256 -isnot [string] -or $Operation.expectedPayloadSha256 -cnotmatch '^[0-9a-f]{64}$' -or (Get-HermesTextSha256 $Operation.expectedPayloadJson) -cne $Operation.expectedPayloadSha256){throw 'Expected payload hash mismatch'}
        $prior=$Operation.expectedPayloadJson | ConvertFrom-Json -DateKind String
        if($Operation.payload.expenseId -cne $Operation.expenseId -or $prior.expenseId -cne $Operation.expenseId){throw 'Expense identity mismatch'}
        if(-not $Operation.payload.PSObject.Properties['itemType'] -or $Operation.payload.itemType -cnotin @('pending','processed') -or $Operation.payload.extractionStatus -cne 'manual'){throw 'Correction needs explicit itemType and manual provenance'}
        foreach($field in @('schemaVersion','expenseId','createdAt','sourceDeviceId','sourceDeviceName','inputSource','hasReceiptImage','receiptImageRelativePath','receiptImageUri','originalImageFileName','receiptPhotoLink')){
            $old=$prior.PSObject.Properties[$field];$new=$Operation.payload.PSObject.Properties[$field]
            if(($null -eq $old) -ne ($null -eq $new)){throw "Changed immutable field $field"}
            if($null -ne $old -and -not (Test-HermesPayloadEqual $old.Value $new.Value)){throw "Changed immutable field $field"}
        }
        $valid=Test-HermesHandoff $Operation.payload (Get-HermesPayloadFileName $Operation.payload) $OneDriveRoot
        if(-not $valid.Valid){if($valid.ReasonCode -ceq 'missing_image'){return [pscustomobject]@{Valid=$false;Retryable=$true;Message=$valid.Message}};throw $valid.Message}
        if($valid.Currency -cne 'CAD'){throw 'Corrections require CAD'}
        return [pscustomobject]@{Valid=$true;Retryable=$false;Prior=$prior;Validation=$valid}
    } catch {return [pscustomobject]@{Valid=$false;Retryable=$false;Message=$_.Exception.Message}}
}

function Get-HermesPublicCorrectionState {
    param([string]$Inbox,[string]$ExpenseId,[object]$Record)
    if($null -ne $Record -and $Record.Contains('currentPayload')){
        $value=[ordered]@{correctionProtocolVersion=1;expenseId=$ExpenseId;revision=$Record.appliedRevision;payload=((Get-HermesPayloadJson $Record.currentPayload)|ConvertFrom-Json -DateKind String);status='processed'}
        if($Record.Contains('operationId')){$value.operationId=$Record.operationId}
        return [pscustomobject]$value
    }
    $path=Join-Path $Inbox "receipt_correction_state/$ExpenseId.json"
    if($null -eq $Record -and (Test-Path -LiteralPath $path)){return Get-Content -LiteralPath $path -Raw | ConvertFrom-Json -DateKind String}
    if($null -ne $Record){
        $archive=Join-Path $Inbox ('receipt_jsons_done/'+$Record.sourceFileName)
        if(Test-Path -LiteralPath $archive){return [pscustomobject]@{correctionProtocolVersion=1;expenseId=$ExpenseId;revision=0;payload=(Get-Content -LiteralPath $archive -Raw|ConvertFrom-Json -DateKind String);status='processed'}}
    }
    return $null
}

function Publish-HermesCorrectionState {
    param([string]$Inbox,[object]$Payload,[int64]$Revision=0,[string]$OperationId)
    $value=[ordered]@{correctionProtocolVersion=1;expenseId=$Payload.expenseId;revision=$Revision;payload=$Payload;status='processed'}
    if($OperationId){$value.operationId=$OperationId}
    Write-HermesAtomicJson (Join-Path $Inbox "receipt_correction_state/$($Payload.expenseId).json") $value
}

function Sync-HermesCorrectionProjection {
    param([string]$Inbox,[object]$State,[string]$StatePath)
    $changed=$false
    if(Test-Path -LiteralPath $StatePath){$persisted=Get-Content -LiteralPath $StatePath -Raw|ConvertFrom-Json -DateKind String;$changed=$persisted.schemaVersion -ne 2}
    foreach($key in $State.records.Keys){
        $record=$State.records[$key]
        $current=Get-HermesPublicCorrectionState $Inbox $key $record
        if($null -eq $current){continue}
        if(-not $record.Contains('currentPayload')){$record.currentPayload=$current.payload;$record.appliedRevision=0;$changed=$true}
        $path=Join-Path $Inbox "receipt_correction_state/$key.json"
        $projected=$null
        if(Test-Path -LiteralPath $path){try{$projected=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json -DateKind String}catch{}}
        if($null -eq $projected -or -not (Test-HermesPayloadEqual $current $projected)){Publish-HermesCorrectionState $Inbox $current.payload $current.revision $(if($current.PSObject.Properties['operationId']){$current.operationId}else{$null})}
    }
    if($changed){Write-HermesState $State $StatePath}
}

function Write-HermesCorrectionAck {
    param([string]$Inbox,[object]$Operation,[string]$Status,[string]$Reason,[int64]$Revision,[object]$Payload,[string]$StatePath,[object]$State,[string]$Message)
    $ack=[ordered]@{correctionProtocolVersion=1;operationId=$Operation.operationId;expenseId=$Operation.expenseId;status=$Status;reasonCode=$Reason;expectedRevision=$Operation.expectedRevision;revision=$Revision;processedAt=[datetimeoffset]::UtcNow.ToString('o')}
    if($null -ne $Payload){$ack.payload=$Payload}
    if($StatePath){if($null -eq $State){$State=Read-HermesState $StatePath};$State.operations[$Operation.operationId]=[ordered]@{operation=$Operation;ack=$ack};if($PSBoundParameters.ContainsKey('Message')){$State.operations[$Operation.operationId].message=$Message};Write-HermesState $State $StatePath}
    Write-HermesAtomicJson (Join-Path $Inbox "receipt_correction_acks/$($Operation.operationId).json") $ack
}

# The local ledger remains authoritative even after the source operation is archived.
function Sync-HermesCorrectionAcks {
    param([string]$Inbox,[object]$State)
    foreach($entry in $State.operations.Values){
        $path=Join-Path $Inbox "receipt_correction_acks/$($entry.operation.operationId).json"
        if(-not (Test-Path -LiteralPath $path)){Write-HermesAtomicJson $path $entry.ack}
    }
}

function Move-HermesCorrectionTerminal {
    param([string]$Inbox,[string]$SourcePath,[string]$Status,[string]$Reason,[string]$Message)
    $folder=Join-Path $Inbox $(switch($Status){'applied'{'receipt_jsons_done'}'conflict'{'receipt_jsons_review'}default{'receipt_jsons_error'}})
    $destination=Join-Path $folder ([IO.Path]::GetFileName($SourcePath))
    # Check before writing a sidecar: an existing different operation is never replaced.
    if((Test-Path -LiteralPath $destination) -and (Get-HermesFileSha256 $SourcePath) -cne (Get-HermesFileSha256 $destination)){throw "archive_conflict: $destination contains different bytes"}
    if($Status -cne 'applied'){Write-HermesSidecar $folder ([IO.Path]::GetFileName($SourcePath)) $(if($Status -ceq 'conflict'){'review'}else{'error'}) $Reason $Message|Out-Null}
    Move-HermesHandoff $SourcePath $folder -StateProvesCompletion|Out-Null
}

function Get-HermesPendingCorrections {
    param([string]$Inbox,[string]$OneDriveRoot,[object]$State,[string]$StatePath)
    $overlays=@{}
    foreach($file in @(Get-ChildItem -LiteralPath $Inbox -File | Where-Object Name -CLike 'expense_correction_*.json' | Sort-Object Name -CaseSensitive)){
        try {$operation=Get-Content -LiteralPath $file.FullName -Raw|ConvertFrom-Json -DateKind String;$test=Test-HermesCorrection $operation $file.Name $OneDriveRoot
            $pendingJournal=Join-Path ("$StatePath.correction-journal") "$($operation.operationId).json"
            $publicPath=Join-Path $Inbox "receipt_correction_state/$($operation.expenseId).json"
            $saved=$null;$recoverKnown=$false
            if(Test-Path -LiteralPath $pendingJournal){$saved=Get-Content -LiteralPath $pendingJournal -Raw|ConvertFrom-Json -DateKind String;if(-not (Test-HermesPayloadEqual $saved.operation $operation)){continue}}
            if($State.records.Contains($operation.expenseId)){
                $known=$State.records[$operation.expenseId]
                $recoverKnown=$known.appliedRevision -eq 0 -and $known.Contains('currentPayload') -and (Test-HermesPayloadEqual $known.currentPayload $operation.payload) -and $null -ne $saved -and $saved.PSObject.Properties['targetSheet'] -and $saved.targetSheet -ceq $known.sheet -and $saved.targetRow -eq $known.row -and -not (Test-Path -LiteralPath $publicPath)
            }
            if($test.Valid -and $operation.payload.itemType -ceq 'pending' -and $operation.expectedRevision -eq 0 -and (-not $State.records.Contains($operation.expenseId) -or $recoverKnown)){
                # Conflicting first revisions cannot both acquire the same source.
                if($overlays.ContainsKey($operation.expenseId)){$overlays[$operation.expenseId]=$null}else{$overlays[$operation.expenseId]=$operation}
            }
        } catch {continue}
    }
    return $overlays
}

function Complete-HermesOriginalCorrectionState {
    param([string]$Inbox,[object]$Payload,[object]$Operation,[string]$StatePath,[object]$State)
    if($null -eq $State){$State=Read-HermesState $StatePath}
    $record=$State.records[$Payload.expenseId]
    if($null -eq $Operation){
        if(-not $record.Contains('currentPayload')){$record.currentPayload=$Payload;$record.appliedRevision=0;Write-HermesState $State $StatePath}
        $existing=Join-Path $Inbox "receipt_correction_state/$($Payload.expenseId).json"
        if(-not (Test-Path -LiteralPath $existing)){Publish-HermesCorrectionState $Inbox $record.currentPayload $record.appliedRevision $(if($record.Contains('operationId')){$record.operationId}else{$null})}
    } else {
        $record.currentPayload=$Payload;$record.appliedRevision=1;$record.operationId=$Operation.operationId
        Write-HermesState $State $StatePath
        Publish-HermesCorrectionState $Inbox $Payload 1 $Operation.operationId
        Write-HermesCorrectionAck $Inbox $Operation 'applied' 'pending_updated' 1 $Payload $StatePath $State
        # Archival is retried by the queue after an applied ack; never downgrade application.
        try{Move-HermesCorrectionTerminal $Inbox (Join-Path $Inbox "expense_correction_$($Operation.operationId).json") 'applied' 'pending_updated' 'Pending correction applied.'}catch{}
    }
}

function Get-HermesCellSnapshot {
    param([object]$Sheet,[int]$Row)
    $h=$Sheet.Cells.Item($Row,8);$link=$null;if($h.Hyperlinks.Count -gt 0){$link=[string]$h.Hyperlinks.Item(1).Address}
    return [pscustomobject]@{amount=$Sheet.Cells.Item($Row,6).Value2;description=$Sheet.Cells.Item($Row,7).Value2;image=$h.Value2;hyperlink=$link;formulas=@(6..8|ForEach-Object {[string]$Sheet.Cells.Item($Row,$_).Formula})}
}

function Get-HermesExpectedCells {
    param([object]$Validation)
    return [pscustomobject]@{amount=[double]$Validation.Amount;description=$Validation.Description;image=if($Validation.HasReceiptImage){$Validation.ReceiptImageRelativePath}else{$null};hyperlink=if($Validation.HasReceiptImage){Get-HermesWorkbookHyperlinkPath $Validation.ReceiptImageRelativePath}else{$null}}
}

function Test-HermesCellsMatch {
    param([object]$Actual,[object]$Expected)
    foreach($field in @('amount','description','image','hyperlink')){if([string]$Actual.$field -cne [string]$Expected.$field){return $false}}
    if($Actual.PSObject.Properties['formulas']){foreach($formula in $Actual.formulas){if($formula -match '^='){return $false}}}
    return $true
}

function Set-HermesCorrectionCells {
    param([object]$Sheet,[int]$Row,[object]$Cells)
    $Sheet.Cells.Item($Row,8).Hyperlinks.Delete()|Out-Null
    $Sheet.Range("F${Row}:H${Row}").ClearContents()|Out-Null
    if($null -ne $Cells){$Sheet.Cells.Item($Row,6).Value2=[double]$Cells.amount;$Sheet.Cells.Item($Row,7).Formula=([char]39)+[string]$Cells.description
        if($Cells.hyperlink){$Sheet.Hyperlinks.Add($Sheet.Cells.Item($Row,8),$Cells.hyperlink,'','',$Cells.image)|Out-Null}}
}

function Invoke-HermesWorkbookCorrection {
    param([string]$WorkbookPath,[object]$Operation,[object]$Current,[object]$Record,[object]$State,[string]$JournalPath,[string]$OneDriveRoot,[switch]$DryRun)
    if(-not (Test-Path -LiteralPath $WorkbookPath)){throw 'workbook_unavailable'}
    if(Test-Path -LiteralPath (Join-Path (Split-Path $WorkbookPath -Parent) ('~$'+[IO.Path]::GetFileName($WorkbookPath)))){throw 'workbook_locked'}
    $excel=$null;$book=$null
    try {
        $excel=New-Object -ComObject Excel.Application;$excel.Visible=$false;$excel.DisplayAlerts=$false;$excel.EnableEvents=$false
        $book=$excel.Workbooks.Open($WorkbookPath,$null,$DryRun.IsPresent);if($book.ReadOnly -and -not $DryRun){throw 'workbook_read_only'}
        if($Record.outcome -cne 'inserted'){throw 'conflict_shared_or_legacy_duplicate_row'}
        foreach($key in $State.records.Keys){$other=$State.records[$key];if($key -cne $Operation.expenseId -and $other.sheet -ceq $Record.sheet -and $other.row -eq $Record.row){throw 'conflict_shared_row'}}
        $oldValidation=Test-HermesHandoff $Current.payload (Get-HermesPayloadFileName $Current.payload) $OneDriveRoot
        $newValidation=(Test-HermesCorrection $Operation "expense_correction_$($Operation.operationId).json" $OneDriveRoot).Validation
        $before=Get-HermesExpectedCells $oldValidation;$after=Get-HermesExpectedCells $newValidation
        $source=$book.Worksheets.Item($Record.sheet);Test-HermesMonthlySheetStructure $source $Record.sheet|Out-Null
        $sourceRow=[int]$Record.row;$destination=$source;$destinationRow=$sourceRow;$journal=$null
        if(Test-Path -LiteralPath $JournalPath){$journal=Get-Content -LiteralPath $JournalPath -Raw|ConvertFrom-Json -DateKind String
            if(-not (Test-HermesPayloadEqual $journal.operation $Operation)){throw 'conflict_operation_id_reused'}
            $source=$book.Worksheets.Item($journal.sourceSheet);$sourceRow=[int]$journal.sourceRow
            try{$destination=$book.Worksheets.Item($journal.targetSheet)}catch{$destination=Ensure-HermesMonthlySheet $book $journal.targetSheet}
            $destinationRow=[int]$journal.targetRow
            $sourceCells=Get-HermesCellSnapshot $source $sourceRow;$targetCells=Get-HermesCellSnapshot $destination $destinationRow
            $sourceCleared=[string]::IsNullOrEmpty([string]$sourceCells.amount) -and [string]::IsNullOrEmpty([string]$sourceCells.description) -and [string]::IsNullOrEmpty([string]$sourceCells.image) -and [string]::IsNullOrEmpty([string]$sourceCells.hyperlink) -and @($sourceCells.formulas|Where-Object {$_ -match '^='}).Count -eq 0
            if((Test-HermesCellsMatch $targetCells $after) -and (($source.Name -ceq $destination.Name -and $sourceRow -eq $destinationRow) -or $sourceCleared)){return [pscustomobject]@{Sheet=$destination.Name;Row=$destinationRow;Recovered=$true}}
        }
        if(-not (Test-HermesCellsMatch (Get-HermesCellSnapshot $source $sourceRow) $before)){throw 'conflict_independent_excel_edit'}
        $newRecord=[pscustomobject]@{ReceiptDate=$newValidation.ReceiptDate.Date;Amount=$newValidation.Amount;NormalizedMerchant=(Normalize-HermesMerchant $newValidation.MerchantName)}
        $others=@(Get-HermesWorkbookRecords $book|Where-Object {-not ($_.Sheet -ceq $source.Name -and $_.Row -eq $sourceRow)})
        $duplicate=Get-HermesDuplicateDecision $newRecord $others;if($duplicate.Outcome -cne 'inserted'){throw 'conflict_duplicate_target'}
        if($newValidation.ReceiptMonth -cne $source.Name){
            if($null -eq $journal){$destination=Ensure-HermesMonthlySheet $book $newValidation.ReceiptMonth;$rows=@();for($r=12;$r -le 100;$r++){$rows+=@{F=$destination.Cells.Item($r,6).Value2;G=$destination.Cells.Item($r,7).Value2}};$slot=Find-HermesSafeTransactionRow $rows;if($null -eq $slot.Row){throw 'workbook_capacity'};$destinationRow=[int]$slot.Row}
            $target=Get-HermesCellSnapshot $destination $destinationRow
            if(-not [string]::IsNullOrEmpty([string]$target.amount) -or -not [string]::IsNullOrEmpty([string]$target.description) -or -not [string]::IsNullOrEmpty([string]$target.image) -or -not [string]::IsNullOrEmpty([string]$target.hyperlink) -or @($target.formulas|Where-Object {$_ -match '^='}).Count -gt 0){throw 'conflict_target_not_empty'}
        }
        if(-not $DryRun){
            if($null -eq $journal){Write-HermesAtomicJson $JournalPath ([ordered]@{operation=$Operation;sourceSheet=$source.Name;sourceRow=$sourceRow;targetSheet=$destination.Name;targetRow=$destinationRow;before=$before;after=$after;phase='prepared'})}
            Set-HermesCorrectionCells $destination $destinationRow $after
            if($source.Name -cne $destination.Name -or $sourceRow -ne $destinationRow){Set-HermesCorrectionCells $source $sourceRow $null}
            $summary=@(Add-HermesSummaryMonth $book $newValidation.ReceiptMonth)[-1];$excel.Calculate();if([string]$summary.Sheet.Cells.Item($summary.CoverageStatusRow,6).Value2 -cne 'OK'){throw 'summary_coverage_failed'}
            $book.Save()
        }
        return [pscustomobject]@{Sheet=$destination.Name;Row=$destinationRow;Recovered=$false}
    } finally {if($book){try{$book.Close($false)}catch{};[Runtime.InteropServices.Marshal]::ReleaseComObject($book)|Out-Null};if($excel){try{$excel.Quit()}catch{};[Runtime.InteropServices.Marshal]::ReleaseComObject($excel)|Out-Null};[GC]::Collect();[GC]::WaitForPendingFinalizers()}
}

function Invoke-HermesCorrectionQueue {
    param([string]$Inbox,[string]$OneDriveRoot,[string]$WorkbookPath,[string]$StatePath,[switch]$DryRun)
    $state=Read-HermesState $StatePath
    if(-not $DryRun){Sync-HermesCorrectionAcks $Inbox $state}
    $files=@(Get-ChildItem -LiteralPath $Inbox -File | Where-Object Name -CLike 'expense_correction_*.json' | Sort-Object Name -CaseSensitive)
    # expectedRevision determines replay order; UUID names do not encode chronology.
    $operations=@(foreach($file in $files){try{[pscustomobject]@{File=$file;Operation=(Get-Content -LiteralPath $file.FullName -Raw|ConvertFrom-Json -DateKind String);Error=$null}}catch{[pscustomobject]@{File=$file;Operation=$null;Error=$_.Exception.Message}}})
    foreach($entry in @($operations|Sort-Object -Property @{Expression={if($_.Operation -and $_.Operation.PSObject.Properties['expectedRevision']){$_.Operation.expectedRevision}else{[int64]::MaxValue}}},@{Expression={$_.File.Name}} -CaseSensitive)){
        if($null -eq $entry.Operation){
            if(-not $DryRun){try{Move-HermesCorrectionTerminal $Inbox $entry.File.FullName 'error' 'invalid_correction_json' $entry.Error}catch{New-HermesResultItem $entry.File.Name $null 'failed' 'correction_archive_failed' $_.Exception.Message $null $null;continue}}
            New-HermesResultItem $entry.File.Name $null 'error' 'invalid_correction_json' $entry.Error $null $null;continue
        }
        $file=$entry.File;$op=$entry.Operation;$reason='invalid_correction';$status='conflict';$revision=0;$current=$null;$valid=[pscustomobject]@{Valid=$false;Retryable=$false};$canAck=$true;$previous=$null;$terminal=$false
        try {
            $valid=Test-HermesCorrection $op $file.Name $OneDriveRoot
            if($op.PSObject.Properties['operationId'] -and $op.operationId -is [string] -and $state.operations.Contains($op.operationId)){
                if($file.Name -cne "expense_correction_$($op.operationId).json"){$canAck=$false;throw 'Operation filename mismatch'}
                $completed=$state.operations[$op.operationId]
                if(-not (Test-HermesPayloadEqual $completed.operation $op)){$canAck=$false;throw 'Operation ID reused with different bytes'}
                $previous=$completed
                if($completed.ack.status -cin @('applied','conflict')){
                    $terminal=$true
                    if(-not $DryRun){
                        $terminalMessage=if($completed.Contains('message') -and -not [string]::IsNullOrWhiteSpace([string]$completed.message)){$completed.message}else{'Refresh the current expense and confirm a new correction to resolve this conflict.'}
                        Move-HermesCorrectionTerminal $Inbox $file.FullName $completed.ack.status $completed.ack.reasonCode $terminalMessage
                    }
                    continue
                }
            }
            if(-not $valid.Valid -and -not $valid.Retryable){throw $valid.Message}
            $journalPath=Join-Path ("$StatePath.correction-journal") "$($op.operationId).json"
            if($valid.Retryable){$status='deferred';$reason='missing_image';throw $valid.Message}
            $record=if($state.records.Contains($op.expenseId)){$state.records[$op.expenseId]}else{$null}
            $current=Get-HermesPublicCorrectionState $Inbox $op.expenseId $record
            if($null -eq $record -or $null -eq $current){$status='deferred';$reason='original_not_processed';throw 'Wait for original handoff and durable row identity'}
            if(-not $record.Contains('currentPayload')){$record.currentPayload=$current.payload;$record.appliedRevision=$current.revision;if(-not $DryRun){Write-HermesState $state $StatePath}}
            if(-not $DryRun -and -not (Test-Path -LiteralPath (Join-Path $Inbox "receipt_correction_state/$($op.expenseId).json"))){Publish-HermesCorrectionState $Inbox $current.payload}
            $revision=[int64]$current.revision
            if($op.payload.itemType -ceq 'pending' -and $revision -eq 0 -and (Test-Path -LiteralPath $journalPath) -and (Test-HermesPayloadEqual $current.payload $op.payload)){
                $pendingJournal=Get-Content -LiteralPath $journalPath -Raw|ConvertFrom-Json -DateKind String
                if($pendingJournal.phase -ceq 'pending_prepared' -and (Test-HermesPayloadEqual $pendingJournal.operation $op)){
                    $record.appliedRevision=1;$record.operationId=$op.operationId
                    if(-not $DryRun){Write-HermesState $state $StatePath}
                    $current.revision=1;$current|Add-Member -NotePropertyName operationId -NotePropertyValue $op.operationId -Force;$revision=1
                }
            }
            if($current.PSObject.Properties['operationId'] -and $current.operationId -ceq $op.operationId){
                if(-not (Test-HermesPayloadEqual $current.payload $op.payload)){throw 'Operation ID reused'}
                if(-not (Test-Path -LiteralPath $journalPath)){throw 'Missing prepared operation journal'}
                $savedJournal=Get-Content -LiteralPath $journalPath -Raw|ConvertFrom-Json -DateKind String
                if(-not (Test-HermesPayloadEqual $savedJournal.operation $op)){throw 'Operation journal identity mismatch'}
                if(-not $DryRun){Publish-HermesCorrectionState $Inbox $current.payload $current.revision $op.operationId}
                $status='applied';$reason='updated'
            } else {
                if($op.payload.itemType -ceq 'pending'){$reason='processing_changed';throw 'Original was processed; confirm a processed correction'}
                if($revision -ne $op.expectedRevision){$reason='stale_revision';throw 'Refresh current revision'}
                if(-not (Test-HermesPayloadEqual $current.payload $valid.Prior)){$reason='stale_payload';throw 'Expected prior payload differs from durable current payload'}
                $reason='workbook_correction_failed'
                $result=Invoke-HermesWorkbookCorrection $WorkbookPath $op $current $record $state $journalPath $OneDriveRoot -DryRun:$DryRun
                if($DryRun){New-HermesResultItem $file.Name $op.expenseId 'deferred' 'dry_run_correction_eligible' 'Workbook was not changed.' $result.Sheet $result.Row;continue}
                $record.sheet=$result.Sheet;$record.row=$result.Row
                $revision++
                $record.currentPayload=$op.payload;$record.appliedRevision=$revision;$record.operationId=$op.operationId
                Write-HermesState $state $StatePath
                Publish-HermesCorrectionState $Inbox $op.payload $revision $op.operationId
                $status='applied';$reason='updated'
            }
            if(-not $DryRun){
                Write-HermesCorrectionAck $Inbox $op $status $reason $revision $op.payload $StatePath $state
                $terminal=$true
                try{Move-HermesCorrectionTerminal $Inbox $file.FullName 'applied' $reason 'Excel correction applied.'}catch{New-HermesResultItem $file.Name $op.expenseId 'failed' 'correction_archive_failed' $_.Exception.Message $record.sheet $record.row}
            }
            New-HermesResultItem $file.Name $op.expenseId 'updated' $reason 'Excel correction applied.' $record.sheet $record.row `
                -SourceDeviceName ([string]$op.payload.sourceDeviceName) -Merchant $valid.Validation.MerchantName `
                -Amount $valid.Validation.Amount -ReceiptDate $valid.Validation.ReceiptDate -Currency $valid.Validation.Currency
        } catch {
            $message=$_.Exception.Message
            if($terminal){New-HermesResultItem $file.Name $op.expenseId 'failed' 'correction_archive_failed' $message $null $null;continue}
            if($reason -eq 'workbook_correction_failed'){
                if($message -match '^conflict_'){$reason=$message}else{$status='deferred';$reason='workbook_deferred'}
            }
            $unchangedDeferred=$status -ceq 'deferred' -and $null -ne $previous -and $previous.ack.status -ceq $status -and $previous.ack.reasonCode -ceq $reason -and $previous.Contains('message') -and $previous.message -ceq $message
            if(-not $DryRun -and ($valid.Valid -or $valid.Retryable) -and $canAck -and -not $unchangedDeferred){
                Write-HermesCorrectionAck $Inbox $op $status $reason $revision $(if($current){$current.payload}else{$null}) $StatePath $state -Message $message
            }
            if(-not $DryRun -and $status -cne 'deferred'){
                try{Move-HermesCorrectionTerminal $Inbox $file.FullName $(if($valid.Valid){'conflict'}else{'error'}) $reason $message}catch{New-HermesResultItem $file.Name $op.expenseId 'failed' 'correction_archive_failed' $_.Exception.Message $null $null}
            }
            if(-not $unchangedDeferred){New-HermesResultItem $file.Name $(if($op.PSObject.Properties['expenseId']){$op.expenseId}else{$null}) $(if($status -eq 'deferred'){'deferred'}elseif(-not $valid.Valid){'error'}else{'review'}) $reason $message $null $null}
        }
    }
}
