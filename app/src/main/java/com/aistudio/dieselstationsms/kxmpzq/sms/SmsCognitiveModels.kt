package com.aistudio.dieselstationsms.kxmpzq.sms

import org.json.JSONObject

/** خطة محادثة قابلة للتدقيق بدلاً من تفسير الرسالة كـIntent منفرد فقط. */
data class SmsCognitivePlan(
    val intentResult: SmsIntentDetector.IntentResult,
    val normalizedText: String,
    val knownEntities: Map<String, String> = emptyMap(),
    val missingEntities: List<String> = emptyList(),
    val assumptions: List<String> = emptyList(),
    val contradictions: List<SmsContradiction> = emptyList(),
    val temporalWindow: SmsTemporalWindow? = null,
    val referenceResolved: Boolean = false,
    val nextQuestion: String? = null,
    val complaintCategory: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("intent", intentResult.intent)
        put("confidence", intentResult.confidence)
        put("normalized_text", normalizedText.take(1000))
        put("known_entities", JSONObject(knownEntities))
        put("missing_entities", missingEntities)
        put("assumptions", assumptions)
        put("contradictions", contradictions.map { it.toJson() })
        temporalWindow?.let { put("temporal_window", it.toJson()) }
        put("reference_resolved", referenceResolved)
        nextQuestion?.let { put("next_question", it) }
        complaintCategory?.let { put("complaint_category", it) }
    }
}

data class SmsContradiction(
    val entity: String,
    val previousValue: String,
    val proposedValue: String,
    val source: String = "SMS"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("entity", entity)
        put("previous_value", previousValue)
        put("proposed_value", proposedValue)
        put("source", source)
    }
}

data class SmsTemporalWindow(
    val display: String,
    val startAt: Long,
    val endAt: Long,
    val confidence: Double,
    val source: String = "SMS"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("display", display)
        put("start_at", startAt)
        put("end_at", endAt)
        put("confidence", confidence)
        put("source", source)
    }
}

data class SmsSemanticCommand(
    val commandId: String,
    val commandType: String,
    val conversationId: String,
    val eventId: String,
    val idempotencyKey: String,
    val payload: JSONObject,
    val status: String = "ROUTED"
)

data class SmsDecisionResult(
    val allowed: Boolean,
    val outcome: String,
    val policyVersion: String,
    val reasons: List<String>,
    val riskLevel: String,
    val proof: JSONObject = JSONObject()
)
