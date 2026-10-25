package com.aistudio.dieselstationsms.kxmpzq.utils

/**
 * ═══════════════════════════════════════════════════════════════
 * PhoneUtils – أدوات مساعدة موحدة للتعامل مع أرقام الهواتف
 * ═══════════════════════════════════════════════════════════════
 *
 * يوفر دوال مشتركة للتعامل مع أرقام الهواتف بدلاً من
 * تكرار normalizePhone() في عدة ملفات.
 *
 * Yemen Mobile Contract:
 *   Country Code: +967
 *   Valid Prefixes: 70 (واي), 71 (سبأ فون), 73 (الشركة اليمنية العمانية),
 *                   77 (يمن موبايل), 78 (يمن موبايل)
 *   Canonical Format: 967 + 9-digit national number
 * ═══════════════════════════════════════════════════════════════
 */
object PhoneUtils {

    private const val COUNTRY_CODE = "967"

    // Yemen mobile prefixes per project contract
    private val YEMEN_MOBILE_PREFIXES = setOf("70", "71", "73", "77", "78")

    /**
     * تطبيع رقم الهاتف:
     * - إزالة المسافات والشرطات
     * - إضافة بادئة الدولة إذا لزم الأمر
     * - توحيد الصيغة
     *
     * @param phone رقم الهاتف الأصلي
     * @return الرقم المُطَبَّع أو null إذا كان غير صالح
     */
    @JvmStatic
    fun normalize(phone: String?): String? {
        if (phone.isNullOrBlank()) return null

        return try {
            // Convert all Unicode decimal digits (Arabic, Persian, fullwidth, etc.) to ASCII.
            val canonicalInput = buildString(phone.length) {
                for (ch in phone) {
                    val digit = when (ch.code) {
                        in 0x0660..0x0669 -> ch.code - 0x0660
                        in 0x06F0..0x06F9 -> ch.code - 0x06F0
                        in 0xFF10..0xFF19 -> ch.code - 0xFF10
                        else -> Character.digit(ch, 10)
                    }
                    when {
                        digit in 0..9 -> append(('0'.code + digit).toChar())
                        ch == '+' || ch == '\uFF0B' -> append('+')
                        ch.isWhitespace() || Character.isSpaceChar(ch) -> Unit
                        Character.getType(ch) == Character.FORMAT.toInt() -> Unit // bidi/zero-width formatting
                        ch == '-' || ch == '\u2010' || ch == '\u2011' || ch == '\u2012' ||
                            ch == '\u2013' || ch == '\u2014' || ch == '(' || ch == ')' ||
                            ch == '.' || ch == '/' -> Unit
                        else -> append(ch)
                    }
                }
            }
            var normalized = canonicalInput.replace("+", "")

            // Reject remaining non-ASCII numeric/text characters instead of silently accepting them.
            if (normalized.any { it !in '0'..'9' }) return null

            // Strip international access/country prefixes.
            when {
                normalized.startsWith("00967") -> normalized = normalized.substring(5)
                normalized.startsWith("967") -> normalized = normalized.substring(3)
                normalized.startsWith("00") -> normalized = normalized.substring(2)
            }

            // Strip domestic trunk zero from 10-digit local format.
            if (normalized.startsWith("0") && normalized.length == 10) {
                normalized = normalized.substring(1)
            }

            if (normalized.length == 9) normalized = COUNTRY_CODE + normalized
            if (normalized.length != 12) return null

            val nationalPrefix = normalized.substring(3, 5)
            if (nationalPrefix !in YEMEN_MOBILE_PREFIXES) return null
            normalized
        } catch (_: Exception) {
            null
        }
    }

    /**
     * مقارنة رقمين بعد تطبيعهما
     */
    @JvmStatic
    fun isSameNumber(phone1: String?, phone2: String?): Boolean {
        val norm1 = normalize(phone1)
        val norm2 = normalize(phone2)
        return norm1 != null && norm2 != null && norm1 == norm2
    }

    /**
     * التحقق مما إذا كان الرقم يبدأ ببادئة معينة
     */
    @JvmStatic
    fun startsWithPrefix(phone: String?, prefix: String): Boolean {
        return normalize(phone)?.startsWith(prefix) == true
    }

    /**
     * إخفاء جزء من الرقم لأغراض التسجيل (privacy)
     */
    private fun maskPhone(phone: String?): String {
        if (phone == null || phone.length <= 4) return "***"
        return phone.take(3) + "***" + phone.takeLast(2)
    }
}