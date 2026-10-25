package com.aistudio.dieselstationsms.kxmpzq.sms

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Exercises the actual WorkManager consumer against the production SQLite database and outbox
 * repository. Only the carrier-facing sender is replaced; no real SMS is transmitted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmsOutboxWorkerIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: DatabaseHelper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context.applicationContext as android.app.Application)
            .grantPermissions(Manifest.permission.SEND_SMS)
        DatabaseHelper.closeInstance()
        database = DatabaseHelper.getInstance(context)
        database.writableDatabase.delete("sms_outbox_parts", null, null)
        database.writableDatabase.delete("sms_outbox", null, null)
        database.writableDatabase.delete("sms_messages", null, null)
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
    }

    @Test
    fun workerClaimsQueuedMessageAndPassesItToInjectedTransport() = runBlocking {
        val queued = SmsOutboxRepository.enqueue(
            database,
            "967771234567",
            "Worker integration message",
            dedupeKey = "worker-integration-dedupe"
        )!!
        val sender = RecordingTransport()
        val worker = TestListenableWorkerBuilder.from(context, SmsOutboxWorker::class.java)
            .setWorkerFactory(sender.factory())
            .build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(queued.messageId), sender.sentMessageIds)
        assertEquals("SENDING", status(queued.messageId))

        // The production callback persistence path records carrier outcomes after transport send.
        SmsOutboxRepository.recordPartResult(
            database,
            queued.messageId,
            0,
            delivered = false,
            success = true,
            resultCode = "RESULT_OK"
        )
        assertEquals("DELIVERY_PENDING", status(queued.messageId))
        SmsOutboxRepository.recordPartResult(
            database,
            queued.messageId,
            0,
            delivered = true,
            success = true,
            resultCode = "RESULT_OK"
        )
        assertEquals("DELIVERED", status(queued.messageId))
        assertEquals("delivered", legacyStatus(queued.messageId))
    }

    private fun status(id: String): String =
        database.readableDatabase.rawQuery(
            "SELECT status FROM sms_outbox WHERE message_id = ?", arrayOf(id)
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }

    private fun legacyStatus(id: String): String =
        database.readableDatabase.rawQuery(
            "SELECT status FROM sms_messages WHERE uuid = ?", arrayOf(id)
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }

    private class RecordingTransport {
        val sentMessageIds = mutableListOf<String>()

        fun factory(): WorkerFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker? {
                if (workerClassName != SmsOutboxWorker::class.java.name) return null
                return SmsOutboxWorker(appContext, workerParameters, object : SmsTransportSender {
                    override fun send(job: SmsOutboxRepository.OutboxMessage) {
                        sentMessageIds += job.messageId
                    }
                })
            }
        }
    }
}
