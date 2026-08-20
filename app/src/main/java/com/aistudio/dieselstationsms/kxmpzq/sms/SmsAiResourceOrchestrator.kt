package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * يختار مورداً واحداً لكل مهمة وفق routing/priority/health/quota.
 * fallback هنا بين موارد مهيأة شرعياً، وليس تدويراً لإخفاء الاستهلاك أو تجاوز limits.
 */
class SmsAiResourceOrchestrator(context: Context) {
    private val maxAttemptsPerCandidate = 2
    private val baseBackoffMs = 250L
    private val maxBackoffMs = 1_000L
    private val appContext = context.applicationContext
    private val profiles = runCatching { SmsAiProviderStore(appContext) }.getOrNull()
    private val remoteProvider = SmsRemoteAiProvider()
    private val geminiProvider = SmsGeminiAiProvider()

    suspend fun understand(
        request: SmsAiRequest,
        tools: SmsAiToolExecutor,
        routing: SmsAiRoutingDecision
    ): SmsAiAnalysis {
        if (!routing.needsAi) {
            return SmsAiAnalysis(
                availability = SmsAiAvailability.UNAVAILABLE,
                understanding = null,
                provider = "local",
                model = "deterministic",
                latencyMs = 0L,
                fallbackReason = "local_route:${routing.reason}"
            )
        }
        val candidates = buildCandidates()
        if (candidates.isEmpty()) {
            return SmsAiAnalysis(
                availability = SmsAiAvailability.UNAVAILABLE,
                understanding = null,
                provider = "none",
                model = "none",
                latencyMs = 0L,
                fallbackReason = "not_configured"
            )
        }

        val failures = mutableListOf<String>()
        val orchestrationStartedAt = System.currentTimeMillis()
        for (candidate in candidates) {
            var attempt = 0
            while (attempt < maxAttemptsPerCandidate) {
                val startedAt = System.currentTimeMillis()
                try {
                    val providerResponse = withTimeoutOrNull(candidate.config.timeoutMs.coerceIn(2_000L, 9_000L)) {
                        candidate.provider.understand(request, candidate.config, tools)
                    } ?: throw SmsAiProviderException("timeout", kind = SmsAiFailureKind.TIMEOUT)
                    candidate.profileId?.let { profiles?.recordResult(it, success = true) }
                    return SmsAiAnalysis(
                        availability = SmsAiAvailability.AVAILABLE,
                        understanding = providerResponse.understanding,
                        provider = providerResponse.provider,
                        model = providerResponse.model,
                        latencyMs = System.currentTimeMillis() - startedAt,
                        usage = providerResponse.usage,
                        fallbackReason = if (failures.isEmpty()) null else "fallback_after:${failures.joinToString(",")}"
                    )
                } catch (error: SmsAiProviderException) {
                    candidate.profileId?.let {
                        profiles?.recordResult(it, success = false, error = error.message)
                    }
                    failures += "${candidate.config.provider}:${error.kind.name}${error.httpCode?.let { "_$it" }.orEmpty()}"
                    val retryable = error.kind == SmsAiFailureKind.RETRYABLE_HTTP ||
                        error.kind == SmsAiFailureKind.NETWORK ||
                        error.kind == SmsAiFailureKind.TIMEOUT
                    attempt += 1
                    if (!retryable || attempt >= maxAttemptsPerCandidate) break
                    delay((baseBackoffMs * (1L shl (attempt - 1))).coerceAtMost(maxBackoffMs))
                } catch (error: Exception) {
                    candidate.profileId?.let {
                        profiles?.recordResult(it, success = false, error = error.message)
                    }
                    failures += "${candidate.config.provider}:${error.javaClass.simpleName}"
                    break
                }
            }
        }
        return SmsAiAnalysis(
            availability = SmsAiAvailability.DEGRADED,
            understanding = null,
            provider = candidates.first().config.provider,
            model = candidates.first().config.model,
            latencyMs = System.currentTimeMillis() - orchestrationStartedAt,
            fallbackReason = "all_providers_failed:${failures.joinToString(",")}"
        )
    }

