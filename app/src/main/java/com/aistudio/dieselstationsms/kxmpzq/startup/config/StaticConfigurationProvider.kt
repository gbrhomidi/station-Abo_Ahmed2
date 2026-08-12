package com.aistudio.dieselstationsms.kxmpzq.startup.config

/**
 * Static application startup configuration.
 *
 * Centralizes the default configuration values used by the startup
 * initialization pipeline.
 *
 * All values are kept in one place so that startup behavior can be
 * adjusted without scattering hard-coded values across individual
 * startup phases.
 */
object StaticConfigurationProvider : ConfigurationProvider {

    override fun getBootDelayMs(): Long = 10_000L

    override fun getMaxRetryAttempts(): Int = 3

    override fun getRetryBackoffMs(): Long = 5_000L

    override fun getPhaseTimeoutMs(): Long = 30_000L

    override fun getPipelineTimeoutMs(): Long = 120_000L

    override fun getHealthCheckDelayMs(): Long = 30_000L

    override fun getHealthCheckIntervalMs(): Long = 60_000L

    override fun getMetricsEnabled(): Boolean = true
}