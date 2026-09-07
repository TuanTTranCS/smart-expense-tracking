package com.hugo.smartexpense.app.modelprofile.ui

import com.hugo.smartexpense.app.modelprofile.domain.SelectedReceiptModelProviderResolver
import com.hugo.smartexpense.app.modelprofile.migration.FakeRepository
import com.hugo.smartexpense.extraction.ModelProfile
import com.hugo.smartexpense.extraction.ModelProfileDraft
import com.hugo.smartexpense.extraction.ProviderTestResult
import com.hugo.smartexpense.extraction.RemoteInputMode
import com.hugo.smartexpense.extraction.RemoteStructuredOutputFormat
import com.hugo.smartexpense.extraction.WritableApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
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

    private fun viewModel(
        repository: FakeRepository,
        credentials: TestCredentials,
        tester: ModelProfileTestService = ModelProfileTestService { _, _ -> ProviderTestResult.Success },
    ) = ModelProfilesViewModel(
        repository, credentials, SelectedReceiptModelProviderResolver(repository), tester,
        idFactory = { "profile-id" }, clock = { 100 },
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
