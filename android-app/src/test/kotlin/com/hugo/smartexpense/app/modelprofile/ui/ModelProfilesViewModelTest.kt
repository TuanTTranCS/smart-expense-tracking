package com.hugo.smartexpense.app.modelprofile.ui

import com.hugo.smartexpense.app.modelprofile.domain.AvailableProviderModel
import com.hugo.smartexpense.app.modelprofile.domain.ProviderModelCatalogService
import com.hugo.smartexpense.app.modelprofile.domain.ProviderModelCatalogResult
import com.hugo.smartexpense.app.modelprofile.domain.SelectedReceiptModelProviderResolver
import com.hugo.smartexpense.app.modelprofile.migration.FakeRepository
import com.hugo.smartexpense.app.modelprofile.transfer.ProviderConfigExportV1
import com.hugo.smartexpense.app.modelprofile.transfer.ProviderConfigJsonCodec
import com.hugo.smartexpense.app.modelprofile.transfer.ProviderConfigProfileV1
import com.hugo.smartexpense.app.modelprofile.transfer.ProviderConfigSelectorV1
import com.hugo.smartexpense.app.modelprofile.transfer.ProviderConfigTransferService
import com.hugo.smartexpense.extraction.ModelProfile
import com.hugo.smartexpense.extraction.ModelProfileDraft
import com.hugo.smartexpense.extraction.ProviderTestResult
import com.hugo.smartexpense.extraction.RemoteInputMode
import com.hugo.smartexpense.extraction.RemoteStructuredOutputFormat
import com.hugo.smartexpense.extraction.WritableApiKeyStore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import com.hugo.smartexpense.extraction.ModelProfileRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ModelProfilesViewModelTest {
    @Test fun editorPersistsAndRestoresPerProfileTailscalePermissionWithoutSelecting() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = viewModel(repository, TestCredentials())
        vm.addProfile()
        vm.updateDraft(ModelProfileDraft(displayName = "Tailnet", baseUrl = "https://example.test/v1", modelId = "model", showTailscaleToggle = true))
        vm.saveProfile()
        advanceUntilIdle()
        val saved = repository.profiles.value.single()
        assertTrue(saved.showTailscaleToggle)
        assertEquals(null, repository.selector.value.selectedRemoteProfileId)
        assertEquals(false, repository.selector.value.remoteProvidersEnabled)
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.editProfile(saved)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.editor!!.draft.showTailscaleToggle)
        vm.updateDraft(vm.uiState.value.editor!!.draft.copy(showTailscaleToggle = false))
        vm.saveProfile()
        advanceUntilIdle()
        assertEquals(false, repository.profiles.value.single().showTailscaleToggle)
        collection.cancel()
    }
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun saveAndSelectPersistsNormalizedMetadataAndTargetCredential() = runTest(dispatcher) {
        val repository = FakeRepository()
        val credentials = TestCredentials()
        val viewModel = viewModel(repository, credentials)
        viewModel.addProfile()
        viewModel.updateDraft(
            ModelProfileDraft(
                displayName = "  Home server ", baseUrl = " https://example.test/v1/ ", modelId = " model-a ",
                apiKey = "secret", inputMode = RemoteInputMode.DIRECT_IMAGE,
            ),
        )
        viewModel.saveProfile(selectAfterSave = true)
        advanceUntilIdle()

        val saved = repository.profiles.value.single()
        assertEquals("Home server", saved.displayName)
        assertEquals("https://example.test/v1", saved.baseUrl)
        assertEquals("profile-id", repository.selector.value.selectedRemoteProfileId)
        assertTrue(repository.selector.value.remoteProvidersEnabled)
        assertEquals("secret", credentials.values[saved.credentialAlias])
    }

    @Test fun metadataEditRetainsAnUntouchedStoredCredential() = runTest(dispatcher) {
        val repository = FakeRepository()
        val existing = profile()
        repository.profiles.value = listOf(existing)
        val credentials = TestCredentials(mutableMapOf(existing.credentialAlias to "kept-secret"))
        val viewModel = viewModel(repository, credentials)
        viewModel.editProfile(existing)
        viewModel.updateDraft(
            ModelProfileDraft(
                id = existing.id, displayName = "Renamed", baseUrl = existing.baseUrl, modelId = existing.modelId,
                inputMode = existing.inputMode, structuredOutputFormat = existing.structuredOutputFormat,
            ),
        )
        viewModel.saveProfile()
        advanceUntilIdle()
        assertEquals("kept-secret", credentials.values[existing.credentialAlias])
    }

    @Test fun testingAnUnsavedDraftDoesNotPersistOrSelectIt() = runTest(dispatcher) {
        val repository = FakeRepository()
        val credentials = TestCredentials()
        var tests = 0
        val viewModel = viewModel(repository, credentials) { _, _ -> tests++; ProviderTestResult.Success }
        viewModel.addProfile()
        viewModel.updateDraft(ModelProfileDraft(displayName = "Test", baseUrl = "https://example.test/v1", modelId = "model"))
        viewModel.testProfile()
        advanceUntilIdle()
        assertEquals(1, tests)
        assertTrue(repository.profiles.value.isEmpty())
        assertEquals(null, repository.selector.value.selectedRemoteProfileId)
    }

    @Test fun importIsPreviewedThenCancelledWithoutMutation() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = viewModel(repository, TestCredentials())
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        viewModel.loadProviderConfigImport(importJson())
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.importPreview?.profileCount)
        assertTrue(repository.profiles.value.isEmpty())

        viewModel.cancelProviderConfigImport()
        advanceUntilIdle()
        assertEquals(null, viewModel.uiState.value.importPreview)
        assertTrue(repository.profiles.value.isEmpty())
        collection.cancel()
    }

    @Test fun confirmedImportKeepsSelectorAndReportsMissingCredentials() = runTest(dispatcher) {
        val repository = FakeRepository()
        repository.selector.value = repository.selector.value.copy(remoteProvidersEnabled = true)
        val transfer = ProviderConfigTransferService(
            repository,
            idFactory = { "copy-id" },
            clock = { 200 },
        )
        val viewModel = viewModel(repository, TestCredentials(), transferService = transfer)
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        viewModel.loadProviderConfigImport(importJson())
        advanceUntilIdle()
        viewModel.confirmProviderConfigImport()
        advanceUntilIdle()

        assertEquals(listOf("imported-id"), repository.profiles.value.map { it.id })
        assertTrue(repository.selector.value.remoteProvidersEnabled)
        assertEquals(null, repository.selector.value.selectedRemoteProfileId)
        assertTrue(viewModel.uiState.value.message.orEmpty().contains("without credentials"))
        collection.cancel()
    }

    @Test fun invalidImportSurfacesAnActionableMessageWithoutMutation() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = viewModel(repository, TestCredentials())
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

        viewModel.loadProviderConfigImport("not-json")
        advanceUntilIdle()

        assertTrue(repository.profiles.value.isEmpty())
        assertTrue(viewModel.uiState.value.message.orEmpty().contains("valid provider configuration"))
        collection.cancel()
    }

    @Test fun verifyingLocalProviderRunsModelReadinessCheck() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = viewModel(repository, TestCredentials())
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        var checks = 0

        viewModel.verifySelectedProvider {
            checks++
            LocalModelReadinessResult.Ready("model.litertlm", 5L * 1024 * 1024)
        }
        advanceUntilIdle()

        assertEquals(1, checks)
        assertEquals("local-gemma-4-e2b", viewModel.uiState.value.verification?.providerId)
        assertTrue(viewModel.uiState.value.verification?.message.orEmpty().contains("ready"))
        collection.cancel()
    }

    @Test fun verifyingSelectedRemoteUsesSavedProfile() = runTest(dispatcher) {
        val repository = FakeRepository()
        val saved = profile()
        repository.profiles.value = listOf(saved)
        repository.selector.value = repository.selector.value.copy(
            remoteProvidersEnabled = true,
            selectedRemoteProfileId = saved.id,
        )
        var tested: ModelProfile? = null
        val viewModel = viewModel(repository, TestCredentials()) { profile, _ ->
            tested = profile
            ProviderTestResult.Success
        }
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.verifySelectedProvider { error("local readiness must not run") }
        advanceUntilIdle()

        assertEquals(saved, tested)
        assertTrue(viewModel.uiState.value.verification?.message.orEmpty().contains("Connected"))
        collection.cancel()
    }

    @Test fun discoveryNeedsOnlyEndpointAndSelectsExactIdInDraftWithoutSaving() = runTest(dispatcher) {
        val repository = FakeRepository()
        var requests = 0
        val vm = viewModel(repository, TestCredentials(), catalog = ProviderModelCatalogService { url, key ->
            requests++
            assertEquals("https://example.test/v1", url)
            assertEquals(null, key)
            ProviderModelCatalogResult.Loaded(listOf(AvailableProviderModel("org/Model:V2")))
        })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.addProfile()
        vm.updateDraft(ModelProfileDraft(baseUrl = "https://example.test/v1"))
        vm.loadModels()
        advanceUntilIdle()
        assertEquals(1, requests)
        assertTrue(vm.uiState.value.editor!!.catalogState is ModelCatalogState.Loaded)
        assertEquals("", vm.uiState.value.editor!!.draft.modelId)
        vm.selectCatalogModel("org/Model:V2")
        advanceUntilIdle()
        assertEquals("org/Model:V2", vm.uiState.value.editor!!.draft.modelId)
        assertEquals(null, vm.uiState.value.editor!!.validationErrors[com.hugo.smartexpense.extraction.ModelProfileField.MODEL_ID])
        assertEquals(null, vm.uiState.value.editor!!.testStatus)
        assertTrue(repository.profiles.value.isEmpty())
        assertEquals(false, repository.selector.value.remoteProvidersEnabled)
    }

    @Test fun discoveryUsesActualSavedAliasThenDraftOverrideAndClearWithoutSavingDraft() = runTest(dispatcher) {
        val repository = FakeRepository()
        val saved = profile().copy(credentialAlias = "legacy-actual-alias")
        repository.profiles.value = listOf(saved)
        val keys = mutableListOf<String?>()
        val credentials = TestCredentials(mutableMapOf(saved.credentialAlias to "stored-secret"))
        val vm = viewModel(repository, credentials, catalog = ProviderModelCatalogService { _, key ->
            keys += key
            ProviderModelCatalogResult.Loaded(emptyList())
        })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.editProfile(saved)
        vm.loadModels()
        advanceUntilIdle()
        vm.updateDraft(vm.uiState.value.editor!!.draft.copy(apiKey = "draft-secret"))
        vm.loadModels()
        advanceUntilIdle()
        vm.clearCredential()
        advanceUntilIdle()
        vm.loadModels()
        advanceUntilIdle()
        assertEquals(listOf("stored-secret", "draft-secret", null), keys)
        assertEquals(null, credentials.values[saved.credentialAlias])
        assertEquals(saved, repository.profiles.value.single())
    }

    @Test fun invalidDiscoveryInputsNeverExposeEndpointOrCallCatalog() = runTest(dispatcher) {
        val vm = viewModel(FakeRepository(), TestCredentials(), catalog = ProviderModelCatalogService { _, _ -> error("must not load") })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.addProfile()
        vm.updateDraft(ModelProfileDraft(baseUrl = "https://user:secret@example.test/v1"))
        vm.loadModels()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.editor!!.catalogState is ModelCatalogState.Failed)
        assertEquals(null, vm.uiState.value.editor!!.catalogEndpoint)
    }

    @Test fun emptyAndFailedListingsKeepManualEntryAndSeparateInferenceStatus() = runTest(dispatcher) {
        var response: ProviderModelCatalogResult = ProviderModelCatalogResult.Loaded(emptyList())
        val vm = viewModel(FakeRepository(), TestCredentials(), catalog = ProviderModelCatalogService { _, _ -> response })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.addProfile()
        vm.updateDraft(ModelProfileDraft(displayName = "Manual", baseUrl = "https://example.test/v1", modelId = "manual-id"))
        vm.testProfile()
        advanceUntilIdle()
        vm.loadModels()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.editor!!.catalogState is ModelCatalogState.Empty)
        assertTrue(vm.uiState.value.editor!!.testStatus!!.contains("Connected"))
        response = ProviderModelCatalogResult.Failed("Access denied")
        vm.loadModels()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.editor!!.catalogState is ModelCatalogState.Failed)
        assertEquals("manual-id", vm.uiState.value.editor!!.draft.modelId)
    }

    @Test fun lateCancellationIgnoringResponseCannotReplaceChangedEndpointOrRetainedDraft() = runTest(dispatcher) {
        lateinit var continuation: Continuation<ProviderModelCatalogResult>
        val vm = viewModel(FakeRepository(), TestCredentials(), catalog = ProviderModelCatalogService { _, _ ->
            suspendCoroutine { continuation = it }
        })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.addProfile()
        vm.updateDraft(ModelProfileDraft(baseUrl = "https://first.test/v1", modelId = "manual"))
        vm.loadModels()
        runCurrent()
        vm.updateDraft(vm.uiState.value.editor!!.draft.copy(baseUrl = "https://second.test/v1"))
        vm.cancelModelDiscovery()
        continuation.resume(ProviderModelCatalogResult.Loaded(listOf(AvailableProviderModel("stale"))))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.editor!!.catalogState is ModelCatalogState.Idle)
        assertEquals("https://second.test/v1", vm.uiState.value.editor!!.draft.baseUrl)
        assertEquals("manual", vm.uiState.value.editor!!.draft.modelId)
    }

    @Test fun lifecycleCancelsLoadingKeepsDraftAndCredentialChangeClearsResult() = runTest(dispatcher) {
        val result = CompletableDeferred<ProviderModelCatalogResult>()
        val vm = viewModel(FakeRepository(), TestCredentials(), catalog = ProviderModelCatalogService { _, _ -> result.await() })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.addProfile()
        vm.updateDraft(ModelProfileDraft(baseUrl = "https://example.test/v1", apiKey = "draft-key"))
        vm.loadModels()
        runCurrent()
        vm.cancelModelDiscovery()
        runCurrent()
        vm.cancelModelDiscovery()
        runCurrent()
        assertTrue(vm.uiState.value.editor!!.catalogState is ModelCatalogState.Cancelled)
        assertEquals("draft-key", vm.uiState.value.editor!!.draft.apiKey)
        vm.updateDraft(vm.uiState.value.editor!!.draft.copy(apiKey = "replacement"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.editor!!.catalogState is ModelCatalogState.Idle)
    }

    @Test fun saveAcquiresBusyBeforeLaunchAndIgnoresRepeatedActivationAndEdit() = runTest(dispatcher) {
        val repository = FakeRepository()
        var ids = 0
        val vm = ModelProfilesViewModel(repository, TestCredentials(), SelectedReceiptModelProviderResolver(repository),
            ModelProfileTestService { _, _ -> ProviderTestResult.Success }, idFactory = { "id-${++ids}" })
        vm.addProfile()
        vm.updateDraft(ModelProfileDraft(displayName = "Saved", baseUrl = "https://example.test/v1", modelId = "model"))
        vm.saveProfile()
        vm.saveProfile()
        vm.addProfile()
        advanceUntilIdle()
        assertEquals(1, ids)
        assertEquals(1, repository.profiles.value.size)
    }

    @Test fun overlappingRequestsAndCredentialChangesIgnoreLateResponses() = runTest(dispatcher) {
        val requests = mutableListOf<Continuation<ProviderModelCatalogResult>>()
        val vm = viewModel(FakeRepository(), TestCredentials(), catalog = ProviderModelCatalogService { _, _ ->
            suspendCoroutine { requests += it }
        })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.addProfile()
        vm.updateDraft(ModelProfileDraft(baseUrl = "https://example.test/v1"))
        vm.loadModels()
        runCurrent()
        vm.loadModels()
        runCurrent()
        requests[1].resume(ProviderModelCatalogResult.Loaded(listOf(AvailableProviderModel("current"))))
        runCurrent()
        requests[0].resume(ProviderModelCatalogResult.Loaded(listOf(AvailableProviderModel("stale"))))
        runCurrent()
        assertEquals("current", (vm.uiState.value.editor!!.catalogState as ModelCatalogState.Loaded).models.single().id)
        vm.loadModels()
        runCurrent()
        vm.updateDraft(vm.uiState.value.editor!!.draft.copy(apiKey = "changed-key"))
        requests[2].resume(ProviderModelCatalogResult.Loaded(listOf(AvailableProviderModel("stale-key"))))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.editor!!.catalogState is ModelCatalogState.Idle)
        assertEquals("changed-key", vm.uiState.value.editor!!.draft.apiKey)
    }

    @Test fun choosingModelInvalidatesInferenceTestAndSaveFeedsNextProviderSnapshot() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = viewModel(repository, TestCredentials(), catalog = ProviderModelCatalogService { _, _ ->
            ProviderModelCatalogResult.Loaded(listOf(AvailableProviderModel("org/New:V2")))
        })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.addProfile()
        vm.updateDraft(ModelProfileDraft(displayName = "Provider", baseUrl = "https://example.test/v1", modelId = "old"))
        vm.testProfile()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.editor!!.testStatus!!.contains("Connected"))
        vm.loadModels()
        advanceUntilIdle()
        vm.selectCatalogModel("org/New:V2")
        advanceUntilIdle()
        assertEquals(null, vm.uiState.value.editor!!.testStatus)
        assertEquals(null, repository.selector.value.selectedRemoteProfileId)
        vm.saveProfile(selectAfterSave = true)
        advanceUntilIdle()
        val next = SelectedReceiptModelProviderResolver(repository).resolve(repository.profiles.value, repository.selector.value)
        assertEquals("org/New:V2", next.remoteProfile!!.modelId)
    }

    @Test fun duplicationDoubleTapSerializesAgainstEditorAndAllMutations() = runTest(dispatcher) {
        val backing = FakeRepository()
        val saved = profile()
        backing.profiles.value = listOf(saved)
        backing.selector.value = backing.selector.value.copy(remoteProvidersEnabled = true, selectedRemoteProfileId = saved.id)
        val selector = backing.selector.value
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var inserts = 0
        val repository = object : ModelProfileRepository by backing {
            override suspend fun credentialAliasInUse(alias: String) = backing.profiles.value.any { it.credentialAlias == alias }
            override suspend fun insertDuplicate(sourceSnapshot: ModelProfile, copy: ModelProfile) {
                inserts++
                entered.complete(Unit)
                release.await()
                check(backing.getProfile(sourceSnapshot.id) == sourceSnapshot)
                check(backing.getProfile(copy.id) == null)
                backing.profiles.value = backing.profiles.value + copy
            }
        }
        val vm = ModelProfilesViewModel(repository, TestCredentials(), SelectedReceiptModelProviderResolver(repository),
            ModelProfileTestService { _, _ -> ProviderTestResult.Success }, idFactory = { "copied-id" })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.duplicateProfile(saved.id)
        vm.duplicateProfile(saved.id)
        vm.editProfile(saved)
        vm.saveProfile()
        vm.deleteProfile(saved)
        vm.loadProviderConfigImport(importJson())
        vm.clearCredential()
        runCurrent()
        withContext(Dispatchers.Default) { withTimeout(5_000) { entered.await() } }
        assertEquals(null, vm.uiState.value.editor)
        assertEquals(listOf(saved), backing.profiles.value)
        release.complete(Unit)
        runCurrent()
        withContext(Dispatchers.Default) { withTimeout(5_000) { vm.uiState.first { !it.busy && it.profiles.size == 2 } } }
        assertEquals(1, inserts)
        assertEquals(selector, backing.selector.value)
        assertEquals(null, vm.uiState.value.importPreview)
    }

    private fun viewModel(
        repository: FakeRepository,
        credentials: TestCredentials,
        transferService: ProviderConfigTransferService = ProviderConfigTransferService(repository),
        catalog: ProviderModelCatalogService = ProviderModelCatalogService { _, _ -> ProviderModelCatalogResult.Loaded(emptyList()) },
        tester: ModelProfileTestService = ModelProfileTestService { _, _ -> ProviderTestResult.Success },
    ) = ModelProfilesViewModel(
        repository, credentials, SelectedReceiptModelProviderResolver(repository), tester,
        transferService = transferService,
        idFactory = { "profile-id" }, clock = { 100 },
        catalogService = catalog, discoveryDispatcher = dispatcher,
    )

    private fun importJson(): String = ProviderConfigJsonCodec().encode(
        ProviderConfigExportV1(
            exportedAt = "2026-09-09T12:00:00Z",
            selector = ProviderConfigSelectorV1(true, "imported-id"),
            profiles = listOf(
                ProviderConfigProfileV1(
                    "imported-id", "Imported", "https://example.test/v1", "model",
                    RemoteInputMode.DIRECT_IMAGE, RemoteStructuredOutputFormat.JSON_SCHEMA,
                ),
            ),
        ),
    )

    private fun profile() = ModelProfile(
        "profile-id", "Provider", "https://example.test/v1", "model", RemoteInputMode.DIRECT_IMAGE,
        RemoteStructuredOutputFormat.JSON_SCHEMA, ModelProfile.credentialAlias("profile-id"), 1, 1,
    )
}

private class TestCredentials(val values: MutableMap<String, String> = mutableMapOf()) : WritableApiKeyStore {
    override fun get(alias: String) = values[alias]
    override fun put(alias: String, value: String) { values[alias] = value }
    override fun remove(alias: String) { values.remove(alias) }
}
