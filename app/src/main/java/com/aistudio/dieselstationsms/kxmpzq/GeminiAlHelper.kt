package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GeminiAIHelper(private val context: Context) {
    
    companion object {
        private const val TAG = "GeminiAIHelper"
    }
    
    private var generativeModel: GenerativeModel? = null
    private val chatHistory = mutableListOf<com.google.ai.client.generativeai.type.Content>()
    
    fun initialize(apiKey: String) {
        try {
            val harassmentSafety = SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.ONLY_HIGH)
            val hateSpeechSafety = SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.ONLY_HIGH)
            
            generativeModel = GenerativeModel(
                modelName = "gemini-1.5-flash",
                apiKey = apiKey,
                safetySettings = listOf(harassmentSafety, hateSpeechSafety)
            )
            Log.d(TAG, "Gemini AI initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Gemini", e)
        }
    }
    
    suspend fun sendMessage(message: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val model = generativeModel ?: return@withContext "خطأ: النموذج غير مهيأ"
                
                val context = """
                    أنت مساعد ذكي لمحطة وقود. ساعد في:
                    - إدارة المخزون والمبيعات
                    - متابعة العملاء والموردين
                    - حسابات الورديات
                    - التقارير المالية
                    
                    السؤال: $message
                """.trimIndent()
                
                val chat = model.startChat(history = chatHistory)
                val response = chat.sendMessage(context)
                val text = response.text ?: "لم أفهم"
                
                chatHistory.add(content("user") { text(message) })
                chatHistory.add(content("model") { text(text) })
                
                if (chatHistory.size > 20) {
                    chatHistory.removeAt(0)
                    chatHistory.removeAt(0)
                }
                
                text
            } catch (e: Exception) {
                Log.e(TAG, "Error", e)
                "خطأ: ${e.message}"
            }
        }
    }
    
    fun sendMessageSync(message: String): String {
        return "استخدم sendToAI للاستجابة الفورية"
    }
}
