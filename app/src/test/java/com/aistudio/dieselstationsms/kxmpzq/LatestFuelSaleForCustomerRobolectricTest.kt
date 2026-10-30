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

        assertTrue("Latest-sale lookup response: $result; ${saleLookupDiagnostics(targetCustomer)}", result.getBoolean("found"))
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

        assertTrue("Latest-sale lookup response: $result; ${saleLookupDiagnostics(customer)}", result.getBoolean("found"))
        assertEquals(onlySaleId, result.getJSONObject("sale").getLong("fuel_sale_id"))
    }

    @Test
    fun lookupNeverReturnsFuelSaleFromAnotherStation() {
        val customer = insertCustomer("عميل له مبيعات في محطتين")
        val expectedFuelSaleId = insertFuelSale(customer, stationId, "2026-10-29 12:00:00")
        val otherStationId = stationId + 100000
        insertFuelSale(customer, otherStationId, "2026-10-30 23:59:00")

        val result = DeliveryFeeAccounting.latestFuelSaleForCustomer(helper, customer, stationId)

        assertTrue("Latest-sale lookup response: $result; ${saleLookupDiagnostics(customer)}", result.getBoolean("found"))
        assertEquals(expectedFuelSaleId, result.getJSONObject("sale").getLong("fuel_sale_id"))
    }

    @Test
    fun lookupExcludesDeletedFuelRowsAndDeletedOrInvalidParentInvoices() {
        val customer = insertCustomer("عميل عمليات غير صالحة")
        val expectedFuelSaleId = insertFuelSale(customer, stationId, "2026-10-27 10:00:00")
        insertFuelSale(customer, stationId, "2026-10-28 10:00:00", childDeleted = true)
        insertFuelSale(customer, stationId, "2026-10-29 10:00:00", parentDeleted = true)
        insertFuelSale(customer, stationId, "2026-10-30 10:00:00", parentInvalid = true)

        val result = DeliveryFeeAccounting.latestFuelSaleForCustomer(helper, customer, stationId)

        assertTrue("Latest-sale lookup response: $result; ${saleLookupDiagnostics(customer)}", result.getBoolean("found"))
        assertEquals(expectedFuelSaleId, result.getJSONObject("sale").getLong("fuel_sale_id"))
    }


    @Test
    fun lookupExcludesDraftAndRefundedParentInvoices() {
        val customer = insertCustomer("عميل فواتير غير مكتملة أو مسترجعة")
        val expectedFuelSaleId = insertFuelSale(customer, stationId, "2026-10-27 10:00:00")
        insertFuelSale(
            customer,
            stationId,
            "2026-10-29 10:00:00",
            parentStatusOverride = "refunded"
        )
        insertFuelSale(
            customer,
            stationId,
            "2026-10-30 10:00:00",
            parentStatusOverride = "draft"
        )

        val result = DeliveryFeeAccounting.latestFuelSaleForCustomer(helper, customer, stationId)

        assertTrue("Latest-sale lookup response: $result; ${saleLookupDiagnostics(customer)}", result.getBoolean("found"))
        assertEquals(expectedFuelSaleId, result.getJSONObject("sale").getLong("fuel_sale_id"))
    }

    @Test
    fun savedLocationsAreStationScopedNormalizedDeduplicatedAndExcludeInvalidRowsWithoutWrites() {
        val customer = insertCustomer("عميل المواقع المحفوظة")
        val validOne = insertFuelSale(customer, stationId, "2026-10-28 08:00:00", location = "  شارع   الملك  ")
        insertFuelSale(customer, stationId, "2026-10-29 08:00:00", location = "شارع الملك")
        insertFuelSale(customer, stationId, "2026-10-29 09:00:00", childDeleted = true, location = "موقع محذوف")
        insertFuelSale(customer, stationId, "2026-10-29 10:00:00", parentStatusOverride = "refunded", location = "موقع مسترجع")
        insertFuelSale(customer, stationId, "2026-10-29 11:00:00", parentInvalid = true, location = "موقع ملغي")
        insertFuelSale(customer, stationId + 100000, "2026-10-30 12:00:00", location = "موقع محطة أخرى")

        // The child claims the current station, but its owning invoice belongs elsewhere.
        val mismatchedChild = insertFuelSale(customer, stationId, "2026-10-30 13:00:00", location = "موقع فاتورة مختلفة")
        val mismatchedParent = scalarLong(
            "SELECT sale_id FROM fuel_sales WHERE id=?",
            arrayOf(mismatchedChild.toString())
        )
        db.setForeignKeyConstraintsEnabled(false)
        try {
            db.update(
                "sales_transactions",
                ContentValues().apply { put("station_id", stationId + 200000) },
                "id=?",
                arrayOf(mismatchedParent.toString())
            )
        } finally {
            db.setForeignKeyConstraintsEnabled(true)
        }

        val schemaBefore = scalarString(
            "SELECT GROUP_CONCAT(type || ':' || name || ':' || COALESCE(sql,''),'|') FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'"
        )
        val rowCountsBefore = listOf("fuel_sales", "sales_transactions", "deliveries").associateWith {
            optionalScalarLong("SELECT COUNT(*) FROM `$it`")
        }

        val locations = DeliveryFeeAccounting.savedDeliveryLocations(helper, stationId)
        val values = (0 until locations.length()).map { locations.getString(it) }
        val normalized = values.map { it.trim().replace(Regex("\\s+"), " ").lowercase() }

        assertTrue("Expected valid location from fuel row $validOne; actual=$values", normalized.contains("شارع الملك"))
        assertEquals("Location suggestions must be unique after case/whitespace normalization", normalized.size, normalized.toSet().size)
        assertFalse(values.any { it.contains("محذوف") || it.contains("مسترجع") || it.contains("ملغي") })
        assertFalse(values.any { it.contains("محطة أخرى") || it.contains("فاتورة مختلفة") })

        val schemaAfter = scalarString(
            "SELECT GROUP_CONCAT(type || ':' || name || ':' || COALESCE(sql,''),'|') FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'"
        )
        val rowCountsAfter = listOf("fuel_sales", "sales_transactions", "deliveries").associateWith {
            optionalScalarLong("SELECT COUNT(*) FROM `$it`")
        }
        assertEquals("Autocomplete must not mutate SQLite schema", schemaBefore, schemaAfter)
        assertEquals("Autocomplete must not insert/update/delete business rows", rowCountsBefore, rowCountsAfter)
    }

    @Test
    fun savedLocationsReadDeliveriesOnlyThroughValidSameStationInvoicesWithoutWrites() {
        val customer = insertCustomer("عميل مواقع الفواتير")
        val localFuelSaleId = insertFuelSale(
            customer, stationId, "2026-10-30 10:00:00", location = ""
        )
        val localInvoiceId = scalarLong(
            "SELECT sale_id FROM fuel_sales WHERE id=?",
            arrayOf(localFuelSaleId.toString())
        )

        // This invoice is owned by another station while the delivery.party_id points
        // to a customer in the current station. The invoice, not party_id, is authoritative.
        val foreignFuelSaleId = insertFuelSale(
            customer, stationId + 100000, "2026-10-30 11:00:00", location = ""
        )
        val foreignInvoiceId = scalarLong(
            "SELECT sale_id FROM fuel_sales WHERE id=?",
            arrayOf(foreignFuelSaleId.toString())
        )

        fun insertDelivery(invoiceId: Long, location: String, deleted: Boolean = false, declaredStation: Int? = null) {
            val values = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("sale_id", invoiceId)
                put("party_id", customer)
                put("delivery_date", "2026-10-30 12:00:00")
                put("status", "delivered")
                put("location", location)
                put("is_deleted", if (deleted) 1 else 0)
            }
            if (declaredStation != null) {
                putExisting("deliveries", values, "station_id", declaredStation)
            }
            db.insertOrThrow("deliveries", null, values)
        }

        val localLocation = "موقع توصيل من فاتورة المحطة الحالية"
        val foreignLocation = "موقع تسليم تابع لفاتورة محطة أخرى"
        val deletedLocation = "موقع توصيل محذوف"
        insertDelivery(localInvoiceId, localLocation, declaredStation = stationId)
        insertDelivery(foreignInvoiceId, foreignLocation, declaredStation = stationId)
        insertDelivery(localInvoiceId, deletedLocation, deleted = true, declaredStation = stationId)

        val contradictoryLocation = "موقع تسليم بنطاق محطة متناقض"
        if ("station_id" in tableColumns("deliveries")) {
            insertDelivery(localInvoiceId, contradictoryLocation, declaredStation = stationId + 500000)
        }

        val schemaBefore = scalarString(
            "SELECT GROUP_CONCAT(type || ':' || name || ':' || COALESCE(sql,''),'|') FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'"
        )
        val deliveryCountBefore = optionalScalarLong("SELECT COUNT(*) FROM deliveries")

        val locations = DeliveryFeeAccounting.savedDeliveryLocations(helper, stationId)
        val values = (0 until locations.length()).map { locations.getString(it) }

        assertTrue("A valid delivery location linked to a current-station invoice must be suggested: $values", values.contains(localLocation))
        assertFalse("A delivery linked to a foreign-station invoice must not leak via its party_id: $values", values.contains(foreignLocation))
        assertFalse("Deleted delivery rows must not supply autocomplete locations: $values", values.contains(deletedLocation))
        if ("station_id" in tableColumns("deliveries")) {
            assertFalse("A conflicting legacy deliveries.station_id must be rejected: $values", values.contains(contradictoryLocation))
        }

        val schemaAfter = scalarString(
            "SELECT GROUP_CONCAT(type || ':' || name || ':' || COALESCE(sql,''),'|') FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'"
        )
        val deliveryCountAfter = optionalScalarLong("SELECT COUNT(*) FROM deliveries")
        assertEquals("Delivery autocomplete must remain schema read-only", schemaBefore, schemaAfter)
        assertEquals("Delivery autocomplete must not create or repair delivery rows", deliveryCountBefore, deliveryCountAfter)
    }

    @Test
    fun recentCustomerRankingUsesRealDatesGroupsCustomersAndRejectsInvalidOrForeignParentInvoices() {
        val lateCustomer = insertCustomer("عميل أحدث زمنياً")
        val earlyCustomer = insertCustomer("عميل أقدم زمنياً")
        val invalidCustomer = insertCustomer("عميل عمليات غير صالحة")
        val stationMismatchCustomer = insertCustomer("عميل فاتورة بمحطة أخرى")

        // Lexical TEXT ordering incorrectly puts the 'T09...' ISO value after the space value.
        insertFuelSale(lateCustomer, stationId, "2026-10-30 23:00:00")
        insertFuelSale(lateCustomer, stationId, "2026-10-30 20:00:00")
        insertFuelSale(earlyCustomer, stationId, "2026-10-30T09:00:00Z")
        insertFuelSale(invalidCustomer, stationId, "2026-10-30 23:59:00", childDeleted = true)
        insertFuelSale(invalidCustomer, stationId, "2026-10-30 23:58:00", parentStatusOverride = "refunded")
        insertFuelSale(invalidCustomer, stationId, "2026-10-30 23:57:00", parentInvalid = true)

        val mismatchedChild = insertFuelSale(
            stationMismatchCustomer,
            stationId,
            "2026-10-30 23:56:00"
        )
        val mismatchedParent = scalarLong(
            "SELECT sale_id FROM fuel_sales WHERE id=?",
            arrayOf(mismatchedChild.toString())
        )
        db.setForeignKeyConstraintsEnabled(false)
        try {
            db.update(
                "sales_transactions",
                ContentValues().apply { put("station_id", stationId + 300000) },
                "id=?",
                arrayOf(mismatchedParent.toString())
            )
        } finally {
            db.setForeignKeyConstraintsEnabled(true)
        }

        val ids = DeliveryFeeAccounting.recentCustomerSaleIds(helper, stationId)
        val orderedIds = (0 until ids.length()).map { ids.getLong(it) }

        assertEquals("The output must contain each customer exactly once", orderedIds.size, orderedIds.toSet().size)
        assertTrue("Recent customer result: $orderedIds", orderedIds.contains(lateCustomer))
        assertTrue("Recent customer result: $orderedIds", orderedIds.contains(earlyCustomer))
        assertFalse("Deleted/refunded/invalid customers must not influence recent ranking", orderedIds.contains(invalidCustomer))
        assertFalse("A child row whose parent invoice belongs to another station must be excluded", orderedIds.contains(stationMismatchCustomer))
        assertTrue("julianday ordering should place the real 23:00 timestamp before 09:00Z", orderedIds.indexOf(lateCustomer) < orderedIds.indexOf(earlyCustomer))
    }

    @Test
    fun deliveryLookupsReturnEmptyArraysWhenNoStationHasMatchingRows() {
        val unusedStationId = stationId + 900000
        assertEquals(0, DeliveryFeeAccounting.savedDeliveryLocations(helper, unusedStationId).length())
        assertEquals(0, DeliveryFeeAccounting.recentCustomerSaleIds(helper, unusedStationId).length())
    }

    @Test
    fun deliveryLookupQueriesExposePlansAndBoundedRobolectricPerformanceMeasurements() {
        val customer = insertCustomer("عميل قياس الأداء")
        repeat(35) { index ->
            insertFuelSale(
                customer,
                stationId,
                "2026-10-30 %02d:%02d:00".format(index / 60, index % 60),
                location = "موقع أداء ${index % 7}"
            )
        }

        val locationPlan = DeliveryFeeAccounting.explainDeliveryLookupQueryPlan(helper, stationId, recentCustomers = false)
        val customerPlan = DeliveryFeeAccounting.explainDeliveryLookupQueryPlan(helper, stationId, recentCustomers = true)
        assertTrue("savedDeliveryLocations EXPLAIN QUERY PLAN was empty", locationPlan.isNotEmpty())
        assertTrue("recentCustomerSaleIds EXPLAIN QUERY PLAN was empty", customerPlan.isNotEmpty())

        fun measure(action: () -> Unit): List<Long> {
            repeat(3) { action() } // warm SQLite/Robolectric before collecting samples
            return List(12) {
                val startNanos = System.nanoTime()
                action()
                (System.nanoTime() - startNanos) / 1_000_000L
            }.sorted()
        }
        val locationSamples = measure { DeliveryFeeAccounting.savedDeliveryLocations(helper, stationId) }
        val customerSamples = measure { DeliveryFeeAccounting.recentCustomerSaleIds(helper, stationId) }
        val locationP95 = locationSamples[(locationSamples.size * 95 / 100).coerceAtMost(locationSamples.lastIndex)]
        val customerP95 = customerSamples[(customerSamples.size * 95 / 100).coerceAtMost(customerSamples.lastIndex)]

        println("DELIVERY_LOOKUP_PLAN locations=${locationPlan.joinToString(" || ")}")
        println("DELIVERY_LOOKUP_PLAN recentCustomers=${customerPlan.joinToString(" || ")}")
        println("DELIVERY_LOOKUP_PERF ms locationsMedian=${locationSamples[locationSamples.size / 2]} locationsP95=$locationP95 recentMedian=${customerSamples[customerSamples.size / 2]} recentP95=$customerP95")

        // A generous guard catches pathological regressions without making the test
        // dependent on the speed of a particular CI virtual machine.
        assertTrue("savedDeliveryLocations P95 exceeded 5 seconds: $locationP95 ms", locationP95 < 5000L)
        assertTrue("recentCustomerSaleIds P95 exceeded 5 seconds: $customerP95 ms", customerP95 < 5000L)
    }

    private fun saleLookupDiagnostics(customerId: Long): String {
        val childColumns = tableColumns("fuel_sales")
        val parentColumns = tableColumns("sales_transactions")
        val canJoinParent = "sale_id" in childColumns && "id" in parentColumns
        fun expression(alias: String, columns: Set<String>, name: String): String =
            if (name in columns) "COALESCE(CAST($alias.`$name` AS TEXT),'NULL')" else "'<missing>'"

        val from = if (canJoinParent) {
            "fuel_sales fs LEFT JOIN sales_transactions st ON st.id=fs.sale_id"
        } else {
            "fuel_sales fs"
        }
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        for ((alias, columns) in listOf("fs" to childColumns, "st" to parentColumns)) {
            if (alias == "st" && !canJoinParent) continue
            for (column in listOf("customer_party_id", "customer_id")) {
                if (column in columns) {
                    where += "$alias.`$column`=?"
                    args += customerId.toString()
                }
            }
        }
        if (where.isEmpty()) return "customerId=$customerId childColumns=$childColumns parentColumns=$parentColumns"

        val fields = mutableListOf(
            "fs.id AS fuel_sale_id",
            expression("fs", childColumns, "sale_id") + " AS child_parent_id",
            expression("fs", childColumns, "station_id") + " AS child_station",
            expression("fs", childColumns, "customer_party_id") + " AS child_party",
            expression("fs", childColumns, "customer_id") + " AS child_customer",
            expression("st", parentColumns, "station_id") + " AS parent_station",
            expression("st", parentColumns, "customer_party_id") + " AS parent_party",
            expression("st", parentColumns, "customer_id") + " AS parent_customer"
        )
        for (column in listOf("is_deleted", "deleted_at", "is_valid", "is_void", "is_voided", "is_invalid", "is_cancelled", "is_canceled", "is_reversed", "status", "sale_status", "transaction_status", "cancelled_at", "canceled_at", "voided_at", "invalidated_at", "reversed_at")) {
            fields += expression("fs", childColumns, column) + " AS child_$column"
            if (canJoinParent) fields += expression("st", parentColumns, column) + " AS parent_$column"
        }
        val sql = "SELECT ${fields.joinToString(",")} FROM $from WHERE ${where.joinToString(" OR ")} ORDER BY fs.id DESC LIMIT 20"
        val rows = mutableListOf<String>()
        db.rawQuery(sql, args.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                val values = (0 until cursor.columnCount).map { index ->
                    "${cursor.getColumnName(index)}=${cursor.getString(index) ?: "NULL"}"
                }
                rows += values.joinToString(",")
            }
        }
        return "customerId=$customerId stationId=$stationId childColumns=$childColumns parentColumns=$parentColumns rows=$rows"
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

    private fun scalarString(sql: String): String =
        db.rawQuery(sql, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }

    private fun insertFuelSale(
        customerId: Long,
        saleStationId: Int,
        timestamp: String,
        quantity: Double = 25.0,
        childDeleted: Boolean = false,
        parentDeleted: Boolean = false,
        parentInvalid: Boolean = false,
        parentStatusOverride: String? = null,
        location: String = "موقع اختبار"
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
        putExisting("sales_transactions", parentValues, "status", if (parentInvalid) "cancelled" else parentStatusOverride ?: "completed")
        for (flag in listOf("is_cancelled", "is_canceled", "is_void", "is_voided", "is_invalid", "is_reversed")) {
            val invalidFlag = flag == "is_void" || flag == "is_voided" || flag == "is_invalid"
            putExisting("sales_transactions", parentValues, flag, if (parentInvalid && invalidFlag) 1 else 0)
        }
        putExisting("sales_transactions", parentValues, "is_valid", if (parentInvalid) 0 else 1)
        for (column in listOf("cancelled_at", "canceled_at", "voided_at", "invalidated_at", "reversed_at")) {
            putExisting("sales_transactions", parentValues, column, "")
        }
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
            putExisting("fuel_sales", childValues, "delivery_location", location)
            putExisting("fuel_sales", childValues, "cash_customer_name", "")
            putExisting("fuel_sales", childValues, "delivery_fee_display_total", quantity * 500.0 + 250.0)
            putExisting("fuel_sales", childValues, "status", if (parentInvalid) "cancelled" else "completed")
            for (flag in listOf("is_cancelled", "is_canceled", "is_void", "is_voided", "is_invalid", "is_reversed")) {
                val invalidFlag = flag == "is_void" || flag == "is_voided" || flag == "is_invalid"
                putExisting("fuel_sales", childValues, flag, if (parentInvalid && invalidFlag) 1 else 0)
            }
            putExisting("fuel_sales", childValues, "is_valid", if (parentInvalid) 0 else 1)
            for (column in listOf("cancelled_at", "canceled_at", "voided_at", "invalidated_at", "reversed_at")) {
                putExisting("fuel_sales", childValues, column, "")
            }
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
