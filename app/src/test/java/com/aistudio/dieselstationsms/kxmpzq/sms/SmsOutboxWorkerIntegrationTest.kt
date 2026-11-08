package com.aistudio.dieselstationsms.kxmpzq.sms

import android.Manifest
import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.aistudio.dieselstationsms.kxmpzq.BusinessDay
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
        database.setSetting("sms_send_enabled", "1")
        database.writableDatabase.delete("sms_outbox_attempt_parts", null, null)
        database.writableDatabase.delete("sms_outbox_attempts", null, null)
        database.writableDatabase.delete("sms_outbox_late_callbacks", null, null)
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

        // Callback persistence is owned by the exact claim attempt.
        val sentJob = sender.sentJobs.single()
        assertTrue(sentJob.attemptToken.isNotBlank())
        SmsOutboxRepository.recordPartResult(
            database,
            queued.messageId,
            0,
            delivered = false,
            success = true,
            resultCode = "RESULT_OK",
            attemptToken = sentJob.attemptToken
        )
        assertEquals("DELIVERY_PENDING", status(queued.messageId))
        SmsOutboxRepository.recordPartResult(
            database,
            queued.messageId,
            0,
            delivered = true,
            success = true,
            resultCode = "RESULT_OK",
            attemptToken = sentJob.attemptToken
        )
        assertEquals("DELIVERED", status(queued.messageId))
        assertEquals("delivered", legacyStatus(queued.messageId))
    }

    @Test
    fun concurrentDebtUpdatesDuringTransportPreventStaleMessagesAcrossOneHundredQueuedReminders() = runBlocking {
        val today = BusinessDay.currentDate()
        val queued = mutableListOf<SmsOutboxRepository.OutboxMessage>()
        val debtRows = mutableListOf<Pair<Long, Long>>()
        val probe = SmsOutboxRepository.enqueue(
            database,
            "967771234567",
            "Concurrent transport probe",
            dedupeKey = "load-transport-probe"
        )!!
        database.writableDatabase.update(
            "sms_outbox",
            ContentValues().apply { put("created_at", 0L) },
            "message_id = ?",
            arrayOf(probe.messageId)
        )
        val saleColumns = database.readableDatabase.rawQuery(
            "PRAGMA table_info(sales_transactions)", null
        ).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }
        repeat(100) { index ->
            val (customerId, saleId) = insertDebtFixture()
            val phone = "96777${(1_000_000 + index).toString()}"
            debtRows += customerId to saleId
            database.writableDatabase.insertOrThrow(
                "party_contacts",
                null,
                ContentValues().apply {
                    put("uuid", "load-contact-${System.nanoTime()}-$index")
                    put("party_id", customerId)
                    put("contact_name", "Load test $index")
                    put("phone", phone)
                    put("is_primary", 1)
                    put("is_active", 1)
                    put("is_deleted", 0)
                }
            )
            val saleUpdate = ContentValues().apply {
                put("due_date", today)
                put("due_date_source", "sale_creation")
                if ("payment_terms_source_at_sale" in saleColumns) {
                    put("payment_terms_source_at_sale", "explicit_text")
                }
            }
            database.writableDatabase.update(
                "sales_transactions", saleUpdate, "id = ?", arrayOf(saleId.toString())
            )

            val eventId = "debt-reminder:1:$customerId:$today:$saleId"
            val body = "موعد استحقاق الدين هو اليوم. المبلغ المستحق اليوم 1 وإجمالي الرصيد القائم 1 ر. ي."
            val enqueued = SmsOutboxRepository.enqueue(
                database,
                phone,
                body,
                eventId = eventId,
                businessEntityId = "party:$customerId",
                dedupeKey = "load-debt-reminder-$index"
            )!!
            queued.add(
                SmsOutboxRepository.OutboxMessage(
                    messageId = enqueued.messageId,
                    recipient = phone,
                    body = body,
                    partsCount = enqueued.partsCount,
                    attemptCount = 0,
                    subscriptionId = null,
                    eventId = eventId,
                    businessEntityId = "party:$customerId"
                )
            )
        }

        assertEquals(
            "All 100 synthetic reminders must be valid before the concurrent update begins",
            queued.map { it.messageId }.toSet(),
            DebtDueDateRepository.validateDebtReminderBatch(database, queued)
        )

        val transportStarted = CountDownLatch(1)
        val updateCommitted = CountDownLatch(1)
        val updater = Executors.newSingleThreadExecutor()
        val sentMessageIds = java.util.Collections.synchronizedList(mutableListOf<String>())
        val transport = object : SmsTransportSender {
            override fun send(job: SmsOutboxRepository.OutboxMessage) {
                sentMessageIds += job.messageId
                if (job.messageId == probe.messageId) {
                    transportStarted.countDown()
                    check(updateCommitted.await(10, TimeUnit.SECONDS)) {
                        "Concurrent payment/contact update did not finish while transport was active"
                    }
                }
            }
        }

        val updateFuture = updater.submit {
            check(transportStarted.await(10, TimeUnit.SECONDS)) {
                "The fake transport did not start"
            }
            val db = database.writableDatabase
            db.beginTransaction()
            try {
                debtRows.forEachIndexed { index, (customerId, saleId) ->
                    db.update(
                        "sales_transactions",
                        ContentValues().apply {
                            put("paid_amount", 1.0)
                            put("remaining_amount", 0.0)
                            put("payment_status", "paid")
                        },
                        "id = ?",
                        arrayOf(saleId.toString())
                    )
                    db.update(
                        "party_contacts",
                        ContentValues().apply { put("phone", "967770${index.toString().padStart(6, '0')}") },
                        "party_id = ?",
                        arrayOf(customerId.toString())
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
                updateCommitted.countDown()
            }
        }

        try {
            repeat(6) {
                val worker = TestListenableWorkerBuilder.from(context, SmsOutboxWorker::class.java)
                    .setWorkerFactory(object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters
                        ): ListenableWorker? =
                            if (workerClassName == SmsOutboxWorker::class.java.name) {
                                SmsOutboxWorker(appContext, workerParameters, transport)
                            } else null
                    })
                    .build()
                worker.doWork()
            }
            updateFuture.get(10, TimeUnit.SECONDS)
        } finally {
            updater.shutdownNow()
        }

        assertEquals("Only the non-debt probe may be submitted before the concurrent update", listOf(probe.messageId), sentMessageIds)
        assertEquals(1, countStatus("SENDING"))
        assertEquals(100, countStatus("CANCELLED"))
        assertEquals("No queued debt reminder may remain after all six 20-message batches", 0, countStatus("QUEUED"))
    }

    private fun status(id: String): String =
        database.readableDatabase.rawQuery(
            "SELECT status FROM sms_outbox WHERE message_id = ?", arrayOf(id)
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }

    private fun legacyStatus(id: String): String =
        database.readableDatabase.rawQuery(
            "SELECT status FROM sms_messages WHERE uuid = ?", arrayOf(id)
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }

    private fun countStatus(value: String): Int =
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM sms_outbox WHERE status = ?", arrayOf(value)
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }

    private fun insertDebtFixture(): Pair<Long, Long> {
        val db = database.writableDatabase
        db.execSQL("PRAGMA foreign_keys = OFF")
        val suffix = System.nanoTime().toString()
        val customerId = db.insertOrThrow("parties", null, ContentValues().apply {
            put("uuid", "sms-load-party-$suffix")
            put("party_code", "SMS-LOAD-$suffix")
            put("party_type_id", 1)
            put("station_id", 1)
            put("commercial_name_ar", "SMS load test $suffix")
            put("payment_terms", "صافي 30 يوم")
            put("payment_terms_days", 30)
            put("payment_terms_source", "explicit_text")
            put("is_active", 1)
            put("is_deleted", 0)
        })
        val saleColumns = db.rawQuery("PRAGMA table_info(sales_transactions)", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }
        val saleValues = ContentValues().apply {
            put("uuid", "sms-load-sale-$suffix")
            put("sale_code", "SMS-LOAD-SALE-$suffix")
            put("station_id", 1)
            put("shift_id", 1)
            put("customer_party_id", customerId)
            put("subtotal", 1.0)
            put("gross_amount", 1.0)
            put("net_amount", 1.0)
            put("payment_method", "credit")
            put("payment_status", "pending")
            put("paid_amount", 0.0)
            put("remaining_amount", 1.0)
            put("status", "completed")
            put("cashier_id", 1)
            put("is_deleted", 0)
            listOf("business_day", "transaction_date", "sale_date")
                .firstOrNull { it in saleColumns }
                ?.let { put(it, BusinessDay.currentDate()) }
            if ("payment_terms_at_sale" in saleColumns) put("payment_terms_at_sale", "صافي 30 يوم")
            if ("payment_terms_source_at_sale" in saleColumns) {
                put("payment_terms_source_at_sale", "explicit_text")
            }
        }
        val saleId = db.insertOrThrow("sales_transactions", null, saleValues)
        return customerId to saleId
    }

    private class RecordingTransport {
        val sentMessageIds = mutableListOf<String>()
        val sentJobs = mutableListOf<SmsOutboxRepository.OutboxMessage>()

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
                        sentJobs += job
                    }
                })
            }
        }
    }
}
