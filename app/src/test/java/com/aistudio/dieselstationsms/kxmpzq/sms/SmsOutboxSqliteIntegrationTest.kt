package com.aistudio.dieselstationsms.kxmpzq.sms

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Integration checks use the production DatabaseHelper/SQLite schema and the real outbox
 * repository. Only the external carrier transport is replaced, so no SMS is sent to a real SIM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmsOutboxSqliteIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: DatabaseHelper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        database = DatabaseHelper.getInstance(context)
        database.writableDatabase.delete("sms_outbox_parts", null, null)
        database.writableDatabase.delete("sms_outbox", null, null)
        database.writableDatabase.delete("sms_messages", null, null)
        database.writableDatabase.delete("sms_outbound_dedupe", null, null)
        database.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fail_sms_outbox_insert")
        database.writableDatabase.execSQL(
            "CREATE TABLE IF NOT EXISTS test_financial_sms_records (id INTEGER PRIMARY KEY AUTOINCREMENT, amount REAL NOT NULL)"
        )
        database.writableDatabase.delete("test_financial_sms_records", null, null)
        database.setSetting("sms_send_enabled", "1")
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
    }

    @Test
    fun enqueueIsDurableAndDuplicateDedupeKeyDoesNotCreateSecondMessage() {
        val first = SmsOutboxRepository.enqueue(
            database, "967771234567", "Test body", eventId = "evt-1", dedupeKey = "integration-dedupe"
        )
        val duplicate = SmsOutboxRepository.enqueue(
            database, "967771234567", "Test body", eventId = "evt-1", dedupeKey = "integration-dedupe"
        )

        assertNotNull(first)
        assertNotNull(duplicate)
        assertEquals(first!!.messageId, duplicate!!.messageId)
        assertEquals(1, count("sms_outbox"))
        assertEquals("QUEUED", status(first.messageId))
        assertEquals(1, count("sms_messages"))
    }

    @Test
    fun repositoryClaimAndReplaceableTransportFollowSentThenDeliveredLifecycle() {
        val queued = SmsOutboxRepository.enqueue(
            database, "967771234567", "Integration lifecycle", dedupeKey = "lifecycle-dedupe"
        )!!
        val fakeTransport = RecordingTransport()

        val claimed = SmsOutboxRepository.claimNext(database)
        assertNotNull(claimed)
        assertEquals(queued.messageId, claimed!!.messageId)
        SmsOutboxRepository.prepareParts(database, claimed.messageId, claimed.partsCount)
        fakeTransport.send(claimed)

        assertEquals(listOf(claimed.messageId), fakeTransport.sentMessageIds)
        assertEquals("SENDING", status(claimed.messageId))

        SmsOutboxRepository.recordPartResult(
            database, claimed.messageId, 0, delivered = false, success = true, resultCode = "RESULT_OK"
        )
        assertEquals("DELIVERY_PENDING", status(claimed.messageId))

        SmsOutboxRepository.recordPartResult(
            database, claimed.messageId, 0, delivered = true, success = true, resultCode = "RESULT_OK"
        )
        assertEquals("DELIVERED", status(claimed.messageId))
        assertEquals("delivered", legacyStatus(claimed.messageId))
        assertNull(SmsOutboxRepository.claimNext(database))
    }

    @Test
    fun retryPendingMessageCanBeClaimedAgainOnlyAfterItsBackoff() {
        val queued = SmsOutboxRepository.enqueue(
            database, "967771234567", "Retry body", dedupeKey = "retry-dedupe"
        )!!
        val claimed = SmsOutboxRepository.claimNext(database)!!
        SmsOutboxRepository.markFailed(
            database, claimed.messageId, "TEMPORARY_TEST_FAILURE", "simulated carrier failure",
            retry = true, now = 1_000_000L
        )

        assertEquals("RETRY_PENDING", status(queued.messageId))
        assertNull(SmsOutboxRepository.claimNext(database, now = 1_059_999L))
        assertNotNull(SmsOutboxRepository.claimNext(database, now = 1_060_000L))
        assertTrue(SmsOutboxRepository.hasPending(database))
    }

    @Test
    fun financialRecordAndNotificationAreCommittedAtomically() {
        shadowOf(context.applicationContext as Application)
            .grantPermissions(Manifest.permission.SEND_SMS)
        database.setSetting("sms_send_enabled", "1")
        database.writableDatabase.execSQL(
            "CREATE TABLE IF NOT EXISTS test_financial_sms_records (id INTEGER PRIMARY KEY AUTOINCREMENT, amount REAL NOT NULL)"
        )

        val result = SmsOutboxAtomicTransaction.execute(
            db = database,
            context = context,
            persist = { insertFinancialRecordInsideNestedTransaction(100.0) },
            notificationFactory = { recordId ->
                SmsOutboxAtomicTransaction.NotificationDraft(
                    recipient = "967771234567",
                    body = "Payment verified",
                    dedupeKey = "atomic-financial-$recordId",
                    eventId = "payment:$recordId",
                    businessEntityId = "payment:$recordId"
                )
            }
        )

        assertNotNull(result.queuedMessageId)
        assertEquals(1, count("test_financial_sms_records"))
        assertEquals(1, count("sms_outbox"))
        assertEquals(1, count("sms_messages"))
        assertEquals(1, count("sms_outbound_dedupe"))
    }

    @Test
    fun missingSendSmsPermissionKeepsBusinessNotificationQueuedForLaterDelivery() {
        shadowOf(context.applicationContext as Application)
            .denyPermissions(Manifest.permission.SEND_SMS)
        assertEquals(
            android.content.pm.PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
        )
        database.setSetting("sms_send_enabled", "1")

        val result = SmsOutboxAtomicTransaction.execute(
            db = database,
            context = context,
            persist = { insertFinancialRecordInsideNestedTransaction(175.0) },
            notificationFactory = { recordId ->
                SmsOutboxAtomicTransaction.NotificationDraft(
                    recipient = "967771234567",
                    body = "Permission deferred notification",
                    dedupeKey = "permission-deferred-$recordId",
                    eventId = "payment:$recordId",
                    businessEntityId = "payment:$recordId"
                )
            }
        )

        assertNotNull(result.queuedMessageId)
        assertEquals(1, count("test_financial_sms_records"))
        assertEquals(1, count("sms_outbox"))
        assertEquals("QUEUED", status(result.queuedMessageId!!))
    }

    @Test
    fun disabledSmsSettingDoesNotQueueButDoesNotBlockTheFinancialRecord() {
        database.setSetting("sms_send_enabled", "0")

        val result = SmsOutboxAtomicTransaction.execute(
            db = database,
            context = context,
            persist = { insertFinancialRecordInsideNestedTransaction(80.0) },
            notificationFactory = { recordId ->
                SmsOutboxAtomicTransaction.NotificationDraft(
                    recipient = "967771234567",
                    body = "Should not be queued while SMS is disabled",
                    dedupeKey = "sms-disabled-$recordId"
                )
            }
        )

        assertNull(result.queuedMessageId)
        assertEquals(1, count("test_financial_sms_records"))
        assertEquals(0, count("sms_outbox"))
        assertEquals(0, count("sms_messages"))
    }

    @Test
    fun notificationPreparationFailureRollsBackTheFinancialRecord() {
        shadowOf(context.applicationContext as Application)
            .grantPermissions(Manifest.permission.SEND_SMS)
        database.setSetting("sms_send_enabled", "1")

        val failure = runCatching {
            SmsOutboxAtomicTransaction.execute(
                db = database,
                context = context,
                persist = { insertFinancialRecordInsideNestedTransaction(95.0) },
                notificationFactory = {
                    throw IllegalStateException("simulated contact lookup failure")
                }
            )
        }.exceptionOrNull()

        assertNotNull("Unexpected notification preparation errors must abort the atomic operation", failure)
        assertEquals(0, count("test_financial_sms_records"))
        assertEquals(0, count("sms_outbox"))
        assertEquals(0, count("sms_messages"))
        assertEquals(0, count("sms_outbound_dedupe"))
    }

    @Test
    fun outboxInsertFailureRollsBackTheFinancialRecord() {
        shadowOf(context.applicationContext as Application)
            .grantPermissions(Manifest.permission.SEND_SMS)
        database.setSetting("sms_send_enabled", "1")
        database.writableDatabase.execSQL(
            "CREATE TABLE IF NOT EXISTS test_financial_sms_records (id INTEGER PRIMARY KEY AUTOINCREMENT, amount REAL NOT NULL)"
        )
        database.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_sms_outbox_insert
            BEFORE INSERT ON sms_outbox
            BEGIN
                SELECT RAISE(ABORT, 'simulated outbox insert failure');
            END
            """.trimIndent()
        )

        val failure = runCatching {
            SmsOutboxAtomicTransaction.execute(
                db = database,
                context = context,
                persist = { insertFinancialRecordInsideNestedTransaction(250.0) },
                notificationFactory = { recordId ->
                    SmsOutboxAtomicTransaction.NotificationDraft(
                        recipient = "967771234567",
                        body = "Payment failure rollback",
                        dedupeKey = "atomic-rollback-$recordId",
                        eventId = "payment:$recordId",
                        businessEntityId = "payment:$recordId"
                    )
                }
            )
        }.exceptionOrNull()

        assertNotNull("The injected outbox failure must be raised", failure)
        assertEquals(0, count("test_financial_sms_records"))
        assertEquals(0, count("sms_outbox"))
        assertEquals(0, count("sms_messages"))
        assertEquals(0, count("sms_outbound_dedupe"))
    }

    @Test
    fun debtReminderAndOutboxAreCommittedInOneTransaction() {
        val (customerId, saleId) = insertDebtFixture()
        val eventId = "debt-reminder:$customerId:2026-11-06"
        val result = SmsOutboxAtomicTransaction.execute(
            db = database,
            context = context,
            persist = {
                database.recordQueuedDebtReminder(
                    customerId, saleId, "967771234567", "Due today", daysBeforeDue = 0
                )
            },
            notificationFactory = {
                SmsOutboxAtomicTransaction.NotificationDraft(
                    recipient = "967771234567",
                    body = "Due today",
                    eventId = eventId,
                    businessEntityId = "party:$customerId",
                    dedupeKey = "$eventId:967771234567"
                )
            }
        )

        assertNotNull(result.queuedMessageId)
        assertEquals(1, countWhere("sms_reminders", "transaction_id = ?", saleId.toString()))
        assertEquals(1, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM sms_logs l JOIN sms_reminders r ON r.id = l.reminder_id WHERE r.transaction_id = ?",
            arrayOf(saleId.toString())
        ).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(1, countWhere("sms_outbox", "event_id = ?", eventId))
    }

    @Test
    fun outboxFailureRollsBackDebtReminderAndLegacySmsLog() {
        val (customerId, saleId) = insertDebtFixture()
        database.writableDatabase.execSQL(
            "CREATE TRIGGER fail_sms_outbox_insert BEFORE INSERT ON sms_outbox BEGIN SELECT RAISE(ABORT, 'simulated outbox failure'); END"
        )

        val failure = runCatching {
            SmsOutboxAtomicTransaction.execute(
                db = database,
                context = context,
                persist = {
                    database.recordQueuedDebtReminder(
                        customerId, saleId, "967771234567", "Due today", daysBeforeDue = 0
                    )
                },
                notificationFactory = {
                    SmsOutboxAtomicTransaction.NotificationDraft(
                        recipient = "967771234567",
                        body = "Due today",
                        eventId = "debt-reminder-rollback:$saleId",
                        businessEntityId = "party:$customerId",
                        dedupeKey = "debt-reminder-rollback:$saleId"
                    )
                }
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(0, countWhere("sms_reminders", "transaction_id = ?", saleId.toString()))
        assertEquals(0, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM sms_logs l JOIN sms_reminders r ON r.id = l.reminder_id WHERE r.transaction_id = ?",
            arrayOf(saleId.toString())
        ).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(0, count("sms_outbox"))
        assertEquals(0, count("sms_messages"))
    }

    private fun insertDebtFixture(): Pair<Long, Long> {
        val db = database.writableDatabase
        db.execSQL("PRAGMA foreign_keys = OFF")
        val suffix = System.nanoTime().toString()
        val customerId = db.insertOrThrow("parties", null, ContentValues().apply {
            put("uuid", "sms-party-$suffix")
            put("party_code", "SMS-$suffix")
            put("party_type_id", 1)
            put("station_id", 1)
            put("commercial_name_ar", "SMS test $suffix")
            put("payment_terms", "صافي 30 يوم")
            put("payment_terms_days", 30)
            put("is_active", 1)
            put("is_deleted", 0)
        })
        val saleColumns = db.rawQuery("PRAGMA table_info(sales_transactions)", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
        }
        val saleValues = ContentValues().apply {
            put("uuid", "sms-sale-$suffix")
            put("sale_code", "SMS-SALE-$suffix")
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
            if ("business_day" in saleColumns) put("business_day", "2026-11-06")
            if ("transaction_date" in saleColumns) put("transaction_date", "2026-11-06")
            if ("sale_date" in saleColumns) put("sale_date", "2026-11-06")
        }
        val saleId = db.insertOrThrow("sales_transactions", null, saleValues)
        return customerId to saleId
    }

    private fun countWhere(table: String, where: String, value: String): Int =
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $table WHERE $where", arrayOf(value)
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }

    @Test
    fun staleOutboundReservationsAreReconciledAgainstTheDurableOutbox() {
        val now = System.currentTimeMillis()
        val queued = SmsOutboxRepository.enqueue(
            database,
            "967771234567",
            "Existing durable message",
            dedupeKey = "recovery-existing-outbox"
        )!!
        insertOutboundReservation("recovery-existing-outbox", now - 10 * 60 * 1000L)
        insertOutboundReservation("recovery-orphan", now - 10 * 60 * 1000L)
        insertOutboundReservation("recovery-fresh", now - 30 * 1000L)

        val result = SmsOutboxAtomicTransaction.recoverOutboundDedupeReservations(
            db = database,
            now = now,
            staleAfterMs = 5 * 60 * 1000L,
            retentionMs = 24 * 60 * 60 * 1000L
        )

        assertEquals(1, result.finalizedWithOutbox)
        assertEquals(1, result.releasedWithoutOutbox)
        assertEquals(0, result.expired)
        assertEquals("sent", outboundStatus("recovery-existing-outbox"))
        assertEquals("QUEUED", outboxStatusFor(queued.messageId))
        assertEquals(0, outboundReservationCount("recovery-orphan"))
        assertEquals("reserved", outboundStatus("recovery-fresh"))
    }

    /** Mirrors the nested transaction used by DatabaseHelper financial persistence methods. */
    private fun insertFinancialRecordInsideNestedTransaction(amount: Double): Long {
        val databaseHandle = database.writableDatabase
        databaseHandle.beginTransaction()
        try {
            val id = databaseHandle.insertOrThrow(
                "test_financial_sms_records",
                null,
                ContentValues().apply { put("amount", amount) }
            )
            databaseHandle.setTransactionSuccessful()
            return id
        } finally {
            databaseHandle.endTransaction()
        }
    }

    private fun insertOutboundReservation(key: String, reservedAt: Long) {
        database.writableDatabase.insertOrThrow(
            "sms_outbound_dedupe",
            null,
            ContentValues().apply {
                put("dedupe_key", key)
                put("phone", "967771234567")
                put("message_hash", "hash-$key")
                put("message_preview", "test")
                put("status", "reserved")
                put("reserved_at", reservedAt)
            }
        )
    }

    private fun outboundStatus(key: String): String? =
        database.readableDatabase.rawQuery(
            "SELECT status FROM sms_outbound_dedupe WHERE dedupe_key = ?",
            arrayOf(key)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun outboundReservationCount(key: String): Int =
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM sms_outbound_dedupe WHERE dedupe_key = ?",
            arrayOf(key)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    private fun outboxStatusFor(messageId: String): String? =
        database.readableDatabase.rawQuery(
            "SELECT status FROM sms_outbox WHERE message_id = ?",
            arrayOf(messageId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun status(id: String): String =
        database.readableDatabase.rawQuery(
            "SELECT status FROM sms_outbox WHERE message_id = ?", arrayOf(id)
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }

    private fun legacyStatus(id: String): String =
        database.readableDatabase.rawQuery(
            "SELECT status FROM sms_messages WHERE uuid = ?", arrayOf(id)
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }

    private fun count(table: String): Int =
        database.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null)
            .use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }

    private class RecordingTransport : SmsTransportSender {
        val sentMessageIds = mutableListOf<String>()
        override fun send(job: SmsOutboxRepository.OutboxMessage) {
            sentMessageIds += job.messageId
        }
    }
}
