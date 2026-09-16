Set-StrictMode -Version Latest

$script:MonthlyPattern = '^\d{4}-\d{2}$'
$script:HandoffPattern = '^expense_\d{8}_\d{6}_[0-9a-f]{8}\.json$'
$script:OneDriveExpenseRoot = 'Documents/2_Others/Expenses_finance'
$script:Invariant = [Globalization.CultureInfo]::InvariantCulture

function ConvertTo-HermesForwardSlash([string]$Path) {
    return ($Path -replace '\\', '/')
}

function Test-HermesHandoffFileName {
    param([Parameter(Mandatory)][string]$FileName)
    return $FileName -cmatch $script:HandoffPattern
}

function Get-HermesOrdinalFiles {
    param([Parameter(Mandatory)][string]$Inbox)
    if (-not (Test-Path -LiteralPath $Inbox -PathType Container)) { return @() }
    return @(Get-ChildItem -LiteralPath $Inbox -File -ErrorAction Stop |
        Where-Object { Test-HermesHandoffFileName $_.Name } |
        Sort-Object -Property Name -CaseSensitive)
}

function Get-HermesFullPath {
    param([Parameter(Mandatory)][string]$Path)
    return [IO.Path]::GetFullPath($Path)
}

function Test-HermesPathUnderRoot {
    param([Parameter(Mandatory)][string]$Path,[Parameter(Mandatory)][string]$Root)
    $fullPath = Get-HermesFullPath $Path
    $fullRoot = (Get-HermesFullPath $Root).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    return $fullPath.StartsWith($fullRoot, [StringComparison]::OrdinalIgnoreCase) -or
        $fullPath.Equals($fullRoot.TrimEnd([IO.Path]::DirectorySeparatorChar), [StringComparison]::OrdinalIgnoreCase)
}

function Get-HermesOneDriveRoot {
    [CmdletBinding()]
    param(
        [string]$ExplicitRoot,
        [string]$ConsumerRoot = $env:OneDriveConsumer,
        [string]$GenericRoot = $env:OneDrive,
        [string[]]$RegistryRoots,
        [string[]]$RegistryPaths = @(
            'HKCU:\Software\Microsoft\OneDrive\Accounts\Personal',
            'HKCU:\Software\Microsoft\OneDrive\Accounts\Consumer',
            'HKCU:\Software\Microsoft\OneDrive\Accounts\Business1',
            'HKCU:\Software\Microsoft\OneDrive\Accounts\Business2',
            'HKCU:\Software\Microsoft\OneDrive'
        )
    )
    if ($ExplicitRoot) {
        if (-not (Test-Path -LiteralPath $ExplicitRoot -PathType Container)) {
            throw "Explicit OneDriveRoot does not exist: $ExplicitRoot"
        }
        return Get-HermesFullPath $ExplicitRoot
    }

    $envCandidates = @(@($ConsumerRoot, $GenericRoot) | Where-Object {
        $_ -and (Test-Path -LiteralPath $_ -PathType Container)
    } | ForEach-Object { Get-HermesFullPath $_ } | Sort-Object -Unique)
    if ($envCandidates.Count -gt 1) { throw 'Conflicting valid OneDrive roots were found in OneDriveConsumer and OneDrive.' }
    if ($envCandidates.Count -eq 1) { return $envCandidates[0] }

    $registryCandidates = [Collections.Generic.List[string]]::new()
    foreach ($registryRoot in @($RegistryRoots)) {
        if ($registryRoot -and (Test-Path -LiteralPath $registryRoot -PathType Container)) {
            $full = Get-HermesFullPath $registryRoot
            if (-not $registryCandidates.Contains($full)) { $registryCandidates.Add($full) }
        }
    }
    foreach ($registryPath in $RegistryPaths) {
        if ($RegistryRoots) { break }
        try {
            $property = Get-ItemProperty -LiteralPath $registryPath -ErrorAction Stop
            foreach ($propertyName in @('UserFolder','OneDrivePath','Path')) {
                $value = [string]$property.$propertyName
                if ($value -and (Test-Path -LiteralPath $value -PathType Container)) {
                    $full = Get-HermesFullPath $value
                    if (-not $registryCandidates.Contains($full)) { $registryCandidates.Add($full) }
                }
            }
        } catch { }
    }
    if ($registryCandidates.Count -gt 1) { throw 'Conflicting valid OneDrive roots were found in the Windows registry.' }
    if ($registryCandidates.Count -eq 1) { return $registryCandidates[0] }
    throw 'No valid OneDrive root was found in OneDriveConsumer, OneDrive, or the Windows registry.'
}

function Normalize-HermesMerchant {
    param([AllowNull()][string]$MerchantName)
    $value = if ($null -eq $MerchantName) { '' } else { $MerchantName }
    $value = $value.Normalize([Text.NormalizationForm]::FormKC).Trim() -replace '\s+', ' '
    $value = [regex]::Replace($value, '\bSupermarket\b', 'Mart', [Text.RegularExpressions.RegexOptions]::IgnoreCase)
    # Keep ampersand as a meaningful merchant separator but normalize other punctuation conservatively.
    $value = [regex]::Replace($value, '[\p{P}-[&]]+', ' ') -replace '\s+', ' '
    return $value.Trim().ToLowerInvariant()
}

function Format-HermesMerchantDescription {
    param([Parameter(Mandatory)][string]$MerchantName,[Parameter(Mandatory)][string]$ReceiptDate)
    $date = [datetime]::ParseExact($ReceiptDate, 'yyyy-MM-dd', $script:Invariant)
    $merchant = [regex]::Replace(($MerchantName.Trim() -replace '\s+', ' '), '\bSupermarket\b', 'Mart', [Text.RegularExpressions.RegexOptions]::IgnoreCase)
    return "{0} {1}" -f $merchant.Trim(), $date.ToString('MMM dd', $script:Invariant)
}

function ConvertTo-HermesMoney([object]$Amount) {
    return [decimal]::Round([decimal]$Amount, 2, [MidpointRounding]::AwayFromZero)
}

