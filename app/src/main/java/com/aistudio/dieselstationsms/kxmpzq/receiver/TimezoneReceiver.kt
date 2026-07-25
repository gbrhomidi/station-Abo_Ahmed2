package com.aistudio.dieselstationsms.kxmpzq.receiver

import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.utils.SystemEventLogger
import com.aistudio.dieselstationsms.kxmpzq.service.SMSService

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * ═══════════════════════════════════════════════════════════════
 * TimezoneReceiver – مستقبل تغيير المنطقة الزمنية (نسخة نهائية)
 * ═══════════════════════════════════════════════════════════════
 *
 * مسؤولياته (فقط):
 * 1. إعلام SMSService بضرورة إعادة الجدولة
 *
 * ❌ لا يحتوي على دالة recordEvent()
 * ❌ لا يتعامل مع قاعدة البيانات مباشرة
 * ✅ يستخدم SystemEventLogger للتسجيل
 * ═══════════════════════════════════════════════════════════════
 */
class TimezoneReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TimezoneReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIMEZONE_CHANGED) return

        Log.i(TAG, "Timezone changed")

        try {
            val serviceIntent = Intent(context, SMSService::class.java).apply {
                putExtra("action", "reschedule_tasks")
                putExtra("reason", "timezone_changed")
            }
            context.startService(serviceIntent)

            SystemEventLogger.record(context, "TIMEZONE_CHANGED")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle timezone change: ${e.message}")
            SystemEventLogger.recordError(context, "TimezoneReceiver", e.message)
        }
    }
}