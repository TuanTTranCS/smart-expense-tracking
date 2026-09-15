# Gap-Closing Plan Details

Last updated: 2026-09-14

## Cross-Cutting Android Branding

Status: ✅ Done — the launcher label is `Smart Expense Tracking` and the supplied `images/smart-expense.png` asset is configured as the launcher icon. Both were verified on a physical Pixel 7 on 2026-09-12.

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
- ✅ Done: Added versioned bulk provider-configuration JSON export/import through Android's system document picker. Exports contain all saved remote-profile metadata and the selector snapshot but exclude credentials and internal aliases; imports provide validation and confirmation, atomically persist profiles, remap conflicting IDs, and preserve the current local selector/opt-in state. Focused unit coverage verifies the JSON contract, security boundary, import workflow, and Room bulk persistence. User-validated on a physical device on 2026-09-10.
- ✅ Done: Updated and unit-tested both extraction prompts so matching itemized and finalized receipt documents produce one expense using the finalized amount including tax and tip; ambiguous pairs require `low_confidence` review.
- ✅ Done: Separated the Main receipt workflow from Settings configuration with Navigation Compose. Added compact provider selection and saved-profile verification, local Gemma model-file readiness checks, a shared navigation-stable receipt workflow state holder, persisted preprocessing snapshots, and one derived OneDrive readiness rule. Focused Android unit tests and debug assembly pass, and the separated app was successfully tested on a physical Pixel 7 on 2026-09-11.
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

Status: Authentication, read-only verification, paired normalized-receipt/Version 2 JSON export, real Entra debug registration, and deterministic matching debug signing are implemented with passing verification and Android unit tests. The user has validated OneDrive access and successful saving of the JSON handoff file on a physical device; interrupted-retry and reconnect/silent-restore validation remain.

Problem:
The Android app uses Microsoft Graph to upload a normalized receipt JPEG followed by its Version 2 JSON handoff. The remaining gap is physical-device validation with a real Entra registration.

Resolution path:
- ✅ Done: Confirmed Microsoft account type is personal.
- ✅ Done: Defined a personal-account Android public-client registration with delegated `Files.ReadWrite`, no client secret, single-account MSAL behavior, and debug/release redirect handling in `docs/microsoft-graph-OneDrive-plan.md`.
- ✅ Done: Confirmed the OneDrive-relative handoff folder as `Documents/2_Others/Expenses_finance/logs`. Android must not store the local `D:\OneDrive` sync prefix.
- ✅ Done: Confirmed normalized receipt JPEGs use `Documents/2_Others/Expenses_finance/receipt_images/YYYY-MM/YYYYMMDD_HHmmss_receipt_<shortExpenseId>.jpg`.
- In Progress: MSAL silent token acquisition and refresh handling are implemented, and the Entra Android debug registration is configured. OneDrive access and JSON-file saving have been validated on a physical device; verify silent restore, disconnect, reconnect, and interrupted retry.
- ✅ Done: Fixed the Connect no-op caused by sandbox-specific debug signing. The build selects the stable host debug keystore (with explicit overrides available) and verifies its certificate against the MSAL JSON redirect and manifest callback before every debug build.
- ✅ Done: Implemented image-first Graph upload using the authentication boundary, followed by temporary-name and final-name Version 2 JSON publication.
- ✅ Done: Persist stable expense/export identity, normalized local JPEG, failure state, and paired paths so restart/retry cannot create duplicates.
- Pending: Validate interrupted retry on the Pixel 7 with the real Entra registration.

Done when:
- A normalized receipt JPEG and its Version 2 handoff can be uploaded repeatedly to their target folders.
- The final JSON never appears before its referenced image is complete.
- Unit tests cover image normalization, paired naming, schema generation, ordering, and idempotent retry.

## Gap 3: Hermes PowerShell Action

Status: Design resolved in `docs/3.excel-update-hermes-goal.md`; implementation, tests, and paused canary pending

