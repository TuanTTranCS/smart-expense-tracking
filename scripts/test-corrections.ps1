param([string]$NotificationResultPath,[string]$QuietDeferredResultPath)
$ErrorActionPreference='Stop'
$env:SMART_EXPENSE_RUN_LOCK_NAME='Global\SmartExpenseTracking.Corrections.Test.'+[guid]::NewGuid().ToString('N')
Import-Module (Join-Path $PSScriptRoot 'SmartExpense.Excel.psm1') -Force -DisableNameChecking
$passed=0
function Assert($Condition,[string]$Message){if(-not $Condition){throw $Message};$script:passed++}
$root=Join-Path ([IO.Path]::GetTempPath()) ('expense-corrections-'+[guid]::NewGuid().ToString('N'))
$inbox=Join-Path $root 'logs';New-Item -ItemType Directory -Path $inbox -Force|Out-Null
$statePath=Join-Path $root 'state.json'
$payload=[pscustomobject]@{schemaVersion=3;expenseId='aaaaaaaa-1111-4111-8111-111111111111';createdAt='2026-10-16T12:00:00-07:00';sourceDeviceId='test';sourceDeviceName='Test';inputSource='typed';hasReceiptImage=$false;receiptImageRelativePath=$null;receiptDate='2026-10-16';merchantName='Test shop';totalAmount=10;currency='CAD';extractionStatus='manual';notes='Original notes';extension=[pscustomobject]@{unknown='retained'}}
$filename=Get-HermesPayloadFileName $payload
$fixtures=Join-Path $PSScriptRoot '../fixtures/corrections'
$fixturePending=Get-Content -LiteralPath (Join-Path $fixtures 'pending-v3.json') -Raw|ConvertFrom-Json -DateKind String
Assert ((Test-HermesHandoff $fixturePending (Get-HermesPayloadFileName $fixturePending) $root).Valid) 'Pending itemType fixture rejected'
$fixtureUnsupported=Get-Content -LiteralPath (Join-Path $fixtures 'unsupported-item-type-v3.json') -Raw|ConvertFrom-Json -DateKind String
Assert (-not (Test-HermesHandoff $fixtureUnsupported (Get-HermesPayloadFileName $fixtureUnsupported) $root).Valid) 'Unsupported itemType fixture accepted'
$fixtureCorrection=Get-Content -LiteralPath (Join-Path $fixtures 'expense_correction_00000000-0000-4000-8000-000000000001.json') -Raw|ConvertFrom-Json -DateKind String
Assert ((Test-HermesCorrection $fixtureCorrection 'expense_correction_00000000-0000-4000-8000-000000000001.json' $root).Valid) 'Immutable correction fixture rejected'
function New-Operation([object]$Prior,[string]$ItemType='processed',[int]$Revision=0){
    $corrected=(Get-HermesPayloadJson $Prior)|ConvertFrom-Json -DateKind String
    $corrected|Add-Member -NotePropertyName itemType -NotePropertyValue $ItemType -Force
    $corrected.totalAmount=20
    $json=Get-HermesPayloadJson $Prior
    return [pscustomobject]@{correctionProtocolVersion=1;operationId=[guid]::NewGuid().ToString();expenseId=$Prior.expenseId;expectedRevision=$Revision;expectedPayloadJson=$json;expectedPayloadSha256=(Get-HermesTextSha256 $json);payload=$corrected}
}
function Save-Operation($Operation){$path=Join-Path $inbox "expense_correction_$($Operation.operationId).json";Write-HermesAtomicJson $path $Operation;return $path}
Assert ((Test-HermesHandoff $payload $filename $root).Valid) 'Legacy missing itemType rejected'
$payload|Add-Member -NotePropertyName itemType -NotePropertyValue 'pending'
Assert ((Test-HermesHandoff $payload $filename $root).Valid) 'Explicit pending rejected'
$payload.itemType='processed';Assert ((Test-HermesHandoff $payload $filename $root).Valid) 'Processed discriminator rejected by contract validation'
foreach($bad in @('unknown','Pending','',42,$null)){$payload.itemType=$bad;Assert (-not (Test-HermesHandoff $payload $filename $root).Valid) "Unsupported itemType accepted: $bad"}
$payload.itemType='pending'
$op=New-Operation $payload
Assert ((Test-HermesCorrection $op "expense_correction_$($op.operationId).json" $root).Valid) 'Valid processed envelope rejected'
$op.expectedPayloadSha256='0'*64;Assert (-not (Test-HermesCorrection $op "expense_correction_$($op.operationId).json" $root).Valid) 'Bad hash accepted';$op.expectedPayloadSha256=Get-HermesTextSha256 $op.expectedPayloadJson
$op.payload.createdAt='2026-10-17T12:00:00-07:00';Assert (-not (Test-HermesCorrection $op "expense_correction_$($op.operationId).json" $root).Valid) 'Changed creation identity accepted';$op.payload.createdAt=$payload.createdAt
Assert (-not (Test-HermesCorrection $op 'expense_correction_wrong.json' $root).Valid) 'Operation filename mismatch accepted'
Assert ((Test-HermesPayloadEqual $payload ($op.expectedPayloadJson|ConvertFrom-Json -DateKind String))) 'Semantic prior matching failed'
Assert ((Get-HermesTextSha256 'test') -ceq '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08') 'UTF8 SHA256 differs'
Assert (Test-HermesPayloadEqual ('{"amount":12.00}'|ConvertFrom-Json) ('{"amount":12}'|ConvertFrom-Json)) 'Numeric formatting changed payload identity'
Assert (Test-HermesPayloadEqual ('{"array":[1,null,[2.00,null]]}'|ConvertFrom-Json) ('{"array":[1.0,null,[2,null]]}'|ConvertFrom-Json)) 'Nested arrays/null/numbers not semantically equal'
Assert (-not (Test-HermesPayloadEqual ('{"array":[1,null,2]}'|ConvertFrom-Json) ('{"array":[1,2]}'|ConvertFrom-Json))) 'Null array member dropped'
Assert (-not (Test-HermesPayloadEqual ('{"array":[1,2]}'|ConvertFrom-Json) ('{"array":[2,1]}'|ConvertFrom-Json))) 'Array ordering ignored'
Assert (-not (Test-HermesPayloadEqual ('{"array":[[1,2]]}'|ConvertFrom-Json) ('{"array":[1,2]}'|ConvertFrom-Json))) 'Nested array shape lost'
$wrappedString=Get-HermesPayloadJson $payload
Assert (Test-HermesPayloadEqual $wrappedString ([string]::new($wrappedString.ToCharArray()))) 'PowerShell JSON string wrapper changed scalar identity'
Assert (-not (Test-HermesCellsMatch ([pscustomobject]@{amount=10;description='x';image=$null;hyperlink=$null;formulas=@('=5+5','x','')}) ([pscustomobject]@{amount=10;description='x';image=$null;hyperlink=$null}))) 'Independent formula accepted'

