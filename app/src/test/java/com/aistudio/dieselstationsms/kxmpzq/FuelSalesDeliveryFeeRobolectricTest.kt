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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.round

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FuelSalesDeliveryFeeRobolectricTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper
    private lateinit var db: SQLiteDatabase
    private var stationId: Int = 0
    private var userId: Long = 0L
    private var fuelTypeId: Long = 0L
    private var pumpId: Long = 0L

    private data class SavedSale(
        val parentSaleId: Long,
        val fuelSaleId: Long,
        val feeResult: JSONObject,
        val unitPrice: Double,
        val grossQuantity: Double,
        val grossAmount: Double,
        val feeAmount: Double,
        val fuelQuantity: Double,
        val fuelAmount: Double,
        val invoiceTotal: Double
    )

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
        pumpId = db.rawQuery(
            "SELECT id FROM pumps WHERE station_id=? AND COALESCE(is_deleted,0)=0 ORDER BY id LIMIT 1",
            arrayOf(stationId.toString())
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        db.insertOrThrow("shifts", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("shift_code", "FEE-SHIFT-" + UUID.randomUUID().toString().take(8))
            put("station_id", stationId)
            put("shift_date", now.substring(0, 10))
            put("shift_type", "full_day")
            put("start_time", now)
            put("cashier_id", userId)
            put("status", "open")
            put("is_deleted", 0)
        })
        DeliveryFeeAccounting.ensureSchema(helper)
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    @Test
    fun exemptModeKeepsFuelAndInvoiceTotalsGrossWithoutFeeJournal() {
        assertMode(
            mode = DeliveryFeeAccounting.EXEMPT,
            fee = 0.0,
            expectedInvoiceExtra = 0.0,
            expectedFuelReduction = 0.0,
            expectedStatus = "exempt",
            expectsServiceRevenue = false
        )
    }

    @Test
    fun addToSalesModeAddsFeeToInvoiceAndPostsServiceRevenue() {
        assertMode(
            mode = DeliveryFeeAccounting.ADD_TO_SALES,
            fee = 100.0,
            expectedInvoiceExtra = 100.0,
            expectedFuelReduction = 0.0,
            expectedStatus = "posted",
            expectsServiceRevenue = true
        )
    }

    @Test
    fun driverModeAddsFeeToInvoiceButKeepsItPendingForDriverSettlement() {
        assertMode(
            mode = DeliveryFeeAccounting.DRIVER,
            fee = 100.0,
            expectedInvoiceExtra = 100.0,
            expectedFuelReduction = 0.0,
            expectedStatus = "pending_driver",
            expectsServiceRevenue = false
        )
    }

    @Test
    fun deductFromSalesModeReducesFuelQuantityOnceAndPostsServiceRevenue() {
        assertMode(
            mode = DeliveryFeeAccounting.DEDUCT_FROM_SALES,
            fee = 100.0,
            expectedInvoiceExtra = 0.0,
            expectedFuelReduction = 100.0,
            expectedStatus = "posted",
            expectsServiceRevenue = true
        )
    }

    @Test
    fun emptyLocationLookupRejectsBlankLocationAndAcceptsManuallyEnteredLocation() {
        val lookup = JSONObject(DeliveryLookupBridgeContract.savedLocations(helper, stationId))
        assertTrue("Location lookup should succeed with an empty result", lookup.getBoolean("success"))
        assertEquals(0, lookup.getJSONArray("data").length())

        val before = scalarLong("SELECT COUNT(*) FROM sales_transactions WHERE station_id=?", arrayOf(stationId.toString()))
        val noLocationInput = buildFuelSaleInput(DeliveryFeeAccounting.ADD_TO_SALES, 100.0, "")
        db.beginTransaction()
        var rejected = false
        try {
            val parentSaleId = helper.addFuelSale(noLocationInput, stationId, userId)
            val fuelSaleId = scalarLong(
                "SELECT id FROM fuel_sales WHERE sale_id=? AND station_id=? ORDER BY id DESC LIMIT 1",
                arrayOf(parentSaleId.toString(), stationId.toString())
            )
            try {
                DeliveryFeeAccounting.persistFuelSaleFee(
                    helper, parentSaleId, stationId, userId, 100.0,
                    DeliveryFeeAccounting.ADD_TO_SALES, "", "", "cash",
                    noLocationInput.optDouble("gross_quantity"), noLocationInput.optDouble("gross_amount"), true
                )
            } catch (error: IllegalArgumentException) {
                rejected = error.message.orEmpty().contains("موقع التوصيل")
            }
            // The outer sale transaction is deliberately not marked successful after rejection.
            assertTrue("SQLite boundary must reject an empty delivery location", rejected)
            assertTrue("The inserted fuel row should be part of the same rollback", fuelSaleId > 0L)
        } finally {
            db.endTransaction()
        }
        assertEquals("Rejected save must roll back the parent sale", before,
            scalarLong("SELECT COUNT(*) FROM sales_transactions WHERE station_id=?", arrayOf(stationId.toString())))

        val saved = createAndPersistSale(DeliveryFeeAccounting.ADD_TO_SALES, 100.0, "موقع جديد أُدخل يدوياً")
        assertTrue(saved.fuelSaleId > 0L)
        val afterManualEntry = DeliveryLookupBridgeContract.savedLocations(helper, stationId)
        val manualLocations = JSONObject(afterManualEntry).getJSONArray("data")
        assertTrue("A manually typed location must be persisted and appear in future lookup results",
            (0 until manualLocations.length()).any { manualLocations.getString(it) == "موقع جديد أُدخل يدوياً" })
    }

    private fun assertMode(
        mode: String,
        fee: Double,
        expectedInvoiceExtra: Double,
        expectedFuelReduction: Double,
        expectedStatus: String,
        expectsServiceRevenue: Boolean
    ) {
        val saved = createAndPersistSale(mode, fee, "موقع اختبار $mode")
        assertEquals(saved.grossAmount + expectedInvoiceExtra, saved.invoiceTotal, 0.01)
        assertEquals(saved.grossAmount + expectedInvoiceExtra, saved.feeResult.getDouble("invoice_total"), 0.01)
        assertEquals(saved.grossQuantity, saved.fuelQuantity + (expectedFuelReduction / saved.unitPrice), 0.000001)
        assertEquals(saved.grossAmount - expectedFuelReduction, saved.fuelAmount, 0.01)

        val parentAmounts = db.rawQuery(
            "SELECT total_amount,net_amount FROM sales_transactions WHERE id=? AND station_id=?",
            arrayOf(saved.parentSaleId.toString(), stationId.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getDouble(0) to cursor.getDouble(1)
        }
        assertEquals(saved.invoiceTotal, parentAmounts.first, 0.01)
        assertEquals(saved.invoiceTotal, parentAmounts.second, 0.01)

        val child = db.rawQuery(
            """SELECT quantity,total_amount,gross_quantity,gross_amount,delivery_service_fee,delivery_fee_mode
               FROM fuel_sales WHERE id=? AND station_id=?""",
            arrayOf(saved.fuelSaleId.toString(), stationId.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            listOf(
                cursor.getDouble(0),
                cursor.getDouble(1),
                cursor.getDouble(2),
                cursor.getDouble(3),
                cursor.getDouble(4)
            ) to cursor.getString(5)
        }
        assertEquals(saved.fuelQuantity, child.first[0], 0.000001)
        assertEquals(saved.fuelAmount, child.first[1], 0.01)
        assertEquals(saved.grossQuantity, child.first[2], 0.000001)
        assertEquals(saved.grossAmount, child.first[3], 0.01)
        assertEquals(saved.feeAmount, child.first[4], 0.01)
        assertEquals(mode, child.second)

        val ledger = db.rawQuery(
            "SELECT amount,mode,status,COALESCE(journal_entry_id,0) FROM delivery_service_fee_ledger WHERE fuel_sale_id=? AND station_id=?",
            arrayOf(saved.fuelSaleId.toString(), stationId.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            listOf(cursor.getDouble(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3))
        }
        assertEquals(saved.feeAmount, ledger[0] as Double, 0.01)
        assertEquals(mode, ledger[1] as String)
        assertEquals(expectedStatus, ledger[2] as String)

        val expectedJournalId = if (expectsServiceRevenue) saved.feeResult.optLong("journal_entry_id", 0L) else 0L
        assertEquals(expectsServiceRevenue, expectedJournalId > 0L)
        assertEquals(expectedJournalId, ledger[3] as Long)

        val serviceRevenue = if (expectedJournalId > 0L) {
            val header = db.rawQuery(
                "SELECT status,total_debit,total_credit FROM journal_entries WHERE id=? AND station_id=?",
                arrayOf(expectedJournalId.toString(), stationId.toString())
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                Triple(cursor.getString(0), cursor.getDouble(1), cursor.getDouble(2))
            }
            assertEquals("posted", header.first)
            assertEquals(saved.feeAmount, header.second, 0.01)
            assertEquals(saved.feeAmount, header.third, 0.01)
            val debit = scalarDouble(
                "SELECT COALESCE(SUM(debit),0) FROM journal_entry_items WHERE journal_entry_id=?",
                arrayOf(expectedJournalId.toString())
            )
            val credit = scalarDouble(
                "SELECT COALESCE(SUM(credit),0) FROM journal_entry_items WHERE journal_entry_id=?",
                arrayOf(expectedJournalId.toString())
            )
            assertEquals(saved.feeAmount, debit, 0.01)
            assertEquals(saved.feeAmount, credit, 0.01)
            scalarDouble(
                """SELECT COALESCE(SUM(ji.credit),0)
                   FROM journal_entry_items ji JOIN accounts a ON a.id=ji.account_id
                   WHERE ji.journal_entry_id=? AND a.account_type='revenue'
                     AND (LOWER(COALESCE(a.account_name,'')) LIKE '%delivery service revenue%'
                       OR COALESCE(a.account_name_ar,'') LIKE '%خدمة التوصيل%')""",
                arrayOf(expectedJournalId.toString())
            )
        } else {
            0.0
        }
        assertEquals(if (expectsServiceRevenue) saved.feeAmount else 0.0, serviceRevenue, 0.01)

        // The list response must preserve the gross basis needed to reopen a deduction sale.
        val page = helper.getFuelSalesPage(JSONObject().put("limit", 100), stationId)
        val rows = page.getJSONArray("rows")
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }
            .first { it.optLong("fuel_sale_id") == saved.fuelSaleId }
        assertEquals(saved.grossQuantity, row.getDouble("gross_quantity"), 0.000001)
        assertEquals(saved.grossAmount, row.getDouble("gross_amount"), 0.01)
        assertEquals(mode, row.getString("delivery_fee_mode"))
        assertEquals(saved.invoiceTotal, row.getDouble("delivery_fee_display_total"), 0.01)

        // A normal sale-revenue journal must still be present; fee posting is additional,
        // and this test deliberately does not alter that financial posting/migration path.
        assertTrue(
            scalarLong(
                "SELECT COUNT(*) FROM journal_entries WHERE station_id=? AND reference_type='sale' AND reference_id=? AND status='posted' AND is_deleted=0",
                arrayOf(stationId.toString(), saved.parentSaleId.toString())
            ) > 0L
        )
    }

    private fun createAndPersistSale(mode: String, requestedFee: Double, location: String): SavedSale {
        val unitPrice = scalarDouble(
            "SELECT default_sale_price FROM fuel_types WHERE id=? AND is_deleted=0",
            arrayOf(fuelTypeId.toString())
        )
        assertTrue("Test fuel must have an active selling price", unitPrice > 0.0)
        val grossQuantity = 10.0
        val grossAmount = round(grossQuantity * unitPrice * 100.0) / 100.0
        val feeAmount = if (mode == DeliveryFeeAccounting.EXEMPT) 0.0 else requestedFee
        val fuelAmount = round((grossAmount - if (mode == DeliveryFeeAccounting.DEDUCT_FROM_SALES) feeAmount else 0.0) * 100.0) / 100.0
        val fuelQuantity = if (mode == DeliveryFeeAccounting.DEDUCT_FROM_SALES) fuelAmount / unitPrice else grossQuantity
        val invoiceTotal = grossAmount + if (mode == DeliveryFeeAccounting.ADD_TO_SALES || mode == DeliveryFeeAccounting.DRIVER) feeAmount else 0.0
        val input = buildFuelSaleInput(mode, feeAmount, location).apply {
            put("quantity", fuelQuantity)
            put("price_per_liter", unitPrice)
            put("total_amount", fuelAmount)
            put("net_amount", fuelAmount)
            put("gross_amount", grossAmount)
            put("gross_quantity", grossQuantity)
            put("delivery_fee_display_total", invoiceTotal)
        }
        val parentSaleId = helper.addFuelSale(input, stationId, userId)
        val feeResult = DeliveryFeeAccounting.persistFuelSaleFee(
            helper = helper,
            fuelSaleId = parentSaleId,
            stationId = stationId,
            userId = userId,
            amount = feeAmount,
            rawMode = mode,
            location = location,
            cashCustomerName = "",
            paymentMethod = "cash",
            originalQuantity = grossQuantity,
            originalAmount = grossAmount,
            schemaAlreadyEnsured = true
        )
        val fuelSaleId = scalarLong(
            "SELECT id FROM fuel_sales WHERE sale_id=? AND station_id=? ORDER BY id DESC LIMIT 1",
            arrayOf(parentSaleId.toString(), stationId.toString())
        )
        return SavedSale(
            parentSaleId, fuelSaleId, feeResult, unitPrice, grossQuantity, grossAmount,
            feeAmount, fuelQuantity, fuelAmount, invoiceTotal
        )
    }

    private fun buildFuelSaleInput(mode: String, fee: Double, location: String): JSONObject {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return JSONObject().apply {
            put("fuel_type_id", fuelTypeId)
            if (pumpId > 0L) put("pump_id", pumpId)
            put("quantity", 10.0)
            put("price_per_liter", scalarDouble(
                "SELECT default_sale_price FROM fuel_types WHERE id=?",
                arrayOf(fuelTypeId.toString())
            ))
            put("payment_method", "cash")
            put("delivery_service_fee", fee)
            put("delivery_fee_mode", mode)
            put("delivery_location", location)
            put("cash_customer_name", "")
            put("sale_date", now.substring(0, 10))
            put("sale_time", now.substring(11))
            put("transaction_time", now)
            put("idempotency_key", "delivery-fee-test-" + UUID.randomUUID())
            put("gross_quantity", 10.0)
            put("gross_amount", 10.0 * scalarDouble(
                "SELECT default_sale_price FROM fuel_types WHERE id=?",
                arrayOf(fuelTypeId.toString())
            ))
        }
    }

    private fun ensureTestUser(): Long {
        val existing = scalarLongOrZero("SELECT id FROM users WHERE is_deleted=0 ORDER BY id LIMIT 1")
        if (existing > 0L) return existing
        return db.insertOrThrow("users", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("username", "fuel-fee-test-" + UUID.randomUUID().toString().take(8))
            put("password_hash", "test-hash")
            put("password_salt", "test-salt")
            put("full_name", "Fuel Fee Test")
            put("full_name_ar", "مستخدم اختبار رسوم الوقود")
            put("role_id", 1L)
            put("station_id", stationId)
            put("company_id", 1L)
            put("status", "active")
            put("is_deleted", 0)
        })
    }

    private fun scalarLong(sql: String, args: Array<String> = emptyArray()): Long =
        db.rawQuery(sql, args).use { cursor ->
            check(cursor.moveToFirst()) { "Expected a row for SQL: $sql" }
            cursor.getLong(0)
        }

    private fun scalarLongOrZero(sql: String): Long =
        db.rawQuery(sql, null).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    private fun scalarDouble(sql: String, args: Array<String> = emptyArray()): Double =
        db.rawQuery(sql, args).use { cursor ->
            check(cursor.moveToFirst()) { "Expected a row for SQL: $sql" }
            cursor.getDouble(0)
        }
}
