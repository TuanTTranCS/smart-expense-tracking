package com.hugo.smartexpense.app.receipt.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hugo.smartexpense.app.ReceiptReviewState
import com.hugo.smartexpense.app.receiptexport.ReceiptExportFailure
import com.hugo.smartexpense.app.receiptexport.ReceiptExportRecord
import com.hugo.smartexpense.app.receiptexport.ReceiptExportStatus
import com.hugo.smartexpense.app.receiptexport.toHandoffJson
import com.hugo.smartexpense.app.settings.data.AppSettingsRepository
import com.hugo.smartexpense.extraction.ModelProfile
import com.hugo.smartexpense.extraction.ReceiptImage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import java.util.UUID
import com.hugo.smartexpense.extraction.BatchRequestScheduler
import com.hugo.smartexpense.extraction.BatchProcessingPolicy
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ReceiptWorkflowUiState(
    val items: List<BatchItemState> = emptyList(),
    val batchMessage: String? = null,
    val extracting: Boolean = false,
    val exporting: Boolean = false,
    val reviews: List<ReceiptReviewState> = emptyList(),
    val selectedImage: ReceiptImage? = null,
    val selectedImageUri: String? = null,
) {
    val review: ReceiptReviewState? get() = reviews.firstOrNull()
    val pendingExportCount: Int get() = items.sumOf { item -> item.transactions.count { !it.review.exportComplete } }
    val exportAllBlockers: List<String> get() = items.mapIndexedNotNull { index, item ->
        val remaining = item.transactions.filterNot { it.review.exportComplete }
        when {
            item.transactions.isNotEmpty() && remaining.isEmpty() -> null
            item.status != BatchItemStatus.SUCCEEDED || remaining.isEmpty() -> "Item ${index + 1} needs extraction or manual review."
            remaining.any { it.revision != item.sourceRevision } -> "Item ${index + 1} changed; reprocess it before export."
            remaining.any { it.review.expenseValidationErrors().isNotEmpty() || it.review.extractionStatus !in setOf("confirmed", "manual", "low_confidence") } -> "Item ${index + 1} has invalid review fields."
            else -> null
        }
    }
    val canExportAll: Boolean get() = !extracting && !exporting && pendingExportCount > 0 && exportAllBlockers.isEmpty()
    val canRetryExtraction: Boolean get() =
        selectedImage != null && !selectedImageUri.isNullOrBlank() && !extracting && !exporting
}

