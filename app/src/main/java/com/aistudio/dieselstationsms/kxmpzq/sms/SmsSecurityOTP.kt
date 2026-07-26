package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
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
        val code = (1000..9999).random().toString()
        val now = System.currentTimeMillis()
        val expiresAt = now + OTP_EXPIRY_MS

        writableDb.delete(OTP_TABLE, "phone = ?", arrayOf(phone))

        val values = ContentValues().apply {
            put("phone", phone)
            put("otp_code", code)
            put("timestamp", now)
            put("attempts", 0)
            put("max_attempts", OTP_MAX_ATTEMPTS)
            put("expires_at", expiresAt)
        }

        writableDb.insert(OTP_TABLE, null, values)
        code
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ التحقق من OTP ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun verifyOTP(phone: String, code: String): Boolean = withContext(Dispatchers.IO) {
        var cursor: Cursor? = null
        var expiresAtCursor: Cursor? = null

        try {
            cursor = readableDb.rawQuery(
                """
                SELECT otp_code, timestamp, attempts, max_attempts, expires_at 
                FROM $OTP_TABLE 
                WHERE phone = ? 
                LIMIT 1
                """.trimIndent(),
                arrayOf(phone)
            )

            if (!cursor.moveToFirst()) return@withContext false

            val otpCode = cursor.getString(cursor.getColumnIndexOrThrow("otp_code"))
            val timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp"))
            val attempts = cursor.getInt(cursor.getColumnIndexOrThrow("attempts"))
            val maxAttempts = cursor.getInt(cursor.getColumnIndexOrThrow("max_attempts"))
            val expiresAt = cursor.getLong(cursor.getColumnIndexOrThrow("expires_at"))

            val otpData = OTPData(
                code = otpCode,
                timestamp = timestamp,
                attempts = attempts,
                maxAttempts = maxAttempts
            )

            if (System.currentTimeMillis() > expiresAt) {
                writableDb.delete(OTP_TABLE, "phone = ?", arrayOf(phone))
                return@withContext false
            }

            if (otpData.attempts >= otpData.maxAttempts) {
                writableDb.delete(OTP_TABLE, "phone = ?", arrayOf(phone))
                return@withContext false
            }

            val newAttempts = otpData.attempts + 1
            val values = ContentValues().apply {
                put("attempts", newAttempts)
            }
            writableDb.update(OTP_TABLE, values, "phone = ?", arrayOf(phone))

            val isValid = code == otpData.code
            if (isValid) {
                writableDb.delete(OTP_TABLE, "phone = ?", arrayOf(phone))
            }

            isValid
        } finally {
            cursor?.close()
            expiresAtCursor?.close()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ═══ التحقق من وجود OTP نشط ═══
    // ═══════════════════════════════════════════════════════════════

    suspend fun hasActiveOTP(phone: String): Boolean = withContext(Dispatchers.IO) {
        var cursor: Cursor? = null
        try {
            cursor = readableDb.rawQuery(
                "SELECT 1 FROM $OTP_TABLE WHERE phone = ? AND expires_at > ? LIMIT 1",
                arrayOf(phone, System.currentTimeMillis().toString())
            )
            cursor.moveToFirst()
        } finally {
            cursor?.close()
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
}
