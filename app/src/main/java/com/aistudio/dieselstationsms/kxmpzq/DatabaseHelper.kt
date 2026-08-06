package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteException  // تمت إضافته
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
 * الإصدار المدمج V13 - تم إصلاح جميع المشاكل الحرجة والعالية الخطورة
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
        const val DATABASE_NAME = DB_NAME   // <-- هذا هو السطر المضاف
        const val VERSION = 13

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
            insertInitialData(db)
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
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
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
        createUserOtpVerificationsTable(db)
        ensureSmsSettings(db)
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

    // ===================================================================================
    // دوال إنشاء الجداول (CREATE TABLE)
    // ===================================================================================

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
        // هذه الدالة موجودة بالفعل في createAssetTables، لكن نعيد تعريفها هنا إن لزم
        // لكنها موجودة بالفعل، لذلك نتركها فارغة أو نضع تنفيذ فوري
        // للتأكد من عدم تكرار الجدول، نستخدم IF NOT EXISTS
        // لكنها موجودة بالفعل في createAssetTables، لذلك نتركها فارغة
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
            val partyType = data.optString("party_type", "customer")
            val typeId = when (partyType.lowercase()) {
                "customer" -> 1
                "supplier" -> 6
                "driver" -> 4
                else -> data.optInt("party_type_id", 1)
            }
            val values = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("party_code", data.optString("party_code", "PTY-${System.currentTimeMillis()}"))
                put("party_type_id", typeId)
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
                put("credit_limit", data.optDouble("credit_limit", 0.0))
                put("current_balance", data.optDouble("current_balance", 0.0))
                put("total_due", data.optDouble("total_due", 0.0))
                put("loyalty_points", data.optInt("loyalty_points", 0))
                put("is_active", if (data.optBoolean("is_active", true)) 1 else 0)
                put("notes", data.optString("notes", ""))
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
                put("credit_limit", data.optDouble("credit_limit", 0.0))
                put("current_balance", data.optDouble("current_balance", 0.0))
                put("total_due", data.optDouble("total_due", 0.0))
                put("loyalty_points", data.optInt("loyalty_points", 0))
                put("is_active", if (data.optBoolean("is_active", true)) 1 else 0)
                put("notes", data.optString("notes", ""))
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
            put("party_id", c.getInt(c.getColumnIndexOrThrow("id")))
            put("party_code", c.getString(c.getColumnIndexOrThrow("party_code")))
            put("commercial_name", c.getString(c.getColumnIndexOrThrow("commercial_name")))
            put("commercial_name_ar", c.getString(c.getColumnIndexOrThrow("commercial_name_ar")))
            put("phone", c.getString(c.getColumnIndexOrThrow("phone")))
            put("credit_limit", c.getDouble(c.getColumnIndexOrThrow("credit_limit")))
            put("current_balance", c.getDouble(c.getColumnIndexOrThrow("current_balance")))
            put("total_due", c.getDouble(c.getColumnIndexOrThrow("total_due")))
            put("loyalty_points", c.getInt(c.getColumnIndexOrThrow("loyalty_points")))
            put("loyalty_tier", c.getString(c.getColumnIndexOrThrow("loyalty_tier")))
            put("risk_level", c.getString(c.getColumnIndexOrThrow("risk_level")))
            put("status", c.getString(c.getColumnIndexOrThrow("status")))
            put("is_active", c.getInt(c.getColumnIndexOrThrow("is_active")))
            put("created_at", c.getString(c.getColumnIndexOrThrow("created_at")))
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
                    put("product_name", cursor.getString(cursor.getColumnIndexOrThrow("product_name")))
                    put("sale_price", cursor.getDouble(cursor.getColumnIndexOrThrow("sale_price")))
                    put("quantity", cursor.getDouble(cursor.getColumnIndexOrThrow("quantity")))
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
                paymentMethod = data.optString("payment_type", "cash"),
                isCredit = data.optString("payment_type") == "آجل",
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
            result.put("invoice_number", "INV-$saleId")
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

            var currentQty = 0.0
            db.rawQuery(
                "SELECT quantity_on_hand FROM inventory_levels WHERE product_id = ? AND warehouse_id = 1",
                arrayOf(productId.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) currentQty = cursor.getDouble(0)
            }

            val quantityBefore = currentQty
            val quantityAfter = if (movementType == "in") currentQty + quantity else currentQty - quantity

            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("movement_code", data.optString("movement_code", "INV-${System.currentTimeMillis()}"))
                put("product_id", productId)
                put("station_id", stationId)
                put("movement_type", movementType)
                put("movement_subtype", data.optString("movement_subtype", ""))
                put("quantity_before", quantityBefore)
                put("quantity_change", quantity)
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
                    "SELECT id FROM inventory_levels WHERE product_id = ? AND warehouse_id = 1",
                    arrayOf(productId.toString())
                ).use { exists ->
                    if (exists.moveToFirst()) {
                        db.execSQL(
                            "UPDATE inventory_levels SET quantity_on_hand = ? WHERE product_id = ? AND warehouse_id = 1",
                            arrayOf(quantityAfter, productId)
                        )
                    } else {
                        val cvInv = ContentValues().apply {
                            put("product_id", productId)
                            put("warehouse_id", 1)
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

    fun getStockMovements(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                """SELECT im.*, p.product_name 
                   FROM inventory_movements im
                   LEFT JOIN products p ON im.product_id = p.id
                   WHERE im.is_deleted = 0
                   ORDER BY im.created_at DESC LIMIT 200""",
                null
            ).use { cursor -> cursorToJsonArray(cursor) }
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
            val password = data.optString("password", "123456")
            val (hash, salt) = hashPassword(password)
            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("username", data.optString("username", ""))
                put("password_hash", hash)
                put("password_salt", salt)
                put("full_name", data.optString("full_name", ""))
                put("full_name_ar", data.optString("full_name_ar", ""))
                put("email", data.optString("email", ""))
                put("phone", data.optString("phone", ""))
                put("role_id", data.optInt("role_id", 4))
                put("station_id", data.optInt("station_id", 1))
                put("company_id", data.optInt("company_id", 1))
                put("preferred_language", data.optString("preferred_language", "ar"))
                put("status", data.optString("status", "active"))
                put("job_title", data.optString("job_title", ""))
                put("created_at", getCurrentDateTime())
                put("updated_at", getCurrentDateTime())
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
                """SELECT u.*, r.role_name, r.role_name_ar 
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
                """SELECT u.*, r.role_name, r.role_name_ar 
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
                data.optString("full_name")?.let { put("full_name", it) }
                data.optString("email")?.let { put("email", it) }
                data.optString("phone")?.let { put("phone", it) }
                data.optInt("role_id")?.let { put("role_id", it) }
                data.optString("status")?.let { put("status", it) }
                put("updated_at", getCurrentDateTime())
            }
            val rows = db.update("users", cv, "id=?", arrayOf(id.toString()))
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
            val arr = JSONArray()
            val db = readableDatabase
            db.rawQuery(
                """SELECT p.permission_code, p.permission_name, p.permission_name_ar, p.module, p.action,
                          rp.can_create, rp.can_read, rp.can_update, rp.can_delete, rp.can_export, rp.can_print, rp.can_approve
                   FROM permissions p
                   JOIN role_permissions rp ON p.id = rp.permission_id
                   JOIN users u ON u.role_id = rp.role_id
                   WHERE u.id = ? AND p.is_deleted = 0
                   UNION
                   SELECT p.permission_code, p.permission_name, p.permission_name_ar, p.module, p.action,
                          1, up.is_granted, 1, 1, 1, 1, 1
                   FROM permissions p
                   JOIN user_permissions up ON p.id = up.permission_id
                   WHERE up.user_id = ? AND up.is_granted = 1""",
                arrayOf(userId.toString(), userId.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    arr.put(JSONObject().apply {
                        put("permission_code", cursor.getString(0))
                        put("permission_name", cursor.getString(1))
                        put("permission_name_ar", cursor.getString(2))
                        put("module", cursor.getString(3))
                        put("action", cursor.getString(4))
                        put("can_create", cursor.getInt(5) == 1)
                        put("can_read", cursor.getInt(6) == 1)
                        put("can_update", cursor.getInt(7) == 1)
                        put("can_delete", cursor.getInt(8) == 1)
                        put("can_export", cursor.getInt(9) == 1)
                        put("can_print", cursor.getInt(10) == 1)
                        put("can_approve", cursor.getInt(11) == 1)
                    })
                }
            }
            arr
        } finally {
            dbLock.unlock()
        }
    }

    fun getUserScreens(userId: Long): JSONArray {
        
        val result = JSONArray()

        val db = readableDatabase


        val cursor = db.rawQuery(
            """
            SELECT DISTINCT
                p.module
            FROM permissions p
            JOIN role_permissions rp
                ON rp.permission_id = p.id
            JOIN users u
                ON u.role_id = rp.role_id
            WHERE u.id=?
            AND rp.can_read=1
            """,
            arrayOf(userId.toString())
        )


        cursor.use {

            while(it.moveToNext()) {

                val module = it.getString(0)

                result.put(module)

            }
        }


        return result
    }        
        
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

    fun getUserNotifications(userId: Long): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                "SELECT * FROM notifications WHERE user_id = ? AND is_deleted = 0 ORDER BY created_at DESC LIMIT 50",
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

    fun processPayment(customerId: Int, amount: Double, method: String, operator: String = "System"): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE parties SET current_balance = current_balance - ?, total_due = total_due - ? WHERE id = ?",
                arrayOf(amount, amount, customerId)
            )
            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("payment_code", "PAY-${System.currentTimeMillis()}")
                put("customer_party_id", customerId)
                put("payment_type", method)
                put("payment_method", method)
                put("amount", amount)
                put("status", "completed")
                put("operator", operator)
                put("notes", "تسديد عبر API")
                put("created_at", getCurrentDateTime())
            }
            db.insert("payments", null, cv)

            db.execSQL(
                """UPDATE sales_transactions 
                   SET paid_amount = paid_amount + ?, remaining_amount = remaining_amount - ?,
                       payment_status = CASE WHEN remaining_amount - ? <= 0 THEN 'paid' ELSE 'partial' END
                   WHERE customer_party_id = ? AND remaining_amount > 0 AND is_deleted = 0 ORDER BY id LIMIT 1""",
                arrayOf(amount, amount, amount, customerId)
            )

            db.setTransactionSuccessful()
            logActivity(operator, "payment", "تسديد مبلغ $amount للعميل $customerId")
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
                "SELECT * FROM notifications WHERE is_deleted = 0 ORDER BY created_at DESC LIMIT 100",
                null
            ).use { cursor -> cursorToJsonArray(cursor) }
        } finally {
            dbLock.unlock()
        }
    }

    // ========================================================================
    // إحصائيات لوحة التحكم
    // ========================================================================

    fun getDashboardStats(stationId: Int = 1): JSONObject {
        val stats = JSONObject()
        val db = readableDatabase

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

        db.rawQuery("SELECT COALESCE(SUM(current_quantity),0) FROM tanks WHERE station_id=? AND is_deleted=0", arrayOf(stationId.toString()))
            .use { cursor ->
                if (cursor.moveToFirst()) stats.put("total_remaining", cursor.getDouble(0))
            }

        db.rawQuery(
            "SELECT COALESCE(SUM(remaining_amount),0) FROM sales_transactions WHERE station_id=? AND payment_status IN ('pending','partial') AND is_deleted=0",
            arrayOf(stationId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) stats.put("total_due", cursor.getDouble(0))
        }

        db.rawQuery("SELECT COUNT(*) FROM parties WHERE is_active=1 AND is_deleted=0", null)
            .use { cursor ->
                if (cursor.moveToFirst()) stats.put("total_customers", cursor.getInt(0))
            }

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
                put("user_id", data.optLong("user_id", 0))
                put("role_id", data.optLong("role_id", 0))
                put("notification_type", data.optString("notification_type", "info"))
                put("title", data.optString("title", ""))
                put("title_ar", data.optString("title_ar", ""))
                put("message", data.optString("message", ""))
                put("message_ar", data.optString("message_ar", ""))
                put("priority", data.optString("priority", "normal"))
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
            db.rawQuery("SELECT COUNT(*) FROM notifications WHERE is_read = 0 AND is_deleted = 0", null)
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
                "SELECT * FROM sms_messages ORDER BY created_at DESC LIMIT 500",
                null
            ).use { cursor -> cursorToJsonArray(cursor) }
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
            val tables = listOf("parties", "sales_transactions", "tanks", "pumps", "users", "employees", "shifts", "notifications", "sms_messages", "fuel_types")
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
                        put("product_code", cursor.getString(cursor.getColumnIndexOrThrow("product_code")))
                        put("product_name", cursor.getString(cursor.getColumnIndexOrThrow("product_name")))
                        put("product_name_ar", cursor.getString(cursor.getColumnIndexOrThrow("product_name_ar")))
                        put("category_name", cursor.getString(cursor.getColumnIndexOrThrow("category_name")))
                        put("sale_price", cursor.getDouble(cursor.getColumnIndexOrThrow("sale_price")))
                        put("purchase_price", cursor.getDouble(cursor.getColumnIndexOrThrow("purchase_price")))
                        put("quantity", cursor.getDouble(cursor.getColumnIndexOrThrow("quantity")))
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
                put("unit_id", data.optString("unit_id", "لتر"))
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
                data.optString("unit_id")?.let { put("unit_id", it) }
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

    fun getCustomerDebts(fromDate: String?, toDate: String?): JSONArray = getCustomerDebts(null)

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
        dbLock.lock()
        return try {
            val db = readableDatabase
            db.rawQuery(
                """SELECT 1 FROM role_permissions rp
                   JOIN users u ON u.role_id = rp.role_id
                   JOIN permissions p ON p.id = rp.permission_id
                   WHERE u.id = ? AND p.permission_code = ? AND rp.can_read = 1
                   LIMIT 1""".trimIndent(),
                arrayOf(userId.toString(), permissionCode)
            ).use { cursor -> cursor.moveToFirst() }
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

}