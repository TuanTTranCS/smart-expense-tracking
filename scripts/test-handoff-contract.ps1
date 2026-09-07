$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$validDir = Join-Path $repoRoot 'fixtures/handoff/valid'
$invalidDir = Join-Path $repoRoot 'fixtures/handoff/invalid'

function Assert-Condition {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function Test-RequiredString {
    param(
        [object]$Value,
        [string]$Name
    )

    Assert-Condition ($null -ne $Value) "$Name is required."
    Assert-Condition (-not [string]::IsNullOrWhiteSpace([string]$Value)) "$Name must not be blank."
}

function Test-HandoffPayload {
    param(
        [object]$Payload
    )

    Assert-Condition ($Payload.schemaVersion -eq 1) 'schemaVersion must be 1.'

    Test-RequiredString $Payload.expenseId 'expenseId'
    Test-RequiredString $Payload.createdAt 'createdAt'
    Test-RequiredString $Payload.sourceDeviceId 'sourceDeviceId'
    Test-RequiredString $Payload.receiptDate 'receiptDate'
    Test-RequiredString $Payload.merchantName 'merchantName'
    Test-RequiredString $Payload.currency 'currency'
    Test-RequiredString $Payload.extractionStatus 'extractionStatus'

    $expenseGuid = [Guid]::Empty
    Assert-Condition ([Guid]::TryParse([string]$Payload.expenseId, [ref]$expenseGuid)) 'expenseId must be a UUID.'

    $createdAt = [DateTimeOffset]::MinValue
    Assert-Condition ([string]$Payload.createdAt -cmatch '(Z|[+-]\d{2}:\d{2})$') 'createdAt must include timezone information.'
    Assert-Condition ([DateTimeOffset]::TryParse([string]$Payload.createdAt, [ref]$createdAt)) 'createdAt must be an ISO-8601 timestamp.'

    $receiptDate = [DateTime]::MinValue
    Assert-Condition ([DateTime]::TryParseExact([string]$Payload.receiptDate, 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::None, [ref]$receiptDate)) 'receiptDate must be yyyy-MM-dd.'

    Assert-Condition ([string]$Payload.currency -cmatch '^[A-Z]{3}$') 'currency must be three uppercase letters.'

    $allowedStatuses = @('confirmed', 'manual', 'low_confidence', 'failed')
    Assert-Condition ($allowedStatuses -contains [string]$Payload.extractionStatus) 'extractionStatus is unsupported.'

    Assert-Condition ($null -ne $Payload.totalAmount) 'totalAmount is required.'
    $totalAmount = [decimal]$Payload.totalAmount
    Assert-Condition ($totalAmount -ge 0) 'totalAmount must be non-negative.'

    foreach ($field in @('taxAmount', 'tipAmount')) {
        if ($null -ne $Payload.$field) {
            Assert-Condition ([decimal]$Payload.$field -ge 0) "$field must be non-negative."
        }
    }
}

function Test-HandoffFileName {
    param(
        [string]$FileName,
        [object]$Payload
    )

    Assert-Condition ($FileName -cmatch '^expense_\d{8}_\d{6}_[0-9a-f]{8}\.json$') "File name does not match handoff pattern: $FileName"

    $shortExpenseId = ([string]$Payload.expenseId).Replace('-', '').Substring(0, 8).ToLowerInvariant()
    Assert-Condition ($FileName.EndsWith("_$shortExpenseId.json")) "File name short id does not match expenseId: $FileName"
}

function Read-HandoffJson {
    param([string]$Path)
    Get-Content -Raw -Path $Path | ConvertFrom-Json
}

$validFiles = Get-ChildItem -Path $validDir -Filter '*.json' -File
$invalidFiles = Get-ChildItem -Path $invalidDir -Filter '*.json' -File

Assert-Condition ($validFiles.Count -gt 0) 'Expected at least one valid fixture.'
Assert-Condition ($invalidFiles.Count -gt 0) 'Expected at least one invalid fixture.'

foreach ($file in $validFiles) {
    $payload = Read-HandoffJson $file.FullName
    Test-HandoffPayload $payload
    Test-HandoffFileName $file.Name $payload
}

foreach ($file in $invalidFiles) {
    $payload = Read-HandoffJson $file.FullName
    $failedAsExpected = $false

    try {
        Test-HandoffPayload $payload
    }
    catch {
        $failedAsExpected = $true
    }

    Assert-Condition $failedAsExpected "Invalid fixture passed validation: $($file.Name)"
}

Write-Host "Handoff contract fixtures passed: $($validFiles.Count) valid accepted, $($invalidFiles.Count) invalid rejected."
