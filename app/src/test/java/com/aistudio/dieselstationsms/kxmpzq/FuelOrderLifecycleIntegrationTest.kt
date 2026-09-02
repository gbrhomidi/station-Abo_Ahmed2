package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aistudio.dieselstationsms.kxmpzq.sms.BankSmsVerificationEngine
import com.aistudio.dieselstationsms.kxmpzq.sms.DriverAssignmentEngine
import com.aistudio.dieselstationsms.kxmpzq.sms.FuelOrderRepository
import com.aistudio.dieselstationsms.kxmpzq.sms.FuelOrderStatus
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
class FuelOrderLifecycleIntegrationTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance(); context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        helper = DatabaseHelper.getInstance(context); helper.writableDatabase
    }

    @After fun tearDown() { DatabaseHelper.closeInstance(); context.deleteDatabase(DatabaseHelper.DATABASE_NAME) }

    @Test fun smsOrderMovesFromQuoteToPaymentDriverAcceptanceAndDelivery() {
        val db = helper.writableDatabase
        val partyType = db.rawQuery("SELECT id FROM party_types WHERE is_deleted = 0 ORDER BY id LIMIT 1", null).use { check(it.moveToFirst()); it.getLong(0) }
        val customerId = db.insertOrThrow("parties", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("party_code", "INT-CUS-${UUID.randomUUID().toString().take(8)}"); put("party_type_id", partyType); put("commercial_name", "عميل دورة SMS"); put("commercial_name_ar", "عميل دورة SMS"); put("credit_limit", 0.0); put("current_balance", 0.0); put("is_active", 1); put("is_deleted", 0)
        })
        val fuelTypeId = db.rawQuery("SELECT id FROM fuel_types WHERE fuel_code = 'diesel' AND is_deleted = 0 LIMIT 1", null).use { check(it.moveToFirst()); it.getLong(0) }
        val driverPartyId = db.insertOrThrow("parties", null, ContentValues().apply { put("uuid", UUID.randomUUID().toString()); put("party_code", "INT-DRV-${UUID.randomUUID().toString().take(8)}"); put("party_type_id", partyType); put("commercial_name", "مالك المركبة"); put("is_active", 1); put("is_deleted", 0) })
        val vehicleId = db.insertOrThrow("vehicles", null, ContentValues().apply { put("uuid", UUID.randomUUID().toString()); put("vehicle_code", "INT-VEH-${UUID.randomUUID().toString().take(8)}"); put("party_id", driverPartyId); put("plate_number", "اختبار-1"); put("status", "active"); put("is_deleted", 0) })
        val driverId = db.insertOrThrow("drivers", null, ContentValues().apply { put("uuid", UUID.randomUUID().toString()); put("driver_code", "INT-DRV-${UUID.randomUUID().toString().take(8)}"); put("vehicle_id", vehicleId); put("full_name", "سائق دورة SMS"); put("full_name_ar", "سائق دورة SMS"); put("phone", "777123456"); put("status", "active"); put("is_deleted", 0) })
        db.insertOrThrow("sms_sender_identities", null, ContentValues().apply { put("sender_phone", "771234567"); put("bank_id", "ALKURAIMI"); put("bank_account_id", "TEST-ACCOUNT"); put("source_type", "BANK"); put("verified", 1); put("active", 1); put("verification_method", "integration-test"); put("created_at", System.currentTimeMillis()); put("updated_at", System.currentTimeMillis()) })

        val repo = FuelOrderRepository(helper)
        val order = repo.createDraft(customerId, "967771234567", fuelTypeId, 500.0, "LITER", 500.0, 10.0, "PREPAID", "بئر شعبان", System.currentTimeMillis() + 3_600_000, "integration-sms-order")
        val quote = repo.createImmutableQuote(order.orderId, "TEST-PRICE-V1")
        assertEquals(FuelOrderStatus.QUOTED, repo.get(order.orderId)!!.status)
        repo.reserve(order.orderId)
        repo.transition(order.orderId, FuelOrderStatus.AWAITING_PAYMENT)

        val payment = BankSmsVerificationEngine(helper).verifyAndMatch("771234567", "الكريمي: مبلغ 500 ريال من: عميل دورة SMS المرجع: ${quote.quoteId}")
        assertTrue(payment.matched); assertEquals(order.orderId, payment.orderId)
        repo.transition(order.orderId, FuelOrderStatus.AWAITING_DELIVERY)
        assertTrue(DriverAssignmentEngine(context, helper).assign(order.orderId))
        assertTrue(DriverAssignmentEngine(context, helper).handleDriverReply("777123456", "1"))
        repo.transition(order.orderId, FuelOrderStatus.OUT_FOR_DELIVERY)
        repo.transition(order.orderId, FuelOrderStatus.DELIVERED)
        assertEquals(FuelOrderStatus.DELIVERED, repo.get(order.orderId)!!.status)
        assertEquals(1, db.rawQuery("SELECT COUNT(*) FROM sms_delivery_tasks WHERE order_id = ? AND status = 'ACCEPTED'", arrayOf(order.orderId)).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(1, db.rawQuery("SELECT COUNT(*) FROM fuel_payment_matches WHERE order_id = ? AND status = 'VERIFIED'", arrayOf(order.orderId)).use { it.moveToFirst(); it.getInt(0) })
        assertTrue(db.rawQuery("PRAGMA foreign_key_check", null).use { !it.moveToFirst() })
    }
}
