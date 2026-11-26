package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.aistudio.dieselstationsms.kxmpzq.data.AuditLogDao
import com.aistudio.dieselstationsms.kxmpzq.data.FuelShiftDao
import com.aistudio.dieselstationsms.kxmpzq.data.MovementReasonDao
import com.aistudio.dieselstationsms.kxmpzq.data.ProductBatchDao
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class MovementInfrastructureV75Test {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        helper = DatabaseHelper.getInstance(context)
    }

    @After fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    @Test fun v74UpgradeInstallsMovementSchemaIdempotentlyAndPreservesLegacyReferenceRows() {
        val db = helper.writableDatabase
        db.execSQL("INSERT OR IGNORE INTO inventory_reason_codes(code,label_ar,label_en,applies_to,requires_note) VALUES('V75_KEEP','سبب قديم','Legacy reason','BOTH',1)")
        db.execSQL("DROP TRIGGER IF EXISTS trg_inventory_movement_audit_insert")
        db.execSQL("DROP TRIGGER IF EXISTS trg_inventory_movement_audit_update")
        db.execSQL("DROP TRIGGER IF EXISTS trg_inventory_movement_audit_delete")
        db.execSQL("DROP TRIGGER IF EXISTS trg_inventory_movements_locked_update")
        db.execSQL("DROP TRIGGER IF EXISTS trg_inventory_movements_locked_delete")
        db.execSQL("DROP TRIGGER IF EXISTS trg_inventory_movements_lock_approved")
        db.execSQL("DROP TRIGGER IF EXISTS trg_movement_audit_no_update")
        db.execSQL("DROP TRIGGER IF EXISTS trg_movement_audit_no_delete")
        db.execSQL("DROP TABLE IF EXISTS fuel_shift_readings")
        db.execSQL("DROP TABLE IF EXISTS fuel_dips")
        db.execSQL("DROP TABLE IF EXISTS fuel_deliveries")
        db.execSQL("DROP TABLE IF EXISTS movement_audit_log")
        db.execSQL("DROP TABLE IF EXISTS movement_reasons")
        db.execSQL("DROP TABLE IF EXISTS fuel_shifts")
        db.version = 74
        DatabaseHelper.closeInstance()

        helper = DatabaseHelper.getInstance(context)
        val upgraded = helper.writableDatabase
        assertEquals(75, upgraded.version)
        listOf("fuel_shifts","fuel_shift_readings","fuel_dips","fuel_deliveries","movement_reasons","movement_audit_log").forEach { table ->
            val exists = upgraded.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }
            assertTrue("V74 upgrade must create $table", exists)
        }
        val legacyReason = upgraded.rawQuery("SELECT label_ar FROM inventory_reason_codes WHERE code='V75_KEEP'", null).use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }
        assertEquals("سبب قديم", legacyReason)
        val reasons = upgraded.rawQuery("SELECT COUNT(*) FROM movement_reasons WHERE code IN ('DAMAGE','RECONCILIATION_LOSS','STOCKTAKE_CORRECTION','SUPPLIER_RETURN','SHIFT_RECONCILIATION','TEMPERATURE_CORRECTION')", null).use {
            assertTrue(it.moveToFirst()); it.getInt(0)
        }
        assertEquals(6, reasons)
        val columns = upgraded.rawQuery("PRAGMA table_info(inventory_movements)", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(1)) }
        }
        listOf("batch_id","reason_code_id","shift_id","approved_at","is_locked").forEach { assertTrue("missing inventory_movements.$it", it in columns) }
        val tankColumns = upgraded.rawQuery("PRAGMA table_info(tank_readings)", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(1)) }
        }
        listOf("temperature_corrected_volume","water_level_mm","variance_liters","variance_percent").forEach { assertTrue("missing tank_readings.$it", it in tankColumns) }
        val integrity = upgraded.rawQuery("PRAGMA integrity_check", null).use { assertTrue(it.moveToFirst()); it.getString(0) }
        assertEquals("ok", integrity)
        assertEquals(0, upgraded.rawQuery("PRAGMA foreign_key_check", null).use { it.count })

        // A second open is a no-op: DDL and reference seeding are idempotent.
        DatabaseHelper.closeInstance()
        helper = DatabaseHelper.getInstance(context)
        assertEquals(75, helper.writableDatabase.version)
    }

    @Test fun auditJournalIsAppendOnlyAndDaosExposeCoreQueries() {
        val db = helper.writableDatabase
        val id = AuditLogger.log(
            db, 991L, "inventory_movements", "create", 1L, null,
            null, JSONObject().put("movement_code", "TEST-991").toString(),
            stationId = 1L
        )
        assertTrue(id > 0)
        assertEquals(1, AuditLogDao(db).byMovement(991L).size)
        assertEquals(0, AuditLogDao(db).getPendingAuditLog().size)
        try {
            db.execSQL("UPDATE movement_audit_log SET action='tampered' WHERE id=?", arrayOf(id))
            fail("audit update must be rejected")
        } catch (_: SQLiteConstraintException) { }
        try {
            db.delete("movement_audit_log", "id=?", arrayOf(id.toString()))
            fail("audit delete must be rejected")
        } catch (_: SQLiteConstraintException) { }

        assertNotNull(MovementReasonDao(db).getByCode("DAMAGE"))
        assertTrue(MovementReasonDao(db).listActive("FUEL").isNotEmpty())
        assertNull(FuelShiftDao(db).getOpenShift(1L))
        assertNull(ProductBatchDao(db).getActiveBatchByProduct(999999L))
        assertEquals(1, AuditLogger.page(db, 1L).length())
    }
}
