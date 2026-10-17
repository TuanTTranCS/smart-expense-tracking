$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'SmartExpense.Excel.psm1') -Force -DisableNameChecking
$passed=0
function Assert($Condition,[string]$Message){if(-not $Condition){throw $Message};$script:passed++}
$root=Join-Path ([IO.Path]::GetTempPath()) ('expense-correction-workbook-'+[guid]::NewGuid().ToString('N'))
$inbox=Join-Path $root 'logs';New-Item -ItemType Directory -Force -Path $inbox|Out-Null
$workbook=Join-Path $root 'disposable.xlsx';$statePath=Join-Path $root 'state.json'
$payload=[pscustomobject]@{schemaVersion=3;expenseId='aaaaaaaa-1111-4111-8111-111111111111';createdAt='2026-10-16T12:00:00-07:00';sourceDeviceId='test';sourceDeviceName='Test';inputSource='typed';hasReceiptImage=$false;receiptImageRelativePath=$null;receiptDate='2026-10-16';merchantName='Test shop';totalAmount=10;currency='CAD';extractionStatus='manual';itemType='pending'}
$fileName=Get-HermesPayloadFileName $payload
$state=[ordered]@{schemaVersion=1;records=@{$payload.expenseId=[ordered]@{outcome='inserted';processedAt='2026-10-16T19:00:00Z';sourceFileName=$fileName;sheet='2026-10';row=12}}}
Write-HermesState $state $statePath
Write-HermesAtomicJson (Join-Path $inbox "receipt_jsons_done/$fileName") $payload
Publish-HermesCorrectionState $inbox $payload

# Create a workbook from scratch. No production workbook or queue is accessed.
$excel=$null;$book=$null
try {
    $excel=New-Object -ComObject Excel.Application;$excel.Visible=$false;$excel.DisplayAlerts=$false
    $book=$excel.Workbooks.Add();$sheet=$book.Worksheets.Item(1);$sheet.Name='2026-10'
    $sheet.Cells.Item(1,6).Value2='Expense';$sheet.Cells.Item(1,7).Value2='Description'
    $sheet.Cells.Item(12,6).Value2=[double]10;$sheet.Cells.Item(12,7).Value2=Format-HermesMerchantDescription $payload.merchantName $payload.receiptDate
    $sheet.Cells.Item(12,5).Value2=[double]0.5;$sheet.Cells.Item(12,15).Formula='=F12*2';$sheet.Cells.Item(12,16).Value2='unrelated sentinel'
    $summary=$book.Worksheets.Add([Type]::Missing,$sheet);$summary.Name='Food Expense Summary'
    $summary.Cells.Item(3,1).Value2='Month';$summary.Cells.Item(3,5).Value2='Month Total';$summary.Cells.Item(4,1).Value2='2026-10';$summary.Cells.Item(5,1).Value2='Accumulated'
    $summary.Cells.Item(14,1).Value2='Month';$summary.Cells.Item(14,6).Value2='Source Sheet'
    for($offset=0;$offset -lt 89;$offset++){$r=$offset+15;$source=$offset+12;$summary.Cells.Item($r,1).Value2='2026-10';$summary.Cells.Item($r,2).Value2=[double]$source;$summary.Cells.Item($r,3).Formula='=IF(''2026-10''!F{0}="","",''2026-10''!F{0})' -f $source;$summary.Cells.Item($r,4).Formula='=IF(''2026-10''!G{0}="","",''2026-10''!G{0})' -f $source;$summary.Cells.Item($r,5).Value2='Others';$summary.Cells.Item($r,6).Value2='2026-10'}
    $summary.Cells.Item(8,6).Formula='=SUMPRODUCT(--(LEN(C15:C103)>0),--(LEN(D15:D103)>0))';$summary.Cells.Item(9,6).Formula='=SUMPRODUCT(--(LEN(''2026-10''!F12:F100)>0),--(LEN(''2026-10''!G12:G100)>0))';$summary.Cells.Item(10,6).Formula='=IF(F8=F9,"OK","Review")'
    $excel.Calculate();$book.SaveAs($workbook,51)
} finally {if($book){$book.Close($false);[Runtime.InteropServices.Marshal]::ReleaseComObject($book)|Out-Null};if($excel){$excel.Quit();[Runtime.InteropServices.Marshal]::ReleaseComObject($excel)|Out-Null};[GC]::Collect();[GC]::WaitForPendingFinalizers()}

