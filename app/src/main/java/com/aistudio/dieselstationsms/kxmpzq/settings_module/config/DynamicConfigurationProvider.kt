package com.aistudio.dieselstationsms.kxmpzq.settings.config

import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings
import com.aistudio.dieselstationsms.kxmpzq.settings.repository.SettingsRepository
import kotlinx.coroutines.runBlocking

/**
 * تنفيذ ConfigurationProvider يقرأ من Repository ديناميكياً
 * يُستخدم داخل StartupCompositionRoot وكل مكونات النظام
 */
class DynamicConfigurationProvider(
    private val repository: SettingsRepository
) : ConfigurationProvider {

    override fun getSettings(): ApplicationSettings =
        runBlocking { repository.getSettings() }

    override fun updateSettings(settings: ApplicationSettings) {
        runBlocking { repository.saveSettings(settings) }
    }

    override fun getBootDelayMs() = getSettings().bootDelayMs
    override fun getPipelineTimeoutMs() = getSettings().pipelineTimeoutMs
    override fun getPhaseTimeoutMs() = getSettings().phaseTimeoutMs
    override fun getMaxRetryAttempts() = getSettings().maxRetryAttempts
    override fun getRetryBackoffMs() = getSettings().retryBackoffMs
    override fun getHealthCheckIntervalMs() = getSettings().healthCheckIntervalMs
    override fun getHeartbeatTimeoutMs() = getSettings().heartbeatTimeoutMs
    override fun getMetricsEnabled() = getSettings().metricsEnabled
    override fun isParallelExecutionEnabled() = getSettings().parallelExecutionEnabled
    override fun isLoggingEnabled() = getSettings().loggingEnabled
    override fun isSmsServiceEnabled() = getSettings().smsServiceEnabled
    override fun isHealthCheckEnabled() = getSettings().healthCheckEnabled
    override fun isAutoStartEnabled() = getSettings().autoStartEnabled
    override fun getEventBufferSize() = getSettings().eventBufferSize
    override fun getLogLevel() = getSettings().logLevel
    override fun getMaxHealthFailures() = getSettings().maxHealthFailures
    override fun getSmsProcessingIntervalMs() = getSettings().smsProcessingIntervalMs
    override fun getSmsQueueSize() = getSettings().smsQueueSize
    override fun getBackupIntervalHours() = getSettings().backupIntervalHours
    override fun getAutoLogoutMinutes() = getSettings().autoLogoutMinutes
    override fun getMaxLogSizeMb() = getSettings().maxLogSizeMb
    override fun getCleanupIntervalDays() = getSettings().cleanupIntervalDays
    override fun getMetricsRetentionDays() = getSettings().metricsRetentionDays
    override fun getEventHistoryDays() = getSettings().eventHistoryDays
    override fun getKeepLogsDays() = getSettings().keepLogsDays
    override fun getMaxParallelPhases() = getSettings().maxParallelPhases
    override fun getRetryStrategy() = getSettings().retryStrategy
    override fun getStartupMode() = getSettings().startupMode
}
