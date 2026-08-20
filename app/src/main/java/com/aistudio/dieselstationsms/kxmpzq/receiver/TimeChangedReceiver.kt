package com.aistudio.dieselstationsms.kxmpzq.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.aistudio.dieselstationsms.kxmpzq.service.SMSService

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
        Log.d(TAG, "Time changed: $action")
        val serviceIntent = Intent(context, SMSService::class.java).apply {
            putExtra("action", "reschedule_tasks")
            putExtra("reason", action)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}