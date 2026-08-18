package com.aistudio.dieselstationsms.kxmpzq.sms

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsIntentDetectorTest {

    private val detector = SmsIntentDetector()
    private val idleContext = SmsIntentDetector.ConversationState(
        awaitingResponse = false,
        pendingAction = "",
        lastTopic = "",
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
}
