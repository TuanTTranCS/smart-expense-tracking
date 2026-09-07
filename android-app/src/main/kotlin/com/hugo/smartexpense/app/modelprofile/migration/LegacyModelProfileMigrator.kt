package com.hugo.smartexpense.app.modelprofile.migration

import com.hugo.smartexpense.extraction.LmStudioTestProfile
import com.hugo.smartexpense.extraction.ModelProfile
import com.hugo.smartexpense.extraction.ModelProfileDraft
import com.hugo.smartexpense.extraction.ModelProfileRepository
import com.hugo.smartexpense.extraction.ModelProfileValidator
import com.hugo.smartexpense.extraction.RemoteInputMode
import com.hugo.smartexpense.extraction.RemoteStructuredOutputFormat
import com.hugo.smartexpense.extraction.WritableApiKeyStore
import java.util.UUID
import kotlinx.coroutines.flow.first

interface LegacyModelProfilePreferences {
    fun contains(key: String): Boolean
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getString(key: String, default: String): String
    fun putBoolean(key: String, value: Boolean)
    fun putString(key: String, value: String)
}

sealed interface LegacyMigrationResult {
    data object NoLegacyData : LegacyMigrationResult
    data object AlreadyComplete : LegacyMigrationResult
    data class Migrated(val profileId: String) : LegacyMigrationResult
    data class InvalidLegacyData(val reason: String) : LegacyMigrationResult
}

class LegacyModelProfileMigrator(
    private val preferences: LegacyModelProfilePreferences,
    private val repository: ModelProfileRepository,
    private val credentialStore: WritableApiKeyStore,
    private val validator: ModelProfileValidator = ModelProfileValidator(),
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun migrate(): LegacyMigrationResult {
        if (preferences.getBoolean(MIGRATION_COMPLETE, false)) {
            ensureBundledLmStudioProfile()
            return LegacyMigrationResult.AlreadyComplete
        }

        val hasLegacyData = LEGACY_KEYS.any(preferences::contains) ||
            credentialStore.get(LmStudioTestProfile.apiKeyAlias) != null
        if (!hasLegacyData) {
            ensureBundledLmStudioProfile()
            preferences.putBoolean(MIGRATION_COMPLETE, true)
            return LegacyMigrationResult.NoLegacyData
        }

        val draft = ModelProfileDraft(
            displayName = "LM Studio",
            baseUrl = preferences.getString(BASE_URL, LmStudioTestProfile.baseUrl),
            modelId = preferences.getString(MODEL_ID, LmStudioTestProfile.modelId),
            inputMode = runCatching {
                RemoteInputMode.valueOf(preferences.getString(INPUT_MODE, RemoteInputMode.DIRECT_IMAGE.name))
            }.getOrDefault(RemoteInputMode.DIRECT_IMAGE),
            structuredOutputFormat = RemoteStructuredOutputFormat.JSON_SCHEMA,
        )
        val validation = validator.validate(draft)
        if (!validation.isValid) {
            repository.setRemoteProvidersEnabled(false)
            return LegacyMigrationResult.InvalidLegacyData(validation.errors.values.joinToString(" "))
        }

        val profileId = preferences.getString(MIGRATED_PROFILE_ID, "").ifBlank {
            idFactory().also { preferences.putString(MIGRATED_PROFILE_ID, it) }
        }
        val now = clock()
        val existing = repository.getProfile(profileId)
        val alias = ModelProfile.credentialAlias(profileId)
        credentialStore.get(LmStudioTestProfile.apiKeyAlias)?.let { credentialStore.put(alias, it) }
        repository.saveProfile(
            ModelProfile(
                id = profileId,
                displayName = validation.normalizedDisplayName,
                baseUrl = validation.normalizedBaseUrl,
                modelId = validation.normalizedModelId,
                inputMode = draft.inputMode,
                structuredOutputFormat = draft.structuredOutputFormat,
                credentialAlias = alias,
                createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = existing?.updatedAtEpochMillis ?: now,
            ),
        )
        val enabled = preferences.getBoolean(REMOTE_ENABLED, false)
        repository.setRemoteProvidersEnabled(enabled)
        repository.selectProfile(profileId.takeIf { enabled })
        preferences.putBoolean(BUNDLED_LM_STUDIO_COMPLETE, true)
        preferences.putBoolean(MIGRATION_COMPLETE, true)
        return LegacyMigrationResult.Migrated(profileId)
    }

    private suspend fun ensureBundledLmStudioProfile() {
        if (preferences.getBoolean(BUNDLED_LM_STUDIO_COMPLETE, false)) return

        val migratedProfileId = preferences.getString(MIGRATED_PROFILE_ID, "")
        if (migratedProfileId.isNotBlank() && repository.getProfile(migratedProfileId) != null) {
            preferences.putBoolean(BUNDLED_LM_STUDIO_COMPLETE, true)
            return
        }

        val defaultBaseUrl = LmStudioTestProfile.baseUrl.trim().trimEnd('/')
        val matchingProfileExists = repository.observeProfiles().first().any { profile ->
            profile.baseUrl.trim().trimEnd('/').equals(defaultBaseUrl, ignoreCase = true) &&
                profile.modelId.trim() == LmStudioTestProfile.modelId
        }
        if (!matchingProfileExists) {
            val existing = repository.getProfile(LmStudioTestProfile.providerId)
            if (existing == null) {
                val now = clock()
                val alias = ModelProfile.credentialAlias(LmStudioTestProfile.providerId)
                credentialStore.put(alias, LmStudioTestProfile.placeholderApiKey)
                repository.saveProfile(
                    ModelProfile(
                        id = LmStudioTestProfile.providerId,
                        displayName = LmStudioTestProfile.displayName,
                        baseUrl = defaultBaseUrl,
                        modelId = LmStudioTestProfile.modelId,
                        inputMode = RemoteInputMode.DIRECT_IMAGE,
                        structuredOutputFormat = RemoteStructuredOutputFormat.JSON_SCHEMA,
                        credentialAlias = alias,
                        createdAtEpochMillis = now,
                        updatedAtEpochMillis = now,
                    ),
                )
            }
        }
        preferences.putBoolean(BUNDLED_LM_STUDIO_COMPLETE, true)
    }

    companion object {
        const val MIGRATION_COMPLETE = "model_profiles_migration_complete_v1"
        const val MIGRATED_PROFILE_ID = "model_profiles_migrated_profile_id_v1"
        const val BUNDLED_LM_STUDIO_COMPLETE = "bundled_lm_studio_profile_complete_v1"
        const val REMOTE_ENABLED = "remote_enabled"
        const val BASE_URL = "base_url"
        const val MODEL_ID = "model_id"
        const val INPUT_MODE = "input_mode"
        val LEGACY_KEYS = listOf(REMOTE_ENABLED, BASE_URL, MODEL_ID, INPUT_MODE)
    }
}
