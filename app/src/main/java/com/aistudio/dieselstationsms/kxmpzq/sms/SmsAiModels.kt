package com.aistudio.dieselstationsms.kxmpzq.sms

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** إعدادات المزود القابلة للضبط؛ لا يحتوي JSON العام على المفتاح السري. */
data class SmsAiRuntimeConfig(
    val enabled: Boolean = false,
    val provider: String = "openai_compatible",
    val endpoint: String = "https://api.openai.com/v1/chat/completions",
    val model: String = "gpt-5-mini",
    val apiKey: String = "",
    val timeoutMs: Long = 8_000L,
    val maxOutputTokens: Int = 700,
    val minimumConfidence: Double = 0.65
) {
    fun usable(): Boolean = enabled && endpoint.startsWith("https://") && apiKey.isNotBlank() && model.isNotBlank()

    fun publicJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("provider", provider)
        put("endpoint", endpoint)
        put("model", model)
        put("configured", apiKey.isNotBlank())
        put("timeout_ms", timeoutMs)
        put("max_output_tokens", maxOutputTokens)
        put("minimum_confidence", minimumConfidence)
    }
}

data class SmsAiRequest(
    val message: String,
    val phone: String,
    val customerName: String,
    val conversationId: String,
    val lastIntent: String,
    val pendingAction: String,
    val contextJson: JSONObject,
    val preferencesJson: JSONObject,
    val draftJson: JSONObject?
)

data class SmsAiToolCall(
    val id: String,
    val name: String,
    val arguments: JSONObject
)

data class SmsAiUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
)

data class SmsAiUnderstanding(
    val intent: String,
    val entities: Map<String, String> = emptyMap(),
    val confidence: Double,
    val status: String,
    val reason: String,
    val missingEntities: List<String> = emptyList(),
    val assumptions: List<String> = emptyList(),
    val responseDraft: String? = null,
    val toolCalls: List<SmsAiToolCall> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("intent", intent)
        put("entities", JSONObject(entities))
        put("confidence", confidence)
        put("status", status)
        put("reason", reason)
        put("missing_entities", JSONArray(missingEntities))
        put("assumptions", JSONArray(assumptions))
        responseDraft?.let { put("response_draft", it.take(500)) }
    }

    companion object {
        private const val MAX_TEXT = 500
        private val ALLOWED_INTENTS = setOf(
            "diesel_request", "quantity_response", "quantity_ambiguous", "location_response",
            "time_response", "confirm_order", "cancel_order", "balance_query", "payment_request",
            "transfer_request", "offers_query", "price_query", "loyalty_query", "redeem_points",
            "track_order", "order_history", "help", "complaint", "emergency", "callback_request",
            "location_query", "working_hours", "invoice_request", "weekly_report", "schedule_appointment",
            "schedule_recurring", "rating", "greeting", "thanks", "gasoline_request", "unknown"
        )

        fun fromJson(json: JSONObject): SmsAiUnderstanding {
            val rawIntent = json.optString("intent", "unknown").trim().lowercase(Locale.ROOT)
            val intent = rawIntent.takeIf { it in ALLOWED_INTENTS } ?: "unknown"
            val entities = linkedMapOf<String, String>()
            json.optJSONObject("entities")?.let { objectJson ->
                objectJson.keys().forEach { key ->
                    val value = objectJson.optString(key, "").trim()
                    if (value.isNotBlank() && key.length <= 50) entities[key] = value.take(MAX_TEXT)
                }
            }
            val missing = json.optJSONArray("missing_entities").toStringList()
            val assumptions = json.optJSONArray("assumptions").toStringList()
            return SmsAiUnderstanding(
                intent = intent,
                entities = entities,
                confidence = json.optDouble("confidence", 0.0).coerceIn(0.0, 1.0),
                status = json.optString("status", "NEEDS_CLARIFICATION").take(40),
                reason = json.optString("reason", "").take(MAX_TEXT),
                missingEntities = missing.take(12),
                assumptions = assumptions.take(12),
                responseDraft = json.optString("response_draft", "").trim().takeIf { it.isNotBlank() }?.take(MAX_TEXT)
            )
        }

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            return buildList {
                for (index in 0 until length()) {
                    val value = optString(index, "").trim()
                    if (value.isNotBlank()) add(value.take(80))
                }
            }
        }
    }
}

enum class SmsAiAvailability { AVAILABLE, DEGRADED, UNAVAILABLE }

data class SmsAiAnalysis(
    val availability: SmsAiAvailability,
    val understanding: SmsAiUnderstanding?,
    val provider: String,
    val model: String,
    val latencyMs: Long,
    val usage: SmsAiUsage = SmsAiUsage(),
    val fallbackReason: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("availability", availability.name)
        put("provider", provider)
        put("model", model)
        put("latency_ms", latencyMs)
        put("usage", JSONObject().apply {
            put("prompt_tokens", usage.promptTokens)
            put("completion_tokens", usage.completionTokens)
            put("total_tokens", usage.totalTokens)
        })
        fallbackReason?.let { put("fallback_reason", it) }
        understanding?.let { put("understanding", it.toJson()) }
    }
}

enum class SmsAiFailureKind {
    AUTHENTICATION,
    QUOTA,
    RETRYABLE_HTTP,
    HTTP,
    TIMEOUT,
    NETWORK,
    MALFORMED_RESPONSE,
    UNSAFE_RESPONSE,
    UNKNOWN
}

class SmsAiProviderException(
    message: String,
    cause: Throwable? = null,
    val kind: SmsAiFailureKind = SmsAiFailureKind.UNKNOWN,
    val httpCode: Int? = null
) : Exception(message, cause)
