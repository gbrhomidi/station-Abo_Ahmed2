package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.Context
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
        SmsOutboxRepository.recoverStuck(db)
        var processed = 0

        while (processed < MAX_BATCH) {
            val job = SmsOutboxRepository.claimNext(db) ?: break
            try {
                SmsOutboxRepository.prepareParts(db, job.messageId, job.partsCount)
                SmsTransport(applicationContext).send(job)
            } catch (securityException: SecurityException) {
                SmsOutboxRepository.markFailed(db, job.messageId, "SEND_SMS_PERMISSION", securityException.message.orEmpty(), retry = false)
            } catch (exception: Exception) {
                SmsOutboxRepository.markFailed(
                    db,
                    job.messageId,
                    exception.javaClass.simpleName,
                    exception.message.orEmpty(),
                    retry = job.attemptCount < MAX_ATTEMPTS
                )
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
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
