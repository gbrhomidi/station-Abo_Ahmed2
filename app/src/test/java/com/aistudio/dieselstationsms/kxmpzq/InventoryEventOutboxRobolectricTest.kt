package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InventoryEventOutboxRobolectricTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper
    private lateinit var db: android.database.sqlite.SQLiteDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        helper = DatabaseHelper.getInstance(context)
        db = helper.writableDatabase
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    private fun event(consumer: String = "stock-levels", key: String = "event-key") =
        JSONObject().put("event_type", "inventory_test")
            .put("consumer", consumer).put("idempotency_key", key)
            .put("payload", JSONObject().put("source", "robolectric"))

    private fun scalar(sql: String, args: Array<String>): Long =
        db.rawQuery(sql, args).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }

    @Test
    fun idempotencyIsScopedByStationAndConsumer() {
        val key = "same-business-event"
        val first = helper.publishEvent(event("stock-levels", key), 1)
        val replay = helper.publishEvent(event("stock-levels", key), 1)
        val anotherConsumer = helper.publishEvent(event("audit-log", key), 1)
        val anotherStation = helper.publishEvent(event("stock-levels", key), 2)
        assertEquals(first, replay)
        assertTrue(first != anotherConsumer)
        assertTrue(first != anotherStation)
        assertEquals(3L, scalar("SELECT COUNT(*) FROM events_outbox WHERE idempotency_key=?", arrayOf(key)))
    }

    @Test
    fun claimLeasesEventsAndAcknowledgementIsTerminal() {
        val id = helper.publishEvent(event(), 1)
        val claimed = helper.claimPendingEvents(JSONObject().put("consumer", "stock-levels"), 1)
        assertEquals(1, claimed.length())
        assertEquals(id, claimed.getJSONObject(0).getLong("event_id"))
        assertEquals("PROCESSING", claimed.getJSONObject(0).getString("status"))
        assertEquals(1, claimed.getJSONObject(0).getInt("attempts"))
        val leaseToken = claimed.getJSONObject(0).getString("lease_token")
        assertTrue(leaseToken.isNotBlank())
        assertTrue(helper.acknowledgeEvent(id, 1, "stock-levels", leaseToken))
        assertFalse(helper.acknowledgeEvent(id, 1, "stock-levels", leaseToken))
        assertEquals(0, helper.claimPendingEvents(JSONObject().put("consumer", "stock-levels"), 1).length())
        assertEquals("DELIVERED", db.rawQuery("SELECT status FROM events_outbox WHERE event_id=?", arrayOf(id.toString()))
            .use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) })
    }

    @Test
    fun expiredProcessingLeaseIsRecoveredAfterWorkerCrash() {
        val id = helper.publishEvent(event(), 1)
        assertEquals(1, helper.claimPendingEvents(JSONObject(), 1).length())
        db.update("events_outbox", ContentValues().apply { put("lease_until", "2000-01-01 00:00:00") },
            "event_id=?", arrayOf(id.toString()))
        val recovered = helper.claimPendingEvents(JSONObject(), 1)
        assertEquals(1, recovered.length())
        assertEquals(id, recovered.getJSONObject(0).getLong("event_id"))
        assertEquals("PROCESSING", recovered.getJSONObject(0).getString("status"))
        assertEquals(2, recovered.getJSONObject(0).getInt("attempts"))
    }

    @Test
    fun failedDeliveryBacksOffThenStopsAtAttemptLimit() {
        val id = helper.publishEvent(event(), 1)
        val firstClaim = helper.claimPendingEvents(JSONObject(), 1)
        assertEquals(1, firstClaim.length())
        val firstToken = firstClaim.getJSONObject(0).getString("lease_token")
        assertTrue(helper.failEvent(id, 1, "stock-levels", firstToken, "temporary failure", maxAttempts = 2))
        assertEquals("PENDING", db.rawQuery("SELECT status FROM events_outbox WHERE event_id=?", arrayOf(id.toString()))
            .use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) })
        db.update("events_outbox", ContentValues().apply { put("available_at", "2000-01-01 00:00:00") },
            "event_id=?", arrayOf(id.toString()))
        val secondClaim = helper.claimPendingEvents(JSONObject(), 1)
        assertEquals(1, secondClaim.length())
        assertEquals(2, secondClaim.getJSONObject(0).getInt("attempts"))
        val secondToken = secondClaim.getJSONObject(0).getString("lease_token")
        assertTrue(helper.failEvent(id, 1, "stock-levels", secondToken, "permanent failure", maxAttempts = 2))
        assertEquals("FAILED", db.rawQuery("SELECT status FROM events_outbox WHERE event_id=?", arrayOf(id.toString()))
            .use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) })
    }

    @Test
    fun staleLeaseOwnerCannotAcknowledgeOrFailAfterReclaim() {
        val id = helper.publishEvent(event(), 1)
        val firstClaim = helper.claimPendingEvents(JSONObject(), 1).getJSONObject(0)
        val staleToken = firstClaim.getString("lease_token")
        assertTrue(staleToken.isNotBlank())

        db.update("events_outbox", ContentValues().apply { put("lease_until", "2000-01-01 00:00:00") },
            "event_id=?", arrayOf(id.toString()))
        val reclaimed = helper.claimPendingEvents(JSONObject(), 1).getJSONObject(0)
        val currentToken = reclaimed.getString("lease_token")
        assertTrue(currentToken.isNotBlank())
        assertFalse(staleToken == currentToken)

        assertFalse(helper.acknowledgeEvent(id, 1, "stock-levels", staleToken))
        assertFalse(helper.failEvent(id, 1, "stock-levels", staleToken, "late worker failure"))
        assertTrue(helper.acknowledgeEvent(id, 1, "stock-levels", currentToken))
        assertEquals("DELIVERED", db.rawQuery("SELECT status FROM events_outbox WHERE event_id=?",
            arrayOf(id.toString())).use { c -> check(c.moveToFirst()); c.getString(0) })
    }

    @Test
    fun unknownInventoryEventIsRejectedWithoutAcknowledgement() {
        val unknown = JSONObject().put("event_type", "unregistered_inventory_event")
            .put("consumer", "stock-levels").put("idempotency_key", "unknown-event-key")
            .put("payload", JSONObject().put("sale_id", 99))
        val id = helper.publishEvent(unknown, 1)
        assertEquals(0, helper.processPendingInventoryOutbox(1, 50))
        assertEquals("PENDING", db.rawQuery(
            "SELECT status FROM events_outbox WHERE event_id=?", arrayOf(id.toString())
        ).use { c -> check(c.moveToFirst()); c.getString(0) })
        assertEquals(1L, scalar("SELECT attempts FROM events_outbox WHERE event_id=?", arrayOf(id.toString())))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM events_outbox WHERE event_id=? AND status='DELIVERED'", arrayOf(id.toString())))
    }

    @Test
    fun claimsCannotCrossStationOrConsumerBoundaries() {
        val stockEvent = helper.publishEvent(event("stock-levels", "stock-key"), 1)
        val auditEvent = helper.publishEvent(event("audit-log", "audit-key"), 1)
        helper.publishEvent(event("stock-levels", "other-station-key"), 2)
        val stationOneStock = helper.claimPendingEvents(JSONObject().put("consumer", "stock-levels"), 1)
        assertEquals(1, stationOneStock.length())
        assertEquals(stockEvent, stationOneStock.getJSONObject(0).getLong("event_id"))
        assertEquals(0, helper.claimPendingEvents(JSONObject().put("consumer", "audit-log"), 2).length())
        val auditClaim = helper.claimPendingEvents(JSONObject().put("consumer", "audit-log"), 1)
        assertEquals(1, auditClaim.length())
        assertEquals(auditEvent, auditClaim.getJSONObject(0).getLong("event_id"))
    }
}
