package com.aistudio.dieselstationsms.kxmpzq.settings.backup

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.settings.security.SecuritySettingsStore
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Periodic and app-exit work uses the same verified full SQLite snapshot path. */
class FullDatabaseBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val database = DatabaseHelper.getInstance(applicationContext)
            if (!isEnabled(database.getSetting("includeDatabaseBackup"), true)) {
                return Result.success(Data.Builder().putBoolean("skipped", true).build())
            }
            val folderUri = FullDatabaseBackupManager.selectedTreeUri(applicationContext)
                ?: return Result.failure(Data.Builder().putString("error", "لم يتم تحديد مجلد النسخ الاحتياطي").build())
            val secureSettings = SecuritySettingsStore.open(applicationContext)
            val secureBackupRequired = if (secureSettings.encryptionEnabled()) {
                isEnabled(secureSettings.getSetting("secureBackupEnabled"), true)
            } else {
                isEnabled(database.getSetting("secureBackupEnabled"), true)
            }
            val encrypted = secureBackupRequired || isEnabled(database.getSetting("encryptBackup"), true)
            val keepCount = database.getSetting("keepDatabaseBackupCount").toIntOrNull()?.coerceIn(1, 100) ?: 10
            val result = FullDatabaseBackupManager(applicationContext, database)
                .createBackup(folderUri, encrypted, keepCount)
            if (result.optBoolean("success", false)) {
                Result.success(
                    Data.Builder()
                        .putString("backup_uri", result.optString("uri"))
                        .putString("file_name", result.optString("file_name"))
                        .putLong("size", result.optLong("size"))
                        .build()
                )
            } else Result.retry()
        } catch (error: Exception) {
            Log.e("FullDatabaseBackupWorker", "Full database backup failed", error)
            Result.retry()
        }
    }

    private fun isEnabled(value: String?, defaultValue: Boolean): Boolean = when (value?.trim()?.lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> defaultValue
    }
}

/** Applies the saved backup settings to a real WorkManager schedule. */
object FullDatabaseBackupScheduler {
    const val PERIODIC_WORK_NAME = "station_full_database_backup_periodic"
    private const val EXIT_WORK_NAME = "station_full_database_backup_on_exit"

    fun configure(context: Context, database: DatabaseHelper, selectedTreeUri: String?): JSONObject {
        val manager = WorkManager.getInstance(context.applicationContext)
        val enabled = isEnabled(database.getSetting("autoBackupEnabled"), false)
        val includeDatabase = isEnabled(database.getSetting("includeDatabaseBackup"), true)
        if (!enabled || !includeDatabase) {
            manager.cancelUniqueWork(PERIODIC_WORK_NAME)
            return JSONObject()
                .put("success", true)
                .put("scheduled", false)
                .put("message", if (!enabled) "النسخ التلقائي معطل" else "إدراج قاعدة البيانات معطل؛ لم تتم جدولة النسخ التلقائي لقاعدة البيانات")
        }
        if (selectedTreeUri.isNullOrBlank()) {
            manager.cancelUniqueWork(PERIODIC_WORK_NAME)
            return JSONObject()
                .put("success", false)
                .put("error", "تم حفظ الإعدادات، لكن يجب تحديد «مكان النسخة الاحتياطية» قبل تفعيل النسخ التلقائي")
        }
        val interval = database.getSetting("backupIntervalHours").toIntOrNull()?.coerceIn(1, 720) ?: 24
        val request = PeriodicWorkRequestBuilder<FullDatabaseBackupWorker>(interval.toLong(), TimeUnit.HOURS).build()
        manager.enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        return JSONObject()
            .put("success", true)
            .put("scheduled", true)
            .put("interval_hours", interval)
            .put("message", "تم تطبيق جدولة النسخ الاحتياطي التلقائي")
    }

    fun scheduleOnExit(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            EXIT_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<FullDatabaseBackupWorker>().build()
        )
    }

    private fun isEnabled(value: String?, defaultValue: Boolean): Boolean = when (value?.trim()?.lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> defaultValue
    }
}
