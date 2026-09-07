# Smart Expense Tracking Requirements

Last updated: 2026-09-06

## Purpose

Build a private-first expense capture workflow where an Android phone extracts order data from receipt photos and hands that data to a local Windows automation agent, which appends the expense to an existing Excel workbook.

## Feasibility Summary

Overall feasibility: feasible for an MVP, with a few integration details to resolve before implementation.

High-confidence parts:
- Android can capture a receipt photo or let the user select one from local/cloud-backed media.
- Android can upload a normalized receipt JPEG followed by a structured handoff file to OneDrive through Microsoft Graph.
- A Windows agent can poll the synced OneDrive folder every 5 minutes.
- The Windows side can update the local synced workbook using a PowerShell action, with Excel automation or Graph-based workbook APIs as candidate implementation mechanisms.

Main risks:
- Google AI Edge APIs and LiteRT-LM provide an Android integration path, but the exact receipt-image extraction approach still needs a spike because LiteRT-LM model capability, image input support, prompt format, and device performance must be verified.
- OpenAI-compatible APIs are feasible as a user-enabled extraction provider, but they add network dependency, API key handling, cost/rate-limit behavior, and privacy disclosure requirements.
- Uploading a receipt image and its JSON as one recoverable export requires deterministic naming, idempotent retries, and image-first publication so Hermes never processes a JSON whose image is missing.

## System Context

The system has three main components:

1. Android app
   - Captures or imports receipt images.
   - Runs extraction through the selected model provider.
   - Provides Settings for choosing the extraction provider and model.
   - Lets the user review/edit extracted fields.
   - Uploads a normalized receipt JPEG and its handoff file into OneDrive using Microsoft Graph.

2. OneDrive expense folders
   - Receives one JSON handoff file and one normalized receipt JPEG per confirmed expense.
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

REQ-A-002A: The app shall provide a default-enabled option to reduce selected input images larger than 200 KB (204,800 bytes), and when enabled shall resize and re-encode them to strictly below 200 KB before extraction. The user may disable this option to use the original image.

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

REQ-A-011: When exporting a confirmed expense, the app shall generate and upload a normalized JPEG copy of the processed receipt image, strictly below 200 KB (204,800 bytes), regardless of whether extraction-time resizing was disabled or unnecessary.

REQ-A-012: The app shall upload receipt images under the OneDrive-root-relative folder `Documents/2_Others/Expenses_finance/receipt_images/YYYY-MM`, where `YYYY-MM` is derived from the export timestamp in the Android device's local timezone.

REQ-A-013: Receipt image file names shall use `YYYYMMDD_HHmmss_receipt_<shortExpenseId>.jpg`, where the timestamp is the same export timestamp used for the JSON handoff name and `<shortExpenseId>` follows the handoff naming rule.

REQ-A-014: The handoff JSON shall include the required OneDrive-root-relative field `receiptImageRelativePath`, using forward slashes and identifying the uploaded JPEG for that expense.

REQ-A-015: The app shall complete the receipt image upload before publishing the final `.json` handoff file. If image upload fails, the final JSON shall not be published and the local export shall remain retryable with an actionable status.

REQ-A-016: Retrying an interrupted export shall reuse the expense ID, export timestamp, JSON name, and image path so retries are idempotent and do not create duplicate receipt images or expense handoffs.

REQ-A-017: Before image upload, the app shall idempotently resolve or create the required `receipt_images/YYYY-MM` folder hierarchy. A folder-creation failure shall preserve the local export for retry and shall not publish the final JSON.

### Model Provider and Extraction

REQ-M-001: The extraction implementation shall default to an on-device provider.

REQ-M-002: The extraction prompt/output contract shall require structured data, not free-form prose.

REQ-M-003: The app shall validate model output before allowing export.

REQ-M-004: If model extraction fails or confidence is low, the app shall allow manual entry.

REQ-M-005: The preferred local model integration shall use Google AI Edge APIs or Google AI Edge LiteRT-LM directly in the Android app.

REQ-M-006: The implementation shall use AI Edge Gallery only as a sample/reference app unless a stable documented app-to-app integration is confirmed.

REQ-M-007: The implementation spike shall verify whether the selected local model path supports the receipt-image input workflow directly, or whether OCR/preprocessing is required before LLM extraction.

REQ-M-008: The Android app shall support user-configured OpenAI-compatible API providers in addition to local models.

REQ-M-009: OpenAI-compatible API support shall be disabled by default and shall require explicit user configuration before any receipt image, OCR text, or extracted data is sent to a remote model provider.

