package com.hugo.smartexpense.app.receipt.ui

import com.hugo.smartexpense.app.ReceiptReviewState
import com.hugo.smartexpense.app.settings.data.AppSettings
import com.hugo.smartexpense.app.settings.data.AppSettingsRepository
import com.hugo.smartexpense.extraction.ModelProfile
import com.hugo.smartexpense.extraction.RemoteInputMode
import com.hugo.smartexpense.extraction.RemoteStructuredOutputFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptWorkflowViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun importSnapshotsImageReductionAndRetainsReviewInViewModel() = runTest(dispatcher) {
        val settings = FakeSettingsRepository(AppSettings(reduceOversizedImages = true))
        var capturedReduction: Boolean? = null
        val viewModel = ReceiptWorkflowViewModel(
            settingsRepository = settings,
            extractReceipt = { _, reduce, _ ->
                capturedReduction = reduce
                settings.setReduceOversizedImages(false)
                ReceiptReviewState(merchantName = "Market", message = "Review")
            },
            startExport = { error("not used") },
            retryExport = { error("not used") },
            operationDispatcher = dispatcher,
        )

        viewModel.importReceipt("content://receipt", null)
        advanceUntilIdle()

        assertEquals(true, capturedReduction)
        assertEquals("Market", viewModel.uiState.value.review?.merchantName)
        assertEquals("content://receipt", viewModel.uiState.value.review?.sourceImageUri)
        assertFalse(viewModel.uiState.value.extracting)
    }

    @Test fun importKeepsProviderSnapshotWhenSelectionChangesBeforeWorkRuns() = runTest(dispatcher) {
        val settings = FakeSettingsRepository(AppSettings())
        val selected = profile("selected")
        var currentSelection = selected
        var extractedWith: ModelProfile? = null
        val viewModel = ReceiptWorkflowViewModel(
            settingsRepository = settings,
            extractReceipt = { _, _, snapshot -> extractedWith = snapshot; ReceiptReviewState() },
            startExport = { error("not used") }, retryExport = { error("not used") },
            operationDispatcher = dispatcher,
        )

        viewModel.importReceipt("content://receipt", currentSelection)
        currentSelection = profile("changed")
        advanceUntilIdle()

        assertEquals(selected, extractedWith)
        assertEquals("changed", currentSelection.id)
    }

    private fun profile(id: String) = ModelProfile(
        id, id, "https://example.test/v1", "model", RemoteInputMode.DIRECT_IMAGE,
        RemoteStructuredOutputFormat.JSON_SCHEMA, ModelProfile.credentialAlias(id), 1, 1,
    )
}

private class FakeSettingsRepository(initial: AppSettings) : AppSettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<AppSettings> = state
    override fun setReduceOversizedImages(enabled: Boolean) {
        state.value = state.value.copy(reduceOversizedImages = enabled)
    }
}
