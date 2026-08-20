package com.aistudio.dieselstationsms.kxmpzq.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsIntentDetectorTest {

    private val detector = SmsIntentDetector()
    private val idleContext = SmsIntentDetector.ConversationState(
        awaitingResponse = false,
        pendingAction = "",
        lastTopic = "",
        timestamp = System.currentTimeMillis()
    )
    private val quantityContext = SmsIntentDetector.ConversationState(
        awaitingResponse = true,
        pendingAction = "awaiting_quantity",
        lastTopic = "quantity",
        timestamp = System.currentTimeMillis()
    )

    @Test
    fun `plain Arabic diesel request is detected`() {
        val result = detector.detectIntent("اريد ديزل", idleContext, "967771234567")
        assertEquals("diesel_request", result.intent)
    }

    @Test
    fun `spaced diesel request is detected`() {
        val result = detector.detectIntent("  أريد   ديزل  ", idleContext, "967771234567")
        assertEquals("diesel_request", result.intent)
    }

    @Test
    fun `public help command is detected without customer state`() {
        val result = detector.detectIntent("استعلام", idleContext, "967701234567")
        assertEquals("help", result.intent)
    }

    @Test
    fun `quantity parser converts supported Yemeni units`() {
        val cases = listOf(
            "5 دباب" to 100.0,
            "200 لتر" to 200.0,
            "عدد الدباب 5" to 100.0,
            "دبتين ديزل" to 40.0,
            "خمسة دباب ديزل" to 100.0,
            "خمس دبات" to 100.0,
            "١٢ دبة" to 240.0
        )
        cases.forEach { (text, expectedLiters) ->
            val parsed = detector.parseQuantity(text)
            assertEquals(text, expectedLiters, parsed.liters, 0.001)
            assertTrue("$text should have a positive dabba quantity", parsed.dabbas > 0.0)
        }
    }

    @Test
    fun `pending quantity context routes number-first and reversed replies`() {
        listOf("5 دباب", "200 لتر", "عدد الدباب 5", "دبتين ديزل").forEach { text ->
            val result = detector.detectIntent(text, quantityContext, "967771234567")
            assertEquals(text, "quantity_response", result.intent)
        }
    }
}
