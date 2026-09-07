# Handoff Contract

Last updated: 2026-09-06

## Purpose

This contract defines the shared JSON payload that the Android app uploads to OneDrive and the Windows Hermes/PowerShell side consumes. It is the Phase 1 Workstream 0 baseline for Android extraction, Microsoft Graph upload, schema validation, Excel mapping, and duplicate detection spikes.

## File Naming

Final handoff files use this pattern:

```text
expense_yyyyMMdd_HHmmss_<shortExpenseId>.json
```

Example:

```text
expense_20260626_153000_018f6b3e.json
```

Rules:
- `yyyyMMdd_HHmmss` is based on the export timestamp.
- `<shortExpenseId>` is the first 8 lowercase hexadecimal characters from `expenseId` after removing separators.
- Final files must use `.json`.
- Temporary or incomplete files must not use `.json`; use a temporary extension such as `.uploading`.
- The Windows agent must process only final `.json` files.

Receipt image files use this OneDrive-root-relative pattern:

```text
Documents/2_Others/Expenses_finance/receipt_images/YYYY-MM/YYYYMMDD_HHmmss_receipt_<shortExpenseId>.jpg
```

Rules:
- The image is a normalized JPEG strictly below 200 KB (204,800 bytes).
- `YYYY-MM` and `YYYYMMDD_HHmmss` use the same export timestamp as the JSON handoff, expressed in the Android device's local timezone.
- `<shortExpenseId>` is identical to the suffix used by the paired JSON name.
- A retry for the same expense reuses the same image path and JSON name.

## Version 1 Payload

Version 1 remains documented for the existing fixtures and implementation baseline. New Android receipt exports use Version 2.

Required fields:

| Field | Type | Rule | Requirements |
| --- | --- | --- | --- |
| `schemaVersion` | integer | Must be `1`. | REQ-H-002, NFR-006 |
| `expenseId` | string | Globally unique UUID. | REQ-A-007, REQ-H-002 |
| `createdAt` | string | ISO-8601 timestamp with timezone offset or `Z`. | REQ-H-002 |
| `sourceDeviceId` | string | Non-empty stable device identifier chosen by the app. | REQ-H-002 |
| `receiptDate` | string | ISO date in `yyyy-MM-dd` format. | REQ-A-003, REQ-H-002 |
| `merchantName` | string | Non-empty merchant, shop, service, or payee name. | REQ-A-003, REQ-H-002 |
| `totalAmount` | number | Greater than or equal to `0`. | REQ-A-003, REQ-H-002 |
| `currency` | string | Three-letter ISO-style uppercase currency code. Default candidate is `CAD`, pending DEC-005. | REQ-H-002 |
| `extractionStatus` | string | One of `confirmed`, `manual`, `low_confidence`, or `failed`. Exportable files should normally be `confirmed` or `manual`. | REQ-A-004, REQ-A-005, REQ-H-002 |

Optional fields:

| Field | Type | Rule | Requirements |
| --- | --- | --- | --- |
| `category` | string or null | Optional user/category label. | REQ-H-003 |
| `paymentMethod` | string or null | Optional payment method. | REQ-H-003 |
| `taxAmount` | number or null | Must be greater than or equal to `0` when present. | REQ-H-003 |
| `tipAmount` | number or null | Must be greater than or equal to `0` when present. | REQ-H-003 |
| `merchantLocation` | string or null | Optional city/location text. | REQ-H-003 |
| `merchantAddress` | string or null | Optional address text. | REQ-H-003 |
| `originalImageFileName` | string or null | Optional local original file name. | REQ-H-003 |
| `receiptImageUri` | string or null | Optional local Android URI; not the OneDrive image path. | REQ-H-003 |
| `receiptPhotoLink` | string or null | Optional external photo link when reliably available. | REQ-H-003, REQ-O-001 |
| `notes` | string or null | Optional user notes. | REQ-H-003 |
| `rawModelOutput` | string or object or null | Optional debug/extraction trace; avoid sensitive full logging unless debug is enabled. | REQ-H-003, NFR-002 |

## Version 2 Extension

Version 2 is implemented for Android receipt export. It retains the Version 1 fields and adds this required field:

