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
 * TimeChangedReceiver – مستقبل تغيير الوقت (نسخة نهائية)
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
class TimeChangedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TimeChangedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_TIME_SET && action != Intent.ACTION_TIME_CHANGED) return

        Log.i(TAG, "Time changed: $action")

        try {
            val serviceIntent = Intent(context, SMSService::class.java).apply {
                putExtra("action", "reschedule_tasks")
                putExtra("reason", action)
            }
            context.startService(serviceIntent)

            SystemEventLogger.record(context, "TIME_CHANGED", action)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle time change: ${e.message}")
            SystemEventLogger.recordError(context, "TimeChangedReceiver", e.message)
        }
    }
}