package com.aistudio.dieselstationsms.kxmpzq.sms

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** مزود فعلي يستدعي Chat Completions عبر HTTPS. لا يوجد Mock أو رد ثابت. */
class SmsRemoteAiProvider : SmsAiProvider {
    override suspend fun understand(
        request: SmsAiRequest,
        config: SmsAiRuntimeConfig,
        tools: SmsAiToolExecutor
    ): SmsAiProviderResponse = withContext(Dispatchers.IO) {
        if (!config.usable()) {
            throw SmsAiProviderException("AI provider is not configured", kind = SmsAiFailureKind.AUTHENTICATION)
        }
        val messages = JSONArray()
            .put(JSONObject().apply {
                put("role", "system")
                put("content", SmsAiPromptFactory.systemInstructions())
            })
            .put(JSONObject().apply {
                put("role", "user")
                put("content", SmsAiPromptFactory.userMessage(request))
            })
        val availableTools = tools.definitions()
        var totalPromptTokens = 0
        var totalCompletionTokens = 0
        var totalTokens = 0

        repeat(MAX_TOOL_ROUNDS) { round ->
            val payload = JSONObject().apply {
                put("model", config.model)
                put("messages", messages)
                put("temperature", 0.1)
                put("max_completion_tokens", config.maxOutputTokens)
                put("response_format", SmsAiPromptFactory.responseFormat())
                if (availableTools.length() > 0) {
                    put("tools", availableTools)
                    put("tool_choice", "auto")
                }
            }
            val response = call(config, payload)
            response.optJSONObject("usage")?.let {
                totalPromptTokens += it.optInt("prompt_tokens", 0)
                totalCompletionTokens += it.optInt("completion_tokens", 0)
                totalTokens += it.optInt("total_tokens", 0)
            }
            val message = response.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?: throw SmsAiProviderException("AI response missing message")
            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                if (round == MAX_TOOL_ROUNDS - 1) throw SmsAiProviderException("AI tool call limit exceeded")
                messages.put(JSONObject().apply {
                    put("role", "assistant")
                    put("tool_calls", toolCalls)
                })
                for (index in 0 until toolCalls.length()) {
                    val callJson = toolCalls.optJSONObject(index)
                        ?: throw SmsAiProviderException("Malformed AI tool call")
                    val function = callJson.optJSONObject("function")
                        ?: throw SmsAiProviderException("AI tool function missing")
                    val name = function.optString("name", "").trim()
                    val rawArguments = function.optString("arguments", "{}")
                    val arguments = runCatching { JSONObject(rawArguments) }
                        .getOrElse { throw SmsAiProviderException("Malformed AI tool arguments") }
                    val result = tools.execute(
                        SmsAiToolCall(
                            id = callJson.optString("id", "tool_$index"),
                            name = name,
                            arguments = arguments
                        )
                    )
                    messages.put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", result.toolCallId)
                        put("name", result.name)
                        put("content", result.output.toString().take(MAX_TOOL_OUTPUT_CHARS))
                    })
                }
            } else {
                val content = cleanJson(message.optString("content", ""))
                if (content.isBlank()) throw SmsAiProviderException("AI response content is empty")
                val understanding = runCatching { SmsAiUnderstanding.fromJson(JSONObject(content)) }
                    .getOrElse { throw SmsAiProviderException("AI response schema validation failed", it, SmsAiFailureKind.MALFORMED_RESPONSE) }
                return@withContext SmsAiProviderResponse(
                    understanding = understanding,
                    usage = SmsAiUsage(totalPromptTokens, totalCompletionTokens, totalTokens),
                    provider = config.provider,
                    model = config.model
                )
            }
        }
        throw SmsAiProviderException("AI provider did not return a final understanding")
    }

    private fun call(config: SmsAiRuntimeConfig, payload: JSONObject): JSONObject {
        val client = OkHttpClient.Builder()
            .connectTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder()
            .url(config.endpoint)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val providerMessage = runCatching { JSONObject(body).optString("error", "") }.getOrDefault("")
                    val safeReason = if (providerMessage.length <= 160) providerMessage else "provider_error"
                    val kind = when (response.code) {
                        401, 403 -> SmsAiFailureKind.AUTHENTICATION
                        429 -> SmsAiFailureKind.QUOTA
                        in 500..599 -> SmsAiFailureKind.RETRYABLE_HTTP
                        else -> SmsAiFailureKind.HTTP
                    }
                    throw SmsAiProviderException("AI HTTP ${response.code}: $safeReason", kind = kind, httpCode = response.code)
                }
                if (body.isBlank()) throw SmsAiProviderException("AI provider returned empty body", kind = SmsAiFailureKind.MALFORMED_RESPONSE)
                JSONObject(body)
            }
        } catch (e: SmsAiProviderException) {
            throw e
        } catch (e: Exception) {
            throw SmsAiProviderException("AI network failure: ${e.javaClass.simpleName}", e, SmsAiFailureKind.NETWORK)
        }
    }

    private fun cleanJson(content: String): String {
        val trimmed = content.trim()
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            return trimmed.removePrefix("```").removeSuffix("```").removePrefix("json").trim()
        }
        return trimmed
    }

    companion object {
        private const val MAX_TOOL_ROUNDS = 3
        private const val MAX_TOOL_OUTPUT_CHARS = 5000
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
