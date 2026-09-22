package com.aistudio.dieselstationsms.kxmpzq.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsAiRoutingAndValidationTest {
    private val router = SmsAiRoutingEngine()

    @Test
    fun `plain diesel request stays deterministic`() {
        val decision = router.decide("اشتي ديزل", SmsConversationManager.ConversationContext())
        assertFalse(decision.needsAi)
        assertEquals("local_pattern", decision.reason)
    }

    @Test
    fun `compound fuel request uses AI decision layer`() {
        val decision = router.decide(
            "اشتي ديزل 5 دباب إلى الحصبة بكرة العصر",
            SmsConversationManager.ConversationContext()
        )
        assertTrue(decision.needsAi)
        assertTrue(decision.complexity != SmsAiTaskComplexity.LOW)
    }

    @Test
    fun `high confidence valid understanding is executable`() {
        val understanding = SmsAiUnderstanding(
            intent = "diesel_request",
            entities = mapOf("fuel" to "diesel", "quantity_liters" to "100", "unit" to "DABBA"),
            confidence = 0.95,
            status = "UNDERSTOOD",
            reason = "clear"
        )
        val validated = SmsAiUnderstandingValidator.validate(understanding)
        assertEquals("UNDERSTOOD", validated.status)
        assertTrue(validated.confidence >= SmsAiUnderstandingValidator.AUTO_EXECUTE_CONFIDENCE)
    }

    @Test
    fun `medium confidence becomes clarification`() {
        val understanding = SmsAiUnderstanding(
            intent = "diesel_request",
            entities = mapOf("fuel" to "diesel", "quantity_liters" to "100"),
            confidence = 0.82,
            status = "UNDERSTOOD",
            reason = "ambiguous"
        )
        val validated = SmsAiUnderstandingValidator.validate(understanding)
        assertEquals("NEEDS_CLARIFICATION", validated.status)
    }

    @Test
    fun `invalid quantity and unit cannot reach business execution`() {
        val understanding = SmsAiUnderstanding(
            intent = "diesel_request",
            entities = mapOf("fuel" to "diesel", "quantity_liters" to "-1", "unit" to "UNKNOWN"),
            confidence = 0.99,
            status = "UNDERSTOOD",
            reason = "bad data"
        )
        val validated = SmsAiUnderstandingValidator.validate(understanding)
        assertEquals("NEEDS_CLARIFICATION", validated.status)
        assertTrue(validated.reason.contains("invalid_quantity"))
        assertTrue(validated.reason.contains("invalid_unit"))
    }
}
