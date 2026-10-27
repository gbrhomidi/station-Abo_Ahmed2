package com.aistudio.dieselstationsms.kxmpzq.settings.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Stores security controls only in AndroidX EncryptedSharedPreferences.
 * PIN values are never persisted as text and are never returned to JavaScript.
 */
class SecuritySettingsStore internal constructor(
    private val preferences: SharedPreferences
) {
    companion object {
        const val PIN_MASK = "••••••"
        const val KEY_ENCRYPT_STORAGE = "encryptStorage"
        const val KEY_PIN_CODE = "pinCode"

        val SETTING_KEYS = setOf(
            "requireDeviceUnlock",
            KEY_ENCRYPT_STORAGE,
            "biometricLockEnabled",
            "hideSensitiveData",
            "autoLogoutMinutes",
            "preventScreenshots",
            "secureBackupEnabled"
        )

        val DEFAULTS = mapOf(
            "requireDeviceUnlock" to "0",
            KEY_ENCRYPT_STORAGE to "1",
            "biometricLockEnabled" to "0",
            "hideSensitiveData" to "0",
            "autoLogoutMinutes" to "0",
            "preventScreenshots" to "0",
            "secureBackupEnabled" to "1"
        )

        private const val PREFS_NAME = "security_settings_encrypted"
        private const val PIN_HASH = "_pin_hash"
        private const val PIN_SALT = "_pin_salt"
        private const val PIN_FAILURES = "_pin_failures"
        private const val PIN_BLOCKED_UNTIL = "_pin_blocked_until"
        private const val PBKDF2_ITERATIONS = 150_000
        private const val PIN_KEY_BITS = 256
        private const val MAX_PIN_FAILURES = 5
        private const val LOCKOUT_MS = 60_000L

        fun open(context: Context): SecuritySettingsStore {
            val appContext = context.applicationContext
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            val encrypted = EncryptedSharedPreferences.create(
                PREFS_NAME,
                masterKeyAlias,
                appContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            // Leave this key absent until legacy SQLite settings have been inspected. The
            // runtime default is encrypted, but an existing explicit "0" must be preserved.
            return SecuritySettingsStore(encrypted)
        }
    }

    fun encryptionEnabled(): Boolean =
        preferences.getString(KEY_ENCRYPT_STORAGE, "1")?.let(::isTrue) ?: true

    fun getSetting(key: String): String? {
        if (key == KEY_PIN_CODE) return pinValueForUi()
        if (key !in SETTING_KEYS) return null
        return preferences.getString(key, DEFAULTS[key])
    }

    fun hasStoredSetting(key: String): Boolean = key in SETTING_KEYS && preferences.contains(key)

    fun putSetting(key: String, value: String) {
        require(key in SETTING_KEYS && key != KEY_PIN_CODE) { "مفتاح إعداد أمان غير مسموح" }
        preferences.edit().putString(key, value).apply()
    }

    fun removeSetting(key: String) {
        if (key in SETTING_KEYS && key != KEY_PIN_CODE) {
            preferences.edit().remove(key).apply()
        }
    }

    fun pinValueForUi(): String = if (isPinConfigured()) PIN_MASK else ""

    fun isPinConfigured(): Boolean =
        !preferences.getString(PIN_HASH, null).isNullOrBlank() &&
            !preferences.getString(PIN_SALT, null).isNullOrBlank()

    /**
     * Validates and hashes a new numeric PIN. Empty/masked input intentionally means
     * "keep the current PIN", preventing a normal settings save from deleting it.
     */
    fun savePinCode(pin: String) {
        val candidate = pin.trim()
        if (candidate.isEmpty() || candidate == PIN_MASK) return
        require(candidate.matches(Regex("[0-9]{4,12}"))) {
            "رمز PIN يجب أن يتكون من 4 إلى 12 رقماً"
        }

        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val chars = candidate.toCharArray()
        val hash = try {
            derivePin(chars, salt)
        } finally {
            chars.fill('\u0000')
        }
        preferences.edit()
            .putString(PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .remove(PIN_FAILURES)
            .remove(PIN_BLOCKED_UNTIL)
            .apply()
    }

    /**
     * Five failed checks block further attempts for one minute.
     * Returns only a Boolean; neither the stored hash nor its salt is exposed.
     */
    fun verifyPinCode(pin: String): Boolean {
        if (!isPinConfigured()) return false
        val now = System.currentTimeMillis()
        if (preferences.getLong(PIN_BLOCKED_UNTIL, 0L) > now) return false

        val salt = runCatching {
            Base64.decode(preferences.getString(PIN_SALT, null), Base64.NO_WRAP)
        }.getOrNull() ?: return false
        val expected = runCatching {
            Base64.decode(preferences.getString(PIN_HASH, null), Base64.NO_WRAP)
        }.getOrNull() ?: return false

        val chars = pin.toCharArray()
        val actual = try {
            derivePin(chars, salt)
        } catch (_: Exception) {
            return false
        } finally {
            chars.fill('\u0000')
        }

        if (MessageDigest.isEqual(expected, actual)) {
            preferences.edit().remove(PIN_FAILURES).remove(PIN_BLOCKED_UNTIL).apply()
            return true
        }

        val failures = preferences.getInt(PIN_FAILURES, 0) + 1
        val edit = preferences.edit().putInt(PIN_FAILURES, failures)
        if (failures >= MAX_PIN_FAILURES) {
            edit.putLong(PIN_BLOCKED_UNTIL, now + LOCKOUT_MS).putInt(PIN_FAILURES, 0)
        }
        edit.apply()
        return false
    }

    /** Exportable only inside an already AES-GCM-encrypted settings backup. */
    fun exportPinCredential(): Map<String, String> {
        if (!isPinConfigured()) return emptyMap()
        return mapOf(
            "salt" to preferences.getString(PIN_SALT, "").orEmpty(),
            "hash" to preferences.getString(PIN_HASH, "").orEmpty()
        )
    }

    /** Restore accepts only the fixed-size credential envelope produced by this class. */
    fun importPinCredential(credential: Map<String, String>) {
        if (credential.isEmpty()) {
            preferences.edit().remove(PIN_SALT).remove(PIN_HASH)
                .remove(PIN_FAILURES).remove(PIN_BLOCKED_UNTIL).apply()
            return
        }
        val salt = Base64.decode(credential["salt"] ?: error("ملح PIN غير موجود"), Base64.NO_WRAP)
        val hash = Base64.decode(credential["hash"] ?: error("بصمة PIN غير موجود"), Base64.NO_WRAP)
        require(salt.size == 16 && hash.size == PIN_KEY_BITS / 8) {
            "بيانات PIN في النسخة الاحتياطية غير صالحة"
        }
        preferences.edit()
            .putString(PIN_SALT, credential.getValue("salt"))
            .putString(PIN_HASH, credential.getValue("hash"))
            .remove(PIN_FAILURES)
            .remove(PIN_BLOCKED_UNTIL)
            .apply()
    }

    private fun derivePin(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, PBKDF2_ITERATIONS, PIN_KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun isTrue(value: String): Boolean =
        value.trim().lowercase() in setOf("1", "true", "yes", "on")
}
