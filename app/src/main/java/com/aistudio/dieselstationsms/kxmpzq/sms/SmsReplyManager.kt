package com.aistudio.dieselstationsms.kxmpzq.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.utils.PhoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * ═══════════════════════════════════════════════════════════════
 * مدير الردود - SmsReplyManager
 * ═══════════════════════════════════════════════════════════════
 *
 * المسؤوليات:
 * 1. إرسال رسائل SMS الصادرة.
 * 2. منع الإرسال المتكرر غير المقصود.
 * 3. التحقق من صلاحية SEND_SMS.
 * 4. تقسيم الرسائل الطويلة إلى أجزاء عند الحاجة.
 * 5. تسجيل نتيجة محاولة الإرسال في قاعدة البيانات.
 * 6. إرسال تنبيهات المديرين عبر SMS.
 * 7. توفير نقطة تكامل مستقبلية للإشعارات Push.
 *
 * مبدأ التصميم:
 * - هذا الملف مسؤول عن "الإرسال".
 * - SmsProcessor مسؤول عن "المعالجة".
 * - SmsSecurity مسؤول عن "الأمان وRate Limiting".
 *
 * لا يحتوي هذا الملف على منطق تفسير أوامر SMS الواردة.
 */
class SmsReplyManager(
    private val context: Context,
    private val db: DatabaseHelper
) {

    companion object {
        private const val TAG = "SmsReplyManager"

        /**
         * الحد الأدنى بين الردود المتكررة لنفس الرقم
         * من خلال sendReplyOnce().
         */
        private const val RATE_LIMIT_MS = 60_000L

        /**
         * الحد الأعلى لحجم النص الذي يسمح به المدير.
         *
         * SmsManager يستطيع تقسيم الرسالة إلى أجزاء،
         * لذلك لا نضع حدًا صغيرًا يمنع الرسائل الطويلة المشروعة.
         *
         * هذا الحد هو طبقة حماية ضد تمرير نصوص ضخمة بشكل غير مقصود.
         */
        private const val MAX_MESSAGE_LENGTH = 4096

        private const val SMS_TYPE_AUTO_REPLY = SmsLogContract.TYPE_NOTIFICATION

        private const val STATUS_SENT = "sent"

        private const val STATUS_FAILED = "failed"

        private const val STATUS_PERMISSION_DENIED =
            "failed: permission denied"

        private const val STATUS_INVALID_RECIPIENT =
            "failed: invalid recipient"

        private const val STATUS_EMPTY_MESSAGE =
            "failed: empty message"

        private const val STATUS_MESSAGE_TOO_LONG =
            "failed: message too long"
    }

    /**
     * Cache محلي لمنع الردود المتكررة.
     *
     * المفتاح هو رقم الهاتف بعد التطبيع.
     */
    private val recentReplies =
        ConcurrentHashMap<String, Long>()

    /**
     * إرسال رد SMS.
     *
     * هذه الدالة لا تمنع الإرسال المتكرر من تلقاء نفسها.
     * إذا كان المطلوب منع الرد المتكرر، استخدم sendReplyOnce().
     */
    suspend fun sendReply(
        phone: String,
        message: String
    ): Boolean = withContext(Dispatchers.IO) {

        val normalizedPhone = normalizePhone(phone)

        if (normalizedPhone == null) {
            Log.w(
                TAG,
                "SMS reply blocked: invalid recipient"
            )

            logSmsSafely(
                phone = phone,
                message = message,
                status = STATUS_INVALID_RECIPIENT
            )

            return@withContext false
        }

        val normalizedMessage = message.trim()

        if (normalizedMessage.isEmpty()) {
            Log.w(
                TAG,
                "SMS reply blocked: empty message"
            )

            logSmsSafely(
                phone = normalizedPhone,
                message = normalizedMessage,
                status = STATUS_EMPTY_MESSAGE
            )

            return@withContext false
        }

        if (normalizedMessage.length > MAX_MESSAGE_LENGTH) {
            Log.w(
                TAG,
                "SMS reply blocked: message too long"
            )

            logSmsSafely(
                phone = normalizedPhone,
                message = normalizedMessage,
                status = STATUS_MESSAGE_TOO_LONG
            )

            return@withContext false
        }

        if (!checkSmsPermission()) {
            Log.e(
                TAG,
                "SEND_SMS permission denied; " +
                    "reply blocked for ${maskPhone(normalizedPhone)}"
            )

            logSmsSafely(
                phone = normalizedPhone,
                message = normalizedMessage,
                status = STATUS_PERMISSION_DENIED
            )

            return@withContext false
        }

        return@withContext try {

            val smsManager = getSmsManager()

            val parts = smsManager.divideMessage(
                normalizedMessage
            )

            if (parts.isEmpty()) {
                Log.w(
                    TAG,
                    "SmsManager produced no message parts"
                )

                logSmsSafely(
                    phone = normalizedPhone,
                    message = normalizedMessage,
                    status = "$STATUS_FAILED: no message parts"
                )

                false

            } else if (parts.size == 1) {

                smsManager.sendTextMessage(
                    normalizedPhone,
                    null,
                    parts[0],
                    null,
                    null
                )

                logSmsSafely(
                    phone = normalizedPhone,
                    message = normalizedMessage,
                    status = STATUS_SENT
                )

                Log.d(
                    TAG,
                    "SMS sent to ${maskPhone(normalizedPhone)}"
                )

                true

            } else {

                smsManager.sendMultipartTextMessage(
                    normalizedPhone,
                    null,
                    parts,
                    null,
                    null
                )

                logSmsSafely(
                    phone = normalizedPhone,
                    message = normalizedMessage,
                    status = "$STATUS_SENT: ${parts.size} parts"
                )

                Log.d(
                    TAG,
                    "Multipart SMS sent to " +
                        "${maskPhone(normalizedPhone)} " +
                        "parts=${parts.size}"
                )

                true
            }

        } catch (securityException: SecurityException) {

            Log.e(
                TAG,
                "SMS send failed: SecurityException"
            )

            logSmsSafely(
                phone = normalizedPhone,
                message = normalizedMessage,
                status = "$STATUS_FAILED: SecurityException"
            )

            false

        } catch (illegalArgumentException: IllegalArgumentException) {

            Log.e(
                TAG,
                "SMS send failed: IllegalArgumentException"
            )

            logSmsSafely(
                phone = normalizedPhone,
                message = normalizedMessage,
                status = "$STATUS_FAILED: IllegalArgumentException"
            )

            false

        } catch (exception: Exception) {

            Log.e(
                TAG,
                "SMS send failed: " +
                    exception.javaClass.simpleName
            )

            logSmsSafely(
                phone = normalizedPhone,
                message = normalizedMessage,
                status = "$STATUS_FAILED: " +
                    exception.javaClass.simpleName
            )

            false
        }
    }

    /**
     * إرسال رد مرة واحدة ضمن نافذة RATE_LIMIT_MS.
     *
     * مهم:
     * لا يتم تسجيل الرد على أنه "مرسل" في الذاكرة
     * إلا بعد نجاح sendReply().
     *
     * هذا يمنع فقدان الرد في حال فشل الإرسال.
     */
    suspend fun sendReplyOnce(
        phone: String,
        message: String
    ): Boolean = withContext(Dispatchers.IO) {

        val normalizedPhone = normalizePhone(phone)

        if (normalizedPhone == null) {
            Log.w(
                TAG,
                "sendReplyOnce blocked: invalid recipient"
            )
            return@withContext false
        }

        val now = System.currentTimeMillis()

        val lastSent =
            recentReplies[normalizedPhone] ?: 0L

        if (lastSent > 0L &&
            now - lastSent < RATE_LIMIT_MS
        ) {

            Log.d(
                TAG,
                "Duplicate reply suppressed for " +
                    maskPhone(normalizedPhone)
            )

            return@withContext false
        }

        /*
         * نحاول الإرسال أولاً.
         */
        val sent = sendReply(
            phone = normalizedPhone,
            message = message
        )

        /*
         * لا نحجز الرقم إلا بعد نجاح الإرسال.
         */
        if (sent) {
            recentReplies[normalizedPhone] = now
            cleanupReplyCache(now)
        }

        sent
    }

    /**
     * إرسال آمن لا يسمح باستثناءات الإرسال
     * بالخروج إلى الطبقة المستدعية.
     */
    suspend fun safeSendReply(
        phone: String,
        message: String
    ): Boolean {

        return try {

            sendReply(
                phone = phone,
                message = message
            )

        } catch (exception: Exception) {

            Log.e(
                TAG,
                "Safe SMS send failed: " +
                    exception.javaClass.simpleName
            )

            false
        }
    }

    /**
     * إرسال تنبيه إلى مدير.
     *
     * النتيجة تعكس نجاح إرسال SMS.
     *
     * Push Notification لا تعتبر ناجحة ما لم يكن هناك
     * تكامل Push حقيقي مفعّل في المشروع.
     */
    suspend fun notifyManager(
        managerPhone: String,
        message: String
    ): Boolean = withContext(Dispatchers.IO) {

        val smsSent = try {

            sendReply(
                phone = managerPhone,
                message = message
            )

        } catch (exception: Exception) {

            Log.e(
                TAG,
                "Manager SMS notification failed: " +
                    exception.javaClass.simpleName
            )

            false
        }

        /*
         * Push هو مسار إضافي مستقل.
         *
         * لا نعتبر مجرد وجود Token دليلاً على نجاح
         * إرسال Push.
         */
        val pushEnabled =
            getSystemSetting(
                "push_notifications_enabled",
                "0"
            ) == "1"

        if (pushEnabled) {
            sendPushNotificationIfEnabled(
                target = managerPhone,
                title = "تنبيه مدير",
                body = message
            )
        }

        smsSent
    }

    /**
     * نقطة تكامل Push مستقبلية.
     *
     * النسخة الحالية لا تدعي إرسال Push فعلي.
     *
     * لا يتم تسجيل FCM Token في Log.
     */
    private fun sendPushNotificationIfEnabled(
        target: String,
        title: String,
        body: String
    ): Boolean {

        return try {

            val normalizedTarget =
                normalizePhone(target) ?: return false

            val tokenKey =
                "fcm_token_$normalizedTarget"

            val fcmToken =
                getSystemSetting(tokenKey, "")

            if (fcmToken.isBlank()) {
                Log.d(
                    TAG,
                    "Push skipped: no token configured"
                )
                return false
            }

            /*
             * لا يوجد هنا FirebaseMessaging implementation فعلي.
             *
             * لذلك لا نزعم نجاح الإرسال.
             */
            Log.d(
                TAG,
                "Push integration available but " +
                    "actual provider is not configured"
            )

            false

        } catch (exception: Exception) {

            Log.e(
                TAG,
                "Push notification preparation failed: " +
                    exception.javaClass.simpleName
            )

            false
        }
    }

    /**
     * التحقق من صلاحية SEND_SMS.
     */
    private fun checkSmsPermission(): Boolean {

        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * الحصول على SmsManager المناسب لإصدار Android.
     *
     * في Android 12+ نستخدم خدمة النظام.
     */
    private fun getSmsManager(): SmsManager {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {

            context.getSystemService(
                SmsManager::class.java
            ) ?: SmsManager.getDefault()

        } else {

            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    /**
     * قراءة إعداد من system_settings.
     */
    private fun getSystemSetting(
        key: String,
        defaultValue: String = "0"
    ): String {

        return try {

            val cursor =
                db.readableDatabase.rawQuery(
                    """
                    SELECT setting_value
                    FROM system_settings
                    WHERE setting_key = ?
                    LIMIT 1
                    """.trimIndent(),
                    arrayOf(key)
                )

            cursor.use {

                if (it.moveToFirst()) {

                    it.getString(0)
                        ?.takeIf { value ->
                            value.isNotBlank()
                        }
                        ?: defaultValue

                } else {

                    defaultValue
                }
            }

        } catch (exception: Exception) {

            Log.e(
                TAG,
                "Failed to read system setting " +
                    "key=$key: " +
                    exception.javaClass.simpleName
            )

            defaultValue
        }
    }

    /**
     * تطبيع رقم الهاتف قبل استخدامه كمستلم.
     *
     * PhoneUtils هو المرجع المركزي لتوحيد الأرقام
     * في نظام SMS.
     */
    private fun normalizePhone(
        phone: String
    ): String? {

        if (phone.isBlank()) {
            return null
        }

        return try {

            PhoneUtils.normalize(phone)
                ?.trim()
                ?.takeIf { normalized ->
                    normalized.isNotEmpty()
                }

        } catch (exception: Exception) {

            Log.w(
                TAG,
                "Phone normalization failed"
            )

            null
        }
    }

    /**
     * تنظيف Cache القديم.
     *
     * لا نحتاج إلى الاحتفاظ بكل الأرقام إلى الأبد.
     */
    private fun cleanupReplyCache(now: Long) {

        val iterator =
            recentReplies.entries.iterator()

        while (iterator.hasNext()) {

            val entry = iterator.next()

            if (
                now - entry.value >= RATE_LIMIT_MS
            ) {
                iterator.remove()
            }
        }
    }

    /**
     * تسجيل عملية SMS في قاعدة البيانات بطريقة آمنة.
     *
     * يتم تجنب السماح بفشل التسجيل بأن يحول عملية
     * الإرسال الناجحة إلى عملية فاشلة.
     */
    private fun logSmsSafely(
        phone: String,
        message: String,
        status: String
    ) {

        try {

            db.logSms(
                phone,
                message,
                SmsLogContract.normalizeType(SMS_TYPE_AUTO_REPLY),
                SmsLogContract.normalizeStatus(status)
            )

        } catch (exception: Exception) {

            Log.e(
                TAG,
                "Failed to write SMS audit record: " +
                    exception.javaClass.simpleName
            )
        }
    }

    /**
     * إخفاء الرقم داخل Logs.
     *
     * لا يتم تسجيل الرقم كاملًا.
     */
    private fun maskPhone(phone: String): String {

        val normalized =
            phone.trim()

        if (normalized.length <= 4) {
            return "***"
        }

        if (normalized.length <= 7) {
            return normalized.take(2) +
                "***" +
                normalized.takeLast(2)
        }

        return normalized.take(3) +
            "***" +
            normalized.takeLast(2)
    }
}