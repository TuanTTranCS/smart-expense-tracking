package com.hugo.smartexpense.app.receipt.ui

import com.hugo.smartexpense.app.ReceiptReviewState
import com.hugo.smartexpense.app.settings.data.AppSettings
import com.hugo.smartexpense.app.settings.data.AppSettingsRepository
import com.hugo.smartexpense.extraction.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SamePhoneWorkflowTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()

    private fun profile(category: ApiProfileCategory) = ModelProfile(
        "profile", "Provider", "http://127.0.0.1:8080/v1", "vision",
        RemoteInputMode.DIRECT_IMAGE, RemoteStructuredOutputFormat.PROMPT_ONLY_JSON,
        "alias", 1, 1, category = category,
    )
    private fun review() = ReceiptReviewState(receiptDate = "2026-10-11", merchantName = "Shop",
        totalAmount = "10", extractionStatus = "confirmed", manualEntryRequired = false)
    private class Settings : AppSettingsRepository {
        override val settings = MutableStateFlow(AppSettings(batchMaxConcurrency = 3, batchStartSpacingMillis = 0))
        override fun setReduceOversizedImages(enabled: Boolean) {}
        override fun setDebugOutputEnabled(enabled: Boolean) {}
        override fun setThemeMode(mode: com.hugo.smartexpense.app.settings.data.AppThemeMode) {}
        override fun setDeviceNameOverride(name: String) {}
    }
    private fun vm(capture: suspend (ModelProfile?) -> WorkflowProviderSnapshot = { WorkflowProviderSnapshot(it) },
        extract: suspend (ModelProfile?) -> List<ReceiptReviewState>) = ReceiptWorkflowViewModel(
        settingsRepository = Settings(), extractReceipt = { _, _, p, _, _ -> extract(p) },
        startExport = { error("No automatic export") }, retryExport = { error("No automatic export") },
        operationDispatcher = dispatcher, prepareImage = { _, _ -> ReceiptImage("image.jpg", byteArrayOf(1), "image/jpeg") },
        reviewTyped = { _, p -> extract(p) }, captureProvider = capture,
    )
    private fun ReceiptWorkflowViewModel.addDraft() {
        addTypedItem()
        val item = uiState.value.items.last()
        updateSource(item.itemId, true, ManualExpenseDraft(notes = "Shop, ten Canadian dollars on October 11, 2026"))
    }

    @Test fun remoteConsentRevocationPreservesSamePhoneAndBuiltInButCancelsRemote() = runTest(dispatcher) {
        for (category in listOf(null, ApiProfileCategory.SAME_PHONE_SERVER, ApiProfileCategory.REMOTE_API)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val model = vm { entered.complete(Unit); release.await(); listOf(review()) }
            model.addDraft()
            model.extractAll(category?.let(::profile)); runCurrent()
            assertTrue(entered.isCompleted)
            model.revokeRemoteAccess(); runCurrent()
            if (category == ApiProfileCategory.REMOTE_API) {
                assertFalse(model.uiState.value.extracting)
                assertEquals(BatchItemStatus.CANCELLED, model.uiState.value.items.single().status)
            } else {
                assertTrue(model.uiState.value.extracting)
                release.complete(Unit); advanceUntilIdle()
                assertEquals(BatchItemStatus.SUCCEEDED, model.uiState.value.items.single().status)
            }
        }
    }

    @Test fun batchFreezesCompleteProviderAndCredentialBeforeFirstItemAndQueuedEdits() = runTest(dispatcher) {
        val initial = profile(ApiProfileCategory.SAME_PHONE_SERVER)
        var saved = initial
        var key = "old-key"
        var captures = 0
        val snapshots = mutableListOf<Pair<ModelProfile?, String?>>()
        val model = vm(capture = {
            captures++
            WorkflowProviderSnapshot(saved, OperationCredentialSnapshot(mapOf(saved.credentialAlias to key)))
        }, extract = { captured ->
            val scope = requireNotNull(InferenceExecution.current.get())
            snapshots += captured to scope.credentials.get("alias", ApiKeyStore { error("Must use admitted credential") })
            saved = saved.copy(baseUrl = "http://127.0.0.1:8091/expense/v1", modelId = "changed")
            key = "new-key"
            listOf(review())
        })
        model.addDraft(); model.addDraft(); model.extractAll(initial); advanceUntilIdle()
        assertEquals(1, captures)
        assertEquals(listOf<Pair<ModelProfile?, String?>>(initial to "old-key", initial to "old-key"), snapshots)
        assertTrue(model.uiState.value.items.all { it.status == BatchItemStatus.SUCCEEDED })
    }

    @Test fun samePhoneNetworkFailureHasOneAttemptAndRetainsDraftForExplicitRetry() = runTest(dispatcher) {
        var attempts = 0
        val model = vm { attempts++; throw RemoteProviderException.NetworkUnavailable("Provider", "Start the companion.") }
        model.addDraft()
        val draft = model.uiState.value.items.single().draft
        model.extractAll(profile(ApiProfileCategory.SAME_PHONE_SERVER)); advanceUntilIdle()
        assertEquals(1, attempts)
        assertEquals(BatchItemStatus.FAILED, model.uiState.value.items.single().status)
        assertEquals(draft, model.uiState.value.items.single().draft)
        assertFalse(model.uiState.value.canExportAll)
        model.retryItem(model.uiState.value.items.single().itemId, profile(ApiProfileCategory.SAME_PHONE_SERVER)); advanceUntilIdle()
        assertEquals(2, attempts)
    }

    @Test fun singleImageCancellationRejectsLateCompletionAndRetainsStagedBytes() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val image = ReceiptImage("image.jpg", byteArrayOf(5, 6), "image/jpeg")
        val model = ReceiptWorkflowViewModel(Settings(), { _, _, _, _, loaded ->
            loaded(image); entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            listOf(review())
        }, { error("No export") }, { error("No export") }, dispatcher)
        model.importReceipt("content://image", profile(ApiProfileCategory.SAME_PHONE_SERVER)); runCurrent()
        assertTrue(entered.isCompleted)
        model.setForeground(false); release.complete(Unit); advanceUntilIdle()
        assertFalse(model.uiState.value.extracting)
        assertTrue(model.uiState.value.reviews.isEmpty())
        assertContentEquals(image.bytes, model.uiState.value.selectedImage?.bytes)
    }
}
