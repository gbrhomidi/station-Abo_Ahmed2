package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PendingTasksSQLiteRobolectricTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper
    private val stationId = 1

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

    private fun addTask(
        type: String,
        reference: String = "TASK-$type",
        status: String = "قيد التنفيذ",
        priority: String = "متوسطة",
        amount: Double = 125.0
    ): Long = helper.addTask(
        JSONObject()
            .put("task_type", type)
            .put("reference", reference)
            .put("task_date", "2026-11-02")
            .put("amount", amount)
            .put("priority", priority)
            .put("status", status)
            .put("notes", "بيانات اختبار SQLite"),
        stationId
    )

    private fun query(search: String, includeArchived: Boolean = false, includeResolved: Boolean = false): JSONArray =
        helper.getPendingTasks(
            stationId,
            JSONObject()
                .put("search", search)
                .put("include_archived", includeArchived)
                .put("include_resolved", includeResolved)
                .put("limit", 100)
        )

    private fun rowById(rows: JSONArray, id: Long): JSONObject? {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            if (row.optLong("id") == id) return row
        }
        return null
    }

    @Test
    fun pendingQueryReturnsPersistedRowsAndHonorsArchiveResolveAndDeleteFlags() {
        val activeId = addTask("مهمة نشطة")
        val completedId = addTask("مهمة مكتملة", status = "مكتملة")
        val archivedId = addTask("مهمة مؤرشفة")
        val deletedId = addTask("مهمة محذوفة")

        assertEquals(1, helper.archiveTask(archivedId, stationId))
        assertEquals(1, helper.deleteTask(deletedId, stationId))

        val defaultRows = query("TASK-")
        assertEquals(1, defaultRows.length())
        assertEquals(activeId, defaultRows.getJSONObject(0).getLong("id"))
        assertEquals("مهمة نشطة", defaultRows.getJSONObject(0).getString("type"))
        assertEquals("2026-11-02", defaultRows.getJSONObject(0).getString("date"))

        val fullRows = query("TASK-", includeArchived = true, includeResolved = true)
        val ids = (0 until fullRows.length()).map { fullRows.getJSONObject(it).getLong("id") }.toSet()
        assertEquals(setOf(activeId, completedId, archivedId), ids)
        assertTrue("Soft-deleted rows must never return", !ids.contains(deletedId))
    }

    @Test
    fun taskCrudAndReportUseTheSameStationScopedSQLiteRecords() {
        val id = addTask("مهمة قبل التعديل", reference = "REF-UPDATED")

        val updated = helper.updateTask(
            id,
            JSONObject()
                .put("task_type", "مهمة بعد التعديل")
                .put("reference", "REF-UPDATED")
                .put("task_date", "2026-11-01")
                .put("amount", 875.5)
                .put("priority", "عالية")
                .put("status", "متأخرة")
                .put("notes", "تم التعديل فعلياً"),
            stationId
        )
        assertEquals(1, updated)

        val matched = query("REF-UPDATED")
        assertEquals(1, matched.length())
        val row = matched.getJSONObject(0)
        assertEquals("مهمة بعد التعديل", row.getString("task_type"))
        assertEquals(875.5, row.getDouble("amount"), 0.0001)
        assertEquals("عالية", row.getString("priority"))
        assertEquals("متأخرة", row.getString("status"))
        assertEquals("2026-11-01", row.getString("task_date"))

        assertEquals(1, helper.archiveTask(id, stationId))
        assertEquals(0, query("REF-UPDATED").length())

        assertEquals(1, helper.restoreTask(id, stationId))
        assertEquals(1, query("REF-UPDATED").length())

        assertEquals(1, helper.resolveTask(id, stationId))
        assertEquals(0, query("REF-UPDATED").length())

        val fullRows = query("REF-UPDATED", includeArchived = true, includeResolved = true)
        assertEquals(1, fullRows.length())

        val report = helper.generateTaskReport(
            stationId,
            JSONObject().put("search", "REF-UPDATED").put("limit", 100)
        )
        assertEquals(1, report.length())
        assertNotNull(report.getJSONObject(0).optString("uuid"))

        assertEquals(1, helper.deleteTask(id, stationId))
        assertEquals(0, query("REF-UPDATED", includeArchived = true, includeResolved = true).length())
        assertEquals(
            0,
            helper.generateTaskReport(
                stationId,
                JSONObject().put("search", "REF-UPDATED").put("limit", 100)
            ).length()
        )
    }

    @Test
    fun paginationDoesNotRepeatRowsAndHonorsOffsetWithinTheCurrentStation() {
        val ids = (1..3).map { index ->
            addTask("مهمة ترقيم $index", reference = "PAGED-TASK-$index")
        }
        val firstPage = helper.getPendingTasks(
            stationId,
            JSONObject().put("search", "PAGED-TASK-").put("limit", 2).put("offset", 0)
        )
        val secondPage = helper.getPendingTasks(
            stationId,
            JSONObject().put("search", "PAGED-TASK-").put("limit", 2).put("offset", 2)
        )

        assertEquals(2, firstPage.length())
        assertEquals(1, secondPage.length())
        val firstIds = (0 until firstPage.length()).map { firstPage.getJSONObject(it).getLong("id") }.toSet()
        val secondIds = (0 until secondPage.length()).map { secondPage.getJSONObject(it).getLong("id") }.toSet()
        assertTrue(firstIds.intersect(secondIds).isEmpty())
        assertEquals(ids.toSet(), firstIds + secondIds)
    }

    @Test
    fun taskMutationsRejectInvalidStationScopeAndDoNotChangePersistedRows() {
        val id = addTask("مهمة عزل المحطة", reference = "STATION-ISOLATION")
        assertEquals(0, helper.updateTask(id, JSONObject().put("notes", "تغيير غير مسموح"), 2))
        assertEquals(0, helper.archiveTask(id, 2))
        assertEquals(0, helper.restoreTask(id, 2))
        assertEquals(0, helper.resolveTask(id, 2))
        assertEquals(0, helper.deleteTask(id, 2))

        val row = rowById(query("STATION-ISOLATION"), id)
        assertNotNull(row)
        assertEquals("بيانات اختبار SQLite", row!!.getString("notes"))
        assertEquals(0, row.getInt("is_archived"))
        assertEquals(0, row.getInt("is_resolved"))
        assertEquals(0, row.getInt("is_deleted"))
    }
}
