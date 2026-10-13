package com.hugo.smartexpense.app.receipt.ui

import android.graphics.BitmapFactory
import com.hugo.smartexpense.app.ui.CopyableMessage

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import com.hugo.smartexpense.app.ui.ReceiptFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import com.hugo.smartexpense.app.ReceiptReviewState
import com.hugo.smartexpense.app.graphauth.GraphAuthenticationUiState
import com.hugo.smartexpense.app.graphauth.OneDrivePresentation
import com.hugo.smartexpense.app.graphauth.OneDriveRecoveryAction
import com.hugo.smartexpense.app.modelprofile.domain.SelectedReceiptModelProviderResolver
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfilesUiState
import com.hugo.smartexpense.app.modelprofile.ui.ProviderVerificationState
import com.hugo.smartexpense.extraction.ReceiptImage
import com.hugo.smartexpense.extraction.ModelProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReceiptWorkflowScreen(
    profileState: ModelProfilesUiState,
    workflowState: ReceiptWorkflowUiState,
    graphState: GraphAuthenticationUiState,
    oneDrive: OneDrivePresentation,
    onOpenSettings: () -> Unit,
    onSelectLocal: () -> Unit,
    onSelectRemote: (ModelProfile) -> Unit,
    onVerifyProvider: () -> Unit,
    onOneDriveRecovery: (OneDriveRecoveryAction) -> Unit,
    onChooseReceipt: () -> Unit,
    onRetryExtraction: () -> Unit,
    onReviewChange: (Int, ReceiptReviewState) -> Unit,
    onExport: (Int) -> Unit,
    debugOutputEnabled: Boolean = false,
    onTailscaleRequest: (Boolean) -> Unit = {},
    onChooseItemImage: (String) -> Unit = {},
    onAddTypedItem: () -> Unit = {},
    onAddItem: () -> Unit = {}, onExtractAll: () -> Unit = {}, onCancelRemaining: () -> Unit = {},
    onRemoveItem: (String) -> Unit = {}, onUndoRemove: () -> Unit = {}, onToggleExpanded: (String) -> Unit = {},
    onSourceChange: (String, Boolean, ManualExpenseDraft) -> Unit = { _, _, _ -> },
    onRetryItem: (String, Boolean) -> Unit = { _, _ -> },
    onTransactionChange: (String, String, ReceiptReviewState) -> Unit = { _, _, _ -> },
    onTransactionExport: (String, String) -> Unit = { _, _ -> },
    onManualValues: (String) -> Unit = {},
    onLoadThumbnail: suspend (BatchItemState) -> ReceiptImage? = { it.image },
    onLoadPreparedImage: suspend (BatchItemState) -> ReceiptImage? = { it.image },
    onExportAll: () -> Unit = {},
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Smart Expense", style = MaterialTheme.typography.headlineMedium)
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.semantics { contentDescription = "Open settings" },
            ) { Text("Settings") }
        }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CompactProviderSelector(
                    state = profileState,
                    onSelectLocal = onSelectLocal,
                    onSelectRemote = onSelectRemote,
                )
                val selectedId = profileState.selectedEffectiveProviderId()
                com.hugo.smartexpense.app.tailscale.TailscaleControl(profileState, onTailscaleRequest)
                profileState.verification?.takeIf { it.providerId == selectedId }?.let {
                    ProviderVerificationMessage(it)
                }
                OutlinedButton(
                    onClick = onVerifyProvider,
                    enabled = !profileState.busy && profileState.verification?.busy != true,
                    modifier = Modifier.semantics { contentDescription = "Verify selected provider" },
                ) { Text(if (profileState.verification?.busy == true) "Verifying" else "Verify profile") }
            }
        }

        OneDriveStatusCard(oneDrive, graphState.accountDisplayName, onOneDriveRecovery)

        Text("Receipt inputs", style = MaterialTheme.typography.titleLarge)
        Button(
            enabled = !workflowState.extracting && !workflowState.exporting && workflowState.items.size < MAX_BATCH_ITEMS,
            onClick = onChooseReceipt,
        ) { Text(if (workflowState.extracting) "Extracting" else "Choose images") }
        if (workflowState.selectedImage != null) {
            OutlinedButton(
                enabled = workflowState.canRetryExtraction && !profileState.busy,
                onClick = onRetryExtraction,
                modifier = Modifier.semantics { contentDescription = "Retry extraction for selected image" },
            ) { Text("Retry extraction") }
            Text("Retry uses the selected provider and replaces the current review results.", style = MaterialTheme.typography.bodySmall)
        }
        SelectedReceiptImageReview(workflowState.selectedImage)
        val rawOutput = workflowState.reviews.firstOrNull()?.rawModelOutput.orEmpty()
        if (debugOutputEnabled && rawOutput.isNotBlank()) {
            DebugOutputField("Raw extraction response", rawOutput)
        }
        Text("Add more")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onChooseReceipt, enabled = !workflowState.extracting && !workflowState.exporting && workflowState.items.size < MAX_BATCH_ITEMS) { Text("Images") }
            OutlinedButton(onClick = onAddItem, enabled = !workflowState.extracting && !workflowState.exporting && workflowState.items.size < MAX_BATCH_ITEMS) { Text("New item") }

        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("Review and export", style = MaterialTheme.typography.titleLarge)
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onExtractAll, enabled = !workflowState.extracting && !workflowState.exporting && !profileState.busy && profileState.verification?.busy != true && workflowState.items.any { it.eligible } && workflowState.items.none { it.status == BatchItemStatus.PREPARING }) { Text("Extract all") }
                Button(onClick = onExportAll, enabled = oneDrive.readyForExport && workflowState.canExportAll,
                    modifier = Modifier.semantics { contentDescription = "Export all reviewed transactions" }) {
                    Text(if (workflowState.exporting) "Exporting" else "Export All")
                }
                Text("Export All confirms every remaining reviewed transaction and saves one JSON list. All remaining items must be ready.", style = MaterialTheme.typography.bodySmall)
                workflowState.exportAllBlockers.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("${workflowState.items.count { it.status == BatchItemStatus.SUCCEEDED }} of ${workflowState.items.size} completed / ${workflowState.items.count { it.status == BatchItemStatus.PROCESSING }} processing / ${workflowState.items.count { it.status == BatchItemStatus.QUEUED }} queued / limit $MAX_BATCH_ITEMS")
                if (workflowState.extracting) OutlinedButton(onClick = onCancelRemaining) { Text("Cancel remaining") }
                workflowState.batchMessage?.let { CopyableMessage(it); TextButton(onClick = onUndoRemove) { Text("Undo") } }
            }
        }
        if (workflowState.items.isEmpty()) Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(false, { onAddTypedItem() })
                Text("No receipt image")
            }
        }
        workflowState.reviews.forEachIndexed { index, review ->
            if (workflowState.reviews.size > 1) Text("Receipt ${index + 1} of ${workflowState.reviews.size}", style = MaterialTheme.typography.titleLarge)
            ReceiptReviewEditor(review, workflowState.exporting, oneDrive.readyForExport,
                { onReviewChange(index, it) }, { onExport(index) }, debugOutputEnabled)
        }
        } }
        items(workflowState.items, key = { it.itemId }) { item ->
            BatchInputGroup(item, workflowState.items.indexOfFirst { it.itemId == item.itemId } + 1, workflowState.extracting, workflowState.exporting, oneDrive.readyForExport,
                debugOutputEnabled, { onChooseItemImage(item.itemId) }, onRemoveItem, onToggleExpanded, onSourceChange, onRetryItem,
                onTransactionChange, onTransactionExport, onManualValues, onLoadThumbnail, onLoadPreparedImage,
                extractionAvailable = !profileState.busy && profileState.verification?.busy != true && workflowState.items.none { it.status == BatchItemStatus.PREPARING })
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private const val SelectedReceiptPreviewDescription = "Review selected receipt image"

@Composable
internal fun SelectedReceiptImageReview(selectedImage: ReceiptImage?, onRemove: (() -> Unit)? = null, loadFullImage: (suspend () -> ReceiptImage?)? = null) {
    if (selectedImage == null) return

    // Recreate this subtree for each image so a prior bitmap cannot render while a replacement decodes.
    key(selectedImage) {
        var viewerOpen by remember { mutableStateOf(false) }
        LaunchedEffect(selectedImage) { viewerOpen = false }
        val display by produceState<ReceiptImageDisplay>(
            initialValue = ReceiptImageDisplay.Loading,
            key1 = selectedImage,
        ) {
            value = withContext(Dispatchers.Default) {
                runCatching {
                    decodeReceiptPreview(selectedImage.bytes)
                        ?.asImageBitmap()
                        ?.let(ReceiptImageDisplay::Available)
                        ?: ReceiptImageDisplay.Unavailable
                }.getOrDefault(ReceiptImageDisplay.Unavailable)
            }
        }

        when (val current = display) {
            ReceiptImageDisplay.Loading -> Text("Preparing selected image preview")
            ReceiptImageDisplay.Unavailable -> Text(
                "Selected image preview is unavailable. Choose the receipt again to review it.",
            )
            is ReceiptImageDisplay.Available -> {
                Card(
                    Modifier.fillMaxWidth()
                        .clickable { viewerOpen = true }
                        .semantics { contentDescription = SelectedReceiptPreviewDescription },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Review selected image", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.foundation.Image(
                            bitmap = current.bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().height(120.dp).background(MaterialTheme.colorScheme.surface),
                        )
                    }
                }
                if (viewerOpen) {
                    val fullDisplay by produceState<androidx.compose.ui.graphics.ImageBitmap?>(if (loadFullImage == null) current.bitmap else null, selectedImage) {
                        value = withContext(Dispatchers.IO) { loadFullImage?.invoke()?.let { decodeReceiptPreview(it.bytes)?.asImageBitmap() } ?: current.bitmap }
                    }
                    fullDisplay?.let { SelectedReceiptImageViewer(it, onRemove) { viewerOpen = false } }
                }
            }
        }
    }
}

