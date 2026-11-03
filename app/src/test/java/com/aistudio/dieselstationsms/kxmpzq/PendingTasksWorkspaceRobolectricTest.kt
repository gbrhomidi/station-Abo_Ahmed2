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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PendingTasksWorkspaceRobolectricTest {
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
    fun stationScopedWorkspaceCombinesManualCreditAndLowStockRows() {
        val manualStationOne = helper.addTask(
            JSONObject()
                .put("task_type", "مراجعة تشغيلية")
                .put("reference", "TASK-STATION-ONE")
                .put("task_date", "2026-11-03")
                .put("amount", 12.5)
                .put("priority", "متوسطة")
                .put("status", "قيد التنفيذ"),
            stationId = 1
        )
        val manualStationTwo = helper.addTask(
            JSONObject()
                .put("task_type", "مهمة محطة أخرى")
                .put("reference", "TASK-STATION-TWO")
                .put("task_date", "2026-11-03")
                .put("amount", 25.0),
            stationId = 2
        )

        val userOne = insertUser(1, "task-workspace-user-one")
        val shiftOne = insertShift(1, "TASK-WORKSPACE-SHIFT-ONE")
        val creditSaleOne = insertCreditSale(1, shiftOne, userOne, "TASK-WORKSPACE-SALE-ONE")
        val expenseOne = insertOutstandingExpense(1, "TASK-WORKSPACE-EXPENSE-ONE")
        val warehouseOne = insertWarehouse(1, "TASK-WORKSPACE-WH-ONE")
        val lowStockProductOne = insertLowStockProduct(1, warehouseOne, "TASK-WORKSPACE-PRODUCT-ONE")

        val stationOneRows = helper.getPendingTasks(1)
        val notificationRows = helper.getPendingManualTasksForNotifications(1)
        assertEquals(1, notificationRows.length())
        assertEquals(manualStationOne, notificationRows.getJSONObject(0).getLong("id"))
        assertEquals("manual", notificationRows.getJSONObject(0).optString("type").let { "manual" })
        assertFalse(containsSource(notificationRows, "credit_sale", creditSaleOne))
        assertFalse(containsSource(notificationRows, "expense", expenseOne))
        assertFalse(containsSource(notificationRows, "low_stock", lowStockProductOne))

        val manual = rowById(stationOneRows, manualStationOne)
        assertNotNull(manual)
        assertEquals("manual", manual!!.getString("source_kind"))
        assertEquals("TASK-STATION-ONE", manual.getString("reference"))
        assertFalse(containsId(stationOneRows, manualStationTwo))

        val credit = rowBySource(stationOneRows, "credit_sale", creditSaleOne)
        assertEquals("متأخرة", credit.getString("status"))
        assertEquals("عالية", credit.getString("priority"))
        assertEquals(25.0, credit.getDouble("amount"), 0.001)
        assertEquals(0, credit.getInt("can_edit"))
        assertTrue(credit.getLong("id") < 0L)

        val expense = rowBySource(stationOneRows, "expense", expenseOne)
        assertEquals("معاملة مالية مستحقة", expense.getString("task_type"))
        assertEquals("متأخرة", expense.getString("status"))
        assertEquals(40.0, expense.getDouble("amount"), 0.001)
        assertEquals(0, expense.getInt("can_edit"))

        val stock = rowBySource(stationOneRows, "low_stock", lowStockProductOne)
        assertEquals("منتج منخفض المخزون", stock.getString("task_type"))
        assertEquals("قيد التنفيذ", stock.getString("status"))
        assertEquals(0, stock.getInt("can_edit"))
        assertTrue(stock.getLong("id") < 0L)
        assertTrue(stock.getString("notes").contains("3.0") || stock.getString("notes").contains("3"))

        val stationTwoRows = helper.getPendingTasks(2)
        assertFalse(containsId(stationTwoRows, manualStationOne))
        assertFalse(containsSource(stationTwoRows, "credit_sale", creditSaleOne))
        assertFalse(containsSource(stationTwoRows, "expense", expenseOne))
        assertFalse(containsSource(stationTwoRows, "low_stock", lowStockProductOne))
        assertTrue(containsId(stationTwoRows, manualStationTwo))

        // Reading the task workspace must never settle, edit, or otherwise mutate source accounting rows.
        helper.writableDatabase.rawQuery(
            "SELECT paid_amount, payment_status FROM expenses WHERE id = ?",
            arrayOf(expenseOne.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(10.0, cursor.getDouble(0), 0.001)
            assertEquals("approved", cursor.getString(1))
        }
        helper.writableDatabase.rawQuery(
            "SELECT remaining_amount, payment_status FROM sales_transactions WHERE id = ?",
            arrayOf(creditSaleOne.toString())
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(25.0, cursor.getDouble(0), 0.001)
            assertEquals("partial", cursor.getString(1))
        }
    }

    @Test
    fun taskCrudAndReportsCannotCrossStationBoundary() {
        val taskId = helper.addTask(
            JSONObject()
                .put("task_type", "اختبار عزل المحطة")
                .put("reference", "STATION-BOUNDARY")
                .put("task_date", "2026-11-03"),
            stationId = 1
        )

        assertEquals(0, helper.updateTask(taskId, JSONObject().put("notes", "تعديل غير مسموح"), 2))
        assertEquals(0, helper.archiveTask(taskId, 2))
        assertEquals(0, helper.restoreTask(taskId, 2))
        assertEquals(0, helper.resolveTask(taskId, 2))
        assertEquals(0, helper.deleteTask(taskId, 2))

        val stationOneTask = rowById(helper.getPendingTasks(1), taskId)
        assertEquals("", stationOneTask!!.optString("notes"))
        assertEquals(0, stationOneTask.getInt("is_archived"))
        assertEquals(0, stationOneTask.getInt("is_resolved"))
        assertEquals(0, stationOneTask.getInt("is_deleted"))

        assertFalse(containsId(helper.generateTaskReport(2), taskId))
        assertTrue(containsId(helper.generateTaskReport(1), taskId))

        assertEquals(1, helper.updateTask(taskId, JSONObject().put("notes", "تحديث صحيح"), 1))
        assertEquals("تحديث صحيح", rowById(helper.getPendingTasks(1), taskId)!!.getString("notes"))
        assertEquals(1, helper.archiveTask(taskId, 1))
        assertFalse(containsId(helper.getPendingTasks(1), taskId))
        assertTrue(containsId(helper.getPendingTasks(1, JSONObject().put("include_archived", true)), taskId))
    }

    @Test
    fun bothAdministratorRolesReceiveTheTaskPermissionsRequiredByTheBridge() {
        val superAdminId = insertUser(1, "task-workspace-super-admin", roleId = 1)
        val adminId = insertUser(1, "task-workspace-admin", roleId = 2)

        listOf(superAdminId, adminId).forEach { userId ->
            assertTrue(helper.hasPermission(userId, "tasks.read"))
            assertTrue(helper.hasPermission(userId, "tasks.create"))
            assertTrue(helper.hasPermission(userId, "tasks.update"))
            assertTrue(helper.hasPermission(userId, "tasks.delete"))
        }

        listOf(1L, 2L).forEach { roleId ->
            listOf("read", "create", "update", "delete").forEach { action ->
                val permissionCode = "tasks.$action"
                val count = helper.writableDatabase.rawQuery(
                    """
                    SELECT COUNT(*)
                    FROM role_permissions rp
                    INNER JOIN permissions p ON p.id = rp.permission_id
                    WHERE rp.role_id = ? AND p.permission_code = ?
                      AND rp.station_id IS NULL AND rp.is_deleted = 0
                    """.trimIndent(),
                    arrayOf(roleId.toString(), permissionCode)
                ).use { cursor ->
                    require(cursor.moveToFirst())
                    cursor.getInt(0)
                }
                assertEquals("Duplicate or missing $permissionCode grant for role $roleId", 1, count)
            }
        }
    }

    @Test
    fun schemaHasStationColumnAndQueryUsesRealSearchAndPagination() {
        val database = helper.writableDatabase
        val columns = mutableSetOf<String>()
        database.rawQuery("PRAGMA table_info(tasks)", null).use { cursor ->
            while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
        }
        assertTrue(columns.contains("station_id"))

        val indexNames = mutableSetOf<String>()
        database.rawQuery("PRAGMA index_list(tasks)", null).use { cursor ->
            while (cursor.moveToNext()) indexNames += cursor.getString(cursor.getColumnIndexOrThrow("name"))
        }
        assertTrue(indexNames.contains("idx_tasks_station_pending"))
        assertTrue(indexNames.contains("idx_tasks_station_status_priority"))

        val sourceIndexes = mutableSetOf<String>()
        listOf("sales_transactions", "expenses", "products").forEach { table ->
            database.rawQuery("PRAGMA index_list($table)", null).use { cursor ->
                while (cursor.moveToNext()) sourceIndexes += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
        }
        assertTrue(sourceIndexes.contains("idx_expenses_tasks_station_open_due"))
        assertTrue(sourceIndexes.contains("idx_sales_tasks_station_open_due"))
        assertTrue(sourceIndexes.contains("idx_products_tasks_station_active"))

        repeat(3) { index ->
            helper.addTask(
                JSONObject()
                    .put("task_type", "بحث قابل للترقيم")
                    .put("reference", "PAGED-TASK-$index")
                    .put("task_date", "2026-11-03"),
                stationId = 1
            )
        }
        val first = helper.getPendingTasks(1, JSONObject().put("search", "PAGED-TASK-").put("limit", 2).put("offset", 0))
        val second = helper.getPendingTasks(1, JSONObject().put("search", "PAGED-TASK-").put("limit", 2).put("offset", 2))
        assertEquals(2, first.length())
        assertEquals(1, second.length())
        assertFalse(first.getJSONObject(0).getLong("id") == second.getJSONObject(0).getLong("id"))
    }

    private fun insertUser(stationId: Long, suffix: String, roleId: Long = 1L): Long {
        return helper.writableDatabase.insertOrThrow(
            "users",
            null,
            ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("username", suffix)
                put("password_hash", "test-hash")
                put("password_salt", "test-salt")
                put("full_name", suffix)
                put("role_id", roleId)
                put("station_id", stationId)
                put("status", "active")
                put("is_deleted", 0)
            }
        )
    }

    private fun insertShift(stationId: Long, suffix: String): Long {
        return helper.writableDatabase.insertOrThrow(
            "shifts",
            null,
            ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("shift_code", suffix)
                put("station_id", stationId)
                put("shift_date", "2026-11-03")
                put("shift_type", "morning")
                put("start_time", "2026-11-03 08:00:00")
            }
        )
    }

    private fun insertCreditSale(stationId: Long, shiftId: Long, cashierId: Long, suffix: String): Long {
        return helper.writableDatabase.insertOrThrow(
            "sales_transactions",
            null,
            ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("sale_code", suffix)
                put("station_id", stationId)
                put("shift_id", shiftId)
                put("subtotal", 100.0)
                put("gross_amount", 100.0)
                put("net_amount", 100.0)
                put("cashier_id", cashierId)
                put("payment_method", "credit")
                put("payment_status", "partial")
                put("paid_amount", 75.0)
                put("remaining_amount", 25.0)
                put("is_credit", 1)
                put("due_date", "2020-01-01")
                put("status", "completed")
                put("is_deleted", 0)
            }
        )
    }

    private fun insertOutstandingExpense(stationId: Long, suffix: String): Long {
        val categoryId = helper.writableDatabase.insertOrThrow(
            "expense_categories",
            null,
            ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("category_code", suffix)
                put("category_name", suffix)
                put("category_name_ar", suffix)
                put("is_active", 1)
                put("is_deleted", 0)
            }
        )
        return helper.writableDatabase.insertOrThrow(
            "expenses",
            null,
            ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("expense_code", suffix)
                put("expense_category_id", categoryId)
                put("station_id", stationId)
                put("payee_name", "مورد الاختبار")
                put("payee_name_ar", "مورد الاختبار")
                put("amount", 50.0)
                put("total_amount", 50.0)
                put("paid_amount", 10.0)
                put("description", "مصروف مستحق للاختبار")
                put("description_ar", "مصروف مستحق للاختبار")
                put("next_due_date", "2020-01-01")
                put("status", "approved")
                put("payment_status", "approved")
                put("is_deleted", 0)
            }
        )
    }

    private fun insertWarehouse(stationId: Long, suffix: String): Long {
        return helper.writableDatabase.insertOrThrow(
            "warehouses",
            null,
            ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("station_id", stationId)
                put("warehouse_name", suffix)
                put("is_active", 1)
            }
        )
    }

    private fun insertLowStockProduct(stationId: Long, warehouseId: Long, suffix: String): Long {
        val categoryId = helper.writableDatabase.rawQuery(
            "SELECT id FROM product_categories WHERE category_type = 'product' AND is_deleted = 0 ORDER BY id LIMIT 1",
            null
        ).use { cursor ->
            require(cursor.moveToFirst()) { "Product reference category is required for this test" }
            cursor.getLong(0)
        }
        val unitId = helper.writableDatabase.rawQuery(
            "SELECT id FROM units WHERE category = 'product' ORDER BY id LIMIT 1",
            null
        ).use { cursor ->
            require(cursor.moveToFirst()) { "Product reference unit is required for this test" }
            cursor.getLong(0)
        }
        val productId = helper.writableDatabase.insertOrThrow(
            "products",
            null,
            ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("product_code", suffix)
                put("product_name", suffix)
                put("product_name_ar", suffix)
                put("category_id", categoryId)
                put("unit_id", unitId)
                put("station_id", stationId)
                put("purchase_price", 1.0)
                put("sale_price", 2.0)
                put("minimum_stock", 10.0)
                put("status", "active")
                put("is_deleted", 0)
            }
        )
        helper.writableDatabase.insertOrThrow(
            "inventory_levels",
            null,
            ContentValues().apply {
                put("product_id", productId)
                put("warehouse_id", warehouseId)
                put("quantity_on_hand", 3.0)
            }
        )
        return productId
    }

    private fun rowById(rows: org.json.JSONArray, id: Long): JSONObject? {
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            if (row.optLong("id") == id) return row
        }
        return null
    }

    private fun rowBySource(rows: org.json.JSONArray, source: String, sourceId: Long): JSONObject {
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            if (row.optString("source_kind") == source && row.optLong("source_id") == sourceId) return row
        }
        throw AssertionError("Missing $source row for source id $sourceId")
    }

    private fun containsId(rows: org.json.JSONArray, id: Long): Boolean = rowById(rows, id) != null

    private fun containsSource(rows: org.json.JSONArray, source: String, sourceId: Long): Boolean =
        runCatching { rowBySource(rows, source, sourceId) }.isSuccess
}
