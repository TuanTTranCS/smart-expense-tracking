package com.hugo.smartexpense.app.tailscale

import com.hugo.smartexpense.app.connectivity.HttpModelsEndpointChecker
import com.hugo.smartexpense.app.connectivity.ModelsEndpointChecker
import com.hugo.smartexpense.app.connectivity.ModelsEndpointInput
import com.hugo.smartexpense.app.connectivity.ModelsEndpointResult
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfileTestService
import com.hugo.smartexpense.extraction.ApiKeyStore
import com.hugo.smartexpense.extraction.ModelProfile
import com.hugo.smartexpense.extraction.ProviderTestResult
import com.hugo.smartexpense.extraction.ApiProfileCategory
import com.hugo.smartexpense.app.modelprofile.domain.ProfileConfigurationCoordinator

/** Adapts an immutable saved profile to the reusable, receipt-free models endpoint check. */
class SavedProfileReadinessProbe(
    private val credentials: ApiKeyStore,
    private val checker: ModelsEndpointChecker = HttpModelsEndpointChecker(),
    private val configurationCoordinator: ProfileConfigurationCoordinator? = null,
) : ModelProfileTestService {
    override suspend fun test(profile: ModelProfile, apiKeyOverride: String?): ProviderTestResult {
        if (profile.category != ApiProfileCategory.REMOTE_API) return ProviderTestResult.Failed("Tailscale is available only for Remote API profiles.")
        val captured = configurationCoordinator?.snapshot(profile)
        val effectiveProfile = captured?.profile ?: profile
        val result = checker.check(ModelsEndpointInput(
            baseUrl = effectiveProfile.baseUrl,
            apiKey = apiKeyOverride ?: if (captured != null) captured.apiKey else credentials.get(profile.credentialAlias),
        ))
        return when (result) {
            is ModelsEndpointResult.Reachable -> ProviderTestResult.Success
            is ModelsEndpointResult.Unavailable -> ProviderTestResult.Failed(result.reason)
        }
    }
}
