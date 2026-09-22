package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * مخزن ملفات المزودين. يخزن المفاتيح داخل EncryptedSharedPreferences فقط.
 * البيانات العامة تعاد بعد إخفاء المفتاح ولا تسجل قيم المفاتيح في Logcat.
 */
class SmsAiProviderStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS_NAME,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    @Synchronized
    fun list(): List<SmsAiProviderProfile> = readProfiles().map { resetIfNewDay(it) }

    @Synchronized
    fun get(id: String): SmsAiProviderProfile? = list().firstOrNull { it.id == id }

    @Synchronized
    fun upsert(profile: SmsAiProviderProfile): SmsAiProviderProfile {
        val normalized = profile.normalized()
        require(normalized.provider.isNotBlank()) { "provider is required" }
        require(normalized.endpoint.startsWith("https://")) { "provider endpoint must use HTTPS" }
        require(normalized.model.isNotBlank()) { "model is required" }
        require(normalized.apiKey.isNotBlank()) { "api key is required" }
        require(normalized.apiKey.length <= 4096) { "api key is too long" }
        val existing = list().toMutableList()
        val index = existing.indexOfFirst { it.id == normalized.id }
        if (index >= 0) existing[index] = normalized else existing += normalized
        writeProfiles(existing)
        return normalized
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val existing = list().toMutableList()
        val removed = existing.removeAll { it.id == id }
        if (removed) writeProfiles(existing)
        return removed
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val existing = list().toMutableList()
        val index = existing.indexOfFirst { it.id == id }
        if (index < 0) return false
        existing[index] = existing[index].copy(enabled = enabled)
        writeProfiles(existing)
        return true
    }

    @Synchronized
    fun recordResult(id: String, success: Boolean, error: String? = null): Boolean {
        val existing = list().toMutableList()
        val index = existing.indexOfFirst { it.id == id }
        if (index < 0) return false
        val current = resetIfNewDay(existing[index])
        existing[index] = current.copy(
            usedToday = current.usedToday + 1,
            successCount = current.successCount + if (success) 1 else 0,
            failureCount = current.failureCount + if (success) 0 else 1,
            lastUsedAt = System.currentTimeMillis(),
            lastError = if (success) "" else error.orEmpty().take(180)
        )
        writeProfiles(existing)
        return true
    }

    @Synchronized
    fun publicJson(): JSONArray = JSONArray().apply {
        list().sortedWith(compareBy<SmsAiProviderProfile> { it.priority }.thenBy { it.provider })
            .forEach { put(it.publicJson()) }
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun readProfiles(): List<SmsAiProviderProfile> {
        val raw = preferences.getString(KEY_PROFILES, "[]").orEmpty()
        val json = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until json.length()) {
                runCatching { SmsAiProviderProfile.fromStorageJson(json.getJSONObject(index)) }
                    .onSuccess { add(it) }
            }
        }
    }

    private fun writeProfiles(profiles: List<SmsAiProviderProfile>) {
        preferences.edit()
            .putString(KEY_PROFILES, JSONArray(profiles.map { it.toStorageJson() }).toString())
            .apply()
    }

    private fun resetIfNewDay(profile: SmsAiProviderProfile): SmsAiProviderProfile {
        if (profile.lastUsedAt == 0L) return profile
        return if (dayKey(profile.lastUsedAt) == dayKey(System.currentTimeMillis())) {
            profile
        } else {
            profile.copy(usedToday = 0, lastError = "")
        }
    }

    private fun dayKey(timestamp: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(timestamp))

    companion object {
        private const val PREFS_NAME = "sms_ai_provider_profiles_secure"
        private const val KEY_PROFILES = "profiles_json"
    }
}
