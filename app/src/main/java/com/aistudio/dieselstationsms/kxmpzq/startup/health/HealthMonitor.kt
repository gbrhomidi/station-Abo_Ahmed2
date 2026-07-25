package com.aistudio.dieselstationsms.kxmpzq.startup.health

interface HealthMonitor {
    suspend fun check(): HealthStatus
    fun isHealthy(): Boolean
    suspend fun waitForHealthy(timeoutMs: Long): Boolean
}

sealed class HealthStatus {
    object Healthy : HealthStatus()
    data class Unhealthy(val reason: String) : HealthStatus()
    data class Unknown(val reason: String) : HealthStatus()
}
