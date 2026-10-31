package com.aistudio.dieselstationsms.kxmpzq.startup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SingleFlightInitializerTest {

    @Test
    fun concurrentCallersShareOneRunAndCachedValue() = runTest {
        val started = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val initializer = SingleFlightInitializer<String>(
            backgroundScope, 3, { 0L }, { false }
        )
        val first = async {
            initializer.get {
                attempts.incrementAndGet()
                started.await()
                "ready"
            }
        }
        val second = async {
            initializer.get {
                attempts.incrementAndGet()
                "duplicate"
            }
        }
        runCurrent()
        assertEquals(1, attempts.get())
        started.complete(Unit)
        assertEquals("ready", first.await())
        assertEquals("ready", second.await())
        assertEquals(1, attempts.get())
    }

    @Test
    fun cancellingOneWaiterDoesNotCancelTheSharedInitialization() = runTest {
        val gate = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val initializer = SingleFlightInitializer<String>(
            backgroundScope, 3, { 0L }, { false }
        )
        val firstWaiter = launch {
            initializer.get {
                attempts.incrementAndGet()
                gate.await()
                "ready"
            }
        }
        runCurrent()
        firstWaiter.cancelAndJoin()

        val survivingWaiter = async {
            initializer.get {
                attempts.incrementAndGet()
                "duplicate"
            }
        }
        runCurrent()
        assertEquals(1, attempts.get())
        gate.complete(Unit)
        assertEquals("ready", survivingWaiter.await())
        assertEquals(1, attempts.get())
    }

    @Test
    fun retriesTransientFailureAndThenSucceeds() = runTest {
        val attempts = AtomicInteger()
        val initializer = SingleFlightInitializer<String>(
            backgroundScope, 3, { 0L }, { it is TemporaryFailure }
        )
        val result = initializer.get {
            if (attempts.incrementAndGet() == 1) throw TemporaryFailure()
            "ready"
        }
        assertEquals("ready", result)
        assertEquals(2, attempts.get())
    }

    @Test
    fun boundsRetriesAtConfiguredMaximum() = runTest {
        val attempts = AtomicInteger()
        val initializer = SingleFlightInitializer<String>(
            backgroundScope, 3, { 0L }, { it is TemporaryFailure }
        )
        var failure: Throwable? = null
        try {
            initializer.get {
                attempts.incrementAndGet()
                throw TemporaryFailure()
            }
        } catch (error: Exception) {
            failure = error
        }
        assertTrue(failure is TemporaryFailure)
        assertEquals(3, attempts.get())
    }

    @Test
    fun permanentFailureDoesNotRetryAndLaterCallCanRecover() = runTest {
        val attempts = AtomicInteger()
        val initializer = SingleFlightInitializer<String>(
            backgroundScope, 3, { 0L }, { it is TemporaryFailure }
        )
        var failure: Throwable? = null
        try {
            initializer.get {
                attempts.incrementAndGet()
                throw IllegalStateException("permanent")
            }
        } catch (error: Exception) {
            failure = error
        }
        assertTrue(failure is IllegalStateException)
        assertEquals(1, attempts.get())
        assertEquals("ready", initializer.get {
            attempts.incrementAndGet()
            "ready"
        })
        assertEquals(2, attempts.get())
    }

    private class TemporaryFailure : Exception()
}
