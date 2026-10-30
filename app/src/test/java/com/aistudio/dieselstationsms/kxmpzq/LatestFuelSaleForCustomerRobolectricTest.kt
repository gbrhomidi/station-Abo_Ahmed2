package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LatestFuelSaleForCustomerRobolectricTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper
    private lateinit var db: SQLiteDatabase
    private var stationId: Int = 0
    private var userId: Long = 0L
    private var shiftId: Long = 0L
    private var fuelTypeId: Long = 0L
    private var pumpId: Long = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        helper = DatabaseHelper.getInstance(context)
        db = helper.writableDatabase
        stationId = scalarLong("SELECT id FROM stations ORDER BY id LIMIT 1").toInt()
        userId = ensureTestUser()
        fuelTypeId = scalarLong("SELECT id FROM fuel_types WHERE is_deleted=0 ORDER BY id LIMIT 1")
        val now = "2026-10-30 08:00:00"
        shiftId = db.insertOrThrow("shifts", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("shift_code", "LATEST-SHIFT-" + UUID.randomUUID().toString().take(8))
            put("station_id", stationId)
            put("shift_date", now.substring(0, 10))
            put("shift_type", "full_day")
            put("start_time", now)
            put("cashier_id", userId)
            put("status", "open")
            put("is_deleted", 0)
        })
        pumpId = optionalScalarLong(
            "SELECT id FROM pumps WHERE station_id=? AND COALESCE(is_deleted,0)=0 ORDER BY id LIMIT 1",
            arrayOf(stationId.toString())
        )
        DeliveryFeeAccounting.ensureSchema(helper)
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    @Test
    fun latestLookupChoosesNewestSaleForExactCustomerIdWhenNamesMatch() {
        val targetCustomer = insertCustomer("عميل متشابه الاسم")
        val otherCustomerSameName = insertCustomer("عميل متشابه الاسم")

        insertFuelSale(targetCustomer, stationId, "2026-10-28 09:00:00", quantity = 10.0)
        val expectedFuelSaleId = insertFuelSale(targetCustomer, stationId, "2026-10-29 15:30:00", quantity = 30.0)
        insertFuelSale(otherCustomerSameName, stationId, "2026-10-30 18:00:00", quantity = 90.0)

        val result = DeliveryFeeAccounting.latestFuelSaleForCustomer(helper, targetCustomer, stationId)

        assertTrue(result.getBoolean("found"))
        val sale = result.getJSONObject("sale")
        assertEquals(expectedFuelSaleId, sale.getLong("fuel_sale_id"))
        assertEquals(expectedFuelSaleId, sale.getLong("sale_id"))
        assertEquals(targetCustomer, sale.getLong("customer_party_id"))
        assertEquals(30.0, sale.getDouble("quantity"), 0.0001)
        assertEquals(15000.0, sale.getDouble("total_amount"), 0.0001)
        assertEquals(250.0, sale.getDouble("delivery_service_fee"), 0.0001)
        assertTrue(sale.has("parent_sale_id"))
    }

    @Test
    fun lookupReturnsTheOnlySaleAndSafelyHandlesCustomerWithoutSales() {
        val customer = insertCustomer("عميل عملية واحدة")

        val emptyResult = DeliveryFeeAccounting.latestFuelSaleForCustomer(helper, customer, stationId)
        assertFalse(emptyResult.getBoolean("found"))

        val onlySaleId = insertFuelSale(customer, stationId, "2026-10-30 11:20:00")
        val result = DeliveryFeeAccounting.latestFuelSaleForCustomer(helper, customer, stationId)

        assertTrue(result.getBoolean("found"))
        assertEquals(onlySaleId, result.getJSONObject("sale").getLong("fuel_sale_id"))
    }

    @Test
    fun lookupNeverReturnsFuelSaleFromAnotherStation() {
        val customer = insertCustomer("عميل محطة مختلفة")
        val otherStationId = stationId + 100000
        insertFuelSale(customer, otherStationId, "2026-10-30 23:59:00")

        val result = DeliveryFeeAccounting.latestFuelSaleForCustomer(helper, customer, stationId)

        assertFalse(result.getBoolean("found"))
    }

    @Test
    fun lookupExcludesDeletedFuelRowsAndDeletedOrInvalidParentInvoices() {
        val customer = insertCustomer("عميل عمليات غير صالحة")
        val expectedFuelSaleId = insertFuelSale(customer, stationId, "2026-10-27 10:00:00")
        insertFuelSale(customer, stationId, "2026-10-28 10:00:00", childDeleted = true)
        insertFuelSale(customer, stationId, "2026-10-29 10:00:00", parentDeleted = true)
        insertFuelSale(customer, stationId, "2026-10-30 10:00:00", parentInvalid = true)

        val result = DeliveryFeeAccounting.latestFuelSaleForCustomer(helper, customer, stationId)

        assertTrue(result.getBoolean("found"))
        assertEquals(expectedFuelSaleId, result.getJSONObject("sale").getLong("fuel_sale_id"))
    }

    private fun insertCustomer(name: String): Long {
        val partyTypeId = scalarLong("SELECT id FROM party_types WHERE is_deleted=0 ORDER BY id LIMIT 1")
        return db.insertOrThrow("parties", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("party_code", "LATEST-CUS-" + UUID.randomUUID().toString().take(10))
            put("party_type_id", partyTypeId)
            put("station_id", stationId)
            put("commercial_name", name)
            put("commercial_name_ar", name)
            put("credit_limit", 100000.0)
            put("current_balance", 0.0)
            put("total_due", 0.0)
            put("is_active", 1)
            put("is_deleted", 0)
        })
    }

    private fun insertFuelSale(
        customerId: Long,
        saleStationId: Int,
        timestamp: String,
        quantity: Double = 25.0,
        childDeleted: Boolean = false,
        parentDeleted: Boolean = false,
        parentInvalid: Boolean = false
    ): Long {
        val token = UUID.randomUUID().toString()
        val parentValues = ContentValues()
        putExisting("sales_transactions", parentValues, "uuid", token)
        putExisting("sales_transactions", parentValues, "sale_code", "LATEST-SALE-$token")
        putExisting("sales_transactions", parentValues, "station_id", saleStationId)
        putExisting("sales_transactions", parentValues, "shift_id", shiftId)
        putExisting("sales_transactions", parentValues, "customer_party_id", customerId)
        putExisting("sales_transactions", parentValues, "customer_id", customerId)
        putExisting("sales_transactions", parentValues, "fuel_type_id", fuelTypeId)
        putExisting("sales_transactions", parentValues, "liters", quantity)
        putExisting("sales_transactions", parentValues, "quantity", quantity)
        putExisting("sales_transactions", parentValues, "price_per_liter", 500.0)
        putExisting("sales_transactions", parentValues, "fuel_subtotal", quantity * 500.0)
        putExisting("sales_transactions", parentValues, "subtotal", quantity * 500.0)
        putExisting("sales_transactions", parentValues, "gross_amount", quantity * 500.0)
        putExisting("sales_transactions", parentValues, "net_amount", quantity * 500.0)
        putExisting("sales_transactions", parentValues, "total_amount", quantity * 500.0)
        putExisting("sales_transactions", parentValues, "payment_method", "cash")
        putExisting("sales_transactions", parentValues, "payment_status", "paid")
        putExisting("sales_transactions", parentValues, "paid_amount", quantity * 500.0)
        putExisting("sales_transactions", parentValues, "remaining_amount", 0.0)
        putExisting("sales_transactions", parentValues, "is_credit", 0)
        putExisting("sales_transactions", parentValues, "status", if (parentInvalid) "void" else "completed")
        putExisting("sales_transactions", parentValues, "is_void", if (parentInvalid) 1 else 0)
        putExisting("sales_transactions", parentValues, "is_valid", if (parentInvalid) 0 else 1)
        putExisting("sales_transactions", parentValues, "is_deleted", if (parentDeleted) 1 else 0)
        putExisting("sales_transactions", parentValues, "deleted_at", if (parentDeleted) timestamp else null)
        putExisting("sales_transactions", parentValues, "cashier_id", userId)
        putExisting("sales_transactions", parentValues, "order_type", "sale")
        putExisting("sales_transactions", parentValues, "sale_type", "retail")
        putExisting("sales_transactions", parentValues, "created_at", timestamp)
        putExisting("sales_transactions", parentValues, "updated_at", timestamp)
        completeRequiredColumns("sales_transactions", parentValues, customerId, saleStationId, 0L)

        db.setForeignKeyConstraintsEnabled(false)
        try {
            val parentSaleId = db.insertOrThrow("sales_transactions", null, parentValues)

            val childValues = ContentValues()
            putExisting("fuel_sales", childValues, "uuid", token)
            putExisting("fuel_sales", childValues, "sale_code", "LATEST-FUEL-$token")
            putExisting("fuel_sales", childValues, "sale_id", parentSaleId)
            putExisting("fuel_sales", childValues, "station_id", saleStationId)
            putExisting("fuel_sales", childValues, "customer_party_id", customerId)
            putExisting("fuel_sales", childValues, "customer_id", customerId)
            putExisting("fuel_sales", childValues, "party_id", customerId)
            putExisting("fuel_sales", childValues, "shift_id", shiftId)
            putExisting("fuel_sales", childValues, "pump_id", pumpId)
            putExisting("fuel_sales", childValues, "fuel_type_id", fuelTypeId)
            putExisting("fuel_sales", childValues, "fuel_id", fuelTypeId)
            putExisting("fuel_sales", childValues, "quantity", quantity)
            putExisting("fuel_sales", childValues, "liters", quantity)
            putExisting("fuel_sales", childValues, "price_per_liter", 500.0)
            putExisting("fuel_sales", childValues, "total_amount", quantity * 500.0)
            putExisting("fuel_sales", childValues, "net_amount", quantity * 500.0)
            putExisting("fuel_sales", childValues, "gross_amount", quantity * 500.0)
            putExisting("fuel_sales", childValues, "gross_quantity", quantity)
            putExisting("fuel_sales", childValues, "payment_method", "cash")
            putExisting("fuel_sales", childValues, "sale_date", timestamp.substring(0, 10))
            putExisting("fuel_sales", childValues, "sale_time", timestamp.substring(11))
            putExisting("fuel_sales", childValues, "transaction_time", timestamp)
            putExisting("fuel_sales", childValues, "created_at", timestamp)
            putExisting("fuel_sales", childValues, "delivery_service_fee", 250.0)
            putExisting("fuel_sales", childValues, "delivery_fee_mode", "add_to_sales")
            putExisting("fuel_sales", childValues, "delivery_location", "موقع اختبار")
            putExisting("fuel_sales", childValues, "cash_customer_name", "")
            putExisting("fuel_sales", childValues, "delivery_fee_display_total", quantity * 500.0 + 250.0)
            putExisting("fuel_sales", childValues, "status", if (parentInvalid) "void" else "completed")
            putExisting("fuel_sales", childValues, "is_void", if (parentInvalid) 1 else 0)
            putExisting("fuel_sales", childValues, "is_valid", if (parentInvalid) 0 else 1)
            putExisting("fuel_sales", childValues, "is_deleted", if (childDeleted) 1 else 0)
            putExisting("fuel_sales", childValues, "deleted_at", if (childDeleted) timestamp else null)
            completeRequiredColumns("fuel_sales", childValues, customerId, saleStationId, parentSaleId)
            return db.insertOrThrow("fuel_sales", null, childValues)
        } finally {
            db.setForeignKeyConstraintsEnabled(true)
        }
    }

    private fun putExisting(table: String, values: ContentValues, column: String, value: Any?) {
        if (column !in tableColumns(table)) return
        when (value) {
            null -> values.putNull(column)
            is String -> values.put(column, value)
            is Int -> values.put(column, value)
            is Long -> values.put(column, value)
            is Double -> values.put(column, value)
            is Float -> values.put(column, value)
            is Boolean -> values.put(column, if (value) 1 else 0)
            else -> values.put(column, value.toString())
        }
    }

    private fun completeRequiredColumns(
        table: String,
        values: ContentValues,
        customerId: Long,
        rowStationId: Int,
        parentSaleId: Long
    ) {
        val foreignKeys = mutableMapOf<String, Pair<String, String>>()
        db.rawQuery("PRAGMA foreign_key_list(`$table`)", null).use { cursor ->
            while (cursor.moveToNext()) {
                val fromIndex = cursor.getColumnIndex("from")
                val tableIndex = cursor.getColumnIndex("table")
                val toIndex = cursor.getColumnIndex("to")
                if (fromIndex >= 0 && tableIndex >= 0) {
                    val column = cursor.getString(fromIndex)
                    val refTable = cursor.getString(tableIndex)
                    val refColumn = if (toIndex >= 0) cursor.getString(toIndex).orEmpty().ifBlank { "id" } else "id"
                    foreignKeys[column] = refTable to refColumn
                }
            }
        }

        db.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val type = cursor.getString(cursor.getColumnIndexOrThrow("type")).orEmpty().uppercase()
                val notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) != 0
                val hasDefault = !cursor.isNull(cursor.getColumnIndexOrThrow("dflt_value"))
                val primaryKey = cursor.getInt(cursor.getColumnIndexOrThrow("pk")) != 0
                if (!notNull || hasDefault || primaryKey || values.containsKey(name)) continue

                val foreign = foreignKeys[name]
                val foreignValue = when (foreign?.first) {
                    "sales_transactions" -> parentSaleId.takeIf { it > 0L }
                    "parties" -> customerId
                    "stations" -> rowStationId.toLong()
                    "users" -> userId
                    "shifts" -> shiftId
                    "fuel_types" -> fuelTypeId
                    "pumps" -> pumpId.takeIf { it > 0L }
                    else -> foreign?.let { referencedValue(it.first, it.second) }
                }
                if (foreignValue != null) {
                    values.put(name, foreignValue)
                } else if (type.contains("INT")) {
                    values.put(name, 0L)
                } else if (type.contains("REAL") || type.contains("NUM") || type.contains("FLOA") || type.contains("DOUB")) {
                    values.put(name, 0.0)
                } else {
                    values.put(name, "TEST-" + UUID.randomUUID().toString())
                }
            }
        }
    }

    private fun referencedValue(table: String, column: String): Long? {
        if (table !in setOf("stations", "parties", "users", "shifts", "fuel_types", "pumps", "accounts", "companies", "warehouses")) return null
        return runCatching {
            db.rawQuery("SELECT `$column` FROM `$table` ORDER BY `$column` LIMIT 1", null).use {
                if (it.moveToFirst()) it.getLong(0) else null
            }
        }.getOrNull()
    }

    private fun tableColumns(table: String): Set<String> {
        val columns = linkedSetOf<String>()
        db.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
        }
        return columns
    }

    private fun scalarLong(sql: String, args: Array<String> = emptyArray()): Long =
        db.rawQuery(sql, args).use { cursor -> check(cursor.moveToFirst()) { "Expected a row for: $sql" }; cursor.getLong(0) }

    private fun optionalScalarLong(sql: String, args: Array<String> = emptyArray()): Long =
        db.rawQuery(sql, args).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    private fun ensureTestUser(): Long {
        val existing = optionalScalarLong("SELECT id FROM users WHERE is_deleted=0 ORDER BY id LIMIT 1")
        if (existing > 0L) return existing
        return db.insertOrThrow("users", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("username", "latest-test-" + UUID.randomUUID().toString().take(8))
            put("password_hash", "test-hash")
            put("password_salt", "test-salt")
            put("full_name", "Latest Sale Test")
            put("full_name_ar", "مستخدم اختبار آخر بيع")
            put("role_id", 1L)
            put("station_id", stationId)
            put("company_id", 1L)
            put("status", "active")
            put("is_deleted", 0)
        })
    }
}
