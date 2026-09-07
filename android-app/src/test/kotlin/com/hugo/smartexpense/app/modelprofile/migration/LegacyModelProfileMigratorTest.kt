package com.hugo.smartexpense.app.modelprofile.migration

import com.hugo.smartexpense.extraction.LmStudioTestProfile
import com.hugo.smartexpense.extraction.ModelProfile
import com.hugo.smartexpense.extraction.ModelProfileRepository
import com.hugo.smartexpense.extraction.ModelProfileSelectorState
import com.hugo.smartexpense.extraction.WritableApiKeyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyModelProfileMigratorTest {
    @Test fun cleanInstallCreatesBundledLmStudioProfileWithoutSelectingIt() = runTest {
        val preferences = FakePreferences()
        val repository = FakeRepository()
        val credentials = FakeCredentials()
        val result = migrator(preferences, repository, credentials).migrate()
        assertIs<LegacyMigrationResult.NoLegacyData>(result)
        val profile = repository.profiles.value.single()
        assertEquals(LmStudioTestProfile.providerId, profile.id)
        assertEquals(LmStudioTestProfile.displayName, profile.displayName)
        assertEquals(LmStudioTestProfile.baseUrl, profile.baseUrl)
        assertEquals(LmStudioTestProfile.modelId, profile.modelId)
        assertEquals(
            LmStudioTestProfile.placeholderApiKey,
            credentials.values[ModelProfile.credentialAlias(LmStudioTestProfile.providerId)],
        )
        assertFalse(repository.selector.value.remoteProvidersEnabled)
        assertNull(repository.selector.value.selectedRemoteProfileId)
    }

    @Test fun completedOlderBootstrapAddsBundledProfileAlongsideUserProfileExactlyOnce() = runTest {
        val preferences = FakePreferences(mutableMapOf(LegacyModelProfileMigrator.MIGRATION_COMPLETE to true))
        val repository = FakeRepository()
        repository.profiles.value = listOf(
            ModelProfile(
                "open-router", "OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-oss-20b",
                com.hugo.smartexpense.extraction.RemoteInputMode.DIRECT_IMAGE,
                com.hugo.smartexpense.extraction.RemoteStructuredOutputFormat.JSON_SCHEMA,
                ModelProfile.credentialAlias("open-router"), 1, 1,
            ),
        )
        val migrator = migrator(preferences, repository, FakeCredentials())

        assertIs<LegacyMigrationResult.AlreadyComplete>(migrator.migrate())
        assertIs<LegacyMigrationResult.AlreadyComplete>(migrator.migrate())
        assertEquals(listOf("lm-studio-gemma", "open-router"), repository.profiles.value.map { it.id }.sorted())
    }

    @Test fun equivalentExistingLmStudioProfileIsNotDuplicated() = runTest {
        val preferences = FakePreferences(mutableMapOf(LegacyModelProfileMigrator.MIGRATION_COMPLETE to true))
        val repository = FakeRepository()
        repository.profiles.value = listOf(
            ModelProfile(
                "user-lm-studio", "My LM Studio", "${LmStudioTestProfile.baseUrl}/", LmStudioTestProfile.modelId,
                com.hugo.smartexpense.extraction.RemoteInputMode.DIRECT_IMAGE,
                com.hugo.smartexpense.extraction.RemoteStructuredOutputFormat.JSON_SCHEMA,
                ModelProfile.credentialAlias("user-lm-studio"), 1, 1,
            ),
        )
        migrator(preferences, repository, FakeCredentials()).migrate()
        assertEquals(listOf("user-lm-studio"), repository.profiles.value.map { it.id })
    }

    @Test fun editedBundledProfileIsNotOverwrittenOnUpgrade() = runTest {
        val preferences = FakePreferences(mutableMapOf(LegacyModelProfileMigrator.MIGRATION_COMPLETE to true))
        val repository = FakeRepository()
        val edited = ModelProfile(
            LmStudioTestProfile.providerId, "My edited server", "http://192.168.1.50:1234/v1", "custom-model",
            com.hugo.smartexpense.extraction.RemoteInputMode.DIRECT_IMAGE,
            com.hugo.smartexpense.extraction.RemoteStructuredOutputFormat.JSON_SCHEMA,
            ModelProfile.credentialAlias(LmStudioTestProfile.providerId), 1, 2,
        )
        repository.profiles.value = listOf(edited)
        migrator(preferences, repository, FakeCredentials()).migrate()
        assertEquals(edited, repository.profiles.value.single())
    }

    @Test fun validLegacyConfigurationAndCredentialMigrateExactlyOnce() = runTest {
        val preferences = FakePreferences(mutableMapOf(
            "base_url" to " http://10.0.2.2:1234/v1/ ", "model_id" to " legacy-model ",
            "input_mode" to "DIRECT_IMAGE", "remote_enabled" to true,
        ))
        val repository = FakeRepository()
        val credentials = FakeCredentials(mutableMapOf(LmStudioTestProfile.apiKeyAlias to "secret"))
        val migrator = migrator(preferences, repository, credentials)

        assertIs<LegacyMigrationResult.Migrated>(migrator.migrate())
        assertIs<LegacyMigrationResult.AlreadyComplete>(migrator.migrate())
        assertEquals(1, repository.profiles.value.size)
        assertEquals("http://10.0.2.2:1234/v1", repository.profiles.value.single().baseUrl)
        assertEquals("secret", credentials.values["remote-provider:stable-id"])
        assertEquals("stable-id", repository.selector.value.selectedRemoteProfileId)
    }

    @Test fun invalidLegacyDataIsNotActivatedOrDiscarded() = runTest {
        val preferences = FakePreferences(mutableMapOf("base_url" to "not-a-url", "model_id" to "model", "remote_enabled" to true))
        val repository = FakeRepository()
        val result = migrator(preferences, repository, FakeCredentials()).migrate()
        assertIs<LegacyMigrationResult.InvalidLegacyData>(result)
        assertFalse(repository.selector.value.remoteProvidersEnabled)
        assertNull(repository.selector.value.selectedRemoteProfileId)
        assertEquals("not-a-url", preferences.values["base_url"])
    }

    @Test fun disabledLegacyConfigurationIsSavedButNotSelected() = runTest {
        val preferences = FakePreferences(mutableMapOf(
            "base_url" to "https://example.test/v1", "model_id" to "model", "remote_enabled" to false,
        ))
        val repository = FakeRepository()
        assertIs<LegacyMigrationResult.Migrated>(migrator(preferences, repository, FakeCredentials()).migrate())
        assertEquals(1, repository.profiles.value.size)
        assertFalse(repository.selector.value.remoteProvidersEnabled)
        assertNull(repository.selector.value.selectedRemoteProfileId)
    }

    @Test fun credentialCopyFailureCanRetryWithoutCreatingDuplicates() = runTest {
        val preferences = FakePreferences(mutableMapOf(
            "base_url" to "https://example.test/v1", "model_id" to "model", "remote_enabled" to true,
        ))
        val repository = FakeRepository()
        val credentials = FailOnceCredentials(mutableMapOf(LmStudioTestProfile.apiKeyAlias to "secret"))
        val migrator = LegacyModelProfileMigrator(
            preferences, repository, credentials, idFactory = { "stable-id" }, clock = { 10 },
        )
        assertFailsWith<IllegalStateException> { migrator.migrate() }
        assertFalse(preferences.getBoolean(LegacyModelProfileMigrator.MIGRATION_COMPLETE, false))
        assertIs<LegacyMigrationResult.Migrated>(migrator.migrate())
        assertEquals(1, repository.profiles.value.size)
    }

    private fun migrator(p: FakePreferences, r: FakeRepository, c: FakeCredentials) =
        LegacyModelProfileMigrator(p, r, c, idFactory = { "stable-id" }, clock = { 10 })
}

