package com.aistudio.dieselstationsms.kxmpzq.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.MainActivity
import com.aistudio.dieselstationsms.kxmpzq.receiver.TaskNotificationReceiver
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * مدير إشعارات المهام المحلية.
 *
 * الإشعار مرتبط بسجل tasks الفعلي في SQLite، ولا يعتمد على WebView أو شبكة.
 * يتم جدولة التذكير عند الساعة 09:00 بالتوقيت المحلي ليوم المهمة، وعند
 * تجاوز التاريخ يتم إرسال تذكير واحد يومياً ثم جدولة التذكير التالي لليوم
 * التالي ما دامت المهمة معلقة وغير مؤرشفة وغير محذوفة.
 */
object TaskNotificationManager {
    const val ACTION_TASK_NOTIFICATION = "com.aistudio.dieselstationsms.TASK_NOTIFICATION"
    const val EXTRA_TASK_ID = "task_id"

    const val CHANNEL_ID = "pending_tasks_alerts"
    private const val CHANNEL_NAME = "تنبيهات المهام المعلقة"
    private const val PREFS_NAME = "task_notification_state"
    private const val KEY_SCHEDULED_IDS = "scheduled_task_ids"
    private const val KEY_LAST_ALERT_PREFIX = "last_alert_day_"
    private const val REMINDER_HOUR = 9
    private const val REMINDER_MINUTE = 0

