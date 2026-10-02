package com.hugo.smartexpense.app.tailscale

import com.hugo.smartexpense.app.modelprofile.domain.SelectedReceiptModelProviderResolver
import com.hugo.smartexpense.app.modelprofile.migration.FakeRepository
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfileTestService
import com.hugo.smartexpense.app.modelprofile.ui.ModelProfilesViewModel
import com.hugo.smartexpense.extraction.*
import com.hugo.smartexpense.app.connectivity.VpnStatus
import com.hugo.smartexpense.app.connectivity.VpnStatusSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.junit.Before
import org.junit.After

@OptIn(ExperimentalCoroutinesApi::class)
class TailscaleAccessTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun teardown() = Dispatchers.resetMain()

    @Test fun viewModelRejectsLocalDisabledMissingAndDisallowedActionsWithoutUiSubscription() = runTest(dispatcher) {
        val repository = FakeRepository()
        val profile = profile()
        repository.profiles.value = listOf(profile)
        val requests = mutableListOf<Pair<ModelProfile, Boolean>>()
        val vm = vm(repository, requests)
        for ((index, selection) in listOf(
            ModelProfileSelectorState(null, true), ModelProfileSelectorState(profile.id, false),
            ModelProfileSelectorState("missing", true), ModelProfileSelectorState(profile.id, true),
        ).withIndex()) {
            repository.selector.value = selection
            repository.profiles.value = listOf(profile.copy(showTailscaleToggle = index < 3))
            advanceUntilIdle()
            vm.requestTailscale(true)
            advanceUntilIdle()
            assertTrue(requests.isEmpty())
            vm.requestTailscale(false)
            advanceUntilIdle()
            assertTrue(requests.isEmpty())
        }
    }

    @Test fun viewModelUsesOnlyCurrentSavedSnapshotAndSavedCredentialsAndNeverAutoDisconnects() = runTest(dispatcher) {
        val repository = eligibleRepository()
        val requests = mutableListOf<Pair<ModelProfile, Boolean>>()
        var tested: ModelProfile? = null
        val vm = vm(repository, requests, ModelProfileTestService { snapshot, override ->
            assertNull(override)
            tested = snapshot
            ProviderTestResult.Success
        })
        vm.addProfile()
        vm.updateDraft(ModelProfileDraft(baseUrl = "https://unsaved.invalid/v1", showTailscaleToggle = true))
        advanceUntilIdle()
        vm.requestTailscale(true)
        advanceUntilIdle()
        assertEquals(repository.profiles.value.single(), tested)
        vm.requestTailscale(false)
        advanceUntilIdle()
        assertEquals(listOf(true, false), requests.map { it.second })
        repository.selectProfile(null)
        advanceUntilIdle()
        vm.requestTailscale(false)
        advanceUntilIdle()
        assertEquals(2, requests.size)
    }

    @Test fun staleCallbackAfterPermissionRevokedCannotDispatch() = runTest {
        val repository = eligibleRepository()
        val requests = mutableListOf<Boolean>()
        val access = access(repository, backgroundScope, TailscaleController { _, on -> requests += on })
        runCurrent()
        repository.profiles.value = listOf(profile().copy(showTailscaleToggle = false))
        access.request(true)
        runCurrent()
        assertTrue(requests.isEmpty())
        assertNull(access.state.value)
    }

    @Test fun retriesUntilReadinessAndDisconnectReportsOnlyRequest() = runTest {
        val repository = eligibleRepository()
        var attempts = 0
        val requests = mutableListOf<Boolean>()
        val access = access(repository, backgroundScope, TailscaleController { _, on -> requests += on },
            ModelProfileTestService { _, _ -> if (++attempts == 3) ProviderTestResult.Success else ProviderTestResult.Failed("unavailable") })
        runCurrent()
        access.request(true)
        runCurrent()
        assertTrue(access.state.value!!.busy)
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(3, attempts)
        assertEquals(EndpointStatus.REACHABLE, access.state.value!!.endpointStatus)
        assertContains(access.state.value!!.message, "Tailscale state are unconfirmed")
        access.request(false)
        runCurrent()
        assertEquals(listOf(true, false), requests)
        assertEquals(3, attempts)
        assertContains(access.state.value!!.message, "Disconnection requested")
    }

    @Test fun perAttemptAndOverallTimeoutBoundAHangingProbe() = runTest {
        val repository = eligibleRepository()
        var attempts = 0
        var cancellations = 0
        val access = access(repository, backgroundScope, probe = ModelProfileTestService { _, _ ->
            attempts++
            try { awaitCancellation() } finally { cancellations++ }
        })
        runCurrent()
        access.request(true)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(3, attempts)
        assertEquals(3, cancellations)
        assertFalse(access.state.value!!.busy)
        assertEquals(EndpointStatus.UNAVAILABLE, access.state.value!!.endpointStatus)
        assertContains(access.state.value!!.endpointDetail.orEmpty(), "Endpoint unavailable")
    }

    @Test fun profileChangeOrResumeCancelsStaleProbeWithoutSendingDisconnect() = runTest {
        val repository = eligibleRepository()
        var cancellations = 0
        val requests = mutableListOf<Boolean>()
        val access = access(repository, backgroundScope, TailscaleController { _, on -> requests += on },
            ModelProfileTestService { _, _ -> try { awaitCancellation() } finally { cancellations++ } })
        runCurrent()
        access.request(true)
        runCurrent()
        val replacement = profile().copy(id = "second", baseUrl = "https://second.test/v1")
        repository.profiles.value = listOf(replacement)
        repository.selectProfile(replacement.id)
        runCurrent()
        assertEquals(replacement, access.state.value!!.profile)
        assertContains(access.state.value!!.message, "unknown")
        assertEquals(1, cancellations)
        access.request(true)
        runCurrent()
        access.resetStatus()
        runCurrent()
        assertEquals(2, cancellations)
        assertEquals(listOf(true, true), requests)
        assertFalse(access.state.value!!.connectRequested)
    }

    @Test fun missingAppAndDispatchFailureAreActionableWithoutProbing() = runTest {
        for (error in listOf(TailscaleUnavailableException("Install Tailscale"), SecurityException("restricted"))) {
            val access = access(eligibleRepository(), backgroundScope,
                TailscaleController { _, _ -> throw error }, ModelProfileTestService { _, _ -> fail("probe must not run") })
            runCurrent()
            access.request(true)
            runCurrent()
            assertFalse(access.state.value!!.busy)
            assertFalse(access.state.value!!.connectRequested)
            assertTrue(access.state.value!!.message.isNotBlank())
        }
    }

    @Test fun foregroundChecksWithoutCommandsAndTracksExternalVpnAndEndpointChanges() = runTest {
        val repository = eligibleRepository()
        val vpn = MutableStateFlow(VpnStatus.NOT_DETECTED)
        var reachable = true
        var attempts = 0
        val access = TailscaleAccess(repository, SelectedReceiptModelProviderResolver(repository),
            TailscaleController { _, _ -> fail("Observation must never send commands") },
            ModelProfileTestService { _, override ->
                assertNull(override)
                attempts++
                if (reachable) ProviderTestResult.Success else ProviderTestResult.Failed("offline")
            }, backgroundScope, vpnStatusSource = VpnStatusSource { vpn })
        runCurrent()
        access.setMonitoring(true)
        runCurrent()
        assertEquals(VpnStatus.NOT_DETECTED, access.state.value!!.vpnStatus)
        assertEquals(EndpointStatus.REACHABLE, access.state.value!!.endpointStatus)
        // A reachable LAN endpoint must not turn the command switch on.
        assertFalse(access.state.value!!.connectRequested)
        reachable = false
        vpn.value = VpnStatus.DETECTED
        runCurrent()
        assertEquals(VpnStatus.DETECTED, access.state.value!!.vpnStatus)
        assertEquals(EndpointStatus.UNAVAILABLE, access.state.value!!.endpointStatus)
        reachable = true
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(EndpointStatus.REACHABLE, access.state.value!!.endpointStatus)
        access.setMonitoring(false)
        runCurrent()
        val pausedAttempts = attempts
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(pausedAttempts, attempts)
        assertEquals(VpnStatus.UNKNOWN, access.state.value!!.vpnStatus)
        vpn.value = VpnStatus.NOT_DETECTED
        reachable = false
        access.setMonitoring(true)
        runCurrent()
        assertEquals(VpnStatus.NOT_DETECTED, access.state.value!!.vpnStatus)
        assertEquals(EndpointStatus.UNAVAILABLE, access.state.value!!.endpointStatus)
    }

    @Test fun monitoringCancelsOldProfileChecksAndNeverChecksDisallowedProfiles() = runTest {
        val repository = eligibleRepository()
        val tested = mutableListOf<String>()
        var cancellations = 0
        val access = TailscaleAccess(repository, SelectedReceiptModelProviderResolver(repository),
            TailscaleController { _, _ -> fail("No commands") },
            ModelProfileTestService { profile, _ ->
                tested += profile.id
                try { awaitCancellation() } finally { cancellations++ }
            }, backgroundScope)
        runCurrent()
        access.setMonitoring(true)
        runCurrent()
        val second = profile().copy(id = "second")
        repository.profiles.value = listOf(second)
        repository.selector.value = ModelProfileSelectorState(second.id, true)
        runCurrent()
        assertEquals(listOf("profile", "second"), tested)
        assertEquals(1, cancellations)
        assertEquals(second, access.state.value!!.profile)
        repository.profiles.value = listOf(second.copy(showTailscaleToggle = false))
        runCurrent()
        assertEquals(2, cancellations)
        assertNull(access.state.value)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, tested.size)
    }

    @Test fun pausingAbortsHangingCheckAndResumingRetriesCurrentProfile() = runTest {
        val repository = eligibleRepository()
        var cancellations = 0
        val access = access(repository, backgroundScope, probe = ModelProfileTestService { _, _ ->
            try { awaitCancellation() } finally { cancellations++ }
        })
        runCurrent()
        access.setMonitoring(true)
        runCurrent()
        assertEquals(EndpointStatus.CHECKING, access.state.value!!.endpointStatus)
        access.setMonitoring(false)
        runCurrent()
        assertEquals(1, cancellations)
        assertEquals(EndpointStatus.UNKNOWN, access.state.value!!.endpointStatus)
        access.setMonitoring(true)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(EndpointStatus.UNAVAILABLE, access.state.value!!.endpointStatus)
        assertEquals(2, cancellations)
    }

    private fun access(repository: FakeRepository, scope: CoroutineScope,
        controller: TailscaleController = TailscaleController { _, _ -> },
        probe: ModelProfileTestService = ModelProfileTestService { _, _ -> ProviderTestResult.Success },
    ) = TailscaleAccess(repository, SelectedReceiptModelProviderResolver(repository), controller, probe, scope)

    private fun vm(repository: FakeRepository, requests: MutableList<Pair<ModelProfile, Boolean>>,
        probe: ModelProfileTestService = ModelProfileTestService { _, _ -> ProviderTestResult.Success },
    ) = ModelProfilesViewModel(repository, object : WritableApiKeyStore {
        override fun get(alias: String): String? = null
        override fun put(alias: String, value: String) {}
        override fun remove(alias: String) {}
    }, SelectedReceiptModelProviderResolver(repository), probe,
        tailscaleController = TailscaleController { profile, on -> requests += profile to on })

    private fun eligibleRepository() = FakeRepository().apply {
        profiles.value = listOf(profile())
        selector.value = ModelProfileSelectorState("profile", true)
    }
    private fun profile() = ModelProfile("profile", "Tailnet", "https://example.test/v1", "model",
        RemoteInputMode.DIRECT_IMAGE, RemoteStructuredOutputFormat.JSON_SCHEMA, "saved-alias", 1, 1, true)
}
