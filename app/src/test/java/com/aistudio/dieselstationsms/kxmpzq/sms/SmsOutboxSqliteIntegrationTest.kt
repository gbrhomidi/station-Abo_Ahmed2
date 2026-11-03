package com.aistudio.dieselstationsms.kxmpzq.sms

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.content.Context
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
            persist = {
                database.writableDatabase.insertOrThrow(
                    "test_financial_sms_records",
                    null,
                    ContentValues().apply { put("amount", 100.0) }
                )
            },
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
                persist = {
                    database.writableDatabase.insertOrThrow(
                        "test_financial_sms_records",
                        null,
                        ContentValues().apply { put("amount", 250.0) }
                    )
                },
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
