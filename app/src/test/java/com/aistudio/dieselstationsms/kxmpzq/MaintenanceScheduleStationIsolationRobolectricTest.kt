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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MaintenanceScheduleStationIsolationRobolectricTest {
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
    fun `maintenance schedules are fail closed outside their authoritative station`() {
        val db = helper.writableDatabase
        val roleId = db.rawQuery("SELECT id FROM roles ORDER BY id LIMIT 1", null).use { cursor ->
            check(cursor.moveToFirst()) { "لا يوجد دور افتراضي للاختبار" }
            cursor.getLong(0)
        }
        insertStation(db, 31, "TEST-MSCH-A", "محطة جدولة أ")
        insertStation(db, 32, "TEST-MSCH-B", "محطة جدولة ب")
        val stationAUser = insertUser(db, "schedule-scope-a", roleId, 31)
        val stationBUser = insertUser(db, "schedule-scope-b", roleId, 32)

        val scheduleId = helper.saveOperationalRecord(
            "maintenance_schedule",
            schedulePayload("فحص أسبوعي للمضخة", 31),
            stationAUser
        )

        assertEquals(1, helper.getOperationalRows("maintenance_schedule", JSONObject().put("station_id", 31)).length())
        assertEquals(0, helper.getOperationalRows("maintenance_schedule", JSONObject().put("station_id", 32)).length())

        val rowsUpdatedByB = helper.updateOperationalRecord(
            "maintenance_schedule",
            scheduleId,
            schedulePayload("محاولة تعديل من محطة ب", 32),
            stationBUser
        )
        assertEquals(0, rowsUpdatedByB)

        val rowsUpdatedByA = helper.updateOperationalRecord(
            "maintenance_schedule",
            scheduleId,
            schedulePayload("فحص أسبوعي محدّث", 31),
            stationAUser
        )
        assertEquals(1, rowsUpdatedByA)
        val stationARows = helper.getOperationalRows("maintenance_schedule", JSONObject().put("station_id", 31))
        assertEquals("فحص أسبوعي محدّث", stationARows.getJSONObject(0).getString("schedule_name"))
    }

    @Test
    fun `v29 migration backfills legacy schedule only from a valid creator station`() {
        val db = helper.writableDatabase
        val roleId = db.rawQuery("SELECT id FROM roles ORDER BY id LIMIT 1", null).use { cursor ->
            check(cursor.moveToFirst()) { "لا يوجد دور افتراضي للاختبار" }
            cursor.getLong(0)
        }
        insertStation(db, 41, "TEST-MSCH-MIG", "محطة ترحيل الجدولة")
        val userId = insertUser(db, "schedule-migration-user", roleId, 41)
        val scheduleId = helper.saveOperationalRecord(
            "maintenance_schedule",
            schedulePayload("جدولة ترحيل", 41),
            userId
        )
        db.update("maintenance_schedule", ContentValues().apply { put("station_id", 0) }, "id = ?", arrayOf(scheduleId.toString()))
        db.execSQL("PRAGMA user_version = 28")

        DatabaseHelper.closeInstance()
        helper = DatabaseHelper.getInstance(context)
        assertEquals(1, helper.getOperationalRows("maintenance_schedule", JSONObject().put("station_id", 41)).length())
        assertEquals(0, helper.getOperationalRows("maintenance_schedule", JSONObject().put("station_id", 42)).length())
        assertTrue(helper.writableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { cursor -> !cursor.moveToFirst() })
    }

    private fun schedulePayload(name: String, stationId: Int): JSONObject = JSONObject()
        .put("schedule_name", name)
        .put("asset_type", "pump")
        .put("frequency_type", "weekly")
        .put("frequency_value", 1)
        .put("station_id", stationId)

    private fun insertUser(db: android.database.sqlite.SQLiteDatabase, username: String, roleId: Long, stationId: Int): Long =
        db.insertOrThrow("users", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("username", username)
            put("password_hash", "test-only")
            put("full_name", username)
            put("role_id", roleId)
            put("station_id", stationId)
        })

    private fun insertStation(db: android.database.sqlite.SQLiteDatabase, id: Int, code: String, name: String) {
        db.insertOrThrow("stations", null, ContentValues().apply {
            put("id", id)
            put("uuid", UUID.randomUUID().toString())
            put("station_code", code)
            put("station_name", name)
            put("status", "active")
            put("is_deleted", 0)
        })
    }
}
