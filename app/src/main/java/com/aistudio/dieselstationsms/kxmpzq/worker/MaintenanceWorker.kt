package com.aistudio.dieselstationsms.kxmpzq.worker

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil

/**
 * ========================================================================
 * MaintenanceWorker – Enterprise Grade (v4.6.2 Production Final)
 * ========================================================================
 *
 * مسؤول عن تنفيذ مهام الصيانة الدورية للنظام.
 * يعمل كمنسق (Orchestrator) فقط، وكل المنطق الفعلي يُفوض إلى DatabaseHelper.
 *
 * المهام وتصنيفها حسب الخطورة:
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │  NORMAL            │ cleanupExpired, cleanupOldRateLimits,        │
 * │                    │ cleanupOldConversationContext,                 │
 * │                    │ cleanupOldMetrics, cleanupOldData, flush       │
 * ├────────────────────┼────────────────────────────────────────────────┤
 * │  SECURITY_CRITICAL │ Security Check (فشله يوقف Pipeline – أمان)   │
 * ├────────────────────┼────────────────────────────────────────────────┤
 * │  DATABASE_CRITICAL │ Integrity Check (فساد قاعدة البيانات)        │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * استراتيجية إعادة المحاولة:
 * • NORMAL             → retry حتى MAX_ATTEMPTS (إذا retryable)
 * • SECURITY_CRITICAL  → retry حتى SECURITY_MAX_ATTEMPTS
 * • DATABASE_CRITICAL  → retry حتى CRITICAL_MAX_ATTEMPTS (مع فحص Transient)
 *
 * سلوك Partial Failure:
 * • NORMAL tasks فشلت → Result.success(status=partial) [ليس failure]
 * • SECURITY/INTEGRITY فشلت → Result.failure()
 *
 * ========================================================================
 * @version 4.6.2 – Production Final
 * ========================================================================
 */
class MaintenanceWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "MaintenanceWorker"
        private const val VERSION = "4.6.2"
        private const val NOTIFICATION_CHANNEL_ID = "maintenance_alerts"

        /** مفاتيح Data الراجعة. */
        private const val KEY_STATUS = "status"
        private const val KEY_VERSION = "version"
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_RUN_ID = "run_id"
        private const val KEY_WORKER_ID = "worker_id"
        private const val KEY_STARTED_AT = "started_at"
        private const val KEY_FINISHED_AT = "finished_at"
        private const val KEY_DURATION = "duration"
        private const val KEY_ERROR = "error"
        private const val KEY_FAILED_TASK = "failed_task"
        private const val KEY_FAILURE_TYPE = "failure_type"
        private const val KEY_ATTEMPT = "attempt"
        private const val KEY_TASKS_SUCCEEDED = "tasks_succeeded"
        private const val KEY_TASKS_SKIPPED = "tasks_skipped"
        private const val KEY_TASKS_FAILED = "tasks_failed"
        private const val KEY_TOTAL_TASKS = "total_tasks"
        private const val KEY_DB_SIZE_MB = "db_size_mb"
        private const val KEY_DB_SIZE_BEFORE_MB = "db_size_before_mb"
        private const val KEY_DB_SIZE_AFTER_MB = "db_size_after_mb"
        private const val KEY_DB_SIZE_UNAVAILABLE = "db_size_unavailable"
        private const val KEY_HEALTH_SCORE = "health_score"
        private const val KEY_VACUUM_EXECUTED = "vacuum_executed"
        private const val KEY_INTEGRITY_STATUS = "integrity_status"
        private const val KEY_SECURITY_STATUS = "security_status"
        private const val KEY_MEMORY_USED_MB = "memory_used_mb"
        private const val KEY_TASK_HISTORY = "task_history"

        /** حالات النتيجة. */
        private const val STATUS_SUCCESS = "success"
        private const val STATUS_FAILURE = "failure"
        private const val STATUS_PARTIAL = "partial"
        private const val STATUS_SKIPPED = "skipped"
    }

    // ========================================================================
    // معرّفات المهام (TaskId) — ثابتة وآمنة
    // ========================================================================

    private enum class TaskId {
        SECURITY_CHECK,
        WAL_MODE_CHECK,
        CLEANUP_EXPIRED,
        CLEANUP_RATE_LIMITS,
        CLEANUP_CONVERSATION_CONTEXT,
        CLEANUP_METRICS,
        CLEANUP_OLD_DATA,
        FLUSH_CACHE,
        INTEGRITY_CHECK,
        VACUUM_DATABASE,
        FINAL_FLUSH
    }

    // ========================================================================
    // أنواع المهام (Severity)
    // ========================================================================

    private enum class TaskSeverity {
        /** مهام روتينية – فشلها لا يؤثر على استقرار النظام. */
        NORMAL,

        /** مهام أمنية – فشلها يوقف Pipeline (لكن ليست فساد DB). */
        SECURITY_CRITICAL,

        /** مهام حرجة – فشلها يعني فساد قاعدة البيانات. */
        DATABASE_CRITICAL
    }

    // ========================================================================
    // Immutable Result Types
    // ========================================================================

    /** نتيجة قراءة حجم قاعدة البيانات – immutable وآمنة. */
    private data class DatabaseSizeResult(
        val bytes: Long,
        val available: Boolean
    )

    /** نتيجة خط أنابيب الصيانة – immutable. */
    private data class PipelineResult(
        val results: List<TaskResult>,
        val dbSizeBefore: DatabaseSizeResult,
        val dbSizeAfter: DatabaseSizeResult,
        val securityFailed: Boolean,
        val integrityFailed: Boolean,
        val vacuumExecuted: Boolean
    )

    // ========================================================================
    // State
    // ========================================================================

    /** Run ID فريد لهذا التنفيذ — initialized safely with default. */
    private var runId: String = "unknown"

    /** وقت بدء التنفيذ (millis). */
    private var startedAt: Long = 0L

    // ========================================================================
    // Logging
    // ========================================================================

    private fun logDebug(message: String) = Log.d(TAG, "[$runId] $message")
    private fun logWarning(message: String) = Log.w(TAG, "[$runId] $message")
    private fun logError(message: String, throwable: Throwable? = null) =
        Log.e(TAG, "[$runId] $message", throwable)

    // ========================================================================
    // Data Builders
    // ========================================================================

    private fun successData(
        duration: Long,
        results: List<TaskResult>,
        dbSizeBefore: DatabaseSizeResult,
        dbSizeAfter: DatabaseSizeResult,
        vacuumExecuted: Boolean,
        memoryUsedMb: Long
    ): Data {
        val succeeded = results.count { it.success && !it.skipped }
        val skipped = results.count { it.skipped }
        val failed = results.count { !it.success && !it.skipped }
        val status = when {
            failed == 0 && skipped == results.size -> STATUS_SKIPPED
            failed == 0 -> STATUS_SUCCESS
            succeeded > 0 && failed > 0 -> STATUS_PARTIAL
            else -> STATUS_FAILURE
        }
        val dbSizeMb = if (dbSizeAfter.available && dbSizeAfter.bytes > 0) {
            ceil(dbSizeAfter.bytes / 1024.0 / 1024.0).toLong()
        } else {
            0L
        }
        val dbSizeBeforeMb = if (dbSizeBefore.available && dbSizeBefore.bytes > 0) {
            ceil(dbSizeBefore.bytes / 1024.0 / 1024.0).toLong()
        } else {
            0L
        }
        val healthScore = calculateHealthScore(results, dbSizeAfter)
        return Data.Builder()
            .putString(KEY_STATUS, status)
            .putString(KEY_VERSION, VERSION)
            .putInt(KEY_SCHEMA_VERSION, MaintenanceConfig.SCHEMA_VERSION)
            .putString(KEY_RUN_ID, runId)
            .putString(KEY_WORKER_ID, id.toString())
            .putLong(KEY_STARTED_AT, startedAt)
            .putLong(KEY_FINISHED_AT, System.currentTimeMillis())
            .putLong(KEY_DURATION, duration)
            .putInt(KEY_TASKS_SUCCEEDED, succeeded)
            .putInt(KEY_TASKS_SKIPPED, skipped)
            .putInt(KEY_TASKS_FAILED, failed)
            .putInt(KEY_TOTAL_TASKS, results.size)
            .putLong(KEY_DB_SIZE_MB, dbSizeMb)
            .putLong(KEY_DB_SIZE_BEFORE_MB, dbSizeBeforeMb)
            .putLong(KEY_DB_SIZE_AFTER_MB, dbSizeMb)
            .putBoolean(KEY_DB_SIZE_UNAVAILABLE, !dbSizeAfter.available)
            .putInt(KEY_HEALTH_SCORE, healthScore)
            .putBoolean(KEY_VACUUM_EXECUTED, vacuumExecuted)
            .putBoolean(KEY_INTEGRITY_STATUS, !results.any { it.taskId == TaskId.INTEGRITY_CHECK && !it.success })
            .putBoolean(KEY_SECURITY_STATUS, !results.any { it.taskId == TaskId.SECURITY_CHECK && !it.success })
            .putLong(KEY_MEMORY_USED_MB, memoryUsedMb)
            .putString(KEY_TASK_HISTORY, buildTaskHistoryJson(results))
            .build()
    }

    private fun failureData(
        error: String,
        failedTask: String? = null,
        failureType: String? = null,
        dbSizeResult: DatabaseSizeResult? = null,
        memoryUsedMb: Long = 0L,
        results: List<TaskResult>? = null
    ): Data {
        val builder = Data.Builder()
            .putString(KEY_STATUS, STATUS_FAILURE)
            .putString(KEY_VERSION, VERSION)
            .putInt(KEY_SCHEMA_VERSION, MaintenanceConfig.SCHEMA_VERSION)
            .putString(KEY_RUN_ID, runId)
            .putString(KEY_WORKER_ID, id.toString())
            .putLong(KEY_STARTED_AT, startedAt)
            .putLong(KEY_FINISHED_AT, System.currentTimeMillis())
            .putString(KEY_ERROR, error)
            .putInt(KEY_ATTEMPT, runAttemptCount + 1)
            .putLong(KEY_MEMORY_USED_MB, memoryUsedMb)
        failedTask?.let { builder.putString(KEY_FAILED_TASK, it) }
        failureType?.let { builder.putString(KEY_FAILURE_TYPE, it) }
        dbSizeResult?.let {
            builder.putBoolean(KEY_DB_SIZE_UNAVAILABLE, !it.available)
            if (it.available && it.bytes > 0) {
                builder.putLong(KEY_DB_SIZE_MB, ceil(it.bytes / 1024.0 / 1024.0).toLong())
            }
        }
        results?.let { builder.putString(KEY_TASK_HISTORY, buildTaskHistoryJson(it)) }
        return builder.build()
    }

    // ========================================================================
    // Task History JSON Builder
    // ========================================================================

    private fun buildTaskHistoryJson(results: List<TaskResult>): String {
        return try {
            val array = JSONArray()
            results.forEach { task ->
                array.put(JSONObject().apply {
                    put("task_id", task.taskId.name)
                    put("name", task.name)
                    put("status", when {
                        task.skipped -> "skipped"
                        task.success -> "success"
                        else -> "failed"
                    })
                    put("severity", task.severity.name)
                    put("duration_ms", task.durationMs)
                    put("retryable", task.retryable)
                    task.error?.let { put("error", it) }
                })
            }
            array.toString()
        } catch (e: Exception) {
            logWarning("Failed to build task history JSON: ${e.message}")
            "[]"
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun shouldRetry(): Boolean = runAttemptCount < MaintenanceConfig.MAX_ATTEMPTS

    private fun shouldRetryForSeverity(severity: TaskSeverity): Boolean {
        return when (severity) {
            TaskSeverity.NORMAL -> runAttemptCount < MaintenanceConfig.MAX_ATTEMPTS
            TaskSeverity.SECURITY_CRITICAL -> runAttemptCount < MaintenanceConfig.SECURITY_MAX_ATTEMPTS
            TaskSeverity.DATABASE_CRITICAL -> runAttemptCount < MaintenanceConfig.CRITICAL_MAX_ATTEMPTS
        }
    }

    private suspend fun checkCancellation() {
        coroutineContext.ensureActive()
    }

    private fun isTransientDatabaseError(e: Exception): Boolean {
        val msg = e.message?.lowercase() ?: return false
        return msg.contains("database is locked") ||
               msg.contains("database is busy") ||
               msg.contains("sqlite_busy") ||
               msg.contains("sqlite_locked") ||
               msg.contains("busy")
    }

    /**
     * الحصول على استهلاك الذاكرة بدقة عالية باستخدام ActivityManager.
     * يُفضل على Runtime.getRuntime() لأنه يعطي قياساً أدق في Android.
     */
    private fun getMemoryUsedMb(): Long {
        return try {
            val activityManager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            // إجمالي الذاكرة المتاحة - المتاحة الحالية = المستخدمة
            val totalMem = memoryInfo.totalMem / (1024 * 1024)
            val availMem = memoryInfo.availMem / (1024 * 1024)
            totalMem - availMem
        } catch (e: Exception) {
            // Fallback إلى Runtime
            try {
                val runtime = Runtime.getRuntime()
                (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            } catch (e2: Exception) {
                0L
            }
        }
    }

    /**
     * الحصول على استهلاك الذاكرة الخاص بالتطبيق (PSS) باستخدام Debug.MemoryInfo.
     * أكثر دقة لقياس استهلاك التطبيق نفسه.
     */
    private fun getAppMemoryUsedMb(): Long {
        return try {
            val memoryInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memoryInfo)
            memoryInfo.totalPss / 1024L  // convert KB to MB
        } catch (e: Exception) {
            0L
        }
    }

    // ========================================================================
    // Health Score Calculator
    // ========================================================================

    private fun calculateHealthScore(results: List<TaskResult>, dbSizeResult: DatabaseSizeResult): Int {
        // Integrity failure = score 0 (database may be corrupted)
        val integrityFailed = results.any { it.taskId == TaskId.INTEGRITY_CHECK && !it.success && !it.skipped }
        if (integrityFailed) return 0

        var score = 100
        score -= results.count { !it.success && !it.skipped && it.severity == TaskSeverity.DATABASE_CRITICAL } * 25
        score -= results.count { !it.success && !it.skipped && it.severity == TaskSeverity.SECURITY_CRITICAL } * 30
        score -= results.count { !it.success && !it.skipped && it.severity == TaskSeverity.NORMAL } * 5
        if (!dbSizeResult.available) score -= 5
        val dbSizeMb = if (dbSizeResult.available) dbSizeResult.bytes / (1024 * 1024) else 0
        if (dbSizeMb > MaintenanceConfig.VERY_LARGE_DB_MB) score -= 20
        else if (dbSizeMb > MaintenanceConfig.LARGE_DB_MB) score -= 10
        return score.coerceIn(0, 100)
    }

    // ========================================================================
    // Notification Helper
    // ========================================================================

    private fun showCriticalNotification(title: String, message: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.checkSelfPermission(
                        applicationContext,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    logWarning("POST_NOTIFICATIONS permission not granted — skipping notification")
                    return
                }
            }

            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                    val channel = NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        "Maintenance Alerts",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                    notificationManager.createNotificationChannel(channel)
                }
            }

            val iconRes = applicationContext.resources.getIdentifier(
                "ic_warning", "drawable", applicationContext.packageName
            ).takeIf { it != 0 } ?: android.R.drawable.ic_dialog_alert

            // Safe notification ID: hashCode() and 0x7fffffff (avoids abs(Int.MIN_VALUE) issue)
            val notificationId = runId.hashCode() and 0x7fffffff

            val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(iconRes)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(notificationId, notification)
        } catch (e: Exception) {
            logWarning("Failed to show notification: ${e.message}")
        }
    }

    // ========================================================================
    // Database Size Safe Getter (Immutable)
    // ========================================================================

    private fun getDatabaseSizeSafe(db: DatabaseHelper): DatabaseSizeResult {
        return try {
            val size = db.getDatabaseSize()
            DatabaseSizeResult(bytes = size, available = true)
        } catch (e: Exception) {
            logWarning("Unable to get database size: ${e.message}")
            DatabaseSizeResult(bytes = 0L, available = false)
        }
    }

    // ========================================================================
    // VACUUM Space Check (uses actual database directory)
    // ========================================================================

    /**
     * التحقق من وجود مساحة كافية لتشغيل VACUUM.
     * VACUUM يحتاج مساحة إضافية تقريباً بحجم قاعدة البيانات.
     * يستخدم مسار قاعدة البيانات الفعلي (DatabaseHelper.DATABASE_NAME).
     * @return true إذا كانت المساحة كافية
     */
    private fun hasEnoughSpaceForVacuum(dbSizeBytes: Long): Boolean {
        return try {
            // استخدام مسار قاعدة البيانات الفعلي من DatabaseHelper
            val dbPath = applicationContext.getDatabasePath(DatabaseHelper.DATABASE_NAME)
            val dbDir = dbPath.parentFile ?: applicationContext.filesDir
            val freeSpace = dbDir.freeSpace
            val requiredSpace = (dbSizeBytes * 1.5).toLong()
            val hasSpace = freeSpace >= requiredSpace
            if (!hasSpace) {
                logWarning("Insufficient space for VACUUM: ${freeSpace / (1024 * 1024)} MB free in ${dbDir.absolutePath}, need ${requiredSpace / (1024 * 1024)} MB")
            }
            hasSpace
        } catch (e: Exception) {
            logWarning("Unable to check available space: ${e.message}")
            false
        }
    }

    // ========================================================================
    // Diagnostics & Health Monitoring
    // ========================================================================

    private fun logSystemState() {
        try {
            val runtime = Runtime.getRuntime()
            val maxMemory = runtime.maxMemory() / (1024 * 1024)
            val totalMemory = runtime.totalMemory() / (1024 * 1024)
            val freeMemory = runtime.freeMemory() / (1024 * 1024)
            val usedMemory = totalMemory - freeMemory
            val appMemory = getAppMemoryUsedMb()
            val systemMemory = getMemoryUsedMb()
            val appVersion = getAppVersion()

            logDebug(
                """
                ===== Maintenance Diagnostics =====
                App Version      : $appVersion
                Android SDK      : ${Build.VERSION.SDK_INT}
                Device           : ${Build.MANUFACTURER} ${Build.MODEL}
                App Memory (PSS) : ${appMemory} MB
                System Memory    : ${systemMemory} MB
                Heap Used        : ${usedMemory} MB
                Heap Free        : ${freeMemory} MB
                Heap Total       : ${totalMemory} MB
                Heap Max         : ${maxMemory} MB
                Thread           : ${Thread.currentThread().name}
                ===== Version: $VERSION | Run: $runId =====
                """.trimIndent()
            )
        } catch (e: Exception) {
            logWarning("Unable to collect memory diagnostics: ${e.message}")
        }
    }

    private fun getAppVersion(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                applicationContext.packageManager.getPackageInfo(
                    applicationContext.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                ).versionName ?: "unknown"
            } else {
                @Suppress("DEPRECATION")
                applicationContext.packageManager.getPackageInfo(
                    applicationContext.packageName, 0
                ).versionName ?: "unknown"
            }
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun logStorageStatus() {
        try {
            val dir = applicationContext.filesDir
            val freeMb = dir.freeSpace / (1024 * 1024)
            val totalMb = dir.totalSpace / (1024 * 1024)
            logDebug("Storage: ${freeMb} MB free of ${totalMb} MB")
        } catch (e: Exception) {
            logWarning("Unable to read storage status: ${e.message}")
        }
    }

    private fun logExecutionStatistics(duration: Long) {
        when {
            duration < 1000L -> logDebug("Execution time: ${duration} ms (Excellent)")
            duration < 5000L -> logDebug("Execution time: ${duration} ms (Good)")
            duration < MaintenanceConfig.WARNING_EXECUTION_TIME_MS -> logWarning("Execution time: ${duration} ms (Acceptable)")
            duration < 60_000L -> logWarning("Execution time: ${duration} ms (Slow)")
            else -> logError("Execution time: ${duration} ms (Critical Slow)")
        }
    }

    // ========================================================================
    // Safe Maintenance Task Runner
    // ========================================================================

    private data class TaskResult(
        val taskId: TaskId,
        val name: String,
        val success: Boolean,
        val startTime: Long,
        val endTime: Long,
        val durationMs: Long,
        val severity: TaskSeverity,
        val skipped: Boolean = false,
        val retryable: Boolean = true,
        val error: String? = null
    )

    private suspend fun runMaintenanceTask(
        taskId: TaskId,
        name: String,
        severity: TaskSeverity = TaskSeverity.NORMAL,
        retryable: Boolean = true,
        task: suspend () -> Unit
    ): TaskResult {
        checkCancellation()
        val start = SystemClock.elapsedRealtime()
        val startTime = System.currentTimeMillis()
        return try {
            task()
            val endTime = System.currentTimeMillis()
            val duration = SystemClock.elapsedRealtime() - start
            logDebug("✔ $name completed in ${duration} ms")
            TaskResult(taskId, name, true, startTime, endTime, duration, severity, retryable = retryable)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val endTime = System.currentTimeMillis()
            val duration = SystemClock.elapsedRealtime() - start
            logError("✘ $name failed (${severity.name})", e)
            TaskResult(taskId, name, false, startTime, endTime, duration, severity, retryable = retryable, error = e.message)
        }
    }

    // ========================================================================
    // VACUUM Safe Executor (مع حماية المساحة و Timeout منفصل)
    // ========================================================================

    private suspend fun runVacuumSafely(db: DatabaseHelper): Pair<TaskResult, Boolean> {
        val taskId = TaskId.VACUUM_DATABASE
        val name = "Vacuum Database"
        val severity = TaskSeverity.NORMAL

        val sizeResult = getDatabaseSizeSafe(db)
        val dbSizeMb = if (sizeResult.available && sizeResult.bytes > 0) {
            ceil(sizeResult.bytes / 1024.0 / 1024.0).toLong()
        } else {
            0L
        }

        if (!sizeResult.available || dbSizeMb <= MaintenanceConfig.VACUUM_THRESHOLD_MB) {
            val message = if (!sizeResult.available) {
                "Skipped – unable to determine DB size"
            } else {
                "Skipped – DB size (${dbSizeMb} MB) ≤ threshold (${MaintenanceConfig.VACUUM_THRESHOLD_MB} MB)"
            }
            logDebug(message)
            val now = System.currentTimeMillis()
            return Pair(
                TaskResult(taskId, name, true, now, now, 0L, severity, skipped = true, retryable = true, error = message),
                false
            )
        }

        // ── التحقق من المساحة الكافية ──
        if (!hasEnoughSpaceForVacuum(sizeResult.bytes)) {
            val message = "Skipped – insufficient storage space for VACUUM"
            logWarning(message)
            val now = System.currentTimeMillis()
            return Pair(
                TaskResult(taskId, name, true, now, now, 0L, severity, skipped = true, retryable = true, error = message),
                false
            )
        }

        logDebug("Starting VACUUM – DB size is ${dbSizeMb} MB")

        val start = SystemClock.elapsedRealtime()
        val startTime = System.currentTimeMillis()
        return try {
            // VACUUM مع Timeout منفصل أطول
            withContext(Dispatchers.IO) {
                withTimeout(MaintenanceConfig.VACUUM_TIMEOUT_MS) {
                    db.vacuumDatabase()
                }
            }
            val endTime = System.currentTimeMillis()
            val duration = SystemClock.elapsedRealtime() - start
            logDebug("✔ $name completed in ${duration} ms")
            Pair(TaskResult(taskId, name, true, startTime, endTime, duration, severity, retryable = true), true)
        } catch (e: TimeoutCancellationException) {
            val endTime = System.currentTimeMillis()
            val duration = SystemClock.elapsedRealtime() - start
            logError("✘ $name timed out after ${MaintenanceConfig.VACUUM_TIMEOUT_MS}ms", e)
            Pair(TaskResult(taskId, name, false, startTime, endTime, duration, severity, retryable = true, error = "VACUUM timed out"), false)
        } catch (e: android.database.sqlite.SQLiteException) {
            val endTime = System.currentTimeMillis()
            val duration = SystemClock.elapsedRealtime() - start
            if (e.message?.contains("transaction", ignoreCase = true) == true) {
                logWarning("VACUUM skipped due to open transaction: ${e.message}")
                Pair(
                    TaskResult(taskId, name, true, startTime, endTime, duration, severity, skipped = true, retryable = true, error = "Skipped: ${e.message}"),
                    false
                )
            } else {
                logError("✘ $name failed (SQLiteException)", e)
                Pair(TaskResult(taskId, name, false, startTime, endTime, duration, severity, retryable = true, error = e.message), false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val endTime = System.currentTimeMillis()
            val duration = SystemClock.elapsedRealtime() - start
            logError("✘ $name failed", e)
            Pair(TaskResult(taskId, name, false, startTime, endTime, duration, severity, retryable = true, error = e.message), false)
        }
    }

    // ========================================================================
    // Production Maintenance Pipeline
    // ========================================================================

    private suspend fun runMaintenancePipeline(db: DatabaseHelper): PipelineResult {
        val results = mutableListOf<TaskResult>()
        var vacuumExecuted = false

        // ── حجم DB قبل التنفيذ ──
        val dbSizeBefore = getDatabaseSizeSafe(db)

        // ── 1. Security Check – SECURITY_CRITICAL ──
        results += runMaintenanceTask(TaskId.SECURITY_CHECK, "Security Check", TaskSeverity.SECURITY_CRITICAL, retryable = true) {
            val securityOk = db.performSecurityCheck()
            if (!securityOk) {
                throw SecurityException("Security check returned false – system may be compromised")
            }
        }

        val securityFailed = results.lastOrNull()?.let {
            !it.success && !it.skipped && it.severity == TaskSeverity.SECURITY_CRITICAL
        } ?: false

        if (securityFailed) {
            logWarning("Security check failed. Stopping maintenance pipeline.")
            return PipelineResult(results, dbSizeBefore, dbSizeBefore, securityFailed = true, integrityFailed = false, vacuumExecuted = false)
        }

        // ── 2. WAL Mode Check – NORMAL (يفشل إذا لم يكن WAL) ──
        results += runMaintenanceTask(TaskId.WAL_MODE_CHECK, "WAL Mode Check", retryable = false) {
            val journalMode = db.getJournalMode()
            val trimmedMode = journalMode?.trim()?.uppercase()
            if (trimmedMode != "WAL") {
                throw IllegalStateException("Journal mode is '$trimmedMode', expected WAL. Performance and concurrency may be degraded.")
            }
        }

        // ── 3. Cleanup Expired – NORMAL ──
        results += runMaintenanceTask(TaskId.CLEANUP_EXPIRED, "Cleanup Expired", retryable = true) {
            db.cleanupExpired()
        }

        // ── 4. Cleanup Rate Limits – NORMAL ──
        results += runMaintenanceTask(TaskId.CLEANUP_RATE_LIMITS, "Cleanup Rate Limits", retryable = true) {
            db.cleanupOldRateLimits()
        }

        // ── 5. Cleanup Conversation Context – NORMAL ──
        results += runMaintenanceTask(TaskId.CLEANUP_CONVERSATION_CONTEXT, "Cleanup Conversation Context", retryable = true) {
            db.cleanupOldConversationContext()
        }

        // ── 6. Cleanup Metrics – NORMAL ──
        results += runMaintenanceTask(TaskId.CLEANUP_METRICS, "Cleanup Metrics", retryable = true) {
            db.cleanupOldMetrics()
        }

        // ── 7. Cleanup Old Data – NORMAL ──
        results += runMaintenanceTask(TaskId.CLEANUP_OLD_DATA, "Cleanup Old Data (Full)", retryable = true) {
            db.cleanupOldData()
        }

        // ── 8. Flush – NORMAL (قبل Integrity) ──
        results += runMaintenanceTask(TaskId.FLUSH_CACHE, "Flush Cache", retryable = true) {
            db.flush()
        }

        // ── 9. Integrity Check – DATABASE_CRITICAL ──
        results += runMaintenanceTask(TaskId.INTEGRITY_CHECK, "Integrity Check", TaskSeverity.DATABASE_CRITICAL, retryable = false) {
            val isOk = db.checkIntegrity()
            if (!isOk) {
                throw IllegalStateException("Database integrity check failed – possible corruption")
            }
        }

        val integrityFailed = results.lastOrNull()?.let {
            !it.success && !it.skipped && it.severity == TaskSeverity.DATABASE_CRITICAL
        } ?: false

        if (integrityFailed) {
            logWarning("Integrity check failed. Stopping maintenance pipeline.")
            val dbSizeAfter = getDatabaseSizeSafe(db)
            return PipelineResult(results, dbSizeBefore, dbSizeAfter, securityFailed = false, integrityFailed = true, vacuumExecuted = false)
        }

        // ── 10. VACUUM – NORMAL (بعد التأكد من سلامة DB) ──
        val (vacuumResult, didVacuum) = runVacuumSafely(db)
        results += vacuumResult
        vacuumExecuted = didVacuum

        // ── 11. Final Flush – NORMAL ──
        results += runMaintenanceTask(TaskId.FINAL_FLUSH, "Final Flush", retryable = true) {
            db.flush()
        }

        val dbSizeAfter = getDatabaseSizeSafe(db)
        return PipelineResult(results, dbSizeBefore, dbSizeAfter, securityFailed = false, integrityFailed = false, vacuumExecuted = vacuumExecuted)
    }

    // ========================================================================
    // Performance Recording
    // ========================================================================

    private fun recordPerformanceStats(
        db: DatabaseHelper,
        duration: Long,
        results: List<TaskResult>,
        dbSizeBefore: DatabaseSizeResult,
        dbSizeAfter: DatabaseSizeResult,
        vacuumExecuted: Boolean
    ) {
        try {
            val stats = JSONObject().apply {
                put("worker", "MaintenanceWorker")
                put("version", VERSION)
                put("schema_version", MaintenanceConfig.SCHEMA_VERSION)
                put("run_id", runId)
                put("worker_id", id.toString())
                put("duration_ms", duration)
                put("timestamp", System.currentTimeMillis())
                put("attempt", runAttemptCount + 1)
                put("tasks_total", results.size)
                put("tasks_success", results.count { it.success && !it.skipped })
                put("tasks_skipped", results.count { it.skipped })
                put("tasks_failed", results.count { !it.success && !it.skipped })
                put("db_size_before_bytes", dbSizeBefore.bytes)
                put("db_size_after_bytes", dbSizeAfter.bytes)
                put("db_size_available", dbSizeAfter.available)
                put("vacuum_executed", vacuumExecuted)
                put("integrity_status", !results.any { it.taskId == TaskId.INTEGRITY_CHECK && !it.success })
                put("security_status", !results.any { it.taskId == TaskId.SECURITY_CHECK && !it.success })
                put("health_score", calculateHealthScore(results, dbSizeAfter))
                put("memory_used_mb", getMemoryUsedMb())
                put("app_memory_pss_mb", getAppMemoryUsedMb())
                put("worker_thread", Thread.currentThread().name)
                put("android_sdk", Build.VERSION.SDK_INT)
                put("device_manufacturer", Build.MANUFACTURER)
                put("device_model", Build.MODEL)
                put("app_version", getAppVersion())
                put("task_history", JSONArray(buildTaskHistoryJson(results)))
            }
            val saved = db.recordPerformanceStats(stats)
            if (saved) {
                logDebug("Performance statistics recorded")
            } else {
                logWarning("Performance statistics were not saved")
            }
        } catch (e: Exception) {
            logWarning("Unable to record performance statistics: ${e.message}")
        }
    }

    // ========================================================================
    // Summary Report
    // ========================================================================

    private fun logMaintenanceSummary(
        duration: Long,
        results: List<TaskResult>,
        dbSizeBefore: DatabaseSizeResult,
        dbSizeAfter: DatabaseSizeResult,
        vacuumExecuted: Boolean
    ) {
        val succeeded = results.count { it.success && !it.skipped }
        val skipped = results.count { it.skipped }
        val failed = results.count { !it.success && !it.skipped }
        val securityFailed = results.count {
            !it.success && !it.skipped && it.severity == TaskSeverity.SECURITY_CRITICAL
        }
        val criticalFailed = results.count {
            !it.success && !it.skipped && it.severity == TaskSeverity.DATABASE_CRITICAL
        }
        val healthScore = calculateHealthScore(results, dbSizeAfter)

        val beforeText = if (!dbSizeBefore.available) "unavailable" else "${ceil(dbSizeBefore.bytes / 1024.0 / 1024.0).toLong()} MB"
        val afterText = if (!dbSizeAfter.available) "unavailable" else "${ceil(dbSizeAfter.bytes / 1024.0 / 1024.0).toLong()} MB"
        val freedBytes = (dbSizeBefore.bytes - dbSizeAfter.bytes).coerceAtLeast(0L)
        val freedText = if (dbSizeBefore.available && dbSizeAfter.available) {
            "${ceil(freedBytes / 1024.0 / 1024.0).toLong()} MB"
        } else "unknown"

        logDebug(
            """
            ==================================================
                    Maintenance Report [Run: $runId]
            ==================================================
            Version          : $VERSION
            Total Tasks      : ${results.size}
            Succeeded        : $succeeded
            Skipped          : $skipped
            Failed           : $failed
            Security Failed  : $securityFailed
            Critical Failed  : $criticalFailed
            Health Score     : $healthScore%
            DB Size Before   : $beforeText
            DB Size After    : $afterText
            Space Freed      : $freedText
            VACUUM Executed  : $vacuumExecuted
            Total Time       : ${duration} ms
            Attempt          : ${runAttemptCount + 1}
            ==================================================
            """.trimIndent()
        )

        if (failed > 0) {
            logWarning("Failed tasks:")
            results.filter { !it.success && !it.skipped }.forEach {
                logWarning("  - ${it.name} (${it.severity.name}) [retryable=${it.retryable}] -> ${it.error ?: "Unknown"}")
            }
        }

        if (skipped > 0) {
            logDebug("Skipped tasks:")
            results.filter { it.skipped }.forEach {
                logDebug("  - ${it.name} -> ${it.error ?: "Skipped"}")
            }
        }
    }

    // ========================================================================
    // Main Entry Point
    // ========================================================================

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // ── تهيئة Run ID بأمان ──
        runId = try {
            java.util.UUID.randomUUID().toString()
        } catch (e: Exception) {
            "fallback-${System.currentTimeMillis()}"
        }
        startedAt = System.currentTimeMillis()
        val startTime = SystemClock.elapsedRealtime()

        logDebug("Maintenance job started (attempt=${runAttemptCount + 1})")
        checkCancellation()

        // تشخيص النظام
        logSystemState()
        logStorageStatus()

        val memoryUsedMb = getMemoryUsedMb()

        return@withContext try {
            // ── Lock لمنع تشغيل Worker مرتين ──
            MaintenanceCoordinator.lock.withLock {
                // ── الحصول على DatabaseHelper بأمان ──
                val db = try {
                    DatabaseHelper.getInstance(applicationContext)
                } catch (e: Exception) {
                    logError("Failed to initialize DatabaseHelper", e)
                    return@withLock if (shouldRetry()) {
                        Result.retry()
                    } else {
                        Result.failure(failureData("DatabaseHelper initialization failed: ${e.message}", memoryUsedMb = memoryUsedMb))
                    }
                }

                // ── التحقق من أن قاعدة البيانات مفتوحة ──
                if (!db.isOpen()) {
                    logError("Database connection is closed")
                    return@withLock if (shouldRetry()) {
                        Result.retry()
                    } else {
                        Result.failure(failureData("Database connection is closed", memoryUsedMb = memoryUsedMb))
                    }
                }

                // ── تنفيذ خط الأنابيب مع Timeout ──
                val pipelineResult = withTimeout(MaintenanceConfig.TIMEOUT_MS) {
                    runMaintenancePipeline(db)
                }
                val results = pipelineResult.results
                val dbSizeBefore = pipelineResult.dbSizeBefore
                val dbSizeAfter = pipelineResult.dbSizeAfter
                val integrityFailed = pipelineResult.integrityFailed
                val securityFailed = pipelineResult.securityFailed
                val vacuumExecuted = pipelineResult.vacuumExecuted

                val duration = SystemClock.elapsedRealtime() - startTime
                logExecutionStatistics(duration)
                logMaintenanceSummary(duration, results, dbSizeBefore, dbSizeAfter, vacuumExecuted)

                // ── تسجيل الأداء فقط إذا لم يفشل Integrity أو Security ──
                if (!integrityFailed && !securityFailed) {
                    recordPerformanceStats(db, duration, results, dbSizeBefore, dbSizeAfter, vacuumExecuted)
                } else {
                    if (integrityFailed) {
                        logWarning("Performance statistics skipped – database integrity compromised")
                    }
                    if (securityFailed) {
                        logWarning("Performance statistics skipped – security check failed")
                    }
                }

                // ── تنبيه إذا فشل شيء حرج ──
                if (integrityFailed || securityFailed) {
                    val notificationTitle = when {
                        integrityFailed && securityFailed -> "Database & Security Alert"
                        integrityFailed -> "Database Corruption Alert"
                        else -> "Security Alert"
                    }
                    val notificationMessage = when {
                        integrityFailed && securityFailed -> "Critical: Database integrity and security checks both failed. Immediate action required."
                        integrityFailed -> "Database integrity check failed. Possible corruption detected."
                        else -> "Security check failed. System may be compromised."
                    }
                    showCriticalNotification(notificationTitle, notificationMessage)
                }

                // ── تحليل النتائج واتخاذ القرار ──
                val retryableFailures = results.any { !it.success && !it.skipped && it.retryable }
                val allSucceeded = results.isNotEmpty() && results.all { it.success || it.skipped }
                val criticalFailed = results.any { !it.success && !it.skipped && it.severity == TaskSeverity.DATABASE_CRITICAL }
                val securityTaskFailed = results.any { !it.success && !it.skipped && it.severity == TaskSeverity.SECURITY_CRITICAL }

                when {
                    criticalFailed -> {
                        val failedTask = results.find { !it.success && !it.skipped && it.severity == TaskSeverity.DATABASE_CRITICAL }
                        if (shouldRetryForSeverity(TaskSeverity.DATABASE_CRITICAL)) {
                            logWarning("Critical task failed. Retrying... (attempt ${runAttemptCount + 1}/${MaintenanceConfig.CRITICAL_MAX_ATTEMPTS})")
                            Result.retry()
                        } else {
                            logError("Critical task failed after ${MaintenanceConfig.CRITICAL_MAX_ATTEMPTS} attempts.")
                            Result.failure(
                                failureData(
                                    "Critical maintenance task failed: ${failedTask?.name}",
                                    failedTask = failedTask?.name,
                                    failureType = "DATABASE_CRITICAL",
                                    dbSizeResult = dbSizeAfter,
                                    memoryUsedMb = memoryUsedMb,
                                    results = results
                                )
                            )
                        }
                    }
                    securityTaskFailed -> {
                        val failedTask = results.find { !it.success && !it.skipped && it.severity == TaskSeverity.SECURITY_CRITICAL }
                        if (shouldRetryForSeverity(TaskSeverity.SECURITY_CRITICAL)) {
                            logWarning("Security task failed. Retrying... (attempt ${runAttemptCount + 1}/${MaintenanceConfig.SECURITY_MAX_ATTEMPTS})")
                            Result.retry()
                        } else {
                            logError("Security task failed after ${MaintenanceConfig.SECURITY_MAX_ATTEMPTS} attempts.")
                            Result.failure(
                                failureData(
                                    "Security maintenance task failed: ${failedTask?.name}",
                                    failedTask = failedTask?.name,
                                    failureType = "SECURITY_CRITICAL",
                                    dbSizeResult = dbSizeAfter,
                                    memoryUsedMb = memoryUsedMb,
                                    results = results
                                )
                            )
                        }
                    }
                    allSucceeded -> {
                        Result.success(successData(duration, results, dbSizeBefore, dbSizeAfter, vacuumExecuted, memoryUsedMb))
                    }
                    else -> {
                        // NORMAL tasks failed — return partial success, not failure
                        if (retryableFailures && shouldRetry()) {
                            logWarning("Some retryable tasks failed. Retrying... (attempt ${runAttemptCount + 1}/${MaintenanceConfig.MAX_ATTEMPTS})")
                            Result.retry()
                        } else {
                            logWarning("Some normal tasks failed. Returning partial success.")
                            Result.success(successData(duration, results, dbSizeBefore, dbSizeAfter, vacuumExecuted, memoryUsedMb))
                        }
                    }
                }
            }

        } catch (e: TimeoutCancellationException) {
            logError("Maintenance pipeline timed out after ${MaintenanceConfig.TIMEOUT_MS}ms", e)
            if (shouldRetry()) {
                Result.retry()
            } else {
                Result.failure(failureData("Maintenance timed out after ${MaintenanceConfig.MAX_ATTEMPTS} attempts", memoryUsedMb = memoryUsedMb))
            }

        } catch (e: CancellationException) {
            logWarning("Maintenance cancelled")
            throw e

        } catch (e: Exception) {
            if (isTransientDatabaseError(e)) {
                logWarning("Transient database error detected: ${e.message}")
                if (shouldRetry()) {
                    Result.retry()
                } else {
                    Result.failure(failureData("Transient database error persisted: ${e.message}", memoryUsedMb = memoryUsedMb))
                }
            } else {
                logError("Unexpected maintenance error", e)
                if (shouldRetry()) {
                    Result.retry()
                } else {
                    Result.failure(failureData(e.message ?: "Unknown error", memoryUsedMb = memoryUsedMb))
                }
            }

        } finally {
            logDebug("Maintenance worker finished")
        }
    }
}

/**
 * ========================================================================
 * MaintenanceCoordinator – إدارة تنسيق تنفيذ الصيانة
 * ========================================================================
 */
object MaintenanceCoordinator {
    val lock = Mutex()
}

/**
 * ========================================================================
 * MaintenanceConfig – سياسة الصيانة المركزية
 * ========================================================================
 */
object MaintenanceConfig {
    const val SCHEMA_VERSION = 1
    const val MAX_ATTEMPTS = 3
    const val SECURITY_MAX_ATTEMPTS = 2
    const val CRITICAL_MAX_ATTEMPTS = 2
    const val WARNING_EXECUTION_TIME_MS = 10_000L
    const val VACUUM_THRESHOLD_MB = 100L
    const val LARGE_DB_MB = 1_000L
    const val VERY_LARGE_DB_MB = 5_000L
    const val TIMEOUT_MS = 240_000L
    const val VACUUM_TIMEOUT_MS = 600_000L
}
