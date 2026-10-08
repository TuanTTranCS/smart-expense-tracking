$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'SmartExpense.Excel.psm1') -Force -DisableNameChecking
$repoRoot = Split-Path -Parent $PSScriptRoot
$fixture = Join-Path $repoRoot 'fixtures/handoff/valid/expense_20261005_120000_99999999.json'
$fileName = Split-Path -Leaf $fixture
$passed = 0
function Read-TypedFixture { return Get-Content -Raw -LiteralPath $fixture | ConvertFrom-Json -DateKind String }
function Assert-Typed([bool]$Condition,[string]$Message) { if(-not $Condition){throw $Message};$script:passed++ }
$payload=Read-TypedFixture
$valid=Test-HermesHandoff $payload $fileName $repoRoot
Assert-Typed $valid.Valid 'Typed fixture must validate without image files.'
Assert-Typed (-not $valid.HasReceiptImage -and $null -eq $valid.ImagePath) 'Typed validation must not produce an image path.'
Assert-Typed ($valid.Amount -eq [decimal]12.34 -and $valid.Description -eq 'Typed Merchant Oct 05') 'Typed mapping differs from the image mapping.'
Assert-Typed (-not (Test-HermesVersion2Handoff $payload $fileName $repoRoot).Valid) 'Strict V2 entry point accepted V3.'
$cases = @(
    {param($p) $p.hasReceiptImage=$true},
    {param($p) $p.hasReceiptImage='false'},
    {param($p) $p.inputSource='image'},
    {param($p) $p.receiptImageRelativePath=''},
    {param($p) $p.PSObject.Properties.Remove('receiptImageRelativePath')},
    {param($p) $p.extractionStatus='confirmed'},
    {param($p) $p.schemaVersion='3'},
    {param($p) $p.schemaVersion=4},
    {param($p) $p.totalAmount='12.34'},
    {param($p) $p.totalAmount=-1},
    {param($p) $p.receiptDate='2026-02-29'},
    {param($p) $p.sourceDeviceName=''},
    {param($p) $p | Add-Member -NotePropertyName receiptImageUri -NotePropertyValue 'content://image'},
    {param($p) $p | Add-Member -NotePropertyName originalImageFileName -NotePropertyValue ''},
    {param($p) $p | Add-Member -NotePropertyName receiptPhotoLink -NotePropertyValue 'https://example.test/photo'},
    {param($p) $p.notes=123}
)
foreach($case in $cases){$payload=Read-TypedFixture;& $case $payload;Assert-Typed (-not (Test-HermesHandoff $payload $fileName $repoRoot).Valid) 'Malformed V3 was accepted.'}
$payload=Read-TypedFixture;$payload.currency='USD'
Assert-Typed (Test-HermesHandoff $payload $fileName $repoRoot).Valid 'Non-CAD typed records must reach the existing quarantine policy.'
$payload=Read-TypedFixture;$payload.schemaVersion=2
Assert-Typed (-not (Test-HermesHandoff $payload $fileName $repoRoot).Valid) 'Version 2 without image was accepted.'
Write-Host "Typed handoff unit tests passed: $passed assertions."
