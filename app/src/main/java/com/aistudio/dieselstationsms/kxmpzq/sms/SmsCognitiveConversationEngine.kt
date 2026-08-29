package com.aistudio.dieselstationsms.kxmpzq.sms

import java.util.Calendar
import java.util.Locale

/**
 * محرك حوار معرفي deterministic يعمل Offline-first فوق IntentDetector.
 * لا ينفذ أثراً مالياً؛ مهمته بناء خطة قابلة للمراجعة فقط.
 */
enum class SmsConversationState {
    NEW,
    CLASSIFIED,
    NEEDS_CONTEXT,
    WAITING_FOR_USER,
    READY,
    CONFIRMATION_REQUIRED,
    CONFIRMED,
    EXECUTING,
    EXECUTED,
    REPLIED,
    CANCELLED,
    EXPIRED,
    FAILED,
    RECOVERABLE,
    REQUIRES_REVIEW
}

class SmsCognitiveConversationEngine(
    private val intentDetector: SmsIntentDetector = SmsIntentDetector()
) {
    fun plan(
        message: String,
        context: SmsConversationManager.ConversationContext,
        preferences: SmsConversationManager.CustomerPreferences,
        draft: SmsConversationManager.OrderDraft? = null,
        aiUnderstanding: SmsAiUnderstanding? = null
    ): SmsCognitivePlan {
        val normalized = SmsMessageNormalizer.normalizeForMatch(message)
        val deterministicIntent = intentDetector.detectIntent(
            message,
            SmsIntentDetector.ConversationState(
                awaitingResponse = context.awaitingResponse,
                pendingAction = context.pendingAction,
                lastTopic = context.lastTopic,
                timestamp = context.timestamp
            ),
            context.data[SmsConversationManager.DATA_PHONE].orEmpty()
        )
        val aiAccepted = aiUnderstanding != null &&
            aiUnderstanding.status == "UNDERSTOOD" &&
            aiUnderstanding.confidence >= 0.65 &&
            aiUnderstanding.intent != "unknown"
        val deterministicIsContextual = context.awaitingResponse &&
            context.pendingAction.isNotBlank() &&
            deterministicIntent.intent in CONTEXTUAL_DETERMINISTIC_INTENTS
        val aiMayOverrideIntent = aiAccepted && !deterministicIsContextual
        val intent = if (aiMayOverrideIntent) {
            SmsIntentDetector.IntentResult(
                intent = aiUnderstanding!!.intent,
                confidence = (aiUnderstanding.confidence * 100.0).toInt().coerceIn(0, 100),
                allScores = mapOf(aiUnderstanding.intent to (aiUnderstanding.confidence * 100.0).toInt())
            )
        } else {
            deterministicIntent
        }
        val known = linkedMapOf<String, String>()
        if (aiMayOverrideIntent) known.putAll(aiUnderstanding!!.entities)
        val assumptions = mutableListOf<String>()
        if (aiMayOverrideIntent) assumptions += aiUnderstanding!!.assumptions
        val contradictions = mutableListOf<SmsContradiction>()
        var referenceResolved = false

        val reference = normalized.contains("نفس الطلب") ||
            normalized.contains("طلب الاسبوع الماضي") ||
            normalized.contains("الطلب السابق")
        if (reference) {
            referenceResolved = true
            if (preferences.preferredQuantity > 0) {
                known["quantity_liters"] = preferences.preferredQuantity.toString()
                assumptions += "تم استخدام كمية الطلب السابق باحتمال يحتاج إلى تأكيد"
            }
            if (preferences.preferredLocation.isNotBlank()) {
                known["location"] = preferences.preferredLocation
                assumptions += "تم استخدام موقع الطلب السابق باحتمال يحتاج إلى تأكيد"
            }
            if (preferences.preferredTime.isNotBlank()) {
                known["time"] = preferences.preferredTime
                assumptions += "تم استخدام وقت الطلب السابق باحتمال يحتاج إلى تأكيد"
            }
        }

        if (draft != null) {
            if (draft.quantityLiters > 0) known.putIfAbsent("quantity_liters", draft.quantityLiters.toString())
            if (draft.deliveryLocation.isNotBlank()) known.putIfAbsent("location", draft.deliveryLocation)
            if (draft.deliveryTime.isNotBlank()) known.putIfAbsent("time", draft.deliveryTime)
        }

        val parsedQuantity = runCatching { intentDetector.parseQuantity(message) }.getOrNull()
        if (parsedQuantity != null && parsedQuantity.liters > 0) {
            val proposed = parsedQuantity.liters.toString()
            known["quantity_liters"] = proposed
            if (draft != null && draft.quantityLiters > 0 && draft.quantityLiters != parsedQuantity.liters) {
                contradictions += SmsContradiction("quantity_liters", draft.quantityLiters.toString(), proposed)
            }
        }

        val temporal = parseTemporal(normalized)
        if (temporal != null) known["time_window"] = temporal.display

        val complaintCategory = if (intent.intent == "complaint") classifyComplaint(normalized) else null
        val missing = mutableListOf<String>()
        if (aiMayOverrideIntent) missing += aiUnderstanding!!.missingEntities
        if (intent.intent == "diesel_request" || context.pendingAction.startsWith("awaiting_")) {
            if (!known.containsKey("quantity_liters")) missing += "quantity"
            if (!known.containsKey("location")) missing += "location"
            if (!known.containsKey("time_window") && !known.containsKey("time")) missing += "time"
            if (missing.contains("quantity")) missing.removeAll { it == "location" || it == "time" }
            else if (missing.contains("location")) missing.removeAll { it == "time" }
        }
        val question = when (missing.firstOrNull()) {
            "quantity" -> "كم لتر تقريباً؟"
            "location" -> "إلى أي موقع تريد التوصيل؟"
            "time" -> "متى تريد التوصيل؟"
            else -> null
        }

        val suggestedState = when {
            intent.intent == "unknown" -> SmsConversationState.REQUIRES_REVIEW.name
            missing.isNotEmpty() -> SmsConversationState.NEEDS_CONTEXT.name
            intent.intent == "confirm_order" -> SmsConversationState.CONFIRMED.name
            intent.intent == "cancel_order" -> SmsConversationState.CANCELLED.name
            intent.intent in setOf("diesel_request", "gasoline_request") && missing.isEmpty() -> SmsConversationState.CONFIRMATION_REQUIRED.name
            else -> SmsConversationState.READY.name
        }

        return SmsCognitivePlan(
            intentResult = intent,
            normalizedText = normalized,
            knownEntities = known,
            missingEntities = missing,
            assumptions = assumptions,
            contradictions = contradictions,
            temporalWindow = temporal,
            referenceResolved = referenceResolved,
            nextQuestion = question,
            complaintCategory = complaintCategory,
            suggestedState = suggestedState
        )
    }

    private fun classifyComplaint(text: String): String = when {
        text.contains("توصيل") || text.contains("سائق") || text.contains("وصل") -> "DELIVERY"
        text.contains("سعر") || text.contains("غالي") || text.contains("رسوم") -> "PRICE"
        text.contains("دفع") || text.contains("تحويل") || text.contains("حساب") -> "PAYMENT"
        text.contains("جوده") || text.contains("جودة") || text.contains("مغشوش") -> "QUALITY"
        text.contains("تقني") || text.contains("تطبيق") || text.contains("رساله") -> "TECHNICAL"
        else -> "GENERAL"
    }

    private fun parseTemporal(text: String): SmsTemporalWindow? {
        val now = Calendar.getInstance()
        val dayOffset = when {
            text.contains("بعد بكره") || text.contains("بعد غد") -> 2
            text.contains("بكره") || text.contains("غدا") -> 1
            text.contains("اليوم") || text.contains("الحين") || text.contains("الان") -> 0
            else -> return null
        }
        now.add(Calendar.DAY_OF_YEAR, dayOffset)
        val startHour = when {
            text.contains("الصباح") || text.contains("صباح") -> 8
            text.contains("العصر") || text.contains("بعد العصر") -> 15
            text.contains("المساء") || text.contains("مساء") || text.contains("الليل") -> 18
            else -> now.get(Calendar.HOUR_OF_DAY).coerceIn(8, 20)
        }
        now.set(Calendar.HOUR_OF_DAY, startHour)
        now.set(Calendar.MINUTE, 0)
        now.set(Calendar.SECOND, 0)
        now.set(Calendar.MILLISECOND, 0)
        val start = now.timeInMillis
        val end = start + if (text.contains("الان") || text.contains("الحين")) 2 * 60 * 60 * 1000L else 3 * 60 * 60 * 1000L
        val displayDay = when (dayOffset) { 0 -> "اليوم"; 1 -> "غداً"; else -> "بعد غد" }
        val displayWindow = when (startHour) { 8 -> "صباحاً"; 15 -> "بعد العصر"; 18 -> "مساءً"; else -> "الوقت المحدد" }
        return SmsTemporalWindow("$displayDay $displayWindow", start, end, 0.82)
    }

    companion object {
        private val CONTEXTUAL_DETERMINISTIC_INTENTS = setOf(
            "quantity_response",
            "quantity_ambiguous",
            "location_response",
            "time_response",
            "confirm_order",
            "cancel_order"
        )
    }
}
