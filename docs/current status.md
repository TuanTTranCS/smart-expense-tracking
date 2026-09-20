# Current Status

- ✅ Done: Set the Android launcher label to `Smart Expense Tracking` and configured the supplied `images/smart-expense.png` as the launcher icon resource.
- ✅ Done: Verified the `Smart Expense Tracking` launcher name and supplied logo on a physical Pixel 7 on 2026-09-12.
- ✅ Done: Documented Microsoft Entra ID personal-account registration, Android MSAL redirect configuration, least-privilege `Files.ReadWrite` consent, and OneDrive folder prerequisites in `README.md` with official Microsoft references.

Last updated: 2026-09-19

## Overall Status

The Android app now uses separate Main and Settings destinations. Main owns the receipt workflow, compact provider selection/verification, and contextual OneDrive readiness; Settings owns remote opt-in, full profile management/transfer, and image preprocessing. Receipt state is held above navigation, provider and preprocessing choices are snapshotted per extraction, and local verification checks the installed Gemma model before the wired LiteRT-LM runtime is used. Persistent multi-profile management, secure per-profile credentials, provider configuration transfer, and image-first OneDrive export remain intact. Automated verification passes, and the separated app was successfully tested on a physical Pixel 7 on 2026-09-11. The Windows Hermes/PowerShell design is now decision-complete in `docs/3.excel-update-hermes-goal.md`, but its scripts, tests, copied-workbook canary, and paused cron job are not yet implemented.

## Completed

- ✅ Done: Implemented the app-view separation plan with Main and Settings Navigation Compose destinations, Settings-only configuration controls, compact Main provider selection, selected saved-provider verification, local Gemma model readiness checking, contextual OneDrive recovery without routine Disconnect, shared receipt workflow state, persisted preprocessing settings, and protected Settings Back/Up behavior. Added focused unit coverage, compiled Compose tests, assembled the debug APK, and verified against `REQ-UI-001` through `REQ-UI-005` and `AC-022` through `AC-025`. The separated app was successfully tested on a physical Pixel 7 on 2026-09-11.

- ✅ Done: Updated the LM Studio test profile to use `json_schema` structured output after emulator testing showed that the server rejects legacy `json_object`. The connectivity probe now requires exactly `{"status":"ok"}`.
- ✅ Done: Ran the LM Studio provider-test action on the Pixel 8a emulator. The app connected successfully to `http://10.0.0.207:1234/v1` using `google/gemma-4-e2b` and received the expected JSON-schema response.

