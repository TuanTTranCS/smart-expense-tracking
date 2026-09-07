package com.hugo.smartexpense.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hugo.smartexpense.app.graphauth.GraphAuthenticationViewModel
import com.hugo.smartexpense.app.graphauth.MicrosoftAccountPanel
import com.hugo.smartexpense.app.graphauth.MicrosoftGraphOneDriveFolderVerifier
import com.hugo.smartexpense.app.graphauth.MsalGraphAuthenticationClient
import com.hugo.smartexpense.app.modelprofile.data.ModelProfileDatabase
import com.hugo.smartexpense.app.modelprofile.data.RoomModelProfileRepository
import com.hugo.smartexpense.app.modelprofile.domain.RemoteModelClientFactory
import com.hugo.smartexpense.app.modelprofile.domain.SelectedReceiptModelProviderResolver
import com.hugo.smartexpense.app.modelprofile.migration.AndroidLegacyModelProfilePreferences
import com.hugo.smartexpense.app.modelprofile.migration.LegacyModelProfileMigrator
import com.hugo.smartexpense.app.modelprofile.ui.DefaultModelProfileTestService
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfilesPanel
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfilesViewModel
import com.hugo.smartexpense.app.receiptexport.AndroidReceiptExportImageStore
import com.hugo.smartexpense.app.receiptexport.MicrosoftGraphReceiptExportPublisher
import com.hugo.smartexpense.app.receiptexport.ReceiptExportController
import com.hugo.smartexpense.app.receiptexport.ReceiptExportDatabase
import com.hugo.smartexpense.app.receiptexport.ReceiptExportFailure
import com.hugo.smartexpense.app.receiptexport.ReceiptExportRecord
import com.hugo.smartexpense.app.receiptexport.ReceiptExportStatus
import com.hugo.smartexpense.app.receiptexport.RoomReceiptExportRepository
import com.hugo.smartexpense.extraction.ModelProviderType
import com.hugo.smartexpense.extraction.ReceiptExtractionPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("model_selector", MODE_PRIVATE)
        val keyStore = AndroidApiKeyStore(this)
        val repository = RoomModelProfileRepository(ModelProfileDatabase.getInstance(this).modelProfileDao())
        val resolver = SelectedReceiptModelProviderResolver(repository)
        val clientFactory = RemoteModelClientFactory(keyStore)
        val migrator = LegacyModelProfileMigrator(
            AndroidLegacyModelProfilePreferences(preferences), repository, keyStore,
        )
        val imageLoader = ReceiptImageLoader(
            source = AndroidReceiptImageContentSource(contentResolver),
            sizeReducer = AndroidReceiptImageSizeReducer(),
        )
        val graphAuthenticationClient = MsalGraphAuthenticationClient(this)
        val exportImageStore = AndroidReceiptExportImageStore(this, imageLoader)
        val exportController = ReceiptExportController(
            repository = RoomReceiptExportRepository(ReceiptExportDatabase.getInstance(this).receiptExportDao()),
            imagePreparer = exportImageStore,
            imageReader = exportImageStore,
            publisher = MicrosoftGraphReceiptExportPublisher(graphAuthenticationClient),
            sourceDeviceId = {
                preferences.getString("source_device_id", null) ?: UUID.randomUUID().toString().also {
                    preferences.edit().putString("source_device_id", it).apply()
                }
            },
        )
        val viewModelFactory = ModelProfilesViewModel.Factory {
            ModelProfilesViewModel(
                repository = repository,
                credentialStore = keyStore,
                resolver = resolver,
                testService = DefaultModelProfileTestService(keyStore) { store -> RemoteModelClientFactory(store) },
                migrator = migrator,
            )
        }
        val graphAuthenticationViewModelFactory = GraphAuthenticationViewModel.Factory {
            GraphAuthenticationViewModel(
                authenticationClient = graphAuthenticationClient,
                folderVerifier = MicrosoftGraphOneDriveFolderVerifier(),
            )
        }

        setContent {
            val profilesViewModel: ModelProfilesViewModel = viewModel(factory = viewModelFactory)
            val graphAuthenticationViewModel: GraphAuthenticationViewModel = viewModel(
                factory = graphAuthenticationViewModelFactory,
            )
            val profileState by profilesViewModel.uiState.collectAsStateWithLifecycle()
            val graphAuthenticationState by graphAuthenticationViewModel.uiState.collectAsStateWithLifecycle()
            var extracting by remember { mutableStateOf(false) }
            var exporting by remember { mutableStateOf(false) }
            var reviewState by remember { mutableStateOf<ReceiptReviewState?>(null) }
            var reduceOversizedImages by remember {
                mutableStateOf(preferences.getBoolean("reduce_oversized_images", true))
            }
            val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                extracting = true
                reviewState = null
                val reduceSnapshot = reduceOversizedImages
                lifecycleScope.launch {
                    val result = runCatching {
                        val selectedSnapshot = resolver.resolve()
                        if (selectedSnapshot.selectedProvider.type != ModelProviderType.OPENAI_COMPATIBLE_API ||
                            selectedSnapshot.remoteProfile == null
                        ) {
                            ReceiptReviewState.manual(
                                "The local provider is selected, but on-device model execution is not installed yet. No receipt data was sent remotely.",
                            )
                        } else {
                            withContext(Dispatchers.IO) {
                                ReceiptExtractionController(
                                    imageLoader = imageLoader,
                                    extractor = PipelineReceiptExtractor(
                                        ReceiptExtractionPipeline(clientFactory.create(selectedSnapshot.remoteProfile)),
                                    ),
                                ).extract(uri.toString(), reduceSnapshot)
                            }
                        }
                    }.getOrElse { ReceiptReviewState.manual(it.message ?: "The receipt could not be processed.") }
                    extracting = false
                    reviewState = result.copy(sourceImageUri = uri.toString())
                }
            }

            MaterialTheme {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ModelProfilesPanel(profileState, profilesViewModel)
                    MicrosoftAccountPanel(
                        state = graphAuthenticationState,
                        activity = this@MainActivity,
                        viewModel = graphAuthenticationViewModel,
                    )
                    CheckboxRow(
                        checked = reduceOversizedImages,
                        label = "Reduce input images larger than 200 KB",
                        onCheckedChange = {
                            reduceOversizedImages = it
                            preferences.edit().putBoolean("reduce_oversized_images", it).apply()
                        },
                    )
                    Text("Oversized images are resized and encoded as JPEG below 200 KB before extraction.")
                    Button(
                        enabled = !extracting && !profileState.busy,
                        onClick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
                    ) { Text(if (extracting) "Extracting" else "Choose receipt") }
                    reviewState?.let { state ->
                        ReceiptReviewEditor(
                            state = state,
                            exporting = exporting,
                            canExport = graphAuthenticationState.status == com.hugo.smartexpense.app.graphauth.GraphAuthenticationStatus.SIGNED_IN,
                            onStateChange = { reviewState = it },
                            onExport = {
                                exporting = true
                                lifecycleScope.launch {
                                    val result = runCatching {
                                        withContext(Dispatchers.IO) {
                                            if (state.exportExpenseId == null) {
                                                exportController.start(state)
                                            } else {
                                                exportController.retry(state.exportExpenseId)
                                            }
                                        }
                                    }
                                    exporting = false
                                    reviewState = result.fold(
                                        onSuccess = { record -> state.withExportResult(record) },
                                        onFailure = { error -> state.copy(message = error.message ?: "The receipt could not be exported.") },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceiptReviewEditor(
    state: ReceiptReviewState,
    exporting: Boolean,
    canExport: Boolean,
    onStateChange: (ReceiptReviewState) -> Unit,
    onExport: () -> Unit,
) {
    Text(if (state.manualEntryRequired) "Manual receipt entry" else "Review extracted receipt", style = MaterialTheme.typography.headlineSmall)
    Text(state.message)
    ReviewField("Receipt date (yyyy-MM-dd)", state.receiptDate) { onStateChange(state.copy(receiptDate = it)) }
    ReviewField("Merchant", state.merchantName) { onStateChange(state.copy(merchantName = it)) }
    ReviewField("Total amount", state.totalAmount) { onStateChange(state.copy(totalAmount = it)) }
    ReviewField("Currency", state.currency) { onStateChange(state.copy(currency = it.uppercase())) }
    ReviewField("Merchant location", state.merchantLocation) { onStateChange(state.copy(merchantLocation = it)) }
    Text("Extraction status: ${state.extractionStatus}")
    Text("Confidence: ${state.confidence.ifBlank { "not available" }}")
    if (state.rawModelOutput.isNotBlank()) {
        Text("Raw model output", style = MaterialTheme.typography.titleMedium)
        Text(state.rawModelOutput)
    }
    Button(enabled = canExport && !exporting && !state.exportComplete, onClick = onExport) {
        Text(
            when {
                exporting -> "Exporting"
                state.exportComplete -> "Exported"
                state.exportExpenseId != null -> "Retry export"
                else -> "Confirm and export"
            },
        )
    }
    if (!canExport) Text("Connect your Microsoft account before exporting.")
}

private fun ReceiptReviewState.withExportResult(record: ReceiptExportRecord): ReceiptReviewState = copy(
    exportExpenseId = record.expenseId,
    exportComplete = record.status == ReceiptExportStatus.EXPORTED,
    message = if (record.status == ReceiptExportStatus.EXPORTED) {
        "Receipt image and handoff JSON exported to OneDrive."
    } else {
        when (record.failure) {
            ReceiptExportFailure.RECONNECT_REQUIRED -> "Your Microsoft session needs attention. Reconnect, then retry this export."
            ReceiptExportFailure.PERMISSION_DENIED -> "OneDrive access was denied. Reconnect and approve file access, then retry."
            ReceiptExportFailure.RATE_LIMITED -> record.retryAfterSeconds?.let { "Microsoft Graph is busy. Retry in $it seconds." }
                ?: "Microsoft Graph is busy. Wait, then retry."
            ReceiptExportFailure.NETWORK_UNAVAILABLE -> "OneDrive could not be reached. Check the network, then retry."
            ReceiptExportFailure.INSUFFICIENT_STORAGE -> "The receipt could not be stored. Free device or OneDrive space, then retry."
            ReceiptExportFailure.PATH_CONFLICT -> "A OneDrive item conflicts with the export path. Check the expense folders, then retry."
            ReceiptExportFailure.INVALID_RECEIPT -> "Check the receipt fields and image, then retry."
            ReceiptExportFailure.SERVICE_FAILURE, null -> "Microsoft Graph could not complete the export. Try again later."
        }
    },
)

@Composable
private fun ReviewField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(value, onValueChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun CheckboxRow(checked: Boolean, label: String, onCheckedChange: (Boolean) -> Unit) {
    Row {
        Checkbox(checked, onCheckedChange)
        Text(label, modifier = Modifier.padding(top = 12.dp))
    }
}
