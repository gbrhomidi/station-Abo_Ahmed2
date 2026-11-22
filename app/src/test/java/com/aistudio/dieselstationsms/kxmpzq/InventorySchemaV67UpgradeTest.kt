package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InventorySchemaV67UpgradeTest {
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
    fun v67UpgradeRepairsMissingWetAndDryStockTablesBeforeQueriesRun() {
        val db = helper.writableDatabase
        listOf("tank_readings", "product_batches", "inventory_anomalies").forEach {
            db.execSQL("DROP TABLE IF EXISTS $it")
        }
        DatabaseHelper.closeInstance()

        val installed = SQLiteDatabase.openDatabase(
            context.getDatabasePath(DatabaseHelper.DATABASE_NAME).path,
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        try {
            installed.version = 67
        } finally {
            installed.close()
        }

        helper = DatabaseHelper.getInstance(context)
        val upgraded = helper.writableDatabase
        assertEquals(72, upgraded.version)

        listOf("tank_readings", "product_batches", "inventory_anomalies").forEach { table ->
            val exists = upgraded.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(table)
            ).use { it.moveToFirst() }
            assertTrue("V67-to-current migration chain must preserve/recreate $table", exists)
        }

        // Exercise the exact Kotlin/SQLite read paths that previously failed.
        // This schema-recovery test supplies an explicit calibration fixture; production code must not invent one.
        // Wet-stock reads resolve fuel type 2; retain type 1 for the other fixture paths.
        upgraded.execSQL("INSERT INTO fuel_temperature_coefficients(fuel_type_id, coefficient_exp) VALUES(1, 0.00095), (2, 0.00095)")
        helper.getWetStockPage(1)
        helper.getDryStockPage(JSONObject(), 1)
        helper.runInventoryAnomalyDetection(1)
        helper.getForecourtSnapshot(1)

        assertTrue(upgraded.rawQuery("PRAGMA integrity_check", null).use {
            it.moveToFirst() && it.getString(0) == "ok"
        })
        assertTrue(upgraded.rawQuery("PRAGMA foreign_key_check", null).use {
            !it.moveToFirst()
        })
    }
}
