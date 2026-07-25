package com.aistudio.dieselstationsms.kxmpzq.settings.config

import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings

/**
 * واجهة موفر الإعدادات الديناميكية
 * يقرأ القيم من Repository بدلاً من الثوابت
 */
interface ConfigurationProvider {
    fun getSettings(): ApplicationSettings
    fun updateSettings(settings: ApplicationSettings)

    fun getBootDelayMs(): Long
    fun getPipelineTimeoutMs(): Long
    fun getPhaseTimeoutMs(): Long
    fun getMaxRetryAttempts(): Int
    fun getRetryBackoffMs(): Long
    fun getHealthCheckIntervalMs(): Long
    fun getHeartbeatTimeoutMs(): Long
    fun getMetricsEnabled(): Boolean
    fun isParallelExecutionEnabled(): Boolean
    fun isLoggingEnabled(): Boolean
    fun isSmsServiceEnabled(): Boolean
    fun isHealthCheckEnabled(): Boolean
    fun isAutoStartEnabled(): Boolean
    fun getEventBufferSize(): Int
    fun getLogLevel(): String
    fun getMaxHealthFailures(): Int
    fun getSmsProcessingIntervalMs(): Long
    fun getSmsQueueSize(): Int
    fun getBackupIntervalHours(): Int
    fun getAutoLogoutMinutes(): Int
    fun getMaxLogSizeMb(): Int
    fun getCleanupIntervalDays(): Int
    fun getMetricsRetentionDays(): Int
    fun getEventHistoryDays(): Int
    fun getKeepLogsDays(): Int
    fun getMaxParallelPhases(): Int
    fun getRetryStrategy(): String
    fun getStartupMode(): String
}
