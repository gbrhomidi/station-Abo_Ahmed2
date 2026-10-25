package com.aistudio.dieselstationsms.kxmpzq.sms

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
