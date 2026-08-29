package com.aistudio.dieselstationsms.kxmpzq.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aistudio.dieselstationsms.kxmpzq.notifications.TaskNotificationManager

/** يستقبل تذكير المهمة المجدول ويفوض التحقق والنشر إلى مدير الإشعارات. */
class TaskNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TaskNotificationManager.ACTION_TASK_NOTIFICATION) return
        val taskId = intent.getLongExtra(TaskNotificationManager.EXTRA_TASK_ID, 0L)
        if (taskId <= 0L) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                TaskNotificationManager.deliverTaskNotification(appContext, taskId)
            } catch (error: Exception) {
                Log.e(TAG, "Failed to deliver task notification for taskId=$taskId", error)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private companion object {
        const val TAG = "TaskNotificationReceiver"
    }
}
