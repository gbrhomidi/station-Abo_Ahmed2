package com.aistudio.dieselstationsms.kxmpzq.startup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One in-flight initialization is shared across callers.
 * Caller cancellation never owns or cancels the shared initialization job.
 */
internal class SingleFlightInitializer<T : Any>(
    private val scope: CoroutineScope,
    private val maxAttempts: Int,
    private val retryDelayMs: (Int) -> Long,
    private val isRetryable: (Throwable) -> Boolean
) {
    init {
        require(maxAttempts >= 1)
    }

    private val lock = Any()
    private var initializedValue: T? = null
    private var inFlight: CompletableDeferred<T>? = null

    suspend fun get(initializer: suspend () -> T): T {
        val shared = synchronized(lock) {
            initializedValue?.let { return it }

            inFlight ?: run {
                val created = CompletableDeferred<T>()
                inFlight = created
                created.invokeOnCompletion {
                    synchronized(lock) {
                        if (inFlight === created) {
                            inFlight = null
                        }
                    }
                }

                // Transfer failures to the shared Deferred. An exception must not
                // escape as an unhandled failure in the process-scoped coroutine.
                val job = scope.launch {
                    try {
                        created.complete(initializeWithRetry(initializer))
                    } catch (cancelled: CancellationException) {
                        created.cancel(cancelled)
                    } catch (failure: Exception) {
                        created.completeExceptionally(failure)
                    }
                }
                job.invokeOnCompletion { cause ->
                    if (cause != null && !created.isCompleted) {
                        created.cancel(cause)
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
                val value = initializer()
                synchronized(lock) {
                    initializedValue = value
                }
                return value
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (attempt >= maxAttempts || !isRetryable(failure)) {
                    throw failure
                }
                val pause = retryDelayMs(attempt).coerceAtLeast(0L)
                if (pause > 0L) delay(pause)
            }
        }
    }
}