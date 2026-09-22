package com.aistudio.dieselstationsms.kxmpzq.sms

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsCognitiveConversationEngineTest {
    private val engine = SmsCognitiveConversationEngine()
    private val context = SmsConversationManager.ConversationContext(
        lastTopic = "diesel",
        lastIntent = "diesel_request",
        pendingAction = "awaiting_quantity",
        awaitingResponse = true,
        timestamp = System.currentTimeMillis()
    )

    @Test
    fun `golden follow-up keeps deterministic quantity intent when ai disagrees`() {
        val aiMisclassification = SmsAiUnderstanding(
            intent = "help",
            confidence = 0.97,
            status = "UNDERSTOOD",
            reason = "simulated provider misclassification"
        )

        val plan = engine.plan(
            message = "5 دباب",
            context = context,
            preferences = SmsConversationManager.CustomerPreferences(),
            aiUnderstanding = aiMisclassification
        )

        assertEquals("quantity_response", plan.intentResult.intent)
    }

    @Test
    fun `golden quantity messages are deterministic in awaiting quantity context`() {
        listOf("5 دباب", "200 لتر", "عدد الدباب 5").forEach { message ->
            val plan = engine.plan(
                message = message,
                context = context,
                preferences = SmsConversationManager.CustomerPreferences()
            )
            assertEquals(message, "quantity_response", plan.intentResult.intent)
        }
    }
}
