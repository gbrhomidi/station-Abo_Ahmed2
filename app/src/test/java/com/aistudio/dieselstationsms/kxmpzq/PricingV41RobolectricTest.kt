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
import java.time.ZonedDateTime
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PricingV41RobolectricTest {
    private lateinit var context: Context
    private lateinit var helper: DatabaseHelper
    private var actorId = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
        helper = DatabaseHelper.getInstance(context)
        val db = helper.writableDatabase
        val roleId = db.rawQuery("SELECT id FROM roles ORDER BY id LIMIT 1", null).use { check(it.moveToFirst()); it.getLong(0) }
        actorId = db.insertOrThrow("users", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("username", "pricing-v41-${UUID.randomUUID().toString().take(8)}")
            put("password_hash", "test-only")
            put("full_name", "اختبار التسعير V41")
            put("role_id", roleId)
            put("station_id", 1)
            put("is_deleted", 0)
            put("status", "active")
        })
    }

    @After
    fun tearDown() {
        DatabaseHelper.closeInstance()
        context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
    }

    @Test
    fun v42SchemaAndMigrationContractArePresent() {
        assertEquals(42, DatabaseHelper.VERSION)
        val db = helper.writableDatabase
        assertTrue(db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='fuel_price_history'", null).use { it.moveToFirst() })
        assertTrue(db.rawQuery("PRAGMA table_info(price_lists)", null).use { c ->
            val names = mutableSetOf<String>(); while (c.moveToNext()) names += c.getString(c.getColumnIndexOrThrow("name"));
            names.containsAll(setOf("occasion_code", "occasion_name_ar", "applies_when", "clearance_mode", "clearance_stock_below", "priority"))
        })
        assertTrue(db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='trigger' AND name='trg_products_sale_price_history'", null).use { it.moveToFirst() })
        assertTrue(db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='trigger' AND name='trg_fuel_types_sale_price_history'", null).use { it.moveToFirst() })
        assertTrue(db.rawQuery("PRAGMA table_info(price_list_items)", null).use { c ->
            val names = mutableSetOf<String>(); while (c.moveToNext()) names += c.getString(c.getColumnIndexOrThrow("name"))
            names.containsAll(setOf("quantity_limit", "quantity_sold"))
        })
        assertTrue(db.rawQuery("PRAGMA table_info(sale_items)", null).use { c ->
            val names = mutableSetOf<String>(); while (c.moveToNext()) names += c.getString(c.getColumnIndexOrThrow("name"))
            names.containsAll(setOf("cost_price", "total_cost"))
        })
    }

    @Test
    fun businessDayBoundariesAreCalendarBoundaries() {
        val beforeMidnight = ZonedDateTime.parse("2026-10-06T23:59:59+03:00[Asia/Riyadh]")
        val midnight = ZonedDateTime.parse("2026-10-07T00:00:00+03:00[Asia/Riyadh]")
        val afterMidnight = ZonedDateTime.parse("2026-10-07T00:01:00+03:00[Asia/Riyadh]")
        val twoAm = ZonedDateTime.parse("2026-10-07T02:00:00+03:00[Asia/Riyadh]")
        assertEquals("2026-10-06", BusinessDay.currentDate(now = beforeMidnight))
        assertEquals("2026-10-07", BusinessDay.currentDate(now = midnight))
        assertEquals("2026-10-07", BusinessDay.currentDate(now = afterMidnight))
        assertEquals("2026-10-07", BusinessDay.currentDate(now = twoAm))
    }

    @Test
    fun productPriceResolverUsesValidPriceListAndExclusiveEnd() {
        val db = helper.writableDatabase
        val categoryId = db.rawQuery("SELECT id FROM product_categories WHERE is_deleted=0 ORDER BY id LIMIT 1", null).use { check(it.moveToFirst()); it.getLong(0) }
        val unitId = db.rawQuery("SELECT id FROM units ORDER BY id LIMIT 1", null).use { check(it.moveToFirst()); it.getLong(0) }
        val productId = helper.insertProduct(JSONObject()
            .put("product_name", "V41 Price Test")
            .put("product_name_ar", "اختبار سعر V41")
            .put("category_id", categoryId)
            .put("unit_id", unitId)
            .put("purchase_price", 5.0)
            .put("sale_price", 100.0), 1, actorId)
        val listId = db.insertOrThrow("price_lists", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("list_code", "V41-${UUID.randomUUID().toString().take(6)}")
            put("list_name", "Cross midnight"); put("list_name_ar", "سعر عابر للمنتصف"); put("station_id", 1)
            put("valid_from", "2026-10-06 20:00:00"); put("valid_to", "2026-10-07 02:00:00"); put("priority", 100); put("is_active", 1); put("is_deleted", 0)
        })
        helper.insertPriceListItem(JSONObject().put("price_list_id", listId).put("product_id", productId).put("unit_price", 90.0), actorId, 1)
        assertEquals(90.0, helper.resolveProductSalePrice(productId, 1, null, "2026-10-07 01:59:59").unitPrice, 0.0001)
        assertEquals(100.0, helper.resolveProductSalePrice(productId, 1, null, "2026-10-07 02:00:00").unitPrice, 0.0001)
    }

    @Test
    fun occasionPriceListRequiresMatchingOccasionCode() {
        val db = helper.writableDatabase
        val categoryId = db.rawQuery("SELECT id FROM product_categories WHERE is_deleted=0 ORDER BY id LIMIT 1", null).use { check(it.moveToFirst()); it.getLong(0) }
        val unitId = db.rawQuery("SELECT id FROM units ORDER BY id LIMIT 1", null).use { check(it.moveToFirst()); it.getLong(0) }
        val productId = helper.insertProduct(JSONObject()
            .put("product_name", "Occasion Price Test")
            .put("product_name_ar", "اختبار سعر المناسبة")
            .put("category_id", categoryId)
            .put("unit_id", unitId)
            .put("purchase_price", 5.0)
            .put("sale_price", 100.0), 1, actorId)
        val listId = db.insertOrThrow("price_lists", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("list_code", "OCC-${UUID.randomUUID().toString().take(6)}")
            put("list_name", "Foundation day")
            put("list_name_ar", "عرض يوم التأسيس")
            put("station_id", 1)
            put("valid_from", "2026-10-01 00:00:00")
            put("valid_to", "2026-10-31 23:59:59")
            put("applies_when", "occasion")
            put("occasion_code", "FOUNDATION_DAY")
            put("priority", 100)
            put("is_active", 1)
            put("is_deleted", 0)
        })
        helper.insertPriceListItem(
            JSONObject().put("price_list_id", listId).put("product_id", productId).put("unit_price", 75.0),
            actorId, 1
        )
        assertEquals(100.0, helper.resolveProductSalePrice(productId, 1, null, "2026-10-07 12:00:00").unitPrice, 0.0001)
        assertEquals(100.0, helper.resolveProductSalePrice(productId, 1, null, "2026-10-07 12:00:00", "OTHER_EVENT").unitPrice, 0.0001)
        assertEquals(75.0, helper.resolveProductSalePrice(productId, 1, null, "2026-10-07 12:00:00", "FOUNDATION_DAY").unitPrice, 0.0001)
    }

    @Test
    fun productPriceChangeIsAtomicAndAuditedByTrigger() {
        val db = helper.writableDatabase
        val categoryId = db.rawQuery("SELECT id FROM product_categories WHERE is_deleted=0 ORDER BY id LIMIT 1", null).use { check(it.moveToFirst()); it.getLong(0) }
        val unitId = db.rawQuery("SELECT id FROM units ORDER BY id LIMIT 1", null).use { check(it.moveToFirst()); it.getLong(0) }
        val productId = helper.insertProduct(JSONObject().put("product_name", "Audit Test").put("product_name_ar", "اختبار تدقيق").put("category_id", categoryId).put("unit_id", unitId).put("purchase_price", 5.0).put("sale_price", 10.0), 1, actorId)
        helper.changeProductSalePrice(productId, 12.0, 1, actorId, "اختبار V41")
        db.rawQuery("SELECT old_price,new_price,created_by,change_reason FROM price_history WHERE product_id=? ORDER BY id DESC LIMIT 1", arrayOf(productId.toString())).use {
            check(it.moveToFirst())
            assertEquals(10.0, it.getDouble(0), 0.0001)
            assertEquals(12.0, it.getDouble(1), 0.0001)
            assertEquals(actorId, it.getLong(2))
            assertEquals("اختبار V41", it.getString(3))
        }
    }
}