private class FakePreferences(val values: MutableMap<String, Any> = mutableMapOf()) : LegacyModelProfilePreferences {
    override fun contains(key: String) = values.containsKey(key)
    override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
    override fun getString(key: String, default: String) = values[key] as? String ?: default
    override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    override fun putString(key: String, value: String) { values[key] = value }
}

private class FakeCredentials(val values: MutableMap<String, String> = mutableMapOf()) : WritableApiKeyStore {
    override fun get(alias: String) = values[alias]
    override fun put(alias: String, value: String) { values[alias] = value }
    override fun remove(alias: String) { values.remove(alias) }
}

private class FailOnceCredentials(private val values: MutableMap<String, String>) : WritableApiKeyStore {
    private var shouldFail = true
    override fun get(alias: String) = values[alias]
    override fun put(alias: String, value: String) {
        if (shouldFail) { shouldFail = false; error("copy failed") }
        values[alias] = value
    }
    override fun remove(alias: String) { values.remove(alias) }
}

internal class FakeRepository : ModelProfileRepository {
    val profiles = MutableStateFlow<List<ModelProfile>>(emptyList())
    val selector = MutableStateFlow(ModelProfileSelectorState())
    override fun observeProfiles() = profiles
    override fun observeSelectorState() = selector
    override suspend fun getProfile(id: String) = profiles.value.firstOrNull { it.id == id }
    override suspend fun saveProfile(profile: ModelProfile) {
        profiles.value = profiles.value.filterNot { it.id == profile.id } + profile
    }
    override suspend fun selectProfile(id: String?) { selector.value = selector.value.copy(selectedRemoteProfileId = id) }
    override suspend fun setRemoteProvidersEnabled(enabled: Boolean) { selector.value = selector.value.copy(remoteProvidersEnabled = enabled) }
    override suspend fun deleteProfile(id: String) {
        profiles.value = profiles.value.filterNot { it.id == id }
        if (selector.value.selectedRemoteProfileId == id) selectProfile(null)
    }
}
