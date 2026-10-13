# Smart Expense Tracking Requirements

Last updated: 2026-10-12

✅ Done (2026-10-12): Recorded Hugo's confirmation that **Verify, receipt-photo extraction and typed-expense extraction worked on Pixel 7** for plans 10a/10b; see [companion confirmation](10a.mobile-server-companion-verification.md#phone-verification-confirmation) and [expense-app confirmation](10b.mobile-server-expense-app-verification.md#phone-verification-confirmation). This supersedes earlier blanket phone-verification/paired-photo Pending statements. Offline use, cancellation/retry/native drain, accuracy/latency/memory and background acceptance under `REQ-M-007/035`, `AC-057..060` remain unconfirmed. Requirements and existing automated evidence are unchanged.

✅ Done (2026-10-12): Checked provider verification and readable UI corrections against `REQ-M-011..014`, `REQ-UI-002/006`. **87 shared/252 Android unit tests**, debug assembly and Android-test compilation pass. Prompt-only connectivity checks accept one complete JSON code block while retaining exact object validation and secret-safe errors; receipt/export validation is unchanged. See [evidence and phone-retest limits](10b.mobile-server-expense-app-verification.md#provider-output-and-ui-text-follow-up).

Three-category provider requirements alignment: ✅ Done on 2026-10-10. Built-in on-device, Same-phone server, and Remote API are defined below in accordance with [the mobile-server integration goal](10.mobile-server-integration-goal.md). Companion and expense-app software/client automated verification are complete; native-drain/paired loaded-model/physical acceptance remains pending. See the 2026-10-11 verification record below.

✅ Done (2026-10-11): [10b expense-app software verification](10b.mobile-server-expense-app-verification.md): **79 shared tests**, **244 Android unit tests**, debug assembly/Android-test compilation and **40 full Pixel 8a emulator tests** pass with zero failures/errors/skips. GPT 6 Astra review/re-review completed; primary agent independently checked and fixed valid findings. Client coverage addresses `REQ-M-001..005/008..034`, `REQ-A-019/023..029`, `REQ-H-008..009`, `REQ-UI-001..006/009..013`, `AC-052..056` and client portions of `AC-057..059`: category/consent/Tailscale, Room/transfer compatibility, optional-key secure journal/recovery/snapshots, strict bounded Prompt-only loopback HTTP, deadline/confirmed control/quarantine and unchanged review/export. Existing regressions establish preserved contracts, not native model quality. `REQ-M-006..007/035`, full cross-app `AC-057..059` and physical/offline `AC-060` remain open for the supported companion/native/physical evidence specified in E5/E8 and 10a.

Dedicated narrative prompt and Export All revision: implementation and automated verification ✅ Done on 2026-10-07 for `REQ-A-027..029`, `REQ-H-009`, `REQ-W-014`, and `AC-048..050`. All 60 shared tests, 210 Android unit tests, 28 emulator UI tests, debug assembly, 51 Windows batch assertions, 22 typed assertions, existing fixtures, and 117 copied-workbook assertions pass. Live provider interpretation, physical-device validation and production rollout remain pending.

Free-text no-image revision: implementation and automated verification ✅ Done on 2026-10-06 for `REQ-A-022`, `REQ-A-025`, `AC-043`, and `AC-047`: 59 shared tests, 188 Android unit tests, 27 emulator UI tests, and debug assembly pass. Live provider interpretation/physical-device checks remain pending; see the batch plan verification record.

## Purpose

Build a private-first expense capture workflow where an Android phone extracts order data from receipt photos and hands that data to a local Windows automation agent, which appends the expense to an existing Excel workbook.

## Feasibility Summary

Overall feasibility: feasible for an MVP, with a few integration details to resolve before implementation.

Physical endpoint validation: LM Studio served through Tailscale Serve was reachable from a physical Pixel 7 using `https://<MINI_PC_NAME>.<TAILNET_NAME>.ts.net/v1` as the profile base URL, without the LM Studio `:1234` port, on local and external networks when LM Studio's **Allow local network access** was enabled. Hugo reports successful app-driven Tailscale connect/disconnect. Physical-device validation: ✅ Done on Pixel 7, confirmed by Hugo on 2026-09-30. See [the Tailscale integration goal](6.tailscale-integration-goal.md).

High-confidence parts:
- Android can capture a receipt photo or let the user select one from local/cloud-backed media.
- Android can upload a normalized receipt JPEG followed by a structured handoff file to OneDrive through Microsoft Graph.
- A Windows agent can poll the synced OneDrive folder every 5 minutes.
- The Windows side can update the local synced workbook using a PowerShell action, with Excel automation or Graph-based workbook APIs as candidate implementation mechanisms.

Main risks:
- Google AI Edge APIs and LiteRT-LM provide an Android integration path, but the exact receipt-image extraction approach still needs a spike because LiteRT-LM model capability, image input support, prompt format, and device performance must be verified.
- Remote API profiles are feasible as user-enabled extraction providers, but add network dependency, API key handling, cost/rate-limit behavior, and privacy disclosure requirements. Same-phone server uses the compatible protocol without external inference transmission, but adds companion/model lifecycle and device-memory dependencies.
- Uploading a receipt image and its JSON as one recoverable export requires deterministic naming, idempotent retries, and image-first publication so Hermes never processes a JSON whose image is missing.

## System Context

The system has three main components:

1. Android app
   - Captures or imports receipt images.
   - Runs extraction through the selected Built-in on-device, Same-phone server, or Remote API provider. The optional same-phone companion is a separate runtime on the Android phone under REQ-M-006/031..035.
   - Provides Settings for choosing the extraction provider and model.
   - Lets the user review/edit extracted fields.
   - Uploads a normalized receipt JPEG and its handoff file into OneDrive using Microsoft Graph.

2. OneDrive expense folders
   - Receives singleton expense JSON or an Export All JSON list, with one normalized receipt JPEG per image-backed expense and no image for typed expenses.
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

REQ-A-006: Individual confirmation shall write one structured handoff file per expense. Export All shall write one JSON array containing all remaining confirmed expense objects as specified by REQ-A-027; each object retains its own expense identity and source contract.

REQ-A-007: The app shall include a globally unique expense id in each expense object, whether published individually or as a combined-list member.

REQ-A-008: The app shall write handoff files to a user-configured OneDrive folder.

REQ-A-009: The app shall preserve a local record of exported expenses and their export status.

REQ-A-010: The app shall authenticate with Microsoft Graph to upload handoff files to OneDrive.

REQ-A-011: When exporting a confirmed image-backed expense, the app shall generate and upload a normalized JPEG copy of the processed receipt image, strictly below 200 KB (204,800 bytes), regardless of whether extraction-time resizing was disabled or unnecessary. Typed-origin expenses shall export explicit image-free Version 3 records without image preparation or upload.

REQ-A-012: For image-backed expenses, the app shall upload receipt images under the OneDrive-root-relative folder `Documents/2_Others/Expenses_finance/receipt_images/YYYY-MM`, where `YYYY-MM` is derived from the export timestamp in the Android device's local timezone.

REQ-A-013: Image-backed receipt file names shall use `YYYYMMDD_HHmmss_receipt_<shortExpenseId>.jpg`, where the timestamp is the same export timestamp used for the JSON handoff name and `<shortExpenseId>` follows the handoff naming rule. Typed exports have no image filename.

REQ-A-014: Version 2 handoff JSON shall include the required OneDrive-root-relative string `receiptImageRelativePath`, using forward slashes and identifying the uploaded JPEG. Typed Version 3 shall require `inputSource: "typed"`, boolean `hasReceiptImage: false`, and explicit `receiptImageRelativePath: null`; other image fields shall be null or absent.

REQ-A-015: For Version 2 the app shall complete image upload before publishing the final `.json`; image failure shall prevent final publication and retain actionable retry state. Version 3 shall skip image operations and publish temporary JSON followed by the same final-name commit.

REQ-A-016: Retrying an interrupted export shall reuse the expense ID, export timestamp, immutable reviewed payload, JSON name, and image path when present so retries do not create duplicate receipt images or handoffs.

REQ-A-017: Before image upload, the app shall idempotently resolve or create `receipt_images/YYYY-MM`; failure shall preserve retry state without final JSON. Typed exports shall make no image-folder call.

REQ-A-018: When a user confirms an unchanged image extraction, exported status shall be `confirmed`, including low-confidence extraction. Any review edit shall make status `manual`. Typed-origin expenses shall always use `manual`, regardless of LLM confidence. Retry shall preserve the saved status.

REQ-A-019: When one image contains distinct receipt transactions, the app shall review them separately and retain one Version 2 object and paired normalized image per confirmed transaction. Individual export publishes separate JSON files; Export All places the objects in one array. Each transaction shall retain its own `confirmed` or `manual` status and retry identity.

REQ-A-020: The app shall display a detected device name in Settings, allow a persisted manual override, and include the effective name as `sourceDeviceName` in every new handoff JSON while retaining the stable `sourceDeviceId`.

REQ-A-021: Main shall accept a mixed batch of up to 20 ordered, independently identified image/typed inputs. Multiselect and Add more shall prepare previews without inference, skip duplicate URIs with a message, enforce capacity after picker return, and preserve existing inputs on cancellation.

REQ-A-022: Each item shall default to image input with an unchecked No receipt image checkbox. All no-image source fields (merchant, amount, currency default CAD, date, and notes) shall be optional free-text hints for LLM extraction, with no input data-type, format, or per-field presence requirements. Labels shall suggest what to enter without restricting the information accepted. A user may enter the full expense in any single field, including Merchant or Notes, and leave every other field blank. Only an empty/whitespace-only combined draft, including the untouched CAD default alone, shall block submission. Numeric-only keyboards, amount/currency format checks, and fixed date patterns shall not prevent entering or submitting text. An optional date picker shall not restrict typed dates. Switching modes shall preserve the original draft strings and detach inactive image evidence; source changes shall invalidate unexported results.

REQ-A-023: Extract all shall process only eligible new/changed inputs in a single asynchronous run, snapshotting provider/policy/input revisions. Enforce at most three item requests, Built-in on-device concurrency one, Same-phone server concurrency one, and configurable initial Remote API start spacing 3.5 seconds across inference/recovery/retries. Remote start spacing shall apply only to Remote API requests. Same-phone extraction shall use a 180-second overall deadline and a corresponding HTTP read timeout; cancellation/timeout shall abort the client request and stop companion generation as required by REQ-M-035. Failed items require explicit retry; no automatic export follows extraction.

REQ-A-024: Stable item/transaction/revision identities shall isolate out-of-order completions, edits, retry, and export. Removal/Cancel remaining/backgrounding shall stop pending dispatch and cancel/drain active work without late publication. Remote consent revocation shall stop pending and active Remote API work without cancelling Built-in on-device or Same-phone server work solely because consent changed. Completed results shall survive cancellation.

REQ-A-025: Typed extraction shall send all original labeled field strings, including blanks, as data in a text-only LLM request on Extract all, item-level Extract, or explicit Retry extraction, with no image/OCR operations or local source-format parsing. The LLM shall extract exactly one structured expense using the combined information from every field, including Notes, regardless of labels; a blank dedicated field shall not mean the corresponding fact is absent from the draft. Evaluate explicitly supplied arithmetic (for example, `15+50` or `15+50, which is 65 in total` to numeric `totalAmount: 65`), normalize unambiguous dates (for example, `Oct 7 2026` to `receiptDate: "2026-10-07"`) and currency descriptions (for example, `Canadian dollars` to `CAD`) wherever they appear. Use CAD as a default only when no explicit currency is supplied anywhere; conflicting explicit facts shall require user resolution rather than field-label precedence. For a recognizable well-known merchant, the LLM may suggest a typo correction (for example, `Walmrat` to `Walmart`) with the original and suggestion visible for user acceptance; ambiguous or unfamiliar names shall not be replaced with a guessed brand. After extraction, the app shall deterministically validate a nonblank merchant, finite non-negative decimal amount, uppercase three-letter currency code, and real ISO date before export. Preserve source fields/notes separately, including facts extracted from Notes, show normalization/corrections for explicit confirmation, and retain drafts on failure with retry or validated structured manual fallback. Contradictory amounts, ambiguous dates/currencies, impossible dates, and missing facts shall require user resolution; never invent expense facts, add unstated tax/tip, or convert currencies. Source free-text acceptance shall not weaken result/export validation.

REQ-A-026: Room batch metadata/private staged files shall restore recoverable drafts, edited results, order and export identities after process death, expose interrupted work as retryable without resubmission, and clean only unreferenced preparation files. Export-started payloads remain immutable across removal/retry.

REQ-A-027: Main shall offer Export All after extraction/review. Every remaining input must have current successful results and every remaining transaction must pass structured export validation; incomplete, failed, empty, and stale inputs shall block the action with actionable guidance. Already completed exports shall be excluded. Choosing Export All explicitly confirms all remaining reviewed transactions in input/transaction order and publishes one JSON array to OneDrive, preserving each expense ID, source schema, exact notes, and image correlation. Prevent simultaneous extraction/export and duplicate taps. Individual transaction export shall remain available.

REQ-A-028: Combined export shall atomically persist its immutable ordered membership, member snapshots, timestamp and deterministic list path before publication. Upload every referenced receipt JPEG before temporary/final JSON-list commit; a failed member image shall prevent list publication. Retry/restart shall reuse the original list and member IDs/paths, repair interrupted completion state, and never publish members individually. Retry the pending original export before combining additional transactions; published transactions remain immutable.

REQ-A-029: No-image extraction shall use a dedicated text-only prompt separate from receipt-image and OCR prompts. It shall receive all five original source strings together, including blanks, accept natural expense narratives suitable for future voice transcription, and return the same normalized `receipts` array/core expense fields and validation used by image extraction. Typed review metadata may add issues/corrections without changing the expense format. Microphone capture and speech transcription are future work outside this revision.

REQ-A-030: Each expanded no-image item shall show an item-level Extract action before its first attempt and Retry extraction after an attempt or when replacing existing results. It shall submit only that item's original draft through the shared provider snapshot and scheduler. Empty/default-only drafts, provider configuration/verification, image preparation, extraction and export shall disable the action. Edited reviews require replacement confirmation; failed retries preserve drafts/old reviews but block their export. Export-started expenses remain immutable. Confirm and export shall be visible but disabled before results exist; afterward it requires a current successful result, valid structured fields and ready OneDrive access. It shall confirm only that transaction, prevent duplicate taps and retain Retry export/Exported states for saved exports. Extraction shall never export automatically.

### Model Provider and Extraction

Provider categories used throughout these requirements:

- **Built-in on-device:** inference inside the expense app through Google AI Edge/LiteRT-LM; the default and deterministic fallback.
- **Same-phone server:** a separately installed, explicitly selected supported mobile-server companion fork that performs inference on the same phone through a loopback-only OpenAI-compatible HTTP endpoint.
- **Remote API:** a saved OpenAI-compatible profile whose inference service is outside the phone, including LAN, Tailscale, and cloud services; receipt transmission requires remote opt-in.

OpenAI-compatible describes the API protocol, not whether inference is remote. Saved API profiles comprise Same-phone server and Remote API profiles; the Built-in on-device provider does not require an API profile. A legacy profile remains Remote API on migration/import regardless of its URL; it shall not acquire same-phone eligibility through URL inference. Same-phone classification is valid only under REQ-M-031.

REQ-M-001: The extraction implementation shall default to the Built-in on-device provider. Creating, saving, importing, duplicating, or configuring an API profile shall not automatically select it.

REQ-M-002: The extraction prompt/output contract shall require structured data, not free-form prose.

REQ-M-003: The app shall validate model output before allowing export.

REQ-M-003A: The extraction response shall represent distinct transactions as elements in a `receipts` array. Matching itemized and finalized documents for one transaction shall produce one element using the finalized charged amount; uncertain values shall use `low_confidence`.

REQ-M-004: If model extraction fails or confidence is low, the app shall allow manual entry.

REQ-M-005: The Built-in on-device provider shall use Google AI Edge APIs or Google AI Edge LiteRT-LM directly in the Android expense app and remain the default. The optional Same-phone server provider shall use the separately installed supported companion fork defined by REQ-M-006 and REQ-M-031..035.

REQ-M-006: AI Edge Gallery shall remain a sample/reference implementation for the Built-in on-device provider. As an explicit companion-runtime exception, the project shall support a separately maintained mobile-server fork based on revision `a0ca94a9f79f1e5dbea0cb315f565f5512cae6b7`, preserving its license and attribution and documenting the supported HTTP contract. Unmodified Gallery/mobile-server compatibility shall not be assumed. Companion startup shall remain user-controlled; the expense app shall not embed or automatically start the server, download models automatically, or enable tunnels.

REQ-M-007: The Built-in on-device spike shall verify direct receipt-image support or the need for OCR/preprocessing. Same-phone server validation shall separately prove direct receipt-image and typed-text extraction with the selected provisioned vision model, including vision-backend initialization, offline operation, accuracy, latency, memory pressure, and companion background behavior on a physical phone. OCR is outside the same-phone integration; HTTP compatibility alone shall not establish model quality.

REQ-M-008: The Android app shall support Built-in on-device, Same-phone server, and Remote API providers behind one extraction abstraction, with saved OpenAI-compatible profiles for both API categories.

REQ-M-009: Remote API access shall be disabled by default and require explicit configuration and user opt-in through Settings before receipt images, OCR text, typed drafts, or extracted data are sent outside the phone for inference. A configured Same-phone server profile shall be selectable independently of remote opt-in and require explicit selection before expense extraction; configuring it shall not enable remote access or change selection.

REQ-M-010: The Android app shall include a Settings section with a Model Selector that allows the user to choose:
- provider category: Built-in on-device, Same-phone server, or Remote API
- provider display name
- base URL for OpenAI-compatible providers
- model id/name
- whether the selected provider accepts image input directly or requires OCR text input
- structured-output mode: JSON Schema, JSON Object, or Prompt-only JSON for API profiles; Same-phone server shall use direct-image input for photos and string content for typed drafts, without OCR

REQ-M-011: The expense app shall store any configured API-profile key using Android secure credential storage, not plain preferences or logs. Same-phone keys shall be optional as defined by REQ-M-032; existing Remote API credential requirements shall remain unchanged. Keys shall remain redacted from errors, logs, Debug output, and persisted UI state even when Debug is enabled.

REQ-M-012: The app shall allow the user to test a configured OpenAI-compatible provider before selecting it for receipt extraction.

REQ-M-013: All three provider categories and all API output modes shall use the same normalized structured output contract, parser, validation, review, and export gates. Prompt-only JSON shall not weaken structured result validation.

REQ-M-014: The app shall surface provider failures, rate limits, authentication failures, and network unavailability with a clear recovery path and manual-entry fallback. Same-phone failures shall distinguish stopped/unreachable servers, missing or non-vision models, busy inference, missing/mismatched keys, timeout, and invalid output. Outages shall retain the selected profile, drafts, and recoverable review state for explicit retry or manual entry; they shall not automatically dispatch to another provider.

REQ-M-015: The app shall allow the user to create, view, edit, test, save, select, and delete multiple named OpenAI-compatible provider profiles.

REQ-M-016: Each saved API profile shall have a stable unique ID and shall persist its category, display name, base URL, model ID, input mode, and structured-output mode across app restarts. Same-phone endpoint persistence and validation shall follow REQ-M-031.

REQ-M-017: The app shall persist the selected profile ID and visibly identify the provider that will be used for the next extraction.

REQ-M-018: Each saved API profile shall use a distinct secure credential alias for any stored key, with independent replacement, explicit clearing, and deletion. An alias shall not imply that a key is configured. API-key values shall not be stored in the profile database, plain preferences, logs, Debug output, or UI state intended for persistence; immutable request snapshots shall retain only the effective credential needed for that request and shall not persist it in batch state.

REQ-M-019: Deleting the selected API profile or encountering a missing selected profile shall select the Built-in on-device provider without dispatching expense data to another API provider. Disabling remote access shall select Built-in on-device only when a Remote API profile is selected; an existing Same-phone server or Built-in on-device selection shall remain unchanged. Fallback shall not automatically initiate extraction, and built-in model unavailability shall offer model setup/manual recovery rather than another API selection.

REQ-M-020: Migrating from the single-profile settings format shall preserve a valid existing configuration and credential without creating duplicate profiles on subsequent starts.

REQ-M-021: The app shall display the bundled LM Studio Gemma profile alongside user-created profiles on clean installs and upgrades, without automatically selecting it, overwriting edits, or creating an equivalent duplicate profile.

REQ-M-022: When one input image or OCR text contains multiple receipt documents that clearly represent the same transaction, the extraction prompt shall produce one expense and set `totalAmount` to the finalized amount charged or payable, including tax and tip. The extraction shall not add together totals from those documents. It shall use `low_confidence` when the documents may represent different transactions or the finalized amount cannot be identified reliably.

REQ-M-023: The app shall export all saved Same-phone server and Remote API profiles and the current selector snapshot to a versioned JSON file chosen through Android's system document picker. Export shall include category, endpoint, model, input mode, output mode, and applicable profile capabilities as metadata only and shall never contain either app's API keys, credential aliases, or database timestamps.

REQ-M-024: The app shall validate and preview a provider-configuration JSON file before importing it, including category-specific endpoint and capability validation. Import shall be atomic, preserve non-conflicting profile IDs, save conflicting IDs as new copies, generate local credential aliases, import no credentials, and leave the device's current provider selection and remote-provider opt-in unchanged. A credential-free Same-phone server profile shall be valid for import; if the companion requires a key, subsequent verification/extraction shall request authentication recovery rather than reject the metadata import.

REQ-M-025: Provider-configuration export and import shall use Android's Storage Access Framework without broad storage permissions, allowing the user to choose Downloads or another document-provider location. Cancellation, invalid files, and document read/write failures shall not change saved provider profiles and shall provide a clear recovery message where applicable.

REQ-M-026: Remote profiles shall persist an independently editable, default-false `showTailscaleToggle` capability. Room v1-to-v2 migration shall preserve profile metadata, credentials, selection and remote opt-in while defaulting the flag to false. Provider-configuration v2 shall round-trip the flag without credentials, retain validated/previewed atomic imports and conflict copying, and accept v1 imports with the flag defaulting to false.

The v1/v2 rules above are retained compatibility requirements; current category/output-mode migration shall follow REQ-M-034. Same-phone server profiles shall have no enabled Tailscale capability, and saves/imports with that category and `showTailscaleToggle: true` shall be rejected.

REQ-M-027: Tailscale commands shall require the current effective selected remote profile to exist and explicitly permit control. Connect/disconnect shall dispatch only the supported package-targeted broadcasts to `com.tailscale.ipn`; no profile change, extraction, or capability edit shall dispatch either command automatically. Endpoint reachability shall use the reusable models endpoint check with the immutable saved-profile URL and saved credentials, without receipt images, receipt text or inference, with 10-second attempts, 2-second retries and a 30-second overall connect deadline. Changing the effective profile shall cancel stale operations. Missing/unsupported installations, dispatch failures and timeouts shall provide recovery guidance.

REQ-M-028: A reusable models endpoint checker shall accept caller-provided base URL, optional API key, relative models path (default `models`), and positive connect/read timeouts. It shall send only GET, return model IDs for a successful valid `data` array (including an empty array), and report invalid inputs, invalid responses, HTTP errors and network failures without exposing credentials or response bodies. Redirects shall not forward credentials. Response size shall be bounded to 1 MiB, and coroutine cancellation shall abort the underlying connection. This check shall be independent of Tailscale and profile persistence; the existing inference-based provider verification shall remain separate.

REQ-M-029: Duplicating a saved API profile in either category shall copy every provider setting, including category, endpoint and output mode, and any stored credential under a new independent ID/credential alias, append exactly ` (copy)` to the name, assign fresh timestamps, and preserve the source, selection, and opt-in. A credential-free profile shall remain credential-free. A failed copy shall not overwrite an existing profile or expose a key.

REQ-M-030: Explicit model discovery shall use the editor's endpoint/effective credential snapshot, normalize supported OpenAI-compatible model lists, send only discovery requests, cancel stale work, and retain manual model entry on failure or empty lists.

REQ-M-031: Same-phone profiles shall default to `http://127.0.0.1:8080/v1` and persist an editable listening port and API base path through their saved endpoint. The expense app shall require HTTP with the literal host `127.0.0.1`, a valid port and API path, and no user-info, query or fragment; DNS names, alternate loopback spellings, LAN and remote hosts shall be rejected for this category before saving/importing or requesting. The supported companion shall bind only `127.0.0.1`, keep tunnels disabled, persist configurable port/path with defaults `8080` and `/v1`, and display the active endpoint. The expense app shall narrowly permit cleartext for that literal host and reject same-phone redirects for discovery, verification and extraction. All requests shall use the saved or explicit editor snapshot's base URL. Endpoint/credential/category/model/output-mode edits shall cancel stale discovery/verification; saved changes shall affect subsequent extraction snapshots without mutating active extraction snapshots.

REQ-M-032: Both apps shall support an optional, independently configured same-phone API key. With no client key, the expense app shall omit Authorization entirely; with a key, it shall send `Authorization: Bearer <key>` for discovery, verification and extraction. With no server key, the companion shall allow unauthenticated requests; with a key, it shall require a matching Bearer key for discovery and inference and return HTTP 401 for absent/incorrect keys before inference. The companion shall store its key securely, support explicit clearing, and never expose it in logs/errors. The expense app shall apply REQ-M-011/018 and provide actionable missing/mismatched-key recovery. No placeholder credential shall be sent and neither app's key shall enter configuration transfer.

REQ-M-033: Saved API profiles shall support Prompt-only JSON, which omits `response_format` while retaining the existing extraction/verification JSON prompts and strict output validation. Verification shall parse and semantically validate the expected JSON object rather than require identical serialized whitespace/key ordering. JSON Schema and JSON Object modes shall retain their existing request contracts; companion profiles shall use Prompt-only JSON for the supported server contract.

REQ-M-034: The dedicated model-profile Room database shall migrate v2 to v3 and provider-configuration transfer shall advance v2 to v3 for category and output mode. Existing profiles and v1/v2 imports shall remain Remote API, preserve existing output modes and Tailscale settings (v1 flag defaults false), and never infer same-phone classification from an endpoint. Database migration shall preserve IDs, credentials, selection and opt-in without duplicate profiles; v3 transfer shall retain preview/atomic import/conflict-copy rules and credential exclusion, validate category/output mode, and never auto-select imported profiles. These versions are independent of the batch/export database and expense handoff versions, which shall not change for this integration.

REQ-M-035: The companion shall retain model discovery and non-streaming chat/completions under its configured API base path. It shall accept string messages for typed expenses and OpenAI-style text/image_url parts with inline base64 images for photos, without downloading remote image URLs. Decoded images and prompt text shall enter a vision-initialized inference engine; a non-vision model shall reject image requests clearly. Each expense extraction shall start independent conversation state. Inference shall be serialized across requests, unloading shall coordinate with active inference, and cancellation/client disconnect/timeout shall stop generation and release resources. Running state shall appear only after successful server startup, with model readiness shown separately.

REQ-UI-001: The Android app shall use separate Main and Settings destinations. Main shall contain only receipt-workflow controls plus Settings navigation chrome; Settings shall contain remote-provider opt-in, full profile management and transfer, and receipt-image preprocessing preferences, and shall not contain receipt import, review, or export controls.

REQ-UI-002: Main shall provide compact selection of Built-in on-device or an eligible saved Same-phone server/Remote API profile, show the effective category/provider for the next extraction, and verify the selected provider without opening its editor. Built-in verification shall check the installed model file. API verification shall use only saved metadata and the effective secure credential, including no key for credential-free same-phone profiles, and validate an inference response under the saved output mode. Models discovery/reachability shall remain separate and shall not imply inference readiness.

REQ-UI-003: Main shall show OneDrive readiness with only the contextual connect, reconnect, or verify action needed for export. Routine disconnect shall not appear in the receipt workflow, and export eligibility shall derive from the same readiness rule as the displayed status.

REQ-UI-004: Navigating between Main and Settings shall preserve receipt extraction, review, export, and retry state. Provider selection and image-preprocessing changes shall affect the next extraction and shall not mutate an operation already in flight.

REQ-UI-005: System Back and Up from Settings shall return to the existing Main destination. If a profile editor contains unsaved metadata or an unsaved API key, Back or Up shall require explicit discard confirmation before closing the editor.

REQ-UI-006: Settings shall provide a persisted, default-off Debug output checkbox. When enabled, Main shall show the raw extraction reply or provider/transport error in a read-only textbox after an extraction attempt, and the exact generated Version 2 handoff JSON in a read-only textbox for each attempted export. Failed uploads shall identify the JSON as not published. Disabling Debug shall hide these fields without changing review or retry state.

REQ-UI-007: Each prepared image input shall expose a thumbnail opening the complete processed image without cropping. Preview and extraction shall use identical staged bytes, with preprocessing frozen at preparation time. Removing or replacing one image shall update only that group's preview. Selection and review shall make no inference request.

REQ-UI-008: The full-screen selected-receipt image viewer shall support pinch-to-zoom and visible, accessible Zoom in and Zoom out controls. It shall open with the complete image fitted in view, allow panning while zoomed, keep zoom and pan within bounded limits, and reset the view when closed, reopened, or shown for a different image. Zooming shall affect display only; it shall not change the processed image supplied to extraction, review fields, or export data.

REQ-UI-009: Settings shall expose the per-profile Tailscale checkbox only in the Remote API profile editor. Main shall show an accessible control only for an eligible effective Remote API profile, identify its name and saved endpoint, and display command requests, VPN detection and models endpoint reachability separately. The switch shall represent the last requested command. VPN detection shall report only VPN networks visible to this app, and shall not identify their owner or assert Tailscale connection; endpoint reachability shall not assert successful inference. Restart/resume shall refresh observations without issuing commands; extraction shall remain available. Built-in on-device, Same-phone server, disabled, missing and disallowed profiles shall hide the control and reject callbacks.

REQ-UI-010: While the app is resumed and an eligible Remote API profile permitting Tailscale control is selected, it shall observe VPN changes and check the models endpoint automatically at entry, after VPN detection changes, and again 15 seconds after each completed check. Each passive check shall have a 10-second deadline; connect readiness shall retain its bounded retries. Pausing the app, changing/editing/removing the profile, or revoking permission shall cancel stale checks; pausing shall also unregister network callbacks. Resume shall reset old request/observation state and recheck the current profile. Detection failures shall show unavailable/unknown rather than a false confirmed state. No observation shall automatically connect or disconnect Tailscale. Same-phone profiles shall not participate in this VPN observation/control workflow.

REQ-UI-011: Each prepared input shall expose explicit Retry extraction using its retained image bytes or typed draft and a newly resolved permitted provider snapshot. Retry shall replace only that item's unexported results after success, protect edited reviews with confirmation, and retain old results after failure while preventing stale export. Retry shall be disabled during batch extraction/export or provider configuration. Image-load failure requires reselection; retry shall not upload or alter persisted exports.

REQ-UI-012: Main and Settings shall reserve Android's safe drawing insets outside their scrollable viewports so controls remain fully visible and tappable above gesture and three-button navigation bars. Layout shall respond to status bars, display cutouts, side navigation bars and keyboard inset changes, consuming shared insets once while preserving existing content margins.

REQ-UI-013: Settings shall expose accessible Duplicate profile and Load models actions, a searchable model picker, and separate loading/empty/error/cancelled states. Selecting a catalog item shall edit only the draft until explicitly saved.

REQ-UI-014: Expense-app diagnostics, including provider verification, profile testing/discovery, extraction and review errors, shall support text selection and an accessible Copy message action that copies the complete displayed message on demand. The companion shall expose selectable saved and active endpoint URLs with separate Copy actions that copy only the exact endpoint snapshot. Stopped/unavailable endpoints shall have no Copy action; copying shall not include credentials or change settings, selection, service state or inference.

### Handoff File

REQ-H-001: The handoff format and final file extension shall be JSON (`.json`). Temporary or incomplete uploads shall use a non-JSON extension such as `.uploading`.

REQ-H-002: Each handoff expense object (singleton or combined-list member) shall include these required fields:
- schemaVersion
- expenseId
- createdAt
- sourceDeviceId
- receiptDate
- merchantName
- totalAmount
- currency
- extractionStatus

REQ-H-003: Each handoff expense object may include these optional fields:
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

REQ-H-004: Individual export file names shall be unique and sortable, using this pattern (combined lists use REQ-H-009):

```text
expense_yyyyMMdd_HHmmss_<shortExpenseId>.json
```

REQ-H-005: The Android app shall avoid partial file processing by either writing atomically or using a temporary extension until the file is complete.

REQ-H-006: Version 2 shall require `receiptImageRelativePath` identifying an existing normalized JPEG with deterministic timestamp/ID correlation. Version 3 shall accept only explicit typed/no-image/manual records with a required null image path; reject inconsistent discriminators and unsupported image combinations.

REQ-H-008: Mixed Version 2/typed Version 3 processing shall preserve filename identity, CAD-only rules, safe F/G row selection, duplicate detection, locking, archive/quarantine and idempotency. Typed rows shall clear stale H text/hyperlinks without changing unrelated columns; notes remain in archived handoffs without new workbook mapping.

REQ-H-009: Export All shall publish a nonempty top-level JSON array of existing Version 2/typed Version 3 expense objects as `expense_batch_yyyyMMdd_HHmmss_<shortBatchId>.json`. Every member retains its own UUID, createdAt and deterministic image path; the batch filename identifies the list, not any member. The complete array is a commit marker only after all referenced images exist. Temporary uploads use `.json.uploading`.

REQ-H-007: A final handoff JSON shall be a commit marker: every referenced receipt image must already exist before the singleton or combined-list final `.json` becomes visible to Hermes.

### Windows Hermes Agent

REQ-W-001: The Hermes agent shall poll the configured OneDrive handoff folder every 5 minutes.

REQ-W-002: The Hermes agent shall inspect only the handoff folder's direct children and process complete singleton `expense_yyyyMMdd_HHmmss_<shortExpenseId>.json` or combined `expense_batch_yyyyMMdd_HHmmss_<shortBatchId>.json` files; temporary extensions and archive subfolders shall be ignored.

REQ-W-014: Validate a combined array and all member identities/source contracts before staging any member. Reject malformed lists, duplicate expense IDs, and conflicting existing member files without overwriting data. Expand valid lists into deterministic singleton handoffs using temporary/final staging, then archive the source list separately only after every member is durably staged or already archived. Existing per-expense processing/state, CAD policy, workbook mapping, archive/quarantine and duplicate recovery shall apply unchanged. Dry run shall report members without writes; replay and interrupted expansion shall not create duplicate workbook rows.

REQ-W-003: The Hermes agent shall skip already processed expense ids.

REQ-W-004: Before updating Excel, the processor shall validate filename/expense-ID correlation, expense fields/status and CAD policy. Version 2 additionally requires its deterministic path and real paired JPEG. Version 3 requires the explicit typed/no-image/manual branch in REQ-H-006; no other Version 3 shape may bypass image validation.

REQ-W-005: The Hermes agent shall log success and failure for each handoff file.

REQ-W-006: The PowerShell action shall record successful inserts and exact workbook duplicates in local idempotency state, then move their handoff files to `receipt_jsons_done`.

REQ-W-007: The PowerShell action shall move ambiguous or non-CAD files to `receipt_jsons_review`, move invalid files to `receipt_jsons_error`, and retain an actionable sidecar reason without logging the full receipt payload.

REQ-W-008: The Hermes agent shall run the workbook update action through PowerShell.

REQ-W-009: The PowerShell action shall maintain atomic expense-id state under `%LOCALAPPDATA%\SmartExpenseTracking\Hermes` and combine it with workbook-level exact duplicate checks for restart-safe idempotency.

REQ-W-010: Normal polling, parsing, routing, duplicate detection, and workbook writes shall be deterministic code paths and shall not require an LLM. An LLM may explain a quarantined result but shall not approve an automatic financial write.

REQ-W-011: The scheduled Hermes job shall run in script-only/no-agent mode, emit `[SILENT]` when no final handoff is present, and deliver a concise deterministic status to the configured Discord home channel when a run has an outcome to report.

REQ-W-012: The Windows processor shall prevent overlapping runs and shall process eligible handoff files in ordinal filename order.

REQ-W-013: The production Hermes job shall be created paused after unit tests and a disposable-workbook canary pass. Enabling it against the live workbook shall require separate user approval.

### Excel Update

REQ-X-001: The system shall update an existing Excel workbook.

REQ-X-002: The target workbook path shall be:

```text
D:\OneDrive\Documents\2_Others\Expenses_finance\Canada plan.xlsx
```

REQ-X-003: The target worksheet shall be selected by receipt month using `YYYY-MM` format.

REQ-X-004: If the target monthly worksheet does not exist, the PowerShell action shall copy the latest valid earlier monthly sheet through Excel COM, rename and position it chronologically, preserve rows 1-11 and all non-transaction workbook structure, and clear only F:H in rows 12-100. If no safe source sheet exists or validation fails, it shall leave the handoff for review without saving.

REQ-X-005: The Windows side shall add one expense row per accepted handoff file.

REQ-X-006: The PowerShell action shall use the first row from 12 through 100 where both F and G are empty. It shall not insert or reorder rows, overwrite a partially populated row, or disturb existing formulas, formatting, and layout.

REQ-X-007: For an accepted handoff, write numeric `totalAmount` to F and `<merchant> MMM dd` to G with invariant English dates and approved `Supermarket` to `Mart` wording. Version 2 adds a relative image hyperlink in H; typed Version 3 clears H text/hyperlinks on that safe row. Notes shall not introduce another column mapping.

REQ-X-008: The action shall preserve column E, column O's row formula, and all unrelated cells. If a selected row lacks the established transaction formatting or row-relative formula pattern, the action shall extend the pattern without copying another transaction's values and shall abort if it cannot do so safely.

REQ-X-009: Before writing a row, the action shall check for existing records using:
- receipt date
- normalized merchant/shop/service name
- total amount rounded to two fractional digits

REQ-X-010: A matching receipt date, amount, and normalized merchant shall be treated as an exact workbook duplicate and archived without insertion. A matching date and amount with a different merchant shall be quarantined for review without changing the workbook. Fuzzy or LLM-based automatic duplicate decisions are not allowed.

REQ-X-011: The mapping and duplicate logic shall parse a legacy workbook date only from an unambiguous trailing English `MMM d` or `MMM dd` suffix whose month matches the worksheet; it shall not guess from free text or multiple-date descriptions.

REQ-X-012: The update mechanism shall prevent duplicate rows for the same expense id.

REQ-X-013: If Excel is open or locked, the agent shall retry or fail gracefully without losing the handoff file.

REQ-X-014: The production workbook write shall use desktop Excel COM and save once per successful batch. A locked, read-only, unavailable, full, or structurally unexpected workbook shall leave valid handoffs in the inbox and shall not record them as processed.

REQ-X-015: When a target month later than the latest month in `Food Expense Summary` is missing from that summary, the action shall append its 89-row source-detail block for rows 12-100, add the monthly summary row before `Accumulated`, extend summary and coverage formulas, update the title, and require the coverage status to remain `OK` before saving.

REQ-X-016: The action shall not silently add an out-of-order summary month, bridge a chronological gap, or repair an unfamiliar summary structure.

## Optional Requirements

REQ-O-001: The app may include a receipt photo link if the user can obtain one from Amazon Photos or another existing backup flow.

REQ-O-002: A future enhancement may add or substitute a stable external `receiptPhotoLink`; the MVP Excel row uses the required relative OneDrive receipt-image hyperlink from REQ-X-007.

REQ-O-003: Amazon Photos linking is optional and shall be implemented only if a reliable, user-authorized way to obtain stable share links is confirmed.

REQ-O-004: Amazon Photos may remain the full-resolution backup source, while the required OneDrive receipt copy is a normalized JPEG strictly below 200 KB used for the expense workflow.

## Non-Functional Requirements

NFR-001: The default Built-in on-device workflow shall avoid sending expense data to third-party AI services. Remote API providers, including LAN/Tailscale services, may receive expense data only after explicit user opt-in through Settings. Explicitly selected Same-phone server inference shall remain on the phone under the loopback-only restrictions of REQ-M-031 and shall not require remote opt-in.

NFR-002: Sensitive expense data shall not be logged in full unless debug logging is explicitly enabled. API keys and authentication secrets shall always remain redacted, including when Debug is enabled, in both apps.

NFR-003: The Android app shall work offline for capture, review, correction, manual entry, and local export queueing. Built-in extraction shall work offline after its model is provisioned. Same-phone extraction shall work offline after companion/model provisioning while the companion is running with the required model ready; companion outages shall preserve drafts and offer explicit retry/manual entry. Offline export queueing shall not imply OneDrive upload without network access.

NFR-003A: If a Remote API provider is selected, capture, review, correction, export queueing, and manual entry shall still work offline; Remote API extraction may require network access. A local HTTP connection to Same-phone server shall not imply an external-network dependency.

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

## Key Design Decisions

DEC-001: Define the Android inference paths that remain on the phone.

Decision: Built-in on-device uses Google AI Edge APIs or LiteRT-LM directly in the expense app and remains the default. AI Edge Gallery remains a reference for that provider. Same-phone server is an explicitly selected optional runtime through a separately supported mobile-server companion fork under REQ-M-006/031..035. Direct-image vision quality and physical-phone lifecycle/performance remain implementation acceptance work; see [the integration goal](10.mobile-server-integration-goal.md).

DEC-001A: Confirm the Android model-provider strategy.

Decision: support Built-in on-device, Same-phone server, and Remote API behind one extraction interface and normalized output contract. API protocol and inference location are separate concerns. Both API categories use explicitly selected saved profiles; only Remote API requires remote opt-in. The built-in provider is the default and deterministic fallback; same-phone outages offer retry/manual entry without automatically selecting another provider.

Evaluation:
- Benefits: faster MVP path if local multimodal performance is weak, broader model choice, easier model upgrades, and a useful fallback for devices that cannot run the selected local model reliably.
- Tradeoffs: Remote API may send data outside the phone, require network access and incur costs. Same-phone server adds a companion runtime, model readiness and lifecycle/memory dependencies. Both API categories require tested vision, JSON-output, authentication and error behavior, and secure handling of any configured key.
- Selected approach (Option B, 2026-10-10): explicit three-category metadata and consistent consent, selection, verification, transfer and scheduling rules, preserving the shared prompt/parser/validation/review/handoff contracts.
- Implementation sequencing: retain the existing Remote API path, validate Built-in on-device models, and implement/validate the supported companion fork and expense-app profile changes under the integration goal. Requirements alignment is complete; companion implementation remains pending.

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

DEC-003: Hermes PowerShell execution contract.

Decision: Hermes runs a deterministic PowerShell action from a script-only/no-agent five-minute cron job. The action auto-detects the local OneDrive sync root, stores atomic idempotency state under `%LOCALAPPDATA%\SmartExpenseTracking\Hermes`, and uses the local sync path rather than Microsoft Graph credentials. The job was created paused after a disposable-workbook canary, then activated after Hugo's separate approval; it delivers non-empty results to the configured Discord home channel.

DEC-004: Excel workbook mapping.

Decision:
- workbook path: `D:\OneDrive\Documents\2_Others\Expenses_finance\Canada plan.xlsx`
- monthly worksheet name pattern: `YYYY-MM`
- transaction destination: first row from 12-100 where F and G are both empty; do not insert or overwrite rows
- F: numeric total amount
- G: normalized merchant plus invariant English `MMM dd` receipt date
- H: relative clickable link to the normalized OneDrive receipt image
- E, O, formulas, formatting, and unrelated cells: preserve
- missing month: copy the latest earlier monthly sheet, clear only F:H rows 12-100, validate, and integrate the month into `Food Expense Summary`
- exact date/amount/normalized-merchant duplicate: skip and archive
- same-date/same-amount merchant conflict: quarantine for review
- write mechanism: desktop Excel COM

DEC-005: Currency and locale rules.

Decision: accept CAD handoffs only, store the amount as a numeric decimal value, format the column G date with invariant English `MMM dd`, and quarantine non-CAD handoffs without conversion or workbook mutation.

DEC-006: Receipt image and photo-link behavior.

Decision: upload a normalized JPEG below 200 KB to OneDrive for every exported expense before publishing its final JSON. Amazon Photos may remain the full-resolution backup, and external `receiptPhotoLink` values remain optional.

## Acceptance Criteria

AC-001: A user can capture/import a receipt, review extracted fields produced by the selected model provider or manual entry, and export a handoff file.

AC-002: The handoff file validates against the agreed schema.

AC-003: The Windows agent detects the handoff file within one polling cycle after OneDrive sync completes.

AC-004: An accepted CAD handoff fills one safe row from 12-100 in the correct monthly sheet with numeric amount in F and merchant/date in G. Version 2 adds a working relative image hyperlink in H; typed Version 3 clears H text/hyperlinks. Existing E/O behavior and unrelated cells remain intact.

AC-005: Reprocessing the same handoff file does not create a duplicate row.

AC-006: Invalid handoff files are preserved in `receipt_jsons_error`, ambiguous and non-CAD files are preserved in `receipt_jsons_review`, each has an actionable reason, and neither changes the workbook.

AC-007: Image-backed workflow succeeds without optional `receiptPhotoLink` because required Version 2 `receiptImageRelativePath` supplies H. Typed Version 3 succeeds with explicit null image path and empty H.

AC-008: A user can configure Built-in on-device, Same-phone server, or Remote API in Settings, choose the applicable model ID, explicitly select an eligible provider on Main, and see its category/name for the next extraction. Same-phone profiles remain eligible with remote opt-in disabled.

AC-009: With no remote provider configured, the app does not send receipt data to a third-party AI provider.

AC-010: A user can create at least two named OpenAI-compatible profiles, restart the app, select either profile, and see that profile identified for the next extraction.

AC-011: Saving, editing, clearing, and deleting one profile affects only that profile's secure credential alias; no API-key value is present in Room or plain preferences.

AC-012: Disabling remote access while a Remote API profile is selected chooses Built-in on-device and cancels pending/active remote work without another remote request; disabling it while Same-phone server or Built-in on-device is selected preserves that selection and its work. Deleting either selected API profile or resolving a missing selection chooses Built-in on-device without automatic extraction. Built-in model unavailability offers setup/manual recovery. A legacy single-profile configuration migrates at most once.

AC-013: After a clean install or upgrade from the first multi-profile release, the saved-profile list contains the bundled LM Studio Gemma configuration alongside profiles such as OpenRouter, unless an equivalent or previously migrated LM Studio profile already exists.

AC-014: Exporting a confirmed image-backed expense uploads a normalized JPEG below 200 KB to `Documents/2_Others/Expenses_finance/receipt_images/YYYY-MM/YYYYMMDD_HHmmss_receipt_<shortExpenseId>.jpg` and places that exact path in Version 2 JSON. Typed Version 3 publishes no image and requires a null path.

AC-015: For Version 2 the image finishes before final JSON appears; image failure leaves no processable JSON and exposes retry. Version 3 skips image operations and retains temporary/final JSON commit ordering.

AC-016: Retrying an expense reuses its immutable identity/payload/JSON path and image path when present, without duplicate files.

AC-017: Version 2 creates missing monthly image folders safely before upload; failure retains retry without final JSON. Version 3 makes no image-folder call.

AC-018: Given an itemized receipt without tax or tip and a finalized receipt for the same transaction with tax and tip, extraction returns one expense whose `totalAmount` is the finalized amount rather than the subtotal or the sum of both receipt totals. Ambiguous receipt pairs are flagged `low_confidence` for user review.

AC-019: Exporting provider configuration produces a v3 `.json` document containing every saved Same-phone server and Remote API profile, category/output-mode metadata, and the selector snapshot, with no API-key value from either app, credential alias, or persistence timestamp.

AC-020: Importing a valid provider-configuration document displays a preview before mutation, imports every profile in one transaction, preserves non-conflicting IDs, remaps conflicting IDs, and leaves the current selection and remote-provider setting unchanged.

AC-021: Cancelling either system picker or the import preview changes nothing; malformed, unsupported, empty, duplicate-ID, or invalid-profile JSON is rejected without partial persistence and with an actionable message.

AC-022: Main shows compact provider selection and verification, contextual OneDrive readiness, receipt import, review, and export, while Settings shows provider configuration/transfer and image preprocessing without receipt-workflow content.

AC-023: A Main → Settings → Main round trip retains the current receipt review and any in-flight extraction/export state, and repeated Settings activation creates no duplicate destination.

AC-024: Verify checks the expense app's installed Gemma model file for Built-in on-device, or tests the selected saved Same-phone server/Remote API profile using its saved endpoint/model/output mode and effective key without using an editor draft. A successful models response alone does not establish inference readiness. Changing selection shall not mutate an active extraction snapshot; profile/editor changes shall cancel stale discovery/verification results.

AC-025: Settings Back/Up protects unsaved profile edits, and Main derives both its OneDrive message and export availability from one readiness rule with contextual recovery actions and no Disconnect action.

AC-026: When a future monthly sheet is absent, the processor can copy the latest earlier month, clear only its transaction cells, add the receipt, extend `Food Expense Summary`, and reopen the copied workbook with the summary coverage status still `OK`.

AC-027: When the workbook is open, locked, read-only, full, unavailable, or structurally unexpected, valid handoffs remain in the inbox for retry and no processed state is recorded.

AC-028: A quiet five-minute run emits `[SILENT]`; a run with reportable outcomes emits a concise deterministic status suitable for direct Discord delivery without invoking an LLM.

AC-029: Unit tests and a disposable-workbook Excel COM canary pass before the Hermes job is created, and that job remains paused until the user separately approves live activation.

AC-030: Confirming an unchanged `low_confidence` receipt produces JSON with `extractionStatus: "confirmed"`. Editing any review field before export produces `extractionStatus: "manual"`, and retrying a failed export retains its saved status.

AC-031: In JSON Schema mode, an extraction request is valid JSON with an outer receipts array schema; the provider check and receipt extraction both work with their respective schemas. Debug output is hidden by default, exposes the raw response or error with credentials redacted when enabled, and shows each generated export JSON with its publication state. Prompt-only mode is checked separately by AC-054.

AC-032: Each selected image is prepared without inference and exposes its complete processed preview with Close/Back and Remove. Enabled oversized reduction previews the exact sub-200-KB JPEG sent on Extract all; otherwise original bytes are retained. Appending/removing images preserves other item identities and previews.

AC-033: The selected-image viewer opens at fit scale. Pinching and the labeled Zoom in/Zoom out controls change magnification within the defined range; dragging moves the image only while zoomed and cannot leave it lost offscreen. The controls reflect their minimum/maximum limits, Close and Back work at any zoom, and reopening or selecting another image restores fit scale without changing extraction, review, or export state.

AC-034: Enabling the Remote API profile checkbox persists through editing, restart and v3 provider transfer, with v1/v2 compatibility; profiles migrated from Room v1 and v1 imports default it to false. Main shows the control only for the selected, enabled, opted-in Remote API profile. Built-in/Same-phone selections and disallowed/stale callbacks send no commands; same-phone saves/imports cannot enable the flag. Eligible requests use the saved endpoint/credential and no receipt data or inference. Connect retries until models endpoint reachability or its deadline, profile changes cancel stale checks, disconnect reports only a request, and resume resets and refreshes observations without disconnecting or blocking extraction.

AC-035: Main displays VPN detected, no VPN detected for this app, or VPN detection unavailable separately from models endpoint checking/reachable/unavailable. External VPN changes update detection and trigger a fresh endpoint check without commands; periodic checks detect endpoint outages/recovery. A reachable LAN endpoint with no detected VPN does not turn the command switch on. Backgrounding stops checks and network callbacks; returning refreshes current observations. The reusable checker supports custom URL/path/key/timeouts, empty model lists, HTTP/invalid-response errors and prompt cancellation, with no credential leakage or receipt transmission.

AC-036: Item retry uses retained processed bytes or the typed draft with the provider selected at retry activation. Repeated taps while busy start no extra request; provider changes do not mutate active snapshots. Failure retains source and old results; successful confirmed replacement updates only that item's unexported reviews. Exported identities remain immutable and no upload follows retry.

AC-037: On Main, the receipt selection, extraction retry and export controls can be scrolled completely above Android navigation UI, including with Debug output enabled. Settings content uses the same safe viewport. Insets update when the keyboard or navigation configuration changes, and a nested inset-aware child does not apply the same system spacing twice.

AC-038: Duplicate a profile containing a stored key and a Tailscale flag, restart, and observe identical provider settings with the exact copy name and independent identity. Editing, deleting, or clearing either credential affects only that profile; no remote request/command or selection change occurs.

AC-039: Load models for a new and an existing profile, choose an ID, save, and verify the saved model is used for the next extraction. Empty/error responses retain manual input; changing endpoint/credential, closing the editor, or leaving Settings prevents stale results from replacing current state. No receipt data or inference is sent by discovery.

AC-040: Select five images, inspect them without inference, remove through thumbnail and full viewer, append two images, and retain unaffected identities/order/processed bytes. Duplicate URI/over-capacity additions explain rejection; picker cancellation changes nothing.

AC-041: Six delayed fake Remote API items with concurrency three obey 3.5-second start spacing and never exceed three in flight, including recovery/retries. Built-in on-device and Same-phone server each remain serial and are not subject to remote start spacing. Out-of-order results and an isolated failure remain in their own groups; repeated Extract all creates one run and later runs process new/changed items only.

AC-042: Remove queued/active items, cancel remaining, edit sources and retry, then deliver late callbacks: removed/stale attempts cannot publish or export. Collapse/expand, rotation and Main/Settings navigation retain state; process restoration never automatically submits data.

AC-043: An entirely typed and a mixed batch retain CAD defaults, original source strings, notes and item-level mode-switch drafts. Every no-image field accepts words, arithmetic and punctuation without source type/format or per-field presence validation; only an empty/whitespace combined draft or the untouched CAD default alone blocks submission. Typed requests contain no image, preserve all labeled strings including blanks as data, yield exactly one expense using evidence from any field, and show original fields alongside normalized results/corrections. Invalid/negative/non-finite structured amounts, impossible/non-ISO dates, malformed output currency, contradictory or ambiguous facts, multiple results and model outage block export while preserving drafts and allowing correction/retry or validated manual fallback.

AC-044: Migrate Version 2 pending/completed export rows without losing IDs/status/paths and retry them unchanged. Typed Version 3 export skips image preparation/folder/upload and preserves manual provenance/notes with temporary/final JSON commit.

AC-045: On disposable workbooks, mixed V2/V3 fills F/G independently, keeps image hyperlinks and leaves typed H empty even if stale text/hyperlinks existed. Malformed V3, non-CAD, duplicates and replay preserve quarantine/archive/state guarantees.

AC-046: Verify accessible group/removal/editor controls, narrow-screen/keyboard/system insets and bounded previews at 20 inputs. Record actual emulator/device UI execution separately from compilation; benchmark local model/server concurrency before changing policy defaults.

AC-047: Enter amount `15+50` and `15+50, which is 65 in total`, date `Oct 7 2026`, and currency `Canadian dollars` in no-image items. Repeat with the entire text `Walmrat, Oct 7 2026, 15+50 Canadian dollars` solely in Merchant and solely in Notes, leaving other fields blank, and with facts distributed across mismatched field labels. Extract all shall submit the original strings without local parsing or per-field rejection; controlled provider responses shall produce numeric `totalAmount: 65`, `receiptDate: "2026-10-07"`, and `currency: "CAD"` for review, with a visible `Walmrat` → `Walmart` suggestion requiring user acceptance. Ambiguous/unfamiliar merchant names shall remain unchanged or require clarification. Verify explicit currency anywhere overrides the CAD default and conflicting explicit facts require resolution. Confirmation exports validated normalized values through manual Version 3 while retaining the original draft and exact notes, even when Notes supplied all expense facts. Unit tests shall cover combined-draft eligibility, request serialization, result validation, failure, retry and restoration; Compose tests shall verify unrestricted text entry, blank dedicated fields and suggestion review. Live provider interpretation shall be recorded separately from fake-provider contract tests.

AC-048: A natural narrative such as `I paid 65 Canadian dollars at Walmart on October 7, 2026` in any no-image field shall use the dedicated text prompt, preserve all original strings, and return the same normalized receipt fields as equivalent image extraction. Verify with controlled provider responses and retain strict output/export validation; live model interpretation is separate verification.

AC-049: In a mixed batch containing multiple image transactions and a typed expense, Export All is disabled until every remaining input/result is ready/current/valid and OneDrive is ready. Clicking it once confirms and sends one ordered JSON list; all referenced images precede list commit, typed members perform no image operations, and already completed transactions are excluded. Failure/restart/member retry preserves immutable membership/IDs/paths; extra new items cannot silently join a pending export. Verify Room migration/reopen, partial image/JSON failure, commit recovery, duplicate taps and explicit retry.

AC-050: Windows validates and expands mixed V2/V3 arrays atomically with respect to validation, then applies existing singleton processing. Exercise dry run, malformed members, duplicate IDs, conflicting member files, interrupted staging, replay and per-member quarantine. No production workbook or scheduled job changes are part of this implementation.

AC-051: Create two no-image items. Before extraction, each shows Extract and a disabled Confirm and export. Default-only CAD/blank drafts disable Extract; expense text in any field enables it. Extract one item and verify only its draft is submitted, the other item is unchanged, and repeated busy taps start no extra request. Completion/failure offers Retry extraction; retry protects edits, preserves the source, and cannot export failed or stale results. Confirm and export requires current valid results and ready OneDrive, targets the stable item/transaction IDs once, and preserves saved export retry/completion states. Unit and emulator UI tests shall cover these transitions; provider/physical-device checks are separate.

AC-052: On clean install and upgrade, Built-in on-device remains the default unless a valid existing selection is preserved. Creating/saving/importing/duplicating a same-phone profile never selects it or enables remote access. Explicit selection works with remote opt-in off; selected-profile deletion/missing-profile fallback and consent revocation follow AC-012. Tailscale controls/observations remain absent for same-phone selection. Same-phone verification exercises saved endpoint/model/output mode, while discovery sends no receipt data or inference.

AC-053: Configure both apps first with `http://127.0.0.1:8080/v1`, then a custom port/base path; save/restart and verify discovery, verification and extraction route beneath the persisted matching endpoint. Reject invalid ports/paths, user-info/query/fragment, DNS names, alternate loopback forms, LAN/remote hosts, redirects, and same-phone Tailscale flags. Verify actual companion loopback binding with tunnels disabled, narrow client cleartext permission, active-endpoint display, stale discovery/verification cancellation on edits, and unchanged active extraction snapshots.

AC-054: In Prompt-only JSON mode, verification and photo/typed requests omit `response_format` and retain their existing JSON prompts. Equivalent valid JSON with different whitespace/key ordering passes semantic verification; invalid or semantically incorrect verification responses fail. Malformed/invalid extraction output blocks export with retry/manual recovery. JSON Schema/JSON Object request behavior and multi-transaction/typed validation remain covered by regression tests.

AC-055: With no key in either app, discovery/verification/extraction succeed with no Authorization header. Matching configured keys succeed; a configured server rejects missing/wrong client keys with HTTP 401 before inference and offers actionable recovery. Test independent profile key replacement/clearing/deletion/duplication, secure storage across restart in both apps, immutable request credentials, and absence of keys from logs/errors/Debug/Room/plain preferences/transfer. Clearing the client key omits authentication; clearing the server key restores unauthenticated access.

AC-056: Migrate the model-profile Room database v2 to v3 without changing existing IDs/settings/credentials/selection/opt-in or creating duplicates. Import v1/v2 files as Remote API with existing output modes and compatible Tailscale defaults, even for loopback URLs. Round-trip v3 category, endpoint and output mode; import credential-free same-phone profiles with preview/atomic conflict copying and unchanged selection/opt-in. Invalid categories/modes/endpoints/capabilities produce no partial mutation. Confirm batch/export database and handoff versions are unaffected.

AC-057: Unit tests in the companion cover string and multimodal message parsing, inline-image decoding and remote-image rejection, vision initialization/non-vision rejection, independent expense conversations, inference serialization, unload coordination, cancellation/disconnect/timeout resource release, endpoint persistence, authentication, and startup/model-readiness reporting. Expense-app unit tests cover category eligibility/fallback, endpoint/authentication/request snapshots, output modes, discovery/verification, migration/transfer/duplication, deadlines and recovery. This repo's shared/Android unit tests, companion unit tests, relevant device tests, and debug builds for both apps shall pass before implementation is marked complete.

AC-058: Delayed same-phone batch/retry requests never exceed one active inference; a slow valid response is not cut off by a shorter transport timeout before the 180-second extraction deadline. Cancellation/deadline aborts the request and releases companion inference before later work runs; late results cannot publish. Exercise stopped server, busy inference, missing/non-vision model, invalid output, authentication recovery, and server restart while preserving drafts, completed results and explicit retry/manual recovery without automatic fallback extraction.

AC-059: Integration checks exercise discovery, typed extraction, single-photo extraction, multiple transactions in one image and mixed batches at default/custom endpoints with authentication disabled and enabled. Verify authentication failure/recovery, restart with persisted server endpoint/key, independent conversations and invalid-output export blocking. Existing normalized output, review, image-first Version 2, image-free typed Version 3 and Export All contracts remain unchanged.

AC-060: Physical-phone validation records the companion/model/version, offline direct-image and typed extraction after provisioning, extraction accuracy, latency, memory pressure and companion foreground/background behavior. Report device/model limitations and outstanding validation separately from unit tests/builds; no HTTP-only or fake-provider result shall establish vision quality or device completion.

AC-061: Copy expense Main verification and failed-item diagnostics, including multiline HTTP/code guidance, and Settings provider-test/discovery failures; verify exact clipboard contents and current messages after updates, without automatic clipboard writes or draft credentials. Copy distinct saved/custom and active/default companion endpoints and verify each exact URL; stopped/unavailable states expose no Copy action. Unit tests, relevant clipboard UI checks and both debug builds shall pass.

## No-image item actions implementation verification

✅ Done on 2026-10-08: Implemented `REQ-A-030` and `AC-051`, retaining `REQ-A-022..025`, `REQ-UI-011`, `AC-036`, `AC-043`, and `AC-047` guards. All 60 shared tests and 217 Android unit tests pass, including six new Robolectric Compose button regressions and one targeted workflow extraction/retry regression. Debug APK assembly succeeds. See the [batch plan verification record](9.multiple-and-no-image-support-plan.md) for emulator execution evidence. Live provider and physical-device validation remain pending.

## System-bar layout implementation verification

✅ Done on 2026-10-01: `gradlew.bat test :android-app:testDebugUnitTest :android-app:assembleDebug :android-app:compileDebugAndroidTestKotlin --max-workers=2 --console=plain` succeeds. All 121 Android unit tests pass, including four Robolectric Compose layout regressions; shared JVM tests remain passing/up to date. The regressions use the actual Main workflow with long Debug output to verify full export-button bounds and activation above synthetic gesture/three-button insets, respond to keyboard-sized inset changes, and verify side insets are consumed once. Main and Settings share the same safe container, with explicit edge-to-edge setup and keyboard resize handling. This verifies `REQ-UI-012` and `AC-037`, with existing navigation, review, Debug, and export unit coverage retained. Device UI tests compile; this task did not execute instrumentation tests. Physical-device validation: ✅ Done, confirmed by Hugo on 2026-10-01.

## Extraction retry implementation verification

✅ Done on 2026-10-01: `gradlew.bat test :android-app:testDebugUnitTest :android-app:assembleDebug :android-app:compileDebugAndroidTestKotlin --max-workers=2 --console=plain` succeeds. All 117 Android unit tests pass, including four new workflow retry regressions; the 38 shared JVM tests remain passing/up to date. Compose tests compile and the debug APK assembles. This verifies `REQ-UI-011` and `AC-036` for retained image/source URI, retry-time provider snapshots/local fallback, review/debug replacement, failure recovery, latest selection, busy guards, and independent export state, with regressions for `REQ-UI-004`, `REQ-UI-007`, `REQ-M-004`, `REQ-M-009`, `REQ-M-019`, and `REQ-A-016`. The Compose action test was not executed in this task.

Physical-device validation: ✅ Done on Pixel 7, confirmed by Hugo on 2026-10-01. Individual test scenarios were not recorded.

## Tailscale implementation verification

Final live status UI verification: ✅ Done — the targeted `:android-app:connectedDebugAndroidTest` run for `TailscaleControlTest`, `ModelProfilesPanelTest` and `ReceiptWorkflowScreenTest` passes all ten tests on the Pixel 8a API 35 emulator on 2026-09-30. Together with the unit/build evidence below, this completes automated verification for `REQ-M-027..028`, `REQ-UI-009..010` and `AC-034..035`.

Live status extension verified 2026-09-30 with `.\gradlew.bat test :android-app:assembleDebug :android-app:compileDebugAndroidTestKotlin --max-workers=2`: shared tests and all 113 Android unit tests pass, Compose tests compile and the debug APK assembles. New VPN snapshot/callback/loss/cleanup, external status changes, profile/lifecycle cancellation, periodic reachability, custom checker inputs, HTTP/response/redirect/size failures, saved credentials and socket cancellation cover `REQ-M-027..028`, `REQ-UI-009..010` and `AC-034..035`. Physical-device validation: ✅ Done on Pixel 7, confirmed by Hugo on 2026-09-30. The original rollout evidence follows.

Verified 2026-09-30 with `.\gradlew.bat test :android-app:assembleDebug` and targeted `:android-app:connectedDebugAndroidTest` runs for `TailscaleControlTest`, `ModelProfilesPanelTest`, and `ReceiptWorkflowScreenTest` on the Pixel 8a API 35 emulator. All 38 shared JVM tests, 104 Android unit tests and nine affected Compose tests pass; the final combined run succeeds. Room v2 schema is exported. This verifies `REQ-M-026..027`, `REQ-UI-009` and `AC-034`, with regression coverage for existing provider/privacy/transfer/navigation and receipt-screen behavior. The emulator's blocking System UI ANR dialog was cleared before the successful final run. Physical-device validation: ✅ Done on Pixel 7, confirmed by Hugo on 2026-09-30.

## Windows implementation verification

Verified 2026-09-16 with `pwsh -NoLogo -NoProfile -NonInteractive -File scripts/test-hermes-excel-update.ps1` (100 assertions plus copied-workbook Excel COM canaries) and `pwsh -NoLogo -NoProfile -NonInteractive -File scripts/test-handoff-contract.ps1`. REQ-W-002..010 and REQ-W-012, REQ-X-001..016, and AC-026..028 are implemented in `scripts/SmartExpense.Excel.psm1` and `scripts/Invoke-HermesExcelUpdate.ps1`. The source workbook at `D:\Users\tuant\OneDrive\Documents\2_Others\Expenses_finance\Canada plan.xlsx` is copied to disposable temp trees for automated integration tests. Hugo separately completed a controlled manual non-dry-run test against the live workbook and restored it; the follow-up dry run returned `no_work` and preserved its SHA-256.

REQ-W-001, REQ-W-011, REQ-W-013, and the scheduling portion of AC-029 are configured by active job `f6934b4a04fe`: `every 5m`, script-only/no-agent, Discord channel `1549288366992793652`, `gpt-5.6-luna`, high reasoning. A disposable-path run was delivered and read back as Discord message `1549441490327965807`. Hugo separately approved live activation; the scheduler and gateway are running, and the job's most recent run completed successfully.

## Verified External References

- Android Photo Picker: https://developer.android.com/training/data-storage/shared/photo-picker
- Android Storage Access Framework: https://developer.android.com/training/data-storage/shared/documents-files
- Google AI Edge Gallery repository: https://github.com/google-ai-edge/gallery
- Google AI Edge API reference: https://developers.google.com/edge/api
- Google AI Edge LiteRT-LM: https://developers.google.com/edge/litert-lm
- Google AI Edge LiteRT-LM for Android: https://developers.google.com/edge/litert-lm/android
- Microsoft Graph Excel table row API: https://learn.microsoft.com/en-us/graph/api/table-post-rows
- Hermes scheduled tasks: https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/features/cron.md
