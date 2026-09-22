package com.aistudio.dieselstationsms.kxmpzq.sms

import org.json.JSONArray
import org.json.JSONObject

interface SmsAiToolExecutor {
    fun definitions(): JSONArray
    suspend fun execute(call: SmsAiToolCall): SmsAiToolResult
}

data class SmsAiToolResult(
    val toolCallId: String,
    val name: String,
    val success: Boolean,
    val output: JSONObject,
    val riskLevel: String = "LOW"
)

interface SmsAiProvider {
    suspend fun understand(
        request: SmsAiRequest,
        config: SmsAiRuntimeConfig,
        tools: SmsAiToolExecutor
    ): SmsAiProviderResponse
}

data class SmsAiProviderResponse(
    val understanding: SmsAiUnderstanding,
    val usage: SmsAiUsage,
    val provider: String,
    val model: String
)