Problem:
Hermes will run the Windows automation through PowerShell. The contract is now defined, but the deterministic processor, tests, and safe rollout still need implementation.

Resolution path:
- Implement the parameterized entry point, reusable functions, dry-run support, structured output, and redacted JSON Lines log from `docs/3.excel-update-hermes-goal.md`.
- Auto-detect the local OneDrive root and keep atomic expense-id state under `%LOCALAPPDATA%\SmartExpenseTracking\Hermes`; no Graph credentials are required on Windows.
- Prevent overlapping runs and process direct-child final Version 2 JSON files in ordinal filename order.
- Route inserted/exact-duplicate files to `receipt_jsons_done`, ambiguous/non-CAD files to `receipt_jsons_review`, and invalid files to `receipt_jsons_error` with reasons.
- Use a Hermes script-only/no-agent job every five minutes, suppress empty-run delivery, and deliver concise reportable outcomes to the configured Discord home channel.
- Create the job paused only after tests and a disposable-workbook canary pass. Live activation requires separate approval.

Done when:
- The PowerShell action validates and classifies Version 2 fixtures without an LLM.
- Processing state and duplicate protection survive restart and partial post-save failures.
- Unit tests cover path resolution, validation, routing, output, and idempotency outside Hermes scheduling.
- A paused five-minute job exists and a disposable-path manual run has delivered the expected Discord result.

## Gap 4: Excel Workbook Mapping

Status: Real-workbook mapping and Excel COM mechanism confirmed; implementation and copied-workbook verification pending

Problem:
The workbook contract is confirmed. The remaining work is to implement and verify it without damaging the live workbook or its native formulas, formatting, and summary logic.

Resolution path:
- Use workbook path `D:\OneDrive\Documents\2_Others\Expenses_finance\Canada plan.xlsx`.
- Select monthly worksheet by receipt date using `YYYY-MM`.
- Use Excel COM and the first row from 12-100 where F and G are both empty; do not insert, reorder, or overwrite rows.
- Write numeric amount to F, normalized merchant plus invariant English `MMM dd` to G, and a relative receipt-image hyperlink to H.
- Preserve E, O, formulas, formatting, and unrelated cells; safely extend the established row pattern only when required.
- Copy the latest earlier monthly sheet when the target is missing, retain rows 1-11 and non-transaction structure, and clear only F:H rows 12-100.
- Add missing future months to the validated `Food Expense Summary` structure by extending its 89-row detail block, monthly summary, ranges, coverage checks, and title.
- Skip/archive exact date/amount/normalized-merchant duplicates and quarantine same-date/same-amount merchant conflicts without fuzzy or LLM matching.
- Accept CAD only and defer safely when Excel is locked, read-only, full, unavailable, or structurally unexpected.
- Verify every behavior against disposable copies of the real workbook.

Done when:
- A sample Version 2 handoff fills exactly one safe row in a copied workbook with the confirmed F/G/H mapping.
- E, O, formulas, formatting, sheet objects, and unrelated cells remain intact after save and reopen.
- Missing-month creation and `Food Expense Summary` extension pass the coverage check.
- Duplicate expense IDs and exact workbook duplicates are skipped; similar records are quarantined.
- Unit and copied-workbook integration tests cover mapping, duplicate detection, capacity, locks, and failure recovery.

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
- Pending: Write a relative clickable link to the normalized OneDrive image in Excel column H as part of Gap 4.
- Keep receiptPhotoLink optional in the schema.

Done when:
- Every final Version 2 JSON references an existing normalized OneDrive receipt JPEG.
- Failed/interrupted exports remain retryable and create no duplicate image or JSON.
- The workflow still works without an external Amazon Photos link.
- If available, an Amazon Photos link is included in the handoff and Excel row.
- Unit tests cover normalization, naming, image-first ordering, idempotent retry, and missing/present external links.
