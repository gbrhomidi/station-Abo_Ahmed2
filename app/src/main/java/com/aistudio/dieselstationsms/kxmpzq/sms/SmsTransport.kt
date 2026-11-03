package com.aistudio.dieselstationsms.kxmpzq.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import java.util.ArrayList

/** عقد النقل الصادر؛ يسمح للاختبارات باستبدال مودم SMS دون استبدال قاعدة البيانات أو المستودع. */
fun interface SmsTransportSender {
    fun send(job: SmsOutboxRepository.OutboxMessage)
}

/**
 * Resolve a requested SIM without silently downgrading an explicit subscription to the
 * device default. A missing/invalid selection uses the default; a selected SIM is authoritative.
 */
internal fun <T> resolveSmsManagerRoute(
    subscriptionId: Int?,
    defaultManager: () -> T,
    subscriptionManager: (Int) -> T
): T = if (subscriptionId != null && subscriptionId >= 0) {
    subscriptionManager(subscriptionId)
} else {
    defaultManager()
}

/** نقل SMS الحقيقي فقط؛ النتيجة النهائية تأتي من callback receiver. */
class SmsTransport(private val context: Context) : SmsTransportSender {
    companion object {
        const val ACTION_SENT = "com.aistudio.dieselstationsms.SMS_SENT"
        const val ACTION_DELIVERED = "com.aistudio.dieselstationsms.SMS_DELIVERED"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_PART_INDEX = "part_index"
        const val EXTRA_SUBSCRIPTION_ID = "subscription_id"

        private const val FLAG_BASE = PendingIntent.FLAG_UPDATE_CURRENT

        fun getSmsManager(context: Context, subscriptionId: Int?): SmsManager =
            resolveSmsManagerRoute(
                subscriptionId = subscriptionId,
                defaultManager = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.getSystemService(SmsManager::class.java)
                            ?: throw IllegalStateException("خدمة إرسال SMS غير متاحة على هذا الجهاز")
                    } else {
                        legacyDefaultManager()
                    }
                },
                subscriptionManager = { selectedSubscriptionId ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.getSystemService(SmsManager::class.java)
                            ?.createForSubscriptionId(selectedSubscriptionId)
                            ?: throw IllegalStateException("تعذر إنشاء ناقل SMS لشريحة SIM المحددة")
                    } else {
                        legacySubscriptionManager(selectedSubscriptionId)
                    }
                }
            )

        // Android 12+ uses the instance API. Keep the old calls only for Android 8–11.
        @Suppress("DEPRECATION")
        private fun legacyDefaultManager(): SmsManager = SmsManager.getDefault()

        @Suppress("DEPRECATION")
        private fun legacySubscriptionManager(subscriptionId: Int): SmsManager =
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
    }

    override fun send(job: SmsOutboxRepository.OutboxMessage) {
        val manager = getSmsManager(context, job.subscriptionId)
        val parts = SmsBudgetManager.divideMessage(job.body)
        require(parts.isNotEmpty()) { "SMS body produced no parts" }

        val sentIntents = ArrayList<PendingIntent>(parts.size)
        val deliveryIntents = ArrayList<PendingIntent>(parts.size)
        parts.forEachIndexed { index, _ ->
            sentIntents += callbackPendingIntent(ACTION_SENT, job.messageId, index)
            deliveryIntents += callbackPendingIntent(ACTION_DELIVERED, job.messageId, index)
        }

        if (parts.size == 1) {
            manager.sendTextMessage(
                job.recipient,
                null,
                parts[0],
                sentIntents[0],
                deliveryIntents[0]
            )
        } else {
            manager.sendMultipartTextMessage(
                job.recipient,
                null,
                ArrayList(parts),
                sentIntents,
                deliveryIntents
            )
        }
    }

    private fun callbackPendingIntent(action: String, messageId: String, partIndex: Int): PendingIntent {
        val intent = Intent(context, SmsTransportCallbackReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_MESSAGE_ID, messageId)
            putExtra(EXTRA_PART_INDEX, partIndex)
        }
        val requestCode = (messageId.hashCode() * 31 + partIndex * 2 + if (action == ACTION_SENT) 0 else 1) and 0x7fffffff
        val flags = FLAG_BASE or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }
}
