package com.aistudio.dieselstationsms.kxmpzq.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
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

            // The native query is station-scoped and returns the actual due-date,
            // outstanding balance, phone and eligibility state from SQLite.
            val dueRows = db.getDueSoonDebtReminders(stationId, 0)
            var queuedCount = 0
            for (index in 0 until dueRows.length()) {
                val row = dueRows.optJSONObject(index) ?: continue
                if (!row.optBoolean("eligible", false)) continue
                if (row.optInt("days_remaining", Int.MAX_VALUE) != 0) continue

                val customerId = row.optLong("customer_party_id", 0L)
                val transactionId = row.optLong("transaction_id", 0L)
                val balance = row.optDouble("total_outstanding", 0.0)
                val phone = PhoneUtils.normalize(row.optString("phone"))
                val dueDate = row.optString("nearest_due_date").trim()
                if (customerId <= 0L || transactionId <= 0L || balance <= 0.0 ||
                    phone == null || dueDate.isBlank()
                ) continue

                val balanceText = if (balance % 1.0 == 0.0) {
                    balance.toLong().toString()
                } else {
                    String.format(java.util.Locale.US, "%,.2f", balance)
                }
                val customerName = row.optString("customer_name").trim().ifBlank { "عميلنا الكريم" }
                val message = "مرحباً $customerName، نذكّركم بأن موعد استحقاق الدين هو اليوم ($dueDate). " +
                    "يرجى تسديد الرصيد المستحق وقدره $balanceText ر. ي. شاكرين تعاونكم."

                val eventId = "debt-due-reminder:$stationId:$customerId:$dueDate"
                val result = SmsOutboxRepository.enqueue(
                    db = db,
                    recipient = phone,
                    body = SmsMessageNormalizer.normalizeForSms(message),
                    eventId = eventId,
                    businessEntityId = "party:$customerId",
                    priority = SmsBudgetManager.Priority.HIGH,
                    dedupeKey = "$eventId:$phone"
                ) ?: continue

                // Record only after the outbox accepted the reminder. The
                // eligibility query prevents a second audit record on later runs.
                db.recordQueuedDebtReminder(customerId, transactionId, phone, message, userId)
                if (result.status == "QUEUED" || result.status == "RETRY_PENDING") queuedCount++
            }

            if (queuedCount > 0 || SmsOutboxRepository.hasPending(db)) {
                SmsOutboxWorker.schedule(applicationContext)
            }
            Result.success()
        } catch (exception: Exception) {
            Result.retry()
        }
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
