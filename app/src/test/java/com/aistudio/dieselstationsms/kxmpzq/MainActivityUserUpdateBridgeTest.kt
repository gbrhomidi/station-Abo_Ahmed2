package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityUserUpdateBridgeTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper
    private lateinit var activity: MainActivity
    private lateinit var bridge: MainActivity.WebAppInterface
    private var activityController: ActivityController<MainActivity>? = null
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
        val employeeJobTitle = db.rawQuery(
            "SELECT id, role_name_ar, role_name, role_code FROM roles WHERE is_deleted = 0 ORDER BY id LIMIT 1",
            null
        ).use { cursor ->
            require(cursor.moveToFirst()) { "The synthetic test database must contain an active role" }
            roleId = cursor.getLong(0)
            listOf(
                cursor.getString(cursor.getColumnIndexOrThrow("role_name_ar")),
                cursor.getString(cursor.getColumnIndexOrThrow("role_name")),
                cursor.getString(cursor.getColumnIndexOrThrow("role_code"))
            ).firstOrNull { !it.isNullOrBlank() }?.trim()
                ?: throw IllegalStateException("The active role has no name/code usable as employees.job_title")
        }
        employeeId = db.insertOrThrow("employees", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("employee_code", "EMP-BRIDGE-" + UUID.randomUUID().toString().take(8))
            put("full_name", "Synthetic Bridge Employee")
            put("job_title", employeeJobTitle)
            put("hire_date", "2026-01-01")
            put("status", "active")
        })
        targetUserId = insertUser("bridge-target", "Original Bridge Target")
        otherUserId = insertUser("bridge-other", "Untouched Bridge Other")

        activityController = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = activityController!!.get()

        // Use the attached Robolectric Activity but keep the DB isolated in this test.
        MainActivity::class.java.getDeclaredField("dbHelper").apply {
            isAccessible = true
            set(activity, helper)
        }
        activity.sharedPrefs = context.getSharedPreferences(
            "main_activity_user_update_bridge_test",
            Context.MODE_PRIVATE
        )
        activity.sharedPrefs.edit().clear().commit()
        bridge = activity.WebAppInterface(activity, activity)
    }

    @After
    fun tearDown() {
        runCatching { activityController?.pause()?.stop()?.destroy() }
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        context.getSharedPreferences("main_activity_user_update_bridge_test", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun updateUserInvokesTheRealBridgeAndMutatesOnlyTheExplicitTargetRow() {
        val payload = JSONObject()
            .put("id", otherUserId)
            .put("full_name", "Updated Through Android Bridge")
            .put("full_name_ar", "تم التحديث عبر الجسر")
            .put("employee_id", employeeId)
            .put("role_id", roleId)
            .put("status", "active")
        // The bridge normalizer requires the employee link; submit the existing ID and verify it remains bound.

        val response = JSONObject(bridge.updateUser(targetUserId, payload.toString()))

        assertTrue("The actual WebAppInterface must report a successful update", response.optBoolean("success"))
        assertEquals("تم التحديث بنجاح", response.optString("message"))
        assertEquals("Updated Through Android Bridge", userString(targetUserId, "full_name"))
        assertEquals("تم التحديث عبر الجسر", userString(targetUserId, "full_name_ar"))
        assertEquals(employeeId, userLong(targetUserId, "employee_id"))
        assertEquals(roleId, userLong(targetUserId, "role_id"))
        assertEquals("active", userString(targetUserId, "status"))
        assertNotNull(userString(targetUserId, "updated_at"))
        assertEquals("Untouched Bridge Other", userString(otherUserId, "full_name"))
        assertEquals("bridge-other", userString(otherUserId, "username"))
    }

    @Test
    fun updateUserBridgeReturnsNotFoundWithoutChangingRows() {
        val payload = JSONObject()
            .put("full_name", "Must Not Be Inserted")
            .put("role_id", roleId)
            .put("status", "active")
        val response = JSONObject(bridge.updateUser(9_999_999L, payload.toString()))

        assertFalse(response.optBoolean("success"))
        assertEquals("لم يتم العثور على السجل", response.optString("message"))
        assertEquals("Original Bridge Target", userString(targetUserId, "full_name"))
        assertEquals("Untouched Bridge Other", userString(otherUserId, "full_name"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM users WHERE full_name = ?", "Must Not Be Inserted"))
    }

    @Test
    fun updateUserBridgeRejectsZeroNegativeIdsAndMalformedJsonWithoutPartialWrites() {
        val beforeTarget = userString(targetUserId, "full_name")
        val beforeOther = userString(otherUserId, "full_name")
        val payload = JSONObject()
            .put("full_name", "Must Not Be Written")
            .put("role_id", roleId)
            .put("status", "active")

        listOf(0L, -7L).forEach { invalidId ->
            val response = JSONObject(bridge.updateUser(invalidId, payload.toString()))
            assertFalse("Bridge must reject id=" + invalidId, response.optBoolean("success"))
        }

        val malformed = JSONObject(bridge.updateUser(targetUserId, "{"))
        assertFalse("Malformed JSON must be rejected by the bridge", malformed.optBoolean("success"))
        assertEquals(beforeTarget, userString(targetUserId, "full_name"))
        assertEquals(beforeOther, userString(otherUserId, "full_name"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM users WHERE full_name = ?", "Must Not Be Written"))
    }

    private fun insertUser(username: String, fullName: String): Long =
        helper.writableDatabase.insertOrThrow("users", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("username", username)
            put("password_hash", "synthetic-bridge-password-hash")
            put("password_salt", "synthetic-bridge-password-salt")
            put("full_name", fullName)
            put("role_id", roleId)
            put("employee_id", employeeId)
            put("status", "active")
            put("is_deleted", 0)
        })

    private fun userString(id: Long, column: String): String? =
        helper.readableDatabase.rawQuery(
            "SELECT " + column + " FROM users WHERE id = ?",
            arrayOf(id.toString())
        ).use { cursor ->
            require(cursor.moveToFirst()) { "Expected user row " + id }
            cursor.getString(0)
        }

    private fun userLong(id: Long, column: String): Long =
        helper.readableDatabase.rawQuery(
            "SELECT " + column + " FROM users WHERE id = ?",
            arrayOf(id.toString())
        ).use { cursor ->
            require(cursor.moveToFirst()) { "Expected user row " + id }
            cursor.getLong(0)
        }

    private fun scalarLong(sql: String, vararg args: Any): Long =
        helper.readableDatabase.rawQuery(sql, args.map { it.toString() }.toTypedArray()).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
}
