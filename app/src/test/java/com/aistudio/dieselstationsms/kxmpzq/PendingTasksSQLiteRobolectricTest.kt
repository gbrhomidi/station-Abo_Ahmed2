package com.aistudio.dieselstationsms.kxmpzq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
        status: String = "قيد التنفيذ",
        priority: String = "متوسطة",
        amount: Double = 125.0
    ): Long = helper.addTask(
        JSONObject()
            .put("task_type", type)
            .put("reference", "TASK-$type")
            .put("task_date", "2026-11-02")
            .put("amount", amount)
            .put("priority", priority)
            .put("status", status)
            .put("notes", "بيانات اختبار SQLite"),
        0L
    )

    @Test
    fun pendingQueryReturnsPersistedRowsAndHonorsArchiveAndResolveFlags() {
        val activeId = addTask("مهمة نشطة")
        val completedId = addTask("مهمة مكتملة", status = "مكتملة")
        val archivedId = addTask("مهمة مؤرشفة")
        val deletedId = addTask("مهمة محذوفة")

        assertEquals(1, helper.archiveTask(archivedId, 0L))
        assertEquals(1, helper.deleteTask(deletedId, 0L))

        val defaultRows = helper.getPendingTasks(JSONObject().put("limit", 100))
        assertEquals(1, defaultRows.length())
        assertEquals(activeId, defaultRows.getJSONObject(0).getLong("id"))
        assertEquals("مهمة نشطة", defaultRows.getJSONObject(0).getString("type"))
        assertEquals("2026-11-02", defaultRows.getJSONObject(0).getString("date"))

        val fullRows = helper.getPendingTasks(
            JSONObject()
                .put("include_archived", true)
                .put("include_resolved", true)
                .put("limit", 100)
        )
        val ids = (0 until fullRows.length()).map { fullRows.getJSONObject(it).getLong("id") }.toSet()
        assertEquals(setOf(activeId, completedId, archivedId), ids)
        assertTrue("Soft-deleted rows must never return", !ids.contains(deletedId))
    }

    @Test
    fun taskCrudAndReportUseTheSameSQLiteRecords() {
        val id = addTask("مهمة قبل التعديل")

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
            0L
        )
        assertEquals(1, updated)

        val matched = helper.getPendingTasks(
            JSONObject().put("search", "REF-UPDATED").put("limit", 10)
        )
        assertEquals(1, matched.length())
        val row = matched.getJSONObject(0)
        assertEquals("مهمة بعد التعديل", row.getString("task_type"))
        assertEquals(875.5, row.getDouble("amount"), 0.0001)
        assertEquals("عالية", row.getString("priority"))
        assertEquals("متأخرة", row.getString("status"))
        assertEquals("2026-11-01", row.getString("task_date"))

        assertEquals(1, helper.archiveTask(id, 0L))
        assertEquals(0, helper.getPendingTasks(JSONObject().put("limit", 10)).length())

        assertEquals(1, helper.restoreTask(id, 0L))
        assertEquals(1, helper.getPendingTasks(JSONObject().put("limit", 10)).length())

        assertEquals(1, helper.resolveTask(id, 0L))
        assertEquals(0, helper.getPendingTasks(JSONObject().put("limit", 10)).length())

        val fullRows = helper.getPendingTasks(
            JSONObject().put("include_archived", true).put("include_resolved", true).put("limit", 10)
        )
        assertEquals(1, fullRows.length())

        val report = helper.generateTaskReport(JSONObject().put("limit", 10))
        assertEquals(1, report.length())
        assertNotNull(report.getJSONObject(0).optString("uuid"))

        assertEquals(1, helper.deleteTask(id, 0L))
        assertEquals(
            0,
            helper.getPendingTasks(
                JSONObject().put("include_archived", true).put("include_resolved", true).put("limit", 10)
            ).length()
        )
        assertEquals(0, helper.generateTaskReport(JSONObject().put("limit", 10)).length())
    }

    @Test
    fun paginationDoesNotRepeatRowsAndHonorsOffset() {
        val ids = (1..3).map { addTask("مهمة ترقيم $it") }
        val firstPage = helper.getPendingTasks(JSONObject().put("limit", 2).put("offset", 0))
        val secondPage = helper.getPendingTasks(JSONObject().put("limit", 2).put("offset", 2))

        assertEquals(2, firstPage.length())
        assertEquals(1, secondPage.length())
        val firstIds = (0 until firstPage.length()).map { firstPage.getJSONObject(it).getLong("id") }.toSet()
        val secondIds = (0 until secondPage.length()).map { secondPage.getJSONObject(it).getLong("id") }.toSet()
        assertTrue(firstIds.intersect(secondIds).isEmpty())
        assertEquals(ids.toSet(), firstIds + secondIds)
    }
}
