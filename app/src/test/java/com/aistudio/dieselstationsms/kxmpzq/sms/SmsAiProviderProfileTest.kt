package com.aistudio.dieselstationsms.kxmpzq.sms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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
    fun cooldownUsesDeadlineRatherThanLifetimeSuccessHistory() {
        val profile = SmsAiProviderProfile(
            provider = "openai_compatible",
            endpoint = "https://api.example.test",
            model = "model",
            apiKey = "secret",
            successCount = 12,
            failureCount = 20,
            consecutiveFailureCount = 4,
            cooldownUntil = 10_000L
        )
        assertTrue(profile.isCoolingDown(9_999L))
        assertFalse(profile.isCoolingDown(10_000L))
        assertFalse(profile.eligible(9_999L))
        assertTrue(profile.eligible(10_000L))
        val recovered = profile.copy(consecutiveFailureCount = 0, cooldownUntil = 0L)
        assertTrue(recovered.eligible(9_999L))
    }

    @Test
    fun cooldownStateSurvivesProfileStorageRoundTrip() {
        val profile = SmsAiProviderProfile(
            provider = "gemini",
            endpoint = "https://example.test",
            model = "model",
            apiKey = "secret",
            consecutiveFailureCount = 4,
            cooldownUntil = 123_456L
        )
        val restored = SmsAiProviderProfile.fromStorageJson(profile.toStorageJson())
        assertTrue(restored.consecutiveFailureCount == 4)
        assertTrue(restored.cooldownUntil == 123_456L)
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