# Pending overlay is journaled and inserted once without mutating original bytes.
$op=New-Operation $payload 'pending';$opPath=Save-Operation $op
$original=Join-Path $inbox $filename;Write-HermesAtomicJson $original $payload
$originalHash=Get-HermesFileSha256 $original
$fake={param($book,$items,$dry)
    if($items[0].Validation.Amount -ne 20){throw 'Pending overlay was not passed to workbook'}
    [pscustomobject]@{Items=@($items|ForEach-Object {[pscustomobject]@{FileName=$_.FileName;ExpenseId=$_.Validation.ExpenseId;Outcome='inserted';ReasonCode='inserted';Message='test insert';Sheet='2026-10';Row=12;Candidates=@()}})}
}
$result=Invoke-HermesProcessor $root 'logs' 'unused.xlsx' $statePath (Join-Path $root 'log.jsonl') -WorkbookUpdater $fake
Assert ($result.counts.inserted -eq 1) 'Pending correction was not inserted once'
$ack=Get-Content -LiteralPath (Join-Path $inbox "receipt_correction_acks/$($op.operationId).json") -Raw|ConvertFrom-Json
Assert ($ack.status -ceq 'applied' -and $ack.revision -eq 1) 'Pending correction not acknowledged'
Assert ((Get-HermesFileSha256 (Join-Path $inbox "receipt_jsons_done/$filename")) -ceq $originalHash) 'Original archive mutated'
$current=Get-HermesPublicCorrectionState $inbox $payload.expenseId $null
Assert ($current.revision -eq 1 -and $current.payload.totalAmount -eq 20 -and $current.payload.extension.unknown -ceq 'retained') 'Published state lost correction or compatible unknown fields'
$replay=@(Invoke-HermesCorrectionQueue $inbox $root 'unused.xlsx' $statePath)
Assert ($replay.Count -eq 0) 'Applied immutable replay performed another update'
$op.payload.totalAmount=21;Save-Operation $op|Out-Null
$reused=@(Invoke-HermesCorrectionQueue $inbox $root 'unused.xlsx' $statePath)
Assert ($reused.Count -eq 1 -and $reused[0].outcome -ceq 'review') 'Operation ID reuse not rejected'