function New-Operation($Prior,[int]$Revision,[decimal]$Amount,[string]$Date){
    $corrected=(Get-HermesPayloadJson $Prior)|ConvertFrom-Json -DateKind String;$corrected.totalAmount=$Amount;$corrected.receiptDate=$Date;$corrected.itemType='processed'
    $json=Get-HermesPayloadJson $Prior
    [pscustomobject]@{correctionProtocolVersion=1;operationId=[guid]::NewGuid().ToString();expenseId=$Prior.expenseId;expectedRevision=$Revision;expectedPayloadJson=$json;expectedPayloadSha256=(Get-HermesTextSha256 $json);payload=$corrected}
}
function Save-Operation($Operation){Write-HermesAtomicJson (Join-Path $inbox "expense_correction_$($Operation.operationId).json") $Operation}
function Read-Cells($Month,[int]$Row){$excel=$null;$book=$null;try{$excel=New-Object -ComObject Excel.Application;$excel.Visible=$false;$book=$excel.Workbooks.Open($workbook,$null,$true);$sheet=$book.Worksheets.Item($Month);$cells=Get-HermesCellSnapshot $sheet $Row;$cells|Add-Member -NotePropertyName sentinel -NotePropertyValue $sheet.Cells.Item($Row,16).Value2;$cells|Add-Member -NotePropertyName formula -NotePropertyValue $sheet.Cells.Item($Row,15).Formula;return $cells}finally{if($book){$book.Close($false);[Runtime.InteropServices.Marshal]::ReleaseComObject($book)|Out-Null};if($excel){$excel.Quit();[Runtime.InteropServices.Marshal]::ReleaseComObject($excel)|Out-Null};[GC]::Collect();[GC]::WaitForPendingFinalizers()}}

$op=New-Operation $payload 0 20 '2026-10-16';Save-Operation $op
$operationHash=Get-HermesFileSha256 (Join-Path $inbox "expense_correction_$($op.operationId).json")
$result=@(Invoke-HermesCorrectionQueue $inbox $root $workbook $statePath)
Assert ((Get-HermesFileSha256 (Join-Path $inbox "receipt_jsons_done/expense_correction_$($op.operationId).json")) -ceq $operationHash) 'Applied operation archive changed bytes'
Assert (-not (Test-Path -LiteralPath (Join-Path $inbox "expense_correction_$($op.operationId).json"))) 'Applied workbook correction remained in inbox'
Assert ($result[0].outcome -ceq 'updated') "Same-month correction failed: $($result[0]|ConvertTo-Json -Compress)"
Assert ($result[0].merchant -ceq 'Test shop' -and $result[0].amount -eq 20 -and $result[0].receiptDate -ceq '2026-10-16') 'Same-month success omitted corrected expense details'
$cells=Read-Cells '2026-10' 12
Assert ($cells.amount -eq 20 -and $cells.description -ceq 'Test shop Oct 16') 'Same-month corrected F/G missing'
Assert ($cells.sentinel -ceq 'unrelated sentinel' -and $cells.formula -ceq '=F12*2') 'Unrelated same-month cell/formula changed'
$savedHash=Get-HermesFileSha256 $workbook
Assert ((@(Invoke-HermesCorrectionQueue $inbox $root $workbook $statePath)).Count -eq 0) 'Workbook replay was not suppressed'
Assert ((Get-HermesFileSha256 $workbook) -ceq $savedHash) 'Replay saved workbook again'

Remove-Item -LiteralPath (Join-Path $inbox "receipt_correction_acks/$($op.operationId).json")
Assert ((@(Invoke-HermesCorrectionQueue $inbox $root $workbook $statePath)).Count -eq 0) 'Archived acknowledgement repair notified'
Assert (Test-Path -LiteralPath (Join-Path $inbox "receipt_correction_acks/$($op.operationId).json")) 'Archived operation ack not restored'
Assert ((Get-HermesFileSha256 $workbook) -ceq $savedHash) 'Ack repair saved workbook'

