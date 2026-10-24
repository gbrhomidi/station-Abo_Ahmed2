package com.aistudio.dieselstationsms.kxmpzq.startup.config

interface ConfigurationProvider {
    fun getBootDelayMs(): Long
    fun getMaxRetryAttempts(): Int
    fun getRetryBackoffMs(): Long
    fun getPhaseTimeoutMs(): Long
    fun getPipelineTimeoutMs(): Long
    fun getHealthCheckDelayMs(): Long
    fun getHealthCheckIntervalMs(): Long
    fun getMetricsEnabled(): Boolean
    fun isPipelineEnabled(): Boolean = true
    fun getStartupMode(): String = "BOOT"
}
