package com.aistudio.dieselstationsms.kxmpzq

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.aistudio.dieselstationsms.kxmpzq.sms.SmsReplyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * جدولة وإرسال رسائل مهام التوصيل قبل موعدها بثلاثين دقيقة.
 * تعتمد على بيانات التوصيل الفعلية في SQLite وعلى SmsReplyManager لمنع التكرار.
 */
class DeliverySmsScheduler : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val deliveryId = intent.getLongExtra(EXTRA_DELIVERY_ID, 0L)
        val stationId = intent.getIntExtra(EXTRA_STATION_ID, 0)
        if (deliveryId <= 0L || stationId <= 0) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val db = DatabaseHelper.getInstance(context.applicationContext)
                val payload = db.getDeliverySmsPayload(deliveryId, stationId)
                val phone = payload.optString("driver_phone").trim()
                if (phone.isBlank()) throw IllegalStateException("رقم هاتف السائق غير موجود")
                val message = buildMessage(payload)
                val sent = SmsReplyManager(context.applicationContext, db).sendReplyOnce(
                    phone = phone,
                    message = message,
                    dedupeKey = "delivery-task-$stationId-$deliveryId"
                )
                db.markDeliverySmsAttempt(deliveryId, stationId, sent, if (sent) null else "تعذر إرسال رسالة مهمة التوصيل")
            } catch (e: Exception) {
                try {
                    DatabaseHelper.getInstance(context.applicationContext).markDeliverySmsAttempt(deliveryId, stationId, false, e.message ?: "فشل إرسال رسالة مهمة التوصيل")
                } catch (_: Exception) { }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val EXTRA_DELIVERY_ID = "delivery_id"
        private const val EXTRA_STATION_ID = "station_id"

        /** ينشئ أو يعيد جدولة منبه المهمة قبل موعد التوصيل بثلاثين دقيقة. */
        fun schedule(context: Context, deliveryId: Long, stationId: Int, scheduledAt: Long) {
            if (deliveryId <= 0L || stationId <= 0 || scheduledAt <= 0L) return
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, DeliverySmsScheduler::class.java).apply {
                putExtra(EXTRA_DELIVERY_ID, deliveryId)
                putExtra(EXTRA_STATION_ID, stationId)
            }
            val requestCode = deliveryId.hashCode()
            val pending = PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val trigger = scheduledAt.coerceAtLeast(System.currentTimeMillis() + 1000L)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
                else alarmManager.setExact(AlarmManager.RTC_WAKEUP, trigger, pending)
            } catch (_: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
                else alarmManager.set(AlarmManager.RTC_WAKEUP, trigger, pending)
            }
        }

        /** يلغي المنبه المرتبط بتوصيل محدد عند إلغاء المهمة. */
        fun cancel(context: Context, deliveryId: Long, stationId: Int) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, DeliverySmsScheduler::class.java).apply {
                putExtra(EXTRA_DELIVERY_ID, deliveryId)
                putExtra(EXTRA_STATION_ID, stationId)
            }
            val pending = PendingIntent.getBroadcast(
                context, deliveryId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pending)
            pending.cancel()
        }

        /** يبني نص الرسالة المطلوب من الحقول الحقيقية للتوصيل. */
        fun buildMessage(payload: org.json.JSONObject): String {
            val driver = payload.optString("driver_name", "السائق")
            val type = payload.optString("payload_type", "وقود")
            val fuelType = payload.optString("fuel_type").trim()
            val typeText = if (type == "وقود" && fuelType.isNotBlank()) "$type - $fuelType" else type
            val quantity = payload.optDouble("quantity", 0.0).toString().trimEnd('0').trimEnd('.')
            val customer = payload.optString("customer_name", "العميل")
            val location = payload.optString("location", "غير محدد")
            val fee = payload.optDouble("service_fee", 0.0).toString().trimEnd('0').trimEnd('.')
            return "مرحباً - \"$driver\"\n" +
                "لديك مهمة توصيل جديدة رقمها - \"${payload.optLong("delivery_id")}\"\n" +
                "نوع الحمولة - $typeText\n" +
                "حجم الحمولة - \"$quantity\"\n" +
                "للعميل - \"$customer\"\n" +
                "موقع التوصيل - \"$location\"\n" +
                "قم باستلام - \"$fee\" من العميل،"
        }
    }
}
