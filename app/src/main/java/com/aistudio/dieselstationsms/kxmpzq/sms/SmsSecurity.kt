package com.aistudio.dieselstationsms.kxmpzq.sms

import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.utils.PhoneUtils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * ═══════════════════════════════════════════════════════════════
 * طبقة الأمان المتقدمة - SmsSecurity
 * ═══════════════════════════════════════════════════════════════
 *
 * المهام:
 * 1. توليد Hash دائم للرسائل (منع التكرار)
 * 2. التحقق من SMSC الموثوق
 * 3. كشف الرسائل المشبوهة
 * 4. حماية استنزاف SMS (Rate Limiting مع SQLite)
 * 5. حماية الرسائل الطويلة
 * 6. حفظ الأحداث الأمنية
 * 7. Strict Mode
 */
class SmsSecurity(private val context: Context, private val db: DatabaseHelper) {

    companion object {
        private const val TAG = "SmsSecurity"
        private const val PREFS_NAME = "secure_sms_prefs"
        private const val AUDIT_LOG = "audit_log"
        private const val MAX_MESSAGE_LENGTH = 4096
        private const val RATE_LIMIT_MS = 60000L
        private const val MAX_DAILY_MESSAGES = 10
        private const val MAX_REPEAT_WARNINGS = 3
        private const val BLOCK_DURATION_MS = 86400000L
        private const val CONTEXT_TIMEOUT_MS = 600000L
        private const val HASH_TABLE = "sms_processed_hashes"
        private const val HASH_RETENTION_DAYS = 7

        private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("ar"))
    }

    // ═══ Compiled Regex Patterns (Performance) ═══
    private val suspiciousPatterns = listOf(
        Regex("http", RegexOption.IGNORE_CASE),
        Regex("www\.", RegexOption.IGNORE_CASE),
        Regex("\.com", RegexOption.IGNORE_CASE),
        Regex("بطاقة", RegexOption.IGNORE_CASE),
        Regex("رقم سري", RegexOption.IGNORE_CASE),
        Regex("cvv", RegexOption.IGNORE_CASE),
        Regex("password", RegexOption.IGNORE_CASE),
        Regex("otp", RegexOption.IGNORE_CASE),
        Regex("<script", RegexOption.IGNORE_CASE),
        Regex("javascript", RegexOption.IGNORE_CASE),
        Regex("drop table", RegexOption.IGNORE_CASE),
        Regex("delete from", RegexOption.IGNORE_CASE),
        Regex("insert into", RegexOption.IGNORE_CASE),
        Regex("update ", RegexOption.IGNORE_CASE),
        Regex("union select", RegexOption.IGNORE_CASE)
    )

    private val phoneNormalizerRegex = Regex("[^0-9]")

    // ═══ In-Memory Cache (SQLite-backed) ═══
    private val recentReplies = ConcurrentHashMap<String, Long>()
    private val blockedNumbers = ConcurrentHashMap<String, Long>()

    // ═══ Security Mode ═══
    enum class SecurityMode { RELAXED, STRICT }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 1. توليد Hash للرسالة (منع التكرار) ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * توليد Hash فريد للرسالة باستخدام:
     * - رقم المرسل (phone)
     * - نص الرسالة (message)
     * - نافذة زمنية (timeWindow) - دقيقة واحدة
     *
     * يستخدم SHA-256 بدلاً من الاعتماد على SMS ID
     */
    fun generateMessageHash(phone: String, message: String): String {
        val normalizedPhone = normalizePhone(phone)
        val normalizedMsg = message.trim().lowercase(Locale.getDefault())
        val timeWindow = System.currentTimeMillis() / 60000L
        val raw = "$normalizedPhone|$normalizedMsg|$timeWindow"
        return sha256(raw).take(16)
    }

    /**
     * التحقق مما إذا كانت الرسالة قد تمت معالجتها مسبقاً
     */
    suspend fun isSmsAlreadyProcessed(hash: String): Boolean = withContext(Dispatchers.IO) {
        val inMemory = recentReplies.containsKey("hash_$hash")
        if (inMemory) return@withContext true

        val cursor = db.readableDatabase.rawQuery(
            "SELECT 1 FROM $HASH_TABLE WHERE message_hash = ? AND processed_at > ? LIMIT 1",
            arrayOf(hash, (System.currentTimeMillis() - HASH_RETENTION_DAYS * 24L * 60 * 60 * 1000).toString())
        )
        val exists = cursor.use { it.moveToFirst() }
        if (exists) {
            recentReplies["hash_$hash"] = System.currentTimeMillis()
        }
        exists
    }

    /**
     * تسجيل الرسالة كمعالجة
     */
    suspend fun markSmsProcessed(hash: String, phone: String, message: String) = withContext(Dispatchers.IO) {
        recentReplies["hash_$hash"] = System.currentTimeMillis()

        val values = android.content.ContentValues().apply {
            put("message_hash", hash)
            put("phone", normalizePhone(phone))
            put("message_preview", message.take(100))
            put("processed_at", System.currentTimeMillis())
        }
        db.writableDatabase.insertWithOnConflict(HASH_TABLE, null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * تنظيف الـ Hashes القديمة (تشغيل كل 24 ساعة)
     */
    suspend fun cleanupSmsProcessedMessages() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - (HASH_RETENTION_DAYS * 24L * 60 * 60 * 1000)
        val deleted = db.writableDatabase.delete(
            HASH_TABLE,
            "processed_at < ?",
            arrayOf(cutoff.toString())
        )
        Log.d(TAG, "Cleaned up $deleted old SMS hashes")
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 2. التحقق من SMSC الموثوق (تحسين) ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * التحقق من SMSC بمقارنة الأرقام فقط (آخر 9 أو 12 رقماً)
     */
    fun isTrustedSmsc(smsc: String): Boolean {
        if (smsc.isEmpty()) return true

        val normalizedSmsc = normalizePhone(smsc)
        if (normalizedSmsc.isEmpty()) return true

        val trustedList = getTrustedSmscList()
        if (trustedList.isEmpty()) return true

        return trustedList.any { trusted ->
            val normalizedTrusted = normalizePhone(trusted)
            if (normalizedTrusted.isEmpty()) return@any false

            val smscSuffix = normalizedSmsc.takeLast(9)
            val trustedSuffix = normalizedTrusted.takeLast(9)

            smscSuffix == trustedSuffix ||
                    (normalizedSmsc.length >= 12 && normalizedTrusted.length >= 12 &&
                            normalizedSmsc.takeLast(12) == normalizedTrusted.takeLast(12))
        }
    }

    private fun getTrustedSmscList(): List<String> {
        val phones = mutableListOf<String>()
        val cursor = db.readableDatabase.rawQuery(
            "SELECT phone FROM sms_whitelist WHERE enabled = 1 ORDER BY name",
            null
        )
        cursor.use {
            while (it.moveToNext()) phones.add(it.getString(0))
        }
        return phones
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 3. كشف الرسائل المشبوهة ═══
    // ═══════════════════════════════════════════════════════════════

    fun isSuspiciousMessage(msgBody: String): Boolean {
        val lower = msgBody.lowercase(Locale.getDefault())
        return suspiciousPatterns.any { it.containsMatchIn(lower) }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 4. حماية الرسائل الطويلة ═══
    // ═══════════════════════════════════════════════════════════════

    fun isMessageTooLong(totalLength: Int): Boolean {
        return totalLength > MAX_MESSAGE_LENGTH
    }

    fun getMaxMessageLength(): Int = MAX_MESSAGE_LENGTH

    // ═══════════════════════════════════════════════════════════════
    // ═══ 5. حماية استنزاف SMS (Rate Limiting مع SQLite) ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun canProcessMessage(
        sender: String,
        customerName: String,
        isContextReply: Boolean
    ): RateLimitResult = withContext(Dispatchers.IO) {
        val normalizedSender = normalizePhone(sender)
        val lastReply = getLastReplyTime(normalizedSender)
        val timeSinceLast = System.currentTimeMillis() - lastReply

        if (timeSinceLast < RATE_LIMIT_MS && !isContextReply) {
            val count = getDailyMessageCount(normalizedSender)
            val currentCount = count + 1
            incrementDailyCount(normalizedSender)

            if (currentCount >= MAX_DAILY_MESSAGES) {
                blockNumber(normalizedSender)
                val managerPhone = getManagerPhone()
                logSecurityEvent("RATE_LIMIT_BLOCK", sender, "Daily limit exceeded: $currentCount")
                return@withContext RateLimitResult.BLOCKED(
                    "⚠️ $customerName،\n" +
                    "لقد تجاوزت الحد المسموح من الرسائل اليوم.\n" +
                    "تم حظر رقمك مؤقتاً لمدة 24 ساعة.\n" +
                    "للاستفسار العاجل: ${managerPhone ?: "غير متوفر"}",
                    managerPhone
                )
            }

            val warningCount = getWarningCount(normalizedSender) + 1
            incrementWarningCount(normalizedSender)

            if (warningCount >= MAX_REPEAT_WARNINGS) {
                blockNumber(normalizedSender)
                val managerPhone = getManagerPhone()
                logSecurityEvent("REPEAT_BLOCK", sender, "Repeat warnings: $warningCount")
                return@withContext RateLimitResult.BLOCKED(
                    "🚫 $customerName،\n" +
                    "لقد أرسلت رسائل متكررة كثيرة.\n" +
                    "تم حظر رقمك مؤقتاً لمدة 24 ساعة.\n" +
                    "للاستفسار: ${managerPhone ?: "غير متوفر"}",
                    managerPhone
                )
            }

            return@withContext RateLimitResult.WARNING(
                "⚠️ $customerName،\n" +
                "لقد أرسلت رسائل متكررة.\n" +
                "يرجى تحديد ما تريده في رسالة واحدة بدقة.\n" +
                "تحذير $warningCount من $MAX_REPEAT_WARNINGS"
            )
        }

        updateLastReplyTime(normalizedSender)
        incrementDailyCount(normalizedSender)

        if (timeSinceLast > 300000L) {
            resetWarnings(normalizedSender)
        }

        RateLimitResult.ALLOWED
    }

    fun isBlocked(phone: String): Boolean {
        val normalized = normalizePhone(phone)
        val blockEnd = blockedNumbers[normalized]
            ?: getBlockedUntilFromDb(normalized)
            ?: return false

        return if (System.currentTimeMillis() < blockEnd) {
            blockedNumbers[normalized] = blockEnd
            true
        } else {
            blockedNumbers.remove(normalized)
            unblockInDb(normalized)
            false
        }
    }

    // ═══ Rate Limiting - SQLite Backed ═══

    private fun getLastReplyTime(phone: String): Long {
        val cursor = db.readableDatabase.rawQuery(
            "SELECT last_reply_at FROM sms_rate_limits WHERE phone = ? LIMIT 1",
            arrayOf(phone)
        )
        return cursor.use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
    }

    private fun updateLastReplyTime(phone: String) {
        val values = android.content.ContentValues().apply {
            put("phone", phone)
            put("last_reply_at", System.currentTimeMillis())
        }
        db.writableDatabase.insertWithOnConflict(
            "sms_rate_limits", null, values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun getDailyMessageCount(phone: String): Int {
        val today = getTodayStart()
        val cursor = db.readableDatabase.rawQuery(
            "SELECT daily_count FROM sms_rate_limits WHERE phone = ? AND day_start = ? LIMIT 1",
            arrayOf(phone, today.toString())
        )
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private fun incrementDailyCount(phone: String) {
        val today = getTodayStart()
        val current = getDailyMessageCount(phone)
        val values = android.content.ContentValues().apply {
            put("phone", phone)
            put("day_start", today)
            put("daily_count", current + 1)
            put("last_reply_at", System.currentTimeMillis())
        }
        db.writableDatabase.insertWithOnConflict(
            "sms_rate_limits", null, values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun getWarningCount(phone: String): Int {
        val cursor = db.readableDatabase.rawQuery(
            "SELECT warning_count FROM sms_rate_limits WHERE phone = ? LIMIT 1",
            arrayOf(phone)
        )
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private fun incrementWarningCount(phone: String) {
        val current = getWarningCount(phone)
        val values = android.content.ContentValues().apply {
            put("warning_count", current + 1)
        }
        db.writableDatabase.update(
            "sms_rate_limits", values, "phone = ?", arrayOf(phone)
        )
    }

    private fun resetWarnings(phone: String) {
        val values = android.content.ContentValues().apply {
            put("warning_count", 0)
        }
        db.writableDatabase.update(
            "sms_rate_limits", values, "phone = ?", arrayOf(phone)
        )
    }

    private fun blockNumber(phone: String) {
        val blockEnd = System.currentTimeMillis() + BLOCK_DURATION_MS
        blockedNumbers[phone] = blockEnd
        val values = android.content.ContentValues().apply {
            put("blocked_until", blockEnd)
        }
        db.writableDatabase.update(
            "sms_rate_limits", values, "phone = ?", arrayOf(phone)
        )
    }

    private fun getBlockedUntilFromDb(phone: String): Long? {
        val cursor = db.readableDatabase.rawQuery(
            "SELECT blocked_until FROM sms_rate_limits WHERE phone = ? AND blocked_until > ? LIMIT 1",
            arrayOf(phone, System.currentTimeMillis().toString())
        )
        return cursor.use {
            if (it.moveToFirst()) it.getLong(0) else null
        }
    }

    private fun unblockInDb(phone: String) {
        val values = android.content.ContentValues().apply {
            put("blocked_until", 0L)
            put("daily_count", 0)
            put("warning_count", 0)
        }
        db.writableDatabase.update(
            "sms_rate_limits", values, "phone = ?", arrayOf(phone)
        )
    }

    private fun getTodayStart(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 6. Strict Mode ═══
    // ═══════════════════════════════════════════════════════════════

    fun getSecurityMode(): SecurityMode {
        val mode = getSystemSetting("sms_security_mode", "relaxed")
        return if (mode == "strict") SecurityMode.STRICT else SecurityMode.RELAXED
    }

    fun isStrictMode(): Boolean = getSecurityMode() == SecurityMode.STRICT

    // ═══════════════════════════════════════════════════════════════
    // ═══ 7. حفظ الأحداث الأمنية (Structured) ═══
    // ═══════════════════════════════════════════════════════════════

    fun logSecurityEvent(event: String, phone: String, details: String) {
        try {
            val prefs = getSecurePrefs()
            val timestamp = dateFormat.format(Date())
            val phoneHash = sha256(phone).take(16)

            val structuredLog = JSONObject().apply {
                put("timestamp", timestamp)
                put("event", event)
                put("phone_hash", phoneHash)
                put("details", details.take(200))
                put("device", android.os.Build.MODEL)
                put("android_version", android.os.Build.VERSION.RELEASE)
                put("sim_operator", getSimOperator())
                put("smsc", "")
            }

            val entry = structuredLog.toString()
            val existing = prefs.getString(AUDIT_LOG, "") ?: ""
            val updated = if (existing.length > 10000) entry else "$existing\n$entry"
            prefs.edit().putString(AUDIT_LOG, updated).apply()
            Log.i(TAG, "SECURITY: $event | $phoneHash | $details")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log security event")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ 8. إصلاح normalizePhone ═══
    // ═══════════════════════════════════════════════════════════════

    /**
     * توحيد جميع صيغ الأرقام إلى: 777123456
     */

    private fun normalizePhone(phone: String): String {
        return PhoneUtils.normalize(phone)
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ أدوات مساعدة ═══
    // ═══════════════════════════════════════════════════════════════

    private fun sha256(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun getSecurePrefs(): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create encrypted prefs, falling back")
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun getManagerPhone(): String? {
        val cursor = db.readableDatabase.rawQuery("""
            SELECT u.phone FROM users u
            JOIN roles r ON u.role_id = r.id
            WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN', 'STATION_MANAGER')
              AND u.status = 'active' AND u.is_deleted = 0
            ORDER BY r.level ASC LIMIT 1
        """, null)
        return cursor.use {
            if (it.moveToFirst()) it.getString(0) else null
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

    private fun getSimOperator(): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            tm.simOperatorName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    // ═══ نتائج Rate Limiting ═══
    sealed class RateLimitResult {
        object ALLOWED : RateLimitResult()
        data class WARNING(val message: String) : RateLimitResult()
        data class BLOCKED(val message: String, val managerPhone: String?) : RateLimitResult()
    }
}
