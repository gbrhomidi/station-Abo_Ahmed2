package com.aistudio.dieselstationsms.kxmpzq.sms

import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ═══════════════════════════════════════════════════════════════
 * مدير الردود - SmsReplyManager
 * ═══════════════════════════════════════════════════════════════
 *
 * المهام:
 * 1. إرسال ردود SMS
 * 2. إرسال إشعارات للمديرين
 * 3. إرسال إشعارات Push (إذا مُفعّل)
 * 4. تسجيل الردود في قاعدة البيانات
 * 5. Rate limiting على الردود
 */
class SmsReplyManager(private val context: Context, private val db: DatabaseHelper) {

    companion object {
        private const val TAG = "SmsReplyManager"
        private const val RATE_LIMIT_MS = 60000L
    }

    private val recentReplies = java.util.concurrent.ConcurrentHashMap<String, Long>()

    suspend fun sendReply(phone: String, message: String): Boolean = withContext(Dispatchers.IO) {
        if (!checkSmsPermission()) {
            Log.e(TAG, "SEND_SMS permission denied; reply to ${maskPhone(phone)} blocked")
            db.logSms(phone, message, "auto_reply", "failed: permission denied")
            return@withContext false
        }

        try {
            val smsManager = getSmsManager()
            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phone, null, message, null, null)
            }
            db.logSms(phone, message, "auto_reply", "sent")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send reply to ${maskPhone(phone)}: ${e.javaClass.simpleName}")
            db.logSms(phone, message, "auto_reply", "failed: ${e.javaClass.simpleName}")
            false
        }
    }

    suspend fun sendReplyOnce(phone: String, message: String): Boolean = withContext(Dispatchers.IO) {
        val lastSent = recentReplies[phone] ?: 0
        if (System.currentTimeMillis() - lastSent < RATE_LIMIT_MS) {
            Log.d(TAG, "Skipping duplicate reply to ${maskPhone(phone)}")
            return@withContext false
        }
        recentReplies[phone] = System.currentTimeMillis()
        sendReply(phone, message)
    }

    suspend fun safeSendReply(phone: String, message: String): Boolean {
        return try {
            sendReply(phone, message)
        } catch (e: Exception) {
            Log.e(TAG, "Safe send failed for ${maskPhone(phone)}: ${e.javaClass.simpleName}")
            false
        }
    }

    suspend fun notifyManager(managerPhone: String, message: String) = withContext(Dispatchers.IO) {
        try {
            sendReply(managerPhone, message)

            val pushEnabled = getSystemSetting("push_notifications_enabled", "0") == "1"
            if (pushEnabled) {
                sendPushNotificationIfEnabled(managerPhone, "تنبيه مدير", message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify manager ${maskPhone(managerPhone)}: ${e.javaClass.simpleName}")
        }
    }

    private fun sendPushNotificationIfEnabled(target: String, title: String, body: String) {
        try {
            val fcmToken = getSystemSetting("fcm_token_$target", "")
            if (fcmToken.isNotEmpty()) {
                Log.d(TAG, "Push notification would be sent to ${maskPhone(target)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send push notification: ${e.message}")
        }
    }

    private fun checkSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getSmsManager(): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        } else {
            SmsManager.getDefault()
        }
    }

    private fun getSystemSetting(key: String, defaultValue: String = "0"): String {
        val cursor = db.readableDatabase.rawQuery(
            "SELECT setting_value FROM system_settings WHERE setting_key = ? LIMIT 1",
            arrayOf(key)
        )
        return cursor.use {
            if (it.moveToFirst()) it.getString(0) else defaultValue
        }
    }

    /**
     * إخفاء جزء من الرقم لأغراض التسجيل (privacy)
     */
    private fun maskPhone(phone: String): String {
        if (phone.length <= 4) return "***"
        return phone.take(3) + "***" + phone.takeLast(2)
    }
}