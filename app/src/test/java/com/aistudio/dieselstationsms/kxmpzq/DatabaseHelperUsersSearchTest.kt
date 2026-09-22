package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
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
class DatabaseHelperUsersSearchTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper
    private var roleId = 0L
    private var baselineTotal = 0L
    private var baselineLocked = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        helper = DatabaseHelper.getInstance(context)
        val db = helper.writableDatabase
        roleId = db.rawQuery("SELECT id FROM roles WHERE is_deleted = 0 LIMIT 1", null).use { cursor ->
            require(cursor.moveToFirst()) { "The test database must contain an active role" }
            cursor.getLong(0)
        }
        db.delete("users", "username LIKE ?", arrayOf("unit-search-%"))
        db.rawQuery("SELECT COUNT(*) FROM users WHERE is_deleted = 0", null).use { cursor ->
            require(cursor.moveToFirst())
            baselineTotal = cursor.getLong(0)
        }
        db.rawQuery("SELECT COUNT(*) FROM users WHERE is_deleted = 0 AND (status = 'locked' OR account_locked = 1)", null).use { cursor ->
            require(cursor.moveToFirst())
            baselineLocked = cursor.getLong(0)
        }
        insertUser("unit-search-alpha", "Alpha User", "active", 1)
        insertUser("unit-search-beta", "Beta User", "active", 1)
        insertUser("unit-search-locked", "Locked User", "locked", 1)
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    @Test
    fun `search matches username and returns total count`() {
        val result = helper.searchUsers("alpha", null, null, null, page = 1, pageSize = 10)
        val rows = result.getJSONArray("data")

        assertEquals(1, rows.length())
        assertEquals("unit-search-alpha", rows.getJSONObject(0).getString("username"))
        assertEquals(1, result.getLong("total"))
        assertEquals(1, result.getInt("page"))
        assertEquals(10, result.getInt("pageSize"))
    }

    @Test
    fun `pagination keeps stable page size and does not duplicate rows`() {
        val first = helper.searchUsers("unit-search-", null, null, null, page = 1, pageSize = 2)
        val second = helper.searchUsers("unit-search-", null, null, null, page = 2, pageSize = 2)
        val firstRows = first.getJSONArray("data")
        val secondRows = second.getJSONArray("data")

        assertEquals(3, first.getLong("total"))
        assertEquals(2, firstRows.length())
        assertEquals(1, secondRows.length())
        val firstIds = ids(firstRows)
        val secondIds = ids(secondRows)
        assertTrue(firstIds.intersect(secondIds).isEmpty())
    }

    @Test
    fun `status filter is applied by SQLite and statistics remain real`() {
        val result = helper.searchUsers("unit-search-", "locked", null, null, page = 1, pageSize = 10)
        val rows = result.getJSONArray("data")
        val stats = result.getJSONObject("stats")

        assertEquals(1, rows.length())
        assertEquals("locked", rows.getJSONObject(0).getString("status"))
        assertEquals(baselineTotal + 3, stats.getLong("total"))
        assertEquals(baselineLocked + 1, stats.getLong("locked"))
    }

    private fun ids(rows: JSONArray): Set<Long> = buildSet {
        for (index in 0 until rows.length()) add(rows.getJSONObject(index).getLong("id"))
    }

    private fun insertUser(username: String, fullName: String, status: String, isDeleted: Int) {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("username", username)
            put("password_hash", "test-hash")
            put("password_salt", "test-salt")
            put("full_name", fullName)
            put("role_id", roleId)
            put("status", status)
            put("account_locked", if (status == "locked") 1 else 0)
            put("is_deleted", isDeleted)
            put("created_at", "2026-01-01 00:00:00")
            put("updated_at", "2026-01-01 00:00:00")
        }
        require(db.insertOrThrow("users", null, values) > 0L)
    }
}