private fun decodeReceiptPreview(bytes: ByteArray): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    // A 4x viewer needs more detail than the compact preview. Cap decoded memory at roughly
    // 48 MiB (12 million ARGB pixels) and the longest edge at 4096 px.
    while (
        bounds.outWidth / sampleSize > 4096 ||
        bounds.outHeight / sampleSize > 4096 ||
        bounds.outWidth.toLong() / sampleSize * (bounds.outHeight.toLong() / sampleSize) > 12_000_000L
    ) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedReceiptImageViewer(
    image: androidx.compose.ui.graphics.ImageBitmap,
    onRemove: (() -> Unit)? = null,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var viewport by remember { mutableStateOf(IntSize.Zero) }
        var transform by remember { mutableStateOf(ReceiptImageViewerTransform()) }
        val imageWidth = image.width.toFloat()
        val imageHeight = image.height.toFloat()
        val viewportWidth = viewport.width.toFloat()
        val viewportHeight = viewport.height.toFloat()
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface) {
                FlowRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    onRemove?.let { TextButton({ it(); onClose() }) { Text("Remove") } }
                    val canZoomIn = transform.scale < ReceiptImageViewerTransform.MAX_SCALE
                    TextButton(
                        onClick = {
                            transform = transform.zoomBy(
                                ReceiptImageViewerTransform.BUTTON_SCALE_FACTOR,
                                viewportWidth, viewportHeight, imageWidth, imageHeight,
                            )
                        },
                        enabled = canZoomIn,
                        modifier = Modifier.semantics { contentDescription = "Zoom in" },
                    ) { Text("Zoom in") }
                    val canZoomOut = transform.scale > ReceiptImageViewerTransform.MIN_SCALE
                    TextButton(
                        onClick = {
                            transform = transform.zoomBy(
                                1f / ReceiptImageViewerTransform.BUTTON_SCALE_FACTOR,
                                viewportWidth, viewportHeight, imageWidth, imageHeight,
                            )
                        },
                        enabled = canZoomOut,
                        modifier = Modifier.semantics { contentDescription = "Zoom out" },
                    ) { Text("Zoom out") }
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.semantics { contentDescription = "Close selected receipt image" },
                    ) { Text("Close") }
                }
            }
            Box(
                Modifier.fillMaxWidth().weight(1f).clipToBounds().onSizeChanged {
                    viewport = it
                    transform = transform.bounded(it.width.toFloat(), it.height.toFloat(), imageWidth, imageHeight)
                }.pointerInput(image, viewport) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        transform = transform.zoomAround(
                            factor = zoom,
                            focalX = centroid.x,
                            focalY = centroid.y,
                            viewportWidth = viewportWidth,
                            viewportHeight = viewportHeight,
                            imageWidth = imageWidth,
                            imageHeight = imageHeight,
                        ).panBy(
                            deltaX = pan.x,
                            deltaY = pan.y,
                            viewportWidth = viewportWidth,
                            viewportHeight = viewportHeight,
                            imageWidth = imageWidth,
                            imageHeight = imageHeight,
                        )
                    }
                },
            ) {
                androidx.compose.foundation.Image(
                    bitmap = image,
                    contentDescription = "Selected receipt image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = transform.scale
                        scaleY = transform.scale
                        translationX = transform.offsetX
                        translationY = transform.offsetY
                    },
                )
            }

        }
    }
}