- ✅ Done: Created initial requirements for the Android-to-OneDrive-to-Windows-to-Excel workflow.
- ✅ Done: Documented feasibility findings and integration risks.
- ✅ Done: Created the initial implementation plan.
- ✅ Done: Created the gap-closing checklist.
- ✅ Done: Confirmed Microsoft Graph as the preferred OneDrive handoff mechanism.
- ✅ Done: Confirmed Hermes will run the Windows action through PowerShell.
- ✅ Done: Captured the target workbook path and monthly worksheet pattern.
- ✅ Done: Confirmed receipt images should not be duplicated into OneDrive for the MVP.
- ✅ Done: Created Phase 1 handoff contract notes in `docs/handoff-contract.md`.
- ✅ Done: Created valid and invalid handoff JSON fixtures in `fixtures/handoff`.
- ✅ Done: Added and ran `scripts/test-handoff-contract.ps1` to verify handoff fixtures.
- ✅ Done: Added a real receipt-based example fixture derived from the user-provided `Photo 1.jpg`.
- ✅ Done: Reviewed current Google AI Edge/LiteRT-LM Android documentation for the extraction spike.
- ✅ Done: Added Kotlin extraction prompt, parser, validation model, and focused parser tests under `src`.
- ✅ Done: Added extraction model-output fixtures in `fixtures/extraction`.
- ✅ Done: Added and ran `scripts/test-extraction-contract.ps1` to verify extraction fixtures.
- ✅ Done: Documented the selected LiteRT-LM direct-in-app extraction direction in `docs/android-extraction-spike.md`.
- ✅ Done: Added a Kotlin extraction pipeline that supports direct image extraction plus OCR-to-text fallback orchestration.
- ✅ Done: Added focused pipeline unit tests for direct image success, parser-driven fallback, and invalid-output failure cases.
- ✅ Done: Ran `gradle test` to verify the extraction parser and pipeline tests.
- ✅ Done: Pinned Workstream 1 to the official `Gemma 4 E2B` LiteRT-LM repo and default `.litertlm` filename in code and docs.
- ✅ Done: Added an Android-facing LiteRT-LM receipt model client bridge and unit tests for model-path, backend, and multimodal staging behavior.
- ✅ Done: Added an `android-extraction` module that wires the common extraction bridge to the LiteRT-LM Android Kotlin API.
- ✅ Done: Configured the workspace Android SDK path through `local.properties` and installed the Android platform/build-tools needed by `:android-extraction`.
- ✅ Done: Upgraded the project Kotlin plugins to `2.2.21` and pinned `com.google.ai.edge.litertlm:litertlm-android` to `0.13.1` so the Android module builds reproducibly.
- ✅ Done: Verified `gradle :android-extraction:assembleDebug` succeeds in the workspace.
- ✅ Done: Evaluated optional OpenAI-compatible API support and updated requirements/plans to support a Settings-based Model Selector while keeping local extraction as the default.
- ✅ Done: Added a shared model-selection contract for local and OpenAI-compatible providers, including remote opt-in gating and provider metadata normalization.
- ✅ Done: Added an OpenAI-compatible `ReceiptModelClient` spike with OCR-text and direct-image request construction, response parsing, provider-test probing, auth/rate/network error mapping, and request-log redaction.
- ✅ Done: Added focused JVM tests for provider selection, OpenAI-compatible request construction, response parsing, credential redaction, and remote-provider error mapping.
- ✅ Done: Ran `gradle test` with `GRADLE_OPTS=-Dkotlin.compiler.execution.strategy=in-process` to verify the updated JVM and Android-module test suites.
- ✅ Done: Migrated the Android build to AGP 9.3.0 built-in Kotlin and Kotlin/Compose 2.3.21 for Gradle 9.7.0 compatibility, added the Android app's missing Kotlin test dependency, and verified JVM tests, Android app unit tests, and debug assembly of both Android modules.
- ✅ Done: Added Android Photo Picker receipt selection, URI-to-`ReceiptImage` loading, LM Studio direct-image extraction through `ReceiptExtractionPipeline`, an editable review view, raw-output diagnostics, and manual-entry recovery for malformed output and remote failures. Added focused Android app unit tests and verified the shared tests, app tests, and debug APK build.
- ✅ Done: Fixed the physical-device debug APK packaging to include `arm64-v8a` alongside emulator ABIs; verified the rebuilt APK metadata and debug assembly for Pixel 7 installation.
- ✅ Done: Installed the debug APK on a physical Pixel 7 running Android 16 and successfully extracted a real receipt through the configured LM Studio direct-image flow.
- ✅ Done: Confirmed Microsoft Graph authentication will target a personal Microsoft account, and the handoff folder will be configured relative to the OneDrive root instead of storing the machine-specific `D:\OneDrive` prefix.
- ✅ Done: Confirmed the OneDrive-relative handoff folder as `Documents/2_Others/Expenses_finance/logs`, corresponding to `D:\OneDrive\Documents\2_Others\Expenses_finance\logs` on Windows.
- ✅ Done: Added a default-enabled, persisted Android checkbox that reduces selected input images larger than 200 KB to a JPEG strictly below 200 KB before extraction, with an opt-out path and focused loader unit tests. Verified against REQ-A-002A.
- ✅ Done: Created `docs/model-profile-management-plan.md`, a phased implementation plan for multiple named OpenAI-compatible profiles, per-profile secure credentials, selected-profile persistence, legacy migration, UI management, and verification.
- ✅ Done: Implemented `REQ-M-015` through `REQ-M-020`: stable named profiles, Room persistence, selector state, per-profile encrypted credential aliases, local fallback, and idempotent legacy migration.
- ✅ Done: Added `ModelProfilesViewModel`, saved-profile resolution, explicit Test/Save/Save-and-select separation, create/edit/select/delete/clear-credential flows, and immutable selection snapshots for receipt extraction.
- ✅ Done: Added the Compose selector summary, empty/list/editor states, live validation, masked credential entry, remote privacy disclosure, delete confirmation, and unsaved-change confirmation while preserving the receipt picker/review flow.
- ✅ Done: Verified shared JVM tests, Android app unit tests (including Room, migration, resolver, and ViewModel coverage), Compose-test compilation, and `:android-app:assembleDebug` on 2026-09-01.
- ✅ Done: Added `REQ-M-021` and a one-time bundled LM Studio bootstrap for clean installs and upgrades. It preserves user profiles, avoids equivalent duplicates, stays unselected, and supplies the LM Studio placeholder credential. Migration tests and debug assembly passed on 2026-09-05.
- ✅ Done: User-verified the multiple model-profile feature successfully on a physical Pixel 7 on 2026-09-05.
- ✅ Done: Added bulk provider-configuration JSON export/import through Android's Storage Access Framework. Exports include all remote-profile metadata and selector state but no credentials; imports validate and preview the whole file, atomically save profiles, copy conflicting IDs, create local credential aliases, and preserve the device's current selector state. Codec, ViewModel, Room, shared JVM, Android unit-test, Compose-test compilation, and debug APK verification pass on 2026-09-10.
- ✅ Done: User-validated provider-profile JSON export/import through the Android system document picker on a physical device on 2026-09-10.
- ✅ Done: Defined the paired receipt export requirements on 2026-09-05: normalize a JPEG below 200 KB, upload it under `Documents/2_Others/Expenses_finance/receipt_images/YYYY-MM`, add required Version 2 `receiptImageRelativePath`, then publish the final JSON as the commit marker. Implementation followed on 2026-09-06.
- ✅ Done: Implemented the Microsoft Graph authentication code path with MSAL 8.4.2 single-account mode, personal-account audience, delegated `Files.ReadWrite`, silent token acquisition, safe recovery states, explicit sign-out, and read-only verification of the confirmed OneDrive folder. Focused Android unit tests and debug assembly pass.
- ✅ Done: Fixed the Microsoft Connect no-op caused by the debug APK being signed with a sandbox-specific certificate that did not match the Entra redirect. Debug signing now resolves the stable host keystore, supports explicit Gradle/environment overrides, and fails the build if the signing certificate disagrees with either MSAL redirect configuration. Verification, Android unit tests, and debug assembly pass on 2026-09-09.
- ✅ Done: Implemented confirmed-receipt export with stable expense identity and device-local timestamp, always-normalized sub-200-KB JPEG storage, Room-backed retry records, deterministic paired OneDrive paths, idempotent monthly-folder handling, image-first upload, Version 2 JSON generation, temporary upload/final-name commit, actionable failure mapping, and retry UI. Focused Android unit tests pass on 2026-09-06.
- ✅ Done: User-validated Microsoft OneDrive access and successful saving of the receipt handoff JSON file on a physical device on 2026-09-10.

