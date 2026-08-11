package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ═══════════════════════════════════════════════════════════════
 * مستقبل الرسائل - SmsReceiver
 * ═══════════════════════════════════════════════════════════════
 *
 * المهام:
 * 1. استقبال الرسائل الواردة
 * 2. تصفية الرسائل غير المرغوب فيها
 * 3. إرسال الرسائل إلى المعالج
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        private val ALLOWED_SENDERS = java.util.Collections.synchronizedSet<String>(java.util.LinkedHashSet())
        private val BLOCKED_SENDERS = java.util.Collections.synchronizedSet<String>(java.util.LinkedHashSet())
        private const val MAX_MESSAGE_LENGTH = 1000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages == null || messages.isEmpty()) return

        val fullMessage = StringBuilder()
        var senderPhone = ""
        var timestamp = System.currentTimeMillis()

        for (msg in messages) {
            if (msg == null) continue
            fullMessage.append(msg.messageBody)
            if (senderPhone.isEmpty()) {
                senderPhone = msg.originatingAddress ?: ""
            }
            if (msg.timestampMillis > 0) {
                timestamp = msg.timestampMillis
            }
        }

        val messageBody = fullMessage.toString().trim()
        if (messageBody.isEmpty() || messageBody.length > MAX_MESSAGE_LENGTH) {
            Log.w(TAG, "Message too long or empty from $senderPhone")
            return
        }

        // تصفية المرسلين
        if (isBlocked(senderPhone)) {
            Log.d(TAG, "Blocked message from $senderPhone")
            return
        }

        if (!isAllowed(senderPhone)) {
            Log.d(TAG, "Message from unknown sender: $senderPhone")
            // يمكن إضافة منطق للسماح بالمرسلين الجدد هنا
        }

        Log.d(TAG, "Received SMS from $senderPhone: ${messageBody.take(50)}...")

        // إرسال إلى المعالج
        scope.launch {
            try {
                val processor = SmsProcessorFactory.getProcessor(context)
                val result = processor.processIncomingMessage(senderPhone, messageBody, timestamp)

                // إرسال الرد
                val response = when (result) {
                    is SmsProcessor.SmsProcessingResult.OrderDraft -> {
                        "تم استلام طلبك:
" +
                        "الكمية: ${result.quantity} لتر
" +
                        "الموقع: ${result.location}
" +
                        "يرجى الرد بـ 'تأكيد' للتأكيد أو 'إلغاء' للإلغاء."
                    }
                    is SmsProcessor.SmsProcessingResult.OrderConfirmed -> {
                        "✅ تم تأكيد طلبك رقم ${result.orderId}
" +
                        "الكمية: ${result.quantity} لتر
" +
                        "وقت التوصيل المتوقع: ${result.estimatedDelivery}"
                    }
                    is SmsProcessor.SmsProcessingResult.OrderCancelled -> {
                        result.message
                    }
                    is SmsProcessor.SmsProcessingResult.PriceInfo -> {
                        "سعر ${result.product}: ${result.price} ريال/${result.unit}"
                    }
                    is SmsProcessor.SmsProcessingResult.AvailabilityInfo -> {
                        if (result.available) {
                            "✅ ${result.product} متوفر حالياً."
                        } else {
                            "❌ ${result.product} غير متوفر حالياً."
                        }
                    }
                    is SmsProcessor.SmsProcessingResult.DeliveryInfo -> {
                        "وقت التوصيل المتوقع إلى ${result.location}: ${result.estimatedTime}"
                    }
                    is SmsProcessor.SmsProcessingResult.OrderStatus -> {
                        "حالة طلبك: ${result.status}"
                    }
                    is SmsProcessor.SmsProcessingResult.ComplaintCreated -> {
                        "تم إنشاء شكوى رقم ${result.ticketId}. سيتم التواصل معك قريباً."
                    }
                    is SmsProcessor.SmsProcessingResult.OTPSent -> {
                        "تم إرسال رمز التحقق إلى ${result.maskedPhone}. صالح لمدة ${result.expiryMinutes} دقائق."
                    }
                    is SmsProcessor.SmsProcessingResult.OTPVerified -> {
                        result.message
                    }
                    is SmsProcessor.SmsProcessingResult.NeedMoreInfo -> {
                        result.message
                    }
                    is SmsProcessor.SmsProcessingResult.Error -> {
                        result.message
                    }
                    is SmsProcessor.SmsProcessingResult.HelpInfo -> {
                        "الأوامر المتاحة:
" + result.commands.joinToString("
")
                    }
                    is SmsProcessor.SmsProcessingResult.Greeting -> {
                        if (result.isReturning) {
                            "أهلاً بعودتك ${result.customerName}! كيف يمكنني مساعدتك اليوم؟"
                        } else {
                            "أهلاً ${result.customerName}! مرحباً بك في محطة أبو أحمد. كيف يمكنني مساعدتك؟"
                        }
                    }
                    is SmsProcessor.SmsProcessingResult.Unknown -> {
                        result.message
                    }
                }

                SmsSender.sendSms(context, senderPhone, response)

            } catch (e: Exception) {
                Log.e(TAG, "Error processing SMS: ${e.message}", e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ Helpers ═══
    // ═══════════════════════════════════════════════════════════════

    private fun isBlocked(phone: String): Boolean {
        return BLOCKED_SENDERS.contains(phone)
    }

    private fun isAllowed(phone: String): Boolean {
        return ALLOWED_SENDERS.isEmpty() || ALLOWED_SENDERS.contains(phone)
    }

    fun addAllowedSender(phone: String) {
        ALLOWED_SENDERS.add(phone)
    }

    fun removeAllowedSender(phone: String) {
        ALLOWED_SENDERS.remove(phone)
    }

    fun addBlockedSender(phone: String) {
        BLOCKED_SENDERS.add(phone)
    }

    fun removeBlockedSender(phone: String) {
        BLOCKED_SENDERS.remove(phone)
    }
}
