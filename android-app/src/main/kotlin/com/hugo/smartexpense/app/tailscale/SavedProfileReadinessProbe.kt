package com.hugo.smartexpense.app.tailscale

import com.hugo.smartexpense.app.connectivity.HttpModelsEndpointChecker
import com.hugo.smartexpense.app.connectivity.ModelsEndpointChecker
import com.hugo.smartexpense.app.connectivity.ModelsEndpointInput
import com.hugo.smartexpense.app.connectivity.ModelsEndpointResult
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfileTestService
import com.hugo.smartexpense.extraction.ApiKeyStore
import com.hugo.smartexpense.extraction.ModelProfile
import com.hugo.smartexpense.extraction.ProviderTestResult

/** Adapts an immutable saved profile to the reusable, receipt-free models endpoint check. */
class SavedProfileReadinessProbe(
    private val credentials: ApiKeyStore,
    private val checker: ModelsEndpointChecker = HttpModelsEndpointChecker(),
) : ModelProfileTestService {
    override suspend fun test(profile: ModelProfile, apiKeyOverride: String?): ProviderTestResult {
        val result = checker.check(ModelsEndpointInput(
            baseUrl = profile.baseUrl,
            apiKey = apiKeyOverride ?: credentials.get(profile.credentialAlias),
        ))
        return when (result) {
            is ModelsEndpointResult.Reachable -> ProviderTestResult.Success
            is ModelsEndpointResult.Unavailable -> ProviderTestResult.Failed(result.reason)
        }
    }
}