# Save workbook, then simulate interruption before state/publication/ack.
$current=Get-HermesPublicCorrectionState $inbox $payload.expenseId $null
$cross=New-Operation $current.payload 1 30 '2026-11-02';Save-Operation $cross
$journal=Join-Path ("$statePath.correction-journal") "$($cross.operationId).json"
$loaded=Read-HermesState $statePath
$excel=$null;$book=$null;try{$excel=New-Object -ComObject Excel.Application;$excel.Visible=$false;$excel.DisplayAlerts=$false;$book=$excel.Workbooks.Open($workbook);$targetSheet=Ensure-HermesMonthlySheet $book '2026-11';$targetSheet.Cells.Item(12,8).Formula='=""';$book.Save()}finally{if($book){$book.Close($false);[Runtime.InteropServices.Marshal]::ReleaseComObject($book)|Out-Null};if($excel){$excel.Quit();[Runtime.InteropServices.Marshal]::ReleaseComObject($excel)|Out-Null};[GC]::Collect();[GC]::WaitForPendingFinalizers()}
$formulaHash=Get-HermesFileSha256 $workbook;$formulaConflict=$false
try{Invoke-HermesWorkbookCorrection $workbook $cross $current $loaded.records[$payload.expenseId] $loaded $journal $root|Out-Null}catch{$formulaConflict=$_.Exception.Message -ceq 'conflict_target_not_empty'}
Assert ($formulaConflict) 'Blank-result target formula was cleared'
Assert ((Get-HermesFileSha256 $workbook) -ceq $formulaHash) 'Target formula conflict saved workbook'
$excel=$null;$book=$null;try{$excel=New-Object -ComObject Excel.Application;$excel.Visible=$false;$book=$excel.Workbooks.Open($workbook);$book.Worksheets.Item('2026-11').Cells.Item(12,8).ClearContents()|Out-Null;$book.Save()}finally{if($book){$book.Close($false);[Runtime.InteropServices.Marshal]::ReleaseComObject($book)|Out-Null};if($excel){$excel.Quit();[Runtime.InteropServices.Marshal]::ReleaseComObject($excel)|Out-Null};[GC]::Collect();[GC]::WaitForPendingFinalizers()}
$crossResult=Invoke-HermesWorkbookCorrection $workbook $cross $current $loaded.records[$payload.expenseId] $loaded $journal $root
Assert ($crossResult.Sheet -ceq '2026-11') 'Cross-month target missing'
$excel=$null;$book=$null;try{$excel=New-Object -ComObject Excel.Application;$excel.Visible=$false;$book=$excel.Workbooks.Open($workbook);$book.Worksheets.Item('2026-10').Cells.Item(12,8).Formula='=""';$book.Save()}finally{if($book){$book.Close($false);[Runtime.InteropServices.Marshal]::ReleaseComObject($book)|Out-Null};if($excel){$excel.Quit();[Runtime.InteropServices.Marshal]::ReleaseComObject($excel)|Out-Null};[GC]::Collect();[GC]::WaitForPendingFinalizers()}
$formulaHash=Get-HermesFileSha256 $workbook;$formulaConflict=$false
try{Invoke-HermesWorkbookCorrection $workbook $cross $current $loaded.records[$payload.expenseId] $loaded $journal $root|Out-Null}catch{$formulaMessage=$_.Exception.Message;$formulaConflict=$formulaMessage -ceq 'conflict_independent_excel_edit'}
Assert ($formulaConflict) "Blank-result source formula falsely proved a cleared row: $formulaMessage"
Assert ((Get-HermesFileSha256 $workbook) -ceq $formulaHash) 'Source formula recovery conflict saved workbook'
$excel=$null;$book=$null;try{$excel=New-Object -ComObject Excel.Application;$excel.Visible=$false;$book=$excel.Workbooks.Open($workbook);$book.Worksheets.Item('2026-10').Cells.Item(12,8).ClearContents()|Out-Null;$book.Save()}finally{if($book){$book.Close($false);[Runtime.InteropServices.Marshal]::ReleaseComObject($book)|Out-Null};if($excel){$excel.Quit();[Runtime.InteropServices.Marshal]::ReleaseComObject($excel)|Out-Null};[GC]::Collect();[GC]::WaitForPendingFinalizers()}
$result=@(Invoke-HermesCorrectionQueue $inbox $root $workbook $statePath)
Assert ($result[0].outcome -ceq 'updated') "Saved-before-state replay failed: $($result[0]|ConvertTo-Json -Compress)"
Assert ($result[0].merchant -ceq 'Test shop' -and $result[0].amount -eq 30 -and $result[0].receiptDate -ceq '2026-11-02' -and $result[0].sourceDeviceName -ceq 'Test') 'Recovered cross-month success omitted corrected expense details'
$target=Read-Cells '2026-11' $crossResult.Row;$source=Read-Cells '2026-10' 12
Assert ($target.amount -eq 30 -and $target.description -ceq 'Test shop Nov 02') 'Cross-month target cells incorrect'
Assert ([string]::IsNullOrEmpty([string]$source.amount) -and [string]::IsNullOrEmpty([string]$source.description)) 'Cross-month old row not cleared'
Assert ($source.sentinel -ceq 'unrelated sentinel' -and $source.formula -ceq '=F12*2') 'Cross-month source unrelated cells changed'
Assert ((Get-HermesPublicCorrectionState $inbox $payload.expenseId $null).revision -eq 2) 'Recovered revision not advanced once'
Assert (Test-Path -LiteralPath (Join-Path $inbox "receipt_jsons_done/expense_correction_$($cross.operationId).json")) 'Recovered cross-month correction not archived'

