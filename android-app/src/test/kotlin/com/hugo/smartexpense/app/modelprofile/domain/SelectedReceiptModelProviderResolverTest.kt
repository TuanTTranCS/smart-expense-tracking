package com.hugo.smartexpense.app.modelprofile.domain

import com.hugo.smartexpense.app.modelprofile.migration.FakeRepository
import com.hugo.smartexpense.extraction.ModelProfile
import com.hugo.smartexpense.extraction.ModelProfileSelectorState
import com.hugo.smartexpense.extraction.ModelProviderType
import com.hugo.smartexpense.extraction.RemoteInputMode
import com.hugo.smartexpense.extraction.RemoteStructuredOutputFormat
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SelectedReceiptModelProviderResolverTest {
    @Test fun enabledExistingSelectionResolvesRemoteSnapshot() = runTest {
        val repository = FakeRepository()
        repository.profiles.value = listOf(profile())
        repository.selector.value = ModelProfileSelectorState("remote-1", true)
        val resolved = SelectedReceiptModelProviderResolver(repository).resolve()
        assertEquals(ModelProviderType.OPENAI_COMPATIBLE_API, resolved.selectedProvider.type)
        assertEquals("remote-1", resolved.remoteProfile?.id)
    }

    @Test fun disabledOrMissingSelectionFallsBackLocally() = runTest {
        val repository = FakeRepository()
        repository.profiles.value = listOf(profile())
        repository.selector.value = ModelProfileSelectorState("remote-1", false)
        assertEquals(ModelProviderType.LOCAL_ON_DEVICE, SelectedReceiptModelProviderResolver(repository).resolve().selectedProvider.type)
        repository.selector.value = ModelProfileSelectorState("missing", true)
        assertNull(SelectedReceiptModelProviderResolver(repository).resolve().remoteProfile)
    }

    private fun profile() = ModelProfile(
        "remote-1", "Remote", "https://example.test/v1", "model", RemoteInputMode.DIRECT_IMAGE,
        RemoteStructuredOutputFormat.JSON_SCHEMA, ModelProfile.credentialAlias("remote-1"), 1, 1,
    )
}
