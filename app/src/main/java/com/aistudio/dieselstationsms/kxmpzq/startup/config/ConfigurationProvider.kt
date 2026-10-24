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
    fun getParallelExecutionEnabled(): Boolean = false
    fun getContinueOnFailure(): Boolean = true
    fun getMaxParallelPhases(): Int = 2
    fun getDagValidationEnabled(): Boolean = true
    fun getDagAutoRecoveryEnabled(): Boolean = true
    fun getAllowPhaseSkipping(): Boolean = false
    fun getCriticalPhaseFailureStopsPipeline(): Boolean = true
}
