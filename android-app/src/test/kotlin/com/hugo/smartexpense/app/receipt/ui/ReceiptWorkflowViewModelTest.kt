package com.hugo.smartexpense.app.receipt.ui

import com.hugo.smartexpense.app.ReceiptReviewState
import com.hugo.smartexpense.app.receiptexport.ReceiptExportStatus
import com.hugo.smartexpense.app.receiptexport.sampleRecord
import com.hugo.smartexpense.app.receiptexport.toVersion2Json
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

    @Test fun distinctReceiptsKeepIndependentReviewAndExportStatus() = runTest(dispatcher) {
        val exported = mutableListOf<Pair<String, String>>()
        val viewModel = ReceiptWorkflowViewModel(
            settingsRepository = FakeSettingsRepository(AppSettings()),
            extractReceipt = { _, _, _ -> listOf(validReview(), validReview().copy(merchantName = "Second shop", totalAmount = "24.50")) },
            startExport = {
                exported += it.merchantName to it.extractionStatus
                sampleRecord().copy(expenseId = "expense-${exported.size}", status = ReceiptExportStatus.EXPORTED)
            },
            retryExport = { error("not used") },
            operationDispatcher = dispatcher,
        )
        viewModel.importReceipt("content://two-receipts", null)
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.reviews.size)
        viewModel.updateReview(1, viewModel.uiState.value.reviews[1].copy(merchantName = "Corrected shop"))
        viewModel.export(0)
        advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.reviews[0].exportComplete)
        assertFalse(viewModel.uiState.value.reviews[1].exportComplete)
        viewModel.export(1)
        advanceUntilIdle()
        assertEquals(listOf("Shop" to "confirmed", "Corrected shop" to "manual"), exported)
        assertEquals("expense-1", viewModel.uiState.value.reviews[0].exportExpenseId)
        assertEquals("expense-2", viewModel.uiState.value.reviews[1].exportExpenseId)
        assertEquals(
            sampleRecord().copy(expenseId = "expense-1").toVersion2Json(),
            viewModel.uiState.value.reviews[0].exportJsonPreview,
        )
        assertEquals(
            sampleRecord().copy(expenseId = "expense-2").toVersion2Json(),
            viewModel.uiState.value.reviews[1].exportJsonPreview,
        )
    }

    @Test fun importSnapshotsImageReductionAndRetainsReviewInViewModel() = runTest(dispatcher) {
        val settings = FakeSettingsRepository(AppSettings(reduceOversizedImages = true))
        var capturedReduction: Boolean? = null
        val viewModel = ReceiptWorkflowViewModel(
            settingsRepository = settings,
            extractReceipt = { _, reduce, _ ->
                capturedReduction = reduce
                settings.setReduceOversizedImages(false)
                listOf(ReceiptReviewState(merchantName = "Market", message = "Review"))
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
            extractReceipt = { _, _, snapshot -> extractedWith = snapshot; listOf(ReceiptReviewState()) },
            startExport = { error("not used") }, retryExport = { error("not used") },
            operationDispatcher = dispatcher,
        )

        viewModel.importReceipt("content://receipt", currentSelection)
        currentSelection = profile("changed")
        advanceUntilIdle()

        assertEquals(selected, extractedWith)
        assertEquals("changed", currentSelection.id)
    }

    @Test fun confirmAndExportPromotesUnchangedLowConfidenceReview() = runTest(dispatcher) {
        var exportedStatus: String? = null
        val viewModel = ReceiptWorkflowViewModel(
            settingsRepository = FakeSettingsRepository(AppSettings()),
            extractReceipt = { _, _, _ -> listOf(validReview()) },
            startExport = {
                exportedStatus = it.extractionStatus
                sampleRecord().copy(status = ReceiptExportStatus.EXPORTED, extractionStatus = it.extractionStatus)
            },
            retryExport = { error("not used") },
            operationDispatcher = dispatcher,
        )

        viewModel.importReceipt("content://receipt", null)
        advanceUntilIdle()
        viewModel.export(0)
        advanceUntilIdle()

        assertEquals("confirmed", exportedStatus)
        assertEquals("confirmed", viewModel.uiState.value.review?.extractionStatus)
    }

    @Test fun editedReviewExportsAsManual() = runTest(dispatcher) {
        var exportedStatus: String? = null
        val viewModel = ReceiptWorkflowViewModel(
            settingsRepository = FakeSettingsRepository(AppSettings()),
            extractReceipt = { _, _, _ -> listOf(validReview()) },
            startExport = {
                exportedStatus = it.extractionStatus
                sampleRecord().copy(status = ReceiptExportStatus.EXPORTED, extractionStatus = it.extractionStatus)
            },
            retryExport = { error("not used") },
            operationDispatcher = dispatcher,
        )

        viewModel.importReceipt("content://receipt", null)
        advanceUntilIdle()
        viewModel.updateReview(0, requireNotNull(viewModel.uiState.value.review).copy(merchantName = "Corrected shop"))
        viewModel.export(0)
        advanceUntilIdle()

        assertEquals("manual", exportedStatus)
        assertEquals("manual", viewModel.uiState.value.review?.extractionStatus)
    }

    @Test fun savedExportKeepsItsReviewFieldsForRetry() = runTest(dispatcher) {
        var retriedId: String? = null
        val viewModel = ReceiptWorkflowViewModel(
            settingsRepository = FakeSettingsRepository(AppSettings()),
            extractReceipt = { _, _, _ -> listOf(validReview()) },
            startExport = { sampleRecord().copy(status = ReceiptExportStatus.FAILED, extractionStatus = it.extractionStatus) },
            retryExport = {
                retriedId = it
                sampleRecord().copy(status = ReceiptExportStatus.EXPORTED)
            },
            operationDispatcher = dispatcher,
        )

        viewModel.importReceipt("content://receipt", null)
        advanceUntilIdle()
        viewModel.export(0)
        advanceUntilIdle()
        val savedReview = requireNotNull(viewModel.uiState.value.review)
        assertFalse(savedReview.exportComplete)
        assertEquals(sampleRecord().toVersion2Json(), savedReview.exportJsonPreview)
        viewModel.updateReview(0, savedReview.copy(merchantName = "Late edit"))
        viewModel.export(0)
        advanceUntilIdle()

        assertEquals("Shop", viewModel.uiState.value.review?.merchantName)
        assertEquals(savedReview.exportExpenseId, retriedId)
        assertEquals(savedReview.exportJsonPreview, viewModel.uiState.value.review?.exportJsonPreview)
        assertEquals(true, viewModel.uiState.value.review?.exportComplete)
    }

    @Test fun failedValidationHasNoExportJsonPreview() = runTest(dispatcher) {
        val viewModel = ReceiptWorkflowViewModel(
            settingsRepository = FakeSettingsRepository(AppSettings()),
            extractReceipt = { _, _, _ -> listOf(validReview()) },
            startExport = { error("Invalid amount") },
            retryExport = { error("not used") },
            operationDispatcher = dispatcher,
        )
        viewModel.importReceipt("content://receipt", null)
        advanceUntilIdle()
        viewModel.export(0)
        advanceUntilIdle()
        assertEquals(null, viewModel.uiState.value.review?.exportJsonPreview)
        assertEquals("Invalid amount", viewModel.uiState.value.review?.message)
    }

    private fun validReview() = ReceiptReviewState(
        receiptDate = "2026-09-05", merchantName = "Shop", totalAmount = "12.34",
        extractionStatus = "low_confidence", sourceImageUri = "content://receipt",
    )

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
    override fun setDebugOutputEnabled(enabled: Boolean) {
        state.value = state.value.copy(debugOutputEnabled = enabled)
    }
    override fun setDeviceNameOverride(name: String) {
        state.value = state.value.copy(deviceNameOverride = name)
    }
}
