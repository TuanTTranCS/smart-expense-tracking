package com.hugo.smartexpense.app.modelprofile.transfer

import com.hugo.smartexpense.app.modelprofile.migration.FakeRepository
import com.hugo.smartexpense.extraction.ModelProfile
import com.hugo.smartexpense.extraction.ModelProfileSelectorState
import com.hugo.smartexpense.extraction.RemoteInputMode
import com.hugo.smartexpense.extraction.RemoteStructuredOutputFormat
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ProviderConfigTransferTest {
    private val codec = ProviderConfigJsonCodec()

    @Test fun codecRoundTripIsDeterministicAndNeverExportsCredentials() {
        val config = config(
            profiles = listOf(
                exportedProfile("one", "Provider \"One\"") ,
                exportedProfile("two", "Provider Two", inputMode = RemoteInputMode.OCR_TEXT),
            ),
            selectedId = "two",
        )

        val first = codec.encode(config)
        val second = codec.encode(config)

        assertEquals(first, second)
        assertEquals(config, codec.decode(first))
        assertContains(first, "Provider \\\"One\\\"")
        assertFalse(first.contains("apiKey", ignoreCase = true))
        assertFalse(first.contains("credentialAlias", ignoreCase = true))
        assertContains(first, "\"credentialsIncluded\":false")
    }

    @Test fun codecRejectsMalformedUnsupportedEmptyDuplicateAndInvalidProfiles() {
        assertFailsWith<ProviderConfigException> { codec.decode("not-json") }
        assertFailsWith<ProviderConfigException> {
            codec.decode(codec.encode(config()).replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
        }
        assertFailsWith<ProviderConfigException> {
            codec.decode(codec.encode(config()).replace(Regex("\\[\\{.*}\\]"), "[]"))
        }
        assertFailsWith<ProviderConfigException> {
            codec.decode(codec.encode(config(profiles = listOf(exportedProfile("same"), exportedProfile("same")))))
        }
        assertFailsWith<ProviderConfigException> {
            codec.decode(
                codec.encode(config(profiles = listOf(exportedProfile("one").copy(baseUrl = "not-a-url")))),
            )
        }
        assertFailsWith<ProviderConfigException> {
            codec.decode(codec.encode(config()).replace("DIRECT_IMAGE", "UNKNOWN_MODE"))
        }
    }

    @Test fun codecRejectsMissingFieldsCredentialsAndDanglingSelection() {
        val json = codec.encode(config())
        assertFailsWith<ProviderConfigException> {
            codec.decode(json.replace("\"credentialsIncluded\":false,", ""))
        }
        assertFailsWith<ProviderConfigException> {
            codec.decode(json.replace("\"credentialsIncluded\":false", "\"credentialsIncluded\":true"))
        }
        assertFailsWith<ProviderConfigException> {
            codec.decode(json.replace("\"selectedRemoteProfileId\":null", "\"selectedRemoteProfileId\":\"missing\""))
        }
    }

    @Test fun exportIncludesAllMetadataAndSelectorButNoStoredCredentialData() {
        val repository = FakeRepository()
        val service = ProviderConfigTransferService(repository, codec, clock = { 1_000 })
        val first = savedProfile("one", "One")
        val second = savedProfile("two", "Two").copy(inputMode = RemoteInputMode.OCR_TEXT)

        val json = service.createExportJson(
            listOf(first, second),
            ModelProfileSelectorState(selectedRemoteProfileId = "two", remoteProvidersEnabled = true),
        )
        val decoded = codec.decode(json)

        assertEquals(listOf("one", "two"), decoded.profiles.map { it.id })
        assertTrue(decoded.selector.remoteProvidersEnabled)
        assertEquals("two", decoded.selector.selectedRemoteProfileId)
        assertEquals("1970-01-01T00:00:01Z", decoded.exportedAt)
        assertFalse(json.contains(first.credentialAlias))
    }

    @Test fun previewDoesNotMutateAndConfirmationCopiesConflictsWithoutChangingSelector() = runTest {
        val repository = FakeRepository()
        val existing = savedProfile("one", "Existing")
        repository.profiles.value = listOf(existing)
        repository.selector.value = ModelProfileSelectorState("one", remoteProvidersEnabled = true)
        val service = ProviderConfigTransferService(
            repository = repository,
            codec = codec,
            idFactory = { "conflict-copy" },
            clock = { 500 },
        )
        val json = codec.encode(
            config(
                profiles = listOf(exportedProfile("one", "Imported One"), exportedProfile("two", "Imported Two")),
                selectedId = "two",
            ),
        )

        val preview = service.previewImport(json)
        assertEquals(listOf(existing), repository.profiles.value)
        assertEquals(setOf("one"), preview.conflictingProfileIds)

        val result = service.confirmImport(preview)
        assertEquals(2, result.importedCount)
        assertEquals(1, result.copiedConflictCount)
        assertEquals(setOf("one", "two", "conflict-copy"), repository.profiles.value.map { it.id }.toSet())
        val copied = repository.profiles.value.single { it.id == "conflict-copy" }
        assertEquals("Imported One", copied.displayName)
        assertEquals(ModelProfile.credentialAlias("conflict-copy"), copied.credentialAlias)
        assertEquals(500, copied.createdAtEpochMillis)
        assertEquals(ModelProfileSelectorState("one", true), repository.selector.value)
    }

    @Test fun importedProfilesHaveLocalAliasesAndNoCredentialPayload() = runTest {
        val repository = FakeRepository()
        val service = ProviderConfigTransferService(repository, codec, clock = { 100 })

        service.confirmImport(service.previewImport(codec.encode(config())))

        val imported = repository.profiles.value.single()
        assertEquals(ModelProfile.credentialAlias(imported.id), imported.credentialAlias)
        assertNull(repository.selector.value.selectedRemoteProfileId)
        assertFalse(repository.selector.value.remoteProvidersEnabled)
    }

    private fun config(
        profiles: List<ProviderConfigProfileV1> = listOf(exportedProfile("one")),
        selectedId: String? = null,
    ) = ProviderConfigExportV1(
        exportedAt = "2026-09-09T12:34:56Z",
        selector = ProviderConfigSelectorV1(remoteProvidersEnabled = selectedId != null, selectedRemoteProfileId = selectedId),
        profiles = profiles,
    )

    private fun exportedProfile(
        id: String,
        name: String = "Provider",
        inputMode: RemoteInputMode = RemoteInputMode.DIRECT_IMAGE,
    ) = ProviderConfigProfileV1(
        id = id,
        displayName = name,
        baseUrl = "https://example.test/v1",
        modelId = "model-$id",
        inputMode = inputMode,
        structuredOutputFormat = RemoteStructuredOutputFormat.JSON_SCHEMA,
    )

    private fun savedProfile(id: String, name: String) = ModelProfile(
        id = id,
        displayName = name,
        baseUrl = "https://example.test/v1",
        modelId = "model-$id",
        inputMode = RemoteInputMode.DIRECT_IMAGE,
        structuredOutputFormat = RemoteStructuredOutputFormat.JSON_SCHEMA,
        credentialAlias = "secret-alias-$id",
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )
}
