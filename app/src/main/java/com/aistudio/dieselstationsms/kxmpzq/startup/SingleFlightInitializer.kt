package com.aistudio.dieselstationsms.kxmpzq.startup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class SingleFlightInitializer<T : Any>(
    private val scope: CoroutineScope,
    private val maxAttempts: Int,
    private val retryDelayMs: (Int) -> Long,
    private val isRetryable: (Throwable) -> Boolean
) {
    init { require(maxAttempts >= 1) }

    private val lock = Any()
    private var initializedValue: T? = null
    private var inFlight: CompletableDeferred<T>? = null
    private var inFlightJob: Job? = null
    private var generation: Long = 0L

    /**
     * Test reset is a lifecycle boundary: cancel the producer as well as its shared result.
     * A producer that is already inside non-cooperative SQLite work must not publish a stale
     * helper into the next test's generation.
     */
    fun resetForTests() {
        synchronized(lock) {
            generation++
            inFlightJob?.cancel()
            inFlightJob = null
            inFlight?.cancel()
            inFlight = null
            initializedValue = null
        }
    }

    suspend fun get(initializer: suspend () -> T): T {
        val shared = synchronized(lock) {
            initializedValue?.let { return it }
            inFlight ?: run {
                val created = CompletableDeferred<T>()
                val producerGeneration = generation
                inFlight = created
                created.invokeOnCompletion {
                    synchronized(lock) {
                        if (inFlight === created) {
                            inFlight = null
                            inFlightJob = null
                        }
                    }
                }
                val job = scope.launch {
                    try {
                        val value = initializeWithRetry(initializer)
                        val mayPublish = synchronized(lock) {
                            generation == producerGeneration && inFlight === created
                        }
                        if (mayPublish) created.complete(value) else created.cancel()
                    } catch (cancelled: CancellationException) {
                        created.cancel(cancelled)
                    } catch (failure: Exception) {
                        created.completeExceptionally(failure)
                    }
                }
                inFlightJob = job
                job.invokeOnCompletion { cause ->
                    if (cause != null && !created.isCompleted) {
                        if (cause is CancellationException) created.cancel(cause)
                        else created.completeExceptionally(cause)
                    }
                }
                created
            }
        }
        return shared.await()
    }

    private suspend fun initializeWithRetry(initializer: suspend () -> T): T {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return initializer()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (attempt >= maxAttempts || !isRetryable(failure)) throw failure
                val pause = retryDelayMs(attempt).coerceAtLeast(0L)
                if (pause > 0L) delay(pause)
            }
        }
    }
}