    suspend fun testProfile(profileId: String): SmsAiAnalysis {
        val profile = profiles?.get(profileId) ?: return SmsAiAnalysis(
            availability = SmsAiAvailability.UNAVAILABLE,
            understanding = null,
            provider = "none",
            model = "none",
            latencyMs = 0L,
            fallbackReason = "profile_not_found"
        )
        val request = SmsAiRequest(
            message = "اختبار اتصال فقط. أخرج JSON بفهم الرسالة دون تنفيذ أي عملية.",
            phone = "",
            customerName = "",
            conversationId = "health-check",
            lastIntent = "",
            pendingAction = "",
            contextJson = JSONObject(),
            preferencesJson = JSONObject(),
            draftJson = null
        )
        val tools = object : SmsAiToolExecutor {
            override fun definitions() = org.json.JSONArray()
            override suspend fun execute(call: SmsAiToolCall) = SmsAiToolResult(call.id, call.name, false, JSONObject().put("status", "DENIED"))
        }
        val config = profile.toRuntimeConfig()
        val startedAt = System.currentTimeMillis()
        return try {
            val providerResponse = withTimeoutOrNull(config.timeoutMs.coerceIn(2_000L, 9_000L)) {
                providerFor(profile.provider).understand(request, config, tools)
            } ?: throw SmsAiProviderException("timeout")
            profiles.recordResult(profile.id, success = true)
            SmsAiAnalysis(SmsAiAvailability.AVAILABLE, providerResponse.understanding, providerResponse.provider, providerResponse.model, System.currentTimeMillis() - startedAt, providerResponse.usage)
        } catch (error: Exception) {
            profiles.recordResult(profile.id, success = false, error = error.message)
            SmsAiAnalysis(SmsAiAvailability.DEGRADED, null, profile.provider, profile.model, System.currentTimeMillis() - startedAt, fallbackReason = "test_failed:${error.javaClass.simpleName}")
        }
    }

    fun publicProfiles(): org.json.JSONArray = profiles?.publicJson() ?: org.json.JSONArray()
    fun deleteProfile(id: String): Boolean = profiles?.delete(id) ?: false
    fun saveProfile(profile: SmsAiProviderProfile): SmsAiProviderProfile = requireNotNull(profiles).upsert(profile)
    fun setProfileEnabled(id: String, enabled: Boolean): Boolean = profiles?.setEnabled(id, enabled) ?: false

    private fun buildCandidates(): List<Candidate> {
        val profileCandidates = profiles?.list()
            .orEmpty()
            .filter { it.enabled && it.apiKey.isNotBlank() && it.endpoint.startsWith("https://") && it.model.isNotBlank() && it.usedToday < it.dailyLimit }
            .sortedWith(compareBy<SmsAiProviderProfile> { it.priority }.thenByDescending { healthScore(it) })
            .map { profile -> Candidate(profile.id, profile.toRuntimeConfig(), providerFor(profile.provider)) }
        val legacy = runCatching { SmsAiConfigStore(appContext).get() }.getOrNull()
            ?.takeIf { it.usable() }
            ?.let { Candidate(null, it, providerFor(it.provider)) }
        return profileCandidates + listOfNotNull(legacy)
    }

    private fun providerFor(provider: String): SmsAiProvider = if (provider.equals("gemini", true)) geminiProvider else remoteProvider

    private fun healthScore(profile: SmsAiProviderProfile): Double {
        val total = profile.successCount + profile.failureCount
        return if (total == 0L) 0.5 else profile.successCount.toDouble() / total.toDouble()
    }

    private fun SmsAiProviderProfile.toRuntimeConfig() = SmsAiRuntimeConfig(
        enabled = enabled,
        provider = provider,
        endpoint = endpoint,
        model = model,
        apiKey = apiKey,
        timeoutMs = 8_000L,
        maxOutputTokens = 700,
        minimumConfidence = minConfidence
    )

    private data class Candidate(
        val profileId: String?,
        val config: SmsAiRuntimeConfig,
        val provider: SmsAiProvider
    )
}