private sealed interface ReceiptImageDisplay {
    data object Loading : ReceiptImageDisplay
    data object Unavailable : ReceiptImageDisplay
    data class Available(val bitmap: androidx.compose.ui.graphics.ImageBitmap) : ReceiptImageDisplay
}

private fun ModelProfilesUiState.selectedEffectiveProviderId(): String =
    profiles.firstOrNull {
        it.id == selectorState.selectedRemoteProfileId &&
            (it.category == com.hugo.smartexpense.extraction.ApiProfileCategory.SAME_PHONE_SERVER || selectorState.remoteProvidersEnabled)
    }?.id ?: SelectedReceiptModelProviderResolver.BUILT_IN_LOCAL_PROVIDER.id

@Composable
private fun CompactProviderSelector(
    state: ModelProfilesUiState,
    onSelectLocal: () -> Unit,
    onSelectRemote: (ModelProfile) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.profiles.firstOrNull {
        it.id == state.selectorState.selectedRemoteProfileId &&
            (it.category == com.hugo.smartexpense.extraction.ApiProfileCategory.SAME_PHONE_SERVER || state.selectorState.remoteProvidersEnabled)
    }
    Text("Provider for next receipt", style = MaterialTheme.typography.titleMedium)
    OutlinedButton(
        onClick = { expanded = true }, enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Select receipt provider" },
    ) { Text(selected?.let { "${it.displayName} (${if (it.category == com.hugo.smartexpense.extraction.ApiProfileCategory.SAME_PHONE_SERVER) "Same-phone server" else "Remote API"})" } ?: "Built-in on-device Gemma") }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("Built-in on-device Gemma") },
            onClick = { expanded = false; onSelectLocal() },
        )
        state.profiles.forEach { profile ->
            DropdownMenuItem(
                text = {
                    Text(
                        if (profile.category == com.hugo.smartexpense.extraction.ApiProfileCategory.SAME_PHONE_SERVER) "${profile.displayName} (Same-phone server)"
                        else if (state.selectorState.remoteProvidersEnabled) "${profile.displayName} (Remote API)"
                        else "${profile.displayName} — enable remote access in Settings",
                    )
                },
                enabled = profile.category == com.hugo.smartexpense.extraction.ApiProfileCategory.SAME_PHONE_SERVER || state.selectorState.remoteProvidersEnabled,
                onClick = { expanded = false; onSelectRemote(profile) },
            )
        }
    }
    Text(state.effectiveProviderSummary, style = MaterialTheme.typography.bodySmall)
    if (!state.selectorState.remoteProvidersEnabled && state.profiles.any { it.category == com.hugo.smartexpense.extraction.ApiProfileCategory.REMOTE_API }) {
        Text("Remote providers must be enabled in Settings before selection.")
    }
}

