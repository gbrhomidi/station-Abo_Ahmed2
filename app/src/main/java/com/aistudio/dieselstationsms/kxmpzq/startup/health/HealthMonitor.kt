package com.aistudio.dieselstationsms.kxmpzq.startup.health

/**
 * Abstraction for monitoring the health of the SMS service
 * during and after application startup.
 *
 * Implementations are responsible for determining the current
 * service health and for optionally waiting until the service
 * reaches a healthy state.
 */
interface HealthMonitor {

    /**
     * Performs an asynchronous health check.
     */
    suspend fun check(): HealthStatus

    /**
     * Returns the latest known health state.
     *
     * This method must not perform a blocking health check.
     */
    fun isHealthy(): Boolean

    /**
     * Waits until the service becomes healthy or the specified
     * timeout is reached.
     *
     * @return true when a healthy state is reached, false otherwise.
     */
    suspend fun waitForHealthy(timeoutMs: Long): Boolean
}

/**
 * Result of a startup/service health check.
 */
sealed class HealthStatus {

    /**
     * The monitored service is healthy and operational.
     */
    object Healthy : HealthStatus()

    /**
     * The monitored service is known to be unhealthy.
     */
    data class Unhealthy(
        val reason: String
    ) : HealthStatus()

    /**
     * The health state could not be determined reliably.
     */
    data class Unknown(
        val reason: String
    ) : HealthStatus()
}