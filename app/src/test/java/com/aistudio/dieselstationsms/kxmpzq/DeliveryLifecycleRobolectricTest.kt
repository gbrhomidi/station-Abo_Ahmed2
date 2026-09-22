package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeliveryLifecycleRobolectricTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        helper = DatabaseHelper.getInstance(context)
        helper.writableDatabase
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    @Test
    fun deliveryReferencesExistingSaleAndDuplicateSaveDoesNotCreateAnotherSaleOrDelivery() {
        val db = helper.writableDatabase
        val stationId = scalarLong(db, "SELECT id FROM stations ORDER BY id LIMIT 1")
        val userId = ensureTestUser(db, stationId)
        val partyTypeId = scalarLong(db, "SELECT id FROM party_types WHERE is_deleted = 0 ORDER BY id LIMIT 1")
        val fuelTypeId = scalarLong(db, "SELECT id FROM fuel_types WHERE is_deleted = 0 ORDER BY id LIMIT 1")
        val token = System.nanoTime().toString()

        val partyId = db.insertOrThrow("parties", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("party_code", "DEL-CUS-" + token)
            put("party_type_id", partyTypeId)
            put("station_id", stationId)
            put("commercial_name_ar", "عميل اختبار التوصيل")
            put("credit_limit", 100000.0)
            put("current_balance", 0.0)
            put("total_due", 0.0)
            put("is_active", 1)
            put("is_deleted", 0)
        })

        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val shiftId = db.insertOrThrow("shifts", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("shift_code", "DEL-SHIFT-" + token)
            put("station_id", stationId)
            put("shift_date", now.substring(0, 10))
            put("shift_type", "full_day")
            put("start_time", now)
            put("cashier_id", userId)
            put("status", "open")
            put("is_deleted", 0)
        })

        val saleId = db.insertOrThrow("sales_transactions", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("sale_code", "DEL-SALE-" + token)
            put("station_id", stationId)
            put("shift_id", shiftId)
            put("customer_party_id", partyId)
            put("fuel_type_id", fuelTypeId)
            put("liters", 100.0)
            put("price_per_liter", 500.0)
            put("fuel_subtotal", 50000.0)
            put("subtotal", 50000.0)
            put("gross_amount", 50000.0)
            put("net_amount", 50000.0)
            put("payment_method", "credit")
            put("payment_status", "pending")
            put("paid_amount", 0.0)
            put("remaining_amount", 50000.0)
            put("is_credit", 1)
            put("status", "completed")
            put("cashier_id", userId)
            put("order_type", "sale")
            put("created_at", now)
            put("updated_at", now)
            put("is_deleted", 0)
        })

        val beforeSales = scalarLong(db, "SELECT COUNT(*) FROM sales_transactions WHERE id = ?", arrayOf(saleId.toString()))
        val payload = JSONObject().apply {
            put("sale_id", saleId)
            put("party_id", partyId)
            put("delivery_date", now)
            put("status", "delivered")
            put("location", "موقع اختبار")
            put("idempotency_key", "delivery-test-" + saleId)
        }
        val first = helper.addDelivery(payload, stationId.toInt(), userId)
        val second = helper.addDelivery(JSONObject(payload.toString()), stationId.toInt(), userId)

        assertTrue(first > 0)
        assertEquals(first, second)
        assertEquals(beforeSales, scalarLong(db, "SELECT COUNT(*) FROM sales_transactions WHERE id = ?", arrayOf(saleId.toString())))
        assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM deliveries WHERE sale_id = ? AND is_deleted = 0", arrayOf(saleId.toString())))
        assertEquals(50000.0, scalarDouble(db, "SELECT net_amount FROM sales_transactions WHERE id = ?", arrayOf(saleId.toString())), 0.0001)
        assertEquals(0.0, scalarDouble(db, "SELECT current_balance FROM parties WHERE id = ?", arrayOf(partyId.toString())), 0.0001)
        assertTrue(db.rawQuery("PRAGMA foreign_key_check", null).use { !it.moveToFirst() })
    }

    @Test
    fun deliveryServiceFeeUpdatesOriginalSaleAndCustomerLedgerOnce() {
        val db = helper.writableDatabase
        val stationId = scalarLong(db, "SELECT id FROM stations ORDER BY id LIMIT 1")
        val userId = ensureTestUser(db, stationId)
        val partyTypeId = scalarLong(db, "SELECT id FROM party_types WHERE is_deleted = 0 ORDER BY id LIMIT 1")
        val fuelTypeId = scalarLong(db, "SELECT id FROM fuel_types WHERE is_deleted = 0 ORDER BY id LIMIT 1")
        val token = System.nanoTime().toString()
        val partyId = db.insertOrThrow("parties", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("party_code", "FEE-CUS-" + token)
            put("party_type_id", partyTypeId); put("station_id", stationId)
            put("commercial_name_ar", "عميل رسوم التوصيل"); put("credit_limit", 100000.0)
            put("current_balance", 0.0); put("total_due", 0.0); put("is_active", 1); put("is_deleted", 0)
        })
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val shiftId = db.insertOrThrow("shifts", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("shift_code", "FEE-SHIFT-" + token)
            put("station_id", stationId); put("shift_date", now.substring(0,10)); put("shift_type", "full_day")
            put("start_time", now); put("cashier_id", userId); put("status", "open"); put("is_deleted", 0)
        })
        val saleId = db.insertOrThrow("sales_transactions", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("sale_code", "FEE-SALE-" + token)
            put("station_id", stationId); put("shift_id", shiftId); put("customer_party_id", partyId)
            put("fuel_type_id", fuelTypeId); put("liters", 20.0); put("price_per_liter", 500.0)
            put("fuel_subtotal", 10000.0); put("subtotal", 10000.0); put("gross_amount", 10000.0)
            put("net_amount", 10000.0); put("service_fee", 0.0); put("payment_method", "credit")
            put("payment_status", "pending"); put("paid_amount", 0.0); put("remaining_amount", 10000.0)
            put("is_credit", 1); put("status", "completed"); put("cashier_id", userId)
            put("order_type", "sale"); put("created_at", now); put("updated_at", now); put("is_deleted", 0)
        })
        val payload = JSONObject().apply {
            put("sale_id", saleId); put("party_id", partyId); put("delivery_date", now)
            put("status", "delivered"); put("location", "موقع رسوم")
            put("delivery_service_fee", 100.0); put("idempotency_key", "fee-delivery-" + saleId)
        }
        val first = helper.addDelivery(payload, stationId.toInt(), userId)
        val second = helper.addDelivery(JSONObject(payload.toString()), stationId.toInt(), userId)

        assertEquals(first, second)
        assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM deliveries WHERE sale_id = ? AND is_deleted = 0", arrayOf(saleId.toString())))
        assertEquals(100.0, scalarDouble(db, "SELECT service_fee FROM sales_transactions WHERE id = ?", arrayOf(saleId.toString())), 0.0001)
        assertEquals(10100.0, scalarDouble(db, "SELECT net_amount FROM sales_transactions WHERE id = ?", arrayOf(saleId.toString())), 0.0001)
        assertEquals(100.0, scalarDouble(db, "SELECT current_balance FROM parties WHERE id = ?", arrayOf(partyId.toString())), 0.0001)
        assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM customer_ledger WHERE party_id = ? AND transaction_type = 'delivery_service_fee' AND transaction_id = ?", arrayOf(partyId.toString(), first.toString())))
    }

    @Test
    fun anonymousCustomerDeliveryUsesNullPartyWithoutCreatingFakeCustomer() {
        val db = helper.writableDatabase
        val stationId = scalarLong(db, "SELECT id FROM stations ORDER BY id LIMIT 1")
        val userId = ensureTestUser(db, stationId)
        val fuelTypeId = scalarLong(db, "SELECT id FROM fuel_types WHERE is_deleted = 0 ORDER BY id LIMIT 1")
        val token = System.nanoTime().toString()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val shiftId = db.insertOrThrow("shifts", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("shift_code", "ANON-SHIFT-" + token)
            put("station_id", stationId); put("shift_date", now.substring(0,10)); put("shift_type", "full_day")
            put("start_time", now); put("cashier_id", userId); put("status", "open"); put("is_deleted", 0)
        })
        val saleId = db.insertOrThrow("sales_transactions", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("sale_code", "ANON-SALE-" + token)
            put("station_id", stationId); put("shift_id", shiftId); put("fuel_type_id", fuelTypeId)
            put("liters", 30.0); put("price_per_liter", 500.0); put("fuel_subtotal", 15000.0)
            put("subtotal", 15000.0); put("gross_amount", 15000.0); put("net_amount", 15000.0)
            put("payment_method", "cash"); put("payment_status", "paid"); put("paid_amount", 15000.0)
            put("remaining_amount", 0.0); put("is_credit", 0); put("status", "completed")
            put("cashier_id", userId); put("order_type", "sale"); put("created_at", now)
            put("updated_at", now); put("is_deleted", 0)
        })

        val beforeParties = scalarLong(db, "SELECT COUNT(*) FROM parties")
        val deliveryId = helper.addDelivery(
            JSONObject().apply {
                put("sale_id", saleId)
                put("delivery_date", now)
                put("status", "delivered")
                put("location", "موقع عميل غير مسجل")
                put("idempotency_key", "anonymous-delivery-" + saleId)
            },
            stationId.toInt(),
            userId
        )

        assertTrue(deliveryId > 0)
        assertEquals(beforeParties, scalarLong(db, "SELECT COUNT(*) FROM parties"))
        assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM deliveries WHERE id = ? AND party_id IS NULL AND sale_id = ?", arrayOf(deliveryId.toString(), saleId.toString())))
        assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM sales_transactions WHERE id = ?", arrayOf(saleId.toString())))
        assertTrue(db.rawQuery("PRAGMA foreign_key_check", null).use { !it.moveToFirst() })
    }

    @Test
    fun anonymousSaleContextCanBeReReadWithoutCreatingCustomer() {
        val db = helper.writableDatabase
        val stationId = scalarLong(db, "SELECT id FROM stations ORDER BY id LIMIT 1")
        val userId = ensureTestUser(db, stationId)
        val fuelTypeId = scalarLong(db, "SELECT id FROM fuel_types WHERE is_deleted = 0 ORDER BY id LIMIT 1")
        val token = System.nanoTime().toString()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val shiftId = db.insertOrThrow("shifts", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("shift_code", "CTX-SHIFT-" + token)
            put("station_id", stationId); put("shift_date", now.substring(0,10)); put("shift_type", "full_day")
            put("start_time", now); put("cashier_id", userId); put("status", "open"); put("is_deleted", 0)
        })
        val saleId = db.insertOrThrow("sales_transactions", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("sale_code", "CTX-SALE-" + token)
            put("station_id", stationId); put("shift_id", shiftId); put("fuel_type_id", fuelTypeId)
            put("liters", 12.0); put("price_per_liter", 500.0); put("fuel_subtotal", 6000.0)
            put("subtotal", 6000.0); put("gross_amount", 6000.0); put("net_amount", 6000.0)
            put("payment_method", "cash"); put("payment_status", "paid"); put("paid_amount", 6000.0)
            put("remaining_amount", 0.0); put("is_credit", 0); put("status", "completed")
            put("cashier_id", userId); put("order_type", "sale"); put("created_at", now)
            put("updated_at", now); put("is_deleted", 0)
        })
        val beforeParties = scalarLong(db, "SELECT COUNT(*) FROM parties")
        val context = helper.getDeliverySaleContext(saleId, stationId.toInt())
        assertTrue(context.getBoolean("found"))
        val sale = context.getJSONObject("sale")
        assertTrue(sale.isNull("customer_party_id"))
        assertEquals(saleId, sale.getLong("sale_id"))
        assertEquals(12.0, sale.getDouble("quantity"), 0.0001)
        assertEquals(beforeParties, scalarLong(db, "SELECT COUNT(*) FROM parties"))
    }

    @Test
    fun deliveryReportsValidateDatesAndFilterByCustomerAndSite() {
        val db = helper.writableDatabase
        val stationId = scalarLong(db, "SELECT id FROM stations ORDER BY id LIMIT 1")
        val userId = ensureTestUser(db, stationId)
        val partyTypeId = scalarLong(db, "SELECT id FROM party_types WHERE is_deleted = 0 ORDER BY id LIMIT 1")
        val fuelTypeId = scalarLong(db, "SELECT id FROM fuel_types WHERE is_deleted = 0 ORDER BY id LIMIT 1")
        val token = System.nanoTime().toString()
        val partyId = db.insertOrThrow("parties", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("party_code", "REP-CUS-" + token)
            put("party_type_id", partyTypeId); put("station_id", stationId); put("commercial_name_ar", "عميل تقارير")
            put("credit_limit", 100000.0); put("is_active", 1); put("is_deleted", 0)
        })
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val shiftId = db.insertOrThrow("shifts", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("shift_code", "REP-SHIFT-" + token)
            put("station_id", stationId); put("shift_date", now.substring(0,10)); put("shift_type", "full_day")
            put("start_time", now); put("cashier_id", userId); put("status", "open"); put("is_deleted", 0)
        })
        val saleId = db.insertOrThrow("sales_transactions", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("sale_code", "REP-SALE-" + token)
            put("station_id", stationId); put("shift_id", shiftId); put("customer_party_id", partyId); put("fuel_type_id", fuelTypeId)
            put("liters", 20.0); put("price_per_liter", 500.0); put("fuel_subtotal", 10000.0); put("subtotal", 10000.0)
            put("gross_amount", 10000.0); put("net_amount", 10000.0); put("payment_method", "credit"); put("payment_status", "pending")
            put("remaining_amount", 10000.0); put("is_credit", 1); put("status", "completed"); put("cashier_id", userId)
            put("order_type", "sale"); put("created_at", now); put("updated_at", now); put("is_deleted", 0)
        })
        helper.addDelivery(JSONObject().apply {
            put("sale_id", saleId); put("party_id", partyId); put("delivery_date", now)
            put("status", "delivered"); put("location", "موقع تقارير"); put("idempotency_key", "report-delivery-" + saleId)
        }, stationId.toInt(), userId)

        val customerReport = helper.getDeliveryManagementReport(
            JSONObject().apply {
                put("report_type", "customer"); put("from_date", now.substring(0,10)); put("to_date", now.substring(0,10)); put("party_id", partyId)
            },
            stationId.toInt()
        )
        assertEquals(1, customerReport.getInt("count"))

        val siteReport = helper.getDeliveryManagementReport(
            JSONObject().apply {
                put("report_type", "site"); put("from_date", now.substring(0,10)); put("to_date", now.substring(0,10)); put("location", "موقع تقارير")
            },
            stationId.toInt()
        )
        assertEquals(1, siteReport.getInt("count"))

        try {
            helper.getDeliveryManagementReport(
                JSONObject().apply { put("report_type", "detailed"); put("from_date", "2026-99-99"); put("to_date", "2026-99-99") },
                stationId.toInt()
            )
            throw AssertionError("Invalid dates must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    private fun ensureTestUser(db: android.database.sqlite.SQLiteDatabase, stationId: Long): Long {
        val existing = db.rawQuery("SELECT id FROM users WHERE is_deleted = 0 ORDER BY id LIMIT 1", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
        if (existing > 0L) return existing
        return db.insertOrThrow("users", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("username", "delivery-test-" + System.nanoTime())
            put("password_hash", "test-hash")
            put("password_salt", "test-salt")
            put("full_name", "Delivery Test User")
            put("full_name_ar", "مستخدم اختبار التوصيل")
            put("role_id", 1L)
            put("station_id", stationId)
            put("company_id", 1L)
            put("status", "active")
            put("is_deleted", 0)
        })
    }

    private fun scalarLong(db: android.database.sqlite.SQLiteDatabase, sql: String, args: Array<String> = emptyArray()): Long =
        db.rawQuery(sql, args).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }

    private fun scalarDouble(db: android.database.sqlite.SQLiteDatabase, sql: String, args: Array<String> = emptyArray()): Double =
        db.rawQuery(sql, args).use { cursor -> check(cursor.moveToFirst()); cursor.getDouble(0) }
}