# An already-processed original never accepts a late pending correction.
$late=New-Operation $payload 'pending';Save-Operation $late|Out-Null
$lateResult=@(Invoke-HermesCorrectionQueue $inbox $root 'unused.xlsx' $statePath)
Assert ($lateResult[0].reasonCode -ceq 'processing_changed') 'Pending processing race silently applied'
Assert ((@(Invoke-HermesCorrectionQueue $inbox $root 'unused.xlsx' $statePath)).Count -eq 0) 'Permanent identical conflict notified again'
$stale=New-Operation $payload 'processed' 0;Save-Operation $stale|Out-Null
Assert ((@(Invoke-HermesCorrectionQueue $inbox $root 'unused.xlsx' $statePath))[0].reasonCode -ceq 'stale_revision') 'Stale revision accepted'
$stale=New-Operation $payload 'processed' 1;Save-Operation $stale|Out-Null
Assert ((@(Invoke-HermesCorrectionQueue $inbox $root 'unused.xlsx' $statePath))[0].reasonCode -ceq 'stale_payload') 'Stale payload accepted'

# Mock only the workbook boundary; state ordering, journal replay and acks are real.
$module=Get-Module SmartExpense.Excel
& $module {function script:Invoke-HermesWorkbookCorrection {
    param($WorkbookPath,$Operation,$Current,$Record,$State,$JournalPath,$OneDriveRoot,[switch]$DryRun)
    if(-not $DryRun){Write-HermesAtomicJson $JournalPath ([ordered]@{operation=$Operation;phase='prepared'})}
    [pscustomobject]@{Sheet='2026-11';Row=13;Recovered=$false}
}}
$processed=New-Operation $current.payload 'processed' 1;$processed.payload.totalAmount=30;$processed.payload.merchantName='Corrected shop';$processed.payload.receiptDate='2026-11-02';Save-Operation $processed|Out-Null
$notificationLog=Join-Path $root 'notification.jsonl'
$notification=Invoke-HermesProcessor $root 'logs' 'unused.xlsx' $statePath $notificationLog -WorkbookUpdater {throw 'Correction must not insert an original'}
$updated=@($notification.items)
Assert ($updated[0].outcome -ceq 'updated') 'Processed correction failed'
Assert ($notification.counts.discovered -eq 1 -and $notification.counts.updated -eq 1 -and $notification.counts.inserted -eq 0) 'Correction notification counts differ'
Assert ($updated[0].merchant -ceq 'Corrected shop' -and $updated[0].amount -eq 30 -and $updated[0].receiptDate -ceq '2026-11-02' -and $updated[0].currency -ceq 'CAD') 'Correction notification omitted corrected expense details'
Assert ($updated[0].sourceDeviceName -ceq 'Test') 'Correction notification omitted source device'
Assert (-not $updated[0].PSObject.Properties['notes'] -and -not $updated[0].PSObject.Properties['extension']) 'Correction notification copied unrelated payload fields'
$notificationJson=Format-HermesOutput $notification Json
$loggedNotification=Get-Content -LiteralPath $notificationLog -Raw|ConvertFrom-Json -DateKind String
Assert (Test-HermesPayloadEqual ($notificationJson|ConvertFrom-Json -DateKind String) $loggedNotification) 'Correction JSON and JSONL details differ'
$notificationText=Format-HermesOutput $loggedNotification Hermes
Assert ($notificationText.Contains('1 updated') -and $notificationText.Contains('**Corrected shop** — CAD 30.00 on 2026-11-02 _(Status: updated)_')) 'PowerShell correction notification omitted expense details'
if($NotificationResultPath){Write-HermesAtomicJson $NotificationResultPath $loggedNotification}
$latest=Get-HermesPublicCorrectionState $inbox $payload.expenseId $null
Assert ($latest.revision -eq 2 -and $latest.payload.totalAmount -eq 30) 'Processed correction state not advanced'
Assert ((Read-HermesState $statePath).records[$payload.expenseId].row -eq 13) 'Cross-month row identity not persisted'
Assert ((Read-HermesState $statePath).schemaVersion -eq 2 -and (Read-HermesState $statePath).records[$payload.expenseId].appliedRevision -eq 2) 'Durable v2 revision ledger missing'
$staleProjection=(Get-HermesPayloadJson $latest)|ConvertFrom-Json -DateKind String;$staleProjection.revision=0;$staleProjection.payload.totalAmount=1
Write-HermesAtomicJson (Join-Path $inbox "receipt_correction_state/$($payload.expenseId).json") $staleProjection
$authoritative=Get-HermesPublicCorrectionState $inbox $payload.expenseId (Read-HermesState $statePath).records[$payload.expenseId]
Assert ($authoritative.revision -eq 2 -and $authoritative.payload.totalAmount -eq 30) 'Lagging public projection replaced local durable revision'
Sync-HermesCorrectionProjection $inbox (Read-HermesState $statePath) $statePath
Assert ((Get-HermesPublicCorrectionState $inbox $payload.expenseId $null).revision -eq 2) 'Local ledger failed to repair stale cloud projection'
Assert ((@(Invoke-HermesCorrectionQueue $inbox $root 'unused.xlsx' $statePath)).Count -eq 0) 'Processed replay duplicated'
$tieLater=New-Operation $latest.payload 'processed' 2;$tieLater.operationId='00000000-0000-4000-8000-000000000003';$tieLater.payload.totalAmount=41;Save-Operation $tieLater|Out-Null
$tieFirst=New-Operation $latest.payload 'processed' 2;$tieFirst.operationId='00000000-0000-4000-8000-000000000002';$tieFirst.payload.totalAmount=42;Save-Operation $tieFirst|Out-Null
$tieResults=@(Invoke-HermesCorrectionQueue $inbox $root 'unused.xlsx' $statePath)
Assert ($tieResults.Count -eq 2 -and $tieResults[0].outcome -ceq 'updated' -and $tieResults[1].reasonCode -ceq 'stale_revision') 'Same-revision corrections not deterministically serialized'
Assert (-not $tieResults[1].PSObject.Properties['merchant'] -and -not $tieResults[1].PSObject.Properties['amount'] -and -not $tieResults[1].PSObject.Properties['receiptDate']) 'Conflicting correction disclosed unapplied expense details'
Assert ((Read-HermesState $statePath).records[$payload.expenseId].currentPayload.totalAmount -eq 42) 'Filename tie-breaker selected wrong correction'

