package com.aistudio.dieselstationsms.kxmpzq.settings.monitoring

import android.content.Context
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.service.SMSService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** مراقبة حقيقية تعتمد على حالة الخدمة وSQLite، بلا أرقام تجميلية. */
class SettingsMonitoringRepositoryImpl(
    context: Context,
    private val database: DatabaseHelper = DatabaseHelper.getInstance(context.applicationContext)
) : SettingsMonitoringRepository {

    private val _state = MutableStateFlow(SettingsMonitoringState())

    override fun observeMonitoring(): Flow<SettingsMonitoringState> = _state.asStateFlow()

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        val service = SMSService.getInstance()
        val serviceStatus = runCatching { service?.getServiceStatus() }.getOrNull()
        val serviceStatistics = runCatching { service?.getServiceStatistics() }.getOrNull()
        val dbMetrics = runCatching { database.getCurrentMetrics() }.getOrNull()
        val smsMetrics = runCatching { service?.getCurrentMetrics() }.getOrNull()
        val logs = runCatching { database.getActivityLogs(10) }.getOrNull()

        val running = service?.isServiceRunning() == true
        val healthy = service?.isHealthy() == true && database.isOpen() && database.checkIntegrity()
        val uptime = serviceStatus?.optLong("uptime_seconds", 0L) ?: 0L
        val metrics = linkedMapOf<String, Any>().apply {
            putAll(jsonObjectToMap(dbMetrics))
            putAll(jsonObjectToMap(smsMetrics))
            serviceStatistics?.let { putAll(jsonObjectToMap(it)) }
        }

        _state.value = SettingsMonitoringState(
            serviceRunning = running,
            serviceHealthy = healthy,
            currentStartupState = serviceStatus?.optString("health_status", "UNKNOWN") ?: "UNAVAILABLE",
            activePhase = serviceStatus?.optString("mode")?.takeIf { it.isNotBlank() },
            completedPhases = emptyList(),
            failedPhases = if (serviceStatus?.optInt("error_count", 0) ?: 0 > 0) listOf("SMS") else emptyList(),
            eventsCount = logs?.length() ?: 0,
            metrics = metrics,
            logs = jsonArrayToLines(logs),
            lastError = serviceStatus?.optString("last_error")?.takeIf { it.isNotBlank() },
            uptime = formatDuration(uptime),
            memoryUsage = "غير متاح من Android bridge",
            cpuUsage = "غير متاح من Android bridge",
            batteryLevel = -1
        )
    }

    override suspend fun clearLogs() = withContext(Dispatchers.IO) {
        database.cleanupActivityLogs(1)
        refresh()
    }

    override suspend fun clearMetrics() = withContext(Dispatchers.IO) {
        database.cleanupOldMetrics(1)
        refresh()
    }

    private fun jsonObjectToMap(value: JSONObject?): Map<String, Any> {
        if (value == null) return emptyMap()
        val result = linkedMapOf<String, Any>()
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = value.opt(key)
            if (item != null && item != JSONObject.NULL) result[key] = item
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
        return "%02d:%02d:%02d".format(hours, minutes, remainder)
    }
}
