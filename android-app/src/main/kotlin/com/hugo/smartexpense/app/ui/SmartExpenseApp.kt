package com.hugo.smartexpense.app.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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

    MaterialTheme {
        NavHost(navController, startDestination = MAIN_ROUTE) {
            composable(MAIN_ROUTE) {
                val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
                    uri?.let {
                        workflowViewModel.importReceipt(
                            it.toString(),
                            profilesViewModel.uiState.value.effectiveRemoteProfile(),
                        )
                    }
                }
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
                    onChooseReceipt = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
                    onReviewChange = workflowViewModel::updateReview,
                    onExport = workflowViewModel::export,
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
