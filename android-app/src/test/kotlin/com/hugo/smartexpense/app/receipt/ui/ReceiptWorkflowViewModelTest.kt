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
import com.hugo.smartexpense.extraction.ReceiptImage
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
import kotlin.test.assertTrue
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptWorkflowViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun distinctReceiptsKeepIndependentReviewAndExportStatus() = runTest(dispatcher) {
        val exported = mutableListOf<Pair<String, String>>()
        val image = selectedImage(byteArrayOf(4, 5))
        val viewModel = ReceiptWorkflowViewModel(
            settingsRepository = FakeSettingsRepository(AppSettings()),
            extractReceipt = { _, _, _, _, onImageLoaded ->
                onImageLoaded(image)
                listOf(validReview(), validReview().copy(merchantName = "Second shop", totalAmount = "24.50"))
            },
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
        assertEquals(image, viewModel.uiState.value.selectedImage)
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
            extractReceipt = { _, reduce, _, _, onImageLoaded ->
                capturedReduction = reduce
                settings.setReduceOversizedImages(false)
                onImageLoaded(selectedImage(byteArrayOf(1, 2, 3)))
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
        assertEquals(byteArrayOf(1, 2, 3).toList(), viewModel.uiState.value.selectedImage?.bytes?.toList())
        assertFalse(viewModel.uiState.value.extracting)
    }

    @Test fun importKeepsProviderSnapshotWhenSelectionChangesBeforeWorkRuns() = runTest(dispatcher) {
        val settings = FakeSettingsRepository(AppSettings())
        val selected = profile("selected")
        var currentSelection = selected
        var extractedWith: ModelProfile? = null
        val viewModel = ReceiptWorkflowViewModel(
            settingsRepository = settings,
            extractReceipt = { _, _, snapshot, _, _ -> extractedWith = snapshot; listOf(ReceiptReviewState()) },
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
            extractReceipt = { _, _, _, _, _ -> listOf(validReview()) },
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
            extractReceipt = { _, _, _, _, _ -> listOf(validReview()) },
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
            extractReceipt = { _, _, _, _, _ -> listOf(validReview()) },
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
            extractReceipt = { _, _, _, _, _ -> listOf(validReview()) },
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

    @Test fun replacementClearsPreviewThenKeepsTheNewImageAfterExtractionFailure() = runTest(dispatcher) {
        val first = selectedImage(byteArrayOf(1))
        val replacement = selectedImage(byteArrayOf(2))
        var calls = 0
        val viewModel = ReceiptWorkflowViewModel(
            settingsRepository = FakeSettingsRepository(AppSettings()),
            extractReceipt = { _, _, _, _, onImageLoaded ->
                calls += 1
                onImageLoaded(if (calls == 1) first else replacement)
                if (calls == 2) error("Model unavailable") else listOf(validReview())
            },
            startExport = { error("not used") }, retryExport = { error("not used") },
            operationDispatcher = dispatcher,
        )

        viewModel.importReceipt("content://first", null)
        advanceUntilIdle()
        assertEquals(first, viewModel.uiState.value.selectedImage)

        viewModel.importReceipt("content://replacement", null)
        assertEquals(null, viewModel.uiState.value.selectedImage)
        advanceUntilIdle()

        assertEquals(replacement, viewModel.uiState.value.selectedImage)
        assertTrue(viewModel.uiState.value.review?.message.orEmpty().contains("Model unavailable"))
        assertTrue(viewModel.uiState.value.review?.manualEntryRequired == true)
    }

    @Test fun loadFailureClearsPreviewAndBlankImportKeepsCurrentWorkflow() = runTest(dispatcher) {
        val image = selectedImage(byteArrayOf(7))
        var failLoading = false
        val viewModel = ReceiptWorkflowViewModel(
            settingsRepository = FakeSettingsRepository(AppSettings()),
            extractReceipt = { _, _, _, _, onImageLoaded ->
                if (failLoading) error("Image unavailable")
                onImageLoaded(image)
                listOf(validReview())
            },
            startExport = { error("not used") }, retryExport = { error("not used") },
            operationDispatcher = dispatcher,
        )

        viewModel.importReceipt("content://first", null)
        advanceUntilIdle()
        val beforeCancellation = viewModel.uiState.value
        viewModel.importReceipt("", null)
        assertEquals(beforeCancellation, viewModel.uiState.value)

        failLoading = true
        viewModel.importReceipt("content://failed", null)
        advanceUntilIdle()
        assertEquals(null, viewModel.uiState.value.selectedImage)
        assertTrue(viewModel.uiState.value.review?.message.orEmpty().contains("Image unavailable"))
        assertTrue(viewModel.uiState.value.review?.manualEntryRequired == true)
    }

    @Test fun previewStaysAvailableWhileTheLoadedReceiptIsReviewed() = runTest(dispatcher) {
        val image = selectedImage(byteArrayOf(9))
        val viewModel = ReceiptWorkflowViewModel(
            settingsRepository = FakeSettingsRepository(AppSettings()),
            extractReceipt = { _, _, _, _, onImageLoaded -> onImageLoaded(image); listOf(validReview()) },
            startExport = { error("not used") }, retryExport = { error("not used") },
            operationDispatcher = dispatcher,
        )

        viewModel.importReceipt("content://receipt", null)
        advanceUntilIdle()
        viewModel.updateReview(0, requireNotNull(viewModel.uiState.value.review).copy(merchantName = "Corrected shop"))

        assertEquals(image, viewModel.uiState.value.selectedImage)
    }

    @Test fun retryReusesProcessedImageAndSnapshotsCurrentProviderWhileReplacingReviews() = runTest(dispatcher) {
        val image = selectedImage(byteArrayOf(1, 2, 3))
        val settings = FakeSettingsRepository(AppSettings())
        val providers = mutableListOf<ModelProfile?>()
        val images = mutableListOf<ReceiptImage?>()
        val uris = mutableListOf<String>()
        val viewModel = ReceiptWorkflowViewModel(
            settingsRepository = settings,
            extractReceipt = { uri, _, provider, cachedImage, onImageLoaded ->
                providers += provider
                images += cachedImage
                uris += uri
                onImageLoaded(cachedImage ?: image)
                listOf(validReview().copy(rawModelOutput = "response-${providers.size}"))
            },
            startExport = { error("Retry extraction must not export") },
            retryExport = { error("Retry extraction must not retry exports") },
            operationDispatcher = dispatcher,
        )
        viewModel.importReceipt("content://original", profile("original"))
        advanceUntilIdle()
        viewModel.updateReview(0, requireNotNull(viewModel.uiState.value.review).copy(merchantName = "User edit"))
        settings.setReduceOversizedImages(false)
        var currentProvider = profile("retry")
        viewModel.retryExtraction(currentProvider)
        currentProvider = profile("later-selection")
        assertTrue(viewModel.uiState.value.extracting)
        assertFalse(viewModel.uiState.value.canRetryExtraction)
        assertTrue(viewModel.uiState.value.reviews.isEmpty())
        assertSame(image, viewModel.uiState.value.selectedImage)
        viewModel.retryExtraction(currentProvider)
        advanceUntilIdle()
        assertEquals(listOf("original", "retry"), providers.map { it?.id })
        assertEquals(null, images[0])
        assertSame(image, images[1])
        assertEquals(listOf("content://original", "content://original"), uris)
        assertEquals("Shop", viewModel.uiState.value.review?.merchantName)
        assertEquals("response-2", viewModel.uiState.value.review?.rawModelOutput)
        assertEquals("content://original", viewModel.uiState.value.review?.sourceImageUri)
        assertTrue(viewModel.uiState.value.canRetryExtraction)

        // Null is the effective provider when remote access is disabled or local is selected.
        viewModel.retryExtraction(null)
        advanceUntilIdle()
        assertEquals(null, providers.last())
        assertSame(image, images.last())
    }

    @Test fun failedExtractionKeepsImageForRepeatedRetriesAndRecovery() = runTest(dispatcher) {
        val image = selectedImage(byteArrayOf(9))
        var calls = 0
        val viewModel = ReceiptWorkflowViewModel(
            settingsRepository = FakeSettingsRepository(AppSettings()),
            extractReceipt = { _, _, _, cachedImage, onImageLoaded ->
                calls++
                onImageLoaded(cachedImage ?: image)
                if (calls < 3) error("Provider unavailable")
                listOf(validReview(), validReview().copy(merchantName = "Second shop"))
            },
            startExport = { error("not used") }, retryExport = { error("not used") },
            operationDispatcher = dispatcher,
        )
        viewModel.importReceipt("content://receipt", null)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.canRetryExtraction)
        viewModel.retryExtraction(null)
        advanceUntilIdle()
        assertSame(image, viewModel.uiState.value.selectedImage)
        assertTrue(viewModel.uiState.value.review?.manualEntryRequired == true)
        assertTrue(viewModel.uiState.value.canRetryExtraction)
        viewModel.retryExtraction(null)
        advanceUntilIdle()
        assertEquals(3, calls)
        assertEquals(2, viewModel.uiState.value.reviews.size)
        assertFalse(viewModel.uiState.value.extracting)
    }

    @Test fun retryRequiresLoadedImageAndCannotInterruptExport() = runTest(dispatcher) {
        var calls = 0
        val viewModel = ReceiptWorkflowViewModel(
            settingsRepository = FakeSettingsRepository(AppSettings()),
            extractReceipt = { uri, _, _, _, onImageLoaded ->
                calls++
                if (uri == "content://unavailable") error("Image unavailable")
                onImageLoaded(selectedImage(byteArrayOf(1)))
                listOf(validReview())
            },
            startExport = { sampleRecord().copy(status = ReceiptExportStatus.FAILED) },
            retryExport = { error("not used") }, operationDispatcher = dispatcher,
        )
        viewModel.retryExtraction(null)
        assertEquals(0, calls)
        viewModel.importReceipt("content://unavailable", null)
        advanceUntilIdle()
        val failed = viewModel.uiState.value
        assertFalse(failed.canRetryExtraction)
        viewModel.retryExtraction(null)
        assertEquals(failed, viewModel.uiState.value)
        assertEquals(1, calls)
        viewModel.importReceipt("content://receipt", null)
        viewModel.retryExtraction(null)
        advanceUntilIdle()
        assertEquals(2, calls)
        viewModel.export(0)
        val exporting = viewModel.uiState.value
        assertFalse(exporting.canRetryExtraction)
        viewModel.retryExtraction(null)
        assertEquals(exporting, viewModel.uiState.value)
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals(sampleRecord().expenseId, viewModel.uiState.value.review?.exportExpenseId)
    }

    @Test fun retryUsesLatestSelectedImageAndDoesNotKeepOldExportPreview() = runTest(dispatcher) {
        val first = selectedImage(byteArrayOf(1))
        val second = selectedImage(byteArrayOf(2))
        var retriedImage: ReceiptImage? = null
        var retriedUri: String? = null
        val viewModel = ReceiptWorkflowViewModel(
            settingsRepository = FakeSettingsRepository(AppSettings()),
            extractReceipt = { uri, _, _, cachedImage, onImageLoaded ->
                if (cachedImage != null) { retriedImage = cachedImage; retriedUri = uri }
                onImageLoaded(cachedImage ?: if (uri == "content://first") first else second)
                listOf(validReview())
            },
            startExport = { sampleRecord().copy(status = ReceiptExportStatus.EXPORTED) },
            retryExport = { error("not used") }, operationDispatcher = dispatcher,
        )
        viewModel.importReceipt("content://first", null)
        advanceUntilIdle()
        viewModel.importReceipt("content://second", null)
        advanceUntilIdle()
        viewModel.export(0)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.review?.exportComplete == true)
        viewModel.retryExtraction(null)
        advanceUntilIdle()
        assertSame(second, retriedImage)
        assertEquals("content://second", retriedUri)
        assertEquals(null, viewModel.uiState.value.review?.exportExpenseId)
        assertEquals(null, viewModel.uiState.value.review?.exportJsonPreview)
        assertFalse(requireNotNull(viewModel.uiState.value.review).exportComplete)
    }

    private fun validReview() = ReceiptReviewState(
        receiptDate = "2026-09-05", merchantName = "Shop", totalAmount = "12.34",
        extractionStatus = "low_confidence", sourceImageUri = "content://receipt",
    )

    private fun selectedImage(bytes: ByteArray) = ReceiptImage("receipt.jpg", bytes, "image/jpeg")

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
