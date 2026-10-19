# Gap-Closing Plan Details

Last updated: 2026-10-18

## README Presentation Refresh

Status: ✅ Done (2026-10-18) — documentation only.

- Added the supplied app logo, custom platform/status/stack badges, linked skill icons, and section navigation. Placed all five screenshots beside Expenses/History, appearance/provider management, and Discord reporting, with descriptive alt text and bounded display widths.
- Folded advanced setup and milestone details into expandable sections while retaining existing configuration instructions and companion attribution.
- Verified descriptions against `REQ-M-008/009`, `REQ-A-031`, `REQ-W-015` and `REQ-UI-015`, preserving explicit remote opt-in, correction semantics, and outstanding runtime validation.
- Seven documentation checks pass for image coverage/placement, accessibility, local links/navigation, balanced HTML/code fences, and stack versions. All 14 hosted badge/icon URLs return SVGs; whitespace checks pass. Application code is unchanged, so application unit tests and a build were not required.

## Gap 18: Correction Archival and Quiet Deferred Retries

Status: ✅ Done (2026-10-17) — implementation and automated verification.

- Archive applied immutable correction operations in `receipt_jsons_done` after durable workbook/state/acknowledgement completion; route terminal conflicts and invalid operations to review/error with sidecar reasons.
- Repair acknowledgements from the local ledger after archival, retry interrupted moves without another workbook update, and reject differing archive collisions.
- Persist deferred notification identity/reason so unchanged retries stay quiet across restarts while processing continues; report changed reasons and terminal outcomes.
- Added Android reconciliation of matching archived operations and public operation identity during delayed acknowledgement sync; archive placement confirms publication only.
- Verified lifecycle, recovery, notification renderers and dry-run behavior against `REQ-W-002/003/006/007/009/011/016`, `AC-065` and `AC-067`. **144 correction**, **17 deferred-notification**, **40 disposable Excel correction** and **117 existing consumer assertions/canaries** pass, plus **18 wrapper** and **17 Android correction tests**. Batch/typed/contract regressions and the debug APK build pass. See [evidence](12.export-history-verification.md#correction-archival-and-quiet-deferred-retries). README/protocol/requirements updated.

## Gap 17: App Theme Preference

Status: ✅ Done (2026-10-17) — implementation and automated verification.

- Added an accessible Light/Dark/System radio group under **Settings → Appearance → Theme**, backed by the existing SharedPreferences settings repository and StateFlow. Missing/unknown saved values use System.
- Applied the selected appearance immediately across Expenses, History and Settings, preserving dynamic colors, safe drawing insets, content state and unsaved profile edits. System-bar icons follow the app appearance; Android 9 navigation-bar scrims update with the selection.
- Verified against `REQ-UI-015` and `AC-066` with seven new unit regressions covering persistence, invalid-value fallback, system resolution, full Settings callbacks/profile-draft preservation, live root appearance/content identity, and Android 9/15 system-bar behavior. Existing theme contrast and layout tests remain passing.
- Verification: `gradlew.bat test :android-app:assembleDebug :android-app:compileDebugAndroidTestKotlin --max-workers=2 --console=plain` succeeds: **308 Android tests** pass, **94 shared tests** remain passing/up to date, APK assembly and device-test compilation pass. README updated. Physical-device appearance verification remains pending; instrumentation tests were not executed for this change.

✅ Done (2026-10-17): Hugo confirmed physical-device verification of Plan 12 and its correction-notification expense-details fix on Pixel 7. See [device confirmation](12.export-history-verification.md#pixel-7-verification-confirmation). Individual scenario/provider details were not recorded.

## Gap 16: Export History, Date Context, and JSON Corrections

- ✅ Done (2026-10-16): Plan 12 I12-7 closes the missing expense details in successful correction notifications. Populate the existing result detail fields from validated corrected data and include source device; retain reason-only conflict output. Actual producer-to-renderer JSON/JSONL, PowerShell and Python regressions pass: **55 correction assertions**, **30 disposable Excel COM assertions**, **17 wrapper tests**, installed renderer parity/direct rendering and debug assembly. Updated `REQ-W-011`/`AC-065`, checked `AC-028`, and updated README. No production job invocation or notification was needed; see [evidence](12.export-history-verification.md#correction-notification-expense-details-follow-up).

Planning artifact and I12-1..6 software implementation/automated verification: ✅ Done (2026-10-16). Physical-device verification of Plan 12 and the I12-7 notification fix is ✅ Done on Pixel 7, confirmed by Hugo on 2026-10-17; detailed scenario/provider evidence is recorded separately in the verification record.

- See [iteration 12](12.export-history-date-context-and-json-edit-plan.md) for persistent History with automatic movement after publication and local Delete/Undo, device-local reference dates in image/OCR/typed prompts, and eligible exported-expense editing/re-export with `itemType: pending|processed`.
- ✅ Done (2026-10-16): Hugo confirmed both pending and done expenses are editable; processed corrections must update existing Excel entries. Inspected `C:\Users\tuant\AppData\Local\hermes\scripts\smart-expense-excel-update.py`, the main PowerShell entry/module, alternative notification entry, and wrapper test. Added concrete state migration, revision-aware workbook updates, updated-count notification support, installed-wrapper delivery, and isolated tests to the plan. Both product decisions are resolved.
- ✅ Done: Room v5 History, dated prompts, remote status/drafts/editor, immutable correction protocol, local v2 revision ledger, journalled Excel updates/relocation and updated notifications. **94 shared/301 Android unit tests**, debug assembly/test compilation, **14 emulator tests**, **48 correction assertions**, **27 disposable Excel correction assertions**, existing **117 consumer assertions/canaries** and **16 wrapper tests** pass. Exact installed-wrapper SHA256 parity is verified; see [verification](12.export-history-verification.md).
- ✅ Done: Requirements and handoff contract distinguish immutable published/retry snapshots from explicit corrections and allow reference-based date inference. Stable identities, batch members, normalized images, local removal and recoverable retries remain protected.
- ✅ Done: README describes History, reference-date extraction, corrections and consumer capability gating. Verification did not invoke the production processor or modify its queue/workbook/schedule. Pixel 7 verification of Plan 12 and the notification fix is ✅ Done, confirmed by Hugo on 2026-10-17; detailed cloud/provider scenarios are separately unrecorded.

## Gap 15: Adaptive Main and Settings UI

Status: ✅ Done (2026-10-12).

- Applied Hugo's chosen Android dynamic-color policy using semantic Material 3 roles with system light/dark switching and pre-Android-12 defaults. Bundled Inter and Roboto Mono with licenses.
- Grouped Main provider/input/review content, separated receipt fields and status badges, emphasized totals, and moved viewer controls outside the complete fitted-image viewport.
- Grouped Settings cards with dividers and uniform icons; retained checkbox/switch semantics and all callbacks, added full-row 48dp targets and wrapping profile/concurrency actions.
- Verified against `REQ-UI-001..014` and relevant `AC-022..025/032..039`: **87 shared/264 Android unit tests**, **26 affected emulator tests**, debug build and device-test compilation pass. Independent review and light/dark emulator inspection complete; README updated. See [evidence, commands and physical-display limits](11.adaptive-ui-verification.md).

## Gap 14: Same-Phone Mobile Model Server

Planning, companion software/HTTP-service checks and expense-app software/automated client verification: ✅ Done. Phone Verify, receipt-photo extraction and typed-expense extraction: ✅ Done on Pixel 7, confirmed by Hugo on 2026-10-12. Offline use, cancellation/retry/native drain, accuracy/latency/memory and background acceptance remain unconfirmed.

- ✅ Done (2026-10-12): Recorded Hugo's confirmation that Verify, receipt-photo extraction and typed-expense extraction passed on Pixel 7 for plans 10a/10b in both plans/evidence records and project summaries. Checked the evidence distinction against `REQ-M-007/035`, `AC-057..060`; remaining scenarios/measurements are unconfirmed. README updated briefly. Documentation only; no application code, tests or builds changed. See [companion confirmation](10a.mobile-server-companion-verification.md#phone-verification-confirmation) and [expense-app confirmation](10b.mobile-server-expense-app-verification.md#phone-verification-confirmation).

- ✅ Done (2026-10-12): Reviewed companion chat output against the expense client; the envelope is compatible. Fixed Prompt-only verification of completely fenced JSON, added a dedicated connectivity prompt and safe empty/malformed/unexpected-output diagnostics, and repaired two corrupted UI labels. Exact semantic JSON validation, strict HTTP-envelope parsing and existing credential/review/export boundaries remain covered. **87 shared/252 Android unit tests**, debug assembly and Android-test compilation pass (`REQ-M-011..014`, `REQ-UI-002/006`); README updated. [Evidence](10b.mobile-server-expense-app-verification.md#provider-output-and-ui-text-follow-up) distinguishes the verified formatting correction from the still-unconfirmed Pixel 7 generated reply/retest.

- ✅ Done (2026-10-12): Made expense diagnostics selectable and copyable with **Copy message**, including verification HTTP/code guidance, Settings profile test/discovery failures and failed expense items. Added separate saved/active companion endpoint **Copy** actions that copy the exact URL without credentials or unavailable/stopped placeholders. `REQ-UI-014`/`AC-061`: **81 shared/250 Android unit tests**, expense debug build/Android-test compilation, **60 companion unit tests**, companion debug/test APK builds and **2 actual emulator clipboard tests** pass. README and both implementation plans/evidence updated; native/paired/physical gates remain open.

- ✅ Done (2026-10-12): Corrected companion terminal cleanup to dispatch outside native callbacks, retaining ownership through reset and asynchronously cancelled unstarted handles. Added request-stage/HTTP/allowlisted-code diagnostics to expense verification with hostile-body redaction coverage. Hugo's original report is Same-phone server Verify with Gemma-4-E2B-it and Stop/restart guidance, plus LMSA custom OpenAI failure. **81 shared/244 Android unit tests**, expense APK build/test compilation pass; companion/device details are recorded in [verification](10a.mobile-server-companion-verification.md#callback-cleanup-and-verification-diagnostics-revision). Checked `REQ-M-011/031/032/035`, `REQ-A-023`, `AC-057/058/060`; README reviewed/updated. Software correction complete; Pixel 7 resolution and native/paired/physical gates remain Pending. Generic-client incompatibility alone did not explain expense Verify.

- ✅ Done (2026-10-12): Checked 10a after Hugo's Pixel 7 report: models discovery/built-in chat work, external local HTTP chat fails. Source confirms mandatory request preparation/headers plus non-streaming single-user extraction restrictions, so generic OpenAI chat compatibility is not established. Updated plan/evidence/status and concise README guidance against `REQ-M-031/032/035`, `AC-057..060`; documentation only, no application tests/build. Exact external app/error/request details remain requested, and native/paired/physical gates remain Pending. See [investigation](10a.mobile-server-companion-verification.md#pixel-7-external-chat-report-2026-10-12).

- ✅ Done (2026-10-11): Implemented [10b](10b.mobile-server-expense-app-integration-plan.md) software, independently validated/fixed GPT 6 Astra findings and passed re-review. Explicit categories, Room/transfer v3, optional secure Keep/Replace/Clear with revision journal/recovery, immutable admission, remote-only consent/Tailscale, strict loopback/Prompt-only HTTP, serial deadline/preparation/drain/quarantine and review/export preservation are verified. All **79 shared/244 Android unit tests**, debug build/Android-test compilation and **40 full emulator tests** pass, including actual socket auth/redirect/cancel/61-second response and real Room migrations. README setup and requirements/goal/PLAN/status records updated. E1–E4/E6/E7 ✅ Done; E5 software ✅ Done with native gate open; E8 docs/controlled HTTP ✅ Done with paired loaded-model/physical acceptance open. See [verification](10b.mobile-server-expense-app-verification.md); this supersedes historical implementation Pending statements below.

- ✅ Done (2026-10-10): Implemented the separate companion fork, contract version 1, Keystore-encrypted endpoint/key persistence, loopback-only truthful service, strict bounded text/image parsing, atomic HTTP/UI/native admission, socket-close/control cancellation and terminal drain/quarantine. Unit tests, APK builds, 18 Pixel 8a HTTP/security/service tests, and this repo's 60 shared/217 Android regression tests/build pass. Exact Gemma GPU artifact/hash is provisioned; runtime 0.16.1 reports missing emulator OpenCL. C1 native proof/C6 paired-physical acceptance and 10b remain open. See [verification and setup](10a.mobile-server-companion-verification.md); these results supersede the planning-only Pending entries below for companion software.

- ✅ Done (2026-10-10): Applied Option B's explicit Built-in on-device, Same-phone server, and Remote API requirements model. Aligned consent/fallback, verification, endpoint/optional-key security, Prompt-only JSON, profile/transfer v3 compatibility, duplication, Tailscale exclusion, offline recovery and scheduling. Added `REQ-M-031..035` and `AC-052..060`; updated existing requirements, design decisions, PLAN and goal. Documentation checks passed; README reviewed with setup deferred until implementation. No application code/tests/build changed.
- Plan: [Mobile-server integration goal](10.mobile-server-integration-goal.md).
- ✅ Done (2026-10-10): Detailed [companion app C1–C6](10a.mobile-server-companion-plan.md) and [expense-app integration E1–E8](10b.mobile-server-expense-app-integration-plan.md) plans cover the shared contract, endpoint/authentication, vision, native cancellation/drain, profile/transfer migrations, category-aware selection/scheduling, unit/build checks and physical validation. GPT 6 Astra review findings were applied: mandatory cancellation acknowledgement and instance fencing, Preparing-state cleanup, coordinated metadata/key staging/snapshots/journal recovery, predecode memory admission, incremental output bounds, strict response decoding and per-item deadline timing. Final re-review passed; documentation checks and README review complete. All application implementation tasks, runtime proof, tests/builds and device validation remain Pending.
- Extend a separate mobile-server fork to accept direct receipt-image requests using its existing vision inference layer, with loopback-only serving, configurable port/API path (defaults `8080` and `/v1`), optional API-key enforcement, and tunnels disabled.
- Add same-phone provider profiles with a persisted editable endpoint (initially `http://127.0.0.1:8080/v1`) and optional securely stored API keys, prompt-only JSON, compatible persistence/transfer migrations, and serial inference to this app while preserving the built-in on-device default, remote opt-in, and review/export validation. Omit authentication when no client key is configured; send a matching Bearer key when companion authentication is enabled.
- ✅ Done (2026-10-10): Updated the goal for Hugo's confirmed same-phone-only configurable endpoint and optional keys in both apps; expanded planned endpoint/authentication tests and requirements alignment. Implementation remains pending.
- Implement and verify the aligned requirements (`REQ-M-031..035`, `AC-052..060`), complete unit/integration/device tests and both Android builds, then verify real vision-model accuracy, latency, memory use, and companion lifecycle on a physical phone. Add concise README setup instructions during implementation and keep remaining device validation explicit.

## Gap 13: Multiple Images and No-Image Input

Status: ✅ Done — plan completion and physical-device verification on Pixel 7 confirmed by Hugo on 2026-10-08, including the free-text, dedicated prompt/Export All, and no-image item-action revisions. This supersedes pending physical-device checks in the historical entries below. Individual device scenarios and provider/model details were not recorded. Live provider interpretation/benchmarks, maximum-batch memory profiling, and production Windows processor rollout remain separate follow-ups.

- ✅ Done (2026-10-07): Implemented the dedicated no-image narrative prompt/shared receipt format and **Export All** JSON-list publication (`REQ-A-027..029`, `REQ-H-009`, `REQ-W-014`, `AC-048..050`). Hugo requires all remaining items to be ready; incomplete/failed/stale/invalid inputs block export. Mixed lists preserve ordered expense identities/notes, image-first publication and Room v4 immutable membership. Serialized draft checkpoints and durable export reconciliation close process-death recovery gaps. Windows whole-array validation, durable staging, quarantine and replay/collision protection preserve existing member processing. All 60 shared tests, 210 Android unit tests, 28 emulator UI tests, debug assembly, 51 batch assertions, 22 typed assertions, fixtures and 117 copied-workbook assertions pass. Independent review/diff checks and documentation updates complete. Voice capture, live provider interpretation, physical-device checks and production processor rollout remain pending; no production workbook/job changed.

Original planning and implementation status: ✅ Done on 2026-10-05. Free-text revision: implementation and automated verification ✅ Done on 2026-10-06. Live provider interpretation, physical-device checks/hardware measurements, and production rollout remain pending.

- Plan: [Multiple images and no-image input support](9.multiple-and-no-image-support-plan.md).
- ✅ Done (2026-10-08): Added item-level **Extract / Retry extraction** and visible **Confirm and export** to no-image cards (`REQ-A-030`, `AC-051`). Initial drafts cannot export; current validated reviews require OneDrive readiness. Shared scheduling/provider snapshots, empty/busy guards, edit replacement confirmation, failed-retry preservation and saved export retry identities are covered by six new Compose unit regressions and one workflow regression. All 60 shared tests, 217 Android unit tests and debug assembly pass; emulator evidence is in the batch plan. Requirements/PLAN/status/README updated; physical-device validation remains pending.
- ✅ Done: Clarified free-form entry on 2026-10-06: source labels are optional hints, any field supplies any expense fact, and Merchant or Notes may hold the entire expense with other fields blank. Only an empty combined draft/default-only CAD blocks inference. Add visible well-known merchant typo suggestions requiring acceptance, preserve original notes, and resolve conflicting explicit facts rather than choosing by label. This supersedes the per-field nonblank rule below (`REQ-A-022`, `REQ-A-025`, `AC-043`, `AC-047`).
- ✅ Done: Revised no-image source requirements on 2026-10-05: all fields are free-text hints, required fields need only be nonblank before inference, and the LLM extracts normalized amount/date/currency. Examples: `15+50` → numeric 65, `Oct 7 2026` → `2026-10-07`, `Canadian dollars` → `CAD`. Original strings remain intact; structured result/export validation and explicit confirmation remain required (`REQ-A-022`, `REQ-A-025`, `AC-043`, `AC-047`).
- ✅ Done: Implemented combined-draft eligibility, unchanged source currency/text/date entry, cross-field typed extraction, reviewable suggestions, normalized LLM results, exact original notes, unresolved-output rejection, and structured manual/export validation on 2026-10-06. All 59 shared tests, 188 Android unit tests, 27 Pixel 8a Android 15/API 35 UI tests, and debug assembly pass with zero failures/errors/skips. New tests cover raw requests/recovery, arithmetic/dates/currencies in any field, single-field drafts/blank dedicated fields, conflicts/defaults, strict output validation, failure/retry/source revisions, Room reopening, manual correction, and normalized image-free Version 3 export. Source originals and suggestions stay visible until explicit confirmation. Extraction/handoff fixtures and 22 typed-contract assertions pass; independent review, README/status updates and `git diff --check` pass. Live provider interpretation and physical-device checks remain pending. The older verification counts below describe the original implementation.
- Prepare multiple selected images without inference, inspect exact processed images, remove through thumbnail (×) or review-screen Remove, append with Add more, and explicitly start Extract all.
- Use stable collapsible input groups, preserving existing editable multi-transaction outputs and independent export identities; guard out-of-order results, removal, retry, cancellation, and process restoration.
- Add an unchecked-by-default item-level No receipt image checkbox with merchant, amount, CAD-default currency, date, and optional notes. Support mixed batches and text-only LLM review with visible suggestions and manual fallback.
- ✅ Done: Scheduling enforces a hard ceiling of three in-flight item requests, serial on-device inference, default remote concurrency one and 3.5-second start spacing, shared rate/recovery/verification gates, bounded retries and actual I/O cancellation/drain. Settings permit concurrency 1–3; hardware tuning is pending.
- Extend Android persistence/export and the Windows validator/Excel processor for explicit Version 3 typed/no-image handoffs, retaining Version 2 image-first guarantees. Deploy processor compatibility before enabling typed exports.
- ✅ Done: Stable IDs/source revisions, private staged image bytes and thumbnails, typed/image mode preservation, review edits, independent multi-transaction exports, immutable retry identities, Room migrations, lifecycle cancellation and interrupted-run restoration have unit coverage. Picker cancellation, duplicate URI and capacity failures are visible; preparation failures cannot dispatch inference.
- ✅ Done: All 54 shared JVM tests, 181 Android unit tests and 22 Pixel 8a Android 15/API 35 UI tests pass with zero failures/errors/skips. Debug assembly succeeds. Device tests exercise typed creation/CAD defaults, source/collapse preservation, retry confirmation, separate thumbnail/viewer removal and lazy full-image loading, alongside existing navigation, settings, insets, image viewer and profile regressions.
- ✅ Done: Extraction fixtures (2 valid/2 invalid), handoff fixtures (5 valid/7 invalid), 22 typed-contract assertions and 117 Excel assertions plus disposable-workbook COM canaries pass. Mixed image/typed entries preserve F/G mapping, empty typed H, image hyperlinks, notes/archive, quarantine and idempotency. Summary coverage lookup tolerates the source workbook's stale extra coverage row; month insertion remains tested. The production workbook and scheduler were not changed.
- ✅ Done: Requirements, handoff contract, PLAN, README and implementation plan record actual behavior and verification. See new `REQ-A-021..026`, `REQ-H-008`, `AC-040..046` and revised image/export requirements.
- Pending: Benchmark a fixed receipt set on the on-device model and LM Studio at concurrency 1/2/3, profile maximum-batch memory on a physical device, and deploy Windows compatibility before installing the new APK in production. Current policy defaults are conservative, not hardware-certified throughput claims.

## Gap 12: Profile Duplication and Provider Model Discovery

Planning and implementation status: ✅ Done on 2026-10-04. Independent review, 38 shared JVM tests, 149 Android unit tests, all 18 Pixel 8a API 35 device tests, and debug assembly pass. Physical-device validation: ✅ Done on Pixel 7, confirmed by Hugo on 2026-10-04. Live OpenRouter validation remains pending.

- Emulator capability check: ✅ Done on 2026-10-04. Existing Pixel 8a Android 15/API 35 AVD boots with WHPX; current debug assembly and both profile/Settings Compose device tests pass (2 tests, no failures/errors/skips). ADB installation, normal launch, Settings navigation, UI hierarchy capture, and screenshot inspection also succeeded. This is current-app testing capability, not feature validation. Run implemented-feature tests on this emulator before Hugo's physical-device verification; see the plan's capability evidence and command.
- Plan: [Duplicate profile and provider model selector](8.duplicate-profile-and-model-selector-plan.md).
- ✅ Done: Corrected the outdated APK on Hugo's visible Pixel 8a emulator on 2026-10-04. Rebuilt, reran the 38 shared/149 Android unit tests, and installed with `adb install -r` to preserve data. Installed/build checksums match; screenshots and UI hierarchies confirm Duplicate profile and Load models, with the original profile and remote opt-in preserved. README now clarifies that opening an emulator does not update its installed app.
- ✅ Done: Settings duplication copies all saved fields and the credential with exact ` (copy)` naming, independent IDs/aliases, durable key-first writes and transactional conflict-abort insertion. Mutation guards, compensation and uncertain-commit reference checks protect existing profiles and selector state.
- ✅ Done: Explicit compatible model loading/search preserves manual input, exact IDs and draft-only selection; separate transient statuses and token/credential/editor generations prevent stale results across changes, overlapping requests, editor close and lifecycle pause/stop/disposal.
- Native-provider response/auth/pagination adapters are deferred by Hugo's confirmed compatible scope; native inference requires separate provider work.
- ✅ Done: Service, credential/persistence failures, real Room/encrypted-store reopen, HTTP/parser, ViewModel, Compose lifecycle/navigation and existing regressions pass. Checked `REQ-M-029..030`, `REQ-UI-013`, `AC-038..039` and the plan's existing-requirements mapping.
- ✅ Done: Direct emulator UI checking duplicated the configured LM Studio profile while remote providers were disabled, loaded 17 live models, searched with the keyboard visible, saved only the copy's new model and confirmed independence after app restart. Live OpenRouter validation remains pending.
- ✅ Done: Physical-device validation on Pixel 7, confirmed by Hugo on 2026-10-04. Individual device scenarios and provider-specific results were not recorded.
- ✅ Done: README contains a concise feature note; requirements, current status and the plan record results. A process death between encrypted key storage and Room insertion can leave an unreferenced alias; no broad cleanup is added. Preimplementation capability evidence above remains historical.

## Gap 11: Android System-Bar Layout

Status: ✅ Done — implementation, unit verification, device UI test compilation, and debug assembly completed on 2026-10-01. Physical-device validation: ✅ Done, confirmed by Hugo on 2026-10-01.

- Reserve `WindowInsets.safeDrawing` outside the shared Main/Settings scroll viewport and consume system spacing once, retaining the existing 24 dp content margins.
- Enable consistent edge-to-edge window behavior and `adjustResize` for keyboard inset delivery.
- Four Robolectric Compose unit tests verify a fully visible, clickable export button above gesture/three-button bars with long Debug output, changes to keyboard space, and nested side-inset consumption.
- All 121 Android unit tests pass, shared JVM tests remain passing/up to date, device UI tests compile, and the debug APK assembles. Checked `REQ-UI-012`, `AC-037`, and existing navigation, Debug, and export behavior.

## Gap 10: Retry Extraction for the Selected Image

Status: ✅ Done — implementation, unit verification, Compose test compilation, and debug assembly completed on 2026-10-01. Physical-device validation: ✅ Done on Pixel 7, confirmed by Hugo on 2026-10-01; individual test scenarios were not recorded. Compose execution remains pending.

- Added Main's accessible Retry extraction action, enabled after image loading and disabled during receipt or provider configuration work.
- Reuse the processed image and source URI with the current effective provider snapshot; retain preview and replace reviews/debug/export previews without changing persisted export records.
- Cover failures/recovery, repeated taps, provider changes/local fallback, review replacement, new selections, and busy guards with unit and Compose tests.
- Plan: [Main screen extraction retry](7.retry-extraction-plan.md). Checked against `REQ-UI-011`, `AC-036`, existing privacy/fallback, image review, navigation state, and export retry requirements.
- ✅ Done: All 117 Android unit tests pass, including four new retry regressions; the 38 shared JVM tests remain passing/up to date. Compose tests compile and the debug APK assembles.

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
- ✅ Done: Documented how to carry the registered debug keystore to a new PC, and how to replace its Entra registration if the original key is unavailable.
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

## Gap 9: Profile-Gated Tailscale Access to LM Studio

Live status extension verification: ✅ Done — all 113 Android unit tests, shared tests, ten affected Compose tests on the Pixel 8a API 35 emulator, and debug assembly pass on 2026-09-30. Physical-device validation: ✅ Done on Pixel 7, confirmed by Hugo on 2026-09-30.

Status: ✅ Done — app implementation, unit tests, nine affected Compose tests on the Pixel 8a emulator, and debug assembly pass on 2026-09-30. Physical command/consent/reconnect verification remains pending.

- ✅ Done: Verified LM Studio served through Tailscale Serve using `https://<MINI_PC_NAME>.<TAILNET_NAME>.ts.net/v1` as the saved profile `baseUrl`, without `:1234`, while LM Studio **Allow local network access** was enabled.
- ✅ Done: Confirmed the endpoint was reachable from the Pixel 7 on both local and external networks.
- ✅ Done: Persist the default-off capability per profile with Room v2 migration/schema; expose it only in the remote-profile editor; emit provider-configuration v2 and accept v1 imports with false defaults, preserving atomicity, conflict copies, credentials exclusion and selection.
- ✅ Done: Gate Main visibility and actions on the current effective eligible saved profile. Dispatch compatible package-targeted broadcasts, show request/endpoint status without asserting VPN state, and provide recovery for unavailable Tailscale or endpoints.
- ✅ Done: Use the reusable models endpoint GET checker with immutable saved URLs and credentials, 10-second attempt deadlines, 2-second connect retry intervals and a 30-second connect deadline. Keep model-specific provider inference verification separate. Abort stale/cancelled HTTP probes and preserve independent extraction.
- ✅ Done: Add independent app-visible VPN detection and periodic foreground models endpoint observations (`REQ-M-028`, `REQ-UI-010`, `AC-035`). Support custom checker URL/key/path/timeouts and model-ID results; cover empty lists, invalid responses, HTTP failures, redirection, response limits and cancellation. Refresh on resume/VPN changes and 15 seconds after completed checks; pause/profile changes stop stale work. All 113 Android unit tests, shared tests, UI-test compilation and debug assembly pass on 2026-09-30.
- ✅ Done: Unit tests cover flag propagation, editor persistence, v1/v2 transfer and conflict copying, real Room migration, action gating, retries/timeouts, cancellation, saved credentials and receipt-free probes. All 38 shared JVM tests, 104 Android unit tests, nine affected Compose tests and debug assembly pass. Corrected existing UI test text matching and invalid PNG fixtures, and use a synchronized Espresso Back action with bounded waits. Checked `REQ-M-026..027`, `REQ-UI-009`, `AC-034` and existing profile/privacy/transfer/navigation requirements.
- ✅ Done: Hugo confirmed physical-device validation on Pixel 7 on 2026-09-30. Individual scenario results and the installed Tailscale version were not recorded; see the validation checklist in [the Tailscale integration goal](6.tailscale-integration-goal.md).

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
