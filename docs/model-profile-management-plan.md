# Model Profile Management Implementation Plan

Last updated: 2026-09-05

Planning status: ✅ Done

Implementation status: ✅ Done and verified on a physical Pixel 7.

## Purpose

Replace the Android app's single fixed LM Studio configuration with persistent management of multiple named OpenAI-compatible model profiles. A user must be able to create, view, edit, test, select, and delete profiles, and the selected valid profile must drive the next receipt extraction.

This plan extends the Model Selector work in `docs/PLAN.md` and closes the remaining Android Settings persistence work tracked in `docs/gap-closing-plan-details.md`.

## Current State

The current `android-app` implementation:

- Holds one `LmStudioSettingsState` in `MainActivity`.
- Saves one `base_url`, `model_id`, `input_mode`, and remote-enabled flag in one `SharedPreferences` file.
- Stores one API key under the fixed `lm-studio-api-key` encrypted credential alias.
- Saves the configuration when the provider is tested or a valid extraction starts; there is no explicit Save action.
- Has no profile identifier, profile list, display-name editor, create/duplicate/delete actions, or persisted selected-profile identifier.
- Builds every remote client from `LmStudioTestProfile`, so it cannot independently resolve several saved provider configurations.

The shared extraction layer already provides useful foundations:

- `OpenAiCompatibleProviderOption` represents a named remote provider.
- `ModelSelectorSettings` supports a list of remote providers plus a selected provider ID.
- `ModelSelector` safely falls back to the local provider when remote access is disabled or the requested profile is missing.
- `AndroidApiKeyStore` keeps credentials outside plain preferences.

## Scope

### Included

- Multiple named OpenAI-compatible profiles.
- Persistent profile metadata and selected-profile state.
- Per-profile encrypted API-key storage.
- Create, edit, test, save, select, and delete actions.
- Migration of the current single LM Studio settings and credential.
- Local-provider fallback when remote access is disabled or no usable remote profile is selected.
- Refactoring the extraction and provider-test flows to resolve a saved profile by stable ID.
- Unit, persistence, migration, ViewModel, and practical Compose UI coverage.
- Requirements, status, and gap-plan updates as implementation work is completed.

### Not included

- Import/export or cloud synchronization of profiles.
- Duplicating or reordering profiles in the first increment.
- Custom headers, organization/project IDs, or provider-specific request templates.
- Storing API keys in the profile database.
- User-created local LiteRT-LM profiles. The existing built-in local provider remains the privacy-preserving fallback until local model installation/configuration requirements are defined.

## Requirements Changes

Before implementation, add explicit requirements to `docs/requirements.md` so completion is testable:

- `REQ-M-015`: The app shall allow the user to create, view, edit, test, save, select, and delete multiple named OpenAI-compatible provider profiles.
- `REQ-M-016`: Each saved profile shall have a stable unique ID and shall persist its display name, base URL, model ID, input mode, and structured-output format across app restarts.
- `REQ-M-017`: The app shall persist the selected profile ID and visibly identify the provider that will be used for the next extraction.
- `REQ-M-018`: Each remote profile shall use a distinct secure credential alias; API-key values shall not be stored in the profile database, plain preferences, logs, or UI state snapshots intended for persistence.
- `REQ-M-019`: Deleting the selected profile, disabling remote providers, or encountering a missing selected profile shall result in a deterministic local-provider fallback without sending receipt data remotely.
- `REQ-M-020`: Migrating from the single-profile settings format shall preserve a valid existing configuration and credential without creating duplicate profiles on subsequent starts.

These additions refine existing requirements `REQ-M-008` through `REQ-M-014`, acceptance criteria `AC-008` and `AC-009`, and `NFR-001`, `NFR-002`, `NFR-003A`, and `NFR-007`.

## Proposed Design

### Domain model

Introduce an app-facing profile model independent of Compose widgets and persistence entities:

```kotlin
data class ModelProfile(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val modelId: String,
    val inputMode: RemoteInputMode,
    val structuredOutputFormat: RemoteStructuredOutputFormat,
    val credentialAlias: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
```

