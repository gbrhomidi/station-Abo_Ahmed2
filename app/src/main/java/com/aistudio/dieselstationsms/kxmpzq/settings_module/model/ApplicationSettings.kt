package com.aistudio.dieselstationsms.kxmpzq.settings.model

import kotlinx.serialization.Serializable

/**
 * نموذج إعدادات التطبيق المركزي
 * يمثل كل إعدادات النظام في Data Class واحد
 */
@Serializable
data class ApplicationSettings(

    // ───────────────────────────────────────────────
    // الإعدادات العامة
    // ───────────────────────────────────────────────
    val appEnabled: Boolean = true,
    val debugMode: Boolean = false,
    val language: String = "ar",
    val darkMode: Boolean = true,

    // ───────────────────────────────────────────────
    // إعدادات التشغيل التلقائي
    // ───────────────────────────────────────────────
    val autoStartEnabled: Boolean = true,
    val bootDelayMs: Long = 10_000L,
    val startupMode: String = "BOOT",

    // ───────────────────────────────────────────────
    // إعدادات Pipeline
    // ───────────────────────────────────────────────
    val pipelineEnabled: Boolean = true,
    val pipelineTimeoutMs: Long = 120_000L,
    val phaseTimeoutMs: Long = 30_000L,
    val parallelExecutionEnabled: Boolean = false,
    val continueOnFailure: Boolean = false,
    val maxParallelPhases: Int = 3,
    val dagValidationEnabled: Boolean = true,
    val dagAutoRecoveryEnabled: Boolean = true,
    val allowPhaseSkipping: Boolean = true,
    val criticalPhaseFailureStopsPipeline: Boolean = true,

    // ───────────────────────────────────────────────
    // إعدادات SMS Service
    // ───────────────────────────────────────────────
    val smsServiceEnabled: Boolean = true,
    val smsReceiveEnabled: Boolean = true,
    val smsSendEnabled: Boolean = true,
    val smsProcessingIntervalMs: Long = 5_000L,
    val smsQueueEnabled: Boolean = true,
    val smsQueueSize: Int = 500,
    val duplicateMessageProtection: Boolean = true,
    val smsProcessingThreads: Int = 1,
    val smsRetryEnabled: Boolean = true,
    val allowedPhoneNumbers: List<String> = emptyList(),
    val blockedPhoneNumbers: List<String> = emptyList(),

    // ───────────────────────────────────────────────
    // إعدادات Retry
    // ───────────────────────────────────────────────
    val retryEnabled: Boolean = true,
    val maxRetryAttempts: Int = 3,
    val retryBackoffMs: Long = 5_000L,
    val retryStrategy: String = "EXPONENTIAL",

    // ───────────────────────────────────────────────
    // إعدادات Health Monitor
    // ───────────────────────────────────────────────
    val healthCheckEnabled: Boolean = true,
    val healthCheckIntervalMs: Long = 60_000L,
    val heartbeatTimeoutMs: Long = 120_000L,
    val maxHealthFailures: Int = 3,
    val autoRestartService: Boolean = true,
    val notifyOnFailure: Boolean = true,

    // ───────────────────────────────────────────────
    // إعدادات EventBus
    // ───────────────────────────────────────────────
    val eventBusEnabled: Boolean = true,
    val eventHistoryEnabled: Boolean = true,
    val eventBufferSize: Int = 64,
    val persistEvents: Boolean = false,
    val eventHistoryDays: Int = 7,

    // ───────────────────────────────────────────────
    // إعدادات Logging
    // ───────────────────────────────────────────────
    val loggingEnabled: Boolean = true,
    val logLevel: String = "INFO",
    val saveLogsToDatabase: Boolean = true,
    val compressLogs: Boolean = true,
    val exportLogsEnabled: Boolean = true,
    val crashLogEnabled: Boolean = true,
    val keepLogsDays: Int = 30,
    val maxLogSizeMb: Int = 50,
    val autoDeleteOldLogs: Boolean = true,

    // ───────────────────────────────────────────────
    // إعدادات Metrics
    // ───────────────────────────────────────────────
    val metricsEnabled: Boolean = true,
    val metricsStorageEnabled: Boolean = true,
    val collectStartupDuration: Boolean = true,
    val collectFailureStatistics: Boolean = true,
    val metricsRetentionDays: Int = 30,

    // ───────────────────────────────────────────────
    // إعدادات الإشعارات
    // ───────────────────────────────────────────────
    val notificationsEnabled: Boolean = true,
    val startupNotification: Boolean = true,
    val failureNotification: Boolean = true,
    val healthNotification: Boolean = true,

    // ───────────────────────────────────────────────
    // إعدادات الأمان
    // ───────────────────────────────────────────────
    val requireDeviceUnlock: Boolean = true,
    val encryptStorage: Boolean = true,
    val biometricLockEnabled: Boolean = false,
    val hideSensitiveData: Boolean = true,
    val autoLogoutMinutes: Int = 30,
    val preventScreenshots: Boolean = false,
    val secureBackupEnabled: Boolean = true,
    val pinCode: String = "",

    // ───────────────────────────────────────────────
    // إعدادات النسخ الاحتياطي
    // ───────────────────────────────────────────────
    val autoBackupEnabled: Boolean = false,
    val backupIntervalHours: Int = 24,
    val backupLocation: String = "Internal Storage",
    val includeLogsInBackup: Boolean = true,
    val includeDatabaseBackup: Boolean = true,
    val encryptBackup: Boolean = true,
    val automaticBackupOnExit: Boolean = false,

    // ───────────────────────────────────────────────
    // إعدادات الصيانة
    // ───────────────────────────────────────────────
    val autoCleanupEnabled: Boolean = true,
    val cleanupIntervalDays: Int = 30,
    val keepDatabaseBackupCount: Int = 5,
    val autoRepairEnabled: Boolean = true,

    // ───────────────────────────────────────────────
    // إعدادات الأداء
    // ───────────────────────────────────────────────
    val batteryOptimizationEnabled: Boolean = true,
    val networkOptimizationEnabled: Boolean = true,
    val wakeLockEnabled: Boolean = true,

    // ───────────────────────────────────────────────
    // إعدادات المطور
    // ───────────────────────────────────────────────
    val developerMode: Boolean = false,
    val showAdvancedSettings: Boolean = false,

    // ───────────────────────────────────────────────
    // البيانات الوصفية
    // ───────────────────────────────────────────────
    val updatedAt: Long = System.currentTimeMillis()
)
