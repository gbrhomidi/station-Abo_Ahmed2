package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.Context
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.security.MessageDigest

/** بوابة واحدة لمسار AI؛ SmsProcessor لا يعرف تفاصيل HTTP أو مزود النموذج. */
class SmsAiGateway(
    context: Context,
    private val db: DatabaseHelper,
    private val repository: SmsCognitiveRepository = SmsCognitiveRepository(db)
) {
    private val appContext = context.applicationContext
    private val provider: SmsAiProvider = SmsRemoteAiProvider()

    suspend fun understand(
        request: SmsAiRequest,
        tools: SmsAiToolExecutor
    ): SmsAiAnalysis = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val config = loadConfig()
        if (!config.usable()) {
            val result = SmsAiAnalysis(
                availability = SmsAiAvailability.UNAVAILABLE,
                understanding = null,
                provider = config.provider,
                model = config.model,
                latencyMs = 0L,
                fallbackReason = if (config.apiKey.isBlank()) "not_configured" else "disabled_or_invalid_config"
            )
            record(request, result, startedAt, null)
            return@withContext result
        }

        try {
            val response = withTimeoutOrNull(config.timeoutMs) {
                provider.understand(request, config, tools)
            } ?: throw SmsAiProviderException("AI timeout")
            val result = SmsAiAnalysis(
                availability = SmsAiAvailability.AVAILABLE,
                understanding = response.understanding,
                provider = response.provider,
                model = response.model,
                latencyMs = System.currentTimeMillis() - startedAt,
                usage = response.usage
            )
            record(request, result, startedAt, null)
            result
        } catch (e: Exception) {
            Log.w(TAG, "AI inference unavailable: ${e.javaClass.simpleName}")
            val result = SmsAiAnalysis(
                availability = SmsAiAvailability.DEGRADED,
                understanding = null,
                provider = config.provider,
                model = config.model,
                latencyMs = System.currentTimeMillis() - startedAt,
                fallbackReason = classifyFailure(e)
            )
            record(request, result, startedAt, e)
            result
        }
    }

    fun publicConfig(): JSONObject = runCatching { SmsAiConfigStore(appContext).get().publicJson() }
        .getOrElse { JSONObject().put("enabled", false).put("configured", false).put("status", "secure_store_unavailable") }

    fun saveConfig(config: SmsAiRuntimeConfig) {
        SmsAiConfigStore(appContext).save(config)
    }

    fun clearConfig() {
        SmsAiConfigStore(appContext).clear()
    }

    private fun loadConfig(): SmsAiRuntimeConfig = runCatching {
        SmsAiConfigStore(appContext).get()
    }.getOrElse {
        SmsAiRuntimeConfig()
    }

    private fun record(
        request: SmsAiRequest,
        result: SmsAiAnalysis,
        startedAt: Long,
        error: Throwable?
    ) {
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
                errorType = error?.javaClass?.simpleName
            )
        }.onFailure { Log.w(TAG, "AI observability write failed", it) }
    }

    private fun classifyFailure(error: Throwable): String = when (error) {
        is SmsAiProviderException -> error.message?.take(160) ?: "provider_error"
        else -> error.javaClass.simpleName
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "SmsAiGateway"
    }
}
