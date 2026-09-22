package com.aistudio.dieselstationsms.kxmpzq.utils

import android.content.Context
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper

/**
 * ═══════════════════════════════════════════════════════════════
 * SystemEventLogger – مسجل الأحداث الموحد (طبقة وسيطة)
 * ═══════════════════════════════════════════════════════════════
 *
 * هذا الملف هو طبقة وسيطة PURE - لا يحتوي على أي منطق أعمال.
 * كل ما يفعله هو استدعاء DatabaseHelper.recordEvent() بشكل موحد.
 *
 * ✅ لا ينشئ DatabaseHelper مباشرة
 * ✅ لا يكتب في SQLite مباشرة
 * ✅ يستخدم DatabaseHelper كـ Singleton
 *
 * سلسلة الاستدعاء:
 * Receiver → SystemEventLogger → DatabaseHelper → SQLite
 *
 * ═══════════════════════════════════════════════════════════════
 */
object SystemEventLogger {

    private const val TAG = "SystemEventLogger"

    /**
     * تسجيل حدث عام في النظام
     *
     * @param context سياق التطبيق
     * @param eventType نوع الحدث (مثال: "BOOT_COMPLETED", "TIME_CHANGED")
     * @param details تفاصيل إضافية (اختياري)
     */
    @JvmStatic
    fun record(context: Context, eventType: String, details: String? = null) {
        try {
            DatabaseHelper.getInstance(context).recordEvent(
                eventType = eventType,
                details = details,
                timestamp = System.currentTimeMillis()
            )
            Log.d(TAG, "Recorded: $eventType")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record [$eventType]: ${e.message}")
        }
    }

    /**
     * تسجيل حدث إقلاع
     */
    @JvmStatic
    fun recordBoot(context: Context, eventType: String, details: String? = null) {
        record(context, "BOOT_$eventType", details)
    }

    /**
     * تسجيل حدث Receiver
     */
    @JvmStatic
    fun recordReceiver(context: Context, receiverName: String, action: String, details: String? = null) {
        record(context, "RECEIVER_${receiverName.uppercase()}", "$action | $details")
    }

    /**
     * تسجيل حدث خدمة
     */
    @JvmStatic
    fun recordService(context: Context, action: String, status: String) {
        record(context, "SERVICE", "$action → $status")
    }

    /**
     * تسجيل خطأ
     */
    @JvmStatic
    fun recordError(context: Context, source: String, error: String?) {
        record(context, "ERROR", "$source: $error")
    }

    /**
     * تسجيل تحذير
     */
    @JvmStatic
    fun recordWarning(context: Context, source: String, warning: String?) {
        record(context, "WARNING", "$source: $warning")
    }

    /**
     * تسجيل معلومات أمان
     */
    @JvmStatic
    fun recordSecurity(context: Context, eventType: String, details: String? = null) {
        record(context, "SECURITY_$eventType", details)
    }
}