package com.aistudio.dieselstationsms.kxmpzq.sms

import org.json.JSONObject
import java.util.UUID

/** ملف مزود AI قابل للتدوير الإداري؛ القيمة السرية لا تظهر في JSON العام. */
data class SmsAiProviderProfile(
    val id: String = UUID.randomUUID().toString(),
    val provider: String,
    val endpoint: String,
    val model: String,
    val apiKey: String,
    val enabled: Boolean = true,
    val priority: Int = 100,
    val dailyLimit: Int = 500,
    val usedToday: Int = 0,
    val successCount: Long = 0L,
    val failureCount: Long = 0L,
    val lastUsedAt: Long = 0L,
    val lastError: String = "",
    val minConfidence: Double = 0.65,
    val fallbackProviderId: String? = null
) {
    fun normalized(): SmsAiProviderProfile = copy(
        provider = provider.trim().lowercase().take(40),
        endpoint = endpoint.trim().take(500),
        model = model.trim().take(160),
        apiKey = apiKey.trim(),
        priority = priority.coerceIn(0, 10_000),
        dailyLimit = dailyLimit.coerceIn(1, 100_000),
        usedToday = usedToday.coerceAtLeast(0),
        minConfidence = minConfidence.coerceIn(0.50, 0.95),
        lastError = lastError.take(180)
    )

    fun eligible(): Boolean {
        return enabled && apiKey.isNotBlank() && endpoint.startsWith("https://") && model.isNotBlank() && usedToday < dailyLimit
    }

    fun publicJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("provider", provider)
        put("endpoint", endpoint)
        put("model", model)
        put("configured", apiKey.isNotBlank())
        put("enabled", enabled)
        put("priority", priority)
        put("daily_limit", dailyLimit)
        put("used_today", usedToday)
        put("success_count", successCount)
        put("failure_count", failureCount)
        put("last_used_at", lastUsedAt)
        put("last_error", lastError)
        put("min_confidence", minConfidence)
        put("fallback_provider_id", fallbackProviderId ?: JSONObject.NULL)
    }

    fun toStorageJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("provider", provider)
        put("endpoint", endpoint)
        put("model", model)
        put("api_key", apiKey)
        put("enabled", enabled)
        put("priority", priority)
        put("daily_limit", dailyLimit)
        put("used_today", usedToday)
        put("success_count", successCount)
        put("failure_count", failureCount)
        put("last_used_at", lastUsedAt)
        put("last_error", lastError)
        put("min_confidence", minConfidence)
        fallbackProviderId?.let { put("fallback_provider_id", it) }
    }

    companion object {
        fun fromStorageJson(json: JSONObject): SmsAiProviderProfile = SmsAiProviderProfile(
            id = json.optString("id", UUID.randomUUID().toString()),
            provider = json.optString("provider", "openai_compatible"),
            endpoint = json.optString("endpoint", ""),
            model = json.optString("model", ""),
            apiKey = json.optString("api_key", ""),
            enabled = json.optBoolean("enabled", true),
            priority = json.optInt("priority", 100),
            dailyLimit = json.optInt("daily_limit", 500),
            usedToday = json.optInt("used_today", 0),
            successCount = json.optLong("success_count", 0L),
            failureCount = json.optLong("failure_count", 0L),
            lastUsedAt = json.optLong("last_used_at", 0L),
            lastError = json.optString("last_error", ""),
            minConfidence = json.optDouble("min_confidence", 0.65),
            fallbackProviderId = json.optString("fallback_provider_id", "").takeIf { it.isNotBlank() }
        ).normalized()
    }
}
