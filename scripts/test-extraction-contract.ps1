param(
    [string]$RootPath = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
)

$ErrorActionPreference = 'Stop'

function Test-ExtractionPayload {
    param(
        [Parameter(Mandatory)]
        [string]$Path
    )

    $errors = [System.Collections.Generic.List[string]]::new()
    $content = Get-Content -LiteralPath $Path -Raw

    try {
        $payload = $content | ConvertFrom-Json
    } catch {
        return @("Model output must contain parseable JSON.")
    }

    foreach ($field in @('receiptDate', 'merchantName', 'totalAmount', 'currency', 'extractionStatus')) {
        if (-not ($payload.PSObject.Properties.Name -contains $field)) {
            $errors.Add("$field is required.")
        }
    }

    if ($payload.PSObject.Properties.Name -contains 'receiptDate') {
        $date = [datetime]::MinValue
        if (-not [datetime]::TryParseExact($payload.receiptDate, 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::None, [ref]$date)) {
            $errors.Add('receiptDate must be an ISO date in yyyy-MM-dd format.')
        }
    }

    if (($payload.PSObject.Properties.Name -contains 'merchantName') -and [string]::IsNullOrWhiteSpace($payload.merchantName)) {
        $errors.Add('merchantName is required.')
    }

    if ($payload.PSObject.Properties.Name -contains 'totalAmount') {
        if (-not ($payload.totalAmount -is [decimal] -or $payload.totalAmount -is [double] -or $payload.totalAmount -is [int] -or $payload.totalAmount -is [long])) {
            $errors.Add('totalAmount must be numeric.')
        } elseif ([decimal]$payload.totalAmount -lt 0) {
            $errors.Add('totalAmount must be non-negative.')
        }
    }

    if (($payload.PSObject.Properties.Name -contains 'currency') -and ($payload.currency -notmatch '^[A-Z]{3}$')) {
        $errors.Add('currency must be a three-letter uppercase code.')
    }

    if (($payload.PSObject.Properties.Name -contains 'extractionStatus') -and ($payload.extractionStatus -notin @('confirmed', 'manual', 'low_confidence', 'failed'))) {
        $errors.Add('extractionStatus must be confirmed, manual, low_confidence, or failed.')
    }

    if (($payload.PSObject.Properties.Name -contains 'confidence') -and $null -ne $payload.confidence) {
        if (-not ($payload.confidence -is [decimal] -or $payload.confidence -is [double] -or $payload.confidence -is [int] -or $payload.confidence -is [long])) {
            $errors.Add('confidence must be numeric.')
        } elseif ([decimal]$payload.confidence -lt 0 -or [decimal]$payload.confidence -gt 1) {
            $errors.Add('confidence must be between 0 and 1.')
        }
    }

    return $errors
}

$validFiles = Get-ChildItem -LiteralPath (Join-Path $RootPath 'fixtures/extraction/valid') -File
$invalidFiles = Get-ChildItem -LiteralPath (Join-Path $RootPath 'fixtures/extraction/invalid') -File
$failures = [System.Collections.Generic.List[string]]::new()

foreach ($file in $validFiles) {
    $errors = Test-ExtractionPayload -Path $file.FullName
    if ($errors.Count -gt 0) {
        $failures.Add("Expected valid extraction fixture failed: $($file.Name) :: $($errors -join '; ')")
    }
}

foreach ($file in $invalidFiles) {
    $errors = Test-ExtractionPayload -Path $file.FullName
    if ($errors.Count -eq 0) {
        $failures.Add("Expected invalid extraction fixture passed: $($file.Name)")
    }
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Host "Extraction contract fixtures passed. Valid: $($validFiles.Count); Invalid: $($invalidFiles.Count)"

