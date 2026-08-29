package com.aistudio.dieselstationsms.kxmpzq.sms

/**
 * القيم المسموح بها في sms_logs حسب DatabaseHelper.kt.
 *
 * هذا العقد لا يغير المخطط؛ بل يحصر القيم القادمة من طبقات SMS
 * في مجموعة القيم التي يقبلها الجدول فعلياً.
 */
internal object SmsLogContract {
    const val TYPE_NOTIFICATION = "notification"

    fun normalizeType(raw: String): String {
        return when (raw.trim().lowercase()) {
            "reminder", "notification", "alert", "custom" ->
                raw.trim().lowercase()
            else -> TYPE_NOTIFICATION
        }
    }

    fun normalizeStatus(raw: String): String {
        val value = raw.trim().lowercase()
        return when {
            value.startsWith("queued") -> "queued"
            value.startsWith("sending") -> "sending"
            value.startsWith("sent") -> "sent"
            value.startsWith("delivered") -> "delivered"
            value.startsWith("cancelled") -> "cancelled"
            else -> "failed"
        }
    }
}
