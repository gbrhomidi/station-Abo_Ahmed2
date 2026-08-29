package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * مخزن runtime مشفر لمعلومات مزود AI.
 * لا يوجد fallback إلى SharedPreferences عادية لأن هذا المخزن يحتوي على API key.
 */
class SmsAiConfigStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS_NAME,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun get(): SmsAiRuntimeConfig {
        return SmsAiRuntimeConfig(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            provider = preferences.getString(KEY_PROVIDER, "openai_compatible").orEmpty().ifBlank { "openai_compatible" },
            endpoint = preferences.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT).orEmpty().ifBlank { DEFAULT_ENDPOINT },
            model = preferences.getString(KEY_MODEL, "gpt-5-mini").orEmpty().ifBlank { "gpt-5-mini" },
            apiKey = preferences.getString(KEY_API_KEY, "").orEmpty(),
            timeoutMs = preferences.getLong(KEY_TIMEOUT_MS, 8_000L).coerceIn(2_000L, 9_000L),
            maxOutputTokens = preferences.getInt(KEY_MAX_OUTPUT_TOKENS, 700).coerceIn(200, 2_000),
            minimumConfidence = preferences.getString(KEY_MIN_CONFIDENCE, "0.65")
                ?.toDoubleOrNull()
                ?.coerceIn(0.50, 0.95)
                ?: 0.65
        )
    }

    fun save(config: SmsAiRuntimeConfig) {
        val endpoint = config.endpoint.trim().take(500)
        require(endpoint.startsWith("https://")) { "AI endpoint must use HTTPS" }
        require(config.model.trim().isNotBlank()) { "AI model is required" }
        require(config.apiKey.length <= 4096) { "AI key is too long" }
        preferences.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_PROVIDER, config.provider.trim().take(80))
            .putString(KEY_ENDPOINT, endpoint)
            .putString(KEY_MODEL, config.model.trim().take(160))
            .putString(KEY_API_KEY, config.apiKey.trim())
            .putLong(KEY_TIMEOUT_MS, config.timeoutMs.coerceIn(2_000L, 9_000L))
            .putInt(KEY_MAX_OUTPUT_TOKENS, config.maxOutputTokens.coerceIn(200, 2_000))
            .putString(KEY_MIN_CONFIDENCE, config.minimumConfidence.coerceIn(0.50, 0.95).toString())
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "sms_ai_secure_runtime"
        private const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_MODEL = "model"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_TIMEOUT_MS = "timeout_ms"
        private const val KEY_MAX_OUTPUT_TOKENS = "max_output_tokens"
        private const val KEY_MIN_CONFIDENCE = "minimum_confidence"
    }
}
