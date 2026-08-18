package com.aistudio.dieselstationsms.kxmpzq.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.aistudio.dieselstationsms.kxmpzq.notifications.TaskNotificationManager
import com.aistudio.dieselstationsms.kxmpzq.service.SMSService

/**
 * يعيد تشغيل خدمة SMS بعد إقلاع الجهاز.
 * استقبال SMS نفسه يعتمد على SmsReceiver المعلن في Manifest، لذلك لا
 * يتوقف على نجاح هذا المستقبل، لكنه يضمن عودة المراقبة والإشعار والخدمات
 * المساندة بعد إعادة التشغيل.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON"
        )
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in SUPPORTED_ACTIONS) return

        val appContext = context.applicationContext
        TaskNotificationManager.reschedulePendingTasksAsync(appContext)
        val serviceIntent = Intent(appContext, SMSService::class.java).apply {
            putExtra("startup_reason", "BOOT")
            putExtra("launch_time", System.currentTimeMillis())
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(appContext, serviceIntent)
            } else {
                appContext.startService(serviceIntent)
            }
            Log.i(TAG, "SMSService launch requested after boot")
        }.onFailure {
            Log.e(TAG, "Failed to start SMSService after boot", it)
        }
    }
}
