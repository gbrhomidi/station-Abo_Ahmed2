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
import java.util.concurrent.ConcurrentHashMap

/**
 * ═══════════════════════════════════════════════════════════════
 * مدير الردود - SmsReplyManager
 * ═══════════════════════════════════════════════════════════════
 */
class SmsReplyManager(private val context: Context, private val db: DatabaseHelper) {

    companion object {
        private const val TAG = "SmsReplyManager"
        private const val RATE_LIMIT_MS = 60000L
        private const val MAX_PARTS = 255
        private const val MAX_CACHE_SIZE = 100
    }

    private val recentReplies = ConcurrentHashMap<String, Long>()

    suspend fun sendReply(phone: String, message: String): Boolean = withContext(Dispatchers.IO) {
        // التحقق من صحة الرقم
        if (!isValidPhone(phone)) {
            Log.e(TAG, "Invalid phone: ${maskPhone(phone)}")
            return@withContext false
        }

        // التحقق من الإذن
        if (!checkSmsPermission()) {
            Log.e(TAG, "SEND_SMS denied for ${maskPhone(phone)}")
            logSmsAsync(phone, message, "auto_reply", "failed: permission denied")
            return@withContext false
        }

        // التحقق من طول الرسالة
        if (message.isBlank()) {
            Log.w(TAG, "Empty message, skipping")
            return@withContext false
        }

        try {
            val smsManager = getSmsManager()
            val parts = smsManager.divideMessage(message)

            if (parts.isEmpty()) {
                Log.w(TAG, "Message division returned empty")
                return@withContext false
            }

            if (parts.size > MAX_PARTS) {
                Log.e(TAG, "Message too long: ${parts.size} parts > $MAX_PARTS")
                return@withContext false
            }

            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phone, null, message, null, null)
            }

            logSmsAsync(phone, message, "auto_reply", "sent")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Send failed to ${maskPhone(phone)}: ${e.javaClass.simpleName}")
            logSmsAsync(phone, message, "auto_reply", "failed: ${e.javaClass.simpleName}")
            false
        }
    }

    suspend fun sendReplyOnce(phone: String, message: String): Boolean = withContext(Dispatchers.IO) {
        cleanupOldReplies()

        val lastSent = recentReplies[phone] ?: 0
        if (System.currentTimeMillis() - lastSent < RATE_LIMIT_MS) {
            Log.d(TAG, "Rate limited: ${maskPhone(phone)}")
            return@withContext false
        }

        val success = sendReply(phone, message)
        if (success) {
            recentReplies[phone] = System.currentTimeMillis()
        }
        success
    }

    suspend fun safeSendReply(phone: String, message: String): Boolean {
        return try {
            sendReply(phone, message)
        } catch (e: Exception) {
            Log.e(TAG, "Safe send failed: ${maskPhone(phone)}: ${e.javaClass.simpleName}")
            false
        }
    }

    suspend fun notifyManager(managerPhone: String, message: String): Boolean = withContext(Dispatchers.IO) {
        val smsSuccess = sendReply(managerPhone, message)

        try {
            val pushEnabled = getSystemSetting("push_notifications_enabled", "0") == "1"
            if (pushEnabled) {
                sendPushNotificationIfEnabled(managerPhone, "تنبيه مدير", message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Push notify failed: ${e.javaClass.simpleName}")
        }

        smsSuccess
    }

    private fun sendPushNotificationIfEnabled(target: String, title: String, body: String) {
        try {
            val fcmToken = getSystemSetting("fcm_token_$target", "")
            if (fcmToken.isNotEmpty()) {
                Log.d(TAG, "Push would send to ${maskPhone(target)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Push failed: ${e.message}")
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
        return try {
            db.readableDatabase.rawQuery(
                "SELECT setting_value FROM system_settings WHERE setting_key = ? LIMIT 1",
                arrayOf(key)
            ).use {
                if (it.moveToFirst()) it.getString(0) else defaultValue
            }
        } catch (e: Exception) {
            Log.w(TAG, "Setting read failed [$key]: ${e.javaClass.simpleName}")
            defaultValue
        }
    }

    /** تسجيل غير متزامن لتجنب تأخير الإرسال */
    private fun logSmsAsync(phone: String, message: String, type: String, status: String) {
        try {
            db.logSms(phone, message, type, status)
        } catch (e: Exception) {
            Log.w(TAG, "Log failed: ${e.javaClass.simpleName}")
        }
    }

    private fun cleanupOldReplies() {
        if (recentReplies.size <= MAX_CACHE_SIZE) return
        val cutoff = System.currentTimeMillis() - RATE_LIMIT_MS * 2
        recentReplies.entries.removeIf { it.value < cutoff }
    }

    private fun isValidPhone(phone: String): Boolean {
        return phone.isNotBlank() && phone.matches(Regex("^\\+?[0-9]{7,15}$"))
    }

    private fun maskPhone(phone: String): String {
        if (phone.length <= 4) return "***"
        return phone.take(3) + "***" + phone.takeLast(2)
    }
}