- ✅ Done: Added the multiple-receipt extraction rule to direct-image and OCR-text prompts: matching itemized and finalized documents yield one expense using the finalized amount including tax and tip, totals are never summed, and ambiguous pairs are marked `low_confidence`. Prompt contract tests were added on 2026-09-06.
- ✅ Done: Android confirmation now exports unchanged `low_confidence` receipts as `confirmed`; any user edit to a review field exports as `manual`. The status is stored with the local export for consistent JSON generation on retry. Focused unit tests added on 2026-09-16; verified against REQ-A-018 and AC-030.
- ✅ Done: Distinct transactions in one receipt image now produce a `receipts` array, separate review cards, and independent paired OneDrive image/JSON exports with per-transaction confirmed/manual status and retry identity. Settings auto-detects a device name, allows a persistent override, and snapshots `sourceDeviceName` into every new JSON. Covered by parser, workflow, export, and settings unit tests; verified against REQ-A-019, REQ-A-020, and REQ-M-003A.

- ✅ Done: Fixed the malformed nested receipts JSON Schema that caused extraction requests to be rejected while provider checks passed. Added a default-off persisted Debug setting, one raw extraction response/error field, and a generated Version 2 JSON preview with publication status per export. Full request-JSON, provider-error, setting, workflow, and Compose tests were added; shared and Android unit tests, Compose test compilation, and debug APK assembly passed on 2026-09-17. Verified against REQ-M-002, REQ-M-003A, REQ-M-014, REQ-UI-006, and AC-031. No device was attached for an LM Studio/OpenRouter smoke test.
- ✅ Done: Removed the remaining trailing comma from the nested receipt schema after LM Studio strictly rejected the request even though the lenient JVM `org.json` test parser accepted it. Added raw-request regression coverage and a narrow recovery path that retries a JSON-schema extraction once without `response_format` only when the provider returns HTTP 400 `invalid_json`. Full shared tests, Android unit tests, Compose test compilation, and debug APK assembly passed on 2026-09-19. Verified against REQ-M-002, REQ-M-003A, REQ-M-013, REQ-M-014, and AC-031; Hugo confirmed successful LM Studio receipt-image extraction on a physical Pixel 7 on 2026-09-19.

