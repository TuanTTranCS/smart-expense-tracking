package com.hugo.smartexpense.app.modelprofile.transfer

import com.hugo.smartexpense.extraction.ModelProfile
import com.hugo.smartexpense.extraction.ModelProfileDraft
import com.hugo.smartexpense.extraction.ModelProfileRepository
import com.hugo.smartexpense.extraction.ModelProfileSelectorState
import com.hugo.smartexpense.extraction.ModelProfileValidator
import com.hugo.smartexpense.extraction.RemoteInputMode
import com.hugo.smartexpense.extraction.RemoteStructuredOutputFormat
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONStringer

data class ProviderConfigProfileV1(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val modelId: String,
    val inputMode: RemoteInputMode,
    val structuredOutputFormat: RemoteStructuredOutputFormat,
)

data class ProviderConfigSelectorV1(
    val remoteProvidersEnabled: Boolean,
    val selectedRemoteProfileId: String?,
)

data class ProviderConfigExportV1(
    val exportedAt: String,
    val selector: ProviderConfigSelectorV1,
    val profiles: List<ProviderConfigProfileV1>,
)

data class ProviderConfigImportPreview(
    val config: ProviderConfigExportV1,
    val conflictingProfileIds: Set<String>,
) {
    val profileCount: Int get() = config.profiles.size
    val conflictCount: Int get() = conflictingProfileIds.size
}

data class ProviderConfigImportResult(
    val importedCount: Int,
    val copiedConflictCount: Int,
)

class ProviderConfigException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

