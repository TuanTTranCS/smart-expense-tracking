package com.hugo.smartexpense.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import com.hugo.smartexpense.app.graphauth.GraphAuthenticationUiState
import com.hugo.smartexpense.app.graphauth.OneDrivePresentation
import com.hugo.smartexpense.app.graphauth.OneDriveRecoveryAction
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfilesUiState
import com.hugo.smartexpense.app.modelprofile.ui.ProviderVerificationState
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfilesPanel
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfilesViewModel
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfileEditorState
import com.hugo.smartexpense.app.modelprofile.ui.ModelCatalogState
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfileTestService
import com.hugo.smartexpense.app.modelprofile.domain.SelectedReceiptModelProviderResolver
import com.hugo.smartexpense.extraction.ModelProfile
import com.hugo.smartexpense.extraction.ModelProfileDraft
import com.hugo.smartexpense.extraction.ModelProfileRepository
import com.hugo.smartexpense.extraction.ModelProfileSelectorState
import com.hugo.smartexpense.extraction.ProviderTestResult
import com.hugo.smartexpense.extraction.WritableApiKeyStore
import kotlinx.coroutines.flow.MutableStateFlow
import com.hugo.smartexpense.app.receipt.ui.ReceiptWorkflowScreen
import com.hugo.smartexpense.app.receipt.ui.ReceiptWorkflowUiState
import com.hugo.smartexpense.app.receipt.ui.BatchInputGroup
import com.hugo.smartexpense.app.receipt.ui.BatchItemState
import com.hugo.smartexpense.app.receipt.ui.BatchItemStatus
import com.hugo.smartexpense.app.ReceiptReviewState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CopyableMessageTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var clipboard: ClipboardManager

    @Test fun modelProfileCategoryHeadingUsesReadableSeparators() {
        val repository = object : ModelProfileRepository {
            override fun observeProfiles() = MutableStateFlow<List<ModelProfile>>(emptyList())
            override fun observeSelectorState() = MutableStateFlow(ModelProfileSelectorState())
            override suspend fun getProfile(id: String): ModelProfile? = null
            override suspend fun saveProfile(profile: ModelProfile) = Unit
            override suspend fun saveProfiles(profiles: List<ModelProfile>) = Unit
            override suspend fun selectProfile(id: String?) = Unit
            override suspend fun setRemoteProvidersEnabled(enabled: Boolean) = Unit
            override suspend fun deleteProfile(id: String) = Unit
        }
        val credentials = object : WritableApiKeyStore {
            override fun get(alias: String): String? = null
            override fun put(alias: String, value: String) = Unit
            override fun remove(alias: String) = Unit
        }
        val viewModel = ModelProfilesViewModel(repository, credentials,
            SelectedReceiptModelProviderResolver(repository), ModelProfileTestService { _, _ -> ProviderTestResult.Success })
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ModelProfilesPanel(ModelProfilesUiState(), viewModel, {}, {})
                }
            }
        }
        compose.onNodeWithText("Built-in on-device | Same-phone server | Remote API").assertExists()
        compose.onNodeWithText("Built-in on-device \uFFFD Same-phone server \uFFFD Remote API").assertDoesNotExist()
    }

    @Test fun handoffPreviewLabelUsesReadableText() {
        compose.setContent {
            MaterialTheme {
                ReceiptWorkflowScreen(
                    ModelProfilesUiState(),
                    ReceiptWorkflowUiState(reviews = listOf(ReceiptReviewState(exportJsonPreview = "{}"))),
                    GraphAuthenticationUiState(),
                    OneDrivePresentation("OneDrive", "", false, OneDriveRecoveryAction.NONE),
                    onOpenSettings = {},
                    onSelectLocal = {},
                    onSelectRemote = {},
                    onVerifyProvider = {},
                    onOneDriveRecovery = {},
                    onChooseReceipt = {},
                    onRetryExtraction = {},
                    onReviewChange = { _, _ -> },
                    onExport = {},
                    debugOutputEnabled = true,
                )
            }
        }
        compose.onNodeWithText("Generated handoff JSON (not published)").performScrollTo().assertExists()
        compose.onNodeWithText("Generated handoff JSON \u00E2\u20AC\u201D not published").assertDoesNotExist()
    }

    @Test fun copiesEntireMultilineDiagnosticOnlyAfterUserAction() {
        val message = "Verification failed during chat (HTTP 503, runtime_unavailable).\nStop/restart the companion, then retry."
        compose.setContent {
            clipboard = LocalClipboardManager.current
            MaterialTheme { CopyableMessage(message) }
        }
        compose.runOnIdle { clipboard.setText(AnnotatedString("Previous clipboard")) }
        compose.onNodeWithText(message).assertExists()
        compose.runOnIdle { assertEquals("Previous clipboard", clipboard.getText()?.text) }
        compose.onNodeWithText("Copy message").performClick()
        compose.runOnIdle { assertEquals(message, clipboard.getText()?.text) }
    }

    @Test fun copiesLatestMessageAfterRecomposition() {
        val message = mutableStateOf("Old failure")
        compose.setContent {
            clipboard = LocalClipboardManager.current
            MaterialTheme { CopyableMessage(message.value) }
        }
        compose.runOnIdle { message.value = "New failure: HTTP 401. Check the configured key." }
        compose.onNodeWithText("Copy message").performClick()
        compose.runOnIdle { assertEquals(message.value, clipboard.getText()?.text) }
    }

    @Test fun blankMessageHasNoCopyAction() {
        compose.setContent { MaterialTheme { CopyableMessage(" ") } }
        compose.onNodeWithText("Copy message").assertDoesNotExist()
    }

    @Test fun mainVerificationFailureCanBeCopiedFromItsCard() {
        val message = "Verification failed during preparation (HTTP 503, runtime_unavailable). Stop/restart the companion."
        val profile = ModelProfile("same-phone", "Phone companion", "http://127.0.0.1:8080/v1", "vision",
            com.hugo.smartexpense.extraction.RemoteInputMode.DIRECT_IMAGE,
            com.hugo.smartexpense.extraction.RemoteStructuredOutputFormat.PROMPT_ONLY_JSON,
            "same-phone-credential", 0, 0, category = com.hugo.smartexpense.extraction.ApiProfileCategory.SAME_PHONE_SERVER)
        compose.setContent {
            clipboard = LocalClipboardManager.current
            MaterialTheme {
                ReceiptWorkflowScreen(
                    ModelProfilesUiState(profiles = listOf(profile),
                        selectorState = ModelProfileSelectorState(selectedRemoteProfileId = profile.id),
                        verification = ProviderVerificationState(profile.id, false, message)),
                    ReceiptWorkflowUiState(), GraphAuthenticationUiState(),
                    OneDrivePresentation("OneDrive", "", false, OneDriveRecoveryAction.NONE),
                    {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {},
                )
            }
        }
        compose.onNodeWithText("Copy message").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(message, clipboard.getText()?.text) }
    }

    @Test fun failedExpenseCanBeCopiedWhileExtractionActionsAreDisabled() {
        val message = "Inference timed out. Retry this item explicitly.\nHTTP 504, inference_timeout"
        compose.setContent {
            clipboard = LocalClipboardManager.current
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    BatchInputGroup(
                        BatchItemState(itemId = "typed", noImage = true, status = BatchItemStatus.FAILED, error = message),
                        1, true, false, false, false,
                        {}, {}, {}, { _, _, _ -> }, { _, _ -> }, { _, _, _ -> }, { _, _ -> }, {}, { null }, { null },
                    )
                }
            }
        }
        compose.onNodeWithText("Copy message").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(message, clipboard.getText()?.text) }
    }

    @Test fun settingsCopiesProviderTestAndDiscoveryErrorsWithoutDraftCredential() {
        val repository = object : ModelProfileRepository {
            override fun observeProfiles() = MutableStateFlow<List<ModelProfile>>(emptyList())
            override fun observeSelectorState() = MutableStateFlow(ModelProfileSelectorState())
            override suspend fun getProfile(id: String): ModelProfile? = null
            override suspend fun saveProfile(profile: ModelProfile) = Unit
            override suspend fun saveProfiles(profiles: List<ModelProfile>) = Unit
            override suspend fun selectProfile(id: String?) = Unit
            override suspend fun setRemoteProvidersEnabled(enabled: Boolean) = Unit
            override suspend fun deleteProfile(id: String) = Unit
        }
        val credentials = object : WritableApiKeyStore {
            override fun get(alias: String): String? = null
            override fun put(alias: String, value: String) = Unit
            override fun remove(alias: String) = Unit
        }
        val viewModel = ModelProfilesViewModel(repository, credentials,
            SelectedReceiptModelProviderResolver(repository), ModelProfileTestService { _, _ -> ProviderTestResult.Success })
        val message = "Provider verification failed (HTTP 401). Check the stored credential."
        val draft = ModelProfileDraft(apiKey = "private-draft-key")
        val editor = mutableStateOf(ModelProfileEditorState(draft, draft, testStatus = message))
        compose.setContent {
            clipboard = LocalClipboardManager.current
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ModelProfilesPanel(ModelProfilesUiState(editor = editor.value), viewModel, {}, {})
                }
            }
        }
        compose.onNodeWithText("Copy message").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(message, clipboard.getText()?.text) }
        compose.runOnIdle { editor.value = editor.value.copy(testStatus = null,
            catalogState = ModelCatalogState.Failed("Models endpoint returned HTTP 503.")) }
        compose.onNodeWithText("Copy message").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Models could not be loaded: Models endpoint returned HTTP 503. Manual model entry is available.", clipboard.getText()?.text) }
    }
}