# Missing originals wait and never create workbook entries.
$missing=New-Operation $payload 'processed';$missing.expenseId='bbbbbbbb-1111-4111-8111-111111111111';$missing.payload.expenseId=$missing.expenseId
$prior=$missing.expectedPayloadJson|ConvertFrom-Json;$prior.expenseId=$missing.expenseId;$missing.expectedPayloadJson=Get-HermesPayloadJson $prior;$missing.expectedPayloadSha256=Get-HermesTextSha256 $missing.expectedPayloadJson
Save-Operation $missing|Out-Null
Assert ((@(Invoke-HermesCorrectionQueue $inbox $root 'unused.xlsx' $statePath))[0].reasonCode -ceq 'original_not_processed') 'Missing original was not deferred'
Set-Content -LiteralPath (Join-Path $inbox 'expense_correction_invalid.json') -Value '{'
Set-Content -LiteralPath (Join-Path $inbox 'expense_correction_missing-fields.json') -Value '{}'
$malformed=@(Invoke-HermesCorrectionQueue $inbox $root 'unused.xlsx' $statePath)
Assert (@($malformed|Where-Object reasonCode -eq 'invalid_correction_json').Count -eq 1) 'Malformed JSON crashed correction scan'

# A prepared pending journal alone cannot claim an original processed meanwhile.
$raceRoot=Join-Path $root 'race';$raceInbox=Join-Path $raceRoot 'logs';New-Item -ItemType Directory -Path $raceInbox -Force|Out-Null
$raceStatePath=Join-Path $raceRoot 'state.json';$raceOp=New-Operation $payload 'pending'
$raceState=[ordered]@{schemaVersion=2;records=@{$payload.expenseId=[ordered]@{outcome='inserted';processedAt='2026-10-16T19:00:00Z';sourceFileName=$filename;sheet='2026-10';row=12;appliedRevision=0;currentPayload=$payload}};operations=@{}}
Write-HermesState $raceState $raceStatePath
Write-HermesAtomicJson (Join-Path $raceInbox $filename) $payload
Write-HermesAtomicJson (Join-Path $raceInbox "receipt_jsons_done/$filename") $payload
Write-HermesAtomicJson (Join-Path $raceInbox "expense_correction_$($raceOp.operationId).json") $raceOp
Write-HermesAtomicJson (Join-Path ("$raceStatePath.correction-journal") "$($raceOp.operationId).json") ([ordered]@{operation=$raceOp;phase='pending_prepared';targetSheet='2026-10';targetRow=12})
$overlays=Get-HermesPendingCorrections $raceInbox $raceRoot (Read-HermesState $raceStatePath) $raceStatePath
Assert (-not $overlays.ContainsKey($payload.expenseId)) 'Stale journal falsely acquired already processed original'
$raceResult=Invoke-HermesProcessor $raceRoot 'logs' 'unused.xlsx' $raceStatePath (Join-Path $raceRoot 'log.jsonl') -WorkbookUpdater {throw 'Known original must not invoke workbook'}
$raceAck=Get-Content -LiteralPath (Join-Path $raceInbox "receipt_correction_acks/$($raceOp.operationId).json") -Raw|ConvertFrom-Json
Assert ($raceAck.status -ceq 'conflict' -and $raceAck.reasonCode -ceq 'processing_changed') 'Stale journal falsely acknowledged pending correction'
Assert ((Read-HermesState $raceStatePath).records[$payload.expenseId].currentPayload.totalAmount -eq 10) 'Stale journal replaced original published values'
# Terminal routing preserves immutable bytes and produces actionable diagnostics.
Assert (-not (Test-Path -LiteralPath $opPath)) 'Applied pending operation remained in inbox'
Assert (Test-Path -LiteralPath (Join-Path $inbox "receipt_jsons_done/expense_correction_$($op.operationId).json")) 'Pending operation not archived in done'
Assert (Test-Path -LiteralPath (Join-Path $inbox "receipt_jsons_done/expense_correction_$($processed.operationId).json")) 'Processed operation not archived in done'
Assert (Test-Path -LiteralPath (Join-Path $inbox "receipt_jsons_review/expense_correction_$($late.operationId).json")) 'Conflict not quarantined for review'
$sidecar=Get-Content -LiteralPath (Join-Path $inbox "receipt_jsons_review/expense_correction_$($late.operationId).result.json") -Raw|ConvertFrom-Json
Assert ($sidecar.reasonCode -ceq 'processing_changed' -and $sidecar.message.Contains('confirm')) 'Conflict sidecar lacks recovery guidance'
foreach($badName in @('expense_correction_invalid','expense_correction_missing-fields')){
    Assert (Test-Path -LiteralPath (Join-Path $inbox "receipt_jsons_error/$badName.json")) 'Invalid operation not quarantined'
    Assert (Test-Path -LiteralPath (Join-Path $inbox "receipt_jsons_error/$badName.result.json")) 'Invalid operation lacks reason sidecar'
}
# Completed ledger identity cannot authorize a different source filename.
$genuineAck=Join-Path $inbox "receipt_correction_acks/$($processed.operationId).json";$genuineAckHash=Get-HermesFileSha256 $genuineAck
Copy-Item -LiteralPath (Join-Path $inbox "receipt_jsons_done/expense_correction_$($processed.operationId).json") -Destination (Join-Path $inbox 'expense_correction_wrong.json')
$wrongName=@(Invoke-HermesCorrectionQueue $inbox $root 'unused.xlsx' $statePath)
Assert ($wrongName.Count -eq 1 -and $wrongName[0].outcome -ceq 'error') 'Completed ledger bypassed canonical operation filename'
Assert (Test-Path -LiteralPath (Join-Path $inbox 'receipt_jsons_error/expense_correction_wrong.json')) 'Malformed replay filename not quarantined as invalid'
Assert (-not (Test-Path -LiteralPath (Join-Path $inbox 'receipt_jsons_done/expense_correction_wrong.json'))) 'Malformed replay filename archived as applied'
Assert ((Get-HermesFileSha256 $genuineAck) -ceq $genuineAckHash) 'Malformed filename replay changed genuine applied ack'
# A terminal replay reconstructs the original actionable reason text.
$conflictName="expense_correction_$($late.operationId)"
Copy-Item -LiteralPath (Join-Path $inbox "receipt_jsons_review/$conflictName.json") -Destination (Join-Path $inbox "$conflictName.json")
Remove-Item -LiteralPath (Join-Path $inbox "receipt_jsons_review/$conflictName.result.json")
Assert ((@(Invoke-HermesCorrectionQueue $inbox $root 'unused.xlsx' $statePath)).Count -eq 0) 'Conflict archive replay notified'
$repairedSidecar=Get-Content -LiteralPath (Join-Path $inbox "receipt_jsons_review/$conflictName.result.json") -Raw|ConvertFrom-Json
Assert ($repairedSidecar.message -ceq (Read-HermesState $statePath).operations[$late.operationId].message) 'Conflict replay discarded saved actionable message'
# Missing acks for archived applied/conflict operations repair without result items.
foreach($id in @($processed.operationId,$late.operationId,$missing.operationId)){Remove-Item -LiteralPath (Join-Path $inbox "receipt_correction_acks/$id.json")}
Assert ((@(Invoke-HermesCorrectionQueue $inbox $root 'unused.xlsx' $statePath)).Count -eq 0) 'Ack repair or unchanged missing-original retry notified'
foreach($id in @($processed.operationId,$late.operationId,$missing.operationId)){Assert (Test-Path -LiteralPath (Join-Path $inbox "receipt_correction_acks/$id.json")) 'Ledger ack repair requires inbox operation'}
$quiet=Invoke-HermesProcessor $root 'logs' 'unused.xlsx' $statePath (Join-Path $root 'quiet.jsonl') -WorkbookUpdater {throw 'No insert expected'}
if($QuietDeferredResultPath){Write-HermesAtomicJson $QuietDeferredResultPath $quiet}
Assert ($quiet.counts.discovered -eq 0 -and $quiet.counts.deferred -eq 0 -and $quiet.items.Count -eq 0) 'Quiet deferred retry inflated processor notification counts'
Assert (Test-Path -LiteralPath (Join-Path $inbox "expense_correction_$($missing.operationId).json")) 'Deferred operation left inbox'

