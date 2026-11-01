package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseHelperUserUpdateContractTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper
    private var roleId = 0L
    private var employeeId = 0L
    private var targetUserId = 0L
    private var otherUserId = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        helper = DatabaseHelper.getInstance(context)
        val db = helper.writableDatabase
        roleId = db.rawQuery(
            "SELECT id FROM roles WHERE is_deleted = 0 LIMIT 1",
            null
        ).use { cursor ->
            require(cursor.moveToFirst()) { "The test database must contain an active role" }
            cursor.getLong(0)
        }
        employeeId = db.insertOrThrow("employees", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("employee_code", "EMP-UPDATE-" + UUID.randomUUID().toString().take(8))
            put("full_name", "Synthetic Update Employee")
            put("job_title", "Technician")
            put("hire_date", "2026-01-01")
            put("status", "active")
        })
        targetUserId = insertUser("update-target", "Original Target")
        otherUserId = insertUser("update-other", "Untouched Other")
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    @Test
    fun updateUserUsesTheExplicitIdAndChangesOnlyTheTargetRow() {
        val payload = JSONObject()
            .put("full_name", "Updated Target")
            .put("full_name_ar", "الهدف المحدث")
            .put("employee_id", employeeId)
            .put("role_id", roleId)
            .put("status", "active")

        val affectedRows = helper.updateUser(targetUserId, payload)

        assertEquals("exactly one SQLite row must be updated", 1, affectedRows)
        val db = helper.readableDatabase
        val target = userRow(db, targetUserId)
        val other = userRow(db, otherUserId)
        assertEquals("Updated Target", target.getString(target.getColumnIndexOrThrow("full_name")))
        assertEquals("الهدف المحدث", target.getString(target.getColumnIndexOrThrow("full_name_ar")))
        assertEquals(employeeId, target.getLong(target.getColumnIndexOrThrow("employee_id")))
        assertEquals("Original Target", other.getString(other.getColumnIndexOrThrow("full_name")))
        assertEquals("update-other", other.getString(other.getColumnIndexOrThrow("username")))
        assertNotNull(target.getString(target.getColumnIndexOrThrow("updated_at")))
        target.close()
        other.close()
    }

    @Test
    fun updateUserReturnsZeroForMissingIdWithoutChangingExistingRows() {
        val payload = JSONObject()
            .put("full_name", "Should Not Be Written")
            .put("employee_id", employeeId)
            .put("role_id", roleId)
            .put("status", "active")

        val affectedRows = helper.updateUser(9_999_999L, payload)

        assertEquals("a missing user must produce zero affected rows", 0, affectedRows)
        val db = helper.readableDatabase
        db.rawQuery(
            "SELECT COUNT(*) FROM users WHERE full_name = ?",
            arrayOf("Should Not Be Written")
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        assertEquals("Original Target", userFullName(targetUserId))
        assertEquals("Untouched Other", userFullName(otherUserId))
    }

    @Test
    fun updateUserRejectsNonPositiveIdsBeforeWriting() {
        val payload = JSONObject()
            .put("full_name", "Invalid ID")
            .put("employee_id", employeeId)
            .put("role_id", roleId)
        assertThrows(IllegalArgumentException::class.java) {
            helper.updateUser(0L, payload)
        }
        assertEquals("Original Target", userFullName(targetUserId))
        assertEquals("Untouched Other", userFullName(otherUserId))
    }

    private fun insertUser(username: String, fullName: String): Long =
        helper.writableDatabase.insertOrThrow("users", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("username", username)
            put("password_hash", "synthetic-test-hash")
            put("password_salt", "synthetic-test-salt")
            put("full_name", fullName)
            put("role_id", roleId)
            put("employee_id", employeeId)
            put("status", "active")
            put("is_deleted", 0)
        })

    private fun userFullName(id: Long): String =
        helper.readableDatabase.rawQuery(
            "SELECT full_name FROM users WHERE id = ?",
            arrayOf(id.toString())
        ).use { cursor ->
            require(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun userRow(db: android.database.sqlite.SQLiteDatabase, id: Long) =
        db.rawQuery("SELECT * FROM users WHERE id = ?", arrayOf(id.toString())).also {
            require(it.moveToFirst()) { "Expected user row $id" }
        }
}
