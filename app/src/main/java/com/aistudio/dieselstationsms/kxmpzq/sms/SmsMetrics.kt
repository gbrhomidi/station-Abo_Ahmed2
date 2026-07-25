package com.aistudio.dieselstationsms.kxmpzq.sms

import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ═══════════════════════════════════════════════════════════════
 * مقاييس الأداء - SmsMetrics
 * ═══════════════════════════════════════════════════════════════
 *
 * المهام:
 * 1. تتبع عدد الرسائل المستلمة
 * 2. تتبع عدد الرسائل المرفوضة
 * 3. تتبع عدد الرسائل المكررة
 * 4. تتبع عدد الرسائل المشبوهة
 * 5. تتبع عدد الرسائل المعالجة
 * 6. تتبع عدد الرسائل الفاشلة
 * 7. توليد تقارير
 */
class SmsMetrics(private val db: DatabaseHelper) {

    companion object {
        private const val TAG = "SmsMetrics"
        private const val METRICS_TABLE = "sms_metrics"
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
        ORDER_CANCELLED
    }

    suspend fun recordEvent(eventType: EventType, phone: String = "", details: String = "") = withContext(Dispatchers.IO) {
        val values = android.content.ContentValues().apply {
            put("event_type", eventType.name)
            put("phone", phone)
            put("details", details.take(200))
            put("timestamp", System.currentTimeMillis())
            put("date", java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale("ar")).format(java.util.Date()))
        }

        db.writableDatabase.insert(METRICS_TABLE, null, values)
    }

    suspend fun getTodayStats(): Map<String, Int> = withContext(Dispatchers.IO) {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale("ar")).format(java.util.Date())
        val stats = mutableMapOf<String, Int>()

        for (eventType in EventType.values()) {
            val cursor = db.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM $METRICS_TABLE WHERE event_type = ? AND date = ?",
                arrayOf(eventType.name, today)
            )
            val count = cursor.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
            stats[eventType.name.lowercase()] = count
        }

        stats
    }

    suspend fun getStatsForPeriod(days: Int): Map<String, Int> = withContext(Dispatchers.IO) {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, -days)
        val startDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale("ar")).format(cal.time)
        val stats = mutableMapOf<String, Int>()

        for (eventType in EventType.values()) {
            val cursor = db.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM $METRICS_TABLE WHERE event_type = ? AND date >= ?",
                arrayOf(eventType.name, startDate)
            )
            val count = cursor.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
            stats[eventType.name.lowercase()] = count
        }

        stats
    }

    suspend fun generateReport(days: Int = 7): String = withContext(Dispatchers.IO) {
        val stats = getStatsForPeriod(days)
        val sb = StringBuilder()
        sb.appendLine("📊 تقرير SMS - آخر $days أيام")
        sb.appendLine("═══════════════════")
        sb.appendLine("📥 مستلمة: ${stats["sms_received"] ?: 0}")
        sb.appendLine("❌ مرفوضة: ${stats["sms_rejected"] ?: 0}")
        sb.appendLine("🔄 مكررة: ${stats["sms_duplicated"] ?: 0}")
        sb.appendLine("🚨 مشبوهة: ${stats["sms_spoofed"] ?: 0}")
        sb.appendLine("✅ معالجة: ${stats["sms_processed"] ?: 0}")
        sb.appendLine("❌ فاشلة: ${stats["sms_failed"] ?: 0}")
        sb.appendLine("🚫 محظورة: ${stats["sms_blocked"] ?: 0}")
        sb.appendLine("⚠️ تحذيرات: ${stats["sms_warning"] ?: 0}")
        sb.appendLine("🔐 OTP: ${stats["otp_sent"] ?: 0}")
        sb.appendLine("📦 طلبات مؤكدة: ${stats["order_confirmed"] ?: 0}")
        sb.appendLine("❌ طلبات ملغاة: ${stats["order_cancelled"] ?: 0}")
        sb.appendLine("═══════════════════")
        sb.toString()
    }

    suspend fun cleanupOldMetrics(retentionDays: Int) = withContext(Dispatchers.IO) {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, -retentionDays)
        val cutoffDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale("ar")).format(cal.time)

        val deleted = db.writableDatabase.delete(
            METRICS_TABLE,
            "date < ?",
            arrayOf(cutoffDate)
        )

        android.util.Log.d(TAG, "Cleaned up $deleted old metrics records")
    }
}