Rules:

- Generate IDs once with UUIDs; never derive identity from a display name, URL, or model ID.
- Trim display name, base URL, and model ID before persistence.
- Require a nonblank display name and model ID.
- Require an absolute `http` or `https` base URL and normalize trailing slashes through one shared validator.
- Keep the structured-output format on the profile because compatible providers differ in JSON-mode support.
- Generate credential aliases from the stable profile ID, for example `remote-provider:<profile-id>`.
- Keep API-key contents out of `ModelProfile` and persistence entities.

Add a `ModelProfileDraft` for form editing. It may temporarily contain the entered API key in memory, but it must not be placed in a saved-state handle, database entity, log, analytics event, or exception message.

### Persistence

Use Room for non-secret model-profile metadata and selector state. Room matches the project's planned Android persistence stack, provides schema versioning and atomic transactions, and can later share the application database used for export history.

Tables:

1. `model_profiles`
   - `id` primary key
   - `display_name`
   - `base_url`
   - `model_id`
   - `input_mode`
   - `structured_output_format`
   - `credential_alias`
   - `created_at_epoch_millis`
   - `updated_at_epoch_millis`

2. `model_selector_state`
   - singleton primary key
   - nullable `selected_remote_profile_id`
   - `remote_providers_enabled`

Repository contract:

```kotlin
interface ModelProfileRepository {
    fun observeProfiles(): Flow<List<ModelProfile>>
    fun observeSelectorState(): Flow<ModelSelectorState>
    suspend fun getProfile(id: String): ModelProfile?
    suspend fun saveProfile(profile: ModelProfile)
    suspend fun selectProfile(id: String?)
    suspend fun setRemoteProvidersEnabled(enabled: Boolean)
    suspend fun deleteProfile(id: String)
}
```

Repository behavior:

- Save profile metadata and selector changes through DAO transactions.
- Return profiles in a deterministic order: case-insensitive display name, then stable ID.
- When deleting the selected profile, clear the selected ID in the same transaction.
- Treat a dangling selected ID as no remote selection, even if database corruption or an interrupted migration produces one.
- Expose domain models rather than Room entities to the UI and extraction layers.

### Credential storage

Extend `AndroidApiKeyStore` behind a writable credential-store contract with `put` and `remove` operations.

- Save a nonblank key under the profile's stable credential alias.
- On edit, an empty untouched key field means "retain the existing credential," not "replace it with empty text."
- Provide an explicit Clear credential action when removal is required.
- Remove a profile credential only after profile deletion succeeds.
- If credential removal fails after metadata deletion, report a recoverable cleanup error without restoring a deleted profile implicitly.
- Never substitute the LM Studio placeholder value for arbitrary profiles. If LM Studio still needs a nonempty authorization header, handle that as a profile/test-profile policy instead of treating it as a user API key.

### State and application structure

Move model-settings orchestration out of `MainActivity`:

- `ModelProfilesViewModel` owns list state, selected profile, remote opt-in, editor state, validation messages, save/delete progress, and provider-test results.
- `ModelProfileRepository` owns profile and selector persistence.
- `ModelProfileValidator` owns normalization and field validation.
- `RemoteModelClientFactory` converts a saved `ModelProfile` to `OpenAiCompatibleProviderOption` and creates the client.
- `SelectedReceiptModelProviderResolver` combines the built-in local provider, repository selector state, and saved remote profiles using the shared `ModelSelector` contract.
- Extraction takes an immutable selected-provider snapshot before background work starts, so editing or deleting a profile cannot change an in-flight request.
- Use lifecycle-aware coroutines and ViewModel scope instead of UI-owned `Thread` instances for new profile operations.

### Compose user experience

Split the current screen into a profile list/selector and an editor:

1. Model Selector summary
   - Show whether local or remote extraction is effective.
   - Show the selected profile name and model for the next extraction.
   - Keep the global remote-provider opt-in explicit and disabled by default for a clean install.

2. Saved profiles list
   - Display profile name, model ID, base URL, input mode, and selected state.
   - Provide Select and Edit actions on each profile.
   - Provide Delete with confirmation.
   - Show a clear empty state and Add profile action.

