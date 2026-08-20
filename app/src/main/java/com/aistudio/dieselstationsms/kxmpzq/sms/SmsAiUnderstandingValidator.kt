package com.aistudio.dieselstationsms.kxmpzq.sms

import java.util.Locale

/**
 * Validates AI output before it can influence the deterministic conversation engine.
 * It never writes to the database and never executes a business command.
 */
object SmsAiUnderstandingValidator {
    const val AUTO_EXECUTE_CONFIDENCE = 0.90
    const val CLARIFICATION_CONFIDENCE = 0.70
    private const val MAX_LITERS = 10_000.0
    private val allowedStatuses = setOf("UNDERSTOOD", "NEEDS_CLARIFICATION", "UNSAFE")
    private val allowedUnits = setOf("DABBA", "DABBAS", "دبة", "دباب", "لتر", "LITER", "LITERS", "LTR")
    private val allowedEntityKeys = setOf(
        "fuel", "quantity_liters", "unit", "location", "date", "time", "time_window",
        "payment_amount", "order_id", "invoice_id", "customer_name", "phone", "vehicle",
        "delivery_preference", "reference"
    )

    fun validate(understanding: SmsAiUnderstanding): SmsAiUnderstanding {
        val normalizedStatus = understanding.status.trim().uppercase(Locale.ROOT)
        val sanitizedEntities = understanding.entities
            .filterKeys { it in allowedEntityKeys }
            .mapValues { (_, value) -> value.trim().take(500) }
            .toMutableMap()
        val invalid = mutableListOf<String>()

        if (normalizedStatus !in allowedStatuses) invalid += "invalid_status"
        if (understanding.intent == "unknown") invalid += "unknown_intent"
        if (!understanding.confidence.isFinite() || understanding.confidence !in 0.0..1.0) invalid += "invalid_confidence"

        sanitizedEntities["fuel"]?.let { fuel ->
            val normalizedFuel = fuel.lowercase(Locale.ROOT)
            if (normalizedFuel !in setOf("diesel", "ديزل", "gasoline", "petrol", "بنزين")) invalid += "invalid_fuel"
        }
        sanitizedEntities["unit"]?.let { unit ->
            if (unit.uppercase(Locale.ROOT) !in allowedUnits && unit !in allowedUnits) invalid += "invalid_unit"
        }
        sanitizedEntities["quantity_liters"]?.let { raw ->
            val liters = raw.replace(',', '.').toDoubleOrNull()
            if (liters == null || !liters.isFinite() || liters <= 0.0 || liters > MAX_LITERS) invalid += "invalid_quantity"
        }

        val confidence = understanding.confidence.coerceIn(0.0, 1.0)
        val finalStatus = when {
            invalid.isNotEmpty() -> "NEEDS_CLARIFICATION"
            normalizedStatus == "UNSAFE" -> "UNSAFE"
            normalizedStatus == "NEEDS_CLARIFICATION" -> "NEEDS_CLARIFICATION"
            confidence < CLARIFICATION_CONFIDENCE -> "NEEDS_CLARIFICATION"
            confidence < AUTO_EXECUTE_CONFIDENCE -> "NEEDS_CLARIFICATION"
            else -> "UNDERSTOOD"
        }
        val reason = buildString {
            if (understanding.reason.isNotBlank()) append(understanding.reason.take(400))
            if (invalid.isNotEmpty()) {
                if (isNotEmpty()) append("; ")
                append("validation:").append(invalid.joinToString(","))
            }
            if (finalStatus == "NEEDS_CLARIFICATION" && confidence < AUTO_EXECUTE_CONFIDENCE) {
                if (isNotEmpty()) append("; ")
                append("confidence_below_auto_execute_threshold")
            }
        }.ifBlank { "validated" }

        return understanding.copy(
            entities = sanitizedEntities,
            confidence = confidence,
            status = finalStatus,
            reason = reason,
            responseDraft = if (finalStatus == "NEEDS_CLARIFICATION") understanding.responseDraft?.take(500) else null
        )
    }
}
