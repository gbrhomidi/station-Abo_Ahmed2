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

/** Adapter رسمي لـ Gemini Generative Language API، منفصل عن Conversation/Business Engine. */
class SmsGeminiAiProvider : SmsAiProvider {
    override suspend fun understand(
        request: SmsAiRequest,
        config: SmsAiRuntimeConfig,
        tools: SmsAiToolExecutor
    ): SmsAiProviderResponse = withContext(Dispatchers.IO) {
        require(config.usable()) { "Gemini provider is not configured" }
        val contents = JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", SmsAiPromptFactory.userMessage(request))))
        })
        val toolDeclarations = geminiToolDeclarations(tools.definitions())
        var promptTokens = 0
        var completionTokens = 0
        var totalTokens = 0

        repeat(MAX_TOOL_ROUNDS) { round ->
            val payload = JSONObject().apply {
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(
                    JSONObject().put("text", SmsAiPromptFactory.systemInstructions())
                )))
                put("contents", contents)
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                    put("maxOutputTokens", config.maxOutputTokens)
                    put("responseMimeType", "application/json")
                })
                if (toolDeclarations.length() > 0) {
                    put("tools", JSONArray().put(JSONObject().put("functionDeclarations", toolDeclarations)))
                }
            }
            val response = call(config, payload)
            val usage = response.optJSONObject("usageMetadata")
            promptTokens += usage?.optInt("promptTokenCount", 0) ?: 0
            completionTokens += usage?.optInt("candidatesTokenCount", 0) ?: 0
            totalTokens += usage?.optInt("totalTokenCount", 0) ?: 0
            val candidateContent = response.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?: throw SmsAiProviderException("Gemini response missing content")
            val parts = candidateContent.optJSONArray("parts") ?: JSONArray()
            val functionCalls = mutableListOf<JSONObject>()
            var text: String? = null
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                part.optJSONObject("functionCall")?.let { functionCalls += it }
                part.optString("text", "").takeIf { it.isNotBlank() }?.let { text = it }
            }
            if (functionCalls.isNotEmpty()) {
                if (round == MAX_TOOL_ROUNDS - 1) throw SmsAiProviderException("Gemini tool call limit exceeded")
                contents.put(JSONObject().apply {
                    put("role", "model")
                    put("parts", parts)
                })
                val responses = JSONArray()
                for (functionCall in functionCalls) {
                    val name = functionCall.optString("name", "").trim()
                    val args = functionCall.optJSONObject("args") ?: JSONObject()
                    val result = tools.execute(SmsAiToolCall(name, name, args))
                    responses.put(JSONObject().apply {
                        put("functionResponse", JSONObject().apply {
                            put("name", name)
                            put("response", result.output)
                        })
                    })
                }
                contents.put(JSONObject().apply {
                    put("role", "user")
                    put("parts", responses)
                })
            } else {
                val raw = text?.trim().orEmpty()
                if (raw.isBlank()) throw SmsAiProviderException("Gemini response text is empty")
                val json = JSONObject(cleanJson(raw))
                return@withContext SmsAiProviderResponse(
                    understanding = SmsAiUnderstanding.fromJson(json),
                    usage = SmsAiUsage(promptTokens, completionTokens, totalTokens),
                    provider = config.provider,
                    model = config.model
                )
            }
        }
        throw SmsAiProviderException("Gemini did not return a final understanding")
    }

    private fun call(config: SmsAiRuntimeConfig, payload: JSONObject): JSONObject {
        val endpoint = config.endpoint.trimEnd('/')
        val url = if (endpoint.endsWith(":generateContent")) endpoint else "$endpoint/models/${config.model}:generateContent"
        val client = OkHttpClient.Builder()
            .connectTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", config.apiKey)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw SmsAiProviderException("Gemini HTTP ${response.code}")
                if (body.isBlank()) throw SmsAiProviderException("Gemini returned empty body")
                JSONObject(body)
            }
        } catch (e: SmsAiProviderException) {
            throw e
        } catch (e: Exception) {
            throw SmsAiProviderException("Gemini network failure: ${e.javaClass.simpleName}", e)
        }
    }

    private fun geminiToolDeclarations(openAiTools: JSONArray): JSONArray = JSONArray().apply {
        for (index in 0 until openAiTools.length()) {
            val function = openAiTools.optJSONObject(index)?.optJSONObject("function") ?: continue
            put(JSONObject().apply {
                put("name", function.optString("name", ""))
                put("description", function.optString("description", ""))
                put("parameters", function.optJSONObject("parameters") ?: JSONObject().put("type", "OBJECT"))
            })
        }
    }

    private fun cleanJson(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            trimmed.removePrefix("```").removeSuffix("```").removePrefix("json").trim()
        } else trimmed
    }

    companion object {
        private const val MAX_TOOL_ROUNDS = 3
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
