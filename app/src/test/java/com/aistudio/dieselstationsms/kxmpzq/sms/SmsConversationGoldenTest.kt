package com.aistudio.dieselstationsms.kxmpzq.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsConversationGoldenTest {
    private val engine = SmsCognitiveConversationEngine()
    private val phone = "967771234567"

    @Test
    fun `golden scenario preserves diesel intent then routes quantity variants`() {
        val idle = SmsConversationManager.ConversationContext(
            data = mutableMapOf(SmsConversationManager.DATA_PHONE to phone)
        )
        val firstPlan = engine.plan(
            message = "اشتي ديزل",
            context = idle,
            preferences = SmsConversationManager.CustomerPreferences()
        )
        assertEquals("diesel_request", firstPlan.intentResult.intent)

        val awaitingQuantity = idle.apply {
            lastIntent = firstPlan.intentResult.intent
            lastTopic = "diesel"
            pendingAction = "awaiting_quantity"
            awaitingResponse = true
        }
        val quantityVariants = listOf(
            "5 دباب" to 100.0,
            "200 لتر" to 200.0,
            "عدد الدباب 5" to 100.0
        )

        quantityVariants.forEach { (message, expectedLiters) ->
            val plan = engine.plan(
                message = message,
                context = awaitingQuantity,
                preferences = SmsConversationManager.CustomerPreferences()
            )
            assertEquals(message, "quantity_response", plan.intentResult.intent)
            assertEquals(message, expectedLiters.toString(), plan.knownEntities["quantity_liters"])
        }
        assertTrue(awaitingQuantity.awaitingResponse)
        assertEquals("awaiting_quantity", awaitingQuantity.pendingAction)
    }
}
