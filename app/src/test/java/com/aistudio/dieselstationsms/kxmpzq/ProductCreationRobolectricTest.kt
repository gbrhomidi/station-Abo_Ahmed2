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
class ProductCreationRobolectricTest {
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
    fun `new product saves with automatic code using SQLite reference data`() {
        val db = helper.writableDatabase
        val roleId = db.rawQuery("SELECT id FROM roles ORDER BY id LIMIT 1", null).use { cursor ->
            check(cursor.moveToFirst()) { "لا يوجد دور افتراضي للاختبار" }
            cursor.getLong(0)
        }
        val actorId = db.insertOrThrow("users", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("username", "product-create-test")
            put("password_hash", "test-only")
            put("full_name", "مستخدم اختبار المنتجات")
            put("role_id", roleId)
            put("station_id", 1)
        })
        val categoryId = db.rawQuery("SELECT id FROM product_categories WHERE is_deleted = 0 ORDER BY id LIMIT 1", null).use { cursor ->
            check(cursor.moveToFirst()) { "لا توجد فئة مرجعية صالحة" }
            cursor.getLong(0)
        }
        val unitId = db.rawQuery("SELECT id FROM units ORDER BY id LIMIT 1", null).use { cursor ->
            check(cursor.moveToFirst()) { "لا توجد وحدة مرجعية صالحة" }
            cursor.getLong(0)
        }

        val productId = helper.insertProduct(
            JSONObject()
                .put("product_name", "منتج اختبار محفوظ")
                .put("product_name_ar", "منتج اختبار محفوظ")
                .put("category_id", categoryId)
                .put("unit_id", unitId)
                .put("purchase_price", 10.0)
                .put("sale_price", 12.0)
                .put("quantity", 0.0)
                .put("minimum_stock", 0.0),
            1,
            actorId
        )

        assertTrue(productId > 0L)
        db.rawQuery("SELECT product_code FROM products WHERE id = ? AND station_id = ?", arrayOf(productId.toString(), "1")).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.getString(0).startsWith("PRD-001-"))
        }
        assertTrue(db.rawQuery("PRAGMA foreign_key_check", null).use { cursor -> !cursor.moveToFirst() })
    }

    @Test
    fun `stock adjustment is persisted as inventory movement and reflected by product list`() {
        val db = helper.writableDatabase
        val roleId = db.rawQuery("SELECT id FROM roles ORDER BY id LIMIT 1", null).use { cursor ->
            check(cursor.moveToFirst()) { "لا يوجد دور افتراضي للاختبار" }
            cursor.getLong(0)
        }
        val actorId = db.insertOrThrow("users", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("username", "product-stock-test")
            put("password_hash", "test-only")
            put("full_name", "مستخدم اختبار رصيد المنتجات")
            put("role_id", roleId)
            put("station_id", 1)
        })
        val categoryId = db.rawQuery("SELECT id FROM product_categories WHERE is_deleted = 0 ORDER BY id LIMIT 1", null).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
        val unitId = db.rawQuery("SELECT id FROM units ORDER BY id LIMIT 1", null).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
        val productId = helper.insertProduct(JSONObject()
            .put("product_name", "منتج تسوية المخزون")
            .put("product_name_ar", "منتج تسوية المخزون")
            .put("category_id", categoryId)
            .put("unit_id", unitId)
            .put("purchase_price", 8.0)
            .put("sale_price", 10.0)
            .put("quantity", 3.0)
            .put("minimum_stock", 1.0), 1, actorId)

        assertEquals(8.0, helper.adjustProductStock(productId, 8.0, 1, actorId), 0.0001)
        val product = helper.getProducts(1).let { rows ->
            (0 until rows.length()).map { rows.getJSONObject(it) }.first { it.getLong("product_id") == productId }
        }
        assertEquals(8.0, product.getDouble("current_stock"), 0.0001)
        assertTrue(db.rawQuery("SELECT quantity_change FROM inventory_movements WHERE product_id = ? AND movement_type = 'adjustment'", arrayOf(productId.toString())).use { cursor -> cursor.moveToFirst() && cursor.getDouble(0) == 5.0 })
        assertTrue(db.rawQuery("PRAGMA foreign_key_check", null).use { cursor -> !cursor.moveToFirst() })
    }
}
