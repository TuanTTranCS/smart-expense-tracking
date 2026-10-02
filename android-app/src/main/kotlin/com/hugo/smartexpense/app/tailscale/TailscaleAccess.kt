package com.hugo.smartexpense.app.tailscale

import com.hugo.smartexpense.app.connectivity.UnknownVpnStatusSource
import com.hugo.smartexpense.app.connectivity.VpnStatus
import com.hugo.smartexpense.app.connectivity.VpnStatusSource
import com.hugo.smartexpense.app.modelprofile.domain.SelectedReceiptModelProviderResolver
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfileTestService
import com.hugo.smartexpense.extraction.ModelProfile
import com.hugo.smartexpense.extraction.ModelProfileRepository
import com.hugo.smartexpense.extraction.ProviderTestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A dispatched request is never evidence of delivery, acceptance, or VPN state. */
fun interface TailscaleController {
    fun request(profile: ModelProfile, connect: Boolean)
}

data class TailscaleAccessState(
    val profile: ModelProfile,
    val connectRequested: Boolean = false,
    val busy: Boolean = false,
    val message: String = "Tailscale request state unknown. Connect or disconnect on demand.",
    val vpnStatus: VpnStatus = VpnStatus.UNKNOWN,
    val endpointStatus: EndpointStatus = EndpointStatus.UNKNOWN,
    val endpointDetail: String? = null,
)

enum class EndpointStatus(val label: String) {
    UNKNOWN("Models endpoint not checked"),
    CHECKING("Checking models endpoint…"),
    REACHABLE("Models endpoint reachable"),
    UNAVAILABLE("Models endpoint unavailable"),
}

class TailscaleUnavailableException(message: String) : IllegalStateException(message)

