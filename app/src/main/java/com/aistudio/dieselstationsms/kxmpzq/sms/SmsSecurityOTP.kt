package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.aistudio.dieselstationsms.kxmpzq.utils.PhoneUtils
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * ═══════════════════════════════════════════════════════════════
 * نظام OTP مع حفظ في قاعدة البيانات
 * ═══════════════════════════════════════════════════════════════
 *
 * المهام:
 * 1. توليد OTP
 * 2. التحقق من OTP
 * 3. حفظ/استرجاع من SQLite
 * 4. انتهاء الصلاحية التلقائي
 */
class SmsSecurityOTP(private val db: DatabaseHelper) {

    companion object {
        private const val TAG = "SmsSecurityOTP"
        private const val OTP_EXPIRY_MS = 300000L // 5 دقائق
        private const val OTP_MAX_ATTEMPTS = 3
        private const val OTP_TABLE = "sms_otp_verifications"
        private const val OTP_GENERATION_COOLDOWN_MS = 60000L // 1 دقيقة
    }

    data class OTPData(
        val code: String,
        val timestamp: Long = System.currentTimeMillis(),
        var attempts: Int = 0,
        val maxAttempts: Int = OTP_MAX_ATTEMPTS
    )

    private val secureRandom = SecureRandom()
    private val otpGenerationTimes = ConcurrentHashMap<String, Long>()

    // ═══════════════════════════════════════════════════════════════
    // ═══ Helper properties to access database ═══
    // ═══════════════════════════════════════════════════════════════

    private val writableDb: SQLiteDatabase
        get() = db.writableDatabase

    private val readableDb: SQLiteDatabase
        get() = db.readableDatabase

    // ═══════════════════════════════════════════════════════════════
    // ═══ توليد OTP ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun generateOTP(phone: String): String = withContext(Dispatchers.IO) {
        val normalizedPhone = normalizePhone(phone)

        // Rate Limiting
        val lastGen = otpGenerationTimes[normalizedPhone] ?: 0
        val timeSinceLast = System.currentTimeMillis() - lastGen
        if (timeSinceLast < OTP_GENERATION_COOLDOWN_MS && lastGen > 0) {
            val waitSeconds = (OTP_GENERATION_COOLDOWN_MS - timeSinceLast) / 1000
            throw IllegalStateException("يرجى الانتظار ${waitSeconds} ثانية قبل طلب OTP جديد")
        }

        // حذف OTP القديم
        writableDb.delete(OTP_TABLE, "phone = ?", arrayOf(normalizedPhone))

        // توليد OTP آمن
        val code = (1000 + secureRandom.nextInt(9000)).toString()
        val now = System.currentTimeMillis()
        val expiresAt = now + OTP_EXPIRY_MS

        val values = ContentValues().apply {
            put("phone", normalizedPhone)
            put("otp_code", code)
            put("timestamp", now)
            put("attempts", 0)
            put("max_attempts", OTP_MAX_ATTEMPTS)
            put("expires_at", expiresAt)
        }

        writableDb.insert(OTP_TABLE, null, values)
        otpGenerationTimes[normalizedPhone] = now
        Log.d(TAG, "OTP generated for ${maskPhone(normalizedPhone)}")
        code
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ التحقق من OTP ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun verifyOTP(phone: String, code: String): Boolean = withContext(Dispatchers.IO) {
        val normalizedPhone = normalizePhone(phone)

        readableDb.rawQuery(
            """
            SELECT otp_code, timestamp, attempts, max_attempts, expires_at
            FROM $OTP_TABLE
            WHERE phone = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(normalizedPhone)
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                Log.w(TAG, "No OTP found for ${maskPhone(normalizedPhone)}")
                return@withContext false
            }

            val otpCode = cursor.getString(cursor.getColumnIndexOrThrow("otp_code"))
            val attempts = cursor.getInt(cursor.getColumnIndexOrThrow("attempts"))
            val maxAttempts = cursor.getInt(cursor.getColumnIndexOrThrow("max_attempts"))
            val expiresAt = cursor.getLong(cursor.getColumnIndexOrThrow("expires_at"))

            // التحقق من انتهاء الصلاحية
            if (System.currentTimeMillis() > expiresAt) {
                writableDb.delete(OTP_TABLE, "phone = ?", arrayOf(normalizedPhone))
                Log.d(TAG, "OTP expired for ${maskPhone(normalizedPhone)}")
                return@withContext false
            }

            // التحقق من عدد المحاولات
            if (attempts >= maxAttempts) {
                writableDb.delete(OTP_TABLE, "phone = ?", arrayOf(normalizedPhone))
                Log.w(TAG, "Max attempts exceeded for ${maskPhone(normalizedPhone)}")
                return@withContext false
            }

            // زيادة عدد المحاولات
            val newAttempts = attempts + 1
            val values = ContentValues().apply {
                put("attempts", newAttempts)
            }
            writableDb.update(OTP_TABLE, values, "phone = ?", arrayOf(normalizedPhone))

            // التحقق من الكود
            val isValid = code == otpCode
            if (isValid) {
                writableDb.delete(OTP_TABLE, "phone = ?", arrayOf(normalizedPhone))
                Log.i(TAG, "OTP verified successfully for ${maskPhone(normalizedPhone)}")
            } else {
                val remaining = maxAttempts - newAttempts
                Log.w(TAG, "Invalid OTP for ${maskPhone(normalizedPhone)}, $remaining attempts remaining")
            }

            isValid
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ التحقق من وجود OTP نشط ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun hasActiveOTP(phone: String): Boolean = withContext(Dispatchers.IO) {
        val normalizedPhone = normalizePhone(phone)
        readableDb.rawQuery(
            "SELECT 1 FROM $OTP_TABLE WHERE phone = ? AND expires_at > ? LIMIT 1",
            arrayOf(normalizedPhone, System.currentTimeMillis().toString())
        ).use { cursor ->
            cursor.moveToFirst()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ تنظيف OTP منتهية الصلاحية ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun cleanupExpiredOTPs() = withContext(Dispatchers.IO) {
        val deleted = writableDb.delete(
            OTP_TABLE,
            "expires_at < ?",
            arrayOf(System.currentTimeMillis().toString())
        )
        Log.d(TAG, "Cleaned up $deleted expired OTPs")
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ تنظيف منتهية الصلاحية (alias for compatibility) ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun cleanupExpired() = cleanupExpiredOTPs()

    // ═══════════════════════════════════════════════════════════════
    // ═══ مزامنة بيانات OTP (syncData) ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun syncData() = withContext(Dispatchers.IO) {
        try {
            cleanupExpiredOTPs()
            // تنظيف ذاكرة Rate Limiting القديمة
            val cutoff = System.currentTimeMillis() - OTP_GENERATION_COOLDOWN_MS * 2
            otpGenerationTimes.entries.removeIf { it.value < cutoff }
            Log.d(TAG, "OTP data synced successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync OTP data: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ Helpers ═══
    // ═══════════════════════════════════════════════════════════════

    private fun normalizePhone(phone: String): String {
        return PhoneUtils.normalize(phone) ?: phone.replace(Regex("[^0-9+]"), "")
    }

    private fun maskPhone(phone: String): String {
        if (phone.length <= 4) return "***"
        return phone.take(3) + "***" + phone.takeLast(2)
    }
}
