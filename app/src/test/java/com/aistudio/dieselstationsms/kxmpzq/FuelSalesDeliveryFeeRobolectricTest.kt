package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.aistudio.dieselstationsms.kxmpzq.sms.DebtDueDateRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
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
    fun legacyDeductionRowsRecoverGrossBasisFromNetFuelAndSavedFee() {
        val saved = createAndPersistSale(DeliveryFeeAccounting.DEDUCT_FROM_SALES, 100.0, "موقع سجل قديم")
        // Simulate a row created before gross_quantity/gross_amount snapshots were persisted.
        db.update("fuel_sales", ContentValues().apply {
            put("gross_quantity", 0.0)
            put("gross_amount", 0.0)
        }, "id=? AND station_id=?", arrayOf(saved.fuelSaleId.toString(), stationId.toString()))

        val page = helper.getFuelSalesPage(JSONObject().put("limit", 100), stationId)
        val rows = page.getJSONArray("rows")
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }
            .first { it.optLong("fuel_sale_id") == saved.fuelSaleId }

        assertEquals("Missing gross_quantity must be recovered from net litres plus fee litres",
            saved.grossQuantity, row.getDouble("gross_quantity"), 0.000001)
        assertEquals("Missing gross_amount must be recovered from net fuel value plus fee",
            saved.grossAmount, row.getDouble("gross_amount"), 0.01)
    }

    @Test
    fun blankDeliveryLocationIsRejectedAndSaleRollsBackInSqlite() {
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
            assertTrue("SQLite boundary must reject an empty delivery location", rejected)
            assertTrue("The inserted fuel row should be part of the same rollback", fuelSaleId > 0L)
        } finally {
            // Deliberately do not mark this transaction successful after validation rejects the fee.
            db.endTransaction()
        }
        assertEquals("Rejected save must roll back the parent sale", before,
            scalarLong("SELECT COUNT(*) FROM sales_transactions WHERE station_id=?", arrayOf(stationId.toString())))
    }

    @Test
    fun creditFuelSaleRollsBackInvoiceFuelStockShiftBalanceAndIdempotencyWhenDueSnapshotFails() {
        val customerId = ensureTestCustomer()
        val beforeSales = scalarLong("SELECT COUNT(*) FROM sales_transactions WHERE station_id=?", arrayOf(stationId.toString()))
        val beforeFuelRows = scalarLong("SELECT COUNT(*) FROM fuel_sales WHERE station_id=?", arrayOf(stationId.toString()))
        val beforeShift = db.rawQuery(
            "SELECT total_sales,total_fuel_liters FROM shifts WHERE station_id=? AND status='open' ORDER BY id DESC LIMIT 1",
            arrayOf(stationId.toString())
        ).use { assertTrue(it.moveToFirst()); it.getDouble(0) to it.getDouble(1) }
        val tankId = if (pumpId > 0L) scalarLong("SELECT tank_id FROM pumps WHERE id=?", arrayOf(pumpId.toString())) else 0L
        val beforeTank = if (tankId > 0L) scalarDouble("SELECT current_quantity FROM tanks WHERE id=?", arrayOf(tankId.toString())) else 0.0
        val idempotencyKey = "fuel-snapshot-failure-${UUID.randomUUID()}"
        val input = buildFuelSaleInput(
            DeliveryFeeAccounting.EXEMPT, 0.0, "",
            paymentMethod = "credit", customerId = customerId
        ).put("idempotency_key", idempotencyKey)
        db.execSQL("""
            CREATE TRIGGER reject_fuel_due_snapshot
            BEFORE UPDATE OF payment_terms_id_at_sale ON sales_transactions
            WHEN NEW.payment_terms_id_at_sale IS NOT NULL AND OLD.payment_terms_id_at_sale IS NULL
            BEGIN SELECT RAISE(ABORT, 'forced fuel payment-term snapshot failure'); END
        """.trimIndent())
        var failed = false
        try {
            helper.addFuelSale(input, stationId, userId)
        } catch (_: Exception) {
            failed = true
        } finally {
            db.execSQL("DROP TRIGGER IF EXISTS reject_fuel_due_snapshot")
        }

        assertTrue("The injected snapshot failure must abort the fuel invoice save", failed)
        assertEquals(beforeSales, scalarLong("SELECT COUNT(*) FROM sales_transactions WHERE station_id=?", arrayOf(stationId.toString())))
        assertEquals(beforeFuelRows, scalarLong("SELECT COUNT(*) FROM fuel_sales WHERE station_id=?", arrayOf(stationId.toString())))
        assertEquals(0L, scalarLong(
            "SELECT COUNT(*) FROM financial_idempotency_keys WHERE station_id=? AND idempotency_key=?",
            arrayOf(stationId.toString(), idempotencyKey)
        ))
        assertEquals(0.0, scalarDouble("SELECT current_balance FROM parties WHERE id=?", arrayOf(customerId.toString())), 0.0001)
        val afterShift = db.rawQuery(
            "SELECT total_sales,total_fuel_liters FROM shifts WHERE station_id=? AND status='open' ORDER BY id DESC LIMIT 1",
            arrayOf(stationId.toString())
        ).use { assertTrue(it.moveToFirst()); it.getDouble(0) to it.getDouble(1) }
        assertEquals(beforeShift.first, afterShift.first, 0.0001)
        assertEquals(beforeShift.second, afterShift.second, 0.0001)
        if (tankId > 0L) assertEquals(beforeTank, scalarDouble("SELECT current_quantity FROM tanks WHERE id=?", arrayOf(tankId.toString())), 0.0001)
    }

    @Test
    fun v58ToV59MigrationPreservesFuelInvoiceAndReconstructsFromLinkedFuelSaleDateIdempotently() {
        val customerId = ensureTestCustomer()
        val input = buildFuelSaleInput(
            DeliveryFeeAccounting.EXEMPT, 0.0, "",
            paymentMethod = "credit", customerId = customerId
        )
        val parentSaleId = helper.addFuelSale(input, stationId, userId)
        val fuelSaleId = scalarLong(
            "SELECT id FROM fuel_sales WHERE sale_id=? AND station_id=? ORDER BY id DESC LIMIT 1",
            arrayOf(parentSaleId.toString(), stationId.toString())
        )
        val historicalFuelDate = "2026-10-05"
        db.execSQL("DROP TRIGGER IF EXISTS trg_debt_due_date_sales_insert_v4")
        db.execSQL("DROP TRIGGER IF EXISTS trg_debt_due_date_sales_update_v4")
        db.update("fuel_sales", ContentValues().apply { put("sale_date", historicalFuelDate) }, "id=?", arrayOf(fuelSaleId.toString()))
        db.update("sales_transactions", ContentValues().apply {
            put("payment_terms_source_at_sale", "explicit_text")
            put("payment_terms_at_sale", "صافي 7 أيام")
            put("payment_terms_days_at_sale", 7)
            putNull("due_date_basis")
            putNull("due_date")
            putNull("due_date_source")
        }, "id=?", arrayOf(parentSaleId.toString()))
        val preservedAmount = scalarDouble(
            "SELECT net_amount FROM sales_transactions WHERE id=?", arrayOf(parentSaleId.toString())
        )

        db.execSQL("ALTER TABLE sales_transactions DROP COLUMN transaction_date")
        db.version = 58
        DatabaseHelper.closeInstance()
        helper = DatabaseHelper.getInstance(context)
        db = helper.writableDatabase

        assertEquals(59, db.version)
        val migrated = db.rawQuery(
            "SELECT transaction_date,due_date_basis,due_date,due_date_source,net_amount FROM sales_transactions WHERE id=? AND station_id=?",
            arrayOf(parentSaleId.toString(), stationId.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getDouble(4))
        }
        assertEquals(historicalFuelDate, migrated[0])
        assertEquals(historicalFuelDate, migrated[1])
        assertEquals("2026-10-12", migrated[2])
        assertEquals("historical_snapshot_reconstruction", migrated[3])
        assertEquals(preservedAmount, migrated[4] as Double, 0.0001)
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM fuel_sales WHERE id=? AND sale_id=?", arrayOf(fuelSaleId.toString(), parentSaleId.toString())))

        DebtDueDateRepository.ensureSchema(helper)
        DebtDueDateRepository.ensureSchema(helper)
        val transactionDateColumns = db.rawQuery("PRAGMA table_info(sales_transactions)", null).use { cursor ->
            var count = 0
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) if (cursor.getString(nameColumn) == "transaction_date") count++
            count
        }
        assertEquals("Reapplying V59 schema setup must not duplicate the additive column", 1, transactionDateColumns)
        assertEquals("ok", db.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst(); it.getString(0) })
    }

    @Test
    fun emptySavedLocationLookupAllowsManualLocationToPersistInSqlite() {
        val lookup = JSONObject(DeliveryLookupBridgeContract.savedLocations(helper, stationId))
        assertTrue("Location lookup should succeed with an empty result", lookup.getBoolean("success"))
        assertEquals("There are no saved delivery locations in this fixture", 0, lookup.getJSONArray("data").length())

        val manuallyEnteredLocation = "موقع جديد أُدخل يدوياً"
        val saved = createAndPersistSale(DeliveryFeeAccounting.ADD_TO_SALES, 100.0, manuallyEnteredLocation)
        assertTrue(saved.fuelSaleId > 0L)

        val afterManualEntry = DeliveryLookupBridgeContract.savedLocations(helper, stationId)
        val manualLocations = JSONObject(afterManualEntry).getJSONArray("data")
        assertTrue("A manually typed location must be persisted and appear in future lookup results",
            (0 until manualLocations.length()).any { manualLocations.getString(it) == manuallyEnteredLocation })
    }

    @Test
    fun actualExecuteFuelSalesAsyncCallbackPersistsDeliverySaleInRealSqlite() {
        // Use a real Activity, the production WebAppInterface, this test's SQLite helper,
        // and a real Robolectric WebView. ShadowWebView records the exact JavaScript callback
        // sent by executeFuelSalesAsync without replacing the native dispatcher or database.
        val harness = createNativeAsyncBridgeHarness()
        val bridge = harness.first
        val webView = harness.second

        val emptyLocations = invokeFuelSalesAsyncAndAwait(
            bridge,
            webView,
            "locations-before-" + UUID.randomUUID().toString().take(8),
            "locations",
            JSONArray()
        )
        assertTrue("The native locations callback must succeed: $emptyLocations",
            emptyLocations.optBoolean("success"))
        assertEquals("The fixture starts with no saved delivery locations",
            0, emptyLocations.getJSONArray("data").length())
        val location = "موقع اختبار الجسر الأصلي-" + UUID.randomUUID().toString().take(8)
        val input = buildFuelSaleInput(
            DeliveryFeeAccounting.ADD_TO_SALES,
            100.0,
            location
        )

        // Exercise the production asynchronous allowlisted dispatcher. The helper waits
        // until the actual native callback script reaches WebView.evaluateJavascript and
        // decodes the JSON response that the page callback receives.
        val nativeResponse = invokeFuelSalesAsyncAndAwait(
            bridge,
            webView,
            "manual-location-save-" + UUID.randomUUID().toString().take(8),
            "save",
            JSONArray().put(input.toString())
        )
        assertTrue(
            "The real Android bridge should return success: $nativeResponse",
            nativeResponse.optBoolean("success")
        )
        val parentSaleId = nativeResponse.getLong("id")
        assertTrue(parentSaleId > 0L)

        val grossQuantity = input.getDouble("quantity")
        val unitPrice = input.getDouble("price_per_liter")
        val grossAmount = round(grossQuantity * unitPrice * 100.0) / 100.0
        val invoiceTotal = grossAmount + 100.0

        assertEquals(grossQuantity, nativeResponse.getDouble("gross_quantity"), 0.000001)
        assertEquals(grossAmount, nativeResponse.getDouble("gross_amount"), 0.01)
        assertEquals(
            "total_amount remains the fuel-only amount in the native bridge response",
            grossAmount,
            nativeResponse.getDouble("total_amount"),
            0.01
        )
        assertEquals(invoiceTotal, nativeResponse.getDouble("invoice_total"), 0.01)
        assertEquals(invoiceTotal, nativeResponse.getDouble("delivery_fee_display_total"), 0.01)

        val parentAmounts = db.rawQuery(
            "SELECT gross_amount,net_amount FROM sales_transactions WHERE id=? AND station_id=?",
            arrayOf(parentSaleId.toString(), stationId.toString())
        ).use { cursor ->
            assertTrue("The parent sale must be committed by the Android bridge", cursor.moveToFirst())
            cursor.getDouble(0) to cursor.getDouble(1)
        }
        assertEquals(grossAmount, parentAmounts.first, 0.01)
        assertEquals(invoiceTotal, parentAmounts.second, 0.01)

        val child = db.rawQuery(
            """SELECT id,quantity,total_amount,gross_quantity,gross_amount,
                      delivery_service_fee,delivery_fee_mode
               FROM fuel_sales
               WHERE sale_id=? AND station_id=?
               ORDER BY id DESC LIMIT 1""",
            arrayOf(parentSaleId.toString(), stationId.toString())
        ).use { cursor ->
            assertTrue("The fuel row must be committed by the Android bridge", cursor.moveToFirst())
            JSONObject().apply {
                put("id", cursor.getLong(0))
                put("quantity", cursor.getDouble(1))
                put("total_amount", cursor.getDouble(2))
                put("gross_quantity", cursor.getDouble(3))
                put("gross_amount", cursor.getDouble(4))
                put("delivery_service_fee", cursor.getDouble(5))
                put("delivery_fee_mode", cursor.getString(6))
            }
        }
        val fuelSaleId = child.getLong("id")
        assertEquals(grossQuantity, child.getDouble("quantity"), 0.000001)
        assertEquals(grossAmount, child.getDouble("total_amount"), 0.01)
        assertEquals(grossQuantity, child.getDouble("gross_quantity"), 0.000001)
        assertEquals(grossAmount, child.getDouble("gross_amount"), 0.01)
        assertEquals(100.0, child.getDouble("delivery_service_fee"), 0.01)
        assertEquals(DeliveryFeeAccounting.ADD_TO_SALES, child.getString("delivery_fee_mode"))

        var feeJournalId = 0L
        db.rawQuery(
            """SELECT amount,mode,status,COALESCE(journal_entry_id,0)
               FROM delivery_service_fee_ledger
               WHERE fuel_sale_id=? AND station_id=?""",
            arrayOf(fuelSaleId.toString(), stationId.toString())
        ).use { cursor ->
            assertTrue("The native bridge must persist a delivery-fee ledger row", cursor.moveToFirst())
            assertEquals(100.0, cursor.getDouble(0), 0.01)
            assertEquals(DeliveryFeeAccounting.ADD_TO_SALES, cursor.getString(1))
            assertEquals("posted", cursor.getString(2))
            feeJournalId = cursor.getLong(3)
        }
        assertTrue("The fee ledger must link to its posted service-revenue journal", feeJournalId > 0L)

        db.rawQuery(
            "SELECT status,total_debit,total_credit FROM journal_entries WHERE id=? AND station_id=?",
            arrayOf(feeJournalId.toString(), stationId.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("posted", cursor.getString(0))
            assertEquals(100.0, cursor.getDouble(1), 0.01)
            assertEquals(100.0, cursor.getDouble(2), 0.01)
        }
        assertEquals(
            100.0,
            scalarDouble(
                "SELECT COALESCE(SUM(debit),0) FROM journal_entry_items WHERE journal_entry_id=?",
                arrayOf(feeJournalId.toString())
            ),
            0.01
        )
        assertEquals(
            100.0,
            scalarDouble(
                "SELECT COALESCE(SUM(credit),0) FROM journal_entry_items WHERE journal_entry_id=?",
                arrayOf(feeJournalId.toString())
            ),
            0.01
        )

        // The standard sale journal remains present alongside the fee journal.
        assertTrue(
            scalarLong(
                """SELECT COUNT(*) FROM journal_entries
                   WHERE station_id=? AND reference_type='sale' AND reference_id=?
                     AND status='posted' AND is_deleted=0""",
                arrayOf(stationId.toString(), parentSaleId.toString())
            ) > 0L
        )

        val locationLookup = invokeFuelSalesAsyncAndAwait(
            bridge,
            webView,
            "locations-after-" + UUID.randomUUID().toString().take(8),
            "locations",
            JSONArray()
        )
        assertTrue("The follow-up native locations callback must succeed: $locationLookup",
            locationLookup.getBoolean("success"))
        assertTrue(
            "The manually supplied location must be persisted for subsequent bridge lookups",
            (0 until locationLookup.getJSONArray("data").length()).any {
                locationLookup.getJSONArray("data").getString(it) == location
            }
        )
    }

    @Test
    fun actualExecuteFuelSalesAsyncCallbackRejectsBlankDeliveryLocationWithoutSqliteWrites() {
        val harness = createNativeAsyncBridgeHarness()
        val bridge = harness.first
        val webView = harness.second

        val beforeSales = scalarLong(
            "SELECT COUNT(*) FROM sales_transactions WHERE station_id=?",
            arrayOf(stationId.toString())
        )
        val beforeFuelRows = scalarLong(
            "SELECT COUNT(*) FROM fuel_sales WHERE station_id=?",
            arrayOf(stationId.toString())
        )
        val beforeFeeRows = scalarLong(
            "SELECT COUNT(*) FROM delivery_service_fee_ledger WHERE station_id=?",
            arrayOf(stationId.toString())
        )
        val beforeJournals = scalarLong(
            "SELECT COUNT(*) FROM journal_entries WHERE station_id=?",
            arrayOf(stationId.toString())
        )

        val invalidInput = buildFuelSaleInput(
            DeliveryFeeAccounting.ADD_TO_SALES,
            100.0,
            ""
        )
        val rejected = invokeFuelSalesAsyncAndAwait(
            bridge,
            webView,
            "blank-location-save-" + UUID.randomUUID().toString().take(8),
            "save",
            JSONArray().put(invalidInput.toString())
        )

        assertFalse("The callback must report a failed save for an empty delivery location: $rejected",
            rejected.optBoolean("success"))
        assertTrue("The callback must explain that the delivery location is required: $rejected",
            rejected.optString("error").contains("موقع التوصيل"))

        assertEquals("A rejected async save must not create a parent sale", beforeSales,
            scalarLong("SELECT COUNT(*) FROM sales_transactions WHERE station_id=?",
                arrayOf(stationId.toString())))
        assertEquals("A rejected async save must not create a fuel row", beforeFuelRows,
            scalarLong("SELECT COUNT(*) FROM fuel_sales WHERE station_id=?",
                arrayOf(stationId.toString())))
        assertEquals("A rejected async save must not create a delivery-fee ledger row", beforeFeeRows,
            scalarLong("SELECT COUNT(*) FROM delivery_service_fee_ledger WHERE station_id=?",
                arrayOf(stationId.toString())))
        assertEquals("A rejected async save must not post any journal", beforeJournals,
            scalarLong("SELECT COUNT(*) FROM journal_entries WHERE station_id=?",
                arrayOf(stationId.toString())))
    }

    @Test
    fun actualExecuteFuelSalesAsyncUpdatePersistsLocationAndRefreshesScopedSuggestions() {
        val harness = createNativeAsyncBridgeHarness()
        val bridge = harness.first
        val webView = harness.second

        val scope = invokeFuelSalesAsyncAndAwait(
            bridge, webView, "scope-before-update-" + UUID.randomUUID().toString().take(8),
            "scope", JSONArray()
        )
        assertTrue("The production scope operation must succeed: $scope", scope.optBoolean("success"))
        assertEquals(userId, scope.optLong("user_id"))
        assertEquals(stationId.toLong(), scope.optLong("station_id"))

        val originalLocation = "موقع أصلي للتعديل-" + UUID.randomUUID().toString().take(8)
        val input = buildFuelSaleInput(DeliveryFeeAccounting.ADD_TO_SALES, 100.0, originalLocation)
        val saved = invokeFuelSalesAsyncAndAwait(
            bridge, webView, "save-before-update-" + UUID.randomUUID().toString().take(8),
            "save", JSONArray().put(input.toString())
        )
        assertTrue("The initial sale must be saved by the real bridge: $saved", saved.optBoolean("success"))
        val parentSaleId = saved.optLong("sale_id", 0L)
        assertTrue("The native save response must return a real parent sale id", parentSaleId > 0L)
        val fuelSaleId = scalarLong(
            "SELECT id FROM fuel_sales WHERE sale_id=? AND station_id=? ORDER BY id DESC LIMIT 1",
            arrayOf(parentSaleId.toString(), stationId.toString())
        )
        assertTrue(fuelSaleId > 0L)
        assertEquals(originalLocation, db.rawQuery(
            "SELECT delivery_location FROM fuel_sales WHERE id=? AND station_id=?",
            arrayOf(fuelSaleId.toString(), stationId.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        })

        val journalCountBefore = scalarLong(
            "SELECT COUNT(*) FROM journal_entries WHERE station_id=?",
            arrayOf(stationId.toString())
        )
        val feeLedgerCountBefore = scalarLong(
            "SELECT COUNT(*) FROM delivery_service_fee_ledger WHERE fuel_sale_id=? AND station_id=?",
            arrayOf(fuelSaleId.toString(), stationId.toString())
        )
        val editedLocation = "موقع معدل عبر Android Bridge-" + UUID.randomUUID().toString().take(8)
        val updateResponse = invokeFuelSalesAsyncAndAwait(
            bridge, webView, "real-update-" + UUID.randomUUID().toString().take(8),
            "update", JSONArray().put(fuelSaleId).put(JSONObject().put("delivery_location", editedLocation).toString())
        )
        assertTrue("The production update operation must commit successfully: $updateResponse",
            updateResponse.optBoolean("success"))

        assertEquals("SQLite must contain the newly committed delivery location", editedLocation,
            db.rawQuery(
                "SELECT delivery_location FROM fuel_sales WHERE id=? AND station_id=?",
                arrayOf(fuelSaleId.toString(), stationId.toString())
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            })

        val lookup = invokeFuelSalesAsyncAndAwait(
            bridge, webView, "locations-after-update-" + UUID.randomUUID().toString().take(8),
            "locations", JSONArray()
        )
        assertTrue("The locations read after the update must succeed: $lookup", lookup.optBoolean("success"))
        assertEquals(userId, lookup.optLong("user_id"))
        assertEquals(stationId.toLong(), lookup.optLong("station_id"))
        val updatedSuggestionCount = (0 until lookup.getJSONArray("data").length()).count {
            lookup.getJSONArray("data").getString(it) == editedLocation
        }
        assertEquals("The updated location must be suggested exactly once", 1, updatedSuggestionCount)

        assertEquals("Editing only the delivery location must not create or modify journals",
            journalCountBefore, scalarLong(
                "SELECT COUNT(*) FROM journal_entries WHERE station_id=?",
                arrayOf(stationId.toString())
            ))
        assertEquals("Editing only the delivery location must not duplicate or remove fee ledger rows",
            feeLedgerCountBefore, scalarLong(
                "SELECT COUNT(*) FROM delivery_service_fee_ledger WHERE fuel_sale_id=? AND station_id=?",
                arrayOf(fuelSaleId.toString(), stationId.toString())
            ))
    }

    @Test
    fun actualExecuteFuelSalesAsyncSqliteUpdateFailureDoesNotPublishUnsavedLocation() {
        val harness = createNativeAsyncBridgeHarness()
        val bridge = harness.first
        val webView = harness.second

        val originalLocation = "موقع ثابت قبل فشل الكتابة-" + UUID.randomUUID().toString().take(8)
        val input = buildFuelSaleInput(DeliveryFeeAccounting.ADD_TO_SALES, 100.0, originalLocation)
        val saved = invokeFuelSalesAsyncAndAwait(
            bridge, webView, "save-before-forced-failure-" + UUID.randomUUID().toString().take(8),
            "save", JSONArray().put(input.toString())
        )
        assertTrue("The fixture sale must be committed before inducing the failure: $saved",
            saved.optBoolean("success"))
        val parentSaleId = saved.optLong("sale_id", 0L)
        val fuelSaleId = scalarLong(
            "SELECT id FROM fuel_sales WHERE sale_id=? AND station_id=? ORDER BY id DESC LIMIT 1",
            arrayOf(parentSaleId.toString(), stationId.toString())
        )
        assertTrue(fuelSaleId > 0L)

        val failedLocation = "موقع يجب ألا يظهر بعد فشل SQLite-" + UUID.randomUUID().toString().take(8)
        val triggerName = "test_fail_delivery_location_" + UUID.randomUUID().toString().replace("-", "")
        db.execSQL(
            "CREATE TRIGGER `" + triggerName + "` BEFORE UPDATE OF delivery_location ON fuel_sales " +
                "WHEN OLD.id=" + fuelSaleId + " BEGIN SELECT RAISE(ABORT, 'forced SQLite delivery-location write failure'); END"
        )
        try {
            val failedUpdate = invokeFuelSalesAsyncAndAwait(
                bridge, webView, "forced-sqlite-update-failure-" + UUID.randomUUID().toString().take(8),
                "update", JSONArray().put(fuelSaleId).put(JSONObject().put("delivery_location", failedLocation).toString())
            )
            assertFalse("The native bridge must report the SQLite-triggered write failure: $failedUpdate",
                failedUpdate.optBoolean("success"))

            val storedLocation = db.rawQuery(
                "SELECT delivery_location FROM fuel_sales WHERE id=? AND station_id=?",
                arrayOf(fuelSaleId.toString(), stationId.toString())
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            }
            assertEquals("The failed SQLite UPDATE must leave the committed value untouched",
                originalLocation, storedLocation)

            val lookup = invokeFuelSalesAsyncAndAwait(
                bridge, webView, "locations-after-sqlite-failure-" + UUID.randomUUID().toString().take(8),
                "locations", JSONArray()
            )
            assertTrue("The post-failure locations lookup must still succeed: $lookup",
                lookup.optBoolean("success"))
            val values = (0 until lookup.getJSONArray("data").length()).map {
                lookup.getJSONArray("data").getString(it)
            }
            assertTrue("The original committed location must remain available", values.contains(originalLocation))
            assertFalse("A location rejected by SQLite must never be offered as saved", values.contains(failedLocation))
        } finally {
            db.execSQL("DROP TRIGGER IF EXISTS `" + triggerName + "`")
        }
    }

    private fun createNativeAsyncBridgeHarness(): Pair<MainActivity.WebAppInterface, WebView> {
        // Do not call Activity.onCreate here: it initializes unrelated device security services.
        // The bridge's own lifecycleScope is still the real production coroutine scope.
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val authPrefs = context.getSharedPreferences(
            "fuel-sales-async-bridge-test-" + UUID.randomUUID().toString(),
            Context.MODE_PRIVATE
        )
        authPrefs.edit().putLong("user_id", userId).commit()
        activity.sharedPrefs = authPrefs

        MainActivity::class.java.getDeclaredField("dbHelper").apply {
            isAccessible = true
            set(activity, helper)
        }
        MainActivity::class.java.getDeclaredField("geminiHelper").apply {
            isAccessible = true
            set(activity, GeminiAIHelper(context))
        }

        val webView = WebView(context)
        MainActivity::class.java.getDeclaredField("webView").apply {
            isAccessible = true
            set(activity, webView)
        }

        return activity.WebAppInterface(context, activity) to webView
    }

    private fun invokeFuelSalesAsyncAndAwait(
        bridge: MainActivity.WebAppInterface,
        webView: WebView,
        requestId: String,
        operation: String,
        args: JSONArray
    ): JSONObject {
        val acknowledgement = JSONObject(
            bridge.executeFuelSalesAsync(requestId, operation, args.toString())
        )
        assertTrue("The real async dispatcher must acknowledge the request: $acknowledgement",
            acknowledgement.optBoolean("success"))
        assertTrue("The real async dispatcher must queue the request: $acknowledgement",
            acknowledgement.optBoolean("queued"))

        return awaitFuelSalesAsyncCallback(webView, requestId)
    }

    private fun awaitFuelSalesAsyncCallback(
        webView: WebView,
        requestId: String
    ): JSONObject {
        val requestPrefix = "window.__fuelSalesNativeResult(${JSONObject.quote(requestId)},"
        val callbackPattern = Regex(
            Regex.escape(requestPrefix) + """("(?:\\.|[^"\\])*")\);"""
        )
        val deadline = System.currentTimeMillis() + 20_000L

        while (System.currentTimeMillis() < deadline) {
            // executeFuelSalesAsync runs its SQLite work on Dispatchers.IO and posts the
            // completion back to the Activity's main-thread lifecycleScope.
            shadowOf(Looper.getMainLooper()).idle()
            val script = shadowOf(webView).getLastEvaluatedJavascript().orEmpty()
            val callback = callbackPattern.find(script)
            if (callback != null) {
                val encodedResponse = callback.groupValues[1]
                val rawResponse = JSONArray("[$encodedResponse]").getString(0)
                return JSONObject(rawResponse)
            }
            Thread.sleep(10L)
        }

        val lastScript = shadowOf(webView).getLastEvaluatedJavascript()
        throw AssertionError(
            "Timed out waiting for executeFuelSalesAsync callback for request $requestId. " +
                "Last WebView script: $lastScript"
        )
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
            "SELECT gross_amount,net_amount FROM sales_transactions WHERE id=? AND station_id=?",
            arrayOf(saved.parentSaleId.toString(), stationId.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getDouble(0) to cursor.getDouble(1)
        }
        assertEquals("Parent gross_amount must retain the original fuel basis", saved.grossAmount, parentAmounts.first, 0.01)
        assertEquals("Parent net_amount is the customer invoice total", saved.invoiceTotal, parentAmounts.second, 0.01)

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
                   WHERE ji.journal_entry_id=?
                     AND LOWER(COALESCE(a.account_type,'')) IN ('income','revenue','revenues','إيراد','إيرادات')""",
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


    @Test
    fun fuelReportPaginationReturnsDisjointSqlitePagesAndClampsOffsets() {
        val first = createAndPersistSale(DeliveryFeeAccounting.EXEMPT, 0.0, "موقع تقرير الوقود 1")
        val second = createAndPersistSale(DeliveryFeeAccounting.EXEMPT, 0.0, "موقع تقرير الوقود 2")
        val third = createAndPersistSale(DeliveryFeeAccounting.EXEMPT, 0.0, "موقع تقرير الوقود 3")

        val base = JSONObject().put("report_type", "sales").put("limit", 1)
        val pageOne = helper.getFuelReportPage(JSONObject(base.toString()).put("offset", 0), stationId)
        val pageTwo = helper.getFuelReportPage(JSONObject(base.toString()).put("offset", 1), stationId)
        val pageThree = helper.getFuelReportPage(JSONObject(base.toString()).put("offset", 2), stationId)
        val beyondLastPage = helper.getFuelReportPage(JSONObject(base.toString()).put("offset", 9999), stationId)

        assertEquals(3, pageOne.getInt("total_count"))
        assertEquals(3, pageOne.getInt("total_pages"))
        assertEquals(1, pageOne.getInt("page"))
        assertEquals(1, pageOne.getInt("count"))
        assertTrue(pageOne.getBoolean("has_next"))
        assertFalse(pageOne.getBoolean("has_previous"))

        assertEquals(2, pageTwo.getInt("page"))
        assertEquals(1, pageTwo.getJSONArray("rows").length())
        assertTrue(pageTwo.getBoolean("has_previous"))
        assertTrue(pageTwo.getBoolean("has_next"))

        assertEquals(3, pageThree.getInt("page"))
        assertEquals(1, pageThree.getJSONArray("rows").length())
        assertTrue(pageThree.getBoolean("has_previous"))
        assertFalse(pageThree.getBoolean("has_next"))

        val pageIds = listOf(pageOne, pageTwo, pageThree).map {
            it.getJSONArray("rows").getJSONObject(0).getLong("id")
        }
        assertEquals(3, pageIds.distinct().size)
        assertEquals(
            setOf(first.parentSaleId, second.parentSaleId, third.parentSaleId),
            pageIds.toSet()
        )
        assertEquals(3, beyondLastPage.getInt("page"))
        assertEquals(1, beyondLastPage.getJSONArray("rows").length())
        assertEquals(
            pageThree.getJSONArray("rows").getJSONObject(0).getLong("id"),
            beyondLastPage.getJSONArray("rows").getJSONObject(0).getLong("id")
        )
    }

    @Test
    fun fuelMovementReportCountsFuelSalesAndAppliesSearchAndStationScope() {
        val target = createAndPersistSale(DeliveryFeeAccounting.EXEMPT, 0.0, "موقع تقرير حركات الوقود")
        createAndPersistSale(DeliveryFeeAccounting.EXEMPT, 0.0, "موقع تقرير حركات الوقود 2")
        createAndPersistSale(DeliveryFeeAccounting.EXEMPT, 0.0, "موقع تقرير حركات الوقود 3")

        val movement = helper.getFuelReportPage(
            JSONObject().put("report_type", "movement").put("limit", 10),
            stationId
        )
        assertEquals(3, movement.getInt("total_count"))
        assertEquals(3, movement.getJSONArray("rows").length())
        assertTrue((0 until movement.getJSONArray("rows").length()).all {
            movement.getJSONArray("rows").getJSONObject(it).getString("type") == "sale"
        })

        val targetCode = db.rawQuery(
            "SELECT sale_code FROM sales_transactions WHERE id=? AND station_id=?",
            arrayOf(target.parentSaleId.toString(), stationId.toString())
        ).use {
            check(it.moveToFirst())
            it.getString(0)
        }
        val searched = helper.getFuelReportPage(
            JSONObject()
                .put("report_type", "sales")
                .put("search", targetCode)
                .put("limit", 10),
            stationId
        )
        assertEquals(1, searched.getInt("total_count"))
        assertEquals(1, searched.getJSONArray("rows").length())
        assertEquals(target.parentSaleId, searched.getJSONArray("rows").getJSONObject(0).getLong("id"))

        val isolated = helper.getFuelReportPage(
            JSONObject().put("report_type", "sales").put("limit", 10),
            stationId + 100000
        )
        assertEquals(0, isolated.getInt("total_count"))
        assertEquals(0, isolated.getInt("total_pages"))
        assertEquals(0, isolated.getJSONArray("rows").length())
    }

    @Test
    fun sequentialPartialFuelReturnsRestoreRemainingQuantityAndPopulateDashboard() {
        assertTrue("The return test requires an active pump connected to a station tank", pumpId > 0L)
        val tankId = scalarLong(
            "SELECT tank_id FROM pumps WHERE id=? AND station_id=? AND is_deleted=0 AND status='active'",
            arrayOf(pumpId.toString(), stationId.toString())
        )
        val tankBeforeSale = scalarDouble(
            "SELECT current_quantity FROM tanks WHERE id=? AND station_id=? AND is_deleted=0",
            arrayOf(tankId.toString(), stationId.toString())
        )
        val customerId = ensureTestCustomer()
        val saved = createAndPersistSale(
            DeliveryFeeAccounting.EXEMPT, 0.0,
            "موقع اختبار مرتجعات الوقود " + UUID.randomUUID().toString().take(8),
            paymentMethod = "credit",
            customerId = customerId
        )
        val originalQuantity = saved.grossQuantity
        val originalFuelAmount = saved.fuelAmount
        assertEquals(10.0, originalQuantity, 0.000001)
        assertEquals(originalQuantity, saved.fuelQuantity, 0.000001)

        fun postReturn(quantity: Double, label: String): JSONObject =
            helper.processFuelSaleAdjustment(JSONObject().apply {
                put("station_id", stationId)
                put("created_by", userId)
                put("sale_id", saved.parentSaleId)
                put("fuel_sale_id", saved.fuelSaleId)
                put("adjustment_type", "return")
                put("quantity", quantity)
                put("reason", label)
                put("idempotency_key", "fuel-return-test-" + UUID.randomUUID())
            })

        fun fuelRow(): JSONObject {
            val rows = helper.getFuelSalesPage(JSONObject().put("limit", 100), stationId)
                .getJSONArray("rows")
            return (0 until rows.length()).map { rows.getJSONObject(it) }
                .first { it.optLong("sale_id") == saved.parentSaleId }
        }

        val first = postReturn(4.0, "إرجاع جزئي أول")
        assertTrue("The first partial return must be committed: $first", first.optBoolean("success"))
        var row = fuelRow()
        assertEquals(6.0, row.getDouble("quantity"), 0.000001)
        assertEquals(10.0, row.getDouble("original_quantity"), 0.000001)
        assertEquals(4.0, row.getDouble("returned_quantity"), 0.000001)
        assertEquals(originalFuelAmount * 0.6, row.getDouble("total_amount"), 0.01)
        assertEquals(originalFuelAmount, row.getDouble("original_total_amount"), 0.01)

        // The ledger already includes the previous return; the mutation must use
        // fuel_sales.quantity's live remainder, rather than deducting that ledger again.
        val second = postReturn(3.0, "إرجاع جزئي ثان")
        assertTrue("The second partial return against six remaining litres must succeed: $second",
            second.optBoolean("success"))
        row = fuelRow()
        assertEquals(3.0, row.getDouble("quantity"), 0.000001)
        assertEquals(10.0, row.getDouble("original_quantity"), 0.000001)
        assertEquals(7.0, row.getDouble("returned_quantity"), 0.000001)

        // Returning the final three litres is the full return of the original sale.
        val finalReturn = postReturn(3.0, "إرجاع كامل للكمية المتبقية")
        assertTrue("The final full-balance return must succeed: $finalReturn",
            finalReturn.optBoolean("success"))
        row = fuelRow()
        assertEquals(0.0, row.getDouble("quantity"), 0.000001)
        assertEquals(10.0, row.getDouble("original_quantity"), 0.000001)
        assertEquals(10.0, row.getDouble("returned_quantity"), 0.000001)
        assertEquals(0.0, row.getDouble("total_amount"), 0.01)
        assertEquals(originalFuelAmount, row.getDouble("original_total_amount"), 0.01)

        assertEquals(3L, scalarLong(
            """SELECT COUNT(*) FROM fuel_sale_adjustments
               WHERE sale_id=? AND station_id=? AND adjustment_type='return' AND status='posted'""",
            arrayOf(saved.parentSaleId.toString(), stationId.toString())
        ))
        assertEquals(originalQuantity, scalarDouble(
            """SELECT COALESCE(SUM(quantity),0) FROM fuel_sale_adjustments
               WHERE sale_id=? AND station_id=? AND adjustment_type='return' AND status='posted'""",
            arrayOf(saved.parentSaleId.toString(), stationId.toString())
        ), 0.000001)
        assertEquals(tankBeforeSale, scalarDouble(
            "SELECT current_quantity FROM tanks WHERE id=? AND station_id=?",
            arrayOf(tankId.toString(), stationId.toString())
        ), 0.01)
        assertEquals(0.0, scalarDouble(
            "SELECT current_balance FROM parties WHERE id=? AND station_id=?",
            arrayOf(customerId.toString(), stationId.toString())
        ), 0.01)

        val parentState = db.rawQuery(
            "SELECT liters,net_amount,remaining_amount,status FROM sales_transactions WHERE id=? AND station_id=?",
            arrayOf(saved.parentSaleId.toString(), stationId.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            listOf(cursor.getDouble(0), cursor.getDouble(1), cursor.getDouble(2)) to cursor.getString(3)
        }
        assertEquals(0.0, parentState.first[0], 0.000001)
        assertEquals(0.0, parentState.first[1], 0.01)
        assertEquals(0.0, parentState.first[2], 0.01)
        assertEquals("refunded", parentState.second)

        val dashboard = helper.getDashboardStatsForDate(
            stationId, JSONObject(), BusinessDay.currentDate()
        )
        assertEquals("Fuel returns must populate main.html's litre KPI",
            10.0, dashboard.getDouble("returned_fuel_today"), 0.000001)
        assertEquals("Returns must not be counted as fuel damage",
            0.0, dashboard.getDouble("damaged_fuel_today"), 0.000001)

        var overReturnRejected = false
        try {
            postReturn(0.01, "محاولة إرجاع تتجاوز الكمية المتاحة")
        } catch (_: IllegalArgumentException) {
            overReturnRejected = true
        }
        assertTrue("SQLite must reject another return after the complete balance is exhausted",
            overReturnRejected)
        assertEquals(3L, scalarLong(
            """SELECT COUNT(*) FROM fuel_sale_adjustments
               WHERE sale_id=? AND station_id=? AND adjustment_type='return' AND status='posted'""",
            arrayOf(saved.parentSaleId.toString(), stationId.toString())
        ))
    }

    private fun ensureTestCustomer(): Long {
        val partyTypeId = scalarLong(
            """SELECT id FROM party_types
               WHERE type_code IN ('INDIVIDUAL','COMPANY','GOVERNMENT','TRANSPORT','CONTRACTOR')
                 AND is_deleted=0 AND is_active=1
               ORDER BY CASE type_code WHEN 'INDIVIDUAL' THEN 0 WHEN 'COMPANY' THEN 1 ELSE 2 END, id
               LIMIT 1"""
        )
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        return db.insertOrThrow("parties", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("party_code", "FUEL-RETURN-$suffix")
            put("party_type_id", partyTypeId)
            put("station_id", stationId)
            put("legal_name", "Fuel Return Regression $suffix")
            put("commercial_name", "Fuel Return Regression $suffix")
            put("commercial_name_ar", "عميل اختبار المرتجعات $suffix")
            put("credit_limit", 0.0)
            put("current_balance", 0.0)
            put("total_due", 0.0)
            put("is_active", 1)
            put("is_deleted", 0)
            put("created_by", userId)
            put("created_at", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            put("updated_at", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        })
    }

    private fun createAndPersistSale(
        mode: String,
        requestedFee: Double,
        location: String,
        paymentMethod: String = "cash",
        customerId: Long = 0L
    ): SavedSale {
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
        val input = buildFuelSaleInput(mode, feeAmount, location, paymentMethod, customerId).apply {
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
            paymentMethod = paymentMethod,
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

    private fun buildFuelSaleInput(
        mode: String, fee: Double, location: String,
        paymentMethod: String = "cash", customerId: Long = 0L
    ): JSONObject {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return JSONObject().apply {
            put("fuel_type_id", fuelTypeId)
            if (pumpId > 0L) put("pump_id", pumpId)
            put("quantity", 10.0)
            put("price_per_liter", scalarDouble(
                "SELECT default_sale_price FROM fuel_types WHERE id=?",
                arrayOf(fuelTypeId.toString())
            ))
            put("payment_method", paymentMethod)
            if (customerId > 0L) put("customer_id", customerId)
            if (paymentMethod == "credit") {
                put("payment_terms_id", scalarLong(
                    "SELECT id FROM payment_terms WHERE code='NET_7' AND is_active=1 AND (station_id IS NULL OR station_id=?) ORDER BY station_id DESC LIMIT 1",
                    arrayOf(stationId.toString())
                ))
            }
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
