package com.aistudio.dieselstationsms.kxmpzq.sms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsAiRoutingEngineTest {
    private val engine = SmsAiRoutingEngine()

    @Test
    fun simpleCommandsStayLocal() {
        val result = engine.decide("اريد ديزل", SmsConversationManager.ConversationContext())
        assertFalse(result.needsAi)
        assertTrue(result.reason == "local_pattern")
    }

    @Test
    fun contextualComplexMessageUsesAi() {
        val context = SmsConversationManager.ConversationContext(awaitingResponse = true)
        val result = engine.decide("نفس الطلب السابق بكرة العصر لكن تحويل قبل التأكيد", context)
        assertTrue(result.needsAi)
        assertTrue(result.complexity != SmsAiTaskComplexity.LOW)
        assertTrue(result.sensitive)
    }
}
