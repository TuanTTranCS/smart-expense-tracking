package com.hugo.smartexpense.extraction

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext

data class BatchProcessingPolicy(
    val maxConcurrency: Int = 1,
    val startSpacingMillis: Long = 3_500,
    val itemDeadlineMillis: Long = 180_000,
    val maxAttempts: Int = 3,
) {
    init {
        require(maxConcurrency in 1..3)
        require(startSpacingMillis >= 0 && itemDeadlineMillis > 0 && maxAttempts in 1..3)
    }
    fun effective(onDevice: Boolean) = if (onDevice) copy(maxConcurrency = 1, startSpacingMillis = 0) else this
}

interface BatchClock {
    fun nowMillis(): Long
    suspend fun sleep(millis: Long)
}

object MonotonicBatchClock : BatchClock {
    override fun nowMillis() = System.nanoTime() / 1_000_000
    override suspend fun sleep(millis: Long) { if (millis > 0) delay(millis) }
}

/** One instance is shared by batch, retry and verification for a provider. */
class ProviderRequestGate(private val clock: BatchClock = MonotonicBatchClock) {
    private val mutex = Mutex()
    private var nextStart = Long.MIN_VALUE
    private var cooldownUntil = Long.MIN_VALUE
    suspend fun awaitStart(spacingMillis: Long) {
        while (true) {
            coroutineContext.ensureActive()
            val wait = mutex.withLock {
                val target = maxOf(nextStart, cooldownUntil)
                val now = clock.nowMillis()
                if (target <= now) {
                    nextStart = now + spacingMillis
                    0L
                } else target - now
            }
            if (wait == 0L) return
            // Release the mutex while waiting so an in-flight 429 can extend the shared cooldown.
            clock.sleep(wait)
        }
    }
    suspend fun cooldown(millis: Long) = mutex.withLock {
        cooldownUntil = maxOf(cooldownUntil, clock.nowMillis() + millis.coerceAtLeast(0))
    }
}

/** Context follows IO dispatcher switches; blocking adapters can disconnect their active socket. */
object InferenceExecution {
    data class Scope(val job: Job, val gate: ProviderRequestGate, val spacingMillis: Long,
        val credentials: OperationCredentialSnapshot = OperationCredentialSnapshot())
    val current = ThreadLocal<Scope?>()
    val sharedGate = ProviderRequestGate()
    private val outboundSlots = java.util.concurrent.Semaphore(3, true)
    fun <R> request(verification: Boolean = false, operation: () -> R): R {
        val permits = if (verification) 3 else 1
        // A verification takes the complete budget, so it cannot overlap a batch inference.
        while (!outboundSlots.tryAcquire(permits, 50, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            current.get()?.job?.ensureActive()
        }
        try {
            if (current.get() != null) beforeRequest()
            else runBlocking { sharedGate.awaitStart(if (verification) 3_500 else 0) }
            return operation()
        } finally { outboundSlots.release(permits) }
    }
    fun beforeRequest() {
        val scope = current.get() ?: return
        scope.job.ensureActive()
        runBlocking(scope.job) { scope.gate.awaitStart(scope.spacingMillis) }
        scope.job.ensureActive()
    }
}

/** Secrets live only in an active operation scope, never in UI state or persisted batch snapshots. */
class OperationCredentialSnapshot {
    private val values = mutableMapOf<String, String?>()
    @Synchronized fun get(alias: String, store: ApiKeyStore): String? {
        if (!values.containsKey(alias)) values[alias] = store.get(alias)
        return values[alias]
    }
    override fun toString() = "OperationCredentialSnapshot([REDACTED])"
}

class BatchRequestScheduler(
    private val clock: BatchClock = MonotonicBatchClock,
    val gate: ProviderRequestGate = if (clock === MonotonicBatchClock) InferenceExecution.sharedGate else ProviderRequestGate(clock),
    private val jitterMillis: (Int) -> Long = { kotlin.random.Random.nextLong(0, 501) },
) {
    suspend fun <T, R> run(
        items: List<T>,
        policy: BatchProcessingPolicy = BatchProcessingPolicy(),
        onDevice: Boolean = false,
        operation: suspend (T) -> R,
        onResult: suspend (T, Result<R>) -> Unit,
    ) = supervisorScope {
        val effective = policy.effective(onDevice)
        val slots = Semaphore(effective.maxConcurrency)
        val credentials = OperationCredentialSnapshot()
        items.map { item -> launch {
            slots.withPermit {
                val result = try {
                    Result.success(withTimeout(effective.itemDeadlineMillis) {
                        val scope = InferenceExecution.Scope(coroutineContext.job, gate, effective.startSpacingMillis, credentials)
                        withContext(InferenceExecution.current.asContextElement(scope)) {
                            var attempt = 0
                            while (true) {
                                ensureActive()
                                try {
                                    // Adapters gate every actual outbound inference, including recovery.
                                    return@withContext operation(item)
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) {
                                    attempt++
                                    if (attempt >= effective.maxAttempts || !failure.retryable()) throw failure
                                    val wait = (failure as? RemoteProviderException.RateLimited)?.retryAfterMillis
                                        ?: ((4_000L shl (attempt - 1)) + jitterMillis(attempt))
                                    gate.cooldown(wait)
                                    clock.sleep(wait)
                                }
                            }
                            @Suppress("UNREACHABLE_CODE") error("Unreachable")
                        }
                    })
                } catch (timeout: TimeoutCancellationException) {
                    Result.failure(IllegalStateException("Expense extraction timed out.", timeout))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { Result.failure(failure) }
                coroutineContext.ensureActive()
                onResult(item, result)
            }
        } }.joinAll()
    }

    private fun Exception.retryable() = this is RemoteProviderException.RateLimited ||
        this is RemoteProviderException.NetworkUnavailable ||
        (this is RemoteProviderException.UnexpectedResponse && httpStatusCode in 500..599)
}
