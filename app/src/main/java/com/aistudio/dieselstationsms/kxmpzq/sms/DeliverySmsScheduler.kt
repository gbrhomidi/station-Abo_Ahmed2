package com.aistudio.dieselstationsms.kxmpzq.sms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.DeliveryFeeAccounting
import com.aistudio.dieselstationsms.kxmpzq.receiver.AlarmReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Scheduling and construction of delivery-task SMS messages.
 *
 * Delivery records remain local SQLite records; the SMS is sent only through the
 * app's existing SMS transport after the authoritative delivery time is resolved.
 */
object DeliverySmsScheduler {
    private const val REQUEST_BASE = 0x4D530000
    private const val ACTION = "com.aistudio.dieselstationsms.kxmpzq.ACTION_DELIVERY_SMS"

    fun buildMessage(payload: JSONObject): String {
        val driver = payload.optString("driver_name").trim().ifBlank { "السائق" }
        val deliveryId = payload.optLong("delivery_id", 0L)
        val isProduct = payload.optString("sale_kind", payload.optString("sale_type", ""))
            .trim().equals("product", ignoreCase = true) ||
            payload.optString("cargo_type", "").contains("منتج", ignoreCase = true)

        val rawFuel = sequenceOf(
            payload.optString("fuel_type_name"),
            payload.optString("fuel_type"),
            payload.optString("fuel_name_ar")
        ).map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
        val fuel = normalizeFuelName(rawFuel)
        val cargo = if (isProduct) "منتجات" else "وقود '$fuel'"
        val rawQuantity = payload.optString("quantity").trim().ifBlank { "—" }
        val quantity = if (isProduct || rawQuantity == "—") rawQuantity else "$rawQuantity ${litreUnit(rawQuantity)}"

        val location = payload.optString("location").trim()
            .ifBlank { payload.optString("delivery_location").trim() }
            .ifBlank { "—" }

        val cashSale = payload.optBoolean("cash_sale", false) ||
            payload.optString("payment_method").trim().lowercase(Locale.ROOT) in setOf("cash", "نقدي", "نقداً")
        val namedCashCustomer = payload.optString("cash_customer_name").trim()
        val customer = if (cashSale && namedCashCustomer.isNotBlank()) {
            namedCashCustomer
        } else {
            payload.optString("customer_display").trim()
                .ifBlank { payload.optString("customer_name").trim() }
                .ifBlank { if (cashSale) "مبيعات نقدية" else "العميل" }
        }
        val customerText = customer

        val fee = payload.optDouble("delivery_service_fee", payload.optDouble("service_fee", 0.0)).coerceAtLeast(0.0)
        val feeMode = normalizeFeeMode(payload.optString("delivery_fee_mode", payload.optString("fee_mode", "exempt")))

        return buildString {
            append("مرحباً - ").append(driver).append('\n')
            append("لديك مهمة توصيل جديدة رقمها - ").append(deliveryId).append('\n')
            append("نوع الحمولة - ").append(cargo).append('\n')
            append("حجم الحمولة - ").append(quantity).append('\n')
            append("للعميل - ").append(customerText).append('\n')
            append("موقع التوصيل - ").append(location)
            // The driver collects the service fee only when it is explicitly assigned
            // to the driver; other fee modes must not add collection instructions.
            if (fee > 0.0 && feeMode == "driver") {
                val symbols = DecimalFormatSymbols(Locale.US)
                val formatted = DecimalFormat("0.##", symbols).format(fee)
                append('\n').append("قم باستلام رسوم خدمة التوصيل - \"")
                    .append(formatted).append(" ريال\" من العميل عند التوصيل، وسيتم احتسابها عهدة عليك حتى تقوم بتوريدها.")
            }
        }
    }

    private fun normalizeFuelName(value: String): String {
        val v = value.trim().lowercase(Locale.ROOT)
        return when {
            v.contains("ديزل") || v.contains("diesel") || v.contains("gasoil") -> "ديزل"
            v.contains("بنزين") || v.contains("petrol") || v.contains("gasoline") || v.contains("gasoline") -> "بنزين"
            v.isBlank() -> "غير محدد"
            else -> value.trim()
        }
    }

    private fun litreUnit(quantity: String): String {
        val number = quantity.trim().replace(',', '.').toDoubleOrNull() ?: return "لترات"
        return if (kotlin.math.abs(number - 1.0) < 0.000001) "لتر" else "لترات"
    }

    private fun normalizeFeeMode(value: String): String {
        val v = value.trim().lowercase(Locale.ROOT)
        return when (v) {
            "add_to_sales", "added_to_sales", "sales", "تضاف لقيمة المبيعات", "تُضاف لقيمة المبيعات" -> "add_to_sales"
            "driver", "on_driver", "driver_account", "على حساب السائق" -> "driver"
            "deduct_from_sales", "subtract_from_sales", "deduct", "تُنقص من المبيعات", "تنقص من المبيعات" -> "deduct_from_sales"
            else -> "exempt"
        }
    }

    /**
     * Four-argument overload is used by the feature branch's save bridge; the optional
     * time keeps compatibility with callers that only need the database-defined time.
     */
    fun schedule(context: Context, deliveryId: Long, stationId: Int, scheduledAtOverride: Long? = null): Long {
        require(deliveryId > 0L && stationId > 0) { "معرف التوصيل والمحطة غير صالحين" }
        val db = DatabaseHelper.getInstance(context)
        val sendAt = scheduledAtOverride?.takeIf { it > 0L } ?: db.getDeliverySmsScheduledAt(deliveryId, stationId)
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
            val payload = DeliveryFeeAccounting.enrichDeliverySmsPayload(
                db,
                deliveryId,
                stationId,
                db.getDeliverySmsPayload(deliveryId, stationId)
            )
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
