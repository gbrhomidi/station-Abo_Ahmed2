package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Telephony
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import kotlinx.coroutines.*
import java.util.Collections

/**
 * ═══════════════════════════════════════════════════════════════
 * مستقبل الرسائل - SmsReceiver (الإصدار المُعاد تنظيمه)
 * ═══════════════════════════════════════════════════════════════
 *
 * المهام:
 * - استقبال الرسائل فقط (SMS_RECEIVED + SMS_DELIVER)
 * - تمريرها إلى SmsProcessor
 * - إدارة goAsync lifecycle
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        private const val WAKE_LOCK_TIMEOUT = 25000L  // أقل من 30 ثانية
        
        // منع التكرار - synchronized set
        private val processedMessages = Collections.synchronizedSet<LinkedHashSet<String>>(LinkedHashSet())
    }

    override fun onReceive(context: Context, intent: Intent) {
        // قبول كلا الحدثين
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION &&
            intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION
        ) {
            return
        }

        // استخراج معرف فريد للرسالة
        val messageId = extractMessageId(intent) ?: return
        
        // منع التكرار
        if (!processedMessages.add(messageId)) {
            Log.d(TAG, "Duplicate ignored: $messageId")
            return
        }
        
        // تنظيف الذاكرة المؤقتة إذا كبرت
        if (processedMessages.size > 500) {
            val iterator = processedMessages.iterator()
            repeat(100) { if (iterator.hasNext()) iterator.remove() }
        }

        val pendingResult = goAsync()
        var wakeLock: PowerManager.WakeLock? = null

        // استخدام GlobalScope مع goAsync (الذي يحافظ على العملية)
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "${context.packageName}:SmsReceiver"
                ).apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_TIMEOUT)
                }

                val db = withContext(Dispatchers.IO) {
                    DatabaseHelper.getInstance(context)
                }
                
                val processor = SmsProcessor(context, db)
                val processed = processor.process(intent)

                Log.d(TAG, "SMS processed [$messageId]: $processed")

            } catch (e: Exception) {
                val errorId = java.util.UUID.randomUUID().toString().take(8)
                Log.e(
                    TAG,
                    "SMS failed [$messageId] [ErrorID=$errorId] " +
                    "type=${e.javaClass.simpleName} msg=${e.message?.take(80)}",
                    e
                )
            } finally {
                try {
                    wakeLock?.let { if (it.isHeld) it.release() }
                } catch (e: Exception) {
                    Log.e(TAG, "WakeLock release error", e)
                }
                try {
                    pendingResult.finish()
                } catch (e: Exception) {
                    Log.e(TAG, "PendingResult finish error", e)
                }
            }
        }
    }

    /**
     * استخراج معرف فريد من الرسالة (لمنع التكرار)
     */
    private fun extractMessageId(intent: Intent): String? {
        return try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return null
            
            val sb = StringBuilder()
            for (msg in messages) {
                sb.append(msg.originatingAddress ?: "")
                sb.append(msg.timestampMillis)
                sb.append(msg.messageBody?.hashCode() ?: 0)
            }
            sb.toString().hashCode().toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract message ID", e)
            null
        }
    }
}
