package com.aistudio.dieselstationsms.kxmpzq

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        private const val RATE_LIMIT_MS = 60000L // دقيقة واحدة
        private const val MAX_MESSAGE_LENGTH = 1600
    }

    // حماية من الرسائل المتكررة
    private val recentReplies = ConcurrentHashMap<String, Long>()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.e(TAG, "SEND_SMS permission not granted")
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return

        DatabaseHelper(context).use { db ->
            for (sms in messages) {
                val sender = sms.displayOriginatingAddress ?: continue
                val msgBody = sms.displayMessageBody?.lowercase(Locale.getDefault()) ?: continue

                // حماية من الرسائل الطويلة/الضارة
                if (msgBody.length > MAX_MESSAGE_LENGTH) {
                    Log.w(TAG, "Message too long from $sender")
                    continue
                }

                // حماية من الرسائل المتكررة
                if (!canReply(sender)) {
                    Log.w(TAG, "Rate limit exceeded for $sender")
                    continue
                }

                db.logSms(sender, msgBody, "received", "success")
                Log.d(TAG, "SMS from: $sender, body: $msgBody")

                try {
                    handleMessage(context, db, sender, msgBody)
                } catch (e: Exception) {
                    Log.e(TAG, "Error: ${e.message}", e)
                    safeSendReply(context, db, sender, "عذراً، حدث خطأ. أرسل: استعلام")
                }
            }
        }
    }

    private fun canReply(phone: String): Boolean {
        val lastReply = recentReplies[phone] ?: 0
        val canSend = System.currentTimeMillis() - lastReply > RATE_LIMIT_MS
        if (canSend) {
            recentReplies[phone] = System.currentTimeMillis()
        }
        return canSend
    }

    private fun handleMessage(context: Context, db: DatabaseHelper, sender: String, msgBody: String) {
        when {
            msgBody.contains("رصيد") || msgBody.contains("حساب") || msgBody.contains("balance") -> {
                handleBalanceQuery(context, db, sender)
            }
            msgBody.contains("دفع") || msgBody.contains("تسديد") -> {
                sendReply(context, db, sender,
                    "شكراً لتواصلك. يرجى زيارة المحطة لإتمام عملية الدفع.")
            }
            msgBody.contains("استعلام") || msgBody.contains("help") -> {
                sendReply(context, db, sender,
                    "مرحباً بك في محطة أبو أحمد.\n" +
                    "1. رصيد - الاستعلام عن الرصيد\n" +
                    "2. عروض - معرفة العروض\n" +
                    "3. موقع - الموقع")
            }
            msgBody.contains("عروض") || msgBody.contains("offer") -> {
                sendReply(context, db, sender,
                    "عروض اليوم:\n- سعر اللتر: 500 ريال\n" +
                    "- خصم الولاء: 5% للعملاء الذهبيين")
            }
            msgBody.contains("موقع") || msgBody.contains("location") -> {
                sendReply(context, db, sender,
                    "موقع محطة أبو أحمد:\nبجانب مدرسة الاتحاد - الحميدة - العرش\n24 ساعة")
            }
            else -> {
                sendReply(context, db, sender,
                    "شكراً لتواصلك مع محطة أبو أحمد.\nأرسل: استعلام")
            }
        }
    }

    private fun handleBalanceQuery(context: Context, db: DatabaseHelper, sender: String) {
        val cleanSender = normalizePhone(sender)
        var found = false

        val customers = db.getCustomers()
        for (i in 0 until customers.length()) {
            val c = customers.getJSONObject(i)
            val cPhone = normalizePhone(c.optString("phone", ""))

            if (cPhone.isNotEmpty() && isPhoneMatch(cleanSender, cPhone)) {
                val bal = c.optDouble("current_balance", 0.0)
                val points = c.optInt("loyalty_points", 0)
                val vip = c.optInt("vip_level", 0)
                val vipText = when (vip) {
                    3 -> "ذهبي"
                    2 -> "فضي"
                    else -> "عادي"
                }
                val name = c.optString("full_name", "عميلنا العزيز")
                val reply = "مرحباً $name،\nالرصيد: $bal ريال\nالنقاط: $points\nالعضوية: $vipText"

                sendReply(context, db, sender, reply)
                found = true
                break
            }
        }

        if (!found) {
            sendReply(context, db, sender,
                "لم يتم العثور على حساب مرتبط بهذا الرقم. يرجى التسجيل في المحطة.")
        }
    }

    private fun normalizePhone(phone: String): String {
        return phone.replace("[^0-9]".toRegex(), "").takeLast(9)
    }

    private fun isPhoneMatch(phone1: String, phone2: String): Boolean {
        return phone1 == phone2 || phone1.endsWith(phone2) || phone2.endsWith(phone1)
    }

    private fun sendReply(context: Context, db: DatabaseHelper, phone: String, message: String) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.e(TAG, "Permission denied")
            db.logSms(phone, message, "auto_reply", "failed: permission denied")
            return
        }

        try {
            val smsManager = getSmsManager(context)
            val parts = smsManager.divideMessage(message)

            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phone, null, message, null, null)
            }

            db.logSms(phone, message, "auto_reply", "sent")
            Log.d(TAG, "Reply sent to $phone")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send reply: ${e.message}", e)
            db.logSms(phone, message, "auto_reply", "failed: ${e.message}")
        }
    }

    private fun getSmsManager(context: Context): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
                ?: SmsManager.getDefault()
        } else {
            SmsManager.getDefault()
        }
    }

    private fun safeSendReply(context: Context, db: DatabaseHelper, phone: String, message: String) {
        try {
            sendReply(context, db, phone, message)
        } catch (e: Exception) {
            Log.e(TAG, "Safe send failed: ${e.message}", e)
        }
    }
}
