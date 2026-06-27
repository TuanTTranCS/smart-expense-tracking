# Smart Expense Tracking Requirements

Last updated: 2026-06-26

## Purpose

Build a private-first expense capture workflow where an Android phone extracts order data from receipt photos and hands that data to a local Windows automation agent, which appends the expense to an existing Excel workbook.

## Feasibility Summary

Overall feasibility: feasible for an MVP, with a few integration details to resolve before implementation.

High-confidence parts:
- Android can capture a receipt photo or let the user select one from local/cloud-backed media.
- Android can upload a structured handoff file to OneDrive through Microsoft Graph.
- A Windows agent can poll the synced OneDrive folder every 5 minutes.
- The Windows side can update the local synced workbook using a PowerShell action, with Excel automation or Graph-based workbook APIs as candidate implementation mechanisms.

Main risks:
- Google AI Edge APIs and LiteRT-LM provide an Android integration path, but the exact receipt-image extraction approach still needs a spike because LiteRT-LM model capability, image input support, prompt format, and device performance must be verified.
- Amazon Photos will remain the phone photo backup location. The MVP shall not duplicate receipt images to OneDrive. Programmatic Amazon Photos links are optional and should not block expense entry.

## System Context

The system has three main components:

1. Android app
   - Captures or imports receipt images.
   - Runs local extraction.
   - Lets the user review/edit extracted fields.
   - Uploads a handoff file into OneDrive using Microsoft Graph.

2. OneDrive handoff folder
   - Receives one file per expense.
   - Syncs from Android/cloud to the Windows computer.
   - Acts as the queue between phone and Windows automation.

3. Windows Hermes agent
   - Polls the local OneDrive handoff folder every 5 minutes.
   - Processes new handoff files idempotently.
   - Runs a PowerShell action to update the configured Excel workbook.
   - Archives, marks, or moves processed handoff files.

## MVP Functional Requirements

### Android App

REQ-A-001: The app shall allow the user to take a receipt/order picture.

REQ-A-002: The app shall allow the user to load an existing photo from the phone using Android's photo picker or equivalent system picker.

REQ-A-003: The app shall extract at least these fields from the selected receipt image:
- receipt date
- shop or service name
- total amount

REQ-A-004: The app shall show the extracted fields to the user for confirmation before export.

REQ-A-005: The app shall allow the user to correct any extracted field before export.

REQ-A-006: The app shall write one structured handoff file per confirmed expense.

REQ-A-007: The app shall include a globally unique expense id in each handoff file.

REQ-A-008: The app shall write handoff files to a user-configured OneDrive folder.

REQ-A-009: The app shall preserve a local record of exported expenses and their export status.

REQ-A-010: The app shall authenticate with Microsoft Graph to upload handoff files to OneDrive.

REQ-A-011: The app shall not upload receipt images to OneDrive for the MVP.

### Local Model Extraction

REQ-M-001: The extraction implementation shall run on-device unless the user explicitly enables a cloud fallback.

REQ-M-002: The extraction prompt/output contract shall require structured data, not free-form prose.

REQ-M-003: The app shall validate model output before allowing export.

REQ-M-004: If model extraction fails or confidence is low, the app shall allow manual entry.

REQ-M-005: The preferred local model integration shall use Google AI Edge APIs or Google AI Edge LiteRT-LM directly in the Android app.

REQ-M-006: The implementation shall use AI Edge Gallery only as a sample/reference app unless a stable documented app-to-app integration is confirmed.

REQ-M-007: The implementation spike shall verify whether the selected local model path supports the receipt-image input workflow directly, or whether OCR/preprocessing is required before LLM extraction.

### Handoff File

REQ-H-001: The handoff format shall be JSON, even if the file extension remains `.txt` for operational convenience.

REQ-H-002: The handoff file shall include these required fields:
- schemaVersion
- expenseId
- createdAt
- sourceDeviceId
- receiptDate
- merchantName
- totalAmount
- currency
- extractionStatus

REQ-H-003: The handoff file may include these optional fields:
- category
- paymentMethod
- taxAmount
- tipAmount
- merchantLocation
- merchantAddress
- originalImageFileName
- receiptImageUri
- receiptPhotoLink
- notes
- rawModelOutput

REQ-H-004: File names shall be unique and sortable, using this pattern:

```text
expense_yyyyMMdd_HHmmss_<shortExpenseId>.json
```

REQ-H-005: The Android app shall avoid partial file processing by either writing atomically or using a temporary extension until the file is complete.

### Windows Hermes Agent

REQ-W-001: The Hermes agent shall poll the configured OneDrive handoff folder every 5 minutes.

REQ-W-002: The Hermes agent shall process only complete handoff files.

REQ-W-003: The Hermes agent shall skip already processed expense ids.

REQ-W-004: The Hermes agent shall validate the handoff file schema before updating Excel.

REQ-W-005: The Hermes agent shall log success and failure for each handoff file.

REQ-W-006: The Hermes agent shall move successful files to a processed/archive folder or mark them as processed.

REQ-W-007: The Hermes agent shall move invalid or failed files to a review/error folder with a reason.

REQ-W-008: The Hermes agent shall run the workbook update action through PowerShell.

REQ-W-009: The PowerShell action shall maintain processing state for idempotency, either in a state file, the workbook, or both.

### Excel Update

REQ-X-001: The system shall update an existing Excel workbook.

REQ-X-002: The target workbook path shall be:

```text
D:\OneDrive\Documents\2_Others\Expenses_finance\Canada plan.xlsx
```

REQ-X-003: The target worksheet shall be selected by receipt month using `YYYY-MM` format.