    private val dateOnlyFormat = ThreadLocal<SimpleDateFormat>()
    private val synchronizationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TaskNotificationSync").apply { isDaemon = true }
    }

    private fun dateFormat(): SimpleDateFormat {
        return dateOnlyFormat.get() ?: SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).also {
            it.isLenient = false
            dateOnlyFormat.set(it)
        }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "إشعارات المهام المعلقة والمتأخرة في محطة الوقود"
            enableVibration(true)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun reschedulePendingTasksAsync(context: Context) {
        val appContext = context.applicationContext
        synchronizationExecutor.execute {
            reschedulePendingTasks(appContext)
        }
    }

    /**
     * مزامنة كل إشعارات المهام المعلقة مع SQLite.
     * يمكن استدعاؤها عند بدء التطبيق أو بعد الإقلاع أو تغيير الوقت.
     */
    fun reschedulePendingTasks(context: Context) {
        val appContext = context.applicationContext
        ensureChannel(appContext)
        val database = runCatching { DatabaseHelper.getInstance(appContext) }.getOrElse {
            android.util.Log.e("TaskNotifications", "Database initialization failed", it)
            return
        }
        val rows = runCatching {
            database.getPendingTasks(JSONObject().apply {
                put("include_archived", false)
                put("include_resolved", false)
                put("limit", 5000)
            })
        }.getOrElse {
            android.util.Log.e("TaskNotifications", "Could not read pending tasks", it)
            return
        }

        val currentIds = mutableSetOf<String>()
        for (index in 0 until rows.length()) {
            val task = rows.optJSONObject(index) ?: continue
            val taskId = task.optLong("id", 0L)
            if (taskId <= 0L) continue
            currentIds += taskId.toString()
            synchronizeTask(appContext, task)
        }

        val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val previousIds = preferences.getStringSet(KEY_SCHEDULED_IDS, emptySet()).orEmpty()
        previousIds.filter { it !in currentIds }.forEach { staleId ->
            staleId.toLongOrNull()?.let { cancelTaskNotification(appContext, it) }
        }
        preferences.edit().putStringSet(KEY_SCHEDULED_IDS, currentIds).apply()
    }

    /** مزامنة مهمة واحدة بعد الإنشاء أو التعديل أو الحل أو الأرشفة. */
    fun synchronizeTask(context: Context, taskId: Long) {
        if (taskId <= 0L) return
        // إعادة القراءة من SQLite تمنع جدولة بيانات قديمة أرسلتها الواجهة.
        reschedulePendingTasksAsync(context)
    }

    /** يستخدمه المستقبل بعد وصول Alarm للتحقق من أن المهمة ما زالت معلقة. */
    fun deliverTaskNotification(context: Context, taskId: Long) {
        if (taskId <= 0L) return
        val appContext = context.applicationContext
        ensureChannel(appContext)
        val database = runCatching { DatabaseHelper.getInstance(appContext) }.getOrElse {
            android.util.Log.e("TaskNotifications", "Database initialization failed", it)
            return
        }
        val rows = runCatching {
            database.getPendingTasks(JSONObject().apply {
                put("include_archived", false)
                put("include_resolved", false)
                put("limit", 5000)
            })
        }.getOrElse {
            android.util.Log.e("TaskNotifications", "Could not read task for notification", it)
            return
        }
        val task = findTask(rows, taskId)
        if (task == null) {
            cancelTaskNotification(appContext, taskId)
            return
        }

        val today = dateFormat().format(Date())
        val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alreadyAlertedToday = preferences.getString(KEY_LAST_ALERT_PREFIX + taskId, null) == today
        if (!alreadyAlertedToday) {
            showTaskNotification(appContext, task)
            preferences.edit().putString(KEY_LAST_ALERT_PREFIX + taskId, today).apply()
        }
        scheduleNextDailyReminder(appContext, taskId)
    }

    fun cancelTaskNotification(context: Context, taskId: Long) {
        if (taskId <= 0L) return
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        alarmManager?.cancel(pendingIntent(appContext, taskId))
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(notificationId(taskId))
        val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val scheduled = preferences.getStringSet(KEY_SCHEDULED_IDS, emptySet()).orEmpty().toMutableSet()
        scheduled.remove(taskId.toString())
        preferences.edit()
            .putStringSet(KEY_SCHEDULED_IDS, scheduled)
            .remove(KEY_LAST_ALERT_PREFIX + taskId)
            .apply()
    }

    private fun synchronizeTask(context: Context, task: JSONObject) {
        val taskId = task.optLong("id", 0L)
        if (taskId <= 0L) return
        val taskDate = task.optString("date", task.optString("task_date", "")).trim()
        val dueAt = reminderTime(taskDate) ?: run {
            android.util.Log.w("TaskNotifications", "Invalid task date for taskId=$taskId: $taskDate")
            cancelAlarmOnly(context, taskId)
            return
        }
        val now = System.currentTimeMillis()
        if (dueAt > now) {
            scheduleAlarm(context, taskId, dueAt)
            return
        }

        val today = dateFormat().format(Date())
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (preferences.getString(KEY_LAST_ALERT_PREFIX + taskId, null) != today) {
            showTaskNotification(context, task)
            preferences.edit().putString(KEY_LAST_ALERT_PREFIX + taskId, today).apply()
        }
        scheduleNextDailyReminder(context, taskId)
    }

    private fun showTaskNotification(context: Context, task: JSONObject) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.w("TaskNotifications", "POST_NOTIFICATIONS is not granted; notification skipped")
            return
        }
        ensureChannel(context)
        val taskId = task.optLong("id", 0L)
        val type = task.optString("type", task.optString("task_type", "مهمة معلقة"))
        val reference = task.optString("reference", "").trim()
        val status = task.optString("status", "قيد التنفيذ")
        val priority = task.optString("priority", "متوسطة")
        val message = buildString {
            append("الحالة: ").append(status)
            append(" | الأولوية: ").append(priority)
            if (reference.isNotBlank()) append(" | المرجع: ").append(reference)
        }
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("notification_action", "open_tasks")
            putExtra(EXTRA_TASK_ID, taskId)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId(taskId),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("مهمة معلقة: $type")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setWhen(System.currentTimeMillis())
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.notify(notificationId(taskId), notification)
    }

    private fun scheduleNextDailyReminder(context: Context, taskId: Long) {
        val calendar = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, REMINDER_HOUR)
            set(Calendar.MINUTE, REMINDER_MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        scheduleAlarm(context, taskId, calendar.timeInMillis)
    }

    private fun scheduleAlarm(context: Context, taskId: Long, triggerAtMillis: Long) {
        if (taskId <= 0L) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val alarmIntent = pendingIntent(context, taskId)
        alarmManager.cancel(alarmIntent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmIntent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmIntent)
        }
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ids = preferences.getStringSet(KEY_SCHEDULED_IDS, emptySet()).orEmpty().toMutableSet()
        ids += taskId.toString()
        preferences.edit().putStringSet(KEY_SCHEDULED_IDS, ids).apply()
    }

    private fun cancelAlarmOnly(context: Context, taskId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        alarmManager?.cancel(pendingIntent(context, taskId))
    }

    private fun pendingIntent(context: Context, taskId: Long): PendingIntent {
        val intent = Intent(context, TaskNotificationReceiver::class.java).apply {
            action = ACTION_TASK_NOTIFICATION
            putExtra(EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context,
            notificationId(taskId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notificationId(taskId: Long): Int {
        return ((taskId xor (taskId ushr 32)).toInt() and 0x7fffffff).coerceAtLeast(1)
    }

    private fun reminderTime(taskDate: String): Long? {
        val date = runCatching { dateFormat().parse(taskDate) }.getOrNull() ?: return null
        return Calendar.getInstance().apply {
            time = date
            set(Calendar.HOUR_OF_DAY, REMINDER_HOUR)
            set(Calendar.MINUTE, REMINDER_MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun findTask(rows: JSONArray, taskId: Long): JSONObject? {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            if (row.optLong("id", 0L) == taskId) return row
        }
        return null
    }
}
