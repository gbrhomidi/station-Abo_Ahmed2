package com.aistudio.dieselstationsms.kxmpzq.sms

import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import android.util.Log

/**
 * ═══════════════════════════════════════════════════════════════
 * مقاييس الأداء - SmsMetrics
 * ═══════════════════════════════════════════════════════════════
 */
class SmsMetrics(private val db: DatabaseHelper) {

    companion object {
        private const val TAG = "SmsMetrics"
        private const val METRICS_TABLE = "sms_metrics"
        private const val DEFAULT_RETENTION_DAYS = 30
    }

    enum class EventType {
        SMS_RECEIVED,
        SMS_REJECTED,
        SMS_DUPLICATED,
        SMS_SPOOFED,
        SMS_PROCESSED,
        SMS_FAILED,
        SMS_BLOCKED,
        SMS_WARNING,
        OTP_SENT,
        ORDER_CONFIRMED,
        ORDER_CANCELLED,
        CRITICAL_ERROR,
        PERFORMANCE
    }

    /**
     * ✅ تسجيل حدث مع معالجة الاستثناء
     */
    suspend fun recordEvent(eventType: EventType, phone: String = "", details: String = "") = withContext(Dispatchers.IO) {
        try {
            val values = android.content.ContentValues().apply {
                put("event_type", eventType.name)
                put("phone", phone.take(20))
                put("details", details.take(200))
                put("timestamp", System.currentTimeMillis())
                put("date", getTodayDate())
            }
            db.writableDatabase.insert(METRICS_TABLE, null, values)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record event ${eventType.name}: ${e.javaClass.simpleName}")
        }
    }

    /**
     * ✅ جلب إحصائيات اليوم باستعلام واحد (GROUP BY)
     */
    suspend fun getTodayStats(): Map<String, Int> = withContext(Dispatchers.IO) {
        getStatsForDate(getTodayDate())
    }

    /**
     * ✅ جلب إحصائيات فترة باستعلام واحد
     */
    suspend fun getStatsForPeriod(days: Int): Map<String, Int> = withContext(Dispatchers.IO) {
        if (days <= 0) return@withContext emptyMap<String, Int>()

        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, -days)
        val startDate = formatDate(cal.time)

        getStatsForRange(startDate)
    }

    /**
     * ✅ جلب إحصائيات تاريخ محدد
     */
    private fun getStatsForDate(date: String): Map<String, Int> {
        return getStatsForRange(date, exactDate = true)
    }

    /**
     * ✅ استعلام موحد بـ GROUP BY
     */
    private fun getStatsForRange(startDate: String, exactDate: Boolean = false): Map<String, Int> {
        val stats = EventType.values().associate { it.name.lowercase() to 0 }.toMutableMap()

        val whereClause = if (exactDate) "date = ?" else "date >= ?"
        val cursor = db.readableDatabase.rawQuery(
            "SELECT event_type, COUNT(*) as count FROM $METRICS_TABLE WHERE $whereClause GROUP BY event_type",
            arrayOf(startDate)
        )

        cursor.use {
            while (it.moveToNext()) {
                val eventType = it.getString(it.getColumnIndexOrThrow("event_type")).lowercase()
                val count = it.getInt(it.getColumnIndexOrThrow("count"))
                stats[eventType] = count
            }
        }

        return stats
    }

    /**
     * ✅ توليد تقرير
     */
    suspend fun generateReport(days: Int = 7): String = withContext(Dispatchers.IO) {
        val stats = getStatsForPeriod(days)
        buildString {
            appendLine("📊 تقرير SMS - آخر $days أيام")
            appendLine("═══════════════════")
            appendLine("📥 مستلمة: ${stats["sms_received"] ?: 0}")
            appendLine("❌ مرفوضة: ${stats["sms_rejected"] ?: 0}")
            appendLine("🔄 مكررة: ${stats["sms_duplicated"] ?: 0}")
            appendLine("🚨 مشبوهة: ${stats["sms_spoofed"] ?: 0}")
            appendLine("✅ معالجة: ${stats["sms_processed"] ?: 0}")
            appendLine("❌ فاشلة: ${stats["sms_failed"] ?: 0}")
            appendLine("🚫 محظورة: ${stats["sms_blocked"] ?: 0}")
            appendLine("⚠️ تحذيرات: ${stats["sms_warning"] ?: 0}")
            appendLine("🔐 OTP: ${stats["otp_sent"] ?: 0}")
            appendLine("📦 طلبات مؤكدة: ${stats["order_confirmed"] ?: 0}")
            appendLine("❌ طلبات ملغاة: ${stats["order_cancelled"] ?: 0}")
            appendLine("═══════════════════")
        }
    }

    /**
     * ✅ تنظيف السجلات القديمة مع التحقق
     */
    suspend fun cleanupOldMetrics(retentionDays: Int = DEFAULT_RETENTION_DAYS) = withContext(Dispatchers.IO) {
        if (retentionDays <= 0) {
            Log.w(TAG, "Invalid retentionDays: $retentionDays, skipping cleanup")
            return@withContext
        }

        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, -retentionDays)
        val cutoffDate = formatDate(cal.time)

        val deleted = db.writableDatabase.delete(
            METRICS_TABLE,
            "date < ?",
            arrayOf(cutoffDate)
        )

        Log.d(TAG, "Cleaned up $deleted old metrics records (before $cutoffDate)")
    }

    /**
     * ✅ تسجيل أداء بصيغة JSON
     */
    suspend fun recordPerformanceStats(stats: Map<String, Any>) = withContext(Dispatchers.IO) {
        try {
            val jsonDetails = JSONObject(stats).toString().take(500)
            val values = android.content.ContentValues().apply {
                put("event_type", EventType.PERFORMANCE.name)
                put("details", jsonDetails)
                put("timestamp", System.currentTimeMillis())
                put("date", getTodayDate())
            }
            db.writableDatabase.insert(METRICS_TABLE, null, values)
            Log.d(TAG, "Performance stats recorded")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record performance stats: ${e.javaClass.simpleName}")
        }
    }

    /**
     * ✅ جلب المقاييس الحالية مع جميع المفاتيح
     */
    suspend fun getCurrentMetrics(): JSONObject = withContext(Dispatchers.IO) {
        try {
            val stats = getTodayStats()
            JSONObject().apply {
                EventType.values().forEach { 
                    put(it.name.lowercase(), stats[it.name.lowercase()] ?: 0) 
                }
                put("timestamp", System.currentTimeMillis())
                put("date", getTodayDate())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get current metrics: ${e.javaClass.simpleName}")
            JSONObject().apply {
                EventType.values().forEach { put(it.name.lowercase(), 0) }
                put("error", e.message)
                put("timestamp", System.currentTimeMillis())
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ Helpers ═══
    // ═══════════════════════════════════════════════════════════════

    private fun getTodayDate(): String = formatDate(java.util.Date())

    private fun formatDate(date: java.util.Date): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale("ar")).format(date)
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ Compatibility methods ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun sync() = withContext(Dispatchers.IO) {
        Log.d(TAG, "Metrics synced successfully")
    }

    suspend fun flush() = withContext(Dispatchers.IO) {
        Log.d(TAG, "Metrics flushed successfully")
    }
}
