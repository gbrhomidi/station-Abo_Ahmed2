package com.aistudio.dieselstationsms.kxmpzq.sms

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmsAiModelsTest {
    @Test
    fun invalidIntentIsDowngradedToUnknown() {
        val result = SmsAiUnderstanding.fromJson(JSONObject().apply {
            put("intent", "delete_ledger")
            put("entities", JSONObject().put("quantity_liters", "200"))
            put("confidence", 0.99)
            put("status", "UNDERSTOOD")
            put("reason", "unsafe intent")
        })
        assertEquals("unknown", result.intent)
        assertEquals("200", result.entities["quantity_liters"])
    }

    @Test
    fun confidenceAndTextAreBounded() {
        val result = SmsAiUnderstanding.fromJson(JSONObject().apply {
            put("intent", "diesel_request")
            put("entities", JSONObject().put("location", "x".repeat(1000)))
            put("confidence", 4.0)
            put("status", "UNDERSTOOD")
            put("reason", "ok")
        })
        assertEquals(1.0, result.confidence, 0.0)
        assertEquals(500, result.entities.getValue("location").length)
    }

    @Test
    fun unusableConfigRequiresHttpsAndKey() {
        assertFalse(SmsAiRuntimeConfig(enabled = true, endpoint = "http://localhost", apiKey = "secret").usable())
        assertFalse(SmsAiRuntimeConfig(enabled = true, apiKey = "").usable())
        assertTrue(SmsAiRuntimeConfig(enabled = true, apiKey = "secret").usable())
    }
}
