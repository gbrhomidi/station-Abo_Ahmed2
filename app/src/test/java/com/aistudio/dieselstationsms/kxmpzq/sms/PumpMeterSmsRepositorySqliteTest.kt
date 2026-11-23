package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PumpMeterSmsRepositorySqliteTest {
    private lateinit var context: Context
    private lateinit var database: DatabaseHelper
    private lateinit var repository: PumpMeterSmsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        database = DatabaseHelper.getInstance(context)
        repository = PumpMeterSmsRepository(context, database)
        repository.ensureSchema()
        val db = database.writableDatabase
        db.delete("pump_sms_replay_events", null, null)
        db.delete("pump_sms_receipts", null, null)
        db.delete("pump_sms_audit", null, null)
        db.delete("employee_deductions", null, null)
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
    }

    @Test
    fun duplicateDeliveryIsRecordedWithoutRepeatingSmsBusinessProcessing() = runBlocking {
        val first = repository.handleIncomingSms(
            sender = "+967771234567",
            body = "1-100-F-1",
            messageId = "android-message-42",
            serviceCenter = "+967700000000"
        )
        val replay = repository.handleIncomingSms(
            sender = "+967771234567",
            body = "1-100-F-1",
            messageId = "android-message-42",
            serviceCenter = "+967700000000"
        )

        assertTrue(first)
        assertTrue(replay)
        assertEquals(1, count("pump_sms_receipts"))
        assertEquals(1, count("pump_sms_audit"))
        assertEquals(1, count("pump_sms_replay_events"))
        assertEquals(
            "DUPLICATE_DELIVERY",
            database.readableDatabase.rawQuery(
                "SELECT reason FROM pump_sms_replay_events LIMIT 1", null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            }
        )
    }

    @Test
    fun employeeDeductionUniqueIndexRejectsRepeatedDedupeKeyButAllowsNullKeys() {
        insertDeduction("deduction-key-1")
        var duplicateRejected = false
        try {
            insertDeduction("deduction-key-1")
        } catch (_: SQLiteConstraintException) {
            duplicateRejected = true
        }
        assertTrue("the partial unique index must reject a repeated non-null key", duplicateRejected)

        insertDeduction(null)
        insertDeduction(null)
        assertEquals(3, count("employee_deductions"))
    }

    private fun insertDeduction(dedupeKey: String?) {
        database.writableDatabase.execSQL(
            "INSERT INTO employee_deductions(station_id,employee_id,pump_id,nozzle_id,shortage_liters,unit_sale_price,amount,dedupe_key) VALUES(1,2,3,4,5.0,6.0,30.0,?)",
            arrayOf(dedupeKey)
        )
    }

    private fun count(table: String): Int = database.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM $table", null
    ).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getInt(0)
    }
}
