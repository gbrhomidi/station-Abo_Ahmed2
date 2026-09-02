package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aistudio.dieselstationsms.kxmpzq.sms.DriverAssignmentEngine
import com.aistudio.dieselstationsms.kxmpzq.sms.FuelOrderRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DriverAssignmentPolicyRobolectricTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance(); context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        helper = DatabaseHelper.getInstance(context); helper.writableDatabase
    }

    @After fun tearDown() { DatabaseHelper.closeInstance(); context.deleteDatabase(DatabaseHelper.DATABASE_NAME) }

    private fun party(stationId: Long, name: String): Long {
        val db = helper.writableDatabase
        val type = db.rawQuery("SELECT id FROM party_types WHERE is_deleted = 0 ORDER BY id LIMIT 1", null).use { check(it.moveToFirst()); it.getLong(0) }
        return db.insertOrThrow("parties", null, ContentValues().apply { put("uuid", UUID.randomUUID().toString()); put("party_code", "POL-${UUID.randomUUID()}"); put("party_type_id", type); put("station_id", stationId); put("commercial_name", name); put("commercial_name_ar", name); put("is_active", 1); put("is_deleted", 0) })
    }

    private fun driver(stationId: Long, ownerStationId: Long, phone: String, suffix: String): Long {
        val db = helper.writableDatabase
        val owner = party(ownerStationId, "مالك $suffix")
        val vehicleId = db.insertOrThrow("vehicles", null, ContentValues().apply { put("uuid", UUID.randomUUID().toString()); put("vehicle_code", "POL-VEH-$suffix"); put("party_id", owner); put("plate_number", "POL-$suffix"); put("status", "active"); put("is_deleted", 0) })
        return db.insertOrThrow("drivers", null, ContentValues().apply { put("uuid", UUID.randomUUID().toString()); put("driver_code", "POL-DRV-$suffix"); put("station_id", stationId); put("vehicle_id", vehicleId); put("full_name", "سائق $suffix"); put("full_name_ar", "سائق $suffix"); put("phone", phone); put("status", "active"); put("is_deleted", 0); put("updated_at", System.currentTimeMillis()) })
    }

    private fun order(phone: String, suffix: String): String {
        val customer = party(1, "عميل $suffix")
        val fuel = helper.writableDatabase.rawQuery("SELECT id FROM fuel_types WHERE is_deleted = 0 ORDER BY id LIMIT 1", null).use { check(it.moveToFirst()); it.getLong(0) }
        val repo = FuelOrderRepository(helper)
        val created = repo.createDraft(customer, phone, fuel, 100.0, "LITER", 100.0, 5.0, "PREPAID", "موقع الاختبار", System.currentTimeMillis() + 3_600_000, "POL-ORDER-$suffix")
        repo.createImmutableQuote(created.orderId, "POL-PRICE")
        repo.reserve(created.orderId); repo.transition(created.orderId, com.aistudio.dieselstationsms.kxmpzq.sms.FuelOrderStatus.AWAITING_PAYMENT); repo.transition(created.orderId, com.aistudio.dieselstationsms.kxmpzq.sms.FuelOrderStatus.PAYMENT_VERIFIED); repo.transition(created.orderId, com.aistudio.dieselstationsms.kxmpzq.sms.FuelOrderStatus.AWAITING_DELIVERY)
        return created.orderId
    }

    @Test fun assignmentRejectsVehicleOwnedByAnotherStation() = runBlocking {
        val driverId = driver(1, 2, "967771111111", "FOREIGN")
        val orderId = order("967772222222", "FOREIGN")
        DriverAssignmentEngine(context, helper).assign(orderId)
        assertEquals(0, helper.writableDatabase.rawQuery("SELECT COUNT(*) FROM sms_delivery_tasks WHERE order_id = ? AND driver_id = ?", arrayOf(orderId, driverId.toString())).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun assignmentSkipsDriverWithActiveTask() = runBlocking {
        val driverId = driver(1, 1, "967773333333", "BUSY")
        val busyOrder = order("967774444444", "BUSY-OLD")
        helper.writableDatabase.insertOrThrow("sms_delivery_tasks", null, ContentValues().apply { put("delivery_id", "DT-BUSY"); put("order_id", busyOrder); put("driver_id", driverId); put("station_id", 1); put("location", "موقع قائم"); put("status", "ACCEPTED"); put("created_at", System.currentTimeMillis()); put("updated_at", System.currentTimeMillis()) })
        val orderId = order("967775555555", "BUSY-NEW")
        DriverAssignmentEngine(context, helper).assign(orderId)
        assertEquals(0, helper.writableDatabase.rawQuery("SELECT COUNT(*) FROM sms_delivery_tasks WHERE order_id = ?", arrayOf(orderId)).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun driverAcceptanceIsRecordedAndRefusalDoesNotCancelTask() = runBlocking {
        val driverId = driver(1, 1, "967776666666", "MANDATORY")
        val orderId = order("967777777777", "MANDATORY")
        DriverAssignmentEngine(context, helper).assign(orderId)
        val task = helper.writableDatabase.rawQuery("SELECT delivery_id FROM sms_delivery_tasks WHERE order_id = ?", arrayOf(orderId)).use { check(it.moveToFirst()); it.getString(0) }
        assertTrue(DriverAssignmentEngine(context, helper).handleDriverReply("967776666666", "2"))
        helper.writableDatabase.rawQuery("SELECT status, failure_reason FROM sms_delivery_tasks WHERE delivery_id = ?", arrayOf(task)).use { c -> assertTrue(c.moveToFirst()); assertEquals("ASSIGNED", c.getString(0)); assertTrue(c.getString(1).startsWith("DRIVER_REFUSAL_PENALTY:")) }
        assertEquals(1, helper.writableDatabase.rawQuery("SELECT COUNT(*) FROM driver_penalties WHERE driver_id = ? AND task_code = ?", arrayOf(driverId.toString(), task)).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(1, helper.writableDatabase.rawQuery("SELECT COUNT(*) FROM sms_business_events WHERE aggregate_id = ? AND event_type = 'DRIVER_REFUSAL_RECORDED'", arrayOf(orderId)).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun invalidDriverReplyCannotChangeAssignedTask() = runBlocking {
        driver(1, 1, "967778888888", "INVALID")
        val orderId = order("967779999999", "INVALID")
        DriverAssignmentEngine(context, helper).assign(orderId)
        assertTrue(!DriverAssignmentEngine(context, helper).handleDriverReply("967778888888", "ربما"))
        assertEquals("ASSIGNED", helper.writableDatabase.rawQuery("SELECT status FROM sms_delivery_tasks WHERE order_id = ?", arrayOf(orderId)).use { it.moveToFirst(); it.getString(0) })
    }
}
