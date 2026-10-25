package com.aistudio.dieselstationsms.kxmpzq.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper

/** يستقبل نتائج modem لكل جزء؛ لا ينفذ منطقاً تجارياً. */
class SmsTransportCallbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val messageId = intent.getStringExtra(SmsTransport.EXTRA_MESSAGE_ID) ?: return
        val partIndex = intent.getIntExtra(SmsTransport.EXTRA_PART_INDEX, -1)
        if (partIndex < 0) return

        val action = intent.action
        if (action != SmsTransport.ACTION_SENT && action != SmsTransport.ACTION_DELIVERED) return
        val delivered = action == SmsTransport.ACTION_DELIVERED
        val success = resultCode == Activity.RESULT_OK
        val code = if (success) "OK" else resultCode.toString()
        val reason = if (success) "" else intent.getStringExtra("error")
            ?: when (resultCode) {
                android.telephony.SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "فشل عام في إرسال SMS؛ تحقق من الشبكة ومركز الرسائل (SMSC)."
                android.telephony.SmsManager.RESULT_ERROR_RADIO_OFF -> "راديو الهاتف أو شريحة SIM غير متاحين للإرسال؛ تحقق من تفعيل الشريحة ووضع الطيران وتغطية الشبكة."
                android.telephony.SmsManager.RESULT_ERROR_NULL_PDU -> "تعذر إنشاء حزمة SMS (PDU) من محتوى الرسالة."
                android.telephony.SmsManager.RESULT_ERROR_NO_SERVICE -> "لا توجد خدمة شبكة خلوية متاحة لإرسال SMS."
                else -> "فشل إرسال SMS برمز Android: $resultCode"
            }

        runCatching {
            val db = DatabaseHelper.getInstance(context.applicationContext)
            SmsOutboxRepository.recordPartResult(
                db = db,
                messageId = messageId,
                partIndex = partIndex,
                delivered = delivered,
                success = success,
                resultCode = code,
                reason = reason
            )
            if (!success) {
                SmsFailureNotificationPublisher.publishForMessage(
                    context.applicationContext,
                    db,
                    messageId
                )
            }
        }.onFailure {
            Log.e("SmsTransportCallback", "Failed to persist SMS callback", it)
        }
    }
}