# Workbook edits must block corrections without saving.
$current=Get-HermesPublicCorrectionState $inbox $payload.expenseId $null
$independent=New-Operation $current.payload 2 40 '2026-11-02';Save-Operation $independent
$excel=$null;$book=$null;try{$excel=New-Object -ComObject Excel.Application;$excel.Visible=$false;$book=$excel.Workbooks.Open($workbook);$book.Worksheets.Item('2026-11').Cells.Item($crossResult.Row,7).Value2='Independent edit';$book.Save()}finally{if($book){$book.Close($false);[Runtime.InteropServices.Marshal]::ReleaseComObject($book)|Out-Null};if($excel){$excel.Quit();[Runtime.InteropServices.Marshal]::ReleaseComObject($excel)|Out-Null};[GC]::Collect();[GC]::WaitForPendingFinalizers()}
$savedHash=Get-HermesFileSha256 $workbook
$result=@(Invoke-HermesCorrectionQueue $inbox $root $workbook $statePath)
Assert ($result[0].reasonCode -ceq 'conflict_independent_excel_edit') 'Independent Excel edit overwritten'
Assert ((Get-HermesFileSha256 $workbook) -ceq $savedHash) 'Conflict saved workbook'
Assert (Test-Path -LiteralPath (Join-Path $inbox "receipt_jsons_review/expense_correction_$($independent.operationId).result.json")) 'Excel conflict has no review sidecar'

# Pending overlay crash after workbook save before original state writer.
$pendingOriginal=(Get-HermesPayloadJson $payload)|ConvertFrom-Json -DateKind String
$pendingOriginal.expenseId='bbbbbbbb-1111-4111-8111-111111111111';$pendingOriginal.merchantName='Crash recovery shop';$pendingOriginal.receiptDate='2026-11-03'
$pendingOp=New-Operation $pendingOriginal 0 60 '2026-11-03';$pendingOp.payload.itemType='pending';Save-Operation $pendingOp
$pendingFile=Get-HermesPayloadFileName $pendingOriginal
Write-HermesAtomicJson (Join-Path $inbox $pendingFile) $pendingOriginal
$failed=Invoke-HermesProcessor $root 'logs' 'disposable.xlsx' $statePath (Join-Path $root 'log.jsonl') -StateWriter {throw 'injected state failure after save'}
Assert ($failed.counts.failed -eq 1) 'Injected save-before-state failure not surfaced'
Assert (-not (Read-HermesState $statePath).records.Contains($pendingOriginal.expenseId)) 'Failed state writer recorded owner prematurely'
$recovered=Invoke-HermesProcessor $root 'logs' 'disposable.xlsx' $statePath (Join-Path $root 'log.jsonl')
Assert ($recovered.counts.inserted -eq 1) "Pending journal did not recover owner: $($recovered|ConvertTo-Json -Depth 10 -Compress)"
$record=(Read-HermesState $statePath).records[$pendingOriginal.expenseId]
Assert ($record.outcome -ceq 'inserted' -and $record.appliedRevision -eq 1) 'Recovered pending row lost unique ownership/revision'
Assert (Test-Path -LiteralPath (Join-Path $inbox "receipt_jsons_done/expense_correction_$($pendingOp.operationId).json")) 'Recovered pending overlay not archived'
$pendingCurrent=Get-HermesPublicCorrectionState $inbox $pendingOriginal.expenseId $record
$followup=New-Operation $pendingCurrent.payload 1 70 '2026-11-03';Save-Operation $followup
$followupResults=@(Invoke-HermesCorrectionQueue $inbox $root $workbook $statePath)
Assert ($followupResults[0].outcome -ceq 'updated') "Recovered pending expense could not be corrected: $($followupResults[0]|ConvertTo-Json -Compress)"

