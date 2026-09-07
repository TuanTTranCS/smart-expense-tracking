# Gap-Closing Plan Details

Last updated: 2026-09-06

## Gap 1: Model Provider and Local Extraction Path

Status: The API-first Phase 1 extraction spike is proven on a physical Pixel 7. OCR text fixtures, extraction output fixtures, a Kotlin parser/validation spike, extraction runtime orchestration with OCR fallback, an Android-facing adapter bridge, a real Android LiteRT-LM module, successful workspace Android assembly, and documentation notes are available. Android local model execution remains deferred until after the OneDrive handoff path is proven.

Problem:
The Android app does not need to depend on AI Edge Gallery specifically. Google AI Edge APIs and LiteRT-LM are acceptable, but the exact receipt-image extraction path must be verified. The app also needs a provider abstraction so local models and user-configured OpenAI-compatible APIs can share the same structured extraction contract.

Resolution path:
- ✅ Done: Review Google AI Edge API and LiteRT-LM Android documentation.
- ✅ Done: Evaluate OpenAI-compatible API support as an optional model provider. Recommendation: test it first behind explicit user opt-in while keeping local extraction as the default long-term privacy path.
- Use AI Edge Gallery as a reference app/sample where useful.
- ✅ Done: Define the extraction provider interface used by both local and OpenAI-compatible providers.
- ✅ Done: Define Settings Model Selector behavior, including provider type, provider display name, base URL, model id, image-vs-OCR input capability, selected provider state, and provider test action.
- ✅ Done: Define API-key indirection and request-log redaction rules for OpenAI-compatible providers through the `ApiKeyStore` and request redaction contracts.
- ✅ Done: Prototype OpenAI-compatible request/response handling against the normalized receipt extraction contract first.
- ✅ Done: Adapted the LM Studio profile to its observed `json_schema` structured-output requirement and added focused regression coverage for the provider-test request.
- ✅ Done: Wired the writable `ApiKeyStore` contract to encrypted Android credential storage with stable, distinct per-profile aliases; Room persists only non-secret metadata and selector state.
- ✅ Done: Migrated the build to Gradle 9.7.0-compatible AGP 9.3.0 with built-in Kotlin, aligned Kotlin/Compose to 2.3.21, and verified shared JVM tests, Android app unit tests, and debug assembly of both Android modules.
- ✅ Done: Verified the LM Studio provider-test action on the Pixel 8a emulator. The default profile at `http://10.0.0.207:1234/v1`, using `google/gemma-4-e2b` and JSON-schema output, returned the expected connectivity response.
- ✅ Done: Added Android Photo Picker support to `android-app`, URI-to-`ReceiptImage` conversion, configured OpenAI-compatible extraction through `ReceiptExtractionPipeline`, editable parsed fields, raw-output visibility, and manual-entry recovery. Focused unit tests cover URI conversion, successful direct-image result mapping, malformed model output, and remote failure recovery.
- ✅ Done: Verified a real receipt extraction through the configured LM Studio direct-image flow on a physical Pixel 7 running Android 16.
- ✅ Done: Added default-enabled oversized-image reduction to the Android receipt flow. Images larger than 200 KB are resized and re-encoded below 200 KB before extraction unless the user disables the persisted checkbox; focused unit tests cover the size boundary, enabled behavior, disabled behavior, and invalid reducer output.
- ✅ Done: Defined the phased multi-profile management implementation in `docs/model-profile-management-plan.md`, including Room-backed profile metadata, per-profile encrypted credential aliases, selected-profile persistence, legacy single-profile migration, Compose management flows, and required verification.
- ✅ Done: Implemented multiple named OpenAI-compatible profile creation, editing, testing, explicit saving, selection, deletion, Room persistence, encrypted per-profile credentials, selected-profile extraction snapshots, local fallback, and idempotent legacy migration.
- ✅ Done: Restored the bundled LM Studio Gemma profile alongside user-created profiles on clean installs and upgrades after Pixel 7 validation exposed its absence. One-time bootstrap avoids selection changes, user-edit overwrites, and equivalent duplicates.
- ✅ Done: The user successfully verified the multiple model-profile feature on a physical Pixel 7 on 2026-09-05.
- ✅ Done: Updated and unit-tested both extraction prompts so matching itemized and finalized receipt documents produce one expense using the finalized amount including tax and tip; ambiguous pairs require `low_confidence` review.
- Later: Prototype Google AI Edge API or LiteRT-LM integration on Android hardware. The platform-neutral parser/prompt spike, runtime pipeline, initial `Gemma 4 E2B` model selection, Android-facing adapter bridge, Android LiteRT-LM module wiring, workspace SDK configuration, and Android assembly verification are done; device execution is deferred.
- Later: Verify whether receipt images can be passed directly to the selected local model path. Documentation confirms the API supports image content with multimodal models and a vision backend; actual selected model/device behavior remains unverified.
- Later: If direct local image input is not practical, add an OCR/preprocessing step before LLM extraction.
- In Progress: Document the selected model format, device requirements, and fallback behavior in `docs/android-extraction-spike.md`.

