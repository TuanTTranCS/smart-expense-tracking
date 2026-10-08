package com.hugo.smartexpense.app.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hugo.smartexpense.app.graphauth.GraphAuthenticationViewModel
import com.hugo.smartexpense.app.graphauth.OneDriveRecoveryAction
import com.hugo.smartexpense.app.graphauth.toOneDrivePresentation
import com.hugo.smartexpense.app.modelprofile.transfer.ProviderConfigDocumentStore
import com.hugo.smartexpense.app.modelprofile.ui.LocalModelReadinessService
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfilesViewModel
import com.hugo.smartexpense.app.receipt.ui.ReceiptWorkflowScreen
import com.hugo.smartexpense.app.receipt.ui.ReceiptWorkflowViewModel
import com.hugo.smartexpense.app.settings.data.AppSettingsRepository
import com.hugo.smartexpense.app.settings.ui.SettingsScreen
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.hugo.smartexpense.extraction.ModelProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAIN_ROUTE = "main"
private const val SETTINGS_ROUTE = "settings"

@Composable
fun SmartExpenseApp(
    activity: Activity,
    profilesViewModel: ModelProfilesViewModel,
    graphViewModel: GraphAuthenticationViewModel,
    workflowViewModel: ReceiptWorkflowViewModel,
    settingsRepository: AppSettingsRepository,
    localReadinessService: LocalModelReadinessService,
    providerConfigDocumentStore: ProviderConfigDocumentStore,
) {
    val navController = rememberNavController()
    val profileState by profilesViewModel.uiState.collectAsStateWithLifecycle()
    val graphState by graphViewModel.uiState.collectAsStateWithLifecycle()
    val workflowState by workflowViewModel.uiState.collectAsStateWithLifecycle()
    val settings by settingsRepository.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, profilesViewModel) {
        val workflowLifecycle = com.hugo.smartexpense.app.receipt.ui.ReceiptWorkflowLifecycleObserver(workflowViewModel::setForeground) { activity.isChangingConfigurations }
        val observer = LifecycleEventObserver { owner, event ->
            workflowLifecycle.onStateChanged(owner, event)
            when (event) {
                Lifecycle.Event.ON_RESUME -> profilesViewModel.setConnectivityMonitoring(true)
                Lifecycle.Event.ON_PAUSE -> profilesViewModel.setConnectivityMonitoring(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        profilesViewModel.setConnectivityMonitoring(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            profilesViewModel.setConnectivityMonitoring(false)
        }
    }

    LaunchedEffect(profileState.selectorState.remoteProvidersEnabled) { if (!profileState.selectorState.remoteProvidersEnabled) workflowViewModel.revokeRemoteAccess() }
    SmartExpenseAppContent {
        NavHost(navController, startDestination = MAIN_ROUTE) {
            composable(MAIN_ROUTE) {
                var pickerTarget by remember { mutableStateOf<String?>(null) }
                fun retainAndPrepare(uris: List<android.net.Uri>) {
                    uris.forEach { uri -> runCatching { activity.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
                    workflowViewModel.prepareImages(uris.map { it.toString() }, pickerTarget)
                    pickerTarget = null
                }
                val picker = rememberLauncherForActivityResult(PickMultipleVisualMedia(20)) { uris -> retainAndPrepare(uris) }
                val singlePicker = rememberLauncherForActivityResult(PickVisualMedia()) { uri -> uri?.let { retainAndPrepare(listOf(it)) } }
                ReceiptWorkflowScreen(
                    profileState = profileState,
                    workflowState = workflowState,
                    graphState = graphState,
                    oneDrive = graphState.toOneDrivePresentation(),
                    onOpenSettings = {
                        navController.navigate(SETTINGS_ROUTE) { launchSingleTop = true }
                    },
                    onSelectLocal = profilesViewModel::selectLocalProvider,
                    onSelectRemote = profilesViewModel::selectProfile,
                    onVerifyProvider = { profilesViewModel.verifySelectedProvider(localReadinessService) },
                    onOneDriveRecovery = { action ->
                        when (action) {
                            OneDriveRecoveryAction.CONNECT, OneDriveRecoveryAction.RECONNECT -> graphViewModel.connect(activity)
                            OneDriveRecoveryAction.VERIFY -> graphViewModel.verifyFolder()
                            OneDriveRecoveryAction.NONE -> Unit
                        }
                    },
                    onChooseItemImage = { id -> pickerTarget = id; singlePicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
                    onChooseReceipt = { pickerTarget = null; if (20 - workflowState.items.size == 1) singlePicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) else picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
                    onRetryExtraction = {
                        workflowViewModel.retryExtraction(profilesViewModel.uiState.value.effectiveRemoteProfile())
                    },
                    onReviewChange = workflowViewModel::updateReview,
                    onExport = workflowViewModel::export,
                    debugOutputEnabled = settings.debugOutputEnabled,
                    onTailscaleRequest = profilesViewModel::requestTailscale,
                    onAddTypedItem = workflowViewModel::addTypedItem,
                    onAddItem = workflowViewModel::addItem,
                    onExtractAll = { workflowViewModel.extractAll(profilesViewModel.uiState.value.effectiveRemoteProfile()) },
                    onCancelRemaining = workflowViewModel::cancelRemaining,
                    onRemoveItem = workflowViewModel::removeItem,
                    onUndoRemove = workflowViewModel::undoRemove,
                    onToggleExpanded = workflowViewModel::toggleExpanded,
                    onSourceChange = workflowViewModel::updateSource,
                    onRetryItem = { id, confirmed -> workflowViewModel.retryItem(id, profilesViewModel.uiState.value.effectiveRemoteProfile(), confirmed) },
                    onTransactionChange = workflowViewModel::updateTransaction,
                    onTransactionExport = workflowViewModel::exportTransaction,
                    onExportAll = workflowViewModel::exportAll,
                    onManualValues = workflowViewModel::useManualValues,
                    onLoadThumbnail = workflowViewModel::loadPreparedThumbnail,
                    onLoadPreparedImage = workflowViewModel::loadPreparedImage,
                )
            }
            composable(SETTINGS_ROUTE) {
                val exporter = rememberLauncherForActivityResult(CreateDocument("application/json")) { uri ->
                    if (uri != null) {
                        val count = profilesViewModel.uiState.value.profiles.size
                        runCatching { profilesViewModel.createProviderConfigExportJson() }
                            .onSuccess { json ->
                                scope.launch {
                                    runCatching { withContext(Dispatchers.IO) { providerConfigDocumentStore.write(uri, json) } }
                                        .onSuccess { profilesViewModel.providerConfigExportCompleted(count) }
                                        .onFailure(profilesViewModel::reportProviderConfigFailure)
                                }
                            }.onFailure(profilesViewModel::reportProviderConfigFailure)
                    }
                }
                val importer = rememberLauncherForActivityResult(OpenDocument()) { uri ->
                    if (uri != null) scope.launch {
                        runCatching { withContext(Dispatchers.IO) { providerConfigDocumentStore.read(uri) } }
                            .onSuccess(profilesViewModel::loadProviderConfigImport)
                            .onFailure(profilesViewModel::reportProviderConfigFailure)
                    }
                }
                SettingsScreen(
                    profileState = profileState,
                    profilesViewModel = profilesViewModel,
                    settings = settings,
                    onReduceOversizedImagesChange = settingsRepository::setReduceOversizedImages,
                    onDebugOutputEnabledChange = settingsRepository::setDebugOutputEnabled,
                    onDeviceNameChange = settingsRepository::setDeviceNameOverride,
                    onBatchMaxConcurrencyChange = settingsRepository::setBatchMaxConcurrency,
                    onBatchStartSpacingMillisChange = settingsRepository::setBatchStartSpacingMillis,
                    onExportProfiles = { exporter.launch(providerConfigFileName()) },
                    onImportProfiles = { importer.launch(arrayOf("application/json", "text/json")) },
                    onNavigateBack = { navController.popBackStack() },
                )
            }
        }
    }
}

private fun providerConfigFileName(): String =
    "smart-expense-provider-config_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT))}.json"

private fun com.hugo.smartexpense.app.modelprofile.ui.ModelProfilesUiState.effectiveRemoteProfile(): ModelProfile? =
    profiles.firstOrNull {
        selectorState.remoteProvidersEnabled && it.id == selectorState.selectedRemoteProfileId
    }
