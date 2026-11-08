package com.aistudio.dieselstationsms.kxmpzq.sms

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.aistudio.dieselstationsms.kxmpzq.BusinessDay
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.time.LocalDate
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
    fun actualDebtReminderWorkerQueuesOnlyInsideTheConfiguredPreDueWindow() = runBlocking {
        // Initialize WorkManager's in-memory test scheduler; the production worker still
        // executes its SQLite reads, transaction, reminder persistence and outbox enqueue.
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.SEND_SMS)
        val db = database.writableDatabase
        val today = LocalDate.parse(BusinessDay.currentDate())
        val (inWindowCustomer, inWindowSale) = insertDebtFixture()
        val (outsideCustomer, outsideSale) = insertDebtFixture()
        val phoneInWindow = "967771234567"
        val phoneOutside = "967771765432"
        val partyColumns = db.rawQuery("PRAGMA table_info(parties)", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }
        val legacyPhoneColumn = listOf("phone", "phone_number", "mobile", "phone1")
            .firstOrNull { it in partyColumns }
        listOf(inWindowCustomer to phoneInWindow, outsideCustomer to phoneOutside).forEachIndexed { index, pair ->
            legacyPhoneColumn?.let { column ->
                db.update("parties", ContentValues().apply { put(column, pair.second) },
                    "id=? AND station_id=1", arrayOf(pair.first.toString()))
            }
            db.insertOrThrow("party_contacts", null, ContentValues().apply {
                put("uuid", "worker-pre-due-contact-${System.nanoTime()}-$index")
                put("party_id", pair.first)
                put("contact_name", "Pre-due worker test $index")
                put("phone", pair.second)
                put("is_primary", 1)
                put("is_active", 1)
                put("is_deleted", 0)
            })
        }
        val dueDateColumns = db.rawQuery("PRAGMA table_info(sales_transactions)", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }
        val dueWindowUpdate = ContentValues().apply {
            put("due_date", today.plusDays(2).toString())
            put("due_date_source", "sale_creation")
            if ("payment_terms_source_at_sale" in dueDateColumns) put("payment_terms_source_at_sale", "explicit_text")
            if ("payment_terms_at_sale" in dueDateColumns) put("payment_terms_at_sale", "صافي 30 يوم")
        }
        assertEquals(1, db.update("sales_transactions", dueWindowUpdate, "id=?", arrayOf(inWindowSale.toString())))
        val outsideUpdate = ContentValues().apply {
            put("due_date", today.plusDays(4).toString())
            put("due_date_source", "sale_creation")
            if ("payment_terms_source_at_sale" in dueDateColumns) put("payment_terms_source_at_sale", "explicit_text")
            if ("payment_terms_at_sale" in dueDateColumns) put("payment_terms_at_sale", "صافي 30 يوم")
        }
        assertEquals(1, db.update("sales_transactions", outsideUpdate, "id=?", arrayOf(outsideSale.toString())))

        val userId = db.rawQuery("SELECT id FROM users WHERE is_deleted=0 ORDER BY id LIMIT 1", null)
            .use { cursor -> assertTrue("A valid authenticated user is required by the reminder worker", cursor.moveToFirst()); cursor.getLong(0) }
        database.setSetting("sms_send_enabled", "1")
        database.setSetting("debt_reminder_days_before_due", "3")
        // Scheduling twice must update the same durable station work item, not create
        // duplicate periodic workers; changing the authenticated user updates its input.
        DebtDueReminderWorker.schedule(context, 1, userId)
        DebtDueReminderWorker.schedule(context, 1, userId + 1L)
        val scheduled = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("debt-due-sms-reminders-station-1")
            .get(10, TimeUnit.SECONDS)
        assertEquals(1, scheduled.size)
        assertEquals(userId + 1L, scheduled.single().inputData.getLong("debt_reminder_user_id", 0L))

        val requestData = Data.Builder()
            .putInt("debt_reminder_station_id", 1)
            .putLong("debt_reminder_user_id", userId)
            .build()
        val worker = TestListenableWorkerBuilder.from(context, DebtDueReminderWorker::class.java)
            .setInputData(requestData)
            .build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, db.rawQuery(
            "SELECT COUNT(*) FROM sms_reminders WHERE transaction_id=? AND days_before_due=2 AND status='pending'",
            arrayOf(inWindowSale.toString())
        ).use { assertTrue(it.moveToFirst()); it.getInt(0) })
        assertEquals(0, db.rawQuery(
            "SELECT COUNT(*) FROM sms_reminders WHERE transaction_id=?",
            arrayOf(outsideSale.toString())
        ).use { assertTrue(it.moveToFirst()); it.getInt(0) })
        val queued = db.rawQuery(
            """SELECT o.status,o.body,o.event_id FROM sms_outbox o
               WHERE o.business_entity_id=? ORDER BY o.queued_at DESC LIMIT 1""",
            arrayOf("party:$inWindowCustomer")
        ).use {
            assertTrue("The in-window customer must have one durable outbox row", it.moveToFirst())
            Triple(it.getString(0), it.getString(1), it.getString(2))
        }
        assertEquals("QUEUED", queued.first)
        assertTrue(queued.second.contains("يتبقى 2 يوم على موعد استحقاق الدين"))
        assertTrue(queued.third.startsWith("debt-reminder:1:$inWindowCustomer:"))
        assertEquals(0, db.rawQuery(
            "SELECT COUNT(*) FROM sms_outbox WHERE business_entity_id=?",
            arrayOf("party:$outsideCustomer")
        ).use { assertTrue(it.moveToFirst()); it.getInt(0) })

        val queuedMessageId = db.rawQuery(
            "SELECT message_id FROM sms_outbox WHERE business_entity_id=? ORDER BY queued_at DESC LIMIT 1",
            arrayOf("party:$inWindowCustomer")
        ).use { assertTrue(it.moveToFirst()); it.getString(0) }
        // A payment, cancellation or other invoice-state change after queueing must be
        // revalidated by the actual outbox worker immediately before carrier transport.
        assertEquals(1, db.update("sales_transactions", ContentValues().apply {
            put("status", "cancelled")
        }, "id=? AND station_id=1", arrayOf(inWindowSale.toString())))
        val transport = RecordingTransport()
        val outboxWorker = TestListenableWorkerBuilder.from(context, SmsOutboxWorker::class.java)
            .setWorkerFactory(transport.factory())
            .build()
        val dispatchResult = outboxWorker.doWork()
        assertEquals(ListenableWorker.Result.success(), dispatchResult)
        assertTrue("The cancelled invoice reminder must never reach the carrier transport",
            transport.sentMessageIds.isEmpty())
        assertEquals("CANCELLED", status(queuedMessageId))
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

        val ready = CountDownLatch(16)
        val start = CountDownLatch(1)
        val updatePool = Executors.newFixedThreadPool(8)
        val sendPool = Executors.newFixedThreadPool(8)
        val updateFutures = mutableListOf<java.util.concurrent.Future<*>>()
        val sendFutures = mutableListOf<java.util.concurrent.Future<*>>()
        val committedUpdates = java.util.concurrent.atomic.AtomicInteger()
        val simulatedSentIds = java.util.Collections.synchronizedList(mutableListOf<String>())
        try {
            queued.forEachIndexed { index, message ->
                val (customerId, saleId) = debtRows[index]
                val thisUpdateCommitted = CountDownLatch(1)
                updateFutures += updatePool.submit {
                    ready.countDown()
                    check(start.await(30, TimeUnit.SECONDS)) { "Concurrent load start gate timed out" }
                    val db = database.writableDatabase
                    db.beginTransaction()
                    try {
                        check(db.update(
                            "sales_transactions",
                            ContentValues().apply {
                                put("paid_amount", 1.0)
                                put("remaining_amount", 0.0)
                                put("payment_status", "paid")
                            },
                            "id = ?",
                            arrayOf(saleId.toString())
                        ) == 1)
                        check(db.update(
                            "party_contacts",
                            ContentValues().apply {
                                put("phone", "967770${index.toString().padStart(6, '0')}")
                            },
                            "party_id = ?",
                            arrayOf(customerId.toString())
                        ) == 1)
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                        thisUpdateCommitted.countDown()
                    }
                    committedUpdates.incrementAndGet()
                }
                sendFutures += sendPool.submit {
                    ready.countDown()
                    check(start.await(30, TimeUnit.SECONDS)) { "Concurrent load start gate timed out" }
                    check(thisUpdateCommitted.await(30, TimeUnit.SECONDS)) {
                        "The matching payment/contact update did not commit before final validation"
                    }
                    // This is the same final eligibility gate the worker calls immediately before
                    // transport. The fake transport records only messages that pass that gate.
                    if (DebtDueDateRepository.isCurrentDebtReminder(
                            database,
                            message.eventId,
                            message.businessEntityId,
                            message.recipient,
                            message.body
                        )
                    ) {
                        simulatedSentIds += message.messageId
                    }
                }
            }
            check(ready.await(30, TimeUnit.SECONDS)) { "Not all concurrent workers reached the start gate" }
            start.countDown()
            updateFutures.forEach { it.get(60, TimeUnit.SECONDS) }
            sendFutures.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            updatePool.shutdownNow()
            sendPool.shutdownNow()
        }

        assertEquals("All 100 payment updates must commit", 100, committedUpdates.get())
        assertTrue("No paid debt reminder may pass final validation to the fake transport", simulatedSentIds.isEmpty())
    }

    private fun status(id: String): String =
        database.readableDatabase.rawQuery(
            "SELECT status FROM sms_outbox WHERE message_id = ?", arrayOf(id)
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }

    private fun legacyStatus(id: String): String =
        database.readableDatabase.rawQuery(
            "SELECT status FROM sms_messages WHERE uuid = ?", arrayOf(id)
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }

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