class ReceiptWorkflowViewModel(
    private val settingsRepository: AppSettingsRepository,
    private val extractReceipt: suspend (
        uri: String,
        reduceOversizedImages: Boolean,
        remoteProfileSnapshot: ModelProfile?,
        imageSnapshot: ReceiptImage?,
        onImageLoaded: (ReceiptImage) -> Unit,
    ) -> List<ReceiptReviewState>,
    private val startExport: suspend (ReceiptReviewState) -> ReceiptExportRecord,
    private val retryExport: suspend (String) -> ReceiptExportRecord,
    private val operationDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val prepareImage: (suspend (String, Boolean) -> ReceiptImage)? = null,
    private val reviewTyped: (suspend (ManualExpenseDraft, ModelProfile?) -> List<ReceiptReviewState>)? = null,
    private val batchStore: PreparedBatchStore? = null,
    private val startExportWithIdentity: (suspend (ReceiptReviewState, String, (ReceiptExportRecord) -> Unit) -> ReceiptExportRecord)? = null,
    private val scheduler: BatchRequestScheduler = BatchRequestScheduler(),
    private val exportReviewedBatch: (suspend (List<Pair<String, ReceiptReviewState>>, (ReceiptExportRecord) -> Unit) -> List<ReceiptExportRecord>)? = null,
    private val loadExportBatchRecords: (suspend (String) -> List<ReceiptExportRecord>)? = null,
    private val loadSavedExportRecord: (suspend (String) -> ReceiptExportRecord?)? = null,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ReceiptWorkflowUiState())
    val uiState: StateFlow<ReceiptWorkflowUiState> = mutableUiState.asStateFlow()

    private var batchJob: Job? = null
    private var runId: String? = null
    private var removed: Pair<Int, BatchItemState>? = null
    private var foreground = true
    private var runUsesRemote = false
    private var restoring = batchStore != null
    private val itemJobs = mutableMapOf<String, Job>()
    private val preparationJobs = mutableMapOf<String, Job>()
    private val draftSaveMutex = Mutex()
    private val saveJobs = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                if (!settings.debugOutputEnabled) mutate { state -> state.copy(items = state.items.map { item -> item.copy(rawFailureOutput = "", transactions = item.transactions.map { tx -> tx.copy(review = tx.review.copy(rawModelOutput = "")) }) }) }
            }
        }
        viewModelScope.launch {
            batchStore?.let { store ->
                val restored = try {
                    withContext(operationDispatcher) {
                        val batchPreviews = mutableMapOf<String, String>()
                        store.load().map { item -> item.copy(transactions = item.transactions.map { tx ->
                            val record = loadSavedExportRecord?.invoke(tx.review.exportExpenseId ?: tx.id)
                            check(loadSavedExportRecord == null || tx.review.exportExpenseId == null || record != null) {
                                "The saved export could not be found."
                            }
                            if (record == null) tx else {
                                val preview = record.batchId?.let { batchId ->
                                    batchPreviews[batchId] ?: loadExportBatchRecords?.invoke(record.expenseId)
                                        ?.takeIf { it.isNotEmpty() }?.joinToString(prefix = "[", postfix = "]") { it.toHandoffJson() }
                                        ?.also { batchPreviews[batchId] = it }
                                }
                                tx.copy(review = tx.review.withSavedExport(record).let { restored ->
                                    if (preview == null) restored else restored.copy(exportJsonPreview = preview)
                                })
                            }
                        }) }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    mutableUiState.update { it.copy(batchMessage = "Saved batch could not be restored safely: ${error.message}. Restart after resolving the storage problem.") }
                    // Leave restoration locked and the persisted draft untouched until reconciliation succeeds.
                    return@launch
                }
                mutableUiState.update { state -> state.copy(items = restored.map { item -> if (settingsRepository.settings.value.debugOutputEnabled) item else item.copy(rawFailureOutput = "", transactions = item.transactions.map { tx -> tx.copy(review = tx.review.copy(rawModelOutput = "")) }) } + state.items.filterNot { added -> restored.any { it.itemId == added.itemId } }) }
                restoring = false
                saveJobs.trySend(Unit)
                restored.filter { it.status == BatchItemStatus.PREPARING }.forEach { prepare(it) }
            }
            for (signal in saveJobs) batchStore?.let {
                try { checkpointDraft() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { mutableUiState.update { it.copy(batchMessage = "Batch could not be saved. Keep the app open and retry after freeing storage: ${error.message}") } }
            }
        }
    }
    private fun mutate(change: (ReceiptWorkflowUiState) -> ReceiptWorkflowUiState) {
        mutableUiState.update(change)
        if (!restoring) saveJobs.trySend(Unit)
    }
    /** Serialize background saves and export checkpoints; queued signals never carry stale snapshots. */
    private suspend fun checkpointDraft() = draftSaveMutex.withLock {
        batchStore?.let { store -> withContext(operationDispatcher) { store.save(mutableUiState.value.items) } }
    }
    private fun ReceiptReviewState.withSavedExport(record: ReceiptExportRecord): ReceiptReviewState = copy(
        receiptDate = record.receiptDate,
        merchantName = record.merchantName,
        totalAmount = record.totalAmount,
        currency = record.currency,
        extractionStatus = record.extractionStatus,
        merchantLocation = record.merchantLocation.orEmpty(),
        notes = record.notes,
        sourceType = record.sourceType,
        sourceImageUri = record.sourceImageUri.orEmpty(),
        manualEntryRequired = false,
    ).withExportResult(record)
    private fun itemChange(id: String, change: (BatchItemState) -> BatchItemState) = mutate { state ->
        state.copy(items = state.items.map { if (it.itemId == id) change(it) else it })
    }
    fun addTypedItem() { val oldIds = mutableUiState.value.items.map { it.itemId }.toSet(); addItem(); mutableUiState.value.items.firstOrNull { it.itemId !in oldIds }?.let { updateSource(it.itemId, true, it.draft) } }
    fun addItem() {
        if (restoring || mutableUiState.value.extracting || mutableUiState.value.exporting) return
        if (mutableUiState.value.items.size >= MAX_BATCH_ITEMS) { mutate { it.copy(batchMessage = "Batch limit is $MAX_BATCH_ITEMS items. Remove an item before adding more.") }; return }
        mutate { it.copy(items = it.items + BatchItemState()) }
    }
    fun prepareImages(uris: List<String>, targetItemId: String? = null) {
        if (uris.isEmpty()) return
        if (restoring || mutableUiState.value.extracting || mutableUiState.value.exporting) return
        val existing = mutableUiState.value.items.filterNot { it.itemId == targetItemId }.mapNotNull { it.sourceUri }.toSet()
        val distinct = uris.filter(String::isNotBlank).distinct().filterNot(existing::contains)
        val emptySlot = mutableUiState.value.items.firstOrNull { it.itemId == targetItemId && !it.noImage && !it.immutable }
        val capacity = MAX_BATCH_ITEMS - mutableUiState.value.items.size + if (emptySlot != null) 1 else 0
        val additions = distinct.take(capacity).map { BatchItemState(sourceUri = it, status = BatchItemStatus.PREPARING, reduceOversizedImages = settingsRepository.settings.value.reduceOversizedImages) }
        val preparedAdditions = additions.mapIndexed { index, item -> if (index == 0 && emptySlot != null) item.copy(itemId = emptySlot.itemId, sourceRevision = emptySlot.sourceRevision + 1, draft = emptySlot.draft, expanded = emptySlot.expanded) else item }
        mutate { state -> state.copy(items = state.items.map { existingItem -> if (emptySlot != null && preparedAdditions.isNotEmpty() && existingItem.itemId == emptySlot.itemId) preparedAdditions.first() else existingItem } + if (emptySlot != null) preparedAdditions.drop(1) else preparedAdditions, batchMessage = when {
            distinct.size > capacity -> "Batch limit is $MAX_BATCH_ITEMS; ${distinct.size - capacity} additions rejected."
            distinct.size != uris.size -> "Duplicate URI selection skipped."
            else -> null
        }) }
        preparedAdditions.forEach { item -> preparationJobs[item.itemId]?.cancel(); prepare(item) }
    }
    private val preparationGate = kotlinx.coroutines.sync.Semaphore(1)
    private fun prepare(item: BatchItemState) {
        val reduce = item.reduceOversizedImages
        preparationJobs[item.itemId] = viewModelScope.launch {
            preparationGate.acquire()
            try {
                val image = withContext(operationDispatcher) { requireNotNull(prepareImage) { "Image preparation unavailable" }(requireNotNull(item.sourceUri), reduce) }
                if (mutableUiState.value.items.none { it.itemId == item.itemId && it.sourceUri == item.sourceUri }) return@launch
                val staged = batchStore?.let { withContext(operationDispatcher) { it.stage(item.itemId, image) } }
                if (mutableUiState.value.items.none { it.itemId == item.itemId && it.sourceUri == item.sourceUri }) { staged?.let { batchStore?.release(it) }; return@launch }
                itemChange(item.itemId) { current -> if (current.sourceUri != item.sourceUri) current else current.copy(image = if (staged == null) image else null, preparedUri = staged, mimeType = image.mimeType, status = if (current.noImage) current.status else BatchItemStatus.READY) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { itemChange(item.itemId) { if (it.sourceUri != item.sourceUri) it else it.copy(status = BatchItemStatus.FAILED, error = error.message ?: "Choose this image again.") } }
            finally { preparationGate.release() }
        }
    }
    fun updateSource(id: String, noImage: Boolean, draft: ManualExpenseDraft) {
        if (restoring || mutableUiState.value.extracting || mutableUiState.value.exporting) return
        itemChange(id) { item -> if (item.immutable || (item.noImage == noImage && item.draft == draft)) item else item.copy(noImage = noImage, draft = draft,
            sourceRevision = item.sourceRevision + 1, status = if (noImage || item.preparedUri != null || item.image != null) BatchItemStatus.READY else BatchItemStatus.EMPTY, error = null, rawFailureOutput = "") }
    }
    fun toggleExpanded(id: String) = itemChange(id) { it.copy(expanded = !it.expanded) }
    fun removeItem(id: String) {
        if (mutableUiState.value.exporting) return
        val index = mutableUiState.value.items.indexOfFirst { it.itemId == id }
        if (index < 0) return
        val previousRemoved = removed?.second
        removed = index to mutableUiState.value.items[index]
        itemJobs[id]?.cancel()
        preparationJobs[id]?.cancel()
        previousRemoved?.preparedUri?.let { uri -> viewModelScope.launch { batchStore?.release(uri) } }
        mutate { it.copy(items = it.items.filterNot { item -> item.itemId == id }, batchMessage = "Item removed. Undo is available.") }
        viewModelScope.launch { kotlinx.coroutines.delay(5000); if (removed?.second?.itemId == id) { val staged = removed?.second?.preparedUri; removed = null; staged?.let { batchStore?.release(it) }; mutate { it.copy(batchMessage = null) } } }
    }
    fun undoRemove() {
        if (restoring || mutableUiState.value.extracting || mutableUiState.value.exporting || mutableUiState.value.items.size >= MAX_BATCH_ITEMS) return
        val saved = removed ?: return
        removed = null
        mutate { state -> state.copy(items = state.items.toMutableList().apply { add(saved.first.coerceAtMost(size), saved.second.copy(status = if (saved.second.status in listOf(BatchItemStatus.PROCESSING, BatchItemStatus.QUEUED)) BatchItemStatus.CANCELLED else saved.second.status)) }, batchMessage = null) }
        if (saved.second.status == BatchItemStatus.PREPARING) viewModelScope.launch { preparationJobs[saved.second.itemId]?.join(); prepare(saved.second) }
    }
    suspend fun loadPreparedImage(item: BatchItemState): ReceiptImage? = item.image ?: item.preparedUri?.let { batchStore?.read(it, item.mimeType) }
    fun extractAll(profile: ModelProfile?) = startBatch(profile, null)
    fun useManualValues(id: String) {
        if (restoring || mutableUiState.value.extracting || mutableUiState.value.exporting) return
        itemChange(id) { item ->
            if (item.immutable) item else if (!item.noImage && (item.preparedUri != null || item.image != null)) {
                item.copy(status = BatchItemStatus.SUCCEEDED, error = null, transactions = listOf(BatchTransaction(revision = item.sourceRevision, review = ReceiptReviewState.manual("Enter the receipt details manually.").copy(sourceImageUri = item.preparedUri ?: item.sourceUri.orEmpty()))))
            } else if (!item.noImage) item else {
                item.copy(status = BatchItemStatus.SUCCEEDED, error = null, transactions = listOf(BatchTransaction(revision = item.sourceRevision,
                    review = ReceiptReviewState(receiptDate = item.draft.date, merchantName = item.draft.merchant, totalAmount = item.draft.amount, currency = item.draft.currency,
                        notes = item.draft.notes, sourceType = com.hugo.smartexpense.app.ReceiptSourceType.TYPED, extractionStatus = "manual", message = "Enter a merchant, numeric total, three-letter currency code, and ISO date in the review fields before export. Your original draft is preserved above.", manualEntryRequired = true))))
            }
        }
    }
    suspend fun loadPreparedThumbnail(item: BatchItemState): ReceiptImage? = item.image ?: item.preparedUri?.let { batchStore?.thumbnail(it, item.mimeType) }
    fun retryItem(id: String, profile: ModelProfile?, replaceEdits: Boolean = false) {
        if (restoring || mutableUiState.value.extracting || mutableUiState.value.exporting) return
        val item = mutableUiState.value.items.find { it.itemId == id } ?: return
        if (!item.noImage && item.preparedUri == null && item.image == null) return
        if (item.transactions.isNotEmpty() && item.transactions.all { it.review.exportExpenseId != null }) return
        if (item.edited && !replaceEdits) { mutate { it.copy(batchMessage = "Retry replaces your edits. Confirm replacement to continue.") }; return }
        startBatch(profile, id)
    }
    private fun startBatch(profile: ModelProfile?, only: String?) {
        val state = mutableUiState.value
        if (restoring || state.extracting || state.exporting || !foreground || state.items.any { it.status == BatchItemStatus.PREPARING }) return
        val candidates = state.items.filter { if (only != null) it.itemId == only && (it.transactions.isEmpty() || it.transactions.any { tx -> tx.review.exportExpenseId == null }) else it.eligible && !it.immutable }
        if (candidates.isEmpty()) return
        val invalid = candidates.filter { it.noImage && it.draft.errors().isNotEmpty() }
        if (invalid.isNotEmpty()) { invalid.forEach { item -> itemChange(item.itemId) { it.copy(error = it.draft.errors().joinToString(" ")) } }; return }
        val policy = BatchProcessingPolicy(maxConcurrency = settingsRepository.settings.value.batchMaxConcurrency, startSpacingMillis = settingsRepository.settings.value.batchStartSpacingMillis)
        val debugSnapshot = settingsRepository.settings.value.debugOutputEnabled
        val token = UUID.randomUUID().toString()
        runId = token
        runUsesRemote = profile != null
        mutate { it.copy(extracting = true, items = it.items.map { item -> if (candidates.any { c -> c.itemId == item.itemId }) item.copy(status = BatchItemStatus.QUEUED, error = null) else item }) }
        batchJob = viewModelScope.launch {
            try {
                scheduler.run(candidates, policy, onDevice = profile == null, operation = { item ->
                    if (runId != token || mutableUiState.value.items.none { it.itemId == item.itemId }) throw CancellationException()
                    itemJobs[item.itemId] = kotlinx.coroutines.currentCoroutineContext()[Job]!!
                    itemChange(item.itemId) { it.copy(status = BatchItemStatus.PROCESSING) }
                    withContext(operationDispatcher) {
                        val reviews = if (item.noImage) requireNotNull(reviewTyped)(item.draft, profile) else {
                            val image = item.image ?: requireNotNull(batchStore).read(requireNotNull(item.preparedUri), item.mimeType)
                            extractReceipt(item.sourceUri.orEmpty(), false, profile, image) {}
                        }
                        if (item.noImage && reviews.size != 1) error("Typed input must return exactly one transaction.")
                        if (reviews.isEmpty() || reviews.all { it.manualEntryRequired }) error(reviews.firstOrNull()?.message ?: "No valid extraction returned.")
                        val remaining = reviews.toMutableList()
                        item.transactions.filter { it.review.exportExpenseId != null }.forEach { saved ->
                            val match = remaining.indexOfFirst { sameExpense(it, saved.review) }
                            if (match < 0) error("Retry could not reliably match a saved transaction. Keep its immutable export and create a new draft for remaining expenses.")
                            remaining.removeAt(match)
                        }
                        remaining.toList()
                    }
                }, onResult = { item, result ->
                    if (runId == token) itemChange(item.itemId) { current ->
                        if (current.sourceRevision != item.sourceRevision) current else result.fold(
                            onSuccess = { reviews -> current.copy(status = BatchItemStatus.SUCCEEDED, edited = false, rawFailureOutput = "", transactions = current.transactions.filter { it.review.exportExpenseId != null } + reviews.map { review -> BatchTransaction(revision = item.sourceRevision, review = review.copy(sourceType = if (item.noImage) com.hugo.smartexpense.app.ReceiptSourceType.TYPED else com.hugo.smartexpense.app.ReceiptSourceType.IMAGE, rawModelOutput = if (debugSnapshot && settingsRepository.settings.value.debugOutputEnabled) review.rawModelOutput else "", sourceImageUri = if (item.noImage) "" else item.preparedUri ?: item.sourceUri.orEmpty())) }) },
                            onFailure = { error -> current.copy(status = BatchItemStatus.FAILED, error = error.message ?: "Extraction failed. Retry this item.", rawFailureOutput = if (debugSnapshot && settingsRepository.settings.value.debugOutputEnabled) when (error) {
                                is BatchExtractionFailure -> error.rawOutput
                                is com.hugo.smartexpense.extraction.RemoteProviderException -> error.rawResponseBody.orEmpty()
                                else -> error.message.orEmpty()
                            } else "") })
                    }
                })
            } finally {
                if (runId == token) { runId = null; mutate { it.copy(extracting = false, items = it.items.map { item -> if (item.status in listOf(BatchItemStatus.QUEUED, BatchItemStatus.PROCESSING)) item.copy(status = BatchItemStatus.CANCELLED) else item }) } }
            }
        }
    }
    private fun sameExpense(left: ReceiptReviewState, right: ReceiptReviewState): Boolean =
        left.receiptDate == right.receiptDate && left.currency.uppercase() == right.currency.uppercase() &&
        left.merchantName.trim().replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT) == right.merchantName.trim().replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT) &&
        left.totalAmount.toBigDecimalOrNull()?.let { amount -> right.totalAmount.toBigDecimalOrNull()?.let { amount.compareTo(it) == 0 } } == true
    fun cancelRemaining() { runId = null; batchJob?.cancel(); mutate { it.copy(items = it.items.map { item -> if (item.status in listOf(BatchItemStatus.QUEUED, BatchItemStatus.PROCESSING)) item.copy(status = BatchItemStatus.CANCELLED) else item }) }; viewModelScope.launch { batchJob?.join(); mutate { it.copy(extracting = false) } } }
    fun setForeground(value: Boolean) { foreground = value; if (!value && mutableUiState.value.extracting) cancelRemaining() }
    fun revokeRemoteAccess() { if (runUsesRemote && mutableUiState.value.extracting) cancelRemaining() }
    fun updateTransaction(itemId: String, transactionId: String, review: ReceiptReviewState) {
        if (restoring) return
        itemChange(itemId) { item -> item.copy(edited = true, transactions = item.transactions.map { tx -> if (tx.id == transactionId && tx.review.exportExpenseId == null && !mutableUiState.value.exporting) tx.copy(review = tx.review.withUserEdits(review)) else tx }) }
    }
    /** The button confirms all current reviewed results; no unfinished input is silently skipped. */
    fun exportAll() {
        val state = mutableUiState.value
        if (restoring || state.extracting || state.exporting) return
        if (!state.canExportAll) {
            mutate { it.copy(batchMessage = state.exportAllBlockers.joinToString(" ").ifBlank { "There are no remaining transactions to export." }) }
            return
        }
        val exporter = exportReviewedBatch ?: return
        val snapshots = state.items.flatMap { item -> item.transactions.filterNot { it.review.exportComplete }
            .map { it.id to it.review.confirmedForExport() } }
        val reviewed = snapshots.toMap()
        val savedMembers = linkedMapOf<String, ReceiptExportRecord>()
        mutate { it.copy(exporting = true, batchMessage = null) }
        viewModelScope.launch {
            try {
                checkpointDraft()
                val records = withContext(operationDispatcher) {
                    state.items.filter { item -> item.transactions.any { !it.review.exportComplete } && !item.noImage }
                        .mapNotNull { it.preparedUri }.forEach { batchStore?.protect(it) }
                    exporter(snapshots) { saved ->
                        savedMembers[saved.expenseId] = saved
                        if (savedMembers.size == snapshots.size) applyExportRecords(
                            snapshots.map { requireNotNull(savedMembers[it.first]) }, reviewed, combined = true)
                        else applyExportRecords(listOf(saved), reviewed)
                    }
                }
                applyExportRecords(records, reviewed, combined = true)
                releaseCompletedExportImages()
                mutate { it.copy(batchMessage = if (records.all { record -> record.status == ReceiptExportStatus.EXPORTED })
                    "${records.size} transactions exported in one JSON list to OneDrive."
                    else "Export did not complete. Retry the saved export before adding other transactions to Export All.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutate { it.copy(batchMessage = error.message ?: "The combined export could not be completed. Retry the saved export.") } }
            finally { mutate { it.copy(exporting = false) } }
        }
    }
    private fun applyExportRecords(records: List<ReceiptExportRecord>, reviewed: Map<String, ReceiptReviewState> = emptyMap(), combined: Boolean = false) {
        val byId = records.associateBy { it.expenseId }
        val preview = if (combined && records.isNotEmpty()) records.joinToString(prefix = "[", postfix = "]") { it.toHandoffJson() } else null
        mutate { state -> state.copy(items = state.items.map { item -> item.copy(transactions = item.transactions.map { tx ->
            val record = byId[tx.review.exportExpenseId ?: tx.id]
            if (record == null) tx else tx.copy(review = (reviewed[tx.id] ?: tx.review).withExportResult(record).let { review ->
                if (preview == null) review else review.copy(exportJsonPreview = preview)
            })
        }) }) }
    }
    private suspend fun releaseCompletedExportImages() {
        mutableUiState.value.items.filter { !it.noImage && it.transactions.isNotEmpty() && it.transactions.all { tx -> tx.review.exportComplete } }
            .mapNotNull { it.preparedUri }.forEach { batchStore?.unprotect(it) }
    }
    fun exportTransaction(itemId: String, transactionId: String) {
        val state = mutableUiState.value
        if (restoring || state.extracting || state.exporting) return
        val item = state.items.find { it.itemId == itemId } ?: return
        val transaction = item.transactions.find { it.id == transactionId } ?: return
        if (transaction.revision != item.sourceRevision || transaction.review.exportComplete || item.status != BatchItemStatus.SUCCEEDED) return
        val errors = transaction.review.expenseValidationErrors()
        if (errors.isNotEmpty()) {
            updateTransaction(itemId, transactionId, transaction.review.copy(message = errors.joinToString(" ")))
            return
        }
        val snapshot = transaction.review.confirmedForExport()
        mutate { it.copy(exporting = true) }
        viewModelScope.launch {
            try {
                checkpointDraft()
                val record = withContext(operationDispatcher) {
                    item.preparedUri?.let { batchStore?.protect(it) }
                    snapshot.exportExpenseId?.let { retryExport(it) } ?: startExportWithIdentity?.invoke(snapshot, transaction.id) { saved ->
                    itemChange(itemId) { current -> current.copy(transactions = current.transactions.map { tx -> if (tx.id == transactionId) tx.copy(review = snapshot.copy(exportExpenseId = saved.expenseId, exportJsonPreview = saved.toHandoffJson())) else tx }) }
                } ?: startExport(snapshot) }
                if (record.status == ReceiptExportStatus.EXPORTED && item.transactions.none { it.id != transactionId && it.review.exportExpenseId != null && !it.review.exportComplete }) item.preparedUri?.let { batchStore?.unprotect(it) }
                itemChange(itemId) { it.copy(transactions = it.transactions.map { tx -> if (tx.id == transactionId) tx.copy(review = snapshot.withExportResult(record)) else tx }) }
                loadExportBatchRecords?.let { load ->
                    val records = withContext(operationDispatcher) { load(record.expenseId) }
                    applyExportRecords(records, combined = true)
                    releaseCompletedExportImages()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { itemChange(itemId) { it.copy(transactions = it.transactions.map { tx -> if (tx.id == transactionId) tx.copy(review = tx.review.copy(message = error.message.orEmpty())) else tx }) } }
            finally { mutate { it.copy(exporting = false) } }
        }
    }

    fun importReceipt(uri: String, remoteProfileSnapshot: ModelProfile?) {
        if (uri.isBlank() || mutableUiState.value.extracting || mutableUiState.value.exporting) return
        runExtraction(uri, remoteProfileSnapshot, imageSnapshot = null)
    }

    fun retryExtraction(remoteProfileSnapshot: ModelProfile?) {
        val state = mutableUiState.value
        if (!state.canRetryExtraction) return
        runExtraction(requireNotNull(state.selectedImageUri), remoteProfileSnapshot, state.selectedImage)
    }

    private fun runExtraction(uri: String, remoteProfileSnapshot: ModelProfile?, imageSnapshot: ReceiptImage?) {
        val reduceSnapshot = settingsRepository.settings.value.reduceOversizedImages
        mutableUiState.value = mutableUiState.value.copy(
            extracting = true,
            reviews = emptyList(),
            selectedImage = imageSnapshot,
            selectedImageUri = uri,
        )
        viewModelScope.launch {
            val result = runCatching {
                withContext(operationDispatcher) {
                    extractReceipt(uri, reduceSnapshot, remoteProfileSnapshot, imageSnapshot) { image ->
                        mutableUiState.value = mutableUiState.value.copy(selectedImage = image)
                    }
                }
            }.getOrElse { if (it is CancellationException) throw it else listOf(ReceiptReviewState.manual(it.message ?: "The receipt could not be processed.")) }
            mutableUiState.value = mutableUiState.value.copy(
                extracting = false,
                reviews = result.map { it.copy(sourceImageUri = uri) },
            )
        }
    }

    fun updateReview(index: Int, review: ReceiptReviewState) {
        if (!mutableUiState.value.exporting) {
            val current = mutableUiState.value.reviews.getOrNull(index) ?: return
            if (current.exportExpenseId != null) return
            mutableUiState.value = mutableUiState.value.copy(reviews = mutableUiState.value.reviews.toMutableList().apply {
                this[index] = current.withUserEdits(review)
            })
        }
    }

    fun export(index: Int) {
        val reviewSnapshot = mutableUiState.value.reviews.getOrNull(index)?.confirmedForExport() ?: return
        if (mutableUiState.value.exporting || reviewSnapshot.exportComplete) return
        mutableUiState.value = mutableUiState.value.copy(exporting = true, reviews = mutableUiState.value.reviews.toMutableList().apply {
            this[index] = reviewSnapshot
        })
        viewModelScope.launch {
            val result = runCatching {
                withContext(operationDispatcher) {
                    reviewSnapshot.exportExpenseId?.let { retryExport(it) } ?: startExport(reviewSnapshot)
                }
            }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            mutableUiState.value = mutableUiState.value.copy(
                exporting = false,
                reviews = mutableUiState.value.reviews.toMutableList().apply { this[index] = result.fold(
                    onSuccess = { reviewSnapshot.withExportResult(it) },
                    onFailure = { reviewSnapshot.copy(message = it.message ?: "The receipt could not be exported.") },
                ) },
            )
        }
    }

    private fun ReceiptReviewState.withExportResult(record: ReceiptExportRecord): ReceiptReviewState = copy(
        exportExpenseId = record.expenseId,
        exportComplete = record.status == ReceiptExportStatus.EXPORTED,
        exportJsonPreview = record.toHandoffJson(),
        message = if (record.status == ReceiptExportStatus.EXPORTED) {
            if (record.sourceType == com.hugo.smartexpense.app.ReceiptSourceType.TYPED) "Image-free handoff JSON exported to OneDrive." else "Receipt image and handoff JSON exported to OneDrive."
        } else when (record.failure) {
            ReceiptExportFailure.RECONNECT_REQUIRED -> "Your Microsoft session needs attention. Reconnect, then retry this export."
            ReceiptExportFailure.PERMISSION_DENIED -> "OneDrive access was denied. Reconnect and approve file access, then retry."
            ReceiptExportFailure.RATE_LIMITED -> record.retryAfterSeconds?.let { "Microsoft Graph is busy. Retry in $it seconds." }
                ?: "Microsoft Graph is busy. Wait, then retry."
            ReceiptExportFailure.NETWORK_UNAVAILABLE -> "OneDrive could not be reached. Check the network, then retry."
            ReceiptExportFailure.INSUFFICIENT_STORAGE -> "The receipt could not be stored. Free device or OneDrive space, then retry."
            ReceiptExportFailure.PATH_CONFLICT -> "A OneDrive item conflicts with the export path. Check the expense folders, then retry."
            ReceiptExportFailure.INVALID_RECEIPT -> "Check the receipt fields and image, then retry."
            ReceiptExportFailure.SERVICE_FAILURE, null -> "Microsoft Graph could not complete the export. Try again later."
        },
    )

    class Factory(private val create: () -> ReceiptWorkflowViewModel) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }
}
