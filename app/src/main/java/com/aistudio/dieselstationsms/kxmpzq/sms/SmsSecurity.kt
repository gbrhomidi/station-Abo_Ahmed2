package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.utils.PhoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ═══════════════════════════════════════════════════════════════
 * طبقة الأمان المتقدمة - SmsSecurity
 * الإصدار المصحح والمحدث
 * ═══════════════════════════════════════════════════════════════
 *
 * المسؤوليات:
 *
 * 1. منع معالجة الرسالة نفسها أكثر من مرة.
 * 2. إنشاء Hash ثابت للرسالة.
 * 3. التحقق من قائمة أرقام SMS الموثوقة.
 * 4. كشف الرسائل المشبوهة.
 * 5. حماية النظام من إساءة استخدام SMS.
 * 6. Rate Limiting يومي وزمني.
 * 7. حظر الأرقام المخالفة مؤقتاً.
 * 8. تنظيف سجلات منع التكرار القديمة.
 * 9. Strict / Relaxed Security Mode.
 * 10. تسجيل الأحداث الأمنية بدون كشف البيانات الحساسة.
 *
 * متوافق مع DatabaseHelper V13:
 *
 * sms_processed_hashes:
 *   message_hash
 *   phone
 *   message_preview
 *   processed_at
 *
 * sms_rate_limits:
 *   phone
 *   last_reply_at
 *   day_start
 *   daily_count
 *   warning_count
 *   blocked_until
 *
 * sms_whitelist:
 *   phone
 *   name
 *   enabled
 */
