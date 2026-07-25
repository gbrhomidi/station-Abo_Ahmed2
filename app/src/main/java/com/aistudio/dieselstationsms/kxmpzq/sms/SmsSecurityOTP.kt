package com.aistudio.dieselstationsms.kxmpzq.sms

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    }

    data class OTPData(
        val code: String,
        val timestamp: Long = System.currentTimeMillis(),
        var attempts: Int = 0,
        val maxAttempts: Int = OTP_MAX_ATTEMPTS
    )

    // ═══════════════════════════════════════════════════════════════
    // ═══ توليد OTP ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun generateOTP(phone: String): String = withContext(Dispatchers.IO) {
        val code = (1000..9999).random().toString()
        val now = System.currentTimeMillis()
        val expiresAt = now + OTP_EXPIRY_MS

        db.writableDatabase.delete(OTP_TABLE, "phone = ?", arrayOf(phone))

        val values = android.content.ContentValues().apply {
            put("phone", phone)
            put("otp_code", code)
            put("timestamp", now)
            put("attempts", 0)
            put("max_attempts", OTP_MAX_ATTEMPTS)
            put("expires_at", expiresAt)
        }

        db.writableDatabase.insert(OTP_TABLE, null, values)
        code
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ التحقق من OTP ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun verifyOTP(phone: String, code: String): Boolean = withContext(Dispatchers.IO) {
        val cursor = db.readableDatabase.rawQuery(
            """
            SELECT otp_code, timestamp, attempts, max_attempts, expires_at 
            FROM $OTP_TABLE 
            WHERE phone = ? 
            LIMIT 1
            """.trimIndent(),
            arrayOf(phone)
        )

        val otpData = cursor.use {
            if (it.moveToFirst()) {
                OTPData(
                    code = it.getString(it.getColumnIndexOrThrow("otp_code")),
                    timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp")),
                    attempts = it.getInt(it.getColumnIndexOrThrow("attempts")),
                    maxAttempts = it.getInt(it.getColumnIndexOrThrow("max_attempts"))
                )
            } else null
        }

        if (otpData == null) return@withContext false

        val expiresAt = cursor.use {
            if (it.moveToFirst()) it.getLong(it.getColumnIndexOrThrow("expires_at")) else 0L
        }

        if (System.currentTimeMillis() > expiresAt) {
            db.writableDatabase.delete(OTP_TABLE, "phone = ?", arrayOf(phone))
            return@withContext false
        }

        if (otpData.attempts >= otpData.maxAttempts) {
            db.writableDatabase.delete(OTP_TABLE, "phone = ?", arrayOf(phone))
            return@withContext false
        }

        val newAttempts = otpData.attempts + 1
        val values = android.content.ContentValues().apply {
            put("attempts", newAttempts)
        }
        db.writableDatabase.update(OTP_TABLE, values, "phone = ?", arrayOf(phone))

        val isValid = code == otpData.code
        if (isValid) {
            db.writableDatabase.delete(OTP_TABLE, "phone = ?", arrayOf(phone))
        }

        isValid
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ التحقق من وجود OTP نشط ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun hasActiveOTP(phone: String): Boolean = withContext(Dispatchers.IO) {
        val cursor = db.readableDatabase.rawQuery(
            "SELECT 1 FROM $OTP_TABLE WHERE phone = ? AND expires_at > ? LIMIT 1",
            arrayOf(phone, System.currentTimeMillis().toString())
        )
        cursor.use { it.moveToFirst() }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ تنظيف OTP منتهية الصلاحية ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun cleanupExpiredOTPs() = withContext(Dispatchers.IO) {
        val deleted = db.writableDatabase.delete(
            OTP_TABLE,
            "expires_at < ?",
            arrayOf(System.currentTimeMillis().toString())
        )
        android.util.Log.d(TAG, "Cleaned up $deleted expired OTPs")
    }
}