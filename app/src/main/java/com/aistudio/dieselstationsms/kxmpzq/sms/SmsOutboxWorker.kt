package com.aistudio.dieselstationsms.kxmpzq.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** عامل النقل الصادر. QUEUED لا تصبح SENT إلا عبر PendingIntent callback. */
class SmsOutboxWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = DatabaseHelper.getInstance(applicationContext)
        // Respect the global outbound switch and leave messages durable while permission is absent.
        if (db.getSetting("sms_send_enabled") == "0") return@withContext Result.success()
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return@withContext Result.success()
        }
        // Restore messages failed only because SEND_SMS was denied, once permission is restored.
        SmsOutboxRepository.requeuePermissionFailures(db)
        SmsOutboxRepository.recoverStuck(db)
        SmsOutboxRepository.failDeliveryTimeouts(db).forEach { messageId ->
            SmsFailureNotificationPublisher.publishForMessage(applicationContext, db, messageId)
        }
        var processed = 0

        while (processed < MAX_BATCH) {
            val job = SmsOutboxRepository.claimNext(db) ?: break
            try {
                SmsOutboxRepository.prepareParts(db, job.messageId, job.partsCount)
                SmsTransport(applicationContext).send(job)
            } catch (securityException: SecurityException) {
                SmsOutboxRepository.markFailed(db, job.messageId, "SEND_SMS_PERMISSION", securityException.message.orEmpty(), retry = false)
                SmsFailureNotificationPublisher.publishForMessage(applicationContext, db, job.messageId)
            } catch (exception: Exception) {
                SmsOutboxRepository.markFailed(
                    db,
                    job.messageId,
                    exception.javaClass.simpleName,
                    exception.message.orEmpty(),
                    retry = job.attemptCount < MAX_ATTEMPTS
                )
                SmsFailureNotificationPublisher.publishForMessage(applicationContext, db, job.messageId)
            }
            processed++
        }

        if (SmsOutboxRepository.hasPending(db) && processed >= MAX_BATCH) Result.retry() else Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "sms-outbox-transport"
        private const val MAX_BATCH = 20
        private const val MAX_ATTEMPTS = 3

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<SmsOutboxWorker>().build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }
    }
}