| Field | Type | Rule | Requirements |
| --- | --- | --- | --- |
| `receiptImageRelativePath` | string | OneDrive-root-relative path using forward slashes. It must match `Documents/2_Others/Expenses_finance/receipt_images/YYYY-MM/YYYYMMDD_HHmmss_receipt_<shortExpenseId>.jpg`. | REQ-A-014, REQ-H-006 |

The Version 2 JSON must not be published under its final `.json` name until the referenced image upload is complete.

## Canonical Field Names

Use the JSON field names above unchanged in Kotlin DTOs, PowerShell objects, logs, and tests. Platform-specific models may use idiomatic type names, but serialized JSON must remain camelCase.

## Validation Rules

A handoff file is valid when:

- It is parseable JSON.
- It has `schemaVersion` equal to `1` for legacy fixtures or `2` for paired receipt exports.
- All required fields are present.
- Required string fields are not blank.
- `receiptDate` is a valid calendar date in `yyyy-MM-dd` format.
- `createdAt` is an ISO-8601 timestamp with timezone information.
- `totalAmount`, `taxAmount`, and `tipAmount` are numeric and non-negative when present.
- `currency` matches `^[A-Z]{3}$`.
- `extractionStatus` is one of the allowed values.
- For Version 2, `receiptImageRelativePath` is present and matches the deterministic normalized-JPEG path for the same export timestamp and short expense ID.

## Complete-File Handling

The implemented paired-export convention is:

1. Android creates a stable expense ID and one export timestamp, then derives both final paths once.
2. Android resolves or idempotently creates the `receipt_images/YYYY-MM` folder hierarchy.
3. Android uploads the normalized receipt JPEG to its deterministic final image path.
4. Only after the image succeeds, Android uploads the JSON with a temporary name ending in `.uploading`.
5. Android renames or copies the complete JSON to its final `.json` name.
6. Hermes/PowerShell ignores anything that does not end in `.json` and treats the final JSON as the commit marker for the pair.
7. Hermes/PowerShell validates the Version 2 schema and `receiptImageRelativePath` before any workbook update.
8. A failed folder, image, or JSON operation remains locally retryable and reuses the same paths; it must not create a second expense or receipt image.

If Microsoft Graph upload behavior proves that only complete final files become visible, the temporary-name step can be removed by a documented Phase 1 decision.

## Fixture Index

Valid fixtures:
- `fixtures/handoff/valid/expense_20260626_153000_018f6b3e.json`
- `fixtures/handoff/valid/expense_20260627_081500_11111111.json`
- `fixtures/handoff/valid/expense_20260623_164000_66666666.json`

Invalid fixtures:
- `fixtures/handoff/invalid/missing-merchant-name.json`
- `fixtures/handoff/invalid/invalid-receipt-date.json`
- `fixtures/handoff/invalid/negative-total-amount.json`
- `fixtures/handoff/invalid/unsupported-schema-version.json`

OCR text fixtures for extraction spike preparation:
- `fixtures/receipt-text/example-shop-20260626.txt`
- `fixtures/receipt-text/photo-1-lotto-max-20260623.txt`

Receipt image fixtures:
- A real receipt example is available from the user-provided `Photo 1.jpg` attachment and is represented in the OCR-style text fixture plus derived handoff JSON fixture above.
- Keep original receipt images out of Git if they contain sensitive information.

## Requirements Check

- REQ-H-001: JSON handoff format is defined.
- REQ-H-002: Required fields are enumerated.
- REQ-H-003: Optional fields are enumerated.
- REQ-H-004: Sortable unique file name pattern is defined.
- REQ-H-005: Complete-file handling is defined.
- ✅ Done: REQ-H-006: Version 2 receipt-image path correlation is implemented.
- ✅ Done: REQ-H-007: The final JSON is implemented as the paired-export commit marker.
- ✅ Done: REQ-A-011 through REQ-A-017: Normalization, path naming, monthly-folder creation, publication order, failure behavior, and idempotent retry are implemented and unit-tested.
- REQ-M-002: Structured output contract is defined.
- REQ-M-003: Validation rules are defined.
- REQ-X-011: Deterministic source fields for future Excel mapping are defined.
- NFR-006: Schema versioning starts with `schemaVersion: 1`.
