package com.aistudio.dieselstationsms.kxmpzq.settings.config

import com.aistudio.dieselstationsms.kxmpzq.settings.repository.SettingsRepository

/** مزود إعدادات متوافق مع SettingsModule؛ يحافظ على قيم تشغيل آمنة حتى لا يؤثر فشل التخزين على الإقلاع. */
class DynamicConfigurationProvider(
    private val repository: SettingsRepository
) : ConfigurationProvider {
    override fun getBootDelayMs(): Long = 10_000L
    override fun getMaxRetryAttempts(): Int = 3
    override fun getRetryBackoffMs(): Long = 5_000L
    override fun getPhaseTimeoutMs(): Long = 30_000L
    override fun getPipelineTimeoutMs(): Long = 120_000L
    override fun getHealthCheckDelayMs(): Long = 30_000L
    override fun getHealthCheckIntervalMs(): Long = 60_000L
    override fun getMetricsEnabled(): Boolean = true
}
