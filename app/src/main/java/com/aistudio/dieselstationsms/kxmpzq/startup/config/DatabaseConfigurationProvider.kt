package com.aistudio.dieselstationsms.kxmpzq.startup.config

import android.content.Context
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper

/** Runtime startup configuration read from SQLite with bounded, safe defaults. */
class DatabaseConfigurationProvider(context: Context) : ConfigurationProvider {
    private val database = DatabaseHelper.getInstance(context.applicationContext)
    private fun value(key: String): String = runCatching { database.getSetting(key).trim() }.getOrDefault("")
    private fun int(key: String, default: Int, min: Int, max: Int): Int =
        value(key).toIntOrNull()?.coerceIn(min, max) ?: default
    private fun bool(key: String, default: Boolean): Boolean = when (value(key).lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> default
    }

    override fun getBootDelayMs(): Long = int("bootDelayMs", 5_000, 0, 60_000).toLong()
    override fun getMaxRetryAttempts(): Int = if (getDagAutoRecoveryEnabled()) int("maxRetryAttempts", 3, 1, 20) else 1
    override fun getRetryBackoffMs(): Long = int("retryBackoffMs", 5_000, 0, 300_000).toLong()
    override fun getPhaseTimeoutMs(): Long = int("phaseTimeoutMs", 10_000, 100, 300_000).toLong()
    override fun getPipelineTimeoutMs(): Long = int("pipelineTimeoutMs", 30_000, 1_000, 600_000).toLong()
    override fun getHealthCheckDelayMs(): Long = int("healthCheckDelayMs", 30_000, 0, 300_000).toLong()
    override fun getHealthCheckIntervalMs(): Long = int("healthCheckIntervalMs", 60_000, 1_000, 600_000).toLong()
    override fun getMetricsEnabled(): Boolean = bool("metricsEnabled", true)
    override fun isPipelineEnabled(): Boolean = bool("pipelineEnabled", true)
    override fun getStartupMode(): String = if (value("startupMode").equals("MANUAL", true)) "MANUAL" else "BOOT"
    override fun getParallelExecutionEnabled(): Boolean = bool("parallelExecutionEnabled", false)
    override fun getContinueOnFailure(): Boolean = bool("continueOnFailure", true)
    override fun getMaxParallelPhases(): Int = int("maxParallelPhases", 2, 1, 16)
    override fun getDagValidationEnabled(): Boolean = bool("dagValidationEnabled", true)
    override fun getDagAutoRecoveryEnabled(): Boolean = bool("dagAutoRecoveryEnabled", true)
    override fun getAllowPhaseSkipping(): Boolean = bool("allowPhaseSkipping", false)
    override fun getCriticalPhaseFailureStopsPipeline(): Boolean = bool("criticalPhaseFailureStopsPipeline", true)
}