3. Profile editor
   - Fields: display name, base URL, model ID, API key, input mode, and structured-output format.
   - Actions: Test provider, Save, Cancel, and Clear credential where applicable.
   - Saving is explicit; testing does not silently save metadata or change selection.
   - Save remains disabled until local validation succeeds.
   - A successful save returns to the list and keeps selection unchanged unless the user chooses "Save and select."
   - Unsaved edits require confirmation before leaving the editor.

4. Failure and privacy behavior
   - Test failures remain attached to the edited profile and use the existing authentication, rate-limit, network, and provider error mapping.
   - Remote opt-in is required both for selection and extraction, but profiles may be created and tested only after the UI clearly states that testing contacts the configured remote endpoint.
   - Deleting the selected profile immediately changes the effective provider to local and explains that fallback.

## Legacy Migration

Add an idempotent bootstrap migration that runs before the Model Selector is displayed:

1. Read the legacy `model_selector` preference keys and fixed LM Studio credential alias.
2. Determine whether legacy configuration exists. `REQ-M-021` now explicitly requires the bundled LM Studio Gemma profile on clean installs and upgrades; seed it once when no migrated, equivalent, or stable-ID LM Studio profile exists.
3. Normalize and validate the legacy base URL, model ID, and input mode.
4. Create one stable migrated profile ID, such as a stored generated UUID, and persist the LM Studio display name and `JSON_SCHEMA` format.
5. Copy the existing encrypted credential to the new per-profile alias without exposing its value outside the credential-store boundary.
6. Preserve the legacy remote-enabled state and select the migrated profile only when the legacy configuration was enabled and valid.
7. Set a migration-complete marker only after profile metadata, selector state, and credential copy succeed.
8. On later app starts, detect the marker or existing migrated ID and perform no duplicate insert.
9. Retain legacy values for one release as rollback protection; remove them only in a separately tested cleanup migration.
10. If legacy data is invalid, keep remote extraction disabled, preserve the raw legacy settings, and show a recoverable prompt to review them rather than silently selecting the profile.

## Implementation Phases

### Phase 1: Requirements and domain contracts

Status: ✅ Done

- ✅ Done: Add `REQ-M-015` through `REQ-M-020` and map them to acceptance criteria.
- ✅ Done: Add `ModelProfile`, `ModelProfileDraft`, selector-state, validation, repository, and writable credential-store contracts.
- ✅ Done: Reuse `RemoteInputMode`, `RemoteStructuredOutputFormat`, and `ModelSelector` rather than defining parallel enums or selection rules.
- ✅ Done: Allow duplicate display names because stable IDs own identity, while showing model/base URL to disambiguate them.
- ✅ Done: Add unit tests for validation, normalization, stable identity, provider conversion, and local fallback.

Exit criteria:

- Profile behavior is expressed independently of Android UI and Room.
- Requirements and tests define all validation and fallback rules.

### Phase 2: Room repository and secure credentials

Status: ✅ Done

- ✅ Done: Add Room runtime, KSP/compiler configuration, and test dependencies to `android-app`.
- ✅ Done: Create entities, type converters, DAOs, database, and repository implementation.
- ✅ Done: Extend the credential store with put/remove behavior.
- ✅ Done: Implement atomic selected-profile clearing during deletion.
- ✅ Done: Add repository tests using an in-memory Room database.
- ✅ Done: Add credential-store contract coverage with fake stores; tests never print real secrets.

Exit criteria:

- Multiple profiles and selection state survive repository recreation.
- Profile metadata contains no API-key value.
- Delete behavior is deterministic for selected and unselected profiles.

### Phase 3: Legacy migration

Status: ✅ Done

- ✅ Done: Implement the bootstrap migrator from the existing `SharedPreferences` and fixed credential alias.
- ✅ Done: Start migration before repository-backed UI state is presented.
- ✅ Done: Cover no-legacy-data, valid legacy data, disabled legacy data, invalid legacy data, credential copy, partial-failure retry, and repeated-run cases.
- ✅ Done: Seed the bundled LM Studio Gemma profile once on clean installs and upgrades, without selecting it, overwriting an existing profile, or duplicating an equivalent profile.
- ✅ Done: Retain legacy values for rollback; eventual key cleanup is deferred to a separately tested future database release.

