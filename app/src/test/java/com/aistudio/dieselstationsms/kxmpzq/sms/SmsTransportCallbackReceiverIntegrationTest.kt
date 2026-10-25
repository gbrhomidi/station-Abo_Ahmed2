package com.aistudio.dieselstationsms.kxmpzq.sms

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the production callback-processing path with synthetic modem result codes and
 * verifies that SQLite outbox and legacy SMS log remain synchronized. No carrier SMS is sent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmsTransportCallbackReceiverIntegrationTest {
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
    fun actualReceiverPersistsSentAndDeliveredCallbacksAndDoesNotDowngradeDelivery() {
        val queued = SmsOutboxRepository.enqueue(
            database, "967771234567", "Receiver lifecycle", dedupeKey = "receiver-lifecycle"
        )!!
        val claimed = SmsOutboxRepository.claimNext(database)!!
        SmsOutboxRepository.prepareParts(database, claimed.messageId, claimed.partsCount)
        val receiver = SmsTransportCallbackReceiver()

        receiver.handleCallback(context, callbackIntent(SmsTransport.ACTION_SENT, queued.messageId), Activity.RESULT_OK)
        assertEquals("DELIVERY_PENDING", status(queued.messageId))
        assertEquals("sent", legacyStatus(queued.messageId))

        receiver.handleCallback(context, callbackIntent(SmsTransport.ACTION_DELIVERED, queued.messageId), Activity.RESULT_OK)
        assertEquals("DELIVERED", status(queued.messageId))
        assertEquals("delivered", legacyStatus(queued.messageId))

        // A delayed SENT callback must not downgrade an already confirmed delivery.
        receiver.handleCallback(context, callbackIntent(SmsTransport.ACTION_SENT, queued.messageId), Activity.RESULT_OK)
        assertEquals("DELIVERED", status(queued.messageId))
        assertEquals("delivered", legacyStatus(queued.messageId))
    }

    @Test
    fun actualReceiverPersistsCarrierFailureAndMakesMessageRetryable() {
        val queued = SmsOutboxRepository.enqueue(
            database, "967771234567", "Receiver failure", dedupeKey = "receiver-failure"
        )!!
        val claimed = SmsOutboxRepository.claimNext(database)!!
        SmsOutboxRepository.prepareParts(database, claimed.messageId, claimed.partsCount)
        val receiver = SmsTransportCallbackReceiver()

        receiver.handleCallback(
            context,
            callbackIntent(SmsTransport.ACTION_SENT, queued.messageId),
            SmsManager.RESULT_ERROR_NO_SERVICE
        )

        assertEquals("RETRY_PENDING", status(queued.messageId))
        assertEquals(SmsManager.RESULT_ERROR_NO_SERVICE.toString(), failureCode(queued.messageId))
        assertEquals("failed", legacyStatus(queued.messageId))
    }

    @Test
    fun actualReceiverIgnoresMalformedAndUnrelatedCallbacks() {
        val queued = SmsOutboxRepository.enqueue(
            database, "967771234567", "Receiver invalid callback", dedupeKey = "receiver-invalid"
        )!!
        val receiver = SmsTransportCallbackReceiver()

        receiver.handleCallback(context, Intent("unrelated.action").apply {
            putExtra(SmsTransport.EXTRA_MESSAGE_ID, queued.messageId)
            putExtra(SmsTransport.EXTRA_PART_INDEX, 0)
        }, Activity.RESULT_CANCELED)
        receiver.handleCallback(context, Intent(SmsTransport.ACTION_SENT).apply {
            putExtra(SmsTransport.EXTRA_PART_INDEX, 0)
        }, Activity.RESULT_OK)
        receiver.handleCallback(context, Intent(SmsTransport.ACTION_SENT).apply {
            putExtra(SmsTransport.EXTRA_MESSAGE_ID, queued.messageId)
            putExtra(SmsTransport.EXTRA_PART_INDEX, -1)
        }, Activity.RESULT_OK)

        assertEquals("QUEUED", status(queued.messageId))
        assertEquals("queued", legacyStatus(queued.messageId))
    }

    private fun callbackIntent(action: String, messageId: String) =
        Intent(action).apply {
            putExtra(SmsTransport.EXTRA_MESSAGE_ID, messageId)
            putExtra(SmsTransport.EXTRA_PART_INDEX, 0)
        }

    private fun status(id: String): String =
        database.readableDatabase.rawQuery(
            "SELECT status FROM sms_outbox WHERE message_id = ?", arrayOf(id)
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }

    private fun legacyStatus(id: String): String =
        database.readableDatabase.rawQuery(
            "SELECT status FROM sms_messages WHERE uuid = ?", arrayOf(id)
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }

    private fun failureCode(id: String): String =
        database.readableDatabase.rawQuery(
            "SELECT failure_code FROM sms_outbox WHERE message_id = ?", arrayOf(id)
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }
}