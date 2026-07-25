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
 * ═══════════════════════════════════════════════════════════════
 */
object PhoneUtils {

    private const val TAG = "PhoneUtils"

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
                normalized.startsWith("00966") -> normalized = normalized.substring(5)
                normalized.startsWith("966") -> normalized = normalized.substring(3)
                normalized.startsWith("00") -> normalized = normalized.substring(2)
            }

            // إضافة بادئة الدولة إذا لم تكن موجودة
            if (!normalized.startsWith("966") && normalized.length == 9) {
                normalized = "966$normalized"
            }

            // التحقق من الطول
            if (normalized.length < 9) {
                Log.w(TAG, "Phone number too short: $normalized")
                return null
            }

            normalized
        } catch (e: Exception) {
            Log.e(TAG, "Failed to normalize phone: $phone", e)
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
}