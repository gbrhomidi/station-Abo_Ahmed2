package com.aistudio.dieselstationsms.kxmpzq.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aistudio.dieselstationsms.kxmpzq.startup.DatabaseInitializationCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** عامل النقل الصادر. QUEUED لا تصبح SENT إلا عبر PendingIntent callback. */
 class SmsOutboxWorker @JvmOverloads constructor(
    appContext: Context,
    workerParams: WorkerParameters,
    private val transportSender: SmsTransportSender = SmsTransport(appContext)
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = DatabaseInitializationCoordinator.awaitDatabase(applicationContext)
        // Repair interrupted dedupe reservations even when transport is currently disabled.
        runCatching { SmsOutboxAtomicTransaction.recoverOutboundDedupeReservations(db) }
            .onFailure { android.util.Log.e("SmsOutboxWorker", "Outbound dedupe recovery failed", it) }
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
        val batch = mutableListOf<SmsOutboxRepository.OutboxMessage>()
        while (batch.size < MAX_BATCH) {
            val job = SmsOutboxRepository.claimNext(db) ?: break
            batch += job
        }

        val eligibleMessageIds = try {
            DebtDueDateRepository.validateDebtReminderBatch(db, batch)
        } catch (exception: Exception) {
            // Fail closed: no claimed message is sent when the batch snapshot cannot be read.
            batch.forEach { job ->
                SmsOutboxRepository.markFailed(
                    db,
                    job.messageId,
                    "DEBT_REVALIDATION_ERROR",
                    exception.javaClass.simpleName,
                    retry = SmsOutboxRepository.shouldRetryAfterAttempt(job.attemptCount)
                )
                SmsFailureNotificationPublisher.publishForMessage(applicationContext, db, job.messageId)
            }
            return@withContext Result.retry()
        }

        val processed = batch.size
        batch.forEach { job ->
            try {
                if (job.messageId !in eligibleMessageIds) {
                    SmsOutboxRepository.cancelClaimed(
                        db,
                        job.messageId,
                        "Debt reminder no longer matches the trusted open debt or current recipient"
                    )
                } else {
                    SmsOutboxRepository.prepareParts(db, job.messageId, job.partsCount)
                    // The batch result is only a pre-filter. Re-read this customer's current
                    // debt and phone after part preparation, immediately before transport.
                    if (!DebtDueDateRepository.isCurrentDebtReminder(
                            helper = db,
                            eventId = job.eventId,
                            businessEntityId = job.businessEntityId,
                            recipient = job.recipient,
                            body = job.body
                        )
                    ) {
                        SmsOutboxRepository.cancelClaimed(
                            db,
                            job.messageId,
                            "Debt reminder failed final pre-transport revalidation"
                        )
                    } else {
                        transportSender.send(job)
                    }
                }
            } catch (securityException: SecurityException) {
                SmsOutboxRepository.markFailed(db, job.messageId, "SEND_SMS_PERMISSION", securityException.message.orEmpty(), retry = false)
                SmsFailureNotificationPublisher.publishForMessage(applicationContext, db, job.messageId)
            } catch (exception: Exception) {
                SmsOutboxRepository.markFailed(
                    db,
                    job.messageId,
                    exception.javaClass.simpleName,
                    exception.message.orEmpty(),
                    retry = SmsOutboxRepository.shouldRetryAfterAttempt(job.attemptCount)
                )
                SmsFailureNotificationPublisher.publishForMessage(applicationContext, db, job.messageId)
            }
        }

        if (SmsOutboxRepository.hasPending(db) && processed >= MAX_BATCH) Result.retry() else Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "sms-outbox-transport"
        private const val PERIODIC_RECOVERY_WORK_NAME = "sms-outbox-recovery-periodic"
        private const val RECOVERY_INTERVAL_MINUTES = 15L
        private const val MAX_BATCH = 20
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<SmsOutboxWorker>().build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }

        /**
         * Register a periodic durable outbox scan and request an immediate startup pass.
         * The periodic worker closes the process-death window between SQLite commit and
         * enqueueUniqueWork(), even when the app is not reopened.
         */
        fun scheduleRecovery(context: Context) {
            val workManager = WorkManager.getInstance(context.applicationContext)
            val periodicRequest = PeriodicWorkRequestBuilder<SmsOutboxWorker>(
                RECOVERY_INTERVAL_MINUTES,
                TimeUnit.MINUTES
            ).build()
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_RECOVERY_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )

            val immediateRequest = OneTimeWorkRequestBuilder<SmsOutboxWorker>().build()
            workManager.enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                immediateRequest
            )
        }
    }
}