/** Only this layer grants permission; the Android adapter just dispatches a command. */
class TailscaleAccess(
    private val repository: ModelProfileRepository,
    private val resolver: SelectedReceiptModelProviderResolver,
    private val controller: TailscaleController,
    private val probe: ModelProfileTestService,
    private val scope: CoroutineScope,
    private val attemptTimeoutMillis: Long = 10_000,
    private val retryIntervalMillis: Long = 2_000,
    private val overallTimeoutMillis: Long = 30_000,
    private val vpnStatusSource: VpnStatusSource = UnknownVpnStatusSource,
    private val refreshIntervalMillis: Long = 15_000,
) {
    private val mutableState = MutableStateFlow<TailscaleAccessState?>(null)
    val state = mutableState.asStateFlow()
    private var selected: ModelProfile? = null
    private var operation: Job? = null
    private var vpnObservation: Job? = null
    private var polling: Job? = null
    private var monitoring = false
    private var generation = 0L

    init {
        scope.launch {
            combine(repository.observeProfiles(), repository.observeSelectorState()) { profiles, selector ->
                resolver.resolve(profiles, selector).tailscaleProfile
            }.distinctUntilChanged().collect { profile ->
                if (selected != profile) {
                    generation++
                    operation?.cancel()
                    selected = profile
                    mutableState.value = profile?.let(::TailscaleAccessState)
                    restartMonitoring()
                }
            }
        }
    }

    fun request(connect: Boolean) {
        val requestGeneration = ++generation
        operation?.cancel()
        polling?.cancel()
        operation = scope.launch {
            // Read the repository again: a stale UI callback must not grant permission.
            val profile = resolver.resolve().tailscaleProfile ?: return@launch
            if (selected != profile) return@launch
            mutableState.value = (mutableState.value ?: TailscaleAccessState(profile)).copy(
                connectRequested = connect, busy = true,
                message = if (connect) "Connecting… Requesting Tailscale and checking models endpoint."
                else "Disconnecting… Sending a Tailscale request.",
                endpointStatus = EndpointStatus.UNKNOWN, endpointDetail = null,
            )
            try {
                controller.request(profile, connect)
                if (!connect) {
                    mutableState.value = mutableState.value?.copy(
                        connectRequested = false, busy = false,
                        message = "Disconnection requested. Delivery and Tailscale state are unconfirmed.",
                    )
                    return@launch
                }
                val ready = withTimeoutOrNull(overallTimeoutMillis) {
                    var reachable = false
                    while (!reachable) {
                        if (resolver.resolve().tailscaleProfile != profile) return@withTimeoutOrNull false
                        val result = checkEndpoint(profile)
                        reachable = result == ProviderTestResult.Success
                        if (!reachable) delay(retryIntervalMillis)
                    }
                    true
                } == true
                if (resolver.resolve().tailscaleProfile == profile) {
                    mutableState.value = mutableState.value?.copy(
                        connectRequested = true, busy = false,
                        message = "Connection requested. Delivery and Tailscale state are unconfirmed.",
                        endpointStatus = if (ready) EndpointStatus.REACHABLE else EndpointStatus.UNAVAILABLE,
                        endpointDetail = if (ready) null else "Endpoint unavailable after ${overallTimeoutMillis / 1_000} seconds. Open Tailscale to check login, VPN consent or a competing VPN; check the saved URL and credentials, and that LM Studio allows local network access. Turn off/on to retry.",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (resolver.resolve().tailscaleProfile == profile) {
                    mutableState.value = mutableState.value?.copy(
                        connectRequested = false, busy = false,
                        message = if (error is TailscaleUnavailableException) error.message.orEmpty()
                        else "Tailscale request failed. Open Tailscale to check setup, then try again.",
                    )
                }
            } finally {
                if (generation == requestGeneration) {
                    operation = null
                    restartPolling()
                }
            }
        }
    }

    /** Refresh observations without issuing a command. */
    fun resetStatus() {
        generation++
        operation?.cancel()
        operation = null
        mutableState.value = selected?.let(::TailscaleAccessState)
        restartMonitoring()
    }

    /** Network callbacks and automatic checks exist only while the app is in the foreground. */
    fun setMonitoring(active: Boolean) {
        if (monitoring == active) return
        monitoring = active
        resetStatus()
    }

    private fun restartMonitoring() {
        vpnObservation?.cancel()
        polling?.cancel()
        if (!monitoring || selected == null) return
        vpnObservation = scope.launch {
            vpnStatusSource.observe().collect { status ->
                val previous = mutableState.value?.vpnStatus
                mutableState.value = mutableState.value?.copy(vpnStatus = status)
                if (previous != status || polling == null || polling?.isActive != true) {
                    // A changed VPN invalidates any old reachability observation.
                    mutableState.value = mutableState.value?.copy(endpointStatus = EndpointStatus.UNKNOWN, endpointDetail = null)
                    restartPolling()
                }
            }
        }
    }

    private fun restartPolling() {
        polling?.cancel()
        if (!monitoring || operation?.isActive == true) return
        val profile = selected ?: return
        polling = scope.launch {
            while (monitoring && selected == profile) {
                if (resolver.resolve().tailscaleProfile != profile) return@launch
                checkEndpoint(profile)
                delay(refreshIntervalMillis)
            }
        }
    }

    private suspend fun checkEndpoint(profile: ModelProfile): ProviderTestResult? {
        mutableState.value = mutableState.value?.copy(endpointStatus = EndpointStatus.CHECKING, endpointDetail = null)
        val result = try {
            withTimeoutOrNull(attemptTimeoutMillis) { probe.test(profile, null) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (selected == profile && resolver.resolve().tailscaleProfile == profile) {
            mutableState.value = mutableState.value?.copy(
                endpointStatus = if (result == ProviderTestResult.Success) EndpointStatus.REACHABLE else EndpointStatus.UNAVAILABLE,
                endpointDetail = if (result == ProviderTestResult.Success) null
                    else "Check the saved URL, credentials and model server. Endpoint access does not confirm Tailscale state.",
            )
        }
        return result
    }
}
