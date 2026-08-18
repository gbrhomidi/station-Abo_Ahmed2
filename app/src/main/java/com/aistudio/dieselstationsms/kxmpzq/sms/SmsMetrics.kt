package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.ContentValues
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**

* ═══════════════════════════════════════════════════════════════

* مقاييس الأداء - SmsMetrics

* Production Version

* ═══════════════════════════════════════════════════════════════

* 

* المسؤوليات:

* 1. تتبع عدد الرسائل المستلمة.

* 2. تتبع عدد الرسائل المرفوضة.

* 3. تتبع عدد الرسائل المكررة.

* 4. تتبع عدد الرسائل المشبوهة.

* 5. تتبع عدد الرسائل المعالجة.

* 6. تتبع عدد الرسائل الفاشلة.

* 7. تتبع الرسائل المحظورة والتحذيرات.

* 8. تتبع OTP ونتائج الطلبات.

* 9. تسجيل أخطاء النظام الحرجة.

* 10. تسجيل مؤشرات الأداء.

* 11. توليد تقارير فعلية من قاعدة البيانات.

* 

* ملاحظات معمارية:

* - هذا الملف لا ينشئ بيانات تجريبية.

* - جميع الإحصاءات مبنية على sms_metrics الفعلي.

* - لا يحتوي على منطق العملاء أو الطلبات أو الأسعار.

* - لا يحتوي على قواعد تحويل الكميات.

