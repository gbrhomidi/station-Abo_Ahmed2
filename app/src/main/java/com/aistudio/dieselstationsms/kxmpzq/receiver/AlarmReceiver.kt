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
 * AlarmReceiver – مستقبل إعادة جدولة المهام (نسخة نهائية)
 * ═══════════════════════════════════════════════════════════════
 *
 * مسؤولياته (فقط):
 * 1. إرسال المهمة المجدولة إلى SMSService للتنفيذ
 *
 * ❌ لا يحتوي على دالة recordEvent()
 * ❌ لا يتعامل مع قاعدة البيانات مباشرة
 * ✅ يستخدم SystemEventLogger للتسجيل
 * ═══════════════════════════════════════════════════════════════
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
        const val EXTRA_TASK_TYPE = "task_type"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val taskType = intent.getStringExtra(EXTRA_TASK_TYPE) ?: "unknown"
        Log.i(TAG, "Alarm triggered: task=$taskType")

        try {
            val serviceIntent = Intent(context, SMSService::class.java).apply {
                putExtra("action", "execute_scheduled_task")
                putExtra("task_type", taskType)
            }
            context.startService(serviceIntent)

            SystemEventLogger.record(context, "ALARM_EXECUTED", "task=$taskType")

        } catch (e: Exception) {
            Log.e(TAG, "Alarm dispatch failed: ${e.message}")
            SystemEventLogger.recordError(context, "AlarmReceiver", e.message)
        }
    }
}