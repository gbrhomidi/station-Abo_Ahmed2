package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, VERSION) {

    companion object {
        private const val TAG = "DatabaseHelper"
        private const val DB_NAME = "diesel_station.db"
        private const val VERSION = 4

        fun hashPassword(password: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(password.toByteArray(Charsets.UTF_8))
            return hash.joinToString("") { "%02x".format(it) }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            createAllTables(db)
            insertDefaultSettings(db)
            insertDefaultUser(db)
            seedDemoData(db)
            db.setTransactionSuccessful()
            Log.d(TAG, "Database created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize database: ${e.message}", e)
            throw e
        } finally {
            db.endTransaction()
        }
    }

    // FIXED: Proper migration steps (1->2->3->4) instead of jumping directly
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.beginTransaction()
        try {
            var currentVersion = oldVersion
            while (currentVersion < newVersion) {
                when (currentVersion) {
                    1 -> migrateV1ToV2(db)
                    2 -> migrateV2ToV3(db)
                    3 -> migrateV3ToV4(db)
                }
                currentVersion++
            }
            db.setTransactionSuccessful()
            Log.d(TAG, "Database upgraded from $oldVersion to $newVersion")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upgrade database: ${e.message}", e)
            throw e
        } finally {
            db.endTransaction()
        }
    }

    // Migration from V1 to V2
    private fun migrateV1ToV2(db: SQLiteDatabase) {
        Log.d(TAG, "Migrating V1 -> V2")
        // Add basic columns if not exist
        safeAddColumn(db, "customers", "loyalty_points", "INTEGER DEFAULT 0")
        safeAddColumn(db, "customers", "vip_level", "INTEGER DEFAULT 0")
        safeAddColumn(db, "customers", "created_at", "TEXT DEFAULT CURRENT_TIMESTAMP")
    }

    // Migration from V2 to V3
    private fun migrateV2ToV3(db: SQLiteDatabase) {
        Log.d(TAG, "Migrating V2 -> V3")
        safeAddColumn(db, "transactions", "invoice_number", "TEXT")
        safeAddColumn(db, "transactions", "payment_type", "TEXT DEFAULT 'نقداً'")
        safeAddColumn(db, "refills", "alert_threshold", "REAL DEFAULT 1000")
    }

    // Migration from V3 to V4
    private fun migrateV3ToV4(db: SQLiteDatabase) {
        Log.d(TAG, "Migrating V3 -> V4")
        // Create new tables if not exist
        safeCreateTable(db, "users", """
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT UNIQUE,
                password_hash TEXT,
                full_name TEXT,
                role TEXT DEFAULT 'cashier',
                biometric_enabled INTEGER DEFAULT 0,
                active INTEGER DEFAULT 1,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
        safeCreateTable(db, "loyalty_rewards", """
            CREATE TABLE IF NOT EXISTS loyalty_rewards (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                customer_id INTEGER,
                points_used INTEGER,
                reward_type TEXT,
                description TEXT,
                date TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
        safeCreateTable(db, "inventory_alerts", """
            CREATE TABLE IF NOT EXISTS inventory_alerts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                refill_id INTEGER,
                alert_type TEXT,
                message TEXT,
                is_read INTEGER DEFAULT 0,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
        safeCreateTable(db, "ai_chat_history", """
            CREATE TABLE IF NOT EXISTS ai_chat_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT,
                role TEXT,
                message TEXT,
                timestamp TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
        safeCreateTable(db, "print_queue", """
            CREATE TABLE IF NOT EXISTS print_queue (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT,
                content TEXT,
                status TEXT DEFAULT 'pending',
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
        safeCreateTable(db, "sync_queue", """
            CREATE TABLE IF NOT EXISTS sync_queue (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                table_name TEXT,
                record_id INTEGER,
                action TEXT,
                status TEXT DEFAULT 'pending',
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)

        // Insert default user if not exists - FIXED: Uses ContentValues to prevent SQL injection
        insertDefaultUserIfNotExists(db)

        // Insert default settings if not exists
        insertDefaultSettingIfNotExists(db, "loyalty_enabled", "1")
        insertDefaultSettingIfNotExists(db, "points_per_liter", "1")
        insertDefaultSettingIfNotExists(db, "min_points_redeem", "100")
    }

    // Helper: Add column only if it doesn't exist
    private fun safeAddColumn(db: SQLiteDatabase, table: String, column: String, type: String) {
        try {
            val cursor = db.rawQuery("PRAGMA table_info($table)", null)
            val existingColumns = mutableListOf<String>()
            while (cursor.moveToNext()) {
                existingColumns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            cursor.close()
            if (column !in existingColumns) {
                db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
                Log.d(TAG, "Added column $column to $table")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not add column $column to $table: ${e.message}")
        }
    }

    // Helper: Create table only if it doesn't exist
    private fun safeCreateTable(db: SQLiteDatabase, tableName: String, createSql: String) {
        try {
            db.execSQL(createSql)
            Log.d(TAG, "Created table $tableName")
        } catch (e: Exception) {
            Log.w(TAG, "Could not create table $tableName: ${e.message}")
        }
    }

    // FIXED: Insert default user using ContentValues to prevent SQL injection
    private fun insertDefaultUserIfNotExists(db: SQLiteDatabase) {
        val cursor = db.rawQuery("SELECT COUNT(*) FROM users WHERE username=?", arrayOf("admin"))
        val exists = cursor.use {
            if (it.moveToFirst()) it.getInt(0) > 0 else false
        }
        if (!exists) {
            val cv = ContentValues().apply {
                put("username", "admin")
                put("password_hash", hashPassword("admin123"))
                put("full_name", "المدير العام")
                put("role", "admin")
            }
            db.insert("users", null, cv)
            Log.d(TAG, "Inserted default admin user")
        }
    }

    // FIXED: Insert default setting using ContentValues
    private fun insertDefaultSettingIfNotExists(db: SQLiteDatabase, key: String, value: String) {
        val cursor = db.rawQuery("SELECT COUNT(*) FROM settings WHERE key=?", arrayOf(key))
        val exists = cursor.use {
            if (it.moveToFirst()) it.getInt(0) > 0 else false
        }
        if (!exists) {
            val cv = ContentValues().apply {
                put("key", key)
                put("value", value)
            }
            db.insert("settings", null, cv)
        }
    }

    private fun createAllTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE customers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT,
                phone TEXT,
                credit_limit REAL,
                balance REAL,
                status TEXT,
                loyalty_points INTEGER DEFAULT 0,
                vip_level INTEGER DEFAULT 0,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("""
            CREATE TABLE refills (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                date TEXT,
                supplier TEXT,
                total_qty REAL,
                remaining_qty REAL,
                sell_price REAL,
                allow_credit INTEGER,
                alert_threshold REAL DEFAULT 1000
            )
        """)
        db.execSQL("""
            CREATE TABLE transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                customer_id INTEGER,
                refill_id INTEGER,
                qty REAL,
                price REAL,
                total REAL,
                paid REAL,
                due REAL,
                method TEXT,
                due_date TEXT,
                status TEXT,
                date TEXT DEFAULT CURRENT_TIMESTAMP,
                invoice_number TEXT,
                payment_type TEXT DEFAULT 'نقداً'
            )
        """)
        db.execSQL("""
            CREATE TABLE payments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                customer_id INTEGER,
                amount REAL,
                method TEXT,
                date TEXT,
                notes TEXT
            )
        """)
        db.execSQL("""
            CREATE TABLE settings (
                key TEXT PRIMARY KEY,
                value TEXT
            )
        """)
        db.execSQL("""
            CREATE TABLE sms_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                phone TEXT,
                message TEXT,
                type TEXT,
                status TEXT,
                date TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("""
            CREATE TABLE activity_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                operator TEXT,
                action TEXT,
                details TEXT,
                date TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("""
            CREATE TABLE users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT UNIQUE,
                password_hash TEXT,
                full_name TEXT,
                role TEXT DEFAULT 'cashier',
                biometric_enabled INTEGER DEFAULT 0,
                active INTEGER DEFAULT 1,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("""
            CREATE TABLE loyalty_rewards (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                customer_id INTEGER,
                points_used INTEGER,
                reward_type TEXT,
                description TEXT,
                date TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("""
            CREATE TABLE inventory_alerts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                refill_id INTEGER,
                alert_type TEXT,
                message TEXT,
                is_read INTEGER DEFAULT 0,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("""
            CREATE TABLE ai_chat_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT,
                role TEXT,
                message TEXT,
                timestamp TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("""
            CREATE TABLE print_queue (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT,
                content TEXT,
                status TEXT DEFAULT 'pending',
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("""
            CREATE TABLE sync_queue (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                table_name TEXT,
                record_id INTEGER,
                action TEXT,
                status TEXT DEFAULT 'pending',
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
    }

    private fun insertDefaultSettings(db: SQLiteDatabase) {
        val defaults = listOf(
            "sms_gateway_type" to "android_app",
            "sms_sim_slot" to "1",
            "low_stock_threshold" to "1000",
            "currency" to "ريال",
            "station_name" to "محطة ابو أحمد لمشتقات الديزل",
            "ai_enabled" to "1",
            "auto_backup_interval" to "24",
            "dark_mode_default" to "0",
            "require_biometric" to "0",
            "loyalty_enabled" to "1",
            "points_per_liter" to "1",
            "min_points_redeem" to "100"
        )
        for ((key, value) in defaults) {
            val cv = ContentValues().apply {
                put("key", key)
                put("value", value)
            }
            db.insert("settings", null, cv)
        }
    }

    private fun insertDefaultUser(db: SQLiteDatabase) {
        val cv = ContentValues().apply {
            put("username", "admin")
            put("password_hash", hashPassword("admin123"))
            put("full_name", "المدير العام")
            put("role", "admin")
        }
        db.insert("users", null, cv)
    }

    private fun seedDemoData(db: SQLiteDatabase) {
        val customers = listOf(
            Triple("أحمد محمد", "0778123456", 500000.0),
            Triple("خالد عبدالله", "0789123456", 300000.0),
            Triple("سعد علي", "0771122334", 100000.0)
        )
        for ((name, phone, credit) in customers) {
            val cv = ContentValues().apply {
                put("name", name)
                put("phone", phone)
                put("credit_limit", credit)
                put("balance", if (name == "أحمد محمد") 125000.0 else if (name == "سعد علي") 45000.0 else 0.0)
                put("status", "active")
                put("loyalty_points", if (name == "أحمد محمد") 450 else if (name == "خالد عبدالله") 1200 else 80)
                put("vip_level", if (name == "أحمد محمد") 2 else if (name == "خالد عبدالله") 3 else 1)
            }
            db.insert("customers", null, cv)
        }

        val refills = listOf(
            listOf("2026-06-01", "شركة النفط اليمنية", 10000.0, 8500.0, 950.0, 1, 1000.0),
            listOf("2026-06-15", "مورد الجنوب", 5000.0, 3200.0, 940.0, 1, 500.0)
        )
        for (refill in refills) {
            val cv = ContentValues().apply {
                put("date", refill[0] as String)
                put("supplier", refill[1] as String)
                put("total_qty", refill[2] as Double)
                put("remaining_qty", refill[3] as Double)
                put("sell_price", refill[4] as Double)
                put("allow_credit", refill[5] as Int)
                put("alert_threshold", refill[6] as Double)
            }
            db.insert("refills", null, cv)
        }
    }

    // ==================== Users & Authentication ====================
    fun authenticateUser(username: String, password: String): JSONObject? {
        val db = readableDatabase
        val c = db.rawQuery(
            "SELECT * FROM users WHERE username=? AND password_hash=? AND active=1",
            arrayOf(username, hashPassword(password))
        )
        return try {
            if (c.moveToFirst()) {
                val o = userCursorToJson(c)
                logActivity(username, "login", "تسجيل دخول ناجح")
                o
            } else {
                null
            }
        } finally {
            c.close()
        }
    }

    fun getUserByUsername(username: String): JSONObject? {
        val db = readableDatabase
        val c = db.rawQuery("SELECT * FROM users WHERE username=?", arrayOf(username))
        return try {
            if (c.moveToFirst()) userCursorToJson(c) else null
        } finally {
            c.close()
        }
    }

    fun updateBiometricStatus(username: String, enabled: Boolean) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("biometric_enabled", if (enabled) 1 else 0)
        }
        db.update("users", cv, "username=?", arrayOf(username))
    }

    private fun userCursorToJson(c: Cursor): JSONObject {
        val o = JSONObject()
        o.put("user_id", c.getInt(0))
        o.put("username", c.getString(1))
        o.put("full_name", c.getString(3))
        o.put("role", c.getString(4))
        o.put("biometric_enabled", c.getInt(5))
        o.put("active", c.getInt(6))
        return o
    }

    // ==================== Customers ====================
    fun getCustomers(): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        val c = db.rawQuery("SELECT * FROM customers ORDER BY name", null)
        try {
            while (c.moveToNext()) {
                arr.put(customerCursorToJson(c))
            }
        } finally {
            c.close()
        }
        return arr
    }

    fun getCustomer(id: Int): JSONObject? {
        val db = readableDatabase
        val c = db.rawQuery("SELECT * FROM customers WHERE id=?", arrayOf(id.toString()))
        return try {
            if (c.moveToFirst()) customerCursorToJson(c) else null
        } finally {
            c.close()
        }
    }

    private fun customerCursorToJson(c: Cursor): JSONObject {
        val o = JSONObject()
        o.put("customer_id", c.getInt(0))
        o.put("full_name", c.getString(1))
        o.put("phone", c.getString(2))
        o.put("credit_limit", c.getDouble(3))
        o.put("current_balance", c.getDouble(4))
        o.put("status", c.getString(5))
        o.put("loyalty_points", c.getInt(6))
        o.put("vip_level", c.getInt(7))
        o.put("created_at", c.getString(8))
        return o
    }

    // ==================== Refills & Inventory ====================
    fun getRefills(): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        val c = db.rawQuery("SELECT * FROM refills ORDER BY id DESC", null)
        try {
            while (c.moveToNext()) {
                arr.put(refillCursorToJson(c))
            }
        } finally {
            c.close()
        }
        return arr
    }

    fun getRefill(id: Int): JSONObject? {
        val db = readableDatabase
        val c = db.rawQuery("SELECT * FROM refills WHERE id=?", arrayOf(id.toString()))
        return try {
            if (c.moveToFirst()) refillCursorToJson(c) else null
        } finally {
            c.close()
        }
    }

    private fun refillCursorToJson(c: Cursor): JSONObject {
        val o = JSONObject()
        o.put("refill_id", c.getInt(0))
        o.put("refill_date", c.getString(1))
        o.put("supplier_name", c.getString(2))
        o.put("quantity_liters", c.getDouble(3))
        o.put("remaining_quantity", c.getDouble(4))
        o.put("sell_price_per_liter", c.getDouble(5))
        o.put("allow_credit_sale", c.getInt(6))
        o.put("alert_threshold", c.getDouble(7))
        return o
    }

    fun updateRefillQty(id: Int, qty: Double, operator: String = "System"): Boolean {
        val db = writableDatabase
        db.execSQL(
            "UPDATE refills SET remaining_qty = remaining_qty - ? WHERE id=?",
            arrayOf(qty, id)
        )
        logActivity(operator, "update_refill_qty", "تنقيص تعبئة ID $id بمقدار $qty لتر")
        checkInventoryAlerts(id)
        return true
    }

    private fun checkInventoryAlerts(refillId: Int) {
        val refill = getRefill(refillId) ?: return
        val remaining = refill.getDouble("remaining_quantity")
        val threshold = refill.getDouble("alert_threshold")
        if (remaining <= threshold) {
            val msg = "تنبيه: مخزون التعبئة (${refill.getString("supplier_name")}) وصل إلى ${remaining.toInt()} لتر (الحد: ${threshold.toInt()})"
            val cv = ContentValues().apply {
                put("refill_id", refillId)
                put("alert_type", "low_stock")
                put("message", msg)
            }
            writableDatabase.insert("inventory_alerts", null, cv)
        }
    }

    fun getInventoryAlerts(): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        val c = db.rawQuery(
            "SELECT * FROM inventory_alerts WHERE is_read=0 ORDER BY id DESC",
            null
        )
        try {
            while (c.moveToNext()) {
                val o = JSONObject()
                o.put("id", c.getInt(0))
                o.put("refill_id", c.getInt(1))
                o.put("alert_type", c.getString(2))
                o.put("message", c.getString(3))
                o.put("is_read", c.getInt(4))
                o.put("created_at", c.getString(5))
                arr.put(o)
            }
        } finally {
            c.close()
        }
        return arr
    }

    fun markAlertRead(alertId: Int) {
        writableDatabase.execSQL(
            "UPDATE inventory_alerts SET is_read=1 WHERE id=?",
            arrayOf(alertId)
        )
    }

    // ==================== Transactions ====================
    fun insertTransaction(
        customerId: Int,
        refillId: Int,
        qty: Double,
        price: Double,
        paid: Double,
        due: Double,
        method: String,
        dueDate: String,
        paymentType: String = "نقداً",
        operator: String = "System"
    ): Int {
        val db = writableDatabase
        val invoiceNo = "INV-" + System.currentTimeMillis().toString().takeLast(6)
        val cv = ContentValues().apply {
            put("customer_id", customerId)
            put("refill_id", refillId)
            put("qty", qty)
            put("price", price)
            put("total", qty * price)
            put("paid", paid)
            put("due", due)
            put("method", method)
            put("due_date", dueDate)
            put("status", if (due > 0) "unpaid" else "paid")
            put("invoice_number", invoiceNo)
            put("payment_type", paymentType)
        }
        val id = db.insert("transactions", null, cv).toInt()

        if (getSetting("loyalty_enabled") == "1") {
            val pointsPerLiter = getSetting("points_per_liter").toDoubleOrNull() ?: 1.0
            val points = (qty * pointsPerLiter).toInt()
            db.execSQL(
                "UPDATE customers SET loyalty_points = loyalty_points + ? WHERE id=?",
                arrayOf(points, customerId)
            )
        }

        logActivity(operator, "sale", "بيع جديد: $qty لتر للعميل $customerId")
        return id
    }

    fun getTransactions(): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        val c = db.rawQuery("SELECT * FROM transactions ORDER BY id DESC LIMIT 200", null)
        try {
            while (c.moveToNext()) {
                arr.put(transactionCursorToJson(c))
            }
        } finally {
            c.close()
        }
        return arr
    }

    fun getTransactionById(id: Int): JSONObject? {
        val db = readableDatabase
        val c = db.rawQuery("SELECT * FROM transactions WHERE id=?", arrayOf(id.toString()))
        return try {
            if (c.moveToFirst()) transactionCursorToJson(c) else null
        } finally {
            c.close()
        }
    }

    private fun transactionCursorToJson(c: Cursor): JSONObject {
        val o = JSONObject()
        o.put("transaction_id", c.getInt(0))
        o.put("customer_id", c.getInt(1))
        o.put("refill_id", c.getInt(2))
        o.put("qty", c.getDouble(3))
        o.put("price", c.getDouble(4))
        o.put("total", c.getDouble(5))
        o.put("paid", c.getDouble(6))
        o.put("due", c.getDouble(7))
        o.put("method", c.getString(8))
        o.put("due_date", c.getString(9))
        o.put("status", c.getString(10))
        o.put("date", c.getString(11))
        o.put("invoice_number", c.getString(12))
        o.put("payment_type", c.getString(13))
        return o
    }

    fun searchTransactions(query: String): JSONArray {
        val arr = JSONArray()
        val q = "%$query%"
        val sql = """
            SELECT t.*, c.name as customer_name
            FROM transactions t
            LEFT JOIN customers c ON t.customer_id = c.id
            WHERE c.name LIKE ? OR t.due_date LIKE ? OR t.invoice_number LIKE ?
            ORDER BY t.id DESC LIMIT 100
        """.trimIndent()
        val db = readableDatabase
        val c = db.rawQuery(sql, arrayOf(q, q, q))
        try {
            while (c.moveToNext()) {
                val o = JSONObject()
                o.put("transaction_id", c.getInt(c.getColumnIndexOrThrow("id")))
                o.put("customer_id", c.getInt(c.getColumnIndexOrThrow("customer_id")))
                o.put("customer_name", c.getString(c.getColumnIndexOrThrow("customer_name")))
                o.put("refill_id", c.getInt(c.getColumnIndexOrThrow("refill_id")))
                o.put("qty", c.getDouble(c.getColumnIndexOrThrow("qty")))
                o.put("price", c.getDouble(c.getColumnIndexOrThrow("price")))
                o.put("total", c.getDouble(c.getColumnIndexOrThrow("total")))
                o.put("paid", c.getDouble(c.getColumnIndexOrThrow("paid")))
                o.put("due", c.getDouble(c.getColumnIndexOrThrow("due")))
                o.put("method", c.getString(c.getColumnIndexOrThrow("method")))
                o.put("due_date", c.getString(c.getColumnIndexOrThrow("due_date")))
                o.put("status", c.getString(c.getColumnIndexOrThrow("status")))
                o.put("invoice_number", c.getString(c.getColumnIndexOrThrow("invoice_number")))
                o.put("payment_type", c.getString(c.getColumnIndexOrThrow("payment_type")))
                arr.put(o)
            }
        } finally {
            c.close()
        }
        return arr
    }

    // ==================== Payments ====================
    fun processPayment(customerId: Int, amount: Double, operator: String = "System"): Boolean {
        val db = writableDatabase
        db.execSQL(
            "UPDATE customers SET balance = balance - ? WHERE id=?",
            arrayOf(amount, customerId)
        )
        val cv = ContentValues().apply {
            put("customer_id", customerId)
            put("amount", amount)
            put("method", "cash")
            put("date", "now")
            put("notes", "تسديد يدوي")
        }
        db.insert("payments", null, cv)
        db.execSQL(
            """UPDATE transactions SET paid = paid + ?, due = due - ?,
                status = CASE WHEN due - ? <= 0 THEN 'paid' ELSE 'partial' END
                WHERE customer_id = ? AND due > 0 ORDER BY id LIMIT 1""",
            arrayOf(amount, amount, amount, customerId)
        )
        logActivity(operator, "payment", "تسديد مبلغ $amount للعميل $customerId")
        return true
    }

    // ==================== Reports & Dashboard ====================
    // FIXED: Single database instance per method to avoid repeated open/close
    fun getDashboardStats(): JSONArray {
        val stats = JSONObject()
        val db = readableDatabase

        try {
            db.rawQuery(
                "SELECT COALESCE(SUM(total),0), COALESCE(SUM(qty),0) FROM transactions WHERE date(date) = date('now')",
                null
            ).use { c ->
                if (c.moveToFirst()) {
                    stats.put("total_sales", c.getDouble(0))
                    stats.put("total_liters", c.getDouble(1))
                }
            }

            db.rawQuery("SELECT COALESCE(SUM(remaining_qty),0) FROM refills", null).use { c ->
                if (c.moveToFirst()) stats.put("total_remaining", c.getDouble(0))
            }

            db.rawQuery(
                "SELECT COALESCE(SUM(due),0) FROM transactions WHERE status IN ('unpaid','partial')",
                null
            ).use { c ->
                if (c.moveToFirst()) stats.put("total_due", c.getDouble(0))
            }

            db.rawQuery("SELECT COUNT(*) FROM customers WHERE status='active'", null).use { c ->
                if (c.moveToFirst()) stats.put("total_customers", c.getInt(0))
            }

            db.rawQuery("SELECT COUNT(*) FROM transactions WHERE date(date) = date('now')", null).use { c ->
                if (c.moveToFirst()) stats.put("transactions_today", c.getInt(0))
            }

            val threshold = getSetting("low_stock_threshold").toDoubleOrNull() ?: 1000.0
            stats.put("alerts", getLowStockRefills(threshold))
            stats.put("inventory_alerts", getInventoryAlerts())
            stats.put("total_sms_today", getSmsCountToday())
        } catch (e: Exception) {
            Log.e(TAG, "Error getting dashboard stats: ${e.message}", e)
        }

        return JSONArray().put(stats)
    }

    fun getDailySales(): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        val c = db.rawQuery(
            """SELECT date(date) as sale_date, SUM(qty) as total_qty,
                SUM(total) as total_sales, COUNT(*) as count
                FROM transactions GROUP BY date(date)
                ORDER BY date(date) DESC LIMIT 30""",
            null
        )
        try {
            while (c.moveToNext()) {
                val o = JSONObject()
                o.put("date", c.getString(0))
                o.put("total_qty", c.getDouble(1))
                o.put("total_sales", c.getDouble(2))
                o.put("count", c.getInt(3))
                arr.put(o)
            }
        } finally {
            c.close()
        }
        return arr
    }

    fun getMonthlySales(): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        val c = db.rawQuery(
            """SELECT strftime('%Y-%m', date) as sale_month, SUM(qty) as total_qty,
                SUM(total) as total_sales, COUNT(*) as count
                FROM transactions GROUP BY strftime('%Y-%m', date)
                ORDER BY sale_month DESC LIMIT 12""",
            null
        )
        try {
            while (c.moveToNext()) {
                val o = JSONObject()
                o.put("month", c.getString(0))
                o.put("total_qty", c.getDouble(1))
                o.put("total_sales", c.getDouble(2))
                o.put("count", c.getInt(3))
                arr.put(o)
            }
        } finally {
            c.close()
        }
        return arr
    }

    fun getEodReport(): JSONObject {
        val report = JSONObject()
        val db = readableDatabase

        try {
            db.rawQuery(
                """SELECT COALESCE(SUM(total),0), COALESCE(SUM(qty),0), COUNT(*)
                    FROM transactions WHERE date(date) = date('now')""",
                null
            ).use { c ->
                if (c.moveToFirst()) {
                    report.put("total_sales_amount", c.getDouble(0))
                    report.put("total_liters_sold", c.getDouble(1))
                    report.put("total_transactions", c.getInt(2))
                }
            }

            db.rawQuery(
                "SELECT COALESCE(SUM(amount),0) FROM payments WHERE date(date) = date('now')",
                null
            ).use { c ->
                if (c.moveToFirst()) report.put("total_payments", c.getDouble(0))
            }

            report.put("total_sms_sent", getSmsCountToday())
            report.put("report_date", java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm", java.util.Locale.getDefault()
            ).format(java.util.Date()))
        } catch (e: Exception) {
            Log.e(TAG, "Error getting EOD report: ${e.message}", e)
        }

        return report
    }

    // ==================== Overdue Transactions ====================
    fun getOverdueTransactions(): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        val sql = """
            SELECT t.*, c.name as customer_name, c.phone as customer_phone
            FROM transactions t
            LEFT JOIN customers c ON t.customer_id = c.id
            WHERE t.due > 0 AND date(t.due_date) < date('now')
        """.trimIndent()
        val c = db.rawQuery(sql, null)
        try {
            while (c.moveToNext()) {
                val o = JSONObject()
                o.put("transaction_id", c.getInt(c.getColumnIndexOrThrow("id")))
                o.put("customer_id", c.getInt(c.getColumnIndexOrThrow("customer_id")))
                o.put("customer_name", c.getString(c.getColumnIndexOrThrow("customer_name")))
                o.put("customer_phone", c.getString(c.getColumnIndexOrThrow("customer_phone")))
                o.put("due", c.getDouble(c.getColumnIndexOrThrow("due")))
                o.put("due_date", c.getString(c.getColumnIndexOrThrow("due_date")))
                o.put("invoice_number", c.getString(c.getColumnIndexOrThrow("invoice_number")))
                arr.put(o)
            }
        } finally {
            c.close()
        }
        return arr
    }

    // ==================== Low Stock ====================
    fun getLowStockRefills(threshold: Double): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        val c = db.rawQuery(
            "SELECT * FROM refills WHERE remaining_qty < ?",
            arrayOf(threshold.toString())
        )
        try {
            while (c.moveToNext()) {
                arr.put(refillCursorToJson(c))
            }
        } finally {
            c.close()
        }
        return arr
    }

    // ==================== SMS Logs ====================
    fun logSms(phone: String, message: String, type: String, status: String) {
        val cv = ContentValues().apply {
            put("phone", phone)
            put("message", message)
            put("type", type)
            put("status", status)
        }
        writableDatabase.insert("sms_logs", null, cv)
    }

    fun getSmsLogs(): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        val c = db.rawQuery("SELECT * FROM sms_logs ORDER BY id DESC LIMIT 500", null)
        try {
            while (c.moveToNext()) {
                val o = JSONObject()
                o.put("id", c.getInt(0))
                o.put("phone", c.getString(1))
                o.put("message", c.getString(2))
                o.put("type", c.getString(3))
                o.put("status", c.getString(4))
                o.put("date", c.getString(5))
                arr.put(o)
            }
        } finally {
            c.close()
        }
        return arr
    }

    private fun getSmsCountToday(): Int {
        val db = readableDatabase
        val c = db.rawQuery("SELECT COUNT(*) FROM sms_logs WHERE date(date) = date('now')", null)
        return try {
            if (c.moveToFirst()) c.getInt(0) else 0
        } finally {
            c.close()
        }
    }

    // ==================== Activity Logs ====================
    fun logActivity(operator: String, action: String, details: String) {
        val cv = ContentValues().apply {
            put("operator", operator)
            put("action", action)
            put("details", details)
        }
        writableDatabase.insert("activity_logs", null, cv)
    }

    fun getActivityLogs(): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        val c = db.rawQuery("SELECT * FROM activity_logs ORDER BY id DESC LIMIT 100", null)
        try {
            while (c.moveToNext()) {
                val o = JSONObject()
                o.put("id", c.getInt(0))
                o.put("operator", c.getString(1))
                o.put("action", c.getString(2))
                o.put("details", c.getString(3))
                o.put("date", c.getString(4))
                arr.put(o)
            }
        } finally {
            c.close()
        }
        return arr
    }

    // ==================== Loyalty ====================
    fun getLoyaltyHistory(customerId: Int): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        val c = db.rawQuery(
            "SELECT * FROM loyalty_rewards WHERE customer_id=? ORDER BY id DESC",
            arrayOf(customerId.toString())
        )
        try {
            while (c.moveToNext()) {
                val o = JSONObject()
                o.put("id", c.getInt(0))
                o.put("points_used", c.getInt(2))
                o.put("reward_type", c.getString(3))
                o.put("description", c.getString(4))
                o.put("date", c.getString(5))
                arr.put(o)
            }
        } finally {
            c.close()
        }
        return arr
    }

    fun redeemLoyaltyPoints(customerId: Int, points: Int, description: String): Boolean {
        val customer = getCustomer(customerId) ?: return false
        val currentPoints = customer.getInt("loyalty_points")
        if (currentPoints < points) return false
        writableDatabase.execSQL(
            "UPDATE customers SET loyalty_points = loyalty_points - ? WHERE id=?",
            arrayOf(points, customerId)
        )
        val cv = ContentValues().apply {
            put("customer_id", customerId)
            put("points_used", points)
            put("reward_type", "discount")
            put("description", description)
        }
        writableDatabase.insert("loyalty_rewards", null, cv)
        return true
    }

    // ==================== AI Chat ====================
    fun saveAiMessage(sessionId: String, role: String, message: String) {
        val cv = ContentValues().apply {
            put("session_id", sessionId)
            put("role", role)
            put("message", message)
        }
        writableDatabase.insert("ai_chat_history", null, cv)
    }

    fun getAiChatHistory(sessionId: String): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        val c = db.rawQuery(
            "SELECT * FROM ai_chat_history WHERE session_id=? ORDER BY id",
            arrayOf(sessionId)
        )
        try {
            while (c.moveToNext()) {
                val o = JSONObject()
                o.put("role", c.getString(2))
                o.put("message", c.getString(3))
                o.put("timestamp", c.getString(4))
                arr.put(o)
            }
        } finally {
            c.close()
        }
        return arr
    }

    // ==================== Settings ====================
    fun setSetting(key: String, value: String) {
        val cv = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        writableDatabase.insertWithOnConflict(
            "settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getSetting(key: String): String {
        val db = readableDatabase
        val c = db.rawQuery("SELECT value FROM settings WHERE key=?", arrayOf(key))
        return try {
            if (c.moveToFirst()) c.getString(0) else ""
        } finally {
            c.close()
        }
    }

    // ==================== Export ====================
    fun exportAllData(): JSONObject {
        val result = JSONObject()
        result.put("customers", getCustomers())
        result.put("refills", getRefills())
        result.put("transactions", getTransactions())
        result.put("sms_logs", getSmsLogs())
        result.put("activity_logs", getActivityLogs())
        result.put("inventory_alerts", getInventoryAlerts())

        val payments = JSONArray()
        val db = readableDatabase
        val pc = db.rawQuery("SELECT * FROM payments", null)
        try {
            while (pc.moveToNext()) {
                val o = JSONObject()
                o.put("payment_id", pc.getInt(0))
                o.put("customer_id", pc.getInt(1))
                o.put("amount", pc.getDouble(2))
                o.put("method", pc.getString(3))
                o.put("date", pc.getString(4))
                payments.put(o)
            }
        } finally {
            pc.close()
        }
        result.put("payments", payments)
        return result
    }

    fun getCustomerReport(customerId: Int): JSONObject {
        val report = JSONObject()
        val customer = getCustomer(customerId) ?: return report
        report.put("customer", customer)
        report.put("loyalty_history", getLoyaltyHistory(customerId))

        val transactions = JSONArray()
        val db = readableDatabase
        val tc = db.rawQuery(
            "SELECT * FROM transactions WHERE customer_id = ? ORDER BY id DESC",
            arrayOf(customerId.toString())
        )
        try {
            while (tc.moveToNext()) transactions.put(transactionCursorToJson(tc))
        } finally {
            tc.close()
        }
        report.put("transactions", transactions)

        val payments = JSONArray()
        val pc = db.rawQuery(
            "SELECT * FROM payments WHERE customer_id = ? ORDER BY id DESC",
            arrayOf(customerId.toString())
        )
        try {
            while (pc.moveToNext()) {
                val o = JSONObject()
                o.put("payment_id", pc.getInt(0))
                o.put("amount", pc.getDouble(2))
                o.put("method", pc.getString(3))
                o.put("date", pc.getString(4))
                payments.put(o)
            }
        } finally {
            pc.close()
        }
        report.put("payments", payments)
        return report
    }
}