# Every deferred retry reaches the workbook; a changed dependency reason is reportable.
$retry=New-Operation ((Read-HermesState $statePath).records[$payload.expenseId].currentPayload) 'processed' 3;$retry.payload.totalAmount=60
Save-Operation $retry|Out-Null
& $module {$script:retryCalls=0;$script:retryFailure='workbook_locked';function script:Invoke-HermesWorkbookCorrection {
    param($WorkbookPath,$Operation,$Current,$Record,$State,$JournalPath,$OneDriveRoot,[switch]$DryRun)
    $script:retryCalls++
    if($script:retryFailure){throw $script:retryFailure}
    if(-not $DryRun){Write-HermesAtomicJson $JournalPath ([ordered]@{operation=$Operation;phase='prepared'})}
    [pscustomobject]@{Sheet='2026-11';Row=13;Recovered=$false}
}}
Assert ((@(Invoke-HermesCorrectionQueue $inbox $root 'unused.xlsx' $statePath))[0].outcome -ceq 'deferred') 'First workbook defer did not report'
Assert ((@(Invoke-HermesCorrectionQueue $inbox $root 'unused.xlsx' $statePath)).Count -eq 0) 'Unchanged workbook defer repeated'
& $module {$script:retryFailure='workbook_read_only'}
Assert ((@(Invoke-HermesCorrectionQueue $inbox $root 'unused.xlsx' $statePath))[0].message -ceq 'workbook_read_only') 'Changed dependency reason was suppressed'
Assert ((& $module {$script:retryCalls}) -eq 3) 'Dedup skipped a retry attempt'
& $module {$script:retryFailure=$null}
# Force archive collision after successful application: ack must remain applied.
$retryName="expense_correction_$($retry.operationId).json";$collision=Join-Path $inbox "receipt_jsons_done/$retryName"
Set-Content -LiteralPath $collision -Value 'different operation bytes';$collisionHash=Get-HermesFileSha256 $collision
$archiveFailure=@(Invoke-HermesCorrectionQueue $inbox $root 'unused.xlsx' $statePath)
Assert (@($archiveFailure|Where-Object outcome -CEQ 'updated').Count -eq 1 -and @($archiveFailure|Where-Object reasonCode -CEQ 'correction_archive_failed').Count -eq 1) 'Archive failure hid successful application or lacked diagnostic'
$retryAckPath=Join-Path $inbox "receipt_correction_acks/$($retry.operationId).json"
Assert ((Get-Content -LiteralPath $retryAckPath -Raw|ConvertFrom-Json).status -ceq 'applied') 'Archive failure downgraded applied ack'
Assert ((Get-HermesFileSha256 $collision) -ceq $collisionHash) 'Different archive bytes overwritten'
Assert (Test-Path -LiteralPath (Join-Path $inbox $retryName)) 'Failed archive lost source'
$attempts=& $module {$script:retryCalls}
# Identical destination is completion evidence; replay removes duplicate source without Excel.
Copy-Item -LiteralPath (Join-Path $inbox $retryName) -Destination $collision -Force
Remove-Item -LiteralPath $retryAckPath
Assert ((@(Invoke-HermesCorrectionQueue $inbox $root 'unused.xlsx' $statePath)).Count -eq 0) 'Completed archive retry notified'
Assert ((& $module {$script:retryCalls}) -eq $attempts) 'Archive retry wrote Excel again'
Assert (-not (Test-Path -LiteralPath (Join-Path $inbox $retryName)) -and (Test-Path -LiteralPath $retryAckPath)) 'Archive retry or missing applied ack repair incomplete'

