package com.aistudio.dieselstationsms.kxmpzq.settings.storage

import android.content.Context
import androidx.core.content.edit
import com.aistudio.dieselstationsms.kxmpzq.settings.model.ApplicationSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * تنفيذ التخزين باستخدام SharedPreferences
 * قابل للاستبدال بـ Room/SQLite بدون تعديل الطبقات العليا
 */
class SharedPreferencesSettingsStorage(
    context: Context
) : SettingsStorage {

    private val prefs = context.getSharedPreferences(
        "application_settings_v2",
        Context.MODE_PRIVATE
    )

    override suspend fun load(): ApplicationSettings =
        withContext(Dispatchers.IO) {
            val json = prefs.getString("settings_json", null)
                ?: return@withContext ApplicationSettings()
            parseJson(json)
        }

    override suspend fun save(settings: ApplicationSettings) {
        withContext(Dispatchers.IO) {
            prefs.edit {
                putString("settings_json", toJson(settings))
                putLong("updated_at", System.currentTimeMillis())
            }
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            prefs.edit { clear() }
        }
    }

    // ── Serialization ─────────────────────────────────────────

    private fun toJson(s: ApplicationSettings): String {
        return JSONObject().apply {
            put("appEnabled", s.appEnabled)
            put("debugMode", s.debugMode)
            put("language", s.language)
            put("darkMode", s.darkMode)
            put("autoStartEnabled", s.autoStartEnabled)
            put("bootDelayMs", s.bootDelayMs)
            put("startupMode", s.startupMode)
            put("pipelineEnabled", s.pipelineEnabled)
            put("pipelineTimeoutMs", s.pipelineTimeoutMs)
            put("phaseTimeoutMs", s.phaseTimeoutMs)
            put("parallelExecutionEnabled", s.parallelExecutionEnabled)
            put("continueOnFailure", s.continueOnFailure)
            put("maxParallelPhases", s.maxParallelPhases)
            put("dagValidationEnabled", s.dagValidationEnabled)
            put("dagAutoRecoveryEnabled", s.dagAutoRecoveryEnabled)
            put("allowPhaseSkipping", s.allowPhaseSkipping)
            put("criticalPhaseFailureStopsPipeline", s.criticalPhaseFailureStopsPipeline)
            put("smsServiceEnabled", s.smsServiceEnabled)
            put("smsReceiveEnabled", s.smsReceiveEnabled)
            put("smsSendEnabled", s.smsSendEnabled)
            put("smsProcessingIntervalMs", s.smsProcessingIntervalMs)
            put("smsQueueEnabled", s.smsQueueEnabled)
            put("smsQueueSize", s.smsQueueSize)
            put("duplicateMessageProtection", s.duplicateMessageProtection)
            put("smsProcessingThreads", s.smsProcessingThreads)
            put("smsRetryEnabled", s.smsRetryEnabled)
            put("allowedPhoneNumbers", JSONArray(s.allowedPhoneNumbers))
            put("blockedPhoneNumbers", JSONArray(s.blockedPhoneNumbers))
            put("retryEnabled", s.retryEnabled)
            put("maxRetryAttempts", s.maxRetryAttempts)
            put("retryBackoffMs", s.retryBackoffMs)
            put("retryStrategy", s.retryStrategy)
            put("healthCheckEnabled", s.healthCheckEnabled)
            put("healthCheckIntervalMs", s.healthCheckIntervalMs)
            put("heartbeatTimeoutMs", s.heartbeatTimeoutMs)
            put("maxHealthFailures", s.maxHealthFailures)
            put("autoRestartService", s.autoRestartService)
            put("notifyOnFailure", s.notifyOnFailure)
            put("eventBusEnabled", s.eventBusEnabled)
            put("eventHistoryEnabled", s.eventHistoryEnabled)
            put("eventBufferSize", s.eventBufferSize)
            put("persistEvents", s.persistEvents)
            put("eventHistoryDays", s.eventHistoryDays)
            put("loggingEnabled", s.loggingEnabled)
            put("logLevel", s.logLevel)
            put("saveLogsToDatabase", s.saveLogsToDatabase)
            put("compressLogs", s.compressLogs)
            put("exportLogsEnabled", s.exportLogsEnabled)
            put("crashLogEnabled", s.crashLogEnabled)
            put("keepLogsDays", s.keepLogsDays)
            put("maxLogSizeMb", s.maxLogSizeMb)
            put("autoDeleteOldLogs", s.autoDeleteOldLogs)
            put("metricsEnabled", s.metricsEnabled)
            put("metricsStorageEnabled", s.metricsStorageEnabled)
            put("collectStartupDuration", s.collectStartupDuration)
            put("collectFailureStatistics", s.collectFailureStatistics)
            put("metricsRetentionDays", s.metricsRetentionDays)
            put("notificationsEnabled", s.notificationsEnabled)
            put("startupNotification", s.startupNotification)
            put("failureNotification", s.failureNotification)
            put("healthNotification", s.healthNotification)
            put("requireDeviceUnlock", s.requireDeviceUnlock)
            put("encryptStorage", s.encryptStorage)
            put("biometricLockEnabled", s.biometricLockEnabled)
            put("hideSensitiveData", s.hideSensitiveData)
            put("autoLogoutMinutes", s.autoLogoutMinutes)
            put("preventScreenshots", s.preventScreenshots)
            put("secureBackupEnabled", s.secureBackupEnabled)
            put("pinCode", s.pinCode)
            put("autoBackupEnabled", s.autoBackupEnabled)
            put("backupIntervalHours", s.backupIntervalHours)
            put("backupLocation", s.backupLocation)
            put("includeLogsInBackup", s.includeLogsInBackup)
            put("includeDatabaseBackup", s.includeDatabaseBackup)
            put("encryptBackup", s.encryptBackup)
            put("automaticBackupOnExit", s.automaticBackupOnExit)
            put("autoCleanupEnabled", s.autoCleanupEnabled)
            put("cleanupIntervalDays", s.cleanupIntervalDays)
            put("keepDatabaseBackupCount", s.keepDatabaseBackupCount)
            put("autoRepairEnabled", s.autoRepairEnabled)
            put("batteryOptimizationEnabled", s.batteryOptimizationEnabled)
            put("networkOptimizationEnabled", s.networkOptimizationEnabled)
            put("wakeLockEnabled", s.wakeLockEnabled)
            put("developerMode", s.developerMode)
            put("showAdvancedSettings", s.showAdvancedSettings)
            put("updatedAt", s.updatedAt)
        }.toString()
    }

    private fun parseJson(json: String): ApplicationSettings {
        val o = JSONObject(json)
        return ApplicationSettings(
            appEnabled = o.optBoolean("appEnabled", true),
            debugMode = o.optBoolean("debugMode", false),
            language = o.optString("language", "ar"),
            darkMode = o.optBoolean("darkMode", true),
            autoStartEnabled = o.optBoolean("autoStartEnabled", true),
            bootDelayMs = o.optLong("bootDelayMs", 10_000L),
            startupMode = o.optString("startupMode", "BOOT"),
            pipelineEnabled = o.optBoolean("pipelineEnabled", true),
            pipelineTimeoutMs = o.optLong("pipelineTimeoutMs", 120_000L),
            phaseTimeoutMs = o.optLong("phaseTimeoutMs", 30_000L),
            parallelExecutionEnabled = o.optBoolean("parallelExecutionEnabled", false),
            continueOnFailure = o.optBoolean("continueOnFailure", false),
            maxParallelPhases = o.optInt("maxParallelPhases", 3),
            dagValidationEnabled = o.optBoolean("dagValidationEnabled", true),
            dagAutoRecoveryEnabled = o.optBoolean("dagAutoRecoveryEnabled", true),
            allowPhaseSkipping = o.optBoolean("allowPhaseSkipping", true),
            criticalPhaseFailureStopsPipeline = o.optBoolean("criticalPhaseFailureStopsPipeline", true),
            smsServiceEnabled = o.optBoolean("smsServiceEnabled", true),
            smsReceiveEnabled = o.optBoolean("smsReceiveEnabled", true),
            smsSendEnabled = o.optBoolean("smsSendEnabled", true),
            smsProcessingIntervalMs = o.optLong("smsProcessingIntervalMs", 5_000L),
            smsQueueEnabled = o.optBoolean("smsQueueEnabled", true),
            smsQueueSize = o.optInt("smsQueueSize", 500),
            duplicateMessageProtection = o.optBoolean("duplicateMessageProtection", true),
            smsProcessingThreads = o.optInt("smsProcessingThreads", 1),
            smsRetryEnabled = o.optBoolean("smsRetryEnabled", true),
            allowedPhoneNumbers = parseStringArray(o, "allowedPhoneNumbers"),
            blockedPhoneNumbers = parseStringArray(o, "blockedPhoneNumbers"),
            retryEnabled = o.optBoolean("retryEnabled", true),
            maxRetryAttempts = o.optInt("maxRetryAttempts", 3),
            retryBackoffMs = o.optLong("retryBackoffMs", 5_000L),
            retryStrategy = o.optString("retryStrategy", "EXPONENTIAL"),
            healthCheckEnabled = o.optBoolean("healthCheckEnabled", true),
            healthCheckIntervalMs = o.optLong("healthCheckIntervalMs", 60_000L),
            heartbeatTimeoutMs = o.optLong("heartbeatTimeoutMs", 120_000L),
            maxHealthFailures = o.optInt("maxHealthFailures", 3),
            autoRestartService = o.optBoolean("autoRestartService", true),
            notifyOnFailure = o.optBoolean("notifyOnFailure", true),
            eventBusEnabled = o.optBoolean("eventBusEnabled", true),
            eventHistoryEnabled = o.optBoolean("eventHistoryEnabled", true),
            eventBufferSize = o.optInt("eventBufferSize", 64),
            persistEvents = o.optBoolean("persistEvents", false),
            eventHistoryDays = o.optInt("eventHistoryDays", 7),
            loggingEnabled = o.optBoolean("loggingEnabled", true),
            logLevel = o.optString("logLevel", "INFO"),
            saveLogsToDatabase = o.optBoolean("saveLogsToDatabase", true),
            compressLogs = o.optBoolean("compressLogs", true),
            exportLogsEnabled = o.optBoolean("exportLogsEnabled", true),
            crashLogEnabled = o.optBoolean("crashLogEnabled", true),
            keepLogsDays = o.optInt("keepLogsDays", 30),
            maxLogSizeMb = o.optInt("maxLogSizeMb", 50),
            autoDeleteOldLogs = o.optBoolean("autoDeleteOldLogs", true),
            metricsEnabled = o.optBoolean("metricsEnabled", true),
            metricsStorageEnabled = o.optBoolean("metricsStorageEnabled", true),
            collectStartupDuration = o.optBoolean("collectStartupDuration", true),
            collectFailureStatistics = o.optBoolean("collectFailureStatistics", true),
            metricsRetentionDays = o.optInt("metricsRetentionDays", 30),
            notificationsEnabled = o.optBoolean("notificationsEnabled", true),
            startupNotification = o.optBoolean("startupNotification", true),
            failureNotification = o.optBoolean("failureNotification", true),
            healthNotification = o.optBoolean("healthNotification", true),
            requireDeviceUnlock = o.optBoolean("requireDeviceUnlock", true),
            encryptStorage = o.optBoolean("encryptStorage", true),
            biometricLockEnabled = o.optBoolean("biometricLockEnabled", false),
            hideSensitiveData = o.optBoolean("hideSensitiveData", true),
            autoLogoutMinutes = o.optInt("autoLogoutMinutes", 30),
            preventScreenshots = o.optBoolean("preventScreenshots", false),
            secureBackupEnabled = o.optBoolean("secureBackupEnabled", true),
            pinCode = o.optString("pinCode", ""),
            autoBackupEnabled = o.optBoolean("autoBackupEnabled", false),
            backupIntervalHours = o.optInt("backupIntervalHours", 24),
            backupLocation = o.optString("backupLocation", "Internal Storage"),
            includeLogsInBackup = o.optBoolean("includeLogsInBackup", true),
            includeDatabaseBackup = o.optBoolean("includeDatabaseBackup", true),
            encryptBackup = o.optBoolean("encryptBackup", true),
            automaticBackupOnExit = o.optBoolean("automaticBackupOnExit", false),
            autoCleanupEnabled = o.optBoolean("autoCleanupEnabled", true),
            cleanupIntervalDays = o.optInt("cleanupIntervalDays", 30),
            keepDatabaseBackupCount = o.optInt("keepDatabaseBackupCount", 5),
            autoRepairEnabled = o.optBoolean("autoRepairEnabled", true),
            batteryOptimizationEnabled = o.optBoolean("batteryOptimizationEnabled", true),
            networkOptimizationEnabled = o.optBoolean("networkOptimizationEnabled", true),
            wakeLockEnabled = o.optBoolean("wakeLockEnabled", true),
            developerMode = o.optBoolean("developerMode", false),
            showAdvancedSettings = o.optBoolean("showAdvancedSettings", false),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
        )
    }

    private fun parseStringArray(o: JSONObject, key: String): List<String> {
        return try {
            val arr = o.getJSONArray(key)
            List(arr.length()) { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