REQ-X-004: If the target monthly worksheet does not exist, the agent shall follow a defined recovery behavior before writing. The preferred recovery behavior is to create or copy the monthly sheet from an approved template, but this requires confirmation.

REQ-X-005: The Windows side shall add one expense row per accepted handoff file.

REQ-X-006: The agent shall insert the new expense row after row 12 in the selected monthly worksheet, while preserving the workbook's existing formulas, formatting, and layout.

REQ-X-007: The agent shall update columns F and G for the inserted row.

REQ-X-008: The exact mapping for columns F and G shall be confirmed before implementation.

REQ-X-009: Before inserting a row, the agent shall check for similar existing records using at least:
- receipt date
- merchant/shop/service name
- total amount

REQ-X-010: When a similar record is found, the agent shall not silently add a duplicate. It shall either skip, quarantine for review, or apply a user-approved duplicate rule.

REQ-X-011: The implementation shall define deterministic mapping from handoff fields to workbook columns before coding.

REQ-X-012: The update mechanism shall prevent duplicate rows for the same expense id.

REQ-X-013: If Excel is open or locked, the agent shall retry or fail gracefully without losing the handoff file.

## Optional Requirements

REQ-O-001: The app may include a receipt photo link if the user can obtain one from Amazon Photos or another existing backup flow.

REQ-O-002: The Excel row may include a receipt photo link.

REQ-O-003: Amazon Photos linking is optional and shall be implemented only if a reliable, user-authorized way to obtain stable share links is confirmed.

REQ-O-004: The MVP shall not duplicate receipt images into OneDrive.

## Non-Functional Requirements

NFR-001: The default workflow shall avoid sending receipt images or extracted data to third-party cloud AI services.

NFR-002: Sensitive data shall not be logged in full unless debug logging is explicitly enabled.

NFR-003: The Android app shall work offline for capture, extraction, review, and local queueing.

NFR-004: OneDrive sync delay shall be tolerated; a 5-minute polling interval is acceptable for the MVP.

NFR-005: The Windows agent shall be restart-safe and idempotent.

NFR-006: The handoff schema shall be versioned for future migrations.

NFR-007: User-visible failures shall provide a clear recovery path.

## Proposed Handoff Schema

```json
{
  "schemaVersion": 1,
  "expenseId": "018f6b3e-9c6e-7c41-8e1a-4f7c3a8a9b11",
  "createdAt": "2026-06-26T15:30:00-07:00",
  "sourceDeviceId": "android-phone-1",
  "receiptDate": "2026-06-26",
  "merchantName": "Example Shop",
  "totalAmount": 42.35,
  "currency": "CAD",
  "extractionStatus": "confirmed",
  "category": null,
  "paymentMethod": null,
  "taxAmount": null,
  "tipAmount": null,
  "merchantLocation": null,
  "merchantAddress": null,
  "originalImageFileName": "IMG_20260626_153000.jpg",
  "receiptImageUri": null,
  "receiptPhotoLink": null,
  "notes": null
}
```

## Key Design Decisions Needed

DEC-001: Confirm the exact Android local extraction path.

Current direction: use Google AI Edge APIs or LiteRT-LM directly in the app. AI Edge Gallery is a reference implementation, not a required runtime dependency.

DEC-002: Confirm Microsoft Graph details for OneDrive handoff.

Current direction: use Microsoft Graph.

Needed details:
- Microsoft account type: personal, work, or school
- app registration approach
- target OneDrive folder path
- token storage and refresh behavior

DEC-003: Confirm PowerShell execution details for Hermes.

Needed details:
- Can Hermes keep local state?
- Can Hermes access the synced OneDrive path?
- Can Hermes use Microsoft Graph credentials?

DEC-004: Confirm the Excel workbook mapping.

Known details:
- workbook path: `D:\OneDrive\Documents\2_Others\Expenses_finance\Canada plan.xlsx`
- monthly worksheet name pattern: `YYYY-MM`
- insertion point: after row 12
- columns to update: F and G

Still needed:
- what values go into columns F and G
- whether date/merchant/amount are stored elsewhere on the row
- exact duplicate/similar-record action: skip, ask for review, or add with a warning
- whether monthly sheets are created manually or by the agent
- whether the workbook contains protected sheets, formulas, tables, or pivot tables affected by row insertion

DEC-005: Confirm currency and locale rules.

Needed details:
- default currency
- date format
- decimal separator expectations
- multi-currency handling

DEC-006: Confirm receipt photo link behavior.

Current direction: rely on Amazon Photos backup and do not duplicate images in OneDrive. Receipt photo links remain optional.

## Acceptance Criteria

AC-001: A user can capture/import a receipt, review extracted fields, and export a handoff file.

AC-002: The handoff file validates against the agreed schema.

AC-003: The Windows agent detects the handoff file within one polling cycle after OneDrive sync completes.

AC-004: The Excel workbook receives exactly one row for the expense.

AC-005: Reprocessing the same handoff file does not create a duplicate row.

AC-006: Invalid handoff files are preserved for review and do not corrupt the workbook.

AC-007: The workflow still succeeds when the receipt photo link is absent.

## Verified External References

- Android Photo Picker: https://developer.android.com/training/data-storage/shared/photo-picker
- Android Storage Access Framework: https://developer.android.com/training/data-storage/shared/documents-files
- Google AI Edge Gallery repository: https://github.com/google-ai-edge/gallery
- Google AI Edge API reference: https://developers.google.com/edge/api
- Google AI Edge LiteRT-LM: https://developers.google.com/edge/litert-lm
- Google AI Edge LiteRT-LM for Android: https://developers.google.com/edge/litert-lm/android
- Microsoft Graph Excel table row API: https://learn.microsoft.com/en-us/graph/api/table-post-rows
