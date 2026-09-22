package com.aistudio.dieselstationsms.kxmpzq.sms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsAiProviderProfileTest {
    @Test
    fun providerEligibilityRequiresHttpsAndQuota() {
        val profile = SmsAiProviderProfile(
            provider = "openai_compatible",
            endpoint = "https://api.example.test/v1/chat/completions",
            model = "model",
            apiKey = "secret",
            dailyLimit = 2,
            usedToday = 1
        )
        assertTrue(profile.eligible())
        assertFalse(profile.copy(usedToday = 2).eligible())
        assertFalse(profile.copy(endpoint = "http://api.example.test").eligible())
        assertFalse(profile.copy(apiKey = "").eligible())
    }

    @Test
    fun normalizationBoundsProviderValues() {
        val profile = SmsAiProviderProfile(
            provider = "  Gemini  ",
            endpoint = "https://example.test",
            model = " model ",
            apiKey = "key",
            priority = -2,
            dailyLimit = 0,
            minConfidence = 2.0,
            lastError = "x".repeat(400)
        ).normalized()
        assertTrue(profile.provider == "gemini")
        assertTrue(profile.priority == 0)
        assertTrue(profile.dailyLimit == 1)
        assertTrue(profile.minConfidence <= 0.95)
        assertTrue(profile.lastError.length == 180)
    }
}