## In Progress

- Phase 1 integration spike planning and exploration.
- Concrete Phase 1 plan: `docs/1.integration-plan.md`.
- Windows implementation goal: `docs/3.excel-update-hermes-goal.md`. The confirmed design uses deterministic PowerShell, Excel COM, atomic local idempotency state, next-empty-row F/G/H mapping, missing-month and summary integration, conservative duplicate handling, CAD-only writes, and a live script-only/no-agent Hermes job.
- The real workbook mapping was reviewed on 2026-09-14: monthly transactions begin at row 12; F stores amount, G stores merchant/date text, H is available for a receipt hyperlink, and E/O plus existing formulas and formatting must be preserved.
- ✅ Done: Windows processor implementation and copied-workbook COM verification passed on 2026-09-16 with 100 assertions. The source workbook is `D:\Users\tuant\OneDrive\Documents\2_Others\Expenses_finance\Canada plan.xlsx`; automated write tests use disposable copies. Hugo also completed a controlled manual non-dry-run test against the live workbook and restored the workbook afterward; a subsequent live-tree dry run found no pending handoffs and left its SHA-256 unchanged. Active job `f6934b4a04fe` runs every five minutes in script-only/no-agent mode, targets Discord channel `1549288366992793652`, and is pinned to `gpt-5.6-luna` with high reasoning. Disposable-path delivery was verified by reading back Discord message `1549441490327965807`; the temporary override was removed.
- Workstream 0 fixtures are now based on both synthetic and real receipt examples.
- Workstream 1 Android extraction is API-first. Android Settings, secure key storage, saved-profile resolution, real HTTP transport, local readiness verification, and LiteRT-LM runtime selection are wired; physical-device LiteRT-LM execution remains.
- Local-provider implementation is wired through the Android LiteRT-LM module when the pinned model exists in the app-private models directory. Device execution and performance remain unverified.

## Pending Decisions

- Confirm the real remote endpoints/models to use for device verification; profiles now support user-selectable direct-image or OCR-text input, normalized HTTP(S) base URLs, nonblank model IDs, and JSON object/schema output.
- Confirm the exact Google AI Edge API or LiteRT-LM extraction path for receipt images after the API-provider spike.
- Select the Android test device for the local extraction spike. The model target is now pinned to `Gemma 4 E2B`, and the workspace build is ready for device testing.

## Current Recommendation

Keep the verified Windows job paused until separate approval is given to activate production polling against the live workbook. In parallel, the remaining Android device work is interrupted-export retry, reconnect/silent restore, and deferred LiteRT-LM execution validation.
