package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.Context
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest

/** بوابة AI الوحيدة؛ تختار local/cloud عبر routing ثم تستدعي orchestrator متعدد المزودين. */
class SmsAiGateway(
    context: Context,
    private val db: DatabaseHelper,
    private val repository: SmsCognitiveRepository = SmsCognitiveRepository(db)
) {
    private val appContext = context.applicationContext
    private val orchestrator = SmsAiResourceOrchestrator(appContext)
    private val routingEngine = SmsAiRoutingEngine()

    suspend fun understand(
        request: SmsAiRequest,
        tools: SmsAiToolExecutor,
        routing: SmsAiRoutingDecision? = null
    ): SmsAiAnalysis = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val effectiveRouting = routing ?: routingEngine.decide(
            request.message,
            SmsConversationManager.ConversationContext(
                lastIntent = request.lastIntent,
                pendingAction = request.pendingAction,
                conversationId = request.conversationId
            )
        )
        val result = runCatching {
            orchestrator.understand(request, tools, effectiveRouting)
        }.getOrElse { error ->
            Log.w(TAG, "AI orchestration unavailable: ${error.javaClass.simpleName}")
            SmsAiAnalysis(
                availability = SmsAiAvailability.DEGRADED,
                understanding = null,
                provider = "orchestrator",
                model = "none",
                latencyMs = System.currentTimeMillis() - startedAt,
                fallbackReason = "orchestrator_failure:${error.javaClass.simpleName}"
            )
        }
        record(request, result)
        result
    }

    suspend fun testProvider(profileId: String): SmsAiAnalysis = withContext(Dispatchers.IO) {
        orchestrator.testProfile(profileId)
    }

    fun publicConfig(): JSONObject = JSONObject().apply {
        put("legacy", runCatching { SmsAiConfigStore(appContext).get().publicJson() }
            .getOrElse { JSONObject().put("enabled", false).put("configured", false) })
        put("profiles", orchestrator.publicProfiles())
        put("routing", "local_first")
    }

    fun saveConfig(config: SmsAiRuntimeConfig) {
        SmsAiConfigStore(appContext).save(config)
    }

    fun clearConfig() {
        SmsAiConfigStore(appContext).clear()
    }

    fun saveProfile(profile: SmsAiProviderProfile): SmsAiProviderProfile = orchestrator.saveProfile(profile)
    fun deleteProfile(profileId: String): Boolean = orchestrator.deleteProfile(profileId)
    fun setProfileEnabled(profileId: String, enabled: Boolean): Boolean = orchestrator.setProfileEnabled(profileId, enabled)

    private fun record(request: SmsAiRequest, result: SmsAiAnalysis) {
        runCatching {
            repository.recordAiRun(
                conversationId = request.conversationId,
                eventId = request.contextJson.optString("event_id", ""),
                provider = result.provider,
                model = result.model,
                requestHash = sha256(request.message),
                latencyMs = result.latencyMs,
                availability = result.availability.name,
                confidence = result.understanding?.confidence,
                usage = result.usage,
                fallbackReason = result.fallbackReason,
                errorType = null
            )
        }.onFailure { Log.w(TAG, "AI observability write failed", it) }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "SmsAiGateway"
    }
}
