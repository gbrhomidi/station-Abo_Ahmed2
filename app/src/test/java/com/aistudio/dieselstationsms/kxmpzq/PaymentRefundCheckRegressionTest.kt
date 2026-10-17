package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import java.util.Locale
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PaymentRefundCheckRegressionTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        helper = DatabaseHelper.getInstance(context)
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    @Test
    fun creditSaleRefundKeepsPaymentTypeSeparateFromPaymentMethod() {
        val db = helper.writableDatabase
        // This fixture tests the refund's CHECK behavior in isolation; FK coverage
        // is exercised by the existing finance integration tests.
        db.setForeignKeyConstraintsEnabled(false)
        val saleId = db.insertOrThrow("sales_transactions", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("sale_code", "REFUND-CHECK-${UUID.randomUUID().toString().take(8)}")
            put("station_id", 1)
            put("shift_id", 1)
            put("cashier_id", 1)
            put("subtotal", 100.0)
            put("gross_amount", 100.0)
            put("net_amount", 100.0)
            put("payment_method", "credit")
            put("payment_status", "partial")
            put("paid_amount", 60.0)
            put("remaining_amount", 40.0)
        })
        val originalPaymentId = db.insertOrThrow("payments", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("payment_code", "ORIGINAL-${UUID.randomUUID().toString().take(8)}")
            put("station_id", 1)
            put("sale_id", saleId)
            put("payment_type", "cash")
            put("payment_method", "credit")
            put("amount", 60.0)
            put("status", "completed")
            put("is_refund", 0)
            put("is_deleted", 0)
        })

        val refundMethod = DatabaseHelper::class.java.getDeclaredMethod(
            "refundOriginalPayment",
            SQLiteDatabase::class.java,
            java.lang.Long.TYPE,
            java.lang.Integer.TYPE,
            java.lang.Long.TYPE,
            java.lang.Double.TYPE,
            String::class.java
        ).apply { isAccessible = true }
        val result = refundMethod.invoke(helper, db, saleId, 1, 77L, 25.0, "test refund") as Pair<*, *>

        assertTrue("Expected a refund row", (result.first as? Long ?: 0L) > 0L)
        assertEquals(25.0, result.second as Double, 0.000001)
        assertEquals(1, db.rawQuery(
            "SELECT COUNT(*) FROM payments WHERE original_payment_id=? AND is_refund=1",
            arrayOf(originalPaymentId.toString())
        ).use { c -> c.moveToFirst(); c.getInt(0) })
        db.rawQuery(
            "SELECT payment_type, payment_method, station_id FROM payments WHERE original_payment_id=? AND is_refund=1",
            arrayOf(originalPaymentId.toString())
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("cash", c.getString(0))
            assertEquals("credit", c.getString(1))
            assertEquals(1, c.getInt(2))
        }
    }

    @Test
    fun processPaymentRejectsCreditAsASettlementPaymentTypeBeforeWriting() {
        var rejected = false
        try {
            helper.processPayment(1, 10.0, "credit")
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Credit is a sale term, not a valid payments.payment_type", rejected)
        assertEquals(0, helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM payments", null
        ).use { c -> c.moveToFirst(); c.getInt(0) })
    }
}
