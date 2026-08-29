package com.aistudio.dieselstationsms.kxmpzq.receiver

import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.utils.SystemEventLogger
import com.aistudio.dieselstationsms.kxmpzq.service.SMSService

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * ═══════════════════════════════════════════════════════════════
 * PackageUpdatedReceiver – مستقبل تحديث التطبيق (نسخة نهائية)
 * ═══════════════════════════════════════════════════════════════
 *
 * مسؤولياته (فقط):
 * 1. إعادة تشغيل SMSService بعد التحديث
 *
 * ❌ لا يحتوي على دالة recordEvent()
 * ❌ لا يتعامل مع قاعدة البيانات مباشرة
 * ✅ يستخدم SystemEventLogger للتسجيل
 * ═══════════════════════════════════════════════════════════════
 */
class PackageUpdatedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PackageUpdatedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        Log.i(TAG, "App updated, restarting SMSService...")

        try {
            val serviceIntent = Intent(context, SMSService::class.java).apply {
                putExtra("action", "app_updated")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            SystemEventLogger.record(context, "APP_UPDATED")
            Log.i(TAG, "SMSService restarted after update")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart service: ${e.message}", e)
            SystemEventLogger.recordError(context, "PackageUpdatedReceiver", e.message)
        }
    }
}