REQ-M-010: The Android app shall include a Settings section with a Model Selector that allows the user to choose:
- provider type: local on-device or OpenAI-compatible API
- provider display name
- base URL for OpenAI-compatible providers
- model id/name
- whether the selected provider accepts image input directly or requires OCR text input

REQ-M-011: The app shall store OpenAI-compatible API credentials using Android secure credential storage, not plain preferences or logs.

REQ-M-012: The app shall allow the user to test a configured OpenAI-compatible provider before selecting it for receipt extraction.

REQ-M-013: The extraction pipeline shall use the same normalized structured output contract for local and OpenAI-compatible providers.

REQ-M-014: The app shall surface remote-provider failures, rate limits, authentication failures, and network unavailability with a clear recovery path and manual-entry fallback.

REQ-M-015: The app shall allow the user to create, view, edit, test, save, select, and delete multiple named OpenAI-compatible provider profiles.

REQ-M-016: Each saved profile shall have a stable unique ID and shall persist its display name, base URL, model ID, input mode, and structured-output format across app restarts.

REQ-M-017: The app shall persist the selected profile ID and visibly identify the provider that will be used for the next extraction.

REQ-M-018: Each remote profile shall use a distinct secure credential alias; API-key values shall not be stored in the profile database, plain preferences, logs, or UI state intended for persistence.

REQ-M-019: Deleting the selected profile, disabling remote providers, or encountering a missing selected profile shall result in a deterministic local-provider fallback without sending receipt data remotely.

REQ-M-020: Migrating from the single-profile settings format shall preserve a valid existing configuration and credential without creating duplicate profiles on subsequent starts.

REQ-M-021: The app shall display the bundled LM Studio Gemma profile alongside user-created profiles on clean installs and upgrades, without automatically selecting it, overwriting edits, or creating an equivalent duplicate profile.

REQ-M-022: When one input image or OCR text contains multiple receipt documents that clearly represent the same transaction, the extraction prompt shall produce one expense and set `totalAmount` to the finalized amount charged or payable, including tax and tip. The extraction shall not add together totals from those documents. It shall use `low_confidence` when the documents may represent different transactions or the finalized amount cannot be identified reliably.

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

REQ-H-006: The next handoff schema version shall require `receiptImageRelativePath`, containing the OneDrive-root-relative path of the successfully uploaded normalized JPEG.

REQ-H-007: A final handoff JSON shall be a commit marker for the paired export: its referenced receipt image must already exist before the final `.json` becomes visible to Hermes.

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

REQ-O-004: Amazon Photos may remain the full-resolution backup source, while the required OneDrive receipt copy is a normalized JPEG strictly below 200 KB used for the expense workflow.

## Non-Functional Requirements

NFR-001: The default workflow shall avoid sending receipt images or extracted data to third-party cloud AI services. Remote AI providers may be used only after explicit user opt-in through Settings.

NFR-002: Sensitive data shall not be logged in full unless debug logging is explicitly enabled.

NFR-003: The Android app shall work offline for capture, extraction, review, and local queueing.

NFR-003A: If a remote OpenAI-compatible provider is selected, capture, review, correction, export queueing, and manual entry shall still work offline; only remote extraction may require network access.

NFR-004: OneDrive sync delay shall be tolerated; a 5-minute polling interval is acceptable for the MVP.

NFR-005: The Windows agent shall be restart-safe and idempotent.

NFR-006: The handoff schema shall be versioned for future migrations.

NFR-007: User-visible failures shall provide a clear recovery path.

NFR-008: Paired receipt-image and JSON export shall be restart-safe and idempotent; interruption at any stage shall not expose a processable JSON with a missing image or create duplicate files on retry.

## Proposed Handoff Schema

```json
{
  "schemaVersion": 2,
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
  "receiptImageRelativePath": "Documents/2_Others/Expenses_finance/receipt_images/2026-06/20260626_153000_receipt_018f6b3e.jpg",
  "receiptPhotoLink": null,
  "notes": null
}
```

## Key Design Decisions Needed

DEC-001: Confirm the exact Android local extraction path.

Current direction: use Google AI Edge APIs or LiteRT-LM directly in the app. AI Edge Gallery is a reference implementation, not a required runtime dependency.

DEC-001A: Confirm the Android model-provider strategy.

Current direction: support both local on-device models and OpenAI-compatible API providers behind one extraction interface. Local on-device extraction remains the default privacy-preserving path. OpenAI-compatible API providers are user-configured optional providers selected from Settings.

