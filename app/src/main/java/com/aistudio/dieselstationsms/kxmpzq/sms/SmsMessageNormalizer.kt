package com.aistudio.dieselstationsms.kxmpzq.sms

import java.util.Locale

/**
 * تطبيع واحد للنص قبل المطابقة أو حساب طول SMS.
 * لا يزيل كلمات أو معاني؛ يزيل فقط اختلافات Unicode والمسافات.
 */
object SmsMessageNormalizer {
    private val DIACRITICS = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]")
    private val WHITESPACE = Regex("\\s+")
    private val DECORATIVE_LINE = Regex("[═━─_*#]{3,}")

    fun normalizeForMatch(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val baseNormalized = normalizeDigits(
            value
                .lowercase(Locale.ROOT)
                .replace(DIACRITICS, "")
                .replace('أ', 'ا')
                .replace('إ', 'ا')
                .replace('آ', 'ا')
                .replace('ٱ', 'ا')
                .replace('ى', 'ي')
                .replace('ة', 'ه')
                .replace('ؤ', 'و')
                .replace('ئ', 'ي')
                .replace("ـ", "")
        ).replace(WHITESPACE, " ").trim()

        return normalizeFuelSemantics(baseNormalized)
    }

    private fun normalizeFuelSemantics(text: String): String {
        var result = text
        // تطبيع الكسور والفواصل العربية
        result = result.replace("،", ".")

        // توحيد مصطلحات الوقود
        result = result.replace(Regex("\\b(دبه|دباب|دبابات)\\b"), "دبة")
        result = result.replace(Regex("\\b(برميل|براميل)\\b"), "برميل")
        result = result.replace(Regex("\\b(لتر|لترات|ل)\\b"), "لتر")
        result = result.replace(Regex("\\b(عادي|اخضر)\\b"), "بنزين 91")
        result = result.replace(Regex("\\b(احمر|ممتاز)\\b"), "بنزين 95")

        // توحيد الأوامر السياقية
        result = result.replace(Regex("\\b(بكره|بكرى|غدا)\\b"), "غدا")
        result = result.replace(Regex("\\b(نفس السابق|نفسه|زي اول)\\b"), "نفس الطلب")
        result = result.replace(Regex("\\b(غير|عدل|بدل)\\b"), "تعديل")
        result = result.replace(Regex("\\b(الغ|الغي|كنسل|بطلت)\\b"), "الغاء")
        result = result.replace(Regex("\\b(اكد|تم|اعتمد|موافق)\\b"), "تاكيد")

        return result
    }

    fun normalizeForSms(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value
            .replace(DECORATIVE_LINE, "")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .replace(Regex("[ \\t]{2,}"), " ")
            .trim()
    }

    fun normalizeDigits(value: String): String = buildString(value.length) {
        value.forEach { char ->
            append(
                when (char) {
                    in '\u0660'..'\u0669' -> ('0'.code + (char.code - '\u0660'.code)).toChar()
                    in '\u06F0'..'\u06F9' -> ('0'.code + (char.code - '\u06F0'.code)).toChar()
                    else -> char
                }
            )
        }
    }
}