* - يجب ألا يؤدي فشل تسجيل Metric إلى إسقاط مسار SMS الأساسي.
    */
    class SmsMetrics(private val db: DatabaseHelper) {
  
  companion object {
  private const val TAG = "SmsMetrics"
  private const val METRICS_TABLE = "sms_metrics"
  
   private const val MAX_DETAILS_LENGTH = 500
 private const val MAX_PHONE_LENGTH = 64

 private const val DEFAULT_REPORT_DAYS = 7
 private const val MAX_REPORT_DAYS = 3650

 private const val DEFAULT_RETENTION_DAYS = 90
 private const val MAX_RETENTION_DAYS = 3650

 private const val DATE_PATTERN = "yyyy-MM-dd"

 private val DATE_LOCALE = Locale.US

 /**
  * أنواع الأحداث المعتمدة.
  *
  * لا تحذف أو تعيد تسمية أي قيمة لأن أسماءها جزء
  * من عقد التكامل مع بقية منظومة SMS.
  */
 private val ALL_EVENT_TYPES = EventType.values()
  
  }
  
  /**
  
  * أنواع الأحداث التي يمكن تسجيلها.
    */
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
  
  // ═══════════════════════════════════════════════════════════════
  // Date helpers
  // ═══════════════════════════════════════════════════════════════
  
  /**
  
  * تنسيق التاريخ بشكل ثابت ومستقل عن لغة واجهة الجهاز.
  * 
  * لا نستخدم Locale("ar") هنا لأن قيمة التاريخ المخزنة في
  * قاعدة البيانات يجب أن تبقى ASCII ثابتة:
  * 
  * yyyy-MM-dd
    */
    private fun formatDate(timestamp: Long): String {
    return SimpleDateFormat(
    DATE_PATTERN,
    DATE_LOCALE
    ).format(Date(timestamp))
    }
  
  private fun todayDate(): String {
  return formatDate(System.currentTimeMillis())
  }
  
  private fun dateDaysAgo(days: Int): String {
  val calendar = Calendar.getInstance()
  
   calendar.timeInMillis =
     System.currentTimeMillis()

 calendar.add(
     Calendar.DAY_OF_YEAR,
     -days
 )

 return formatDate(calendar.timeInMillis)
  
  }
  
  // ═══════════════════════════════════════════════════════════════
  // Input helpers
  // ═══════════════════════════════════════════════════════════════
  
  private fun safeDays(
  days: Int,
  defaultValue: Int,
  maxValue: Int
  ): Int {
  return when {
  days <= 0 -> defaultValue
  days > maxValue -> maxValue
  else -> days
  }
  }
  
  private fun sanitizePhone(
  phone: String
  ): String {
  return phone
  .trim()
  .take(MAX_PHONE_LENGTH)
  }
  
  private fun sanitizeDetails(
  details: String
  ): String {
  return details
  .trim()
  .take(MAX_DETAILS_LENGTH)
  }
  
  // ═══════════════════════════════════════════════════════════════
  // Record event
  // ═══════════════════════════════════════════════════════════════
  
  /**
  
  * تسجيل حدث فعلي في قاعدة البيانات.
  
  * 
  
  * هذه الدالة متعمدة على ألا ترمي Exception إلى المستدعي
  
  * في حالة فشل تسجيل Metric، لأن Metrics طبقة مراقبة ولا
  
  * ينبغي أن تتسبب في تعطيل معالجة SMS الأساسية.
    */
    suspend fun recordEvent(
    eventType: EventType,
    phone: String = "",
    details: String = ""
    ) = withContext(Dispatchers.IO) {
    
    try {
    
     val now =
     System.currentTimeMillis()

 val values =
     ContentValues().apply {
         put(
             "event_type",
             eventType.name
         )

         put(
             "phone",
             sanitizePhone(phone)
         )

         put(
             "details",
             sanitizeDetails(details)
         )

         put(
             "timestamp",
             now
         )

         put(
             "date",
             formatDate(now)
         )
     }

 val rowId =
     db.writableDatabase.insert(
         METRICS_TABLE,
         null,
         values
     )

 if (rowId == -1L) {
     Log.w(
         TAG,
         "Failed to insert metric event=${eventType.name}"
     )
 }
    
    } catch (e: Exception) {
    
     /*
  * لا نسمح لطبقة Metrics بإسقاط مسار SMS.
  */
 Log.e(
     TAG,
     "Failed to record metric event=${eventType.name}: ${e.javaClass.simpleName}"
 )
    
    }
    }
  
  // ═══════════════════════════════════════════════════════════════
  // Today statistics
  // ═══════════════════════════════════════════════════════════════
  
  /**
  
  * إحصائيات اليوم.
  
  * 
  
  * يتم تنفيذ استعلام واحد فقط بدل استعلام مستقل لكل EventType.
    */
    suspend fun getTodayStats(): Map<String, Int> =
    withContext(Dispatchers.IO) {
    
     try {

     val today =
         todayDate()

     val stats =
         createEmptyStatsMap()

     val cursor =
         db.readableDatabase.rawQuery(
             """
             SELECT event_type, COUNT(*) AS event_count
             FROM $METRICS_TABLE
             WHERE date = ?
             GROUP BY event_type
             """.trimIndent(),
             arrayOf(today)
         )

     cursor.use {

         while (it.moveToNext()) {

             val eventType =
                 it.getString(0)
                     ?: continue

             val count =
                 it.getInt(1)

             stats[
                 eventType.lowercase(Locale.US)
             ] = count
         }
     }

     stats

 } catch (e: Exception) {

     Log.e(
         TAG,
         "Failed to get today stats: ${e.javaClass.simpleName}"
     )

     createEmptyStatsMap()
 }
    
    }
  
  // ═══════════════════════════════════════════════════════════════
  // Period statistics
  // ═══════════════════════════════════════════════════════════════
  
  /**
  
  * إحصائيات فترة زمنية.
  
  * 
  
  * days = 1 يعني اليوم الحالي وما يطابق تاريخ اليوم.
  
  * 
  
  * يتم الحفاظ على جميع أنواع الأحداث في النتيجة حتى عندما
  
  * يكون عددها صفرًا.
    */
    suspend fun getStatsForPeriod(
    days: Int
    ): Map<String, Int> =
    withContext(Dispatchers.IO) {
    
     val safePeriod =
     safeDays(
         days = days,
         defaultValue = DEFAULT_REPORT_DAYS,
         maxValue = MAX_REPORT_DAYS
     )

 try {

     val startDate =
         dateDaysAgo(
             safePeriod
         )

     val stats =
         createEmptyStatsMap()

     val cursor =
         db.readableDatabase.rawQuery(
             """
             SELECT event_type, COUNT(*) AS event_count
             FROM $METRICS_TABLE
             WHERE date >= ?
             GROUP BY event_type
             """.trimIndent(),
             arrayOf(startDate)
         )

     cursor.use {

         while (it.moveToNext()) {

             val eventType =
                 it.getString(0)
                     ?: continue

             val count =
                 it.getInt(1)

             stats[
                 eventType.lowercase(Locale.US)
             ] = count
         }
     }

     stats

 } catch (e: Exception) {

     Log.e(
         TAG,
         "Failed to get stats for period: ${e.javaClass.simpleName}"
     )

     createEmptyStatsMap()
 }
    
    }
  
  // ═══════════════════════════════════════════════════════════════
  // Statistics map
  // ═══════════════════════════════════════════════════════════════
  
  /**
  
  * إنشاء خريطة إحصاءات كاملة بكل أنواع الأحداث.
    */
    private fun createEmptyStatsMap(): MutableMap<String, Int> {
    
    val stats =
    linkedMapOf<String, Int>()
    
    ALL_EVENT_TYPES.forEach { eventType ->
    stats[
    eventType.name.lowercase(Locale.US)
    ] = 0
    }
    
    return stats
    }
  
  // ═══════════════════════════════════════════════════════════════
  // Report generation
  // ═══════════════════════════════════════════════════════════════
  
  /**
  
  * توليد تقرير SMS فعلي من قاعدة البيانات.
    */
    suspend fun generateReport(
    days: Int = DEFAULT_REPORT_DAYS
    ): String = withContext(Dispatchers.IO) {
    
    val safePeriod =
    safeDays(
    days = days,
    defaultValue = DEFAULT_REPORT_DAYS,
    maxValue = MAX_REPORT_DAYS
    )
    
    val stats =
    getStatsForPeriod(
    safePeriod
    )
    
    val sb =
    StringBuilder()
    
    sb.appendLine(
    "📊 تقرير SMS - آخر $safePeriod أيام"
    )
    
    sb.appendLine(
    "═══════════════════"
    )
    
    sb.appendLine(
    "📥 مستلمة: ${stats["sms_received"] ?: 0}"
    )
    
    sb.appendLine(
    "❌ مرفوضة: ${stats["sms_rejected"] ?: 0}"
    )
    
    sb.appendLine(
    "🔄 مكررة: ${stats["sms_duplicated"] ?: 0}"
    )
    
    sb.appendLine(
    "🚨 مشبوهة: ${stats["sms_spoofed"] ?: 0}"
    )
    
    sb.appendLine(
    "✅ معالجة: ${stats["sms_processed"] ?: 0}"
    )
    
    sb.appendLine(
    "❌ فاشلة: ${stats["sms_failed"] ?: 0}"
    )
    
    sb.appendLine(
    "🚫 محظورة: ${stats["sms_blocked"] ?: 0}"
    )
    
    sb.appendLine(
    "⚠️ تحذيرات: ${stats["sms_warning"] ?: 0}"
    )
    
    sb.appendLine(
    "🔐 OTP: ${stats["otp_sent"] ?: 0}"
    )
    
    sb.appendLine(
    "📦 طلبات مؤكدة: ${stats["order_confirmed"] ?: 0}"
    )
    
    sb.appendLine(
    "❌ طلبات ملغاة: ${stats["order_cancelled"] ?: 0}"
    )
    
    sb.appendLine(
    "🚨 أخطاء حرجة: ${stats["critical_error"] ?: 0}"
    )
    
    sb.appendLine(
    "⚙️ أداء: ${stats["performance"] ?: 0}"
    )
    
    sb.appendLine(
    "═══════════════════"
    )
    
    sb.toString()
    }
  
  // ═══════════════════════════════════════════════════════════════
  // Cleanup
  // ═══════════════════════════════════════════════════════════════
  
  /**
  
  * حذف السجلات القديمة.
  
  * 
  
  * لا نحذف السجلات الحالية أو المستقبلية.
    */
    suspend fun cleanupOldMetrics(
    retentionDays: Int
    ) = withContext(Dispatchers.IO) {
    
    val safeRetention =
    safeDays(
    days = retentionDays,
    defaultValue = DEFAULT_RETENTION_DAYS,
    maxValue = MAX_RETENTION_DAYS
    )
    
    try {
    
     val cutoffDate =
     dateDaysAgo(
         safeRetention
     )

 val deleted =
     db.writableDatabase.delete(
         METRICS_TABLE,
         "date < ?",
         arrayOf(cutoffDate)
     )

 Log.d(
     TAG,
     "Cleaned up $deleted old metrics records"
 )
    
    } catch (e: Exception) {
    
     Log.e(
     TAG,
     "Failed to cleanup old metrics: ${e.javaClass.simpleName}"
 )
    
    }
    }
  
  // ═══════════════════════════════════════════════════════════════
  // Synchronization
  // ═══════════════════════════════════════════════════════════════
  
  /**
  
  * مزامنة المقاييس.
  
  * 
  
  * لا توجد خدمة خارجية هنا.
  
  * السجل المحلي في SQLite هو مصدر الحقيقة.
    */
    suspend fun sync() = withContext(Dispatchers.IO) {
    
    try {
    
     /*
  * لا نقوم بإنشاء أحداث وهمية.
  * يتم فقط التأكد من أن العملية قابلة للتنفيذ.
  */
 cleanupExpiredOperationalData()

 Log.d(
     TAG,
     "Metrics synced successfully"
 )
    
    } catch (e: Exception) {
    
     Log.e(
     TAG,
     "Failed to sync metrics: ${e.javaClass.simpleName}"
 )
    
    }
    }
  
  /**
  
  * تنظيف داخلي خفيف للبيانات التشغيلية غير الصالحة.
  
  * 
  
  * لا يحذف السجل التاريخي العادي.
    */
    private fun cleanupExpiredOperationalData() {
    try {
    
     db.writableDatabase.delete(
     METRICS_TABLE,
     "event_type IS NULL OR event_type = ''",
     null
 )
    
    } catch (e: Exception) {
    
     Log.w(
     TAG,
     "Operational metrics cleanup skipped: ${e.javaClass.simpleName}"
 )
    
    }
    }
  
  // ═══════════════════════════════════════════════════════════════
  // Flush
  // ═══════════════════════════════════════════════════════════════
  
  /**
  
  * Flush للتوافق مع المستدعين الحاليين.
  
  * 
  
  * SQLite يضمن كتابة العملية ضمن transaction الخاصة به،
  
  * لذلك لا نحتاج إلى إنشاء طبقة تخزين وهمية.
    */
    suspend fun flush() = withContext(Dispatchers.IO) {
    
    try {
    
     /*
  * تنفيذ استعلام بسيط لضمان إمكانية الوصول للقاعدة.
  */
 db.readableDatabase.rawQuery(
     "SELECT 1",
     null
 ).use {
     if (it.moveToFirst()) {
         // Database is accessible.
     }
 }

 Log.d(
     TAG,
     "Metrics flushed successfully"
 )
    
    } catch (e: Exception) {
    
     Log.e(
     TAG,
     "Failed to flush metrics: ${e.javaClass.simpleName}"
 )
    
    }
    }
  
  // ═══════════════════════════════════════════════════════════════
  // Performance metrics
  // ═══════════════════════════════════════════════════════════════
  
  /**
  
  * تسجيل مؤشرات الأداء.
  
  * 
  
  * يتم تحويل Map إلى JSON منظم بدل Map.toString()
  
  * حتى يكون details قابلاً للقراءة والمعالجة لاحقًا.
    */
    suspend fun recordPerformanceStats(
    stats: Map<String, Any>
    ) = withContext(Dispatchers.IO) {
    
    try {
    
     val json =
     JSONObject()

 stats.forEach { (key, value) ->

     if (key.isNotBlank()) {
         json.put(
             key,
             value
         )
     }
 }

 val now =
     System.currentTimeMillis()

 val values =
     ContentValues().apply {

         put(
             "event_type",
             EventType.PERFORMANCE.name
         )

         put(
             "phone",
             ""
         )

         put(
             "details",
             json.toString()
                 .take(MAX_DETAILS_LENGTH)
         )

         put(
             "timestamp",
             now
         )

         put(
             "date",
             formatDate(now)
         )
     }

 val rowId =
     db.writableDatabase.insert(
         METRICS_TABLE,
         null,
         values
     )

 if (rowId == -1L) {

     Log.w(
         TAG,
         "Failed to record performance stats"
     )

 } else {

     Log.d(
         TAG,
         "Performance stats recorded"
     )
 }
    
    } catch (e: Exception) {
    
     Log.e(
     TAG,
     "Failed to record performance stats: ${e.javaClass.simpleName}"
 )
    
    }
    }
  
  // ═══════════════════════════════════════════════════════════════
  // Current metrics
  // ═══════════════════════════════════════════════════════════════
  
  /**
  
  * الحصول على مؤشرات اليوم الحالية.
    */
    suspend fun getCurrentMetrics(): JSONObject =
    withContext(Dispatchers.IO) {
    
     try {

     val stats =
         getTodayStats()

     JSONObject().apply {

         stats.forEach { (key, value) ->
             put(
                 key,
                 value
             )
         }

         put(
             "timestamp",
             System.currentTimeMillis()
         )
     }

 } catch (e: Exception) {

     Log.e(
         TAG,
         "Failed to get current metrics: ${e.javaClass.simpleName}"
     )

     JSONObject().apply {
         put(
             "error",
             "metrics_unavailable"
         )

         put(
             "timestamp",
             System.currentTimeMillis()
         )
     }
 }
    
    }
    }