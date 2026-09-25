# Gap-Closing Plan Details

Last updated: 2026-09-20

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
- ✅ Done: Updated the Android review and OneDrive handoff status rule: confirming an unchanged `low_confidence` receipt exports `confirmed`, while editing any review field exports `manual`. Added focused unit coverage for review edits, export, JSON, and retry persistence.
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

Status: ✅ Done; production job is active after separate live-activation approval.

Problem:
Hermes runs the Windows automation through PowerShell. The deterministic processor, disposable-workbook verification, scheduling, and Discord canary are complete; the production job is active.

Resolution path:
- Implement the parameterized entry point, reusable functions, dry-run support, structured output, and redacted JSON Lines log from `docs/3.excel-update-hermes-goal.md`.
- Auto-detect the local OneDrive root and keep atomic expense-id state under `%LOCALAPPDATA%\SmartExpenseTracking\Hermes`; no Graph credentials are required on Windows.
- Prevent overlapping runs and process direct-child final Version 2 JSON files in ordinal filename order.
- Route inserted/exact-duplicate files to `receipt_jsons_done`, ambiguous/non-CAD files to `receipt_jsons_review`, and invalid files to `receipt_jsons_error` with reasons.
- Use a Hermes script-only/no-agent job every five minutes, suppress empty-run delivery, and deliver concise reportable outcomes to the configured Discord home channel.
- Create the job paused only after tests and a disposable-workbook canary pass; activate it only after separate user approval. The approval has been granted and the job is active.

Done when:
- ✅ The PowerShell action validates and classifies Version 2 fixtures without an LLM.
- ✅ Processing state and duplicate protection survive restart and partial post-save failures.
- ✅ Unit tests cover path resolution, validation, routing, output, and idempotency outside Hermes scheduling.
- ✅ Paused job `f6934b4a04fe` is configured every five minutes in no-agent mode for Discord channel `1549288366992793652`; disposable delivery was read back as message `1549441490327965807`.

## Gap 4: Excel Workbook Mapping

Status: Excel COM implementation and copied-workbook verification ✅ Done

Problem:
The workbook contract is confirmed. The remaining work is to implement and verify it without damaging the live workbook or its native formulas, formatting, and summary logic.

Resolution path:
- Use workbook path `D:\Users\tuant\OneDrive\Documents\2_Others\Expenses_finance\Canada plan.xlsx`; copy it to a disposable tree for tests.
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
- ✅ A sample Version 2 handoff fills exactly one safe row in a copied workbook with the confirmed F/G/H mapping.
- ✅ E, O, formulas, formatting, sheet objects, and unrelated cells remain intact after save and reopen.
- ✅ Missing-month creation and `Food Expense Summary` extension pass the coverage check.
- ✅ Duplicate expense IDs and exact workbook duplicates are skipped; similar records are quarantined.
- ✅ Unit and copied-workbook integration tests cover mapping, duplicate detection, capacity, read-only/deferred paths, and failure recovery seams.

## Gap 6: Multiple Transactions and Device Name

Status: ✅ Done

- ✅ Done: Direct-image and OCR-text prompts return a `receipts` list with one element for each distinct transaction and one finalized element for matching itemized/finalized documents.
- ✅ Done: Parse and validate all list elements, while accepting legacy single-object responses.
- ✅ Done: Show each transaction in its own review card and keep edits, `confirmed`/`manual` status, export ID, and retry result independent.
- ✅ Done: Export each confirmed transaction to its own Version 2 JSON file and paired JPEG using the existing image-first publication rule.
- ✅ Done: Detect the Android device name, persist an optional Settings override, and snapshot `sourceDeviceName` into each export record and JSON.
- ✅ Done: Unit tests cover array parsing, independent review/export, status behavior, device-name persistence, and JSON output. Checked against REQ-A-019, REQ-A-020, and REQ-M-003A.

## Gap 7: Receipt Extraction Regression and Debug Output

Status: ✅ Done (automated verification complete; live provider smoke test pending)

- ✅ Done: Repaired the nested receipts JSON Schema and validated complete image and OCR requests for JSON Schema and JSON Object provider modes.
- ✅ Done: Preserved raw model output and provider/transport errors in transient review state, visible only when the persisted Debug setting is enabled.
- ✅ Done: Displayed each attempted export's exact generated Version 2 JSON with published or not-published status, independently across receipts and retries.
- ✅ Done: Shared and Android unit tests, Compose test compilation, and debug APK assembly passed on 2026-09-17; checked REQ-M-003A, REQ-M-014, REQ-UI-006, and AC-031.
- ✅ Done: Removed a trailing comma that LM Studio's strict parser rejected but the JVM `org.json` test parser accepted. Added a raw JSON trailing-comma regression check and a one-time `invalid_json` recovery request without `response_format`. Shared and Android unit tests, Compose test compilation, and debug APK assembly passed on 2026-09-19; checked REQ-M-002, REQ-M-003A, REQ-M-013, REQ-M-014, and AC-031.
- ✅ Done: Hugo confirmed the corrected LM Studio receipt-image extraction flow on a physical Pixel 7 on 2026-09-19. An OpenRouter smoke test remains pending when a configured endpoint is available.

## Gap 8: Selected Receipt Image Review

Status: ✅ Done (automated verification complete; verified on a physical Pixel 7 on 2026-09-21).

- ✅ Done: Defined `REQ-UI-007`, `AC-032`, and the implementation plan in `docs/5.image-review-plan.md`.
- ✅ Done: Retain the exact post-preprocessing `ReceiptImage` used by extraction in navigation-stable workflow state.
- ✅ Done: Show an accessible thumbnail after selection and open the complete image without cropping, with Close and Back dismissal.
- ✅ Done: Clear or replace preview state with each selection attempt and avoid retaining stale receipt images.
- ✅ Done: Added unit and Compose UI coverage; shared and Android unit suites pass, Compose tests compile, and the debug app assembles on 2026-09-21. Hugo confirmed the feature on a physical Pixel 7 on 2026-09-21; individual device test scenarios were not recorded.

Zoom extension status: ✅ Done (automated verification complete on 2026-09-21; verified on a physical Pixel 7 on 2026-09-22). `REQ-UI-008` and `AC-033` now have pinch-to-zoom, bounded panning, accessible Zoom in/Zoom out controls, and reset on reopening or replacement in the full-screen viewer. Pure transform and Android unit tests pass, Compose UI tests compile, and the debug APK assembles. Display decoding uses the processed in-memory image with a 4096-pixel longest-edge and 12-million-pixel memory bound.

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
