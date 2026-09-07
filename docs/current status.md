# Current Status

Last updated: 2026-09-06

## Overall Status

The Android app implements persistent management of multiple named OpenAI-compatible model profiles. Room stores non-secret profile metadata and selector state, encrypted preferences store distinct per-profile credentials, bootstrap migrates legacy LM Studio settings and now seeds the bundled LM Studio Gemma profile once, and extraction resolves an immutable saved-profile snapshot with deterministic local fallback. The user successfully verified the multiple-profile feature on a physical Pixel 7. During that validation, the user reported that the first multi-profile build omitted the default LM Studio entry; the 2026-09-05 fix restores it alongside user profiles.

## Completed

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
- ✅ Done: Defined the paired receipt export requirements on 2026-09-05: normalize a JPEG below 200 KB, upload it under `Documents/2_Others/Expenses_finance/receipt_images/YYYY-MM`, add required Version 2 `receiptImageRelativePath`, then publish the final JSON as the commit marker. Implementation followed on 2026-09-06.
- ✅ Done: Implemented the Microsoft Graph authentication code path with MSAL 8.4.2 single-account mode, personal-account audience, delegated `Files.ReadWrite`, silent token acquisition, safe recovery states, explicit sign-out, and read-only verification of the confirmed OneDrive folder. Focused Android unit tests and debug assembly pass.
- ✅ Done: Implemented confirmed-receipt export with stable expense identity and device-local timestamp, always-normalized sub-200-KB JPEG storage, Room-backed retry records, deterministic paired OneDrive paths, idempotent monthly-folder handling, image-first upload, Version 2 JSON generation, temporary upload/final-name commit, actionable failure mapping, and retry UI. Focused Android unit tests pass on 2026-09-06.

- ✅ Done: Added the multiple-receipt extraction rule to direct-image and OCR-text prompts: matching itemized and finalized documents yield one expense using the finalized amount including tax and tip, totals are never summed, and ambiguous pairs are marked `low_confidence`. Prompt contract tests were added on 2026-09-06.

## In Progress

- Phase 1 integration spike planning and exploration.
- Concrete Phase 1 plan: `docs/1.integration-plan.md`.
- Workstream 0 fixtures are now based on both synthetic and real receipt examples.
- Workstream 1 Android extraction is API-first. Android Settings, secure key storage, saved-profile resolution, and the real HTTP transport are wired; runtime multi-profile verification and LiteRT-LM execution remain.
- In Progress: The receipt picker, extraction review, and paired export code paths are implemented. Physical Pixel 7 verification of the real Microsoft sign-in and paired OneDrive export remains.
- Local-provider groundwork is available. The parser/validation spike, runtime orchestration, model pinning, Android adapter bridge, Android module wiring, workspace SDK setup, and Android module build verification are complete; the remaining local-provider step is deferred until after the API path is proven.
- Microsoft Graph authentication runtime verification is pending. Replace the placeholder client ID and redirect signature with an Entra Android registration, then validate connect, silent restore, folder verification, disconnect, and reconnect on the Pixel 7. See `docs/microsoft-graph-OneDrive-plan.md`.
- Paired receipt-image/Version 2 JSON export physical-device verification is pending; implementation and focused unit coverage are complete.

## Pending Decisions

- Confirm the real remote endpoints/models to use for device verification; profiles now support user-selectable direct-image or OCR-text input, normalized HTTP(S) base URLs, nonblank model IDs, and JSON object/schema output.
- Confirm the exact Google AI Edge API or LiteRT-LM extraction path for receipt images after the API-provider spike.
- Select the Android test device for the local extraction spike. The model target is now pinned to `Gemma 4 E2B`, and the workspace build is ready for device testing.
- Create the real Microsoft Entra Android app registration and insert its client ID plus debug-signature redirect URI; the delegated permission, personal-account audience, and MSAL token-refresh behavior are now defined in `docs/microsoft-graph-OneDrive-plan.md`.
- Confirm the meanings of Excel columns F and G in `Canada plan.xlsx`.
- Confirm the desired action when a similar record already exists: skip, quarantine for review, or insert with a warning.
- Confirm whether monthly sheets are created manually or by the PowerShell agent.
- Confirm default currency and locale rules.

## Current Recommendation

Continue Workstream 2 on the Pixel 7: configure the real Entra registration, verify authentication, export one normalized receipt JPEG followed by its Version 2 handoff JSON, and exercise a retry after interruption. Defer LiteRT-LM device execution until the paired Graph export is proven end-to-end.
