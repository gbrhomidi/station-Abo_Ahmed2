package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.ContentValues
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.exp

/** مصدر الحقيقة للذاكرة والتتبع والـcommands؛ لا يعتمد على cache لحفظ الحالة الحرجة. */
class SmsCognitiveRepository(private val db: DatabaseHelper) {
    fun recordInboundTrace(conversationId: String, eventId: String, stage: String, payload: JSONObject) {
        val now = System.currentTimeMillis()
        val sanitizedPayload = sanitizeTracePayload(payload)
        db.writableDatabase.insert(
            "sms_conversation_trace", null, ContentValues().apply {
                put("trace_id", UUID.randomUUID().toString())
                put("conversation_id", conversationId)
                put("event_id", eventId)
                put("stage", stage)
                put("payload_json", sanitizedPayload.toString().take(8000))
                put("created_at", now)
            }
        )
    }

    private fun sanitizeTracePayload(payload: JSONObject): JSONObject {
        val sanitized = JSONObject(payload.toString())
        val sensitiveKeys = setOf("api_key", "password", "token", "secret", "phone", "sender")
        val keys = sanitized.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (sensitiveKeys.any { key.lowercase(java.util.Locale.ROOT).contains(it) }) {
                sanitized.put(key, "***REDACTED***")
            }
        }
        return sanitized
    }

    fun recordPlan(phone: String, conversationId: String, eventId: String, plan: SmsCognitivePlan) {
        val now = System.currentTimeMillis()
        db.writableDatabase.insert(
            "sms_cognitive_plans", null, ContentValues().apply {
                put("plan_id", UUID.randomUUID().toString())
                put("event_id", eventId)
                put("conversation_id", conversationId)
                put("phone", phone)
                put("intent", plan.intentResult.intent)
                put("confidence", plan.intentResult.confidence)
                put("plan_json", plan.toJson().toString().take(12000))
                put("created_at", now)
            }
        )
        recordInboundTrace(conversationId, eventId, "UNDERSTOOD", plan.toJson())
        if (plan.knownEntities.isNotEmpty() || plan.missingEntities.isNotEmpty()) {
            recordInboundTrace(conversationId, eventId, "CONTEXTUALIZED", JSONObject().apply {
                put("known_entities", JSONObject(plan.knownEntities))
                put("missing_entities", plan.missingEntities)
                put("assumptions", plan.assumptions)
            })
        }
        plan.contradictions.forEach { contradiction ->
            recordEvent(
                eventId = UUID.randomUUID().toString(),
                conversationId = conversationId,
                eventType = "ENTITY_CONTRADICTION",
                aggregateType = "conversation",
                aggregateId = conversationId,
                payload = contradiction.toJson()
            )
        }
    }

    fun remember(
        phone: String,
        memoryType: String,
        value: String,
        confidence: Double,
        source: String,
        sensitive: Boolean = false,
        expiresAt: Long? = null
    ) {
        if (phone.isBlank() || value.isBlank()) return
        val now = System.currentTimeMillis()
        val database = db.writableDatabase
        val updated = database.update(
            "sms_customer_memory",
            ContentValues().apply {
                put("value", value.take(1000))
                put("confidence", confidence.coerceIn(0.0, 1.0))
                put("source", source.take(80))
                put("last_confirmed", now)
                put("expires_at", expiresAt)
                put("sensitive", if (sensitive) 1 else 0)
            },
            "phone = ? AND memory_type = ?",
            arrayOf(phone, memoryType)
        )
        if (updated == 0) {
            database.insertWithOnConflict(
                "sms_customer_memory", null, ContentValues().apply {
                    put("memory_id", UUID.randomUUID().toString())
                    put("phone", phone)
                    put("memory_type", memoryType)
                    put("value", value.take(1000))
                    put("confidence", confidence.coerceIn(0.0, 1.0))
                    put("source", source.take(80))
                    put("created_at", now)
                    put("last_confirmed", now)
                    put("expires_at", expiresAt)
                    put("customer_visible", 1)
                    put("sensitive", if (sensitive) 1 else 0)
                },
                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
            )
        }
    }

    fun applyMemoryDecay(phone: String, now: Long = System.currentTimeMillis()) {
        val database = db.writableDatabase
        database.rawQuery(
            "SELECT memory_id, confidence, last_confirmed FROM sms_customer_memory WHERE phone = ?",
            arrayOf(phone)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val ageDays = ((now - cursor.getLong(2)).coerceAtLeast(0L) / 86_400_000.0)
                if (ageDays <= 0.0) continue
                val confidence = (cursor.getDouble(1) * exp(-ageDays / 365.0)).coerceAtLeast(0.1)
                database.update(
                    "sms_customer_memory",
                    ContentValues().apply { put("confidence", confidence) },
                    "memory_id = ?",
                    arrayOf(cursor.getString(0))
                )
            }
        }
    }

    fun recordEvent(
        eventId: String,
        conversationId: String,
        eventType: String,
        aggregateType: String,
        aggregateId: String?,
        payload: JSONObject,
        proofJson: JSONObject? = null
    ): Boolean {
        val inserted = db.writableDatabase.insertWithOnConflict(
            "sms_business_events", null, ContentValues().apply {
                put("event_id", eventId)
                put("conversation_id", conversationId)
                put("event_type", eventType)
                put("aggregate_type", aggregateType)
                put("aggregate_id", aggregateId)
                put("payload_json", payload.toString().take(12000))
                put("proof_json", proofJson?.toString()?.take(8000))
                put("created_at", System.currentTimeMillis())
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
        )
        return inserted != -1L
    }

    fun recordDecision(
        decisionId: String,
        eventId: String,
        conversationId: String,
        commandType: String,
        result: SmsDecisionResult
    ) {
        db.writableDatabase.insertWithOnConflict(
            "sms_decision_trail", null, ContentValues().apply {
                put("decision_id", decisionId)
                put("event_id", eventId)
                put("conversation_id", conversationId)
                put("command_type", commandType)
                put("outcome", result.outcome)
                put("policy_version", result.policyVersion)
                put("risk_level", result.riskLevel)
                put("reasons_json", JSONObject().apply { put("reasons", result.reasons) }.toString())
                put("proof_json", result.proof.toString())
                put("created_at", System.currentTimeMillis())
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
        )
        recordInboundTrace(conversationId, eventId, "AUTHORIZED", JSONObject().apply {
            put("outcome", result.outcome)
            put("policy_version", result.policyVersion)
            put("risk_level", result.riskLevel)
            put("reasons", result.reasons)
            put("proof", result.proof)
        })
    }

    fun recordAiRun(
        conversationId: String,
        eventId: String,
        provider: String,
        model: String,
        requestHash: String,
        latencyMs: Long,
        availability: String,
        confidence: Double?,
        usage: SmsAiUsage,
        fallbackReason: String?,
        errorType: String?
    ) {
        val safeEventId = eventId.ifBlank { UUID.randomUUID().toString() }
        val payload = JSONObject().apply {
            put("provider", provider.take(80))
            put("model", model.take(160))
            put("request_hash", requestHash.take(128))
            put("latency_ms", latencyMs.coerceAtLeast(0L))
            put("availability", availability.take(30))
            confidence?.let { put("confidence", it.coerceIn(0.0, 1.0)) }
            put("prompt_tokens", usage.promptTokens.coerceAtLeast(0))
            put("completion_tokens", usage.completionTokens.coerceAtLeast(0))
            put("total_tokens", usage.totalTokens.coerceAtLeast(0))
            fallbackReason?.let { put("fallback_reason", it.take(180)) }
            errorType?.let { put("error_type", it.take(100)) }
        }
        db.writableDatabase.insertWithOnConflict(
            "sms_ai_runs", null, ContentValues().apply {
                put("run_id", UUID.randomUUID().toString())
                put("event_id", safeEventId)
                put("conversation_id", conversationId)
                put("provider", provider.take(80))
                put("model", model.take(160))
                put("request_hash", requestHash.take(128))
                put("latency_ms", latencyMs.coerceAtLeast(0L))
                put("availability", availability.take(30))
                put("confidence", confidence)
                put("prompt_tokens", usage.promptTokens.coerceAtLeast(0))
                put("completion_tokens", usage.completionTokens.coerceAtLeast(0))
                put("total_tokens", usage.totalTokens.coerceAtLeast(0))
                put("fallback_reason", fallbackReason?.take(180))
                put("error_type", errorType?.take(100))
                put("created_at", System.currentTimeMillis())
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
        )
        if (conversationId.isNotBlank()) {
            recordInboundTrace(conversationId, safeEventId, "AI_INFERENCE", payload)
        }
    }

    fun enqueueCommand(command: SmsSemanticCommand): Boolean {
        val inserted = db.writableDatabase.insertWithOnConflict(
            "sms_semantic_commands", null, ContentValues().apply {
                put("command_id", command.commandId)
                put("event_id", command.eventId)
                put("conversation_id", command.conversationId)
                put("command_type", command.commandType)
                put("idempotency_key", command.idempotencyKey)
                put("payload_json", command.payload.toString().take(12000))
                put("status", command.status)
                put("created_at", System.currentTimeMillis())
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
        )
        return inserted != -1L
    }

    fun markCommandApplied(commandId: String, outcome: String) {
        db.writableDatabase.update(
            "sms_semantic_commands",
            ContentValues().apply {
                put("status", "APPLIED")
                put("outcome", outcome.take(1000))
                put("applied_at", System.currentTimeMillis())
            },
            "command_id = ? AND status IN ('ROUTED','AUTHORIZED')",
            arrayOf(commandId)
        )
    }

    fun idempotencyKey(phone: String, normalizedText: String, conversationId: String): String =
        sha256("$phone|$conversationId|$normalizedText")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
