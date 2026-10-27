package com.aistudio.dieselstationsms.kxmpzq.settings.monitoring

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.os.Process
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.service.SMSService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Collects genuine Android process, SMS service, and SQLite measurements.
 * Missing/unavailable sources are reported explicitly and never replaced by fabricated zeroes.
 */
class SettingsMonitoringRepositoryImpl(
    context: Context,
    private val database: DatabaseHelper = DatabaseHelper.getInstance(context.applicationContext)
) : SettingsMonitoringRepository {

    private val appContext = context.applicationContext
    private val stateFlow = MutableStateFlow(SettingsMonitoringState())

    override fun observeMonitoring(): Flow<SettingsMonitoringState> = stateFlow.asStateFlow()

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        val sources = linkedMapOf<String, String>()
        val metricValues = linkedMapOf<String, Any>()

        val service = runCatching { SMSService.getInstance() }.getOrNull()
        val serviceStatus = if (service == null) {
            sources["service_status"] = "unavailable"
            errors += "خدمة SMS غير متاحة لقراءة الحالة"
            null
        } else {
            runCatching { service.getServiceStatus() }
                .onSuccess { sources["service_status"] = if (it.has("error")) "error" else "available" }
                .onFailure {
                    sources["service_status"] = "error"
                    errors += "تعذرت قراءة حالة خدمة SMS"
                }
                .getOrNull()
        }

        val serviceStatistics = if (service == null) {
            sources["service_statistics"] = "unavailable"
            null
        } else {
            runCatching { service.getServiceStatistics() }
                .onSuccess {
                    val ok = it.length() > 0 && !it.has("error")
                    sources["service_statistics"] = if (ok) "available" else "error"
                    if (!ok) errors += "إحصاءات خدمة SMS غير متاحة من المصدر"
                }
                .onFailure {
                    sources["service_statistics"] = "error"
                    errors += "تعذرت قراءة إحصاءات خدمة SMS"
                }
                .getOrNull()
        }

        val databaseAvailable = runCatching { database.isOpen() }
            .onSuccess { sources["database"] = if (it) "available" else "error" }
            .onFailure {
                sources["database"] = "error"
                errors += "تعذر التحقق من إتاحة SQLite"
            }
            .getOrDefault(false)
        if (!databaseAvailable && "تعذر التحقق من إتاحة SQLite" !in errors) {
            errors += "قاعدة SQLite غير متاحة"
        }

        val databaseIntegrityOk = if (databaseAvailable) {
            runCatching { database.checkIntegrity() }
                .onSuccess { sources["database_integrity"] = if (it) "available" else "error" }
                .onFailure {
                    sources["database_integrity"] = "error"
                    errors += "تعذر فحص سلامة SQLite"
                }
                .getOrNull()
        } else {
            sources["database_integrity"] = "unavailable"
            null
        }
        if (databaseIntegrityOk == false) errors += "فشل فحص سلامة SQLite"

        val databaseMetrics = if (databaseAvailable) {
            runCatching { database.getCurrentMetrics() }
                .onSuccess { sources["database_metrics"] = if (it.length() > 0 && !it.has("error")) "available" else "error" }
                .onFailure {
                    sources["database_metrics"] = "error"
                    errors += "تعذرت قراءة مقاييس قاعدة البيانات"
                }
                .getOrNull()
        } else {
            sources["database_metrics"] = "unavailable"
            null
        }
        if (databaseMetrics != null && !databaseMetrics.has("error")) {
            jsonObjectToMap(databaseMetrics).forEach { (key, value) -> metricValues["database." + key] = value }
        } else if (sources["database_metrics"] == "available") {
            sources["database_metrics"] = "error"
        }

        val smsMetrics = if (service == null) {
            sources["sms_metrics"] = "unavailable"
            null
        } else {
            runCatching { service.getCurrentMetrics() }
                .onSuccess {
                    val ok = it.length() > 0 && !it.has("error")
                    sources["sms_metrics"] = if (ok) "available" else "error"
                    if (!ok) errors += "مقاييس SMS غير متاحة أو أعاد المصدر خطأ"
                }
                .onFailure {
                    sources["sms_metrics"] = "error"
                    errors += "تعذرت قراءة مقاييس SMS"
                }
                .getOrNull()
        }
        if (smsMetrics != null && !smsMetrics.has("error")) {
            jsonObjectToMap(smsMetrics).forEach { (key, value) -> metricValues["sms." + key] = value }
        }

        val recentLogs = if (databaseAvailable) {
            runCatching { database.getActivityLogs(10) }
                .onSuccess { sources["activity_logs"] = "available" }
                .onFailure {
                    sources["activity_logs"] = "error"
                    errors += "تعذرت قراءة أحدث سجلات النشاط"
                }
                .getOrNull()
        } else {
            sources["activity_logs"] = "unavailable"
            null
        }

        if (serviceStatistics != null && !serviceStatistics.has("error")) {
            jsonObjectToMap(serviceStatistics).forEach { (key, value) -> metricValues["service." + key] = value }
        }

        val isRunning = if (service != null) {
            runCatching { service.isServiceRunning() }
                .onFailure { errors += "تعذر التحقق من تشغيل خدمة SMS" }
                .getOrDefault(false)
        } else false
        val serviceHealthStatus = when {
            service == null || serviceStatus == null -> "UNAVAILABLE"
            !isRunning -> "STOPPED"
            !runCatching { service.isHealthy() }.getOrDefault(false) -> "UNHEALTHY"
            !databaseAvailable || databaseIntegrityOk != true -> "UNHEALTHY"
            else -> "HEALTHY"
        }
        val uptimeSeconds = if (isRunning && serviceStatus?.has("uptime_seconds") == true) {
            serviceStatus.optLong("uptime_seconds", -1L).takeIf { it >= 0L }
        } else null

        val memoryUsage = runCatching { readProcessMemoryUsage() }
            .onSuccess { sources["process_memory"] = "available" }
            .onFailure {
                sources["process_memory"] = "error"
                errors += "تعذرت قراءة استهلاك ذاكرة عملية التطبيق"
            }
            .getOrDefault("غير متاح")

        val cpuUsage = runCatching { readProcessCpuUsage() }
            .onSuccess { sources["process_cpu"] = "available" }
            .onFailure {
                sources["process_cpu"] = "error"
                errors += "تعذرت قراءة استهلاك CPU لعملية التطبيق"
            }
            .getOrDefault("غير متاح")

        val batteryLevel = runCatching { readBatteryLevel() }
            .onSuccess { sources["battery"] = if (it in 0..100) "available" else "unavailable" }
            .onFailure {
                sources["battery"] = "error"
                errors += "تعذرت قراءة مستوى البطارية"
            }
            .getOrDefault(-1)
        if (batteryLevel !in 0..100 && sources["battery"] == "unavailable") {
            errors += "مستوى البطارية غير متاح من Android"
        }

        val logLines = jsonArrayToLines(recentLogs)
        val currentState = serviceStatus?.optString("health_status")?.takeIf { it.isNotBlank() }
            ?: if (service == null) "UNAVAILABLE" else "UNKNOWN"
        val activeMode = serviceStatus?.optString("mode")?.takeIf { it.isNotBlank() }
        val serviceErrorCount = if (serviceStatus != null && serviceStatus.has("error_count") && !serviceStatus.isNull("error_count")) {
            serviceStatus.optInt("error_count", 0).coerceAtLeast(0)
        } else null
        val lastError = serviceStatus?.optString("last_error")?.takeIf { it.isNotBlank() }

        stateFlow.value = SettingsMonitoringState(
            serviceRunning = isRunning,
            serviceHealthy = serviceHealthStatus == "HEALTHY",
            serviceHealthStatus = serviceHealthStatus,
            currentStartupState = currentState,
            // SMSService actually exposes current mode, not a completed/active pipeline phase.
            activePhase = activeMode,
            completedPhases = emptyList(),
            failedPhases = emptyList(),
            eventsCount = logLines.size,
            metrics = metricValues,
            sourceStatus = sources,
            collectionErrors = errors.distinct(),
            logs = logLines,
            lastError = lastError,
            serviceErrorCount = serviceErrorCount,
            uptime = uptimeSeconds?.let(::formatDuration) ?: "غير متاح — الخدمة متوقفة أو وقت التشغيل غير متاح",
            memoryUsage = memoryUsage,
            cpuUsage = cpuUsage,
            batteryLevel = batteryLevel,
            databaseAvailable = databaseAvailable,
            databaseIntegrityOk = databaseIntegrityOk,
            capturedAtMillis = System.currentTimeMillis()
        )
    }

    override suspend fun clearLogs(): Int = withContext(Dispatchers.IO) {
        val retentionDays = database.getSetting("keepLogsDays").toIntOrNull()?.coerceIn(1, 3650) ?: 30
        val deletedCount = database.cleanupActivityLogs(retentionDays)
        refresh()
        deletedCount
    }

    override suspend fun clearMetrics(): Int = withContext(Dispatchers.IO) {
        val retentionDays = database.getSetting("metricsRetentionDays").toIntOrNull()?.coerceIn(1, 3650) ?: 90
        val deletedCount = database.cleanupOldMetrics(retentionDays)
        refresh()
        deletedCount
    }

    private fun readProcessMemoryUsage(): String {
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: throw IllegalStateException("ActivityManager غير متاح")
        val memoryInfo = manager.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()
            ?: throw IllegalStateException("تعذر قراءة ذاكرة عملية التطبيق")
        return "%.1f MB".format(Locale.US, memoryInfo.totalPss / 1024.0)
    }

    private suspend fun readProcessCpuUsage(): String {
        val cpuBefore = Process.getElapsedCpuTime()
        val wallBefore = SystemClock.elapsedRealtime()
        delay(250L)
        val cpuElapsed = (Process.getElapsedCpuTime() - cpuBefore).coerceAtLeast(0L)
        val wallElapsed = (SystemClock.elapsedRealtime() - wallBefore).coerceAtLeast(1L)
        val maxPercent = Runtime.getRuntime().availableProcessors().coerceAtLeast(1) * 100.0
        val percent = (cpuElapsed.toDouble() * 100.0 / wallElapsed.toDouble()).coerceIn(0.0, maxPercent)
        return "%.1f%%".format(Locale.US, percent)
    }

    private fun readBatteryLevel(): Int {
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return -1
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return -1
        return ((level * 100.0) / scale).toInt().coerceIn(0, 100)
    }

    private fun jsonObjectToMap(value: JSONObject?): Map<String, Any> {
        if (value == null) return emptyMap()
        val result = linkedMapOf<String, Any>()
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = value.opt(key)
            if (item != null && item != JSONObject.NULL && key != "error") result[key] = item
        }
        return result
    }

    private fun jsonArrayToLines(value: JSONArray?): List<String> {
        if (value == null) return emptyList()
        return buildList {
            for (index in 0 until value.length()) {
                val item = value.opt(index)
                if (item != null && item != JSONObject.NULL) add(item.toString())
            }
        }
    }

    private fun formatDuration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        val remainder = safe % 60
        return "%02d:%02d:%02d".format(Locale.US, hours, minutes, remainder)
    }
}
