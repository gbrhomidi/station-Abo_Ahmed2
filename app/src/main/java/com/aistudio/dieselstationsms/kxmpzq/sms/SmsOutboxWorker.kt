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
import java.util.UUID

/** عامل النقل الصادر. QUEUED لا تصبح SENT إلا عبر PendingIntent callback. */
 class SmsOutboxWorker @JvmOverloads constructor(
    appContext: Context,
    workerParams: WorkerParameters,
    private val transportSender: SmsTransportSender = SmsTransport(appContext)
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = DatabaseInitializationCoordinator.awaitDatabase(applicationContext)

        // Reconcile expired leases and missing delivery receipts even when sending is disabled.
        SmsOutboxRepository.recoverStuck(db).forEach { messageId ->
            SmsFailureNotificationPublisher.publishForMessage(applicationContext, db, messageId)
        }
        SmsOutboxRepository.failDeliveryTimeouts(db).forEach { messageId ->
            SmsFailureNotificationPublisher.publishForMessage(applicationContext, db, messageId)
        }
        runCatching { SmsOutboxAtomicTransaction.recoverOutboundDedupeReservations(db) }
            .onFailure {
                android.util.Log.e(
                    "SmsOutboxWorker",
                    "Outbound dedupe recovery failed (${it.javaClass.simpleName})"
                )
            }

        if (db.getSetting("sms_send_enabled") == "0") return@withContext Result.success()
        if (ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.SEND_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext Result.success()
        }

        // Restore messages failed only because SEND_SMS was denied, once permission is restored.
        SmsOutboxRepository.requeuePermissionFailures(db)
        val workerLeaseOwner = UUID.randomUUID().toString()
        val batch = mutableListOf<SmsOutboxRepository.OutboxMessage>()
        while (batch.size < MAX_BATCH) {
            val job = SmsOutboxRepository.claimNext(db, leaseOwner = workerLeaseOwner) ?: break
            batch += job
        }

        val eligibleMessageIds = try {
            DebtDueDateRepository.validateDebtReminderBatch(db, batch)
        } catch (exception: Exception) {
            // No carrier call started: release claims without consuming a transport attempt.
            batch.forEach { job ->
                SmsOutboxRepository.releaseClaimForLocalRetry(
                    db, job, "DEBT_REVALIDATION_ERROR"
                )
            }
            return@withContext Result.retry()
        }

        val processed = batch.size
        batch.forEach { job ->
            var transportStarted = false
            try {
                if (job.messageId !in eligibleMessageIds) {
                    SmsOutboxRepository.cancelClaimed(
                        db,
                        job.messageId,
                        "Debt reminder no longer matches the trusted open debt or current recipient",
                        job.attemptToken
                    )
                } else {
                    SmsOutboxRepository.prepareParts(
                        db, job.messageId, job.partsCount, job.attemptToken
                    )

                    // The batch result is only a pre-filter. Re-read the current debt, recipient,
                    // and exact body immediately before crossing the carrier-submission boundary.
                    val stillEligible = DebtDueDateRepository.isCurrentDebtReminder(
                        helper = db,
                        eventId = job.eventId,
                        businessEntityId = job.businessEntityId,
                        recipient = job.recipient,
                        body = job.body
                    )
                    if (!stillEligible) {
                        SmsOutboxRepository.cancelClaimed(
                            db,
                            job.messageId,
                            "Debt reminder failed final pre-transport revalidation",
                            job.attemptToken
                        )
                    } else {
                        val submittingJob = SmsOutboxRepository.beginTransport(db, job)
                        if (submittingJob != null) {
                            transportStarted = true
                            transportSender.send(submittingJob)
                        }
                    }
                }
            } catch (securityException: SecurityException) {
                if (transportStarted) {
                    SmsOutboxRepository.markFailed(
                        db,
                        job.messageId,
                        "SEND_SMS_PERMISSION",
                        securityException.javaClass.simpleName,
                        retry = false,
                        attemptToken = job.attemptToken
                    )
                    SmsFailureNotificationPublisher.publishForMessage(
                        applicationContext, db, job.messageId
                    )
                } else {
                    SmsOutboxRepository.releaseClaimForLocalRetry(
                        db, job, "PRE_TRANSPORT_SECURITY_CHECK"
                    )
                }
            } catch (exception: Exception) {
                if (transportStarted) {
                    // Once SmsManager is entered, an exception does not prove rejection by the modem.
                    SmsOutboxRepository.markSubmissionUnknown(
                        db, job, exception.javaClass.simpleName
                    )
                    SmsFailureNotificationPublisher.publishForMessage(
                        applicationContext, db, job.messageId
                    )
                } else {
                    SmsOutboxRepository.releaseClaimForLocalRetry(
                        db, job, exception.javaClass.simpleName
                    )
                }
            }
        }

        if (SmsOutboxRepository.hasPending(db) && processed >= MAX_BATCH) {
            Result.retry()
        } else {
            Result.success()
        }
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
