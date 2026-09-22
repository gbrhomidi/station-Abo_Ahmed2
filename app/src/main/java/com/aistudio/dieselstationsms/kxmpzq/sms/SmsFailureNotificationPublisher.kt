package com.aistudio.dieselstationsms.kxmpzq.sms

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.MainActivity
import java.security.MessageDigest
import java.util.UUID

/**
 * ينشر تنبيهاً محلياً فورياً عند فشل إرسال/تسليم SMS.
 * الجدول الدائم يمنع تكرار التنبيه نفسه بعد إعادة تشغيل الخدمة أو تكرار callback.
 */
object SmsFailureNotificationPublisher {
    private const val CHANNEL_ID = "sms_delivery_failures"
    private const val CHANNEL_NAME = "تنبيهات تسليم الرسائل"
    private const val GROUP_KEY = "station.sms.delivery"

    fun publishForMessage(context: Context, db: DatabaseHelper, messageId: String): Boolean {
        val failure = loadFailure(db, messageId) ?: return false
        if (!hasNotificationPermission(context) || !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false
        }
        ensureChannel(context)
        val retrying = failure.status == "RETRY_PENDING"
        val title = if (retrying) "تعذر تسليم رسالة SMS — ستتم إعادة المحاولة" else "فشل تسليم رسالة SMS"
        val body = buildString {
            append("الوجهة: ")
            append(failure.recipient)
            append("\n")
            append(if (retrying) "سيعيد النظام المحاولة تلقائياً." else "تحتاج الرسالة إلى متابعة يدوية.")
            if (failure.code.isNotBlank()) {
                append("\nرمز الخطأ: ")
                append(failure.code)
            }
            if (failure.reason.isNotBlank()) {
                append("\nالتفاصيل: ")
                append(failure.reason)
            }
        }
        val dedupeKey = sha256("$messageId|${failure.status}|${failure.code}|${failure.reason}")
        val inserted = db.writableDatabase.insertWithOnConflict(
            "sms_failure_notifications",
            null,
            android.content.ContentValues().apply {
                put("notification_id", UUID.randomUUID().toString())
                put("dedupe_key", dedupeKey)
                put("message_id", messageId)
                put("recipient", failure.recipient)
                put("delivery_status", failure.status)
                put("failure_code", failure.code)
                put("failure_reason", failure.reason)
                put("notification_title", title)
                put("notification_body", body)
                put("notified_at", System.currentTimeMillis())
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
        )
        if (inserted == -1L) return false

        val openIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("open_sms_message_id", messageId)
            putExtra("open_sms_failures", true)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            messageId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText("اضغط لفتح مركز الرسائل")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_KEY)
            .setContentIntent(pendingIntent)
            .build()
        return runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(messageId, dedupeKey), notification)
            true
        }.getOrDefault(false)
    }

    private fun loadFailure(db: DatabaseHelper, messageId: String): Failure? =
        db.readableDatabase.rawQuery(
            "SELECT recipient, status, failure_code, failure_reason FROM sms_outbox WHERE message_id = ? LIMIT 1",
            arrayOf(messageId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val status = cursor.getString(1) ?: return@use null
            if (status != "FAILED" && status != "RETRY_PENDING") return@use null
            Failure(
                recipient = cursor.getString(0).orEmpty(),
                status = status,
                code = cursor.getString(2).orEmpty(),
                reason = cursor.getString(3).orEmpty()
            )
        }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "تنبيهات فشل إرسال وتسليم رسائل المحطة"
                enableVibration(true)
            }
        )
    }

    private fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notificationId(messageId: String, dedupeKey: String): Int =
        (sha256("$messageId|$dedupeKey").take(7).toLong(16) and 0x7fffffff).toInt()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private data class Failure(val recipient: String, val status: String, val code: String, val reason: String)
}
