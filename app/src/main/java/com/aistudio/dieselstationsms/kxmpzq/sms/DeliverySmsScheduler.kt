package com.aistudio.dieselstationsms.kxmpzq.sms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.receiver.AlarmReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * جدولة رسائل مهام التوصيل.
 *
 * قاعدة الجدولة:
 * - إذا كان وقت التوصيل أبعد من 30 دقيقة: الإرسال عند (موعد التوصيل - 30 دقيقة).
 * - إذا كان موعد التوصيل خلال 30 دقيقة أو قد حان/تجاوز: الإرسال فوراً.
 *
 * لا توجد نافذة ثابتة تمنع التوصيلات القريبة.
 */
object DeliverySmsScheduler {
    private const val REQUEST_BASE = 0x4D530000
    private const val ACTION = "com.aistudio.dieselstationsms.kxmpzq.ACTION_DELIVERY_SMS"

    fun buildMessage(payload: JSONObject): String {
        val driver = payload.optString("driver_name").trim().ifBlank { "السائق" }
        val deliveryId = payload.optLong("delivery_id", 0L)
        val fuelType = payload.optString("fuel_type_name").trim().ifBlank { "وقود" }
        val quantity = payload.optString("quantity").trim().ifBlank { "—" }
        val location = payload.optString("location").trim()
            .ifBlank { payload.optString("delivery_location").trim() }
            .ifBlank { "—" }

        val cashSale = payload.optBoolean("cash_sale", false)
        val customer = if (cashSale) {
            "مبيعات نقدية"
        } else {
            payload.optString("customer_display").trim()
                .ifBlank { payload.optString("customer_name").trim() }
                .ifBlank { "العميل" }
        }
        val note = payload.optString("sms_note").trim()
        val customerText = if (cashSale && note.isNotBlank()) "$customer/$note" else customer

        return buildString {
            append("مرحباً - ").append(driver).append('\n')
            append("لديك مهمة توصيل جديدة رقمها - ").append(deliveryId).append('\n')
            append("نوع الحمولة - ").append(fuelType).append('\n')
            append("حجم الحمولة - ").append(quantity).append('\n')
            append("للعميل - ").append(customerText).append('\n')
            append("موقع التوصيل - ").append(location)
        }
    }

    fun schedule(context: Context, deliveryId: Long, stationId: Int): Long {
        require(deliveryId > 0L && stationId > 0) { "معرف التوصيل والمحطة غير صالحين" }
        val db = DatabaseHelper.getInstance(context)
        val sendAt = db.getDeliverySmsScheduledAt(deliveryId, stationId)
        val alarmAt = sendAt.coerceAtLeast(System.currentTimeMillis() + 1000L)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION
            putExtra("task_type", "delivery_sms")
            putExtra("delivery_id", deliveryId)
            putExtra("station_id", stationId)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_BASE + (deliveryId and 0x00FFFFFF).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmAt, pending)
                } catch (_: SecurityException) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmAt, pending)
                }
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, alarmAt, pending)
            }
        } catch (_: Exception) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, alarmAt, pending)
        }
        return alarmAt
    }

    fun cancel(context: Context, deliveryId: Long) {
        if (deliveryId <= 0L) return
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION
            putExtra("task_type", "delivery_sms")
            putExtra("delivery_id", deliveryId)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_BASE + (deliveryId and 0x00FFFFFF).toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        pending?.let {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(it)
            it.cancel()
        }
    }

    suspend fun sendNow(context: Context, deliveryId: Long, stationId: Int): Boolean =
        withContext(Dispatchers.IO) {
            val db = DatabaseHelper.getInstance(context)
            val payload = db.getDeliverySmsPayload(deliveryId, stationId)
            val phone = payload.optString("driver_phone").trim()
            require(phone.isNotBlank()) { "رقم هاتف السائق غير موجود" }
            val sent = SmsReplyManager(context.applicationContext, db).sendReplyOnce(
                phone = phone,
                message = buildMessage(payload),
                dedupeKey = "delivery-task-$stationId-$deliveryId"
            )
            db.markDeliverySmsAttempt(
                deliveryId,
                stationId,
                sent,
                if (sent) null else "تعذر إرسال رسالة مهمة التوصيل"
            )
            sent
        }
}
