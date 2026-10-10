package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ApprovalControlRobolectricTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper
    private var roleId = 0L
    private var makerId = 0L
    private var checkerId = 0L
    private var otherStationUserId = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        helper = DatabaseHelper.getInstance(context)
        val db = helper.writableDatabase
        roleId = db.rawQuery("SELECT id FROM roles ORDER BY id LIMIT 1", null).use { c ->
            check(c.moveToFirst()) { "لا يوجد دور افتراضي للاختبار" }
            c.getLong(0)
        }
        ensureStation(11, "TEST-APPROVAL-11")
        ensureStation(12, "TEST-APPROVAL-12")
        makerId = createUser(11, "maker")
        checkerId = createUser(11, "checker")
        otherStationUserId = createUser(12, "other-station")
        db.insertOrThrow("approval_thresholds", null, ContentValues().apply {
            put("station_id", 11)
            put("entity_type", "payment")
            put("operation", "complete")
            put("amount_threshold", 5000.0)
            put("required_approvals", 1)
            put("approver_role_id", roleId)
            put("is_active", 1)
        })
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    private fun ensureStation(id: Int, code: String) {
        val db = helper.writableDatabase
        db.rawQuery("SELECT id FROM stations WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) return
        }
        db.insertOrThrow("stations", null, ContentValues().apply {
            put("id", id)
            put("uuid", UUID.randomUUID().toString())
            put("station_code", code)
            put("station_name", code)
            put("status", "active")
            put("is_deleted", 0)
        })
    }

    private fun createUser(stationId: Int, prefix: String): Long =
        helper.writableDatabase.insertOrThrow("users", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("username", "$prefix-${UUID.randomUUID().toString().take(8)}")
            put("password_hash", "test-only")
            put("full_name", prefix)
            put("role_id", roleId)
            put("station_id", stationId)
            put("is_deleted", 0)
            put("status", "active")
        })

    private fun createRequest(): Long =
        helper.createPendingApproval(11, "payment", 901, "complete", 6000.0, makerId)

    @Test
    fun amountThresholdAndPendingRequestAreEnforcedAndIdempotent() {
        assertTrue(helper.requiresApproval(11, "payment", "complete", 6000.0))
        assertTrue(!helper.requiresApproval(11, "payment", "complete", 1000.0))
        val first = createRequest()
        val second = createRequest()
        assertEquals(first, second)
        assertEquals(1, helper.getPendingApprovals(11, "pending").length())
    }

    @Test(expected = IllegalArgumentException::class)
    fun makerCannotApproveOwnRequest() {
        helper.approveTransaction(createRequest(), makerId, 11, "self approval must fail")
    }

    @Test
    fun separateAuthorizedCheckerCanApproveAndQueueLeavesPendingState() {
        val requestId = createRequest()
        assertTrue(helper.approveTransaction(requestId, checkerId, 11, "reviewed"))
        val row = helper.getPendingApprovals(11, "approved").getJSONObject(0)
        assertEquals(requestId, row.getLong("id"))
        assertEquals("approved", row.getString("status"))
        assertEquals(1, row.getInt("approvals_count"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun userFromAnotherStationCannotApproveRequest() {
        helper.approveTransaction(createRequest(), otherStationUserId, 11, "wrong station")
    }

    @Test
    fun rejectedRequestRequiresReasonAndRecordsDecision() {
        val requestId = createRequest()
        assertTrue(helper.rejectTransaction(requestId, checkerId, 11, "مستندات غير مكتملة"))
        val row = helper.getPendingApprovals(11, "rejected").getJSONObject(0)
        assertEquals("rejected", row.getString("status"))
        assertEquals("مستندات غير مكتملة", row.getString("rejection_reason"))
        assertNotEquals(makerId, checkerId)
    }
}