class SmsSecurity(
    private val context: Context,
    private val db: DatabaseHelper
) {

    companion object {
        private const val TAG = "SmsSecurity"

        private const val PREFS_NAME = "secure_sms_prefs"
        private const val AUDIT_LOG = "audit_log"

        private const val HASH_TABLE = "sms_processed_hashes"
        private const val RATE_LIMIT_TABLE = "sms_rate_limits"
        private const val WHITELIST_TABLE = "sms_whitelist"

        private const val MAX_MESSAGE_LENGTH = 4096

        /**
         * أقل مدة بين ردين عاديين من نفس الرقم.
         */
        private const val RATE_LIMIT_MS = 60_000L

        /**
         * الحد الأقصى للرسائل اليومية.
         */
        private const val MAX_DAILY_MESSAGES = 10

        /**
         * عدد التحذيرات المتتالية قبل الحظر.
         */
        private const val MAX_REPEAT_WARNINGS = 3

        /**
         * مدة الحظر: 24 ساعة.
         */
        private const val BLOCK_DURATION_MS = 24L * 60L * 60L * 1000L

        /**
         * بعد هذه المدة يتم تصفير تحذيرات التكرار.
         */
        private const val WARNING_RESET_MS = 5L * 60L * 1000L

        /**
         * مدة الاحتفاظ بسجلات Hash.
         */
        private const val HASH_RETENTION_DAYS = 7

        /**
         * الحد الأقصى لسجل التدقيق المخزن في SharedPreferences.
         */
        private const val MAX_AUDIT_LOG_LENGTH = 10_000

        /**
         * لتجنب تعارض تحديثات Rate Limit من أكثر من Coroutine.
         */
        private val rateLimitLock = Any()
    }

    /**
     * أوضاع الحماية.
     */
    enum class SecurityMode {
        RELAXED,
        STRICT
    }

    /**
     * ذاكرة مؤقتة لمنع عمليات القراءة المتكررة من SQLite.
     */
    private val recentProcessedHashes = ConcurrentHashMap<String, Long>()

    /**
     * ذاكرة مؤقتة للحظر.
     */
    private val blockedNumbers = ConcurrentHashMap<String, Long>()

    /**
     * أنماط الرسائل المشبوهة.
     *
     * ملاحظة:
     * وجود كلمة مثل "بطاقة" أو "OTP" لا يعني بالضرورة أن
     * الرسالة ضارة، لذلك هذه الدالة تستخدم كإشارة أمنية
     * وليست قرار رفض نهائي وحدها.
     */
    private val suspiciousPatterns = listOf(
        Regex("""https?://""", RegexOption.IGNORE_CASE),
        Regex("""www\.""", RegexOption.IGNORE_CASE),
        Regex("""\.com\b""", RegexOption.IGNORE_CASE),
        Regex("""\.net\b""", RegexOption.IGNORE_CASE),
        Regex("""\.org\b""", RegexOption.IGNORE_CASE),

        Regex("بطاقة", RegexOption.IGNORE_CASE),
        Regex("رقم\\s+سري", RegexOption.IGNORE_CASE),
        Regex("cvv", RegexOption.IGNORE_CASE),
        Regex("password", RegexOption.IGNORE_CASE),
        Regex("otp", RegexOption.IGNORE_CASE),

        Regex("<script", RegexOption.IGNORE_CASE),
        Regex("javascript\\s*:", RegexOption.IGNORE_CASE),

        Regex("drop\\s+table", RegexOption.IGNORE_CASE),
        Regex("delete\\s+from", RegexOption.IGNORE_CASE),
        Regex("insert\\s+into", RegexOption.IGNORE_CASE),
        Regex("update\\s+", RegexOption.IGNORE_CASE),
        Regex("union\\s+select", RegexOption.IGNORE_CASE)
    )

    // ═══════════════════════════════════════════════════════════════
    // 1. Hash الرسالة ومنع التكرار
    // ═══════════════════════════════════════════════════════════════

    /**
     * إنشاء Hash ثابت للرسالة.
     *
     * النسخة السابقة كانت تضيف نافذة زمنية قدرها دقيقة:
     *
     * phone + message + minute
     *
     * وهذا يعني أن نفس الرسالة يمكن أن تحصل على Hash جديد
     * بعد انتقالها إلى الدقيقة التالية.
     *
     * هنا أصبح Hash ثابتاً بالنسبة إلى:
     *
     * sender + normalized message
     *
     * مع اعتماد مدة الاحتفاظ في قاعدة البيانات لتحديد مدة
     * منع التكرار.
     */
    fun generateMessageHash(
        phone: String,
        message: String
    ): String {
        val normalizedPhone = normalizePhone(phone)
        val normalizedMessage = normalizeMessage(message)

        val raw = "$normalizedPhone|$normalizedMessage"

        return sha256(raw).take(32)
    }

    /**
     * التحقق من معالجة الرسالة مسبقاً.
     *
     * لا تعتمد هذه الدالة على الذاكرة فقط؛ بل تتحقق من SQLite
     * أيضاً حتى يستمر منع التكرار بعد إعادة تشغيل التطبيق.
     */
    suspend fun isSmsAlreadyProcessed(
        hash: String
    ): Boolean = withContext(Dispatchers.IO) {

        if (hash.isBlank()) {
            return@withContext false
        }

        val cachedAt = recentProcessedHashes[hash]
        if (cachedAt != null) {
            return@withContext true
        }

        try {
            val cutoff = System.currentTimeMillis() -
                    (HASH_RETENTION_DAYS * 24L * 60L * 60L * 1000L)

            val cursor = db.readableDatabase.rawQuery(
                """
                SELECT 1
                FROM $HASH_TABLE
                WHERE message_hash = ?
                  AND processed_at >= ?
                LIMIT 1
                """.trimIndent(),
                arrayOf(
                    hash,
                    cutoff.toString()
                )
            )

            val exists = cursor.use {
                it.moveToFirst()
            }

            if (exists) {
                recentProcessedHashes[hash] = System.currentTimeMillis()
            }

            exists
        } catch (e: SQLiteException) {
            Log.e(
                TAG,
                "Failed to check SMS duplicate status: ${e.javaClass.simpleName}",
                e
            )

            /**
             * في حالة فشل قاعدة البيانات لا نعتبر الرسالة مكررة
             * حتى لا يؤدي خلل أمني ثانوي إلى إسقاط نظام SMS بالكامل.
             */
            false
        }
    }

    /**
     * تسجيل Hash الرسالة كرسالة تمت معالجتها.
     */
    suspend fun markSmsProcessed(
        hash: String,
        phone: String,
        message: String
    ) = withContext(Dispatchers.IO) {

        if (hash.isBlank()) {
            return@withContext
        }

        val now = System.currentTimeMillis()
        val normalizedPhone = normalizePhone(phone)

        recentProcessedHashes[hash] = now

        try {
            val values = ContentValues().apply {
                put("message_hash", hash)
                put("phone", normalizedPhone)
                put(
                    "message_preview",
                    sanitizePreview(message).take(100)
                )
                put("processed_at", now)
            }

            db.writableDatabase.insertWithOnConflict(
                HASH_TABLE,
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE
            )
        } catch (e: SQLiteException) {
            Log.e(
                TAG,
                "Failed to persist SMS processed hash: ${e.javaClass.simpleName}",
                e
            )
        }
    }

    /**
     * تنظيف Hashes القديمة.
     */
    suspend fun cleanupSmsProcessedMessages() =
        withContext(Dispatchers.IO) {

            val cutoff = System.currentTimeMillis() -
                    (HASH_RETENTION_DAYS * 24L * 60L * 60L * 1000L)

            try {
                val deleted = db.writableDatabase.delete(
                    HASH_TABLE,
                    "processed_at < ?",
                    arrayOf(cutoff.toString())
                )

                cleanupInMemoryProcessedHashes(cutoff)

                Log.d(
                    TAG,
                    "Cleaned old SMS hashes: deleted=$deleted"
                )
            } catch (e: SQLiteException) {
                Log.e(
                    TAG,
                    "Failed to cleanup SMS hashes: ${e.javaClass.simpleName}",
                    e
                )
            }
        }

    private fun cleanupInMemoryProcessedHashes(cutoff: Long) {
        recentProcessedHashes.entries.removeIf {
            it.value < cutoff
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. التحقق من SMSC / الرقم الموثوق
    // ═══════════════════════════════════════════════════════════════

    /**
     * التحقق من الرقم مقابل قائمة sms_whitelist.
     *
     * ملاحظة مهمة:
     * جدول sms_whitelist في DatabaseHelper V13 يحتوي على أرقام
     * وليس على جدول مستقل لمراكز SMSC.
     *
     * لذلك هذه الدالة تتحقق من الرقم الوارد إليها مقابل القائمة
     * البيضاء دون افتراض وجود جدول SMSC آخر.
     */
    fun isTrustedSmsc(smsc: String): Boolean {
        if (smsc.isBlank()) {
            return true
        }

        val normalizedSmsc = normalizePhone(smsc)

        if (normalizedSmsc.isBlank()) {
            return true
        }

        return try {
            val trustedList = getTrustedSmscList()

            /**
             * عدم وجود قائمة بيضاء يعني عدم فرض سياسة منع إضافية.
             */
            if (trustedList.isEmpty()) {
                return true
            }

            trustedList.any { trusted ->
                phonesMatch(normalizedSmsc, normalizePhone(trusted))
            }
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Trusted SMSC check failed: ${e.javaClass.simpleName}",
                e
            )

            /**
             * الفشل لا يجب أن يحول النظام تلقائياً إلى حظر شامل.
             */
            !isStrictMode()
        }
    }

    private fun getTrustedSmscList(): List<String> {
        val result = mutableListOf<String>()

        try {
            db.readableDatabase.rawQuery(
                """
                SELECT phone
                FROM $WHITELIST_TABLE
                WHERE enabled = 1
                ORDER BY name, phone
                """.trimIndent(),
                null
            ).use { cursor ->

                while (cursor.moveToNext()) {
                    val phone = cursor.getString(0)

                    if (!phone.isNullOrBlank()) {
                        result.add(phone)
                    }
                }
            }
        } catch (e: SQLiteException) {
            Log.e(
                TAG,
                "Failed to load SMS whitelist: ${e.javaClass.simpleName}",
                e
            )
        }

        return result
    }

    /**
     * مقارنة رقمين بصورة أكثر مرونة.
     *
     * نستخدم آخر 9 أرقام عند عدم توفر صيغة دولية موحدة.
     */
    private fun phonesMatch(
        first: String,
        second: String
    ): Boolean {

        if (first.isBlank() || second.isBlank()) {
            return false
        }

        if (first == second) {
            return true
        }

        val firstSuffix = first.takeLast(9)
        val secondSuffix = second.takeLast(9)

        return firstSuffix.length == 9 &&
                secondSuffix.length == 9 &&
                firstSuffix == secondSuffix
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. كشف الرسائل المشبوهة
    // ═══════════════════════════════════════════════════════════════

    fun isSuspiciousMessage(
        msgBody: String
    ): Boolean {

        if (msgBody.isBlank()) {
            return false
        }

        val normalized = msgBody
            .trim()
            .lowercase(Locale.getDefault())

        return suspiciousPatterns.any {
            it.containsMatchIn(normalized)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 4. حماية الرسائل الطويلة
    // ═══════════════════════════════════════════════════════════════

    fun isMessageTooLong(
        totalLength: Int
    ): Boolean {
        return totalLength > MAX_MESSAGE_LENGTH
    }

    fun getMaxMessageLength(): Int {
        return MAX_MESSAGE_LENGTH
    }

    // ═══════════════════════════════════════════════════════════════
    // 5. Rate Limiting
    // ═══════════════════════════════════════════════════════════════

    /**
     * التحقق من إمكانية معالجة رسالة جديدة.
     *
     * السياسة:
     *
     * - الرقم المحظور => BLOCKED.
     * - تجاوز الحد اليومي => BLOCKED.
     * - رسالة متكررة خلال دقيقة => WARNING.
     * - تكرار التحذير 3 مرات => BLOCKED.
     * - مرور أكثر من 5 دقائق => تصفير تحذيرات التكرار.
     * - الرسالة المسموحة => زيادة العداد اليومي وتحديث آخر رد.
     */
    suspend fun canProcessMessage(
        sender: String,
        customerName: String,
        isContextReply: Boolean
    ): RateLimitResult = withContext(Dispatchers.IO) {

        val normalizedSender = normalizePhone(sender)

        if (normalizedSender.isBlank()) {
            return@withContext RateLimitResult.BLOCKED(
                message = "⚠️ تعذر التحقق من رقم المرسل.",
                managerPhone = null
            )
        }

        synchronized(rateLimitLock) {

            try {
                val now = System.currentTimeMillis()
                val state = getRateLimitState(normalizedSender)

                /**
                 * 1. التحقق من الحظر الحالي.
                 */
                if (state.blockedUntil > now) {
                    val managerPhone = getManagerPhone()

                    return@synchronized RateLimitResult.BLOCKED(
                        message = buildBlockedMessage(
                            customerName,
                            managerPhone
                        ),
                        managerPhone = managerPhone
                    )
                }

                /**
                 * 2. إذا انتهى الحظر، ننظف الحالة.
                 */
                if (state.blockedUntil > 0L &&
                    state.blockedUntil <= now
                ) {
                    clearExpiredBlock(normalizedSender)
                    state.blockedUntil = 0L
                    state.dailyCount = 0
                    state.warningCount = 0
                }

                /**
                 * 3. تحديد بداية اليوم.
                 */
                val todayStart = getTodayStart()

                if (state.dayStart != todayStart) {
                    state.dayStart = todayStart
                    state.dailyCount = 0
                    state.warningCount = 0
                }

                /**
                 * 4. الحد اليومي يتم فحصه دائماً.
                 *
                 * هذه نقطة مهمة:
                 * النسخة القديمة كانت تفحص الحد اليومي فقط داخل
                 * مسار الرسائل المتكررة، مما يسمح بتجاوزه بسهولة.
                 */
                if (state.dailyCount >= MAX_DAILY_MESSAGES) {

                    val blockEnd = now + BLOCK_DURATION_MS

                    state.blockedUntil = blockEnd

                    saveRateLimitState(
                        normalizedSender,
                        state.copy(
                            lastReplyAt = now,
                            dayStart = todayStart
                        )
                    )

                    val managerPhone = getManagerPhone()

                    logSecurityEvent(
                        event = "RATE_LIMIT_BLOCK",
                        phone = normalizedSender,
                        details = "daily_limit=$MAX_DAILY_MESSAGES"
                    )

                    return@synchronized RateLimitResult.BLOCKED(
                        message = buildDailyLimitMessage(
                            customerName,
                            managerPhone
                        ),
                        managerPhone = managerPhone
                    )
                }

                /**
                 * 5. حساب مدة آخر رسالة.
                 */
                val timeSinceLastReply =
                    if (state.lastReplyAt > 0L) {
                        now - state.lastReplyAt
                    } else {
                        Long.MAX_VALUE
                    }

                /**
                 * 6. رسالة متكررة.
                 *
                 * isContextReply يسمح بالردود التابعة لسياق
                 * المحادثة دون تطبيق تحذير التكرار الزمني.
                 */
                if (
                    timeSinceLastReply < RATE_LIMIT_MS &&
                    !isContextReply
                ) {

                    state.dailyCount += 1
                    state.warningCount += 1
                    state.lastReplyAt = now

                    if (
                        state.dailyCount >= MAX_DAILY_MESSAGES ||
                        state.warningCount >= MAX_REPEAT_WARNINGS
                    ) {

                        val blockEnd = now + BLOCK_DURATION_MS
                        state.blockedUntil = blockEnd

                        saveRateLimitState(
                            normalizedSender,
                            state.copy(dayStart = todayStart)
                        )

                        val managerPhone = getManagerPhone()

                        logSecurityEvent(
                            event = if (
                                state.dailyCount >= MAX_DAILY_MESSAGES
                            ) {
                                "RATE_LIMIT_BLOCK"
                            } else {
                                "REPEAT_BLOCK"
                            },
                            phone = normalizedSender,
                            details = "daily_count=${state.dailyCount};warnings=${state.warningCount}"
                        )

                        return@synchronized RateLimitResult.BLOCKED(
                            message = if (
                                state.dailyCount >= MAX_DAILY_MESSAGES
                            ) {
                                buildDailyLimitMessage(
                                    customerName,
                                    managerPhone
                                )
                            } else {
                                buildRepeatBlockMessage(
                                    customerName,
                                    managerPhone
                                )
                            },
                            managerPhone = managerPhone
                        )
                    }

                    saveRateLimitState(
                        normalizedSender,
                        state.copy(dayStart = todayStart)
                    )

                    return@synchronized RateLimitResult.WARNING(
                        message = buildWarningMessage(
                            customerName,
                            state.warningCount
                        )
                    )
                }

                /**
                 * 7. مرور فترة كافية.
                 * نعيد تصفير التحذيرات.
                 */
                if (
                    state.lastReplyAt > 0L &&
                    timeSinceLastReply >= WARNING_RESET_MS
                ) {
                    state.warningCount = 0
                }

                /**
                 * 8. رسالة مسموحة.
                 */
                state.dailyCount += 1
                state.lastReplyAt = now
                state.dayStart = todayStart

                saveRateLimitState(
                    normalizedSender,
                    state
                )

                RateLimitResult.ALLOWED

            } catch (e: Exception) {

                val errorId = UUID.randomUUID()
                    .toString()
                    .take(8)

                Log.e(
                    TAG,
                    "Rate-limit check failed [ErrorID=$errorId] " +
                            "type=${e.javaClass.simpleName}",
                    e
                )

                /**
                 * في حالة فشل قاعدة البيانات:
                 *
                 * لا نرسل رسالة خطأ داخلية للمستخدم.
                 * نسمح بالمعالجة حتى لا يتوقف النظام بالكامل.
                 */
                RateLimitResult.ALLOWED
            }
        }
    }

    /**
     * التحقق من الحظر فقط.
     */
    fun isBlocked(
        phone: String
    ): Boolean {

        val normalized = normalizePhone(phone)

        if (normalized.isBlank()) {
            return false
        }

        val cached = blockedNumbers[normalized]

        if (cached != null) {
            if (System.currentTimeMillis() < cached) {
                return true
            }

            blockedNumbers.remove(normalized)
        }

        return try {

            val blockedUntil = getBlockedUntilFromDb(normalized)

            if (
                blockedUntil != null &&
                blockedUntil > System.currentTimeMillis()
            ) {
                blockedNumbers[normalized] = blockedUntil
                true
            } else {

                if (blockedUntil != null) {
                    clearExpiredBlock(normalized)
                }

                false
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Blocked-number check failed: ${e.javaClass.simpleName}",
                e
            )

            false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Rate Limit State
    // ═══════════════════════════════════════════════════════════════

    private data class RateLimitState(
        var lastReplyAt: Long = 0L,
        var dayStart: Long = 0L,
        var dailyCount: Int = 0,
        var warningCount: Int = 0,
        var blockedUntil: Long = 0L
    )

    private fun getRateLimitState(
        phone: String
    ): RateLimitState {

        return try {

            db.readableDatabase.rawQuery(
                """
                SELECT
                    last_reply_at,
                    day_start,
                    daily_count,
                    warning_count,
                    blocked_until
                FROM $RATE_LIMIT_TABLE
                WHERE phone = ?
                LIMIT 1
                """.trimIndent(),
                arrayOf(phone)
            ).use { cursor ->

                if (!cursor.moveToFirst()) {
                    return@use RateLimitState()
                }

                RateLimitState(
                    lastReplyAt = cursor.getLong(0),
                    dayStart = cursor.getLong(1),
                    dailyCount = cursor.getInt(2),
                    warningCount = cursor.getInt(3),
                    blockedUntil = cursor.getLong(4)
                )
            }

        } catch (e: SQLiteException) {

            Log.e(
                TAG,
                "Failed to read rate-limit state: ${e.javaClass.simpleName}",
                e
            )

            RateLimitState()
        }
    }

    /**
     * حفظ الحالة بطريقة update -> insert.
     *
     * لا نستخدم CONFLICT_REPLACE هنا لأن الجدول يحتوي على:
     *
     * id INTEGER PRIMARY KEY
     * phone TEXT UNIQUE
     *
     * وREPLACE قد يؤدي إلى حذف الصف وإعادة إنشائه،
     * وهو سلوك غير مرغوب للحالة الأمنية.
     */
    private fun saveRateLimitState(
        phone: String,
        state: RateLimitState
    ) {

        val now = System.currentTimeMillis()

        val values = ContentValues().apply {
            put("last_reply_at", state.lastReplyAt)
            put("day_start", state.dayStart)
            put("daily_count", state.dailyCount.coerceAtLeast(0))
            put("warning_count", state.warningCount.coerceAtLeast(0))
            put("blocked_until", state.blockedUntil.coerceAtLeast(0L))
            put(
                "updated_at",
                formatDatabaseDate(now)
            )
        }

        val db = db.writableDatabase

        var updated = db.update(
            RATE_LIMIT_TABLE,
            values,
            "phone = ?",
            arrayOf(phone)
        )

        if (updated == 0) {

            val insertValues = ContentValues(values).apply {
                put("phone", phone)
            }

            val inserted = db.insertWithOnConflict(
                RATE_LIMIT_TABLE,
                null,
                insertValues,
                SQLiteDatabase.CONFLICT_IGNORE
            )

            if (inserted == -1L) {
                /**
                 * حالة race condition:
                 * Coroutine أخرى ربما أنشأت الصف بين update وinsert.
                 */
                updated = db.update(
                    RATE_LIMIT_TABLE,
                    values,
                    "phone = ?",
                    arrayOf(phone)
                )
            }
        }

        if (
            state.blockedUntil > now
        ) {
            blockedNumbers[phone] = state.blockedUntil
        } else {
            blockedNumbers.remove(phone)
        }
    }

    /**
     * الدالة موجودة للحفاظ على التوافق مع التصميم السابق.
     */
    private fun getLastReplyTime(
        phone: String
    ): Long {
        return getRateLimitState(
            normalizePhone(phone)
        ).lastReplyAt
    }

    /**
     * تحديث آخر وقت رد.
     */
    private fun updateLastReplyTime(
        phone: String
    ) {

        val normalized = normalizePhone(phone)

        if (normalized.isBlank()) {
            return
        }

        synchronized(rateLimitLock) {

            val state = getRateLimitState(normalized)

            state.lastReplyAt = System.currentTimeMillis()
            state.dayStart = getTodayStart()

            saveRateLimitState(
                normalized,
                state
            )
        }
    }

    /**
     * الحصول على عدد الرسائل اليومية.
     */
    private fun getDailyMessageCount(
        phone: String
    ): Int {

        val normalized = normalizePhone(phone)

        if (normalized.isBlank()) {
            return 0
        }

        val state = getRateLimitState(normalized)
        val today = getTodayStart()

        return if (state.dayStart == today) {
            state.dailyCount.coerceAtLeast(0)
        } else {
            0
        }
    }

    /**
     * زيادة العداد اليومي.
     */
    private fun incrementDailyCount(
        phone: String
    ) {

        val normalized = normalizePhone(phone)

        if (normalized.isBlank()) {
            return
        }

        synchronized(rateLimitLock) {

            val today = getTodayStart()
            val state = getRateLimitState(normalized)

            if (state.dayStart != today) {
                state.dayStart = today
                state.dailyCount = 0
                state.warningCount = 0
            }

            state.dailyCount += 1
            state.lastReplyAt = System.currentTimeMillis()

            saveRateLimitState(
                normalized,
                state
            )
        }
    }

    /**
     * الحصول على عدد التحذيرات.
     */
    private fun getWarningCount(
        phone: String
    ): Int {
        return getRateLimitState(
            normalizePhone(phone)
        ).warningCount.coerceAtLeast(0)
    }

    /**
     * زيادة عدد التحذيرات.
     */
    private fun incrementWarningCount(
        phone: String
    ) {

        val normalized = normalizePhone(phone)

        if (normalized.isBlank()) {
            return
        }

        synchronized(rateLimitLock) {

            val state = getRateLimitState(normalized)

            state.warningCount += 1

            saveRateLimitState(
                normalized,
                state
            )
        }
    }

    /**
     * تصفير التحذيرات.
     */
    private fun resetWarnings(
        phone: String
    ) {

        val normalized = normalizePhone(phone)

        if (normalized.isBlank()) {
            return
        }

        synchronized(rateLimitLock) {

            val state = getRateLimitState(normalized)

            state.warningCount = 0

            saveRateLimitState(
                normalized,
                state
            )
        }
    }

    /**
     * حظر رقم.
     */
    private fun blockNumber(
        phone: String
    ) {

        val normalized = normalizePhone(phone)

        if (normalized.isBlank()) {
            return
        }

        synchronized(rateLimitLock) {

            val state = getRateLimitState(normalized)

            state.blockedUntil =
                System.currentTimeMillis() + BLOCK_DURATION_MS

            saveRateLimitState(
                normalized,
                state
            )
        }
    }

    /**
     * قراءة وقت انتهاء الحظر.
     */
    private fun getBlockedUntilFromDb(
        phone: String
    ): Long? {

        val normalized = normalizePhone(phone)

        if (normalized.isBlank()) {
            return null
        }

        return try {

            db.readableDatabase.rawQuery(
                """
                SELECT blocked_until
                FROM $RATE_LIMIT_TABLE
                WHERE phone = ?
                LIMIT 1
                """.trimIndent(),
                arrayOf(normalized)
            ).use { cursor ->

                if (!cursor.moveToFirst()) {
                    null
                } else {

                    val value = cursor.getLong(0)

                    if (value > 0L) {
                        value
                    } else {
                        null
                    }
                }
            }

        } catch (e: SQLiteException) {

            Log.e(
                TAG,
                "Failed to read blocked-until: ${e.javaClass.simpleName}",
                e
            )

            null
        }
    }

    /**
     * إزالة الحظر المنتهي.
     */
    private fun clearExpiredBlock(
        phone: String
    ) {

        val normalized = normalizePhone(phone)

        if (normalized.isBlank()) {
            return
        }

        try {

            val values = ContentValues().apply {
                put("blocked_until", 0L)
                put("warning_count", 0)
                put(
                    "updated_at",
                    formatDatabaseDate(
                        System.currentTimeMillis()
                    )
                )
            }

            db.writableDatabase.update(
                RATE_LIMIT_TABLE,
                values,
                "phone = ?",
                arrayOf(normalized)
            )

            blockedNumbers.remove(normalized)

        } catch (e: SQLiteException) {

            Log.e(
                TAG,
                "Failed to clear expired block: ${e.javaClass.simpleName}",
                e
            )
        }
    }

    /**
     * الحفاظ على اسم الدالة القديمة للتوافق.
     */
    private fun unblockInDb(
        phone: String
    ) {
        clearExpiredBlock(phone)
    }

    // ═══════════════════════════════════════════════════════════════
    // Date / Phone Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun getTodayStart(): Long {

        val calendar = Calendar.getInstance()

        calendar.set(
            Calendar.HOUR_OF_DAY,
            0
        )
        calendar.set(
            Calendar.MINUTE,
            0
        )
        calendar.set(
            Calendar.SECOND,
            0
        )
        calendar.set(
            Calendar.MILLISECOND,
            0
        )

        return calendar.timeInMillis
    }

    private fun normalizePhone(
        phone: String?
    ): String {

        if (phone.isNullOrBlank()) {
            return ""
        }

        return try {
            PhoneUtils.normalize(phone)
                ?.trim()
                ?.filter { it.isDigit() }
                .orEmpty()
        } catch (e: Exception) {

            phone.filter { it.isDigit() }
        }
    }

    private fun normalizeMessage(
        message: String?
    ): String {

        if (message.isNullOrBlank()) {
            return ""
        }

        return message
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.getDefault())
    }

    private fun sanitizePreview(
        message: String?
    ): String {

        if (message.isNullOrBlank()) {
            return ""
        }

        return message
            .replace("\u0000", "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun formatDatabaseDate(
        timestamp: Long
    ): String {

        return SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date(timestamp))
    }

    private fun formatAuditTimestamp(
        timestamp: Long
    ): String {

        val formatter = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.US
        )

        formatter.timeZone = TimeZone.getDefault()

        return formatter.format(Date(timestamp))
    }

    // ═══════════════════════════════════════════════════════════════
    // 6. Security Mode
    // ═══════════════════════════════════════════════════════════════

    fun getSecurityMode(): SecurityMode {

        val mode = getSystemSetting(
            "sms_security_mode",
            "relaxed"
        )
            .trim()
            .lowercase(Locale.ROOT)

        return when (mode) {
            "strict" -> SecurityMode.STRICT
            else -> SecurityMode.RELAXED
        }
    }

    fun isStrictMode(): Boolean {
        return getSecurityMode() == SecurityMode.STRICT
    }

    // ═══════════════════════════════════════════════════════════════
    // 7. Security Audit Log
    // ═══════════════════════════════════════════════════════════════

    /**
     * تسجيل حدث أمني.
     *
     * لا يتم تخزين رقم الهاتف نفسه؛ بل Hash فقط.
     *
     * كما لا يتم تسجيل نص الرسالة.
     */
    fun logSecurityEvent(
        event: String,
        phone: String?,
        details: String
    ) {

        try {

            val safeEvent = event
                .take(80)
                .replace(Regex("[\\r\\n]"), " ")

            val safeDetails = details
                .take(200)
                .replace(Regex("[\\r\\n]"), " ")
                .replace(Regex("(?i)(password|otp|cvv|pin)\\s*[:=]\\s*\\S+"), "$1=[REDACTED]")

            val phoneHash =
                sha256(
                    normalizePhone(phone)
                ).take(16)

            val structuredLog = JSONObject().apply {

                put(
                    "timestamp",
                    formatAuditTimestamp(
                        System.currentTimeMillis()
                    )
                )

                put(
                    "event",
                    safeEvent
                )

                put(
                    "phone_hash",
                    phoneHash
                )

                put(
                    "details",
                    safeDetails
                )

                put(
                    "device",
                    Build.MODEL ?: "unknown"
                )

                put(
                    "android_version",
                    Build.VERSION.RELEASE ?: "unknown"
                )

                put(
                    "sim_operator",
                    getSimOperator()
                )
            }

            val entry = structuredLog.toString()

            val prefs = getSecurePrefs()

            val existing =
                prefs.getString(
                    AUDIT_LOG,
                    ""
                ).orEmpty()

            val updated = if (existing.isBlank()) {

                entry

            } else {

                val combined =
                    "$existing\n$entry"

                if (combined.length > MAX_AUDIT_LOG_LENGTH) {
                    combined.takeLast(
                        MAX_AUDIT_LOG_LENGTH
                    )
                } else {
                    combined
                }
            }

            prefs.edit()
                .putString(
                    AUDIT_LOG,
                    updated
                )
                .apply()

            /**
             * Logcat لا يحصل على التفاصيل الأصلية.
             */
            Log.i(
                TAG,
                "SECURITY event=$safeEvent phoneHash=$phoneHash"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to write security audit event: ${e.javaClass.simpleName}",
                e
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Secure Preferences
    // ═══════════════════════════════════════════════════════════════

    private fun getSecurePrefs(): SharedPreferences {

        return try {

            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(
                    MasterKey.KeyScheme.AES256_GCM
                )
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

        } catch (e: Exception) {

            /**
             * نستخدم fallback فقط لضمان عدم تعطيل النظام.
             *
             * البيانات المخزنة هنا لا تحتوي على نص الرسالة أو رقم
             * الهاتف الخام، وإنما Audit Log بعد تنقيته.
             */
            Log.e(
                TAG,
                "Encrypted preferences unavailable: ${e.javaClass.simpleName}"
            )

            context.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Database Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun getManagerPhone(): String? {

        return try {

            db.readableDatabase.rawQuery(
                """
                SELECT u.phone
                FROM users u
                JOIN roles r ON u.role_id = r.id
                WHERE r.role_code IN (
                    'SUPER_ADMIN',
                    'ADMIN',
                    'STATION_MANAGER'
                )
                  AND u.status = 'active'
                  AND u.is_deleted = 0
                  AND u.phone IS NOT NULL
                  AND TRIM(u.phone) <> ''
                ORDER BY r.level ASC
                LIMIT 1
                """.trimIndent(),
                null
            ).use { cursor ->

                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else {
                    null
                }
            }

        } catch (e: SQLiteException) {

            Log.e(
                TAG,
                "Failed to get manager phone: ${e.javaClass.simpleName}",
                e
            )

            null
        }
    }

    private fun getSystemSetting(
        key: String,
        defaultValue: String = ""
    ): String {

        if (key.isBlank()) {
            return defaultValue
        }

        return try {

            db.readableDatabase.rawQuery(
                """
                SELECT setting_value
                FROM system_settings
                WHERE setting_key = ?
                LIMIT 1
                """.trimIndent(),
                arrayOf(key)
            ).use { cursor ->

                if (!cursor.moveToFirst()) {
                    defaultValue
                } else {
                    cursor.getString(0)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: defaultValue
                }
            }

        } catch (e: SQLiteException) {

            Log.e(
                TAG,
                "Failed to read system setting: ${e.javaClass.simpleName}",
                e
            )

            defaultValue
        }
    }

    private fun getSimOperator(): String {

        return try {

            val telephonyManager =
                context.getSystemService(
                    Context.TELEPHONY_SERVICE
                ) as? TelephonyManager

            telephonyManager
                ?.simOperatorName
                ?.takeIf { it.isNotBlank() }
                ?: "unknown"

        } catch (e: SecurityException) {

            "unknown"

        } catch (e: Exception) {

            "unknown"
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Security Maintenance
    // ═══════════════════════════════════════════════════════════════

    /**
     * مزامنة Rate Limits.
     *
     * أبقينا الدالة ضمن الـ API حتى لا تنكسر الملفات التي تعتمد
     * عليها، مع تنفيذ تنظيف الحالات المنتهية فعلياً.
     */
    suspend fun syncRateLimits() =
        withContext(Dispatchers.IO) {

            try {

                val now = System.currentTimeMillis()

                /**
                 * إزالة الحظر المنتهي من الذاكرة.
                 */
                blockedNumbers.entries.removeIf {
                    it.value <= now
                }

                /**
                 * إزالة الحالات المنتهية من SQLite.
                 *
                 * لا نحذف السجل نفسه حتى لا نفقد الإحصائيات اليومية.
                 * فقط نزيل حالة الحظر.
                 */
                val values = ContentValues().apply {
                    put("blocked_until", 0L)
                    put("warning_count", 0)
                    put(
                        "updated_at",
                        formatDatabaseDate(now)
                    )
                }

                db.writableDatabase.update(
                    RATE_LIMIT_TABLE,
                    values,
                    "blocked_until > 0 AND blocked_until <= ?",
                    arrayOf(now.toString())
                )

                Log.d(
                    TAG,
                    "Rate limits synchronized"
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Failed to sync rate limits: ${e.javaClass.simpleName}",
                    e
                )
            }
        }

    /**
     * تنفيذ فحص أمني دوري.
     */
    suspend fun performSecurityCheck() =
        withContext(Dispatchers.IO) {

            try {

                cleanupSmsProcessedMessages()
                syncRateLimits()

                Log.d(
                    TAG,
                    "Security check completed"
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Security check failed: ${e.javaClass.simpleName}",
                    e
                )

                throw e
            }
        }

    // ═══════════════════════════════════════════════════════════════
    // Message Builders
    // ═══════════════════════════════════════════════════════════════

    private fun buildDailyLimitMessage(
        customerName: String,
        managerPhone: String?
    ): String {

        val name =
            customerName.trim().takeIf {
                it.isNotEmpty()
            } ?: "عزيزي العميل"

        return """
            ⚠️ $name،
            لقد تجاوزت الحد المسموح من الرسائل اليوم.
            تم حظر رقمك مؤقتاً لمدة 24 ساعة.
            للاستفسار العاجل: ${managerPhone ?: "غير متوفر"}
        """.trimIndent()
    }

    private fun buildRepeatBlockMessage(
        customerName: String,
        managerPhone: String?
    ): String {

        val name =
            customerName.trim().takeIf {
                it.isNotEmpty()
            } ?: "عزيزي العميل"

        return """
            🚫 $name،
            لقد أرسلت رسائل متكررة كثيرة خلال فترة قصيرة.
            تم حظر رقمك مؤقتاً لمدة 24 ساعة.
            للاستفسار: ${managerPhone ?: "غير متوفر"}
        """.trimIndent()
    }

    private fun buildBlockedMessage(
        customerName: String,
        managerPhone: String?
    ): String {

        val name =
            customerName.trim().takeIf {
                it.isNotEmpty()
            } ?: "عزيزي العميل"

        return """
            🚫 $name،
            رقمك محظور مؤقتاً بسبب تجاوز سياسة استخدام الرسائل.
            يرجى المحاولة لاحقاً.
            للاستفسار: ${managerPhone ?: "غير متوفر"}
        """.trimIndent()
    }

    private fun buildWarningMessage(
        customerName: String,
        warningCount: Int
    ): String {

        val name =
            customerName.trim().takeIf {
                it.isNotEmpty()
            } ?: "عزيزي العميل"

        return """
            ⚠️ $name،
            لقد أرسلت رسائل متكررة خلال فترة قصيرة.
            يرجى تحديد ما تريده في رسالة واحدة بدقة.
            تحذير $warningCount من $MAX_REPEAT_WARNINGS
        """.trimIndent()
    }

    // ═══════════════════════════════════════════════════════════════
    // Cryptographic Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun sha256(
        input: String
    ): String {

        val digest =
            MessageDigest.getInstance("SHA-256")

        return digest
            .digest(
                input.toByteArray(
                    StandardCharsets.UTF_8
                )
            )
            .joinToString("") {
                "%02x".format(it)
            }
    }

    // ═══════════════════════════════════════════════════════════════
    // Rate Limit Result
    // ═══════════════════════════════════════════════════════════════

    sealed class RateLimitResult {

        object ALLOWED : RateLimitResult()

        data class WARNING(
            val message: String
        ) : RateLimitResult()

        data class BLOCKED(
            val message: String,
            val managerPhone: String?
        ) : RateLimitResult()
    }
}