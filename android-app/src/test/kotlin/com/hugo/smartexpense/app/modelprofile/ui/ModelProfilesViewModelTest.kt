package com.hugo.smartexpense.app.modelprofile.ui

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ModelProfilesViewModelTest {
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

    private fun viewModel(
        repository: FakeRepository,
        credentials: TestCredentials,
        transferService: ProviderConfigTransferService = ProviderConfigTransferService(repository),
        tester: ModelProfileTestService = ModelProfileTestService { _, _ -> ProviderTestResult.Success },
    ) = ModelProfilesViewModel(
        repository, credentials, SelectedReceiptModelProviderResolver(repository), tester,
        transferService = transferService,
        idFactory = { "profile-id" }, clock = { 100 },
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
