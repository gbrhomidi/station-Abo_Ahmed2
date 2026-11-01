package com.aistudio.dieselstationsms.kxmpzq.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SingleFlightLaunchGateTest {
    @Test
    fun concurrentLaunchRequestsProduceExactlyOneClaim() {
        val gate = SingleFlightLaunchGate()
        val workers = 24
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val results = (0 until workers).map {
                pool.submit<Boolean> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    gate.tryAcquire()
                }
            }
            assertTrue("workers did not reach the start barrier", ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) })
            assertFalse(gate.tryAcquire())
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun releasingClaimAllowsARecoveryAttempt() {
        val gate = SingleFlightLaunchGate()
        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
        gate.release()
        assertTrue(gate.tryAcquire())
        gate.release()
        gate.release()
        assertTrue(gate.tryAcquire())
    }
}