Evaluation:
- Benefits: faster MVP path if local multimodal performance is weak, broader model choice, easier model upgrades, and a useful fallback for devices that cannot run the selected local model reliably.
- Tradeoffs: receipt data may leave the device, extraction can fail without network access, users may incur API costs, API keys must be protected, and provider compatibility must be tested because "OpenAI-compatible" APIs can differ in vision, JSON output, authentication, and error behavior.
- Recommendation: proceed with OpenAI-compatible API support as an optional provider, not as the default. Build a provider abstraction and Model Selector early so local and remote providers share the same prompt, parser, validation, review, and handoff contracts.
- Implementation sequencing: test the OpenAI-compatible API option first, then validate the local LiteRT-LM model path later against the same extraction contract.

Needed details:
- initial supported remote provider profiles, if any
- whether images may be sent to remote providers or OCR text should be sent by default
- secure API key storage and redaction behavior
- provider test request contract
- cost/rate-limit messaging in Settings

DEC-002: Confirm Microsoft Graph details for OneDrive handoff.

Current direction: use Microsoft Graph. JSON handoffs use `Documents/2_Others/Expenses_finance/logs`; normalized receipt JPEGs use `Documents/2_Others/Expenses_finance/receipt_images/YYYY-MM`.

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

DEC-006: Confirm receipt image and photo-link behavior.

Decision: upload a normalized JPEG below 200 KB to OneDrive for every exported expense before publishing its final JSON. Amazon Photos may remain the full-resolution backup, and external `receiptPhotoLink` values remain optional.

## Acceptance Criteria

AC-001: A user can capture/import a receipt, review extracted fields produced by the selected model provider or manual entry, and export a handoff file.

AC-002: The handoff file validates against the agreed schema.

AC-003: The Windows agent detects the handoff file within one polling cycle after OneDrive sync completes.

AC-004: The Excel workbook receives exactly one row for the expense.

AC-005: Reprocessing the same handoff file does not create a duplicate row.

AC-006: Invalid handoff files are preserved for review and do not corrupt the workbook.

AC-007: The workflow still succeeds when the receipt photo link is absent.

AC-008: A user can open Settings, select the local provider or a configured OpenAI-compatible provider, choose a model id, and see which provider will be used for the next extraction.

AC-009: With no remote provider configured, the app does not send receipt data to a third-party AI provider.

AC-010: A user can create at least two named OpenAI-compatible profiles, restart the app, select either profile, and see that profile identified for the next extraction.

AC-011: Saving, editing, clearing, and deleting one profile affects only that profile's secure credential alias; no API-key value is present in Room or plain preferences.

AC-012: Disabling remote access, deleting the selected profile, or resolving a missing selection chooses the local provider without making a remote receipt request; a legacy single-profile configuration migrates at most once.

AC-013: After a clean install or upgrade from the first multi-profile release, the saved-profile list contains the bundled LM Studio Gemma configuration alongside profiles such as OpenRouter, unless an equivalent or previously migrated LM Studio profile already exists.

AC-014: Exporting a confirmed expense uploads a normalized JPEG below 200 KB to `Documents/2_Others/Expenses_finance/receipt_images/YYYY-MM/YYYYMMDD_HHmmss_receipt_<shortExpenseId>.jpg` and places that exact relative path in the handoff JSON.

AC-015: The receipt image is completely uploaded before the final JSON appears in the `logs` folder; a failed image upload leaves no processable final JSON and presents a retry path.

AC-016: Retrying the same expense export reuses its image and JSON paths and does not create duplicate OneDrive files.

AC-017: If the monthly receipt-image folder does not exist, the app creates it safely before upload; folder-creation failure leaves the export retryable and exposes no final JSON.

AC-018: Given an itemized receipt without tax or tip and a finalized receipt for the same transaction with tax and tip, extraction returns one expense whose `totalAmount` is the finalized amount rather than the subtotal or the sum of both receipt totals. Ambiguous receipt pairs are flagged `low_confidence` for user review.

## Verified External References

- Android Photo Picker: https://developer.android.com/training/data-storage/shared/photo-picker
- Android Storage Access Framework: https://developer.android.com/training/data-storage/shared/documents-files
- Google AI Edge Gallery repository: https://github.com/google-ai-edge/gallery
- Google AI Edge API reference: https://developers.google.com/edge/api
- Google AI Edge LiteRT-LM: https://developers.google.com/edge/litert-lm
- Google AI Edge LiteRT-LM for Android: https://developers.google.com/edge/litert-lm/android
- Microsoft Graph Excel table row API: https://learn.microsoft.com/en-us/graph/api/table-post-rows
