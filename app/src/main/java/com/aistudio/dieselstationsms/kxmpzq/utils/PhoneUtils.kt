package com.aistudio.dieselstationsms.kxmpzq.utils

import android.util.Log

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

    private const val TAG = "PhoneUtils"
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
            var normalized = phone.trim()
                .replace(" ", "")
                .replace("-", "")
                .replace("(", "")
                .replace(")", "")
                .replace("+", "")

            // إزالة البادئات المكررة
            when {
                normalized.startsWith("00967") -> normalized = normalized.substring(5)
                normalized.startsWith("967") -> normalized = normalized.substring(3)
                normalized.startsWith("00") -> normalized = normalized.substring(2)
            }

            // إزالة صفر الاتصال المحلي الوطني (trunk 0) للصيغة المحلية 07XXXXXXXX
            if (normalized.startsWith("0") && normalized.length == 10) {
                normalized = normalized.substring(1)
            }

            // إضافة بادئة الدولة إذا لم تكن موجودة
            if (!normalized.startsWith(COUNTRY_CODE) && normalized.length == 9) {
                normalized = COUNTRY_CODE + normalized
            }

            // التحقق من الطول القياسي
            if (normalized.length != 12) {
                Log.w(TAG, "Phone number length invalid: ${normalized.length} for input: ${maskPhone(phone)}")
                return null
            }

            // التحقق من بادئة الشركة اليمنية
            val nationalPrefix = normalized.substring(3, 5)
            if (nationalPrefix !in YEMEN_MOBILE_PREFIXES) {
                Log.w(TAG, "Unsupported Yemen mobile prefix: $nationalPrefix for input: ${maskPhone(phone)}")
                return null
            }

            normalized
        } catch (e: Exception) {
            Log.e(TAG, "Failed to normalize phone: ${maskPhone(phone)}", e)
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