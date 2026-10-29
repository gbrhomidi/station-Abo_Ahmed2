package com.aistudio.dieselstationsms.kxmpzq.sms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.MainActivity
import com.aistudio.dieselstationsms.kxmpzq.utils.PhoneUtils
import java.util.concurrent.TimeUnit

/**
 * Periodically queues one SMS reminder for a customer's debt on its due date.
 *
 * The station is passed from the authenticated user session; the worker never
 * scans or sends across station boundaries. The database's eligible flag and
 * the outbox dedupe key jointly prevent repeat reminders for the same due date.
 */
class DebtDueReminderWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val stationId = inputData.getInt(KEY_STATION_ID, 0)
        val userId = inputData.getLong(KEY_USER_ID, 0L)
        if (stationId <= 0 || userId <= 0L) return Result.success()

        val db = DatabaseHelper.getInstance(applicationContext)
        return try {
            if (db.getSetting("sms_send_enabled") == "0") return Result.success()

            // Create/repair maturity dates before the reminder query runs. The same
            // date calculation is used by the customer-debt screen and the UI banner.
            DebtDueDateRepository.ensureSchema(db)
            val dueRows = DebtDueDateRepository.getDueSoonDebtReminders(db, stationId, 0)
            var queuedCount = 0
            val remindedNames = mutableListOf<String>()
            for (index in 0 until dueRows.length()) {
                val row = dueRows.optJSONObject(index) ?: continue
                if (row.optInt("days_remaining", Int.MAX_VALUE) != 0) continue
                if (!row.optBoolean("eligible", false)) continue

                val customerId = row.optLong("customer_party_id", 0L)
                val transactionId = row.optLong("transaction_id", 0L)
                val balance = row.optDouble("total_outstanding", 0.0)
                val phone = PhoneUtils.normalize(row.optString("phone"))
                val dueDate = row.optString("nearest_due_date").trim()
                if (customerId <= 0L || transactionId <= 0L || balance <= 0.0 ||
                    phone == null || dueDate.isBlank()
                ) continue

                // A reminder already recorded on the due date is enough; a reminder
                // from yesterday must not suppress the actual due-date notification.
                if (row.optString("last_reminder_at").take(10) == dueDate) continue

                val dueAmount = row.optDouble("due_soon_amount", balance)
                val balanceText = formatAmount(balance)
                val dueAmountText = formatAmount(dueAmount)
                val customerName = row.optString("customer_name").trim().ifBlank { "عميلنا الكريم" }
                val message = "مرحباً $customerName، نذكّركم بأن موعد استحقاق الدين هو اليوم ($dueDate). " +
                    "المبلغ المستحق اليوم $dueAmountText ر. ي، وإجمالي الرصيد القائم $balanceText ر. ي. شاكرين تعاونكم."

                val eventId = "debt-reminder:$stationId:$customerId:$dueDate"
                val dedupeKey = "$eventId:$phone"
                val alreadyQueued = db.readableDatabase.rawQuery(
                    "SELECT 1 FROM sms_outbox WHERE dedupe_key = ? LIMIT 1",
                    arrayOf(dedupeKey)
                ).use { it.moveToFirst() }
                if (alreadyQueued) continue

                val result = SmsOutboxRepository.enqueue(
                    db = db,
                    recipient = phone,
                    body = SmsMessageNormalizer.normalizeForSms(message),
                    eventId = eventId,
                    businessEntityId = "party:$customerId",
                    priority = SmsBudgetManager.Priority.HIGH,
                    dedupeKey = dedupeKey
                ) ?: continue

                // Persist the exact due-day event only after the durable outbox accepted it.
                db.recordQueuedDebtReminder(
                    customerId, transactionId, phone, message,
                    createdBy = userId, daysBeforeDue = 0
                )
                if (result.status == "QUEUED" || result.status == "RETRY_PENDING") {
                    queuedCount++
                    if (customerName !in remindedNames) remindedNames += customerName
                }
            }

            if (queuedCount > 0 || SmsOutboxRepository.hasPending(db)) {
                SmsOutboxWorker.schedule(applicationContext)
            }
            if (queuedCount > 0) postDueReminderNotification(stationId, queuedCount, remindedNames)
            Result.success()
        } catch (exception: Exception) {
            Result.retry()
        }
    }

    private fun postDueReminderNotification(stationId: Int, count: Int, names: List<String>) {
        try {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "debt_due_reminders"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(channelId, "تذكيرات الديون المستحقة", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "إشعارات داخلية عند تجهيز رسائل تذكير الديون المستحقة"
                    }
                )
            }
            val openApp = PendingIntent.getActivity(
                applicationContext,
                0xD3B7 + (stationId and 0x0FFF),
                Intent(applicationContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("open_screen", "تذكيرات الديون")
                },
                PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )
            val detail = names.take(3).joinToString("، ")
            val notification = Notification.Builder(applicationContext, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("تذكيرات الديون المستحقة")
                .setContentText("تم تجهيز $count رسالة تذكير للعملاء المستحقة ديونهم اليوم" + if (detail.isBlank()) "" else ": $detail")
                .setStyle(Notification.BigTextStyle().bigText("تمت إضافة $count رسالة تذكير إلى طابور SMS بسبب حلول تاريخ الاستحقاق اليوم. ستُرسل وفق إعدادات الرسائل وأذونات الجهاز."))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build()
            manager.notify(0xD3B7 + (stationId and 0x0FFF), notification)
        } catch (_: SecurityException) {
            // Notification permission is optional; the durable SMS outbox still works.
        } catch (_: Exception) {
            // Never fail/retry the debt SMS batch solely because the UI notification failed.
        }
    }

    private fun formatAmount(value: Double): String = if (value % 1.0 == 0.0) {
        value.toLong().toString()
    } else {
        String.format(java.util.Locale.US, "%,.2f", value)
    }

    companion object {
        private const val KEY_STATION_ID = "debt_reminder_station_id"
        private const val KEY_USER_ID = "debt_reminder_user_id"
        private const val WORK_PREFIX = "debt-due-sms-reminders-station-"

        /** WorkManager persists this schedule across process death and device restarts. */
        fun schedule(context: Context, stationId: Int, userId: Long) {
            if (stationId <= 0 || userId <= 0L) return
            val request = PeriodicWorkRequestBuilder<DebtDueReminderWorker>(
                15, TimeUnit.MINUTES
            ).setInputData(
                Data.Builder()
                    .putInt(KEY_STATION_ID, stationId)
                    .putLong(KEY_USER_ID, userId)
                    .build()
            ).build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_PREFIX + stationId,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
