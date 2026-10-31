package com.aistudio.dieselstationsms.kxmpzq.startup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

internal class SingleFlightInitializer<T : Any>(
    private val scope: CoroutineScope,
    private val maxAttempts: Int,
    private val retryDelayMs: (Int) -> Long,
    private val isRetryable: (Throwable) -> Boolean
) {
    init { require(maxAttempts >= 1) }

    private val lock = Any()
    private var initializedValue: T? = null
    private var inFlight: Deferred<T>? = null

    suspend fun get(initializer: suspend () -> T): T {
        val shared = synchronized(lock) {
            initializedValue?.let { return it }
            inFlight ?: run {
                val created = scope.async(start = CoroutineStart.LAZY) {
                    initializeWithRetry(initializer)
                }
                inFlight = created
                created.invokeOnCompletion {
                    synchronized(lock) {
                        if (inFlight === created) inFlight = null
                    }
                }
                created.start()
                created
            }
        }
        return shared.await()
    }

    private suspend fun initializeWithRetry(initializer: suspend () -> T): T {
        var attempt = 0
        while (true) {
            attempt += 1
            try {
                val value = initializer()
                synchronized(lock) { initializedValue = value }
                return value
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