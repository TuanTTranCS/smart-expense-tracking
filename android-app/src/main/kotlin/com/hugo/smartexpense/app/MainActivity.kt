package com.hugo.smartexpense.app

import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hugo.smartexpense.androidextraction.AndroidLiteRtLmReceiptModelClientFactory
import com.hugo.smartexpense.app.graphauth.GraphAuthenticationViewModel
import com.hugo.smartexpense.app.graphauth.MicrosoftGraphOneDriveFolderVerifier
import com.hugo.smartexpense.app.graphauth.MsalGraphAuthenticationClient
import com.hugo.smartexpense.app.modelprofile.data.ModelProfileDatabase
import com.hugo.smartexpense.app.modelprofile.data.RoomModelProfileRepository
import com.hugo.smartexpense.app.modelprofile.domain.RemoteModelClientFactory
import com.hugo.smartexpense.app.modelprofile.domain.SelectedReceiptModelProviderResolver
import com.hugo.smartexpense.app.modelprofile.migration.AndroidLegacyModelProfilePreferences
import com.hugo.smartexpense.app.modelprofile.migration.LegacyModelProfileMigrator
import com.hugo.smartexpense.app.modelprofile.transfer.ProviderConfigDocumentStore
import com.hugo.smartexpense.app.modelprofile.ui.AndroidLocalModelReadinessService
import com.hugo.smartexpense.app.modelprofile.ui.DefaultModelProfileTestService
import com.hugo.smartexpense.app.modelprofile.ui.LocalModelReadinessResult
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfilesViewModel
import com.hugo.smartexpense.app.receiptexport.AndroidReceiptExportImageStore
import com.hugo.smartexpense.app.receiptexport.MicrosoftGraphReceiptExportPublisher
import com.hugo.smartexpense.app.receiptexport.ReceiptExportController
import com.hugo.smartexpense.app.receiptexport.ReceiptExportDatabase
import com.hugo.smartexpense.app.receiptexport.RoomReceiptExportRepository
import com.hugo.smartexpense.app.receipt.ui.ReceiptWorkflowViewModel
import com.hugo.smartexpense.app.settings.data.SharedPreferencesAppSettingsRepository
import com.hugo.smartexpense.app.ui.SmartExpenseApp
import com.hugo.smartexpense.extraction.ReceiptExtractionPipeline
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("model_selector", MODE_PRIVATE)
        val detectedName = listOf(Build.MANUFACTURER, Build.MODEL).filterNot(String::isNullOrBlank)
            .joinToString(" ").ifBlank { "Android device" }
        val settingsRepository = SharedPreferencesAppSettingsRepository(preferences, detectedName)
        val keyStore = AndroidApiKeyStore(this)
        val profileRepository = RoomModelProfileRepository(ModelProfileDatabase.getInstance(this).modelProfileDao())
        val providerConfigDocumentStore = ProviderConfigDocumentStore(contentResolver)
        val resolver = SelectedReceiptModelProviderResolver(profileRepository)
        val remoteClientFactory = RemoteModelClientFactory(keyStore)
        val localReadinessService = AndroidLocalModelReadinessService(this)
        val localClientFactory = AndroidLiteRtLmReceiptModelClientFactory(this, File(filesDir, "models"))
        val migrator = LegacyModelProfileMigrator(
            AndroidLegacyModelProfilePreferences(preferences), profileRepository, keyStore,
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
            sourceDeviceName = { settingsRepository.settings.value.deviceName },
        )
        val profilesFactory = ModelProfilesViewModel.Factory {
            ModelProfilesViewModel(
                repository = profileRepository,
                credentialStore = keyStore,
                resolver = resolver,
                testService = DefaultModelProfileTestService(keyStore) { RemoteModelClientFactory(it) },
                migrator = migrator,
            )
        }
        val graphFactory = GraphAuthenticationViewModel.Factory {
            GraphAuthenticationViewModel(
                authenticationClient = graphAuthenticationClient,
                folderVerifier = MicrosoftGraphOneDriveFolderVerifier(),
            )
        }
        val workflowFactory = ReceiptWorkflowViewModel.Factory {
            ReceiptWorkflowViewModel(
                settingsRepository = settingsRepository,
                extractReceipt = extract@{ uri, reduceOversizedImages, remoteProfileSnapshot ->
                    val client = if (remoteProfileSnapshot != null) {
                        remoteClientFactory.create(remoteProfileSnapshot)
                    } else {
                        when (val readiness = localReadinessService.check()) {
                            is LocalModelReadinessResult.Ready -> localClientFactory.create()
                            is LocalModelReadinessResult.NotReady -> return@extract listOf(ReceiptReviewState.manual(
                                "${readiness.reason} No receipt data was sent remotely.",
                            ))
                        }
                    }
                    ReceiptExtractionController(
                        imageLoader = imageLoader,
                        extractor = PipelineReceiptExtractor(ReceiptExtractionPipeline(client)),
                    ).extractAll(uri, reduceOversizedImages)
                },
                startExport = exportController::start,
                retryExport = exportController::retry,
            )
        }

        setContent {
            val profilesViewModel: ModelProfilesViewModel = viewModel(factory = profilesFactory)
            val graphViewModel: GraphAuthenticationViewModel = viewModel(factory = graphFactory)
            val workflowViewModel: ReceiptWorkflowViewModel = viewModel(factory = workflowFactory)
            SmartExpenseApp(
                activity = this,
                profilesViewModel = profilesViewModel,
                graphViewModel = graphViewModel,
                workflowViewModel = workflowViewModel,
                settingsRepository = settingsRepository,
                localReadinessService = localReadinessService,
                providerConfigDocumentStore = providerConfigDocumentStore,
            )
        }
    }
}
