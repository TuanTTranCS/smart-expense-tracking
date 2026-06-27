# Gap-Closing Plan Details

Last updated: 2026-06-26

## Gap 1: Local Model Extraction Path

Status: Pending

Problem:
The Android app does not need to depend on AI Edge Gallery specifically. Google AI Edge APIs and LiteRT-LM are acceptable, but the exact receipt-image extraction path must be verified.

Resolution path:
- Review Google AI Edge API and LiteRT-LM Android documentation.
- Use AI Edge Gallery as a reference app/sample where useful.
- Prototype Google AI Edge API or LiteRT-LM integration in Kotlin.
- Verify whether receipt images can be passed directly to the selected model path.
- If direct image input is not practical, add an OCR/preprocessing step before LLM extraction.
- Document the selected model format, device requirements, and fallback behavior.

Done when:
- Integration path is confirmed.
- A minimal extraction spike returns structured receipt fields from sample receipt images.
- The spike documents whether OCR is required.
- Unit tests cover output parsing and validation.

## Gap 2: Microsoft Graph OneDrive Handoff

Status: Pending

Problem:
The Android app will use Microsoft Graph to upload handoff files to OneDrive. The target folder and authentication flow are not yet defined.

Resolution path:
- Confirm Microsoft account type: personal, work, or school.
- Define the app registration and permissions.
- Confirm the target OneDrive handoff folder path.
- Implement Graph upload with token refresh.
- Write files with a complete-file convention so the Windows agent never processes partial content.

Done when:
- A handoff file can be uploaded repeatedly to the target OneDrive folder.
- Partial writes cannot be processed by the Windows agent.
- Unit tests cover file naming and schema generation.

## Gap 3: Hermes PowerShell Action

Status: Pending

Problem:
Hermes will run the Windows automation through PowerShell. The exact script contract, state handling, and credentials need to be defined.

Resolution path:
- Define PowerShell script input parameters.
- Confirm how Hermes stores credentials and state.
- Confirm whether Hermes has access to the local OneDrive sync path.
- Confirm polling configuration and logging behavior.
- Decide whether the PowerShell script uses Excel COM automation, ImportExcel/ClosedXML-style manipulation, or Microsoft Graph workbook APIs.

Done when:
- A minimal Hermes PowerShell action can read a sample handoff file.
- Processing state survives restart.
- Unit tests cover idempotency logic outside Hermes-specific scheduling.

## Gap 4: Excel Workbook Mapping

Status: Pending

Problem:
The workbook path, monthly sheet pattern, row insertion point, and duplicate matching criteria are partially defined. The column meanings and duplicate action still need confirmation.

Resolution path:
- Use workbook path `D:\OneDrive\Documents\2_Others\Expenses_finance\Canada plan.xlsx`.
- Select monthly worksheet by receipt date using `YYYY-MM`.
- Insert a new row after row 12.
- Confirm what values belong in columns F and G.
- Confirm where receipt date, merchant/shop/service, location, and amount are stored or compared.
- Define similar-record behavior for same date, amount, shop/service, and location.
- Verify the update mechanism against a copy of the real workbook.

Done when:
- A sample handoff file inserts exactly one row after row 12 in a copied workbook.
- Columns F and G are populated according to the confirmed mapping.
- Duplicate expense ids are skipped.
- Similar records are detected and handled using the chosen rule.
- Unit tests cover mapping and duplicate detection.

## Gap 5: Receipt Photo Link

Status: Pending

Problem:
Receipt images are already backed up through Amazon Photos and should not be duplicated to OneDrive. Stable programmatic receipt links may not be available.

Resolution path:
- Verify whether Amazon Photos provides a practical user-authorized share-link flow.
- Do not upload duplicate receipt images to OneDrive in the MVP.
- Keep receiptPhotoLink optional in the schema.

Done when:
- The MVP works without a photo link.
- If available, an Amazon Photos link is included in the handoff and Excel row.
- Unit tests cover missing and present link cases.