Done when:
- API-provider integration path is confirmed first.
- A minimal extraction spike returns structured receipt fields from sample receipt images.
- The Settings Model Selector contract is defined.
- Optional OpenAI-compatible provider behavior is covered by focused request-construction, response-parsing, credential-redaction, and error-mapping tests.
- The later local-model spike documents whether OCR is required for local extraction.
- Unit tests cover output parsing and validation.

## Gap 2: Microsoft Graph OneDrive Handoff

Status: Authentication, read-only verification, and paired normalized-receipt/Version 2 JSON export are implemented with passing Android unit tests. Real Entra registration and Pixel 7 end-to-end verification remain pending.

Problem:
The Android app uses Microsoft Graph to upload a normalized receipt JPEG followed by its Version 2 JSON handoff. The remaining gap is physical-device validation with a real Entra registration.

Resolution path:
- ✅ Done: Confirmed Microsoft account type is personal.
- ✅ Done: Defined a personal-account Android public-client registration with delegated `Files.ReadWrite`, no client secret, single-account MSAL behavior, and debug/release redirect handling in `docs/microsoft-graph-OneDrive-plan.md`.
- ✅ Done: Confirmed the OneDrive-relative handoff folder as `Documents/2_Others/Expenses_finance/logs`. Android must not store the local `D:\OneDrive` sync prefix.
- ✅ Done: Confirmed normalized receipt JPEGs use `Documents/2_Others/Expenses_finance/receipt_images/YYYY-MM/YYYYMMDD_HHmmss_receipt_<shortExpenseId>.jpg`.
- In Progress: MSAL silent token acquisition and refresh handling are implemented; replace placeholder registration values and verify the flow on the Pixel 7.
- ✅ Done: Implemented image-first Graph upload using the authentication boundary, followed by temporary-name and final-name Version 2 JSON publication.
- ✅ Done: Persist stable expense/export identity, normalized local JPEG, failure state, and paired paths so restart/retry cannot create duplicates.
- Pending: Validate complete export and interrupted retry on the Pixel 7 with the real Entra registration.

Done when:
- A normalized receipt JPEG and its Version 2 handoff can be uploaded repeatedly to their target folders.
- The final JSON never appears before its referenced image is complete.
- Unit tests cover image normalization, paired naming, schema generation, ordering, and idempotent retry.

## Gap 3: Hermes PowerShell Action

Status: Phase 1 spike planned in `docs/1.integration-plan.md`

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

Status: Phase 1 spike planned in `docs/1.integration-plan.md`

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

## Gap 5: Receipt Image and Optional Photo Link

Status: Required OneDrive receipt image and Version 2 correlation are implemented and unit-tested. Optional external photo-link work remains pending.

Problem:
Each exported expense now requires a small normalized receipt JPEG in OneDrive so the JSON and eventual Excel record can reference a workflow-owned image. Amazon Photos may remain the independent full-resolution backup, while stable programmatic external links may not be available.

Resolution path:
- Verify whether Amazon Photos provides a practical user-authorized share-link flow.
- ✅ Done: Generate a normalized JPEG strictly below 200 KB for OneDrive export.
- ✅ Done: Store it under `Documents/2_Others/Expenses_finance/receipt_images/YYYY-MM/YYYYMMDD_HHmmss_receipt_<shortExpenseId>.jpg` using the JSON export timestamp and device timezone.
- ✅ Done: Resolve or create the monthly folder hierarchy idempotently before upload.
- ✅ Done: Add required Version 2 field `receiptImageRelativePath` to correlate the handoff with its image.
- ✅ Done: Upload the image before publishing the final JSON and reuse both deterministic paths on retry.
- Keep receiptPhotoLink optional in the schema.

Done when:
- Every final Version 2 JSON references an existing normalized OneDrive receipt JPEG.
- Failed/interrupted exports remain retryable and create no duplicate image or JSON.
- The workflow still works without an external Amazon Photos link.
- If available, an Amazon Photos link is included in the handoff and Excel row.
- Unit tests cover normalization, naming, image-first ordering, idempotent retry, and missing/present external links.
