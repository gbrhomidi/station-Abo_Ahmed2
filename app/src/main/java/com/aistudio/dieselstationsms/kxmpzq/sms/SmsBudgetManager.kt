package com.aistudio.dieselstationsms.kxmpzq.sms

import android.telephony.SmsManager

/**
 * ميزانية SMS مركزية. لا تفترض أن invocation الإرسال نجاح نهائي.
 */
object SmsBudgetManager {
    enum class Level { SHORT, NORMAL, DETAILED, ADMIN, DRIVER, INVOICE }
    enum class Priority { CRITICAL, HIGH, NORMAL, LOW }

    data class PreparedMessage(
        val body: String,
        val partsCount: Int,
        val characterCount: Int,
        val level: Level,
        val priority: Priority
    )

    fun prepare(
        body: String,
        level: Level = Level.NORMAL,
        priority: Priority = Priority.NORMAL
    ): PreparedMessage {
        val clean = SmsMessageNormalizer.normalizeForSms(body)
        val parts = divideMessage(clean)
        return PreparedMessage(
            body = clean,
            partsCount = parts.size.coerceAtLeast(1),
            characterCount = clean.length,
            level = level,
            priority = priority
        )
    }

    fun divideMessage(body: String): List<String> {
        if (body.isEmpty()) return emptyList()
        return try {
            @Suppress("DEPRECATION")
            SmsManager.getDefault().divideMessage(body)
        } catch (_: Exception) {
            listOf(body)
        }
    }
}
