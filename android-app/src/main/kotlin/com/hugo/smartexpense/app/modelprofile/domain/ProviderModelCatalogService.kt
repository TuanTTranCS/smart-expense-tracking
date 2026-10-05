package com.hugo.smartexpense.app.modelprofile.domain

import com.hugo.smartexpense.app.connectivity.HttpModelsEndpointChecker
import com.hugo.smartexpense.app.connectivity.ModelsEndpointChecker
import com.hugo.smartexpense.app.connectivity.ModelsEndpointFailure
import com.hugo.smartexpense.app.connectivity.ModelsEndpointInput
import com.hugo.smartexpense.app.connectivity.ModelsEndpointResult
import com.hugo.smartexpense.app.connectivity.modelsEndpointUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

data class AvailableProviderModel(val id: String, val label: String = id)

enum class CatalogFailure { INVALID_INPUT, ACCESS_DENIED, UNSUPPORTED, RATE_LIMITED, SERVER, REDIRECT, TIMEOUT, INVALID_LIST, TOO_LARGE, NETWORK }

sealed interface ProviderModelCatalogResult {
    data class Loaded(val models: List<AvailableProviderModel>) : ProviderModelCatalogResult
    data class Failed(val reason: String, val kind: CatalogFailure = CatalogFailure.NETWORK) : ProviderModelCatalogResult
}

fun interface ProviderModelCatalogService {
    suspend fun load(baseUrl: String, apiKey: String?): ProviderModelCatalogResult
}

/** No profile validation, persistence, inference, or receipt input belongs in discovery. */
class CompatibleProviderModelCatalogService(
    private val checker: ModelsEndpointChecker = HttpModelsEndpointChecker(),
) : ProviderModelCatalogService {
    override suspend fun load(baseUrl: String, apiKey: String?): ProviderModelCatalogResult {
        val input = ModelsEndpointInput(baseUrl, apiKey)
        if (modelsEndpointUrl(input) == null) return failure(CatalogFailure.INVALID_INPUT)
        val result = try {
            withTimeoutOrNull(10_000) { checker.check(input) } ?: return failure(CatalogFailure.TIMEOUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failure(CatalogFailure.NETWORK)
        }
        return when (result) {
            is ModelsEndpointResult.Reachable -> {
                if (result.modelIds.any(String::isBlank)) failure(CatalogFailure.INVALID_LIST)
                else ProviderModelCatalogResult.Loaded(result.modelIds.distinct().sorted().map(::AvailableProviderModel))
            }
            is ModelsEndpointResult.Unavailable -> failure(when {
                result.httpStatus in listOf(401, 403) -> CatalogFailure.ACCESS_DENIED
                result.httpStatus in listOf(404, 405) -> CatalogFailure.UNSUPPORTED
                result.httpStatus == 429 -> CatalogFailure.RATE_LIMITED
                result.httpStatus != null && result.httpStatus >= 500 -> CatalogFailure.SERVER
                result.httpStatus != null && result.httpStatus in 300..399 -> CatalogFailure.REDIRECT
                result.kind == ModelsEndpointFailure.INVALID_INPUT -> CatalogFailure.INVALID_INPUT
                result.kind == ModelsEndpointFailure.INVALID_LIST -> CatalogFailure.INVALID_LIST
                result.kind == ModelsEndpointFailure.TOO_LARGE -> CatalogFailure.TOO_LARGE
                result.kind == ModelsEndpointFailure.TIMEOUT -> CatalogFailure.TIMEOUT
                else -> CatalogFailure.NETWORK
            })
        }
    }

    private fun failure(kind: CatalogFailure): ProviderModelCatalogResult.Failed {
        val reason = when (kind) {
            CatalogFailure.INVALID_INPUT -> "Enter an HTTP(S) API prefix without URL credentials, query or fragment, and a safe API key."
            CatalogFailure.ACCESS_DENIED -> "Access denied. Check the API key and endpoint."
            CatalogFailure.UNSUPPORTED -> "This endpoint does not support model listing. Check the API prefix."
            CatalogFailure.RATE_LIMITED -> "Model listing is rate limited. Wait and try again."
            CatalogFailure.SERVER -> "The provider could not load its model list. Try again later."
            CatalogFailure.REDIRECT -> "The endpoint redirected the request. Enter the final API prefix."
            CatalogFailure.TIMEOUT -> "Model listing timed out. Check connectivity and try again."
            CatalogFailure.INVALID_LIST -> "The endpoint returned an invalid model list. Check the compatible API prefix."
            CatalogFailure.TOO_LARGE -> "The model list exceeds the 1 MiB limit and could not be loaded."
            CatalogFailure.NETWORK -> "Model listing is unavailable. Check the URL and network connection."
        }
        return ProviderModelCatalogResult.Failed("$reason You can still enter a Model ID manually.", kind)
    }
}