class ProviderConfigJsonCodec(
    private val validator: ModelProfileValidator = ModelProfileValidator(),
) {
    fun encode(config: ProviderConfigExportV1): String {
        require(config.profiles.isNotEmpty()) { "At least one provider profile is required." }
        return JSONStringer()
            .`object`()
            .key("schemaVersion").value(SCHEMA_VERSION)
            .key("exportedAt").value(config.exportedAt)
            .key("credentialsIncluded").value(false)
            .key("selector").`object`()
            .key("remoteProvidersEnabled").value(config.selector.remoteProvidersEnabled)
            .key("selectedRemoteProfileId").value(config.selector.selectedRemoteProfileId ?: JSONObject.NULL)
            .endObject()
            .key("profiles").array()
            .also { writer ->
                config.profiles.forEach { profile ->
                    writer.`object`()
                        .key("id").value(profile.id)
                        .key("displayName").value(profile.displayName)
                        .key("baseUrl").value(profile.baseUrl)
                        .key("modelId").value(profile.modelId)
                        .key("inputMode").value(profile.inputMode.name)
                        .key("structuredOutputFormat").value(profile.structuredOutputFormat.name)
                        .endObject()
                }
            }
            .endArray()
            .endObject()
            .toString()
    }

    fun decode(json: String): ProviderConfigExportV1 {
        try {
            val root = JSONObject(json)
            val version = root.requiredInt("schemaVersion")
            if (version != SCHEMA_VERSION) {
                throw ProviderConfigException("Provider configuration schema version $version is not supported.")
            }
            if (root.requiredBoolean("credentialsIncluded")) {
                throw ProviderConfigException("Provider configuration files containing credentials are not supported.")
            }
            val exportedAt = root.requiredString("exportedAt")
            try {
                Instant.parse(exportedAt)
            } catch (error: DateTimeParseException) {
                throw ProviderConfigException("The provider configuration export timestamp is invalid.", error)
            }

            val selectorObject = root.requiredObject("selector")
            val selector = ProviderConfigSelectorV1(
                remoteProvidersEnabled = selectorObject.requiredBoolean("remoteProvidersEnabled"),
                selectedRemoteProfileId = selectorObject.optionalString("selectedRemoteProfileId"),
            )
            val profileArray = root.requiredArray("profiles")
            if (profileArray.length() == 0) {
                throw ProviderConfigException("The provider configuration file contains no profiles.")
            }
            val ids = mutableSetOf<String>()
            val profiles = buildList {
                repeat(profileArray.length()) { index ->
                    val profileObject = profileArray.opt(index) as? JSONObject
                        ?: throw ProviderConfigException("Provider profile ${index + 1} is not a JSON object.")
                    val id = profileObject.requiredString("id")
                    if (id.isBlank() || id != id.trim()) {
                        throw ProviderConfigException("Provider profile ${index + 1} has an invalid ID.")
                    }
                    if (!ids.add(id)) {
                        throw ProviderConfigException("Provider configuration contains duplicate profile ID '$id'.")
                    }
                    val inputMode = profileObject.requiredEnum<RemoteInputMode>("inputMode", index)
                    val outputFormat = profileObject.requiredEnum<RemoteStructuredOutputFormat>(
                        "structuredOutputFormat", index,
                    )
                    val validation = validator.validate(
                        ModelProfileDraft(
                            id = id,
                            displayName = profileObject.requiredString("displayName"),
                            baseUrl = profileObject.requiredString("baseUrl"),
                            modelId = profileObject.requiredString("modelId"),
                            inputMode = inputMode,
                            structuredOutputFormat = outputFormat,
                        ),
                    )
                    if (!validation.isValid) {
                        val details = validation.errors.values.joinToString(" ")
                        throw ProviderConfigException("Provider profile ${index + 1} is invalid. $details")
                    }
                    add(
                        ProviderConfigProfileV1(
                            id = id,
                            displayName = validation.normalizedDisplayName,
                            baseUrl = validation.normalizedBaseUrl,
                            modelId = validation.normalizedModelId,
                            inputMode = inputMode,
                            structuredOutputFormat = outputFormat,
                        ),
                    )
                }
            }
            val selectedId = selector.selectedRemoteProfileId
            if (selectedId != null && profiles.none { it.id == selectedId }) {
                throw ProviderConfigException("The exported selected profile does not exist in the profile list.")
            }
            return ProviderConfigExportV1(exportedAt, selector, profiles)
        } catch (error: ProviderConfigException) {
            throw error
        } catch (error: JSONException) {
            throw ProviderConfigException("The selected file is not a valid provider configuration JSON file.", error)
        }
    }

    private fun JSONObject.requiredString(name: String): String =
        (opt(name) as? String)?.takeIf { it.isNotEmpty() }
            ?: throw ProviderConfigException("Required string '$name' is missing or invalid.")

    private fun JSONObject.optionalString(name: String): String? {
        if (!has(name)) throw ProviderConfigException("Required field '$name' is missing.")
        if (isNull(name)) return null
        return (opt(name) as? String)?.takeIf { it.isNotBlank() }
            ?: throw ProviderConfigException("Field '$name' must be a non-blank string or null.")
    }

    private fun JSONObject.requiredBoolean(name: String): Boolean =
        opt(name) as? Boolean
            ?: throw ProviderConfigException("Required boolean '$name' is missing or invalid.")

    private fun JSONObject.requiredInt(name: String): Int {
        val value = opt(name)
        if (value !is Number || value.toDouble() != value.toInt().toDouble()) {
            throw ProviderConfigException("Required integer '$name' is missing or invalid.")
        }
        return value.toInt()
    }

    private fun JSONObject.requiredObject(name: String): JSONObject =
        opt(name) as? JSONObject
            ?: throw ProviderConfigException("Required object '$name' is missing or invalid.")

    private fun JSONObject.requiredArray(name: String): JSONArray =
        opt(name) as? JSONArray
            ?: throw ProviderConfigException("Required array '$name' is missing or invalid.")

    private inline fun <reified T : Enum<T>> JSONObject.requiredEnum(name: String, profileIndex: Int): T {
        val value = requiredString(name)
        return enumValues<T>().firstOrNull { it.name == value }
            ?: throw ProviderConfigException("Provider profile ${profileIndex + 1} has invalid '$name' value '$value'.")
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

class ProviderConfigTransferService(
    private val repository: ModelProfileRepository,
    private val codec: ProviderConfigJsonCodec = ProviderConfigJsonCodec(),
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun createExportJson(
        profiles: List<ModelProfile>,
        selectorState: ModelProfileSelectorState,
    ): String {
        if (profiles.isEmpty()) throw ProviderConfigException("There are no provider profiles to export.")
        return codec.encode(
            ProviderConfigExportV1(
                exportedAt = Instant.ofEpochMilli(clock()).toString(),
                selector = ProviderConfigSelectorV1(
                    remoteProvidersEnabled = selectorState.remoteProvidersEnabled,
                    selectedRemoteProfileId = selectorState.selectedRemoteProfileId,
                ),
                profiles = profiles.map { profile ->
                    ProviderConfigProfileV1(
                        profile.id,
                        profile.displayName,
                        profile.baseUrl,
                        profile.modelId,
                        profile.inputMode,
                        profile.structuredOutputFormat,
                    )
                },
            ),
        )
    }

    suspend fun previewImport(json: String): ProviderConfigImportPreview {
        val config = codec.decode(json)
        val existingIds = repository.observeProfiles().first().mapTo(mutableSetOf()) { it.id }
        return ProviderConfigImportPreview(config, config.profiles.map { it.id }.filterTo(mutableSetOf()) { it in existingIds })
    }

    suspend fun confirmImport(preview: ProviderConfigImportPreview): ProviderConfigImportResult {
        val existingIds = repository.observeProfiles().first().mapTo(mutableSetOf()) { it.id }
        val reservedIds = (existingIds + preview.config.profiles.map { it.id }).toMutableSet()
        var copiedConflictCount = 0
        val now = clock()
        val profiles = preview.config.profiles.map { imported ->
            val finalId = if (imported.id in existingIds) {
                copiedConflictCount++
                generateUniqueId(reservedIds)
            } else {
                imported.id
            }
            reservedIds += finalId
            ModelProfile(
                id = finalId,
                displayName = imported.displayName,
                baseUrl = imported.baseUrl,
                modelId = imported.modelId,
                inputMode = imported.inputMode,
                structuredOutputFormat = imported.structuredOutputFormat,
                credentialAlias = ModelProfile.credentialAlias(finalId),
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
        }
        repository.saveProfiles(profiles)
        return ProviderConfigImportResult(profiles.size, copiedConflictCount)
    }

    private fun generateUniqueId(reservedIds: Set<String>): String {
        repeat(100) {
            val candidate = idFactory()
            if (candidate.isNotBlank() && candidate !in reservedIds) return candidate
        }
        throw ProviderConfigException("A unique ID could not be generated for an imported provider profile.")
    }
}
