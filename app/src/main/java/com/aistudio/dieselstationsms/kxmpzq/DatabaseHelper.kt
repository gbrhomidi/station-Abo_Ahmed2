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

    // ==================== onCreate ====================
    override fun onCreate(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            // جميع جداول الإنشاء
            createAllTables(db)
            // إدراج الإعدادات الافتراضية
            insertDefaultSettings(db)
            // إدراج المستخدم الافتراضي مع حماية
            try {
                insertDefaultUser(db)
            } catch (e: Exception) {
                Log.e("DatabaseHelper", "Failed to create default user, continuing: ${e.message}", e)
            }
            // بيانات تجريبية
            seedDemoData(db)
            db.setTransactionSuccessful()
            Log.d("DatabaseHelper", "Database created successfully")
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Failed to initialize database: ${e.message}", e)
            throw e
        } finally {
            db.endTransaction()
        }
    }

    private fun createAllTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE customers (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, phone TEXT, credit_limit REAL, balance REAL, status TEXT, loyalty_points INTEGER DEFAULT 0, vip_level INTEGER DEFAULT 0, created_at TEXT DEFAULT CURRENT_TIMESTAMP)")
        db.execSQL("CREATE TABLE refills (id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT, supplier TEXT, total_qty REAL, remaining_qty REAL, sell_price REAL, allow_credit INTEGER, alert_threshold REAL DEFAULT 1000)")
        db.execSQL("CREATE TABLE transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, customer_id INTEGER, refill_id INTEGER, qty REAL, price REAL, total REAL, paid REAL, due REAL, method TEXT, due_date TEXT, status TEXT, date TEXT DEFAULT CURRENT_TIMESTAMP, invoice_number TEXT, payment_type TEXT DEFAULT 'نقداً')")
        db.execSQL("CREATE TABLE payments (id INTEGER PRIMARY KEY AUTOINCREMENT, customer_id INTEGER, amount REAL, method TEXT, date TEXT, notes TEXT)")
        db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT)")
        db.execSQL("CREATE TABLE sms_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, phone TEXT, message TEXT, type TEXT, status TEXT, date TEXT DEFAULT CURRENT_TIMESTAMP)")
        db.execSQL("CREATE TABLE activity_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, operator TEXT, action TEXT, details TEXT, date TEXT DEFAULT CURRENT_TIMESTAMP)")
        db.execSQL("CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT UNIQUE, password_hash TEXT, full_name TEXT, role TEXT DEFAULT 'cashier', biometric_enabled INTEGER DEFAULT 0, active INTEGER DEFAULT 1, created_at TEXT DEFAULT CURRENT_TIMESTAMP)")
        db.execSQL("CREATE TABLE loyalty_rewards (id INTEGER PRIMARY KEY AUTOINCREMENT, customer_id INTEGER, points_used INTEGER, reward_type TEXT, description TEXT, date TEXT DEFAULT CURRENT_TIMESTAMP)")
        db.execSQL("CREATE TABLE inventory_alerts (id INTEGER PRIMARY KEY AUTOINCREMENT, refill_id INTEGER, alert_type TEXT, message TEXT, is_read INTEGER DEFAULT 0, created_at TEXT DEFAULT CURRENT_TIMESTAMP)")
        db.execSQL("CREATE TABLE ai_chat_history (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT, role TEXT, message TEXT, timestamp TEXT DEFAULT CURRENT_TIMESTAMP)")
        db.execSQL("CREATE TABLE print_queue (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, content TEXT, status TEXT DEFAULT 'pending', created_at TEXT DEFAULT CURRENT_TIMESTAMP)")
        db.execSQL("CREATE TABLE sync_queue (id INTEGER PRIMARY KEY AUTOINCREMENT, table_name TEXT, record_id INTEGER, action TEXT, status TEXT DEFAULT 'pending', created_at TEXT DEFAULT CURRENT_TIMESTAMP)")
    }

    private fun insertDefaultSettings(db: SQLiteDatabase) {
        val settings = mapOf(
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
        settings.forEach { (key, value) ->
            db.execSQL("INSERT OR IGNORE INTO settings (key, value) VALUES (?, ?)", arrayOf(key, value))
        }
    }

    private fun insertDefaultUser(db: SQLiteDatabase) {
        val cv = ContentValues().apply {
            put("username", "admin")
            put("password_hash", hashPassword("admin123"))
            put("full_name", "المدير العام")
            put("role", "admin")
        }
        db.insertWithOnConflict("users", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun seedDemoData(db: SQLiteDatabase) {
        db.execSQL("INSERT OR IGNORE INTO customers (name, phone, credit_limit, balance, status, loyalty_points, vip_level) VALUES ('أحمد محمد', '0778123456', 500000, 125000, 'active', 450, 2)")
        db.execSQL("INSERT OR IGNORE INTO customers (name, phone, credit_limit, balance, status, loyalty_points, vip_level) VALUES ('خالد عبدالله', '0789123456', 300000, 0, 'active', 1200, 3)")
        db.execSQL("INSERT OR IGNORE INTO customers (name, phone, credit_limit, balance, status, loyalty_points, vip_level) VALUES ('سعد علي', '0771122334', 100000, 45000, 'active', 80, 1)")
        db.execSQL("INSERT OR IGNORE INTO refills (date, supplier, total_qty, remaining_qty, sell_price, allow_credit, alert_threshold) VALUES ('2026-06-01', 'شركة النفط اليمنية', 10000, 8500, 950, 1, 1000)")
        db.execSQL("INSERT OR IGNORE INTO refills (date, supplier, total_qty, remaining_qty, sell_price, allow_credit, alert_threshold) VALUES ('2026-06-15', 'مورد الجنوب', 5000, 3200, 940, 1, 500)")
    }

    // ==================== onUpgrade ====================
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.beginTransaction()
        try {
            when (oldVersion) {
                1 -> {
                    upgradeFrom1To2(db)
                    upgradeFrom2To3(db)
                    upgradeFrom3To4(db)
                }
                2 -> {
                    upgradeFrom2To3(db)
                    upgradeFrom3To4(db)
                }
                3 -> {
                    upgradeFrom3To4(db)
                }
                else -> {
                    Log.w("DatabaseHelper", "Unknown version $oldVersion, recreating database")
                    dropAllTables(db)
                    onCreate(db)
                }
            }
            db.setTransactionSuccessful()
            Log.d("DatabaseHelper", "Database upgraded successfully from version $oldVersion to $newVersion")
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Failed to upgrade database: ${e.message}", e)
            // محاولة إنشاء قاعدة جديدة
            try {
                dropAllTables(db)
                onCreate(db)
                db.setTransactionSuccessful()
                Log.w("DatabaseHelper", "Database recreated after upgrade failure")
            } catch (ex: Exception) {
                Log.e("DatabaseHelper", "Failed to recreate database: ${ex.message}", ex)
                throw ex
            }
        } finally {
            db.endTransaction()
        }
    }

    private fun upgradeFrom1To2(db: SQLiteDatabase) {
        try {
            db.execSQL("ALTER TABLE customers ADD COLUMN loyalty_points INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE customers ADD COLUMN vip_level INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE customers ADD COLUMN created_at TEXT DEFAULT CURRENT_TIMESTAMP")
            Log.d("DatabaseHelper", "Upgrade 1->2 completed")
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Upgrade 1->2 failed: ${e.message}", e)
        }
    }

    private fun upgradeFrom2To3(db: SQLiteDatabase) {
        try {
            db.execSQL("ALTER TABLE transactions ADD COLUMN invoice_number TEXT")
            db.execSQL("ALTER TABLE transactions ADD COLUMN payment_type TEXT DEFAULT 'نقداً'")
            db.execSQL("ALTER TABLE refills ADD COLUMN alert_threshold REAL DEFAULT 1000")
            Log.d("DatabaseHelper", "Upgrade 2->3 completed")
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Upgrade 2->3 failed: ${e.message}", e)
        }
    }

    private fun upgradeFrom3To4(db: SQLiteDatabase) {
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT UNIQUE, password_hash TEXT, full_name TEXT, role TEXT DEFAULT 'cashier', biometric_enabled INTEGER DEFAULT 0, active INTEGER DEFAULT 1, created_at TEXT DEFAULT CURRENT_TIMESTAMP)")
            db.execSQL("CREATE TABLE IF NOT EXISTS loyalty_rewards (id INTEGER PRIMARY KEY AUTOINCREMENT, customer_id INTEGER, points_used INTEGER, reward_type TEXT, description TEXT, date TEXT DEFAULT CURRENT_TIMESTAMP)")
            db.execSQL("CREATE TABLE IF NOT EXISTS inventory_alerts (id INTEGER PRIMARY KEY AUTOINCREMENT, refill_id INTEGER, alert_type TEXT, message TEXT, is_read INTEGER DEFAULT 0, created_at TEXT DEFAULT CURRENT_TIMESTAMP)")
            db.execSQL("CREATE TABLE IF NOT EXISTS ai_chat_history (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT, role TEXT, message TEXT, timestamp TEXT DEFAULT CURRENT_TIMESTAMP)")
            db.execSQL("CREATE TABLE IF NOT EXISTS print_queue (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, content TEXT, status TEXT DEFAULT 'pending', created_at TEXT DEFAULT CURRENT_TIMESTAMP)")
            db.execSQL("CREATE TABLE IF NOT EXISTS sync_queue (id INTEGER PRIMARY KEY AUTOINCREMENT, table_name TEXT, record_id INTEGER, action TEXT, status TEXT DEFAULT 'pending', created_at TEXT DEFAULT CURRENT_TIMESTAMP)")
            
            // إضافة المستخدم admin إذا لم يكن موجوداً
            val userCheck = db.rawQuery("SELECT COUNT(*) FROM users WHERE username='admin'", null)
            if (userCheck.moveToFirst() && userCheck.getInt(0) == 0) {
                val cv = ContentValues().apply {
                    put("username", "admin")
                    put("password_hash", hashPassword("admin123"))
                    put("full_name", "المدير العام")
                    put("role", "admin")
                }
                db.insertWithOnConflict("users", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            }
            userCheck.close()
            
            // إضافة الإعدادات المفقودة
            val settingsCheck = db.rawQuery("SELECT COUNT(*) FROM settings WHERE key='loyalty_enabled'", null)
            if (settingsCheck.moveToFirst() && settingsCheck.getInt(0) == 0) {
                db.execSQL("INSERT OR IGNORE INTO settings (key, value) VALUES ('loyalty_enabled', '1')")
                db.execSQL("INSERT OR IGNORE INTO settings (key, value) VALUES ('points_per_liter', '1')")
                db.execSQL("INSERT OR IGNORE INTO settings (key, value) VALUES ('min_points_redeem', '100')")
            }
            settingsCheck.close()
            
            Log.d("DatabaseHelper", "Upgrade 3->4 completed")
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Upgrade 3->4 failed: ${e.message}", e)
        }
    }

    private fun dropAllTables(db: SQLiteDatabase) {
        val tables = listOf(
            "customers", "refills", "transactions", "payments", "settings",
            "sms_logs", "activity_logs", "users", "loyalty_rewards",
            "inventory_alerts", "ai_chat_history", "print_queue", "sync_queue"
        )
        tables.forEach { table ->
            try {
                db.execSQL("DROP TABLE IF EXISTS $table")
                Log.d("DatabaseHelper", "Dropped table: $table")
            } catch (e: Exception) {
                Log.e("DatabaseHelper", "Failed to drop table $table: ${e.message}", e)
            }
        }
    }

    // ==================== المستخدمين والمصادقة ====================
    fun authenticateUser(username: String, password: String): JSONObject? {
        val db = readableDatabase
        val c = db.rawQuery("SELECT * FROM users WHERE username=? AND password_hash=? AND active=1", arrayOf(username, hashPassword(password)))
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
            if (c.moveToFirst()) {
                userCursorToJson(c)
            } else {
                null
            }
        } finally {
            c.close()
        }
    }

    fun updateBiometricStatus(username: String, enabled: Boolean) {
        val db = writableDatabase
        val cv = ContentValues()
        cv.put("biometric_enabled", if (enabled) 1 else 0)
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

    // ==================== العملاء ====================
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
            if (c.moveToFirst()) {
                customerCursorToJson(c)
            } else {
                null
            }
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

    // ==================== التعبئة والمخزون ====================
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
            if (c.moveToFirst()) {
                refillCursorToJson(c)
            } else {
                null
            }
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
        db.execSQL("UPDATE refills SET remaining_qty = remaining_qty - ? WHERE id=?", arrayOf(qty, id))
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
            val cv = ContentValues()
            cv.put("refill_id", refillId)
            cv.put("alert_type", "low_stock")
            cv.put("message", msg)
            writableDatabase.insert("inventory_alerts", null, cv)
        }
    }

    fun getInventoryAlerts(): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        val c = db.rawQuery("SELECT * FROM inventory_alerts WHERE is_read=0 ORDER BY id DESC", null)
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
        writableDatabase.execSQL("UPDATE inventory_alerts SET is_read=1 WHERE id=?", arrayOf(alertId))
    }

    // ==================== المعاملات ====================
    fun insertTransaction(customerId: Int, refillId: Int, qty: Double, price: Double, paid: Double, due: Double, method: String, dueDate: String, paymentType: String = "نقداً", operator: String = "System"): Int {
        val db = writableDatabase
        val invoiceNo = "INV-" + System.currentTimeMillis().toString().takeLast(6)
        val cv = ContentValues()
        cv.put("customer_id", customerId)
        cv.put("refill_id", refillId)
        cv.put("qty", qty)
        cv.put("price", price)
        cv.put("total", qty * price)
        cv.put("paid", paid)
        cv.put("due", due)
        cv.put("method", method)
        cv.put("due_date", dueDate)
        cv.put("status", if (due > 0) "unpaid" else "paid")
        cv.put("invoice_number", invoiceNo)
        cv.put("payment_type", paymentType)
        val id = db.insert("transactions", null, cv).toInt()

        if (getSetting("loyalty_enabled") == "1") {
            val pointsPerLiter = getSetting("points_per_liter").toDoubleOrNull() ?: 1.0
            val points = (qty * pointsPerLiter).toInt()
            db.execSQL("UPDATE customers SET loyalty_points = loyalty_points + ? WHERE id=?", arrayOf(points, customerId))
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
            if (c.moveToFirst()) {
                transactionCursorToJson(c)
            } else {
                null
            }
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

    // ==================== التسديدات ====================
    fun processPayment(customerId: Int, amount: Double, operator: String = "System"): Boolean {
        val db = writableDatabase
        db.execSQL("UPDATE customers SET balance = balance - ? WHERE id=?", arrayOf(amount, customerId))
        val cv = ContentValues()
        cv.put("customer_id", customerId)
        cv.put("amount", amount)
        cv.put("method", "cash")
        cv.put("date", "now")
        cv.put("notes", "تسديد يدوي")
        db.insert("payments", null, cv)
        db.execSQL(
            "UPDATE transactions SET paid = paid + ?, due = due - ?, status = CASE WHEN due - ? <= 0 THEN 'paid' ELSE 'partial' END WHERE customer_id = ? AND due > 0 ORDER BY id LIMIT 1",
            arrayOf(amount, amount, amount, customerId)
        )
        logActivity(operator, "payment", "تسديد مبلغ $amount للعميل $customerId")
        return true
    }

    // ==================== التقارير والإحصائيات ====================
    fun getDashboardStats(): JSONArray {
        val stats = JSONObject()
        val db = readableDatabase
        
        try {
            var c = db.rawQuery("SELECT COALESCE(SUM(total),0), COALESCE(SUM(qty),0) FROM transactions WHERE date(date) = date('now')", null)
            if (c.moveToFirst()) {
                stats.put("total_sales", c.getDouble(0))
                stats.put("total_liters", c.getDouble(1))
            }
            c.close()

            c = db.rawQuery("SELECT COALESCE(SUM(remaining_qty),0) FROM refills", null)
            if (c.moveToFirst()) stats.put("total_remaining", c.getDouble(0))
            c.close()

            c = db.rawQuery("SELECT COALESCE(SUM(due),0) FROM transactions WHERE status IN ('unpaid','partial')", null)
            if (c.moveToFirst()) stats.put("total_due", c.getDouble(0))
            c.close()

            c = db.rawQuery("SELECT COUNT(*) FROM customers WHERE status='active'", null)
            if (c.moveToFirst()) stats.put("total_customers", c.getInt(0))
            c.close()

            c = db.rawQuery("SELECT COUNT(*) FROM transactions WHERE date(date) = date('now')", null)
            if (c.moveToFirst()) stats.put("transactions_today", c.getInt(0))
            c.close()

            val threshold = getSetting("low_stock_threshold").toDoubleOrNull() ?: 1000.0
            stats.put("alerts", getLowStockRefills(threshold))
            stats.put("inventory_alerts", getInventoryAlerts())
            stats.put("total_sms_today", getSmsCountToday())
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Error getting dashboard stats: ${e.message}", e)
        }
        
        return JSONArray().put(stats)
    }

    fun getDailySales(): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        val c = db.rawQuery("SELECT date(date) as sale_date, SUM(qty) as total_qty, SUM(total) as total_sales, COUNT(*) as count FROM transactions GROUP BY date(date) ORDER BY date(date) DESC LIMIT 30", null)
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
        val c = db.rawQuery("SELECT strftime('%Y-%m', date) as sale_month, SUM(qty) as total_qty, SUM(total) as total_sales, COUNT(*) as count FROM transactions GROUP BY strftime('%Y-%m', date) ORDER BY sale_month DESC LIMIT 12", null)
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
            var c = db.rawQuery("SELECT COALESCE(SUM(total),0), COALESCE(SUM(qty),0), COUNT(*) FROM transactions WHERE date(date) = date('now')", null)
            if (c.moveToFirst()) {
                report.put("total_sales_amount", c.getDouble(0))
                report.put("total_liters_sold", c.getDouble(1))
                report.put("total_transactions", c.getInt(2))
            }
            c.close()
            
            c = db.rawQuery("SELECT COALESCE(SUM(amount),0) FROM payments WHERE date(date) = date('now')", null)
            if (c.moveToFirst()) report.put("total_payments", c.getDouble(0))
            c.close()
            
            report.put("total_sms_sent", getSmsCountToday())
            report.put("report_date", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date()))
        } catch (e: Exception) {
            Log.e("DatabaseHelper", "Error getting EOD report: ${e.message}", e)
        }
        
        return report
    }

    // ==================== الديون المتأخرة ====================
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

    // ==================== المخزون المنخفض ====================
    fun getLowStockRefills(threshold: Double): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        val c = db.rawQuery("SELECT * FROM refills WHERE remaining_qty < ?", arrayOf(threshold.toString()))
        try {
            while (c.moveToNext()) {
                arr.put(refillCursorToJson(c))
            }
        } finally {
            c.close()
        }
        return arr
    }

    // ==================== سجل SMS ====================
    fun logSms(phone: String, message: String, type: String, status: String) {
        val cv = ContentValues()
        cv.put("phone", phone)
        cv.put("message", message)
        cv.put("type", type)
        cv.put("status", status)
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
        var count = 0
        try {
            if (c.moveToFirst()) count = c.getInt(0)
        } finally {
            c.close()
        }
        return count
    }

    // ==================== سجل النشاطات ====================
    fun logActivity(operator: String, action: String, details: String) {
        val cv = ContentValues()
        cv.put("operator", operator)
        cv.put("action", action)
        cv.put("details", details)
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

    // ==================== الولاء ====================
    fun getLoyaltyHistory(customerId: Int): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        val c = db.rawQuery("SELECT * FROM loyalty_rewards WHERE customer_id=? ORDER BY id DESC", arrayOf(customerId.toString()))
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
        writableDatabase.execSQL("UPDATE customers SET loyalty_points = loyalty_points - ? WHERE id=?", arrayOf(points, customerId))
        val cv = ContentValues()
        cv.put("customer_id", customerId)
        cv.put("points_used", points)
        cv.put("reward_type", "discount")
        cv.put("description", description)
        writableDatabase.insert("loyalty_rewards", null, cv)
        return true
    }

    // ==================== AI Chat ====================
    fun saveAiMessage(sessionId: String, role: String, message: String) {
        val cv = ContentValues()
        cv.put("session_id", sessionId)
        cv.put("role", role)
        cv.put("message", message)
        writableDatabase.insert("ai_chat_history", null, cv)
    }

    fun getAiChatHistory(sessionId: String): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        val c = db.rawQuery("SELECT * FROM ai_chat_history WHERE session_id=? ORDER BY id", arrayOf(sessionId))
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

    // ==================== الإعدادات ====================
    fun setSetting(key: String, value: String) {
        val cv = ContentValues()
        cv.put("key", key)
        cv.put("value", value)
        writableDatabase.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getSetting(key: String): String {
        val db = readableDatabase
        val c = db.rawQuery("SELECT value FROM settings WHERE key=?", arrayOf(key))
        return try {
            if (c.moveToFirst()) {
                c.getString(0)
            } else {
                ""
            }
        } finally {
            c.close()
        }
    }

    // ==================== التصدير ====================
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
        val tc = db.rawQuery("SELECT * FROM transactions WHERE customer_id = ? ORDER BY id DESC", arrayOf(customerId.toString()))
        try {
            while (tc.moveToNext()) transactions.put(transactionCursorToJson(tc))
        } finally {
            tc.close()
        }
        report.put("transactions", transactions)

        val payments = JSONArray()
        val pc = db.rawQuery("SELECT * FROM payments WHERE customer_id = ? ORDER BY id DESC", arrayOf(customerId.toString()))
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

    // ==================== أدوات ====================
    companion object {
        private const val DB_NAME = "diesel_station.db"
        private const val VERSION = 4

        fun hashPassword(password: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(password.toByteArray(Charsets.UTF_8))
            return hash.joinToString("") { "%02x".format(it) }
        }
    }
}