@Composable
private fun ProviderVerificationMessage(state: ProviderVerificationState) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) { CopyableMessage(state.message, Modifier.padding(16.dp)) }
}

@Composable
private fun OneDriveStatusCard(
    state: OneDrivePresentation,
    accountName: String?,
    onRecovery: (OneDriveRecoveryAction) -> Unit,
) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "OneDrive export status" }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReceiptStatusBadge(state.summary, ready = state.readyForExport)
            accountName?.let { Text(it) }
            CopyableMessage(state.detail)
            state.recoveryLabel?.let { label ->
                Button(onClick = { onRecovery(state.recoveryAction) }) { Text(label) }
            }
        }
    }
}

@Composable
internal fun ReceiptReviewEditor(
    state: ReceiptReviewState,
    exporting: Boolean,
    canExport: Boolean,
    onStateChange: (ReceiptReviewState) -> Unit,
    onExport: () -> Unit,
    debugOutputEnabled: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (state.manualEntryRequired) "Manual receipt entry" else "Review extracted receipt", style = MaterialTheme.typography.headlineSmall)
            if (state.totalAmount.isNotBlank()) {
                Text("${state.currency} ${state.totalAmount}", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em))
            }
            CopyableMessage(state.message)
            val editable = !exporting && state.exportExpenseId == null
            ReviewField("Receipt date (yyyy-MM-dd)", state.receiptDate, editable) { onStateChange(state.copy(receiptDate = it)) }
            ReviewField("Merchant", state.merchantName, editable) { onStateChange(state.copy(merchantName = it)) }
            ReviewField("Total amount", state.totalAmount, editable) { onStateChange(state.copy(totalAmount = it)) }
            ReviewField("Currency", state.currency, editable) { onStateChange(state.copy(currency = it.uppercase())) }
            ReviewField("Notes", state.notes, editable) { onStateChange(state.copy(notes = it)) }
            ReviewField("Merchant location", state.merchantLocation, editable) { onStateChange(state.copy(merchantLocation = it)) }
            if (state.exportExpenseId != null && !state.exportComplete) {
                Text("This export keeps the saved fields on retry. Choose the receipt again to make changes.")
            }
            if (state.currency != "CAD") Text("The Windows processor accepts CAD only. Other currencies go to review without conversion.")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ReceiptStatusBadge("Extraction status: ${state.extractionStatus}", ready = state.exportComplete || state.extractionStatus in listOf("confirmed", "manual", "extracted"))
            Text("Confidence: ${state.confidence.ifBlank { "not available" }}")
            val validationErrors = state.expenseValidationErrors()
            validationErrors.forEach { CopyableMessage(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = canExport && validationErrors.isEmpty() && !exporting && !state.exportComplete, onClick = onExport) {
                Text(when {
                    exporting -> "Exporting"
                    state.exportComplete -> "Exported"
                    state.exportExpenseId != null -> "Retry export"
                    else -> "Confirm and export"
                })
            }
            if (!canExport) Text("Connect and verify OneDrive access before exporting.")
            if (debugOutputEnabled && state.exportJsonPreview != null) {
                Text(
                    if (state.exportComplete) "Published handoff JSON" else "Generated handoff JSON (not published)",
                    style = MaterialTheme.typography.titleMedium,
                )
                DebugOutputField("Handoff JSON", state.exportJsonPreview)
            }
        }
    }
}

@Composable
internal fun ReceiptStatusBadge(label: String, ready: Boolean = false, failed: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = when { failed -> colors.errorContainer; ready -> colors.primaryContainer; else -> colors.secondaryContainer },
        contentColor = when { failed -> colors.onErrorContainer; ready -> colors.onPrimaryContainer; else -> colors.onSecondaryContainer },
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun DebugOutputField(label: String, value: String) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        minLines = 4,
        maxLines = 10,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
    )
}

@Composable
internal fun ReviewField(label: String, value: String, enabled: Boolean, onValueChange: (String) -> Unit) {
    val numeric = label.contains("date", ignoreCase = true) || label.contains("total", ignoreCase = true) || label.contains("amount", ignoreCase = true) || label.contains("tax", ignoreCase = true)
    OutlinedTextField(value, onValueChange, label = { Text(label) }, enabled = enabled, singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = if (numeric) ReceiptFontFamily else MaterialTheme.typography.bodyLarge.fontFamily),
        modifier = Modifier.fillMaxWidth())
}
