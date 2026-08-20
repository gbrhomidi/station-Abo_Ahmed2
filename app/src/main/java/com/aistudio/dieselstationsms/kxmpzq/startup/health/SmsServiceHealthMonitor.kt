package com.aistudio.dieselstationsms.kxmpzq.startup.health

import kotlinx.coroutines.delay

class SmsServiceHealthMonitor(
    private val checkIntervalMs: Long,
    private val heartbeatProvider: HeartbeatProvider,
    private val maxFailures: Int = 3
) : HealthMonitor {

    private var consecutiveFailures = 0

    override suspend fun check(): HealthStatus {
        delay(checkIntervalMs)
        return when {
            !isHeartbeatRecent() -> {
                consecutiveFailures++
                if (consecutiveFailures >= maxFailures) {
                    HealthStatus.Unhealthy("No heartbeat after $maxFailures checks")
                } else {
                    HealthStatus.Unknown("No heartbeat, attempt $consecutiveFailures/$maxFailures")
                }
            }
            else -> {
                consecutiveFailures = 0
                HealthStatus.Healthy
            }
        }
    }

    override fun isHealthy(): Boolean = isHeartbeatRecent()

    override suspend fun waitForHealthy(timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (isHealthy()) return true
            delay(checkIntervalMs)
        }
        return false
    }

    private fun isHeartbeatRecent(): Boolean {
        return System.currentTimeMillis() - heartbeatProvider.lastHeartbeat() < checkIntervalMs * 2
    }
}