Exit criteria:

- A current installation upgrades without losing its saved LM Studio configuration or key.
- Repeated startup never creates duplicate migrated profiles.

### Phase 4: ViewModel and provider resolution

Status: ✅ Done

- ✅ Done: Add `ModelProfilesViewModel` and immutable UI-state/event models.
- ✅ Done: Implement list, create, edit, validate, test, save, select, remote-toggle, delete, and cancel flows.
- ✅ Done: Add `RemoteModelClientFactory` and selected-provider resolver.
- ✅ Done: Refactor receipt extraction to use a saved selected-profile snapshot.
- ✅ Done: Ensure disabled remote access and missing/deleted selections resolve locally.
- ✅ Done: Add ViewModel and resolver unit tests with fake repositories, credential stores, clocks, and clients.

Exit criteria:

- All management behavior is testable without Compose or Android storage.
- The next extraction uses the selected saved profile and never an editor draft.

### Phase 5: Compose profile management UI

Status: ✅ Done

- ✅ Done: Extract the Model Selector from `MainActivity` into focused composables and navigation state.
- ✅ Done: Build the selector summary, saved-profile list, empty state, editor, validation, test status, deletion confirmation, and unsaved-change confirmation.
- ✅ Done: Preserve the existing receipt picker and review flow.
- ✅ Done: Add a Compose UI test for the primary create-and-select flow and compile the instrumented test APK.
- ✅ Done: Add field labels, scroll support, and password masking. Runtime keyboard/accessibility inspection remains part of device verification.

Exit criteria:

- A user can manage at least two profiles and clearly see which provider the next extraction will use.
- Save and Test are separate, predictable operations.

### Phase 6: Integration and device verification

Status: ✅ Done — shared tests, Android unit tests, Compose-test compilation, debug assembly, and the multiple-profile workflow on a physical Pixel 7 are verified.

- ✅ Done: Build and run shared JVM tests and Android app unit tests; compile the Compose/instrumented test and assemble the debug APK.
- On the Pixel 8a emulator, create two profiles pointing to distinguishable test models/endpoints, restart the app, switch between them, and test each provider.
- ✅ Done: On the physical Pixel 7, the user successfully verified the multiple-profile workflow.
- Verify process death/relaunch persistence, remote-disable fallback, selected-profile deletion fallback, invalid credential recovery, and migration from a build containing the legacy settings.
- Confirm API keys do not appear in Logcat, Room inspection, exception messages, provider status text, or screenshots produced by automated tests.

Exit criteria:

- The feature satisfies the new requirements and existing `REQ-M-008` through `REQ-M-014`.
- Device evidence confirms profile persistence and selection affect real extraction.

### Phase 7: Documentation and completion tracking

Status: ✅ Done

- ✅ Done: Update `docs/current status.md` with verified behavior and the lack of an attached verification device.
- ✅ Done: Update the Model Selector section in `docs/gap-closing-plan-details.md`.
- ✅ Done: Update `docs/PLAN.md` for the Room-backed multi-profile architecture.
- ✅ Done: Record deferred duplicate, import/export, reordering, custom-header, and device-verification follow-ups.
- ✅ Done: Mark implemented tasks ✅ Done after unit-test and requirement verification passed.

## Test Matrix

### Domain tests

- Valid and invalid display names, URLs, model IDs, input modes, and structured-output formats.
- URL trimming and trailing-slash normalization.
- Stable ID and credential-alias mapping.
- Remote selection when enabled and local fallback when disabled or missing.
- Conversion from profile to `OpenAiCompatibleProviderOption`.

### Repository tests

- Create and observe several profiles.
- Update one profile without changing its ID or credential alias.
- Deterministic list ordering.
- Persist and restore selected profile and remote-enabled state.
- Delete unselected profile.
- Delete selected profile and atomically clear selection.
- Reject or safely handle a dangling selection.

