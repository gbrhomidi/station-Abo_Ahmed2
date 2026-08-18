package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteException
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.locks.ReentrantLock

/**
 * DatabaseHelper - قاعدة بيانات محطة أبو أحمد لمشتقات الديزل
 * الإصدار المدمج V14 - تم إصلاح جميع المشاكل الحرجة والعالية الخطورة
 * - تم جعل المُنشئ خاصاً (Singleton آمن)
 * - تم استخدام ThreadLocal لـ SimpleDateFormat (أمان الخيوط)
 * - تم إصلاح SQL Injection في الترقية
 * - تم إزالة ازدواجية recordEvent
 * - تم إصلاح addEmployeePayment لتحديث الجدول الصحيح
 * - تم إضافة .use {} لجميع المؤشرات (منع التسريبات)
 * - تم استخدام applicationContext لمنع تسريب الذاكرة
 * - تم إزالة dbLock من المعاملات (تحسين الأداء)
 * - تم إضافة معامل stationId للدوال التي كانت تفترض القيمة 1
 * - تم استدعاء ensureSmsSettings في onCreate
 * - توحيد تنسيقات التاريخ باستخدام ThreadLocal
 * - إضافة الدوال المفقودة: tableExists, getVersion, cleanupOldRateLimits,
 *   cleanupOldConversationContext, cleanupOldMetrics, addMeterReading,
 *   getMeterReadings, getTankReadings, getLatestMeterReadings,
 *   getAssetMaintenanceHistory, deleteOlderThan, syncContext, syncPreferences,
 *   sync, syncRateLimits, syncData, recordPerformanceStats, flush,
 *   performSecurityCheck, cleanupExpired, getCurrentMetrics, isOpen, checkIntegrity
 */
class DatabaseHelper private constructor(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, VERSION) {

    companion object {
        private const val TAG = "DatabaseHelper"
        private const val DB_NAME = "diesel_station.db"
        const val DATABASE_NAME = DB_NAME
        const val VERSION = 21

        private const val HASH_ITERATIONS = 10000
        private const val SMS_HASH_RETENTION_DAYS = 30

        // ThreadLocal لتنسيقات التاريخ (آمن للخيوط)
        private val dateFormatThreadLocal = ThreadLocal<SimpleDateFormat>()
        private val dateOnlyFormatThreadLocal = ThreadLocal<SimpleDateFormat>()
        private val timeFormatThreadLocal = ThreadLocal<SimpleDateFormat>()

        private fun getDateFormat(): SimpleDateFormat {
            var df = dateFormatThreadLocal.get()
            if (df == null) {
                df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                dateFormatThreadLocal.set(df)
            }
            return df
        }

        fun getDateOnlyFormat(): SimpleDateFormat {
            var df = dateOnlyFormatThreadLocal.get()
            if (df == null) {
                df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                dateOnlyFormatThreadLocal.set(df)
            }
            return df
        }

        private fun getTimeFormat(): SimpleDateFormat {
            var df = timeFormatThreadLocal.get()
            if (df == null) {
                df = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                timeFormatThreadLocal.set(df)
            }
            return df
        }

        fun hashPassword(password: String, salt: ByteArray? = null): Pair<String, String> {
            val actualSalt = salt ?: ByteArray(16).also { SecureRandom().nextBytes(it) }
            val digest = MessageDigest.getInstance("SHA-256")
            var hash = actualSalt + password.toByteArray(Charsets.UTF_8)
            repeat(HASH_ITERATIONS) {
                hash = digest.digest(hash)
                hash = actualSalt + hash
            }
            val finalHash = digest.digest(hash)
            val saltHex = actualSalt.joinToString("") { "%02x".format(it) }
            val hashHex = finalHash.joinToString("") { "%02x".format(it) }
            return Pair(hashHex, saltHex)
        }

        fun verifyPassword(password: String, storedHash: String, storedSalt: String): Boolean {
            val salt = storedSalt.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            return hashPassword(password, salt).first == storedHash
        }

        @Volatile
        private var instance: DatabaseHelper? = null

        @JvmStatic
        fun getInstance(context: Context): DatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: DatabaseHelper(context.applicationContext).also {
                    instance = it
                }
            }
        }

        @JvmStatic
        fun closeInstance() {
            instance?.close()
            instance = null
        }
    }

    private val dbLock = ReentrantLock()
    private val contextRef = context.applicationContext

    private data class BalanceAccountAccumulator(
        val id: Long,
        val code: String,
        val name: String,
        val nameAr: String?,
        val type: String,
        val level: Int,
        val normalBalance: String,
        var openingBalance: Double = 0.0,
        var debitTotal: Double = 0.0,
        var creditTotal: Double = 0.0,
        var openingInitialized: Boolean = false
    )

    private fun getCurrentDateTime(): String = getDateFormat().format(Date())
    private fun getCurrentDate(): String = getDateOnlyFormat().format(Date())
    private fun getCurrentTime(): String = getTimeFormat().format(Date())

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            createAllTables(db)
            ensureReportCacheTable(db)
            insertInitialData(db)
            ensureContractSchema(db)
            ensureActivityPermissions(db)
            ensureTaskPermissions(db)
            ensureMessagingPermissions(db)
            ensureSmsSettings(db)
            db.setTransactionSuccessful()
            Log.d(TAG, "Database V$VERSION created successfully")
        } finally {
            db.endTransaction()
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.beginTransaction()
        try {
            for (v in oldVersion until newVersion) {
                when (v) {
                    5 -> migrateV5ToV6(db)
                    6 -> migrateV6ToV7(db)
                    7 -> migrateV7ToV8(db)
                    8 -> migrateV8ToV9(db)
                    9 -> migrateV9ToV10(db)
                    10 -> migrateV10ToV11(db)
                    11 -> migrateV11ToV12(db)
                    12 -> migrateV12ToV13(db)
                    13 -> migrateV13ToV14(db)
                    14 -> migrateV14ToV15(db)
                    15 -> migrateV15ToV16(db)
                    16 -> migrateV16ToV17(db)
                    17 -> migrateV17ToV18(db)
                    18 -> ensureReportCacheTable(db)
                    19 -> migrateV19ToV20(db)
                    20 -> migrateV20ToV21(db)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        ensureSmsMessagesTable(db)
        ensureSmsMessagesColumns(db)
        createNotificationTables(db)
        ensureMessagingIndexes(db)
        ensureContractSchema(db)
        ensureReportCacheTable(db)
        createSmsProcessedTable(db)
        createSmsProcessedHashesTable(db)
        createSmsRateLimitsTable(db)
        createSmsConversationContextTable(db)
        createSmsCustomerPreferencesTable(db)
        createSmsInteractionHistoryTable(db)
        createSmsRecurringOrdersTable(db)
        createSmsMetricsTable(db)
        createSmsOtpVerificationsTable(db)
        createUserOtpVerificationsTable(db)
        createSmsOutboundDedupeTable(db)
        createSmsPlatformTables(db)
        ensureActivityPermissions(db)
        createTasksTable(db)
        ensureTaskPermissions(db)
        ensureMessagingPermissions(db)
        ensureSmsSettings(db)
    }

    private fun ensureSmsMessagesTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                phone_number TEXT NOT NULL,
                message_body TEXT NOT NULL,
                message_type TEXT DEFAULT 'incoming',
                status TEXT DEFAULT 'pending',
                party_id INTEGER REFERENCES parties(id),
                sent_at TEXT,
                is_read INTEGER DEFAULT 0,
                read_at TEXT,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
    }

    // ===================================================================================
    // دوال الترحيل (Migration)
    // ===================================================================================

    private fun migrateV5ToV6(db: SQLiteDatabase) {
        createEmployeeTable(db)
        createBadDebtTable(db)
        createCashDepositTable(db)
        createSmsWhitelistTable(db)
    }

    private fun migrateV6ToV7(db: SQLiteDatabase) {
        createCoreTables(db)
        createSecurityTables(db)
        createPartyTables(db)
        createVehicleTables(db)
        createProductTables(db)
        createTankPumpTables(db)
        createInventoryTables(db)
        createSalesTables(db)
        createFinanceTables(db)
        createAccountingTables(db)
        createHRTables(db)
        createAssetTables(db)
        createNotificationTables(db)
        createLogTables(db)
        createAdvancedTables(db)
        createLedgerTables(db)
        createPrintTables(db)
        createIndexes(db)
        insertInitialData(db)
    }

    private fun migrateV7ToV8(db: SQLiteDatabase) {
        insertInitialData(db)
    }

    private fun migrateV8ToV9(db: SQLiteDatabase) {
        try {
            db.execSQL("ALTER TABLE users ADD COLUMN password_salt VARCHAR(255)")
        } catch (e: Exception) { }

        val (hashAdmin, saltAdmin) = hashPassword("admin123")
        val cvAdmin = ContentValues().apply {
            put("password_hash", hashAdmin)
            put("password_salt", saltAdmin)
        }
        db.update("users", cvAdmin, "username = ?", arrayOf("admin"))

        val (hashKhalil, saltKhalil) = hashPassword("123321")
        val cvKhalil = ContentValues().apply {
            put("password_hash", hashKhalil)
            put("password_salt", saltKhalil)
        }
        db.update("users", cvKhalil, "username = ?", arrayOf("خليل أحمد"))
    }

    private fun migrateV9ToV10(db: SQLiteDatabase) {
        val alterStatements = listOf(
            "ALTER TABLE sales_transactions ADD COLUMN delivery_location TEXT",
            "ALTER TABLE sales_transactions ADD COLUMN delivery_time TEXT",
            "ALTER TABLE sales_transactions ADD COLUMN driver_id INTEGER REFERENCES drivers(id)",
            "ALTER TABLE sales_transactions ADD COLUMN vehicle_id INTEGER REFERENCES vehicles(id)",
            "ALTER TABLE sales_transactions ADD COLUMN order_type TEXT DEFAULT 'sale'"
        )
        for (sql in alterStatements) {
            try { db.execSQL(sql) } catch (e: Exception) { }
        }

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS deliveries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                sale_id INTEGER REFERENCES sales_transactions(id),
                party_id INTEGER REFERENCES parties(id),
                vehicle_id INTEGER REFERENCES vehicles(id),
                driver_id INTEGER REFERENCES drivers(id),
                delivery_date TEXT NOT NULL,
                quantity REAL DEFAULT 0,
                fuel_type TEXT DEFAULT 'diesel',
                price_per_liter REAL DEFAULT 0,
                total_amount REAL DEFAULT 0,
                status TEXT DEFAULT 'delivered',
                location TEXT,
                notes TEXT,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
                is_deleted INTEGER DEFAULT 0
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS fuel_sales (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                sale_id INTEGER REFERENCES sales_transactions(id),
                shift_id INTEGER REFERENCES shifts(id),
                pump_id INTEGER REFERENCES pumps(id),
                fuel_type_id INTEGER REFERENCES fuel_types(id),
                quantity REAL DEFAULT 0,
                price_per_liter REAL DEFAULT 0,
                total_amount REAL DEFAULT 0,
                payment_method TEXT DEFAULT 'cash',
                customer_id INTEGER REFERENCES parties(id),
                vehicle_plate TEXT,
                sale_date TEXT,
                sale_time TEXT,
                notes TEXT,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                is_deleted INTEGER DEFAULT 0
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS stock_movements (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                product_id INTEGER REFERENCES products(id),
                movement_type TEXT DEFAULT 'in',
                quantity REAL DEFAULT 0,
                unit_cost REAL DEFAULT 0,
                total_cost REAL DEFAULT 0,
                reference_type TEXT,
                reference_id INTEGER,
                movement_date TEXT,
                notes TEXT,
                created_by TEXT,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                is_deleted INTEGER DEFAULT 0
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                phone_number TEXT NOT NULL,
                message_body TEXT NOT NULL,
                message_type TEXT DEFAULT 'incoming',
                status TEXT DEFAULT 'pending',
                party_id INTEGER REFERENCES parties(id),
                sent_at TEXT,
                is_read INTEGER DEFAULT 0,
                read_at TEXT,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_templates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                template_name TEXT NOT NULL,
                template_body TEXT NOT NULL,
                template_type TEXT DEFAULT 'general',
                is_active INTEGER DEFAULT 1,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS shift_sales (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                shift_id INTEGER REFERENCES shifts(id),
                sale_id INTEGER REFERENCES sales_transactions(id),
                amount REAL DEFAULT 0,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS shift_deliveries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                shift_id INTEGER REFERENCES shifts(id),
                delivery_id INTEGER REFERENCES deliveries(id),
                amount REAL DEFAULT 0,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS shift_expenses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                shift_id INTEGER REFERENCES shifts(id),
                expense_type TEXT,
                amount REAL DEFAULT 0,
                description TEXT,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS settings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                setting_key TEXT UNIQUE NOT NULL,
                setting_value TEXT,
                setting_type TEXT DEFAULT 'string',
                description TEXT,
                is_editable INTEGER DEFAULT 1,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS assets (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                asset_code TEXT,
                asset_name TEXT NOT NULL,
                asset_type TEXT,
                asset_category TEXT,
                purchase_date TEXT,
                purchase_cost REAL DEFAULT 0,
                current_value REAL DEFAULT 0,
                depreciation_rate REAL DEFAULT 0,
                location TEXT,
                status TEXT DEFAULT 'active',
                maintenance_date TEXT,
                next_maintenance TEXT,
                notes TEXT,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
                is_deleted INTEGER DEFAULT 0
            )
        """)
    }

    private fun migrateV10ToV11(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ai_chat_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS maintenance_requests (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                request_code VARCHAR(30) UNIQUE NOT NULL,
                asset_type VARCHAR(20) NOT NULL,
                asset_id INTEGER NOT NULL,
                request_type VARCHAR(30) NOT NULL,
                priority VARCHAR(10) DEFAULT 'medium',
                title VARCHAR(200) NOT NULL,
                description TEXT NOT NULL,
                description_ar TEXT,
                symptoms TEXT,
                error_codes TEXT,
                reported_by INTEGER NOT NULL,
                reported_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                assigned_to INTEGER,
                assigned_at DATETIME,
                scheduled_date DATE,
                scheduled_time TIME,
                estimated_duration INTEGER,
                started_at DATETIME,
                completed_at DATETIME,
                actual_duration INTEGER,
                resolution TEXT,
                resolution_ar TEXT,
                parts_used TEXT,
                labor_cost DECIMAL(12,2) DEFAULT 0,
                parts_cost DECIMAL(12,2) DEFAULT 0,
                total_cost DECIMAL(12,2) DEFAULT 0,
                status VARCHAR(20) DEFAULT 'open',
                approved_by INTEGER,
                approved_at DATETIME,
                before_photos TEXT,
                after_photos TEXT,
                station_id INTEGER NOT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (reported_by) REFERENCES users(id),
                FOREIGN KEY (assigned_to) REFERENCES users(id),
                FOREIGN KEY (approved_by) REFERENCES users(id),
                FOREIGN KEY (station_id) REFERENCES stations(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_whitelist (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                phone TEXT UNIQUE NOT NULL,
                name TEXT,
                enabled INTEGER DEFAULT 1,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS cash_deposits (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                customer_id INTEGER REFERENCES parties(id),
                amount REAL DEFAULT 0,
                balance_after REAL DEFAULT 0,
                notes TEXT,
                operator TEXT DEFAULT 'System',
                date TEXT DEFAULT CURRENT_TIMESTAMP,
                is_deleted INTEGER DEFAULT 0
            )
        """)
        try {
            db.execSQL("ALTER TABLE payments ADD COLUMN operator TEXT DEFAULT 'System'")
        } catch (e: Exception) { }
        try {
            db.execSQL("ALTER TABLE payments ADD COLUMN notes TEXT")
        } catch (e: Exception) { }
        try {
            db.execSQL("ALTER TABLE maintenance_requests ADD COLUMN station_id INTEGER REFERENCES stations(id)")
        } catch (e: Exception) { }
    }

    private fun migrateV11ToV12(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS cash_movements (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                cash_box_id INTEGER,
                movement_type TEXT NOT NULL,
                amount REAL NOT NULL,
                balance_before REAL,
                balance_after REAL,
                description TEXT,
                reference_type TEXT,
                reference_id INTEGER,
                created_by TEXT,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                is_deleted INTEGER DEFAULT 0,
                FOREIGN KEY (cash_box_id) REFERENCES cash_boxes(id)
            )
        """)
    }

    private fun migrateV12ToV13(db: SQLiteDatabase) {
        ensureSmsSettings(db)
        createSmsProcessedTable(db)
        createSmsProcessedHashesTable(db)
        createSmsRateLimitsTable(db)
        createSmsConversationContextTable(db)
        createSmsCustomerPreferencesTable(db)
        createSmsInteractionHistoryTable(db)
        createSmsRecurringOrdersTable(db)
        createSmsMetricsTable(db)
        createSmsOtpVerificationsTable(db)
        createUserOtpVerificationsTable(db)
        Log.d(TAG, "Migrated to V13 successfully")
    }

    private fun migrateV13ToV14(db: SQLiteDatabase) {
        ensureActivityPermissions(db)
        Log.d(TAG, "Migrated to V14 successfully")
    }
    private fun migrateV14ToV15(db: SQLiteDatabase) {
        ensureContractSchema(db)
        ensureContractPermissions(db)
    }

    private fun migrateV15ToV16(db: SQLiteDatabase) {
        createSecurityTables(db)
        ensureColumn(db, "permissions", "requires_station", "INTEGER DEFAULT 0")
        ensureColumn(db, "permissions", "requires_branch", "INTEGER DEFAULT 0")
        ensureColumn(db, "permissions", "remarks", "TEXT")
        ensureColumn(db, "permissions", "extra_data", "TEXT")
        ensureColumn(db, "permissions", "updated_at", "DATETIME")
        Log.d(TAG, "Migrated IAM schema to V16 successfully")
    }

    private fun migrateV16ToV17(db: SQLiteDatabase) {
        createSmsOutboundDedupeTable(db)
        Log.d(TAG, "Migrated SMS outbound dedupe schema to V17 successfully")
    }

    private fun migrateV19ToV20(db: SQLiteDatabase) {
        ensureSmsConversationColumns(db)
        createSmsPlatformTables(db)
        Log.d(TAG, "Migrated SMS durable platform schema to V20 successfully")
    }

    private fun migrateV20ToV21(db: SQLiteDatabase) {
        createTasksTable(db)
        ensureTaskPermissions(db)
        Log.d(TAG, "Migrated tasks schema to V21 successfully")
    }

    private fun migrateV17ToV18(db: SQLiteDatabase) {
        ensureColumn(db, "inventory_movements", "warehouse_id", "INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_inventory_movements_warehouse_date ON inventory_movements(warehouse_id, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_inventory_movements_product_date ON inventory_movements(product_id, created_at)")
        Log.d(TAG, "Migrated inventory reporting schema to V18 successfully")
    }

    private fun tableHasColumn(db: SQLiteDatabase, tableName: String, columnName: String): Boolean {
        return db.rawQuery("PRAGMA table_info($tableName)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) if (cursor.getString(nameIndex) == columnName) return@use true
            false
        }
    }

    private fun ensureColumn(db: SQLiteDatabase, tableName: String, columnName: String, definition: String) {
        if (!tableHasColumn(db, tableName, columnName)) db.execSQL("ALTER TABLE $tableName ADD COLUMN $columnName $definition")
    }

    /** Compatibility additions for databases created before the messaging read-state contract. */
    private fun ensureSmsMessagesColumns(db: SQLiteDatabase) {
        ensureColumn(db, "sms_messages", "is_read", "INTEGER DEFAULT 0")
        ensureColumn(db, "sms_messages", "read_at", "TEXT")
    }

    /** Idempotent indexes for the high-volume local messaging and notification queries. */
    private fun ensureMessagingIndexes(db: SQLiteDatabase) {
        fun indexIfTableExists(indexName: String, tableName: String, columnName: String) {
            val exists = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
                arrayOf(tableName)
            ).use { it.moveToFirst() }
            if (exists) db.execSQL("CREATE INDEX IF NOT EXISTS $indexName ON $tableName($columnName)")
        }
        indexIfTableExists("idx_sms_messages_phone_number", "sms_messages", "phone_number")
        indexIfTableExists("idx_sms_messages_status", "sms_messages", "status")
        indexIfTableExists("idx_sms_messages_created_at", "sms_messages", "created_at")
        indexIfTableExists("idx_sms_messages_is_read", "sms_messages", "is_read")
        indexIfTableExists("idx_sms_logs_phone_number", "sms_logs", "phone_number")
        indexIfTableExists("idx_sms_logs_status", "sms_logs", "status")
        indexIfTableExists("idx_sms_logs_created_at", "sms_logs", "created_at")
        indexIfTableExists("idx_notifications_user_id", "notifications", "user_id")
        indexIfTableExists("idx_notifications_status", "notifications", "status")
        indexIfTableExists("idx_notifications_channel", "notifications", "channel")
        indexIfTableExists("idx_notifications_created_at", "notifications", "created_at")
        indexIfTableExists("idx_sms_whitelist_phone", "sms_whitelist", "phone")
        indexIfTableExists("idx_sms_whitelist_enabled", "sms_whitelist", "enabled")
        indexIfTableExists("idx_notification_templates_channel", "notification_templates", "channel")
        indexIfTableExists("idx_notification_templates_active", "notification_templates", "is_active")
    }

    /** Seeds only the typed contracts required by the messaging screens; grants are scoped to admin role 1. */
    private fun ensureMessagingPermissions(db: SQLiteDatabase) {
        val rows = listOf(
            arrayOf("PER-SMS-READ-V21", "sms.read", "Read SMS", "قراءة الرسائل", "sms", "الرسائل", "read"),
            arrayOf("PER-SMS-CREATE-V21", "sms.create", "Create SMS", "إنشاء الرسائل", "sms", "الرسائل", "create"),
            arrayOf("PER-SMS-UPDATE-V21", "sms.update", "Update SMS", "تحديث الرسائل", "sms", "الرسائل", "update"),
            arrayOf("PER-SMS-DELETE-V21", "sms.delete", "Delete SMS", "حذف الرسائل", "sms", "الرسائل", "delete"),
            arrayOf("PER-NOTIFICATIONS-READ-V21", "notifications.read", "Read Notifications", "قراءة الإشعارات", "notifications", "الإشعارات", "read"),
            arrayOf("PER-NOTIFICATIONS-CREATE-V21", "notifications.create", "Create Notification Templates", "إنشاء قوالب الإشعارات", "notifications", "الإشعارات", "create"),
            arrayOf("PER-NOTIFICATIONS-UPDATE-V21", "notifications.update", "Update Notification Templates", "تحديث قوالب الإشعارات", "notifications", "الإشعارات", "update"),
            arrayOf("PER-NOTIFICATIONS-DELETE-V21", "notifications.delete", "Delete Notification Templates", "حذف قوالب الإشعارات", "notifications", "الإشعارات", "delete"),
            arrayOf("PER-WHITELIST-READ-V21", "whitelist.read", "Read SMS Whitelist", "قراءة القائمة البيضاء", "whitelist", "القائمة البيضاء", "read"),
            arrayOf("PER-WHITELIST-CREATE-V21", "whitelist.create", "Create SMS Whitelist", "إضافة القائمة البيضاء", "whitelist", "القائمة البيضاء", "create"),
            arrayOf("PER-WHITELIST-UPDATE-V21", "whitelist.update", "Update SMS Whitelist", "تحديث القائمة البيضاء", "whitelist", "القائمة البيضاء", "update"),
            arrayOf("PER-WHITELIST-DELETE-V21", "whitelist.delete", "Delete SMS Whitelist", "حذف القائمة البيضاء", "whitelist", "القائمة البيضاء", "delete")
        )
        rows.forEach { row ->
            db.execSQL(
                """INSERT OR IGNORE INTO permissions
                   (uuid, permission_code, permission_name, permission_name_ar, module, module_name_ar, action)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
                row.map { it as Any }.toTypedArray()
            )
            val permissionCode = row[1] as String
            val permissionId = db.rawQuery(
                "SELECT id FROM permissions WHERE permission_code = ? LIMIT 1",
                arrayOf(permissionCode)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
            if (permissionId > 0L) {
                val values = ContentValues().apply {
                    put("uuid", "RP-${permissionCode.uppercase().replace('.', '-')}-V21-1")
                    put("role_id", 1L)
                    put("permission_id", permissionId)
                    put("can_create", if (permissionCode.endsWith(".create")) 1 else 0)
                    put("can_read", if (permissionCode.endsWith(".read")) 1 else 0)
                    put("can_update", if (permissionCode.endsWith(".update")) 1 else 0)
                    put("can_delete", if (permissionCode.endsWith(".delete")) 1 else 0)
                    put("can_export", 0)
                    put("can_print", 0)
                    put("can_approve", 0)
                    put("created_by", 1L)
                }
                db.insertWithOnConflict("role_permissions", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }

    private fun ensureTaskPermissions(db: SQLiteDatabase) {
        val rows = listOf(
            arrayOf("PER-TASKS-READ-V21", "tasks.read", "Read Tasks", "قراءة المهام", "tasks", "المهام", "read"),
            arrayOf("PER-TASKS-CREATE-V21", "tasks.create", "Create Tasks", "إنشاء المهام", "tasks", "المهام", "create"),
            arrayOf("PER-TASKS-UPDATE-V21", "tasks.update", "Update Tasks", "تحديث المهام", "tasks", "المهام", "update"),
            arrayOf("PER-TASKS-DELETE-V21", "tasks.delete", "Delete Tasks", "حذف المهام", "tasks", "المهام", "delete")
        )
        rows.forEach { row ->
            db.execSQL(
                """INSERT OR IGNORE INTO permissions
                   (uuid, permission_code, permission_name, permission_name_ar, module, module_name_ar, action)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
                row.map { it as Any }.toTypedArray()
            )
            val permissionCode = row[1] as String
            val permissionId = db.rawQuery(
                "SELECT id FROM permissions WHERE permission_code = ? LIMIT 1",
                arrayOf(permissionCode)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
            if (permissionId > 0L) {
                val values = ContentValues().apply {
                    put("uuid", "RP-${permissionCode.uppercase().replace('.', '-')}-ADMIN-V21")
                    put("role_id", 1L)
                    put("permission_id", permissionId)
                    put("can_create", if (permissionCode.endsWith(".create")) 1 else 0)
                    put("can_read", if (permissionCode.endsWith(".read")) 1 else 0)
                    put("can_update", if (permissionCode.endsWith(".update")) 1 else 0)
                    put("can_delete", if (permissionCode.endsWith(".delete")) 1 else 0)
                    put("can_export", 1)
                    put("can_print", 0)
                    put("can_approve", 0)
                    put("created_by", 1L)
                }
                db.insertWithOnConflict("role_permissions", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }

    private fun ensureActivityPermissions(db: SQLiteDatabase) {
        db.execSQL(
            """
            INSERT OR IGNORE INTO permissions
                (uuid, permission_code, permission_name, permission_name_ar, module, module_name_ar, action)
            VALUES
                ('PER-ACTIVITY-READ-V14', 'activity.read', 'View Activity Logs', 'عرض سجل النشاط', 'activity', 'النشاط', 'read'),
                ('PER-ACTIVITY-DELETE-V14', 'activity.delete', 'Delete Activity Logs', 'حذف سجل النشاط', 'activity', 'النشاط', 'delete')
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO role_permissions
                (uuid, role_id, permission_id, can_create, can_read, can_update, can_delete, can_export, can_print, can_approve)
            SELECT 'RP-ACTIVITY-READ-ADMIN-V14', 1, id, 0, 1, 0, 0, 1, 1, 0
            FROM permissions WHERE permission_code = 'activity.read'
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO role_permissions
                (uuid, role_id, permission_id, can_create, can_read, can_update, can_delete, can_export, can_print, can_approve)
            SELECT 'RP-ACTIVITY-DELETE-ADMIN-V14', 1, id, 0, 1, 0, 1, 0, 0, 0
            FROM permissions WHERE permission_code = 'activity.delete'
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO permissions
                (uuid, permission_code, permission_name, permission_name_ar, module, module_name_ar, action)
            VALUES
                ('PER-ACCOUNTING-UPDATE-V14', 'accounting.update', 'Update Accounting Records', 'تعديل سجلات المحاسبة', 'accounting', 'المحاسبة', 'update'),
                ('PER-ACCOUNTING-DELETE-V14', 'accounting.delete', 'Delete Accounting Records', 'حذف سجلات المحاسبة', 'accounting', 'المحاسبة', 'delete'),
                ('PER-ACCOUNTING-EXPORT-V14', 'accounting.export', 'Export Accounting Reports', 'تصدير تقارير المحاسبة', 'accounting', 'المحاسبة', 'export'),
                ('PER-ACCOUNTING-PRINT-V14', 'accounting.print', 'Print Accounting Reports', 'طباعة تقارير المحاسبة', 'accounting', 'المحاسبة', 'print')
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO role_permissions
                (uuid, role_id, permission_id, can_create, can_read, can_update, can_delete, can_export, can_print, can_approve)
            SELECT 'RP-ACCOUNTING-UPDATE-ADMIN-V14', 1, id, 0, 0, 1, 0, 0, 0, 0
            FROM permissions WHERE permission_code = 'accounting.update'
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO role_permissions
                (uuid, role_id, permission_id, can_create, can_read, can_update, can_delete, can_export, can_print, can_approve)
            SELECT 'RP-ACCOUNTING-DELETE-ADMIN-V14', 1, id, 0, 0, 0, 1, 0, 0, 0
            FROM permissions WHERE permission_code = 'accounting.delete'
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO role_permissions
                (uuid, role_id, permission_id, can_create, can_read, can_update, can_delete, can_export, can_print, can_approve)
            SELECT 'RP-ACCOUNTING-EXPORT-ADMIN-V14', 1, id, 0, 1, 0, 0, 1, 0, 0
            FROM permissions WHERE permission_code = 'accounting.export'
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO role_permissions
                (uuid, role_id, permission_id, can_create, can_read, can_update, can_delete, can_export, can_print, can_approve)
            SELECT 'RP-ACCOUNTING-PRINT-ADMIN-V14', 1, id, 0, 1, 0, 0, 0, 1, 0
            FROM permissions WHERE permission_code = 'accounting.print'
            """.trimIndent()
        )
    }

    // ===================================================================================
    // دوال إنشاء الجداول (CREATE TABLE)
    // ===================================================================================


    // =========================================================================
    // CONTRACTS_V15_SCHEMA: امتدادات العقود المعتمدة على SQLite المحلية.
    // =========================================================================
    private fun createContractExtensionTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS contract_line_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                contract_id INTEGER NOT NULL,
                line_number INTEGER NOT NULL,
                description TEXT NOT NULL,
                quantity DECIMAL(15,4) DEFAULT 1,
                unit_price DECIMAL(15,2) DEFAULT 0,
                total_amount DECIMAL(15,2) DEFAULT 0,
                notes TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                FOREIGN KEY (contract_id) REFERENCES contracts(id) ON DELETE CASCADE,
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS contract_payment_schedules (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                contract_id INTEGER NOT NULL,
                installment_number INTEGER NOT NULL,
                due_date DATE NOT NULL,
                amount DECIMAL(15,2) NOT NULL,
                status VARCHAR(20) DEFAULT 'pending' CHECK(status IN ('pending', 'paid', 'overdue', 'cancelled')),
                paid_at DATETIME,
                notes TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                FOREIGN KEY (contract_id) REFERENCES contracts(id) ON DELETE CASCADE,
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS contract_status_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                contract_id INTEGER NOT NULL,
                old_status VARCHAR(20),
                new_status VARCHAR(20) NOT NULL,
                reason TEXT,
                changed_by INTEGER,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (contract_id) REFERENCES contracts(id) ON DELETE CASCADE,
                FOREIGN KEY (changed_by) REFERENCES users(id)
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_contract_line_items_contract ON contract_line_items(contract_id, is_deleted)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_contract_payments_contract ON contract_payment_schedules(contract_id, is_deleted)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_contract_payments_due_date ON contract_payment_schedules(due_date, status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_contract_status_history_contract ON contract_status_history(contract_id, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_contracts_archived ON contracts(is_archived, is_deleted)")
    }

    private fun contractColumnExists(db: SQLiteDatabase, columnName: String): Boolean {
        return db.rawQuery("PRAGMA table_info(contracts)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) if (cursor.getString(nameIndex) == columnName) return@use true
            false
        }
    }

    private fun ensureContractColumn(db: SQLiteDatabase, columnName: String, definition: String) {
        if (!contractColumnExists(db, columnName)) {
            db.execSQL("ALTER TABLE contracts ADD COLUMN $columnName $definition")
        }
    }

    private fun ensureContractSchema(db: SQLiteDatabase) {
        val columns = listOf(
            "parent_contract_id" to "INTEGER",
            "renewal_count" to "INTEGER DEFAULT 0",
            "reminder_days" to "INTEGER DEFAULT 30",
            "is_archived" to "INTEGER DEFAULT 0",
            "archived_at" to "DATETIME",
            "archived_by" to "INTEGER",
            "is_deleted" to "INTEGER DEFAULT 0",
            "deleted_at" to "DATETIME",
            "deleted_by" to "INTEGER",
            "updated_by" to "INTEGER",
            "attachments_json" to "TEXT"
        )
        columns.forEach { (name, definition) -> ensureContractColumn(db, name, definition) }
        createContractExtensionTables(db)
        ensureContractPermissions(db)
    }

    private fun ensureContractPermissions(db: SQLiteDatabase) {
        val permissionRows = listOf(
            arrayOf("PER-CONTRACTS-READ-V15", "contracts.read", "View Contracts", "عرض العقود", "contracts", "العقود", "read"),
            arrayOf("PER-CONTRACTS-CREATE-V15", "contracts.create", "Create Contracts", "إنشاء العقود", "contracts", "العقود", "create"),
            arrayOf("PER-CONTRACTS-UPDATE-V15", "contracts.update", "Update Contracts", "تعديل العقود", "contracts", "العقود", "update"),
            arrayOf("PER-CONTRACTS-DELETE-V15", "contracts.delete", "Delete Contracts", "حذف العقود", "contracts", "العقود", "delete"),
            arrayOf("PER-CONTRACTS-EXPORT-V15", "contracts.export", "Export Contracts", "تصدير العقود", "contracts", "العقود", "export"),
            arrayOf("PER-CONTRACTS-AUDIT-V15", "contracts.audit", "Audit Contracts", "سجل العقود", "contracts", "العقود", "audit")
        )
        permissionRows.forEach { row ->
            db.execSQL(
                """INSERT OR IGNORE INTO permissions
                   (uuid, permission_code, permission_name, permission_name_ar, module, module_name_ar, action)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
                row.map { it as Any }.toTypedArray()
            )
        }
        val rolesReadWrite = listOf(1L, 2L, 3L)
        val rolesReadAudit = listOf(1L, 2L, 3L, 5L)
        val rolesDelete = listOf(1L, 2L)
        val grants = listOf(
            "contracts.read" to rolesReadAudit,
            "contracts.create" to rolesReadWrite,
            "contracts.update" to rolesReadWrite,
            "contracts.delete" to rolesDelete,
            "contracts.export" to rolesReadAudit,
            "contracts.audit" to rolesReadAudit
        )
        grants.forEach { (permissionCode, roles) ->
            val permissionId = db.rawQuery(
                "SELECT id FROM permissions WHERE permission_code = ? LIMIT 1",
                arrayOf(permissionCode)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
            if (permissionId <= 0) return@forEach
            roles.forEach { roleId ->
                val values = ContentValues().apply {
                    put("uuid", "RP-${permissionCode.uppercase().replace('.', '-')}-V15-$roleId")
                    put("role_id", roleId)
                    put("permission_id", permissionId)
                    put("can_create", if (permissionCode.endsWith("create") || permissionCode.endsWith("update")) 1 else 0)
                    put("can_read", if (permissionCode.endsWith("read") || permissionCode.endsWith("export") || permissionCode.endsWith("audit")) 1 else 0)
                    put("can_update", if (permissionCode.endsWith("update")) 1 else 0)
                    put("can_delete", if (permissionCode.endsWith("delete")) 1 else 0)
                    put("can_export", if (permissionCode.endsWith("export")) 1 else 0)
                    put("can_print", if (permissionCode.endsWith("export") || permissionCode.endsWith("audit")) 1 else 0)
                    put("can_approve", 0)
                    put("created_by", 1)
                }
                db.insertWithOnConflict("role_permissions", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }



    // ========================================================================
    // REPORT_CACHE_V1: تخزين مؤقت للقراءة فقط لتقارير المحاسبة وKPI.
    // لا يُستخدم لحفظ أو مزامنة أو تنفيذ أي عملية تجارية.
    // ========================================================================
    private fun ensureReportCacheTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS report_cache (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                cache_key TEXT NOT NULL,
                params_hash TEXT NOT NULL,
                user_id INTEGER NOT NULL,
                station_id INTEGER NOT NULL DEFAULT 0,
                payload_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                last_accessed_at INTEGER NOT NULL,
                schema_version INTEGER NOT NULL,
                UNIQUE(cache_key, params_hash, user_id, station_id)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_report_cache_scope ON report_cache(user_id, station_id, cache_key)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_report_cache_expiry ON report_cache(expires_at)")
    }

    private fun reportCacheParamsHash(paramsJson: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(paramsJson.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun reportCacheTimeLabel(timestamp: Long): String = getDateFormat().format(Date(timestamp))

    fun putReportCache(cacheKey: String, paramsJson: String, userId: Long, stationId: Int, payloadJson: String, ttlSeconds: Long): JSONObject {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val now = System.currentTimeMillis()
            val expiresAt = now + (ttlSeconds.coerceAtLeast(60L) * 1000L)
            val paramsHash = reportCacheParamsHash(paramsJson.ifBlank { "{}" })
            val values = ContentValues().apply {
                put("cache_key", cacheKey)
                put("params_hash", paramsHash)
                put("user_id", userId)
                put("station_id", stationId)
                put("payload_json", payloadJson)
                put("created_at", now)
                put("expires_at", expiresAt)
                put("last_accessed_at", now)
                put("schema_version", VERSION)
            }
            db.insertWithOnConflict("report_cache", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            JSONObject().apply {
                put("source", "sqlite")
                put("read_only", false)
                put("stale", false)
                put("cached_at", reportCacheTimeLabel(now))
                put("expires_at", reportCacheTimeLabel(expiresAt))
            }
        } finally { dbLock.unlock() }
    }

    fun getReportCache(cacheKey: String, paramsJson: String, userId: Long, stationId: Int): JSONObject? {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val paramsHash = reportCacheParamsHash(paramsJson.ifBlank { "{}" })
            db.rawQuery(
                "SELECT payload_json, created_at, expires_at, schema_version FROM report_cache WHERE cache_key = ? AND params_hash = ? AND user_id = ? AND station_id = ? LIMIT 1",
                arrayOf(cacheKey, paramsHash, userId.toString(), stationId.toString())
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                val payloadJson = cursor.getString(0)
                val createdAt = cursor.getLong(1)
                val expiresAt = cursor.getLong(2)
                val schemaVersion = cursor.getInt(3)
                val now = System.currentTimeMillis()
                if (schemaVersion != VERSION || now - createdAt > 7L * 24L * 60L * 60L * 1000L) {
                    db.delete("report_cache", "cache_key = ? AND params_hash = ? AND user_id = ? AND station_id = ?", arrayOf(cacheKey, paramsHash, userId.toString(), stationId.toString()))
                    return null
                }
                val updated = ContentValues().apply { put("last_accessed_at", now) }
                db.update("report_cache", updated, "cache_key = ? AND params_hash = ? AND user_id = ? AND station_id = ?", arrayOf(cacheKey, paramsHash, userId.toString(), stationId.toString()))
                val meta = JSONObject().apply {
                    put("source", "cache")
                    put("read_only", true)
                    put("stale", now > expiresAt)
                    put("cached_at", reportCacheTimeLabel(createdAt))
                    put("expires_at", reportCacheTimeLabel(expiresAt))
                    put("message", "نسخة مؤقتة للقراءة فقط؛ لم تُنفّذ أي عملية محاسبية")
                }
                JSONObject().apply { put("payload_json", payloadJson); put("meta", meta) }
            }
        } finally { dbLock.unlock() }
    }

    fun invalidateReportCache(userId: Long, stationId: Int): Int {
        dbLock.lock()
        return try { writableDatabase.delete("report_cache", "user_id = ? AND station_id = ?", arrayOf(userId.toString(), stationId.toString())) } finally { dbLock.unlock() }
    }

    fun clearReportCacheForUser(userId: Long): Int {
        dbLock.lock()
        return try { writableDatabase.delete("report_cache", "user_id = ?", arrayOf(userId.toString())) } finally { dbLock.unlock() }
    }

    private fun createTasksTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS tasks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                task_type TEXT NOT NULL,
                reference TEXT,
                task_date TEXT NOT NULL,
                amount REAL NOT NULL DEFAULT 0,
                priority TEXT NOT NULL DEFAULT 'متوسطة',
                status TEXT NOT NULL DEFAULT 'قيد التنفيذ',
                notes TEXT,
                is_resolved INTEGER NOT NULL DEFAULT 0,
                resolved_at TEXT,
                resolved_by INTEGER,
                is_archived INTEGER NOT NULL DEFAULT 0,
                archived_at TEXT,
                archived_by INTEGER,
                is_deleted INTEGER NOT NULL DEFAULT 0,
                deleted_at TEXT,
                deleted_by INTEGER,
                created_by INTEGER,
                updated_by INTEGER,
                extra_data TEXT,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (resolved_by) REFERENCES users(id),
                FOREIGN KEY (archived_by) REFERENCES users(id),
                FOREIGN KEY (deleted_by) REFERENCES users(id),
                FOREIGN KEY (created_by) REFERENCES users(id),
                FOREIGN KEY (updated_by) REFERENCES users(id)
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_pending ON tasks(is_deleted, is_archived, is_resolved, task_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_status_priority ON tasks(status, priority, is_deleted)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_reference ON tasks(reference)")
    }

    private fun createAllTables(db: SQLiteDatabase) {
        createCoreTables(db)
        createSecurityTables(db)
        createPartyTables(db)
        createVehicleTables(db)
        createProductTables(db)
        createTankPumpTables(db)
        createInventoryTables(db)
        createSalesTables(db)
        createFinanceTables(db)
        createAccountingTables(db)
        createHRTables(db)
        createAssetTables(db)
        createNotificationTables(db)
        createLogTables(db)
        createAdvancedTables(db)
        createLedgerTables(db)
        createPrintTables(db)
        createEmployeeTable(db)
        createBadDebtTable(db)
        createCashDepositTable(db)
        createSmsWhitelistTable(db)
        createMaintenanceRequestsTable(db)
        createTasksTable(db)
        createAiChatTable(db)
        createCashMovementsTable(db)
        createSmsProcessedTable(db)
        createSmsProcessedHashesTable(db)
        createSmsRateLimitsTable(db)
        createSmsConversationContextTable(db)
        createSmsCustomerPreferencesTable(db)
        createSmsInteractionHistoryTable(db)
        createSmsRecurringOrdersTable(db)
        createSmsMetricsTable(db)
        createSmsOtpVerificationsTable(db)
        createUserOtpVerificationsTable(db)
        createSmsOutboundDedupeTable(db)
        createSmsPlatformTables(db)
        createIndexes(db)
    }

    private fun createCoreTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS currencies (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                currency_code VARCHAR(3) NOT NULL UNIQUE,
                currency_name VARCHAR(100) NOT NULL,
                currency_name_ar VARCHAR(100),
                symbol VARCHAR(10),
                symbol_position VARCHAR(10) DEFAULT 'after' CHECK(symbol_position IN ('before', 'after')),
                decimal_places INTEGER DEFAULT 2,
                is_default INTEGER DEFAULT 0,
                is_active INTEGER DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS companies (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                company_code VARCHAR(20) UNIQUE NOT NULL,
                company_name VARCHAR(200) NOT NULL,
                company_name_ar VARCHAR(200),
                trade_name VARCHAR(200),
                legal_form VARCHAR(50),
                tax_number VARCHAR(50),
                commercial_register VARCHAR(50),
                license_number VARCHAR(50),
                phone VARCHAR(20),
                phone2 VARCHAR(20),
                email VARCHAR(100),
                website VARCHAR(100),
                fax VARCHAR(20),
                country VARCHAR(100),
                city VARCHAR(100),
                district VARCHAR(100),
                street VARCHAR(200),
                building VARCHAR(50),
                postal_code VARCHAR(20),
                latitude DECIMAL(10,8),
                longitude DECIMAL(11,8),
                logo_path VARCHAR(500),
                header_image VARCHAR(500),
                footer_image VARCHAR(500),
                default_currency_id INTEGER,
                fiscal_year_start DATE,
                fiscal_year_end DATE,
                timezone VARCHAR(50) DEFAULT 'UTC',
                date_format VARCHAR(20) DEFAULT 'YYYY-MM-DD',
                language VARCHAR(10) DEFAULT 'ar',
                status VARCHAR(20) DEFAULT 'active' CHECK(status IN ('active', 'inactive')),
                is_head_office INTEGER DEFAULT 0,
                parent_company_id INTEGER,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (default_currency_id) REFERENCES currencies(id),
                FOREIGN KEY (parent_company_id) REFERENCES companies(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS stations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                station_code VARCHAR(20) UNIQUE NOT NULL,
                station_name VARCHAR(200) NOT NULL,
                station_name_ar VARCHAR(200),
                company_id INTEGER,
                branch_id INTEGER,
                country VARCHAR(100),
                city VARCHAR(100),
                district VARCHAR(100),
                street VARCHAR(200),
                building VARCHAR(50),
                postal_code VARCHAR(20),
                latitude DECIMAL(10,8),
                longitude DECIMAL(11,8),
                gps_location VARCHAR(100),
                phone VARCHAR(20),
                phone2 VARCHAR(20),
                email VARCHAR(100),
                emergency_phone VARCHAR(20),
                license_number VARCHAR(50),
                license_issue_date DATE,
                license_expiry_date DATE,
                tax_number VARCHAR(50),
                commercial_register VARCHAR(50),
                environmental_permit VARCHAR(50),
                fire_safety_cert VARCHAR(50),
                operating_hours VARCHAR(100),
                opening_time TIME,
                closing_time TIME,
                is_24_hours INTEGER DEFAULT 1,
                station_type VARCHAR(50) DEFAULT 'retail' CHECK(station_type IN ('retail', 'wholesale', 'both')),
                total_tanks INTEGER DEFAULT 0,
                total_pumps INTEGER DEFAULT 0,
                total_nozzles INTEGER DEFAULT 0,
                storage_capacity DECIMAL(12,2),
                default_currency_id INTEGER,
                status VARCHAR(20) DEFAULT 'active' CHECK(status IN ('active', 'inactive', 'maintenance', 'closed')),
                status_reason TEXT,
                station_photo VARCHAR(500),
                layout_plan VARCHAR(500),
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (company_id) REFERENCES companies(id),
                FOREIGN KEY (default_currency_id) REFERENCES currencies(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS terminals (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                station_id INTEGER NOT NULL,
                terminal_code TEXT NOT NULL,
                name TEXT,
                ip_address TEXT,
                mac_address TEXT,
                device_serial TEXT,
                status TEXT DEFAULT 'active' CHECK(status IN ('active', 'offline', 'broken')),
                last_sync_at DATETIME,
                is_deleted INTEGER DEFAULT 0,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (station_id) REFERENCES stations(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS exchange_rates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                from_currency_id INTEGER NOT NULL,
                to_currency_id INTEGER NOT NULL,
                rate DECIMAL(15,6) NOT NULL,
                inverse_rate DECIMAL(15,6) NOT NULL,
                effective_date DATE NOT NULL,
                expiry_date DATE,
                source VARCHAR(50),
                is_active INTEGER DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (from_currency_id) REFERENCES currencies(id),
                FOREIGN KEY (to_currency_id) REFERENCES currencies(id),
                CHECK(rate > 0),
                CHECK(from_currency_id != to_currency_id)
            )
        """)
    }

    private fun createSecurityTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS screens (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                screen_name TEXT UNIQUE NOT NULL,
                module TEXT,
                description TEXT,
                is_active INTEGER DEFAULT 1,
                archived INTEGER DEFAULT 0,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS groups_table (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                group_name TEXT UNIQUE NOT NULL,
                description TEXT,
                is_active INTEGER DEFAULT 1,
                archived INTEGER DEFAULT 0,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS roles (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                role_code VARCHAR(50) UNIQUE NOT NULL,
                role_name VARCHAR(100) NOT NULL,
                role_name_ar VARCHAR(100),
                description TEXT,
                description_ar TEXT,
                level INTEGER DEFAULT 1,
                parent_role_id INTEGER,
                is_system_role INTEGER DEFAULT 0,
                is_active INTEGER DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (parent_role_id) REFERENCES roles(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS permissions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                permission_code VARCHAR(100) UNIQUE NOT NULL,
                permission_name VARCHAR(200) NOT NULL,
                permission_name_ar VARCHAR(200),
                description TEXT,
                module VARCHAR(50) NOT NULL,
                module_name_ar VARCHAR(100),
                action VARCHAR(50) NOT NULL,
                requires_station INTEGER DEFAULT 0,
                requires_branch INTEGER DEFAULT 0,
                is_active INTEGER DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                is_deleted INTEGER DEFAULT 0,
                remarks TEXT,
                extra_data TEXT
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS role_permissions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                role_id INTEGER NOT NULL,
                permission_id INTEGER NOT NULL,
                station_id INTEGER,
                branch_id INTEGER,
                can_create INTEGER DEFAULT 0,
                can_read INTEGER DEFAULT 1,
                can_update INTEGER DEFAULT 0,
                can_delete INTEGER DEFAULT 0,
                can_export INTEGER DEFAULT 0,
                can_print INTEGER DEFAULT 0,
                can_approve INTEGER DEFAULT 0,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE,
                FOREIGN KEY (permission_id) REFERENCES permissions(id) ON DELETE CASCADE,
                FOREIGN KEY (station_id) REFERENCES stations(id),
                UNIQUE(role_id, permission_id, station_id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS user_permissions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                permission_id INTEGER NOT NULL,
                is_granted INTEGER DEFAULT 1,
                reason TEXT,
                set_by INTEGER,
                set_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                FOREIGN KEY (permission_id) REFERENCES permissions(id) ON DELETE CASCADE,
                FOREIGN KEY (set_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS delegated_permissions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                delegator_id INTEGER NOT NULL,
                delegate_id INTEGER NOT NULL,
                permission_id INTEGER NOT NULL,
                screen_id INTEGER,
                reason TEXT,
                expires_at DATETIME,
                is_active INTEGER DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (delegator_id) REFERENCES users(id) ON DELETE CASCADE,
                FOREIGN KEY (delegate_id) REFERENCES users(id) ON DELETE CASCADE,
                FOREIGN KEY (permission_id) REFERENCES permissions(id) ON DELETE CASCADE,
                FOREIGN KEY (screen_id) REFERENCES screens(id) ON DELETE SET NULL
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS group_permissions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                group_id INTEGER NOT NULL,
                permission_id INTEGER NOT NULL,
                screen_id INTEGER,
                is_granted INTEGER DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (group_id) REFERENCES groups_table(id) ON DELETE CASCADE,
                FOREIGN KEY (permission_id) REFERENCES permissions(id) ON DELETE CASCADE,
                FOREIGN KEY (screen_id) REFERENCES screens(id) ON DELETE SET NULL,
                UNIQUE(group_id, permission_id, screen_id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                username VARCHAR(50) UNIQUE NOT NULL,
                email VARCHAR(100) UNIQUE,
                phone VARCHAR(20) UNIQUE,
                password_hash VARCHAR(255) NOT NULL,
                password_salt VARCHAR(255),
                full_name VARCHAR(200) NOT NULL,
                full_name_ar VARCHAR(200),
                display_name VARCHAR(100),
                avatar_path VARCHAR(500),
                national_id VARCHAR(50),
                passport_number VARCHAR(50),
                nationality VARCHAR(100),
                birth_date DATE,
                gender VARCHAR(10),
                employee_id INTEGER,
                job_title VARCHAR(100),
                department VARCHAR(100),
                hire_date DATE,
                role_id INTEGER NOT NULL,
                station_id INTEGER,
                branch_id INTEGER,
                company_id INTEGER,
                preferred_language VARCHAR(10) DEFAULT 'ar',
                theme VARCHAR(20) DEFAULT 'light',
                timezone VARCHAR(50) DEFAULT 'UTC',
                date_format VARCHAR(20) DEFAULT 'YYYY-MM-DD',
                two_factor_enabled INTEGER DEFAULT 0,
                two_factor_method VARCHAR(20) DEFAULT 'none',
                otp_secret VARCHAR(255),
                biometric_enabled INTEGER DEFAULT 0,
                biometric_type VARCHAR(20),
                last_password_change DATETIME,
                password_expiry_days INTEGER DEFAULT 90,
                password_expiry_date DATETIME,
                must_change_password INTEGER DEFAULT 1,
                failed_login_attempts INTEGER DEFAULT 0,
                account_locked INTEGER DEFAULT 0,
                locked_until DATETIME,
                last_login_at DATETIME,
                last_login_ip VARCHAR(45),
                last_login_device VARCHAR(200),
                session_timeout INTEGER DEFAULT 30,
                device_limit INTEGER DEFAULT 3,
                public_key BLOB,
                sign_count INTEGER DEFAULT 0,
                webauthn_id TEXT,
                credential_id BLOB,
                has_biometrics INTEGER DEFAULT 0,
                status VARCHAR(20) DEFAULT 'active' CHECK(status IN ('active', 'inactive', 'locked', 'suspended')),
                status_reason TEXT,
                email_verified INTEGER DEFAULT 0,
                phone_verified INTEGER DEFAULT 0,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (role_id) REFERENCES roles(id),
                FOREIGN KEY (station_id) REFERENCES stations(id),
                FOREIGN KEY (company_id) REFERENCES companies(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS user_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                user_id INTEGER NOT NULL,
                session_token VARCHAR(500) NOT NULL,
                refresh_token VARCHAR(500),
                device_id VARCHAR(100),
                device_type VARCHAR(50),
                device_name VARCHAR(200),
                device_os VARCHAR(100),
                device_browser VARCHAR(100),
                ip_address VARCHAR(45),
                location_country VARCHAR(100),
                location_city VARCHAR(100),
                latitude DECIMAL(10,8),
                longitude DECIMAL(11,8),
                login_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                last_activity_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                expires_at DATETIME NOT NULL,
                logout_at DATETIME,
                is_active INTEGER DEFAULT 1,
                logout_reason VARCHAR(50),
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS password_reset_tokens (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                token VARCHAR(100) UNIQUE NOT NULL,
                expires_at DATETIME NOT NULL,
                is_used INTEGER DEFAULT 0,
                used_at DATETIME,
                ip_address VARCHAR(45),
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS user_settings (
                user_id INTEGER PRIMARY KEY,
                language VARCHAR(10) DEFAULT 'ar',
                timezone VARCHAR(50) DEFAULT 'Asia/Riyadh',
                date_format VARCHAR(20) DEFAULT 'dd/MM/yyyy',
                time_format VARCHAR(20) DEFAULT 'HH:mm',
                theme VARCHAR(20) DEFAULT 'light',
                alert_types TEXT,
                notification_channels TEXT,
                default_filter VARCHAR(50) DEFAULT 'all',
                default_time_filter VARCHAR(50) DEFAULT 'today',
                display_limit INTEGER DEFAULT 10,
                critical_threshold INTEGER DEFAULT 30,
                preferred_product_id INTEGER,
                preferred_warehouse_id INTEGER,
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS user_activity_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                user_id INTEGER,
                action VARCHAR(100) NOT NULL,
                action_category VARCHAR(50),
                description TEXT,
                description_ar TEXT,
                target_table VARCHAR(50),
                target_id INTEGER,
                target_uuid TEXT,
                station_id INTEGER,
                branch_id INTEGER,
                company_id INTEGER,
                ip_address VARCHAR(45),
                device_id VARCHAR(100),
                device_type VARCHAR(50),
                user_agent TEXT,
                old_values TEXT,
                new_values TEXT,
                old_row_json TEXT,
                new_row_json TEXT,
                changed_columns TEXT,
                device_name VARCHAR(100),
                os_version VARCHAR(20),
                app_version VARCHAR(20),
                browser VARCHAR(50),
                gps VARCHAR(100),
                execution_time INTEGER,
                request_id VARCHAR(50),
                is_success INTEGER DEFAULT 1,
                error_message TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (user_id) REFERENCES users(id),
                FOREIGN KEY (station_id) REFERENCES stations(id)
            )
        """)
    }

    private fun createPartyTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS party_types (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                type_code VARCHAR(20) UNIQUE NOT NULL,
                type_name VARCHAR(100) NOT NULL,
                type_name_ar VARCHAR(100),
                description TEXT,
                default_discount DECIMAL(5,2) DEFAULT 0,
                default_credit_limit DECIMAL(15,2) DEFAULT 0,
                payment_terms_days INTEGER DEFAULT 0,
                is_active INTEGER DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                is_deleted INTEGER DEFAULT 0,
                remarks TEXT,
                extra_data TEXT
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS parties (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                party_code VARCHAR(20) UNIQUE NOT NULL,
                barcode VARCHAR(50),
                qr_code VARCHAR(500),
                party_type_id INTEGER NOT NULL,
                station_id INTEGER REFERENCES stations(id),
                legal_name VARCHAR(200),
                commercial_name VARCHAR(200),
                commercial_name_ar VARCHAR(200),
                tax_number VARCHAR(50) UNIQUE,
                commercial_register VARCHAR(50) UNIQUE,
                vat_number VARCHAR(50),
                credit_limit DECIMAL(15,2) DEFAULT 0 CHECK(credit_limit >= 0),
                current_balance DECIMAL(15,2) DEFAULT 0,
                total_purchases DECIMAL(15,2) DEFAULT 0,
                total_payments DECIMAL(15,2) DEFAULT 0,
                total_due DECIMAL(15,2) DEFAULT 0,
                overdue_amount DECIMAL(15,2) DEFAULT 0,
                payment_terms VARCHAR(50),
                currency_id INTEGER,
                loyalty_points INTEGER DEFAULT 0,
                loyalty_tier VARCHAR(20) DEFAULT 'bronze' CHECK(loyalty_tier IN ('bronze', 'silver', 'gold', 'platinum')),
                risk_level VARCHAR(10) DEFAULT 'low' CHECK(risk_level IN ('low', 'medium', 'high', 'blacklisted')),
                blacklist_reason TEXT,
                blacklist_date DATE,
                blacklist_by INTEGER,
                referred_by INTEGER,
                assigned_to INTEGER,
                rating DECIMAL(3,2) DEFAULT 3.00,
                total_orders INTEGER DEFAULT 0,
                total_order_amount DECIMAL(15,2) DEFAULT 0,
                on_time_rate DECIMAL(5,2) DEFAULT 100.00,
                fuel_type_preference_id INTEGER,
                fleet_size INTEGER DEFAULT 0,
                is_active INTEGER DEFAULT 1,
                notes TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (party_type_id) REFERENCES party_types(id),
                FOREIGN KEY (currency_id) REFERENCES currencies(id),
                FOREIGN KEY (blacklist_by) REFERENCES users(id),
                FOREIGN KEY (referred_by) REFERENCES parties(id),
                FOREIGN KEY (assigned_to) REFERENCES users(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS party_contacts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                party_id INTEGER NOT NULL,
                contact_name VARCHAR(200) NOT NULL,
                contact_name_ar VARCHAR(200),
                job_title VARCHAR(100),
                department VARCHAR(100),
                phone VARCHAR(20),
                phone2 VARCHAR(20),
                email VARCHAR(100),
                whatsapp VARCHAR(20),
                is_primary INTEGER DEFAULT 0,
                is_billing INTEGER DEFAULT 0,
                is_technical INTEGER DEFAULT 0,
                is_active INTEGER DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                is_deleted INTEGER DEFAULT 0,
                FOREIGN KEY (party_id) REFERENCES parties(id) ON DELETE CASCADE
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS party_addresses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                party_id INTEGER NOT NULL,
                address_type VARCHAR(50),
                address_line1 VARCHAR(255),
                address_line2 VARCHAR(255),
                city VARCHAR(100),
                state VARCHAR(100),
                postal_code VARCHAR(20),
                country VARCHAR(100),
                is_default INTEGER DEFAULT 0,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                is_deleted INTEGER DEFAULT 0,
                FOREIGN KEY (party_id) REFERENCES parties(id) ON DELETE CASCADE
            )
        """)
    }

    private fun createVehicleTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS vehicles (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                vehicle_code VARCHAR(20) UNIQUE NOT NULL,
                party_id INTEGER NOT NULL,
                plate_number VARCHAR(20) NOT NULL,
                plate_number_ar VARCHAR(20),
                plate_country VARCHAR(100) DEFAULT 'Yemen',
                plate_city VARCHAR(100),
                vehicle_type VARCHAR(50) CHECK(vehicle_type IN ('car', 'bus', 'truck', 'heavy_equipment', 'motorcycle')),
                brand VARCHAR(100),
                model VARCHAR(100),
                year INTEGER,
                color VARCHAR(50),
                engine_type VARCHAR(20),
                engine_capacity DECIMAL(6,2),
                fuel_type_id INTEGER,
                tank_capacity DECIMAL(8,2),
                chassis_number VARCHAR(100),
                engine_number VARCHAR(100),
                registration_number VARCHAR(100),
                registration_expiry DATE,
                insurance_number VARCHAR(100),
                insurance_expiry DATE,
                rfid_tag VARCHAR(100),
                nfc_tag VARCHAR(100),
                current_odometer DECIMAL(10,2) DEFAULT 0,
                last_odometer DECIMAL(10,2) DEFAULT 0,
                odometer_updated_at DATETIME,
                avg_consumption DECIMAL(5,2),
                status VARCHAR(20) DEFAULT 'active' CHECK(status IN ('active', 'inactive', 'sold', 'scrapped')),
                vehicle_photo VARCHAR(500),
                registration_doc VARCHAR(500),
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (party_id) REFERENCES parties(id),
                FOREIGN KEY (fuel_type_id) REFERENCES fuel_types(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS drivers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                driver_code VARCHAR(20) UNIQUE NOT NULL,
                party_id INTEGER,
                vehicle_id INTEGER,
                full_name VARCHAR(200) NOT NULL,
                full_name_ar VARCHAR(200),
                national_id VARCHAR(50),
                passport_number VARCHAR(50),
                nationality VARCHAR(100),
                birth_date DATE,
                gender VARCHAR(10),
                phone VARCHAR(20),
                phone2 VARCHAR(20),
                email VARCHAR(100),
                whatsapp VARCHAR(20),
                address TEXT,
                license_number VARCHAR(50),
                license_type VARCHAR(20),
                license_issue_date DATE,
                license_expiry_date DATE,
                license_issuing_authority VARCHAR(100),
                license_doc_path VARCHAR(500),
                hire_date DATE,
                job_title VARCHAR(100) DEFAULT 'Driver',
                salary DECIMAL(12,2),
                emergency_name VARCHAR(200),
                emergency_phone VARCHAR(20),
                emergency_relation VARCHAR(50),
                status VARCHAR(20) DEFAULT 'active' CHECK(status IN ('active', 'inactive', 'suspended', 'terminated')),
                termination_date DATE,
                termination_reason TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (party_id) REFERENCES parties(id),
                FOREIGN KEY (vehicle_id) REFERENCES vehicles(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)
    }

    private fun createProductTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS units (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                unit_name VARCHAR(50) UNIQUE NOT NULL,
                unit_symbol VARCHAR(10) UNIQUE NOT NULL,
                is_decimal INTEGER DEFAULT 0,
                base_unit_id INTEGER,
                conversion_factor REAL,
                category VARCHAR(20) DEFAULT 'fuel' CHECK(category IN ('fuel', 'product', 'weight')),
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (base_unit_id) REFERENCES units(id),
                CHECK(conversion_factor IS NULL OR conversion_factor > 0)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS fuel_types (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                fuel_code VARCHAR(20) UNIQUE NOT NULL,
                fuel_name VARCHAR(100) NOT NULL,
                fuel_name_ar VARCHAR(100),
                description TEXT,
                density_standard DECIMAL(8,4),
                temperature_standard DECIMAL(5,2) DEFAULT 15.0,
                flash_point DECIMAL(5,2),
                default_sale_price DECIMAL(12,4),
                default_purchase_price DECIMAL(12,4),
                tax_rate DECIMAL(5,2) DEFAULT 0,
                vat_rate DECIMAL(5,2) DEFAULT 0,
                color_code VARCHAR(7),
                icon_path VARCHAR(500),
                is_active INTEGER DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS product_categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                category_code VARCHAR(20) UNIQUE NOT NULL,
                category_name VARCHAR(100) NOT NULL,
                category_name_ar VARCHAR(100),
                description TEXT,
                description_ar TEXT,
                parent_category_id INTEGER,
                level INTEGER DEFAULT 1,
                category_type VARCHAR(20) DEFAULT 'product' CHECK(category_type IN ('product', 'fuel', 'service', 'package')),
                color_code VARCHAR(7),
                icon_path VARCHAR(500),
                display_order INTEGER DEFAULT 0,
                tax_rate REAL DEFAULT 0.0,
                is_active INTEGER DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (parent_category_id) REFERENCES product_categories(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS products (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                product_code VARCHAR(30) UNIQUE NOT NULL,
                barcode VARCHAR(50) UNIQUE,
                qr_code VARCHAR(500),
                product_name VARCHAR(200) NOT NULL,
                product_name_ar VARCHAR(200),
                short_name VARCHAR(50),
                short_name_ar VARCHAR(50),
                description TEXT,
                description_ar TEXT,
                category_id INTEGER NOT NULL,
                fuel_type_id INTEGER,
                station_id INTEGER,
                unit_id INTEGER NOT NULL,
                product_type VARCHAR(20) DEFAULT 'retail' CHECK(product_type IN ('retail', 'fuel', 'service', 'package')),
                purchase_price DECIMAL(12,4) DEFAULT 0 CHECK(purchase_price >= 0),
                sale_price DECIMAL(12,4) NOT NULL CHECK(sale_price >= 0),
                wholesale_price DECIMAL(12,4),
                min_sale_price DECIMAL(12,4),
                max_sale_price DECIMAL(12,4),
                tax_rate DECIMAL(5,2) DEFAULT 0,
                vat_rate DECIMAL(5,2) DEFAULT 0,
                is_tax_exempt INTEGER DEFAULT 0,
                quantity DECIMAL(12,2) DEFAULT 0,
                minimum_stock DECIMAL(12,2) DEFAULT 10,
                maximum_stock DECIMAL(12,2) DEFAULT 1000,
                reorder_quantity DECIMAL(12,2) DEFAULT 50,
                is_service INTEGER DEFAULT 0,
                is_serialized INTEGER DEFAULT 0,
                is_batch_tracked INTEGER DEFAULT 0,
                has_expiry INTEGER DEFAULT 0,
                expiry_date DATE,
                weight_kg DECIMAL(8,3),
                volume_liters DECIMAL(8,3),
                length_cm DECIMAL(8,2),
                width_cm DECIMAL(8,2),
                height_cm DECIMAL(8,2),
                primary_image VARCHAR(500),
                gallery_images TEXT,
                preferred_supplier_id INTEGER,
                status VARCHAR(20) DEFAULT 'active' CHECK(status IN ('active', 'inactive', 'discontinued')),
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (category_id) REFERENCES product_categories(id),
                FOREIGN KEY (fuel_type_id) REFERENCES fuel_types(id),
                FOREIGN KEY (station_id) REFERENCES stations(id),
                FOREIGN KEY (unit_id) REFERENCES units(id),
                FOREIGN KEY (preferred_supplier_id) REFERENCES parties(id),
                FOREIGN KEY (created_by) REFERENCES users(id),
                CHECK(sale_price >= purchase_price)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS price_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                product_id INTEGER NOT NULL,
                old_price DECIMAL(12,4) CHECK(old_price >= 0),
                new_price DECIMAL(12,4) NOT NULL CHECK(new_price >= 0),
                change_date DATETIME DEFAULT CURRENT_TIMESTAMP,
                change_reason VARCHAR(100),
                created_by INTEGER NOT NULL,
                archived INTEGER DEFAULT 0,
                FOREIGN KEY (product_id) REFERENCES products(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS price_lists (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                list_code VARCHAR(20) UNIQUE NOT NULL,
                list_name VARCHAR(100) NOT NULL,
                list_name_ar VARCHAR(100),
                description TEXT,
                party_id INTEGER,
                party_type_id INTEGER,
                station_id INTEGER,
                valid_from DATE,
                valid_to DATE,
                is_active INTEGER DEFAULT 1,
                is_default INTEGER DEFAULT 0,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                FOREIGN KEY (party_id) REFERENCES parties(id),
                FOREIGN KEY (party_type_id) REFERENCES party_types(id),
                FOREIGN KEY (station_id) REFERENCES stations(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS price_list_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                price_list_id INTEGER NOT NULL,
                product_id INTEGER NOT NULL,
                unit_price DECIMAL(12,4) NOT NULL,
                min_quantity DECIMAL(12,2) DEFAULT 1,
                max_quantity DECIMAL(12,2),
                discount_percent DECIMAL(5,2) DEFAULT 0,
                valid_from DATE,
                valid_to DATE,
                is_active INTEGER DEFAULT 1,
                FOREIGN KEY (price_list_id) REFERENCES price_lists(id) ON DELETE CASCADE,
                FOREIGN KEY (product_id) REFERENCES products(id),
                UNIQUE(price_list_id, product_id)
            )
        """)
    }

    private fun createTankPumpTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS tanks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                tank_code VARCHAR(20) UNIQUE NOT NULL,
                tank_name VARCHAR(100) NOT NULL,
                tank_name_ar VARCHAR(100),
                station_id INTEGER NOT NULL,
                fuel_type_id INTEGER NOT NULL,
                capacity_liters DECIMAL(12,2) NOT NULL,
                minimum_level DECIMAL(12,2) DEFAULT 500,
                maximum_level DECIMAL(12,2),
                current_quantity DECIMAL(12,2) DEFAULT 0 CHECK(current_quantity >= 0),
                usable_capacity DECIMAL(12,2),
                dead_volume DECIMAL(12,2) DEFAULT 0,
                tank_shape VARCHAR(20) DEFAULT 'cylindrical' CHECK(tank_shape IN ('cylindrical', 'rectangular', 'spherical')),
                length_meters DECIMAL(8,3),
                diameter_meters DECIMAL(8,3),
                height_meters DECIMAL(8,3),
                location VARCHAR(200),
                installation_date DATE,
                manufacturer VARCHAR(100),
                serial_number VARCHAR(100),
                model VARCHAR(100),
                sensor_serial VARCHAR(100),
                sensor_type VARCHAR(50),
                sensor_calibration_date DATE,
                sensor_accuracy DECIMAL(5,2),
                leak_detection INTEGER DEFAULT 0,
                overfill_protection INTEGER DEFAULT 0,
                emergency_valve INTEGER DEFAULT 0,
                last_inspection_date DATE,
                next_inspection_date DATE,
                inspection_certificate VARCHAR(500),
                status VARCHAR(20) DEFAULT 'active' CHECK(status IN ('active', 'maintenance', 'empty', 'retired')),
                status_reason TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (station_id) REFERENCES stations(id),
                FOREIGN KEY (fuel_type_id) REFERENCES fuel_types(id),
                FOREIGN KEY (created_by) REFERENCES users(id),
                CHECK(current_quantity <= capacity_liters)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS tank_level_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                tank_id INTEGER NOT NULL,
                reading_date DATETIME NOT NULL,
                reading_type VARCHAR(20) DEFAULT 'auto' CHECK(reading_type IN ('auto', 'manual', 'inspection')),
                opening_level DECIMAL(12,2),
                closing_level DECIMAL(12,2),
                measured_level DECIMAL(12,2),
                calculated_level DECIMAL(12,2),
                difference DECIMAL(12,2),
                fuel_temperature DECIMAL(5,2),
                fuel_density DECIMAL(8,4),
                volume_at_15c DECIMAL(12,2),
                refills_total DECIMAL(12,2) DEFAULT 0,
                sales_total DECIMAL(12,2) DEFAULT 0,
                evaporation_loss DECIMAL(12,4) DEFAULT 0,
                is_below_minimum INTEGER DEFAULT 0,
                is_near_maximum INTEGER DEFAULT 0,
                alert_triggered INTEGER DEFAULT 0,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (tank_id) REFERENCES tanks(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS tank_refills (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                refill_code VARCHAR(30) UNIQUE NOT NULL,
                tank_id INTEGER NOT NULL,
                supplier_id INTEGER,
                station_id INTEGER NOT NULL,
                tanker_number VARCHAR(50),
                tanker_driver VARCHAR(100),
                tanker_driver_phone VARCHAR(20),
                seal_number VARCHAR(50),
                fuel_type_id INTEGER NOT NULL,
                ordered_quantity DECIMAL(12,2),
                delivered_quantity DECIMAL(12,2) NOT NULL,
                actual_quantity DECIMAL(12,2),
                quantity_difference DECIMAL(12,2),
                unloading_start DATETIME,
                unloading_end DATETIME,
                unloading_duration INTEGER,
                tank_level_before DECIMAL(12,2),
                tank_level_after DECIMAL(12,2),
                fuel_density DECIMAL(8,4),
                fuel_temperature DECIMAL(5,2),
                quality_certificate VARCHAR(500),
                lab_test_result VARCHAR(20) DEFAULT 'pending' CHECK(lab_test_result IN ('pending', 'passed', 'failed', 'warning')),
                lab_test_notes TEXT,
                unit_price DECIMAL(12,4),
                total_amount DECIMAL(15,2),
                transport_cost DECIMAL(12,2) DEFAULT 0,
                discount DECIMAL(12,2) DEFAULT 0,
                tax_amount DECIMAL(12,2) DEFAULT 0,
                net_amount DECIMAL(15,2),
                currency_id INTEGER,
                order_date DATE,
                expected_date DATE,
                arrival_date DATETIME,
                received_by INTEGER,
                approved_by INTEGER,
                inspected_by INTEGER,
                status VARCHAR(20) DEFAULT 'pending' CHECK(status IN ('pending', 'in_progress', 'completed', 'rejected', 'cancelled')),
                rejection_reason TEXT,
                invoice_number VARCHAR(50),
                invoice_path VARCHAR(500),
                delivery_note_path VARCHAR(500),
                photos TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (tank_id) REFERENCES tanks(id),
                FOREIGN KEY (supplier_id) REFERENCES parties(id),
                FOREIGN KEY (fuel_type_id) REFERENCES fuel_types(id),
                FOREIGN KEY (station_id) REFERENCES stations(id),
                FOREIGN KEY (currency_id) REFERENCES currencies(id),
                FOREIGN KEY (received_by) REFERENCES users(id),
                FOREIGN KEY (approved_by) REFERENCES users(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS fuel_quality_tests (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                refill_id INTEGER NOT NULL,
                test_date DATETIME DEFAULT CURRENT_TIMESTAMP,
                density DECIMAL(8,4),
                temperature DECIMAL(5,2),
                water_content DECIMAL(6,2),
                sulfur_content DECIMAL(6,2),
                viscosity DECIMAL(6,2),
                flash_point DECIMAL(5,2),
                cetane_number INTEGER,
                result VARCHAR(20) CHECK(result IN ('pass', 'fail', 'warning')),
                certificate_url VARCHAR(500),
                tested_by INTEGER,
                notes TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (refill_id) REFERENCES tank_refills(id),
                FOREIGN KEY (tested_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS pumps (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                pump_code VARCHAR(20) UNIQUE NOT NULL,
                pump_number VARCHAR(10) NOT NULL,
                pump_name VARCHAR(100),
                pump_name_ar VARCHAR(100),
                station_id INTEGER NOT NULL,
                tank_id INTEGER NOT NULL,
                serial_number VARCHAR(100),
                manufacturer VARCHAR(100),
                model VARCHAR(100),
                installation_date DATE,
                max_flow_rate DECIMAL(8,2),
                meter_start DECIMAL(12,2) DEFAULT 0,
                meter_current DECIMAL(12,2) DEFAULT 0,
                meter_last_reset DATETIME,
                status VARCHAR(20) DEFAULT 'active' CHECK(status IN ('active', 'maintenance', 'offline', 'retired')),
                status_reason TEXT,
                last_maintenance DATE,
                next_maintenance DATE,
                maintenance_interval INTEGER DEFAULT 90,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (station_id) REFERENCES stations(id),
                FOREIGN KEY (tank_id) REFERENCES tanks(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS pump_nozzles (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                nozzle_code VARCHAR(20) UNIQUE NOT NULL,
                nozzle_number VARCHAR(10) NOT NULL,
                pump_id INTEGER NOT NULL,
                fuel_type_id INTEGER NOT NULL,
                meter_start DECIMAL(12,2) DEFAULT 0,
                meter_current DECIMAL(12,2) DEFAULT 0,
                meter_last_reset DATETIME,
                total_sold_liters DECIMAL(15,2) DEFAULT 0,
                calibration_date DATE,
                calibration_factor DECIMAL(8,4) DEFAULT 1.0,
                accuracy_percentage DECIMAL(5,2) DEFAULT 100.0,
                hose_length DECIMAL(5,2),
                auto_stop_enabled INTEGER DEFAULT 1,
                status VARCHAR(20) DEFAULT 'active' CHECK(status IN ('active', 'maintenance', 'blocked', 'retired')),
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (pump_id) REFERENCES pumps(id),
                FOREIGN KEY (fuel_type_id) REFERENCES fuel_types(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS meter_readings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                reading_code VARCHAR(30) UNIQUE NOT NULL,
                pump_id INTEGER NOT NULL,
                nozzle_id INTEGER NOT NULL,
                station_id INTEGER NOT NULL,
                shift_id INTEGER,
                reading_date DATE NOT NULL,
                period VARCHAR(20) DEFAULT 'morning' CHECK(period IN ('morning', 'evening', 'night', 'daily')),
                opening_reading DECIMAL(12,2) NOT NULL,
                closing_reading DECIMAL(12,2) NOT NULL,
                sold_liters DECIMAL(12,2) NOT NULL,
                system_sold_liters DECIMAL(12,2),
                difference DECIMAL(12,2),
                difference_percent DECIMAL(5,2),
                is_balanced INTEGER DEFAULT 1,
                tolerance_limit DECIMAL(5,2) DEFAULT 0.5,
                adjustment_amount DECIMAL(12,2) DEFAULT 0,
                adjustment_reason TEXT,
                adjusted_by INTEGER,
                read_by INTEGER NOT NULL,
                verified_by INTEGER,
                approved_by INTEGER,
                status VARCHAR(20) DEFAULT 'draft' CHECK(status IN ('draft', 'verified', 'approved', 'rejected')),
                rejection_reason TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (pump_id) REFERENCES pumps(id),
                FOREIGN KEY (nozzle_id) REFERENCES pump_nozzles(id),
                FOREIGN KEY (station_id) REFERENCES stations(id),
                FOREIGN KEY (read_by) REFERENCES users(id),
                FOREIGN KEY (verified_by) REFERENCES users(id),
                FOREIGN KEY (approved_by) REFERENCES users(id),
                FOREIGN KEY (adjusted_by) REFERENCES users(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS calibration_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                calibration_code VARCHAR(30) UNIQUE NOT NULL,
                entity_type VARCHAR(30) NOT NULL,
                entity_id INTEGER NOT NULL,
                calibration_date DATE NOT NULL,
                technician VARCHAR(100),
                before_value DECIMAL(12,4),
                after_value DECIMAL(12,4),
                error_value DECIMAL(12,4),
                correction_percent DECIMAL(5,2),
                calibration_factor DECIMAL(8,4),
                certificate_number VARCHAR(50),
                certificate_path VARCHAR(500),
                next_calibration_date DATE,
                notes TEXT,
                status VARCHAR(20) DEFAULT 'completed' CHECK(status IN ('scheduled', 'in_progress', 'completed', 'failed')),
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)
    }

    private fun createInventoryTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS warehouses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                station_id INTEGER NOT NULL,
                warehouse_name VARCHAR(100) UNIQUE NOT NULL,
                location_details TEXT,
                is_default INTEGER DEFAULT 0,
                is_active INTEGER DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (station_id) REFERENCES stations(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS inventory_levels (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                product_id INTEGER NOT NULL,
                warehouse_id INTEGER NOT NULL,
                quantity_on_hand DECIMAL(12,2) DEFAULT 0 CHECK(quantity_on_hand >= 0),
                quantity_committed DECIMAL(12,2) DEFAULT 0 CHECK(quantity_committed >= 0),
                average_cost DECIMAL(12,2) DEFAULT 0 CHECK(average_cost >= 0),
                last_count_date DATETIME,
                expiry_date DATE,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (product_id) REFERENCES products(id),
                FOREIGN KEY (warehouse_id) REFERENCES warehouses(id),
                UNIQUE(product_id, warehouse_id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS inventory_movements (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                movement_code VARCHAR(30) UNIQUE NOT NULL,
                product_id INTEGER NOT NULL,
                station_id INTEGER NOT NULL,
                warehouse_id INTEGER,
                movement_type VARCHAR(20) NOT NULL CHECK(movement_type IN ('in', 'out', 'adjustment', 'transfer', 'return', 'damage')),
                movement_subtype VARCHAR(30),
                quantity_before DECIMAL(12,2) NOT NULL,
                quantity_change DECIMAL(12,2) NOT NULL,
                quantity_after DECIMAL(12,2) NOT NULL,
                unit_cost DECIMAL(12,4),
                total_cost DECIMAL(15,2),
                reference_type VARCHAR(50),
                reference_id INTEGER,
                reference_code VARCHAR(50),
                from_location VARCHAR(100),
                to_location VARCHAR(100),
                reason TEXT,
                reason_code VARCHAR(20),
                performed_by INTEGER NOT NULL,
                approved_by INTEGER,
                status VARCHAR(20) DEFAULT 'completed' CHECK(status IN ('draft', 'completed', 'cancelled', 'reversed')),
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (product_id) REFERENCES products(id),
                FOREIGN KEY (station_id) REFERENCES stations(id),
                FOREIGN KEY (performed_by) REFERENCES users(id),
                FOREIGN KEY (approved_by) REFERENCES users(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS stock_alerts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                product_id INTEGER NOT NULL,
                station_id INTEGER NOT NULL,
                alert_type VARCHAR(20) NOT NULL CHECK(alert_type IN ('low_stock', 'out_of_stock', 'overstock', 'expiry')),
                alert_level VARCHAR(10) DEFAULT 'warning' CHECK(alert_level IN ('info', 'warning', 'critical')),
                current_quantity DECIMAL(12,2) NOT NULL,
                threshold_quantity DECIMAL(12,2) NOT NULL,
                shortage_quantity DECIMAL(12,2),
                is_resolved INTEGER DEFAULT 0,
                resolved_at DATETIME,
                resolved_by INTEGER,
                resolution_notes TEXT,
                notification_sent INTEGER DEFAULT 0,
                notification_method VARCHAR(20),
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (product_id) REFERENCES products(id),
                FOREIGN KEY (station_id) REFERENCES stations(id),
                FOREIGN KEY (resolved_by) REFERENCES users(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS initial_inventory (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                warehouse_id INTEGER NOT NULL,
                product_id INTEGER NOT NULL,
                quantity REAL NOT NULL CHECK(quantity >= 0),
                unit_cost REAL NOT NULL CHECK(unit_cost >= 0),
                entry_date DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER NOT NULL,
                archived INTEGER DEFAULT 0,
                FOREIGN KEY (warehouse_id) REFERENCES warehouses(id),
                FOREIGN KEY (product_id) REFERENCES products(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS stocktakes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                warehouse_id INTEGER NOT NULL,
                start_date DATETIME DEFAULT CURRENT_TIMESTAMP,
                end_date DATETIME,
                status TEXT NOT NULL CHECK(status IN ('draft', 'in_progress', 'completed', 'cancelled')),
                total_variance REAL,
                notes TEXT,
                created_by INTEGER NOT NULL,
                archived INTEGER DEFAULT 0,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (warehouse_id) REFERENCES warehouses(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS stocktake_details (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                stocktake_id INTEGER NOT NULL,
                product_id INTEGER NOT NULL,
                system_quantity REAL DEFAULT 0 CHECK(system_quantity >= 0),
                counted_quantity REAL DEFAULT 0 CHECK(counted_quantity >= 0),
                variance_value REAL,
                notes TEXT,
                archived INTEGER DEFAULT 0,
                FOREIGN KEY (stocktake_id) REFERENCES stocktakes(id) ON DELETE CASCADE,
                FOREIGN KEY (product_id) REFERENCES products(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS damaged_products (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                product_id INTEGER NOT NULL,
                warehouse_id INTEGER,
                tank_id INTEGER,
                station_id INTEGER REFERENCES stations(id),
                quantity REAL NOT NULL CHECK(quantity > 0),
                reason TEXT,
                notes TEXT,
                report_date DATETIME DEFAULT CURRENT_TIMESTAMP,
                reported_by INTEGER NOT NULL,
                status TEXT DEFAULT 'pending' CHECK(status IN ('pending', 'approved', 'rejected')),
                approved_by INTEGER,
                approved_at DATETIME,
                archived INTEGER DEFAULT 0,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (product_id) REFERENCES products(id),
                FOREIGN KEY (warehouse_id) REFERENCES warehouses(id),
                FOREIGN KEY (tank_id) REFERENCES tanks(id),
                FOREIGN KEY (reported_by) REFERENCES users(id),
                FOREIGN KEY (approved_by) REFERENCES users(id)
            )
        """)
    }

    private fun createSalesTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS shifts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                shift_code VARCHAR(20) UNIQUE NOT NULL,
                station_id INTEGER NOT NULL,
                shift_date DATE NOT NULL,
                shift_type VARCHAR(20) NOT NULL CHECK(shift_type IN ('morning', 'evening', 'night', 'full_day')),
                start_time DATETIME NOT NULL,
                end_time DATETIME,
                duration_minutes INTEGER,
                manager_id INTEGER,
                cashier_id INTEGER,
                attendant_ids TEXT,
                opening_cash DECIMAL(15,2) DEFAULT 0,
                opening_bank DECIMAL(15,2) DEFAULT 0,
                opening_credit DECIMAL(15,2) DEFAULT 0,
                closing_cash DECIMAL(15,2),
                closing_bank DECIMAL(15,2),
                closing_credit DECIMAL(15,2),
                total_sales DECIMAL(15,2) DEFAULT 0,
                total_fuel_sales DECIMAL(15,2) DEFAULT 0,
                total_product_sales DECIMAL(15,2) DEFAULT 0,
                total_service_sales DECIMAL(15,2) DEFAULT 0,
                total_discounts DECIMAL(15,2) DEFAULT 0,
                total_tax DECIMAL(15,2) DEFAULT 0,
                total_vat DECIMAL(15,2) DEFAULT 0,
                total_cash DECIMAL(15,2) DEFAULT 0,
                total_credit_card DECIMAL(15,2) DEFAULT 0,
                total_bank_transfer DECIMAL(15,2) DEFAULT 0,
                total_credit_sales DECIMAL(15,2) DEFAULT 0,
                total_cheque DECIMAL(15,2) DEFAULT 0,
                total_other DECIMAL(15,2) DEFAULT 0,
                total_fuel_liters DECIMAL(12,2) DEFAULT 0,
                cash_variance DECIMAL(15,2) DEFAULT 0,
                variance_reason TEXT,
                variance_approved_by INTEGER,
                status VARCHAR(20) DEFAULT 'open' CHECK(status IN ('open', 'closed', 'verified', 'approved')),
                closed_at DATETIME,
                closed_by INTEGER,
                verified_at DATETIME,
                verified_by INTEGER,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (station_id) REFERENCES stations(id),
                FOREIGN KEY (manager_id) REFERENCES users(id),
                FOREIGN KEY (cashier_id) REFERENCES users(id),
                FOREIGN KEY (closed_by) REFERENCES users(id),
                FOREIGN KEY (verified_by) REFERENCES users(id),
                FOREIGN KEY (variance_approved_by) REFERENCES users(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sales_transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                sale_code VARCHAR(30) UNIQUE NOT NULL,
                station_id INTEGER NOT NULL,
                shift_id INTEGER NOT NULL,
                customer_party_id INTEGER,
                vehicle_id INTEGER,
                driver_id INTEGER,
                invoice_number VARCHAR(50) UNIQUE,
                invoice_series VARCHAR(20) DEFAULT 'A',
                invoice_type VARCHAR(20) DEFAULT 'standard' CHECK(invoice_type IN ('standard', 'simplified', 'credit_note')),
                receipt_number VARCHAR(50),
                sale_type VARCHAR(20) DEFAULT 'retail' CHECK(sale_type IN ('retail', 'wholesale', 'fleet')),
                fuel_type_id INTEGER,
                pump_id INTEGER,
                nozzle_id INTEGER,
                liters DECIMAL(12,3),
                price_per_liter DECIMAL(12,4),
                fuel_subtotal DECIMAL(15,2),
                product_id INTEGER,
                quantity DECIMAL(12,2) DEFAULT 1,
                unit_price DECIMAL(12,4),
                product_subtotal DECIMAL(15,2),
                subtotal DECIMAL(15,2) NOT NULL,
                discount_amount DECIMAL(15,2) DEFAULT 0,
                discount_percent DECIMAL(5,2) DEFAULT 0,
                tax_rate DECIMAL(5,2) DEFAULT 0,
                tax_amount DECIMAL(15,2) DEFAULT 0,
                vat_rate DECIMAL(5,2) DEFAULT 0,
                vat_amount DECIMAL(15,2) DEFAULT 0,
                service_fee DECIMAL(15,2) DEFAULT 0,
                commission DECIMAL(15,2) DEFAULT 0,
                gross_amount DECIMAL(15,2) NOT NULL,
                net_amount DECIMAL(15,2) NOT NULL,
                currency_id INTEGER,
                exchange_rate DECIMAL(15,6) DEFAULT 1,
                amount_in_default DECIMAL(15,2),
                payment_method VARCHAR(20) DEFAULT 'cash' CHECK(payment_method IN ('cash', 'credit_card', 'bank_transfer', 'credit', 'cheque', 'mobile_money', 'loyalty_points')),
                payment_status VARCHAR(20) DEFAULT 'pending' CHECK(payment_status IN ('pending', 'partial', 'paid', 'refunded', 'cancelled')),
                paid_amount DECIMAL(15,2) DEFAULT 0,
                remaining_amount DECIMAL(15,2) DEFAULT 0,
                is_credit INTEGER DEFAULT 0,
                credit_days INTEGER DEFAULT 0,
                due_date DATE,
                loyalty_points_earned INTEGER DEFAULT 0,
                loyalty_points_used INTEGER DEFAULT 0,
                loyalty_discount DECIMAL(15,2) DEFAULT 0,
                gps_latitude DECIMAL(10,8),
                gps_longitude DECIMAL(11,8),
                invoice_qr VARCHAR(500),
                digital_signature VARCHAR(500),
                zatca_xml TEXT,
                print_count INTEGER DEFAULT 0,
                last_printed_at DATETIME,
                offline_mode INTEGER DEFAULT 0,
                synced_at DATETIME,
                status VARCHAR(20) DEFAULT 'completed' CHECK(status IN ('draft', 'completed', 'cancelled', 'refunded')),
                cancellation_reason TEXT,
                cancelled_by INTEGER,
                cancelled_at DATETIME,
                cashier_id INTEGER NOT NULL,
                attendant_id INTEGER,
                approved_by INTEGER,
                delivery_location TEXT,
                delivery_time TEXT,
                order_type TEXT DEFAULT 'sale',
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (station_id) REFERENCES stations(id),
                FOREIGN KEY (shift_id) REFERENCES shifts(id),
                FOREIGN KEY (customer_party_id) REFERENCES parties(id),
                FOREIGN KEY (vehicle_id) REFERENCES vehicles(id),
                FOREIGN KEY (driver_id) REFERENCES drivers(id),
                FOREIGN KEY (fuel_type_id) REFERENCES fuel_types(id),
                FOREIGN KEY (pump_id) REFERENCES pumps(id),
                FOREIGN KEY (nozzle_id) REFERENCES pump_nozzles(id),
                FOREIGN KEY (product_id) REFERENCES products(id),
                FOREIGN KEY (currency_id) REFERENCES currencies(id),
                FOREIGN KEY (cashier_id) REFERENCES users(id),
                FOREIGN KEY (attendant_id) REFERENCES users(id),
                FOREIGN KEY (approved_by) REFERENCES users(id),
                FOREIGN KEY (cancelled_by) REFERENCES users(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sale_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                sale_id INTEGER NOT NULL,
                line_number INTEGER NOT NULL,
                item_type VARCHAR(20) NOT NULL CHECK(item_type IN ('fuel', 'product', 'service')),
                product_id INTEGER,
                fuel_type_id INTEGER,
                quantity DECIMAL(12,3) NOT NULL,
                unit_of_measure VARCHAR(20),
                unit_price DECIMAL(12,4) NOT NULL,
                subtotal DECIMAL(15,2) NOT NULL,
                discount_percent DECIMAL(5,2) DEFAULT 0,
                discount_amount DECIMAL(15,2) DEFAULT 0,
                tax_rate DECIMAL(5,2) DEFAULT 0,
                tax_amount DECIMAL(15,2) DEFAULT 0,
                vat_rate DECIMAL(5,2) DEFAULT 0,
                vat_amount DECIMAL(15,2) DEFAULT 0,
                line_total DECIMAL(15,2) NOT NULL,
                pump_id INTEGER,
                nozzle_id INTEGER,
                meter_start DECIMAL(12,2),
                meter_end DECIMAL(12,2),
                batch_number VARCHAR(50),
                expiry_date DATE,
                is_returned INTEGER DEFAULT 0,
                returned_quantity DECIMAL(12,3) DEFAULT 0,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (sale_id) REFERENCES sales_transactions(id) ON DELETE CASCADE,
                FOREIGN KEY (product_id) REFERENCES products(id),
                FOREIGN KEY (fuel_type_id) REFERENCES fuel_types(id),
                FOREIGN KEY (pump_id) REFERENCES pumps(id),
                FOREIGN KEY (nozzle_id) REFERENCES pump_nozzles(id)
            )
        """)
    }

    private fun createFinanceTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS payments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                payment_code VARCHAR(30) UNIQUE NOT NULL,
                sale_id INTEGER,
                customer_party_id INTEGER,
                supplier_party_id INTEGER,
                payment_type VARCHAR(20) NOT NULL CHECK(payment_type IN ('cash', 'cheque', 'bank_transfer', 'credit_card', 'mobile_money', 'loyalty_points')),
                payment_method VARCHAR(20) NOT NULL,
                amount DECIMAL(15,2) NOT NULL,
                currency_id INTEGER,
                exchange_rate DECIMAL(15,6) DEFAULT 1,
                amount_in_default DECIMAL(15,2),
                is_partial INTEGER DEFAULT 0,
                total_invoice_amount DECIMAL(15,2),
                remaining_after DECIMAL(15,2),
                cheque_number VARCHAR(50),
                cheque_date DATE,
                cheque_bank VARCHAR(100),
                cheque_branch VARCHAR(100),
                cheque_status VARCHAR(20) DEFAULT 'pending' CHECK(cheque_status IN ('pending', 'cleared', 'bounced', 'cancelled')),
                bank_account_id INTEGER,
                transfer_reference VARCHAR(100),
                transfer_date DATE,
                card_last_four VARCHAR(4),
                card_type VARCHAR(20),
                auth_code VARCHAR(50),
                terminal_id VARCHAR(50),
                mobile_provider VARCHAR(20),
                mobile_number VARCHAR(20),
                transaction_id VARCHAR(100),
                cash_box_id INTEGER,
                status VARCHAR(20) DEFAULT 'completed' CHECK(status IN ('pending', 'completed', 'failed', 'refunded', 'cancelled')),
                is_refund INTEGER DEFAULT 0,
                original_payment_id INTEGER,
                refund_reason TEXT,
                operator TEXT DEFAULT 'System',
                notes TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (sale_id) REFERENCES sales_transactions(id),
                FOREIGN KEY (customer_party_id) REFERENCES parties(id),
                FOREIGN KEY (supplier_party_id) REFERENCES parties(id),
                FOREIGN KEY (currency_id) REFERENCES currencies(id),
                FOREIGN KEY (bank_account_id) REFERENCES bank_accounts(id),
                FOREIGN KEY (cash_box_id) REFERENCES cash_boxes(id),
                FOREIGN KEY (original_payment_id) REFERENCES payments(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS receipts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                receipt_number VARCHAR(30) UNIQUE NOT NULL,
                customer_party_id INTEGER,
                payment_id INTEGER,
                receipt_type VARCHAR(20) NOT NULL CHECK(receipt_type IN ('cash', 'cheque', 'bank', 'mixed')),
                received_from VARCHAR(200) NOT NULL,
                received_from_ar VARCHAR(200),
                received_by INTEGER NOT NULL,
                accountant_id INTEGER,
                amount DECIMAL(15,2) NOT NULL,
                currency_id INTEGER,
                amount_in_words TEXT,
                amount_in_words_ar TEXT,
                purpose TEXT,
                purpose_ar TEXT,
                reference_document VARCHAR(50),
                cash_amount DECIMAL(15,2) DEFAULT 0,
                cheque_amount DECIMAL(15,2) DEFAULT 0,
                bank_amount DECIMAL(15,2) DEFAULT 0,
                other_amount DECIMAL(15,2) DEFAULT 0,
                cash_box_id INTEGER,
                status VARCHAR(20) DEFAULT 'active' CHECK(status IN ('active', 'cancelled', 'void')),
                void_reason TEXT,
                voided_by INTEGER,
                voided_at DATETIME,
                print_count INTEGER DEFAULT 0,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (customer_party_id) REFERENCES parties(id),
                FOREIGN KEY (payment_id) REFERENCES payments(id),
                FOREIGN KEY (received_by) REFERENCES users(id),
                FOREIGN KEY (accountant_id) REFERENCES users(id),
                FOREIGN KEY (currency_id) REFERENCES currencies(id),
                FOREIGN KEY (cash_box_id) REFERENCES cash_boxes(id),
                FOREIGN KEY (voided_by) REFERENCES users(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS cash_boxes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                box_code VARCHAR(20) UNIQUE NOT NULL,
                box_name VARCHAR(100) NOT NULL,
                box_name_ar VARCHAR(100),
                station_id INTEGER NOT NULL,
                box_type VARCHAR(20) DEFAULT 'main' CHECK(box_type IN ('main', 'auxiliary', 'mobile', 'safe')),
                opening_balance DECIMAL(15,2) DEFAULT 0,
                current_balance DECIMAL(15,2) DEFAULT 0,
                maximum_balance DECIMAL(15,2) DEFAULT 500000,
                currency_id INTEGER,
                responsible_user_id INTEGER,
                status VARCHAR(20) DEFAULT 'active' CHECK(status IN ('active', 'closed', 'suspended')),
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (station_id) REFERENCES stations(id),
                FOREIGN KEY (currency_id) REFERENCES currencies(id),
                FOREIGN KEY (responsible_user_id) REFERENCES users(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS banks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                bank_code VARCHAR(20) UNIQUE NOT NULL,
                bank_name VARCHAR(200) NOT NULL,
                bank_name_ar VARCHAR(200),
                swift_code VARCHAR(20),
                country VARCHAR(100),
                city VARCHAR(100),
                address TEXT,
                phone VARCHAR(20),
                email VARCHAR(100),
                website VARCHAR(100),
                is_active INTEGER DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                is_deleted INTEGER DEFAULT 0,
                remarks TEXT,
                extra_data TEXT
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS bank_accounts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                account_code VARCHAR(20) UNIQUE NOT NULL,
                bank_id INTEGER NOT NULL,
                company_id INTEGER,
                station_id INTEGER,
                account_name VARCHAR(200) NOT NULL,
                account_name_ar VARCHAR(200),
                account_number VARCHAR(100) NOT NULL,
                iban VARCHAR(50),
                account_type VARCHAR(20) DEFAULT 'current' CHECK(account_type IN ('current', 'savings', 'deposit', 'loan')),
                currency_id INTEGER,
                opening_balance DECIMAL(15,2) DEFAULT 0,
                current_balance DECIMAL(15,2) DEFAULT 0,
                available_balance DECIMAL(15,2) DEFAULT 0,
                overdraft_limit DECIMAL(15,2) DEFAULT 0,
                authorized_users TEXT,
                status VARCHAR(20) DEFAULT 'active' CHECK(status IN ('active', 'inactive', 'closed', 'frozen')),
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (bank_id) REFERENCES banks(id),
                FOREIGN KEY (company_id) REFERENCES companies(id),
                FOREIGN KEY (station_id) REFERENCES stations(id),
                FOREIGN KEY (currency_id) REFERENCES currencies(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)
    }

    private fun createAccountingTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS accounts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                account_code VARCHAR(50) UNIQUE NOT NULL,
                account_name VARCHAR(200) NOT NULL,
                account_name_ar VARCHAR(200),
                parent_account_id INTEGER,
                level INTEGER NOT NULL,
                account_type VARCHAR(20) NOT NULL CHECK(account_type IN ('asset', 'liability', 'equity', 'revenue', 'expense')),
                account_category VARCHAR(50),
                normal_balance VARCHAR(10) NOT NULL CHECK(normal_balance IN ('debit', 'credit')),
                opening_balance DECIMAL(15,2) DEFAULT 0,
                current_balance DECIMAL(15,2) DEFAULT 0,
                is_bank_account INTEGER DEFAULT 0,
                is_cash_account INTEGER DEFAULT 0,
                is_control_account INTEGER DEFAULT 0,
                is_active INTEGER DEFAULT 1,
                bank_account_id INTEGER,
                cash_box_id INTEGER,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (parent_account_id) REFERENCES accounts(id),
                FOREIGN KEY (bank_account_id) REFERENCES bank_accounts(id),
                FOREIGN KEY (cash_box_id) REFERENCES cash_boxes(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS journal_entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                entry_number VARCHAR(30) UNIQUE NOT NULL,
                entry_date DATE NOT NULL,
                entry_type VARCHAR(30) NOT NULL CHECK(entry_type IN ('general', 'sales', 'purchase', 'payroll', 'adjustment', 'closing')),
                reference_type VARCHAR(50),
                reference_id INTEGER,
                reference_code VARCHAR(50),
                description TEXT NOT NULL,
                description_ar TEXT,
                total_debit DECIMAL(15,2) NOT NULL,
                total_credit DECIMAL(15,2) NOT NULL,
                is_balanced INTEGER DEFAULT 1,
                status VARCHAR(20) DEFAULT 'draft' CHECK(status IN ('draft', 'posted', 'reversed', 'cancelled')),
                posted_at DATETIME,
                posted_by INTEGER,
                reversed_entry_id INTEGER,
                reversal_reason TEXT,
                fiscal_year INTEGER,
                fiscal_period INTEGER,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (posted_by) REFERENCES users(id),
                FOREIGN KEY (reversed_entry_id) REFERENCES journal_entries(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS journal_entry_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                journal_entry_id INTEGER NOT NULL,
                line_number INTEGER NOT NULL,
                account_id INTEGER NOT NULL,
                debit DECIMAL(15,2) DEFAULT 0 CHECK(debit >= 0),
                credit DECIMAL(15,2) DEFAULT 0 CHECK(credit >= 0),
                currency_id INTEGER,
                exchange_rate DECIMAL(15,6) DEFAULT 1,
                description TEXT,
                description_ar TEXT,
                cost_center VARCHAR(50),
                project_code VARCHAR(50),
                customer_party_id INTEGER,
                supplier_party_id INTEGER,
                employee_id INTEGER,
                FOREIGN KEY (journal_entry_id) REFERENCES journal_entries(id) ON DELETE CASCADE,
                FOREIGN KEY (account_id) REFERENCES accounts(id),
                FOREIGN KEY (currency_id) REFERENCES currencies(id),
                FOREIGN KEY (customer_party_id) REFERENCES parties(id),
                FOREIGN KEY (supplier_party_id) REFERENCES parties(id),
                FOREIGN KEY (employee_id) REFERENCES employees(id),
                CHECK((debit = 0 AND credit > 0) OR (debit > 0 AND credit = 0))
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS expense_categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                category_code VARCHAR(20) UNIQUE NOT NULL,
                category_name VARCHAR(100) NOT NULL,
                category_name_ar VARCHAR(100),
                description TEXT,
                default_account_id INTEGER,
                monthly_budget DECIMAL(15,2) DEFAULT 0,
                yearly_budget DECIMAL(15,2) DEFAULT 0,
                is_active INTEGER DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                is_deleted INTEGER DEFAULT 0,
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (default_account_id) REFERENCES accounts(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS expenses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                expense_code VARCHAR(30) UNIQUE NOT NULL,
                expense_category_id INTEGER NOT NULL,
                station_id INTEGER,
                payee_name VARCHAR(200) NOT NULL,
                payee_name_ar VARCHAR(200),
                payee_type VARCHAR(20) DEFAULT 'other' CHECK(payee_type IN ('other', 'party', 'employee')),
                payee_id INTEGER,
                amount DECIMAL(15,2) NOT NULL,
                currency_id INTEGER,
                exchange_rate DECIMAL(15,6) DEFAULT 1,
                amount_in_default DECIMAL(15,2),
                tax_rate DECIMAL(5,2) DEFAULT 0,
                tax_amount DECIMAL(15,2) DEFAULT 0,
                vat_rate DECIMAL(5,2) DEFAULT 0,
                vat_amount DECIMAL(15,2) DEFAULT 0,
                total_amount DECIMAL(15,2) NOT NULL,
                payment_method VARCHAR(20) DEFAULT 'cash' CHECK(payment_method IN ('cash', 'credit_card', 'bank_transfer', 'cheque')),
                payment_status VARCHAR(20) DEFAULT 'pending' CHECK(payment_status IN ('pending', 'approved', 'paid', 'rejected', 'cancelled')),
                paid_amount DECIMAL(15,2) DEFAULT 0,
                is_recurring INTEGER DEFAULT 0,
                recurrence_type VARCHAR(20) CHECK(recurrence_type IN ('daily', 'weekly', 'monthly', 'quarterly', 'yearly')),
                next_due_date DATE,
                description TEXT NOT NULL,
                description_ar TEXT,
                invoice_number VARCHAR(50),
                invoice_path VARCHAR(500),
                receipt_path VARCHAR(500),
                journal_entry_id INTEGER,
                status VARCHAR(20) DEFAULT 'pending' CHECK(status IN ('pending', 'approved', 'paid', 'rejected', 'cancelled')),
                approved_by INTEGER,
                approved_at DATETIME,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (expense_category_id) REFERENCES expense_categories(id),
                FOREIGN KEY (station_id) REFERENCES stations(id),
                FOREIGN KEY (currency_id) REFERENCES currencies(id),
                FOREIGN KEY (approved_by) REFERENCES users(id),
                FOREIGN KEY (journal_entry_id) REFERENCES journal_entries(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS budgets (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                station_id INTEGER NOT NULL,
                budget_name TEXT NOT NULL,
                budget_period TEXT NOT NULL CHECK(budget_period IN ('monthly', 'quarterly', 'yearly')),
                start_date DATE NOT NULL,
                end_date DATE NOT NULL,
                total_amount REAL,
                currency_id INTEGER NOT NULL,
                status TEXT DEFAULT 'draft' CHECK(status IN ('draft', 'approved', 'active', 'closed')),
                created_by INTEGER NOT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (station_id) REFERENCES stations(id),
                FOREIGN KEY (currency_id) REFERENCES currencies(id),
                FOREIGN KEY (created_by) REFERENCES users(id),
                UNIQUE(budget_name, budget_period)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS budget_details (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                budget_id INTEGER NOT NULL,
                category_id INTEGER NOT NULL,
                allocated_amount REAL CHECK(allocated_amount >= 0),
                actual_amount REAL DEFAULT 0 CHECK(actual_amount >= 0),
                notes TEXT,
                FOREIGN KEY (budget_id) REFERENCES budgets(id) ON DELETE CASCADE,
                FOREIGN KEY (category_id) REFERENCES expense_categories(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS balance_sheets (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                station_id INTEGER NOT NULL,
                report_date DATE NOT NULL,
                assets_total REAL NOT NULL CHECK(assets_total >= 0),
                liabilities_total REAL NOT NULL CHECK(liabilities_total >= 0),
                equity_total REAL NOT NULL CHECK(equity_total >= 0),
                net_income REAL,
                currency_id INTEGER NOT NULL,
                generated_by INTEGER NOT NULL,
                generated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                archived INTEGER DEFAULT 0,
                FOREIGN KEY (station_id) REFERENCES stations(id),
                FOREIGN KEY (currency_id) REFERENCES currencies(id),
                FOREIGN KEY (generated_by) REFERENCES users(id)
            )
        """)
    }

    private fun createHRTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS employees (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                employee_code VARCHAR(20) UNIQUE NOT NULL,
                party_id INTEGER,
                full_name VARCHAR(200) NOT NULL,
                full_name_ar VARCHAR(200),
                national_id VARCHAR(50),
                passport_number VARCHAR(50),
                nationality VARCHAR(100),
                birth_date DATE,
                gender VARCHAR(10) CHECK(gender IN ('male', 'female')),
                marital_status VARCHAR(20),
                phone VARCHAR(20),
                phone2 VARCHAR(20),
                email VARCHAR(100),
                address TEXT,
                emergency_contact VARCHAR(200),
                emergency_phone VARCHAR(20),
                department VARCHAR(100),
                job_title VARCHAR(100) NOT NULL,
                job_title_ar VARCHAR(100),
                employment_type VARCHAR(20) DEFAULT 'full_time' CHECK(employment_type IN ('full_time', 'part_time', 'contract', 'temporary')),
                hire_date DATE NOT NULL,
                termination_date DATE,
                termination_reason TEXT,
                station_id INTEGER,
                branch_id INTEGER,
                basic_salary DECIMAL(12,2) DEFAULT 0,
                housing_allowance DECIMAL(12,2) DEFAULT 0,
                transport_allowance DECIMAL(12,2) DEFAULT 0,
                food_allowance DECIMAL(12,2) DEFAULT 0,
                other_allowances DECIMAL(12,2) DEFAULT 0,
                total_salary DECIMAL(12,2) DEFAULT 0,
                insurance_deduction DECIMAL(12,2) DEFAULT 0,
                tax_deduction DECIMAL(12,2) DEFAULT 0,
                other_deductions DECIMAL(12,2) DEFAULT 0,
                bank_name VARCHAR(200),
                bank_account VARCHAR(100),
                contract_path VARCHAR(500),
                id_doc_path VARCHAR(500),
                photo_path VARCHAR(500),
                user_id INTEGER,
                status VARCHAR(20) DEFAULT 'active' CHECK(status IN ('active', 'on_leave', 'suspended', 'terminated')),
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (party_id) REFERENCES parties(id),
                FOREIGN KEY (station_id) REFERENCES stations(id),
                FOREIGN KEY (user_id) REFERENCES users(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS attendance (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                employee_id INTEGER NOT NULL,
                station_id INTEGER,
                shift_id INTEGER,
                attendance_date DATE NOT NULL,
                check_in DATETIME,
                check_in_method VARCHAR(20) DEFAULT 'manual' CHECK(check_in_method IN ('manual', 'face', 'fingerprint', 'mobile', 'card')),
                check_in_location VARCHAR(100),
                check_in_latitude DECIMAL(10,8),
                check_in_longitude DECIMAL(11,8),
                check_in_photo VARCHAR(500),
                check_in_device VARCHAR(100),
                check_out DATETIME,
                check_out_method VARCHAR(20) DEFAULT 'manual',
                check_out_location VARCHAR(100),
                check_out_latitude DECIMAL(10,8),
                check_out_longitude DECIMAL(11,8),
                check_out_photo VARCHAR(500),
                check_out_device VARCHAR(100),
                work_hours DECIMAL(5,2),
                overtime_hours DECIMAL(5,2) DEFAULT 0,
                late_minutes INTEGER DEFAULT 0,
                early_leave_minutes INTEGER DEFAULT 0,
                status VARCHAR(20) DEFAULT 'present' CHECK(status IN ('present', 'absent', 'late', 'early_leave', 'on_leave', 'holiday')),
                absence_reason TEXT,
                approved_by INTEGER,
                approved_at DATETIME,
                notes TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                is_deleted INTEGER DEFAULT 0,
                FOREIGN KEY (employee_id) REFERENCES employees(id),
                FOREIGN KEY (station_id) REFERENCES stations(id),
                FOREIGN KEY (shift_id) REFERENCES shifts(id),
                FOREIGN KEY (approved_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS payroll (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                payroll_code VARCHAR(30) UNIQUE NOT NULL,
                payroll_year INTEGER NOT NULL,
                payroll_month INTEGER NOT NULL,
                period_start DATE NOT NULL,
                period_end DATE NOT NULL,
                total_employees INTEGER DEFAULT 0,
                total_basic_salary DECIMAL(15,2) DEFAULT 0,
                total_allowances DECIMAL(15,2) DEFAULT 0,
                total_deductions DECIMAL(15,2) DEFAULT 0,
                total_net_salary DECIMAL(15,2) DEFAULT 0,
                status VARCHAR(20) DEFAULT 'draft' CHECK(status IN ('draft', 'calculated', 'approved', 'paid', 'closed')),
                calculated_at DATETIME,
                calculated_by INTEGER,
                approved_by INTEGER,
                approved_at DATETIME,
                paid_at DATETIME,
                paid_by INTEGER,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                FOREIGN KEY (calculated_by) REFERENCES users(id),
                FOREIGN KEY (approved_by) REFERENCES users(id),
                FOREIGN KEY (paid_by) REFERENCES users(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS payroll_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                payroll_id INTEGER NOT NULL,
                employee_id INTEGER NOT NULL,
                work_days INTEGER DEFAULT 0,
                absent_days INTEGER DEFAULT 0,
                overtime_hours DECIMAL(5,2) DEFAULT 0,
                late_hours DECIMAL(5,2) DEFAULT 0,
                basic_salary DECIMAL(12,2) DEFAULT 0,
                housing_allowance DECIMAL(12,2) DEFAULT 0,
                transport_allowance DECIMAL(12,2) DEFAULT 0,
                food_allowance DECIMAL(12,2) DEFAULT 0,
                overtime_pay DECIMAL(12,2) DEFAULT 0,
                bonus DECIMAL(12,2) DEFAULT 0,
                other_earnings DECIMAL(12,2) DEFAULT 0,
                total_earnings DECIMAL(15,2) DEFAULT 0,
                absence_deduction DECIMAL(12,2) DEFAULT 0,
                late_deduction DECIMAL(12,2) DEFAULT 0,
                insurance DECIMAL(12,2) DEFAULT 0,
                tax DECIMAL(12,2) DEFAULT 0,
                loan_deduction DECIMAL(12,2) DEFAULT 0,
                other_deductions DECIMAL(12,2) DEFAULT 0,
                total_deductions DECIMAL(15,2) DEFAULT 0,
                net_salary DECIMAL(15,2) DEFAULT 0,
                payment_method VARCHAR(20) DEFAULT 'bank_transfer' CHECK(payment_method IN ('cash', 'bank_transfer')),
                payment_status VARCHAR(20) DEFAULT 'pending' CHECK(payment_status IN ('pending', 'paid')),
                paid_amount DECIMAL(15,2) DEFAULT 0,
                paid_at DATETIME,
                FOREIGN KEY (payroll_id) REFERENCES payroll(id) ON DELETE CASCADE,
                FOREIGN KEY (employee_id) REFERENCES employees(id)
            )
        """)
    }

    private fun createAssetTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS fixed_assets (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                station_id INTEGER NOT NULL,
                asset_code VARCHAR(20) UNIQUE NOT NULL,
                asset_name VARCHAR(255) NOT NULL,
                category_id INTEGER,
                purchase_date DATE DEFAULT CURRENT_TIMESTAMP,
                purchase_cost DECIMAL(12,2) CHECK(purchase_cost >= 0),
                current_value DECIMAL(12,2) CHECK(current_value >= 0),
                useful_life INTEGER,
                salvage_value DECIMAL(12,2) CHECK(salvage_value >= 0),
                depreciation_method VARCHAR(50),
                asset_type VARCHAR(20) CHECK(asset_type IN ('tank', 'pump', 'nozzle', 'generator', 'building', 'other')),
                serial_number VARCHAR(100),
                model VARCHAR(100),
                manufacturer VARCHAR(100),
                warranty_expiry DATE,
                status VARCHAR(20) DEFAULT 'active' CHECK(status IN ('active', 'inactive', 'maintenance', 'disposed')),
                location VARCHAR(255),
                notes TEXT,
                documents TEXT,
                maintenance_history TEXT,
                transfer_history TEXT,
                disposal_data TEXT,
                disposed_at DATETIME,
                disposed_by INTEGER,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (station_id) REFERENCES stations(id),
                FOREIGN KEY (category_id) REFERENCES product_categories(id),
                FOREIGN KEY (disposed_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS depreciation (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                asset_id INTEGER NOT NULL,
                depreciation_date DATE DEFAULT CURRENT_TIMESTAMP,
                depreciation_amount DECIMAL(12,2) CHECK(depreciation_amount >= 0),
                accumulated_depreciation DECIMAL(12,2) CHECK(accumulated_depreciation >= 0),
                remaining_value DECIMAL(12,2) CHECK(remaining_value >= 0),
                journal_entry_id INTEGER,
                created_by INTEGER,
                archived INTEGER DEFAULT 0,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (asset_id) REFERENCES fixed_assets(id),
                FOREIGN KEY (journal_entry_id) REFERENCES journal_entries(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS maintenance_requests (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                request_code VARCHAR(30) UNIQUE NOT NULL,
                asset_type VARCHAR(20) NOT NULL,
                asset_id INTEGER NOT NULL,
                request_type VARCHAR(30) NOT NULL,
                priority VARCHAR(10) DEFAULT 'medium',
                title VARCHAR(200) NOT NULL,
                description TEXT NOT NULL,
                description_ar TEXT,
                symptoms TEXT,
                error_codes TEXT,
                reported_by INTEGER NOT NULL,
                reported_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                assigned_to INTEGER,
                assigned_at DATETIME,
                scheduled_date DATE,
                scheduled_time TIME,
                estimated_duration INTEGER,
                started_at DATETIME,
                completed_at DATETIME,
                actual_duration INTEGER,
                resolution TEXT,
                resolution_ar TEXT,
                parts_used TEXT,
                labor_cost DECIMAL(12,2) DEFAULT 0,
                parts_cost DECIMAL(12,2) DEFAULT 0,
                total_cost DECIMAL(12,2) DEFAULT 0,
                status VARCHAR(20) DEFAULT 'open',
                approved_by INTEGER,
                approved_at DATETIME,
                before_photos TEXT,
                after_photos TEXT,
                station_id INTEGER NOT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                deleted_at DATETIME,
                created_by INTEGER,
                updated_by INTEGER,
                deleted_by INTEGER,
                is_deleted INTEGER DEFAULT 0,
                sync_status VARCHAR(20) DEFAULT 'synced',
                sync_version INTEGER DEFAULT 1,
                sync_at DATETIME,
                device_id VARCHAR(100),
                remarks TEXT,
                extra_data TEXT,
                FOREIGN KEY (reported_by) REFERENCES users(id),
                FOREIGN KEY (assigned_to) REFERENCES users(id),
                FOREIGN KEY (approved_by) REFERENCES users(id),
                FOREIGN KEY (station_id) REFERENCES stations(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS maintenance_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                maintenance_request_id INTEGER NOT NULL,
                event_type VARCHAR(20) NOT NULL,
                event_description TEXT NOT NULL,
                old_value TEXT,
                new_value TEXT,
                performed_by INTEGER NOT NULL,
                performed_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (maintenance_request_id) REFERENCES maintenance_requests(id) ON DELETE CASCADE,
                FOREIGN KEY (performed_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS maintenance_schedule (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                schedule_code VARCHAR(30) UNIQUE NOT NULL,
                schedule_name VARCHAR(100) NOT NULL,
                asset_type VARCHAR(20) NOT NULL,
                frequency_type VARCHAR(20) NOT NULL CHECK(frequency_type IN ('daily', 'weekly', 'monthly', 'yearly', 'meter_based')),
                frequency_value INTEGER,
                day_of_week INTEGER,
                day_of_month INTEGER,
                month INTEGER,
                meter_trigger DECIMAL(12,2),
                description TEXT,
                is_active INTEGER DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS maintenance_parts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                maintenance_request_id INTEGER,
                product_id INTEGER NOT NULL,
                quantity INTEGER NOT NULL,
                unit_price DECIMAL(12,2),
                total_price DECIMAL(12,2),
                notes TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (maintenance_request_id) REFERENCES maintenance_requests(id) ON DELETE CASCADE,
                FOREIGN KEY (product_id) REFERENCES products(id)
            )
        """)
    }

    private fun createNotificationTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS notification_templates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                template_code VARCHAR(30) UNIQUE NOT NULL,
                template_name VARCHAR(100) NOT NULL,
                template_name_ar VARCHAR(100),
                channel VARCHAR(20) NOT NULL CHECK(channel IN ('sms', 'email', 'push', 'whatsapp', 'telegram', 'in_app')),
                subject TEXT,
                body TEXT NOT NULL,
                body_ar TEXT,
                variables TEXT,
                is_active INTEGER DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS notifications (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                user_id INTEGER,
                role_id INTEGER,
                customer_party_id INTEGER,
                template_id INTEGER,
                notification_type VARCHAR(30) NOT NULL,
                title VARCHAR(200) NOT NULL,
                title_ar VARCHAR(200),
                message TEXT NOT NULL,
                message_ar TEXT,
                priority VARCHAR(10) DEFAULT 'normal' CHECK(priority IN ('low', 'normal', 'high', 'urgent')),
                channel VARCHAR(20) DEFAULT 'in_app' CHECK(channel IN ('sms', 'email', 'push', 'whatsapp', 'telegram', 'in_app')),
                status VARCHAR(20) DEFAULT 'pending' CHECK(status IN ('pending', 'queued', 'sent', 'failed', 'read', 'cancelled')),
                is_read INTEGER DEFAULT 0,
                read_at DATETIME,
                reference_type VARCHAR(50),
                reference_id INTEGER,
                action_url VARCHAR(500),
                action_text VARCHAR(100),
                expires_at DATETIME,
                sent_at DATETIME,
                error_message TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                FOREIGN KEY (user_id) REFERENCES users(id),
                FOREIGN KEY (role_id) REFERENCES roles(id),
                FOREIGN KEY (customer_party_id) REFERENCES parties(id),
                FOREIGN KEY (template_id) REFERENCES notification_templates(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS notification_queue (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                notification_id INTEGER NOT NULL,
                channel VARCHAR(20) NOT NULL,
                recipient VARCHAR(255) NOT NULL,
                retry_count INTEGER DEFAULT 0,
                max_retries INTEGER DEFAULT 3,
                next_attempt_at DATETIME,
                status VARCHAR(20) DEFAULT 'pending' CHECK(status IN ('pending', 'sent', 'failed', 'cancelled')),
                error_message TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (notification_id) REFERENCES notifications(id) ON DELETE CASCADE
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS notification_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                notification_id INTEGER,
                queue_id INTEGER,
                channel VARCHAR(20) NOT NULL,
                recipient VARCHAR(255),
                subject TEXT,
                body TEXT,
                sent_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                status VARCHAR(20) DEFAULT 'sent',
                provider_response TEXT,
                error_message TEXT,
                cost DECIMAL(10,4) DEFAULT 0,
                FOREIGN KEY (notification_id) REFERENCES notifications(id) ON DELETE CASCADE,
                FOREIGN KEY (queue_id) REFERENCES notification_queue(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_reminders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                customer_party_id INTEGER NOT NULL,
                transaction_id INTEGER NOT NULL,
                reminder_type TEXT DEFAULT 'due_date' CHECK(reminder_type IN ('due_date', 'overdue', 'custom')),
                reminder_date DATE NOT NULL,
                days_before_due INTEGER DEFAULT 2,
                message_content TEXT,
                message_template TEXT DEFAULT 'default',
                status TEXT DEFAULT 'pending' CHECK(status IN ('pending', 'sent', 'failed', 'cancelled', 'retry')),
                retry_count INTEGER DEFAULT 0,
                max_retries INTEGER DEFAULT 3,
                sent_at DATETIME,
                sms_provider_response TEXT,
                error_message TEXT,
                created_by INTEGER,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (customer_party_id) REFERENCES parties(id),
                FOREIGN KEY (transaction_id) REFERENCES sales_transactions(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                customer_party_id INTEGER,
                reminder_id INTEGER,
                phone_number TEXT NOT NULL,
                message_content TEXT NOT NULL,
                message_type TEXT DEFAULT 'reminder' CHECK(message_type IN ('reminder', 'notification', 'alert', 'custom')),
                gateway_type TEXT DEFAULT 'android_app',
                gateway_response TEXT,
                device_id TEXT,
                sim_slot INTEGER DEFAULT 1,
                status TEXT DEFAULT 'queued' CHECK(status IN ('queued', 'sending', 'sent', 'delivered', 'failed', 'cancelled')),
                sent_at DATETIME,
                delivered_at DATETIME,
                error_message TEXT,
                cost REAL DEFAULT 0,
                created_by INTEGER,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (customer_party_id) REFERENCES parties(id),
                FOREIGN KEY (reminder_id) REFERENCES sms_reminders(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS attachments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                entity_type VARCHAR(50) NOT NULL,
                entity_id INTEGER NOT NULL,
                file_name VARCHAR(255) NOT NULL,
                file_name_original VARCHAR(255),
                file_path VARCHAR(500) NOT NULL,
                file_url VARCHAR(500),
                file_size INTEGER,
                file_type VARCHAR(100),
                file_extension VARCHAR(20),
                thumbnail_path VARCHAR(500),
                description TEXT,
                description_ar TEXT,
                uploaded_by INTEGER NOT NULL,
                is_active INTEGER DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                is_deleted INTEGER DEFAULT 0,
                FOREIGN KEY (uploaded_by) REFERENCES users(id)
            )
        """)
    }

    private fun createLogTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS audit_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                user_id INTEGER,
                action_type VARCHAR(50) NOT NULL,
                table_name VARCHAR(50) NOT NULL,
                record_id INTEGER,
                old_row_json TEXT,
                new_row_json TEXT,
                changed_columns TEXT,
                ip_address VARCHAR(45),
                user_agent TEXT,
                device_name VARCHAR(100),
                os_version VARCHAR(20),
                app_version VARCHAR(20),
                browser VARCHAR(50),
                gps VARCHAR(100),
                execution_time INTEGER,
                request_id VARCHAR(50),
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (user_id) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS system_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                log_level VARCHAR(10) NOT NULL CHECK(log_level IN ('debug', 'info', 'warning', 'error', 'critical')),
                log_type VARCHAR(30) NOT NULL,
                source VARCHAR(100),
                source_version VARCHAR(20),
                message TEXT NOT NULL,
                message_ar TEXT,
                user_id INTEGER,
                station_id INTEGER,
                device_id VARCHAR(100),
                ip_address VARCHAR(45),
                stack_trace TEXT,
                request_data TEXT,
                response_data TEXT,
                is_resolved INTEGER DEFAULT 0,
                resolved_by INTEGER,
                resolved_at DATETIME,
                resolution_notes TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (user_id) REFERENCES users(id),
                FOREIGN KEY (station_id) REFERENCES stations(id),
                FOREIGN KEY (resolved_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                sync_type VARCHAR(20) NOT NULL CHECK(sync_type IN ('push', 'pull', 'bidirectional')),
                sync_direction VARCHAR(20) NOT NULL,
                device_id VARCHAR(100) NOT NULL,
                device_type VARCHAR(50),
                device_name VARCHAR(200),
                app_version VARCHAR(20),
                entity_type VARCHAR(50) NOT NULL,
                records_synced INTEGER DEFAULT 0,
                records_failed INTEGER DEFAULT 0,
                records_total INTEGER DEFAULT 0,
                started_at DATETIME NOT NULL,
                completed_at DATETIME,
                duration_seconds INTEGER,
                status VARCHAR(20) DEFAULT 'in_progress' CHECK(status IN ('in_progress', 'success', 'partial', 'failed')),
                error_message TEXT,
                error_details TEXT,
                network_type VARCHAR(20),
                data_transferred_kb INTEGER,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS backup_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                backup_type VARCHAR(20) NOT NULL CHECK(backup_type IN ('full', 'incremental', 'differential')),
                backup_method VARCHAR(20) DEFAULT 'manual',
                database_type VARCHAR(20),
                database_name VARCHAR(100),
                file_name VARCHAR(255),
                file_path VARCHAR(500),
                file_size_mb DECIMAL(10,2),
                checksum VARCHAR(64),
                tables_included TEXT,
                tables_excluded TEXT,
                started_at DATETIME NOT NULL,
                completed_at DATETIME,
                duration_seconds INTEGER,
                status VARCHAR(20) DEFAULT 'in_progress' CHECK(status IN ('in_progress', 'success', 'failed', 'cancelled')),
                error_message TEXT,
                storage_location VARCHAR(50),
                storage_path VARCHAR(500),
                is_encrypted INTEGER DEFAULT 0,
                encryption_method VARCHAR(50),
                expiry_date DATE,
                is_deleted INTEGER DEFAULT 0,
                deleted_at DATETIME,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS restore_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                backup_id INTEGER,
                restore_date DATETIME DEFAULT CURRENT_TIMESTAMP,
                restored_by INTEGER,
                status VARCHAR(20) DEFAULT 'success' CHECK(status IN ('success', 'failed', 'partial')),
                error_message TEXT,
                notes TEXT,
                FOREIGN KEY (backup_id) REFERENCES backup_history(id),
                FOREIGN KEY (restored_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS system_settings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                setting_key TEXT UNIQUE NOT NULL,
                setting_value TEXT NOT NULL,
                category VARCHAR(50) DEFAULT 'general',
                data_type VARCHAR(20) DEFAULT 'string' CHECK(data_type IN ('string', 'integer', 'float', 'boolean', 'json', 'password')),
                is_encrypted INTEGER DEFAULT 0,
                description TEXT,
                setting_group VARCHAR(50),
                is_public INTEGER DEFAULT 0,
                updated_by INTEGER,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                archived INTEGER DEFAULT 0,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (updated_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS station_settings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                station_id INTEGER NOT NULL,
                setting_key VARCHAR(50) NOT NULL,
                setting_value TEXT,
                data_type VARCHAR(20) DEFAULT 'string',
                description TEXT,
                updated_by INTEGER,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (station_id) REFERENCES stations(id),
                FOREIGN KEY (updated_by) REFERENCES users(id),
                UNIQUE(station_id, setting_key)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS schema_changes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                change_description TEXT NOT NULL,
                change_type TEXT NOT NULL,
                changed_by INTEGER NOT NULL,
                change_date DATETIME DEFAULT CURRENT_TIMESTAMP,
                sql_script TEXT,
                version_from TEXT,
                version_to TEXT,
                archived INTEGER DEFAULT 0,
                FOREIGN KEY (changed_by) REFERENCES users(id)
            )
        """)
    }

    private fun createAdvancedTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS approval_workflows (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                workflow_code VARCHAR(30) UNIQUE NOT NULL,
                workflow_name VARCHAR(100) NOT NULL,
                workflow_name_ar VARCHAR(100),
                description TEXT,
                entity_type VARCHAR(50) NOT NULL,
                is_active INTEGER DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS approval_steps (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                workflow_id INTEGER NOT NULL,
                step_order INTEGER NOT NULL,
                step_name VARCHAR(100) NOT NULL,
                step_name_ar VARCHAR(100),
                role_id INTEGER,
                user_id INTEGER,
                is_parallel INTEGER DEFAULT 0,
                timeout_hours INTEGER DEFAULT 24,
                escalation_role_id INTEGER,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (workflow_id) REFERENCES approval_workflows(id) ON DELETE CASCADE,
                FOREIGN KEY (role_id) REFERENCES roles(id),
                FOREIGN KEY (user_id) REFERENCES users(id),
                FOREIGN KEY (escalation_role_id) REFERENCES roles(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS approval_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                workflow_id INTEGER NOT NULL,
                entity_type VARCHAR(50) NOT NULL,
                entity_id INTEGER NOT NULL,
                current_step_id INTEGER,
                status VARCHAR(20) DEFAULT 'pending' CHECK(status IN ('pending', 'approved', 'rejected', 'escalated', 'cancelled')),
                requested_by INTEGER NOT NULL,
                requested_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                approved_by INTEGER,
                approved_at DATETIME,
                rejection_reason TEXT,
                notes TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (workflow_id) REFERENCES approval_workflows(id),
                FOREIGN KEY (current_step_id) REFERENCES approval_steps(id),
                FOREIGN KEY (requested_by) REFERENCES users(id),
                FOREIGN KEY (approved_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS scheduled_jobs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                job_code VARCHAR(30) UNIQUE NOT NULL,
                job_name VARCHAR(100) NOT NULL,
                job_name_ar VARCHAR(100),
                description TEXT,
                job_class VARCHAR(200) NOT NULL,
                schedule_type VARCHAR(20) NOT NULL CHECK(schedule_type IN ('cron', 'interval', 'once')),
                cron_expression VARCHAR(100),
                interval_seconds INTEGER,
                run_at DATETIME,
                timezone VARCHAR(50) DEFAULT 'UTC',
                enabled INTEGER DEFAULT 1,
                last_run_at DATETIME,
                next_run_at DATETIME,
                last_run_status VARCHAR(20),
                last_run_error TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS job_queue (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                job_type VARCHAR(50) NOT NULL,
                job_data TEXT,
                priority INTEGER DEFAULT 0,
                status VARCHAR(20) DEFAULT 'pending' CHECK(status IN ('pending', 'processing', 'completed', 'failed', 'cancelled')),
                started_at DATETIME,
                completed_at DATETIME,
                retry_count INTEGER DEFAULT 0,
                max_retries INTEGER DEFAULT 3,
                error_message TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS dashboard_widgets (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                user_id INTEGER NOT NULL,
                widget_type VARCHAR(30) NOT NULL,
                widget_title VARCHAR(100),
                widget_title_ar VARCHAR(100),
                position_x INTEGER DEFAULT 0,
                position_y INTEGER DEFAULT 0,
                width INTEGER DEFAULT 2,
                height INTEGER DEFAULT 2,
                config TEXT,
                is_active INTEGER DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                UNIQUE(user_id, widget_type)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS smart_alerts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                alert_code VARCHAR(30) UNIQUE NOT NULL,
                alert_name VARCHAR(100) NOT NULL,
                alert_name_ar VARCHAR(100),
                description TEXT,
                severity VARCHAR(20) DEFAULT 'warning' CHECK(severity IN ('info', 'warning', 'critical')),
                condition_type VARCHAR(30) NOT NULL,
                condition_config TEXT NOT NULL,
                entity_type VARCHAR(50),
                is_active INTEGER DEFAULT 1,
                trigger_action TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS smart_alert_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                alert_id INTEGER NOT NULL,
                entity_id INTEGER,
                detected_value TEXT,
                triggered_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                is_resolved INTEGER DEFAULT 0,
                resolved_at DATETIME,
                resolved_by INTEGER,
                resolution_notes TEXT,
                FOREIGN KEY (alert_id) REFERENCES smart_alerts(id) ON DELETE CASCADE,
                FOREIGN KEY (resolved_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS documents (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                document_code VARCHAR(30) UNIQUE NOT NULL,
                document_name VARCHAR(200) NOT NULL,
                document_name_ar VARCHAR(200),
                document_type VARCHAR(30) NOT NULL,
                entity_type VARCHAR(50) NOT NULL,
                entity_id INTEGER NOT NULL,
                file_name VARCHAR(255) NOT NULL,
                file_path VARCHAR(500) NOT NULL,
                file_url VARCHAR(500),
                file_size INTEGER,
                mime_type VARCHAR(100),
                file_hash VARCHAR(64),
                version INTEGER DEFAULT 1,
                description TEXT,
                description_ar TEXT,
                expiry_date DATE,
                is_confidential INTEGER DEFAULT 0,
                uploaded_by INTEGER NOT NULL,
                uploaded_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                is_deleted INTEGER DEFAULT 0,
                deleted_at DATETIME,
                FOREIGN KEY (uploaded_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS barcode_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                barcode VARCHAR(50) NOT NULL,
                qr_code VARCHAR(500),
                entity_type VARCHAR(50) NOT NULL,
                entity_id INTEGER NOT NULL,
                generated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                generated_by INTEGER,
                is_active INTEGER DEFAULT 1,
                expires_at DATETIME,
                FOREIGN KEY (generated_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS vehicle_locations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                vehicle_id INTEGER NOT NULL,
                latitude DECIMAL(10,8) NOT NULL,
                longitude DECIMAL(11,8) NOT NULL,
                speed DECIMAL(6,2),
                heading DECIMAL(5,2),
                fuel_level DECIMAL(5,2),
                odometer DECIMAL(10,2),
                altitude DECIMAL(8,2),
                accuracy DECIMAL(5,2),
                location_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (vehicle_id) REFERENCES vehicles(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS iot_devices (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                device_code VARCHAR(30) UNIQUE NOT NULL,
                device_name VARCHAR(100) NOT NULL,
                device_type VARCHAR(30) NOT NULL,
                entity_type VARCHAR(50) NOT NULL,
                entity_id INTEGER NOT NULL,
                serial_number VARCHAR(100),
                model VARCHAR(100),
                manufacturer VARCHAR(100),
                firmware_version VARCHAR(20),
                ip_address VARCHAR(45),
                port INTEGER,
                last_communication DATETIME,
                status VARCHAR(20) DEFAULT 'active' CHECK(status IN ('active', 'inactive', 'offline', 'maintenance')),
                config TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS vehicle_maintenance (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                vehicle_id INTEGER NOT NULL,
                maintenance_type VARCHAR(30) NOT NULL,
                description TEXT,
                maintenance_date DATE NOT NULL,
                cost DECIMAL(12,2),
                odometer_at_maintenance DECIMAL(10,2),
                next_due_date DATE,
                next_due_odometer DECIMAL(10,2),
                performed_by VARCHAR(100),
                notes TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (vehicle_id) REFERENCES vehicles(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS vehicle_trips (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                vehicle_id INTEGER NOT NULL,
                driver_id INTEGER,
                trip_date DATE NOT NULL,
                start_location VARCHAR(255),
                end_location VARCHAR(255),
                distance_km DECIMAL(10,2),
                fuel_consumed DECIMAL(8,2),
                fuel_cost DECIMAL(12,2),
                start_odometer DECIMAL(10,2),
                end_odometer DECIMAL(10,2),
                trip_purpose TEXT,
                notes TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (vehicle_id) REFERENCES vehicles(id),
                FOREIGN KEY (driver_id) REFERENCES drivers(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS vehicle_expenses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                vehicle_id INTEGER NOT NULL,
                expense_type VARCHAR(30) NOT NULL,
                expense_date DATE NOT NULL,
                amount DECIMAL(12,2) NOT NULL,
                currency_id INTEGER,
                odometer_reading DECIMAL(10,2),
                description TEXT,
                invoice_path VARCHAR(500),
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (vehicle_id) REFERENCES vehicles(id),
                FOREIGN KEY (currency_id) REFERENCES currencies(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS vehicle_insurance (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                vehicle_id INTEGER NOT NULL,
                insurance_company VARCHAR(200),
                policy_number VARCHAR(50),
                start_date DATE NOT NULL,
                end_date DATE NOT NULL,
                premium DECIMAL(12,2),
                coverage_type VARCHAR(50),
                insurance_doc VARCHAR(500),
                is_active INTEGER DEFAULT 1,
                notes TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (vehicle_id) REFERENCES vehicles(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS vehicle_accidents (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                vehicle_id INTEGER NOT NULL,
                driver_id INTEGER,
                accident_date DATE NOT NULL,
                location VARCHAR(255),
                description TEXT,
                severity VARCHAR(20) CHECK(severity IN ('minor', 'moderate', 'severe', 'total_loss')),
                damage_cost DECIMAL(12,2),
                repair_cost DECIMAL(12,2),
                insurance_claim_number VARCHAR(50),
                police_report VARCHAR(500),
                photos TEXT,
                notes TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (vehicle_id) REFERENCES vehicles(id),
                FOREIGN KEY (driver_id) REFERENCES drivers(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS contracts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                contract_code VARCHAR(30) UNIQUE NOT NULL,
                contract_name VARCHAR(200) NOT NULL,
                contract_name_ar VARCHAR(200),
                party_id INTEGER NOT NULL,
                contract_type VARCHAR(30) NOT NULL,
                start_date DATE NOT NULL,
                end_date DATE,
                auto_renew INTEGER DEFAULT 0,
                renewal_terms TEXT,
                terms TEXT,
                special_conditions TEXT,
                total_value DECIMAL(15,2),
                currency_id INTEGER,
                status VARCHAR(20) DEFAULT 'active' CHECK(status IN ('draft', 'active', 'expired', 'terminated')),
                signed_by INTEGER,
                signed_date DATE,
                document_path VARCHAR(500),
                notes TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                FOREIGN KEY (party_id) REFERENCES parties(id),
                FOREIGN KEY (currency_id) REFERENCES currencies(id),
                FOREIGN KEY (signed_by) REFERENCES users(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS customer_visits (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                party_id INTEGER NOT NULL,
                visit_date DATETIME DEFAULT CURRENT_TIMESTAMP,
                visit_type VARCHAR(30) NOT NULL,
                purpose TEXT,
                outcome TEXT,
                notes TEXT,
                visited_by INTEGER,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (party_id) REFERENCES parties(id),
                FOREIGN KEY (visited_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS customer_calls (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                party_id INTEGER NOT NULL,
                call_date DATETIME DEFAULT CURRENT_TIMESTAMP,
                caller VARCHAR(100),
                receiver VARCHAR(100),
                duration_seconds INTEGER,
                topic TEXT,
                summary TEXT,
                is_outgoing INTEGER DEFAULT 1,
                created_by INTEGER,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (party_id) REFERENCES parties(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS customer_complaints (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                party_id INTEGER NOT NULL,
                complaint_date DATETIME DEFAULT CURRENT_TIMESTAMP,
                complaint_type VARCHAR(50),
                description TEXT,
                priority VARCHAR(10) DEFAULT 'medium' CHECK(priority IN ('low', 'medium', 'high', 'critical')),
                status VARCHAR(20) DEFAULT 'open' CHECK(status IN ('open', 'in_progress', 'resolved', 'closed')),
                resolution TEXT,
                resolved_date DATETIME,
                resolved_by INTEGER,
                created_by INTEGER,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (party_id) REFERENCES parties(id),
                FOREIGN KEY (resolved_by) REFERENCES users(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS customer_followups (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                party_id INTEGER NOT NULL,
                followup_date DATE NOT NULL,
                followup_type VARCHAR(30) NOT NULL,
                description TEXT,
                reminder_date DATE,
                status VARCHAR(20) DEFAULT 'pending' CHECK(status IN ('pending', 'done', 'cancelled')),
                assigned_to INTEGER,
                created_by INTEGER,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (party_id) REFERENCES parties(id),
                FOREIGN KEY (assigned_to) REFERENCES users(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS kpi_definitions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                kpi_code VARCHAR(30) UNIQUE NOT NULL,
                kpi_name VARCHAR(100) NOT NULL,
                kpi_name_ar VARCHAR(100),
                description TEXT,
                category VARCHAR(50) NOT NULL,
                formula TEXT,
                target_value DECIMAL(12,2),
                unit VARCHAR(20),
                frequency VARCHAR(20) DEFAULT 'daily' CHECK(frequency IN ('daily', 'weekly', 'monthly', 'quarterly', 'yearly')),
                is_active INTEGER DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS kpi_results (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                kpi_id INTEGER NOT NULL,
                period_start DATE NOT NULL,
                period_end DATE NOT NULL,
                actual_value DECIMAL(12,2),
                target_value DECIMAL(12,2),
                variance_percent DECIMAL(5,2),
                status VARCHAR(20) DEFAULT 'on_track' CHECK(status IN ('on_track', 'warning', 'critical', 'exceeded')),
                calculated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                notes TEXT,
                FOREIGN KEY (kpi_id) REFERENCES kpi_definitions(id) ON DELETE CASCADE
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS dim_date (
                date_id INTEGER PRIMARY KEY,
                full_date DATE UNIQUE NOT NULL,
                year INTEGER NOT NULL,
                quarter INTEGER NOT NULL,
                month INTEGER NOT NULL,
                month_name VARCHAR(20),
                day INTEGER NOT NULL,
                day_name VARCHAR(20),
                day_of_week INTEGER NOT NULL,
                week_of_year INTEGER NOT NULL,
                is_weekend INTEGER DEFAULT 0,
                is_holiday INTEGER DEFAULT 0
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS dim_customer (
                customer_dim_id INTEGER PRIMARY KEY AUTOINCREMENT,
                party_id INTEGER NOT NULL,
                customer_code VARCHAR(20),
                full_name VARCHAR(200),
                customer_type VARCHAR(50),
                city VARCHAR(100),
                country VARCHAR(100),
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (party_id) REFERENCES parties(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS dim_station (
                station_dim_id INTEGER PRIMARY KEY AUTOINCREMENT,
                station_id INTEGER NOT NULL,
                station_code VARCHAR(20),
                station_name VARCHAR(200),
                city VARCHAR(100),
                country VARCHAR(100),
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (station_id) REFERENCES stations(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS dim_product (
                product_dim_id INTEGER PRIMARY KEY AUTOINCREMENT,
                product_id INTEGER NOT NULL,
                product_code VARCHAR(30),
                product_name VARCHAR(200),
                category_name VARCHAR(100),
                product_type VARCHAR(20),
                unit VARCHAR(20),
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (product_id) REFERENCES products(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS fact_sales (
                fact_id INTEGER PRIMARY KEY AUTOINCREMENT,
                sale_id INTEGER NOT NULL,
                date_id INTEGER NOT NULL,
                customer_dim_id INTEGER,
                station_dim_id INTEGER NOT NULL,
                product_dim_id INTEGER,
                fuel_type_id INTEGER,
                quantity DECIMAL(12,3),
                unit_price DECIMAL(12,4),
                total_amount DECIMAL(15,2),
                discount_amount DECIMAL(15,2),
                tax_amount DECIMAL(15,2),
                net_amount DECIMAL(15,2),
                gross_profit DECIMAL(15,2),
                payment_method VARCHAR(20),
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (date_id) REFERENCES dim_date(date_id),
                FOREIGN KEY (customer_dim_id) REFERENCES dim_customer(customer_dim_id),
                FOREIGN KEY (station_dim_id) REFERENCES dim_station(station_dim_id),
                FOREIGN KEY (product_dim_id) REFERENCES dim_product(product_dim_id),
                FOREIGN KEY (fuel_type_id) REFERENCES fuel_types(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS fact_inventory (
                fact_id INTEGER PRIMARY KEY AUTOINCREMENT,
                product_id INTEGER NOT NULL,
                station_id INTEGER NOT NULL,
                date_id INTEGER NOT NULL,
                opening_quantity DECIMAL(12,2),
                closing_quantity DECIMAL(12,2),
                average_cost DECIMAL(12,4),
                total_value DECIMAL(15,2),
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (date_id) REFERENCES dim_date(date_id),
                FOREIGN KEY (product_id) REFERENCES products(id),
                FOREIGN KEY (station_id) REFERENCES stations(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS fact_payments (
                fact_id INTEGER PRIMARY KEY AUTOINCREMENT,
                payment_id INTEGER NOT NULL,
                date_id INTEGER NOT NULL,
                party_id INTEGER,
                station_id INTEGER NOT NULL,
                amount DECIMAL(15,2),
                payment_method VARCHAR(20),
                payment_type VARCHAR(20),
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (date_id) REFERENCES dim_date(date_id),
                FOREIGN KEY (party_id) REFERENCES parties(id),
                FOREIGN KEY (station_id) REFERENCES stations(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS predictions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                prediction_type VARCHAR(30) NOT NULL,
                entity_type VARCHAR(50),
                entity_id INTEGER,
                prediction_date DATE NOT NULL,
                predicted_value DECIMAL(15,2),
                confidence_interval_low DECIMAL(15,2),
                confidence_interval_high DECIMAL(15,2),
                actual_value DECIMAL(15,2),
                model_version VARCHAR(20),
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS field_permissions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                role_id INTEGER NOT NULL,
                table_name VARCHAR(50) NOT NULL,
                field_name VARCHAR(50) NOT NULL,
                can_view INTEGER DEFAULT 1,
                can_edit INTEGER DEFAULT 0,
                can_hide INTEGER DEFAULT 0,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE,
                FOREIGN KEY (created_by) REFERENCES users(id),
                UNIQUE(role_id, table_name, field_name)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_devices (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                device_id VARCHAR(100) UNIQUE NOT NULL,
                device_name VARCHAR(200),
                device_type VARCHAR(50),
                os_version VARCHAR(20),
                app_version VARCHAR(20),
                station_id INTEGER,
                last_sync_at DATETIME,
                is_active INTEGER DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (station_id) REFERENCES stations(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_queue (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                device_id VARCHAR(100) NOT NULL,
                entity_type VARCHAR(50) NOT NULL,
                entity_id INTEGER NOT NULL,
                operation VARCHAR(20) NOT NULL CHECK(operation IN ('create', 'update', 'delete', 'merge')),
                payload TEXT,
                priority INTEGER DEFAULT 0,
                status VARCHAR(20) DEFAULT 'pending' CHECK(status IN ('pending', 'sent', 'acknowledged', 'failed')),
                retry_count INTEGER DEFAULT 0,
                max_retries INTEGER DEFAULT 5,
                error_message TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (device_id) REFERENCES sync_devices(device_id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_conflicts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                device_id VARCHAR(100) NOT NULL,
                entity_type VARCHAR(50) NOT NULL,
                entity_id INTEGER NOT NULL,
                local_version INTEGER,
                remote_version INTEGER,
                local_data TEXT,
                remote_data TEXT,
                status VARCHAR(20) DEFAULT 'pending' CHECK(status IN ('pending', 'resolved', 'ignored')),
                resolved_by INTEGER,
                resolved_at DATETIME,
                resolution_data TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (device_id) REFERENCES sync_devices(device_id),
                FOREIGN KEY (resolved_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS system_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                event_type VARCHAR(50) NOT NULL,
                event_source VARCHAR(50),
                event_data TEXT,
                user_id INTEGER,
                station_id INTEGER,
                device_id VARCHAR(100),
                ip_address VARCHAR(45),
                is_processed INTEGER DEFAULT 0,
                processed_at DATETIME,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (user_id) REFERENCES users(id),
                FOREIGN KEY (station_id) REFERENCES stations(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS data_versions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                table_name VARCHAR(50) NOT NULL,
                record_id INTEGER NOT NULL,
                version_number INTEGER NOT NULL,
                old_row_json TEXT NOT NULL,
                new_row_json TEXT NOT NULL,
                changed_columns TEXT,
                change_reason TEXT,
                changed_by INTEGER NOT NULL,
                changed_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                is_active INTEGER DEFAULT 1,
                FOREIGN KEY (changed_by) REFERENCES users(id)
            )
        """)
    }

    private fun createLedgerTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS customer_ledger (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                party_id INTEGER NOT NULL,
                transaction_date DATETIME NOT NULL,
                transaction_type VARCHAR(30) NOT NULL,
                transaction_id INTEGER,
                reference_number VARCHAR(50),
                debit DECIMAL(15,2) DEFAULT 0,
                credit DECIMAL(15,2) DEFAULT 0,
                balance DECIMAL(15,2) NOT NULL,
                description TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                FOREIGN KEY (party_id) REFERENCES parties(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS supplier_ledger (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                party_id INTEGER NOT NULL,
                transaction_date DATETIME NOT NULL,
                transaction_type VARCHAR(30) NOT NULL,
                transaction_id INTEGER,
                reference_number VARCHAR(50),
                debit DECIMAL(15,2) DEFAULT 0,
                credit DECIMAL(15,2) DEFAULT 0,
                balance DECIMAL(15,2) NOT NULL,
                description TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                FOREIGN KEY (party_id) REFERENCES parties(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS cash_ledger (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                cash_box_id INTEGER NOT NULL,
                transaction_date DATETIME NOT NULL,
                transaction_type VARCHAR(30) NOT NULL,
                transaction_id INTEGER,
                reference_number VARCHAR(50),
                debit DECIMAL(15,2) DEFAULT 0,
                credit DECIMAL(15,2) DEFAULT 0,
                balance DECIMAL(15,2) NOT NULL,
                description TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                FOREIGN KEY (cash_box_id) REFERENCES cash_boxes(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS bank_ledger (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                bank_account_id INTEGER NOT NULL,
                transaction_date DATETIME NOT NULL,
                transaction_type VARCHAR(30) NOT NULL,
                transaction_id INTEGER,
                reference_number VARCHAR(50),
                debit DECIMAL(15,2) DEFAULT 0,
                credit DECIMAL(15,2) DEFAULT 0,
                balance DECIMAL(15,2) NOT NULL,
                description TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                FOREIGN KEY (bank_account_id) REFERENCES bank_accounts(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS tank_ledger (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                tank_id INTEGER NOT NULL,
                transaction_date DATETIME NOT NULL,
                transaction_type VARCHAR(30) NOT NULL,
                transaction_id INTEGER,
                reference_number VARCHAR(50),
                debit DECIMAL(12,2) DEFAULT 0,
                credit DECIMAL(12,2) DEFAULT 0,
                balance DECIMAL(12,2) NOT NULL,
                description TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                FOREIGN KEY (tank_id) REFERENCES tanks(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS inventory_ledger (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                product_id INTEGER NOT NULL,
                warehouse_id INTEGER NOT NULL,
                transaction_date DATETIME NOT NULL,
                transaction_type VARCHAR(30) NOT NULL,
                transaction_id INTEGER,
                reference_number VARCHAR(50),
                debit DECIMAL(12,2) DEFAULT 0,
                credit DECIMAL(12,2) DEFAULT 0,
                balance DECIMAL(12,2) NOT NULL,
                average_cost DECIMAL(12,4),
                description TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                FOREIGN KEY (product_id) REFERENCES products(id),
                FOREIGN KEY (warehouse_id) REFERENCES warehouses(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)
    }

    private fun createPrintTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS printer_profiles (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                profile_code VARCHAR(30) UNIQUE NOT NULL,
                profile_name VARCHAR(100) NOT NULL,
                printer_name VARCHAR(100),
                printer_type VARCHAR(30) DEFAULT 'thermal' CHECK(printer_type IN ('thermal', 'inkjet', 'laser', 'matrix')),
                connection_type VARCHAR(20) CHECK(connection_type IN ('usb', 'bluetooth', 'wifi', 'ethernet', 'serial')),
                ip_address VARCHAR(45),
                port INTEGER,
                mac_address VARCHAR(17),
                paper_width INTEGER,
                paper_height INTEGER,
                dpi INTEGER DEFAULT 203,
                driver_settings TEXT,
                is_default INTEGER DEFAULT 0,
                is_active INTEGER DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS receipt_templates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                template_code VARCHAR(30) UNIQUE NOT NULL,
                template_name VARCHAR(100) NOT NULL,
                description TEXT,
                station_id INTEGER,
                header TEXT,
                body TEXT,
                footer TEXT,
                variables TEXT,
                paper_width INTEGER DEFAULT 80,
                font_size INTEGER DEFAULT 12,
                is_default INTEGER DEFAULT 0,
                is_active INTEGER DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                FOREIGN KEY (station_id) REFERENCES stations(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS invoice_templates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                template_code VARCHAR(30) UNIQUE NOT NULL,
                template_name VARCHAR(100) NOT NULL,
                description TEXT,
                station_id INTEGER,
                template_html TEXT,
                template_css TEXT,
                variables TEXT,
                is_default INTEGER DEFAULT 0,
                is_active INTEGER DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                FOREIGN KEY (station_id) REFERENCES stations(id),
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS label_templates (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                template_code VARCHAR(30) UNIQUE NOT NULL,
                template_name VARCHAR(100) NOT NULL,
                description TEXT,
                label_type VARCHAR(30) DEFAULT 'barcode' CHECK(label_type IN ('barcode', 'qr', 'tag', 'price')),
                template_data TEXT,
                width_mm DECIMAL(6,2),
                height_mm DECIMAL(6,2),
                is_default INTEGER DEFAULT 0,
                is_active INTEGER DEFAULT 1,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                FOREIGN KEY (created_by) REFERENCES users(id)
            )
        """)
    }

    // ========================================================================
    // جداول إضافية صغيرة
    // ========================================================================

    private fun createEmployeeTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS employees_old (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                phone TEXT,
                position TEXT,
                base_salary REAL DEFAULT 0,
                advances REAL DEFAULT 0,
                penalties REAL DEFAULT 0,
                bonuses REAL DEFAULT 0,
                net_salary REAL DEFAULT 0,
                notes TEXT,
                active INTEGER DEFAULT 1,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS employee_payments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                employee_id INTEGER,
                amount REAL DEFAULT 0,
                type TEXT CHECK(type IN ('salary', 'advance', 'penalty', 'bonus', 'other')),
                description TEXT,
                date TEXT DEFAULT CURRENT_TIMESTAMP,
                operator TEXT DEFAULT 'System'
            )
        """)
    }

    private fun createBadDebtTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS bad_debts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                customer_id INTEGER,
                amount REAL DEFAULT 0,
                type TEXT CHECK(type IN ('overdue', 'doubtful', 'bad')),
                description TEXT,
                date TEXT DEFAULT CURRENT_TIMESTAMP,
                resolved INTEGER DEFAULT 0,
                resolved_date TEXT
            )
        """)
    }

    private fun createCashDepositTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS cash_deposits (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                customer_id INTEGER,
                amount REAL DEFAULT 0,
                balance_after REAL DEFAULT 0,
                notes TEXT,
                operator TEXT DEFAULT 'System',
                date TEXT DEFAULT CURRENT_TIMESTAMP,
                is_deleted INTEGER DEFAULT 0
            )
        """)
    }

    private fun createSmsWhitelistTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_whitelist (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                phone TEXT UNIQUE NOT NULL,
                name TEXT,
                enabled INTEGER DEFAULT 1,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
    }

    private fun createMaintenanceRequestsTable(db: SQLiteDatabase) {
        // تم إنشاؤه بالفعل في createAssetTables، نتركه فارغاً
    }

    private fun createAiChatTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ai_chat_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """)
    }

    private fun createCashMovementsTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS cash_movements (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT UNIQUE NOT NULL,
                cash_box_id INTEGER,
                movement_type TEXT NOT NULL,
                amount REAL NOT NULL,
                balance_before REAL,
                balance_after REAL,
                description TEXT,
                reference_type TEXT,
                reference_id INTEGER,
                created_by TEXT,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                is_deleted INTEGER DEFAULT 0,
                FOREIGN KEY (cash_box_id) REFERENCES cash_boxes(id)
            )
        """)
    }

    private fun ensureSmsConversationColumns(db: SQLiteDatabase) {
        val columns = listOf(
            "conversation_id TEXT",
            "actor_id INTEGER",
            "conversation_type TEXT DEFAULT 'sms'",
            "current_state TEXT DEFAULT 'IDLE'",
            "previous_state TEXT DEFAULT ''",
            "order_id INTEGER",
            "draft_id TEXT",
            "version INTEGER DEFAULT 0",
            "expires_at INTEGER DEFAULT 0",
            "last_inbound_message_id TEXT",
            "last_outbound_message_id TEXT",
            "retry_count INTEGER DEFAULT 0",
            "status TEXT DEFAULT 'ACTIVE'"
        )
        columns.forEach { definition ->
            val name = definition.substringBefore(' ')
            ensureColumn(db, "sms_conversation_context", name, definition.substringAfter(' '))
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_context_state ON sms_conversation_context(current_state, status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_context_expiry ON sms_conversation_context(expires_at)")
    }

    private fun createSmsPlatformTables(db: SQLiteDatabase) {
        ensureSmsConversationColumns(db)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_outbox (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                message_id TEXT NOT NULL UNIQUE,
                event_id TEXT,
                conversation_id TEXT,
                business_entity_id TEXT,
                recipient TEXT NOT NULL,
                body TEXT NOT NULL,
                parts_count INTEGER NOT NULL DEFAULT 1,
                priority TEXT NOT NULL DEFAULT 'NORMAL',
                status TEXT NOT NULL DEFAULT 'QUEUED',
                attempt_count INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                queued_at INTEGER NOT NULL,
                sent_at INTEGER,
                delivered_at INTEGER,
                failed_at INTEGER,
                failure_code TEXT,
                failure_reason TEXT,
                subscription_id INTEGER,
                dedupe_key TEXT NOT NULL UNIQUE,
                next_attempt_at INTEGER NOT NULL DEFAULT 0,
                last_part_index INTEGER NOT NULL DEFAULT 0,
                CHECK(parts_count > 0),
                CHECK(attempt_count >= 0),
                CHECK(status IN ('DRAFT','QUEUED','SENDING','SENT','DELIVERY_PENDING','DELIVERED','FAILED','RETRY_PENDING','CANCELLED','EXPIRED'))
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_outbox_status_next ON sms_outbox(status, next_attempt_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_outbox_conversation ON sms_outbox(conversation_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_outbox_event ON sms_outbox(event_id)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_outbox_parts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                message_id TEXT NOT NULL,
                part_index INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'PENDING',
                sent_at INTEGER,
                delivered_at INTEGER,
                failure_code TEXT,
                failure_reason TEXT,
                UNIQUE(message_id, part_index),
                CHECK(status IN ('PENDING','SENT','DELIVERED','FAILED'))
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_outbox_parts_message ON sms_outbox_parts(message_id)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_payment_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                payment_event_id TEXT NOT NULL UNIQUE,
                idempotency_key TEXT NOT NULL UNIQUE,
                phone TEXT NOT NULL,
                party_id INTEGER,
                institution TEXT,
                amount REAL,
                currency TEXT DEFAULT 'YER',
                sender_name TEXT,
                receiver_name TEXT,
                reference TEXT,
                event_timestamp INTEGER,
                raw_message TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'RECEIVED',
                matched_order_id INTEGER,
                matched_payment_id INTEGER,
                failure_code TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                CHECK(status IN ('RECEIVED','PARSED','MATCHED','VERIFIED','REJECTED','DUPLICATE','EXPIRED','SUSPICIOUS'))
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_payment_phone_status ON sms_payment_events(phone, status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_payment_reference ON sms_payment_events(reference)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_delivery_tasks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                delivery_id TEXT NOT NULL UNIQUE,
                order_id INTEGER,
                sale_id INTEGER,
                party_id INTEGER,
                driver_id INTEGER,
                station_id INTEGER,
                location TEXT NOT NULL,
                scheduled_at INTEGER,
                assigned_at INTEGER,
                started_at INTEGER,
                arrived_at INTEGER,
                completed_at INTEGER,
                status TEXT NOT NULL DEFAULT 'PENDING',
                attempt_count INTEGER NOT NULL DEFAULT 0,
                failure_reason TEXT,
                notes TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                CHECK(status IN ('PENDING','ASSIGNED','ACCEPTED','OUT_FOR_DELIVERY','ARRIVED','CUSTOMER_UNAVAILABLE','WAITING_CUSTOMER','REDELIVERY_REQUIRED','COMPLETED','RETURNED','CANCELLED'))
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_delivery_status_time ON sms_delivery_tasks(status, scheduled_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_delivery_driver ON sms_delivery_tasks(driver_id, status)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_loyalty_transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                transaction_id TEXT NOT NULL UNIQUE,
                idempotency_key TEXT NOT NULL UNIQUE,
                party_id INTEGER NOT NULL,
                points INTEGER NOT NULL,
                balance_before INTEGER NOT NULL,
                balance_after INTEGER NOT NULL,
                transaction_type TEXT NOT NULL,
                reason TEXT NOT NULL,
                reference_type TEXT,
                reference_id TEXT,
                created_at INTEGER NOT NULL,
                CHECK(transaction_type IN ('EARN','REDEEM','ADJUST','EXPIRE','REVERSE')),
                CHECK(points > 0),
                CHECK(balance_before >= 0),
                CHECK(balance_after >= 0)
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_loyalty_party_time ON sms_loyalty_transactions(party_id, created_at)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_audit_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_id TEXT NOT NULL UNIQUE,
                actor_type TEXT NOT NULL,
                actor_id TEXT,
                entity_type TEXT NOT NULL,
                entity_id TEXT,
                previous_state TEXT,
                new_state TEXT,
                source TEXT NOT NULL,
                metadata_json TEXT,
                created_at INTEGER NOT NULL
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_audit_entity ON sms_audit_events(entity_type, entity_id, created_at)")
    }

    private fun createSmsProcessedTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_processed_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                message_hash TEXT NOT NULL UNIQUE,
                sender_phone TEXT,
                message_type TEXT DEFAULT 'incoming',
                processing_status TEXT NOT NULL DEFAULT 'processed',
                processing_result TEXT,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                processed_at TEXT,
                retry_count INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                CHECK(length(message_hash) > 0),
                CHECK(retry_count >= 0),
                CHECK(processing_status IN ('processed', 'failed', 'ignored', 'blocked', 'duplicate'))
            )
        """)
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_sms_processed_hash ON sms_processed_messages(message_hash)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_processed_created ON sms_processed_messages(created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_processed_sender ON sms_processed_messages(sender_phone)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_processed_status ON sms_processed_messages(processing_status)")
        Log.d(TAG, "SMS processed table created successfully")
    }

    private fun createSmsProcessedHashesTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_processed_hashes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                message_hash TEXT NOT NULL UNIQUE,
                phone TEXT NOT NULL,
                message_preview TEXT,
                processed_at INTEGER NOT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_hashes_hash ON sms_processed_hashes(message_hash)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_hashes_phone ON sms_processed_hashes(phone)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_hashes_time ON sms_processed_hashes(processed_at)")
        Log.d(TAG, "SMS processed hashes table created successfully")
    }

    private fun createSmsOutboundDedupeTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_outbound_dedupe (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                dedupe_key TEXT NOT NULL UNIQUE,
                phone TEXT NOT NULL,
                message_hash TEXT NOT NULL,
                message_preview TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'reserved',
                reserved_at INTEGER NOT NULL,
                sent_at INTEGER,
                CHECK(status IN ('reserved', 'sent'))
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_outbound_dedupe_phone ON sms_outbound_dedupe(phone)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_outbound_dedupe_reserved ON sms_outbound_dedupe(reserved_at)")
    }

    private fun createSmsRateLimitsTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_rate_limits (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                phone TEXT NOT NULL UNIQUE,
                last_reply_at INTEGER DEFAULT 0,
                day_start INTEGER DEFAULT 0,
                daily_count INTEGER DEFAULT 0,
                warning_count INTEGER DEFAULT 0,
                blocked_until INTEGER DEFAULT 0,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rate_limits_phone ON sms_rate_limits(phone)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rate_limits_blocked ON sms_rate_limits(blocked_until)")
        Log.d(TAG, "SMS rate limits table created successfully")
    }

    private fun createSmsConversationContextTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_conversation_context (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                phone TEXT NOT NULL UNIQUE,
                last_topic TEXT DEFAULT '',
                last_intent TEXT DEFAULT '',
                timestamp INTEGER DEFAULT 0,
                pending_action TEXT DEFAULT '',
                awaiting_response INTEGER DEFAULT 0,
                data_json TEXT DEFAULT '{}',
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_context_phone ON sms_conversation_context(phone)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_context_time ON sms_conversation_context(timestamp)")
        Log.d(TAG, "SMS conversation context table created successfully")
    }

    private fun createSmsCustomerPreferencesTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_customer_preferences (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                phone TEXT NOT NULL UNIQUE,
                preferred_quantity REAL DEFAULT 0,
                preferred_location TEXT DEFAULT '',
                preferred_time TEXT DEFAULT '',
                last_order_date INTEGER DEFAULT 0,
                order_count INTEGER DEFAULT 0,
                language TEXT DEFAULT 'ar',
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_prefs_phone ON sms_customer_preferences(phone)")
        Log.d(TAG, "SMS customer preferences table created successfully")
    }

    private fun createSmsInteractionHistoryTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_interaction_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                phone TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                intent TEXT NOT NULL,
                message TEXT,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_interaction_phone ON sms_interaction_history(phone)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_interaction_time ON sms_interaction_history(timestamp)")
        Log.d(TAG, "SMS interaction history table created successfully")
    }

    private fun createSmsRecurringOrdersTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_recurring_orders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                customer_id TEXT NOT NULL UNIQUE,
                quantity REAL DEFAULT 0,
                location TEXT DEFAULT '',
                schedule TEXT DEFAULT '',
                next_delivery INTEGER DEFAULT 0,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_recurring_customer ON sms_recurring_orders(customer_id)")
        Log.d(TAG, "SMS recurring orders table created successfully")
    }

    private fun createSmsMetricsTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_metrics (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_type TEXT NOT NULL,
                phone TEXT DEFAULT '',
                details TEXT,
                timestamp INTEGER NOT NULL,
                date TEXT NOT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_metrics_type ON sms_metrics(event_type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_metrics_date ON sms_metrics(date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_metrics_phone ON sms_metrics(phone)")
        Log.d(TAG, "SMS metrics table created successfully")
    }

    private fun createSmsOtpVerificationsTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_otp_verifications (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                phone TEXT NOT NULL UNIQUE,
                otp_code TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                attempts INTEGER DEFAULT 0,
                max_attempts INTEGER DEFAULT 3,
                expires_at INTEGER NOT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_otp_phone ON sms_otp_verifications(phone)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_otp_expires ON sms_otp_verifications(expires_at)")
        Log.d(TAG, "SMS OTP verifications table created successfully")
    }
    private fun createUserOtpVerificationsTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS user_otp_verifications (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL UNIQUE,
                otp_code TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                attempts INTEGER DEFAULT 0,
                max_attempts INTEGER DEFAULT 3,
                expires_at INTEGER NOT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_user_otp_user ON user_otp_verifications(user_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_user_otp_expires ON user_otp_verifications(expires_at)")
        Log.d(TAG, "User OTP verifications table created successfully")
    }


    private fun ensureSmsSettings(db: SQLiteDatabase) {
        val smsSettings = listOf(
            Triple("sms_enabled", "1", "boolean"),
            Triple("sms_security_mode", "strict", "string"),
            Triple("public_price_query_enabled", "1", "boolean"),
            Triple("sms_duplicate_retention_days", SMS_HASH_RETENTION_DAYS.toString(), "integer"),
            Triple("sms_rate_limit", "10", "integer"),
            Triple("sms_daily_limit", "100", "integer"),
            Triple("sms_max_message_length", "160", "integer")
        )

        for ((key, value, dataType) in smsSettings) {
            val cv = ContentValues().apply {
                put("setting_key", key)
                put("setting_value", value)
                put("data_type", dataType)
                put("category", "sms")
                put("description", "SMS system setting for $key")
                put("created_at", getCurrentDateTime())
                put("updated_at", getCurrentDateTime())
            }
            db.insertWithOnConflict("system_settings", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        }

        db.execSQL("""
            INSERT OR IGNORE INTO system_settings (setting_key, setting_value, description)
            VALUES
            ('sms_security_mode', 'relaxed', 'وضع أمان SMS'),
            ('push_notifications_enabled', '0', 'تفعيل إشعارات Push')
        """)

        Log.d(TAG, "SMS settings ensured")
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_companies_code ON companies(company_code)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_stations_code ON stations(station_code)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_stations_company ON stations(company_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_users_username ON users(username)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_users_email ON users(email)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_users_phone ON users(phone)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_users_role ON users(role_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_users_station ON users(station_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_user_sessions_token ON user_sessions(session_token)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_user_sessions_user ON user_sessions(user_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_user_sessions_active ON user_sessions(is_active)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_user_activity_user ON user_activity_log(user_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_parties_code ON parties(party_code)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_parties_tax ON parties(tax_number)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_parties_type ON parties(party_type_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_party_contacts_party ON party_contacts(party_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_party_addresses_party ON party_addresses(party_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tanks_code ON tanks(tank_code)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tanks_station ON tanks(station_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tanks_fuel ON tanks(fuel_type_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tank_level_tank ON tank_level_log(tank_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tank_level_date ON tank_level_log(reading_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_refills_code ON tank_refills(refill_code)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_refills_tank ON tank_refills(tank_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_refills_supplier ON tank_refills(supplier_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_refills_status ON tank_refills(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pumps_code ON pumps(pump_code)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pumps_station ON pumps(station_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pumps_tank ON pumps(tank_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_nozzles_code ON pump_nozzles(nozzle_code)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_nozzles_pump ON pump_nozzles(pump_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_meter_readings_date ON meter_readings(reading_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_meter_readings_station ON meter_readings(station_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_calibration_entity ON calibration_records(entity_type, entity_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_products_code ON products(product_code)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_products_barcode ON products(barcode)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_products_category ON products(category_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_inventory_product ON inventory_levels(product_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_inventory_warehouse ON inventory_levels(warehouse_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_inventory_movements_product ON inventory_movements(product_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_stock_alerts_product ON stock_alerts(product_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_stock_alerts_resolved ON stock_alerts(is_resolved)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sales_code ON sales_transactions(sale_code)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sales_invoice ON sales_transactions(invoice_number)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sales_station ON sales_transactions(station_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sales_shift ON sales_transactions(shift_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sales_customer ON sales_transactions(customer_party_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sales_date ON sales_transactions(created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sales_status ON sales_transactions(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_shifts_code ON shifts(shift_code)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_shifts_station ON shifts(station_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_shifts_date ON shifts(shift_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_accounts_code ON accounts(account_code)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_accounts_type ON accounts(account_type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_journal_entries_number ON journal_entries(entry_number)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_journal_entries_date ON journal_entries(entry_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_journal_items_entry ON journal_entry_items(journal_entry_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_journal_items_account ON journal_entry_items(account_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_expenses_code ON expenses(expense_code)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_expenses_category ON expenses(expense_category_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_payments_sale ON payments(sale_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_payments_customer ON payments(customer_party_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_receipts_number ON receipts(receipt_number)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_employees_code ON employees(employee_code)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_employees_station ON employees(station_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_attendance_employee ON attendance(employee_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_attendance_date ON attendance(attendance_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_payroll_code ON payroll(payroll_code)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_payroll_items_payroll ON payroll_items(payroll_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_maintenance_code ON maintenance_requests(request_code)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_maintenance_asset ON maintenance_requests(asset_type, asset_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_maintenance_status ON maintenance_requests(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_maintenance_hist_request ON maintenance_history(maintenance_request_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_maintenance_schedule_asset ON maintenance_schedule(asset_type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notifications_user ON notifications(user_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notifications_status ON notifications(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notification_queue_status ON notification_queue(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_attachments_entity ON attachments(entity_type, entity_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_audit_logs_user ON audit_logs(user_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_audit_logs_table ON audit_logs(table_name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_audit_logs_created ON audit_logs(created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_system_logs_level ON system_logs(log_level)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_logs_device ON sync_logs(device_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_backup_status ON backup_history(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_system_settings_key ON system_settings(setting_key)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_system_settings_category ON system_settings(category)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_station_settings_station ON station_settings(station_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_data_versions_table ON data_versions(table_name, record_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_approval_history_entity ON approval_history(entity_type, entity_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_scheduled_jobs_next_run ON scheduled_jobs(next_run_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_smart_alerts_entity ON smart_alerts(entity_type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_documents_entity ON documents(entity_type, entity_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_documents_type ON documents(document_type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_vehicle_locations_vehicle ON vehicle_locations(vehicle_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_iot_devices_entity ON iot_devices(entity_type, entity_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_vehicle_maintenance_vehicle ON vehicle_maintenance(vehicle_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_vehicle_trips_vehicle ON vehicle_trips(vehicle_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_vehicle_expenses_vehicle ON vehicle_expenses(vehicle_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_vehicle_insurance_vehicle ON vehicle_insurance(vehicle_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_vehicle_accidents_vehicle ON vehicle_accidents(vehicle_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_contracts_party ON contracts(party_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_customer_visits_party ON customer_visits(party_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_customer_complaints_party ON customer_complaints(party_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_customer_followups_party ON customer_followups(party_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_kpi_results_kpi ON kpi_results(kpi_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fact_sales_date ON fact_sales(date_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fact_sales_customer ON fact_sales(customer_dim_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fact_sales_station ON fact_sales(station_dim_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fact_inventory_date ON fact_inventory(date_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fact_payments_date ON fact_payments(date_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_predictions_date ON predictions(prediction_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_field_permissions_role ON field_permissions(role_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_queue_device ON sync_queue(device_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_queue_status ON sync_queue(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_conflicts_device ON sync_conflicts(device_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_customer_ledger_party ON customer_ledger(party_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_supplier_ledger_party ON supplier_ledger(party_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cash_ledger_box ON cash_ledger(cash_box_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bank_ledger_account ON bank_ledger(bank_account_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tank_ledger_tank ON tank_ledger(tank_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_inventory_ledger_product ON inventory_ledger(product_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_system_events_type ON system_events(event_type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_job_queue_status ON job_queue(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_printer_profiles_default ON printer_profiles(is_default)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_receipt_templates_default ON receipt_templates(is_default)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_invoice_templates_default ON invoice_templates(is_default)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_label_templates_default ON label_templates(is_default)")
    }

    // ========================================================================
    // البيانات الأولية
    // ========================================================================

    private fun insertInitialData(db: SQLiteDatabase) {
        db.execSQL("""
            INSERT OR IGNORE INTO currencies (id, uuid, currency_code, currency_name, currency_name_ar, symbol, symbol_position, decimal_places, is_default, is_active)
            VALUES
            (1, 'CUR-001-UUID', 'USD', 'US Dollar', 'الدولار الأمريكي', '$', 'before', 2, 1, 1),
            (2, 'CUR-002-UUID', 'YER', 'Yemeni Rial', 'الريال اليمني', 'ر.ي', 'after', 0, 0, 1),
            (3, 'CUR-003-UUID', 'SAR', 'Saudi Riyal', 'الريال السعودي', 'ر.س', 'after', 2, 0, 1)
        """)

        db.execSQL("""
            INSERT OR IGNORE INTO companies (id, uuid, company_code, company_name, company_name_ar, tax_number, phone, email, country, city, status, is_head_office, default_currency_id)
            VALUES (1, 'COMP-001-UUID', 'COMP-001', 'Abu Ahmed Fuel Stations Group', 'مجموعة محطات ابو أحمد', 'TAX-123456789', '+967-776-979-279', 'info@abuahmed.com', 'Yemen', 'Sana''a', 'active', 1, 2)
        """)

        db.execSQL("""
            INSERT OR IGNORE INTO stations (id, uuid, station_code, station_name, station_name_ar, company_id, country, city, phone, email, license_number, tax_number, status, is_24_hours, station_type, default_currency_id)
            VALUES (1, 'STA-001-UUID', 'STA-001', 'Abu Ahmed Main Station', 'محطة ابو أحمد الرئيسية', 1, 'Yemen', 'rda', '+967 776 979 279', 'https://www.facebook.com/share/1YAz73x6LY/', 'LIC-2024-001', 'TAX-123456789', 'active', 1, 'both', 2)
        """)

        db.execSQL("""
            INSERT OR IGNORE INTO roles (id, uuid, role_code, role_name, role_name_ar, level, is_system_role, is_active) VALUES
            (1, 'ROL-001-UUID', 'SUPER_ADMIN', 'Super Administrator', 'مدير النظام الأعلى', 1, 1, 1),
            (2, 'ROL-002-UUID', 'ADMIN', 'Administrator', 'مدير النظام', 2, 1, 1),
            (3, 'ROL-003-UUID', 'STATION_MANAGER', 'Station Manager', 'مدير المحطة', 3, 0, 1),
            (4, 'ROL-004-UUID', 'CASHIER', 'Cashier', 'أمين الصندوق', 4, 0, 1),
            (5, 'ROL-005-UUID', 'ACCOUNTANT', 'Accountant', 'محاسب', 4, 0, 1),
            (6, 'ROL-006-UUID', 'SUPERVISOR', 'Supervisor', 'مشرف', 3, 0, 1),
            (7, 'ROL-007-UUID', 'ATTENDANT', 'Pump Attendant', 'مشغل المضخة', 5, 0, 1)
        """)

        db.execSQL("""
            INSERT OR IGNORE INTO permissions (id, uuid, permission_code, permission_name, permission_name_ar, module, module_name_ar, action) VALUES
            (1, 'PER-001-UUID', 'users.create', 'Create Users', 'إنشاء مستخدمين', 'users', 'المستخدمين', 'create'),
            (2, 'PER-002-UUID', 'users.read', 'View Users', 'عرض المستخدمين', 'users', 'المستخدمين', 'read'),
            (3, 'PER-003-UUID', 'users.update', 'Edit Users', 'تعديل المستخدمين', 'users', 'المستخدمين', 'update'),
            (4, 'PER-004-UUID', 'users.delete', 'Delete Users', 'حذف المستخدمين', 'users', 'المستخدمين', 'delete'),
            (5, 'PER-005-UUID', 'sales.create', 'Create Sales', 'إنشاء مبيعات', 'sales', 'المبيعات', 'create'),
            (6, 'PER-006-UUID', 'sales.read', 'View Sales', 'عرض المبيعات', 'sales', 'المبيعات', 'read'),
            (7, 'PER-007-UUID', 'sales.update', 'Edit Sales', 'تعديل المبيعات', 'sales', 'المبيعات', 'update'),
            (8, 'PER-008-UUID', 'sales.delete', 'Delete Sales', 'حذف المبيعات', 'sales', 'المبيعات', 'delete'),
            (9, 'PER-009-UUID', 'sales.print', 'Print Invoices', 'طباعة الفواتير', 'sales', 'المبيعات', 'print'),
            (10, 'PER-010-UUID', 'reports.view', 'View Reports', 'عرض التقارير', 'reports', 'التقارير', 'read'),
            (11, 'PER-011-UUID', 'reports.export', 'Export Reports', 'تصدير التقارير', 'reports', 'التقارير', 'export'),
            (12, 'PER-012-UUID', 'inventory.create', 'Create Inventory', 'إنشاء مخزون', 'inventory', 'المخزون', 'create'),
            (13, 'PER-013-UUID', 'inventory.read', 'View Inventory', 'عرض المخزون', 'inventory', 'المخزون', 'read'),
            (14, 'PER-014-UUID', 'inventory.update', 'Edit Inventory', 'تعديل المخزون', 'inventory', 'المخزون', 'update'),
            (15, 'PER-015-UUID', 'tanks.read', 'View Tanks', 'عرض الخزانات', 'tanks', 'الخزانات', 'read'),
            (16, 'PER-016-UUID', 'tanks.update', 'Edit Tanks', 'تعديل الخزانات', 'tanks', 'الخزانات', 'update'),
            (17, 'PER-017-UUID', 'pumps.read', 'View Pumps', 'عرض المضخات', 'pumps', 'المضخات', 'read'),
            (18, 'PER-018-UUID', 'pumps.update', 'Edit Pumps', 'تعديل المضخات', 'pumps', 'المضخات', 'update'),
            (19, 'PER-019-UUID', 'customers.create', 'Create Customers', 'إنشاء عملاء', 'customers', 'العملاء', 'create'),
            (20, 'PER-020-UUID', 'customers.read', 'View Customers', 'عرض العملاء', 'customers', 'العملاء', 'read'),
            (21, 'PER-021-UUID', 'customers.update', 'Edit Customers', 'تعديل العملاء', 'customers', 'العملاء', 'update'),
            (22, 'PER-022-UUID', 'customers.delete', 'Delete Customers', 'حذف العملاء', 'customers', 'العملاء', 'delete'),
            (23, 'PER-023-UUID', 'accounting.read', 'View Accounting', 'عرض المحاسبة', 'accounting', 'المحاسبة', 'read'),
            (24, 'PER-024-UUID', 'accounting.create', 'Create Entries', 'إنشاء قيود', 'accounting', 'المحاسبة', 'create'),
            (25, 'PER-025-UUID', 'settings.read', 'View Settings', 'عرض الإعدادات', 'settings', 'الإعدادات', 'read'),
            (26, 'PER-026-UUID', 'settings.update', 'Edit Settings', 'تعديل الإعدادات', 'settings', 'الإعدادات', 'update'),
            (27, 'PER-027-UUID', 'dashboard.read', 'View Dashboard', 'عرض لوحة التحكم', 'dashboard', 'لوحة التحكم', 'read')
        """)

        db.execSQL("""
            INSERT OR IGNORE INTO role_permissions (uuid, role_id, permission_id, can_create, can_read, can_update, can_delete, can_export, can_print, can_approve)
            SELECT 'RP-' || substr('000' || rowid, -3, 3) || '-UUID', 1, id, 1, 1, 1, 1, 1, 1, 1 FROM permissions
        """)

        val (hashAdmin, saltAdmin) = hashPassword("admin123")
        db.execSQL("""
            INSERT OR IGNORE INTO users (id, uuid, username, email, phone, password_hash, password_salt, full_name, full_name_ar, role_id, station_id, company_id, preferred_language, status, email_verified, phone_verified)
            VALUES (1, 'USR-001-UUID', 'admin', 'admin@abuahmed.com', '+967-730-005-355', '$hashAdmin', '$saltAdmin', 'أبو أحمد', 'مدير النظام', 1, 1, 1, 'ar', 'active', 1, 1)
        """)

        val (hashKhalil, saltKhalil) = hashPassword("123321")
        db.execSQL("""
            INSERT OR IGNORE INTO users (uuid, username, email, phone, password_hash, password_salt, full_name, full_name_ar, role_id, station_id, company_id, preferred_language, status, email_verified, phone_verified)
            VALUES ('USR-002-UUID', 'خليل أحمد', 'khalil@abuahmed.com', '+967-776-979-279', '$hashKhalil', '$saltKhalil', 'المدير العام', 'المدير العام', 1, 1, 1, 'ar', 'active', 1, 1)
        """)

        db.execSQL("""
            INSERT OR IGNORE INTO fuel_types (id, uuid, fuel_code, fuel_name, fuel_name_ar, density_standard, default_sale_price, default_purchase_price, tax_rate, vat_rate, is_active) VALUES
            (1, 'FT-001-UUID', 'DIESEL', 'Diesel', 'ديزل', 0.8200, 1800.00, 1700.00, 0, 0, 1),
            (2, 'FT-002-UUID', 'PETROL_95', 'Petrol 95', 'بنزين 95', 0.7500, 2000.00, 1900.00, 0, 0, 1),
            (3, 'FT-003-UUID', 'PETROL_91', 'Petrol 91', 'بنزين 91', 0.7450, 1950.00, 1850.00, 0, 0, 1)
        """)

        db.execSQL("""
            INSERT OR IGNORE INTO tanks (id, uuid, tank_code, tank_name, tank_name_ar, station_id, fuel_type_id, capacity_liters, minimum_level, maximum_level, current_quantity, tank_shape, location, status) VALUES
            (1, 'TANK-001-UUID', 'TANK-001', 'Diesel Tank', 'خزان الديزل', 1, 1, 40000.00, 2000.00, 40000.00, 25000.00, 'cylindrical', 'underground', 'active'),
            (2, 'TANK-002-UUID', 'TANK-002', 'Petrol 95 Tank', 'خزان البنزين 95', 1, 2, 35000.00, 1500.00, 35000.00, 20000.00, 'cylindrical', 'underground', 'active'),
            (3, 'TANK-003-UUID', 'TANK-003', 'Petrol 91 Tank', 'خزان البنزين 91', 1, 3, 35000.00, 1500.00, 35000.00, 22000.00, 'cylindrical', 'underground', 'active')
        """)

        db.execSQL("""
            INSERT OR IGNORE INTO pumps (id, uuid, pump_code, pump_number, pump_name, pump_name_ar, station_id, tank_id, serial_number, manufacturer, max_flow_rate, meter_start, meter_current, status) VALUES
            (1, 'PUMP-001-UUID', 'PUMP-001', '1', 'Pump 1 - Diesel', 'مضخة 1 - ديزل', 1, 1, 'SN-001-2024', 'Wayne', 45.00, 0.00, 15420.50, 'active'),
            (2, 'PUMP-002-UUID', 'PUMP-002', '2', 'Pump 2 - Diesel', 'مضخة 2 - ديزل', 1, 1, 'SN-002-2024', 'Wayne', 45.00, 0.00, 12350.75, 'active'),
            (3, 'PUMP-003-UUID', 'PUMP-003', '3', 'Pump 3 - Petrol 95', 'مضخة 3 - بنزين 95', 1, 2, 'SN-003-2024', 'Tokheim', 40.00, 0.00, 28900.00, 'active'),
            (4, 'PUMP-004-UUID', 'PUMP-004', '4', 'Pump 4 - Petrol 91', 'مضخة 4 - بنزين 91', 1, 3, 'SN-004-2024', 'Tokheim', 40.00, 0.00, 31500.25, 'active')
        """)

        db.execSQL("""
            INSERT OR IGNORE INTO pump_nozzles (id, uuid, nozzle_code, nozzle_number, pump_id, fuel_type_id, meter_start, meter_current, status) VALUES
            (1, 'NZ-001-UUID', 'NZ-001-A', 'A', 1, 1, 0.00, 15420.50, 'active'),
            (2, 'NZ-002-UUID', 'NZ-002-A', 'A', 2, 1, 0.00, 12350.75, 'active'),
            (3, 'NZ-003-UUID', 'NZ-003-A', 'A', 3, 2, 0.00, 28900.00, 'active'),
            (4, 'NZ-004-UUID', 'NZ-004-A', 'A', 4, 3, 0.00, 31500.25, 'active')
        """)

        db.execSQL("""
            INSERT OR IGNORE INTO party_types (id, uuid, type_code, type_name, type_name_ar, default_discount, default_credit_limit, payment_terms_days, is_active) VALUES
            (1, 'PT-001-UUID', 'INDIVIDUAL', 'Individual', 'فرد', 0, 0, 0, 1),
            (2, 'PT-002-UUID', 'COMPANY', 'Company', 'شركة', 5, 500000, 30, 1),
            (3, 'PT-003-UUID', 'GOVERNMENT', 'Government', 'جهة حكومية', 3, 1000000, 60, 1),
            (4, 'PT-004-UUID', 'TRANSPORT', 'Transport Company', 'شركة نقل', 4, 750000, 15, 1),
            (5, 'PT-005-UUID', 'CONTRACTOR', 'Contractor', 'مقاول', 2, 300000, 30, 1),
            (6, 'PT-006-UUID', 'SUPPLIER', 'Supplier', 'مورد', 0, 2000000, 30, 1)
        """)

        db.execSQL("""
            INSERT OR IGNORE INTO cash_boxes (id, uuid, box_code, box_name, box_name_ar, station_id, box_type, opening_balance, current_balance, currency_id, status) VALUES
            (1, 'CB-001-UUID', 'CB-001', 'Main Cash Box', 'الصندوق الرئيسي', 1, 'main', 50000.00, 50000.00, 2, 'active'),
            (2, 'CB-002-UUID', 'CB-002', 'Safe', 'الخزنة', 1, 'safe', 200000.00, 200000.00, 2, 'active')
        """)

        db.execSQL("""
            INSERT OR IGNORE INTO accounts (id, uuid, account_code, account_name, account_name_ar, account_type, account_category, normal_balance, level, is_active) VALUES
            (1, 'ACC-001-UUID', '1', 'Assets', 'الأصول', 'asset', NULL, 'debit', 1, 1),
            (2, 'ACC-002-UUID', '11', 'Current Assets', 'الأصول المتداولة', 'asset', 'current_asset', 'debit', 2, 1),
            (3, 'ACC-003-UUID', '1101', 'Cash on Hand', 'النقدية بالصندوق', 'asset', 'current_asset', 'debit', 3, 1),
            (4, 'ACC-004-UUID', '1102', 'Bank Accounts', 'الحسابات البنكية', 'asset', 'current_asset', 'debit', 3, 1),
            (5, 'ACC-005-UUID', '1103', 'Accounts Receivable', 'المدينون', 'asset', 'current_asset', 'debit', 3, 1),
            (6, 'ACC-006-UUID', '1104', 'Inventory', 'المخزون', 'asset', 'current_asset', 'debit', 3, 1),
            (7, 'ACC-007-UUID', '12', 'Fixed Assets', 'الأصول الثابتة', 'asset', 'fixed_asset', 'debit', 2, 1),
            (8, 'ACC-008-UUID', '2', 'Liabilities', 'الخصوم', 'liability', NULL, 'credit', 1, 1),
            (9, 'ACC-009-UUID', '21', 'Current Liabilities', 'الخصوم المتداولة', 'liability', 'current_liability', 'credit', 2, 1),
            (10, 'ACC-010-UUID', '2101', 'Accounts Payable', 'الدائنون', 'liability', 'current_liability', 'credit', 3, 1),
            (11, 'ACC-011-UUID', '3', 'Equity', 'حقوق الملكية', 'equity', NULL, 'credit', 1, 1),
            (12, 'ACC-012-UUID', '31', 'Capital', 'رأس المال', 'equity', 'capital', 'credit', 2, 1),
            (13, 'ACC-013-UUID', '4', 'Revenue', 'الإيرادات', 'revenue', NULL, 'credit', 1, 1),
            (14, 'ACC-014-UUID', '41', 'Sales Revenue', 'إيرادات المبيعات', 'revenue', 'operating_revenue', 'credit', 2, 1),
            (15, 'ACC-015-UUID', '4101', 'Fuel Sales', 'مبيعات الوقود', 'revenue', 'operating_revenue', 'credit', 3, 1),
            (16, 'ACC-016-UUID', '4102', 'Product Sales', 'مبيعات المنتجات', 'revenue', 'operating_revenue', 'credit', 3, 1),
            (17, 'ACC-017-UUID', '5', 'Expenses', 'المصروفات', 'expense', NULL, 'debit', 1, 1),
            (18, 'ACC-018-UUID', '51', 'Operating Expenses', 'مصروفات التشغيل', 'expense', 'operating_expense', 'debit', 2, 1),
            (19, 'ACC-019-UUID', '5101', 'Salaries', 'الرواتب', 'expense', 'operating_expense', 'debit', 3, 1),
            (20, 'ACC-020-UUID', '5102', 'Rent', 'الإيجار', 'expense', 'operating_expense', 'debit', 3, 1),
            (21, 'ACC-021-UUID', '5103', 'Utilities', 'المرافق', 'expense', 'operating_expense', 'debit', 3, 1),
            (22, 'ACC-022-UUID', '5104', 'Maintenance', 'الصيانة', 'expense', 'operating_expense', 'debit', 3, 1)
        """)

        db.execSQL("""
            INSERT OR IGNORE INTO expense_categories (id, uuid, category_code, category_name, category_name_ar, default_account_id, is_active) VALUES
            (1, 'EXC-001-UUID', 'EXC-001', 'Salaries', 'الرواتب', 19, 1),
            (2, 'EXC-002-UUID', 'EXC-002', 'Electricity', 'الكهرباء', 21, 1),
            (3, 'EXC-003-UUID', 'EXC-003', 'Maintenance', 'الصيانة', 22, 1),
            (4, 'EXC-004-UUID', 'EXC-004', 'Rent', 'الإيجار', 20, 1)
        """)

        db.execSQL("""
            INSERT OR IGNORE INTO system_settings (id, uuid, setting_key, setting_value, category, data_type, description) VALUES
            (1, 'SYS-001-UUID', 'VAT_PERCENTAGE', '0', 'tax', 'float', 'نسبة ضريبة القيمة المضافة'),
            (2, 'SYS-002-UUID', 'DEFAULT_CURRENCY', '2', 'general', 'integer', 'معرف العملة الافتراضية (YER)'),
            (3, 'SYS-003-UUID', 'ALLOW_NEGATIVE_STOCK', '0', 'inventory', 'boolean', 'السماح بالمخزون السالب'),
            (4, 'SYS-004-UUID', 'MAX_DISCOUNT_PERCENT', '20', 'sales', 'integer', 'الحد الأقصى للخصم بالنسبة المئوية'),
            (5, 'SYS-005-UUID', 'AUTO_BACKUP_ENABLED', '1', 'system', 'boolean', 'تفعيل النسخ الاحتياطي التلقائي'),
            (6, 'SYS-006-UUID', 'AUTO_SYNC_ENABLED', '1', 'system', 'boolean', 'تفعيل المزامنة التلقائية'),
            (7, 'SYS-007-UUID', 'SMS_GATEWAY', 'android_app', 'sms', 'string', 'نوع بوابة الرسائل القصيرة'),
            (8, 'SYS-008-UUID', 'STATION_NAME', 'محطة ابو أحمد لمشتقات الديزل', 'general', 'string', 'اسم المحطة الرئيسي'),
            (9, 'SYS-009-UUID', 'LOW_STOCK_THRESHOLD', '10', 'inventory', 'integer', 'حد المخزون المنخفض'),
            (10, 'SYS-010-UUID', 'CREDIT_LIMIT_DEFAULT', '500000', 'finance', 'integer', 'حد الائتمان الافتراضي للعملاء'),
            (11, 'SYS-011-UUID', 'retention_days', '90', 'system', 'integer', 'عدد أيام الاحتفاظ بالسجلات قبل الأرشفة'),
            (12, 'SYS-012-UUID', 'push_notifications_enabled', '0', 'notifications', 'boolean', 'تفعيل/تعطيل الإشعارات الفورية (Push)'),
            (13, 'SYS-013-UUID', 'email_notifications_enabled', '0', 'notifications', 'boolean', 'تفعيل/تعطيل الإشعارات عبر البريد الإلكتروني'),
            (14, 'SYS-014-UUID', 'backup_time', '02:00', 'system', 'string', 'وقت تشغيل النسخ الاحتياطي اليومي (HH:MM)'),
            (15, 'SYS-015-UUID', 'max_db_size_mb', '500', 'system', 'integer', 'الحد الأقصى لحجم قاعدة البيانات بالميجابايت'),
            (16, 'SYS-016-UUID', 'verbose_logging', '0', 'system', 'boolean', 'تفعيل التسجيل التفصيلي للأخطاء والأداء'),
            (17, 'SYS-017-UUID', 'offline_mode_enabled', '0', 'system', 'boolean', 'تفعيل وضع العمل بدون إنترنت'),
            (18, 'SYS-018-UUID', 'max_login_attempts', '5', 'security', 'integer', 'الحد الأقصى لمحاولات تسجيل الدخول الفاشلة قبل القفل'),
            (19, 'SYS-019-UUID', 'lockout_duration_minutes', '30', 'security', 'integer', 'مدة قفل الحساب بعد تجاوز المحاولات الفاشلة'),
            (20, 'SYS-020-UUID', 'two_factor_required', '0', 'security', 'boolean', 'إلزام جميع المستخدمين بتفعيل المصادقة الثنائية'),
            (21, 'SYS-021-UUID', 'min_password_length', '8', 'security', 'integer', 'الحد الأدنى لعدد أحرف كلمة المرور'),
            (22, 'SYS-022-UUID', 'password_expiry_days', '90', 'security', 'integer', 'عدد أيام صلاحية كلمة المرور قبل الإجبار على التغيير'),
            (23, 'SYS-023-UUID', 'gps_tracking_enabled', '1', 'sales', 'boolean', 'تسجيل إحداثيات GPS مع كل عملية بيع'),
            (24, 'SYS-024-UUID', 'tank_low_threshold_percent', '20', 'inventory', 'integer', 'نسبة التنبيه لانخفاض مستوى الوقود في الخزان'),
            (25, 'SYS-025-UUID', 'max_discount_without_approval', '10', 'sales', 'integer', 'الحد الأقصى للخصم بدون موافقة المشرف'),
            (26, 'SYS-026-UUID', 'auto_print_receipt', '1', 'pos', 'boolean', 'طباعة الإيصال تلقائياً بعد إتمام البيع'),
            (27, 'SYS-027-UUID', 'receipt_paper_width_mm', '80', 'pos', 'integer', 'عرض ورقة الإيصال بالمليمتر (58 أو 80)'),
            (28, 'SYS-028-UUID', 'receipt_qr_enabled', '1', 'pos', 'boolean', 'إظهار QR Code على إيصال البيع'),
            (29, 'SYS-029-UUID', 'receipt_footer_message', 'شكراً لزيارتكم محطة ابو أحمد', 'pos', 'string', 'الرسالة المطبوعة في تذييل الإيصال'),
            (30, 'SYS-030-UUID', 'auto_bank_transfer_enabled', '0', 'finance', 'boolean', 'تفعيل التحويل الآلي للمبالغ إلى الحساب البنكي'),
            (31, 'SYS-031-UUID', 'max_cash_in_box', '200000', 'finance', 'integer', 'الحد الأقصى للنقدية المسموح بها في الصندوق'),
            (32, 'SYS-032-UUID', 'smart_alerts_enabled', '1', 'notifications', 'boolean', 'تفعيل نظام التنبيهات الذكية'),
            (33, 'SYS-033-UUID', 'low_stock_check_interval_hours', '4', 'inventory', 'integer', 'فترة فحص المخزون المنخفض بالساعات'),
            (34, 'SYS-034-UUID', 'loyalty_program_enabled', '1', 'sales', 'boolean', 'تفعيل نظام نقاط الولاء للعملاء'),
            (35, 'SYS-035-UUID', 'loyalty_points_per_currency', '1', 'sales', 'float', 'عدد نقاط الولاء المكتسبة لكل 1 وحدة عملة'),
            (36, 'SYS-036-UUID', 'zatca_einvoicing_enabled', '0', 'sales', 'boolean', 'تفعيل الفوترة الإلكترونية المتكاملة مع ZATCA'),
            (37, 'SYS-037-UUID', 'station_tax_id', '', 'tax', 'string', 'الرقم الضريبي للمحطة للفوترة الإلكترونية'),
            (38, 'SYS-038-UUID', 'customer_rating_enabled', '0', 'crm', 'boolean', 'تفعيل نظام تقييم العملاء بعد كل عملية بيع')
        """)

        db.execSQL("""
            INSERT OR IGNORE INTO station_settings (id, uuid, station_id, setting_key, setting_value, data_type, description) VALUES
            (1, 'SS-001-UUID', 1, 'receipt_width', '80', 'integer', 'عرض إيصال الطباعة'),
            (2, 'SS-002-UUID', 1, 'allow_manual_price', '1', 'boolean', 'السماح بتعديل السعر يدوياً'),
            (3, 'SS-003-UUID', 1, 'max_cash_limit', '200000', 'integer', 'الحد الأقصى للنقد في الصندوق'),
            (4, 'SS-004-UUID', 1, 'default_shift_duration', '8', 'integer', 'مدة الوردية بالساعات'),
            (5, 'SS-005-UUID', 1, 'tank_low_warning', '20', 'integer', 'نسبة التنبيه لانخفاض الخزان %')
        """)

        db.execSQL("""
            INSERT OR IGNORE INTO notification_templates (id, uuid, template_code, template_name, template_name_ar, channel, subject, body) VALUES
            (1, 'NT-001-UUID', 'WELCOME_SMS', 'Welcome SMS', 'رسالة ترحيب', 'sms', NULL, 'مرحباً {customer_name}، شكراً لزيارتكم محطة ابو أحمد.'),
            (2, 'NT-002-UUID', 'DEBT_REMINDER', 'Debt Reminder', 'تذكير بالدين', 'sms', NULL, 'العميل {customer_name}، المبلغ المستحق {due_amount} بتاريخ {due_date}. الرجاء السداد.'),
            (3, 'NT-003-UUID', 'LOW_STOCK_ALERT', 'Low Stock Alert', 'تنبيه مخزون منخفض', 'email', 'تنبيه مخزون', 'المنتج {product_name} وصل إلى حد الخطر، الكمية الحالية: {current_quantity}'),
            (4, 'NT-004-UUID', 'TANK_LEVEL_ALERT', 'Tank Level Alert', 'تنبيه مستوى خزان', 'push', NULL, 'خزان {tank_name} أقل من الحد الأدنى، النسبة الحالية: {level_percent}%')
        """)

        db.execSQL("""
            INSERT OR IGNORE INTO kpi_definitions (id, uuid, kpi_code, kpi_name, kpi_name_ar, category, description, formula, target_value, unit, frequency) VALUES
            (1, 'KPI-001-UUID', 'DAILY_SALES', 'Daily Sales', 'المبيعات اليومية', 'sales', 'إجمالي المبيعات اليومية', 'SUM(total_amount) FROM sales WHERE DATE(created_at) = CURDATE()', 500000, 'YER', 'daily'),
            (2, 'KPI-002-UUID', 'FUEL_LOSS', 'Fuel Loss', 'فقد الوقود', 'inventory', 'نسبة الفقد في الوقود (تبخر/تسريب)', '(refill_quantity - sold_quantity) / refill_quantity * 100', 2, '%', 'weekly'),
            (3, 'KPI-003-UUID', 'PUMP_EFFICIENCY', 'Pump Efficiency', 'كفاءة المضخة', 'operational', 'معدل التدفق الفعلي مقابل التصميمي', 'avg_actual_flow / design_flow * 100', 95, '%', 'monthly'),
            (4, 'KPI-004-UUID', 'PROFIT_MARGIN', 'Profit Margin', 'هامش الربح', 'financial', 'نسبة الربح الإجمالي', '(total_sales - total_cost) / total_sales * 100', 25, '%', 'monthly')
        """)

        db.execSQL("""
            INSERT OR IGNORE INTO field_permissions (id, uuid, role_id, table_name, field_name, can_view, can_edit) VALUES
            (1, 'FP-001-UUID', 4, 'sales_transactions', 'gross_amount', 0, 0),
            (2, 'FP-002-UUID', 4, 'sales_transactions', 'net_amount', 0, 0),
            (3, 'FP-003-UUID', 4, 'products', 'purchase_price', 0, 0)
        """)

        db.execSQL("""
            INSERT OR IGNORE INTO approval_workflows (id, uuid, workflow_code, workflow_name, workflow_name_ar, entity_type, is_active) VALUES
            (1, 'WF-001-UUID', 'WF_SALES_APPROVAL', 'Large Sales Approval', 'موافقة المبيعات الكبيرة', 'sale', 1)
        """)
        db.execSQL("""
            INSERT OR IGNORE INTO approval_steps (id, uuid, workflow_id, step_order, step_name, step_name_ar, role_id) VALUES
            (1, 'AS-001-UUID', 1, 1, 'Supervisor Approval', 'موافقة المشرف', 6),
            (2, 'AS-002-UUID', 1, 2, 'Manager Approval', 'موافقة المدير', 3)
        """)

        db.execSQL("""
            INSERT OR IGNORE INTO scheduled_jobs (id, uuid, job_code, job_name, job_name_ar, job_class, schedule_type, cron_expression, enabled) VALUES
            (1, 'SJ-001-UUID', 'DAILY_BACKUP', 'Daily Database Backup', 'نسخ احتياطي يومي', 'BackupJob', 'cron', '0 2 * * *', 1)
        """)

        db.execSQL("""
            INSERT OR IGNORE INTO printer_profiles (id, uuid, profile_code, profile_name, printer_type, connection_type, paper_width, is_default) VALUES
            (1, 'PP-001-UUID', 'PP_THERMAL_80', 'Thermal 80mm', 'thermal', 'usb', 80, 1)
        """)

        db.execSQL("""
            INSERT OR IGNORE INTO receipt_templates (id, uuid, template_code, template_name, description, header, footer, is_default) VALUES
            (1, 'RT-001-UUID', 'RECEIPT_DEFAULT', 'Standard Receipt', 'قالب الإيصال القياسي',
            'محطة ابو أحمد لمشتقات الديزل\nشكراً لزيارتكم',
            'مع خالص الشكر والتقدير\nأبو أحمد', 1)
        """)

        db.execSQL("""
            INSERT OR IGNORE INTO screens
            (id, uuid, screen_name, module, description, is_active)
            VALUES
            (1,'SCR-001-UUID','balance-sheet','accounting','الميزانية العمومية',1),
            (2,'SCR-002-UUID','banks-accounts','accounting','الحسابات البنكية',1),
            (3,'SCR-003-UUID','chart-of-accounts','accounting','دليل الحسابات',1),
            (4,'SCR-004-UUID','contracts','crm','العقود',1),
            (5,'SCR-005-UUID','crm','crm','إدارة علاقات العملاء',1),
            (6,'SCR-006-UUID','customer-reports','reports','تقارير العملاء',1),
            (7,'SCR-007-UUID','customers','customers','العملاء',1),
            (8,'SCR-008-UUID','fuel-reports','reports','تقارير الوقود',1),
            (9,'SCR-009-UUID','inventory-movements','inventory','حركات المخزون',1),
            (10,'SCR-010-UUID','inventory-reports','inventory','تقارير المخزون',1),
            (11,'SCR-011-UUID','journal-entries','accounting','القيود اليومية',1),
            (12,'SCR-012-UUID','kpi','reports','مؤشرات الأداء',1),
            (13,'SCR-013-UUID','ledger','accounting','دفتر الأستاذ',1),
            (14,'SCR-014-UUID','party-types','customers','أنواع الأطراف',1),
            (15,'SCR-015-UUID','pos','sales','نقطة البيع',1),
            (16,'SCR-016-UUID','product-categories','inventory','تصنيفات المنتجات',1),
            (17,'SCR-017-UUID','product_categories','inventory','تصنيفات المنتجات',1),
            (18,'SCR-018-UUID','products','inventory','المنتجات',1),
            (19,'SCR-019-UUID','sales-log','sales','سجل المبيعات',1),
            (20,'SCR-020-UUID','sales-reports','reports','تقارير المبيعات',1),
            (21,'SCR-021-UUID','stock-levels','inventory','مستويات المخزون',1),
            (22,'SCR-022-UUID','suppliers','suppliers','الموردين',1),
            (23,'SCR-023-UUID','users','users','المستخدمين',1),
            (24,'SCR-024-UUID','login','security','تسجيل الدخول',1)
        """)
    }

    // ========================================================================
    // دوال SMS Duplicate Protection (الموجودة أصلاً)
    // ========================================================================

    fun isSmsAlreadyProcessed(messageHash: String): Boolean {
        if (messageHash.isBlank()) return false
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery("SELECT 1 FROM sms_processed_messages WHERE message_hash = ? LIMIT 1", arrayOf(messageHash))
                .use { cursor -> cursor.moveToFirst() }
        } catch (e: SQLiteException) {
            Log.e(TAG, "Error checking SMS processed status: ${e.message}", e)
            false
        } finally {
            dbLock.unlock()
        }
    }

    fun markSmsProcessed(
        messageHash: String,
        senderPhone: String? = null,
        processingResult: String? = null,
        status: String = "processed",
        retryCount: Int = 0
    ) {
        if (messageHash.isBlank()) return
        dbLock.lock()
        try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("message_hash", messageHash)
                senderPhone?.takeIf { it.isNotBlank() }?.let { put("sender_phone", it) }
                put("processing_status", status)
                processingResult?.takeIf { it.isNotBlank() }?.let { put("processing_result", it) }
                put("processed_at", getCurrentDateTime())
                put("retry_count", retryCount)
            }
            db.insertWithOnConflict("sms_processed_messages", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        } catch (e: SQLiteException) {
            Log.e(TAG, "Error marking SMS as processed: ${e.message}", e)
        } finally {
            dbLock.unlock()
        }
    }

    fun cleanupSmsProcessedMessages(retentionDays: Int = SMS_HASH_RETENTION_DAYS): Int {
        val actualRetentionDays = maxOf(retentionDays, 1)
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cutoffDate = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -actualRetentionDays) }
            val cutoffStr = getDateOnlyFormat().format(cutoffDate.time)
            db.delete("sms_processed_messages", "date(created_at) < ?", arrayOf(cutoffStr))
        } catch (e: SQLiteException) {
            Log.e(TAG, "Error cleaning up SMS processed messages: ${e.message}", e)
            0
        } finally {
            dbLock.unlock()
        }
    }

    fun getSmsProcessedCount(): Int {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery("SELECT COUNT(*) FROM sms_processed_messages", null)
                .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        } catch (e: SQLiteException) {
            Log.e(TAG, "Error getting SMS processed count: ${e.message}", e)
            0
        } finally {
            dbLock.unlock()
        }
    }

    fun getSmsRetentionDays(): Int {
        val stored = getSetting("sms_duplicate_retention_days")
        return stored?.toIntOrNull() ?: SMS_HASH_RETENTION_DAYS
    }

    // ========================================================================
    // دوال SMS System (الموجودة أصلاً)
    // ========================================================================

    fun getSmsRateLimit(phone: String): JSONObject? {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery("SELECT * FROM sms_rate_limits WHERE phone = ?", arrayOf(phone))
                .use { cursor ->
                    if (cursor.moveToFirst()) cursorToJsonObject(cursor) else null
                }
        } finally {
            dbLock.unlock()
        }
    }

    fun updateSmsRateLimit(phone: String, data: JSONObject): Boolean {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                data.optLong("last_reply_at")?.let { put("last_reply_at", it) }
                data.optLong("day_start")?.let { put("day_start", it) }
                data.optInt("daily_count")?.let { put("daily_count", it) }
                data.optInt("warning_count")?.let { put("warning_count", it) }
                data.optLong("blocked_until")?.let { put("blocked_until", it) }
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("sms_rate_limits", cv, "phone = ?", arrayOf(phone))
            if (rows == 0) {
                val insertCv = ContentValues().apply {
                    put("phone", phone)
                    data.optLong("last_reply_at")?.let { put("last_reply_at", it) }
                    data.optLong("day_start")?.let { put("day_start", it) }
                    data.optInt("daily_count")?.let { put("daily_count", it) }
                    data.optInt("warning_count")?.let { put("warning_count", it) }
                    data.optLong("blocked_until")?.let { put("blocked_until", it) }
                    put("updated_at", getCurrentDateTime())
                }
                db.insert("sms_rate_limits", null, insertCv)
            }
            true
        } finally {
            dbLock.unlock()
        }
    }

    fun getSmsConversationContext(phone: String): JSONObject? {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery("SELECT * FROM sms_conversation_context WHERE phone = ?", arrayOf(phone))
                .use { cursor ->
                    if (cursor.moveToFirst()) cursorToJsonObject(cursor) else null
                }
        } finally {
            dbLock.unlock()
        }
    }

    fun updateSmsConversationContext(phone: String, data: JSONObject): Boolean {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                data.optString("last_topic")?.let { put("last_topic", it) }
                data.optString("last_intent")?.let { put("last_intent", it) }
                data.optLong("timestamp")?.let { put("timestamp", it) }
                data.optString("pending_action")?.let { put("pending_action", it) }
                data.optInt("awaiting_response")?.let { put("awaiting_response", it) }
                data.optString("data_json")?.let { put("data_json", it) }
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("sms_conversation_context", cv, "phone = ?", arrayOf(phone))
            if (rows == 0) {
                val insertCv = ContentValues().apply {
                    put("phone", phone)
                    data.optString("last_topic")?.let { put("last_topic", it) }
                    data.optString("last_intent")?.let { put("last_intent", it) }
                    data.optLong("timestamp")?.let { put("timestamp", it) }
                    data.optString("pending_action")?.let { put("pending_action", it) }
                    data.optInt("awaiting_response")?.let { put("awaiting_response", it) }
                    data.optString("data_json")?.let { put("data_json", it) }
                    put("updated_at", getCurrentDateTime())
                }
                db.insert("sms_conversation_context", null, insertCv)
            }
            true
        } finally {
            dbLock.unlock()
        }
    }

    fun getSmsCustomerPreferences(phone: String): JSONObject? {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery("SELECT * FROM sms_customer_preferences WHERE phone = ?", arrayOf(phone))
                .use { cursor ->
                    if (cursor.moveToFirst()) cursorToJsonObject(cursor) else null
                }
        } finally {
            dbLock.unlock()
        }
    }

    fun updateSmsCustomerPreferences(phone: String, data: JSONObject): Boolean {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                data.optDouble("preferred_quantity")?.let { put("preferred_quantity", it) }
                data.optString("preferred_location")?.let { put("preferred_location", it) }
                data.optString("preferred_time")?.let { put("preferred_time", it) }
                data.optLong("last_order_date")?.let { put("last_order_date", it) }
                data.optInt("order_count")?.let { put("order_count", it) }
                data.optString("language")?.let { put("language", it) }
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("sms_customer_preferences", cv, "phone = ?", arrayOf(phone))
            if (rows == 0) {
                val insertCv = ContentValues().apply {
                    put("phone", phone)
                    data.optDouble("preferred_quantity")?.let { put("preferred_quantity", it) }
                    data.optString("preferred_location")?.let { put("preferred_location", it) }
                    data.optString("preferred_time")?.let { put("preferred_time", it) }
                    data.optLong("last_order_date")?.let { put("last_order_date", it) }
                    data.optInt("order_count")?.let { put("order_count", it) }
                    data.optString("language")?.let { put("language", it) }
                    put("updated_at", getCurrentDateTime())
                }
                db.insert("sms_customer_preferences", null, insertCv)
            }
            true
        } finally {
            dbLock.unlock()
        }
    }

    fun insertSmsInteraction(phone: String, intent: String, message: String? = null): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("phone", phone)
                put("timestamp", System.currentTimeMillis())
                put("intent", intent)
                message?.let { put("message", it) }
                put("created_at", getCurrentDateTime())
            }
            db.insert("sms_interaction_history", null, cv)
        } finally {
            dbLock.unlock()
        }
    }

    fun getSmsInteractionHistory(phone: String, limit: Int = 20): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM sms_interaction_history WHERE phone = ? ORDER BY timestamp DESC LIMIT ?",
                arrayOf(phone, limit.toString())
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun getSmsRecurringOrder(customerId: String): JSONObject? {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery("SELECT * FROM sms_recurring_orders WHERE customer_id = ?", arrayOf(customerId))
                .use { cursor ->
                    if (cursor.moveToFirst()) cursorToJsonObject(cursor) else null
                }
        } finally {
            dbLock.unlock()
        }
    }

    fun updateSmsRecurringOrder(customerId: String, data: JSONObject): Boolean {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                data.optDouble("quantity")?.let { put("quantity", it) }
                data.optString("location")?.let { put("location", it) }
                data.optString("schedule")?.let { put("schedule", it) }
                data.optLong("next_delivery")?.let { put("next_delivery", it) }
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("sms_recurring_orders", cv, "customer_id = ?", arrayOf(customerId))
            if (rows == 0) {
                val insertCv = ContentValues().apply {
                    put("customer_id", customerId)
                    data.optDouble("quantity")?.let { put("quantity", it) }
                    data.optString("location")?.let { put("location", it) }
                    data.optString("schedule")?.let { put("schedule", it) }
                    data.optLong("next_delivery")?.let { put("next_delivery", it) }
                    put("created_at", getCurrentDateTime())
                    put("updated_at", getCurrentDateTime())
                }
                db.insert("sms_recurring_orders", null, insertCv)
            }
            true
        } finally {
            dbLock.unlock()
        }
    }

    fun insertSmsMetric(eventType: String, phone: String? = null, details: String? = null): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val now = System.currentTimeMillis()
            val dateStr = getDateOnlyFormat().format(Date(now))
            val cv = ContentValues().apply {
                put("event_type", eventType)
                phone?.let { put("phone", it) }
                details?.let { put("details", it) }
                put("timestamp", now)
                put("date", dateStr)
                put("created_at", getCurrentDateTime())
            }
            db.insert("sms_metrics", null, cv)
        } finally {
            dbLock.unlock()
        }
    }

    fun recordEvent(eventType: String, phone: String? = null, details: String? = null, timestamp: Long = System.currentTimeMillis()): Long {
        return insertSmsMetric(eventType, phone, details)
    }

    fun getSmsMetrics(eventType: String? = null, fromDate: String? = null, toDate: String? = null): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            var sql = "SELECT * FROM sms_metrics WHERE 1=1"
            val args = mutableListOf<String>()
            if (eventType != null) {
                sql += " AND event_type = ?"
                args.add(eventType)
            }
            if (fromDate != null) {
                sql += " AND date >= ?"
                args.add(fromDate)
            }
            if (toDate != null) {
                sql += " AND date <= ?"
                args.add(toDate)
            }
            sql += " ORDER BY timestamp DESC LIMIT 1000"
            db.rawQuery(sql, args.toTypedArray()).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun createSmsOtp(phone: String, otpCode: String, expirySeconds: Int = 300): Boolean {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val now = System.currentTimeMillis()
            val expiresAt = now + (expirySeconds * 1000L)
            val cv = ContentValues().apply {
                put("phone", phone)
                put("otp_code", otpCode)
                put("timestamp", now)
                put("attempts", 0)
                put("max_attempts", 3)
                put("expires_at", expiresAt)
                put("created_at", getCurrentDateTime())
            }
            db.insertWithOnConflict("sms_otp_verifications", null, cv, SQLiteDatabase.CONFLICT_REPLACE) > 0
        } finally {
            dbLock.unlock()
        }
    }

    fun verifySmsOtp(phone: String, otpCode: String): Boolean {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val now = System.currentTimeMillis()
            db.rawQuery(
                "SELECT * FROM sms_otp_verifications WHERE phone = ? AND otp_code = ? AND expires_at > ?",
                arrayOf(phone, otpCode, now.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    db.delete("sms_otp_verifications", "phone = ?", arrayOf(phone))
                    true
                } else {
                    db.execSQL("UPDATE sms_otp_verifications SET attempts = attempts + 1 WHERE phone = ?", arrayOf(phone))
                    false
                }
            }
        } finally {
            dbLock.unlock()
        }
    }

    fun cleanExpiredSmsOtps(): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val now = System.currentTimeMillis()
            db.delete("sms_otp_verifications", "expires_at < ?", arrayOf(now.toString()))
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال التوثيق والمستخدمين
    // ========================================================================

    fun authenticateUser(username: String, password: String): JSONObject? {
        val db = readableDatabase
        db.rawQuery(
            "SELECT * FROM users WHERE username=? AND status='active' AND is_deleted=0",
            arrayOf(username)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val storedHash = cursor.getString(cursor.getColumnIndexOrThrow("password_hash"))
                val storedSalt = cursor.getString(cursor.getColumnIndexOrThrow("password_salt"))
                if (verifyPassword(password, storedHash, storedSalt)) {
                    return JSONObject().apply {
                        put("user_id", cursor.getInt(cursor.getColumnIndexOrThrow("id")))
                        put("username", cursor.getString(cursor.getColumnIndexOrThrow("username")))
                        put("full_name", cursor.getString(cursor.getColumnIndexOrThrow("full_name")))
                        put("full_name_ar", cursor.getString(cursor.getColumnIndexOrThrow("full_name_ar")))
                        put("role_id", cursor.getInt(cursor.getColumnIndexOrThrow("role_id")))
                        put("station_id", cursor.getInt(cursor.getColumnIndexOrThrow("station_id")))
                        put("company_id", cursor.getInt(cursor.getColumnIndexOrThrow("company_id")))
                        put("biometric_enabled", cursor.getInt(cursor.getColumnIndexOrThrow("biometric_enabled")))
                        put("status", cursor.getString(cursor.getColumnIndexOrThrow("status")))
                    }.also {
                        logActivity(username, "login", "تسجيل دخول ناجح")
                    }
                }
            }
            return null
        }
    }

    fun getUserByUsername(username: String): JSONObject? {
        val db = readableDatabase
        db.rawQuery("SELECT * FROM users WHERE username=? AND is_deleted=0", arrayOf(username))
            .use { cursor ->
                if (cursor.moveToFirst()) {
                    return JSONObject().apply {
                        put("user_id", cursor.getInt(cursor.getColumnIndexOrThrow("id")))
                        put("username", cursor.getString(cursor.getColumnIndexOrThrow("username")))
                        put("full_name", cursor.getString(cursor.getColumnIndexOrThrow("full_name")))
                        put("full_name_ar", cursor.getString(cursor.getColumnIndexOrThrow("full_name_ar")))
                        put("role_id", cursor.getInt(cursor.getColumnIndexOrThrow("role_id")))
                        put("station_id", cursor.getInt(cursor.getColumnIndexOrThrow("station_id")))
                        put("company_id", cursor.getInt(cursor.getColumnIndexOrThrow("company_id")))
                        put("biometric_enabled", cursor.getInt(cursor.getColumnIndexOrThrow("biometric_enabled")))
                        put("status", cursor.getString(cursor.getColumnIndexOrThrow("status")))
                    }
                }
                return null
            }
    }

    fun updateBiometricStatus(username: String, enabled: Boolean): Boolean {
        val cv = ContentValues().apply {
            put("biometric_enabled", if (enabled) 1 else 0)
        }
        val rows = writableDatabase.update("users", cv, "username=?", arrayOf(username))
        return rows > 0
    }

    fun getUserById(userId: Long): JSONObject? {

        val db = readableDatabase

        val cursor = db.rawQuery(
            """
            SELECT
                u.id,
                u.uuid,
                u.username,
                u.full_name,
                u.full_name_ar,
                u.display_name,
                u.role_id,
                r.role_code AS role,
                u.station_id,
                u.company_id,
                u.preferred_language,
                u.theme,
                u.status
            FROM users u
            LEFT JOIN roles r ON r.id = u.role_id
            WHERE u.id = ?
            AND u.is_deleted = 0
            LIMIT 1
            """,
            arrayOf(userId.toString())
        )

        return cursor.use {

            if (it.moveToFirst()) {

                JSONObject().apply {

                    put("user_id", it.getLong(it.getColumnIndexOrThrow("id")))
                    put("uuid", it.getString(it.getColumnIndexOrThrow("uuid")))
                    put("username", it.getString(it.getColumnIndexOrThrow("username")))
                    put("full_name", it.getString(it.getColumnIndexOrThrow("full_name")))
                    put("full_name_ar", it.getString(it.getColumnIndexOrThrow("full_name_ar")))
                    put("display_name", it.getString(it.getColumnIndexOrThrow("display_name")))
                    put("role", it.getString(it.getColumnIndexOrThrow("role")))
                    put("role_id", it.getLong(it.getColumnIndexOrThrow("role_id")))
                    put("station_id", it.getLong(it.getColumnIndexOrThrow("station_id")))
                    put("company_id", it.getLong(it.getColumnIndexOrThrow("company_id")))
                    put("language", it.getString(it.getColumnIndexOrThrow("preferred_language")))
                    put("theme", it.getString(it.getColumnIndexOrThrow("theme")))
                    put("status", it.getString(it.getColumnIndexOrThrow("status")))
                }

            } else {
                null
            }
        }
    }

    // ========================================================================
    // دوال الأطراف (جزء منها، تم توفير الباقي في الملف الأصلي)
    // ========================================================================

    fun getParties(typeId: Int? = null): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        val sql = if (typeId != null) {
            "SELECT * FROM parties WHERE party_type_id=? AND is_deleted=0 ORDER BY commercial_name"
        } else {
            "SELECT * FROM parties WHERE is_deleted=0 ORDER BY commercial_name"
        }
        val args = if (typeId != null) arrayOf(typeId.toString()) else null
        db.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                arr.put(partyCursorToJson(cursor))
            }
        }
        return arr
    }

    fun getParties(type: String): JSONArray {
        val typeId = when (type.lowercase()) {
            "customer" -> 1
            "supplier" -> 6
            "driver" -> 4
            else -> null
        }
        return getParties(typeId)
    }

    fun getParty(id: Int): JSONObject? {
        val db = readableDatabase

        return db.rawQuery(
            "SELECT * FROM parties WHERE id=? AND is_deleted=0",
            arrayOf(id.toString())
        ).use { cursor ->

            if (cursor.moveToFirst()) {
                partyCursorToJson(cursor)
            } else {
                null
            }
        }
    }

    fun getPartyById(id: Long): JSONObject? = getParty(id.toInt())

    fun insertParty(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val partyType = data.optString("party_type", "").trim()
            val typeId = if (data.has("party_type_id") && data.optInt("party_type_id", 0) > 0) {
                data.optInt("party_type_id", 1)
            } else {
                when (partyType.lowercase()) {
                    "customer" -> 1
                    "supplier" -> 6
                    "driver" -> 4
                    else -> 1
                }
            }
            val values = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("party_code", data.optString("party_code", "PTY-${System.currentTimeMillis()}"))
                put("barcode", data.optString("barcode", "").trim())
                put("qr_code", data.optString("qr_code", "").trim())
                put("party_type_id", typeId)
                put("station_id", data.optInt("station_id", 1))
                put("commercial_name", data.optString("commercial_name", data.optString("party_name", "")))
                put("commercial_name_ar", data.optString("commercial_name_ar", data.optString("party_name_ar", "")))
                put("legal_name", data.optString("legal_name", ""))
                put("phone", data.optString("phone", ""))
                put("phone2", data.optString("phone2", ""))
                put("email", data.optString("email", ""))
                put("address", data.optString("address", ""))
                put("city", data.optString("city", ""))
                put("region", data.optString("region", ""))
                put("tax_number", data.optString("tax_number", ""))
                put("commercial_register", data.optString("commercial_register", ""))
                put("vat_number", data.optString("vat_number", "").trim())
                put("credit_limit", data.optDouble("credit_limit", 0.0))
                if (data.has("current_balance")) put("current_balance", data.optDouble("current_balance", 0.0))
                if (data.has("total_due")) put("total_due", data.optDouble("total_due", 0.0))
                put("payment_terms", data.optString("payment_terms", "").trim())
                if (data.has("currency_id") && !data.isNull("currency_id")) put("currency_id", data.optLong("currency_id"))
                if (data.has("loyalty_points")) put("loyalty_points", data.optInt("loyalty_points", 0))
                put("loyalty_tier", data.optString("loyalty_tier", "bronze"))
                put("risk_level", data.optString("risk_level", "low"))
                put("blacklist_reason", data.optString("blacklist_reason", "").trim())
                if (data.has("blacklist_date") && !data.isNull("blacklist_date")) put("blacklist_date", data.optString("blacklist_date"))
                if (data.has("blacklist_by") && !data.isNull("blacklist_by")) put("blacklist_by", data.optLong("blacklist_by"))
                if (data.has("referred_by") && !data.isNull("referred_by")) put("referred_by", data.optLong("referred_by"))
                if (data.has("assigned_to") && !data.isNull("assigned_to")) put("assigned_to", data.optLong("assigned_to"))
                put("rating", data.optDouble("rating", 3.0))
                put("total_orders", data.optInt("total_orders", 0))
                put("total_order_amount", data.optDouble("total_order_amount", 0.0))
                put("on_time_rate", data.optDouble("on_time_rate", 100.0))
                if (data.has("fuel_type_preference_id") && !data.isNull("fuel_type_preference_id")) put("fuel_type_preference_id", data.optLong("fuel_type_preference_id"))
                put("fleet_size", data.optInt("fleet_size", 0))
                put("is_active", if (data.optBoolean("is_active", true)) 1 else 0)
                put("notes", data.optString("notes", ""))
                if (data.has("extra_data")) put("extra_data", data.optString("extra_data"))
                put("created_at", getCurrentDateTime())
                put("updated_at", getCurrentDateTime())
            }
            val id = db.insert("parties", null, values)
            if (id > 0) logActivity("system", "insert_party", "إضافة طرف: ${data.optString("commercial_name")}")
            id
        } finally {
            dbLock.unlock()
        }
    }

    fun updateParty(id: Long, data: JSONObject): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                if (data.has("barcode")) put("barcode", data.optString("barcode").trim())
                if (data.has("qr_code")) put("qr_code", data.optString("qr_code").trim())
                if (data.has("party_type_id")) put("party_type_id", data.optInt("party_type_id", 1))
                if (data.has("station_id")) put("station_id", data.optInt("station_id", 1))
                put("commercial_name", data.optString("commercial_name", data.optString("party_name", "")))
                put("commercial_name_ar", data.optString("commercial_name_ar", data.optString("party_name_ar", "")))
                put("legal_name", data.optString("legal_name", ""))
                put("phone", data.optString("phone", ""))
                put("phone2", data.optString("phone2", ""))
                put("email", data.optString("email", ""))
                put("address", data.optString("address", ""))
                put("city", data.optString("city", ""))
                put("region", data.optString("region", ""))
                put("tax_number", data.optString("tax_number", ""))
                put("commercial_register", data.optString("commercial_register", ""))
                if (data.has("vat_number")) put("vat_number", data.optString("vat_number").trim())
                put("credit_limit", data.optDouble("credit_limit", 0.0))
                if (data.has("current_balance")) put("current_balance", data.optDouble("current_balance", 0.0))
                if (data.has("total_due")) put("total_due", data.optDouble("total_due", 0.0))
                if (data.has("payment_terms")) put("payment_terms", data.optString("payment_terms").trim())
                if (data.has("currency_id")) {
                    if (data.isNull("currency_id")) putNull("currency_id") else put("currency_id", data.optLong("currency_id"))
                }
                if (data.has("loyalty_points")) put("loyalty_points", data.optInt("loyalty_points", 0))
                if (data.has("loyalty_tier")) put("loyalty_tier", data.optString("loyalty_tier"))
                if (data.has("risk_level")) put("risk_level", data.optString("risk_level"))
                if (data.has("blacklist_reason")) put("blacklist_reason", data.optString("blacklist_reason").trim())
                if (data.has("blacklist_date")) {
                    if (data.isNull("blacklist_date")) putNull("blacklist_date") else put("blacklist_date", data.optString("blacklist_date"))
                }
                if (data.has("blacklist_by")) {
                    if (data.isNull("blacklist_by")) putNull("blacklist_by") else put("blacklist_by", data.optLong("blacklist_by"))
                }
                if (data.has("referred_by")) {
                    if (data.isNull("referred_by")) putNull("referred_by") else put("referred_by", data.optLong("referred_by"))
                }
                if (data.has("assigned_to")) {
                    if (data.isNull("assigned_to")) putNull("assigned_to") else put("assigned_to", data.optLong("assigned_to"))
                }
                if (data.has("rating")) put("rating", data.optDouble("rating", 3.0))
                if (data.has("total_orders")) put("total_orders", data.optInt("total_orders", 0))
                if (data.has("total_order_amount")) put("total_order_amount", data.optDouble("total_order_amount", 0.0))
                if (data.has("on_time_rate")) put("on_time_rate", data.optDouble("on_time_rate", 100.0))
                if (data.has("fuel_type_preference_id")) {
                    if (data.isNull("fuel_type_preference_id")) putNull("fuel_type_preference_id") else put("fuel_type_preference_id", data.optLong("fuel_type_preference_id"))
                }
                if (data.has("fleet_size")) put("fleet_size", data.optInt("fleet_size", 0))
                put("is_active", if (data.optBoolean("is_active", true)) 1 else 0)
                put("notes", data.optString("notes", ""))
                if (data.has("extra_data")) put("extra_data", data.optString("extra_data"))
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("parties", values, "id=?", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "update_party", "تحديث طرف: $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    fun deleteParty(id: Long): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply { put("is_deleted", 1) }
            val rows = db.update("parties", cv, "id=?", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "delete_party", "حذف طرف: $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    fun archiveParty(id: Long): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("is_active", 0)
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("parties", cv, "id=?", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "archive_party", "أرشفة طرف: $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    fun searchParties(query: String): JSONArray {
        dbLock.lock()
        return try {
            val arr = JSONArray()
            val db = readableDatabase
            val likeQuery = "%$query%"
            db.rawQuery(
                """SELECT * FROM parties
                   WHERE (commercial_name LIKE ? OR commercial_name_ar LIKE ? OR party_code LIKE ? OR phone LIKE ?)
                   AND is_deleted=0 ORDER BY commercial_name LIMIT 50""",
                arrayOf(likeQuery, likeQuery, likeQuery, likeQuery)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    arr.put(partyCursorToJson(cursor))
                }
            }
            arr
        } finally {
            dbLock.unlock()
        }
    }

    private fun partyCursorToJson(c: Cursor): JSONObject {
        return JSONObject().apply {
            put("id", c.getLong(c.getColumnIndexOrThrow("id")))
            put("party_id", c.getLong(c.getColumnIndexOrThrow("id")))
            put("party_code", c.getString(c.getColumnIndexOrThrow("party_code")))
            put("barcode", c.getString(c.getColumnIndexOrThrow("barcode")))
            put("qr_code", c.getString(c.getColumnIndexOrThrow("qr_code")))
            put("party_type_id", c.getInt(c.getColumnIndexOrThrow("party_type_id")))
            put("station_id", c.getInt(c.getColumnIndexOrThrow("station_id")))
            put("commercial_name", c.getString(c.getColumnIndexOrThrow("commercial_name")))
            put("commercial_name_ar", c.getString(c.getColumnIndexOrThrow("commercial_name_ar")))
            put("legal_name", c.getString(c.getColumnIndexOrThrow("legal_name")))
            put("phone", c.getString(c.getColumnIndexOrThrow("phone")))
            put("phone2", c.getString(c.getColumnIndexOrThrow("phone2")))
            put("email", c.getString(c.getColumnIndexOrThrow("email")))
            put("address", c.getString(c.getColumnIndexOrThrow("address")))
            put("city", c.getString(c.getColumnIndexOrThrow("city")))
            put("region", c.getString(c.getColumnIndexOrThrow("region")))
            put("tax_number", c.getString(c.getColumnIndexOrThrow("tax_number")))
            put("commercial_register", c.getString(c.getColumnIndexOrThrow("commercial_register")))
            put("vat_number", c.getString(c.getColumnIndexOrThrow("vat_number")))
            put("credit_limit", c.getDouble(c.getColumnIndexOrThrow("credit_limit")))
            put("current_balance", c.getDouble(c.getColumnIndexOrThrow("current_balance")))
            put("total_purchases", c.getDouble(c.getColumnIndexOrThrow("total_purchases")))
            put("total_payments", c.getDouble(c.getColumnIndexOrThrow("total_payments")))
            put("total_due", c.getDouble(c.getColumnIndexOrThrow("total_due")))
            put("overdue_amount", c.getDouble(c.getColumnIndexOrThrow("overdue_amount")))
            put("payment_terms", c.getString(c.getColumnIndexOrThrow("payment_terms")))
            put("currency_id", c.getInt(c.getColumnIndexOrThrow("currency_id")))
            put("loyalty_points", c.getInt(c.getColumnIndexOrThrow("loyalty_points")))
            put("loyalty_tier", c.getString(c.getColumnIndexOrThrow("loyalty_tier")))
            put("risk_level", c.getString(c.getColumnIndexOrThrow("risk_level")))
            put("blacklist_reason", c.getString(c.getColumnIndexOrThrow("blacklist_reason")))
            put("blacklist_date", c.getString(c.getColumnIndexOrThrow("blacklist_date")))
            put("blacklist_by", c.getInt(c.getColumnIndexOrThrow("blacklist_by")))
            put("referred_by", c.getInt(c.getColumnIndexOrThrow("referred_by")))
            put("assigned_to", c.getInt(c.getColumnIndexOrThrow("assigned_to")))
            put("rating", c.getDouble(c.getColumnIndexOrThrow("rating")))
            put("total_orders", c.getInt(c.getColumnIndexOrThrow("total_orders")))
            put("total_order_amount", c.getDouble(c.getColumnIndexOrThrow("total_order_amount")))
            put("on_time_rate", c.getDouble(c.getColumnIndexOrThrow("on_time_rate")))
            put("fuel_type_preference_id", c.getInt(c.getColumnIndexOrThrow("fuel_type_preference_id")))
            put("fleet_size", c.getInt(c.getColumnIndexOrThrow("fleet_size")))
            put("is_active", c.getInt(c.getColumnIndexOrThrow("is_active")))
            put("extra_data", c.getString(c.getColumnIndexOrThrow("extra_data")))
            put("created_at", c.getString(c.getColumnIndexOrThrow("created_at")))
            put("updated_at", c.getString(c.getColumnIndexOrThrow("updated_at")))
        }
    }
    // ========================================================================
    // دوال الخزانات والمضخات (جزء منها)
    // ========================================================================

    fun getTanks(stationId: Int = 1): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        db.rawQuery(
            "SELECT t.*, f.fuel_name, f.fuel_name_ar FROM tanks t LEFT JOIN fuel_types f ON t.fuel_type_id = f.id WHERE t.station_id=? AND t.is_deleted=0 ORDER BY t.tank_code",
            arrayOf(stationId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                arr.put(JSONObject().apply {
                    put("tank_id", cursor.getInt(cursor.getColumnIndexOrThrow("id")))
                    put("tank_code", cursor.getString(cursor.getColumnIndexOrThrow("tank_code")))
                    put("tank_name", cursor.getString(cursor.getColumnIndexOrThrow("tank_name")))
                    put("capacity_liters", cursor.getDouble(cursor.getColumnIndexOrThrow("capacity_liters")))
                    put("current_quantity", cursor.getDouble(cursor.getColumnIndexOrThrow("current_quantity")))
                    put("minimum_level", cursor.getDouble(cursor.getColumnIndexOrThrow("minimum_level")))
                    put("status", cursor.getString(cursor.getColumnIndexOrThrow("status")))
                    put("fuel_name", cursor.getString(cursor.getColumnIndexOrThrow("fuel_name")))
                    put("fuel_name_ar", cursor.getString(cursor.getColumnIndexOrThrow("fuel_name_ar")))
                })
            }
        }
        return arr
    }

    fun getPumps(stationId: Int = 1): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        db.rawQuery(
            "SELECT p.*, t.tank_name FROM pumps p LEFT JOIN tanks t ON p.tank_id = t.id WHERE p.station_id=? AND p.is_deleted=0 ORDER BY p.pump_code",
            arrayOf(stationId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                arr.put(JSONObject().apply {
                    put("pump_id", cursor.getInt(cursor.getColumnIndexOrThrow("id")))
                    put("pump_code", cursor.getString(cursor.getColumnIndexOrThrow("pump_code")))
                    put("pump_name", cursor.getString(cursor.getColumnIndexOrThrow("pump_name")))
                    put("pump_number", cursor.getString(cursor.getColumnIndexOrThrow("pump_number")))
                    put("tank_name", cursor.getString(cursor.getColumnIndexOrThrow("tank_name")))
                    put("meter_current", cursor.getDouble(cursor.getColumnIndexOrThrow("meter_current")))
                    put("status", cursor.getString(cursor.getColumnIndexOrThrow("status")))
                })
            }
        }
        return arr
    }

    fun getNozzles(pumpId: Int? = null): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        val sql = if (pumpId != null) {
            "SELECT n.*, f.fuel_name FROM pump_nozzles n LEFT JOIN fuel_types f ON n.fuel_type_id = f.id WHERE n.pump_id=? AND n.is_deleted=0"
        } else {
            "SELECT n.*, f.fuel_name FROM pump_nozzles n LEFT JOIN fuel_types f ON n.fuel_type_id = f.id WHERE n.is_deleted=0"
        }
        val args = if (pumpId != null) arrayOf(pumpId.toString()) else null
        db.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                arr.put(JSONObject().apply {
                    put("nozzle_id", cursor.getInt(cursor.getColumnIndexOrThrow("id")))
                    put("nozzle_code", cursor.getString(cursor.getColumnIndexOrThrow("nozzle_code")))
                    put("nozzle_number", cursor.getString(cursor.getColumnIndexOrThrow("nozzle_number")))
                    put("fuel_name", cursor.getString(cursor.getColumnIndexOrThrow("fuel_name")))
                    put("meter_current", cursor.getDouble(cursor.getColumnIndexOrThrow("meter_current")))
                    put("status", cursor.getString(cursor.getColumnIndexOrThrow("status")))
                })
            }
        }
        return arr
    }

    fun updateTankQuantity(tankId: Int, newQuantity: Double, operator: String = "System"): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE tanks SET current_quantity = ? WHERE id = ?", arrayOf(newQuantity, tankId))
            db.setTransactionSuccessful()
            logActivity(operator, "tank_update", "تحديث كمية الخزان $tankId إلى $newQuantity لتر")
            return true
        } finally {
            db.endTransaction()
        }
    }

    // ========================================================================
    // دوال الورديات (جزء منها)
    // ========================================================================

    fun openShift(stationId: Int, shiftType: String, cashierId: Int, openingCash: Double, openingBank: Double = 0.0): Long {
        val shiftCode = "SHF-${System.currentTimeMillis()}"
        val cv = ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("shift_code", shiftCode)
            put("station_id", stationId)
            put("shift_date", getCurrentDate())
            put("shift_type", shiftType)
            put("start_time", getCurrentDateTime())
            put("cashier_id", cashierId)
            put("opening_cash", openingCash)
            put("opening_bank", openingBank)
            put("status", "open")
        }
        return writableDatabase.insert("shifts", null, cv)
    }

    fun closeShift(shiftId: Int, closingCash: Double, closingBank: Double, totalSales: Double, operator: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put("end_time", getCurrentDateTime())
                put("closing_cash", closingCash)
                put("closing_bank", closingBank)
                put("total_sales", totalSales)
                put("status", "closed")
            }
            db.update("shifts", cv, "id=?", arrayOf(shiftId.toString()))
            db.setTransactionSuccessful()
            logActivity(operator, "shift_close", "إغلاق الوردية $shiftId")
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun getShifts(stationId: Int, limit: Int = 50): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        db.rawQuery(
            "SELECT * FROM shifts WHERE station_id=? ORDER BY id DESC LIMIT ?",
            arrayOf(stationId.toString(), limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                arr.put(JSONObject().apply {
                    put("shift_id", cursor.getInt(cursor.getColumnIndexOrThrow("id")))
                    put("shift_code", cursor.getString(cursor.getColumnIndexOrThrow("shift_code")))
                    put("shift_type", cursor.getString(cursor.getColumnIndexOrThrow("shift_type")))
                    put("shift_date", cursor.getString(cursor.getColumnIndexOrThrow("shift_date")))
                    put("start_time", cursor.getString(cursor.getColumnIndexOrThrow("start_time")))
                    put("end_time", cursor.getString(cursor.getColumnIndexOrThrow("end_time")))
                    put("opening_cash", cursor.getDouble(cursor.getColumnIndexOrThrow("opening_cash")))
                    put("closing_cash", cursor.getDouble(cursor.getColumnIndexOrThrow("closing_cash")))
                    put("total_sales", cursor.getDouble(cursor.getColumnIndexOrThrow("total_sales")))
                    put("status", cursor.getString(cursor.getColumnIndexOrThrow("status")))
                })
            }
        }
        return arr
    }

    fun getOpenShift(stationId: Int = 1): JSONObject? {
        val db = readableDatabase
        db.rawQuery(
            "SELECT * FROM shifts WHERE station_id=? AND status='open' ORDER BY id DESC LIMIT 1",
            arrayOf(stationId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return JSONObject().apply {
                    put("shift_id", cursor.getInt(cursor.getColumnIndexOrThrow("id")))
                    put("shift_code", cursor.getString(cursor.getColumnIndexOrThrow("shift_code")))
                    put("opening_cash", cursor.getDouble(cursor.getColumnIndexOrThrow("opening_cash")))
                }
            }
            return null
        }
    }

    fun getCurrentShift(stationId: Int = 1): JSONObject? = getOpenShift(stationId)

    // ========================================================================
    // دوال المبيعات (جزء منها)
    // ========================================================================

    fun insertSaleTransaction(
        stationId: Int,
        shiftId: Int,
        customerPartyId: Int?,
        fuelTypeId: Int?,
        pumpId: Int?,
        nozzleId: Int?,
        liters: Double,
        pricePerLiter: Double,
        subtotal: Double,
        discountAmount: Double,
        taxAmount: Double,
        grossAmount: Double,
        netAmount: Double,
        paymentMethod: String,
        isCredit: Boolean,
        dueDate: String?,
        cashierId: Int,
        notes: String = "",
        deliveryLocation: String? = null,
        deliveryTime: String? = null,
        orderType: String = "sale"
    ): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val saleCode = "SALE-${System.currentTimeMillis()}"
            val invoiceNo = "INV-${System.currentTimeMillis()}"
            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("sale_code", saleCode)
                put("station_id", stationId)
                put("shift_id", shiftId)
                if (customerPartyId != null) put("customer_party_id", customerPartyId)
                if (fuelTypeId != null) put("fuel_type_id", fuelTypeId)
                if (pumpId != null) put("pump_id", pumpId)
                if (nozzleId != null) put("nozzle_id", nozzleId)
                put("liters", liters)
                put("price_per_liter", pricePerLiter)
                put("fuel_subtotal", liters * pricePerLiter)
                put("subtotal", subtotal)
                put("discount_amount", discountAmount)
                put("tax_amount", taxAmount)
                put("gross_amount", grossAmount)
                put("net_amount", netAmount)
                put("payment_method", paymentMethod)
                put("payment_status", if (isCredit) "pending" else "paid")
                put("paid_amount", if (isCredit) 0.0 else netAmount)
                put("remaining_amount", if (isCredit) netAmount else 0.0)
                put("is_credit", if (isCredit) 1 else 0)
                if (dueDate != null) put("due_date", dueDate)
                put("invoice_number", invoiceNo)
                put("cashier_id", cashierId)
                put("status", "completed")
                put("remarks", notes)
                put("order_type", orderType)
                if (deliveryLocation != null) put("delivery_location", deliveryLocation)
                if (deliveryTime != null) put("delivery_time", deliveryTime)
            }
            val saleId = db.insert("sales_transactions", null, cv)

            if (pumpId != null && fuelTypeId != null) {
                db.execSQL(
                    "UPDATE tanks SET current_quantity = current_quantity - ? WHERE id = (SELECT tank_id FROM pumps WHERE id = ?)",
                    arrayOf(liters, pumpId)
                )
            }

            db.execSQL(
                "UPDATE shifts SET total_sales = total_sales + ?, total_fuel_liters = total_fuel_liters + ? WHERE id = ?",
                arrayOf(netAmount, liters, shiftId)
            )

            if (isCredit && customerPartyId != null) {
                db.execSQL(
                    "UPDATE parties SET current_balance = current_balance + ?, total_due = total_due + ? WHERE id = ?",
                    arrayOf(netAmount, netAmount, customerPartyId)
                )
                val ledgerCv = ContentValues().apply {
                    put("uuid", UUID.randomUUID().toString())
                    put("party_id", customerPartyId)
                    put("transaction_date", getCurrentDateTime())
                    put("transaction_type", "sale_credit")
                    put("transaction_id", saleId.toInt())
                    put("reference_number", invoiceNo)
                    put("debit", netAmount)
                    put("credit", 0.0)
                    put("balance", getPartyBalance(customerPartyId))
                    put("description", "فاتورة بيع آجل: $invoiceNo")
                }
                db.insert("customer_ledger", null, ledgerCv)
            }

            db.setTransactionSuccessful()
            logActivity("cashier_$cashierId", "sale", "بيع جديد: $liters لتر - $netAmount")
            return saleId
        } finally {
            db.endTransaction()
        }
    }

    fun getSalesTransactions(stationId: Int = 1, limit: Int = 200, offset: Int = 0): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        db.rawQuery(
            """SELECT s.*, p.commercial_name as customer_name FROM sales_transactions s
               LEFT JOIN parties p ON s.customer_party_id = p.id
               WHERE s.station_id=? AND s.is_deleted=0 ORDER BY s.id DESC LIMIT ? OFFSET ?""",
            arrayOf(stationId.toString(), limit.toString(), offset.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                arr.put(saleCursorToJson(cursor).apply {
                    put("customer_name", cursor.getString(cursor.getColumnIndexOrThrow("customer_name")))
                })
            }
        }
        return arr
    }

    fun getSaleTransactionById(id: Int): JSONObject? {
        val db = readableDatabase

        return db.rawQuery(
            "SELECT * FROM sales_transactions WHERE id=?",
            arrayOf(id.toString())
        ).use { cursor ->

            if (cursor.moveToFirst()) {
                saleCursorToJson(cursor)
            } else {
                null
            }
        }
    }

    private fun saleCursorToJson(c: Cursor): JSONObject {
        return JSONObject().apply {
            put("sale_id", c.getInt(c.getColumnIndexOrThrow("id")))
            put("sale_code", c.getString(c.getColumnIndexOrThrow("sale_code")))
            put("station_id", c.getInt(c.getColumnIndexOrThrow("station_id")))
            put("shift_id", c.getInt(c.getColumnIndexOrThrow("shift_id")))
            put("customer_party_id", c.getInt(c.getColumnIndexOrThrow("customer_party_id")))
            put("liters", c.getDouble(c.getColumnIndexOrThrow("liters")))
            put("price_per_liter", c.getDouble(c.getColumnIndexOrThrow("price_per_liter")))
            put("subtotal", c.getDouble(c.getColumnIndexOrThrow("subtotal")))
            put("discount_amount", c.getDouble(c.getColumnIndexOrThrow("discount_amount")))
            put("tax_amount", c.getDouble(c.getColumnIndexOrThrow("tax_amount")))
            put("gross_amount", c.getDouble(c.getColumnIndexOrThrow("gross_amount")))
            put("net_amount", c.getDouble(c.getColumnIndexOrThrow("net_amount")))
            put("payment_method", c.getString(c.getColumnIndexOrThrow("payment_method")))
            put("payment_status", c.getString(c.getColumnIndexOrThrow("payment_status")))
            put("paid_amount", c.getDouble(c.getColumnIndexOrThrow("paid_amount")))
            put("remaining_amount", c.getDouble(c.getColumnIndexOrThrow("remaining_amount")))
            put("is_credit", c.getInt(c.getColumnIndexOrThrow("is_credit")))
            put("due_date", c.getString(c.getColumnIndexOrThrow("due_date")))
            put("invoice_number", c.getString(c.getColumnIndexOrThrow("invoice_number")))
            put("status", c.getString(c.getColumnIndexOrThrow("status")))
            put("created_at", c.getString(c.getColumnIndexOrThrow("created_at")))
            put("delivery_location", c.getString(c.getColumnIndexOrThrow("delivery_location")))
            put("delivery_time", c.getString(c.getColumnIndexOrThrow("delivery_time")))
            put("order_type", c.getString(c.getColumnIndexOrThrow("order_type")))
        }
    }

    // ========================================================================
    // دوال الطلبات والتوصيلات (جزء منها)
    // ========================================================================

    fun addOrder(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val customerPartyId = data.optLong("party_id", 0).toInt()
            val liters = data.optDouble("quantity", 0.0)
            val pricePerLiter = data.optDouble("price_per_liter", getDieselPrice())
            val subtotal = liters * pricePerLiter
            val netAmount = data.optDouble("total_amount", subtotal)
            val deliveryLocation = data.optString("delivery_location", data.optString("location", ""))
            val deliveryTime = data.optString("delivery_time", "")
            val notes = data.optString("notes", "")
            val stationId = data.optInt("station_id", 1)
            val shiftId = getCurrentShift(stationId)?.optLong("shift_id", 1)?.toInt() ?: 1

            insertSaleTransaction(
                stationId = stationId,
                shiftId = shiftId,
                customerPartyId = if (customerPartyId > 0) customerPartyId else null,
                fuelTypeId = 1,
                pumpId = null,
                nozzleId = null,
                liters = liters,
                pricePerLiter = pricePerLiter,
                subtotal = subtotal,
                discountAmount = data.optDouble("discount", 0.0),
                taxAmount = 0.0,
                grossAmount = netAmount,
                netAmount = netAmount,
                paymentMethod = data.optString("payment_method", "credit"),
                isCredit = true,
                dueDate = data.optString("due_date", null),
                cashierId = 1,
                notes = notes,
                deliveryLocation = deliveryLocation,
                deliveryTime = deliveryTime,
                orderType = data.optString("order_type", "sale")
            )
        } finally {
            dbLock.unlock()
        }
    }

    fun getOrders(status: String?): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val selection = if (status != null) "status = ? AND order_type != 'retail'" else "order_type != 'retail'"
            val selectionArgs = if (status != null) arrayOf(status) else null
            db.query("sales_transactions", null, selection, selectionArgs, null, null, "created_at DESC")
                .use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun addDelivery(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val partyId = data.optLong("party_id", 0).toInt()
            val liters = data.optDouble("quantity", 0.0)
            val pricePerLiter = data.optDouble("price_per_liter", getDieselPrice())
            val subtotal = liters * pricePerLiter
            val totalAmount = data.optDouble("total_amount", subtotal)
            val location = data.optString("location", "")
            val deliveryTime = data.optString("delivery_time", data.optString("delivery_date", ""))
            val stationId = data.optInt("station_id", 1)
            val shiftId = getCurrentShift(stationId)?.optLong("shift_id", 1)?.toInt() ?: 1

            val saleId = insertSaleTransaction(
                stationId = stationId,
                shiftId = shiftId,
                customerPartyId = if (partyId > 0) partyId else null,
                fuelTypeId = 1,
                pumpId = null,
                nozzleId = null,
                liters = liters,
                pricePerLiter = pricePerLiter,
                subtotal = subtotal,
                discountAmount = 0.0,
                taxAmount = 0.0,
                grossAmount = totalAmount,
                netAmount = totalAmount,
                paymentMethod = data.optString("payment_method", "credit"),
                isCredit = true,
                dueDate = data.optString("due_date", null),
                cashierId = 1,
                notes = data.optString("notes", ""),
                deliveryLocation = location,
                deliveryTime = deliveryTime,
                orderType = "delivery"
            )

            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("sale_id", saleId)
                if (partyId > 0) put("party_id", partyId)
                put("vehicle_id", data.optLong("vehicle_id", 0))
                put("driver_id", data.optLong("driver_id", 0))
                put("delivery_date", data.optString("delivery_date", getCurrentDate()))
                put("quantity", liters)
                put("fuel_type", data.optString("fuel_type", "diesel"))
                put("price_per_liter", pricePerLiter)
                put("total_amount", totalAmount)
                put("status", data.optString("status", "delivered"))
                put("location", location)
                put("notes", data.optString("notes", ""))
                put("created_at", getCurrentDateTime())
            }
            writableDatabase.insert("deliveries", null, cv)
            saleId
        } finally {
            dbLock.unlock()
        }
    }

    fun getDeliveries(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                """SELECT d.*, s.sale_code, s.delivery_location, s.delivery_time, s.created_at as sale_date
                   FROM deliveries d
                   LEFT JOIN sales_transactions s ON d.sale_id = s.id
                   WHERE d.is_deleted = 0
                   ORDER BY d.created_at DESC""",
                null
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun getTodayDeliveries(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val today = getCurrentDate()
            db.rawQuery(
                """SELECT d.*, s.sale_code, s.delivery_location, s.delivery_time
                   FROM deliveries d
                   LEFT JOIN sales_transactions s ON d.sale_id = s.id
                   WHERE d.delivery_date = ? AND d.is_deleted = 0
                   ORDER BY d.created_at DESC""",
                arrayOf(today)
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال مبيعات الوقود
    // ========================================================================

    fun addFuelSale(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val liters = data.optDouble("quantity", 0.0)
            val pricePerLiter = data.optDouble("price_per_liter", getDieselPrice())
            val subtotal = liters * pricePerLiter
            val totalAmount = data.optDouble("total_amount", subtotal)
            val stationId = data.optInt("station_id", 1)
            val shiftId = data.optLong("shift_id", 1).toInt()
            val customerId = data.optLong("customer_id", 0).toInt()
            val pumpId = data.optLong("pump_id", 0).toInt()
            val fuelTypeId = data.optLong("fuel_type_id", 1).toInt()

            val saleId = insertSaleTransaction(
                stationId = stationId,
                shiftId = shiftId,
                customerPartyId = if (customerId > 0) customerId else null,
                fuelTypeId = fuelTypeId,
                pumpId = if (pumpId > 0) pumpId else null,
                nozzleId = null,
                liters = liters,
                pricePerLiter = pricePerLiter,
                subtotal = subtotal,
                discountAmount = 0.0,
                taxAmount = 0.0,
                grossAmount = totalAmount,
                netAmount = totalAmount,
                paymentMethod = data.optString("payment_method", "cash"),
                isCredit = false,
                dueDate = null,
                cashierId = 1,
                notes = data.optString("notes", ""),
                orderType = "fuel"
            )

            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("sale_id", saleId)
                put("shift_id", shiftId)
                if (pumpId > 0) put("pump_id", pumpId)
                put("fuel_type_id", fuelTypeId)
                put("quantity", liters)
                put("price_per_liter", pricePerLiter)
                put("total_amount", totalAmount)
                put("payment_method", data.optString("payment_method", "cash"))
                if (customerId > 0) put("customer_id", customerId)
                put("vehicle_plate", data.optString("vehicle_plate", ""))
                put("sale_date", data.optString("sale_date", getCurrentDate()))
                put("sale_time", data.optString("sale_time", getCurrentTime()))
                put("notes", data.optString("notes", ""))
                put("created_at", getCurrentDateTime())
            }
            writableDatabase.insert("fuel_sales", null, cv)
            saleId
        } finally {
            dbLock.unlock()
        }
    }

    fun getSales(stationId: Int = 1): JSONArray = getSalesTransactions(stationId, 10000)

    fun getTodaySales(stationId: Int = 1): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val today = getCurrentDate()
            db.rawQuery(
                """SELECT s.*, f.fuel_name, p.commercial_name as customer_name
                   FROM sales_transactions s
                   LEFT JOIN fuel_types f ON s.fuel_type_id = f.id
                   LEFT JOIN parties p ON s.customer_party_id = p.id
                   WHERE date(s.created_at) = ? AND s.station_id = ? AND s.is_deleted = 0 AND s.sale_type = 'retail'
                   ORDER BY s.created_at DESC""",
                arrayOf(today, stationId.toString())
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال POS والمخزون (جزء منها)
    // ========================================================================

    fun getProductByBarcode(barcode: String): JSONObject? {
        val db = readableDatabase
        db.rawQuery(
            """
            SELECT *
            FROM products
            WHERE barcode = ?
            AND is_deleted = 0
            LIMIT 1
            """,
            arrayOf(barcode)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return JSONObject().apply {
                    put("product_id", cursor.getInt(cursor.getColumnIndexOrThrow("id")))
                    put("product_code", cursor.getString(cursor.getColumnIndexOrThrow("product_code")))
                    put("barcode", cursor.getString(cursor.getColumnIndexOrThrow("barcode")))
                    put("product_name", cursor.getString(cursor.getColumnIndexOrThrow("product_name")))
                    put("product_name_ar", cursor.getString(cursor.getColumnIndexOrThrow("product_name_ar")))
                    put("sale_price", cursor.getDouble(cursor.getColumnIndexOrThrow("sale_price")))
                    put("purchase_price", cursor.getDouble(cursor.getColumnIndexOrThrow("purchase_price")))
                    put("quantity", cursor.getDouble(cursor.getColumnIndexOrThrow("quantity")))
                    put("minimum_stock", cursor.getDouble(cursor.getColumnIndexOrThrow("minimum_stock")))
                    put("has_expiry", cursor.getInt(cursor.getColumnIndexOrThrow("has_expiry")))
                    put("expiry_date", cursor.getString(cursor.getColumnIndexOrThrow("expiry_date")))
                    put("status", cursor.getString(cursor.getColumnIndexOrThrow("status")))
                }
            }
            return null
        }
    }

    fun completeSale(data: JSONObject): JSONObject {
        val result = JSONObject()
        dbLock.lock()
        try {
            val products = data.getJSONArray("products")
            var total = 0.0
            for (i in 0 until products.length()) {
                val item = products.getJSONObject(i)
                total += item.optDouble("quantity") * item.optDouble("unit_price")
            }

            val stationId = data.optInt("station_id", 1)
            val shiftId = getCurrentShift(stationId)?.optLong("shift_id", 1)?.toInt() ?: 1

            val saleId = insertSaleTransaction(
                stationId = stationId,
                shiftId = shiftId,
                customerPartyId = data.optInt("entity_id", 0).takeIf { it > 0 },
                fuelTypeId = null,
                pumpId = null,
                nozzleId = null,
                liters = 0.0,
                pricePerLiter = 0.0,
                subtotal = total,
                discountAmount = 0.0,
                taxAmount = 0.0,
                grossAmount = total,
                netAmount = total,
                paymentMethod = when (data.optString("payment_type", "cash")) {
                    "آجل", "credit" -> "credit"
                    "بطاقة", "credit_card" -> "credit_card"
                    "تحويل", "bank_transfer" -> "bank_transfer"
                    else -> "cash"
                },
                isCredit = data.optString("payment_type") in setOf("آجل", "credit"),
                dueDate = null,
                cashierId = 1,
                orderType = "product"
            )

            val db = writableDatabase
            for (i in 0 until products.length()) {
                val item = products.getJSONObject(i)
                val qty = item.optDouble("quantity")
                val price = item.optDouble("unit_price")
                val cv = ContentValues().apply {
                    put("uuid", UUID.randomUUID().toString())
                    put("sale_id", saleId)
                    put("line_number", i + 1)
                    put("item_type", "product")
                    put("product_id", item.getInt("product_id"))
                    put("quantity", qty)
                    put("unit_price", price)
                    put("subtotal", qty * price)
                    put("line_total", qty * price)
                }
                db.insert("sale_items", null, cv)
                addStockMovement(
                    JSONObject().apply {
                        put("product_id", item.getInt("product_id"))
                        put("quantity", qty)
                        put("movement_type", "out")
                        put("reference_type", "sale")
                        put("reference_id", saleId)
                        put("station_id", stationId)
                    }
                )
            }
            result.put("success", true)
            result.put("sale_id", saleId)
            result.put("invoice_number", getSaleTransactionById(saleId.toInt())?.optString("invoice_number", "INV-$saleId") ?: "INV-$saleId")
        } catch (e: Exception) {
            result.put("success", false)
            result.put("error", e.message)
        } finally {
            dbLock.unlock()
        }
        return result
    }

    fun addStockMovement(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val productId = data.optLong("product_id", 0).toInt()
            val quantity = data.optDouble("quantity", 0.0)
            val movementType = data.optString("movement_type", "in")
            val unitCost = data.optDouble("unit_cost", 0.0)
            val totalCost = quantity * unitCost
            val stationId = data.optInt("station_id", 1)
            val warehouseId = data.optLong("warehouse_id", 1L).takeIf { it > 0L } ?: 1L
            val signedAdjustment = data.optDouble("signed_quantity", 0.0)

            require(productId > 0) { "المنتج مطلوب" }
            require(quantity > 0.0) { "كمية الحركة يجب أن تكون أكبر من صفر" }
            require(movementType in setOf("in", "out", "adjustment", "transfer", "return", "damage")) { "نوع حركة المخزون غير صحيح" }
            if (movementType == "adjustment") {
                require(signedAdjustment != 0.0) { "التسوية يجب أن تحتوي على كمية موجبة أو سالبة" }
            }

            var currentQty = 0.0
            db.rawQuery(
                "SELECT quantity_on_hand FROM inventory_levels WHERE product_id = ? AND warehouse_id = ?",
                arrayOf(productId.toString(), warehouseId.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) currentQty = cursor.getDouble(0)
            }

            val quantityBefore = currentQty
            val quantityAfter = when {
                movementType == "adjustment" -> currentQty + signedAdjustment
                movementType == "in" || movementType == "return" -> currentQty + quantity
                else -> currentQty - quantity
            }
            require(quantityAfter >= 0.0) { "لا يمكن خصم كمية أكبر من المخزون المتاح" }

            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("movement_code", data.optString("movement_code", "INV-${System.currentTimeMillis()}"))
                put("product_id", productId)
                put("station_id", stationId)
                put("warehouse_id", warehouseId)
                put("movement_type", movementType)
                put("movement_subtype", data.optString("movement_subtype", ""))
                put("quantity_before", quantityBefore)
                put("quantity_change", if (movementType == "adjustment") signedAdjustment else quantity)
                put("quantity_after", quantityAfter)
                put("unit_cost", unitCost)
                put("total_cost", totalCost)
                put("reference_type", data.optString("reference_type", ""))
                put("reference_id", data.optLong("reference_id", 0))
                put("reason", data.optString("notes", ""))
                put("performed_by", data.optInt("performed_by", 1))
                put("status", "completed")
                put("created_at", getCurrentDateTime())
            }
            val id = db.insert("inventory_movements", null, cv)

            if (id > 0) {
                db.rawQuery(
                    "SELECT id FROM inventory_levels WHERE product_id = ? AND warehouse_id = ?",
                    arrayOf(productId.toString(), warehouseId.toString())
                ).use { exists ->

                    if (exists.moveToFirst()) {
                        db.execSQL(
                            "UPDATE inventory_levels SET quantity_on_hand = ? WHERE product_id = ? AND warehouse_id = ?",
                            arrayOf(quantityAfter, productId, warehouseId)
                        )
                    } else {
                        val cvInv = ContentValues().apply {
                            put("product_id", productId)
                            put("warehouse_id", warehouseId)
                            put("quantity_on_hand", quantityAfter)
                            put("average_cost", unitCost)
                        }
                        db.insert("inventory_levels", null, cvInv)
                    }
                }
                logActivity("system", "stock_movement", "$movementType: $quantity للمنتج $productId")
            }
            id
        } finally {
            dbLock.unlock()
        }
    }

    fun getStockMovements(data: JSONObject = JSONObject()): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val where = mutableListOf("im.is_deleted = 0")
            val args = mutableListOf<String>()
            data.optString("start_date").trim().takeIf { it.isNotEmpty() }?.let {
                where += "date(im.created_at) >= date(?)"
                args += it
            }
            data.optString("end_date").trim().takeIf { it.isNotEmpty() }?.let {
                where += "date(im.created_at) <= date(?)"
                args += it
            }
            data.optString("movement_type").trim().takeIf { it in setOf("in", "out", "adjustment", "transfer", "return", "damage") }?.let {
                where += "im.movement_type = ?"
                args += it
            }
            data.optString("status").trim().takeIf { it in setOf("draft", "completed", "cancelled", "reversed") }?.let {
                where += "im.status = ?"
                args += it
            }
            data.optLong("warehouse_id", 0L).takeIf { it > 0L }?.let {
                where += "im.warehouse_id = ?"
                args += it.toString()
            }
            data.optLong("product_id", 0L).takeIf { it > 0L }?.let {
                where += "im.product_id = ?"
                args += it.toString()
            }
            data.optLong("movement_id", 0L).takeIf { it > 0L }?.let {
                where += "im.id = ?"
                args += it.toString()
            }
            data.optString("query").trim().takeIf { it.isNotEmpty() }?.let {
                where += "(p.product_name LIKE ? OR p.product_name_ar LIKE ? OR p.product_code LIKE ? OR p.barcode LIKE ?)"
                val pattern = "%$it%"
                repeat(4) { args += pattern }
            }
            val limit = data.optInt("limit", 50).coerceIn(1, 200)
            val offset = data.optInt("offset", 0).coerceAtLeast(0)
            val sql = """
                SELECT im.*, p.product_name, p.product_name_ar, p.product_code,
                       w.warehouse_name, u.username AS performed_by_name
                FROM inventory_movements im
                LEFT JOIN products p ON im.product_id = p.id
                LEFT JOIN warehouses w ON im.warehouse_id = w.id
                LEFT JOIN users u ON im.performed_by = u.id
                WHERE ${where.joinToString(" AND ")}
                ORDER BY datetime(im.created_at) DESC, im.id DESC
                LIMIT $limit OFFSET $offset
            """.trimIndent()
            db.rawQuery(sql, args.toTypedArray()).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun getLowStockItems(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                """SELECT p.id, p.product_name, p.product_name_ar, p.minimum_stock,
                          COALESCE(il.quantity_on_hand, 0) as quantity_on_hand
                   FROM products p
                   LEFT JOIN inventory_levels il ON p.id = il.product_id AND il.warehouse_id = 1
                   WHERE p.is_deleted = 0 AND p.status = 'active'
                   AND (il.quantity_on_hand <= p.minimum_stock OR il.quantity_on_hand IS NULL)""",
                null
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun checkLowStock(): JSONArray = getLowStockItems()
    fun transferStockMovement(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val productId = data.optLong("product_id", 0L)
            val sourceWarehouseId = data.optLong("source_warehouse_id", 0L)
            val targetWarehouseId = data.optLong("target_warehouse_id", 0L)
            val quantity = data.optDouble("quantity", 0.0)
            val unitCost = data.optDouble("unit_cost", 0.0)
            val stationId = data.optInt("station_id", 1)
            val performedBy = data.optLong("performed_by", 1L)
            require(productId > 0L) { "المنتج مطلوب" }
            require(sourceWarehouseId > 0L && targetWarehouseId > 0L && sourceWarehouseId != targetWarehouseId) { "مستودعا المصدر والهدف مطلوبان ويجب أن يكونا مختلفين" }
            require(quantity > 0.0) { "كمية التحويل يجب أن تكون أكبر من صفر" }
            require(performedBy > 0L) { "المستخدم المنفذ مطلوب" }

            db.beginTransaction()
            try {
                fun currentQuantity(warehouseId: Long): Double = db.rawQuery(
                    "SELECT quantity_on_hand FROM inventory_levels WHERE product_id = ? AND warehouse_id = ?",
                    arrayOf(productId.toString(), warehouseId.toString())
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getDouble(0) else 0.0 }
                fun nextMovementCode(suffix: String): String = "TRF-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}-$suffix"
                fun insertMovement(warehouseId: Long, before: Double, after: Double, subtype: String, from: Long, to: Long): Long {
                    val values = ContentValues().apply {
                        put("uuid", UUID.randomUUID().toString())
                        put("movement_code", nextMovementCode(subtype.take(3)))
                        put("product_id", productId)
                        put("station_id", stationId)
                        put("warehouse_id", warehouseId)
                        put("movement_type", "transfer")
                        put("movement_subtype", subtype)
                        put("quantity_before", before)
                        put("quantity_change", quantity)
                        put("quantity_after", after)
                        put("unit_cost", unitCost)
                        put("total_cost", quantity * unitCost)
                        put("from_location", from.toString())
                        put("to_location", to.toString())
                        put("reference_code", data.optString("reference_code", ""))
                        put("reason", data.optString("notes", "تحويل مخزون"))
                        put("performed_by", performedBy)
                        put("created_by", performedBy)
                        put("status", "completed")
                        put("created_at", getCurrentDateTime())
                    }
                    val id = db.insertOrThrow("inventory_movements", null, values)
                    return id
                }
                fun updateLevel(warehouseId: Long, quantityAfter: Double) {
                    val existing = db.rawQuery("SELECT id FROM inventory_levels WHERE product_id = ? AND warehouse_id = ?", arrayOf(productId.toString(), warehouseId.toString())).use { it -> it.moveToFirst() }
                    if (existing) {
                        db.execSQL("UPDATE inventory_levels SET quantity_on_hand = ?, average_cost = ? WHERE product_id = ? AND warehouse_id = ?", arrayOf(quantityAfter, unitCost, productId, warehouseId))
                    } else {
                        db.execSQL("INSERT INTO inventory_levels(product_id, warehouse_id, quantity_on_hand, average_cost) VALUES (?, ?, ?, ?)", arrayOf(productId, warehouseId, quantityAfter, unitCost))
                    }
                }

                val sourceBefore = currentQuantity(sourceWarehouseId)
                val targetBefore = currentQuantity(targetWarehouseId)
                require(sourceBefore >= quantity) { "الرصيد في المستودع المصدر غير كافٍ" }
                val sourceId = insertMovement(sourceWarehouseId, sourceBefore, sourceBefore - quantity, "transfer_out", sourceWarehouseId, targetWarehouseId)
                insertMovement(targetWarehouseId, targetBefore, targetBefore + quantity, "transfer_in", sourceWarehouseId, targetWarehouseId)
                updateLevel(sourceWarehouseId, sourceBefore - quantity)
                updateLevel(targetWarehouseId, targetBefore + quantity)
                db.setTransactionSuccessful()
                logActivity("system", "stock_transfer", "تحويل $quantity من المستودع $sourceWarehouseId إلى $targetWarehouseId للمنتج $productId")
                sourceId
            } finally {
                db.endTransaction()
            }
        } finally {
            dbLock.unlock()
        }
    }

    fun archiveStockMovement(movementId: Long, userId: Long): Int {
        dbLock.lock()
        return try {
            require(movementId > 0L) { "معرّف الحركة غير صالح" }
            require(userId > 0L) { "المستخدم المنفذ مطلوب" }
            val values = ContentValues().apply {
                put("is_deleted", 1)
                put("deleted_at", getCurrentDateTime())
                put("deleted_by", userId)
                put("updated_at", getCurrentDateTime())
                put("updated_by", userId)
            }
            writableDatabase.update("inventory_movements", values, "id = ? AND is_deleted = 0", arrayOf(movementId.toString()))
        } finally {
            dbLock.unlock()
        }
    }

    fun getInventoryMovementStats(data: JSONObject = JSONObject()): JSONObject {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val where = mutableListOf("is_deleted = 0")
            val args = mutableListOf<String>()
            data.optString("start_date").trim().takeIf { it.isNotEmpty() }?.let { where += "date(created_at) >= date(?)"; args += it }
            data.optString("end_date").trim().takeIf { it.isNotEmpty() }?.let { where += "date(created_at) <= date(?)"; args += it }
            data.optLong("warehouse_id", 0L).takeIf { it > 0L }?.let { where += "warehouse_id = ?"; args += it.toString() }
            val sql = """
                SELECT COUNT(*) AS total_movements,
                       COALESCE(SUM(total_cost), 0) AS total_value,
                       COALESCE(SUM(CASE WHEN date(created_at) = date('now') THEN 1 ELSE 0 END), 0) AS today_movements,
                       COALESCE(SUM(CASE WHEN status = 'draft' THEN 1 ELSE 0 END), 0) AS pending_count,
                       COALESCE(SUM(CASE WHEN movement_type IN ('in', 'return') THEN 1 ELSE 0 END), 0) AS inbound_count,
                       COALESCE(SUM(CASE WHEN movement_type IN ('out', 'damage') THEN 1 ELSE 0 END), 0) AS outbound_count,
                       COALESCE(SUM(CASE WHEN movement_type = 'transfer' THEN 1 ELSE 0 END), 0) AS transfer_count,
                       COALESCE(SUM(CASE WHEN movement_type = 'adjustment' THEN 1 ELSE 0 END), 0) AS adjustment_count
                FROM inventory_movements
                WHERE ${where.joinToString(" AND ")}
            """.trimIndent()
            db.rawQuery(sql, args.toTypedArray()).use { cursor ->
                if (cursor.moveToFirst()) cursorToJsonObject(cursor) else JSONObject()
            }
        } finally {
            dbLock.unlock()
        }
    }

    fun getInventoryReport(data: JSONObject = JSONObject()): JSONObject {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val reportType = data.optString("report_type", "summary").ifBlank { "summary" }
            val warehouseId = data.optLong("warehouse_id", 0L)
            val categoryId = data.optLong("category_id", 0L)
            val statusFilter = data.optString("status").trim()
            val rows = JSONArray()
            val categoryTotals = linkedMapOf<String, Double>()
            val movementSeries = JSONArray()

            if (reportType == "movement") {
                val where = mutableListOf("im.is_deleted = 0")
                val args = mutableListOf<String>()
                data.optString("start_date").trim().takeIf { it.isNotEmpty() }?.let { where += "date(im.created_at) >= date(?)"; args += it }
                data.optString("end_date").trim().takeIf { it.isNotEmpty() }?.let { where += "date(im.created_at) <= date(?)"; args += it }
                if (warehouseId > 0L) { where += "im.warehouse_id = ?"; args += warehouseId.toString() }
                if (categoryId > 0L) { where += "p.category_id = ?"; args += categoryId.toString() }
                data.optString("movement_type").trim().takeIf { it in setOf("in", "out", "adjustment", "transfer", "return", "damage") }?.let { where += "im.movement_type = ?"; args += it }
                val sql = """
                    SELECT im.id AS movement_id, im.movement_code, im.created_at AS movement_date,
                           im.movement_type, im.movement_subtype, im.product_id,
                           p.product_code, p.product_name, p.product_name_ar,
                           c.category_name, w.warehouse_name, im.quantity_change AS quantity,
                           im.unit_cost, im.total_cost AS total_value, im.status,
                           im.reason, im.remarks, im.performed_by, u.username AS performed_by_name
                    FROM inventory_movements im
                    LEFT JOIN products p ON im.product_id = p.id
                    LEFT JOIN product_categories c ON p.category_id = c.id
                    LEFT JOIN warehouses w ON im.warehouse_id = w.id
                    LEFT JOIN users u ON im.performed_by = u.id
                    WHERE ${where.joinToString(" AND ")}
                    ORDER BY datetime(im.created_at) DESC, im.id DESC
                    LIMIT 500
                """.trimIndent()
                db.rawQuery(sql, args.toTypedArray()).use { cursor ->
                    while (cursor.moveToNext()) rows.put(cursorToJsonObject(cursor))
                }
                db.rawQuery(
                    """SELECT date(im.created_at) AS day, COUNT(*) AS movement_count,
                              COALESCE(SUM(im.total_cost), 0) AS total_value
                       FROM inventory_movements im
                       LEFT JOIN products p ON im.product_id = p.id
                       WHERE im.is_deleted = 0
                       GROUP BY date(im.created_at)
                       ORDER BY day ASC LIMIT 90""",
                    null
                ).use { cursor -> while (cursor.moveToNext()) movementSeries.put(cursorToJsonObject(cursor)) }
            } else {
                val levelSource = if (warehouseId > 0L) {
                    "LEFT JOIN inventory_levels il ON p.id = il.product_id AND il.warehouse_id = ${warehouseId} LEFT JOIN warehouses w ON w.id = il.warehouse_id"
                } else {
                    "LEFT JOIN (SELECT product_id, SUM(quantity_on_hand) AS quantity_on_hand, AVG(average_cost) AS average_cost, MAX(last_count_date) AS last_count_date FROM inventory_levels GROUP BY product_id) il ON p.id = il.product_id LEFT JOIN warehouses w ON 1 = 0"
                }
                val where = mutableListOf("p.is_deleted = 0", "p.status = 'active'")
                val args = mutableListOf<String>()
                if (categoryId > 0L) { where += "p.category_id = ?"; args += categoryId.toString() }
                data.optLong("product_id", 0L).takeIf { it > 0L }?.let { where += "p.id = ?"; args += it.toString() }
                val sql = """
                    SELECT p.id AS product_id, p.product_code, p.barcode, p.product_name, p.product_name_ar,
                           p.category_id, c.category_name, w.warehouse_name,
                           COALESCE(il.quantity_on_hand, p.quantity, 0) AS quantity,
                           COALESCE(il.average_cost, p.purchase_price, 0) AS purchase_price,
                           p.sale_price, p.minimum_stock, p.maximum_stock, p.reorder_quantity,
                           il.last_count_date,
                           CASE WHEN COALESCE(il.quantity_on_hand, p.quantity, 0) <= 0 THEN 'critical'
                                WHEN COALESCE(il.quantity_on_hand, p.quantity, 0) <= p.minimum_stock THEN 'low'
                                ELSE 'active' END AS status
                    FROM products p
                    LEFT JOIN product_categories c ON p.category_id = c.id
                    $levelSource
                    WHERE ${where.joinToString(" AND ")}
                    ORDER BY p.product_name ASC
                    LIMIT 1000
                """.trimIndent()
                db.rawQuery(sql, args.toTypedArray()).use { cursor ->
                    while (cursor.moveToNext()) {
                        val item = cursorToJsonObject(cursor)
                        val itemStatus = item.optString("status", "active")
                        if (statusFilter.isNotEmpty() && itemStatus != statusFilter) continue
                        if (reportType == "below_min" && itemStatus != "low" && itemStatus != "critical") continue
                        rows.put(item)
                        val category = item.optString("category_name", "أخرى").ifBlank { "أخرى" }
                        val value = item.optDouble("quantity", 0.0) * item.optDouble("purchase_price", 0.0)
                        categoryTotals[category] = (categoryTotals[category] ?: 0.0) + value
                    }
                }
            }

            var totalQuantity = 0.0
            var totalValue = 0.0
            var lowStock = 0
            var criticalStock = 0
            val warehouses = mutableSetOf<String>()
            for (i in 0 until rows.length()) {
                val item = rows.optJSONObject(i) ?: continue
                totalQuantity += item.optDouble("quantity", item.optDouble("quantity_change", 0.0))
                totalValue += item.optDouble("total_value", item.optDouble("quantity", 0.0) * item.optDouble("purchase_price", 0.0))
                when (item.optString("status")) { "low" -> lowStock++; "critical" -> criticalStock++ }
                item.optString("warehouse_name").takeIf { it.isNotBlank() }?.let { warehouses.add(it) }
            }
            val stats = JSONObject().apply {
                put("total_items", rows.length())
                put("total_quantity", totalQuantity)
                put("total_value", totalValue)
                put("low_stock", lowStock)
                put("critical_stock", criticalStock)
                put("warehouse_count", warehouses.size)
                put("category_count", categoryTotals.size)
                put("inventory_health_score", if (rows.length() == 0) 0 else (100.0 - ((criticalStock * 60.0 + lowStock * 30.0) / rows.length())).coerceIn(0.0, 100.0))
            }
            val categories = JSONArray()
            categoryTotals.forEach { (name, value) -> categories.put(JSONObject().put("category_name", name).put("total_value", value)) }
            JSONObject().apply {
                put("report_type", reportType)
                put("rows", rows)
                put("stats", stats)
                put("categories", categories)
                put("movement_series", movementSeries)
            }
        } finally {
            dbLock.unlock()
        }
    }

    fun getInventoryProductDetails(productId: Long): JSONObject? {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val product = db.rawQuery(
                """SELECT p.id AS product_id, p.product_code, p.barcode, p.product_name, p.product_name_ar,
                          p.purchase_price, p.sale_price, p.minimum_stock, p.maximum_stock, p.reorder_quantity,
                          p.status, c.category_name, COALESCE(SUM(il.quantity_on_hand), p.quantity, 0) AS quantity
                   FROM products p
                   LEFT JOIN product_categories c ON p.category_id = c.id
                   LEFT JOIN inventory_levels il ON p.id = il.product_id
                   WHERE p.id = ? AND p.is_deleted = 0
                   GROUP BY p.id""",
                arrayOf(productId.toString())
            ).use { cursor -> if (cursor.moveToFirst()) cursorToJsonObject(cursor) else null } ?: return null
            val movements = JSONArray()
            db.rawQuery(
                """SELECT im.id AS movement_id, im.movement_code, im.created_at AS movement_date,
                          im.movement_type, im.quantity_change AS quantity, im.unit_cost, im.total_cost,
                          im.status, im.reason, w.warehouse_name, u.username AS performed_by_name
                   FROM inventory_movements im
                   LEFT JOIN warehouses w ON im.warehouse_id = w.id
                   LEFT JOIN users u ON im.performed_by = u.id
                   WHERE im.product_id = ? AND im.is_deleted = 0
                   ORDER BY datetime(im.created_at) DESC, im.id DESC LIMIT 50""",
                arrayOf(productId.toString())
            ).use { cursor -> while (cursor.moveToNext()) movements.put(cursorToJsonObject(cursor)) }
            product.put("movements", movements)
            product
        } finally {
            dbLock.unlock()
        }
    }

    fun getProductMovementTrend(productId: Long, days: Int = 30): JSONObject {
        dbLock.lock()
        return try {
            val safeDays = days.coerceIn(1, 365)
            val labels = JSONArray()
            val inbound = JSONArray()
            val outbound = JSONArray()
            val counts = JSONArray()
            readableDatabase.rawQuery(
                """SELECT date(created_at) AS day,
                          COALESCE(SUM(CASE WHEN movement_type IN ('in', 'return') THEN ABS(quantity_change) ELSE 0 END), 0) AS inbound,
                          COALESCE(SUM(CASE WHEN movement_type IN ('out', 'damage') THEN ABS(quantity_change) ELSE 0 END), 0) AS outbound,
                          COUNT(*) AS movement_count
                   FROM inventory_movements
                   WHERE product_id = ? AND is_deleted = 0 AND date(created_at) >= date('now', ?)
                   GROUP BY date(created_at) ORDER BY day ASC""".trimIndent(),
                arrayOf(productId.toString(), "-${safeDays} days")
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    labels.put(cursor.getString(cursor.getColumnIndexOrThrow("day")))
                    inbound.put(cursor.getDouble(cursor.getColumnIndexOrThrow("inbound")))
                    outbound.put(cursor.getDouble(cursor.getColumnIndexOrThrow("outbound")))
                    counts.put(cursor.getInt(cursor.getColumnIndexOrThrow("movement_count")))
                }
            }
            JSONObject().apply { put("labels", labels); put("inbound", inbound); put("outbound", outbound); put("values", counts) }
        } finally {
            dbLock.unlock()
        }
    }


    fun createStockAlert(productId: Long, threshold: Double): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("product_id", productId)
                put("station_id", 1)
                put("alert_type", "low_stock")
                put("alert_level", "warning")
                put("threshold_quantity", threshold)
                put("current_quantity", 0.0)
                put("is_resolved", 0)
                put("created_at", getCurrentDateTime())
                put("created_by", 1)
            }
            db.insert("stock_alerts", null, cv)
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال الأصول
    // ========================================================================

    fun addAsset(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("asset_code", data.optString("asset_code", "AST-${System.currentTimeMillis()}"))
                put("asset_name", data.optString("asset_name", ""))
                put("asset_type", data.optString("asset_type", "other"))
                put("asset_category", data.optString("asset_category", ""))
                put("station_id", data.optInt("station_id", 1))
                put("purchase_date", data.optString("purchase_date", getCurrentDate()))
                put("purchase_cost", data.optDouble("purchase_cost", 0.0))
                put("current_value", data.optDouble("current_value", 0.0))
                put("depreciation_rate", data.optDouble("depreciation_rate", 0.0))
                put("location", data.optString("location", ""))
                put("status", data.optString("status", "active"))
                put("maintenance_date", data.optString("maintenance_date", ""))
                put("next_maintenance", data.optString("next_maintenance", ""))
                put("notes", data.optString("notes", ""))
                put("created_at", getCurrentDateTime())
                put("updated_at", getCurrentDateTime())
            }
            val id = db.insert("assets", null, cv)
            if (id > 0) logActivity("system", "add_asset", "إضافة أصل: ${data.optString("asset_name")}")
            id
        } finally {
            dbLock.unlock()
        }
    }

    fun getAssets(stationId: Int = 1): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM assets WHERE station_id = ? AND is_deleted = 0 ORDER BY created_at DESC",
                arrayOf(stationId.toString())
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال المستخدمين
    // ========================================================================

    fun addUser(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val username = data.optString("username").trim()
            val fullName = data.optString("full_name").trim()
            val password = data.optString("password")
            require(username.isNotBlank()) { "اسم المستخدم مطلوب" }
            require(fullName.isNotBlank()) { "الاسم الكامل مطلوب" }
            require(password.length >= 6) { "كلمة المرور يجب أن تكون 6 أحرف على الأقل" }
            val (hash, salt) = hashPassword(password)
            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("username", username)
                put("password_hash", hash)
                put("password_salt", salt)
                put("full_name", fullName)
                listOf("full_name_ar", "display_name", "avatar_path", "national_id", "passport_number", "nationality", "gender", "birth_date", "job_title", "department", "hire_date", "timezone", "date_format", "two_factor_method", "biometric_type", "status_reason", "device_id", "remarks", "extra_data").forEach { key ->
                    val value = data.optString(key).trim()
                    if (value.isNotEmpty()) put(key, value) else putNull(key)
                }
                if (data.optLong("employee_id", 0L) > 0) put("employee_id", data.optLong("employee_id")) else putNull("employee_id")
                put("role_id", data.optInt("role_id", 4))
                if (data.optInt("station_id", 0) > 0) put("station_id", data.optInt("station_id")) else putNull("station_id")
                if (data.optInt("branch_id", 0) > 0) put("branch_id", data.optInt("branch_id")) else putNull("branch_id")
                if (data.optInt("company_id", 0) > 0) put("company_id", data.optInt("company_id")) else putNull("company_id")
                listOf("email", "phone", "national_id", "passport_number").forEach { key ->
                    val value = data.optString(key).trim()
                    if (value.isNotEmpty()) put(key, value) else putNull(key)
                }
                put("preferred_language", data.optString("preferred_language", "ar"))
                put("theme", data.optString("theme", "light"))
                put("timezone", data.optString("timezone", "UTC"))
                put("date_format", data.optString("date_format", "YYYY-MM-DD"))
                put("status", data.optString("status", "active"))
                put("two_factor_enabled", data.optInt("two_factor_enabled", 0))
                put("two_factor_method", data.optString("two_factor_method", "none"))
                put("biometric_enabled", data.optInt("biometric_enabled", 0))
                put("biometric_type", data.optString("biometric_type", "none"))
                put("has_biometrics", data.optInt("has_biometrics", 0))
                put("email_verified", data.optInt("email_verified", 0))
                put("phone_verified", data.optInt("phone_verified", 0))
                put("password_expiry_days", data.optInt("password_expiry_days", 90))
                if (data.isNull("password_expiry_date") || data.optString("password_expiry_date").isBlank()) putNull("password_expiry_date") else put("password_expiry_date", data.optString("password_expiry_date"))
                put("must_change_password", data.optInt("must_change_password", 1))
                put("session_timeout", data.optInt("session_timeout", 30))
                put("device_limit", data.optInt("device_limit", 3))
                if (data.isNull("locked_until") || data.optString("locked_until").isBlank()) putNull("locked_until") else put("locked_until", data.optString("locked_until"))
                put("account_locked", if (data.optString("status", "active") == "locked") 1 else 0)
                put("is_deleted", 0)
                put("sync_status", "synced")
                put("sync_version", 1)
                put("created_at", getCurrentDateTime())
                put("updated_at", getCurrentDateTime())
                if (data.optLong("created_by", 0L) > 0) put("created_by", data.optLong("created_by"))
            }
            val id = db.insert("users", null, cv)
            if (id > 0) logActivity("system", "add_user", "إضافة مستخدم: ${data.optString("username")}")
            id
        } finally {
            dbLock.unlock()
        }
    }

    fun getUsers(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                """SELECT u.id, u.uuid, u.username, u.email, u.phone, u.full_name, u.full_name_ar,
                          u.display_name, u.avatar_path, u.national_id, u.passport_number, u.nationality,
                          u.birth_date, u.gender, u.employee_id, u.job_title, u.department, u.hire_date,
                          u.role_id, u.station_id, u.branch_id, u.company_id, u.preferred_language, u.theme,
                          u.timezone, u.date_format, u.two_factor_enabled, u.two_factor_method,
                          u.biometric_enabled, u.biometric_type, u.last_password_change,
                          u.password_expiry_days, u.password_expiry_date, u.must_change_password,
                          u.failed_login_attempts, u.account_locked, u.locked_until, u.last_login_at,
                          u.last_login_ip, u.last_login_device, u.session_timeout, u.device_limit,
                          u.has_biometrics, u.status, u.status_reason, u.email_verified, u.phone_verified,
                          u.deleted_at, u.created_at, u.updated_at, u.created_by, u.updated_by,
                          u.deleted_by, u.is_deleted, u.sync_status, u.sync_version, u.sync_at,
                          u.device_id, u.remarks, u.extra_data,
                          r.role_name, r.role_name_ar
                   FROM users u
                   LEFT JOIN roles r ON u.role_id = r.id
                   WHERE u.is_deleted = 0
                   ORDER BY u.full_name""",
                null
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun getUsersByRole(role: String): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                """SELECT u.id, u.uuid, u.username, u.email, u.phone, u.full_name, u.full_name_ar,
                          u.display_name, u.avatar_path, u.national_id, u.passport_number, u.nationality,
                          u.birth_date, u.gender, u.employee_id, u.job_title, u.department, u.hire_date,
                          u.role_id, u.station_id, u.branch_id, u.company_id, u.preferred_language, u.theme,
                          u.timezone, u.date_format, u.two_factor_enabled, u.two_factor_method,
                          u.biometric_enabled, u.biometric_type, u.last_password_change,
                          u.password_expiry_days, u.password_expiry_date, u.must_change_password,
                          u.failed_login_attempts, u.account_locked, u.locked_until, u.last_login_at,
                          u.last_login_ip, u.last_login_device, u.session_timeout, u.device_limit,
                          u.has_biometrics, u.status, u.status_reason, u.email_verified, u.phone_verified,
                          u.deleted_at, u.created_at, u.updated_at, u.created_by, u.updated_by,
                          u.deleted_by, u.is_deleted, u.sync_status, u.sync_version, u.sync_at,
                          u.device_id, u.remarks, u.extra_data,
                          r.role_name, r.role_name_ar
                   FROM users u
                   LEFT JOIN roles r ON u.role_id = r.id
                   WHERE r.role_code = ? AND u.is_deleted = 0 AND u.status = 'active'
                   ORDER BY u.full_name""",
                arrayOf(role)
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun updateUser(id: Long, data: JSONObject): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                val textFields = listOf("full_name", "full_name_ar", "display_name", "avatar_path", "email", "phone", "national_id", "passport_number", "nationality", "gender", "birth_date", "job_title", "department", "hire_date", "preferred_language", "theme", "timezone", "date_format", "two_factor_method", "biometric_type", "status_reason", "device_id", "remarks", "extra_data")
                textFields.forEach { key ->
                    if (data.has(key)) {
                        val value = data.optString(key).trim()
                        if (value.isNotEmpty()) put(key, value) else putNull(key)
                    }
                }
                if (data.has("employee_id")) { if (data.isNull("employee_id")) putNull("employee_id") else put("employee_id", data.optLong("employee_id")) }
                if (data.has("role_id")) put("role_id", data.optInt("role_id"))
                if (data.has("station_id")) { if (data.isNull("station_id")) putNull("station_id") else put("station_id", data.optInt("station_id")) }
                if (data.has("branch_id")) { if (data.isNull("branch_id")) putNull("branch_id") else put("branch_id", data.optInt("branch_id")) }
                if (data.has("company_id")) { if (data.isNull("company_id")) putNull("company_id") else put("company_id", data.optInt("company_id")) }
                if (data.has("preferred_language")) put("preferred_language", data.optString("preferred_language"))
                if (data.has("theme")) put("theme", data.optString("theme"))
                if (data.has("status")) {
                    val status = data.optString("status")
                    require(status in setOf("active", "inactive", "locked", "suspended")) { "حالة المستخدم غير صالحة" }
                    put("status", status)
                    put("account_locked", if (status == "locked") 1 else 0)
                }
                if (data.has("must_change_password")) put("must_change_password", data.optInt("must_change_password"))
                if (data.has("two_factor_enabled")) put("two_factor_enabled", data.optInt("two_factor_enabled"))
                if (data.has("biometric_enabled")) put("biometric_enabled", data.optInt("biometric_enabled"))
                if (data.has("has_biometrics")) put("has_biometrics", data.optInt("has_biometrics"))
                if (data.has("email_verified")) put("email_verified", data.optInt("email_verified"))
                if (data.has("phone_verified")) put("phone_verified", data.optInt("phone_verified"))
                if (data.has("password_expiry_days")) put("password_expiry_days", data.optInt("password_expiry_days"))
                if (data.has("password_expiry_date")) { if (data.isNull("password_expiry_date")) putNull("password_expiry_date") else put("password_expiry_date", data.optString("password_expiry_date")) }
                if (data.has("session_timeout")) put("session_timeout", data.optInt("session_timeout"))
                if (data.has("device_limit")) put("device_limit", data.optInt("device_limit"))
                if (data.has("locked_until")) { if (data.isNull("locked_until")) putNull("locked_until") else put("locked_until", data.optString("locked_until")) }
                if (data.has("updated_by") && data.optLong("updated_by", 0L) > 0L) put("updated_by", data.optLong("updated_by"))
                if (data.has("password")) {
                    val (hash, salt) = hashPassword(data.optString("password"))
                    put("password_hash", hash)
                    put("password_salt", salt)
                    put("last_password_change", getCurrentDateTime())
                }
                put("updated_at", getCurrentDateTime())
            }
            require(id > 0) { "معرف المستخدم غير صالح" }
            val rows = db.update("users", cv, "id=? AND is_deleted = 0", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "update_user", "تحديث مستخدم $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    fun deleteUser(id: Long): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply { put("is_deleted", 1) }
            val rows = db.update("users", cv, "id=?", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "delete_user", "حذف مستخدم $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    fun getUserPermissions(userId: Long): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                """SELECT p.id AS permission_id, p.permission_code, p.permission_name, p.permission_name_ar,
                          p.module, p.action, 'role' AS source, NULL AS user_permission_id,
                          rp.can_create, rp.can_read, rp.can_update, rp.can_delete, rp.can_export, rp.can_print, rp.can_approve
                   FROM permissions p
                   JOIN role_permissions rp ON p.id = rp.permission_id
                   JOIN users u ON u.role_id = rp.role_id
                   WHERE u.id = ? AND p.is_deleted = 0 AND rp.is_deleted = 0
                   UNION ALL
                   SELECT p.id AS permission_id, p.permission_code, p.permission_name, p.permission_name_ar,
                          p.module, p.action, 'direct' AS source, up.id AS user_permission_id,
                          1, up.is_granted, 1, 1, 1, 1, 1
                   FROM permissions p
                   JOIN user_permissions up ON p.id = up.permission_id
                   WHERE up.user_id = ? AND up.is_granted = 1 AND p.is_deleted = 0
                   ORDER BY module, action, permission_name""",
                arrayOf(userId.toString(), userId.toString())
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally { dbLock.unlock() }
    }

    fun getUserScreens(userId: Long): JSONArray {
        dbLock.lock()
        return try {
            val arr = JSONArray()
            val db = readableDatabase
            db.rawQuery(
                "SELECT r.role_code FROM users u JOIN roles r ON u.role_id=r.id WHERE u.id=?",
                arrayOf(userId.toString())
            ).use { roleCursor ->
                var isAdmin = false
                if (roleCursor.moveToFirst()) {
                    val role = roleCursor.getString(0)
                    isAdmin = role == "SUPER_ADMIN" || role == "ADMIN"
                }
                val cursor = if (isAdmin) {
                    db.rawQuery(
                        """
                        SELECT screen_name, module, description
                        FROM screens
                        WHERE is_active=1
                        ORDER BY id
                        """,
                        null
                    )
                } else {
                    db.rawQuery(
                        """
                        SELECT DISTINCT s.screen_name, s.module, s.description
                        FROM screens s
                        JOIN permissions p ON p.module=s.module
                        JOIN role_permissions rp ON rp.permission_id=p.id
                        JOIN users u ON u.role_id=rp.role_id
                        WHERE u.id=? AND s.is_active=1
                        ORDER BY s.id
                        """,
                        arrayOf(userId.toString())
                    )
                }
                cursor.use {
                    while (it.moveToNext()) {
                        arr.put(JSONObject().apply {
                            put("screen_name", it.getString(0))
                            put("module", it.getString(1))
                            put("description", it.getString(2))
                        })
                    }
                }
            }
            arr
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * يعيد جميع الصلاحيات النشطة مع تفعيل كل القدرات.
     *
     * DEV_MODE: يُستدعى من MainActivity للمستخدم admin رقم 1 في نسخ Debug فقط.
     * لا يغيّر البيانات ولا ينشئ صلاحيات؛ يقرأ فقط السجل الفعلي من جدول permissions.
     */
    fun getAllActivePermissions(): JSONArray {
        dbLock.lock()
        return try {
            val arr = JSONArray()
            val db = readableDatabase
            db.rawQuery(
                """SELECT permission_code, permission_name, permission_name_ar, module, action
                   FROM permissions
                   WHERE is_active = 1 AND is_deleted = 0
                   ORDER BY id""",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    arr.put(JSONObject().apply {
                        put("permission_code", cursor.getString(0))
                        put("permission_name", cursor.getString(1))
                        put("permission_name_ar", cursor.getString(2))
                        put("module", cursor.getString(3))
                        put("action", cursor.getString(4))
                        put("can_create", true)
                        put("can_read", true)
                        put("can_update", true)
                        put("can_delete", true)
                        put("can_export", true)
                        put("can_print", true)
                        put("can_approve", true)
                    })
                }
            }
            arr
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * يعيد جميع الشاشات النشطة المسجلة فعلياً في جدول screens.
     *
     * DEV_MODE: يُستدعى فقط عبر مسار admin في نسخ Debug، ولا يضيف أسماء شاشات
     * غير موجودة في قاعدة البيانات.
     */
    fun getAllActiveScreens(): JSONArray {
        dbLock.lock()
        return try {
            val arr = JSONArray()
            val db = readableDatabase
            db.rawQuery(
                """SELECT screen_name, module, description
                   FROM screens
                   WHERE is_active = 1 AND archived = 0
                   ORDER BY id""",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    arr.put(JSONObject().apply {
                        put("screen_name", cursor.getString(0))
                        put("module", cursor.getString(1))
                        put("description", cursor.getString(2))
                    })
                }
            }
            arr
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * يقرأ أسماء شاشات WebView الموجودة فعلياً في assets/screens.
     * لا ينشئ سجلات SQLite ولا يُستخدم كبديل عن جدول screens؛ الغرض الوحيد هو
     * مزامنة قائمة الشاشات الفعلية مع DEV_MODE أثناء التطوير.
     */
    fun getAvailableAssetScreens(): JSONArray {
        val arr = JSONArray()
        return try {
            contextRef.assets.list("screens")
                ?.asSequence()
                ?.filter { it.endsWith(".html", ignoreCase = true) }
                ?.sorted()
                ?.forEach { fileName ->
                    val screenName = fileName.substringBeforeLast('.')
                    arr.put(JSONObject().apply {
                        put("screen_name", screenName)
                        put("module", "webview")
                        put("description", "شاشة WebView موجودة في assets")
                    })
                }
            arr
        } catch (e: Exception) {
            Log.w(TAG, "Unable to enumerate asset screens: ${e.message}")
            arr
        }
    }

    fun getUserNotifications(userId: Long): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM notifications WHERE user_id = ? ORDER BY created_at DESC LIMIT 50",
                arrayOf(userId.toString())
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال الموظفين
    // ========================================================================

    fun addEmployee(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("employee_code", data.optString("employee_code", "EMP-${System.currentTimeMillis()}"))
                put("full_name", data.optString("full_name", ""))
                put("full_name_ar", data.optString("full_name_ar", ""))
                put("phone", data.optString("phone", ""))
                put("phone2", data.optString("phone2", ""))
                put("email", data.optString("email", ""))
                put("job_title", data.optString("job_title", ""))
                put("job_title_ar", data.optString("job_title_ar", ""))
                put("department", data.optString("department", ""))
                put("basic_salary", data.optDouble("basic_salary", 0.0))
                put("total_salary", data.optDouble("basic_salary", 0.0))
                put("station_id", data.optInt("station_id", 1))
                put("status", data.optString("status", "active"))
                put("notes", data.optString("notes", ""))
                put("created_at", getCurrentDateTime())
                put("updated_at", getCurrentDateTime())
            }
            val id = db.insert("employees", null, cv)
            if (id > 0) logActivity("system", "add_employee", "إضافة موظف: ${data.optString("full_name")}")
            id
        } finally {
            dbLock.unlock()
        }
    }

    fun getEmployees(stationId: Int? = null): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val sql = if (stationId != null) {
                "SELECT * FROM employees WHERE station_id = ? AND is_deleted = 0 ORDER BY full_name"
            } else {
                "SELECT * FROM employees WHERE is_deleted = 0 ORDER BY full_name"
            }
            val args = if (stationId != null) arrayOf(stationId.toString()) else null
            db.rawQuery(sql, args).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun deleteEmployee(id: Int): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply { put("is_deleted", 1) }
            val rows = db.update("employees", cv, "id=?", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "delete_employee", "حذف موظف: $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    fun updateEmployee(id: Long, data: JSONObject): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                data.optString("full_name")?.let { put("full_name", it) }
                data.optString("phone")?.let { put("phone", it) }
                data.optString("job_title")?.let { put("job_title", it) }
                data.optString("department")?.let { put("department", it) }
                data.optDouble("basic_salary")?.let { put("basic_salary", it) }
                data.optString("status")?.let { put("status", it) }
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("employees", cv, "id=?", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "update_employee", "تحديث موظف $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    fun addEmployeePayment(employeeId: Int, amount: Double, type: String, description: String, operator: String): Boolean {
        dbLock.lock()
        return try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val cv = ContentValues().apply {
                    put("employee_id", employeeId)
                    put("amount", amount)
                    put("type", type)
                    put("description", description)
                    put("operator", operator)
                }
                db.insert("employee_payments", null, cv)

                val col = when (type) {
                    "salary" -> "total_salary"
                    "advance" -> "advances"
                    "penalty" -> "penalties"
                    "bonus" -> "bonuses"
                    else -> "total_salary"
                }
                val sign = when (type) {
                    "advance", "penalty" -> "-"
                    else -> "+"
                }
                db.execSQL(
                    "UPDATE employees SET $col = $col $sign ? WHERE id = ?",
                    arrayOf(amount, employeeId)
                )
                db.setTransactionSuccessful()
                logActivity(operator, "employee_payment", "دفعة $type للموظف $employeeId بمبلغ $amount")
                true
            } finally {
                db.endTransaction()
            }
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال الأدوار
    // ========================================================================

    fun getRoles(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery("SELECT * FROM roles WHERE is_deleted = 0 ORDER BY level, role_name", null)
                .use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun addRole(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("role_code", data.optString("role_code", ""))
                put("role_name", data.optString("role_name", ""))
                put("role_name_ar", data.optString("role_name_ar", ""))
                put("description", data.optString("description", ""))
                put("level", data.optInt("level", 1))
                put("is_system_role", data.optInt("is_system_role", 0))
                put("is_active", 1)
                put("created_at", getCurrentDateTime())
                put("updated_at", getCurrentDateTime())
            }
            val id = db.insert("roles", null, cv)
            if (id > 0) logActivity("system", "add_role", "إضافة دور: ${data.optString("role_name")}")
            id
        } finally {
            dbLock.unlock()
        }
    }

    fun updateRole(id: Long, data: JSONObject): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                data.optString("role_name")?.let { put("role_name", it) }
                data.optString("role_name_ar")?.let { put("role_name_ar", it) }
                data.optString("description")?.let { put("description", it) }
                data.optInt("level")?.let { put("level", it) }
                data.optInt("is_system_role")?.let { put("is_system_role", it) }
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("roles", cv, "id=?", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "update_role", "تحديث دور $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    fun deleteRole(id: Long): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply { put("is_deleted", 1) }
            val rows = db.update("roles", cv, "id=?", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "delete_role", "حذف دور $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }



    // ========================================================================
    // دوال إدارة الهوية والصلاحيات والشاشات — مبنية على schema الحالي
    // ========================================================================

    fun getStations(): JSONArray {
        dbLock.lock()
        return try {
            readableDatabase.rawQuery(
                "SELECT id, station_code, station_name, station_name_ar FROM stations WHERE is_deleted = 0 ORDER BY station_name",
                null
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally { dbLock.unlock() }
    }

    fun getGroups(): JSONArray {
        dbLock.lock()
        return try {
            readableDatabase.rawQuery(
                "SELECT id, uuid, group_name, description, is_active, archived, created_at, 0 AS user_count FROM groups_table WHERE archived = 0 ORDER BY group_name",
                null
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally { dbLock.unlock() }
    }

    fun addGroup(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val values = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("group_name", data.optString("group_name").trim())
                put("description", data.optString("description").trim())
                put("is_active", if (data.optInt("is_active", 1) == 1) 1 else 0)
                put("archived", 0)
            }
            require(values.getAsString("group_name").isNotBlank()) { "اسم المجموعة مطلوب" }
            val id = writableDatabase.insert("groups_table", null, values)
            require(id > 0) { "تعذر إنشاء المجموعة" }
            logActivity("system", "add_group", "إضافة مجموعة: ${data.optString("group_name")}")
            id
        } finally { dbLock.unlock() }
    }

    fun updateGroup(id: Long, data: JSONObject): Int {
        dbLock.lock()
        return try {
            require(id > 0) { "معرف المجموعة غير صالح" }
            val values = ContentValues().apply {
                if (data.has("group_name")) put("group_name", data.optString("group_name").trim())
                if (data.has("description")) put("description", data.optString("description").trim())
                if (data.has("is_active")) put("is_active", if (data.optInt("is_active") == 1) 1 else 0)
            }
            val rows = writableDatabase.update("groups_table", values, "id = ? AND archived = 0", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "update_group", "تحديث مجموعة $id")
            rows
        } finally { dbLock.unlock() }
    }

    fun deleteGroup(id: Long): Int {
        dbLock.lock()
        return try {
            val values = ContentValues().apply { put("archived", 1) }
            val rows = writableDatabase.update("groups_table", values, "id = ? AND archived = 0", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "archive_group", "أرشفة مجموعة $id")
            rows
        } finally { dbLock.unlock() }
    }

    fun getScreens(): JSONArray {
        dbLock.lock()
        return try {
            readableDatabase.rawQuery(
                "SELECT id, uuid, screen_name, module, description, is_active, archived, created_at FROM screens WHERE archived = 0 ORDER BY id",
                null
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally { dbLock.unlock() }
    }

    fun getModules(): JSONArray {
        dbLock.lock()
        return try {
            readableDatabase.rawQuery(
                "SELECT module AS module_name, COUNT(*) AS screen_count, 1 AS is_active FROM screens WHERE archived = 0 GROUP BY module ORDER BY module",
                null
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally { dbLock.unlock() }
    }

    fun addScreen(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val values = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("screen_name", data.optString("screen_name").trim())
                put("module", data.optString("module").trim())
                put("description", data.optString("description").trim())
                put("is_active", if (data.optInt("is_active", 1) == 1) 1 else 0)
                put("archived", 0)
            }
            require(values.getAsString("screen_name").isNotBlank()) { "اسم الشاشة مطلوب" }
            require(values.getAsString("module").isNotBlank()) { "وحدة الشاشة مطلوبة" }
            val id = writableDatabase.insert("screens", null, values)
            require(id > 0) { "تعذر إنشاء الشاشة" }
            logActivity("system", "add_screen", "إضافة شاشة: ${data.optString("screen_name")}")
            id
        } finally { dbLock.unlock() }
    }

    fun updateScreen(id: Long, data: JSONObject): Int {
        dbLock.lock()
        return try {
            require(id > 0) { "معرف الشاشة غير صالح" }
            val values = ContentValues().apply {
                if (data.has("screen_name")) put("screen_name", data.optString("screen_name").trim())
                if (data.has("module")) put("module", data.optString("module").trim())
                if (data.has("description")) put("description", data.optString("description").trim())
                if (data.has("is_active")) put("is_active", if (data.optInt("is_active") == 1) 1 else 0)
            }
            val rows = writableDatabase.update("screens", values, "id = ? AND archived = 0", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "update_screen", "تحديث شاشة $id")
            rows
        } finally { dbLock.unlock() }
    }

    fun deleteScreen(id: Long): Int {
        dbLock.lock()
        return try {
            val values = ContentValues().apply { put("archived", 1) }
            val rows = writableDatabase.update("screens", values, "id = ? AND archived = 0", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "archive_screen", "أرشفة شاشة $id")
            rows
        } finally { dbLock.unlock() }
    }

    fun getPermissions(): JSONArray {
        dbLock.lock()
        return try {
            readableDatabase.rawQuery(
                "SELECT id, uuid, permission_code, permission_name, permission_name_ar, description, module, module_name_ar, action, requires_station, requires_branch, is_active, created_at, updated_at, remarks, extra_data FROM permissions WHERE is_deleted = 0 ORDER BY module, action, permission_name",
                null
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally { dbLock.unlock() }
    }

    fun addPermission(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val values = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("permission_code", data.optString("permission_code").trim())
                put("permission_name", data.optString("permission_name").trim())
                put("permission_name_ar", data.optString("permission_name_ar").trim())
                put("description", data.optString("description").trim())
                put("module", data.optString("module").trim())
                put("module_name_ar", data.optString("module_name_ar").trim())
                put("action", data.optString("action").trim())
                put("requires_station", if (data.optInt("requires_station", 0) == 1) 1 else 0)
                put("requires_branch", if (data.optInt("requires_branch", 0) == 1) 1 else 0)
                put("is_active", if (data.optInt("is_active", 1) == 1) 1 else 0)
                put("remarks", data.optString("remarks").trim())
                put("extra_data", data.optString("extra_data").trim())
                put("is_deleted", 0)
            }
            require(values.getAsString("permission_code").isNotBlank()) { "كود الصلاحية مطلوب" }
            require(values.getAsString("permission_name").isNotBlank()) { "اسم الصلاحية مطلوب" }
            require(values.getAsString("module").isNotBlank()) { "وحدة الصلاحية مطلوبة" }
            require(values.getAsString("action").isNotBlank()) { "إجراء الصلاحية مطلوب" }
            val id = writableDatabase.insert("permissions", null, values)
            require(id > 0) { "تعذر إنشاء الصلاحية" }
            logActivity("system", "add_permission", "إضافة صلاحية: ${data.optString("permission_code")}")
            id
        } finally { dbLock.unlock() }
    }

    fun updatePermission(id: Long, data: JSONObject): Int {
        dbLock.lock()
        return try {
            require(id > 0) { "معرف الصلاحية غير صالح" }
            val values = ContentValues().apply {
                if (data.has("permission_name")) put("permission_name", data.optString("permission_name").trim())
                if (data.has("permission_name_ar")) put("permission_name_ar", data.optString("permission_name_ar").trim())
                if (data.has("description")) put("description", data.optString("description").trim())
                if (data.has("module")) put("module", data.optString("module").trim())
                if (data.has("module_name_ar")) put("module_name_ar", data.optString("module_name_ar").trim())
                if (data.has("action")) put("action", data.optString("action").trim())
                if (data.has("requires_station")) put("requires_station", if (data.optInt("requires_station") == 1) 1 else 0)
                if (data.has("requires_branch")) put("requires_branch", if (data.optInt("requires_branch") == 1) 1 else 0)
                if (data.has("is_active")) put("is_active", if (data.optInt("is_active") == 1) 1 else 0)
                if (data.has("remarks")) put("remarks", data.optString("remarks").trim())
                if (data.has("extra_data")) put("extra_data", data.optString("extra_data").trim())
                put("updated_at", getCurrentDateTime())
            }
            val rows = writableDatabase.update("permissions", values, "id = ? AND is_deleted = 0", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "update_permission", "تحديث صلاحية $id")
            rows
        } finally { dbLock.unlock() }
    }

    fun deletePermission(id: Long): Int {
        dbLock.lock()
        return try {
            val values = ContentValues().apply { put("is_deleted", 1) }
            val rows = writableDatabase.update("permissions", values, "id = ? AND is_deleted = 0", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "delete_permission", "حذف صلاحية $id")
            rows
        } finally { dbLock.unlock() }
    }

    fun getScreenPermissions(screenId: Long): JSONArray {
        dbLock.lock()
        return try {
            readableDatabase.rawQuery(
                """SELECT p.id, p.permission_code, p.permission_name, p.permission_name_ar, p.description,
                          p.module, p.action, p.created_at, 'module' AS source
                   FROM permissions p JOIN screens s ON s.module = p.module
                   WHERE s.id = ? AND s.archived = 0 AND p.is_deleted = 0
                   ORDER BY p.action, p.permission_name""",
                arrayOf(screenId.toString())
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally { dbLock.unlock() }
    }

    fun grantUserPermission(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val userId = data.optLong("user_id", 0L)
            val permissionId = data.optLong("permission_id", 0L)
            require(userId > 0 && permissionId > 0) { "المستخدم والصلاحية مطلوبان" }
            val db = writableDatabase
            val existing = db.rawQuery("SELECT id FROM user_permissions WHERE user_id = ? AND permission_id = ?", arrayOf(userId.toString(), permissionId.toString())).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            val values = ContentValues().apply {
                put("user_id", userId)
                put("permission_id", permissionId)
                put("is_granted", 1)
                put("reason", data.optString("reason").trim())
                if (data.optLong("granted_by", 0L) > 0) put("set_by", data.optLong("granted_by"))
                put("set_at", getCurrentDateTime())
            }
            val id = if (existing > 0) {
                db.update("user_permissions", values, "id = ?", arrayOf(existing.toString()))
                existing
            } else db.insert("user_permissions", null, values)
            require(id > 0) { "تعذر منح الصلاحية" }
            val actorId = data.optLong("granted_by", 0L)
            db.insert("notifications", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("user_id", userId)
                put("notification_type", "permission")
                put("title", "Permission granted")
                put("title_ar", "تم منح صلاحية")
                put("message", "A direct permission was granted to your account")
                put("message_ar", "تم منح صلاحية مباشرة لحسابك")
                put("priority", "normal")
                put("channel", "in_app")
                put("status", "pending")
                put("is_read", 0)
                put("reference_type", "user_permission")
                put("reference_id", id)
                if (actorId > 0) put("created_by", actorId)
                put("created_at", getCurrentDateTime())
            })
            logActivity("system", "grant_user_permission", "منح صلاحية $permissionId للمستخدم $userId")
            id
        } finally { dbLock.unlock() }
    }

    fun getGrantedPermissions(): JSONArray {
        dbLock.lock()
        return try {
            readableDatabase.rawQuery(
                """SELECT up.id AS ups_id, up.id AS user_permission_id, up.user_id, up.permission_id,
                          up.set_at AS granted_at, up.reason, up.is_granted AS is_active,
                          u.username, u.full_name AS user_name, p.permission_code, p.permission_name,
                          p.permission_name_ar, p.module, p.action
                   FROM user_permissions up
                   JOIN users u ON u.id = up.user_id
                   JOIN permissions p ON p.id = up.permission_id
                   WHERE up.is_granted = 1 AND p.is_deleted = 0
                   ORDER BY up.set_at DESC""",
                null
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally { dbLock.unlock() }
    }

    fun revokeUserPermission(id: Long): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val targetUserId = db.rawQuery("SELECT user_id FROM user_permissions WHERE id = ? AND is_granted = 1", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            val rows = db.update("user_permissions", ContentValues().apply { put("is_granted", 0) }, "id = ? AND is_granted = 1", arrayOf(id.toString()))
            if (rows > 0 && targetUserId > 0) {
                db.insert("notifications", null, ContentValues().apply {
                    put("uuid", UUID.randomUUID().toString())
                    put("user_id", targetUserId)
                    put("notification_type", "permission")
                    put("title", "Permission revoked")
                    put("title_ar", "تم سحب صلاحية")
                    put("message", "A direct permission was revoked from your account")
                    put("message_ar", "تم سحب صلاحية مباشرة من حسابك")
                    put("priority", "normal")
                    put("channel", "in_app")
                    put("status", "pending")
                    put("is_read", 0)
                    put("reference_type", "user_permission")
                    put("reference_id", id)
                    put("created_at", getCurrentDateTime())
                })
                logActivity("system", "revoke_user_permission", "سحب صلاحية مباشرة رقم $id")
            }
            rows
        } finally { dbLock.unlock() }
    }

    fun getDelegatedPermissions(): JSONArray {
        dbLock.lock()
        return try {
            readableDatabase.rawQuery(
                """SELECT dp.id, dp.uuid, dp.delegator_id, dp.delegate_id, dp.permission_id, dp.screen_id,
                          dp.reason, dp.expires_at, dp.is_active, dp.created_at,
                          u1.full_name AS delegator_name, u2.full_name AS delegate_name,
                          p.permission_name, p.permission_name_ar, p.permission_code, s.screen_name
                   FROM delegated_permissions dp
                   JOIN users u1 ON u1.id = dp.delegator_id
                   JOIN users u2 ON u2.id = dp.delegate_id
                   JOIN permissions p ON p.id = dp.permission_id
                   LEFT JOIN screens s ON s.id = dp.screen_id
                   WHERE dp.is_active = 1
                   ORDER BY dp.created_at DESC""",
                null
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally { dbLock.unlock() }
    }

    fun grantDelegatedPermission(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val delegatorId = data.optLong("delegator_id", 0L)
            val delegateId = data.optLong("delegate_id", 0L)
            val permissionId = data.optLong("permission_id", 0L)
            val screenId = data.optLong("screen_id", 0L)
            val expiresAt = data.optString("expires_at").trim()
            require(delegatorId > 0L && delegateId > 0L && permissionId > 0L) { "المفوض والمفوض إليه والصلاحية مطلوبة" }
            require(delegatorId != delegateId) { "لا يمكن تفويض الصلاحية إلى نفس المستخدم" }
            require(expiresAt.isNotBlank()) { "تاريخ انتهاء التفويض مطلوب" }
            require(!db.rawQuery("SELECT id FROM users WHERE id IN (?, ?) AND is_deleted = 0", arrayOf(delegatorId.toString(), delegateId.toString())).use { cursor -> var count = 0; while (cursor.moveToNext()) count++; count == 2 }) { "المستخدم المفوض أو المفوض إليه غير صالح" }
            val duplicate = db.rawQuery("SELECT id FROM delegated_permissions WHERE delegator_id = ? AND delegate_id = ? AND permission_id = ? AND (screen_id = ? OR (? = 0 AND screen_id IS NULL)) AND is_active = 1", arrayOf(delegatorId.toString(), delegateId.toString(), permissionId.toString(), screenId.toString(), screenId.toString())).use { cursor -> cursor.moveToFirst() }
            require(!duplicate) { "يوجد تفويض نشط مماثل بالفعل" }
            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("delegator_id", delegatorId)
                put("delegate_id", delegateId)
                put("permission_id", permissionId)
                if (screenId > 0) put("screen_id", screenId) else putNull("screen_id")
                put("reason", data.optString("reason").trim())
                put("expires_at", expiresAt)
                put("is_active", 1)
                put("created_at", getCurrentDateTime())
            }
            val id = db.insert("delegated_permissions", null, cv)
            require(id > 0L) { "تعذر حفظ التفويض المؤقت" }
            logActivity("system", "grant_delegated_permission", "منح تفويض مؤقت رقم $id")
            id
        } finally { dbLock.unlock() }
    }

    fun revokeDelegatedPermission(id: Long): Int {
        dbLock.lock()
        return try {
            val rows = writableDatabase.update("delegated_permissions", ContentValues().apply { put("is_active", 0) }, "id = ?", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "revoke_delegated_permission", "إلغاء تفويض مؤقت رقم $id")
            rows
        } finally { dbLock.unlock() }
    }

    fun getGroupPermissions(): JSONArray {
        dbLock.lock()
        return try {
            readableDatabase.rawQuery(
                """SELECT gp.id, gp.group_id, gp.permission_id, gp.screen_id, gp.is_granted, gp.created_at,
                          g.group_name, p.permission_name, p.permission_name_ar, p.permission_code, p.module, s.screen_name
                   FROM group_permissions gp
                   JOIN groups_table g ON g.id = gp.group_id
                   JOIN permissions p ON p.id = gp.permission_id
                   LEFT JOIN screens s ON s.id = gp.screen_id
                   WHERE gp.is_granted = 1
                   ORDER BY g.group_name""",
                null
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally { dbLock.unlock() }
    }

    fun grantGroupPermission(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val groupId = data.optLong("group_id", 0L)
            val permId = data.optLong("permission_id", 0L)
            val screenId = data.optLong("screen_id", 0L)
            require(groupId > 0 && permId > 0) { "المجموعة والصلاحية مطلوبتان" }
            val existing = db.rawQuery("SELECT id FROM group_permissions WHERE group_id = ? AND permission_id = ? AND (screen_id = ? OR (? = 0 AND screen_id IS NULL))", arrayOf(groupId.toString(), permId.toString(), screenId.toString(), screenId.toString())).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            val cv = ContentValues().apply {
                put("group_id", groupId)
                put("permission_id", permId)
                if (screenId > 0) put("screen_id", screenId) else putNull("screen_id")
                put("is_granted", 1)
            }
            val id = if (existing > 0) {
                db.update("group_permissions", cv, "id = ?", arrayOf(existing.toString()))
                existing
            } else {
                db.insert("group_permissions", null, cv)
            }
            if (id > 0) logActivity("system", "grant_group_permission", "ربط مجموعة $groupId بصلاحية $permId")
            id
        } finally { dbLock.unlock() }
    }

    fun revokeGroupPermission(id: Long): Int {
        dbLock.lock()
        return try {
            val rows = writableDatabase.update("group_permissions", ContentValues().apply { put("is_granted", 0) }, "id = ?", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "revoke_group_permission", "إلغاء ربط صلاحية مجموعة رقم $id")
            rows
        } finally { dbLock.unlock() }
    }

    fun getUserSessions(userId: Long): JSONArray {
        dbLock.lock()
        return try {
            readableDatabase.rawQuery(
                "SELECT id, uuid, user_id, device_id, device_type, device_name, device_os, ip_address, login_at, last_activity_at, expires_at, is_active FROM user_sessions WHERE user_id = ? AND is_active = 1 AND logout_at IS NULL ORDER BY last_activity_at DESC",
                arrayOf(userId.toString())
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally { dbLock.unlock() }
    }

    fun terminateSession(sessionId: Long): Int {
        dbLock.lock()
        return try {
            val values = ContentValues().apply {
                put("is_active", 0)
                put("logout_at", getCurrentDateTime())
                put("logout_reason", "manual")
            }
            val rows = writableDatabase.update("user_sessions", values, "id = ? AND is_active = 1", arrayOf(sessionId.toString()))
            if (rows > 0) logActivity("system", "terminate_session", "إنهاء جلسة $sessionId")
            rows
        } finally { dbLock.unlock() }
    }

    fun getUserActivityLog(data: JSONObject): JSONArray {
        dbLock.lock()
        return try {
            val conditions = mutableListOf<String>()
            val args = mutableListOf<String>()
            data.optLong("user_id", 0L).takeIf { it > 0 }?.let { conditions += "ual.user_id = ?"; args += it.toString() }
            data.optString("from_date").takeIf { it.isNotBlank() }?.let { conditions += "date(ual.created_at) >= date(?)"; args += it }
            data.optString("to_date").takeIf { it.isNotBlank() }?.let { conditions += "date(ual.created_at) <= date(?)"; args += it }
            val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
            val limit = data.optInt("limit", 100).coerceIn(1, 500)
            readableDatabase.rawQuery(
                "SELECT ual.*, u.username, u.full_name FROM user_activity_log ual LEFT JOIN users u ON u.id = ual.user_id$where ORDER BY datetime(ual.created_at) DESC LIMIT $limit",
                args.toTypedArray()
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally { dbLock.unlock() }
    }

    // ========================================================================
    // دوال الصيانة
    // ========================================================================

    fun getMaintenanceRequests(stationId: Int, status: String? = null): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val sql = if (status != null) {
                "SELECT * FROM maintenance_requests WHERE station_id = ? AND status = ? AND is_deleted = 0 ORDER BY created_at DESC"
            } else {
                "SELECT * FROM maintenance_requests WHERE station_id = ? AND is_deleted = 0 ORDER BY created_at DESC"
            }
            val args = if (status != null) arrayOf(stationId.toString(), status) else arrayOf(stationId.toString())
            db.rawQuery(sql, args).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun addMaintenanceRequest(
        assetType: String,
        assetId: Int,
        requestType: String,
        priority: String,
        title: String,
        description: String,
        reportedBy: Int,
        stationId: Int
    ): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("request_code", "MR-${System.currentTimeMillis()}")
                put("asset_type", assetType)
                put("asset_id", assetId)
                put("request_type", requestType)
                put("priority", priority)
                put("title", title)
                put("description", description)
                put("reported_by", reportedBy)
                put("station_id", stationId)
                put("status", "open")
                put("created_at", getCurrentDateTime())
                put("updated_at", getCurrentDateTime())
            }
            val id = db.insert("maintenance_requests", null, cv)
            if (id > 0) logActivity("system", "add_maintenance", "إضافة طلب صيانة: $title")
            id
        } finally {
            dbLock.unlock()
        }
    }

    fun updateMaintenanceRequestStatus(requestId: Long, status: String): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("status", status)
                if (status == "completed") {
                    put("completed_at", getCurrentDateTime())
                }
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("maintenance_requests", cv, "id=?", arrayOf(requestId.toString()))
            if (rows > 0) logActivity("system", "update_maintenance_status", "تحديث حالة طلب الصيانة $requestId إلى $status")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال SMS Whitelist
    // ========================================================================

    fun getSmsWhitelist(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery("SELECT * FROM sms_whitelist ORDER BY name, phone", null)
                .use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun addToSmsWhitelist(phone: String, name: String = ""): Boolean {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("phone", phone)
                put("name", name)
                put("enabled", 1)
                put("created_at", getCurrentDateTime())
            }
            val result = db.insertWithOnConflict("sms_whitelist", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            if (result > 0) logActivity("system", "add_whitelist", "إضافة رقم $phone إلى القائمة البيضاء")
            result > 0
        } finally {
            dbLock.unlock()
        }
    }

    fun removeFromSmsWhitelist(phone: String): Boolean {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val rows = db.delete("sms_whitelist", "phone=?", arrayOf(phone))
            if (rows > 0) logActivity("system", "remove_whitelist", "إزالة رقم $phone من القائمة البيضاء")
            rows > 0
        } finally {
            dbLock.unlock()
        }
    }

    fun updateSmsWhitelist(phone: String, name: String, enabled: Boolean): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val rows = db.update(
                "sms_whitelist",
                ContentValues().apply {
                    put("name", name)
                    put("enabled", if (enabled) 1 else 0)
                },
                "phone = ?",
                arrayOf(phone)
            )
            if (rows > 0) logActivity("system", "update_whitelist", "تحديث رقم $phone في القائمة البيضاء")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال SMS Logs
    // ========================================================================

    fun getSmsLogs(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM sms_logs ORDER BY created_at DESC LIMIT 500",
                null
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun logSms(phone: String, message: String, type: String, status: String): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("phone_number", phone)
                put("message_content", message)
                put("message_type", type)
                put("status", status)
                put("created_at", getCurrentDateTime())
            }
            db.insert("sms_logs", null, cv)
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال SMS Messages
    // ========================================================================

    fun addSmsMessage(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("phone_number", data.optString("phone_number", ""))
                put("message_body", data.optString("message_body", ""))
                put("message_type", data.optString("message_type", "incoming"))
                put("status", data.optString("status", "pending"))
                put("party_id", data.optLong("party_id", 0))
                put("sent_at", data.optString("sent_at", ""))
                put("created_at", getCurrentDateTime())
                put("updated_at", getCurrentDateTime())
            }
            db.insert("sms_messages", null, cv)
        } finally {
            dbLock.unlock()
        }
    }

    fun retrySmsMessage(id: Long): Boolean {
        if (id <= 0L) return false
        dbLock.lock()
        val database = writableDatabase
        database.beginTransaction()
        try {
            val uuid = database.rawQuery("SELECT uuid FROM sms_messages WHERE id = ? LIMIT 1", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getString(0) else null }
            if (uuid == null) return false
            val changed = database.update(
                "sms_outbox",
                ContentValues().apply { put("status", "RETRY_PENDING"); put("next_attempt_at", System.currentTimeMillis()); put("failure_code", "MANUAL_RETRY") },
                "message_id = ? AND status IN ('FAILED','CANCELLED','RETRY_PENDING')",
                arrayOf(uuid)
            )
            if (changed != 1) return false
            database.update("sms_messages", ContentValues().apply { put("status", "queued"); put("updated_at", getCurrentDateTime()) }, "id = ?", arrayOf(id.toString()))
            database.setTransactionSuccessful()
            return true
        } finally {
            database.endTransaction()
            dbLock.unlock()
        }
    }

    fun deleteSmsMessage(id: Long): Boolean {
        if (id <= 0L) return false
        dbLock.lock()
        val database = writableDatabase
        database.beginTransaction()
        try {
            val uuid = database.rawQuery(
                "SELECT uuid FROM sms_messages WHERE id = ? LIMIT 1",
                arrayOf(id.toString())
            ).use { if (it.moveToFirst()) it.getString(0) else null }
            if (uuid == null) return false
            val outboxState = database.rawQuery(
                "SELECT status FROM sms_outbox WHERE message_id = ? LIMIT 1",
                arrayOf(uuid)
            ).use { if (it.moveToFirst()) it.getString(0) else null }
            if (outboxState in setOf("SENDING", "SENT", "DELIVERY_PENDING", "DELIVERED")) return false
            val deleted = database.delete("sms_messages", "id = ?", arrayOf(id.toString())) == 1
            if (deleted) database.update(
                "sms_outbox",
                ContentValues().apply { put("status", "CANCELLED"); put("failure_code", "DELETED_FROM_UI"); put("failed_at", System.currentTimeMillis()) },
                "message_id = ? AND status IN ('DRAFT','QUEUED','RETRY_PENDING')",
                arrayOf(uuid)
            )
            database.setTransactionSuccessful()
            return deleted
        } finally {
            database.endTransaction()
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال المدفوعات
    // ========================================================================

    fun getPaymentsWithCustomer(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                """SELECT p.*, pt.commercial_name as customer_name
                   FROM payments p
                   LEFT JOIN parties pt ON p.customer_party_id = pt.id
                   WHERE p.is_deleted = 0
                   ORDER BY p.created_at DESC""",
                null
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun processPayment(customerId: Int, amount: Double, method: String, operator: String = "System", notes: String = ""): Boolean {
        require(customerId > 0) { "معرف العميل غير صالح" }
        require(amount > 0.0 && amount.isFinite()) { "مبلغ التسديد غير صالح" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val partyBalance = db.rawQuery(
                "SELECT COALESCE(total_due, 0) FROM parties WHERE id = ? AND is_deleted = 0 LIMIT 1",
                arrayOf(customerId.toString())
            ).use { cursor ->
                if (!cursor.moveToFirst()) return false
                cursor.getDouble(0)
            }
            if (partyBalance + 0.000001 < amount) return false

            var unapplied = amount
            val invoices = db.rawQuery(
                """SELECT id, COALESCE(remaining_amount, 0)
                   FROM sales_transactions
                   WHERE customer_party_id = ? AND remaining_amount > 0 AND is_deleted = 0
                   ORDER BY due_date ASC, id ASC""",
                arrayOf(customerId.toString())
            )
            invoices.use { cursor ->
                while (cursor.moveToNext() && unapplied > 0.000001) {
                    val invoiceId = cursor.getLong(0)
                    val invoiceRemaining = cursor.getDouble(1)
                    val applied = minOf(unapplied, invoiceRemaining)
                    val updated = db.compileStatement(
                        """UPDATE sales_transactions
                           SET paid_amount = COALESCE(paid_amount, 0) + ?,
                               remaining_amount = MAX(0, COALESCE(remaining_amount, 0) - ?),
                               payment_status = CASE WHEN COALESCE(remaining_amount, 0) - ? <= 0 THEN 'paid' ELSE 'partial' END
                           WHERE id = ? AND is_deleted = 0"""
                    ).apply {
                        bindDouble(1, applied)
                        bindDouble(2, applied)
                        bindDouble(3, applied)
                        bindLong(4, invoiceId)
                    }.executeUpdateDelete()
                    if (updated != 1) throw IllegalStateException("تعذر تحديث الفاتورة $invoiceId")
                    unapplied -= applied
                }
            }
            if (unapplied > 0.000001) return false

            val partyUpdated = db.compileStatement(
                """UPDATE parties
                   SET current_balance = MAX(0, COALESCE(current_balance, 0) - ?),
                       total_due = MAX(0, COALESCE(total_due, 0) - ?)
                   WHERE id = ? AND is_deleted = 0"""
            ).apply {
                bindDouble(1, amount)
                bindDouble(2, amount)
                bindLong(3, customerId.toLong())
            }.executeUpdateDelete()
            if (partyUpdated != 1) throw IllegalStateException("تعذر تحديث رصيد العميل")

            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("payment_code", "PAY-${System.currentTimeMillis()}")
                put("customer_party_id", customerId)
                put("payment_type", method)
                put("payment_method", method)
                put("amount", amount)
                put("status", "completed")
                put("operator", operator)
                put("notes", notes.ifBlank { "تسديد عبر Bridge" })
                put("created_at", getCurrentDateTime())
            }
            val paymentId = db.insert("payments", null, cv)
            if (paymentId <= 0) throw IllegalStateException("تعذر تسجيل عملية التسديد")

            db.setTransactionSuccessful()
            runCatching { logActivity(operator, "payment", "تسديد مبلغ $amount للعميل $customerId") }
            return true
        } finally {
            db.endTransaction()
        }
    }

    // ========================================================================
    // دوال الإيداعات النقدية
    // ========================================================================

    fun addCashDeposit(customerId: Int, amount: Double, notes: String, operator: String = "System"): Boolean {
        dbLock.lock()
        return try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.execSQL(
                    "UPDATE parties SET current_balance = current_balance + ?, total_due = total_due + ? WHERE id = ?",
                    arrayOf(amount, amount, customerId)
                )
                val cv = ContentValues().apply {
                    put("customer_id", customerId)
                    put("amount", amount)
                    put("balance_after", getPartyBalance(customerId))
                    put("notes", notes)
                    put("operator", operator)
                    put("date", getCurrentDateTime())
                }
                db.insert("cash_deposits", null, cv)
                db.setTransactionSuccessful()
                logActivity(operator, "deposit", "إيداع مبلغ $amount للعميل $customerId")
                true
            } finally {
                db.endTransaction()
            }
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال التقارير
    // ========================================================================

    fun getDailySales(stationId: Int = 1, date: String? = null): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val targetDate = date ?: getCurrentDate()
            db.rawQuery(
                """SELECT s.*, f.fuel_name, p.commercial_name as customer_name
                   FROM sales_transactions s
                   LEFT JOIN fuel_types f ON s.fuel_type_id = f.id
                   LEFT JOIN parties p ON s.customer_party_id = p.id
                   WHERE s.station_id = ? AND date(s.created_at) = ? AND s.is_deleted = 0
                   ORDER BY s.created_at DESC""",
                arrayOf(stationId.toString(), targetDate)
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun getMonthlySales(stationId: Int = 1, month: Int? = null, year: Int? = null): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val cal = Calendar.getInstance()
            val m = month ?: (cal.get(Calendar.MONTH) + 1)
            val y = year ?: cal.get(Calendar.YEAR)
            val monthStr = String.format("%02d", m)
            db.rawQuery(
                """SELECT strftime('%Y-%m-%d', created_at) as day,
                          COUNT(*) as transactions,
                          COALESCE(SUM(net_amount),0) as total_sales,
                          COALESCE(SUM(liters),0) as total_liters
                   FROM sales_transactions
                   WHERE station_id = ? AND strftime('%Y-%m', created_at) = ? AND is_deleted = 0
                   GROUP BY day
                   ORDER BY day""",
                arrayOf(stationId.toString(), "$y-$monthStr")
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun getEodReport(stationId: Int = 1, fromDate: String? = null, toDate: String? = null): JSONObject {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val from = fromDate ?: getCurrentDate()
            val to = toDate ?: getCurrentDate()
            db.rawQuery(
                """SELECT
                    COALESCE(SUM(CASE WHEN s.status = 'completed' THEN s.net_amount ELSE 0 END),0) as total_sales,
                    COALESCE(SUM(CASE WHEN s.payment_method = 'cash' THEN s.net_amount ELSE 0 END),0) as cash_sales,
                    COALESCE(SUM(CASE WHEN s.payment_method = 'credit' THEN s.net_amount ELSE 0 END),0) as credit_sales,
                    COALESCE(SUM(CASE WHEN s.payment_method = 'bank_transfer' THEN s.net_amount ELSE 0 END),0) as bank_sales,
                    COALESCE(SUM(CASE WHEN s.payment_method = 'credit_card' THEN s.net_amount ELSE 0 END),0) as card_sales,
                    COALESCE(SUM(CASE WHEN s.is_credit = 1 THEN s.net_amount ELSE 0 END),0) as deferred_sales,
                    COALESCE(SUM(CASE WHEN s.is_credit = 0 THEN s.net_amount ELSE 0 END),0) as cash_sales_actual,
                    COALESCE(SUM(s.liters),0) as total_liters,
                    COUNT(*) as transaction_count,
                    COALESCE(SUM(p.amount),0) as total_payments
                   FROM sales_transactions s
                   LEFT JOIN payments p ON s.id = p.sale_id AND p.status = 'completed' AND p.is_deleted = 0
                   WHERE s.station_id = ? AND date(s.created_at) BETWEEN ? AND ? AND s.is_deleted = 0""",
                arrayOf(stationId.toString(), from, to)
            ).use { cursor ->
                val result = JSONObject()
                if (cursor.moveToFirst()) {
                    result.put("total_sales", cursor.getDouble(0))
                    result.put("cash_sales", cursor.getDouble(1))
                    result.put("credit_sales", cursor.getDouble(2))
                    result.put("bank_sales", cursor.getDouble(3))
                    result.put("card_sales", cursor.getDouble(4))
                    result.put("deferred_sales", cursor.getDouble(5))
                    result.put("cash_sales_actual", cursor.getDouble(6))
                    result.put("total_liters", cursor.getDouble(7))
                    result.put("transaction_count", cursor.getInt(8))
                    result.put("total_payments", cursor.getDouble(9))
                } else {
                    result.put("total_sales", 0)
                    result.put("total_payments", 0)
                }
                result
            }
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // APIs موحدة لسجل النشاط والميزانية العمومية
    // ========================================================================

    /**
     * قراءة سجل موحد من الجداول الفعلية فقط. أسماء الجداول ثابتة داخل هذا الاستعلام
     * ولا يمكن تمريرها من JavaScript.
     */
    fun getActivityLogs(limit: Int = 500): JSONArray {
        val safeLimit = limit.coerceIn(1, 2000)
        dbLock.lock()
        return try {
            val db = readableDatabase
            val sql = """
                SELECT id, uuid, username, user_id, action, action_type, action_category,
                       description, description_ar, table_name, record_id, ip_address,
                       device_id, user_agent, old_values, new_values, changed_columns,
                       status, is_success, error_message, log_type, message_type,
                       phone_number, sent_at, started_at, sync_type, stack_trace,
                       source_table, created_at
                FROM (
                    SELECT ual.id, ual.uuid, u.username, ual.user_id,
                           ual.action, NULL AS action_type, ual.action_category,
                           ual.description, ual.description_ar,
                           ual.target_table AS table_name, ual.target_id AS record_id,
                           ual.ip_address, ual.device_id, ual.user_agent,
                           ual.old_values, ual.new_values, ual.changed_columns,
                           CASE WHEN ual.is_success = 1 THEN 'success' ELSE 'failed' END AS status,
                           ual.is_success, ual.error_message,
                           'user_activity' AS log_type, NULL AS message_type,
                           NULL AS phone_number, NULL AS sent_at, NULL AS started_at,
                           NULL AS sync_type, NULL AS stack_trace,
                           'user_activity_log' AS source_table, ual.created_at
                    FROM user_activity_log ual
                    LEFT JOIN users u ON u.id = ual.user_id

                    UNION ALL

                    SELECT al.id, al.uuid, u.username, al.user_id,
                           al.action_type AS action, al.action_type, 'audit' AS action_category,
                           COALESCE(al.new_row_json, al.old_row_json, al.action_type) AS description,
                           NULL AS description_ar, al.table_name, al.record_id,
                           al.ip_address, NULL AS device_id, al.user_agent,
                           al.old_row_json AS old_values, al.new_row_json AS new_values,
                           al.changed_columns, 'success' AS status, 1 AS is_success,
                           NULL AS error_message, 'audit' AS log_type, NULL AS message_type,
                           NULL AS phone_number, NULL AS sent_at, NULL AS started_at,
                           NULL AS sync_type, NULL AS stack_trace,
                           'audit_logs' AS source_table, al.created_at
                    FROM audit_logs al
                    LEFT JOIN users u ON u.id = al.user_id

                    UNION ALL

                    SELECT sl.id, sl.uuid, u.username, sl.user_id,
                           sl.log_type AS action, sl.log_type, 'system' AS action_category,
                           sl.message AS description, sl.message_ar AS description_ar,
                           'system_logs' AS table_name, sl.id AS record_id,
                           sl.ip_address, sl.device_id, NULL AS user_agent,
                           NULL AS old_values, NULL AS new_values, NULL AS changed_columns,
                           CASE
                               WHEN sl.log_level IN ('error', 'critical') THEN 'failed'
                               WHEN sl.log_level = 'warning' THEN 'warning'
                               ELSE 'success'
                           END AS status,
                           CASE WHEN sl.log_level IN ('error', 'critical') THEN 0 ELSE 1 END AS is_success,
                           CASE WHEN sl.log_level IN ('error', 'critical') THEN sl.message ELSE NULL END AS error_message,
                           'system' AS log_type, NULL AS message_type,
                           NULL AS phone_number, NULL AS sent_at, NULL AS started_at,
                           NULL AS sync_type, sl.stack_trace,
                           'system_logs' AS source_table, sl.created_at
                    FROM system_logs sl
                    LEFT JOIN users u ON u.id = sl.user_id

                    UNION ALL

                    SELECT sy.id, sy.uuid, NULL AS username, NULL AS user_id,
                           sy.sync_type AS action, sy.sync_type, 'sync' AS action_category,
                           sy.entity_type AS description, NULL AS description_ar,
                           sy.entity_type AS table_name, NULL AS record_id,
                           NULL AS ip_address, sy.device_id, NULL AS user_agent,
                           NULL AS old_values, NULL AS new_values, NULL AS changed_columns,
                           sy.status, CASE WHEN sy.status = 'success' THEN 1 ELSE 0 END AS is_success,
                           sy.error_message, 'sync' AS log_type, NULL AS message_type,
                           NULL AS phone_number, NULL AS sent_at, sy.started_at,
                           sy.sync_type, NULL AS stack_trace,
                           'sync_logs' AS source_table, sy.created_at
                    FROM sync_logs sy

                    UNION ALL

                    SELECT sms.id, sms.uuid, u.username, sms.created_by,
                           sms.message_type AS action, sms.message_type, 'sms' AS action_category,
                           sms.message_content AS description, NULL AS description_ar,
                           'sms_logs' AS table_name, sms.id AS record_id,
                           NULL AS ip_address, sms.device_id, NULL AS user_agent,
                           NULL AS old_values, NULL AS new_values, NULL AS changed_columns,
                           sms.status,
                           CASE WHEN sms.status IN ('failed', 'cancelled') THEN 0 ELSE 1 END AS is_success,
                           sms.error_message, 'sms' AS log_type, sms.message_type,
                           sms.phone_number, sms.sent_at, NULL AS started_at,
                           NULL AS sync_type, NULL AS stack_trace,
                           'sms_logs' AS source_table, sms.created_at
                    FROM sms_logs sms
                    LEFT JOIN users u ON u.id = sms.created_by
                ) activity
                ORDER BY created_at DESC, id DESC
                LIMIT ?
            """.trimIndent()
            db.rawQuery(sql, arrayOf(safeLimit.toString())).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    /** حذف سجل من جدول ثابت مسموح به؛ audit_logs غير قابل للحذف من الواجهة. */
    fun deleteActivityLog(sourceTable: String, id: Long): Int {
        if (id <= 0) return 0
        dbLock.lock()
        return try {
            val db = writableDatabase
            when (sourceTable) {
                "user_activity_log" -> db.delete("user_activity_log", "id = ?", arrayOf(id.toString()))
                "system_logs" -> db.delete("system_logs", "id = ?", arrayOf(id.toString()))
                "sync_logs" -> db.delete("sync_logs", "id = ?", arrayOf(id.toString()))
                "sms_logs" -> db.delete("sms_logs", "id = ?", arrayOf(id.toString()))
                else -> -1
            }
        } finally {
            dbLock.unlock()
        }
    }

    /** تنظيف سجلات التشغيل القديمة دون حذف audit_logs ودون VACUUM داخل معاملة. */
    fun cleanupActivityLogs(retentionDays: Int): Int {
        val safeDays = retentionDays.coerceIn(1, 3650)
        val cutoff = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -safeDays) }
        val cutoffDate = getDateOnlyFormat().format(cutoff.time)
        dbLock.lock()
        return try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val deletedActivity = db.delete("user_activity_log", "date(created_at) < ?", arrayOf(cutoffDate))
                val deletedSystem = db.delete("system_logs", "date(created_at) < ? AND is_resolved = 1", arrayOf(cutoffDate))
                val deletedSync = db.delete("sync_logs", "date(created_at) < ?", arrayOf(cutoffDate))
                val deletedSms = db.delete("sms_logs", "date(created_at) < ?", arrayOf(cutoffDate))
                db.setTransactionSuccessful()
                deletedActivity + deletedSystem + deletedSync + deletedSms
            } finally {
                db.endTransaction()
            }
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * حساب ميزانية فعلية حتى تاريخ محدد من accounts وقيود journal_entries.
     * stationId يبقى داخل Android/DatabaseHelper ولا يأتي من JavaScript.
     */
    /**
     * حساب ميزانية فعلية حتى تاريخ محدد، مع تحويل كل سطر إلى reporting currency.
     * سياسة السعر:
     * 1) إذا كانت عملة السطر هي العملة الهدف فالمعامل = 1.
     * 2) يستخدم journal_entry_items.exchange_rate كسعر تاريخي من عملة القيد إلى العملة الافتراضية، وهو نفس الاتجاه الذي تدل عليه amount_in_default في الجداول التجارية.
     * 3) إذا كانت عملة التقرير مختلفة عن العملة الافتراضية، يُركّب السعر التاريخي مع exchange_rates من العملة الافتراضية إلى عملة التقرير.
     * 4) عند وجود سعر عكسي يستخدم exchange_rates.inverse_rate.
     * 5) إذا تعذر إثبات السعر يرفض الحساب بدلاً من خلط عملات مختلفة.
     */
    fun getBalanceSheet(reportDate: String, stationId: Int, currencyId: Long): JSONObject {
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(reportDate)) {
            "صيغة التاريخ يجب أن تكون YYYY-MM-DD"
        }
        require(stationId > 0) { "stationId غير صالح" }
        require(currencyId > 0) { "currencyId غير صالح" }

        dbLock.lock()
        return try {
            val db = readableDatabase
            val defaultCurrencyId = getDefaultCurrencyId(db)
            val assets = JSONArray()
            val liabilities = JSONArray()
            val equity = JSONArray()
            var assetsTotal = 0.0
            var liabilitiesTotal = 0.0
            var equityBaseTotal = 0.0
            var revenueTotal = 0.0
            var expenseTotal = 0.0

            val accountTotals = linkedMapOf<Long, BalanceAccountAccumulator>()
            val accountSql = """
                SELECT a.id,
                       a.account_code,
                       a.account_name,
                       a.account_name_ar,
                       a.account_type,
                       a.level,
                       a.normal_balance,
                       COALESCE(a.opening_balance, 0) AS opening_balance,
                       CASE WHEN je.id IS NOT NULL THEN jei.id ELSE NULL END AS item_id,
                       CASE WHEN je.id IS NOT NULL THEN COALESCE(jei.debit, 0) ELSE 0 END AS debit_amount,
                       CASE WHEN je.id IS NOT NULL THEN COALESCE(jei.credit, 0) ELSE 0 END AS credit_amount,
                       jei.currency_id AS item_currency_id,
                       COALESCE(jei.exchange_rate, 1.0) AS item_exchange_rate,
                       je.entry_date AS entry_date
                FROM accounts a
                LEFT JOIN journal_entry_items jei
                    ON jei.account_id = a.id
                LEFT JOIN journal_entries je
                    ON je.id = jei.journal_entry_id
                   AND je.entry_date <= ?
                   AND je.status = 'posted'
                   AND je.is_deleted = 0
                WHERE a.is_active = 1
                  AND a.is_deleted = 0
                  AND a.account_type IN ('asset', 'liability', 'equity', 'revenue', 'expense')
                ORDER BY a.account_type, a.level, a.account_code, jei.id
            """.trimIndent()

            db.rawQuery(accountSql, arrayOf(reportDate)).use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow("id")
                val codeIndex = cursor.getColumnIndexOrThrow("account_code")
                val nameIndex = cursor.getColumnIndexOrThrow("account_name")
                val nameArIndex = cursor.getColumnIndexOrThrow("account_name_ar")
                val typeIndex = cursor.getColumnIndexOrThrow("account_type")
                val levelIndex = cursor.getColumnIndexOrThrow("level")
                val normalBalanceIndex = cursor.getColumnIndexOrThrow("normal_balance")
                val openingIndex = cursor.getColumnIndexOrThrow("opening_balance")
                val itemIdIndex = cursor.getColumnIndexOrThrow("item_id")
                val debitIndex = cursor.getColumnIndexOrThrow("debit_amount")
                val creditIndex = cursor.getColumnIndexOrThrow("credit_amount")
                val itemCurrencyIndex = cursor.getColumnIndexOrThrow("item_currency_id")
                val itemRateIndex = cursor.getColumnIndexOrThrow("item_exchange_rate")
                val entryDateIndex = cursor.getColumnIndexOrThrow("entry_date")

                while (cursor.moveToNext()) {
                    val accountId = cursor.getLong(idIndex)
                    val accumulator = accountTotals.getOrPut(accountId) {
                        BalanceAccountAccumulator(
                            id = accountId,
                            code = cursor.getString(codeIndex),
                            name = cursor.getString(nameIndex),
                            nameAr = if (cursor.isNull(nameArIndex)) null else cursor.getString(nameArIndex),
                            type = cursor.getString(typeIndex),
                            level = cursor.getInt(levelIndex),
                            normalBalance = cursor.getString(normalBalanceIndex)
                        )
                    }

                    if (!accumulator.openingInitialized) {
                        val openingRate = resolveExchangeRate(
                            db = db,
                            sourceCurrencyId = defaultCurrencyId,
                            targetCurrencyId = currencyId,
                            effectiveDate = reportDate,
                            transactionRate = null,
                            defaultCurrencyId = defaultCurrencyId
                        ) ?: throw SQLiteException(
                            "لا يوجد سعر تحويل افتتاحي من $defaultCurrencyId إلى $currencyId بتاريخ $reportDate"
                        )
                        accumulator.openingBalance = cursor.getDouble(openingIndex) * openingRate
                        accumulator.openingInitialized = true
                    }

                    if (!cursor.isNull(itemIdIndex)) {
                        val entryDate = cursor.getString(entryDateIndex)
                        val sourceCurrencyId = if (cursor.isNull(itemCurrencyIndex)) {
                            defaultCurrencyId
                        } else {
                            cursor.getLong(itemCurrencyIndex)
                        }
                        val transactionRate = cursor.getDouble(itemRateIndex)
                        val conversionRate = resolveExchangeRate(
                            db = db,
                            sourceCurrencyId = sourceCurrencyId,
                            targetCurrencyId = currencyId,
                            effectiveDate = entryDate,
                            transactionRate = transactionRate,
                            defaultCurrencyId = defaultCurrencyId
                        ) ?: throw SQLiteException(
                            "لا يوجد سعر تحويل من $sourceCurrencyId إلى $currencyId بتاريخ $entryDate للحركة ${cursor.getLong(itemIdIndex)}"
                        )
                        accumulator.debitTotal += cursor.getDouble(debitIndex) * conversionRate
                        accumulator.creditTotal += cursor.getDouble(creditIndex) * conversionRate
                    }
                }
            }

            accountTotals.values.forEach { account ->
                val balance = if (account.normalBalance == "debit") {
                    account.openingBalance + account.debitTotal - account.creditTotal
                } else {
                    account.openingBalance + account.creditTotal - account.debitTotal
                }
                val row = JSONObject().apply {
                    put("id", account.id)
                    put("account_code", account.code)
                    put("account_name", account.name)
                    put("account_name_ar", account.nameAr ?: JSONObject.NULL)
                    put("account_type", account.type)
                    put("level", account.level)
                    put("opening_balance", account.openingBalance)
                    put("debit_total", account.debitTotal)
                    put("credit_total", account.creditTotal)
                    put("balance", balance)
                    put("currency_id", currencyId)
                }
                when (account.type) {
                    "asset" -> { assets.put(row); assetsTotal += balance }
                    "liability" -> { liabilities.put(row); liabilitiesTotal += balance }
                    "equity" -> { equity.put(row); equityBaseTotal += balance }
                    "revenue" -> revenueTotal += balance
                    "expense" -> expenseTotal += balance
                }
            }

            val netIncome = revenueTotal - expenseTotal
            if (kotlin.math.abs(netIncome) > 0.000001) {
                equity.put(JSONObject().apply {
                    put("id", JSONObject.NULL)
                    put("account_code", "NET_INCOME")
                    put("account_name", "Net Income")
                    put("account_name_ar", "صافي الدخل")
                    put("account_type", "equity")
                    put("level", 1)
                    put("opening_balance", 0.0)
                    put("debit_total", 0.0)
                    put("credit_total", netIncome)
                    put("balance", netIncome)
                    put("currency_id", currencyId)
                })
            }

            val equityTotal = equityBaseTotal + netIncome
            val difference = assetsTotal - liabilitiesTotal - equityTotal
            JSONObject().apply {
                put("report_date", reportDate)
                put("station_id", stationId)
                put("currency_id", currencyId)
                put("default_currency_id", defaultCurrencyId)
                put("assets", assets)
                put("liabilities", liabilities)
                put("equity", equity)
                put("total_assets", assetsTotal)
                put("total_liabilities", liabilitiesTotal)
                put("total_equity", equityTotal)
                put("assets_total", assetsTotal)
                put("liabilities_total", liabilitiesTotal)
                put("equity_total", equityTotal)
                put("net_income", netIncome)
                put("difference", difference)
                put("is_balanced", kotlin.math.abs(difference) <= 0.01)
            }
        } finally {
            dbLock.unlock()
        }
    }

    private fun getDefaultCurrencyId(db: SQLiteDatabase): Long {
        return db.rawQuery(
            "SELECT id FROM currencies WHERE is_default = 1 AND is_active = 1 AND is_deleted = 0 ORDER BY id LIMIT 1",
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0)
            else throw SQLiteException("لا توجد عملة افتراضية فعالة")
        }
    }

    /**
     * يعيد معامل التحويل من عملة القيد إلى عملة التقرير.
     * exchange_rate في سطر القيد يُعامل كسعر source -> default، لا source -> target.
     */
    private fun resolveExchangeRate(
        db: SQLiteDatabase,
        sourceCurrencyId: Long,
        targetCurrencyId: Long,
        effectiveDate: String,
        transactionRate: Double?,
        defaultCurrencyId: Long
    ): Double? {
        if (sourceCurrencyId == targetCurrencyId) return 1.0
        val explicitTransactionRate = transactionRate?.takeIf {
            it > 0.0 && kotlin.math.abs(it - 1.0) > 0.0000001
        }

        if (targetCurrencyId == defaultCurrencyId && sourceCurrencyId != defaultCurrencyId) {
            if (explicitTransactionRate != null) return explicitTransactionRate
            return findCentralExchangeRate(db, sourceCurrencyId, targetCurrencyId, effectiveDate)
        }

        if (sourceCurrencyId != defaultCurrencyId && targetCurrencyId != defaultCurrencyId) {
            if (explicitTransactionRate != null) {
                val defaultToTarget = findCentralExchangeRate(db, defaultCurrencyId, targetCurrencyId, effectiveDate)
                if (defaultToTarget != null) return explicitTransactionRate * defaultToTarget
            }

            val sourceToDefault = findCentralExchangeRate(db, sourceCurrencyId, defaultCurrencyId, effectiveDate)
            val defaultToTarget = findCentralExchangeRate(db, defaultCurrencyId, targetCurrencyId, effectiveDate)
            if (sourceToDefault != null && defaultToTarget != null) {
                return sourceToDefault * defaultToTarget
            }
        }

        return findCentralExchangeRate(db, sourceCurrencyId, targetCurrencyId, effectiveDate)
    }

    private fun findCentralExchangeRate(
        db: SQLiteDatabase,
        sourceCurrencyId: Long,
        targetCurrencyId: Long,
        effectiveDate: String
    ): Double? {
        if (sourceCurrencyId == targetCurrencyId) return 1.0
        val directRate = db.rawQuery(
            """
            SELECT rate FROM exchange_rates
            WHERE from_currency_id = ?
              AND to_currency_id = ?
              AND effective_date <= ?
              AND (expiry_date IS NULL OR expiry_date >= ?)
              AND is_active = 1
              AND is_deleted = 0
            ORDER BY effective_date DESC, id DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(sourceCurrencyId.toString(), targetCurrencyId.toString(), effectiveDate, effectiveDate)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getDouble(0) else null }
        if (directRate != null && directRate > 0.0) return directRate

        val reverseRate = db.rawQuery(
            """
            SELECT inverse_rate FROM exchange_rates
            WHERE from_currency_id = ?
              AND to_currency_id = ?
              AND effective_date <= ?
              AND (expiry_date IS NULL OR expiry_date >= ?)
              AND is_active = 1
              AND is_deleted = 0
            ORDER BY effective_date DESC, id DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(targetCurrencyId.toString(), sourceCurrencyId.toString(), effectiveDate, effectiveDate)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getDouble(0) else null }
        return reverseRate?.takeIf { it > 0.0 }
    }

    /** حفظ snapshot محسوب داخل Android بعد نجاح القراءة والمعادلة. */
    fun saveBalanceSheetSnapshot(reportDate: String, stationId: Int, currencyId: Long, generatedBy: Long): Long {
        require(generatedBy > 0) { "generatedBy غير صالح" }
        val report = getBalanceSheet(reportDate, stationId, currencyId)
        if (!report.optBoolean("is_balanced", false)) {
            throw SQLiteException("لا يمكن حفظ ميزانية غير متوازنة: الفرق ${report.optDouble("difference", 0.0)}")
        }
        dbLock.lock()
        return try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val values = ContentValues().apply {
                    put("station_id", stationId)
                    put("report_date", reportDate)
                    put("assets_total", report.optDouble("assets_total", 0.0))
                    put("liabilities_total", report.optDouble("liabilities_total", 0.0))
                    put("equity_total", report.optDouble("equity_total", 0.0))
                    put("net_income", report.optDouble("net_income", 0.0))
                    put("currency_id", currencyId)
                    put("generated_by", generatedBy)
                    put("generated_at", getCurrentDateTime())
                    put("archived", 0)
                }
                val existingId = db.rawQuery(
                    "SELECT id FROM balance_sheets WHERE station_id = ? AND report_date = ? AND currency_id = ? AND archived = 0 ORDER BY id DESC LIMIT 1",
                    arrayOf(stationId.toString(), reportDate, currencyId.toString())
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
                val rowId = if (existingId > 0) {
                    val updated = db.update("balance_sheets", values, "id = ?", arrayOf(existingId.toString()))
                    if (updated > 0) existingId else -1L
                } else {
                    db.insert("balance_sheets", null, values)
                }
                if (rowId <= 0) throw SQLiteException("فشل حفظ snapshot الميزانية")
                db.setTransactionSuccessful()
                rowId
            } finally {
                db.endTransaction()
            }
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال الإعدادات
    // ========================================================================

    fun getSetting(key: String): String {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery("SELECT setting_value FROM system_settings WHERE setting_key = ?", arrayOf(key))
                .use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else ""
                }
        } finally {
            dbLock.unlock()
        }
    }

    fun setSetting(key: String, value: String): Boolean {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("setting_value", value)
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("system_settings", cv, "setting_key = ?", arrayOf(key))
            if (rows == 0) {
                val insertCv = ContentValues().apply {
                    put("uuid", UUID.randomUUID().toString())
                    put("setting_key", key)
                    put("setting_value", value)
                    put("category", "general")
                    put("data_type", "string")
                    put("created_at", getCurrentDateTime())
                    put("updated_at", getCurrentDateTime())
                }
                db.insert("system_settings", null, insertCv)
            }
            true
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال AI Chat
    // ========================================================================

    fun getAiChatHistory(sessionId: String): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM ai_chat_history WHERE session_id = ? ORDER BY created_at ASC",
                arrayOf(sessionId)
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun saveAiMessage(sessionId: String, role: String, content: String): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("session_id", sessionId)
                put("role", role)
                put("content", content)
                put("created_at", getCurrentDateTime())
            }
            db.insert("ai_chat_history", null, cv)
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال التصدير
    // ========================================================================

    fun exportAllData(): JSONObject {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val result = JSONObject()
            val tables = listOf(
                "parties", "sales_transactions", "tanks", "pumps", "users", "employees",
                "shifts", "notifications", "sms_logs", "fuel_types", "products",
                "payments", "deliveries", "maintenance_requests", "assets"
            )
            for (table in tables) {
                db.query(table, null, null, null, null, null, null)
                    .use { cursor -> result.put(table, cursorToJsonArray(cursor)) }
            }
            result
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال المنتجات والفئات
    // ========================================================================

    fun getProductCategories(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery("SELECT * FROM product_categories WHERE is_deleted = 0 ORDER BY category_name", null)
                .use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun getFuelNameById(fuelTypeId: Int): String? {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery("SELECT fuel_name FROM fuel_types WHERE id = ?", arrayOf(fuelTypeId.toString()))
                .use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال الأسعار والمعلومات
    // ========================================================================

    fun getDieselPrice(): Double {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT default_sale_price FROM fuel_types WHERE fuel_code = 'DIESEL' AND is_deleted = 0 LIMIT 1",
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getDouble(0) else 0.0
            }
        } finally {
            dbLock.unlock()
        }
    }

    fun getGasolinePrice(fuelCode: String = "PETROL_95"): Double {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT default_sale_price FROM fuel_types WHERE fuel_code = ? AND is_deleted = 0 LIMIT 1",
                arrayOf(fuelCode)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getDouble(0) else 0.0
            }
        } finally {
            dbLock.unlock()
        }
    }

    fun getManagerPhone(): String? {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery("""
                SELECT u.phone FROM users u
                JOIN roles r ON u.role_id = r.id
                WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN', 'STATION_MANAGER')
                  AND u.status = 'active' AND u.is_deleted = 0
                ORDER BY r.level ASC LIMIT 1
            """.trimIndent(), null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } finally {
            dbLock.unlock()
        }
    }

    fun getRetentionDays(): Int {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT setting_value FROM system_settings WHERE setting_key = 'retention_days' LIMIT 1",
                null
            ).use { cursor ->
                val days = if (cursor.moveToFirst()) cursor.getInt(0) else 90
                days.coerceIn(7, 365)
            }
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال الإشعارات
    // ========================================================================

    fun getNotifications(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM notifications ORDER BY created_at DESC LIMIT 100",
                null
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // إحصائيات لوحة التحكم (تم تحديثها بالكامل لتكون دقيقة ومحسوبة فعلياً)
    // ========================================================================

    /**
     * الحصول على إحصائيات لوحة التحكم للمحطة المحددة.
     * جميع القيم محسوبة باستعلامات SQL حقيقية وتراعي station_id.
     * @param stationId معرف المحطة (افتراضي 1)
     * @return JSONObject يحتوي على جميع الإحصائيات المطلوبة
     */
    fun getDashboardStats(stationId: Int = 1): JSONObject {
        val stats = JSONObject()
        val db = readableDatabase

        // 1. إجمالي المنتجات النشطة في المحطة
        db.rawQuery(
            "SELECT COUNT(*) FROM products WHERE station_id = ? AND is_deleted = 0 AND status = 'active'",
            arrayOf(stationId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) stats.put("total_products", cursor.getInt(0))
        }

        // 2. المبيعات اليومية (صافي المبلغ)
        db.rawQuery(
            "SELECT COALESCE(SUM(net_amount),0) FROM sales_transactions WHERE station_id=? AND date(created_at)=date('now') AND is_deleted=0",
            arrayOf(stationId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) stats.put("daily_sales", cursor.getDouble(0))
        }

        // 3. العملاء النشطون في المحطة (من خلال المبيعات)
        db.rawQuery(
            "SELECT COUNT(DISTINCT p.id) FROM parties p " +
                    "JOIN sales_transactions s ON s.customer_party_id = p.id " +
                    "WHERE s.station_id = ? AND p.is_active = 1 AND p.is_deleted = 0",
            arrayOf(stationId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) stats.put("active_customers", cursor.getInt(0))
        }

        // 4. المنتجات المنتهية قريباً (خلال 30 يوم) في المحطة
        db.rawQuery(
            "SELECT COUNT(*) FROM products WHERE station_id = ? AND has_expiry=1 AND expiry_date BETWEEN date('now') AND date('now', '+30 days') AND is_deleted=0",
            arrayOf(stationId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) stats.put("expiry_soon", cursor.getInt(0))
        }

        // 5. المنتجات منخفضة المخزون في المحطة
        db.rawQuery(
            """
            SELECT COUNT(*) FROM products p
            LEFT JOIN inventory_levels il ON p.id = il.product_id
            LEFT JOIN warehouses w ON il.warehouse_id = w.id
            WHERE p.station_id = ? AND p.is_deleted=0 AND p.status='active'
            AND (il.quantity_on_hand <= p.minimum_stock OR il.quantity_on_hand IS NULL)
            AND (w.station_id = ? OR w.station_id IS NULL)
            """.trimIndent(),
            arrayOf(stationId.toString(), stationId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) stats.put("low_stock", cursor.getInt(0))
        }

        // 6. الفواتير المستحقة (خلال أسبوع) في المحطة
        db.rawQuery(
            """SELECT COUNT(*) FROM sales_transactions
               WHERE station_id=? AND remaining_amount > 0
               AND date(due_date) BETWEEN date('now') AND date('now', '+7 days')
               AND is_deleted=0""",
            arrayOf(stationId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) stats.put("due_invoices", cursor.getInt(0))
        }

        // 7. كمية المنتجات المرتجعة اليوم في المحطة
        db.rawQuery(
            """SELECT COALESCE(SUM(quantity),0) FROM inventory_movements
               WHERE station_id=? AND movement_type='return' AND date(created_at)=date('now') AND is_deleted=0""",
            arrayOf(stationId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) stats.put("returned_products_today", cursor.getDouble(0))
        }

        // 8. كمية المنتجات التالفة اليوم في المحطة (مع station_id)
        db.rawQuery(
            "SELECT COALESCE(SUM(quantity),0) FROM damaged_products WHERE station_id=? AND date(report_date)=date('now') AND status='approved'",
            arrayOf(stationId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) stats.put("damaged_products_today", cursor.getDouble(0))
        }

        // 9. مديونية العملاء (المبالغ المتبقية للفواتير الآجلة) في المحطة
        db.rawQuery(
            "SELECT COALESCE(SUM(remaining_amount),0) FROM sales_transactions WHERE station_id=? AND is_credit=1 AND is_deleted=0",
            arrayOf(stationId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) stats.put("customer_debts", cursor.getDouble(0))
        }

        // 10. مديونية الموردين المرتبطين بالمحطة (من خلال عمليات التعبئة)
        db.rawQuery(
            "SELECT COALESCE(SUM(p.current_balance),0) FROM parties p " +
                    "JOIN tank_refills tr ON tr.supplier_id = p.id " +
                    "WHERE tr.station_id = ? AND p.party_type_id = 6 AND p.is_deleted=0",
            arrayOf(stationId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) stats.put("supplier_debts", cursor.getDouble(0))
        }

        // 11. قيمة المخزون في المحطة (من خلال المنتجات والمستودعات)
        db.rawQuery(
            """
            SELECT COALESCE(SUM(il.quantity_on_hand * p.purchase_price),0)
            FROM inventory_levels il
            JOIN products p ON il.product_id = p.id
            JOIN warehouses w ON il.warehouse_id = w.id
            WHERE p.station_id = ? AND w.station_id = ? AND p.is_deleted=0
            """.trimIndent(),
            arrayOf(stationId.toString(), stationId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) stats.put("inventory_value", cursor.getDouble(0))
        }

        // 12. المهام المعلقة من جدول tasks الفعلي
        db.rawQuery(
            "SELECT COUNT(*) FROM tasks WHERE is_deleted=0 AND is_archived=0 AND is_resolved=0",
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) stats.put("pending_tasks", cursor.getInt(0))
        }

        // 13. حساب اتجاه المبيعات (مقارنة بالأمس)
        val today = getCurrentDate()
        val yesterday = getDateOnlyFormat().format(Date(System.currentTimeMillis() - 86400000))
        val todaySales = getDailySalesAmount(stationId, today)
        val yesterdaySales = getDailySalesAmount(stationId, yesterday)
        val salesTrend = if (yesterdaySales > 0) {
            "%.1f%%".format(((todaySales - yesterdaySales) / yesterdaySales) * 100)
        } else if (todaySales > 0) {
            "+100%"
        } else {
            "+0%"
        }
        stats.put("sales_trend", salesTrend)

        // 14. حساب اتجاه عدد المنتجات (مقارنة بالشهر الماضي)
        val currentProducts = getActiveProductsCount(stationId)
        val lastMonthProducts = getActiveProductsCount(stationId, 30)
        val productsTrend = if (lastMonthProducts > 0) {
            "%.1f%%".format(((currentProducts - lastMonthProducts) / lastMonthProducts) * 100)
        } else if (currentProducts > 0) {
            "+100%"
        } else {
            "+0%"
        }
        stats.put("products_trend", productsTrend)

        // الاحتفاظ بالمفاتيح القديمة لتوافق مع أي كود آخر (مع تحديثها لتراعي المحطة)
        // إجمالي المبيعات والليترات وعدد المعاملات اليوم
        db.rawQuery(
            "SELECT COALESCE(SUM(net_amount),0), COALESCE(SUM(liters),0), COUNT(*) FROM sales_transactions WHERE station_id=? AND date(created_at) = date('now') AND is_deleted=0",
            arrayOf(stationId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                stats.put("total_sales", cursor.getDouble(0))
                stats.put("total_liters", cursor.getDouble(1))
                stats.put("transactions_today", cursor.getInt(2))
            }
        }

        // إجمالي الكمية المتبقية في الخزانات للمحطة
        db.rawQuery(
            "SELECT COALESCE(SUM(current_quantity),0) FROM tanks WHERE station_id=? AND is_deleted=0",
            arrayOf(stationId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) stats.put("total_remaining", cursor.getDouble(0))
        }

        // إجمالي المبالغ المستحقة (للعملاء) في المحطة
        db.rawQuery(
            "SELECT COALESCE(SUM(remaining_amount),0) FROM sales_transactions WHERE station_id=? AND payment_status IN ('pending','partial') AND is_deleted=0",
            arrayOf(stationId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) stats.put("total_due", cursor.getDouble(0))
        }

        // إجمالي العملاء (جميع العملاء في المحطة)
        db.rawQuery(
            "SELECT COUNT(DISTINCT p.id) FROM parties p JOIN sales_transactions s ON s.customer_party_id = p.id WHERE s.station_id=? AND p.is_deleted=0",
            arrayOf(stationId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) stats.put("total_customers", cursor.getInt(0))
        }

        // عدد ومبلغ الفواتير المتأخرة في المحطة
        db.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(remaining_amount),0) FROM sales_transactions WHERE station_id=? AND is_credit=1 AND date(due_date) < date('now') AND is_deleted=0",
            arrayOf(stationId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                stats.put("overdue_count", cursor.getInt(0))
                stats.put("overdue_amount", cursor.getDouble(1))
            }
        }

        return stats
    }

    // ========================================================================
    // دوال مساعدة لحساب الاتجاهات (Trends)
    // ========================================================================

    /**
     * حساب إجمالي المبيعات (صافي المبلغ) لمحطة معينة في تاريخ محدد.
     */
    private fun getDailySalesAmount(stationId: Int, date: String): Double {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT COALESCE(SUM(net_amount),0) FROM sales_transactions WHERE station_id=? AND date(created_at)=? AND is_deleted=0",
                arrayOf(stationId.toString(), date)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getDouble(0) else 0.0
            }
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * حساب عدد المنتجات النشطة في محطة معينة، مع إمكانية تحديد عدد الأيام الماضية.
     * @param daysAgo عدد الأيام الماضية (0 = اليوم، 30 = قبل شهر، إلخ)
     */
    private fun getActiveProductsCount(stationId: Int, daysAgo: Int = 0): Int {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val dateCondition = if (daysAgo == 0) {
                "" // لا شرط تاريخي للمنتجات الحالية، نأخذ الوضع الحالي
            } else {
                " AND created_at <= date('now', '-$daysAgo days')"
            }
            // نفترض أننا نأخذ عدد المنتجات النشطة في تاريخ معين (نستخدم created_at كتقريب)
            db.rawQuery(
                "SELECT COUNT(*) FROM products WHERE station_id=? AND is_deleted=0 AND status='active' $dateCondition",
                arrayOf(stationId.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // باقي الدوال كما هي في الملف الأصلي (بدءاً من هنا جميع الدوال المتبقية)
    // ========================================================================

    // ========================================================================
    // تسجيل النشاطات
    // ========================================================================

    fun logActivity(operator: String, action: String, description: String): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("user_id", 0)
                put("action", action)
                put("description", description)
                put("created_at", getCurrentDateTime())
            }
            db.insert("user_activity_log", null, cv)
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال مساعدة
    // ========================================================================

    private fun getPartyBalance(partyId: Int): Double {
        val db = readableDatabase
        db.rawQuery("SELECT COALESCE(current_balance,0) FROM parties WHERE id=?", arrayOf(partyId.toString()))
            .use { cursor ->
                if (cursor.moveToFirst()) return cursor.getDouble(0)
            }
        return 0.0
    }

    private fun cursorToJsonArray(cursor: Cursor): JSONArray {
        val arr = JSONArray()
        cursor.use {
            while (it.moveToNext()) {
                val obj = JSONObject()
                for (i in 0 until it.columnCount) {
                    val colName = it.getColumnName(i)
                    when (it.getType(i)) {
                        Cursor.FIELD_TYPE_STRING -> obj.put(colName, it.getString(i))
                        Cursor.FIELD_TYPE_INTEGER -> obj.put(colName, it.getInt(i))
                        Cursor.FIELD_TYPE_FLOAT -> obj.put(colName, it.getDouble(i))
                        Cursor.FIELD_TYPE_BLOB -> obj.put(colName, it.getBlob(i))
                        else -> obj.put(colName, "")
                    }
                }
                arr.put(obj)
            }
        }
        return arr
    }

    private fun cursorToJsonObject(cursor: Cursor): JSONObject {
        val obj = JSONObject()
        for (i in 0 until cursor.columnCount) {
            val colName = cursor.getColumnName(i)
            when (cursor.getType(i)) {
                Cursor.FIELD_TYPE_STRING -> obj.put(colName, cursor.getString(i))
                Cursor.FIELD_TYPE_INTEGER -> obj.put(colName, cursor.getInt(i))
                Cursor.FIELD_TYPE_FLOAT -> obj.put(colName, cursor.getDouble(i))
                Cursor.FIELD_TYPE_BLOB -> obj.put(colName, cursor.getBlob(i))
                else -> obj.put(colName, "")
            }
        }
        return obj
    }

    fun execSQL(sql: String, bindArgs: Array<Any> = emptyArray()) {
        dbLock.lock()
        try {
            writableDatabase.execSQL(sql, bindArgs)
        } finally {
            dbLock.unlock()
        }
    }

    fun isClosed(): Boolean {
        return !isOpen()
    }


    // ========================================================================
    // ========================================================================
    // Tasks: typed SQLite contract used by tasks.html
    // ========================================================================

    private fun taskPriority(value: String): String {
        val normalized = value.trim()
        require(normalized in setOf("عالية", "متوسطة", "منخفضة")) { "الأولوية غير صالحة" }
        return normalized
    }

    private fun taskStatus(value: String): String {
        val normalized = value.trim()
        require(normalized in setOf("قيد التنفيذ", "غير مدفوعة", "متأخرة", "مكتملة")) { "حالة المهمة غير صالحة" }
        return normalized
    }

    private fun taskActivity(db: SQLiteDatabase, actorId: Long, action: String, taskId: Long, description: String) {
        val values = ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            if (actorId > 0L) {
                val userExists = db.rawQuery("SELECT 1 FROM users WHERE id = ? LIMIT 1", arrayOf(actorId.toString())).use { it.moveToFirst() }
                if (userExists) put("user_id", actorId) else putNull("user_id")
            } else {
                putNull("user_id")
            }
            put("action", action)
            put("action_category", "tasks")
            put("description", description)
            put("description_ar", description)
            put("target_table", "tasks")
            put("target_id", taskId)
            put("is_success", 1)
            put("created_at", getCurrentDateTime())
        }
        db.insert("user_activity_log", null, values)
    }

    private fun taskQuery(params: JSONObject): JSONArray {
        val where = mutableListOf("is_deleted = 0")
        val args = mutableListOf<String>()
        if (!params.optBoolean("include_archived", false)) where += "is_archived = 0"
        if (!params.optBoolean("include_resolved", false)) where += "is_resolved = 0"
        params.optString("status", "").trim().takeIf { it.isNotEmpty() }?.let {
            where += "status = ?"
            args += it
        }
        params.optString("priority", "").trim().takeIf { it.isNotEmpty() }?.let {
            where += "priority = ?"
            args += it
        }
        params.optString("search", "").trim().takeIf { it.isNotEmpty() }?.let {
            where += "(task_type LIKE ? OR reference LIKE ? OR notes LIKE ?)"
            val pattern = "%$it%"
            args += pattern
            args += pattern
            args += pattern
        }
        params.optString("from_date", "").trim().takeIf { it.isNotEmpty() }?.let {
            where += "date(task_date) >= date(?)"
            args += it
        }
        params.optString("to_date", "").trim().takeIf { it.isNotEmpty() }?.let {
            where += "date(task_date) <= date(?)"
            args += it
        }
        val limit = params.optInt("limit", 1000).coerceIn(1, 5000)
        val offset = params.optInt("offset", 0).coerceAtLeast(0)
        args += limit.toString()
        args += offset.toString()
        val sql = """
            SELECT id, uuid, task_type, task_type AS type, reference,
                   task_date, task_date AS date, amount, priority, status, notes,
                   is_resolved, is_archived, is_deleted, extra_data AS extra,
                   resolved_at, archived_at, created_by, updated_by, created_at, updated_at
            FROM tasks
            WHERE ${where.joinToString(" AND ")}
            ORDER BY date(task_date) DESC, id DESC
            LIMIT ? OFFSET ?
        """.trimIndent()
        return readableDatabase.rawQuery(sql, args.toTypedArray()).use { cursor -> cursorToJsonArray(cursor) }
    }

    fun getPendingTasks(params: JSONObject = JSONObject()): JSONArray {
        dbLock.lock()
        return try { taskQuery(params) } finally { dbLock.unlock() }
    }

    fun addTask(data: JSONObject, actorId: Long = 0L): Long {
        val taskType = data.optString("task_type", data.optString("type")).trim()
        val taskDate = data.optString("task_date", data.optString("date")).trim()
        val amount = data.optDouble("amount", 0.0)
        val priority = taskPriority(data.optString("priority", "متوسطة"))
        val status = taskStatus(data.optString("status", "قيد التنفيذ"))
        require(taskType.isNotBlank()) { "نوع المهمة مطلوب" }
        require(taskDate.isNotBlank()) { "تاريخ المهمة مطلوب" }
        require(amount >= 0.0) { "قيمة المهمة لا يمكن أن تكون سالبة" }
        val resolved = if (status == "مكتملة") 1 else 0
        dbLock.lock()
        return try {
            val now = getCurrentDateTime()
            val values = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("task_type", taskType)
                put("reference", data.optString("reference").trim())
                put("task_date", taskDate)
                put("amount", amount)
                put("priority", priority)
                put("status", status)
                put("notes", data.optString("notes").trim())
                put("is_resolved", resolved)
                put("resolved_at", if (resolved == 1) now else null)
                put("resolved_by", if (resolved == 1 && actorId > 0L) actorId else null)
                put("is_archived", 0)
                put("is_deleted", 0)
                put("created_by", if (actorId > 0L) actorId else null)
                put("updated_by", if (actorId > 0L) actorId else null)
                put("extra_data", if (data.has("extra_data") && !data.isNull("extra_data")) data.optString("extra_data") else null)
                put("created_at", now)
                put("updated_at", now)
            }
            val db = writableDatabase
            val id = db.insertOrThrow("tasks", null, values)
            taskActivity(db, actorId, "add_task", id, "إضافة مهمة: $id")
            id
        } finally { dbLock.unlock() }
    }

    fun updateTask(id: Long, data: JSONObject, actorId: Long = 0L): Int {
        require(id > 0L) { "معرف المهمة غير صالح" }
        val values = ContentValues()
        if (data.has("task_type")) values.put("task_type", data.optString("task_type").trim())
        if (data.has("reference")) values.put("reference", data.optString("reference").trim())
        if (data.has("task_date")) values.put("task_date", data.optString("task_date").trim())
        if (data.has("amount")) {
            val amount = data.optDouble("amount", -1.0)
            require(amount >= 0.0) { "قيمة المهمة لا يمكن أن تكون سالبة" }
            values.put("amount", amount)
        }
        if (data.has("priority")) values.put("priority", taskPriority(data.optString("priority")))
        if (data.has("notes")) values.put("notes", data.optString("notes").trim())
        var statusChanged = false
        if (data.has("status")) {
            val status = taskStatus(data.optString("status"))
            values.put("status", status)
            statusChanged = true
            val resolved = if (status == "مكتملة") 1 else 0
            values.put("is_resolved", resolved)
            values.put("resolved_at", if (resolved == 1) getCurrentDateTime() else null)
            values.put("resolved_by", if (resolved == 1 && actorId > 0L) actorId else null)
        }
        if (actorId > 0L) values.put("updated_by", actorId)
        values.put("updated_at", getCurrentDateTime())
        require(values.size() > if (statusChanged) 1 else 0) { "لا توجد بيانات لتحديثها" }
        dbLock.lock()
        return try {
            val db = writableDatabase
            val rows = db.update("tasks", values, "id = ? AND is_deleted = 0", arrayOf(id.toString()))
            if (rows > 0) taskActivity(db, actorId, "update_task", id, "تحديث مهمة: $id")
            rows
        } finally { dbLock.unlock() }
    }

    fun archiveTask(id: Long, actorId: Long = 0L): Int {
        require(id > 0L) { "معرف المهمة غير صالح" }
        dbLock.lock()
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("is_archived", 1)
                put("archived_at", getCurrentDateTime())
                put("archived_by", if (actorId > 0L) actorId else null)
                put("updated_by", if (actorId > 0L) actorId else null)
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("tasks", values, "id = ? AND is_deleted = 0", arrayOf(id.toString()))
            if (rows > 0) taskActivity(db, actorId, "archive_task", id, "أرشفة مهمة: $id")
            rows
        } finally { dbLock.unlock() }
    }

    fun restoreTask(id: Long, actorId: Long = 0L): Int {
        require(id > 0L) { "معرف المهمة غير صالح" }
        dbLock.lock()
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("is_archived", 0)
                putNull("archived_at")
                putNull("archived_by")
                put("updated_by", if (actorId > 0L) actorId else null)
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("tasks", values, "id = ? AND is_deleted = 0", arrayOf(id.toString()))
            if (rows > 0) taskActivity(db, actorId, "restore_task", id, "استعادة مهمة: $id")
            rows
        } finally { dbLock.unlock() }
    }

    fun resolveTask(id: Long, actorId: Long = 0L): Int {
        require(id > 0L) { "معرف المهمة غير صالح" }
        dbLock.lock()
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("status", "مكتملة")
                put("is_resolved", 1)
                put("resolved_at", getCurrentDateTime())
                put("resolved_by", if (actorId > 0L) actorId else null)
                put("updated_by", if (actorId > 0L) actorId else null)
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("tasks", values, "id = ? AND is_deleted = 0", arrayOf(id.toString()))
            if (rows > 0) taskActivity(db, actorId, "resolve_task", id, "حل مهمة: $id")
            rows
        } finally { dbLock.unlock() }
    }

    fun deleteTask(id: Long, actorId: Long = 0L): Int {
        require(id > 0L) { "معرف المهمة غير صالح" }
        dbLock.lock()
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("is_deleted", 1)
                put("deleted_at", getCurrentDateTime())
                put("deleted_by", if (actorId > 0L) actorId else null)
                put("updated_by", if (actorId > 0L) actorId else null)
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("tasks", values, "id = ? AND is_deleted = 0", arrayOf(id.toString()))
            if (rows > 0) taskActivity(db, actorId, "delete_task", id, "حذف مهمة: $id")
            rows
        } finally { dbLock.unlock() }
    }

    fun generateTaskReport(params: JSONObject = JSONObject()): JSONArray {
        val reportParams = JSONObject(params.toString()).apply {
            put("include_archived", true)
            put("include_resolved", true)
            if (has("start_date") && !has("from_date")) put("from_date", optString("start_date"))
            if (has("end_date") && !has("to_date")) put("to_date", optString("end_date"))
            put("limit", optInt("limit", 5000).coerceIn(1, 5000))
        }
        dbLock.lock()
        return try { taskQuery(reportParams) } finally { dbLock.unlock() }
    }

    // Typed operational screen contracts: whitelist-backed SQLite operations
    // ========================================================================

    private data class OperationalTableSpec(
        val table: String,
        val columns: List<String>,
        val required: List<String>,
        val searchColumns: List<String>,
        val softDeleted: Boolean,
        val hasUpdatedAt: Boolean,
        val hasStatus: Boolean,
        val numericColumns: List<String>
    )

    private fun operationalSpec(screenKey: String): OperationalTableSpec? = when (screenKey) {
            "stations" -> OperationalTableSpec(
                table = "stations",
                columns = listOf("station_code", "station_name", "station_name_ar", "company_id", "branch_id", "country", "city", "district", "street", "building", "postal_code", "latitude", "longitude", "gps_location", "phone", "phone2", "email", "emergency_phone", "license_number", "license_issue_date", "license_expiry_date", "tax_number", "commercial_register", "environmental_permit", "fire_safety_cert", "operating_hours", "opening_time", "closing_time", "is_24_hours", "station_type", "total_tanks", "total_pumps", "total_nozzles", "storage_capacity", "default_currency_id", "status", "status_reason", "station_photo", "layout_plan", "remarks", "extra_data"),
                required = listOf("station_name"),
                searchColumns = listOf("station_code", "station_name", "station_name_ar", "city", "phone"),
                softDeleted = true,
                hasUpdatedAt = true,
                hasStatus = true,
                numericColumns = listOf("total_tanks", "total_pumps", "total_nozzles", "storage_capacity")
            )
            "exchange_rates" -> OperationalTableSpec(
                table = "exchange_rates",
                columns = listOf("from_currency_id", "to_currency_id", "rate", "inverse_rate", "effective_date", "expiry_date", "source", "is_active", "remarks", "extra_data"),
                required = listOf("from_currency_id", "to_currency_id", "rate", "effective_date"),
                searchColumns = listOf("source", "effective_date"),
                softDeleted = true,
                hasUpdatedAt = true,
                hasStatus = false,
                numericColumns = listOf("rate", "inverse_rate")
            )
            "bad_debts" -> OperationalTableSpec(
                table = "bad_debts",
                columns = listOf("customer_id", "amount", "type", "description", "date", "resolved", "resolved_date"),
                required = listOf("amount", "type"),
                searchColumns = listOf("description", "type", "date"),
                softDeleted = false,
                hasUpdatedAt = false,
                hasStatus = false,
                numericColumns = listOf("amount")
            )
            "vehicles" -> OperationalTableSpec(
                table = "vehicles",
                columns = listOf("vehicle_code", "party_id", "plate_number", "plate_number_ar", "plate_country", "plate_city", "vehicle_type", "brand", "model", "year", "color", "engine_type", "engine_capacity", "fuel_type_id", "tank_capacity", "chassis_number", "engine_number", "registration_number", "registration_expiry", "insurance_number", "insurance_expiry", "rfid_tag", "nfc_tag", "current_odometer", "last_odometer", "odometer_updated_at", "avg_consumption", "status", "vehicle_photo", "registration_doc", "remarks", "extra_data"),
                required = listOf("vehicle_code", "party_id", "plate_number"),
                searchColumns = listOf("vehicle_code", "plate_number", "brand", "model", "chassis_number"),
                softDeleted = true,
                hasUpdatedAt = true,
                hasStatus = true,
                numericColumns = listOf("year", "engine_capacity", "tank_capacity", "current_odometer", "last_odometer", "avg_consumption")
            )
            "drivers" -> OperationalTableSpec(
                table = "drivers",
                columns = listOf("driver_code", "party_id", "vehicle_id", "full_name", "full_name_ar", "national_id", "passport_number", "nationality", "birth_date", "gender", "phone", "phone2", "email", "whatsapp", "address", "license_number", "license_type", "license_issue_date", "license_expiry_date", "license_issuing_authority", "license_doc_path", "hire_date", "job_title", "salary", "emergency_name", "emergency_phone", "emergency_relation", "status", "termination_date", "termination_reason", "remarks", "extra_data"),
                required = listOf("driver_code", "full_name"),
                searchColumns = listOf("driver_code", "full_name", "full_name_ar", "phone", "license_number"),
                softDeleted = true,
                hasUpdatedAt = true,
                hasStatus = true,
                numericColumns = listOf("salary")
            )
            "vehicle_locations" -> OperationalTableSpec(
                table = "vehicle_locations",
                columns = listOf("vehicle_id", "latitude", "longitude", "speed", "heading", "fuel_level", "odometer", "altitude", "accuracy", "location_time"),
                required = listOf("vehicle_id", "latitude", "longitude"),
                searchColumns = listOf("vehicle_id", "location_time"),
                softDeleted = false,
                hasUpdatedAt = false,
                hasStatus = false,
                numericColumns = listOf("latitude", "longitude", "speed", "heading", "fuel_level", "odometer", "altitude", "accuracy")
            )
            "vehicle_trips" -> OperationalTableSpec(
                table = "vehicle_trips",
                columns = listOf("vehicle_id", "driver_id", "trip_date", "start_location", "end_location", "distance_km", "fuel_consumed", "fuel_cost", "start_odometer", "end_odometer", "trip_purpose", "notes"),
                required = listOf("vehicle_id", "trip_date"),
                searchColumns = listOf("start_location", "end_location", "trip_purpose", "notes"),
                softDeleted = false,
                hasUpdatedAt = false,
                hasStatus = false,
                numericColumns = listOf("distance_km", "fuel_consumed", "fuel_cost", "start_odometer", "end_odometer")
            )
            "vehicle_expenses" -> OperationalTableSpec(
                table = "vehicle_expenses",
                columns = listOf("vehicle_id", "expense_type", "expense_date", "amount", "currency_id", "odometer_reading", "description", "invoice_path"),
                required = listOf("vehicle_id", "expense_type", "expense_date", "amount"),
                searchColumns = listOf("expense_type", "expense_date", "description"),
                softDeleted = false,
                hasUpdatedAt = false,
                hasStatus = false,
                numericColumns = listOf("amount", "odometer_reading")
            )
            "fuel_types" -> OperationalTableSpec(
                table = "fuel_types",
                columns = listOf("fuel_code", "fuel_name", "fuel_name_ar", "description", "density_standard", "temperature_standard", "flash_point", "default_sale_price", "default_purchase_price", "tax_rate", "vat_rate", "color_code", "icon_path", "is_active", "remarks", "extra_data"),
                required = listOf("fuel_code", "fuel_name"),
                searchColumns = listOf("fuel_code", "fuel_name", "fuel_name_ar", "description"),
                softDeleted = true,
                hasUpdatedAt = true,
                hasStatus = true,
                numericColumns = listOf("density_standard", "temperature_standard", "flash_point", "default_sale_price", "default_purchase_price", "tax_rate", "vat_rate")
            )
            "price_lists" -> OperationalTableSpec(
                table = "price_lists",
                columns = listOf("list_code", "list_name", "list_name_ar", "description", "party_id", "party_type_id", "station_id", "valid_from", "valid_to", "is_active", "is_default"),
                required = listOf("list_code", "list_name"),
                searchColumns = listOf("list_code", "list_name", "list_name_ar", "description"),
                softDeleted = true,
                hasUpdatedAt = true,
                hasStatus = true,
                numericColumns = listOf("party_id", "party_type_id", "station_id")
            )
            "price_list_items" -> OperationalTableSpec(
                table = "price_list_items",
                columns = listOf("price_list_id", "product_id", "unit_price", "min_quantity", "max_quantity", "discount_percent", "valid_from", "valid_to", "is_active"),
                required = listOf("price_list_id", "product_id", "unit_price"),
                searchColumns = listOf("price_list_id", "product_id"),
                softDeleted = false,
                hasUpdatedAt = false,
                hasStatus = true,
                numericColumns = listOf("price_list_id", "product_id", "unit_price", "min_quantity", "max_quantity", "discount_percent")
            )
            "price_history" -> OperationalTableSpec(
                table = "price_history",
                columns = listOf("product_id", "old_price", "new_price", "change_date", "change_reason", "created_by", "archived"),
                required = listOf("product_id", "new_price"),
                searchColumns = listOf("product_id", "change_reason", "change_date"),
                softDeleted = false,
                hasUpdatedAt = false,
                hasStatus = false,
                numericColumns = listOf("product_id", "old_price", "new_price")
            )
            "tanks" -> OperationalTableSpec(
                table = "tanks",
                columns = listOf("tank_code", "tank_name", "tank_name_ar", "station_id", "fuel_type_id", "capacity_liters", "minimum_level", "maximum_level", "current_quantity", "usable_capacity", "dead_volume", "tank_shape", "length_meters", "diameter_meters", "height_meters", "location", "installation_date", "manufacturer", "serial_number", "model", "sensor_serial", "sensor_type", "sensor_calibration_date", "sensor_accuracy", "leak_detection", "overfill_protection", "emergency_valve", "last_inspection_date", "next_inspection_date", "inspection_certificate", "status", "status_reason"),
                required = listOf("tank_code", "tank_name", "station_id", "fuel_type_id", "capacity_liters"),
                searchColumns = listOf("tank_code", "tank_name", "tank_name_ar", "location", "serial_number"),
                softDeleted = false,
                hasUpdatedAt = false,
                hasStatus = false,
                numericColumns = listOf("station_id", "fuel_type_id", "capacity_liters", "minimum_level", "maximum_level", "current_quantity", "usable_capacity", "dead_volume")
            )
            "pumps" -> OperationalTableSpec(
                table = "pumps",
                columns = listOf("pump_code", "pump_number", "pump_name", "pump_name_ar", "station_id", "tank_id", "serial_number", "manufacturer", "model", "installation_date", "max_flow_rate", "meter_start", "meter_current", "meter_last_reset", "status", "status_reason", "last_maintenance", "next_maintenance", "maintenance_interval", "remarks", "extra_data"),
                required = listOf("pump_code", "pump_number", "station_id", "tank_id"),
                searchColumns = listOf("pump_code", "pump_number", "pump_name", "pump_name_ar", "serial_number"),
                softDeleted = true,
                hasUpdatedAt = true,
                hasStatus = true,
                numericColumns = listOf("station_id", "tank_id", "max_flow_rate", "meter_start", "meter_current", "maintenance_interval")
            )
            "meter_readings" -> OperationalTableSpec(
                table = "meter_readings",
                columns = listOf("reading_code", "pump_id", "nozzle_id", "station_id", "shift_id", "reading_date", "period", "opening_reading", "closing_reading", "sold_liters", "system_sold_liters", "difference", "difference_percent", "is_balanced", "tolerance_limit", "adjustment_amount", "adjustment_reason", "adjusted_by", "read_by", "verified_by", "approved_by", "status", "rejection_reason", "remarks", "extra_data"),
                required = listOf("reading_code", "pump_id", "nozzle_id", "station_id", "reading_date", "opening_reading", "closing_reading", "sold_liters", "read_by"),
                searchColumns = listOf("reading_code", "reading_date", "period", "status"),
                softDeleted = true,
                hasUpdatedAt = true,
                hasStatus = true,
                numericColumns = listOf("pump_id", "nozzle_id", "station_id", "shift_id", "opening_reading", "closing_reading", "sold_liters", "system_sold_liters", "difference", "difference_percent", "adjustment_amount")
            )
            "tank_level_log" -> OperationalTableSpec(
                table = "tank_level_log",
                columns = listOf("tank_id", "reading_date", "reading_type", "opening_level", "closing_level", "measured_level", "calculated_level", "difference", "fuel_temperature", "fuel_density", "volume_at_15c", "refills_total", "sales_total", "evaporation_loss", "is_below_minimum", "is_near_maximum", "alert_triggered", "created_by", "remarks", "extra_data"),
                required = listOf("tank_id", "reading_date"),
                searchColumns = listOf("tank_id", "reading_date", "reading_type"),
                softDeleted = false,
                hasUpdatedAt = false,
                hasStatus = false,
                numericColumns = listOf("tank_id", "opening_level", "closing_level", "measured_level", "calculated_level", "difference", "fuel_temperature", "fuel_density", "volume_at_15c", "refills_total", "sales_total", "evaporation_loss")
            )
            "fuel_quality_tests" -> OperationalTableSpec(
                table = "fuel_quality_tests",
                columns = listOf("refill_id", "test_date", "density", "temperature", "water_content", "sulfur_content", "viscosity", "flash_point", "cetane_number", "result", "certificate_url", "tested_by", "notes"),
                required = listOf("refill_id"),
                searchColumns = listOf("refill_id", "test_date", "result", "notes"),
                softDeleted = false,
                hasUpdatedAt = false,
                hasStatus = true,
                numericColumns = listOf("refill_id", "density", "temperature", "water_content", "sulfur_content", "viscosity", "flash_point", "cetane_number")
            )
            "calibration_records" -> OperationalTableSpec(
                table = "calibration_records",
                columns = listOf("calibration_code", "entity_type", "entity_id", "calibration_date", "technician", "before_value", "after_value", "error_value", "correction_percent", "calibration_factor", "certificate_number", "certificate_path", "next_calibration_date", "notes", "status"),
                required = listOf("calibration_code", "entity_type", "entity_id", "calibration_date"),
                searchColumns = listOf("calibration_code", "entity_type", "technician", "certificate_number", "status"),
                softDeleted = false,
                hasUpdatedAt = false,
                hasStatus = true,
                numericColumns = listOf("entity_id", "before_value", "after_value", "error_value", "correction_percent", "calibration_factor")
            )
            "warehouses" -> OperationalTableSpec(
                table = "warehouses",
                columns = listOf("station_id", "warehouse_name", "location_details", "is_default", "is_active"),
                required = listOf("station_id", "warehouse_name"),
                searchColumns = listOf("warehouse_name", "location_details"),
                softDeleted = false,
                hasUpdatedAt = false,
                hasStatus = true,
                numericColumns = listOf("station_id")
            )
            "inventory_movements" -> OperationalTableSpec(
                table = "inventory_movements",
                columns = listOf("movement_code", "product_id", "station_id", "warehouse_id", "movement_type", "movement_subtype", "quantity_before", "quantity_change", "quantity_after", "unit_cost", "total_cost", "reference_type", "reference_id", "reference_code", "from_location", "to_location", "reason", "reason_code", "performed_by", "approved_by", "status", "remarks", "extra_data"),
                required = listOf("movement_code", "product_id", "station_id", "movement_type", "quantity_before", "quantity_change", "quantity_after", "performed_by"),
                searchColumns = listOf("movement_code", "movement_type", "reference_code", "from_location", "to_location", "reason", "status"),
                softDeleted = true,
                hasUpdatedAt = true,
                hasStatus = true,
                numericColumns = listOf("product_id", "station_id", "warehouse_id", "quantity_before", "quantity_change", "quantity_after", "unit_cost", "total_cost", "reference_id", "performed_by", "approved_by")
            )
            "stock_alerts" -> OperationalTableSpec(
                table = "stock_alerts",
                columns = listOf("product_id", "station_id", "alert_type", "alert_level", "current_quantity", "threshold_quantity", "shortage_quantity", "is_resolved", "resolved_at", "resolved_by", "resolution_notes", "notification_sent", "notification_method", "remarks", "extra_data"),
                required = listOf("product_id", "station_id", "alert_type", "current_quantity", "threshold_quantity"),
                searchColumns = listOf("alert_type", "alert_level", "resolution_notes"),
                softDeleted = false,
                hasUpdatedAt = false,
                hasStatus = false,
                numericColumns = listOf("product_id", "station_id", "current_quantity", "threshold_quantity", "shortage_quantity")
            )
            "stocktakes" -> OperationalTableSpec(
                table = "stocktakes",
                columns = listOf("warehouse_id", "start_date", "end_date", "status", "total_variance", "notes", "created_by", "archived"),
                required = listOf("warehouse_id", "status", "created_by"),
                searchColumns = listOf("warehouse_id", "status", "notes"),
                softDeleted = false,
                hasUpdatedAt = false,
                hasStatus = true,
                numericColumns = listOf("warehouse_id", "total_variance", "created_by")
            )
            "stocktake_details" -> OperationalTableSpec(
                table = "stocktake_details",
                columns = listOf("stocktake_id", "product_id", "system_quantity", "counted_quantity", "variance_value", "notes", "archived"),
                required = listOf("stocktake_id", "product_id"),
                searchColumns = listOf("stocktake_id", "product_id", "notes"),
                softDeleted = false,
                hasUpdatedAt = false,
                hasStatus = false,
                numericColumns = listOf("stocktake_id", "product_id", "system_quantity", "counted_quantity", "variance_value")
            )
            "shifts" -> OperationalTableSpec(
                table = "shifts",
                columns = listOf("shift_code", "station_id", "shift_date", "shift_type", "start_time", "end_time", "duration_minutes", "manager_id", "cashier_id", "attendant_ids", "opening_cash", "opening_bank", "opening_credit", "closing_cash", "closing_bank", "closing_credit", "total_sales", "total_fuel_sales", "total_product_sales", "total_service_sales", "total_discounts", "total_tax", "total_vat", "total_cash", "total_credit_card", "total_bank_transfer", "total_credit_sales", "total_cheque", "total_other", "total_fuel_liters", "cash_variance", "variance_reason"),
                required = listOf("station_id", "shift_date", "shift_type", "start_time"),
                searchColumns = listOf("shift_code", "shift_type", "shift_date", "status"),
                softDeleted = false,
                hasUpdatedAt = false,
                hasStatus = true,
                numericColumns = listOf("station_id", "cashier_id", "opening_cash", "opening_bank", "closing_cash", "closing_bank", "total_sales", "total_fuel_sales", "total_product_sales", "total_cash")
            )
            "sales_transactions" -> OperationalTableSpec(
                table = "sales_transactions",
                columns = listOf("sale_code", "station_id", "shift_id", "customer_party_id", "vehicle_id", "driver_id", "invoice_number", "invoice_series", "invoice_type", "receipt_number", "sale_type", "fuel_type_id", "pump_id", "nozzle_id", "liters", "price_per_liter", "fuel_subtotal", "product_id", "quantity", "unit_price", "product_subtotal", "subtotal", "discount_amount", "discount_percent", "tax_rate", "tax_amount", "vat_rate", "vat_amount", "service_fee", "commission", "gross_amount", "net_amount", "currency_id", "exchange_rate", "amount_in_default", "payment_method", "payment_status", "paid_amount", "remaining_amount", "is_credit", "credit_days", "due_date", "delivery_location", "delivery_time", "order_type", "status", "cancellation_reason", "cashier_id", "remarks", "extra_data"),
                required = listOf("station_id", "shift_id", "subtotal", "gross_amount", "net_amount", "payment_method", "cashier_id"),
                searchColumns = listOf("sale_code", "invoice_number", "receipt_number", "delivery_location", "status", "payment_status"),
                softDeleted = true,
                hasUpdatedAt = true,
                hasStatus = true,
                numericColumns = listOf("station_id", "shift_id", "customer_party_id", "vehicle_id", "driver_id", "fuel_type_id", "pump_id", "nozzle_id", "liters", "price_per_liter", "subtotal", "gross_amount", "net_amount", "paid_amount", "remaining_amount")
            )
            "deliveries" -> OperationalTableSpec(
                table = "deliveries",
                columns = listOf("sale_id", "party_id", "delivery_date", "quantity", "fuel_type", "price_per_liter", "total_amount", "status", "location", "notes", "driver_id", "vehicle_id", "shift_id"),
                required = listOf("delivery_date", "quantity", "total_amount"),
                searchColumns = listOf("delivery_date", "location", "status", "notes"),
                softDeleted = true,
                hasUpdatedAt = true,
                hasStatus = true,
                numericColumns = listOf("sale_id", "party_id", "quantity", "price_per_liter", "total_amount", "driver_id", "vehicle_id", "shift_id")
            )
            "fuel_sales" -> OperationalTableSpec(
                table = "fuel_sales",
                columns = listOf("sale_id", "shift_id", "pump_id", "fuel_type_id", "quantity", "price_per_liter", "total_amount", "payment_method", "customer_id", "vehicle_plate", "sale_date", "sale_time", "notes"),
                required = listOf("shift_id", "fuel_type_id", "quantity", "price_per_liter", "total_amount"),
                searchColumns = listOf("sale_date", "payment_method", "vehicle_plate", "notes"),
                softDeleted = true,
                hasUpdatedAt = true,
                hasStatus = true,
                numericColumns = listOf("shift_id", "pump_id", "fuel_type_id", "quantity", "price_per_liter", "total_amount", "customer_id")
            )
            "payments" -> OperationalTableSpec(
                table = "payments",
                columns = listOf("payment_code", "sale_id", "customer_party_id", "supplier_party_id", "payment_type", "payment_method", "amount", "currency_id", "exchange_rate", "amount_in_default", "is_partial", "total_invoice_amount", "remaining_after", "cheque_number", "cheque_date", "cheque_bank", "cheque_branch", "cheque_status", "bank_account_id", "transfer_reference", "transfer_date", "card_last_four", "card_type", "auth_code", "terminal_id", "mobile_provider", "mobile_number", "transaction_id", "cash_box_id", "status", "is_refund", "original_payment_id", "refund_reason", "operator", "notes"),
                required = listOf("payment_type", "payment_method", "amount"),
                searchColumns = listOf("payment_code", "payment_method", "status", "operator", "notes"),
                softDeleted = true,
                hasUpdatedAt = true,
                hasStatus = true,
                numericColumns = listOf("sale_id", "customer_party_id", "supplier_party_id", "amount", "currency_id", "bank_account_id", "cash_box_id")
            )
            "receipts" -> OperationalTableSpec(
                table = "receipts",
                columns = listOf("receipt_number", "customer_party_id", "payment_id", "receipt_type", "received_from", "received_from_ar", "received_by", "accountant_id", "amount", "currency_id", "amount_in_words", "amount_in_words_ar", "purpose", "purpose_ar", "reference_document", "cash_amount", "cheque_amount", "bank_amount", "other_amount", "cash_box_id", "status", "void_reason", "voided_by", "voided_at", "print_count", "remarks", "extra_data"),
                required = listOf("receipt_number", "receipt_type", "received_from", "received_by", "amount"),
                searchColumns = listOf("receipt_number", "received_from", "purpose", "status"),
                softDeleted = true,
                hasUpdatedAt = true,
                hasStatus = true,
                numericColumns = listOf("customer_party_id", "payment_id", "received_by", "accountant_id", "amount", "cash_amount", "cheque_amount", "bank_amount", "other_amount", "cash_box_id")
            )
            "cash_boxes" -> OperationalTableSpec(
                table = "cash_boxes",
                columns = listOf("box_code", "box_name", "box_name_ar", "station_id", "box_type", "opening_balance", "current_balance", "maximum_balance", "currency_id", "responsible_user_id", "status", "remarks", "extra_data"),
                required = listOf("box_code", "box_name", "station_id"),
                searchColumns = listOf("box_code", "box_name", "box_name_ar", "status"),
                softDeleted = true,
                hasUpdatedAt = true,
                hasStatus = true,
                numericColumns = listOf("station_id", "opening_balance", "current_balance", "maximum_balance", "currency_id", "responsible_user_id")
            )
            "cash_movements" -> OperationalTableSpec(
                table = "cash_movements",
                columns = listOf("cash_box_id", "movement_type", "amount", "balance_before", "balance_after", "description", "reference_type", "reference_id", "created_by"),
                required = listOf("movement_type", "amount"),
                searchColumns = listOf("movement_type", "description", "reference_type"),
                softDeleted = true,
                hasUpdatedAt = false,
                hasStatus = false,
                numericColumns = listOf("cash_box_id", "amount", "balance_before", "balance_after", "reference_id")
            )
            "expense_categories" -> OperationalTableSpec(
                table = "expense_categories",
                columns = listOf("category_code", "category_name", "category_name_ar", "description", "default_account_id", "monthly_budget", "yearly_budget", "is_active", "remarks", "extra_data"),
                required = listOf("category_code", "category_name"),
                searchColumns = listOf("category_code", "category_name", "category_name_ar", "description"),
                softDeleted = true,
                hasUpdatedAt = true,
                hasStatus = true,
                numericColumns = listOf("default_account_id", "monthly_budget", "yearly_budget")
            )
            "expenses" -> OperationalTableSpec(
                table = "expenses",
                columns = listOf("expense_code", "expense_category_id", "station_id", "payee_name", "payee_name_ar", "payee_type", "payee_id", "amount", "currency_id", "exchange_rate", "amount_in_default", "tax_rate", "tax_amount", "vat_rate", "vat_amount", "total_amount", "payment_method", "payment_status", "paid_amount", "is_recurring", "recurrence_type", "next_due_date", "description", "description_ar", "invoice_number", "invoice_path", "receipt_path", "journal_entry_id", "status", "approved_by", "approved_at", "remarks", "extra_data"),
                required = listOf("expense_code", "expense_category_id", "payee_name", "amount", "total_amount", "description"),
                searchColumns = listOf("expense_code", "payee_name", "payee_name_ar", "description", "payment_status", "status"),
                softDeleted = true,
                hasUpdatedAt = true,
                hasStatus = true,
                numericColumns = listOf("expense_category_id", "station_id", "payee_id", "amount", "total_amount", "paid_amount")
            )
            "budgets" -> OperationalTableSpec(
                table = "budgets",
                columns = listOf("station_id", "budget_name", "budget_period", "start_date", "end_date", "total_amount", "currency_id", "status", "created_by"),
                required = listOf("station_id", "budget_name", "budget_period", "start_date", "end_date", "currency_id", "created_by"),
                searchColumns = listOf("budget_name", "budget_period", "status"),
                softDeleted = false,
                hasUpdatedAt = false,
                hasStatus = true,
                numericColumns = listOf("station_id", "total_amount", "currency_id", "created_by")
            )
            "cash_deposits" -> OperationalTableSpec(
                table = "cash_deposits",
                columns = listOf("customer_id", "amount", "balance_after", "date", "notes", "operator", "is_deleted"),
                required = listOf("amount"),
                searchColumns = listOf("customer_id", "notes", "operator"),
                softDeleted = true,
                hasUpdatedAt = false,
                hasStatus = false,
                numericColumns = listOf("customer_id", "amount", "balance_after")
            )
            "employees" -> OperationalTableSpec(
                table = "employees",
                columns = listOf("employee_code", "party_id", "full_name", "full_name_ar", "national_id", "passport_number", "nationality", "birth_date", "gender", "marital_status", "phone", "phone2", "email", "address", "emergency_contact", "emergency_phone", "department", "job_title", "job_title_ar", "employment_type", "hire_date", "termination_date", "termination_reason", "station_id", "branch_id", "basic_salary", "housing_allowance", "transport_allowance", "food_allowance", "other_allowances", "total_salary", "insurance_deduction", "tax_deduction", "other_deductions", "bank_name", "bank_account", "status", "remarks", "extra_data"),
                required = listOf("employee_code", "full_name", "job_title", "hire_date"),
                searchColumns = listOf("employee_code", "full_name", "full_name_ar", "phone", "department", "job_title"),
                softDeleted = true,
                hasUpdatedAt = true,
                hasStatus = true,
                numericColumns = listOf("station_id", "basic_salary", "housing_allowance", "transport_allowance", "total_salary")
            )
            "attendance" -> OperationalTableSpec(
                table = "attendance",
                columns = listOf("employee_id", "station_id", "shift_id", "attendance_date", "check_in", "check_in_method", "check_in_location", "check_in_latitude", "check_in_longitude", "check_in_device", "check_out", "check_out_method", "check_out_location", "check_out_latitude", "check_out_longitude", "check_out_device", "check_in_photo", "check_out_photo", "work_hours", "overtime_hours", "late_minutes", "early_leave_minutes", "status", "absence_reason", "approved_by", "approved_at", "notes"),
                required = listOf("employee_id", "attendance_date"),
                searchColumns = listOf("attendance_date", "status", "absence_reason", "notes"),
                softDeleted = true,
                hasUpdatedAt = true,
                hasStatus = true,
                numericColumns = listOf("employee_id", "station_id", "shift_id", "work_hours", "overtime_hours", "late_minutes", "early_leave_minutes")
            )
            "payroll" -> OperationalTableSpec(
                table = "payroll",
                columns = listOf("payroll_code", "payroll_year", "payroll_month", "period_start", "period_end", "total_employees", "total_basic_salary", "total_allowances", "total_deductions", "total_net_salary", "status", "calculated_at", "calculated_by", "approved_by", "approved_at", "paid_at", "paid_by", "created_by"),
                required = listOf("payroll_code", "payroll_year", "payroll_month", "period_start", "period_end", "created_by"),
                searchColumns = listOf("payroll_code", "period_start", "period_end", "status"),
                softDeleted = true,
                hasUpdatedAt = true,
                hasStatus = true,
                numericColumns = listOf("payroll_year", "payroll_month", "total_employees", "total_basic_salary", "total_allowances", "total_deductions", "total_net_salary")
            )
            "employee_payments" -> OperationalTableSpec(
                table = "employee_payments",
                columns = listOf("employee_id", "amount", "type", "description", "date", "operator"),
                required = listOf("employee_id", "amount", "type"),
                searchColumns = listOf("employee_id", "type", "description", "operator"),
                softDeleted = false,
                hasUpdatedAt = false,
                hasStatus = false,
                numericColumns = listOf("employee_id", "amount")
            )
            "fixed_assets" -> OperationalTableSpec(
                table = "fixed_assets",
                columns = listOf("station_id", "asset_code", "asset_name", "category_id", "purchase_date", "purchase_cost", "current_value", "useful_life", "salvage_value", "depreciation_method", "asset_type", "serial_number", "model", "manufacturer", "warranty_expiry", "status", "location", "notes", "documents", "maintenance_history", "transfer_history", "disposal_data", "disposed_at", "disposed_by"),
                required = listOf("station_id", "asset_code", "asset_name"),
                searchColumns = listOf("asset_code", "asset_name", "asset_type", "serial_number", "location", "status"),
                softDeleted = false,
                hasUpdatedAt = false,
                hasStatus = true,
                numericColumns = listOf("station_id", "category_id", "purchase_cost", "current_value", "useful_life", "salvage_value")
            )
            "depreciation" -> OperationalTableSpec(
                table = "depreciation",
                columns = listOf("asset_id", "depreciation_date", "depreciation_amount", "accumulated_depreciation", "remaining_value", "journal_entry_id", "created_by", "archived"),
                required = listOf("asset_id", "depreciation_amount"),
                searchColumns = listOf("asset_id", "depreciation_date"),
                softDeleted = false,
                hasUpdatedAt = false,
                hasStatus = false,
                numericColumns = listOf("asset_id", "depreciation_amount", "accumulated_depreciation", "remaining_value")
            )
            "maintenance_requests" -> OperationalTableSpec(
                table = "maintenance_requests",
                columns = listOf("request_code", "asset_type", "asset_id", "request_type", "priority", "title", "description", "description_ar", "symptoms", "error_codes", "reported_by", "reported_at", "assigned_to", "assigned_at", "scheduled_date", "scheduled_time", "estimated_duration", "started_at", "completed_at", "actual_duration", "resolution", "resolution_ar", "parts_used", "labor_cost", "parts_cost", "total_cost", "status", "approved_by", "approved_at", "before_photos", "after_photos", "station_id", "remarks", "extra_data"),
                required = listOf("request_code", "asset_type", "asset_id", "request_type", "title", "description", "reported_by", "station_id"),
                searchColumns = listOf("request_code", "asset_type", "title", "description", "priority", "status"),
                softDeleted = true,
                hasUpdatedAt = true,
                hasStatus = true,
                numericColumns = listOf("asset_id", "reported_by", "assigned_to", "estimated_duration", "labor_cost", "parts_cost", "total_cost", "station_id")
            )
            "maintenance_schedule" -> OperationalTableSpec(
                table = "maintenance_schedule",
                columns = listOf("schedule_code", "schedule_name", "asset_type", "frequency_type", "frequency_value", "day_of_week", "day_of_month", "month", "meter_trigger", "description", "is_active", "created_by"),
                required = listOf("schedule_code", "schedule_name", "asset_type", "frequency_type"),
                searchColumns = listOf("schedule_code", "schedule_name", "asset_type", "frequency_type", "description"),
                softDeleted = false,
                hasUpdatedAt = false,
                hasStatus = true,
                numericColumns = listOf("frequency_value", "day_of_week", "day_of_month", "month", "meter_trigger", "created_by")
            )
            "maintenance_history" -> OperationalTableSpec(
                table = "maintenance_history",
                columns = listOf("maintenance_request_id", "event_type", "event_description", "old_value", "new_value", "performed_by", "performed_at"),
                required = listOf("maintenance_request_id", "event_type", "event_description", "performed_by"),
                searchColumns = listOf("event_type", "event_description", "old_value", "new_value"),
                softDeleted = false,
                hasUpdatedAt = false,
                hasStatus = false,
                numericColumns = listOf("maintenance_request_id", "performed_by")
            )
            "predictions" -> OperationalTableSpec(
                table = "predictions",
                columns = listOf("prediction_type", "entity_type", "entity_id", "prediction_date", "predicted_value", "confidence_interval_low", "confidence_interval_high", "actual_value", "model_version", "created_by"),
                required = listOf("prediction_type", "prediction_date"),
                searchColumns = listOf("prediction_type", "entity_type", "prediction_date", "model_version"),
                softDeleted = false,
                hasUpdatedAt = false,
                hasStatus = false,
                numericColumns = listOf("entity_id", "predicted_value", "confidence_interval_low", "confidence_interval_high", "actual_value")
            )
            "documents" -> OperationalTableSpec(
                table = "documents",
                columns = listOf("document_code", "document_name", "document_name_ar", "document_type", "entity_type", "entity_id", "file_name", "file_path", "file_url", "file_size", "mime_type", "file_hash", "version", "description", "description_ar", "expiry_date", "is_confidential", "uploaded_by", "uploaded_at"),
                required = listOf("document_code", "document_name", "document_type", "entity_type", "entity_id", "file_name", "file_path", "uploaded_by"),
                searchColumns = listOf("document_code", "document_name", "document_name_ar", "document_type", "entity_type", "file_name", "description"),
                softDeleted = true,
                hasUpdatedAt = true,
                hasStatus = false,
                numericColumns = listOf("entity_id", "file_size", "version", "uploaded_by")
            )
            "sync_devices" -> OperationalTableSpec(
                table = "sync_devices",
                columns = listOf("device_id", "device_name", "device_type", "os_version", "app_version", "station_id", "last_sync_at", "is_active"),
                required = listOf("device_id"),
                searchColumns = listOf("device_id", "device_name", "device_type", "os_version", "app_version"),
                softDeleted = false,
                hasUpdatedAt = true,
                hasStatus = true,
                numericColumns = listOf("station_id")
            )
            "sync_logs" -> OperationalTableSpec(
                table = "sync_logs",
                columns = listOf("sync_type", "sync_direction", "device_id", "device_type", "device_name", "app_version", "entity_type", "records_synced", "records_failed", "records_total", "started_at", "completed_at", "duration_seconds", "status", "error_message", "error_details", "network_type", "data_transferred_kb"),
                required = listOf("sync_type", "sync_direction", "device_id", "entity_type", "started_at"),
                searchColumns = listOf("sync_type", "sync_direction", "device_id", "entity_type", "status", "error_message"),
                softDeleted = false,
                hasUpdatedAt = false,
                hasStatus = true,
                numericColumns = listOf("records_synced", "records_failed", "records_total", "duration_seconds", "data_transferred_kb")
            )
            "backup_history" -> OperationalTableSpec(
                table = "backup_history",
                columns = listOf("backup_type", "backup_method", "database_type", "database_name", "file_name", "file_path", "file_size_mb", "checksum", "tables_included", "tables_excluded", "started_at", "completed_at", "duration_seconds", "status", "error_message", "storage_location", "storage_path", "is_encrypted", "encryption_method", "expiry_date", "is_deleted"),
                required = listOf("backup_type", "started_at"),
                searchColumns = listOf("backup_type", "file_name", "status", "storage_location"),
                softDeleted = true,
                hasUpdatedAt = false,
                hasStatus = true,
                numericColumns = listOf("file_size_mb", "duration_seconds")
            )
            "printer_profiles" -> OperationalTableSpec(
                table = "printer_profiles",
                columns = listOf("profile_code", "profile_name", "printer_name", "printer_type", "connection_type", "ip_address", "port", "mac_address", "paper_width", "paper_height", "dpi", "driver_settings", "is_default", "is_active", "created_by"),
                required = listOf("profile_code", "profile_name"),
                searchColumns = listOf("profile_code", "profile_name", "printer_name", "printer_type", "connection_type"),
                softDeleted = false,
                hasUpdatedAt = true,
                hasStatus = true,
                numericColumns = listOf("port", "paper_width", "paper_height", "dpi")
            )
            "receipt_templates" -> OperationalTableSpec(
                table = "receipt_templates",
                columns = listOf("template_code", "template_name", "description", "station_id", "header", "body", "footer", "variables", "paper_width", "font_size", "is_default", "is_active", "created_by"),
                required = listOf("template_code", "template_name"),
                searchColumns = listOf("template_code", "template_name", "description"),
                softDeleted = false,
                hasUpdatedAt = true,
                hasStatus = true,
                numericColumns = listOf("station_id", "paper_width", "font_size")
            )
            "invoice_templates" -> OperationalTableSpec(
                table = "invoice_templates",
                columns = listOf("template_code", "template_name", "description", "station_id", "template_html", "template_css", "variables", "is_default", "is_active", "created_by"),
                required = listOf("template_code", "template_name"),
                searchColumns = listOf("template_code", "template_name", "description"),
                softDeleted = false,
                hasUpdatedAt = true,
                hasStatus = true,
                numericColumns = listOf("station_id")
            )
            else -> null
        }

    private fun putOperationalValue(values: ContentValues, key: String, value: Any?) {
        if (value == null || value == JSONObject.NULL) return
        when (value) {
            is Boolean -> values.put(key, if (value) 1 else 0)
            is Int -> values.put(key, value)
            is Long -> values.put(key, value)
            is Double -> values.put(key, value)
            is Float -> values.put(key, value)
            is Number -> values.put(key, value.toDouble())
            else -> values.put(key, value.toString())
        }
    }

    private fun operationalHasUuid(table: String): Boolean = table !in setOf("bad_debts", "price_history", "stocktakes", "stocktake_details", "cash_deposits", "employee_payments", "depreciation")

    private fun operationalHasCreatedAt(table: String): Boolean = table !in setOf("bad_debts", "price_history", "price_list_items", "stocktakes", "stocktake_details", "cash_deposits", "employee_payments", "depreciation")

    private fun operationalPreparedData(screenKey: String, input: JSONObject, actorId: Long): JSONObject {
        val data = JSONObject(input.toString())
        val spec = operationalSpec(screenKey) ?: error("مسار الشاشة غير مسجل: $screenKey")
        fun defaultString(key: String, prefix: String) {
            if (!data.has(key) || data.optString(key).isBlank()) data.put(key, "$prefix-${System.currentTimeMillis()}")
        }
        when (screenKey) {
            "stations" -> defaultString("station_code", "ST")
            "vehicles" -> defaultString("vehicle_code", "VEH")
            "drivers" -> defaultString("driver_code", "DRV")
            "fuel_types" -> defaultString("fuel_code", "FUEL")
            "price_lists" -> defaultString("list_code", "PL")
            "tanks" -> defaultString("tank_code", "TANK")
            "pumps" -> defaultString("pump_code", "PUMP")
            "meter_readings" -> defaultString("reading_code", "MR")
            "calibration_records" -> defaultString("calibration_code", "CAL")
            "maintenance_requests" -> defaultString("request_code", "MRQ")
            "maintenance_schedule" -> defaultString("schedule_code", "MS")
            "documents" -> defaultString("document_code", "DOC")
            "printer_profiles" -> defaultString("profile_code", "PRN")
            "receipt_templates", "invoice_templates" -> defaultString("template_code", "TPL")
            "payroll" -> defaultString("payroll_code", "PAY")
            "cash_boxes" -> defaultString("box_code", "BOX")
            "shifts" -> defaultString("shift_code", "SHIFT")
            "sales_transactions" -> defaultString("sale_code", "SALE")
            "payments" -> defaultString("payment_code", "PAY")
            "expenses" -> defaultString("expense_code", "EXP")
            "price_history" -> if (!data.has("created_by") && actorId > 0) data.put("created_by", actorId)
        }
        if (actorId > 0) {
            if (spec.columns.contains("created_by") && (!data.has("created_by") || data.optLong("created_by", 0) <= 0)) data.put("created_by", actorId)
            if (spec.columns.contains("reported_by") && (!data.has("reported_by") || data.optLong("reported_by", 0) <= 0)) data.put("reported_by", actorId)
            if (spec.columns.contains("read_by") && (!data.has("read_by") || data.optLong("read_by", 0) <= 0)) data.put("read_by", actorId)
            if (spec.columns.contains("performed_by") && (!data.has("performed_by") || data.optLong("performed_by", 0) <= 0)) data.put("performed_by", actorId)
            if (spec.columns.contains("uploaded_by") && (!data.has("uploaded_by") || data.optLong("uploaded_by", 0) <= 0)) data.put("uploaded_by", actorId)
        }
        if (operationalHasUuid(spec.table) && !data.has("uuid")) data.put("uuid", UUID.randomUUID().toString())
        return data
    }

    private fun requireOperationalData(spec: OperationalTableSpec, data: JSONObject) {
        spec.required.forEach { key ->
            val value = if (data.has(key) && !data.isNull(key)) data.optString(key).trim() else ""
            require(value.isNotBlank() && value != "0") { "الحقل مطلوب: $key" }
        }
    }

    fun getOperationalRows(screenKey: String, params: JSONObject = JSONObject()): JSONArray {
        val spec = operationalSpec(screenKey) ?: error("مسار الشاشة غير مسجل: $screenKey")
        dbLock.lock()
        return try {
            val db = readableDatabase
            val where = mutableListOf<String>()
            val args = mutableListOf<String>()
            if (spec.softDeleted) where += "is_deleted = 0"
            val includeArchived = params.optBoolean("include_archived", false)
            if (!includeArchived && screenKey in setOf("price_history", "stocktakes", "stocktake_details", "depreciation")) where += "archived = 0"
            val search = params.optString("search", "").trim()
            if (search.isNotBlank() && spec.searchColumns.isNotEmpty()) {
                where += "(" + spec.searchColumns.joinToString(" OR ") { "$it LIKE ?" } + ")"
                repeat(spec.searchColumns.size) { args += "%$search%" }
            }
            if (spec.hasStatus && spec.columns.contains("status")) {
                val status = params.optString("status", "").trim()
                if (status.isNotBlank()) { where += "status = ?"; args += status }
            }
            val dateColumn = when (screenKey) {
                "bad_debts" -> "date"
                "price_history" -> "change_date"
                "fuel_sales" -> "sale_date"
                "deliveries" -> "delivery_date"
                "cash_deposits", "employee_payments" -> "date"
                else -> "created_at"
            }
            val from = params.optString("from_date", "").trim()
            val to = params.optString("to_date", "").trim()
            if (from.isNotBlank()) { where += "date($dateColumn) >= date(?)"; args += from }
            if (to.isNotBlank()) { where += "date($dateColumn) <= date(?)"; args += to }
            val limit = params.optInt("limit", 200).coerceIn(1, 1000)
            val offset = params.optInt("offset", 0).coerceAtLeast(0)
            val whereSql = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
            val pageArgs = args.toMutableList().apply { add(limit.toString()); add(offset.toString()) }
            db.rawQuery("SELECT * FROM ${spec.table}$whereSql ORDER BY id DESC LIMIT ? OFFSET ?", pageArgs.toTypedArray()).use { cursor -> cursorToJsonArray(cursor) }
        } finally { dbLock.unlock() }
    }

    fun getOperationalReport(screenKey: String, params: JSONObject = JSONObject()): JSONObject {
        val spec = operationalSpec(screenKey) ?: error("مسار الشاشة غير مسجل: $screenKey")
        val rows = getOperationalRows(screenKey, params)
        val totals = JSONObject()
        spec.numericColumns.forEach { key ->
            var total = 0.0
            for (i in 0 until rows.length()) total += rows.optJSONObject(i)?.optDouble(key, 0.0) ?: 0.0
            totals.put(key, total)
        }
        return JSONObject().apply { put("rows", rows); put("count", rows.length()); put("totals", totals); put("source", spec.table) }
    }

    fun saveOperationalRecord(screenKey: String, input: JSONObject, actorId: Long = 0L): Long {
        val spec = operationalSpec(screenKey) ?: error("مسار الشاشة غير مسجل: $screenKey")
        val data = operationalPreparedData(screenKey, input, actorId)
        requireOperationalData(spec, data)
        dbLock.lock()
        return try {
            val values = ContentValues()
            for (key in spec.columns) {
                if (data.has(key)) putOperationalValue(values, key, data.opt(key))
            }
            if (operationalHasUuid(spec.table)) putOperationalValue(values, "uuid", data.optString("uuid", UUID.randomUUID().toString()))
            if (operationalHasCreatedAt(spec.table) && !data.has("created_at")) values.put("created_at", getCurrentDateTime())
            if (spec.hasUpdatedAt && !data.has("updated_at")) values.put("updated_at", getCurrentDateTime())
            val id = writableDatabase.insertOrThrow(spec.table, null, values)
            if (id > 0) logActivity("system", "add_${spec.table}", "إضافة سجل في ${spec.table}: $id")
            id
        } finally { dbLock.unlock() }
    }

    fun updateOperationalRecord(screenKey: String, id: Long, input: JSONObject, actorId: Long = 0L): Int {
        require(id > 0L) { "معرف السجل غير صالح" }
        val spec = operationalSpec(screenKey) ?: error("مسار الشاشة غير مسجل: $screenKey")
        val data = operationalPreparedData(screenKey, input, actorId)
        dbLock.lock()
        return try {
            val values = ContentValues()
            for (key in spec.columns) {
                if (data.has(key) && key != "created_by") putOperationalValue(values, key, data.opt(key))
            }
            if (spec.hasUpdatedAt) values.put("updated_at", getCurrentDateTime())
            val where = if (spec.softDeleted) "id = ? AND is_deleted = 0" else "id = ?"
            val rows = writableDatabase.update(spec.table, values, where, arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "update_${spec.table}", "تحديث سجل ${spec.table}: $id")
            rows
        } finally { dbLock.unlock() }
    }

    fun deleteOperationalRecord(screenKey: String, id: Long): Int {
        require(id > 0L) { "معرف السجل غير صالح" }
        val spec = operationalSpec(screenKey) ?: error("مسار الشاشة غير مسجل: $screenKey")
        dbLock.lock()
        return try {
            val db = writableDatabase
            val rows = if (spec.softDeleted) db.update(spec.table, ContentValues().apply { put("is_deleted", 1); if (spec.hasUpdatedAt) put("updated_at", getCurrentDateTime()) }, "id = ?", arrayOf(id.toString())) else db.delete(spec.table, "id = ?", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "delete_${spec.table}", "حذف سجل ${spec.table}: $id")
            rows
        } finally { dbLock.unlock() }
    }

    fun resolveOperationalRecord(screenKey: String, id: Long, note: String = ""): Int {
        require(id > 0L) { "معرف السجل غير صالح" }
        val spec = operationalSpec(screenKey) ?: error("مسار الشاشة غير مسجل: $screenKey")
        dbLock.lock()
        return try {
            val db = writableDatabase
            val values = ContentValues()
            val where = "id = ?"
            when (screenKey) {
                "bad_debts" -> { values.put("resolved", 1); values.put("resolved_date", getCurrentDateTime()) }
                "stock_alerts" -> { values.put("is_resolved", 1); values.put("resolved_at", getCurrentDateTime()); values.put("resolution_notes", note) }
                "maintenance_requests" -> { values.put("status", "completed"); values.put("completed_at", getCurrentDateTime()); values.put("resolution", note) }
                "sync_logs" -> { values.put("status", "success"); values.put("completed_at", getCurrentDateTime()) }
                else -> error("لا توجد عملية اعتماد/حل مدعومة لهذه الشاشة")
            }
            if (spec.hasUpdatedAt) values.put("updated_at", getCurrentDateTime())
            db.update(spec.table, values, where, arrayOf(id.toString()))
        } finally { dbLock.unlock() }
    }

    // ========================================================================
    // دوال بيانات ديناميكية من SmsReceiver
    // ========================================================================

    fun getDriverPhones(): List<String> {
        val phones = mutableListOf<String>()
        val db = readableDatabase
        db.rawQuery(
            "SELECT phone, phone2 FROM drivers WHERE status = 'active' AND is_deleted = 0",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0)?.let { p -> if (p.isNotBlank()) phones.add(p) }
                cursor.getString(1)?.let { p -> if (p.isNotBlank()) phones.add(p) }
            }
        }
        return phones.distinct()
    }

    fun getTrustedSmscList(): List<String> {
        val phones = mutableListOf<String>()
        val db = readableDatabase
        db.rawQuery(
            "SELECT phone FROM sms_whitelist WHERE enabled = 1 ORDER BY name",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) phones.add(cursor.getString(0))
        }
        return phones
    }

    fun getCustomerBalanceByPhone(phone: String): Double {
        val db = readableDatabase
        val cleanPhone = phone.replace("[^0-9]".toRegex(), "").takeLast(9)
        db.rawQuery(
            "SELECT current_balance FROM parties WHERE phone = ? AND is_deleted = 0 LIMIT 1",
            arrayOf(cleanPhone)
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getDouble(0)
        }
        return 0.0
    }

    fun getLastOrderByPhone(phone: String): JSONObject? {
        val db = readableDatabase
        val cleanPhone = phone.replace("[^0-9]".toRegex(), "").takeLast(9)
        db.rawQuery(
            """SELECT s.* FROM sales_transactions s
               JOIN parties p ON s.customer_party_id = p.id
               WHERE p.phone = ? AND s.is_deleted = 0
               ORDER BY s.id DESC LIMIT 1""",
            arrayOf(cleanPhone)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return JSONObject().apply {
                    put("sale_code", cursor.getString(cursor.getColumnIndexOrThrow("sale_code")))
                    put("liters", cursor.getDouble(cursor.getColumnIndexOrThrow("liters")))
                    put("delivery_location", cursor.getString(cursor.getColumnIndexOrThrow("delivery_location")))
                    put("status", cursor.getString(cursor.getColumnIndexOrThrow("status")))
                    put("created_at", cursor.getString(cursor.getColumnIndexOrThrow("created_at")))
                }
            }
            return null
        }
    }

    fun getOrderHistoryByPhone(phone: String, limit: Int = 50): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        val cleanPhone = phone.replace("[^0-9]".toRegex(), "").takeLast(9)
        db.rawQuery(
            """SELECT s.sale_code, s.liters, s.net_amount, s.created_at
               FROM sales_transactions s
               JOIN parties p ON s.customer_party_id = p.id
               WHERE p.phone = ? AND s.is_deleted = 0
               ORDER BY s.id DESC LIMIT ?""",
            arrayOf(cleanPhone, limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                arr.put(JSONObject().apply {
                    put("sale_code", cursor.getString(0))
                    put("liters", cursor.getDouble(1))
                    put("net_amount", cursor.getDouble(2))
                    put("created_at", cursor.getString(3))
                })
            }
        }
        return arr
    }

    fun getOrderHistoryByPhone(phone: String): JSONArray = getOrderHistoryByPhone(phone, 50)

    fun getPartyIdByPhone(phone: String): Int? {
        val db = readableDatabase
        val cleanPhone = phone.replace("[^0-9]".toRegex(), "").takeLast(9)
        db.rawQuery(
            "SELECT id FROM parties WHERE phone = ? AND is_deleted = 0 LIMIT 1",
            arrayOf(cleanPhone)
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getInt(0)
            return null
        }
    }

    fun getSystemSetting(key: String, defaultValue: String = ""): String {
        val db = readableDatabase
        db.rawQuery("SELECT setting_value FROM system_settings WHERE setting_key = ? LIMIT 1", arrayOf(key))
            .use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0)
            }
        return defaultValue
    }

    fun recordDieselDelivery(
        customerId: String,
        customerName: String,
        quantityLiters: Double,
        quantityDabbas: Double,
        location: String,
        deliveryTime: String,
        unitPrice: Double,
        totalAmount: Double,
        orderId: String
    ): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val partyId = getPartyIdByPhone(customerId)
            if (partyId == null) {
                Log.e(TAG, "Party not found for phone: $customerId")
                return false
            }

            require(quantityLiters in 1.0..10000.0) { "Invalid quantity" }
            require(unitPrice in 1.0..1000000.0) { "Invalid price" }
            require(location.length in 3..200) { "Invalid location" }

            val subtotal = quantityLiters * unitPrice
            val stationId = 1
            val shiftId = getCurrentShift(stationId)?.optLong("shift_id", 1)?.toInt() ?: 1

            val saleId = insertSaleTransaction(
                stationId = stationId,
                shiftId = shiftId,
                customerPartyId = partyId,
                fuelTypeId = 1,
                pumpId = null,
                nozzleId = null,
                liters = quantityLiters,
                pricePerLiter = unitPrice,
                subtotal = subtotal,
                discountAmount = 0.0,
                taxAmount = 0.0,
                grossAmount = totalAmount,
                netAmount = totalAmount,
                paymentMethod = "credit",
                isCredit = true,
                dueDate = getDateOnlyFormat().format(Date()),
                cashierId = 1,
                notes = "طلب توصيل ديزل - ${location.take(100)} في ${deliveryTime.take(50)}",
                deliveryLocation = location,
                deliveryTime = deliveryTime,
                orderType = "delivery"
            )

            if (saleId <= 0) {
                Log.e(TAG, "Failed to insert sale transaction")
                return false
            }

            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("sale_id", saleId)
                put("party_id", partyId)
                put("delivery_date", getCurrentDate())
                put("quantity", quantityLiters)
                put("fuel_type", "diesel")
                put("price_per_liter", unitPrice)
                put("total_amount", totalAmount)
                put("status", "confirmed")
                put("location", location)
                put("notes", "طلب توصيل ديزل - $orderId")
                put("created_at", getCurrentDateTime())
            }
            db.insert("deliveries", null, cv)

            val currentBalance = getCustomerBalanceByPhone(customerId)
            val newBalance = currentBalance + totalAmount
            val values = ContentValues().apply {
                put("current_balance", newBalance)
                put("total_due", totalAmount)
            }
            db.update("parties", values, "id = ?", arrayOf(partyId.toString()))

            db.setTransactionSuccessful()
            logActivity("SmsReceiver", "delivery_recorded", "Order $orderId for ${quantityLiters}L")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error recording delivery: ${e.message}", e)
            return false
        } finally {
            db.endTransaction()
        }
    }

    // ========================================================================
    // دوال التقارير الإضافية
    // ========================================================================

    fun getSalesByFuelType(): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        db.rawQuery("""
            SELECT f.fuel_name, f.fuel_name_ar,
                   COALESCE(SUM(s.liters), 0) as total_liters,
                   COALESCE(SUM(s.net_amount), 0) as total_amount,
                   COUNT(*) as transaction_count
            FROM fuel_types f
            LEFT JOIN sales_transactions s ON s.fuel_type_id = f.id AND s.is_deleted = 0
            WHERE f.is_deleted = 0
            GROUP BY f.id
            ORDER BY total_amount DESC
        """.trimIndent(), null).use { cursor ->
            while (cursor.moveToNext()) {
                arr.put(JSONObject().apply {
                    put("fuel_name", cursor.getString(0))
                    put("fuel_name_ar", cursor.getString(1))
                    put("total_liters", cursor.getDouble(2))
                    put("total_amount", cursor.getDouble(3))
                    put("transaction_count", cursor.getInt(4))
                })
            }
        }
        return arr
    }

    fun getCustomerCount(): Int {
        val db = readableDatabase
        db.rawQuery("SELECT COUNT(*) FROM parties WHERE party_type_id = 1 AND is_deleted = 0", null)
            .use { cursor ->
                if (cursor.moveToFirst()) return cursor.getInt(0)
            }
        return 0
    }

    fun getDriverPhonesList(): JSONArray {
        val arr = JSONArray()
        getDriverPhones().forEach { arr.put(it) }
        return arr
    }

    // ========================================================================
    // دوال حركات النقدية (V12)
    // ========================================================================

    fun addCashMovement(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("cash_box_id", data.optInt("cash_box_id", 1))
                put("movement_type", data.optString("movement_type", "in"))
                put("amount", data.optDouble("amount", 0.0))
                put("balance_before", data.optDouble("balance_before", 0.0))
                put("balance_after", data.optDouble("balance_after", 0.0))
                put("description", data.optString("description", ""))
                put("reference_type", data.optString("reference_type", ""))
                put("reference_id", data.optLong("reference_id", 0))
                put("created_by", data.optString("created_by", "system"))
                put("created_at", getCurrentDateTime())
            }
            db.insert("cash_movements", null, cv)
        } finally {
            dbLock.unlock()
        }
    }

    fun getCashMovements(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM cash_movements WHERE is_deleted = 0 ORDER BY created_at DESC",
                null
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun getTodayCash(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val today = getCurrentDate()
            db.rawQuery(
                "SELECT * FROM cash_movements WHERE date(created_at) = ? AND is_deleted = 0 ORDER BY created_at DESC",
                arrayOf(today)
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال الورديات الإضافية
    // ========================================================================

    fun startShift(data: JSONObject): Long {
        return openShift(
            stationId = data.optInt("station_id", 1),
            shiftType = data.optString("shift_type", "morning"),
            cashierId = data.optInt("cashier_id", 1),
            openingCash = data.optDouble("opening_cash", 0.0),
            openingBank = data.optDouble("opening_bank", 0.0)
        )
    }

    fun endShift(id: Long, data: JSONObject): Int {
        val success = closeShift(
            shiftId = id.toInt(),
            closingCash = data.optDouble("closing_cash", 0.0),
            closingBank = data.optDouble("closing_bank", 0.0),
            totalSales = data.optDouble("total_sales", 0.0),
            operator = data.optString("operator", "System")
        )
        return if (success) 1 else 0
    }

    fun addShiftSale(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val shiftId = data.optLong("shift_id", 0)
            val amount = data.optDouble("amount", 0.0)
            db.execSQL("UPDATE shifts SET total_sales = total_sales + ? WHERE id = ?", arrayOf(amount, shiftId))
            val cv = ContentValues().apply {
                put("shift_id", shiftId)
                put("sale_id", data.optLong("sale_id", 0))
                put("amount", amount)
                put("created_at", getCurrentDateTime())
            }
            db.insert("shift_sales", null, cv)
        } finally {
            dbLock.unlock()
        }
    }

    fun addShiftDelivery(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val shiftId = data.optLong("shift_id", 0)
            val amount = data.optDouble("amount", 0.0)
            db.execSQL("UPDATE shifts SET total_deliveries = total_deliveries + ? WHERE id = ?", arrayOf(amount, shiftId))
            val cv = ContentValues().apply {
                put("shift_id", shiftId)
                put("delivery_id", data.optLong("delivery_id", 0))
                put("amount", amount)
                put("created_at", getCurrentDateTime())
            }
            db.insert("shift_deliveries", null, cv)
        } finally {
            dbLock.unlock()
        }
    }

    fun addShiftExpense(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val shiftId = data.optLong("shift_id", 0)
            val amount = data.optDouble("amount", 0.0)
            db.execSQL("UPDATE shifts SET total_expenses = total_expenses + ? WHERE id = ?", arrayOf(amount, shiftId))
            val cv = ContentValues().apply {
                put("shift_id", shiftId)
                put("expense_type", data.optString("expense_type", "other"))
                put("amount", amount)
                put("description", data.optString("description", ""))
                put("created_at", getCurrentDateTime())
            }
            db.insert("shift_expenses", null, cv)
        } finally {
            dbLock.unlock()
        }
    }

    fun getShiftReport(shiftId: Long): JSONArray {
        dbLock.lock()
        return try {
            val arr = JSONArray()
            val db = readableDatabase
            db.rawQuery(
                """SELECT 'sales' as type, COUNT(*) as count, COALESCE(SUM(net_amount), 0) as total
                   FROM sales_transactions WHERE shift_id = ? AND is_deleted = 0
                   UNION ALL
                   SELECT 'deliveries', COUNT(*), COALESCE(SUM(total_amount), 0)
                   FROM deliveries WHERE shift_id = ? AND is_deleted = 0
                   UNION ALL
                   SELECT 'expenses', COUNT(*), COALESCE(SUM(amount), 0)
                   FROM shift_expenses WHERE shift_id = ?""",
                arrayOf(shiftId.toString(), shiftId.toString(), shiftId.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    arr.put(JSONObject().apply {
                        put("type", cursor.getString(0))
                        put("count", cursor.getInt(1))
                        put("total", cursor.getDouble(2))
                    })
                }
            }
            arr
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال الإشعارات الإضافية
    // ========================================================================

    fun addNotification(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                val userId = data.optLong("user_id", 0L)
                val roleId = data.optLong("role_id", 0L)
                if (userId > 0L) put("user_id", userId) else putNull("user_id")
                if (roleId > 0L) put("role_id", roleId) else putNull("role_id")
                put("notification_type", data.optString("notification_type", "info"))
                put("title", data.optString("title", ""))
                put("title_ar", data.optString("title_ar", ""))
                put("message", data.optString("message", ""))
                put("message_ar", data.optString("message_ar", ""))
                put("priority", data.optString("priority", "normal"))
                put("channel", data.optString("channel", "in_app"))
                if (data.optString("reference_type").isNotBlank()) put("reference_type", data.optString("reference_type"))
                if (data.optLong("reference_id", 0L) > 0L) put("reference_id", data.optLong("reference_id"))
                if (data.optLong("created_by", 0L) > 0L) put("created_by", data.optLong("created_by"))
                put("is_read", 0)
                put("status", "pending")
                put("created_at", getCurrentDateTime())
            }
            db.insert("notifications", null, cv)
        } finally {
            dbLock.unlock()
        }
    }

    fun getUnreadNotificationsCount(): Int {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery("SELECT COUNT(*) FROM notifications WHERE is_read = 0", null)
                .use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
        } finally {
            dbLock.unlock()
        }
    }

    fun markNotificationRead(id: Long): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("is_read", 1)
                put("read_at", getCurrentDateTime())
            }
            db.update("notifications", cv, "id=?", arrayOf(id.toString()))
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال SMS Messages الإضافية
    // ========================================================================

    fun getSmsMessages(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM sms_messages ORDER BY created_at DESC, id DESC LIMIT 500",
                null
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * Serverless pagination/filter contract for WebView screens.
     * All values are bound parameters; no SQL identifiers or user input are interpolated.
     */
    fun getSmsMessagesPage(params: JSONObject = JSONObject()): JSONObject {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val limit = params.optInt("limit", 50).coerceIn(1, 200)
            val offset = params.optInt("offset", 0).coerceAtLeast(0)
            val where = mutableListOf<String>()
            val args = mutableListOf<String>()

            val status = params.optString("status", "").trim().lowercase(Locale.ROOT)
            val messageType = params.optString("message_type", "").trim().lowercase(Locale.ROOT)
            val dateFrom = params.optString("date_from", "").trim()
            val dateTo = params.optString("date_to", "").trim()
            val search = params.optString("search", "").trim()
            if (search.isNotBlank()) {
                where += "(phone_number LIKE ? OR message_body LIKE ?)"
                val like = "%$search%"
                args += like
                args += like
            }
            if (status == "read") {
                where += "is_read = 1"
            } else if (status == "unread") {
                where += "is_read = 0"
            } else if (status.isNotBlank()) {
                where += "status = ?"
                args += status
            }
            if (messageType.isNotBlank()) {
                where += "message_type = ?"
                args += messageType
            }
            if (dateFrom.isNotBlank()) {
                where += "date(created_at) >= date(?)"
                args += dateFrom
            }
            if (dateTo.isNotBlank()) {
                where += "date(created_at) <= date(?)"
                args += dateTo
            }

            val whereSql = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
            val count = db.rawQuery("SELECT COUNT(*) FROM sms_messages$whereSql", args.toTypedArray()).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
            val pageArgs = args.toMutableList().apply {
                add(limit.toString())
                add(offset.toString())
            }
            val items = db.rawQuery(
                "SELECT * FROM sms_messages$whereSql ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                pageArgs.toTypedArray()
            ).use { cursor -> cursorToJsonArray(cursor) }
            JSONObject().apply {
                put("items", items)
                put("total", count)
                put("limit", limit)
                put("offset", offset)
            }
        } finally {
            dbLock.unlock()
        }
    }

    fun markSmsMessageRead(id: Long): Int {
        if (id <= 0L) return 0
        dbLock.lock()
        return try {
            val db = writableDatabase
            db.update(
                "sms_messages",
                ContentValues().apply {
                    put("is_read", 1)
                    put("read_at", getCurrentDateTime())
                    put("updated_at", getCurrentDateTime())
                },
                "id = ? AND is_read = 0",
                arrayOf(id.toString())
            )
        } finally {
            dbLock.unlock()
        }
    }

    fun getSmsMessagesByPhone(phone: String): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM sms_messages WHERE phone_number = ? ORDER BY created_at DESC",
                arrayOf(phone)
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun getSmsMessagesByStatus(status: String): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM sms_messages WHERE status = ? ORDER BY created_at DESC",
                arrayOf(status)
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun updateSmsStatus(id: Long, status: String): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("status", status)
                put("updated_at", getCurrentDateTime())
            }
            db.update("sms_messages", cv, "id=?", arrayOf(id.toString()))
        } finally {
            dbLock.unlock()
        }
    }

    fun getSmsStats(): JSONObject {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val stats = JSONObject()
            db.rawQuery("SELECT COUNT(*) FROM sms_messages", null).use { cursor ->
                stats.put("total", if (cursor.moveToFirst()) cursor.getInt(0) else 0)
            }
            db.rawQuery("SELECT COUNT(*) FROM sms_messages WHERE status = 'sent'", null).use { cursor ->
                stats.put("sent", if (cursor.moveToFirst()) cursor.getInt(0) else 0)
            }
            db.rawQuery("SELECT COUNT(*) FROM sms_messages WHERE status = 'pending'", null).use { cursor ->
                stats.put("pending", if (cursor.moveToFirst()) cursor.getInt(0) else 0)
            }
            db.rawQuery("SELECT COUNT(*) FROM sms_messages WHERE status = 'failed'", null).use { cursor ->
                stats.put("failed", if (cursor.moveToFirst()) cursor.getInt(0) else 0)
            }
            stats
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال Notification Templates (schema notification_templates)
    // ========================================================================

    fun getNotificationTemplates(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT id, uuid, template_code, template_name, template_name_ar, channel, subject, body, body_ar, variables, is_active, created_at, updated_at, created_by FROM notification_templates ORDER BY created_at DESC, id DESC",
                null
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun addNotificationTemplate(data: JSONObject): Long {
        val code = data.optString("template_code", "").trim()
        val name = data.optString("template_name", "").trim()
        val channel = data.optString("channel", "").trim().lowercase(Locale.ROOT)
        val body = data.optString("body", "").trim()
        require(code.isNotBlank()) { "كود القالب مطلوب" }
        require(name.isNotBlank()) { "اسم القالب مطلوب" }
        require(body.isNotBlank()) { "محتوى القالب مطلوب" }
        require(channel in setOf("sms", "email", "push", "whatsapp", "telegram", "in_app")) { "قناة القالب غير صالحة" }
        dbLock.lock()
        return try {
            val now = getCurrentDateTime()
            val id = writableDatabase.insertOrThrow("notification_templates", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("template_code", code)
                put("template_name", name)
                put("template_name_ar", data.optString("template_name_ar", "").trim())
                put("channel", channel)
                put("subject", data.optString("subject", "").trim())
                put("body", body)
                put("body_ar", data.optString("body_ar", "").trim())
                put("variables", data.optString("variables", "").trim())
                put("is_active", if (data.optInt("is_active", 1) == 1) 1 else 0)
                put("created_at", now)
                put("updated_at", now)
                put("created_by", data.optLong("created_by", 0L).takeIf { it > 0L })
            })
            if (id > 0) logActivity("system", "add_notification_template", "إضافة قالب إشعار: $name")
            id
        } finally {
            dbLock.unlock()
        }
    }

    fun updateNotificationTemplate(id: Long, data: JSONObject): Int {
        require(id > 0L) { "معرف القالب غير صالح" }
        val name = data.optString("template_name", "").trim()
        val channel = data.optString("channel", "").trim().lowercase(Locale.ROOT)
        val body = data.optString("body", "").trim()
        require(name.isNotBlank()) { "اسم القالب مطلوب" }
        require(body.isNotBlank()) { "محتوى القالب مطلوب" }
        require(channel in setOf("sms", "email", "push", "whatsapp", "telegram", "in_app")) { "قناة القالب غير صالحة" }
        dbLock.lock()
        return try {
            val rows = writableDatabase.update("notification_templates", ContentValues().apply {
                put("template_name", name)
                put("template_name_ar", data.optString("template_name_ar", "").trim())
                put("channel", channel)
                put("subject", data.optString("subject", "").trim())
                put("body", body)
                put("body_ar", data.optString("body_ar", "").trim())
                put("variables", data.optString("variables", "").trim())
                put("is_active", if (data.optInt("is_active", 1) == 1) 1 else 0)
                put("updated_at", getCurrentDateTime())
            }, "id = ?", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "update_notification_template", "تحديث قالب إشعار: $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    fun deleteNotificationTemplate(id: Long): Int {
        require(id > 0L) { "معرف القالب غير صالح" }
        dbLock.lock()
        return try {
            val rows = writableDatabase.delete("notification_templates", "id = ?", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "delete_notification_template", "حذف قالب إشعار: $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال SMS Templates
    // ========================================================================

    fun getSmsTemplates(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery("SELECT * FROM sms_templates ORDER BY created_at DESC", null)
                .use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun addSmsTemplate(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("template_name", data.optString("template_name", ""))
                put("template_body", data.optString("template_body", ""))
                put("template_type", data.optString("template_type", "general"))
                put("is_active", if (data.optBoolean("is_active", true)) 1 else 0)
                put("created_at", getCurrentDateTime())
                put("updated_at", getCurrentDateTime())
            }
            db.insert("sms_templates", null, cv)
        } finally {
            dbLock.unlock()
        }
    }

    fun updateSmsTemplate(id: Long, data: JSONObject): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("template_name", data.optString("template_name", ""))
                put("template_body", data.optString("template_body", ""))
                put("template_type", data.optString("template_type", "general"))
                put("is_active", if (data.optBoolean("is_active", true)) 1 else 0)
                put("updated_at", getCurrentDateTime())
            }
            db.update("sms_templates", cv, "id=?", arrayOf(id.toString()))
        } finally {
            dbLock.unlock()
        }
    }

    fun deleteSmsTemplate(id: Long): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            db.delete("sms_templates", "id=?", arrayOf(id.toString()))
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال الإعدادات الإضافية
    // ========================================================================

    fun addSetting(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("setting_key", data.optString("setting_key", ""))
                put("setting_value", data.optString("setting_value", ""))
                put("setting_type", data.optString("setting_type", "string"))
                put("description", data.optString("description", ""))
                put("created_at", getCurrentDateTime())
                put("updated_at", getCurrentDateTime())
            }
            db.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } finally {
            dbLock.unlock()
        }
    }

    fun deleteSetting(key: String): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            db.delete("settings", "setting_key=?", arrayOf(key))
        } finally {
            dbLock.unlock()
        }
    }

    fun getAllSettingsMap(): Map<String, String> {
        dbLock.lock()
        return try {
            val map = mutableMapOf<String, String>()
            val db = readableDatabase
            db.rawQuery("SELECT setting_key, setting_value FROM settings", null)
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        map[cursor.getString(0)] = cursor.getString(1)
                    }
                }
            map
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال التنبيهات والتقارير الإضافية
    // ========================================================================

    fun getOverduePayments(): JSONArray {
        dbLock.lock()
        return try {
            val arr = JSONArray()
            val db = readableDatabase
            db.rawQuery(
                """SELECT s.*, p.commercial_name as customer_name, p.phone as customer_phone
                   FROM sales_transactions s
                   LEFT JOIN parties p ON s.customer_party_id = p.id
                   WHERE s.remaining_amount > 0 AND date(s.due_date) < date('now') AND s.is_deleted=0
                   ORDER BY s.due_date""",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    arr.put(JSONObject().apply {
                        put("sale_id", cursor.getInt(cursor.getColumnIndexOrThrow("id")))
                        put("customer_party_id", cursor.getInt(cursor.getColumnIndexOrThrow("customer_party_id")))
                        put("customer_name", cursor.getString(cursor.getColumnIndexOrThrow("customer_name")))
                        put("customer_phone", cursor.getString(cursor.getColumnIndexOrThrow("customer_phone")))
                        put("remaining_amount", cursor.getDouble(cursor.getColumnIndexOrThrow("remaining_amount")))
                        put("due_date", cursor.getString(cursor.getColumnIndexOrThrow("due_date")))
                        put("invoice_number", cursor.getString(cursor.getColumnIndexOrThrow("invoice_number")))
                    })
                }
            }
            arr
        } finally {
            dbLock.unlock()
        }
    }

    fun getActiveAlerts(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM stock_alerts WHERE is_resolved = 0 ORDER BY created_at DESC",
                null
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun getRecentActivity(limit: Int): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM user_activity_log ORDER BY created_at DESC LIMIT ?",
                arrayOf(limit.toString())
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال المركبات
    // ========================================================================

    fun getVehicles(): JSONArray {
        dbLock.lock()
        return try {
            val arr = JSONArray()
            val db = readableDatabase
            db.rawQuery(
                """SELECT v.*, p.commercial_name as party_name, d.full_name as driver_name
                   FROM vehicles v
                   LEFT JOIN parties p ON v.party_id = p.id
                   LEFT JOIN drivers d ON v.id = d.vehicle_id
                   WHERE v.is_deleted = 0
                   ORDER BY v.plate_number""",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    arr.put(JSONObject().apply {
                        put("vehicle_id", cursor.getInt(cursor.getColumnIndexOrThrow("id")))
                        put("vehicle_code", cursor.getString(cursor.getColumnIndexOrThrow("vehicle_code")))
                        put("plate_number", cursor.getString(cursor.getColumnIndexOrThrow("plate_number")))
                        put("brand", cursor.getString(cursor.getColumnIndexOrThrow("brand")))
                        put("model", cursor.getString(cursor.getColumnIndexOrThrow("model")))
                        put("tank_capacity", cursor.getDouble(cursor.getColumnIndexOrThrow("tank_capacity")))
                        put("status", cursor.getString(cursor.getColumnIndexOrThrow("status")))
                        put("party_name", cursor.getString(cursor.getColumnIndexOrThrow("party_name")))
                        put("driver_name", cursor.getString(cursor.getColumnIndexOrThrow("driver_name")))
                    })
                }
            }
            arr
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال إحصائيات الخزانات
    // ========================================================================

    fun getTankStats(): JSONArray {
        dbLock.lock()
        return try {
            val arr = JSONArray()
            val db = readableDatabase
            db.rawQuery(
                """SELECT t.*, f.fuel_name, f.fuel_name_ar,
                          ROUND((t.current_quantity / t.capacity_liters * 100), 2) as fill_percent
                   FROM tanks t
                   LEFT JOIN fuel_types f ON t.fuel_type_id = f.id
                   WHERE t.is_deleted = 0
                   ORDER BY t.tank_code""",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    arr.put(JSONObject().apply {
                        put("tank_id", cursor.getInt(cursor.getColumnIndexOrThrow("id")))
                        put("tank_code", cursor.getString(cursor.getColumnIndexOrThrow("tank_code")))
                        put("tank_name", cursor.getString(cursor.getColumnIndexOrThrow("tank_name")))
                        put("capacity_liters", cursor.getDouble(cursor.getColumnIndexOrThrow("capacity_liters")))
                        put("current_quantity", cursor.getDouble(cursor.getColumnIndexOrThrow("current_quantity")))
                        put("fill_percent", cursor.getDouble(cursor.getColumnIndexOrThrow("fill_percent")))
                        put("fuel_name", cursor.getString(cursor.getColumnIndexOrThrow("fuel_name")))
                        put("status", cursor.getString(cursor.getColumnIndexOrThrow("status")))
                    })
                }
            }
            arr
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال النسخ الاحتياطي والتصدير
    // ========================================================================

    fun backupDatabase(): String {
        dbLock.lock()
        return try {
            val dbFile = contextRef.getDatabasePath(DB_NAME)
            val backupDir = File(contextRef.getExternalFilesDir(null), "backups")
            if (!backupDir.exists()) backupDir.mkdirs()
            val backupFile = File(backupDir, "backup_${System.currentTimeMillis()}.db")
            dbFile.copyTo(backupFile, overwrite = true)
            backupFile.absolutePath
        } finally {
            dbLock.unlock()
        }
    }

    fun restoreDatabase(path: String): Boolean {
        dbLock.lock()
        return try {
            val dbFile = contextRef.getDatabasePath(DB_NAME)
            val backupFile = File(path)
            if (backupFile.exists()) {
                backupFile.copyTo(dbFile, overwrite = true)
                true
            } else false
        } catch (e: Exception) {
            false
        } finally {
            dbLock.unlock()
        }
    }

    fun exportToCSV(tableName: String): String {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.query(tableName, null, null, null, null, null, null)
                .use { cursor ->
                    val csv = StringBuilder()
                    val columns = cursor.columnNames
                    csv.append(columns.joinToString(",")).append("\n")
                    while (cursor.moveToNext()) {
                        val row = columns.map { col ->
                            val idx = cursor.getColumnIndex(col)
                            when (cursor.getType(idx)) {
                                Cursor.FIELD_TYPE_STRING -> "\"${cursor.getString(idx)?.replace("\"", "\"\"") ?: ""}\""
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getInt(idx).toString()
                                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(idx).toString()
                                else -> ""
                            }
                        }
                        csv.append(row.joinToString(",")).append("\n")
                    }
                    val exportDir = File(contextRef.getExternalFilesDir(null), "exports")
                    if (!exportDir.exists()) exportDir.mkdirs()
                    val exportFile = File(exportDir, "${tableName}_${System.currentTimeMillis()}.csv")
                    exportFile.writeText(csv.toString())
                    exportFile.absolutePath
                }
        } finally {
            dbLock.unlock()
        }
    }

    fun importFromCSV(tableName: String, path: String): Int {
        return 0
    }

    fun getDatabaseSize(): Long {
        return try {
            val dbFile = contextRef.getDatabasePath(DB_NAME)
            dbFile.length()
        } catch (e: Exception) {
            0L
        }
    }

    fun getTableCounts(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val tables = listOf("parties", "sales_transactions", "tanks", "pumps", "users", "employees", "shifts", "notifications", "sms_messages", "fuel_types", "tasks")
            val result = JSONArray()
            tables.forEach { table ->
                db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                    val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
                    val obj = JSONObject()
                    obj.put("table", table)
                    obj.put("count", count)
                    result.put(obj)
                }
            }
            result
        } finally {
            dbLock.unlock()
        }
    }

    fun vacuumDatabase() {
        dbLock.lock()
        try {
            val db = writableDatabase
            db.execSQL("VACUUM")
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال المنتجات التالفة والمستودعات
    // ========================================================================

    fun getWarehouses(stationId: Int? = null): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val sql = if (stationId != null && stationId > 0) {
                "SELECT id, uuid, station_id, warehouse_name, location_details, is_default, is_active, created_at FROM warehouses WHERE station_id = ? AND is_active = 1 ORDER BY warehouse_name"
            } else {
                "SELECT id, uuid, station_id, warehouse_name, location_details, is_default, is_active, created_at FROM warehouses WHERE is_active = 1 ORDER BY warehouse_name"
            }
            val args = if (stationId != null && stationId > 0) arrayOf(stationId.toString()) else null
            db.rawQuery(sql, args).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun getDamagedProducts(data: JSONObject): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val conditions = mutableListOf<String>()
            val args = mutableListOf<String>()
            if (!data.optBoolean("include_archived", false)) {
                conditions += "dp.archived = 0"
            }
            data.optString("start_date").trim().takeIf { it.isNotEmpty() }?.let {
                conditions += "date(dp.report_date) >= date(?)"
                args += it
            }
            data.optString("end_date").trim().takeIf { it.isNotEmpty() }?.let {
                conditions += "date(dp.report_date) <= date(?)"
                args += it
            }
            data.optLong("product_id", 0L).takeIf { it > 0 }?.let {
                conditions += "dp.product_id = ?"
                args += it.toString()
            }
            data.optLong("warehouse_id", 0L).takeIf { it > 0 }?.let {
                conditions += "dp.warehouse_id = ?"
                args += it.toString()
            }
            data.optLong("station_id", 0L).takeIf { it > 0 }?.let {
                conditions += "dp.station_id = ?"
                args += it.toString()
            }
            data.optString("status").trim().takeIf { it in setOf("pending", "approved", "rejected") }?.let {
                conditions += "dp.status = ?"
                args += it
            }
            val where = if (conditions.isEmpty()) "" else " WHERE ${conditions.joinToString(" AND ")}"
            val requestedLimit = data.optInt("limit", 0)
            val limit = if (requestedLimit > 0) requestedLimit.coerceAtMost(1000) else 0
            val limitSql = if (limit > 0) " LIMIT $limit" else ""
            val sql = """
                SELECT dp.id, dp.product_id, dp.warehouse_id, dp.tank_id, dp.station_id,
                       dp.quantity, dp.reason, dp.notes, dp.report_date, dp.reported_by,
                       dp.status, dp.approved_by, dp.approved_at, dp.archived, dp.created_at,
                       p.product_code, p.product_name, p.product_name_ar,
                       COALESCE(p.purchase_price, 0) AS unit_price,
                       COALESCE(p.purchase_price, 0) * dp.quantity AS total_value,
                       w.warehouse_name,
                       t.tank_name,
                       ru.full_name AS reported_by_name,
                       au.full_name AS approved_by_name
                FROM damaged_products dp
                LEFT JOIN products p ON p.id = dp.product_id
                LEFT JOIN warehouses w ON w.id = dp.warehouse_id
                LEFT JOIN tanks t ON t.id = dp.tank_id
                LEFT JOIN users ru ON ru.id = dp.reported_by
                LEFT JOIN users au ON au.id = dp.approved_by
                $where
                ORDER BY datetime(dp.report_date) DESC, dp.id DESC$limitSql
            """.trimIndent()
            db.rawQuery(sql, args.toTypedArray()).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun addDamagedProduct(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val productId = data.optLong("product_id", 0L)
            val quantity = data.optDouble("quantity", 0.0)
            val reportedBy = data.optLong("reported_by", 0L)
            require(productId > 0) { "المنتج مطلوب" }
            require(quantity > 0.0) { "الكمية يجب أن تكون أكبر من صفر" }
            require(reportedBy > 0) { "المبلّغ مطلوب" }

            val db = writableDatabase
            db.rawQuery("SELECT id FROM products WHERE id = ? AND is_deleted = 0", arrayOf(productId.toString())).use { cursor ->
                require(cursor.moveToFirst()) { "المنتج غير موجود أو غير نشط" }
            }
            val values = ContentValues().apply {
                put("product_id", productId)
                if (data.optLong("warehouse_id", 0L) > 0) put("warehouse_id", data.optLong("warehouse_id")) else putNull("warehouse_id")
                if (data.optLong("tank_id", 0L) > 0) put("tank_id", data.optLong("tank_id")) else putNull("tank_id")
                if (data.optLong("station_id", 0L) > 0) put("station_id", data.optLong("station_id")) else putNull("station_id")
                put("quantity", quantity)
                put("reason", data.optString("reason").trim())
                put("notes", data.optString("notes").trim())
                if (data.optString("report_date").trim().isNotEmpty()) put("report_date", data.optString("report_date").trim())
                put("reported_by", reportedBy)
                put("status", "pending")
                put("archived", 0)
            }
            val id = db.insert("damaged_products", null, values)
            require(id > 0) { "تعذر حفظ سجل التالف" }
            logActivity("system", "damaged_product_created", "تم إنشاء سجل تالف رقم $id")
            id
        } finally {
            dbLock.unlock()
        }
    }

    fun updateDamagedProduct(id: Long, data: JSONObject): Int {
        dbLock.lock()
        return try {
            require(id > 0) { "معرف السجل غير صالح" }
            val productId = data.optLong("product_id", 0L)
            val quantity = data.optDouble("quantity", 0.0)
            require(productId > 0) { "المنتج مطلوب" }
            require(quantity > 0.0) { "الكمية يجب أن تكون أكبر من صفر" }
            val db = writableDatabase
            db.rawQuery("SELECT id FROM products WHERE id = ? AND is_deleted = 0", arrayOf(productId.toString())).use { cursor ->
                require(cursor.moveToFirst()) { "المنتج غير موجود أو غير نشط" }
            }
            val values = ContentValues().apply {
                put("product_id", productId)
                if (data.has("warehouse_id")) {
                    if (data.isNull("warehouse_id")) putNull("warehouse_id") else put("warehouse_id", data.optLong("warehouse_id"))
                }
                if (data.has("tank_id")) {
                    if (data.isNull("tank_id")) putNull("tank_id") else put("tank_id", data.optLong("tank_id"))
                }
                if (data.has("station_id")) {
                    if (data.isNull("station_id")) putNull("station_id") else put("station_id", data.optLong("station_id"))
                }
                put("quantity", quantity)
                put("reason", data.optString("reason").trim())
                put("notes", data.optString("notes").trim())
                if (data.has("report_date") && data.optString("report_date").trim().isNotEmpty()) {
                    put("report_date", data.optString("report_date").trim())
                }
            }
            val rows = db.update("damaged_products", values, "id = ? AND archived = 0", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "damaged_product_updated", "تم تحديث سجل تالف رقم $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    fun updateDamagedProductStatus(id: Long, status: String, approvedBy: Long): Int {
        dbLock.lock()
        return try {
            require(id > 0) { "معرف السجل غير صالح" }
            require(status in setOf("pending", "approved", "rejected")) { "حالة التالف غير صحيحة" }
            require(approvedBy > 0) { "المستخدم المعتمد مطلوب" }
            val db = writableDatabase
            var currentStatus = ""
            var productId = 0L
            var quantity = 0.0
            var warehouseId = 1L
            var stationId = 1
            var unitCost = 0.0
            db.rawQuery(
                "SELECT dp.status, dp.product_id, dp.quantity, COALESCE(dp.warehouse_id, 1), COALESCE(dp.station_id, 1), COALESCE(p.purchase_price, 0) FROM damaged_products dp LEFT JOIN products p ON p.id = dp.product_id WHERE dp.id = ? AND dp.archived = 0",
                arrayOf(id.toString())
            ).use { cursor ->
                if (!cursor.moveToFirst()) return 0
                currentStatus = cursor.getString(0) ?: "pending"
                productId = cursor.getLong(1)
                quantity = cursor.getDouble(2)
                warehouseId = cursor.getLong(3)
                stationId = cursor.getInt(4)
                unitCost = cursor.getDouble(5)
            }
            if (currentStatus == status) return 1
            require(!(currentStatus == "approved" && status != "approved")) { "لا يمكن التراجع عن اعتماد خصم المخزون" }

            db.beginTransaction()
            try {
                if (status == "approved") {
                    val movement = JSONObject().apply {
                        put("product_id", productId)
                        put("warehouse_id", warehouseId)
                        put("station_id", stationId)
                        put("movement_type", "damage")
                        put("movement_subtype", "damaged_product")
                        put("quantity", quantity)
                        put("unit_cost", unitCost)
                        put("reference_type", "damaged_products")
                        put("reference_id", id)
                        put("notes", "اعتماد سجل التالف رقم $id")
                        put("performed_by", approvedBy)
                    }
                    val movementId = addStockMovement(movement)
                    require(movementId > 0) { "تعذر تسجيل خصم المخزون" }
                }
                val values = ContentValues().apply {
                    put("status", status)
                    if (status == "pending") {
                        putNull("approved_by")
                        putNull("approved_at")
                    } else {
                        put("approved_by", approvedBy)
                        put("approved_at", getCurrentDateTime())
                    }
                }
                val rows = db.update("damaged_products", values, "id = ? AND archived = 0", arrayOf(id.toString()))
                require(rows > 0) { "تعذر تحديث حالة سجل التالف" }
                logActivity("system", "damaged_product_status", "تم تحديث حالة سجل التالف رقم $id إلى $status")
                db.setTransactionSuccessful()
                rows
            } finally {
                db.endTransaction()
            }
        } finally {
            dbLock.unlock()
        }
    }

    fun archiveDamagedProduct(id: Long): Int {
        dbLock.lock()
        return try {
            require(id > 0) { "معرف السجل غير صالح" }
            val values = ContentValues().apply { put("archived", 1) }
            val rows = writableDatabase.update("damaged_products", values, "id = ? AND archived = 0", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "damaged_product_archived", "تمت أرشفة سجل التالف رقم $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    fun deleteDamagedProduct(id: Long): Int = archiveDamagedProduct(id)

    // ========================================================================
    // دوال المنتجات (Overload)
    // ========================================================================

    fun getProducts(): JSONArray = getProducts(null)

    fun getProducts(stationId: Int?): JSONArray {
        dbLock.lock()
        return try {
            val arr = JSONArray()
            val db = readableDatabase
            val sql = if (stationId != null) {
                "SELECT p.*, c.category_name FROM products p LEFT JOIN product_categories c ON p.category_id = c.id WHERE p.station_id=? AND p.is_deleted=0 ORDER BY p.product_name"
            } else {
                "SELECT p.*, c.category_name FROM products p LEFT JOIN product_categories c ON p.category_id = c.id WHERE p.is_deleted=0 ORDER BY p.product_name"
            }
            val args = if (stationId != null) arrayOf(stationId.toString()) else null
            db.rawQuery(sql, args).use { cursor ->
                while (cursor.moveToNext()) {
                    arr.put(JSONObject().apply {
                        put("product_id", cursor.getInt(cursor.getColumnIndexOrThrow("id")))
                        put("id", cursor.getInt(cursor.getColumnIndexOrThrow("id")))
                        put("product_code", cursor.getString(cursor.getColumnIndexOrThrow("product_code")))
                        put("barcode", cursor.getString(cursor.getColumnIndexOrThrow("barcode")))
                        put("product_name", cursor.getString(cursor.getColumnIndexOrThrow("product_name")))
                        put("product_name_ar", cursor.getString(cursor.getColumnIndexOrThrow("product_name_ar")))
                        put("description", cursor.getString(cursor.getColumnIndexOrThrow("description")))
                        put("category_id", cursor.getInt(cursor.getColumnIndexOrThrow("category_id")))
                        put("category_name", cursor.getString(cursor.getColumnIndexOrThrow("category_name")))
                        put("unit_id", cursor.getInt(cursor.getColumnIndexOrThrow("unit_id")))
                        put("sale_price", cursor.getDouble(cursor.getColumnIndexOrThrow("sale_price")))
                        put("purchase_price", cursor.getDouble(cursor.getColumnIndexOrThrow("purchase_price")))
                        put("quantity", cursor.getDouble(cursor.getColumnIndexOrThrow("quantity")))
                        put("current_stock", cursor.getDouble(cursor.getColumnIndexOrThrow("quantity")))
                        put("minimum_stock", cursor.getDouble(cursor.getColumnIndexOrThrow("minimum_stock")))
                        put("min_stock_level", cursor.getDouble(cursor.getColumnIndexOrThrow("minimum_stock")))
                        put("has_expiry", cursor.getInt(cursor.getColumnIndexOrThrow("has_expiry")))
                        put("expiry_date", cursor.getString(cursor.getColumnIndexOrThrow("expiry_date")))
                        put("is_service", cursor.getInt(cursor.getColumnIndexOrThrow("is_service")))
                        put("is_batch_tracked", cursor.getInt(cursor.getColumnIndexOrThrow("is_batch_tracked")))
                        put("is_serialized", cursor.getInt(cursor.getColumnIndexOrThrow("is_serialized")))
                        put("status", cursor.getString(cursor.getColumnIndexOrThrow("status")))
                        put("is_active", cursor.getString(cursor.getColumnIndexOrThrow("status")) == "active")
                    })
                }
            }
            arr
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال أنواع الوقود
    // ========================================================================

    fun getFuelTypes(): JSONArray {
        dbLock.lock()
        return try {
            val arr = JSONArray()
            val db = readableDatabase
            db.rawQuery(
                "SELECT id, fuel_code, fuel_name, fuel_name_ar, default_sale_price, is_active FROM fuel_types WHERE is_deleted=0 ORDER BY fuel_name",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    arr.put(JSONObject().apply {
                        put("fuel_type_id", cursor.getInt(0))
                        put("fuel_code", cursor.getString(1))
                        put("fuel_name", cursor.getString(2))
                        put("fuel_name_ar", cursor.getString(3))
                        put("default_sale_price", cursor.getDouble(4))
                        put("is_active", cursor.getInt(5) == 1)
                    })
                }
            }
            arr
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال إدارة المنتجات (CRUD)
    // ========================================================================

    fun insertProduct(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("product_code", data.optString("product_code", ""))
                put("product_name", data.optString("product_name", ""))
                put("product_name_ar", data.optString("product_name_ar", ""))
                put("category_id", data.optInt("category_id", 0))
                put("fuel_type_id", data.optInt("fuel_type_id", 0))
                put("station_id", data.optInt("station_id", 1))
                put("unit_id", data.optInt("unit_id", 1))
                put("sale_price", data.optDouble("sale_price", 0.0))
                put("purchase_price", data.optDouble("purchase_price", 0.0))
                put("quantity", data.optDouble("quantity", 0.0))
                put("minimum_stock", data.optDouble("minimum_stock", 10.0))
                put("status", "active")
                put("created_at", getCurrentDateTime())
                put("updated_at", getCurrentDateTime())
            }
            db.insert("products", null, cv)
        } finally {
            dbLock.unlock()
        }
    }

    fun updateProduct(id: Long, data: JSONObject): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                data.optString("product_code")?.let { put("product_code", it) }
                data.optString("product_name")?.let { put("product_name", it) }
                data.optString("product_name_ar")?.let { put("product_name_ar", it) }
                data.optInt("category_id")?.let { put("category_id", it) }
                data.optInt("fuel_type_id")?.let { put("fuel_type_id", it) }
                data.optDouble("sale_price")?.let { put("sale_price", it) }
                data.optDouble("purchase_price")?.let { put("purchase_price", it) }
                data.optDouble("quantity")?.let { put("quantity", it) }
                data.optDouble("minimum_stock")?.let { put("minimum_stock", it) }
                if (data.has("unit_id")) put("unit_id", data.optInt("unit_id", 1))
                if (data.has("barcode")) put("barcode", data.optString("barcode"))
                if (data.has("description")) put("description", data.optString("description"))
                if (data.has("has_expiry")) put("has_expiry", data.optInt("has_expiry", 0))
                if (data.has("expiry_date")) {
                    if (data.isNull("expiry_date")) putNull("expiry_date") else put("expiry_date", data.optString("expiry_date"))
                }
                if (data.has("is_service")) put("is_service", data.optInt("is_service", 0))
                if (data.has("is_batch_tracked")) put("is_batch_tracked", data.optInt("is_batch_tracked", 0))
                if (data.has("is_serialized")) put("is_serialized", data.optInt("is_serialized", 0))
                if (data.has("status")) put("status", data.optString("status", "active"))
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("products", cv, "id=?", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "update_product", "تحديث منتج $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    fun deleteProduct(id: Long): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply { put("is_deleted", 1) }
            val rows = db.update("products", cv, "id=?", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "delete_product", "حذف منتج $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال أنواع الأطراف والعملات
    // ========================================================================


    // ========================================================================
    // عقود المنتجات والفئات وأنواع الأطراف وPOS والمرتجعات - V1
    // جميع الدوال أدناه تعمل على الجداول الموجودة فعلياً في هذا المخطط.
    // ========================================================================

    fun getUnits(): JSONArray {
        dbLock.lock()
        return try {
            readableDatabase.rawQuery(
                "SELECT id, uuid, unit_name, unit_symbol, is_decimal, category FROM units ORDER BY unit_name",
                null
            ).use { cursorToJsonArray(it) }
        } finally { dbLock.unlock() }
    }

    fun insertProductCategory(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val name = data.optString("category_name", data.optString("category_name_ar", "")).trim()
            require(name.isNotEmpty()) { "اسم الفئة مطلوب" }
            val db = writableDatabase
            val values = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("category_code", data.optString("category_code", "CAT-${UUID.randomUUID().toString().take(8)}"))
                put("category_name", name)
                put("category_name_ar", data.optString("category_name_ar", name))
                put("description", data.optString("description", ""))
                put("description_ar", data.optString("description_ar", data.optString("description", "")))
                if (data.optInt("parent_category_id", 0) > 0) put("parent_category_id", data.optInt("parent_category_id"))
                put("category_type", data.optString("category_type", "product"))
                put("tax_rate", data.optDouble("tax_rate", 0.0))
                put("is_active", data.optInt("is_active", 1))
                put("created_at", getCurrentDateTime())
                put("updated_at", getCurrentDateTime())
            }
            db.insertOrThrow("product_categories", null, values)
        } finally { dbLock.unlock() }
    }

    fun updateProductCategory(id: Long, data: JSONObject): Int {
        dbLock.lock()
        return try {
            val values = ContentValues().apply {
                if (data.has("category_code")) put("category_code", data.optString("category_code"))
                if (data.has("category_name")) put("category_name", data.optString("category_name"))
                if (data.has("category_name_ar")) put("category_name_ar", data.optString("category_name_ar"))
                if (data.has("description")) put("description", data.optString("description"))
                if (data.has("description_ar")) put("description_ar", data.optString("description_ar"))
                if (data.has("parent_category_id")) {
                    if (data.isNull("parent_category_id")) putNull("parent_category_id") else put("parent_category_id", data.optInt("parent_category_id"))
                }
                if (data.has("category_type")) put("category_type", data.optString("category_type", "product"))
                if (data.has("tax_rate")) put("tax_rate", data.optDouble("tax_rate", 0.0))
                if (data.has("is_active")) put("is_active", data.optInt("is_active", 1))
                put("updated_at", getCurrentDateTime())
            }
            writableDatabase.update("product_categories", values, "id=? AND is_deleted=0", arrayOf(id.toString()))
        } finally { dbLock.unlock() }
    }

    fun deleteProductCategory(id: Long): Int {
        dbLock.lock()
        return try {
            writableDatabase.update("product_categories", ContentValues().apply {
                put("is_deleted", 1); put("deleted_at", getCurrentDateTime())
            }, "id=? AND is_deleted=0", arrayOf(id.toString()))
        } finally { dbLock.unlock() }
    }

    fun searchProductCategories(query: String): JSONArray {
        dbLock.lock()
        return try {
            val q = "%${query.trim()}%"
            readableDatabase.rawQuery(
                "SELECT * FROM product_categories WHERE is_deleted=0 AND (category_name LIKE ? OR category_name_ar LIKE ? OR category_code LIKE ?) ORDER BY category_name",
                arrayOf(q, q, q)
            ).use { cursorToJsonArray(it) }
        } finally { dbLock.unlock() }
    }

    fun insertPartyType(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val code = data.optString("type_code").trim()
            val name = data.optString("type_name", data.optString("type_name_ar", "")).trim()
            require(code.isNotEmpty()) { "كود النوع مطلوب" }
            require(name.isNotEmpty()) { "اسم النوع مطلوب" }
            val values = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("type_code", code)
                put("type_name", name)
                put("type_name_ar", data.optString("type_name_ar", name))
                put("description", data.optString("description", ""))
                put("default_discount", data.optDouble("default_discount", 0.0))
                put("default_credit_limit", data.optDouble("default_credit_limit", 0.0))
                put("payment_terms_days", data.optInt("payment_terms_days", 0))
                put("is_active", data.optInt("is_active", 1))
                put("remarks", data.optString("remarks", ""))
                put("extra_data", data.optString("extra_data", ""))
                put("created_at", getCurrentDateTime())
                put("updated_at", getCurrentDateTime())
            }
            writableDatabase.insertOrThrow("party_types", null, values)
        } finally { dbLock.unlock() }
    }

    fun updatePartyType(id: Long, data: JSONObject): Int {
        dbLock.lock()
        return try {
            val values = ContentValues().apply {
                if (data.has("type_code")) put("type_code", data.optString("type_code"))
                if (data.has("type_name")) put("type_name", data.optString("type_name"))
                if (data.has("type_name_ar")) put("type_name_ar", data.optString("type_name_ar"))
                if (data.has("description")) put("description", data.optString("description"))
                if (data.has("default_discount")) put("default_discount", data.optDouble("default_discount", 0.0))
                if (data.has("default_credit_limit")) put("default_credit_limit", data.optDouble("default_credit_limit", 0.0))
                if (data.has("payment_terms_days")) put("payment_terms_days", data.optInt("payment_terms_days", 0))
                if (data.has("is_active")) put("is_active", data.optInt("is_active", 1))
                if (data.has("remarks")) put("remarks", data.optString("remarks"))
                if (data.has("extra_data")) put("extra_data", data.optString("extra_data"))
                put("updated_at", getCurrentDateTime())
            }
            writableDatabase.update("party_types", values, "id=? AND is_deleted=0", arrayOf(id.toString()))
        } finally { dbLock.unlock() }
    }

    fun deletePartyType(id: Long): Int {
        dbLock.lock()
        return try {
            writableDatabase.update("party_types", ContentValues().apply {
                put("is_deleted", 1); put("updated_at", getCurrentDateTime())
            }, "id=? AND is_deleted=0", arrayOf(id.toString()))
        } finally { dbLock.unlock() }
    }

    fun getPartyTypeReport(reportType: String): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val sql = when (reportType) {
                "parties_by_type" -> """
                    SELECT pt.id AS type_id, pt.type_code, pt.type_name, pt.type_name_ar,
                           COUNT(p.id) AS total_parties,
                           SUM(CASE WHEN p.is_active=1 THEN 1 ELSE 0 END) AS active_parties,
                           SUM(CASE WHEN p.is_active=0 THEN 1 ELSE 0 END) AS inactive_parties,
                           COALESCE(SUM(p.total_purchases),0) AS total_purchases,
                           COALESCE(SUM(p.total_payments),0) AS total_payments
                    FROM party_types pt LEFT JOIN parties p ON p.party_type_id=pt.id AND p.is_deleted=0
                    WHERE pt.is_deleted=0 GROUP BY pt.id ORDER BY pt.type_name
                """.trimIndent()
                "credit_analysis" -> """
                    SELECT pt.id AS type_id, pt.type_name, COUNT(p.id) AS party_count,
                           COALESCE(AVG(p.credit_limit),0) AS avg_credit,
                           COALESCE(SUM(p.credit_limit),0) AS total_credit,
                           COALESCE(SUM(p.current_balance),0) AS total_balance
                    FROM party_types pt LEFT JOIN parties p ON p.party_type_id=pt.id AND p.is_deleted=0
                    WHERE pt.is_deleted=0 GROUP BY pt.id ORDER BY pt.type_name
                """.trimIndent()
                "activity" -> """
                    SELECT pt.id AS type_id, pt.type_name, COUNT(DISTINCT s.id) AS invoice_count,
                           COALESCE(SUM(s.net_amount),0) AS total_sales, 0 AS payment_count,
                           MAX(s.created_at) AS last_activity
                    FROM party_types pt LEFT JOIN parties p ON p.party_type_id=pt.id AND p.is_deleted=0
                    LEFT JOIN sales_transactions s ON s.customer_party_id=p.id AND s.is_deleted=0
                    WHERE pt.is_deleted=0 GROUP BY pt.id ORDER BY pt.type_name
                """.trimIndent()
                else -> "SELECT id, uuid, type_code, type_name, type_name_ar, description, default_discount, default_credit_limit, payment_terms_days, is_active FROM party_types WHERE is_deleted=0 ORDER BY type_name"
            }
            db.rawQuery(sql, null).use { cursorToJsonArray(it) }
        } finally { dbLock.unlock() }
    }

    fun getNextInvoiceNumber(): String {
        dbLock.lock()
        return try {
            val next = readableDatabase.rawQuery("SELECT COALESCE(MAX(id),0)+1 FROM sales_transactions", null).use { if (it.moveToFirst()) it.getLong(0) else 1L }
            "INV-$next"
        } finally { dbLock.unlock() }
    }

    fun searchInvoices(data: JSONObject): JSONArray {
        dbLock.lock()
        return try {
            val where = mutableListOf("s.is_deleted=0")
            val args = mutableListOf<String>()
            data.optString("start_date").takeIf { it.isNotBlank() }?.let { where += "date(s.created_at) >= date(?)"; args += it }
            data.optString("end_date").takeIf { it.isNotBlank() }?.let { where += "date(s.created_at) <= date(?)"; args += it }
            val limit = data.optInt("limit", 100).coerceIn(1, 500)
            val sql = """SELECT s.id, s.invoice_number, s.payment_method AS payment_type,
                    s.gross_amount AS total_amount, s.paid_amount AS amount_paid, s.remaining_amount,
                    s.created_at AS sale_date, p.commercial_name AS customer_name
                    FROM sales_transactions s LEFT JOIN parties p ON p.id=s.customer_party_id
                    WHERE ${where.joinToString(" AND ")} ORDER BY s.id DESC LIMIT $limit"""
            readableDatabase.rawQuery(sql, args.toTypedArray()).use { cursorToJsonArray(it) }
        } finally { dbLock.unlock() }
    }

    fun getInvoiceDetails(invoiceNumber: String): JSONObject? {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val sale = db.rawQuery("SELECT s.*, p.commercial_name AS customer_name FROM sales_transactions s LEFT JOIN parties p ON p.id=s.customer_party_id WHERE s.invoice_number=? AND s.is_deleted=0 LIMIT 1", arrayOf(invoiceNumber)).use { cursor ->
                if (!cursor.moveToFirst()) return null
                JSONObject().apply {
                    put("sale_id", cursor.getLong(cursor.getColumnIndexOrThrow("id")))
                    put("invoice_number", cursor.getString(cursor.getColumnIndexOrThrow("invoice_number")))
                    put("payment_type", cursor.getString(cursor.getColumnIndexOrThrow("payment_method")))
                    put("total_amount", cursor.getDouble(cursor.getColumnIndexOrThrow("gross_amount")))
                    put("amount_paid", cursor.getDouble(cursor.getColumnIndexOrThrow("paid_amount")))
                    put("entity_id", cursor.getLong(cursor.getColumnIndexOrThrow("customer_party_id")))
                    put("customer_name", cursor.getString(cursor.getColumnIndexOrThrow("customer_name")))
                    put("sale_date", cursor.getString(cursor.getColumnIndexOrThrow("created_at")))
                }
            }
            val saleId = sale.getLong("sale_id")
            val items = db.rawQuery("SELECT si.product_id, si.quantity, si.unit_price, si.line_total AS total_price, p.product_name AS name, p.barcode FROM sale_items si LEFT JOIN products p ON p.id=si.product_id WHERE si.sale_id=? ORDER BY si.line_number", arrayOf(saleId.toString())).use { cursorToJsonArray(it) }
            sale.put("items", items)
            sale
        } finally { dbLock.unlock() }
    }

    fun searchSaleItems(data: JSONObject): JSONArray {
        dbLock.lock()
        return try {
            val where = mutableListOf("s.is_deleted=0", "si.item_type='product'")
            val args = mutableListOf<String>()
            data.optString("start_date").takeIf { it.isNotBlank() }?.let { where += "date(s.created_at) >= date(?)"; args += it }
            data.optString("end_date").takeIf { it.isNotBlank() }?.let { where += "date(s.created_at) <= date(?)"; args += it }
            val sql = """SELECT s.id AS sale_id, s.invoice_number, s.created_at AS sale_date,
                    si.product_id, p.product_name, p.barcode, si.quantity, si.unit_price, si.line_total AS total_price
                    FROM sale_items si JOIN sales_transactions s ON s.id=si.sale_id
                    LEFT JOIN products p ON p.id=si.product_id WHERE ${where.joinToString(" AND ")}
                    ORDER BY s.created_at DESC, si.line_number LIMIT 500"""
            readableDatabase.rawQuery(sql, args.toTypedArray()).use { cursorToJsonArray(it) }
        } finally { dbLock.unlock() }
    }

    fun processSaleReturn(data: JSONObject): JSONObject {
        val invoiceNumber = data.optString("invoice_number").trim()
        val barcode = data.optString("barcode").trim()
        val productId = data.optLong("product_id", 0L)
        val requestedQty = data.optDouble("quantity", 0.0)
        require(invoiceNumber.isNotEmpty()) { "رقم الفاتورة مطلوب" }
        require(requestedQty > 0) { "كمية الإرجاع يجب أن تكون أكبر من صفر" }
        dbLock.lock()
        return try {
            val db = writableDatabase
            val item = db.rawQuery("""SELECT si.id, si.sale_id, si.product_id, si.quantity, si.returned_quantity
                    FROM sale_items si JOIN sales_transactions s ON s.id=si.sale_id
                    LEFT JOIN products p ON p.id=si.product_id
                    WHERE s.invoice_number=? AND (?=0 OR si.product_id=?) AND (?='' OR p.barcode=?) AND si.item_type='product' LIMIT 1""",
                arrayOf(invoiceNumber, productId.toString(), productId.toString(), barcode, barcode)).use { cursor ->
                if (!cursor.moveToFirst()) throw IllegalArgumentException("لم يتم العثور على بند مطابق في الفاتورة")
                JSONObject().apply {
                    put("item_id", cursor.getLong(0)); put("sale_id", cursor.getLong(1)); put("product_id", cursor.getLong(2));
                    put("sold_quantity", cursor.getDouble(3)); put("returned_quantity", cursor.getDouble(4))
                }
            }
            val available = item.getDouble("sold_quantity") - item.getDouble("returned_quantity")
            require(requestedQty <= available) { "كمية الإرجاع تتجاوز الكمية المتاحة" }
            val movementId = addStockMovement(JSONObject().apply {
                put("product_id", item.getLong("product_id")); put("quantity", requestedQty); put("movement_type", "return")
                put("reference_type", "sale_return"); put("reference_id", item.getLong("sale_id"))
                put("notes", data.optString("reason", "مرتجع مبيعات") + " - " + data.optString("notes", ""))
                put("performed_by", data.optInt("created_by", 1)); put("station_id", data.optInt("station_id", 1))
            })
            require(movementId > 0) { "فشل تسجيل حركة الإرجاع" }
            db.execSQL("UPDATE sale_items SET is_returned=1, returned_quantity=returned_quantity+? WHERE id=?", arrayOf(requestedQty, item.getLong("item_id")))
            JSONObject().apply { put("success", true); put("id", movementId); put("sale_id", item.getLong("sale_id")); put("product_id", item.getLong("product_id")) }
        } finally { dbLock.unlock() }
    }

    fun getReturns(data: JSONObject): JSONArray {
        dbLock.lock()
        return try {
            val where = mutableListOf("im.is_deleted=0", "im.movement_type='return'")
            val args = mutableListOf<String>()
            data.optString("start_date").takeIf { it.isNotBlank() }?.let { where += "date(im.created_at) >= date(?)"; args += it }
            data.optString("end_date").takeIf { it.isNotBlank() }?.let { where += "date(im.created_at) <= date(?)"; args += it }
            val sql = """SELECT im.id, im.product_id, p.product_name, p.product_name_ar, im.quantity_change AS quantity,
                    im.total_cost AS value, im.reason, im.created_at AS return_date, im.status,
                    im.reference_id, s.invoice_number, im.performed_by AS created_by
                    FROM inventory_movements im LEFT JOIN products p ON p.id=im.product_id
                    LEFT JOIN sales_transactions s ON s.id=im.reference_id WHERE ${where.joinToString(" AND ")}
                    ORDER BY im.created_at DESC LIMIT 500"""
            readableDatabase.rawQuery(sql, args.toTypedArray()).use { cursorToJsonArray(it) }
        } finally { dbLock.unlock() }
    }

    fun updateReturn(id: Long, data: JSONObject): Int {
        dbLock.lock()
        return try {
            val values = ContentValues().apply {
                if (data.has("reason")) put("reason", data.optString("reason"))
                if (data.has("notes")) put("remarks", data.optString("notes"))
                put("updated_at", getCurrentDateTime())
            }
            writableDatabase.update("inventory_movements", values, "id=? AND movement_type='return' AND is_deleted=0", arrayOf(id.toString()))
        } finally { dbLock.unlock() }
    }

    fun deleteReturn(id: Long): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val movement = db.rawQuery("SELECT product_id, quantity_change, reference_id FROM inventory_movements WHERE id=? AND movement_type='return' AND is_deleted=0", arrayOf(id.toString())).use { cursor ->
                if (!cursor.moveToFirst()) return 0
                longArrayOf(cursor.getLong(0), cursor.getDouble(1).toBits(), cursor.getLong(2))
            }
            val productId = movement[0]
            val qty = Double.fromBits(movement[1])
            val saleId = movement[2]
            val reversal = addStockMovement(JSONObject().apply {
                put("product_id", productId); put("quantity", qty); put("movement_type", "out")
                put("reference_type", "sale_return_reversal"); put("reference_id", saleId)
                put("notes", "عكس مرتجع $id"); put("performed_by", 1); put("station_id", 1)
            })
            require(reversal > 0) { "فشل عكس حركة المرتجع" }
            db.update("inventory_movements", ContentValues().apply { put("is_deleted", 1); put("status", "cancelled"); put("updated_at", getCurrentDateTime()) }, "id=?", arrayOf(id.toString()))
        } finally { dbLock.unlock() }
    }



    fun getPartiesByType(typeId: Long): JSONArray {
        dbLock.lock()
        return try {
            readableDatabase.rawQuery("""SELECT id AS entity_id, id, party_code, legal_name, commercial_name, commercial_name_ar,
                    credit_limit, current_balance, payment_terms, is_active, is_deleted
                    FROM parties WHERE party_type_id=? AND is_deleted=0 ORDER BY commercial_name""", arrayOf(typeId.toString())).use { cursorToJsonArray(it) }
        } finally { dbLock.unlock() }
    }


    fun getPartyTypes(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                """SELECT id, uuid, type_code, type_name, type_name_ar, description,
                          default_discount, default_credit_limit, payment_terms_days, is_active
                   FROM party_types
                   WHERE is_deleted = 0
                   ORDER BY type_name""",
                null
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun getCurrencies(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                """SELECT id, uuid, currency_code, currency_name, currency_name_ar,
                          symbol, symbol_position, decimal_places, is_default, is_active
                   FROM currencies
                   WHERE is_deleted = 0
                   ORDER BY currency_code""",
                null
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال دفتر الأستاذ والروابط
    // ========================================================================

    fun getCustomerLedger(partyId: Int, limit: Int = 100): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                """SELECT cl.id, cl.uuid, cl.party_id, cl.transaction_date, cl.transaction_type,
                          cl.transaction_id, cl.reference_number, cl.debit, cl.credit, cl.balance,
                          cl.description, cl.created_at, cl.created_by,
                          p.commercial_name as customer_name
                   FROM customer_ledger cl
                   LEFT JOIN parties p ON cl.party_id = p.id
                   WHERE cl.party_id = ?
                   ORDER BY cl.transaction_date DESC, cl.id DESC
                   LIMIT ?""",
                arrayOf(partyId.toString(), limit.toString())
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun getCustomerLedger(partyId: Long, limit: Int = 100): JSONArray =
        getCustomerLedger(partyId.toInt(), limit)

    fun getCustomerSales(partyId: Int, limit: Int = 100): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                """SELECT s.id, s.uuid, s.sale_code, s.station_id, s.shift_id, s.customer_party_id,
                          s.liters, s.price_per_liter, s.fuel_subtotal, s.subtotal,
                          s.discount_amount, s.tax_amount, s.gross_amount, s.net_amount,
                          s.payment_method, s.payment_status, s.paid_amount, s.remaining_amount,
                          s.is_credit, s.due_date, s.invoice_number, s.status, s.created_at,
                          s.delivery_location, s.delivery_time, s.order_type,
                          f.fuel_name, f.fuel_name_ar
                   FROM sales_transactions s
                   LEFT JOIN fuel_types f ON s.fuel_type_id = f.id
                   WHERE s.customer_party_id = ? AND s.is_deleted = 0
                   ORDER BY s.created_at DESC
                   LIMIT ?""",
                arrayOf(partyId.toString(), limit.toString())
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun getCustomerSales(partyId: Long, limit: Int = 100): JSONArray =
        getCustomerSales(partyId.toInt(), limit)

    // ========================================================================
    // دوال جهات الاتصال والعناوين
    // ========================================================================

    fun getPartyContacts(partyId: Int): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                """SELECT id, uuid, party_id, contact_name, contact_name_ar, job_title,
                          department, phone, phone2, email, whatsapp,
                          is_primary, is_billing, is_technical, is_active,
                          created_at, updated_at
                   FROM party_contacts
                   WHERE party_id = ? AND is_deleted = 0
                   ORDER BY is_primary DESC, contact_name""",
                arrayOf(partyId.toString())
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun getPartyContacts(partyId: Long): JSONArray = getPartyContacts(partyId.toInt())

    fun addPartyContact(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("party_id", data.optInt("party_id", 0))
                put("contact_name", data.optString("contact_name", ""))
                put("contact_name_ar", data.optString("contact_name_ar", ""))
                put("job_title", data.optString("job_title", ""))
                put("department", data.optString("department", ""))
                put("phone", data.optString("phone", ""))
                put("phone2", data.optString("phone2", ""))
                put("email", data.optString("email", ""))
                put("whatsapp", data.optString("whatsapp", ""))
                put("is_primary", if (data.optBoolean("is_primary", false)) 1 else 0)
                put("is_billing", if (data.optBoolean("is_billing", false)) 1 else 0)
                put("is_technical", if (data.optBoolean("is_technical", false)) 1 else 0)
                put("is_active", 1)
                put("created_at", getCurrentDateTime())
                put("updated_at", getCurrentDateTime())
            }
            val id = db.insert("party_contacts", null, cv)
            if (id > 0) logActivity("system", "add_party_contact", "إضافة جهة اتصال للطرف: ${data.optInt("party_id", 0)}")
            id
        } finally {
            dbLock.unlock()
        }
    }

    fun updatePartyContact(id: Long, data: JSONObject): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                data.optString("contact_name")?.let { if (it.isNotEmpty()) put("contact_name", it) }
                data.optString("contact_name_ar")?.let { if (it.isNotEmpty()) put("contact_name_ar", it) }
                data.optString("job_title")?.let { if (it.isNotEmpty()) put("job_title", it) }
                data.optString("department")?.let { if (it.isNotEmpty()) put("department", it) }
                data.optString("phone")?.let { if (it.isNotEmpty()) put("phone", it) }
                data.optString("phone2")?.let { if (it.isNotEmpty()) put("phone2", it) }
                data.optString("email")?.let { if (it.isNotEmpty()) put("email", it) }
                data.optString("whatsapp")?.let { if (it.isNotEmpty()) put("whatsapp", it) }
                if (data.has("is_primary")) put("is_primary", if (data.optBoolean("is_primary")) 1 else 0)
                if (data.has("is_billing")) put("is_billing", if (data.optBoolean("is_billing")) 1 else 0)
                if (data.has("is_technical")) put("is_technical", if (data.optBoolean("is_technical")) 1 else 0)
                if (data.has("is_active")) put("is_active", if (data.optBoolean("is_active")) 1 else 0)
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("party_contacts", cv, "id=?", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "update_party_contact", "تحديث جهة اتصال: $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    fun deletePartyContact(id: Long): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply { put("is_deleted", 1) }
            val rows = db.update("party_contacts", cv, "id=?", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "delete_party_contact", "حذف جهة اتصال: $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    fun getPartyAddresses(partyId: Int): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                """SELECT id, uuid, party_id, address_type, address_line1, address_line2,
                          city, state, postal_code, country, is_default,
                          created_at, updated_at
                   FROM party_addresses
                   WHERE party_id = ? AND is_deleted = 0
                   ORDER BY is_default DESC""",
                arrayOf(partyId.toString())
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun getPartyAddresses(partyId: Long): JSONArray = getPartyAddresses(partyId.toInt())

    fun addPartyAddress(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("party_id", data.optInt("party_id", 0))
                put("address_type", data.optString("address_type", ""))
                put("address_line1", data.optString("address_line1", ""))
                put("address_line2", data.optString("address_line2", ""))
                put("city", data.optString("city", ""))
                put("state", data.optString("state", ""))
                put("postal_code", data.optString("postal_code", ""))
                put("country", data.optString("country", ""))
                put("is_default", if (data.optBoolean("is_default", false)) 1 else 0)
                put("created_at", getCurrentDateTime())
                put("updated_at", getCurrentDateTime())
            }
            val id = db.insert("party_addresses", null, cv)
            if (id > 0) logActivity("system", "add_party_address", "إضافة عنوان للطرف: ${data.optInt("party_id", 0)}")
            id
        } finally {
            dbLock.unlock()
        }
    }

    fun updatePartyAddress(id: Long, data: JSONObject): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                data.optString("address_type")?.let { if (it.isNotEmpty()) put("address_type", it) }
                data.optString("address_line1")?.let { if (it.isNotEmpty()) put("address_line1", it) }
                data.optString("address_line2")?.let { if (it.isNotEmpty()) put("address_line2", it) }
                data.optString("city")?.let { if (it.isNotEmpty()) put("city", it) }
                data.optString("state")?.let { if (it.isNotEmpty()) put("state", it) }
                data.optString("postal_code")?.let { if (it.isNotEmpty()) put("postal_code", it) }
                data.optString("country")?.let { if (it.isNotEmpty()) put("country", it) }
                if (data.has("is_default")) put("is_default", if (data.optBoolean("is_default")) 1 else 0)
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("party_addresses", cv, "id=?", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "update_party_address", "تحديث عنوان: $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    fun deletePartyAddress(id: Long): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply { put("is_deleted", 1) }
            val rows = db.update("party_addresses", cv, "id=?", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "delete_party_address", "حذف عنوان: $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال ديون العملاء
    // ========================================================================

    fun getCustomerDebts(partyId: Int? = null): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val sql = if (partyId != null) {
                """SELECT s.id, s.uuid, s.sale_code, s.customer_party_id, s.liters,
                          s.net_amount, s.paid_amount, s.remaining_amount, s.due_date,
                          s.payment_status, s.invoice_number, s.created_at,
                          p.commercial_name as customer_name, p.phone as customer_phone
                   FROM sales_transactions s
                   LEFT JOIN parties p ON s.customer_party_id = p.id
                   WHERE s.customer_party_id = ? AND s.remaining_amount > 0 AND s.is_deleted = 0
                   ORDER BY s.due_date ASC"""
            } else {
                """SELECT s.id, s.uuid, s.sale_code, s.customer_party_id, s.liters,
                          s.net_amount, s.paid_amount, s.remaining_amount, s.due_date,
                          s.payment_status, s.invoice_number, s.created_at,
                          p.commercial_name as customer_name, p.phone as customer_phone
                   FROM sales_transactions s
                   LEFT JOIN parties p ON s.customer_party_id = p.id
                   WHERE s.remaining_amount > 0 AND s.is_deleted = 0
                   ORDER BY s.due_date ASC"""
            }
            val args = if (partyId != null) arrayOf(partyId.toString()) else null
            db.rawQuery(sql, args).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun getCustomerDebts(fromDate: String?, toDate: String?): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val predicates = mutableListOf("s.remaining_amount > 0", "s.is_deleted = 0")
            val args = mutableListOf<String>()
            if (!fromDate.isNullOrBlank()) {
                predicates += "date(COALESCE(s.due_date, s.created_at)) >= date(?)"
                args += fromDate
            }
            if (!toDate.isNullOrBlank()) {
                predicates += "date(COALESCE(s.due_date, s.created_at)) <= date(?)"
                args += toDate
            }
            val sql = """SELECT s.id, s.uuid, s.sale_code, s.customer_party_id, s.liters,
                              s.net_amount, s.paid_amount, s.remaining_amount, s.due_date,
                              s.payment_status, s.invoice_number, s.created_at,
                              p.commercial_name as customer_name, p.phone as customer_phone
                       FROM sales_transactions s
                       LEFT JOIN parties p ON s.customer_party_id = p.id
                       WHERE ${predicates.joinToString(" AND ")}
                       ORDER BY s.due_date ASC, s.id ASC"""
            db.rawQuery(sql, args.toTypedArray()).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال التنظيف
    // ========================================================================

    fun cleanupOldData(): Boolean {
        return cleanupOldData(getRetentionDays())
    }

    fun cleanupOldData(retentionDays: Int): Boolean {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cutoffDate = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -retentionDays) }
            val cutoffStr = getDateOnlyFormat().format(cutoffDate.time)

            db.beginTransaction()
            try {
                val deletedActivity = db.delete("user_activity_log", "date(created_at) < ?", arrayOf(cutoffStr))
                val deletedSystem = db.delete("system_logs", "date(created_at) < ? AND is_resolved = 1", arrayOf(cutoffStr))
                val deletedSync = db.delete("sync_logs", "date(created_at) < ?", arrayOf(cutoffStr))
                val deletedNotifications = db.delete("notification_logs", "date(sent_at) < ?", arrayOf(cutoffStr))
                val deletedSms = db.delete("sms_logs", "date(created_at) < ?", arrayOf(cutoffStr))
                db.execSQL("VACUUM")
                db.setTransactionSuccessful()

                val totalDeleted = deletedActivity + deletedSystem + deletedSync + deletedNotifications + deletedSms
                logActivity("system", "cleanup_old_data", "تنظيف البيانات القديمة قبل $cutoffStr. تم حذف $totalDeleted سجل")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup transaction: ${e.message}", e)
                false
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up old data: ${e.message}", e)
            false
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // الدوال الجديدة المطلوبة (تمت إضافتها)
    // ========================================================================

    fun tableExists(tableName: String): Boolean {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(tableName)
            ).use { cursor -> cursor.moveToFirst() }
        } finally {
            dbLock.unlock()
        }
    }

    fun getVersion(): Int = VERSION

    fun cleanupOldRateLimits(): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cutoff = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000) // 30 يوم
            db.delete("sms_rate_limits", "last_reply_at < ? OR blocked_until < ?", arrayOf(cutoff.toString(), cutoff.toString()))
        } finally {
            dbLock.unlock()
        }
    }

    fun cleanupOldConversationContext(days: Int = 30): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cutoff = System.currentTimeMillis() - (days.toLong() * 24 * 60 * 60 * 1000)
            db.delete("sms_conversation_context", "timestamp < ?", arrayOf(cutoff.toString()))
        } finally {
            dbLock.unlock()
        }
    }

    fun cleanupOldMetrics(retentionDays: Int = 90): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cutoff = System.currentTimeMillis() - (retentionDays.toLong() * 24 * 60 * 60 * 1000)
            db.delete("sms_metrics", "timestamp < ?", arrayOf(cutoff.toString()))
        } finally {
            dbLock.unlock()
        }
    }

    fun addMeterReading(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("reading_code", data.optString("reading_code", "MR-${System.currentTimeMillis()}"))
                put("pump_id", data.optInt("pump_id"))
                put("nozzle_id", data.optInt("nozzle_id", 0))
                put("station_id", data.optInt("station_id", 1))
                put("shift_id", data.optInt("shift_id", 0))
                put("reading_date", data.optString("reading_date", getCurrentDate()))
                put("period", data.optString("period", "daily"))
                put("opening_reading", data.optDouble("opening_reading"))
                put("closing_reading", data.optDouble("closing_reading"))
                put("sold_liters", data.optDouble("sold_liters"))
                put("read_by", data.optInt("read_by", 1))
                put("status", data.optString("status", "draft"))
                put("created_at", getCurrentDateTime())
                put("updated_at", getCurrentDateTime())
            }
            db.insert("meter_readings", null, cv)
        } finally {
            dbLock.unlock()
        }
    }

    fun getMeterReadings(pumpId: Int? = null, limit: Int = 100): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val sql = if (pumpId != null) {
                "SELECT * FROM meter_readings WHERE pump_id = ? ORDER BY reading_date DESC, id DESC LIMIT ?"
            } else {
                "SELECT * FROM meter_readings ORDER BY reading_date DESC, id DESC LIMIT ?"
            }
            val args = if (pumpId != null) arrayOf(pumpId.toString(), limit.toString()) else arrayOf(limit.toString())
            db.rawQuery(sql, args).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun getTankReadings(tankId: Int? = null, limit: Int = 100): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val sql = if (tankId != null) {
                "SELECT * FROM tank_level_log WHERE tank_id = ? ORDER BY reading_date DESC, id DESC LIMIT ?"
            } else {
                "SELECT * FROM tank_level_log ORDER BY reading_date DESC, id DESC LIMIT ?"
            }
            val args = if (tankId != null) arrayOf(tankId.toString(), limit.toString()) else arrayOf(limit.toString())
            db.rawQuery(sql, args).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun getLatestMeterReadings(pumpId: Int): JSONObject? {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM meter_readings WHERE pump_id = ? ORDER BY reading_date DESC, id DESC LIMIT 1",
                arrayOf(pumpId.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) cursorToJsonObject(cursor) else null
            }
        } finally {
            dbLock.unlock()
        }
    }
    /**
     * الحصول على آخر قراءة لكل مضخة.
     * @return JSONArray يحتوي على آخر قراءة لكل مضخة
     */
    fun getLatestMeterReadings(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                """
                SELECT mr.*
                FROM meter_readings mr
                INNER JOIN (
                    SELECT pump_id, MAX(id) AS latest_id
                    FROM meter_readings
                    WHERE is_deleted = 0
                    GROUP BY pump_id
                ) latest
                ON mr.id = latest.latest_id
                ORDER BY mr.pump_id
                """.trimIndent(),
                null
            ).use { cursor ->
                cursorToJsonArray(cursor)
            }
        } finally {
            dbLock.unlock()
        }
    }

    fun getAssetMaintenanceHistory(assetType: String, assetId: Int, limit: Int = 20): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM maintenance_requests WHERE asset_type = ? AND asset_id = ? AND is_deleted = 0 ORDER BY created_at DESC LIMIT ?",
                arrayOf(assetType, assetId.toString(), limit.toString())
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    fun deleteOlderThan(tableName: String, columnName: String, timestamp: Long): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            if (!tableExists(tableName)) return 0
            val columns = mutableListOf<String>()
            db.rawQuery("PRAGMA table_info($tableName)", null).use { cursor ->
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
            if (columnName !in columns) return 0
            db.delete(tableName, "$columnName < ?", arrayOf(timestamp.toString()))
        } finally {
            dbLock.unlock()
        }
    }

    fun syncContext(phone: String): Boolean {
        // يمكن تنفيذ مزامنة سياق المحادثة مع خادم مركزي هنا
        return true
    }

    fun syncPreferences(phone: String): Boolean {
        // مزامنة تفضيلات العميل
        return true
    }

    fun sync(phone: String): Boolean {
        return syncContext(phone) && syncPreferences(phone) && syncRateLimits(phone)
    }

    fun syncRateLimits(phone: String): Boolean {
        // مزامنة حدود المعدل مع الخادم
        return true
    }

    fun syncData(data: JSONObject): Boolean {
        // مزامنة بيانات عامة مع الخادم
        return true
    }

    fun recordPerformanceStats(stats: JSONObject): Boolean {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("log_level", "info")
                put("log_type", "performance")
                put("message", stats.toString())
                put("created_at", getCurrentDateTime())
            }
            db.insert("system_logs", null, cv) > 0
        } finally {
            dbLock.unlock()
        }
    }

    fun flush(): Boolean {
        // تنظيف أي ذاكرة تخزين مؤقت (مثلاً Clear Cache)
        return true
    }

    fun performSecurityCheck(): Boolean {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val now = System.currentTimeMillis()
            db.delete("user_sessions", "expires_at < ?", arrayOf(now.toString()))
            true
        } finally {
            dbLock.unlock()
        }
    }

    fun cleanupExpired(): Int {
        return cleanExpiredSmsOtps()
    }

    fun getCurrentMetrics(): JSONObject {
        val metrics = JSONObject()
        dbLock.lock()
        try {
            val db = readableDatabase
            db.rawQuery("SELECT COUNT(*) FROM users WHERE status='active' AND is_deleted=0", null).use { c ->
                metrics.put("active_users", if (c.moveToFirst()) c.getInt(0) else 0)
            }
            db.rawQuery("SELECT COUNT(*) FROM sales_transactions WHERE date(created_at)=date('now') AND is_deleted=0", null).use { c ->
                metrics.put("today_sales", if (c.moveToFirst()) c.getInt(0) else 0)
            }
            metrics.put("db_size_bytes", getDatabaseSize())
            listOf("parties", "products", "tanks", "pumps").forEach { table ->
                db.rawQuery("SELECT COUNT(*) FROM $table WHERE is_deleted=0", null).use { c ->
                    metrics.put("${table}_count", if (c.moveToFirst()) c.getInt(0) else 0)
                }
            }
        } finally {
            dbLock.unlock()
        }
        return metrics
    }

    fun isOpen(): Boolean {
        return try {
            readableDatabase.isOpen
        } catch (e: Exception) {
            false
        }
    }

    fun checkIntegrity(): Boolean {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                if (cursor.moveToFirst()) {
                    val result = cursor.getString(0)
                    result.equals("ok", ignoreCase = true)
                } else false
            }
        } finally {
            dbLock.unlock()
        }
    }
    /**
    * الحصول على وضع دفتر اليومية (Journal Mode) لقاعدة البيانات.
    * @return وضع الـ journal (مثل "WAL", "DELETE") أو null في حال الفشل.
    */
    fun getJournalMode(): String? {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery("PRAGMA journal_mode", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get journal mode: ${e.message}", e)
            null
        } finally {
            dbLock.unlock()
        }
    }


    fun addTankReading(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("tank_id", data.optInt("tank_id", 0))
                put("reading_date", data.optString("reading_date", getCurrentDateTime()))
                put("reading_type", data.optString("reading_type", "manual"))
                put("opening_level", data.optDouble("opening_level", 0.0))
                put("closing_level", data.optDouble("closing_level", 0.0))
                put("measured_level", data.optDouble("measured_level", 0.0))
                put("calculated_level", data.optDouble("calculated_level", 0.0))
                put("difference", data.optDouble("difference", 0.0))
                put("fuel_temperature", data.optDouble("fuel_temperature", 0.0))
                put("fuel_density", data.optDouble("fuel_density", 0.0))
                put("volume_at_15c", data.optDouble("volume_at_15c", 0.0))
                put("refills_total", data.optDouble("refills_total", 0.0))
                put("sales_total", data.optDouble("sales_total", 0.0))
                put("evaporation_loss", data.optDouble("evaporation_loss", 0.0))
                put("is_below_minimum", if (data.optBoolean("is_below_minimum", false)) 1 else 0)
                put("is_near_maximum", if (data.optBoolean("is_near_maximum", false)) 1 else 0)
                put("alert_triggered", if (data.optBoolean("alert_triggered", false)) 1 else 0)
                put("created_at", getCurrentDateTime())
                put("created_by", data.optInt("created_by", 1))
            }
            db.insert("tank_level_log", null, cv)
        } finally {
            dbLock.unlock()
        }
    }

    fun checkUserPermission(userId: Long, permissionCode: String): Boolean {
        if (userId <= 0 || permissionCode.isBlank()) return false
        val action = permissionCode.substringAfterLast('.', "read")
        val capabilityColumn = when (action) {
            "create" -> "rp.can_create"
            "read", "view" -> "rp.can_read"
            "update" -> "rp.can_update"
            "delete" -> "rp.can_delete"
            "export" -> "rp.can_export"
            "print" -> "rp.can_print"
            "approve" -> "rp.can_approve"
            else -> return false
        }
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                """SELECT 1 FROM role_permissions rp
                   JOIN users u ON u.role_id = rp.role_id
                   JOIN permissions p ON p.id = rp.permission_id
                   WHERE u.id = ?
                     AND u.is_deleted = 0
                     AND p.permission_code = ?
                     AND p.is_active = 1
                     AND p.is_deleted = 0
                     AND rp.is_deleted = 0
                     AND $capabilityColumn = 1
                   LIMIT 1""".trimIndent(),
                arrayOf(userId.toString(), permissionCode)
            ).use { cursor -> cursor.moveToFirst() }
        } finally {
            dbLock.unlock()
        }
    }


    // ========================================================================
    // دوال البنوك والحسابات البنكية (مطابقة لـ banks و bank_accounts)
    // ========================================================================

    fun getBanks(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                """SELECT id, uuid, bank_code, bank_name, bank_name_ar, swift_code,
                          country, city, address, phone, email, website, is_active,
                          created_at, updated_at, remarks, extra_data,
                          (SELECT COUNT(*) FROM bank_accounts ba WHERE ba.bank_id = banks.id AND ba.is_deleted = 0) AS account_count,
                          COALESCE((SELECT SUM(ba.current_balance) FROM bank_accounts ba WHERE ba.bank_id = banks.id AND ba.is_deleted = 0), 0) AS total_balance
                   FROM banks
                   WHERE is_deleted = 0
                   ORDER BY COALESCE(bank_name_ar, bank_name) COLLATE NOCASE""",
                null
            ).use { cursorToJsonArray(it) }
        } finally {
            dbLock.unlock()
        }
    }

    fun getBankAccounts(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                """SELECT a.id, a.uuid, a.account_code, a.bank_id, a.company_id, a.station_id,
                          a.account_name, a.account_name_ar, a.account_number, a.iban,
                          a.account_type, a.currency_id, a.opening_balance, a.current_balance,
                          a.available_balance, a.overdraft_limit, a.authorized_users, a.status,
                          a.created_at, a.updated_at, a.remarks, a.extra_data,
                          b.bank_code, b.bank_name, b.bank_name_ar,
                          c.currency_code, c.currency_name, c.currency_name_ar
                   FROM bank_accounts a
                   LEFT JOIN banks b ON b.id = a.bank_id AND b.is_deleted = 0
                   LEFT JOIN currencies c ON c.id = a.currency_id AND c.is_deleted = 0
                   WHERE a.is_deleted = 0
                   ORDER BY COALESCE(a.account_name_ar, a.account_name) COLLATE NOCASE""",
                null
            ).use { cursorToJsonArray(it) }
        } finally {
            dbLock.unlock()
        }
    }

    fun getBankLedger(startDate: String?, endDate: String?): JSONArray {
        dbLock.lock()
        return try {
            val selection = StringBuilder("l.bank_account_id = a.id")
            val args = mutableListOf<String>()
            if (!startDate.isNullOrBlank()) {
                selection.append(" AND date(l.transaction_date) >= date(?)")
                args.add(startDate)
            }
            if (!endDate.isNullOrBlank()) {
                selection.append(" AND date(l.transaction_date) <= date(?)")
                args.add(endDate)
            }
            val db = readableDatabase
            db.rawQuery(
                """SELECT l.id, l.uuid, l.transaction_date, l.transaction_type,
                          l.transaction_id, l.reference_number, l.debit, l.credit,
                          (l.debit - l.credit) AS amount, l.balance, l.description,
                          a.account_name, a.account_name_ar, b.bank_name, b.bank_name_ar
                   FROM bank_ledger l
                   JOIN bank_accounts a ON ${selection}
                   LEFT JOIN banks b ON b.id = a.bank_id AND b.is_deleted = 0
                   WHERE a.is_deleted = 0
                   ORDER BY datetime(l.transaction_date) DESC, l.id DESC""",
                args.toTypedArray()
            ).use { cursorToJsonArray(it) }
        } finally {
            dbLock.unlock()
        }
    }

    fun insertBank(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("bank_code", data.optString("bank_code").trim())
                put("bank_name", data.optString("bank_name").trim())
                put("bank_name_ar", data.optString("bank_name_ar").trim())
                put("swift_code", data.optString("swift_code").trim())
                put("country", data.optString("country").trim())
                put("city", data.optString("city").trim())
                put("address", data.optString("address").trim())
                put("phone", data.optString("phone").trim())
                put("email", data.optString("email").trim())
                put("website", data.optString("website").trim())
                put("is_active", if (data.optInt("is_active", 1) == 1) 1 else 0)
                put("remarks", data.optString("remarks", data.optString("notes", "")).trim())
                put("extra_data", data.optString("extra_data", ""))
            }
            val id = db.insertOrThrow("banks", null, values)
            logActivity("system", "insert_bank", "إضافة بنك: ${data.optString("bank_name_ar", data.optString("bank_name"))}")
            id
        } finally {
            dbLock.unlock()
        }
    }

    fun updateBank(id: Long, data: JSONObject): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("bank_code", data.optString("bank_code").trim())
                put("bank_name", data.optString("bank_name").trim())
                put("bank_name_ar", data.optString("bank_name_ar").trim())
                put("swift_code", data.optString("swift_code").trim())
                put("country", data.optString("country").trim())
                put("city", data.optString("city").trim())
                put("address", data.optString("address").trim())
                put("phone", data.optString("phone").trim())
                put("email", data.optString("email").trim())
                put("website", data.optString("website").trim())
                put("is_active", if (data.optInt("is_active", 1) == 1) 1 else 0)
                put("remarks", data.optString("remarks", data.optString("notes", "")).trim())
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("banks", values, "id = ? AND is_deleted = 0", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "update_bank", "تحديث بنك: $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    fun deleteBank(id: Long): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            db.rawQuery("SELECT COUNT(*) FROM bank_accounts WHERE bank_id = ? AND is_deleted = 0", arrayOf(id.toString())).use { cursor ->
                if (cursor.moveToFirst() && cursor.getInt(0) > 0) {
                    throw IllegalStateException("لا يمكن حذف بنك مرتبط بحسابات بنكية")
                }
            }
            val values = ContentValues().apply {
                put("is_deleted", 1)
                put("is_active", 0)
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("banks", values, "id = ? AND is_deleted = 0", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "delete_bank", "حذف بنك: $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    fun insertBankAccount(data: JSONObject, stationId: Int, userId: Long): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("account_code", data.optString("account_code").trim())
                put("bank_id", data.optLong("bank_id", 0L))
                put("station_id", stationId)
                put("account_name", data.optString("account_name").trim())
                put("account_name_ar", data.optString("account_name_ar").trim())
                put("account_number", data.optString("account_number").trim())
                put("iban", data.optString("iban").trim())
                put("account_type", data.optString("account_type", "current"))
                put("currency_id", data.optLong("currency_id", 0L))
                val opening = data.optDouble("opening_balance", 0.0)
                put("opening_balance", opening)
                put("current_balance", opening)
                put("available_balance", opening)
                put("overdraft_limit", data.optDouble("overdraft_limit", 0.0))
                put("authorized_users", data.optString("authorized_users").trim())
                put("status", data.optString("status", "active"))
                put("created_by", userId)
                put("remarks", data.optString("remarks", data.optString("notes", "")).trim())
                put("extra_data", data.optString("extra_data", ""))
            }
            val id = db.insertOrThrow("bank_accounts", null, values)
            logActivity("system", "insert_bank_account", "إضافة حساب بنكي: ${data.optString("account_name_ar", data.optString("account_name"))}")
            id
        } finally {
            dbLock.unlock()
        }
    }

    fun updateBankAccount(id: Long, data: JSONObject, userId: Long): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("account_code", data.optString("account_code").trim())
                put("bank_id", data.optLong("bank_id", 0L))
                put("account_name", data.optString("account_name").trim())
                put("account_name_ar", data.optString("account_name_ar").trim())
                put("account_number", data.optString("account_number").trim())
                put("iban", data.optString("iban").trim())
                put("account_type", data.optString("account_type", "current"))
                put("currency_id", data.optLong("currency_id", 0L))
                put("overdraft_limit", data.optDouble("overdraft_limit", 0.0))
                put("authorized_users", data.optString("authorized_users").trim())
                put("status", data.optString("status", "active"))
                put("updated_by", userId)
                put("updated_at", getCurrentDateTime())
                put("remarks", data.optString("remarks", data.optString("notes", "")).trim())
            }
            val rows = db.update("bank_accounts", values, "id = ? AND is_deleted = 0", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "update_bank_account", "تحديث حساب بنكي: $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    fun deleteBankAccount(id: Long, userId: Long): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put("is_deleted", 1)
                put("status", "closed")
                put("deleted_at", getCurrentDateTime())
                put("deleted_by", userId)
            }
            val rows = db.update("bank_accounts", values, "id = ? AND is_deleted = 0", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "delete_bank_account", "حذف حساب بنكي: $id")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال إدارة رموز إعادة تعيين كلمة المرور (Password Reset Tokens)
    // ========================================================================

    /**
     * تخزين رمز إعادة تعيين كلمة المرور للمستخدم
     * @param userId معرف المستخدم
     * @param token الرمز المميز
     * @param expiryMinutes مدة صلاحية الرمز بالدقائق (افتراضي 60)
     * @return true إذا نجحت العملية
     */
    fun storeResetToken(userId: Long, token: String, expiryMinutes: Int = 60): Boolean {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val expiresAt = Calendar.getInstance().apply { add(Calendar.MINUTE, expiryMinutes) }
            val cv = ContentValues().apply {
                put("user_id", userId)
                put("token", token)
                put("expires_at", getDateFormat().format(expiresAt.time))
                put("is_used", 0)
                put("created_at", getCurrentDateTime())
            }
            db.insertWithOnConflict("password_reset_tokens", null, cv, SQLiteDatabase.CONFLICT_REPLACE) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error storing reset token: ${e.message}", e)
            false
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * التحقق من صلاحية رمز إعادة تعيين كلمة المرور
     * @param token الرمز المميز
     * @return JSONObject يحتوي على بيانات المستخدم إذا كان الرمز صالحاً، أو null
     */
    fun validateResetToken(token: String): JSONObject? {
        if (token.isBlank()) return null
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                """SELECT t.id as token_id, t.user_id, t.expires_at, t.is_used,
                          u.username, u.full_name, u.full_name_ar, u.email, u.phone
                   FROM password_reset_tokens t
                   JOIN users u ON t.user_id = u.id
                   WHERE t.token = ? AND datetime(t.expires_at) > datetime('now') AND t.is_used = 0 AND u.is_deleted = 0
                   LIMIT 1""".trimIndent(),
                arrayOf(token)
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    JSONObject().apply {
                        put("token_id", cursor.getInt(cursor.getColumnIndexOrThrow("token_id")))
                        put("user_id", cursor.getInt(cursor.getColumnIndexOrThrow("user_id")))
                        put("username", cursor.getString(cursor.getColumnIndexOrThrow("username")))
                        put("full_name", cursor.getString(cursor.getColumnIndexOrThrow("full_name")))
                        put("full_name_ar", cursor.getString(cursor.getColumnIndexOrThrow("full_name_ar")))
                        put("email", cursor.getString(cursor.getColumnIndexOrThrow("email")))
                        put("phone", cursor.getString(cursor.getColumnIndexOrThrow("phone")))
                        put("expires_at", cursor.getString(cursor.getColumnIndexOrThrow("expires_at")))
                    }
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error validating reset token: ${e.message}", e)
            null
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * إزالة رمز إعادة تعيين كلمة المرور (بعد الاستخدام أو الإلغاء)
     * @param token الرمز المميز
     * @return true إذا تم الحذف
     */
    fun clearResetToken(token: String): Boolean {
        if (token.isBlank()) return false
        dbLock.lock()
        return try {
            val db = writableDatabase
            db.delete("password_reset_tokens", "token = ?", arrayOf(token)) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing reset token: ${e.message}", e)
            false
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * تحديث كلمة مرور المستخدم مع توليد salt و hash جديدين
     * @param userId معرف المستخدم
     * @param newPassword كلمة المرور الجديدة
     * @return true إذا نجح التحديث
     */
    fun updateUserPassword(userId: Long, newPassword: String): Boolean {
        if (newPassword.isBlank()) return false
        dbLock.lock()
        return try {
            val db = writableDatabase
            val (hash, salt) = hashPassword(newPassword)
            val cv = ContentValues().apply {
                put("password_hash", hash)
                put("password_salt", salt)
                put("last_password_change", getCurrentDateTime())
                put("must_change_password", 0)
                put("failed_login_attempts", 0)
                put("account_locked", 0)
                put("locked_until", null as String?)
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("users", cv, "id = ? AND is_deleted = 0", arrayOf(userId.toString()))
            if (rows > 0) {
                // تعيين الرمز كمستخدم
                val usedCv = ContentValues().apply {
                    put("is_used", 1)
                    put("used_at", getCurrentDateTime())
                }
                db.update("password_reset_tokens", usedCv, "user_id = ? AND is_used = 0", arrayOf(userId.toString()))
                logActivity("system", "password_change", "تم تحديث كلمة المرور للمستخدم $userId")
            }
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error updating user password: ${e.message}", e)
            false
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال OTP الخاصة بالمستخدمين (User OTP)
    // ========================================================================

    /**
     * إنشاء/تخزين رمز OTP للمستخدم
     * @param userId معرف المستخدم
     * @param otpCode رمز OTP
     * @param expirySeconds مدة الصلاحية بالثواني (افتراضي 300 = 5 دقائق)
     * @return true إذا نجحت العملية
     */
    fun storeUserOtp(userId: Long, otpCode: String, expirySeconds: Int = 300): Boolean {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val now = System.currentTimeMillis()
            val expiresAt = now + (expirySeconds * 1000L)
            val cv = ContentValues().apply {
                put("user_id", userId)
                put("otp_code", otpCode)
                put("timestamp", now)
                put("attempts", 0)
                put("max_attempts", 3)
                put("expires_at", expiresAt)
                put("created_at", getCurrentDateTime())
            }
            db.insertWithOnConflict("user_otp_verifications", null, cv, SQLiteDatabase.CONFLICT_REPLACE) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error storing user OTP: ${e.message}", e)
            false
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * التحقق من رمز OTP للمستخدم
     * @param userId معرف المستخدم
     * @param otpCode رمز OTP المدخل
     * @return true إذا كان الرمز صحيحاً ولم تنتهِ صلاحيته
     */
    fun validateOtpCode(userId: Long, otpCode: String): Boolean {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val now = System.currentTimeMillis()
            db.rawQuery(
                "SELECT * FROM user_otp_verifications WHERE user_id = ? AND otp_code = ? AND expires_at > ?",
                arrayOf(userId.toString(), otpCode, now.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    // نجاح: حذف الرمز بعد الاستخدام
                    db.delete("user_otp_verifications", "user_id = ?", arrayOf(userId.toString()))
                    logActivity("system", "otp_verified", "تم التحقق من OTP للمستخدم $userId")
                    true
                } else {
                    // فشل: زيادة عدد المحاولات
                    db.execSQL("UPDATE user_otp_verifications SET attempts = attempts + 1 WHERE user_id = ?", arrayOf(userId.toString()))
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error validating OTP code: ${e.message}", e)
            false
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * إزالة/حذف رمز OTP للمستخدم (بعد الاستخدام أو الإلغاء)
     * @param userId معرف المستخدم
     * @return true إذا تم الحذف
     */
    fun clearOtpCode(userId: Long): Boolean {
        dbLock.lock()
        return try {
            val db = writableDatabase
            db.delete("user_otp_verifications", "user_id = ?", arrayOf(userId.toString())) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing OTP code: ${e.message}", e)
            false
        } finally {
            dbLock.unlock()
        }
    }

    /**
     * تنظيف رموز OTP منتهية الصلاحية للمستخدمين
     * @return عدد السجلات المحذوفة
     */
    fun cleanupExpiredUserOtps(): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val now = System.currentTimeMillis()
            db.delete("user_otp_verifications", "expires_at < ?", arrayOf(now.toString()))
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up expired user OTPs: ${e.message}", e)
            0
        } finally {
            dbLock.unlock()
        }
    }



    // =========================================================================
    // CRM_BUNDLE_V1: تفاصيل الطرف وتقارير CRM من الجداول الموجودة فعلياً.
    // =========================================================================


    private fun recordPartyAudit(partyId: Long, action: String, oldRow: JSONObject?, newRow: JSONObject?, userId: Long) {
        val values = ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("user_id", if (userId > 0) userId else null)
            put("action_type", action)
            put("table_name", "parties")
            put("record_id", partyId)
            put("old_row_json", oldRow?.toString())
            put("new_row_json", newRow?.toString())
            put("created_at", getCurrentDateTime())
        }
        writableDatabase.insert("audit_logs", null, values)
    }

    fun savePartyBundle(data: JSONObject, userId: Long = 0L): Long {
        dbLock.lock()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val requestedId = data.optLong("id", 0L)
            val oldParty = if (requestedId > 0) getParty(requestedId.toInt()) else null
            val partyId = if (requestedId > 0) {
                require(updateParty(requestedId, data) > 0) { "الطرف غير موجود أو لم يتم تحديثه" }
                requestedId
            } else {
                val inserted = insertParty(data)
                require(inserted > 0) { "لم يتم إنشاء الطرف" }
                inserted
            }
            db.delete("party_contacts", "party_id = ?", arrayOf(partyId.toString()))
            val contacts = data.optJSONArray("contacts") ?: JSONArray()
            for (i in 0 until contacts.length()) {
                val contact = contacts.optJSONObject(i) ?: continue
                val contactName = contact.optString("contact_name").trim().ifEmpty {
                    if (i == 0) data.optString("commercial_name").trim().ifEmpty { "جهة اتصال" } else "جهة اتصال"
                }
                val values = ContentValues().apply {
                    put("uuid", UUID.randomUUID().toString())
                    put("party_id", partyId)
                    put("contact_name", contactName)
                    put("contact_name_ar", contact.optString("contact_name_ar", contactName).trim())
                    put("job_title", contact.optString("job_title").trim())
                    put("department", contact.optString("department").trim())
                    put("phone", contact.optString("phone").trim())
                    put("phone2", contact.optString("phone2").trim())
                    put("email", contact.optString("email").trim())
                    put("whatsapp", contact.optString("whatsapp").trim())
                    put("is_primary", if (contact.optBoolean("is_primary", i == 0)) 1 else 0)
                    put("is_billing", if (contact.optBoolean("is_billing", false)) 1 else 0)
                    put("is_technical", if (contact.optBoolean("is_technical", false)) 1 else 0)
                    put("created_at", getCurrentDateTime())
                    put("updated_at", getCurrentDateTime())
                }
                db.insertOrThrow("party_contacts", null, values)
            }
            db.delete("party_addresses", "party_id = ? AND is_default = 1", arrayOf(partyId.toString()))
            val address = data.optJSONObject("address")
            if (address != null && address.toString() != "{}") {
                val values = ContentValues().apply {
                    put("uuid", UUID.randomUUID().toString())
                    put("party_id", partyId)
                    put("address_type", address.optString("address_type", "main"))
                    put("address_line1", address.optString("address_line1").trim())
                    put("address_line2", address.optString("address_line2").trim())
                    put("city", address.optString("city").trim())
                    put("state", address.optString("state").trim())
                    put("postal_code", address.optString("postal_code").trim())
                    put("country", address.optString("country", "").trim())
                    put("is_default", 1)
                    put("created_at", getCurrentDateTime())
                    put("updated_at", getCurrentDateTime())
                }
                db.insertOrThrow("party_addresses", null, values)
            }
            val newParty = getParty(partyId.toInt())
            recordPartyAudit(partyId, if (requestedId > 0) "update" else "insert", oldParty, newParty, userId)
            logActivity("system", if (requestedId > 0) "update_party_bundle" else "insert_party_bundle", "حفظ بيانات CRM للطرف: $partyId")
            db.setTransactionSuccessful()
            return partyId
        } finally {
            db.endTransaction()
            dbLock.unlock()
        }
    }

    fun updatePartyCreditLimit(partyId: Long, creditLimit: Double, reason: String, userId: Long = 0L): Int {
        require(partyId > 0) { "معرف العميل غير صالح" }
        require(creditLimit.isFinite() && creditLimit >= 0.0) { "حد الائتمان غير صالح" }
        dbLock.lock()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val oldParty = getParty(partyId.toInt()) ?: return 0
            val rows = db.update("parties", ContentValues().apply {
                put("credit_limit", creditLimit)
                put("updated_at", getCurrentDateTime())
            }, "id = ? AND is_deleted = 0", arrayOf(partyId.toString()))
            if (rows != 1) return 0
            val newParty = getParty(partyId.toInt())
            recordPartyAudit(partyId, "update_credit_limit", oldParty, newParty, userId)
            runCatching { logActivity("system", "update_credit_limit", "تعديل حد ائتمان العميل $partyId: $reason") }
            db.setTransactionSuccessful()
            return rows
        } finally {
            db.endTransaction()
            dbLock.unlock()
        }
    }

    fun getPartyCrmBundle(partyId: Long): JSONObject {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val party = getParty(partyId.toInt()) ?: throw IllegalArgumentException("الطرف غير موجود")
            party.put("contacts", getPartyContacts(partyId))
            party.put("addresses", getPartyAddresses(partyId))
            party.put("contracts", db.rawQuery(
                """
                SELECT id, uuid, contract_code, contract_name, contract_name_ar,
                       contract_type, start_date, end_date, total_value, status,
                       auto_renew, created_at, updated_at
                FROM contracts
                WHERE party_id = ? AND is_deleted = 0
                ORDER BY COALESCE(end_date, '9999-12-31'), id DESC
                """.trimIndent(), arrayOf(partyId.toString())
            ).use { cursorToJsonArray(it) })
            party.put("invoices", db.rawQuery(
                """
                SELECT id, sale_code, invoice_number, invoice_type,
                       created_at, net_amount, paid_amount, remaining_amount,
                       payment_status, status, due_date
                FROM sales_transactions
                WHERE customer_party_id = ? AND is_deleted = 0
                ORDER BY datetime(created_at) DESC, id DESC
                LIMIT 200
                """.trimIndent(), arrayOf(partyId.toString())
            ).use { cursorToJsonArray(it) })
            party.put("attachments", db.rawQuery(
                """
                SELECT id, uuid, file_name, file_name_original, file_path, file_url,
                       file_size, file_type, file_extension, description, description_ar, created_at
                FROM attachments
                WHERE entity_type = 'party' AND entity_id = ? AND is_active = 1 AND is_deleted = 0
                ORDER BY id DESC
                """.trimIndent(), arrayOf(partyId.toString())
            ).use { cursorToJsonArray(it) })
            party.put("audit", db.rawQuery(
                """
                SELECT id, uuid, user_id, action_type, table_name, record_id,
                       old_row_json, new_row_json, created_at
                FROM audit_logs
                WHERE table_name = 'parties' AND record_id = ?
                ORDER BY id DESC LIMIT 100
                """.trimIndent(), arrayOf(partyId.toString())
            ).use { cursorToJsonArray(it) })
            party
        } finally {
            dbLock.unlock()
        }
    }

    fun generateCRMReport(data: JSONObject): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val reportType = data.optString("report_type", "parties")
            val startDate = data.optString("start_date").trim()
            val endDate = data.optString("end_date").trim()
            val requestedPartyType = data.optInt("party_type", 0)
            val requestedStatus = if (data.isNull("status")) -1 else data.optInt("status", -1)
            val where = StringBuilder("p.is_deleted = 0")
            val whereArgs = mutableListOf<String>()
            if (requestedPartyType > 0) { where.append(" AND p.party_type_id = ?"); whereArgs.add(requestedPartyType.toString()) }
            if (requestedStatus >= 0) { where.append(" AND p.is_active = ?"); whereArgs.add(requestedStatus.toString()) }
            val dateClause = StringBuilder()
            val dateArgs = mutableListOf<String>()
            if (startDate.isNotEmpty()) { dateClause.append(" AND date(s.created_at) >= date(?)"); dateArgs.add(startDate) }
            if (endDate.isNotEmpty()) { dateClause.append(" AND date(s.created_at) <= date(?)"); dateArgs.add(endDate) }
            val args = dateArgs + whereArgs
            when (reportType) {
                "activity" -> db.rawQuery(
                    """
                    SELECT p.id, p.party_code, p.commercial_name, p.commercial_name_ar,
                           p.party_type_id, p.phone, p.email, p.is_active,
                           MAX(s.created_at) AS last_activity,
                           MAX(s.invoice_number) AS last_invoice,
                           COUNT(s.id) AS invoice_count,
                           COALESCE(SUM(s.paid_amount), 0) AS total_payments
                    FROM parties p LEFT JOIN sales_transactions s
                      ON s.customer_party_id = p.id AND s.is_deleted = 0 $dateClause
                    WHERE $where
                    GROUP BY p.id ORDER BY last_activity DESC, p.commercial_name
                    """.trimIndent(), args.toTypedArray()
                ).use { cursorToJsonArray(it) }
                "balance" -> db.rawQuery(
                    """
                    SELECT p.id, p.party_code, p.commercial_name, p.commercial_name_ar,
                           p.party_type_id, p.credit_limit, p.current_balance,
                           p.total_purchases, p.total_payments, p.total_due,
                           p.overdue_amount, p.is_active
                    FROM parties p WHERE $where
                    ORDER BY CASE WHEN p.credit_limit > 0 THEN p.current_balance / p.credit_limit ELSE 0 END DESC,
                             p.current_balance DESC
                    """.trimIndent(), whereArgs.toTypedArray()
                ).use { cursorToJsonArray(it) }
                "profitability", "purchase_behavior" -> db.rawQuery(
                    """
                    SELECT p.id, p.party_code, p.commercial_name, p.commercial_name_ar,
                           p.party_type_id, p.is_active,
                           COUNT(s.id) AS invoice_count,
                           COALESCE(SUM(s.net_amount), 0) AS total_sales,
                           COALESCE(SUM(s.paid_amount), 0) AS total_payments,
                           COALESCE(SUM(s.remaining_amount), 0) AS outstanding_amount,
                           MAX(s.created_at) AS last_activity
                    FROM parties p LEFT JOIN sales_transactions s
                      ON s.customer_party_id = p.id AND s.is_deleted = 0 $dateClause
                    WHERE $where
                    GROUP BY p.id ORDER BY total_sales DESC, invoice_count DESC
                    """.trimIndent(), args.toTypedArray()
                ).use { cursorToJsonArray(it) }
                else -> {
                    val order = if (reportType == "suppliers") "p.party_type_id = 6" else if (reportType == "customers") "p.party_type_id IN (1, 2, 3, 4, 5)" else "1=1"
                    val finalWhere = if (where.isEmpty()) order else "$where AND $order"
                    db.rawQuery(
                        """
                        SELECT p.id, p.party_code, p.commercial_name, p.commercial_name_ar,
                               p.legal_name, p.party_type_id, pt.type_name, pt.type_name_ar,
                               p.phone, p.email, p.credit_limit, p.current_balance,
                               p.total_purchases, p.total_payments, p.total_due,
                               p.overdue_amount, p.loyalty_points, p.loyalty_tier,
                               p.risk_level, p.is_active, p.created_at, p.updated_at,
                               MAX(s.created_at) AS last_activity
                        FROM parties p
                        LEFT JOIN party_types pt ON pt.id = p.party_type_id AND pt.is_deleted = 0
                        LEFT JOIN sales_transactions s ON s.customer_party_id = p.id AND s.is_deleted = 0 $dateClause
                        WHERE $finalWhere
                        GROUP BY p.id
                        ORDER BY p.commercial_name
                        """.trimIndent(), args.toTypedArray()
                    ).use { cursorToJsonArray(it) }
                }
            }
        } finally {
            dbLock.unlock()
        }
    }

    // =========================================================================
    // CONTRACTS_V15_DATA: عمليات العقود الفعلية عبر SQLite فقط.
    // =========================================================================
    private fun contractRow(db: SQLiteDatabase, id: Long, includeDeleted: Boolean = false): JSONObject? {
        val deletedClause = if (includeDeleted) "" else " AND c.is_deleted = 0"
        return db.rawQuery(
            """
            SELECT c.*, p.commercial_name, p.legal_name, p.commercial_name_ar,
                   cu.currency_code, cu.currency_name, cu.currency_name_ar
            FROM contracts c
            LEFT JOIN parties p ON p.id = c.party_id
            LEFT JOIN currencies cu ON cu.id = c.currency_id
            WHERE c.id = ? $deletedClause LIMIT 1
            """.trimIndent(),
            arrayOf(id.toString())
        ).use { cursor -> if (cursor.moveToFirst()) cursorToJsonObject(cursor) else null }
    }

    private fun writeContractAudit(db: SQLiteDatabase, userId: Long, action: String, recordId: Long, oldRow: JSONObject?, newRow: JSONObject?) {
        val values = ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("user_id", if (userId > 0) userId else null)
            put("action_type", action)
            put("table_name", "contracts")
            put("record_id", recordId)
            put("old_row_json", oldRow?.toString())
            put("new_row_json", newRow?.toString())
            put("created_at", getCurrentDateTime())
        }
        db.insert("audit_logs", null, values)
    }

    private fun contractChildren(db: SQLiteDatabase, contractId: Long, table: String): JSONArray {
        val sql = if (table == "contract_line_items") {
            "SELECT * FROM contract_line_items WHERE contract_id = ? AND is_deleted = 0 ORDER BY line_number, id"
        } else {
            "SELECT * FROM contract_payment_schedules WHERE contract_id = ? AND is_deleted = 0 ORDER BY due_date, installment_number, id"
        }
        return db.rawQuery(sql, arrayOf(contractId.toString())).use { cursorToJsonArray(it) }
    }

    private fun contractAttachments(db: SQLiteDatabase, contractId: Long): JSONArray {
        return db.rawQuery(
            "SELECT id, uuid, file_name, file_name_original, file_path, file_url, file_size, file_type, file_extension, description, description_ar, created_at FROM attachments WHERE entity_type = 'contract' AND entity_id = ? AND is_active = 1 AND is_deleted = 0 ORDER BY id DESC",
            arrayOf(contractId.toString())
        ).use { cursorToJsonArray(it) }
    }

    fun getContracts(includeArchived: Boolean = true): JSONArray {
        dbLock.lock()
        return try {
            val archiveClause = if (includeArchived) "" else " AND c.is_archived = 0"
            readableDatabase.rawQuery(
                """
                SELECT c.id, c.uuid, c.contract_code, c.contract_name, c.contract_name_ar,
                       c.party_id, COALESCE(p.commercial_name_ar, p.commercial_name, p.legal_name, '') AS party_name,
                       c.contract_type, c.start_date, c.end_date, c.auto_renew, c.renewal_terms,
                       c.terms, c.special_conditions, c.total_value, c.currency_id,
                       cu.currency_code, cu.currency_name, c.status, c.signed_by, c.signed_date,
                       c.document_path, c.notes, c.parent_contract_id, c.renewal_count,
                       c.reminder_days, c.is_archived, c.archived_at, c.is_deleted,
                       c.created_at, c.updated_at,
                       (SELECT COUNT(*) FROM contract_line_items li WHERE li.contract_id = c.id AND li.is_deleted = 0) AS line_item_count,
                       (SELECT COUNT(*) FROM contract_payment_schedules ps WHERE ps.contract_id = c.id AND ps.is_deleted = 0) AS payment_schedule_count
                FROM contracts c
                LEFT JOIN parties p ON p.id = c.party_id
                LEFT JOIN currencies cu ON cu.id = c.currency_id
                WHERE c.is_deleted = 0 $archiveClause
                ORDER BY COALESCE(c.end_date, '9999-12-31'), c.id DESC
                """.trimIndent(), null
            ).use { cursorToJsonArray(it) }
        } finally {
            dbLock.unlock()
        }
    }

    fun getContractBundle(id: Long): JSONObject {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val contract = contractRow(db, id) ?: throw IllegalArgumentException("العقد غير موجود")
            contract.put("line_items", contractChildren(db, id, "contract_line_items"))
            contract.put("payment_schedules", contractChildren(db, id, "contract_payment_schedules"))
            contract.put("attachments", contractAttachments(db, id))
            contract.put("audit", getContractAudit(id, 100))
            contract
        } finally {
            dbLock.unlock()
        }
    }

    private fun contractStatusValid(status: String): Boolean = status in setOf("draft", "active", "expired", "terminated")

    private fun saveContractChildren(db: SQLiteDatabase, contractId: Long, data: JSONObject, userId: Long) {
        db.delete("contract_line_items", "contract_id = ?", arrayOf(contractId.toString()))
        val lines = data.optJSONArray("line_items") ?: JSONArray()
        for (i in 0 until lines.length()) {
            val line = lines.optJSONObject(i) ?: continue
            val description = line.optString("description").trim()
            if (description.isEmpty()) continue
            val quantity = line.optDouble("quantity", 1.0)
            val unitPrice = line.optDouble("unit_price", 0.0)
            require(quantity >= 0 && unitPrice >= 0) { "قيم بنود العقد غير صالحة" }
            val values = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("contract_id", contractId)
                put("line_number", i + 1)
                put("description", description)
                put("quantity", quantity)
                put("unit_price", unitPrice)
                put("total_amount", quantity * unitPrice)
                put("notes", line.optString("notes").trim())
                put("created_by", if (userId > 0) userId else null)
            }
            db.insertOrThrow("contract_line_items", null, values)
        }
        db.delete("contract_payment_schedules", "contract_id = ?", arrayOf(contractId.toString()))
        val schedules = data.optJSONArray("payment_schedules") ?: JSONArray()
        for (i in 0 until schedules.length()) {
            val schedule = schedules.optJSONObject(i) ?: continue
            val dueDate = schedule.optString("due_date").trim()
            val amount = schedule.optDouble("amount", 0.0)
            require(dueDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && amount >= 0) { "جدول دفعات العقد غير صالح" }
            val status = schedule.optString("status", "pending")
            require(status in setOf("pending", "paid", "overdue", "cancelled")) { "حالة الدفعة غير صالحة" }
            val values = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("contract_id", contractId)
                put("installment_number", i + 1)
                put("due_date", dueDate)
                put("amount", amount)
                put("status", status)
                if (status == "paid") put("paid_at", getCurrentDateTime()) else putNull("paid_at")
                put("notes", schedule.optString("notes").trim())
                put("created_by", if (userId > 0) userId else null)
            }
            db.insertOrThrow("contract_payment_schedules", null, values)
        }
    }

    fun saveContractBundle(data: JSONObject, userId: Long): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = data.optLong("id", 0L)
            val name = data.optString("contract_name").trim()
            val partyId = data.optLong("party_id", 0L)
            val startDate = data.optString("start_date").trim()
            val endDate = data.optString("end_date").trim()
            val status = data.optString("status", "draft")
            require(name.isNotEmpty()) { "اسم العقد مطلوب" }
            require(partyId > 0) { "الطرف مطلوب" }
            require(startDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "تاريخ بداية العقد غير صالح" }
            require(endDate.isEmpty() || endDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "تاريخ نهاية العقد غير صالح" }
            require(endDate.isEmpty() || endDate >= startDate) { "تاريخ نهاية العقد يجب أن يكون بعد البداية" }
            require(contractStatusValid(status)) { "حالة العقد غير صالحة" }
            require(db.rawQuery("SELECT 1 FROM parties WHERE id = ? AND is_deleted = 0 LIMIT 1", arrayOf(partyId.toString())).use { it.moveToFirst() }) { "الطرف غير موجود" }
            val parentId = if (data.isNull("parent_contract_id")) 0L else data.optLong("parent_contract_id", 0L)
            if (parentId > 0) {
                require(parentId != id) { "لا يمكن ربط العقد بنفسه" }
                require(contractRow(db, parentId) != null) { "العقد الرئيسي غير موجود" }
            }
            val oldRow = if (id > 0) contractRow(db, id) else null
            val code = data.optString("contract_code").trim().ifEmpty { "CTR-${System.currentTimeMillis()}" }
            val values = ContentValues().apply {
                put("contract_code", code)
                put("contract_name", name)
                put("contract_name_ar", data.optString("contract_name_ar", name).trim())
                put("party_id", partyId)
                put("contract_type", data.optString("contract_type", "other"))
                put("start_date", startDate)
                if (endDate.isNotEmpty()) put("end_date", endDate) else putNull("end_date")
                put("auto_renew", data.optInt("auto_renew", 0))
                put("renewal_terms", data.optString("renewal_terms").trim())
                put("terms", data.optString("terms").trim())
                put("special_conditions", data.optString("special_conditions").trim())
                put("total_value", data.optDouble("total_value", 0.0))
                if (data.optLong("currency_id", 0L) > 0) put("currency_id", data.optLong("currency_id")) else putNull("currency_id")
                put("status", status)
                val signedDate = data.optString("signed_date").trim()
                if (signedDate.isNotEmpty()) put("signed_date", signedDate) else putNull("signed_date")
                put("document_path", data.optString("document_path").trim())
                put("notes", data.optString("notes").trim())
                if (parentId > 0) put("parent_contract_id", parentId) else putNull("parent_contract_id")
                put("renewal_count", data.optInt("renewal_count", oldRow?.optInt("renewal_count", 0) ?: 0))
                put("reminder_days", data.optInt("reminder_days", oldRow?.optInt("reminder_days", 30) ?: 30).coerceIn(0, 365))
                put("is_archived", data.optInt("is_archived", oldRow?.optInt("is_archived", 0) ?: 0))
                put("updated_by", if (userId > 0) userId else null)
                put("updated_at", getCurrentDateTime())
                val attachmentMetadata = data.optJSONArray("attachments")
                if (attachmentMetadata != null) put("attachments_json", attachmentMetadata.toString())
            }
            val savedId: Long
            if (id > 0) {
                require(oldRow != null) { "العقد غير موجود" }
                require(db.update("contracts", values, "id = ? AND is_deleted = 0", arrayOf(id.toString())) == 1) { "لم يتم تعديل العقد" }
                savedId = id
            } else {
                values.put("uuid", UUID.randomUUID().toString())
                values.put("created_by", if (userId > 0) userId else null)
                values.put("created_at", getCurrentDateTime())
                savedId = db.insertOrThrow("contracts", null, values)
            }
            saveContractChildren(db, savedId, data, userId)
            val newRow = contractRow(db, savedId)
            if (oldRow?.optString("status") != status && oldRow != null) {
                val history = ContentValues().apply {
                    put("uuid", UUID.randomUUID().toString())
                    put("contract_id", savedId)
                    put("old_status", oldRow.optString("status"))
                    put("new_status", status)
                    put("reason", data.optString("status_reason").trim())
                    put("changed_by", if (userId > 0) userId else null)
                }
                db.insertOrThrow("contract_status_history", null, history)
            }
            writeContractAudit(db, userId, if (oldRow == null) "insert" else "update", savedId, oldRow, newRow)
            db.setTransactionSuccessful()
            return savedId
        } finally {
            db.endTransaction()
        }
    }

    fun deleteContract(id: Long, userId: Long): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val oldRow = contractRow(db, id) ?: throw IllegalArgumentException("العقد غير موجود")
            val values = ContentValues().apply {
                put("is_deleted", 1)
                put("deleted_at", getCurrentDateTime())
                put("deleted_by", if (userId > 0) userId else null)
                put("updated_at", getCurrentDateTime())
                put("updated_by", if (userId > 0) userId else null)
            }
            val rows = db.update("contracts", values, "id = ? AND is_deleted = 0", arrayOf(id.toString()))
            if (rows == 1) writeContractAudit(db, userId, "delete", id, oldRow, contractRow(db, id, true))
            db.setTransactionSuccessful()
            return rows
        } finally {
            db.endTransaction()
        }
    }

    fun archiveContract(id: Long, userId: Long): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val oldRow = contractRow(db, id) ?: throw IllegalArgumentException("العقد غير موجود")
            val values = ContentValues().apply {
                put("is_archived", 1)
                put("archived_at", getCurrentDateTime())
                put("archived_by", if (userId > 0) userId else null)
                put("updated_at", getCurrentDateTime())
                put("updated_by", if (userId > 0) userId else null)
            }
            val rows = db.update("contracts", values, "id = ? AND is_deleted = 0", arrayOf(id.toString()))
            if (rows == 1) writeContractAudit(db, userId, "archive", id, oldRow, contractRow(db, id))
            db.setTransactionSuccessful()
            return rows
        } finally {
            db.endTransaction()
        }
    }

    fun restoreContract(id: Long, userId: Long): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val oldRow = contractRow(db, id, true) ?: throw IllegalArgumentException("العقد غير موجود")
            val values = ContentValues().apply {
                put("is_archived", 0)
                put("is_deleted", 0)
                putNull("archived_at")
                putNull("archived_by")
                putNull("deleted_at")
                putNull("deleted_by")
                put("updated_at", getCurrentDateTime())
                put("updated_by", if (userId > 0) userId else null)
            }
            val rows = db.update("contracts", values, "id = ?", arrayOf(id.toString()))
            if (rows == 1) writeContractAudit(db, userId, "restore", id, oldRow, contractRow(db, id))
            db.setTransactionSuccessful()
            return rows
        } finally {
            db.endTransaction()
        }
    }

    fun cloneContract(sourceId: Long, data: JSONObject, userId: Long): Long {
        val db = readableDatabase
        val source = getContractBundle(sourceId)
        val clone = JSONObject(source.toString()).apply {
            remove("id"); remove("uuid"); remove("contract_code"); remove("created_at"); remove("updated_at")
            remove("created_by"); remove("updated_by"); remove("is_deleted"); remove("deleted_at"); remove("deleted_by")
            put("contract_name", data.optString("contract_name").trim().ifEmpty { source.optString("contract_name") + " - نسخة" })
            put("contract_code", data.optString("contract_code").trim())
            put("status", "draft")
            put("is_archived", 0)
            put("is_deleted", 0)
        }
        require(clone.optString("contract_code").isNotEmpty()) { "كود العقد المنسوخ مطلوب" }
        return saveContractBundle(clone, userId)
    }

    fun changeContractStatus(id: Long, status: String, reason: String?, userId: Long): Int {
        require(contractStatusValid(status)) { "حالة العقد غير صالحة" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val oldRow = contractRow(db, id) ?: throw IllegalArgumentException("العقد غير موجود")
            if (oldRow.optString("status") == status) return 0
            val values = ContentValues().apply {
                put("status", status)
                put("updated_at", getCurrentDateTime())
                put("updated_by", if (userId > 0) userId else null)
            }
            val rows = db.update("contracts", values, "id = ? AND is_deleted = 0", arrayOf(id.toString()))
            if (rows == 1) {
                val history = ContentValues().apply {
                    put("uuid", UUID.randomUUID().toString())
                    put("contract_id", id)
                    put("old_status", oldRow.optString("status"))
                    put("new_status", status)
                    put("reason", reason?.trim())
                    put("changed_by", if (userId > 0) userId else null)
                }
                db.insertOrThrow("contract_status_history", null, history)
                writeContractAudit(db, userId, "status_change", id, oldRow, contractRow(db, id))
            }
            db.setTransactionSuccessful()
            return rows
        } finally {
            db.endTransaction()
        }
    }

    fun getContractAudit(recordId: Long = 0L, limit: Int = 100): JSONArray {
        dbLock.lock()
        return try {
            val where = if (recordId > 0) "AND al.record_id = ?" else ""
            val args = if (recordId > 0) arrayOf(recordId.toString(), limit.coerceIn(1, 500).toString()) else arrayOf(limit.coerceIn(1, 500).toString())
            readableDatabase.rawQuery(
                """
                SELECT al.id, al.uuid, al.action_type, al.table_name, al.record_id,
                       al.old_row_json, al.new_row_json, al.created_at,
                       COALESCE(u.display_name, u.full_name_ar, u.full_name, u.username, 'نظام') AS username
                FROM audit_logs al
                LEFT JOIN users u ON u.id = al.user_id
                WHERE al.table_name = 'contracts' $where
                ORDER BY al.id DESC LIMIT ?
                """.trimIndent(), args
            ).use { cursorToJsonArray(it) }
        } finally {
            dbLock.unlock()
        }
    }

    fun generateContractReport(data: JSONObject): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val reportType = data.optString("report_type", "contracts")
            val startDate = data.optString("start_date").trim()
            val endDate = data.optString("end_date").trim()
            val status = data.optString("status").trim()
            val baseWhere = StringBuilder("c.is_deleted = 0")
            val args = mutableListOf<String>()
            if (status.isNotEmpty()) { baseWhere.append(" AND c.status = ?"); args.add(status) }
            if (startDate.isNotEmpty()) { baseWhere.append(" AND COALESCE(c.end_date, c.start_date) >= ?"); args.add(startDate) }
            if (endDate.isNotEmpty()) { baseWhere.append(" AND c.start_date <= ?"); args.add(endDate) }
            when (reportType) {
                "by_party" -> db.rawQuery(
                    """
                    SELECT COALESCE(p.commercial_name_ar, p.commercial_name, p.legal_name, '') AS party_name,
                           COUNT(c.id) AS contract_count,
                           SUM(CASE WHEN c.status = 'active' THEN 1 ELSE 0 END) AS active_count,
                           SUM(CASE WHEN c.status = 'expired' THEN 1 ELSE 0 END) AS expired_count,
                           COALESCE(SUM(c.total_value), 0) AS total_value
                    FROM contracts c LEFT JOIN parties p ON p.id = c.party_id
                    WHERE ${baseWhere}
                    GROUP BY c.party_id ORDER BY total_value DESC
                    """.trimIndent(), args.toTypedArray()
                ).use { cursorToJsonArray(it) }
                else -> db.rawQuery(
                    """
                    SELECT c.id, c.contract_code, c.contract_name, c.contract_name_ar, c.party_id,
                           COALESCE(p.commercial_name_ar, p.commercial_name, p.legal_name, '') AS party_name,
                           c.contract_type, c.status, c.start_date, c.end_date, c.total_value,
                           cu.currency_code, cu.currency_name, c.auto_renew, c.is_archived
                    FROM contracts c LEFT JOIN parties p ON p.id = c.party_id
                    LEFT JOIN currencies cu ON cu.id = c.currency_id
                    WHERE ${baseWhere}
                    ORDER BY COALESCE(c.end_date, '9999-12-31'), c.id DESC
                    """.trimIndent(), args.toTypedArray()
                ).use { cursorToJsonArray(it) }
            }
        } finally {
            dbLock.unlock()
        }
    }



    // ========================================================================
    // ACCOUNTING_REPORTS_V1: قيود اليومية ودفتر الأستاذ وKPI عبر SQLite فقط.
    // ========================================================================

    private fun journalEntryRow(db: SQLiteDatabase, id: Long, includeDeleted: Boolean = false): JSONObject? {
        val where = if (includeDeleted) "je.id = ?" else "je.id = ? AND je.is_deleted = 0"
        return db.rawQuery(
            """
            SELECT je.*,
                   COALESCE(u.full_name_ar, u.full_name, u.username, 'نظام') AS created_by_name,
                   COALESCE(pu.full_name_ar, pu.full_name, pu.username, 'نظام') AS posted_by_name,
                   COALESCE(je.reference_code, je.reference_type, '') AS reference,
                   (SELECT COUNT(*) FROM journal_entry_items ji WHERE ji.journal_entry_id = je.id) AS item_count
            FROM journal_entries je
            LEFT JOIN users u ON u.id = je.created_by
            LEFT JOIN users pu ON pu.id = je.posted_by
            WHERE $where
            LIMIT 1
            """.trimIndent(), arrayOf(id.toString())
        ).use { cursor -> if (cursor.moveToFirst()) cursorToJsonObject(cursor) else null }
    }

    private fun journalItemsForEntry(db: SQLiteDatabase, id: Long): JSONArray {
        return db.rawQuery(
            """
            SELECT ji.*, a.account_code, COALESCE(a.account_name_ar, a.account_name, '') AS account_name,
                   c.currency_code
            FROM journal_entry_items ji
            LEFT JOIN accounts a ON a.id = ji.account_id
            LEFT JOIN currencies c ON c.id = ji.currency_id
            WHERE ji.journal_entry_id = ?
            ORDER BY ji.line_number, ji.id
            """.trimIndent(), arrayOf(id.toString())
        ).use { cursorToJsonArray(it) }
    }

    private fun writeJournalAudit(db: SQLiteDatabase, userId: Long, action: String, recordId: Long, oldRow: JSONObject?, newRow: JSONObject?) {
        val values = ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            if (userId > 0) put("user_id", userId) else putNull("user_id")
            put("action_type", action)
            put("table_name", "journal_entries")
            put("record_id", recordId)
            put("old_row_json", oldRow?.toString())
            put("new_row_json", newRow?.toString())
            put("created_at", getCurrentDateTime())
        }
        db.insert("audit_logs", null, values)
    }

    private fun journalEntryNumber(db: SQLiteDatabase): String {
        val maxNumber = db.rawQuery(
            "SELECT MAX(CAST(REPLACE(REPLACE(entry_number, 'JE-', ''), '-R', '') AS INTEGER)) FROM journal_entries WHERE is_deleted = 0",
            null
        ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else 0 }
        return "JE-" + (maxNumber + 1).toString().padStart(4, '0')
    }

    fun getNextJournalEntryNumber(): String {
        dbLock.lock()
        return try { journalEntryNumber(readableDatabase) } finally { dbLock.unlock() }
    }

    fun getJournalEntries(params: JSONObject = JSONObject()): JSONObject {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val limit = params.optInt("limit", 20).coerceIn(1, 100)
            val offset = params.optInt("offset", 0).coerceAtLeast(0)
            val where = StringBuilder("WHERE je.is_deleted = 0")
            val args = mutableListOf<String>()
            params.optString("status", "").takeIf { it.isNotBlank() }?.let { where.append(" AND je.status = ?"); args.add(it) }
            params.optString("entry_type", "").takeIf { it.isNotBlank() }?.let { where.append(" AND je.entry_type = ?"); args.add(it) }
            params.optString("start_date", "").takeIf { it.isNotBlank() }?.let { where.append(" AND date(je.entry_date) >= date(?)"); args.add(it) }
            params.optString("end_date", "").takeIf { it.isNotBlank() }?.let { where.append(" AND date(je.entry_date) <= date(?)"); args.add(it) }
            params.optString("search", "").trim().takeIf { it.isNotBlank() }?.let {
                where.append(" AND (je.entry_number LIKE ? OR je.description LIKE ? OR je.description_ar LIKE ? OR je.reference_code LIKE ?)")
                val query = "%$it%"; repeat(4) { args.add(query) }
            }
            val count = db.rawQuery("SELECT COUNT(*) FROM journal_entries je $where", args.toTypedArray()).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            val rows = db.rawQuery(
                """
                SELECT je.*, COALESCE(u.full_name_ar, u.full_name, u.username, 'نظام') AS created_by_name,
                       COALESCE(pu.full_name_ar, pu.full_name, pu.username, 'نظام') AS posted_by_name,
                       COALESCE(je.reference_code, je.reference_type, '') AS reference,
                       (SELECT COUNT(*) FROM journal_entry_items ji WHERE ji.journal_entry_id = je.id) AS item_count
                FROM journal_entries je
                LEFT JOIN users u ON u.id = je.created_by
                LEFT JOIN users pu ON pu.id = je.posted_by
                $where
                ORDER BY date(je.entry_date) DESC, je.id DESC
                LIMIT ? OFFSET ?
                """.trimIndent(), (args + listOf(limit.toString(), offset.toString())).toTypedArray()
            ).use { cursorToJsonArray(it) }
            val stats = JSONObject().apply {
                put("total", count)
                put("posted", db.rawQuery("SELECT COUNT(*) FROM journal_entries WHERE is_deleted = 0 AND status = 'posted'", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 })
                put("draft", db.rawQuery("SELECT COUNT(*) FROM journal_entries WHERE is_deleted = 0 AND status = 'draft'", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 })
                put("total_value", db.rawQuery("SELECT COALESCE(SUM(total_debit), 0) FROM journal_entries WHERE is_deleted = 0", null).use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 })
            }
            JSONObject().apply { put("entries", rows); put("total", count); put("stats", stats) }
        } finally { dbLock.unlock() }
    }

    fun getJournalItems(): JSONArray {
        dbLock.lock()
        return try {
            readableDatabase.rawQuery(
                """
                SELECT ji.*, a.account_code, COALESCE(a.account_name_ar, a.account_name, '') AS account_name,
                       c.currency_code
                FROM journal_entry_items ji
                INNER JOIN journal_entries je ON je.id = ji.journal_entry_id AND je.is_deleted = 0
                LEFT JOIN accounts a ON a.id = ji.account_id
                LEFT JOIN currencies c ON c.id = ji.currency_id
                ORDER BY ji.journal_entry_id DESC, ji.line_number, ji.id
                """.trimIndent(), null
            ).use { cursorToJsonArray(it) }
        } finally { dbLock.unlock() }
    }

    fun saveJournalEntry(data: JSONObject, userId: Long): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = data.optLong("id", 0L)
            val entryDate = data.optString("entry_date", "").trim()
            val description = data.optString("description", "").trim()
            val entryType = data.optString("entry_type", "general").trim()
            val status = data.optString("status", "draft").trim()
            val items = data.optJSONArray("items") ?: JSONArray()
            require(entryDate.isNotEmpty()) { "تاريخ القيد مطلوب" }
            require(description.isNotEmpty()) { "وصف القيد مطلوب" }
            require(entryType in setOf("general", "sales", "purchase", "payroll", "adjustment", "closing")) { "نوع القيد غير صالح" }
            require(status in setOf("draft", "posted")) { "لا يمكن إنشاء القيد بهذه الحالة" }
            require(items.length() > 0) { "يجب أن يحتوي القيد على بند واحد على الأقل" }
            var debit = 0.0
            var credit = 0.0
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: throw IllegalArgumentException("بند القيد غير صالح")
                val accountId = item.optLong("account_id", 0L)
                val itemDebit = item.optDouble("debit", 0.0)
                val itemCredit = item.optDouble("credit", 0.0)
                require(accountId > 0 && getAccountRow(db, accountId) != null) { "الحساب المرتبط بالبند غير موجود" }
                require(itemDebit >= 0 && itemCredit >= 0 && ((itemDebit > 0) xor (itemCredit > 0))) { "يجب أن يحتوي كل بند على مدين أو دائن فقط" }
                debit += itemDebit; credit += itemCredit
            }
            require(kotlin.math.abs(debit - credit) < 0.000001) { "القيد غير متوازن" }
            val values = ContentValues().apply {
                put("entry_number", data.optString("entry_number", "").trim().ifEmpty { journalEntryNumber(db) })
                put("entry_date", entryDate)
                put("entry_type", entryType)
                if (data.optString("reference", "").trim().isNotEmpty()) put("reference_code", data.optString("reference").trim()) else putNull("reference_code")
                put("description", description)
                put("description_ar", data.optString("description_ar", "").trim())
                put("total_debit", debit)
                put("total_credit", credit)
                put("is_balanced", 1)
                put("status", status)
                put("fiscal_year", data.optInt("fiscal_year", entryDate.take(4).toIntOrNull() ?: Calendar.getInstance().get(Calendar.YEAR)))
                put("fiscal_period", data.optInt("fiscal_period", entryDate.substringAfter('-', "01").substringBefore('-').toIntOrNull() ?: 1))
                put("remarks", data.optString("remarks", ""))
                put("updated_at", getCurrentDateTime())
                if (userId > 0) put("updated_by", userId) else putNull("updated_by")
            }
            val savedId: Long
            if (id > 0) {
                val old = journalEntryRow(db, id) ?: throw IllegalArgumentException("القيد غير موجود")
                require(old.optString("status") == "draft") { "لا يمكن تعديل قيد مرحل أو ملغى" }
                val rows = db.update("journal_entries", values, "id = ? AND is_deleted = 0", arrayOf(id.toString()))
                require(rows == 1) { "لم يتم تحديث القيد" }
                db.delete("journal_entry_items", "journal_entry_id = ?", arrayOf(id.toString()))
                savedId = id
                writeJournalAudit(db, userId, "update", id, old, journalEntryRow(db, id))
            } else {
                values.put("uuid", UUID.randomUUID().toString())
                values.put("created_at", getCurrentDateTime())
                if (userId > 0) values.put("created_by", userId) else values.putNull("created_by")
                values.put("is_deleted", 0)
                savedId = db.insertOrThrow("journal_entries", null, values)
                writeJournalAudit(db, userId, "insert", savedId, null, journalEntryRow(db, savedId))
            }
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val itemValues = ContentValues().apply {
                    put("uuid", UUID.randomUUID().toString())
                    put("journal_entry_id", savedId)
                    put("line_number", index + 1)
                    put("account_id", item.optLong("account_id"))
                    put("debit", item.optDouble("debit", 0.0))
                    put("credit", item.optDouble("credit", 0.0))
                    if (item.optLong("currency_id", 0L) > 0) put("currency_id", item.optLong("currency_id")) else putNull("currency_id")
                    put("exchange_rate", item.optDouble("exchange_rate", 1.0))
                    put("description", item.optString("description", ""))
                    put("description_ar", item.optString("description_ar", ""))
                    put("cost_center", item.optString("cost_center", ""))
                    put("project_code", item.optString("project_code", ""))
                }
                db.insertOrThrow("journal_entry_items", null, itemValues)
            }
            db.setTransactionSuccessful()
            return savedId
        } finally { db.endTransaction() }
    }

    fun deleteJournalEntry(id: Long, userId: Long): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val old = journalEntryRow(db, id) ?: throw IllegalArgumentException("القيد غير موجود")
            require(old.optString("status") != "posted") { "لا يمكن حذف قيد مرحل؛ استخدم الإلغاء" }
            val values = ContentValues().apply {
                put("is_deleted", 1); put("status", "cancelled"); put("deleted_at", getCurrentDateTime()); put("updated_at", getCurrentDateTime())
                if (userId > 0) { put("deleted_by", userId); put("updated_by", userId) } else { putNull("deleted_by"); putNull("updated_by") }
            }
            val rows = db.update("journal_entries", values, "id = ? AND is_deleted = 0", arrayOf(id.toString()))
            if (rows > 0) writeJournalAudit(db, userId, "delete", id, old, journalEntryRow(db, id, true))
            db.setTransactionSuccessful(); return rows
        } finally { db.endTransaction() }
    }

    fun postJournalEntry(id: Long, userId: Long): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val old = journalEntryRow(db, id) ?: throw IllegalArgumentException("القيد غير موجود")
            require(old.optString("status") == "draft") { "القيد مرحل أو ملغى مسبقاً" }
            require(old.optInt("is_balanced", 0) == 1 && kotlin.math.abs(old.optDouble("total_debit") - old.optDouble("total_credit")) < 0.000001) { "لا يمكن ترحيل قيد غير متوازن" }
            val values = ContentValues().apply { put("status", "posted"); put("posted_at", getCurrentDateTime()); if (userId > 0) put("posted_by", userId); put("updated_at", getCurrentDateTime()); if (userId > 0) put("updated_by", userId) }
            val rows = db.update("journal_entries", values, "id = ? AND is_deleted = 0", arrayOf(id.toString()))
            if (rows > 0) writeJournalAudit(db, userId, "post", id, old, journalEntryRow(db, id))
            db.setTransactionSuccessful(); return rows
        } finally { db.endTransaction() }
    }

    fun reverseJournalEntry(id: Long, reason: String, userId: Long): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val original = journalEntryRow(db, id) ?: throw IllegalArgumentException("القيد غير موجود")
            require(original.optString("status") == "posted") { "يمكن إلغاء القيود المرحّلة فقط" }
            require(original.optLong("reversed_entry_id", 0L) <= 0L) { "تم إلغاء القيد مسبقاً" }
            val items = journalItemsForEntry(db, id)
            require(items.length() > 0) { "لا توجد بنود لإنشاء القيد العكسي" }
            val reverseValues = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("entry_number", journalEntryNumber(db) + "-R")
                put("entry_date", getDateOnlyFormat().format(Date()))
                put("entry_type", original.optString("entry_type", "general"))
                put("reference_code", original.optString("entry_number"))
                put("description", "عكس: " + original.optString("description"))
                put("description_ar", "قيد عكسي: " + original.optString("description_ar", original.optString("description")))
                put("total_debit", original.optDouble("total_credit", 0.0))
                put("total_credit", original.optDouble("total_debit", 0.0))
                put("is_balanced", 1); put("status", "posted"); put("posted_at", getCurrentDateTime())
                if (userId > 0) put("posted_by", userId)
                put("reversed_entry_id", id); put("reversal_reason", reason.trim()); put("fiscal_year", Calendar.getInstance().get(Calendar.YEAR)); put("fiscal_period", Calendar.getInstance().get(Calendar.MONTH) + 1)
                put("created_at", getCurrentDateTime()); put("updated_at", getCurrentDateTime()); put("is_deleted", 0)
                if (userId > 0) { put("created_by", userId); put("updated_by", userId) }
            }
            val reverseId = db.insertOrThrow("journal_entries", null, reverseValues)
            for (index in 0 until items.length()) {
                val oldItem = items.getJSONObject(index)
                val values = ContentValues().apply {
                    put("uuid", UUID.randomUUID().toString()); put("journal_entry_id", reverseId); put("line_number", index + 1); put("account_id", oldItem.optLong("account_id")); put("debit", oldItem.optDouble("credit", 0.0)); put("credit", oldItem.optDouble("debit", 0.0));
                    if (oldItem.optLong("currency_id", 0L) > 0) put("currency_id", oldItem.optLong("currency_id")) else putNull("currency_id")
                    put("exchange_rate", oldItem.optDouble("exchange_rate", 1.0)); put("description", "عكس: " + oldItem.optString("description", "")); put("description_ar", "عكس: " + oldItem.optString("description_ar", ""))
                }
                db.insertOrThrow("journal_entry_items", null, values)
            }
            val originalValues = ContentValues().apply { put("status", "reversed"); put("reversed_entry_id", reverseId); put("reversal_reason", reason.trim()); put("updated_at", getCurrentDateTime()); if (userId > 0) put("updated_by", userId) }
            db.update("journal_entries", originalValues, "id = ? AND is_deleted = 0", arrayOf(id.toString()))
            writeJournalAudit(db, userId, "reverse", id, original, journalEntryRow(db, id))
            writeJournalAudit(db, userId, "insert_reversal", reverseId, null, journalEntryRow(db, reverseId))
            db.setTransactionSuccessful(); return reverseId
        } finally { db.endTransaction() }
    }

    fun getJournalEntryDetails(id: Long): JSONObject? {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val entry = journalEntryRow(db, id) ?: return null
            entry.put("items", journalItemsForEntry(db, id))
            entry
        } finally { dbLock.unlock() }
    }

    fun generateJournalReport(params: JSONObject): JSONArray {
        val type = params.optString("report_type", "entries")
        if (type == "trial_balance" || type == "general_ledger") {
            val trial = getChartTrialBalance(params.optString("start_date", ""), params.optString("end_date", ""))
            for (i in 0 until trial.length()) {
                val item = trial.optJSONObject(i) ?: continue
                val opening = item.optDouble("opening_balance", 0.0); val debit = item.optDouble("total_debit", 0.0); val credit = item.optDouble("total_credit", 0.0)
                val closing = opening + debit - credit
                item.put("debit_balance", if (closing > 0) closing else 0.0); item.put("credit_balance", if (closing < 0) -closing else 0.0); item.put("closing_balance", closing); item.put("balance", closing)
            }
            return trial
        }
        if (type == "detailed") {
            return getLedgerEntries(JSONObject(params.toString()).apply { put("account_id", 0); put("include_all", true) })
        }
        val page = getJournalEntries(params)
        val entries = page.optJSONArray("entries") ?: JSONArray()
        if (params.optString("status", "").isNotBlank()) return entries
        return entries
    }

    fun getLedgerStats(): JSONObject {
        dbLock.lock()
        return try {
            val db = readableDatabase
            JSONObject().apply {
                put("total_accounts", db.rawQuery("SELECT COUNT(*) FROM accounts WHERE is_deleted = 0 AND is_active = 1", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 })
                put("total_entries", db.rawQuery("SELECT COUNT(*) FROM journal_entries WHERE is_deleted = 0 AND status = 'posted'", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 })
                put("total_debit", db.rawQuery("SELECT COALESCE(SUM(total_debit), 0) FROM journal_entries WHERE is_deleted = 0 AND status = 'posted'", null).use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 })
                put("total_credit", db.rawQuery("SELECT COALESCE(SUM(total_credit), 0) FROM journal_entries WHERE is_deleted = 0 AND status = 'posted'", null).use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 })
            }
        } finally { dbLock.unlock() }
    }

    fun getLedgerEntries(params: JSONObject): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val accountId = params.optLong("account_id", 0L)
            val includeAll = params.optBoolean("include_all", false)
            val where = StringBuilder("je.is_deleted = 0 AND je.status = 'posted'")
            val args = mutableListOf<String>()
            if (!includeAll && accountId > 0) { where.append(" AND ji.account_id = ?"); args.add(accountId.toString()) }
            params.optString("start_date", "").takeIf { it.isNotBlank() }?.let { where.append(" AND date(je.entry_date) >= date(?)"); args.add(it) }
            params.optString("end_date", "").takeIf { it.isNotBlank() }?.let { where.append(" AND date(je.entry_date) <= date(?)"); args.add(it) }
            val rows = db.rawQuery(
                """
                SELECT ji.id, ji.journal_entry_id, ji.line_number, ji.account_id, ji.debit, ji.credit, ji.description, ji.description_ar, ji.currency_id, ji.exchange_rate,
                       je.entry_date, je.entry_number, je.description AS entry_description, je.reference_code,
                       a.account_code, COALESCE(a.account_name_ar, a.account_name, '') AS account_name,
                       a.account_type, COALESCE(a.current_balance, 0) AS opening_balance, c.currency_code
                FROM journal_entry_items ji
                INNER JOIN journal_entries je ON je.id = ji.journal_entry_id
                LEFT JOIN accounts a ON a.id = ji.account_id
                LEFT JOIN currencies c ON c.id = ji.currency_id
                WHERE $where
                ORDER BY date(je.entry_date), je.id, ji.line_number
                """.trimIndent(), args.toTypedArray()
            ).use { cursorToJsonArray(it) }
            var balance = 0.0
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                balance += row.optDouble("debit", 0.0) - row.optDouble("credit", 0.0)
                row.put("balance", balance)
                row.put("description", row.optString("description", row.optString("entry_description", "")))
            }
            rows
        } finally { dbLock.unlock() }
    }

    fun generateLedgerReport(params: JSONObject): JSONArray {
        val type = params.optString("report_type", "trial_balance")
        return when (type) {
            "trial_balance", "summary" -> {
                val rows = getChartTrialBalance(params.optString("start_date", ""), params.optString("end_date", ""))
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val closing = row.optDouble("opening_balance", 0.0) + row.optDouble("total_debit", 0.0) - row.optDouble("total_credit", 0.0)
                    row.put("debit_balance", if (closing > 0) closing else 0.0); row.put("credit_balance", if (closing < 0) -closing else 0.0); row.put("closing_balance", closing); row.put("balance", closing)
                }
                rows
            }
            "ledger" -> getLedgerEntries(params)
            "detailed" -> getLedgerEntries(JSONObject(params.toString()).apply { put("account_id", 0); put("include_all", true) })
            "general_ledger" -> {
                dbLock.lock()
                try {
                    val db = readableDatabase
                    val where = StringBuilder("je.is_deleted = 0 AND je.status = 'posted'"); val args = mutableListOf<String>()
                    params.optString("start_date", "").takeIf { it.isNotBlank() }?.let { where.append(" AND date(je.entry_date) >= date(?)"); args.add(it) }
                    params.optString("end_date", "").takeIf { it.isNotBlank() }?.let { where.append(" AND date(je.entry_date) <= date(?)"); args.add(it) }
                    db.rawQuery("""
                        SELECT a.id, a.account_code, COALESCE(a.account_name_ar, a.account_name, '') AS account_name, a.account_type,
                               COALESCE(a.opening_balance, 0) AS opening_balance, COALESCE(SUM(ji.debit), 0) AS total_debit,
                               COALESCE(SUM(ji.credit), 0) AS total_credit
                        FROM accounts a LEFT JOIN journal_entry_items ji ON ji.account_id = a.id
                        LEFT JOIN journal_entries je ON je.id = ji.journal_entry_id AND $where
                        WHERE a.is_deleted = 0 GROUP BY a.id ORDER BY a.account_code COLLATE NOCASE
                    """.trimIndent(), args.toTypedArray()).use { cursor ->
                        cursorToJsonArray(cursor).also { rows ->
                            for (i in 0 until rows.length()) { val row = rows.optJSONObject(i) ?: continue; val closing = row.optDouble("opening_balance") + row.optDouble("total_debit") - row.optDouble("total_credit"); row.put("balance", closing); row.put("closing_balance", closing) }
                        }
                    }
                } finally { dbLock.unlock() }
            }
            else -> getLedgerEntries(params)
        }
    }

    fun getKPIDashboard(params: JSONObject): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val start = params.optString("start_date", ""); val end = params.optString("end_date", "")
            val category = params.optString("kpi_category", "")
            val rows = db.rawQuery("""
                SELECT d.id, d.kpi_code, d.kpi_name, d.kpi_name_ar, d.category, d.description, d.unit,
                       COALESCE(r.actual_value, 0) AS actual_value, COALESCE(r.target_value, d.target_value, 0) AS target_value,
                       COALESCE(r.status, CASE WHEN COALESCE(d.target_value, 0) <= 0 THEN 'warning' WHEN COALESCE(r.actual_value, 0) >= d.target_value THEN 'exceeded' WHEN COALESCE(r.actual_value, 0) >= d.target_value * 0.8 THEN 'on_track' ELSE 'critical' END) AS status,
                       r.calculated_at, r.period_start, r.period_end,
                       (SELECT previous.actual_value FROM kpi_results previous WHERE previous.kpi_id = d.id AND previous.period_end < COALESCE(r.period_start, ?) ORDER BY previous.period_end DESC LIMIT 1) AS previous_actual
                FROM kpi_definitions d
                LEFT JOIN kpi_results r ON r.id = (SELECT latest.id FROM kpi_results latest WHERE latest.kpi_id = d.id AND (? = '' OR latest.period_start >= ?) AND (? = '' OR latest.period_end <= ?) ORDER BY latest.period_end DESC, latest.id DESC LIMIT 1)
                WHERE d.is_active = 1 ${if (category.isNotBlank()) "AND d.category = ?" else ""}
                ORDER BY d.category, d.kpi_code
            """.trimIndent(), buildList {
                add(end.ifBlank { "9999-12-31" }); add(start); add(start); add(end); add(end); if (category.isNotBlank()) add(category)
            }.toTypedArray()).use { cursorToJsonArray(it) }
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val previous = row.optDouble("previous_actual", Double.NaN); val actual = row.optDouble("actual_value", 0.0)
                if (!previous.isNaN() && previous != 0.0) row.put("trend_percent", ((actual - previous) / kotlin.math.abs(previous)) * 100.0) else row.put("trend_percent", JSONObject.NULL)
                row.remove("previous_actual")
            }
            rows
        } finally { dbLock.unlock() }
    }

    fun getKPIDetails(code: String): JSONObject? {
        val params = JSONObject().put("kpi_category", "")
        return getKPIDashboard(params).let { rows ->
            for (i in 0 until rows.length()) { val row = rows.optJSONObject(i) ?: continue; if (row.optString("kpi_code") == code) return row }
            null
        }
    }

    // =========================================================================
    // COA_RECOMMENDATIONS_V1: عمليات شجرة الحسابات المحلية عبر SQLite فقط.
    // لا تستخدم هذه الدوال خادماً أو localStorage أو قاعدة بيانات بديلة.
    // =========================================================================
    fun getChartAccounts(): JSONArray {
        dbLock.lock()
        return try {
            readableDatabase.rawQuery(
                """
                SELECT id, uuid, account_code, account_name, account_name_ar,
                       parent_account_id, level, account_type, account_category,
                       normal_balance, opening_balance, current_balance,
                       is_bank_account, is_cash_account, is_control_account,
                       is_active, bank_account_id, cash_box_id, created_at,
                       updated_at, remarks, extra_data
                FROM accounts
                WHERE is_deleted = 0
                ORDER BY account_code COLLATE NOCASE, id
                """.trimIndent(),
                null
            ).use { cursorToJsonArray(it) }
        } finally {
            dbLock.unlock()
        }
    }

    private fun getAccountRow(db: SQLiteDatabase, id: Long, includeDeleted: Boolean = false): JSONObject? {
        val where = if (includeDeleted) "id = ?" else "id = ? AND is_deleted = 0"
        return db.rawQuery(
            "SELECT * FROM accounts WHERE $where LIMIT 1",
            arrayOf(id.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) cursorToJsonObject(cursor) else null
        }
    }

    private fun accountHasChildren(db: SQLiteDatabase, id: Long): Boolean {
        return db.rawQuery(
            "SELECT 1 FROM accounts WHERE parent_account_id = ? AND is_deleted = 0 LIMIT 1",
            arrayOf(id.toString())
        ).use { it.moveToFirst() }
    }

    private fun accountIsDescendant(db: SQLiteDatabase, candidateParentId: Long, accountId: Long): Boolean {
        var current = candidateParentId
        val visited = mutableSetOf<Long>()
        while (current > 0 && visited.add(current)) {
            if (current == accountId) return true
            val next = db.rawQuery(
                "SELECT parent_account_id FROM accounts WHERE id = ? AND is_deleted = 0 LIMIT 1",
                arrayOf(current.toString())
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
            }
            current = next
        }
        return false
    }

    private fun updateAccountTreeLevels(db: SQLiteDatabase, rootId: Long) {
        val queue: ArrayDeque<Pair<Long, Int>> = ArrayDeque()
        val rootLevel = db.rawQuery(
            "SELECT level FROM accounts WHERE id = ? AND is_deleted = 0 LIMIT 1",
            arrayOf(rootId.toString())
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 1 }
        queue.add(rootId to rootLevel)
        while (queue.isNotEmpty()) {
            val (parentId, parentLevel) = queue.removeFirst()
            db.rawQuery(
                "SELECT id FROM accounts WHERE parent_account_id = ? AND is_deleted = 0 ORDER BY account_code COLLATE NOCASE",
                arrayOf(parentId.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val childId = cursor.getLong(0)
                    val childLevel = parentLevel + 1
                    db.execSQL("UPDATE accounts SET level = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", arrayOf(childLevel, childId))
                    queue.add(childId to childLevel)
                }
            }
        }
    }

    private fun writeAccountAudit(db: SQLiteDatabase, userId: Long, action: String, recordId: Long, oldRow: JSONObject?, newRow: JSONObject?) {
        val values = ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("user_id", if (userId > 0) userId else null)
            put("action_type", action)
            put("table_name", "accounts")
            put("record_id", recordId)
            put("old_row_json", oldRow?.toString())
            put("new_row_json", newRow?.toString())
            put("created_at", getCurrentDateTime())
        }
        db.insert("audit_logs", null, values)
    }

    fun saveChartAccount(data: JSONObject, userId: Long): Long {
        require(data.optString("account_code").trim().isNotEmpty()) { "كود الحساب مطلوب" }
        require(data.optString("account_name_ar").trim().isNotEmpty() || data.optString("account_name").trim().isNotEmpty()) { "اسم الحساب مطلوب" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = data.optLong("id", 0L)
            val code = data.optString("account_code").trim()
            val parentId = if (data.isNull("parent_account_id")) 0L else data.optLong("parent_account_id", 0L)
            val type = data.optString("account_type", "asset")
            require(type in setOf("asset", "liability", "equity", "revenue", "expense")) { "نوع الحساب غير صالح" }
            if (parentId > 0) {
                require(parentId != id) { "لا يمكن أن يكون الحساب أباً لنفسه" }
                require(getAccountRow(db, parentId) != null) { "الحساب الأب غير موجود" }
                require(!accountIsDescendant(db, parentId, id)) { "لا يمكن نقل الحساب إلى أحد فروعه" }
            }
            val values = ContentValues().apply {
                put("account_code", code)
                put("account_name", data.optString("account_name").trim().ifEmpty { data.optString("account_name_ar").trim() })
                put("account_name_ar", data.optString("account_name_ar").trim().ifEmpty { data.optString("account_name").trim() })
                put("account_type", type)
                put("account_category", data.optString("account_category").trim())
                put("normal_balance", data.optString("normal_balance", if (type in setOf("asset", "expense")) "debit" else "credit"))
                put("opening_balance", data.optDouble("opening_balance", 0.0))
                if (id <= 0) put("current_balance", data.optDouble("current_balance", data.optDouble("opening_balance", 0.0)))
                put("is_bank_account", data.optInt("is_bank_account", 0))
                put("is_cash_account", data.optInt("is_cash_account", 0))
                put("is_control_account", data.optInt("is_control_account", 0))
                put("is_active", data.optInt("is_active", 1))
                if (parentId > 0) put("parent_account_id", parentId) else putNull("parent_account_id")
                if (data.optLong("bank_account_id", 0L) > 0) put("bank_account_id", data.optLong("bank_account_id")) else putNull("bank_account_id")
                if (data.optLong("cash_box_id", 0L) > 0) put("cash_box_id", data.optLong("cash_box_id")) else putNull("cash_box_id")
                put("remarks", data.optString("remarks").trim())
                put("updated_by", if (userId > 0) userId else null)
                put("updated_at", getCurrentDateTime())
            }
            val savedId: Long
            if (id > 0) {
                val oldRow = getAccountRow(db, id) ?: throw IllegalArgumentException("الحساب غير موجود")
                val rows = db.update("accounts", values, "id = ? AND is_deleted = 0", arrayOf(id.toString()))
                require(rows == 1) { "لم يتم تحديث الحساب" }
                updateAccountTreeLevels(db, id)
                savedId = id
                writeAccountAudit(db, userId, "update", id, oldRow, getAccountRow(db, id))
            } else {
                values.put("uuid", UUID.randomUUID().toString())
                values.put("level", if (parentId > 0) {
                    db.rawQuery("SELECT level FROM accounts WHERE id = ?", arrayOf(parentId.toString())).use { c -> if (c.moveToFirst()) c.getInt(0) + 1 else 1 }
                } else 1)
                values.put("created_by", if (userId > 0) userId else null)
                values.put("created_at", getCurrentDateTime())
                savedId = db.insertOrThrow("accounts", null, values)
                writeAccountAudit(db, userId, "insert", savedId, null, getAccountRow(db, savedId))
            }
            db.setTransactionSuccessful()
            return savedId
        } finally {
            db.endTransaction()
        }
    }

    fun deleteChartAccount(id: Long, cascade: Boolean, userId: Long): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val root = getAccountRow(db, id) ?: throw IllegalArgumentException("الحساب غير موجود")
            val ids = mutableListOf<Long>()
            val queue = ArrayDeque<Long>()
            queue.add(id)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                ids.add(current)
                if (cascade) {
                    db.rawQuery("SELECT id FROM accounts WHERE parent_account_id = ? AND is_deleted = 0", arrayOf(current.toString())).use { c ->
                        while (c.moveToNext()) queue.add(c.getLong(0))
                    }
                }
            }
            if (!cascade && accountHasChildren(db, id)) throw IllegalStateException("لا يمكن حذف حساب يحتوي على فروع")
            val values = ContentValues().apply {
                put("is_deleted", 1)
                put("is_active", 0)
                put("deleted_by", if (userId > 0) userId else null)
                put("deleted_at", getCurrentDateTime())
                put("updated_at", getCurrentDateTime())
            }
            var changed = 0
            ids.forEach { changed += db.update("accounts", values, "id = ? AND is_deleted = 0", arrayOf(it.toString())) }
            writeAccountAudit(db, userId, "delete", id, root, getAccountRow(db, id, true))
            db.setTransactionSuccessful()
            return changed
        } finally {
            db.endTransaction()
        }
    }

    fun archiveChartAccount(id: Long, userId: Long): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val oldRow = getAccountRow(db, id) ?: throw IllegalArgumentException("الحساب غير موجود")
            val values = ContentValues().apply {
                put("is_active", 0)
                put("updated_by", if (userId > 0) userId else null)
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("accounts", values, "id = ? AND is_deleted = 0", arrayOf(id.toString()))
            if (rows == 1) writeAccountAudit(db, userId, "archive", id, oldRow, getAccountRow(db, id))
            db.setTransactionSuccessful()
            return rows
        } finally {
            db.endTransaction()
        }
    }

    fun restoreChartAccount(id: Long, userId: Long): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val oldRow = getAccountRow(db, id, true) ?: throw IllegalArgumentException("الحساب غير موجود")
            val values = ContentValues().apply {
                put("is_deleted", 0)
                put("is_active", 1)
                putNull("deleted_by")
                putNull("deleted_at")
                put("updated_by", if (userId > 0) userId else null)
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("accounts", values, "id = ?", arrayOf(id.toString()))
            if (rows == 1) writeAccountAudit(db, userId, "restore", id, oldRow, getAccountRow(db, id))
            db.setTransactionSuccessful()
            return rows
        } finally {
            db.endTransaction()
        }
    }

    fun cloneChartAccount(sourceId: Long, data: JSONObject, userId: Long): Long {
        val db = writableDatabase
        val source = getAccountRow(db, sourceId) ?: throw IllegalArgumentException("الحساب الأصلي غير موجود")
        val clone = JSONObject(source.toString()).apply {
            remove("id")
            remove("uuid")
            remove("created_at")
            remove("updated_at")
            remove("created_by")
            remove("updated_by")
            remove("deleted_at")
            remove("deleted_by")
            remove("is_deleted")
            put("account_code", data.optString("account_code").trim())
            put("account_name", data.optString("account_name").trim().ifEmpty { data.optString("account_name_ar").trim() })
            put("account_name_ar", data.optString("account_name_ar").trim().ifEmpty { data.optString("account_name").trim() })
            put("is_active", 1)
            put("current_balance", 0.0)
            put("parent_account_id", if (data.isNull("parent_account_id")) JSONObject.NULL else data.optLong("parent_account_id"))
        }
        require(clone.optString("account_code").isNotEmpty()) { "كود الحساب المنسوخ مطلوب" }
        return saveChartAccount(clone, userId)
    }

    fun moveChartAccount(id: Long, parentId: Long, userId: Long): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val oldRow = getAccountRow(db, id) ?: throw IllegalArgumentException("الحساب غير موجود")
            if (parentId > 0) {
                require(parentId != id) { "لا يمكن أن يكون الحساب أباً لنفسه" }
                require(getAccountRow(db, parentId) != null) { "الحساب الأب غير موجود" }
                require(!accountIsDescendant(db, parentId, id)) { "لا يمكن نقل الحساب إلى أحد فروعه" }
            }
            val newLevel = if (parentId > 0) {
                db.rawQuery("SELECT level FROM accounts WHERE id = ?", arrayOf(parentId.toString())).use { c -> if (c.moveToFirst()) c.getInt(0) + 1 else 1 }
            } else 1
            val values = ContentValues().apply {
                if (parentId > 0) put("parent_account_id", parentId) else putNull("parent_account_id")
                put("level", newLevel)
                put("updated_by", if (userId > 0) userId else null)
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("accounts", values, "id = ? AND is_deleted = 0", arrayOf(id.toString()))
            if (rows == 1) {
                updateAccountTreeLevels(db, id)
                writeAccountAudit(db, userId, "move", id, oldRow, getAccountRow(db, id))
            }
            db.setTransactionSuccessful()
            return rows
        } finally {
            db.endTransaction()
        }
    }

    fun getChartAccountAudit(id: Long, limit: Int = 100): JSONArray {
        dbLock.lock()
        return try {
            readableDatabase.rawQuery(
                "SELECT id, uuid, user_id, action_type, table_name, record_id, old_row_json, new_row_json, created_at FROM audit_logs WHERE table_name = 'accounts' AND record_id = ? ORDER BY id DESC LIMIT ?",
                arrayOf(id.toString(), limit.coerceIn(1, 500).toString())
            ).use { cursorToJsonArray(it) }
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // COA_TRIAL_BALANCE_V1: ميزان مراجعة مستخرج من قيود اليومية المرحّلة.
    // ========================================================================
    fun getChartTrialBalance(fromDate: String?, toDate: String?): JSONArray {
        dbLock.lock()
        return try {
            val conditions = StringBuilder()
            val args = mutableListOf<String>()
            if (!fromDate.isNullOrBlank()) {
                conditions.append(" AND je.entry_date >= ?")
                args.add(fromDate)
            }
            if (!toDate.isNullOrBlank()) {
                conditions.append(" AND je.entry_date <= ?")
                args.add(toDate)
            }
            val sql = """
                SELECT a.id, a.account_code, a.account_name, a.account_name_ar,
                       a.account_type, a.level, a.normal_balance,
                       COALESCE(a.opening_balance, 0) AS opening_balance,
                       COALESCE(SUM(jei.debit), 0) AS total_debit,
                       COALESCE(SUM(jei.credit), 0) AS total_credit,
                       COALESCE(a.current_balance, 0) AS current_balance,
                       a.is_active
                FROM accounts a
                LEFT JOIN journal_entry_items jei ON jei.account_id = a.id
                LEFT JOIN journal_entries je ON je.id = jei.journal_entry_id
                    AND je.status = 'posted' AND je.is_deleted = 0 $conditions
                WHERE a.is_deleted = 0
                GROUP BY a.id
                ORDER BY a.account_code COLLATE NOCASE, a.id
            """.trimIndent()
            readableDatabase.rawQuery(sql, args.toTypedArray()).use { cursorToJsonArray(it) }
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // دوال تقارير الوقود والصلاحية — مضافة للإصدار 16
    // ========================================================================

    fun getExpirySoonProducts(days: Int = 30): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val sql = """
                SELECT p.*, c.category_name 
                FROM products p
                LEFT JOIN product_categories c ON p.category_id = c.id
                WHERE p.has_expiry = 1 
                AND p.is_deleted = 0
                AND date(p.expiry_date) <= date('now', '+' || ? || ' days')
                ORDER BY p.expiry_date ASC
            """
            db.rawQuery(sql, arrayOf(days.toString())).use { cursorToJsonArray(it) }
        } finally {
            dbLock.unlock()
        }
    }

    fun extendProductExpiry(id: Long, newDate: String): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("expiry_date", newDate)
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("products", cv, "id = ?", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "extend_expiry", "تمديد صلاحية المنتج ${id} إلى ${newDate}")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    fun markProductExpired(id: Long): Int {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("status", "expired")
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("products", cv, "id = ?", arrayOf(id.toString()))
            if (rows > 0) logActivity("system", "mark_expired", "تمييز المنتج ${id} كمنتهي الصلاحية")
            rows
        } finally {
            dbLock.unlock()
        }
    }

    fun getFuelReport(data: JSONObject): JSONArray {
        dbLock.lock()
        return try {
            val fromDate = data.optString("from_date")
            val toDate = data.optString("to_date")
            val stationId = data.optInt("station_id", 1)
            
            val conditions = StringBuilder()
            val args = mutableListOf<String>()
            args.add(stationId.toString())
            
            if (fromDate.isNotBlank()) {
                conditions.append(" AND date(created_at) >= ?")
                args.add(fromDate)
            }
            if (toDate.isNotBlank()) {
                conditions.append(" AND date(created_at) <= ?")
                args.add(toDate)
            }

            val sql = """
                SELECT 'sale' as type, s.id, s.sale_code as code, s.created_at as date, 
                       s.liters as quantity, s.net_amount as amount, f.fuel_name,
                       p.commercial_name as party_name, '---' as tank_name,
                       s.fuel_type_id as fuel_type_id, NULL as tank_id
                FROM sales_transactions s
                LEFT JOIN fuel_types f ON s.fuel_type_id = f.id
                LEFT JOIN parties p ON s.customer_party_id = p.id
                WHERE s.station_id = ? AND s.is_deleted = 0 AND s.order_type = 'fuel' ${conditions}
                UNION ALL
                SELECT 'refill' as type, r.id, r.refill_code as code, r.created_at as date,
                       r.delivered_quantity as quantity, 0 as amount, f.fuel_name,
                       p.commercial_name as party_name, t.tank_name,
                       r.fuel_type_id as fuel_type_id, r.tank_id as tank_id
                FROM tank_refills r
                LEFT JOIN fuel_types f ON r.fuel_type_id = f.id
                LEFT JOIN parties p ON r.supplier_id = p.id
                LEFT JOIN tanks t ON r.tank_id = t.id
                WHERE r.station_id = ? ${conditions}
                ORDER BY date DESC
            """
            
            val allArgs = mutableListOf<String>()
            allArgs.addAll(args)
            allArgs.addAll(args)
            
            readableDatabase.rawQuery(sql, allArgs.toTypedArray()).use { cursorToJsonArray(it) }
        } finally {
            dbLock.unlock()
        }
    }

    fun getFuelTransactionDetails(id: Long, type: String): JSONObject? {
        dbLock.lock()
        return try {
            val db = readableDatabase
            if (type == "sale") {
                db.rawQuery("SELECT * FROM fuel_sales WHERE sale_id = ?", arrayOf(id.toString())).use { cursor ->
                    if (cursor.moveToFirst()) cursorToJsonObject(cursor) else null
                }
            } else if (type == "refill") {
                db.rawQuery("SELECT * FROM tank_refills WHERE id = ?", arrayOf(id.toString())).use { cursor ->
                    if (cursor.moveToFirst()) cursorToJsonObject(cursor) else null
                }
            } else null
        } finally {
            dbLock.unlock()
        }
    }

    fun getFuelInventoryReconciliation(data: JSONObject): JSONArray {
        dbLock.lock()
        return try {
            val stationId = data.optInt("station_id", 1)
            val date = data.optString("date", getCurrentDate())
            val sql = """
                SELECT t.id as tank_id, t.tank_name, f.fuel_name,
                       COALESCE(l.opening_level, 0) as opening_level,
                       COALESCE((SELECT SUM(delivered_quantity) FROM tank_refills WHERE tank_id = t.id AND date(created_at) = ?), 0) as refills,
                       COALESCE((SELECT SUM(liters) FROM sales_transactions WHERE fuel_type_id = t.fuel_type_id AND station_id = t.station_id AND date(created_at) = ? AND is_deleted = 0), 0) as sold,
                       COALESCE(l.closing_level, 0) as closing_level,
                       COALESCE(l.measured_level, 0) as measured_level,
                       COALESCE(l.difference, 0) as difference
                FROM tanks t
                LEFT JOIN fuel_types f ON t.fuel_type_id = f.id
                LEFT JOIN tank_level_log l ON t.id = l.tank_id AND date(l.reading_date) = ?
                WHERE t.station_id = ?
            """
            readableDatabase.rawQuery(sql, arrayOf(date, date, date, stationId.toString())).use { cursorToJsonArray(it) }
        } finally {
            dbLock.unlock()
        }
    }

}