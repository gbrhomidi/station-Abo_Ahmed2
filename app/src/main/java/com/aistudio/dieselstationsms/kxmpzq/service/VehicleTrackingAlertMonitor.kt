package com.aistudio.dieselstationsms.kxmpzq.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * مراقب خلفي لحالة المركبات يعمل داخل SMSService الموجودة مسبقاً.
 *
 * مصدر الحقيقة الوحيد هو DatabaseHelper.getVehicleTrackingStatus؛ لا توجد
 * إحداثيات أو حالات مصطنعة. يحفظ المفاتيح النشطة في SharedPreferences حتى
 * لا يعيد إصدار الإشعار في كل دورة أو بعد إعادة تشغيل الخدمة لنفس الحالة.
 */
class VehicleTrackingAlertMonitor(
    private val context: Context,
    private val database: DatabaseHelper
) {
    companion object {
        private const val TAG = "VehicleAlertMonitor"
        private const val AUTH_PREFS = "auth_prefs"
        private const val USER_ID_KEY = "user_id"
        private const val STATE_PREFS = "vehicle_tracking_alert_state"
        private const val ACTIVE_KEYS_KEY = "active_keys"
        private const val CHANNEL_ID = "vehicle_tracking_alerts"
        private const val CHANNEL_NAME = "تنبيهات تتبع المركبات"
        private const val GROUP_KEY = "vehicle_tracking_alert_group"
        private const val SUMMARY_ID = 42070
        private const val POLL_INTERVAL_MS = 60_000L
        private const val MAX_NOTIFICATIONS_PER_CYCLE = 8
        private const val STOP_SPEED_KMH = 1.0

        private const val TYPE_STOPPED = "stopped"
        private const val TYPE_CONNECTION_LOST = "connection_lost"
    }

    private data class Alert(
        val key: String,
        val type: String,
        val vehicleId: Long,
        val vehicleName: String,
        val timestamp: String?,
        val ageMinutes: Int?
    )

    private var monitorJob: Job? = null
    private val checking = AtomicBoolean(false)

    fun start(scope: CoroutineScope) {
        if (monitorJob?.isActive == true) return
        ensureNotificationChannel()
        monitorJob = scope.launch {
            while (isActive) {
                checkOnce()
                delay(POLL_INTERVAL_MS)
            }
        }
        Log.i(TAG, "Vehicle alert monitor started; interval=${POLL_INTERVAL_MS}ms")
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        Log.i(TAG, "Vehicle alert monitor stopped")
    }

    private suspend fun checkOnce() {
        if (!checking.compareAndSet(false, true)) return
        try {
            val userId = context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
                .getLong(USER_ID_KEY, 0L)
            if (userId <= 0L) {
                Log.d(TAG, "Skipping vehicle alert check: no authenticated user")
                return
            }

            val stationId = database.getUserById(userId)?.optInt("station_id", 0) ?: 0
            if (stationId <= 0) {
                Log.w(TAG, "Skipping vehicle alert check: user has no valid station")
                return
            }

            val statuses = database.getVehicleTrackingStatus(JSONObject().apply {
                put("station_id", stationId)
                put("max_age_minutes", 15)
                put("limit", 1000)
            })
            val alerts = buildAlerts(statuses)
            val currentKeys = alerts.mapTo(linkedSetOf()) { it.key }
            val previousKeys = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .getStringSet(ACTIVE_KEYS_KEY, emptySet())
                ?.toSet()
                .orEmpty()
            val newAlerts = alerts.filter { it.key !in previousKeys }

            context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(ACTIVE_KEYS_KEY, currentKeys)
                .apply()

            if (newAlerts.isNotEmpty()) {
                publishNotifications(newAlerts.take(MAX_NOTIFICATIONS_PER_CYCLE))
            }
        } catch (error: Exception) {
            Log.w(TAG, "Vehicle alert check failed: ${error.message}")
        } finally {
            checking.set(false)
        }
    }

    private fun buildAlerts(statuses: org.json.JSONArray): List<Alert> {
        val alerts = mutableListOf<Alert>()
        for (index in 0 until statuses.length()) {
            val row = statuses.optJSONObject(index) ?: continue
            val vehicleId = row.optLong("vehicle_id", 0L)
            if (vehicleId <= 0L) continue
            val vehicleName = listOf(
                row.optString("vehicle_name", "").trim(),
                row.optString("vehicle_code", "").trim(),
                row.optString("plate_number", "").trim()
            ).firstOrNull { it.isNotEmpty() } ?: "المركبة #$vehicleId"
            val state = row.optString("connection_state", "none")
            val lastLocationTime = row.optString("last_location_time", "").takeIf { it.isNotBlank() }
            val lastCommunication = row.optString("last_communication", "").takeIf { it.isNotBlank() }
            val ageMinutes = row.optInt("age_minutes", -1).takeIf { it >= 0 }
            val speed = row.optDouble("last_speed", Double.NaN)

            val deviceId = row.optLong("device_id", 0L)
            val deviceStatus = row.optString("device_status", "").trim().lowercase()
            val deviceLost = deviceId > 0L &&
                (deviceStatus != "active" || lastCommunication == null || (ageMinutes != null && ageMinutes > 15))
            if (deviceLost) {
                alerts += Alert(
                    key = "$TYPE_CONNECTION_LOST:$vehicleId",
                    type = TYPE_CONNECTION_LOST,
                    vehicleId = vehicleId,
                    vehicleName = vehicleName,
                    timestamp = lastCommunication ?: lastLocationTime,
                    ageMinutes = ageMinutes
                )
            }

            if ((state == "connected" || state == "recent") &&
                lastLocationTime != null && speed.isFinite() && speed <= STOP_SPEED_KMH
            ) {
                alerts += Alert(
                    key = "$TYPE_STOPPED:$vehicleId",
                    type = TYPE_STOPPED,
                    vehicleId = vehicleId,
                    vehicleName = vehicleName,
                    timestamp = lastLocationTime,
                    ageMinutes = ageMinutes
                )
            }
        }
        return alerts
    }

    private fun publishNotifications(alerts: List<Alert>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS is not granted; vehicle alerts were not published")
            return
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        alerts.forEach { alert ->
            val title = if (alert.type == TYPE_STOPPED) {
                "المركبة متوقفة"
            } else {
                "فقد اتصال المركبة"
            }
            val message = if (alert.type == TYPE_STOPPED) {
                "${alert.vehicleName}: السرعة المسجلة ≤ ${STOP_SPEED_KMH.toInt()} كم/س. آخر GPS: ${alert.timestamp ?: "غير متاح"}."
            } else {
                val age = alert.ageMinutes?.let { "$it دقيقة" } ?: "غير متاح"
                "${alert.vehicleName}: آخر اتصال بجهاز GPS منذ $age."
            }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setGroup(GROUP_KEY)
                .setContentIntent(openTrackingIntent(alert.vehicleId))
                .build()
            manager.notify(notificationId(alert.key), notification)
        }
        if (alerts.size > 1) {
            val summary = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("تنبيهات تتبع المركبات")
                .setContentText("${alerts.size} تنبيهات جديدة للمركبات")
                .setStyle(NotificationCompat.InboxStyle().setSummaryText("افتح التطبيق لمراجعة التفاصيل"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setGroup(GROUP_KEY)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setContentIntent(openTrackingIntent(0L))
                .build()
            manager.notify(SUMMARY_ID, summary)
        }
    }

    private fun notificationId(key: String): Int = (key.hashCode() and 0x7fffffff).coerceAtLeast(1)

    private fun openTrackingIntent(vehicleId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_screen", "vehicle-tracking.html")
            if (vehicleId > 0L) putExtra("vehicle_id", vehicleId)
        }
        val requestCode = notificationId("intent:$vehicleId")
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "إشعارات توقف المركبات وفقد اتصال أجهزة GPS"
                    enableVibration(true)
                }
            )
        }
    }
}
