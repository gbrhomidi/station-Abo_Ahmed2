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
import java.util.TimeZone

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
    fun v44SchemaAndMigrationContractArePresent() {
        assertTrue("V44 migration must remain part of the current schema", DatabaseHelper.VERSION >= 44)
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
    fun upgradesRepresentativeV40V41AndV42SchemasToCurrentVersionWithoutLosingData() {
        for (sourceVersion in 40..42) {
            DatabaseHelper.closeInstance()
            context.deleteDatabase(DatabaseHelper.DATABASE_NAME)
            helper = DatabaseHelper.getInstance(context)
            val oldDb = helper.writableDatabase

            oldDb.execSQL("CREATE TABLE migration_probe (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
            oldDb.execSQL("INSERT INTO migration_probe(id, value) VALUES (1, 'preserve-me')")

            if (sourceVersion <= 41) {
                oldDb.execSQL("DROP INDEX IF EXISTS idx_price_list_items_quantity_limit")
                dropColumnForHistoricalFixture(oldDb, "price_list_items", "quantity_limit")
                dropColumnForHistoricalFixture(oldDb, "price_list_items", "quantity_sold")
                dropColumnForHistoricalFixture(oldDb, "sale_items", "cost_price")
                dropColumnForHistoricalFixture(oldDb, "sale_items", "total_cost")
                dropColumnForHistoricalFixture(oldDb, "fuel_sales", "cost_per_liter")
                dropColumnForHistoricalFixture(oldDb, "fuel_sales", "cost_amount")
            }
            if (sourceVersion == 40) {
                oldDb.execSQL("DROP INDEX IF EXISTS idx_price_lists_resolution")
                oldDb.execSQL("DROP INDEX IF EXISTS idx_sales_business_day")
                oldDb.execSQL("DROP INDEX IF EXISTS idx_sales_station_business_day_deleted_payment")
                oldDb.execSQL("DROP INDEX IF EXISTS idx_sales_station_payment_business_day_deleted")
                oldDb.execSQL("DROP TRIGGER IF EXISTS trg_fuel_types_sale_price_history")
                oldDb.execSQL("DROP TRIGGER IF EXISTS trg_products_sale_price_history")
                oldDb.execSQL("DROP TABLE IF EXISTS fuel_price_history")
                dropColumnForHistoricalFixture(oldDb, "sales_transactions", "business_day")
                dropColumnForHistoricalFixture(oldDb, "price_lists", "occasion_code")
                dropColumnForHistoricalFixture(oldDb, "price_lists", "occasion_name_ar")
                dropColumnForHistoricalFixture(oldDb, "price_lists", "applies_when")
                dropColumnForHistoricalFixture(oldDb, "price_lists", "clearance_mode")
                dropColumnForHistoricalFixture(oldDb, "price_lists", "clearance_stock_below")
                dropColumnForHistoricalFixture(oldDb, "price_lists", "priority")
            }

            // Force the real SQLiteOpenHelper upgrade dispatcher to run each step
            // from the selected historical version rather than calling migrations
            // directly or bypassing their version ordering.
            oldDb.execSQL("UPDATE permissions SET is_active=0 WHERE permission_code='price_lists.read'")
            oldDb.version = sourceVersion
            DatabaseHelper.closeInstance()

            helper = DatabaseHelper.getInstance(context)
            val upgradedDb = helper.writableDatabase
            assertEquals("upgrade from $sourceVersion must reach the current schema version", DatabaseHelper.VERSION, upgradedDb.version)

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
        assertEquals("2026-10-06", BusinessDay.currentDate(zoneId = "Asia/Riyadh", now = beforeMidnight))
        assertEquals("2026-10-07", BusinessDay.currentDate(zoneId = "Asia/Riyadh", now = midnight))
        assertEquals("2026-10-07", BusinessDay.currentDate(zoneId = "Asia/Riyadh", now = afterMidnight))
        assertEquals("2026-10-07", BusinessDay.currentDate(zoneId = "Asia/Riyadh", now = twoAm))
    }

    @Test
    fun businessDayDefaultsToDeviceTimeZoneAndRollsAtLocalMidnight() {
        val previousTimeZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val beforeMidnight = ZonedDateTime.parse("2026-10-07T23:59:59-04:00[America/New_York]")
            val midnight = ZonedDateTime.parse("2026-10-08T00:00:00-04:00[America/New_York]")

            assertEquals("America/New_York", BusinessDay.DEFAULT_ZONE_ID)
            assertEquals("2026-10-07", BusinessDay.currentDate(now = beforeMidnight))
            assertEquals("2026-10-08", BusinessDay.currentDate(now = midnight))
            assertEquals("2026-10-08 00:00:00", BusinessDay.context(now = midnight).startDateTime)
            assertEquals("America/New_York", BusinessDay.context(now = midnight).zoneId)
        } finally {
            TimeZone.setDefault(previousTimeZone)
        }
    }

    @Test
    fun dashboardDailyCardsUseLocalBusinessDayAcrossUtcMidnightOffset() {
        val db = helper.writableDatabase
        val stationId = db.rawQuery("SELECT station_id FROM users WHERE id=?", arrayOf(actorId.toString())).use {
            check(it.moveToFirst())
            it.getLong(0)
        }
        val shiftId = db.insertOrThrow("shifts", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("shift_code", "DASH-${UUID.randomUUID().toString().take(8)}")
            put("station_id", stationId)
            put("shift_date", "2026-10-07")
            put("shift_type", "night")
            put("start_time", "2026-10-07 18:00:00")
            put("cashier_id", actorId)
        })
        fun insertSale(code: String, createdAtUtc: String, day: String, amount: Double, credit: Boolean): Long =
            db.insertOrThrow("sales_transactions", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("sale_code", code)
                put("station_id", stationId)
                put("shift_id", shiftId)
                put("subtotal", amount)
                put("gross_amount", amount)
                put("net_amount", amount)
                put("payment_method", if (credit) "credit" else "cash")
                put("is_credit", if (credit) 1 else 0)
                put("cashier_id", actorId)
                put("created_at", createdAtUtc)
                put("business_day", day)
                put("is_deleted", 0)
            })

        // In Riyadh, 20:59:59Z is 23:59:59 local; 21:00:00Z is the next day's midnight.
        insertSale("DASH-BEFORE", "2026-10-07 20:59:59", "2026-10-07", 100.0, credit = false)
        val nextDaySaleId = insertSale("DASH-AFTER", "2026-10-07 21:00:00", "2026-10-08", 200.0, credit = true)
        db.insertOrThrow("payments", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("payment_code", "DASH-PAY-${UUID.randomUUID().toString().take(8)}")
            put("sale_id", nextDaySaleId)
            put("payment_type", "cash")
            put("payment_method", "cash")
            put("amount", 45.0)
            put("status", "completed")
            put("created_at", "2026-10-07 21:01:00")
            put("business_day", "2026-10-08")
        })

        val previousDay = helper.getDashboardStatsForDate(stationId.toInt(), JSONObject(), "2026-10-07")
        val newDay = helper.getDashboardStatsForDate(stationId.toInt(), JSONObject(), "2026-10-08")
        assertEquals(100.0, previousDay.getDouble("daily_sales"), 0.001)
        assertEquals(100.0, previousDay.getDouble("cash_sales"), 0.001)
        assertEquals(0.0, previousDay.getDouble("credit_sales"), 0.001)
        assertEquals(100.0, previousDay.getDouble("total_sales"), 0.001)
        assertEquals(1, previousDay.getInt("transactions_today"))
        assertEquals(200.0, newDay.getDouble("daily_sales"), 0.001)
        assertEquals(0.0, newDay.getDouble("cash_sales"), 0.001)
        assertEquals(200.0, newDay.getDouble("credit_sales"), 0.001)
        assertEquals(45.0, newDay.getDouble("collected_amount"), 0.001)
        assertEquals(200.0, newDay.getDouble("total_sales"), 0.001)
        assertEquals(1, newDay.getInt("transactions_today"))
    }

    @Test
    fun v44CorrectsPreviouslyBackfilledUtcSalesDateWithoutChangingCorrectLocalDates() {
        val previousTimeZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Riyadh"))
            val db = helper.writableDatabase
            val stationId = db.rawQuery("SELECT station_id FROM users WHERE id=?", arrayOf(actorId.toString())).use {
                check(it.moveToFirst())
                it.getLong(0)
            }
            val shiftId = db.insertOrThrow("shifts", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("shift_code", "V44-${UUID.randomUUID().toString().take(8)}")
                put("station_id", stationId)
                put("shift_date", "2026-10-07")
                put("shift_type", "night")
                put("start_time", "2026-10-07 18:00:00")
                put("cashier_id", actorId)
            })
            fun insertSale(code: String, createdAt: String, businessDay: String): Long = db.insertOrThrow(
                "sales_transactions", null, ContentValues().apply {
                    put("uuid", UUID.randomUUID().toString())
                    put("sale_code", code)
                    put("station_id", stationId)
                    put("shift_id", shiftId)
                    put("subtotal", 10.0)
                    put("gross_amount", 10.0)
                    put("net_amount", 10.0)
                    put("cashier_id", actorId)
                    put("created_at", createdAt)
                    put("business_day", businessDay)
                }
            )

            val utcBackfilledRow = insertSale("V44-UTC-1", "2026-10-07 21:00:00", "2026-10-07")
            val alreadyCorrectRow = insertSale("V44-LOCAL-1", "2026-10-07 21:00:00", "2026-10-08")
            val sameCalendarDayRow = insertSale("V44-SAME-1", "2026-10-07 20:30:00", "2026-10-07")
            db.version = 43
            DatabaseHelper.closeInstance()

            helper = DatabaseHelper.getInstance(context)
            val upgradedDb = helper.writableDatabase
            assertEquals(DatabaseHelper.VERSION, upgradedDb.version)
            fun dayFor(id: Long): String = upgradedDb.rawQuery(
                "SELECT business_day FROM sales_transactions WHERE id=?", arrayOf(id.toString())
            ).use { check(it.moveToFirst()); it.getString(0) }
            assertEquals("2026-10-08", dayFor(utcBackfilledRow))
            assertEquals("2026-10-08", dayFor(alreadyCorrectRow))
            assertEquals("2026-10-07", dayFor(sameCalendarDayRow))
        } finally {
            TimeZone.setDefault(previousTimeZone)
        }
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

    /**
     * Rebuilds a current table into a historical test-fixture shape.
     *
     * SQLite DROP COLUMN support and its restrictions vary across Android/Robolectric
     * SQLite builds. Rebuilding from sqlite_master keeps this migration test portable
     * while preserving the existing rows, table constraints, unrelated indexes,
     * triggers, and AUTOINCREMENT state. Objects that depend on the removed column
     * are intentionally omitted because they did not exist in the historical schema.
     */
    private fun dropColumnForHistoricalFixture(
        db: android.database.sqlite.SQLiteDatabase,
        table: String,
        column: String
    ) {
        fun quoteIdentifier(identifier: String): String =
            "`" + identifier.replace("`", "``") + "`"

        fun columnsOf(tableName: String): List<String> =
            db.rawQuery("PRAGMA table_info(${quoteIdentifier(tableName)})", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }

        val originalColumns = columnsOf(table)
        if (column !in originalColumns) return

        val createSql = db.rawQuery(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(table)
        ).use { cursor ->
            check(cursor.moveToFirst()) { "Missing CREATE TABLE SQL for historical fixture table $table" }
            cursor.getString(0)
        }

        val opening = createSql.indexOf('(')
        check(opening >= 0) { "Invalid CREATE TABLE SQL for $table" }

        // Split the top-level column/constraint list without splitting commas inside
        // type declarations, CHECK expressions, DEFAULT values, or quoted identifiers.
        val definitions = mutableListOf<String>()
        var depth = 1
        var quote: Char? = null
        var segmentStart = opening + 1
        var closing = -1
        var index = opening + 1
        while (index < createSql.length) {
            val ch = createSql[index]
            if (quote != null) {
                if (quote == ']' && ch == ']') {
                    quote = null
                } else if (quote != ']' && ch == quote) {
                    if (index + 1 < createSql.length && createSql[index + 1] == quote) {
                        index++
                    } else {
                        quote = null
                    }
                }
            } else {
                when (ch) {
                    '\'', '"', '`' -> quote = ch
                    '[' -> quote = ']'
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            definitions += createSql.substring(segmentStart, index).trim()
                            closing = index
                            break
                        }
                    }
                    ',' -> if (depth == 1) {
                        definitions += createSql.substring(segmentStart, index).trim()
                        segmentStart = index + 1
                    }
                }
            }
            index++
        }
        check(closing > opening && definitions.isNotEmpty()) { "Could not parse CREATE TABLE body for $table" }

        fun definedColumnName(definition: String): String? {
            val value = definition.trimStart()
            if (value.isEmpty()) return null
            val firstWord = value.takeWhile { !it.isWhitespace() && it != '(' }
            if (firstWord.equals("CONSTRAINT", true) ||
                firstWord.equals("PRIMARY", true) ||
                firstWord.equals("UNIQUE", true) ||
                firstWord.equals("CHECK", true) ||
                firstWord.equals("FOREIGN", true)
            ) return null

            return when (value.first()) {
                '"', '`' -> value.drop(1).takeWhile { it != value.first() }
                '[' -> value.drop(1).takeWhile { it != ']' }
                else -> firstWord
            }
        }

        val retainedDefinitions = definitions.filterNot {
            definedColumnName(it)?.equals(column, ignoreCase = true) == true
        }
        check(retainedDefinitions.size < definitions.size) {
            "Column $table.$column was reported by PRAGMA table_info but not found in CREATE TABLE SQL"
        }

        val temporaryTable = "${table}__historical_fixture"
        check(db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(temporaryTable)
        ).use { it.moveToFirst() }.not()) { "Temporary fixture table already exists: $temporaryTable" }

        val prefix = createSql.substring(0, opening)
        val tablePrefixPattern = Regex(
            """(?i)^(CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?)(?:`|")?${Regex.escape(table)}(?:`|")?\s*$"""
        )
        val prefixMatch = tablePrefixPattern.find(prefix)
            ?: error("Could not safely replace table name in CREATE TABLE SQL for $table")
        val temporaryPrefix = prefixMatch.groupValues[1] + quoteIdentifier(temporaryTable)
        val historicalCreateSql = temporaryPrefix +
            "(" + retainedDefinitions.joinToString(", ") + ")" +
            createSql.substring(closing + 1)

        val dependentColumn = Regex(
            "(?i)(?<![A-Za-z0-9_])${Regex.escape(column)}(?![A-Za-z0-9_])"
        )
        val previousSequence = runCatching {
            db.rawQuery("SELECT seq FROM sqlite_sequence WHERE name=?", arrayOf(table)).use {
                if (it.moveToFirst()) it.getLong(0) else null
            }
        }.getOrNull()

        val schemaObjects = db.rawQuery(
            """SELECT type, sql FROM sqlite_master
               WHERE tbl_name=? AND type IN ('index','trigger') AND sql IS NOT NULL
               ORDER BY CASE type WHEN 'index' THEN 0 ELSE 1 END, name""".trimIndent(),
            arrayOf(table)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val sql = cursor.getString(1)
                    if (!dependentColumn.containsMatchIn(sql)) add(sql)
                }
            }
        }

        check(!db.inTransaction()) { "Historical fixture rebuild must start outside a SQLite transaction" }
        val foreignKeysWereEnabled = db.rawQuery("PRAGMA foreign_keys", null).use {
            it.moveToFirst() && it.getInt(0) != 0
        }
        if (foreignKeysWereEnabled) db.setForeignKeyConstraintsEnabled(false)

        try {
            db.beginTransaction()
            try {
                db.execSQL(historicalCreateSql)
                val rebuiltColumns = columnsOf(temporaryTable).toSet()
                val copyColumns = originalColumns.filter { it in rebuiltColumns }
                check(copyColumns.isNotEmpty()) { "No shared columns remain while rebuilding $table" }
                val columnList = copyColumns.joinToString(", ") { quoteIdentifier(it) }
                db.execSQL(
                    "INSERT INTO ${quoteIdentifier(temporaryTable)} ($columnList) " +
                        "SELECT $columnList FROM ${quoteIdentifier(table)}"
                )
                db.execSQL("DROP TABLE ${quoteIdentifier(table)}")
                db.execSQL(
                    "ALTER TABLE ${quoteIdentifier(temporaryTable)} RENAME TO ${quoteIdentifier(table)}"
                )
                previousSequence?.let { sequence ->
                    db.execSQL("DELETE FROM sqlite_sequence WHERE name=?", arrayOf(table))
                    db.execSQL(
                        "INSERT INTO sqlite_sequence(name, seq) VALUES (?, ?)",
                        arrayOf(table, sequence)
                    )
                }
                schemaObjects.forEach(db::execSQL)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            if (foreignKeysWereEnabled) db.setForeignKeyConstraintsEnabled(true)
        }
    }
}