# Image-backed corrections reuse the exact normalized image and hyperlink.
$imagePayload=[pscustomobject]@{schemaVersion=2;expenseId='cccccccc-1111-4111-8111-111111111111';createdAt='2026-10-16T13:00:00-07:00';sourceDeviceId='test';sourceDeviceName='Test';inputSource='image';hasReceiptImage=$true;receiptImageRelativePath='Documents/2_Others/Expenses_finance/receipt_images/2026-10/20261016_130000_receipt_cccccccc.jpg';receiptDate='2026-11-04';merchantName='Image shop';totalAmount=80;currency='CAD';extractionStatus='confirmed';itemType='pending'}
$imagePath=Join-Path $root $imagePayload.receiptImageRelativePath;New-Item -ItemType Directory -Force -Path (Split-Path $imagePath -Parent)|Out-Null;[IO.File]::WriteAllBytes($imagePath,[byte[]]@(255,216,255,217))
$imageHash=Get-HermesFileSha256 $imagePath;$imageFile=Get-HermesPayloadFileName $imagePayload
$imageValidation=Test-HermesHandoff $imagePayload $imageFile $root
$excel=$null;$book=$null;try{$excel=New-Object -ComObject Excel.Application;$excel.Visible=$false;$book=$excel.Workbooks.Open($workbook);Set-HermesCorrectionCells $book.Worksheets.Item('2026-11') 14 (Get-HermesExpectedCells $imageValidation);$book.Save()}finally{if($book){$book.Close($false);[Runtime.InteropServices.Marshal]::ReleaseComObject($book)|Out-Null};if($excel){$excel.Quit();[Runtime.InteropServices.Marshal]::ReleaseComObject($excel)|Out-Null};[GC]::Collect();[GC]::WaitForPendingFinalizers()}
$loaded=Read-HermesState $statePath;$loaded.records[$imagePayload.expenseId]=[ordered]@{outcome='inserted';processedAt='2026-10-16T19:00:00Z';sourceFileName=$imageFile;sheet='2026-11';row=14;appliedRevision=0;currentPayload=$imagePayload};Write-HermesState $loaded $statePath
Write-HermesAtomicJson (Join-Path $inbox "receipt_jsons_done/$imageFile") $imagePayload;Publish-HermesCorrectionState $inbox $imagePayload
$imageOp=New-Operation $imagePayload 0 90 '2026-11-04';$imageOp.payload.extractionStatus='manual';Save-Operation $imageOp
$imageResults=@(Invoke-HermesCorrectionQueue $inbox $root $workbook $statePath)
Assert ($imageResults[0].outcome -ceq 'updated') "Image-backed correction failed: $($imageResults[0]|ConvertTo-Json -Compress)"
Assert ($imageResults[0].merchant -ceq $imageOp.payload.merchantName -and $imageResults[0].amount -eq $imageOp.payload.totalAmount -and $imageResults[0].receiptDate -ceq $imageOp.payload.receiptDate) 'Image-backed success omitted corrected expense details'
$imageCells=Read-Cells '2026-11' 14
Assert ($imageCells.amount -eq 90 -and $imageCells.image -ceq $imagePayload.receiptImageRelativePath -and $imageCells.hyperlink -ceq (Get-HermesWorkbookHyperlinkPath $imagePayload.receiptImageRelativePath)) 'Image correction changed normalized reference/hyperlink'
Assert ((Get-HermesFileSha256 $imagePath) -ceq $imageHash -and @(Get-ChildItem -LiteralPath (Split-Path $imagePath -Parent) -File).Count -eq 1) 'Field correction changed or duplicated image'
$imageCurrent=Get-HermesPublicCorrectionState $inbox $imagePayload.expenseId (Read-HermesState $statePath).records[$imagePayload.expenseId]
$missingImage=New-Operation $imageCurrent.payload 1 95 '2026-11-04';Save-Operation $missingImage
Remove-Item -LiteralPath $imagePath;$beforeMissing=Get-HermesFileSha256 $workbook
$missingResults=@(Invoke-HermesCorrectionQueue $inbox $root $workbook $statePath)
Assert ($missingResults[0].outcome -ceq 'deferred' -and (Get-HermesFileSha256 $workbook) -ceq $beforeMissing) 'Missing image allowed workbook write'
Assert ((@(Invoke-HermesCorrectionQueue $inbox $root $workbook $statePath)).Count -eq 0) 'Unchanged missing-image dependency notified again'
Assert (Test-Path -LiteralPath (Join-Path $inbox "expense_correction_$($missingImage.operationId).json")) 'Missing-image dependency left inbox'
[IO.File]::WriteAllBytes($imagePath,[byte[]]@(255,216,255,217))
Assert ((@(Invoke-HermesCorrectionQueue $inbox $root $workbook $statePath))[0].outcome -ceq 'updated') 'Restored image did not allow immutable correction retry'
Write-Output "Disposable Excel COM correction tests passed: $passed assertions. Root: $root"