### Credential tests

- Different profiles use different aliases.
- Updating metadata does not replace a stored key.
- Entering a replacement key updates only the targeted alias.
- Clear and delete remove only the targeted credential.
- Profile entities, serialized state, and diagnostic messages contain no secret.

### Migration tests

- Clean install creates no accidental remote profile.
- Valid legacy configuration becomes exactly one profile.
- Legacy remote opt-in and selection are preserved safely.
- Disabled legacy configuration remains disabled.
- Existing credential is copied to the generated alias.
- Invalid legacy configuration is not activated.
- Interrupted migration can retry.
- Repeated migration is idempotent.

### ViewModel and UI tests

- Empty state and Add profile.
- Create, cancel, save, and save-and-select.
- Edit without erasing an unchanged API key.
- Field validation and disabled Save.
- Test unsaved draft without persisting or selecting it.
- Test success and mapped failure states.
- Switch between two saved profiles.
- Remote opt-in and privacy disclosure.
- Delete confirmation and selected-profile fallback.
- Unsaved-change confirmation.
- Effective-provider summary always matches resolver output.

### Integration tests

- App restart preserves profiles and selection.
- Extraction uses the selected profile's URL, model ID, input mode, format, and credential alias.
- Changing selection changes the next extraction but not an in-flight extraction.
- Disabling remote access prevents all remote receipt requests.
- Legacy upgrade preserves the current LM Studio setup.

## Suggested File Boundaries

Exact package boundaries may be adjusted during implementation, but responsibilities should remain separate:

- Shared domain: `src/main/kotlin/com/hugo/smartexpense/extraction/ModelProfile.kt`
- Android data: `android-app/src/main/kotlin/com/hugo/smartexpense/app/modelprofile/data/`
- Android domain/orchestration: `android-app/src/main/kotlin/com/hugo/smartexpense/app/modelprofile/domain/`
- Android UI/ViewModel: `android-app/src/main/kotlin/com/hugo/smartexpense/app/modelprofile/ui/`
- Migration: `android-app/src/main/kotlin/com/hugo/smartexpense/app/modelprofile/migration/`
- Tests mirror the production packages under `src/test` and `android-app/src/test`; device-only UI/storage verification belongs under `android-app/src/androidTest`.

## Risks and Mitigations

- Room setup increases the first increment's scope. Mitigation: keep repository contracts storage-agnostic and deliver persistence before UI refactoring.
- Metadata and encrypted credentials cannot be committed in one database transaction. Mitigation: define operation ordering, retryable cleanup, and migration markers explicitly.
- Deleting or editing a selected profile could race with extraction. Mitigation: resolve an immutable provider snapshot before starting extraction.
- Testing a draft contacts a remote endpoint without saving. Mitigation: require the existing remote privacy disclosure/opt-in before network activity and label the action clearly.
- Provider compatibility differs. Mitigation: persist structured-output format and input mode per profile and retain existing mapped provider-test failures.
- A failed migration could duplicate or lose settings. Mitigation: use a stable migration ID/marker, retain legacy data for rollback, and test partial failures and retries.
- Compose state can accidentally retain secrets. Mitigation: keep secret input ephemeral, mask it, avoid saved-state persistence, and clear it when the editor closes.

## Definition of Done

The feature is ✅ Done only when:

- The requirements additions are committed and verified.
- Users can create, view, edit, test, save, select, and delete multiple remote profiles.
- Profile metadata, selection, remote opt-in, and separate credentials persist across app restarts.
- The selected saved profile drives the next receipt extraction.
- Remote-disabled, missing-profile, and deleted-selected-profile cases fall back locally without a remote receipt request.
- Existing single-profile installations migrate idempotently without losing valid settings or credentials.
- Unit, repository, migration, ViewModel, UI, integration, and debug assembly checks pass in proportion to their environment.
- API keys are absent from plain persistence and logs.
- Behavior is verified against `docs/requirements.md`.
- `docs/current status.md` and `docs/gap-closing-plan-details.md` are updated, and completed implementation tasks are marked ✅ Done.
