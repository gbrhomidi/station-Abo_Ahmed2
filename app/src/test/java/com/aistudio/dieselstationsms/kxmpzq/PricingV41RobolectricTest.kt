package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
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
    fun v43SchemaAndMigrationContractArePresent() {
        assertEquals(43, DatabaseHelper.VERSION)
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
        assertTrue(db.rawQuery("PRAGMA table_info(sales_transactions)", null).use { c ->
            var hasBusinessDay = false
            while (c.moveToNext()) hasBusinessDay = hasBusinessDay || c.getString(c.getColumnIndexOrThrow("name")) == "business_day"
            hasBusinessDay
        })
        assertTrue(db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='index' AND name='idx_sales_station_business_day_deleted_payment'", null).use { it.moveToFirst() })
        assertTrue(db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='index' AND name='idx_sales_station_payment_business_day_deleted'", null).use { it.moveToFirst() })
    }

    @Test
    fun upgradesRepresentativeV40V41AndV42SchemasToV43WithoutLosingData() {
        for (sourceVersion in 40..42) {
            DatabaseHelper.closeInstance()
            context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
            helper = DatabaseHelper.getInstance(context)
            val oldDb = helper.writableDatabase

            oldDb.execSQL("CREATE TABLE migration_probe (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
            oldDb.execSQL("INSERT INTO migration_probe(id, value) VALUES (1, 'preserve-me')")

            if (sourceVersion <= 41) {
                oldDb.execSQL("DROP INDEX IF EXISTS idx_price_list_items_quantity_limit")
                oldDb.execSQL("ALTER TABLE price_list_items DROP COLUMN quantity_limit")
                oldDb.execSQL("ALTER TABLE price_list_items DROP COLUMN quantity_sold")
                oldDb.execSQL("ALTER TABLE sale_items DROP COLUMN cost_price")
                oldDb.execSQL("ALTER TABLE sale_items DROP COLUMN total_cost")
                oldDb.execSQL("ALTER TABLE fuel_sales DROP COLUMN cost_per_liter")
                oldDb.execSQL("ALTER TABLE fuel_sales DROP COLUMN cost_amount")
            }
            if (sourceVersion == 40) {
                oldDb.execSQL("DROP INDEX IF EXISTS idx_price_lists_resolution")
                oldDb.execSQL("DROP INDEX IF EXISTS idx_sales_business_day")
                oldDb.execSQL("DROP INDEX IF EXISTS idx_sales_station_business_day_deleted_payment")
                oldDb.execSQL("DROP INDEX IF EXISTS idx_sales_station_payment_business_day_deleted")
                oldDb.execSQL("DROP TRIGGER IF EXISTS trg_fuel_types_sale_price_history")
                oldDb.execSQL("DROP TRIGGER IF EXISTS trg_products_sale_price_history")
                oldDb.execSQL("DROP TABLE IF EXISTS fuel_price_history")
                oldDb.execSQL("ALTER TABLE sales_transactions DROP COLUMN business_day")
                oldDb.execSQL("ALTER TABLE price_lists DROP COLUMN occasion_code")
                oldDb.execSQL("ALTER TABLE price_lists DROP COLUMN occasion_name_ar")
                oldDb.execSQL("ALTER TABLE price_lists DROP COLUMN applies_when")
                oldDb.execSQL("ALTER TABLE price_lists DROP COLUMN clearance_mode")
                oldDb.execSQL("ALTER TABLE price_lists DROP COLUMN clearance_stock_below")
                oldDb.execSQL("ALTER TABLE price_lists DROP COLUMN priority")
            }

            // Force the real SQLiteOpenHelper upgrade dispatcher to run each step
            // from the selected historical version rather than calling migrations
            // directly or bypassing their version ordering.
            oldDb.execSQL("UPDATE permissions SET is_active=0 WHERE permission_code='price_lists.read'")
            oldDb.version = sourceVersion
            DatabaseHelper.closeInstance()

            helper = DatabaseHelper.getInstance(context)
            val upgradedDb = helper.writableDatabase
            assertEquals("upgrade from $sourceVersion must reach the current schema", DatabaseHelper.VERSION, upgradedDb.version)

            assertTrue(upgradedDb.rawQuery("PRAGMA table_info(sales_transactions)", null).use { cursor ->
                var found = false
                while (cursor.moveToNext()) found = found || cursor.getString(cursor.getColumnIndexOrThrow("name")) == "business_day"
                found
            })
            assertTrue(upgradedDb.rawQuery("PRAGMA table_info(price_list_items)", null).use { cursor ->
                val columns = mutableSetOf<String>()
                while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                columns.containsAll(setOf("quantity_limit", "quantity_sold"))
            })
            assertTrue(upgradedDb.rawQuery("PRAGMA table_info(sale_items)", null).use { cursor ->
                val columns = mutableSetOf<String>()
                while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
                columns.containsAll(setOf("cost_price", "total_cost"))
            })
            assertTrue(upgradedDb.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='fuel_price_history'", null).use { it.moveToFirst() })
            assertTrue(upgradedDb.rawQuery("SELECT 1 FROM permissions WHERE permission_code='price_lists.read' AND is_active=1 AND module='price_lists' AND action='read'", null).use { it.moveToFirst() })
            upgradedDb.rawQuery("SELECT value FROM migration_probe WHERE id=1", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("preserve-me", it.getString(0))
            }
        }
    }

    @Test
    fun salesPaymentRangeQueryUsesMigratedIndexAndMeetsLatencyBudget() {
        val db = helper.writableDatabase
        val stationId = db.rawQuery("SELECT station_id FROM users WHERE id=?", arrayOf(actorId.toString())).use {
            check(it.moveToFirst())
            it.getLong(0)
        }
        val shiftId = db.insertOrThrow("shifts", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("shift_code", "PERF-${UUID.randomUUID().toString().take(8)}")
            put("station_id", stationId)
            put("shift_date", "2026-01-01")
            put("shift_type", "morning")
            put("start_time", "2026-01-01 06:00:00")
            put("cashier_id", actorId)
        })

        val insert = db.compileStatement("""
            INSERT INTO sales_transactions
                (uuid, sale_code, station_id, shift_id, subtotal, gross_amount, net_amount,
                 payment_method, cashier_id, business_day, is_deleted)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
        """.trimIndent())
        val dayValues = List(180) { LocalDate.of(2026, 1, 1).plusDays(it.toLong()).toString() }
        db.beginTransaction()
        try {
            repeat(24_000) { index ->
                val amount = ((index % 500) + 1).toDouble()
                insert.clearBindings()
                insert.bindString(1, "perf-uuid-$index")
                insert.bindString(2, "PERF-SALE-$index")
                insert.bindLong(3, stationId)
                insert.bindLong(4, shiftId)
                insert.bindDouble(5, amount)
                insert.bindDouble(6, amount)
                insert.bindDouble(7, amount)
                insert.bindString(8, if (index % 4 == 0) "credit_card" else "cash")
                insert.bindLong(9, actorId)
                insert.bindString(10, dayValues[(index * 17) % dayValues.size])
                insert.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            insert.close()
        }

        val query = """
            SELECT COUNT(*), COALESCE(SUM(net_amount), 0)
            FROM sales_transactions
            WHERE station_id=? AND payment_method=? AND business_day BETWEEN ? AND ? AND is_deleted=0
        """.trimIndent()
        val args = arrayOf(stationId.toString(), "credit_card", "2026-01-03", "2026-01-09")
        fun plan(database: android.database.sqlite.SQLiteDatabase): String = database.rawQuery("EXPLAIN QUERY PLAN $query", args).use { cursor ->
            val details = mutableListOf<String>()
            while (cursor.moveToNext()) details += cursor.getString(3)
            details.joinToString(" | ")
        }
        fun p95Millis(database: android.database.sqlite.SQLiteDatabase): Double {
            val samples = (0 until 7).map {
                val started = System.nanoTime()
                database.rawQuery(query, args).use { cursor -> check(cursor.moveToFirst()) }
                (System.nanoTime() - started) / 1_000_000.0
            }.sorted()
            return samples[6]
        }

        db.execSQL("DROP INDEX IF EXISTS idx_sales_station_business_day_deleted_payment")
        db.execSQL("DROP INDEX IF EXISTS idx_sales_station_payment_business_day_deleted")
        db.version = 42
        val oldPlan = plan(db)
        assertFalse(oldPlan.contains("idx_sales_station_payment_business_day_deleted"))
        val beforeUpgradeP95 = p95Millis(db)
        DatabaseHelper.closeInstance()

        helper = DatabaseHelper.getInstance(context)
        val upgradedDb = helper.writableDatabase
        assertEquals(DatabaseHelper.VERSION, upgradedDb.version)
        val upgradedPlan = plan(upgradedDb)
        assertTrue("Expected the migrated report index, got: $upgradedPlan", upgradedPlan.contains("idx_sales_station_payment_business_day_deleted"))
        upgradedDb.rawQuery(query, args).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue("Fixture must exercise a non-empty selective range", cursor.getLong(0) > 0)
        }
        val afterUpgradeP95 = p95Millis(upgradedDb)
        assertTrue("Indexed report query exceeded 1 second p95: ${afterUpgradeP95}ms; plan=$upgradedPlan", afterUpgradeP95 < 1_000.0)
        println("Sales report query p95: before=${"%.2f".format(beforeUpgradeP95)}ms after=${"%.2f".format(afterUpgradeP95)}ms; plan=$upgradedPlan")
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