function Get-HermesExpectedImagePath {
    param([Parameter(Mandatory)][datetimeoffset]$CreatedAt,[Parameter(Mandatory)][guid]$ExpenseId)
    $short = $ExpenseId.ToString('N').Substring(0, 8).ToLowerInvariant()
    return "Documents/2_Others/Expenses_finance/receipt_images/{0}/{1}_receipt_{2}.jpg" -f `
        $CreatedAt.ToString('yyyy-MM', $script:Invariant), $CreatedAt.ToString('yyyyMMdd_HHmmss', $script:Invariant), $short
}

function Test-HermesSafeRelativePath {
    param([Parameter(Mandatory)][string]$RelativePath)
    if ($RelativePath -match '^[\\/]' -or $RelativePath -match '^[A-Za-z]:[\\/]' -or $RelativePath -match '(^|[\\/])\.\.([\\/]|$)') { return $false }
    return $true
}

function Test-HermesJsonInteger {
    param([AllowNull()][object]$Value)
    return $Value -is [byte] -or $Value -is [sbyte] -or $Value -is [int16] -or $Value -is [uint16] -or $Value -is [int32] -or $Value -is [uint32] -or $Value -is [int64] -or $Value -is [uint64]
}

function Test-HermesJsonNumber {
    param([AllowNull()][object]$Value)
    if($null -eq $Value -or $Value -is [bool]){return $false}
    return (Test-HermesJsonInteger $Value) -or $Value -is [decimal] -or $Value -is [double] -or $Value -is [single]
}

function Test-HermesVersion2Handoff {
    param([Parameter(Mandatory)][object]$Payload,[Parameter(Mandatory)][string]$FileName,[Parameter(Mandatory)][string]$OneDriveRoot)
    $failure = { param([string]$Code,[string]$Message) [pscustomobject]@{ Valid=$false; ReasonCode=$Code; Message=$Message } }
    try {
        if (-not (Test-HermesHandoffFileName $FileName)) { return & $failure 'invalid_filename' 'Filename is not a final Version 2 handoff name.' }
        if($null -eq $Payload){return & $failure 'unsupported_schema' 'Only schemaVersion 2 is supported by the production processor.'}
        $schemaProperty=$Payload.PSObject.Properties['schemaVersion']
        if($null -eq $schemaProperty -or -not (Test-HermesJsonInteger $schemaProperty.Value)){return & $failure 'invalid_schema_type' 'schemaVersion must be the integer 2.'}
        if([int64]$schemaProperty.Value -ne 2){return & $failure 'unsupported_schema' 'Only schemaVersion 2 is supported by the production processor.'}
        foreach($name in @('expenseId','createdAt','sourceDeviceId','receiptDate','merchantName','currency','extractionStatus','receiptImageRelativePath')){
            $property=$Payload.PSObject.Properties[$name]
            if($null -eq $property -or $property.Value -isnot [string] -or [string]::IsNullOrWhiteSpace($property.Value)){return & $failure 'missing_required_field' "$name must be a non-blank string."}
        }
        $id=[guid]::Empty
        if(-not [guid]::TryParse([string]$Payload.expenseId,[ref]$id)){return & $failure 'invalid_expense_id' 'expenseId must be a UUID.'}
        $short=$id.ToString('N').Substring(0,8).ToLowerInvariant()
        if($FileName -cnotmatch "_$short\.json$"){return & $failure 'filename_id_mismatch' 'Filename short ID differs from expenseId.'}
        $created=[datetimeoffset]::MinValue
        if([string]$Payload.createdAt -notmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,7})?(?:Z|[+-]\d{2}:\d{2})$' -or -not [datetimeoffset]::TryParse([string]$Payload.createdAt,$script:Invariant,[Globalization.DateTimeStyles]::RoundtripKind,[ref]$created)){return & $failure 'invalid_created_at' 'createdAt must be an ISO-8601 timestamp with timezone.'}
        $receiptDate=[datetime]::MinValue
        if(-not [datetime]::TryParseExact([string]$Payload.receiptDate,'yyyy-MM-dd',$script:Invariant,[Globalization.DateTimeStyles]::None,[ref]$receiptDate)){return & $failure 'invalid_receipt_date' 'receiptDate must be a real yyyy-MM-dd date.'}
        $amountProperty=$Payload.PSObject.Properties['totalAmount'];$amount=[decimal]0
        if($null -eq $amountProperty -or -not (Test-HermesJsonNumber $amountProperty.Value) -or -not [decimal]::TryParse([string]$amountProperty.Value,[Globalization.NumberStyles]::Number,$script:Invariant,[ref]$amount) -or $amount -lt 0){return & $failure 'invalid_amount' 'totalAmount must be a non-negative numeric value.'}
        foreach($amountName in @('taxAmount','tipAmount')){
            $property=$Payload.PSObject.Properties[$amountName]
            if($null -ne $property -and $null -ne $property.Value){$optionalAmount=[decimal]0;if(-not (Test-HermesJsonNumber $property.Value) -or -not [decimal]::TryParse([string]$property.Value,[Globalization.NumberStyles]::Number,$script:Invariant,[ref]$optionalAmount) -or $optionalAmount -lt 0){$code=if($amountName -eq 'taxAmount'){'invalid_tax_amount'}else{'invalid_tip_amount'};return & $failure $code "$amountName must be null or a non-negative number."}}
        }
        foreach($optionalName in @('category','paymentMethod','merchantLocation','merchantAddress','originalImageFileName','receiptImageUri','receiptPhotoLink','notes')){
            $property=$Payload.PSObject.Properties[$optionalName]
            if($null -ne $property -and $null -ne $property.Value -and $property.Value -isnot [string]){return & $failure 'invalid_optional_field' "$optionalName must be a string or null."}
        }
        $rawProperty=$Payload.PSObject.Properties['rawModelOutput']
        if($null -ne $rawProperty -and $null -ne $rawProperty.Value -and $rawProperty.Value -isnot [string] -and $rawProperty.Value -isnot [pscustomobject] -and $rawProperty.Value -isnot [Collections.IDictionary]){return & $failure 'invalid_optional_field' 'rawModelOutput must be a string, object, or null.'}
        if([string]$Payload.currency -cnotmatch '^[A-Z]{3}$'){return & $failure 'invalid_currency' 'currency must be three uppercase letters.'}
        if(@('confirmed','manual') -notcontains [string]$Payload.extractionStatus){return & $failure 'invalid_status' 'extractionStatus must be confirmed or manual.'}
        $relative=[string]$Payload.receiptImageRelativePath;$expected=Get-HermesExpectedImagePath $created $id
        if($relative -cne $expected -or -not (Test-HermesSafeRelativePath $relative) -or [IO.Path]::IsPathRooted($relative)){return & $failure 'invalid_image_path' 'receiptImageRelativePath does not match the deterministic safe path.'}
        $root=Get-HermesFullPath $OneDriveRoot;$local=Get-HermesFullPath (Join-Path $root ($relative -replace '/',[IO.Path]::DirectorySeparatorChar))
        if(-not (Test-HermesPathUnderRoot $local $root) -or -not (Test-Path -LiteralPath $local -PathType Leaf) -or [IO.Path]::GetExtension($local) -cne '.jpg'){return & $failure 'missing_image' 'Referenced normalized JPEG is unavailable under the OneDrive root.'}
        return [pscustomobject]@{Valid=$true;ReasonCode='valid';Message='Valid Version 2 handoff.';ExpenseId=$id.ToString();CreatedAt=$created;ReceiptDate=$receiptDate;ReceiptMonth=$receiptDate.ToString('yyyy-MM',$script:Invariant);Amount=(ConvertTo-HermesMoney $amount);MerchantName=([string]$Payload.merchantName).Trim();Currency=([string]$Payload.currency);ExtractionStatus=([string]$Payload.extractionStatus);ReceiptImageRelativePath=$relative;ImagePath=$local;Description=(Format-HermesMerchantDescription ([string]$Payload.merchantName) ([string]$Payload.receiptDate))}
    } catch { return & $failure 'validation_exception' $_.Exception.Message }
}

function Find-HermesSafeTransactionRow {
    param([Parameter(Mandatory)][array]$Rows,[int]$StartRow = 12,[int]$EndRow = 100)
    $warnings = [Collections.Generic.List[object]]::new()
    $limit = [Math]::Min($Rows.Count, $EndRow - $StartRow + 1)
    for ($i=0; $i -lt $limit; $i++) {
        $row = $Rows[$i]
        $f = $null -ne $row.F -and -not [string]::IsNullOrWhiteSpace([string]$row.F)
        $g = $null -ne $row.G -and -not [string]::IsNullOrWhiteSpace([string]$row.G)
        if (-not $f -and -not $g) { return [pscustomobject]@{ Row=$StartRow+$i; Warnings=@($warnings); HasCapacity=$true } }
        if ($f -xor $g) { $warnings.Add([pscustomobject]@{ Row=$StartRow+$i; ReasonCode='partial_transaction_row'; Message='Only one of F/G is populated; row was not overwritten.' }) }
    }
    return [pscustomobject]@{ Row=$null; Warnings=@($warnings); HasCapacity=$false }
}

function Get-HermesLegacyReceiptDate {
    param([Parameter(Mandatory)][string]$SheetName,[AllowNull()][string]$Description)
    if ($SheetName -notmatch $script:MonthlyPattern -or [string]::IsNullOrWhiteSpace($Description)) { return $null }
    $month = [datetime]::ParseExact("$SheetName-01",'yyyy-MM-dd',$script:Invariant)
    $matches = [regex]::Matches($Description, '(?i)(?<![A-Za-z])(?<month>Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+(?<day>\d{1,2})(?!\d)')
    if ($matches.Count -ne 1 -or -not $Description.TrimEnd().EndsWith($matches[0].Value, [StringComparison]::OrdinalIgnoreCase)) { return $null }
    if ($matches[0].Groups['month'].Value -ne $month.ToString('MMM',$script:Invariant)) { return $null }
    try { return [datetime]::ParseExact("$SheetName-$($matches[0].Groups['day'].Value)", 'yyyy-MM-d', $script:Invariant) } catch { return $null }
}

function Get-HermesWorkbookRecords {
    param([Parameter(Mandatory)]$Workbook)
    $records = [Collections.Generic.List[object]]::new()
    for ($i=1; $i -le $Workbook.Worksheets.Count; $i++) {
        $sheet = $Workbook.Worksheets.Item($i)
        if ($sheet.Name -notmatch $script:MonthlyPattern) { continue }
        for ($row=12; $row -le 100; $row++) {
            $amountValue = $sheet.Cells.Item($row,6).Value2
            $description = [string]$sheet.Cells.Item($row,7).Value2
            if ($null -eq $amountValue -or [string]::IsNullOrWhiteSpace($description)) { continue }
            $date = Get-HermesLegacyReceiptDate $sheet.Name $description
            if ($null -eq $date) { continue }
            $records.Add([pscustomobject]@{ Sheet=$sheet.Name; Row=$row; ReceiptDate=$date.Date; Amount=(ConvertTo-HermesMoney $amountValue); Description=$description; NormalizedMerchant=(Normalize-HermesMerchant ($description -replace '\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+\d{1,2}$','')) })
        }
    }
    return @($records)
}

function Get-HermesDuplicateDecision {
    param([Parameter(Mandatory)]$Record,[AllowEmptyCollection()][array]$ExistingRecords)
    $exact = @($ExistingRecords | Where-Object { $_.ReceiptDate.Date -eq $Record.ReceiptDate.Date -and (ConvertTo-HermesMoney $_.Amount) -eq (ConvertTo-HermesMoney $Record.Amount) -and $_.NormalizedMerchant -eq $Record.NormalizedMerchant })
    if ($exact.Count -gt 0) { return [pscustomobject]@{ Outcome='exact_duplicate'; ReasonCode='exact_workbook_duplicate'; Message='Same receipt date, amount, and normalized merchant already exists.'; Candidates=@($exact | ForEach-Object { [pscustomobject]@{sheet=$_.Sheet;row=$_.Row} }) } }
    $similar = @($ExistingRecords | Where-Object { $_.ReceiptDate.Date -eq $Record.ReceiptDate.Date -and (ConvertTo-HermesMoney $_.Amount) -eq (ConvertTo-HermesMoney $Record.Amount) })
    if ($similar.Count -gt 0) { return [pscustomobject]@{ Outcome='review'; ReasonCode='similar_date_amount'; Message='Same receipt date and amount exists with a different merchant.'; Candidates=@($similar | ForEach-Object { [pscustomobject]@{sheet=$_.Sheet;row=$_.Row} }) } }
    return [pscustomobject]@{ Outcome='inserted'; ReasonCode='eligible'; Message='No exact or similar workbook record found.'; Candidates=@() }
}

function Read-HermesState {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return [ordered]@{ schemaVersion=1; records=[ordered]@{} } }
    $state = Get-Content -Raw -LiteralPath $Path -ErrorAction Stop | ConvertFrom-Json -AsHashtable -DateKind String
    if($state -isnot [Collections.IDictionary] -or -not $state.Contains('schemaVersion') -or -not (Test-HermesJsonInteger $state.schemaVersion) -or [int64]$state.schemaVersion -ne 1 -or -not $state.Contains('records') -or $state.records -isnot [Collections.IDictionary]){throw 'State document must have integer schemaVersion 1 and a records object.'}
    foreach($key in @($state.records.Keys)){
        $id=[guid]::Empty;if($key -isnot [string] -or -not [guid]::TryParseExact($key,'D',[ref]$id) -or $id.ToString() -cne $key){throw "State record key is not a canonical expenseId: $key"}
        $record=$state.records[$key]
        if($record -isnot [Collections.IDictionary] -or -not $record.Contains('outcome') -or [string]$record.outcome -cnotin @('inserted','exact_duplicate')){throw "State record has an invalid outcome: $key"}
        if(-not $record.Contains('processedAt') -or $record.processedAt -isnot [string] -or [string]$record.processedAt -notmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,7})?(?:Z|[+-]\d{2}:\d{2})$'){throw "State record has an invalid processedAt: $key"}
        $processed=[datetimeoffset]::MinValue;if(-not [datetimeoffset]::TryParse([string]$record.processedAt,$script:Invariant,[Globalization.DateTimeStyles]::RoundtripKind,[ref]$processed)){throw "State record has an invalid processedAt: $key"}
        if(-not $record.Contains('sourceFileName') -or $record.sourceFileName -isnot [string] -or -not (Test-HermesHandoffFileName $record.sourceFileName) -or [string]$record.sourceFileName -cnotmatch ('_'+$id.ToString('N').Substring(0,8)+'\.json$')){throw "State record has an invalid sourceFileName: $key"}
        if($record.Contains('sheet') -and $null -ne $record.sheet -and ($record.sheet -isnot [string] -or [string]$record.sheet -notmatch $script:MonthlyPattern)){throw "State record has an invalid sheet: $key"}
        if($record.Contains('row') -and $null -ne $record.row -and (-not (Test-HermesJsonInteger $record.row) -or [int64]$record.row -lt 12 -or [int64]$record.row -gt 100)){throw "State record has an invalid row: $key"}
    }
    return $state
}

function Write-HermesState {
    param([Parameter(Mandatory)][hashtable]$State,[Parameter(Mandatory)][string]$Path)
    $directory = Split-Path -Parent $Path
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $temporary = Join-Path $directory ('.processed-expenses.' + [guid]::NewGuid().ToString('N') + '.tmp')
    try {
        $State | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $temporary -Encoding utf8 -NoNewline
        if (Test-Path -LiteralPath $Path -PathType Leaf) {
            try { [IO.File]::Replace($temporary, $Path, $null, $true) }
            catch { Move-Item -LiteralPath $temporary -Destination $Path -Force }
        } else { Move-Item -LiteralPath $temporary -Destination $Path }
    } finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue } }
}

function Get-HermesFileSha256([string]$Path) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256 -ErrorAction Stop).Hash }

function Write-HermesSidecar {
    param([Parameter(Mandatory)][string]$DestinationFolder,[Parameter(Mandatory)][string]$OriginalFileName,[Parameter(Mandatory)][string]$Outcome,[Parameter(Mandatory)][string]$ReasonCode,[Parameter(Mandatory)][string]$Message,[array]$Candidates=@())
    New-Item -ItemType Directory -Force -Path $DestinationFolder | Out-Null
    $sidecar = [ordered]@{ schemaVersion=1; originalFileName=$OriginalFileName; outcome=$Outcome; processedAt=[datetimeoffset]::UtcNow.ToString('o'); reasonCode=$ReasonCode; message=$Message; candidates=@($Candidates) }
    $path = Join-Path $DestinationFolder (([IO.Path]::GetFileNameWithoutExtension($OriginalFileName)) + '.result.json')
    $temporary=Join-Path $DestinationFolder ('.'+[IO.Path]::GetFileNameWithoutExtension($OriginalFileName)+'.result.'+[guid]::NewGuid().ToString('N')+'.tmp')
    try{$sidecar|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $temporary -Encoding utf8 -NoNewline;if(Test-Path -LiteralPath $path -PathType Leaf){try{[IO.File]::Replace($temporary,$path,$null,$true)}catch{Move-Item -LiteralPath $temporary -Destination $path -Force}}else{Move-Item -LiteralPath $temporary -Destination $path}}finally{if(Test-Path -LiteralPath $temporary){Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue}}
    return $path
}

function Move-HermesHandoff {
    param([Parameter(Mandatory)][string]$SourcePath,[Parameter(Mandatory)][string]$DestinationFolder,[switch]$StateProvesCompletion)
    New-Item -ItemType Directory -Force -Path $DestinationFolder | Out-Null
    $destination = Join-Path $DestinationFolder ([IO.Path]::GetFileName($SourcePath))
    if (-not (Test-Path -LiteralPath $destination -PathType Leaf)) { Move-Item -LiteralPath $SourcePath -Destination $destination; return $destination }
    if ((Get-HermesFileSha256 $SourcePath) -eq (Get-HermesFileSha256 $destination) -and $StateProvesCompletion) { Remove-Item -LiteralPath $SourcePath -Force; return $destination }
    throw "archive_conflict: destination already contains a different handoff: $destination"
}

function Move-HermesHandoffWithSidecar {
    param([Parameter(Mandatory)][string]$SourcePath,[Parameter(Mandatory)][string]$DestinationFolder,[Parameter(Mandatory)][string]$Outcome,[Parameter(Mandatory)][string]$ReasonCode,[Parameter(Mandatory)][string]$Message,[array]$Candidates=@(),[scriptblock]$ArchiveMover,[scriptblock]$SidecarWriter)
    $destination=Join-Path $DestinationFolder ([IO.Path]::GetFileName($SourcePath))
    if($ArchiveMover){&$ArchiveMover $SourcePath $DestinationFolder $false|Out-Null}else{Move-HermesHandoff $SourcePath $DestinationFolder|Out-Null}
    try{if($SidecarWriter){&$SidecarWriter $DestinationFolder ([IO.Path]::GetFileName($SourcePath)) $Outcome $ReasonCode $Message $Candidates|Out-Null}else{Write-HermesSidecar $DestinationFolder ([IO.Path]::GetFileName($SourcePath)) $Outcome $ReasonCode $Message $Candidates|Out-Null};return $destination}catch{
        $sidecarError=$_.Exception.Message
        try{if(Test-Path -LiteralPath $destination -PathType Leaf){if(Test-Path -LiteralPath $SourcePath){throw 'rollback_source_conflict'};Move-Item -LiteralPath $destination -Destination $SourcePath}}catch{throw "sidecar_failed_and_rollback_failed: $sidecarError; $($_.Exception.Message)"}
        throw "sidecar_failed: $sidecarError"
    }
}

function Enter-HermesRunLock {
    param([string]$Name='Global\SmartExpenseTracking.HermesExcelUpdate')
    $mutex = [Threading.Mutex]::new($false, $Name)
    if (-not $mutex.WaitOne(0)) { $mutex.Dispose(); return $null }
    return $mutex
}

function Exit-HermesRunLock([AllowNull()]$Lock) { if ($null -ne $Lock) { try { $Lock.ReleaseMutex() } catch {}; $Lock.Dispose() } }

function Get-HermesWorkbookHyperlinkPath {
    param([Parameter(Mandatory)][string]$RelativeImagePath)
    $prefix = $script:OneDriveExpenseRoot + '/'
    if (-not $RelativeImagePath.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Image path is outside the workbook expense folder.' }
    return ($RelativeImagePath.Substring($prefix.Length) -replace '/', '\')
}

function Test-HermesMonthlySheetStructure {
    param([Parameter(Mandatory)]$Sheet,[Parameter(Mandatory)][string]$Month)
    if ($Sheet.Name -ne $Month -or $Sheet.Cells.Item(1,6).Value2 -ne 'Expense' -or $Sheet.Cells.Item(1,7).Value2 -ne 'Description') { throw "Unexpected monthly sheet structure: $Month" }
    return $true
}

function Get-HermesMonthlySheetNames {
    param([Parameter(Mandatory)]$Workbook)
    $months = [Collections.Generic.List[string]]::new()
    for ($i=1; $i -le $Workbook.Worksheets.Count; $i++) { $name=[string]$Workbook.Worksheets.Item($i).Name; if($name -match $script:MonthlyPattern){$months.Add($name)} }
    return @($months | Sort-Object)
}

function Ensure-HermesMonthlySheet {
    param([Parameter(Mandatory)]$Workbook,[Parameter(Mandatory)][string]$Month)
    try { $existing=$Workbook.Worksheets.Item($Month); Test-HermesMonthlySheetStructure $existing $Month | Out-Null; return $existing } catch { }
    $targetDate=[datetime]::ParseExact("$Month-01",'yyyy-MM-dd',$script:Invariant)
    $sources=@(Get-HermesMonthlySheetNames $Workbook | Where-Object { [datetime]::ParseExact("$_-01",'yyyy-MM-dd',$script:Invariant) -lt $targetDate })
    if($sources.Count -eq 0){throw "no_earlier_month_source: $Month"}
    $sourceName=$sources[-1];$source=$Workbook.Worksheets.Item($sourceName)
    Test-HermesMonthlySheetStructure $source $sourceName | Out-Null
    $later=@(Get-HermesMonthlySheetNames $Workbook | Where-Object { [datetime]::ParseExact("$_-01",'yyyy-MM-dd',$script:Invariant) -gt $targetDate })
    if($later.Count -gt 0){$before=$Workbook.Worksheets.Item($later[0])}else{$before=$Workbook.Worksheets.Item('Food Expense Summary');if($before.Index -le $source.Index){throw 'summary_sheet_position_unexpected'}}
    $source.Copy($before)|Out-Null
    $created=$Workbook.Application.ActiveSheet
    $created.Name=$Month
    $created.Range('F12:H100').ClearContents() | Out-Null
    try { $created.Range('F12:H100').Hyperlinks.Delete() | Out-Null } catch { }
    Test-HermesMonthlySheetStructure $created $Month | Out-Null
    return $created
}

function Get-HermesSummaryModel {
    param([Parameter(Mandatory)]$Workbook)
    try { $summary=$Workbook.Worksheets.Item('Food Expense Summary') } catch { throw 'summary_missing' }
    if($summary.Cells.Item(3,1).Value2 -ne 'Month' -or $summary.Cells.Item(3,5).Value2 -ne 'Month Total'){throw 'summary_structure_unexpected'}
    $detailHeaderRow=$null;for($candidateRow=14;$candidateRow -le 100;$candidateRow++){if($summary.Cells.Item($candidateRow,1).Value2 -eq 'Month' -and $summary.Cells.Item($candidateRow,6).Value2 -eq 'Source Sheet'){$detailHeaderRow=$candidateRow;break}}
    if($null -eq $detailHeaderRow){throw 'summary_structure_unexpected'}
    $monthRows=[Collections.Generic.List[object]]::new();$row=4
    while($row -lt 100 -and [string]$summary.Cells.Item($row,1).Value2 -ne 'Accumulated'){
        $value=[string]$summary.Cells.Item($row,1).Value2
        if($value -notmatch $script:MonthlyPattern){throw "summary_month_invalid_at_row_$row"}
        $monthRows.Add([pscustomobject]@{Month=$value;Row=$row});$row++
    }
    if($monthRows.Count -eq 0 -or [string]$summary.Cells.Item($row,1).Value2 -ne 'Accumulated'){throw 'summary_accumulated_row_missing'}
    $detailStart=$detailHeaderRow+1;$detailEnd=$detailStart + ($monthRows.Count * 89) - 1
    if([string]$summary.Cells.Item($detailEnd,1).Value2 -ne $monthRows[-1].Month){throw 'summary_detail_block_unexpected'}
    $coverageStatusRow=$detailHeaderRow-2
    if([string]$summary.Cells.Item($coverageStatusRow,6).Value2 -notin @('OK','Review')){throw 'summary_coverage_status_missing'}
    return [pscustomobject]@{ Sheet=$summary; MonthRows=@($monthRows); AccumulatedRow=$row; DetailStart=$detailStart; DetailEnd=$detailEnd; CoverageStatusRow=$coverageStatusRow }
}

function Add-HermesSummaryMonth {
    param([Parameter(Mandatory)]$Workbook,[Parameter(Mandatory)][string]$Month)
    $model=Get-HermesSummaryModel $Workbook;$last=$model.MonthRows[-1].Month
    if(@($model.MonthRows | Where-Object Month -eq $Month).Count -gt 0){return $model}
    $target=[datetime]::ParseExact("$Month-01",'yyyy-MM-dd',$script:Invariant);$latest=[datetime]::ParseExact("$last-01",'yyyy-MM-dd',$script:Invariant)
    if($target -le $latest){throw 'summary_out_of_order'}
    if($target.AddMonths(-1).ToString('yyyy-MM') -ne $last){throw 'summary_chronological_gap'}
    $summary=$model.Sheet;$accum=$model.AccumulatedRow;$newMonthRow=$accum
    $summary.Rows.Item($accum).Insert()
    $newMonthRow=$accum;$newAccum=$accum+1
    $previous=$summary.Rows.Item($newMonthRow-1);$previous.Copy($summary.Rows.Item($newMonthRow))
    $summary.Cells.Item($newMonthRow,1).Value2=$Month
    for($c=2;$c -le 8;$c++){$summary.Cells.Item($newMonthRow,$c).FormulaR1C1=$summary.Cells.Item($newMonthRow-1,$c).FormulaR1C1}
    $newDetailStart=$model.DetailStart+1;$previousDetailEnd=$model.DetailEnd+1;$previousDetailStart=$previousDetailEnd-88;$newBlockStart=$previousDetailEnd+1;$newDetailEnd=$newBlockStart+88
    $summary.Range("A${previousDetailStart}:F${previousDetailEnd}").Copy($summary.Range("A${newBlockStart}"))
    # The copied formulas are reset explicitly so the new block always points at the new month sheet.
    for($offset=0;$offset -lt 89;$offset++){
        $r=$newBlockStart+$offset;$sourceRow=12+$offset
        $summary.Cells.Item($r,1).Value2=$Month;$summary.Cells.Item($r,2).Value2=[double]$sourceRow
        $summary.Cells.Item($r,3).Formula = '=IF(''{0}''!F{1}="","",''{0}''!F{1})' -f $Month,$sourceRow
        $summary.Cells.Item($r,4).Formula = '=IF(''{0}''!G{1}="","",''{0}''!G{1})' -f $Month,$sourceRow
        $summary.Cells.Item($r,5).FormulaR1C1=$summary.Cells.Item($previousDetailEnd,5).FormulaR1C1
        $summary.Cells.Item($r,6).Value2=$Month
    }
    $allMonths=@($model.MonthRows | ForEach-Object Month)+$Month
    $monthLast=$newMonthRow
    for($r=4;$r -le $monthLast;$r++){$summary.Cells.Item($r,2).Formula='=SUMIFS($C${0}:$C${1},$A${0}:$A${1},$A{2},$E${0}:$E${1},"Restaurant")' -f $newDetailStart,$newDetailEnd,$r;$summary.Cells.Item($r,3).Formula='=SUMIFS($C${0}:$C${1},$A${0}:$A${1},$A{2},$E${0}:$E${1},"Grocery")' -f $newDetailStart,$newDetailEnd,$r;$summary.Cells.Item($r,4).Formula='=SUMIFS($C${0}:$C${1},$A${0}:$A${1},$A{2},$E${0}:$E${1},"Others")' -f $newDetailStart,$newDetailEnd,$r;$summary.Cells.Item($r,5).Formula='=SUM(B{0}:D{0})' -f $r;$summary.Cells.Item($r,6).Formula='=IF($E{0}=0,0,B{0}/$E{0})' -f $r;$summary.Cells.Item($r,7).Formula='=IF($E{0}=0,0,C{0}/$E{0})' -f $r;$summary.Cells.Item($r,8).Formula='=IF($E{0}=0,0,D{0}/$E{0})' -f $r}
    for($c=2;$c -le 5;$c++){$letter=[char](64+$c);$summary.Cells.Item($newAccum,$c).Formula='=SUM({0}$4:{0}${1})' -f $letter,$monthLast}
    $summary.Cells.Item($newAccum,6).Formula='=IF($E{0}=0,0,B{0}/$E{0})' -f $newAccum;$summary.Cells.Item($newAccum,7).Formula='=IF($E{0}=0,0,C{0}/$E{0})' -f $newAccum;$summary.Cells.Item($newAccum,8).Formula='=IF($E{0}=0,0,D{0}/$E{0})' -f $newAccum
    $summary.Cells.Item(1,1).Value2="Food Expense Summary ($($allMonths[0]) to $Month)"
    $summary.Cells.Item(15,6).Formula='=SUMPRODUCT(--(LEN($C${0}:$C${1})>0),--(LEN($D${0}:$D${1})>0))' -f $newDetailStart,$newDetailEnd
    $parts=@($allMonths | ForEach-Object { "SUMPRODUCT(--(LEN('$_'!F12:F100)>0),--(LEN('$_'!G12:G100)>0))" });$summary.Cells.Item(16,6).Formula='='+($parts -join '+');$summary.Cells.Item(17,6).Formula='=IF(F15=F16,"OK","Review")'
    $null=$model.MonthRows=@($model.MonthRows)+[pscustomobject]@{Month=$Month;Row=$newMonthRow};$null=$model.AccumulatedRow=$newAccum;$null=$model.DetailStart=$newDetailStart;$null=$model.DetailEnd=$newDetailEnd;$null=$model.CoverageStatusRow=17
    return $model
}

function Invoke-HermesExcelWorkbookUpdate {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$WorkbookPath,[Parameter(Mandatory)][array]$Items,[switch]$DryRun)
    if(-not (Test-Path -LiteralPath $WorkbookPath -PathType Leaf)){throw "workbook_unavailable: $WorkbookPath"}
    $ownerFile=Join-Path (Split-Path -Parent $WorkbookPath) ('~$'+[IO.Path]::GetFileName($WorkbookPath));if(-not $DryRun -and (Test-Path -LiteralPath $ownerFile -PathType Leaf)){throw "workbook_locked: $ownerFile"}
    $excel=$null;$book=$null;$saved=$false;$resultItems=[Collections.Generic.List[object]]::new();$touched=$false
    try {
        $excel=New-Object -ComObject Excel.Application;$excel.Visible=$false;$excel.DisplayAlerts=$false;$excel.ScreenUpdating=$false;$excel.EnableEvents=$false
        $book=$excel.Workbooks.Open($WorkbookPath,$null,$DryRun.IsPresent)
        if($book.ReadOnly -and -not $DryRun){throw 'workbook_read_only'}
        $summary=Get-HermesSummaryModel $book
        $existing=@(Get-HermesWorkbookRecords $book)
        $staged=[Collections.Generic.List[object]]::new();$stagedRecords=[Collections.Generic.List[object]]::new();$targetSheets=@{}
        foreach($item in $Items){
            $record=[pscustomobject]@{ReceiptDate=$item.Validation.ReceiptDate.Date;Amount=$item.Validation.Amount;NormalizedMerchant=(Normalize-HermesMerchant $item.Validation.MerchantName);Sheet=$null;Row=$null}
            $decision=Get-HermesDuplicateDecision $record (@($existing)+@($stagedRecords))
            if($decision.Outcome -eq 'exact_duplicate' -or $decision.Outcome -eq 'review'){
                $resultItems.Add([pscustomobject]@{FileName=$item.FileName;ExpenseId=$item.Validation.ExpenseId;Outcome=$decision.Outcome;ReasonCode=$decision.ReasonCode;Message=$decision.Message;Sheet=$item.Validation.ReceiptMonth;Row=if($decision.Candidates.Count -gt 0){$decision.Candidates[0].row}else{$null};Candidates=$decision.Candidates});continue
            }
            if(-not $targetSheets.ContainsKey($item.Validation.ReceiptMonth)){$targetSheets[$item.Validation.ReceiptMonth]=Ensure-HermesMonthlySheet $book $item.Validation.ReceiptMonth}
            $sheet=$targetSheets[$item.Validation.ReceiptMonth]
            Test-HermesMonthlySheetStructure $sheet $item.Validation.ReceiptMonth | Out-Null
            $rows=@();for($r=12;$r -le 100;$r++){$rows+=@{F=$sheet.Cells.Item($r,6).Value2;G=$sheet.Cells.Item($r,7).Value2}}
            foreach($occupied in @($staged | Where-Object { $_.Sheet.Name -eq $sheet.Name } | ForEach-Object Row)){$rows[$occupied-12]=@{F='__staged__';G='__staged__'}}
            $slot=Find-HermesSafeTransactionRow $rows
            if($null -eq $slot.Row){throw "workbook_capacity: no safe F/G row in $($item.Validation.ReceiptMonth)"}
            $record.Sheet=$sheet.Name;$record.Row=$slot.Row
            $staged.Add([pscustomobject]@{Item=$item;Sheet=$sheet;Row=$slot.Row;Record=$record;Warnings=$slot.Warnings})
            $stagedRecords.Add($record)
        }
        if(-not $DryRun){
            foreach($change in $staged){
                $sheet=$change.Sheet;$row=[int]$change.Row
                $sourceRow=$null
                if($null -eq $sheet.Cells.Item($row,15).Formula -or [string]::IsNullOrWhiteSpace([string]$sheet.Cells.Item($row,15).Formula) -or $sheet.Cells.Item($row,6).Style.NameLocal -ne $sheet.Cells.Item(12,6).Style.NameLocal){
                    for($candidate=$row-1;$candidate -ge 12;$candidate--){if(-not [string]::IsNullOrWhiteSpace([string]$sheet.Cells.Item($candidate,7).Value2) -and -not [string]::IsNullOrWhiteSpace([string]$sheet.Cells.Item($candidate,15).Formula)){$sourceRow=$candidate;break}}
                    if($null -eq $sourceRow){throw "row_template_unavailable: $($sheet.Name)!$row"}
                    $source=$sheet.Rows.Item($sourceRow);$destination=$sheet.Rows.Item($row);$source.Copy();$destination.PasteSpecial(-4122);$excel.CutCopyMode=$false
                    for($column=1;$column -le 16;$column++){if($column -notin @(5,6,7,8) -and [string]$source.Cells.Item(1,$column).Formula -match '^='){ $destination.Cells.Item(1,$column).FormulaR1C1=$source.Cells.Item(1,$column).FormulaR1C1 }}
                }
                $amountValue=[double]$change.Item.Validation.Amount;$descriptionValue=[string]$change.Item.Validation.Description
                try{$cellF=$sheet.Range(('F' + [string]$row));$cellF.Value2=$amountValue}catch{throw "write_amount_failed: $($_.Exception.Message)"}
                try{$cellG=$sheet.Range(('G' + [string]$row));$cellG.Formula=([char]39)+$descriptionValue}catch{throw "write_description_failed: $($_.Exception.Message)"}
                $address=$sheet.Range(('H' + [string]$row));$target=(Get-HermesWorkbookHyperlinkPath $change.Item.Validation.ReceiptImageRelativePath);$sheet.Hyperlinks.Add($address,$target,'','',$change.Item.Validation.ReceiptImageRelativePath)|Out-Null
                $warningRows=@($change.Warnings | ForEach-Object Row);$reason=if($warningRows.Count -gt 0){'inserted_with_structural_warning'}else{'inserted'};$message=if($warningRows.Count -gt 0){'Expense inserted; partially populated transaction rows were skipped: '+($warningRows -join ', ')}else{'Expense inserted into the workbook.'}
                $resultItems.Add([pscustomobject]@{FileName=$change.Item.FileName;ExpenseId=$change.Item.Validation.ExpenseId;Outcome='inserted';ReasonCode=$reason;Message=$message;Sheet=$sheet.Name;Row=$row;Candidates=@()});$touched=$true
            }
            if($touched){$monthsToExtend=@($staged | ForEach-Object { $_.Item.Validation.ReceiptMonth } | Sort-Object -Unique);foreach($month in $monthsToExtend){$summary=@(Add-HermesSummaryMonth $book $month)[-1]};$excel.Calculate();if([string]$summary.Sheet.Cells.Item($summary.CoverageStatusRow,6).Value2 -ne 'OK'){throw 'summary_coverage_failed'};$book.Save();$saved=$true}
        } else {
            foreach($change in $staged){$warningRows=@($change.Warnings | ForEach-Object Row);$reason=if($warningRows.Count -gt 0){'dry_run_eligible_with_structural_warning'}else{'dry_run_eligible'};$message=if($warningRows.Count -gt 0){'No duplicate found; workbook was not changed. Partially populated transaction rows would be skipped: '+($warningRows -join ', ')}else{'No duplicate found; workbook was not changed.'};$resultItems.Add([pscustomobject]@{FileName=$change.Item.FileName;ExpenseId=$change.Item.Validation.ExpenseId;Outcome='eligible';ReasonCode=$reason;Message=$message;Sheet=$change.Item.Validation.ReceiptMonth;Row=$change.Row;Candidates=@()})}
        }
        $orderedItems=@();foreach($sourceItem in $Items){$orderedItems+=@($resultItems|Where-Object FileName -eq $sourceItem.FileName|Select-Object -First 1)}
        return [pscustomobject]@{Saved=$saved;Items=$orderedItems;WorkbookPath=$WorkbookPath}
    } finally {
        if($book){try{$book.Close($false)}catch{};try{[Runtime.InteropServices.Marshal]::ReleaseComObject($book)|Out-Null}catch{}}
        if($excel){try{$excel.Quit()}catch{};try{[Runtime.InteropServices.Marshal]::ReleaseComObject($excel)|Out-Null}catch{}}
        [GC]::Collect();[GC]::WaitForPendingFinalizers()
    }
}

function New-HermesResultItem {
    param([string]$FileName,[string]$ExpenseId,[string]$Outcome,[string]$ReasonCode,[string]$Message,[string]$Sheet,[object]$Row)
    $item=[ordered]@{fileName=$FileName;outcome=$Outcome;reasonCode=$ReasonCode;message=$Message}
    if($ExpenseId){$item.expenseId=$ExpenseId};if($Sheet){$item.sheet=$Sheet};if($null -ne $Row){$item.row=$Row};return [pscustomobject]$item
}

function Format-HermesOutput {
    param([Parameter(Mandatory)]$Result,[ValidateSet('Json','Hermes')][string]$OutputFormat)
    if($OutputFormat -eq 'Json'){return ($Result|ConvertTo-Json -Depth 12 -Compress)}
    $getCount={param($name) $property=$Result.counts.PSObject.Properties[$name];if($null -eq $property -or $null -eq $property.Value){0}else{$property.Value}}
    $discovered=&$getCount 'discovered';$failed=&$getCount 'failed';$deferred=&$getCount 'deferred'
    if($discovered -eq 0 -and $failed -eq 0 -and $deferred -eq 0){return '[SILENT]'}
    $inserted=&$getCount 'inserted';$exact=&$getCount 'exactDuplicate';$review=&$getCount 'review';$invalid=&$getCount 'invalidError'
    $problems=@($Result.items|Where-Object {$_.outcome -in @('review','error','deferred','failed') -or $_.reasonCode -match 'structural_warning'}|ForEach-Object { if($_.fileName){"$($_.fileName):$($_.reasonCode)"}else{"processor:$($_.reasonCode)"} })
    $suffix=if($problems.Count -gt 0){'; problems='+($problems -join ',')}else{''}
    return "Smart Expense: discovered=$discovered; inserted=$inserted; exactDuplicate=$exact; review=$review; invalidError=$invalid; deferred=$deferred; failed=$failed$suffix"
}

function Invoke-HermesProcessor {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$OneDriveRoot,[Parameter(Mandatory)][string]$InboxRelativePath,[Parameter(Mandatory)][string]$WorkbookRelativePath,
        [Parameter(Mandatory)][string]$StatePath,[Parameter(Mandatory)][string]$LogPath,[switch]$DryRun,
        [scriptblock]$WorkbookUpdater,[scriptblock]$StateWriter,[scriptblock]$ArchiveMover,[scriptblock]$SidecarWriter,[scriptblock]$NowProvider
    )
    $started=if($NowProvider){&$NowProvider}else{[datetimeoffset]::UtcNow};$counts=[ordered]@{discovered=0;inserted=0;exactDuplicate=0;review=0;invalidError=0;deferred=0;failed=0};$items=[Collections.Generic.List[object]]::new();$lock=Enter-HermesRunLock
    $result=$null
    try {
        if($null -eq $lock){$counts.deferred=1;$items.Add((New-HermesResultItem $null $null 'deferred' 'processor_locked' 'Another processor run is active.' $null $null));$result=[pscustomobject]@{schemaVersion=1;startedAt=$started.ToString('o');completedAt=[datetimeoffset]::UtcNow.ToString('o');outcome='deferred';counts=[pscustomobject]$counts;items=@($items)};if(-not $DryRun){$logDirectory=Split-Path -Parent $LogPath;New-Item -ItemType Directory -Force -Path $logDirectory|Out-Null;($result|ConvertTo-Json -Depth 12 -Compress)|Add-Content -LiteralPath $LogPath -Encoding utf8};return $result}
        $root=Get-HermesFullPath $OneDriveRoot;$inbox=Get-HermesFullPath (Join-Path $root $InboxRelativePath);$workbook=Get-HermesFullPath (Join-Path $root $WorkbookRelativePath)
        if(-not (Test-Path -LiteralPath $inbox -PathType Container)){throw "Inbox does not exist: $inbox"}
        $state=Read-HermesState $StatePath;$files=@(Get-HermesOrdinalFiles $inbox);$counts.discovered=$files.Count;$eligible=[Collections.Generic.List[object]]::new()
        foreach($file in $files){
            try{$payload=Get-Content -Raw -LiteralPath $file.FullName -ErrorAction Stop|ConvertFrom-Json -DateKind String}catch{$item=New-HermesResultItem $file.Name $null 'error' 'invalid_json' $_.Exception.Message $null $null;$items.Add($item);$counts.invalidError++;if(-not $DryRun){$dest=Join-Path $inbox 'receipt_jsons_error';Move-HermesHandoff $file.FullName $dest|Out-Null;Write-HermesSidecar $dest $file.Name 'error' 'invalid_json' $item.message|Out-Null};continue}
            $valid=Test-HermesVersion2Handoff $payload $file.Name $root
            if(-not $valid.Valid){$item=New-HermesResultItem $file.Name $null 'error' $valid.ReasonCode $valid.Message $null $null;$items.Add($item);$counts.invalidError++;if(-not $DryRun){$dest=Join-Path $inbox 'receipt_jsons_error';Move-HermesHandoff $file.FullName $dest|Out-Null;Write-HermesSidecar $dest $file.Name 'error' $valid.ReasonCode $valid.Message|Out-Null};continue}
            if([string]$payload.currency -cne 'CAD'){
                $item=New-HermesResultItem $file.Name $valid.ExpenseId 'review' 'non_cad' 'Only CAD handoffs may change the workbook.' $valid.ReceiptMonth $null
                if(-not $DryRun){
                    try{
                        $dest=Join-Path $inbox 'receipt_jsons_review'
                        Move-HermesHandoffWithSidecar -SourcePath $file.FullName -DestinationFolder $dest -Outcome 'review' -ReasonCode 'non_cad' -Message $item.message -ArchiveMover $ArchiveMover -SidecarWriter $SidecarWriter|Out-Null
                    }catch{
                        $items.Add((New-HermesResultItem $file.Name $valid.ExpenseId 'failed' 'archive_failed' $_.Exception.Message $valid.ReceiptMonth $null))
                        $counts.failed++
                        continue
                    }
                }
                $items.Add($item)
                $counts.review++
                continue
            }
            $key=$valid.ExpenseId.ToString();if($state.records.Contains($key)){$record=$state.records[$key];$item=New-HermesResultItem $file.Name $key 'exact_duplicate' 'already_processed' 'expenseId was already completed; no workbook write was attempted.' $record.sheet $record.row;$items.Add($item);$counts.exactDuplicate++;if(-not $DryRun){$dest=Join-Path $inbox 'receipt_jsons_done';Move-HermesHandoff $file.FullName $dest -StateProvesCompletion|Out-Null};continue}
            $eligible.Add([pscustomobject]@{FileName=$file.Name;Path=$file.FullName;Payload=$payload;Validation=$valid})
        }
        if($eligible.Count -gt 0){
            if(-not $WorkbookUpdater){$WorkbookUpdater={param($path,$work,$dry) Invoke-HermesExcelWorkbookUpdate -WorkbookPath $path -Items $work -DryRun:$dry}}
            try{$update=&$WorkbookUpdater $workbook @($eligible) $DryRun.IsPresent}catch{$updateError=$_.Exception.Message;$isDeferred=$updateError -match 'workbook_|summary_|row_template|no_earlier|read_only|locked|capacity|structure|unavailable';$failureOutcome=if($isDeferred){'deferred'}else{'failed'};$failureReason=if($isDeferred){'workbook_deferred'}else{'workbook_update_failed'};foreach($candidate in $eligible){$item=New-HermesResultItem $candidate.FileName $candidate.Validation.ExpenseId $failureOutcome $failureReason $updateError $candidate.Validation.ReceiptMonth $null;$items.Add($item);if($isDeferred){$counts.deferred++}else{$counts.failed++}};$update=$null}
            if($update){
                foreach($outcome in $update.Items){
                    $candidate=$eligible|Where-Object FileName -eq $outcome.FileName|Select-Object -First 1
                    $base=New-HermesResultItem $outcome.FileName $outcome.ExpenseId $outcome.Outcome $outcome.ReasonCode $outcome.Message $outcome.Sheet $outcome.Row
                    if($outcome.Outcome -eq 'inserted' -or $outcome.Outcome -eq 'exact_duplicate'){
                        if($DryRun){$items.Add($base);if($outcome.Outcome -eq 'inserted'){$counts.inserted++}else{$counts.exactDuplicate++};continue}
                        try{$state.records[$outcome.ExpenseId]=[ordered]@{outcome=$outcome.Outcome;processedAt=[datetimeoffset]::UtcNow.ToString('o');sourceFileName=$outcome.FileName;sheet=$outcome.Sheet;row=$outcome.Row};if($StateWriter){&$StateWriter $state $StatePath}else{Write-HermesState $state $StatePath};$done=Join-Path $inbox 'receipt_jsons_done';if($ArchiveMover){&$ArchiveMover $candidate.Path $done $true}else{Move-HermesHandoff $candidate.Path $done -StateProvesCompletion|Out-Null};$items.Add($base);if($outcome.Outcome -eq 'inserted'){$counts.inserted++}else{$counts.exactDuplicate++}}catch{$archiveReason=if($_.Exception.Message -match 'archive_conflict'){'archive_conflict'}else{'state_or_archive_failed'};$failed=New-HermesResultItem $outcome.FileName $outcome.ExpenseId 'failed' $archiveReason $_.Exception.Message $outcome.Sheet $outcome.Row;$items.Add($failed);$counts.failed++}
                    }elseif($outcome.Outcome -eq 'review'){
                        if(-not $DryRun){
                            try{
                                $dest=Join-Path $inbox 'receipt_jsons_review'
                                Move-HermesHandoffWithSidecar -SourcePath $candidate.Path -DestinationFolder $dest -Outcome 'review' -ReasonCode $outcome.ReasonCode -Message $outcome.Message -Candidates $outcome.Candidates -ArchiveMover $ArchiveMover -SidecarWriter $SidecarWriter|Out-Null
                                $items.Add($base)
                                $counts.review++
                            }catch{
                                $items.Add((New-HermesResultItem $candidate.FileName $candidate.Validation.ExpenseId 'failed' 'archive_failed' $_.Exception.Message $outcome.Sheet $outcome.Row))
                                $counts.failed++
                            }
                        }else{$items.Add($base);$counts.review++}
                    }elseif($outcome.Outcome -eq 'eligible' -and $DryRun){
                        $items.Add($base)
                    }else{
                        $items.Add($base)
                        if($outcome.Outcome -eq 'deferred'){$counts.deferred++}else{$counts.failed++}
                    }
                }
            }
        }
        $outcomeName=if($counts.failed -gt 0){'completed_with_failures'}elseif($counts.deferred -gt 0){'deferred'}elseif($counts.discovered -eq 0){'no_work'}else{'handled'}
        $result=[pscustomobject]@{schemaVersion=1;startedAt=$started.ToString('o');completedAt=[datetimeoffset]::UtcNow.ToString('o');outcome=$outcomeName;counts=[pscustomobject]$counts;items=@($items)}
        if(-not $DryRun){$logDirectory=Split-Path -Parent $LogPath;New-Item -ItemType Directory -Force -Path $logDirectory|Out-Null;($result|ConvertTo-Json -Depth 12 -Compress)|Add-Content -LiteralPath $LogPath -Encoding utf8}
        return $result
    } finally { Exit-HermesRunLock $lock }
}

Export-ModuleMember -Function *-Hermes*