# Dry runs leave terminal/deferred inputs, state, projections and acknowledgement bytes intact.
$dryOp=New-Operation ((Read-HermesState $statePath).records[$payload.expenseId].currentPayload) 'processed' 4
Save-Operation $dryOp|Out-Null
Set-Content -LiteralPath (Join-Path $inbox 'expense_correction_dry-invalid.json') -Value '{'
Remove-Item -LiteralPath $retryAckPath
$before=@{};Get-ChildItem -LiteralPath $root -File -Recurse|ForEach-Object {$before[$_.FullName]=Get-HermesFileSha256 $_.FullName}
$dryResults=@(Invoke-HermesCorrectionQueue $inbox $root 'unused.xlsx' $statePath -DryRun)
$after=@{};Get-ChildItem -LiteralPath $root -File -Recurse|ForEach-Object {$after[$_.FullName]=Get-HermesFileSha256 $_.FullName}
Assert ($before.Count -eq $after.Count) 'Dry run added or removed lifecycle artifacts'
foreach($key in $before.Keys){Assert ($after.ContainsKey($key) -and $after[$key] -ceq $before[$key]) 'Dry run changed lifecycle artifact bytes'}
Assert (@($dryResults|Where-Object reasonCode -CEQ 'dry_run_correction_eligible').Count -eq 1) 'Dry run did not assess eligible correction'
# A dry-run pending overlay reaches the original updater without journaling or publication.
$dryPendingRoot=Join-Path $root 'dry-pending';$dryPendingInbox=Join-Path $dryPendingRoot 'logs'
New-Item -ItemType Directory -Path $dryPendingInbox -Force|Out-Null
$dryPending=New-Operation $payload 'pending'
Write-HermesAtomicJson (Join-Path $dryPendingInbox $filename) $payload
Write-HermesAtomicJson (Join-Path $dryPendingInbox "expense_correction_$($dryPending.operationId).json") $dryPending
$pendingHashes=@{};Get-ChildItem -LiteralPath $dryPendingRoot -File -Recurse|ForEach-Object {$pendingHashes[$_.FullName]=Get-HermesFileSha256 $_.FullName}
$dryPendingResult=Invoke-HermesProcessor $dryPendingRoot 'logs' 'unused.xlsx' (Join-Path $dryPendingRoot 'state.json') (Join-Path $dryPendingRoot 'log.jsonl') -DryRun -WorkbookUpdater $fake
Assert ($dryPendingResult.counts.inserted -eq 1) 'Dry run pending overlay did not reach original updater'
Assert (@(Get-ChildItem -LiteralPath $dryPendingRoot -File -Recurse).Count -eq $pendingHashes.Count) 'Dry run pending overlay wrote lifecycle artifacts'
foreach($key in $pendingHashes.Keys){Assert ((Get-HermesFileSha256 $key) -ceq $pendingHashes[$key]) 'Dry run pending overlay changed original or operation bytes'}
Write-Output "Correction unit tests passed: $passed assertions. Disposable root: $root"
