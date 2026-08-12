package com.aistudio.dieselstationsms.kxmpzq.startup.health

import kotlinx.coroutines.delay

/**
 * Health monitor for SMSService.
 *
 * The monitor uses the service heartbeat as the source of truth for
 * determining whether SMSService is alive and responsive.
 *
 * A heartbeat is considered recent when its age is less than
 * [heartbeatTimeoutMs].
 */
class SmsServiceHealthMonitor(
    private val checkIntervalMs: Long,
    private val heartbeatProvider: HeartbeatProvider,
    private val maxFailures: Int = 3
) : HealthMonitor {

    companion object {
        private const val MIN_CHECK_INTERVAL_MS = 1L
        private const val DEFAULT_HEARTBEAT_MULTIPLIER = 2L
    }

    private val heartbeatTimeoutMs: Long =
        calculateHeartbeatTimeout(checkIntervalMs)

    @Volatile
    private var consecutiveFailures = 0

    init {
        require(checkIntervalMs >= MIN_CHECK_INTERVAL_MS) {
            "checkIntervalMs must be greater than 0"
        }

        require(maxFailures > 0) {
            "maxFailures must be greater than 0"
        }
    }

    /**
     * Performs a health check against the latest service heartbeat.
     *
     * The first check is performed immediately. If the heartbeat is not
     * recent, subsequent checks can be performed by callers according to
     * the configured check interval.
     */
    override suspend fun check(): HealthStatus {
        return when {
            isHeartbeatRecent() -> {
                consecutiveFailures = 0
                HealthStatus.Healthy
            }

            else -> {
                val failures = incrementFailures()

                if (failures >= maxFailures) {
                    HealthStatus.Unhealthy(
                        "No heartbeat after $maxFailures checks"
                    )
                } else {
                    HealthStatus.Unknown(
                        "No heartbeat, attempt $failures/$maxFailures"
                    )
                }
            }
        }
    }

    /**
     * Returns the latest known heartbeat state synchronously.
     *
     * This method does not perform any delay or blocking operation.
     */
    override fun isHealthy(): Boolean {
        return isHeartbeatRecent()
    }

    /**
     * Waits until SMSService reports a recent heartbeat or the supplied
     * timeout is reached.
     *
     * The first health check is performed immediately.
     */
    override suspend fun waitForHealthy(timeoutMs: Long): Boolean {
        if (timeoutMs <= 0L) {
            return isHealthy()
        }

        val startTime = System.currentTimeMillis()

        while (true) {
            if (isHealthy()) {
                consecutiveFailures = 0
                return true
            }

            val elapsed = System.currentTimeMillis() - startTime
            val remaining = timeoutMs - elapsed

            if (remaining <= 0L) {
                return false
            }

            delay(minOf(checkIntervalMs, remaining))
        }
    }

    /**
     * Determines whether the latest heartbeat is recent enough.
     *
     * A timestamp of 0 means that no heartbeat has ever been recorded.
     */
    private fun isHeartbeatRecent(): Boolean {
        val lastHeartbeat = heartbeatProvider.lastHeartbeat()

        if (lastHeartbeat <= 0L) {
            return false
        }

        val now = System.currentTimeMillis()

        // Protect against a clock anomaly where the heartbeat timestamp
        // is slightly ahead of the current system time.
        if (lastHeartbeat > now) {
            return true
        }

        val heartbeatAge = now - lastHeartbeat
        return heartbeatAge < heartbeatTimeoutMs
    }

    /**
     * Atomically increments the consecutive failure counter.
     */
    private fun incrementFailures(): Int {
        return synchronized(this) {
            consecutiveFailures += 1
            consecutiveFailures
        }
    }

    /**
     * Calculates the maximum acceptable heartbeat age.
     *
     * The original implementation used checkIntervalMs * 2.
     * Keep that behavior while protecting against Long overflow.
     */
    private fun calculateHeartbeatTimeout(intervalMs: Long): Long {
        if (intervalMs <= 0L) {
            return MIN_CHECK_INTERVAL_MS
        }

        return if (intervalMs > Long.MAX_VALUE / DEFAULT_HEARTBEAT_MULTIPLIER) {
            Long.MAX_VALUE
        } else {
            intervalMs * DEFAULT_HEARTBEAT_MULTIPLIER
        }
    }
}