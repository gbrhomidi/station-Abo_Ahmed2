package com.aistudio.dieselstationsms.kxmpzq.sms

import android.Manifest
import android.content.ContentValues
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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import org.json.JSONObject

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
        private const val OUTBOUND_DEDUPE_RETENTION_MS = 24L * 60L * 60L * 1000L
        private const val OUTBOUND_DEDUPE_TABLE = "sms_outbound_dedupe"

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
     * Cache محلي سريع لمنع تكرار نفس النص لنفس الرقم.
     * الحماية الدائمة موجودة في SQLite حتى تعمل بين BroadcastReceiver instances.
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
        message: String,
        eventId: String? = null,
        conversationId: String? = null,
        businessEntityId: String? = null,
        dedupeKey: String? = null
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

        val normalizedMessage = SmsMessageNormalizer.normalizeForSms(message)

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

        val sendEnabled = runCatching { db.getSetting("sms_send_enabled") }
            .getOrDefault("")
        if (sendEnabled == "0") {
            Log.w(TAG, "SMS sending disabled by system settings")
            logSmsSafely(
                phone = normalizedPhone,
                message = normalizedMessage,
                status = "failed: sending disabled"
            )
            return@withContext false
        }

        return@withContext try {
            val prepared = SmsBudgetManager.prepare(normalizedMessage)
            val effectiveConversationId = conversationId ?: resolveConversationId(normalizedPhone)
            val effectiveEventId = eventId ?: UUID.randomUUID().toString()
            val result = SmsOutboxRepository.enqueue(
                db = db,
                recipient = normalizedPhone,
                body = prepared.body,
                eventId = effectiveEventId,
                conversationId = effectiveConversationId,
                businessEntityId = businessEntityId,
                dedupeKey = dedupeKey
            ) ?: run {
                logSmsSafely(normalizedPhone, prepared.body, "$STATUS_FAILED: outbox rejected")
                return@withContext false
            }

            if (!effectiveConversationId.isNullOrBlank()) {
                runCatching {
                    SmsCognitiveRepository(db).recordInboundTrace(
                        conversationId = effectiveConversationId,
                        eventId = effectiveEventId,
                        stage = "MESSAGE_QUEUED",
                        payload = JSONObject().apply {
                            put("message_id", result.messageId)
                            put("recipient", normalizedPhone)
                            put("parts_count", result.partsCount)
                        }
                    )
                }.onFailure { Log.w(TAG, "Unable to persist outbound trace", it) }
            }
            SmsOutboxWorker.schedule(context)
            logSmsSafely(
                phone = normalizedPhone,
                message = prepared.body,
                status = "queued: ${result.partsCount} parts"
            )
            Log.d(
                TAG,
                "SMS queued for ${maskPhone(normalizedPhone)} " +
                    "parts=${result.partsCount} messageId=${result.messageId.take(8)}"
            )
            true
        } catch (exception: Exception) {
            Log.e(TAG, "SMS enqueue failed: ${exception.javaClass.simpleName}")
            logSmsSafely(
                phone = normalizedPhone,
                message = normalizedMessage,
                status = "$STATUS_FAILED: ${exception.javaClass.simpleName}"
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
        message: String,
        eventId: String? = null,
        conversationId: String? = null,
        businessEntityId: String? = null,
        dedupeKey: String? = null
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
        val effectiveDedupeKey = dedupeKey ?: buildDedupeKey(normalizedPhone, message.trim())

        val lastSent =
            recentReplies[effectiveDedupeKey] ?: 0L

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
         * الحجز ذري ودائم قبل استدعاء SmsManager؛ لذلك لا يستطيع
         * BroadcastReceiver ثانٍ إرسال النص نفسه بالتوازي.
         */
        if (!reserveOutboundReply(normalizedPhone, message.trim(), effectiveDedupeKey, now)) {
            Log.d(TAG, "Persistent duplicate reply suppressed for ${maskPhone(normalizedPhone)}")
            return@withContext false
        }

        val sent = try {
            sendReply(
                phone = normalizedPhone,
                message = message,
                eventId = eventId,
                conversationId = conversationId,
                businessEntityId = businessEntityId,
                dedupeKey = effectiveDedupeKey
            )
        } catch (exception: Exception) {
            Log.e(TAG, "Reply send threw after reservation", exception)
            false
        }

        if (sent) {
            // sent هنا تعني QUEUED في outbox؛ نتيجة المودم تسجلها callbacks لاحقاً.
            markOutboundReplySent(effectiveDedupeKey, now)
            recentReplies[effectiveDedupeKey] = now
            cleanupReplyCache(now)
        } else {
            releaseOutboundReply(effectiveDedupeKey)
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

            sendReplyOnce(
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

            sendReplyOnce(
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

    private fun resolveConversationId(phone: String): String? {
        return runCatching {
            db.readableDatabase.rawQuery(
                "SELECT conversation_id FROM sms_conversation_context WHERE phone = ? LIMIT 1",
                arrayOf(phone)
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else cursor.getString(0)?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }


    private fun buildDedupeKey(phone: String, message: String): String {
        val normalizedMessage = message.trim().replace(Regex("\\s+"), " ")
        val raw = "$phone|$normalizedMessage"
        return sha256(raw)
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun reserveOutboundReply(
        phone: String,
        message: String,
        dedupeKey: String,
        now: Long
    ): Boolean {
        return try {
            val database = db.writableDatabase
            database.beginTransaction()
            try {
                val cutoff = now - OUTBOUND_DEDUPE_RETENTION_MS
                database.delete(
                    OUTBOUND_DEDUPE_TABLE,
                    "reserved_at < ?",
                    arrayOf(cutoff.toString())
                )

                val values = ContentValues().apply {
                    put("dedupe_key", dedupeKey)
                    put("phone", phone)
                    put("message_hash", sha256(message))
                    put("message_preview", message.take(120))
                    put("status", "reserved")
                    put("reserved_at", now)
                }
                val inserted = database.insertWithOnConflict(
                    OUTBOUND_DEDUPE_TABLE,
                    null,
                    values,
                    android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
                )
                database.setTransactionSuccessful()
                inserted != -1L
            } finally {
                database.endTransaction()
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to reserve outbound reply", exception)
            false
        }
    }

    private fun markOutboundReplySent(dedupeKey: String, now: Long) {
        runCatching {
            db.writableDatabase.update(
                OUTBOUND_DEDUPE_TABLE,
                ContentValues().apply {
                    put("status", "sent")
                    put("sent_at", now)
                },
                "dedupe_key = ? AND status = 'reserved'",
                arrayOf(dedupeKey)
            )
        }.onFailure {
            Log.e(TAG, "Failed to finalize outbound reply reservation", it)
        }
    }

    private fun releaseOutboundReply(dedupeKey: String) {
        runCatching {
            db.writableDatabase.delete(
                OUTBOUND_DEDUPE_TABLE,
                "dedupe_key = ? AND status = 'reserved'",
                arrayOf(dedupeKey)
            )
        }.onFailure {
            Log.e(TAG, "Failed to release outbound reply reservation", it)
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