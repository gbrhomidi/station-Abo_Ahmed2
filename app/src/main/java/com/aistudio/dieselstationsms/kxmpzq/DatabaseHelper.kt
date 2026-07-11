package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
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
 * الإصدار المدمج V12 - كامل مع جميع الجداول والدوال
 * تم إصلاح جميع الدوال لتتوافق مع SMSService.kt و MainActivity.kt
 */
class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, VERSION) {

    companion object {
        private const val TAG = "DatabaseHelper"
        private const val DB_NAME = "diesel_station.db"
        const val VERSION = 12

        private const val HASH_ITERATIONS = 10000

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

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val dateOnlyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }

    private val dbLock = ReentrantLock()
    private val contextRef = context

    private fun getCurrentDateTime(): String = DATE_FORMAT.format(Date())
    private fun getCurrentDate(): String = dateOnlyFormat.format(Date())
    private fun getCurrentTime(): String = TIME_FORMAT.format(Date())

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            createAllTables(db)
            insertInitialData(db)
            db.setTransactionSuccessful()
            Log.d(TAG, "Database V12 created successfully")
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
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ================================================================
    // MIGRATIONS
    // ================================================================
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
        } catch (e: Exception) { /* قد يكون موجوداً */ }
        val (hashAdmin, saltAdmin) = hashPassword("admin123")
        db.execSQL("UPDATE users SET password_hash = '$hashAdmin', password_salt = '$saltAdmin' WHERE username = 'admin'")
        val (hashKhalil, saltKhalil) = hashPassword("123321")
        db.execSQL("UPDATE users SET password_hash = '$hashKhalil', password_salt = '$saltKhalil' WHERE username = 'خليل أحمد'")
    }

    private fun migrateV9ToV10(db: SQLiteDatabase) {
        try {
            db.execSQL("ALTER TABLE sales_transactions ADD COLUMN delivery_location TEXT")
        } catch (e: Exception) { }
        try {
            db.execSQL("ALTER TABLE sales_transactions ADD COLUMN delivery_time TEXT")
        } catch (e: Exception) { }
        try {
            db.execSQL("ALTER TABLE sales_transactions ADD COLUMN driver_id INTEGER REFERENCES drivers(id)")
        } catch (e: Exception) { }
        try {
            db.execSQL("ALTER TABLE sales_transactions ADD COLUMN vehicle_id INTEGER REFERENCES vehicles(id)")
        } catch (e: Exception) { }
        try {
            db.execSQL("ALTER TABLE sales_transactions ADD COLUMN order_type TEXT DEFAULT 'sale'")
        } catch (e: Exception) { }

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

    // ================================================================
    // CREATE ALL TABLES
    // ================================================================
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
        createIndexes(db)
    }

    // ================================================================
    // 1. CORE TABLES
    // ================================================================
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

    // ================================================================
    // 2. SECURITY TABLES
    // ================================================================
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

    // ================================================================
    // 3. PARTY TABLES
    // ================================================================
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

    // ================================================================
    // 4. VEHICLE & DRIVER TABLES
    // ================================================================
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

    // ================================================================
    // 5. PRODUCT TABLES
    // ================================================================
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

    // ================================================================
    // 6. TANK & PUMP TABLES
    // ================================================================
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

    // ================================================================
    // 7. INVENTORY TABLES
    // ================================================================
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

    // ================================================================
    // 8. SALES & SHIFT TABLES
    // ================================================================
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

    // ================================================================
    // 9. FINANCE TABLES
    // ================================================================
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

    // ================================================================
    // 10. ACCOUNTING TABLES
    // ================================================================
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

    // ================================================================
    // 11. HR TABLES
    // ================================================================
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

    // ================================================================
    // 12. ASSET & MAINTENANCE TABLES
    // ================================================================
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

    // ================================================================
    // 13. NOTIFICATION & SMS TABLES
    // ================================================================
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

    // ================================================================
    // 14. LOG & AUDIT TABLES
    // ================================================================
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

    // ================================================================
    // 15. ADVANCED TABLES
    // ================================================================
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

    // ================================================================
    // 16. LEDGER TABLES
    // ================================================================
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

    // ================================================================
    // 17. PRINT TABLES
    // ================================================================
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

    // ================================================================
    // 18. OLD V6 TABLES (Backward Compatibility)
    // ================================================================
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

    // ================================================================
    // 19. ADDITIONAL TABLES FOR V11
    // ================================================================
    private fun createMaintenanceRequestsTable(db: SQLiteDatabase) {
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

    // ================================================================
    // 20. INDEXES
    // ================================================================
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

    // ================================================================
    // 21. INITIAL DATA
    // ================================================================
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
            VALUES (1, 'COMP-001-UUID', 'COMP-001', 'Abu Ahmed Fuel Stations Group', 'مجموعة محطات ابو أحمد', 'TAX-123456789', '+967-777-000-000', 'info@abuahmed.com', 'Yemen', 'Sana''a', 'active', 1, 2)
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
            (26, 'PER-026-UUID', 'settings.update', 'Edit Settings', 'تعديل الإعدادات', 'settings', 'الإعدادات', 'update')
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
    }

    // ================================================================
    // 22. AUTHENTICATION
    // ================================================================
    fun authenticateUser(username: String, password: String): JSONObject? {
        val db = readableDatabase
        val c = db.rawQuery(
            "SELECT * FROM users WHERE username=? AND status='active' AND is_deleted=0",
            arrayOf(username)
        )
        return c.use {
            if (it.moveToFirst()) {
                val storedHash = it.getString(it.getColumnIndexOrThrow("password_hash"))
                val storedSalt = it.getString(it.getColumnIndexOrThrow("password_salt"))
                if (verifyPassword(password, storedHash, storedSalt)) {
                    val o = JSONObject()
                    o.put("user_id", it.getInt(it.getColumnIndexOrThrow("id")))
                    o.put("username", it.getString(it.getColumnIndexOrThrow("username")))
                    o.put("full_name", it.getString(it.getColumnIndexOrThrow("full_name")))
                    o.put("full_name_ar", it.getString(it.getColumnIndexOrThrow("full_name_ar")))
                    o.put("role_id", it.getInt(it.getColumnIndexOrThrow("role_id")))
                    o.put("station_id", it.getInt(it.getColumnIndexOrThrow("station_id")))
                    o.put("company_id", it.getInt(it.getColumnIndexOrThrow("company_id")))
                    o.put("biometric_enabled", it.getInt(it.getColumnIndexOrThrow("biometric_enabled")))
                    o.put("status", it.getString(it.getColumnIndexOrThrow("status")))
                    logActivity(username, "login", "تسجيل دخول ناجح")
                    o
                } else null
            } else null
        }
    }

    fun getUserByUsername(username: String): JSONObject? {
        val db = readableDatabase
        val c = db.rawQuery("SELECT * FROM users WHERE username=? AND is_deleted=0", arrayOf(username))
        return c.use {
            if (it.moveToFirst()) {
                JSONObject().apply {
                    put("user_id", it.getInt(it.getColumnIndexOrThrow("id")))
                    put("username", it.getString(it.getColumnIndexOrThrow("username")))
                    put("full_name", it.getString(it.getColumnIndexOrThrow("full_name")))
                    put("full_name_ar", it.getString(it.getColumnIndexOrThrow("full_name_ar")))
                    put("role_id", it.getInt(it.getColumnIndexOrThrow("role_id")))
                    put("station_id", it.getInt(it.getColumnIndexOrThrow("station_id")))
                    put("company_id", it.getInt(it.getColumnIndexOrThrow("company_id")))
                    put("biometric_enabled", it.getInt(it.getColumnIndexOrThrow("biometric_enabled")))
                    put("status", it.getString(it.getColumnIndexOrThrow("status")))
                }
            } else null
        }
    }

    fun updateBiometricStatus(username: String, enabled: Boolean): Boolean {
        val cv = ContentValues().apply {
            put("biometric_enabled", if (enabled) 1 else 0)
        }
        val rows = writableDatabase.update("users", cv, "username=?", arrayOf(username))
        return rows > 0
    }

    // ================================================================
    // 23. PARTIES (CUSTOMERS / SUPPLIERS) - الموحدة
    // ================================================================
    fun getParties(typeId: Int? = null): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        val sql = if (typeId != null) {
            "SELECT * FROM parties WHERE party_type_id=? AND is_deleted=0 ORDER BY commercial_name"
        } else {
            "SELECT * FROM parties WHERE is_deleted=0 ORDER BY commercial_name"
        }
        val args = if (typeId != null) arrayOf(typeId.toString()) else null
        val c = db.rawQuery(sql, args)
        c.use {
            while (it.moveToNext()) {
                arr.put(partyCursorToJson(it))
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
        val c = db.rawQuery("SELECT * FROM parties WHERE id=? AND is_deleted=0", arrayOf(id.toString()))
        return c.use { if (it.moveToFirst()) partyCursorToJson(it) else null }
    }

    fun getPartyById(id: Long): JSONObject? {
        return getParty(id.toInt())
    }

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
            val c = db.rawQuery(
                """SELECT * FROM parties 
                   WHERE (commercial_name LIKE ? OR commercial_name_ar LIKE ? OR party_code LIKE ? OR phone LIKE ?) 
                   AND is_deleted=0 ORDER BY commercial_name LIMIT 50""",
                arrayOf(likeQuery, likeQuery, likeQuery, likeQuery)
            )
            c.use {
                while (it.moveToNext()) {
                    arr.put(partyCursorToJson(it))
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

    // ================================================================
    // 24. TANKS & PUMPS
    // ================================================================
    fun getTanks(stationId: Int? = null): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        val sql = if (stationId != null) {
            "SELECT t.*, f.fuel_name, f.fuel_name_ar FROM tanks t LEFT JOIN fuel_types f ON t.fuel_type_id = f.id WHERE t.station_id=? AND t.is_deleted=0 ORDER BY t.tank_code"
        } else {
            "SELECT t.*, f.fuel_name, f.fuel_name_ar FROM tanks t LEFT JOIN fuel_types f ON t.fuel_type_id = f.id WHERE t.is_deleted=0 ORDER BY t.tank_code"
        }
        val args = if (stationId != null) arrayOf(stationId.toString()) else null
        val c = db.rawQuery(sql, args)
        c.use {
            while (it.moveToNext()) {
                arr.put(JSONObject().apply {
                    put("tank_id", it.getInt(it.getColumnIndexOrThrow("id")))
                    put("tank_code", it.getString(it.getColumnIndexOrThrow("tank_code")))
                    put("tank_name", it.getString(it.getColumnIndexOrThrow("tank_name")))
                    put("capacity_liters", it.getDouble(it.getColumnIndexOrThrow("capacity_liters")))
                    put("current_quantity", it.getDouble(it.getColumnIndexOrThrow("current_quantity")))
                    put("minimum_level", it.getDouble(it.getColumnIndexOrThrow("minimum_level")))
                    put("status", it.getString(it.getColumnIndexOrThrow("status")))
                    put("fuel_name", it.getString(it.getColumnIndexOrThrow("fuel_name")))
                    put("fuel_name_ar", it.getString(it.getColumnIndexOrThrow("fuel_name_ar")))
                })
            }
        }
        return arr
    }

    fun getPumps(stationId: Int? = null): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        val sql = if (stationId != null) {
            "SELECT p.*, t.tank_name FROM pumps p LEFT JOIN tanks t ON p.tank_id = t.id WHERE p.station_id=? AND p.is_deleted=0 ORDER BY p.pump_code"
        } else {
            "SELECT p.*, t.tank_name FROM pumps p LEFT JOIN tanks t ON p.tank_id = t.id WHERE p.is_deleted=0 ORDER BY p.pump_code"
        }
        val args = if (stationId != null) arrayOf(stationId.toString()) else null
        val c = db.rawQuery(sql, args)
        c.use {
            while (it.moveToNext()) {
                arr.put(JSONObject().apply {
                    put("pump_id", it.getInt(it.getColumnIndexOrThrow("id")))
                    put("pump_code", it.getString(it.getColumnIndexOrThrow("pump_code")))
                    put("pump_name", it.getString(it.getColumnIndexOrThrow("pump_name")))
                    put("pump_number", it.getString(it.getColumnIndexOrThrow("pump_number")))
                    put("tank_name", it.getString(it.getColumnIndexOrThrow("tank_name")))
                    put("meter_current", it.getDouble(it.getColumnIndexOrThrow("meter_current")))
                    put("status", it.getString(it.getColumnIndexOrThrow("status")))
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
        val c = db.rawQuery(sql, args)
        c.use {
            while (it.moveToNext()) {
                arr.put(JSONObject().apply {
                    put("nozzle_id", it.getInt(it.getColumnIndexOrThrow("id")))
                    put("nozzle_code", it.getString(it.getColumnIndexOrThrow("nozzle_code")))
                    put("nozzle_number", it.getString(it.getColumnIndexOrThrow("nozzle_number")))
                    put("fuel_name", it.getString(it.getColumnIndexOrThrow("fuel_name")))
                    put("meter_current", it.getDouble(it.getColumnIndexOrThrow("meter_current")))
                    put("status", it.getString(it.getColumnIndexOrThrow("status")))
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

    // ================================================================
    // 25. SHIFTS
    // ================================================================
    fun openShift(stationId: Int, shiftType: String, cashierId: Int, openingCash: Double, openingBank: Double = 0.0): Long {
        val shiftCode = "SHF-${System.currentTimeMillis()}"
        val cv = ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("shift_code", shiftCode)
            put("station_id", stationId)
            put("shift_date", SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()))
            put("shift_type", shiftType)
            put("start_time", DATE_FORMAT.format(Date()))
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
                put("end_time", DATE_FORMAT.format(Date()))
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
        val c = db.rawQuery(
            "SELECT * FROM shifts WHERE station_id=? ORDER BY id DESC LIMIT ?",
            arrayOf(stationId.toString(), limit.toString())
        )
        c.use {
            while (it.moveToNext()) {
                arr.put(JSONObject().apply {
                    put("shift_id", it.getInt(it.getColumnIndexOrThrow("id")))
                    put("shift_code", it.getString(it.getColumnIndexOrThrow("shift_code")))
                    put("shift_type", it.getString(it.getColumnIndexOrThrow("shift_type")))
                    put("shift_date", it.getString(it.getColumnIndexOrThrow("shift_date")))
                    put("start_time", it.getString(it.getColumnIndexOrThrow("start_time")))
                    put("end_time", it.getString(it.getColumnIndexOrThrow("end_time")))
                    put("opening_cash", it.getDouble(it.getColumnIndexOrThrow("opening_cash")))
                    put("closing_cash", it.getDouble(it.getColumnIndexOrThrow("closing_cash")))
                    put("total_sales", it.getDouble(it.getColumnIndexOrThrow("total_sales")))
                    put("status", it.getString(it.getColumnIndexOrThrow("status")))
                })
            }
        }
        return arr
    }

    fun getOpenShift(stationId: Int): JSONObject? {
        val db = readableDatabase
        val c = db.rawQuery(
            "SELECT * FROM shifts WHERE station_id=? AND status='open' ORDER BY id DESC LIMIT 1",
            arrayOf(stationId.toString())
        )
        return c.use {
            if (it.moveToFirst()) {
                JSONObject().apply {
                    put("shift_id", it.getInt(it.getColumnIndexOrThrow("id")))
                    put("shift_code", it.getString(it.getColumnIndexOrThrow("shift_code")))
                    put("opening_cash", it.getDouble(it.getColumnIndexOrThrow("opening_cash")))
                }
            } else null
        }
    }

    fun getCurrentShift(): JSONObject? {
        return getOpenShift(1)
    }

    // ================================================================
    // 26. SALES TRANSACTIONS
    // ================================================================
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
                    put("transaction_date", DATE_FORMAT.format(Date()))
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

    fun getSalesTransactions(stationId: Int, limit: Int = 200, offset: Int = 0): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        val c = db.rawQuery(
            """SELECT s.*, p.commercial_name as customer_name FROM sales_transactions s 
               LEFT JOIN parties p ON s.customer_party_id = p.id 
               WHERE s.station_id=? AND s.is_deleted=0 ORDER BY s.id DESC LIMIT ? OFFSET ?""",
            arrayOf(stationId.toString(), limit.toString(), offset.toString())
        )
        c.use {
            while (it.moveToNext()) {
                arr.put(saleCursorToJson(it).apply {
                    put("customer_name", it.getString(it.getColumnIndexOrThrow("customer_name")))
                })
            }
        }
        return arr
    }

    fun getSaleTransactionById(id: Int): JSONObject? {
        val db = readableDatabase
        val c = db.rawQuery("SELECT * FROM sales_transactions WHERE id=?", arrayOf(id.toString()))
        return c.use { if (it.moveToFirst()) saleCursorToJson(it) else null }
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

    // ================================================================
    // 27. ORDERS (باستخدام sales_transactions)
    // ================================================================
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
            val shiftId = getCurrentShift()?.optLong("shift_id", 1)?.toInt() ?: 1

            insertSaleTransaction(
                stationId = 1,
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
            val c = db.query("sales_transactions", null, selection, selectionArgs, null, null, "created_at DESC")
            cursorToJsonArray(c)
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 28. DELIVERIES (باستخدام sales_transactions مع delivery_location)
    // ================================================================
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
            val shiftId = getCurrentShift()?.optLong("shift_id", 1)?.toInt() ?: 1

            val saleId = insertSaleTransaction(
                stationId = 1,
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
            val c = db.rawQuery(
                """SELECT d.*, s.sale_code, s.delivery_location, s.delivery_time, s.created_at as sale_date
                   FROM deliveries d
                   LEFT JOIN sales_transactions s ON d.sale_id = s.id
                   WHERE d.is_deleted = 0
                   ORDER BY d.created_at DESC""",
                null
            )
            cursorToJsonArray(c)
        } finally {
            dbLock.unlock()
        }
    }

    fun getTodayDeliveries(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val today = getCurrentDate()
            val c = db.rawQuery(
                """SELECT d.*, s.sale_code, s.delivery_location, s.delivery_time
                   FROM deliveries d
                   LEFT JOIN sales_transactions s ON d.sale_id = s.id
                   WHERE d.delivery_date = ? AND d.is_deleted = 0
                   ORDER BY d.created_at DESC""",
                arrayOf(today)
            )
            cursorToJsonArray(c)
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 29. FUEL SALES (باستخدام sales_transactions)
    // ================================================================
    fun addFuelSale(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val liters = data.optDouble("quantity", 0.0)
            val pricePerLiter = data.optDouble("price_per_liter", getDieselPrice())
            val subtotal = liters * pricePerLiter
            val totalAmount = data.optDouble("total_amount", subtotal)
            val shiftId = data.optLong("shift_id", 1).toInt()
            val customerId = data.optLong("customer_id", 0).toInt()
            val pumpId = data.optLong("pump_id", 0).toInt()
            val fuelTypeId = data.optLong("fuel_type_id", 1).toInt()

            val saleId = insertSaleTransaction(
                stationId = 1,
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

    fun getSales(): JSONArray = getSalesTransactions(1, 10000)

    fun getTodaySales(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val today = getCurrentDate()
            val c = db.rawQuery(
                """SELECT s.*, f.fuel_name, p.commercial_name as customer_name
                   FROM sales_transactions s
                   LEFT JOIN fuel_types f ON s.fuel_type_id = f.id
                   LEFT JOIN parties p ON s.customer_party_id = p.id
                   WHERE date(s.created_at) = ? AND s.is_deleted = 0 AND s.sale_type = 'retail'
                   ORDER BY s.created_at DESC""",
                arrayOf(today)
            )
            cursorToJsonArray(c)
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 30. METER READINGS
    // ================================================================
    fun addMeterReading(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("reading_code", data.optString("reading_code", "MR-${System.currentTimeMillis()}"))
                put("pump_id", data.optLong("pump_id", 0))
                put("nozzle_id", data.optLong("nozzle_id", 0))
                put("station_id", data.optInt("station_id", 1))
                put("shift_id", data.optLong("shift_id", 0))
                put("reading_date", data.optString("reading_date", getCurrentDate()))
                put("period", data.optString("period", "morning"))
                put("opening_reading", data.optDouble("opening_reading", 0.0))
                put("closing_reading", data.optDouble("closing_reading", 0.0))
                put("sold_liters", data.optDouble("sold_liters", 0.0))
                put("read_by", data.optInt("read_by", 1))
                put("status", data.optString("status", "draft"))
                put("remarks", data.optString("notes", ""))
                put("created_at", getCurrentDateTime())
            }
            db.insert("meter_readings", null, cv)
        } finally {
            dbLock.unlock()
        }
    }

    fun getMeterReadings(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val c = db.rawQuery(
                """SELECT mr.*, p.pump_code, p.pump_name, n.nozzle_code, f.fuel_name
                   FROM meter_readings mr
                   LEFT JOIN pumps p ON mr.pump_id = p.id
                   LEFT JOIN pump_nozzles n ON mr.nozzle_id = n.id
                   LEFT JOIN fuel_types f ON n.fuel_type_id = f.id
                   WHERE mr.is_deleted = 0
                   ORDER BY mr.created_at DESC LIMIT 100""",
                null
            )
            cursorToJsonArray(c)
        } finally {
            dbLock.unlock()
        }
    }

    fun getLatestMeterReadings(): JSONArray = getMeterReadings()

    // ================================================================
    // 31. TANK READINGS
    // ================================================================
    fun addTankReading(data: JSONObject): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("tank_id", data.optLong("tank_id", 0))
                put("shift_id", data.optLong("shift_id", 0))
                put("reading_type", data.optString("reading_type", "dip"))
                put("fuel_level", data.optDouble("fuel_level", 0.0))
                put("fuel_volume", data.optDouble("fuel_volume", 0.0))
                put("temperature", data.optDouble("temperature", 0.0))
                put("water_level", data.optDouble("water_level", 0.0))
                put("reading_date", data.optString("reading_date", getCurrentDate()))
                put("reading_time", data.optString("reading_time", getCurrentTime()))
                put("notes", data.optString("notes", ""))
                put("created_by", data.optString("created_by", "system"))
                put("created_at", getCurrentDateTime())
            }
            db.insert("tank_readings", null, cv)
        } finally {
            dbLock.unlock()
        }
    }

    fun getTankReadings(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val c = db.rawQuery(
                """SELECT tr.*, t.tank_name, t.tank_code, f.fuel_name
                   FROM tank_readings tr
                   LEFT JOIN tanks t ON tr.tank_id = t.id
                   LEFT JOIN fuel_types f ON t.fuel_type_id = f.id
                   WHERE tr.is_deleted = 0
                   ORDER BY tr.created_at DESC LIMIT 100""",
                null
            )
            cursorToJsonArray(c)
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 32. STOCK MOVEMENTS (باستخدام inventory_movements)
    // ================================================================
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
            val cursor = db.rawQuery(
                "SELECT quantity_on_hand FROM inventory_levels WHERE product_id = ? AND warehouse_id = 1",
                arrayOf(productId.toString())
            )
            if (cursor.moveToFirst()) {
                currentQty = cursor.getDouble(0)
            }
            cursor.close()

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
                val exists = db.rawQuery(
                    "SELECT id FROM inventory_levels WHERE product_id = ? AND warehouse_id = 1",
                    arrayOf(productId.toString())
                )
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
                exists.close()
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
            val c = db.rawQuery(
                """SELECT im.*, p.product_name 
                   FROM inventory_movements im
                   LEFT JOIN products p ON im.product_id = p.id
                   WHERE im.is_deleted = 0
                   ORDER BY im.created_at DESC LIMIT 200""",
                null
            )
            cursorToJsonArray(c)
        } finally {
            dbLock.unlock()
        }
    }

    fun getLowStockItems(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val c = db.rawQuery(
                """SELECT p.id, p.product_name, p.product_name_ar, p.minimum_stock, 
                          COALESCE(il.quantity_on_hand, 0) as quantity_on_hand
                   FROM products p
                   LEFT JOIN inventory_levels il ON p.id = il.product_id AND il.warehouse_id = 1
                   WHERE p.is_deleted = 0 AND p.status = 'active'
                   AND (il.quantity_on_hand <= p.minimum_stock OR il.quantity_on_hand IS NULL)""",
                null
            )
            cursorToJsonArray(c)
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

    // ================================================================
    // 33. ASSETS (باستخدام fixed_assets والأساط الجديدة)
    // ================================================================
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

    fun getAssets(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val c = db.rawQuery(
                """SELECT * FROM assets WHERE is_deleted = 0 ORDER BY created_at DESC""",
                null
            )
            cursorToJsonArray(c)
        } finally {
            dbLock.unlock()
        }
    }

    fun getAssets(stationId: Int): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val c = db.rawQuery(
                """SELECT * FROM assets WHERE station_id = ? AND is_deleted = 0 ORDER BY created_at DESC""",
                arrayOf(stationId.toString())
            )
            cursorToJsonArray(c)
        } finally {
            dbLock.unlock()
        }
    }

    fun getAssetMaintenanceHistory(assetId: Long): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val c = db.rawQuery(
                """SELECT * FROM maintenance_requests 
                   WHERE asset_type = 'fixed_asset' AND asset_id = ? AND is_deleted = 0 
                   ORDER BY created_at DESC""",
                arrayOf(assetId.toString())
            )
            cursorToJsonArray(c)
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 34. USERS
    // ================================================================
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
            val c = db.rawQuery(
                """SELECT u.*, r.role_name, r.role_name_ar 
                   FROM users u
                   LEFT JOIN roles r ON u.role_id = r.id
                   WHERE u.is_deleted = 0
                   ORDER BY u.full_name""",
                null
            )
            cursorToJsonArray(c)
        } finally {
            dbLock.unlock()
        }
    }

    fun getUsersByRole(role: String): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val c = db.rawQuery(
                """SELECT u.*, r.role_name, r.role_name_ar 
                   FROM users u
                   LEFT JOIN roles r ON u.role_id = r.id
                   WHERE r.role_code = ? AND u.is_deleted = 0 AND u.status = 'active'
                   ORDER BY u.full_name""",
                arrayOf(role)
            )
            cursorToJsonArray(c)
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
            val c = db.rawQuery(
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
            )
            c.use {
                while (it.moveToNext()) {
                    arr.put(JSONObject().apply {
                        put("permission_code", it.getString(0))
                        put("permission_name", it.getString(1))
                        put("permission_name_ar", it.getString(2))
                        put("module", it.getString(3))
                        put("action", it.getString(4))
                        put("can_create", it.getInt(5) == 1)
                        put("can_read", it.getInt(6) == 1)
                        put("can_update", it.getInt(7) == 1)
                        put("can_delete", it.getInt(8) == 1)
                        put("can_export", it.getInt(9) == 1)
                        put("can_print", it.getInt(10) == 1)
                        put("can_approve", it.getInt(11) == 1)
                    })
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
            val c = db.rawQuery(
                """SELECT * FROM notifications WHERE user_id = ? AND is_deleted = 0 ORDER BY created_at DESC LIMIT 50""",
                arrayOf(userId.toString())
            )
            cursorToJsonArray(c)
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 35. EMPLOYEES
    // ================================================================
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
            val c = db.rawQuery(sql, args)
            cursorToJsonArray(c)
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
                    "UPDATE employees_old SET $col = $col $sign ?, net_salary = base_salary + bonuses - advances - penalties WHERE id = ?",
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

    // ================================================================
    // 36. ROLES
    // ================================================================
    fun getRoles(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val c = db.rawQuery("SELECT * FROM roles WHERE is_deleted = 0 ORDER BY level, role_name", null)
            cursorToJsonArray(c)
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

    // ================================================================
    // 37. MAINTENANCE REQUESTS
    // ================================================================
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
            val c = db.rawQuery(sql, args)
            cursorToJsonArray(c)
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

    // ================================================================
    // 38. SMS WHITELIST
    // ================================================================
    fun getSmsWhitelist(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val c = db.rawQuery("SELECT * FROM sms_whitelist ORDER BY name, phone", null)
            cursorToJsonArray(c)
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

    // ================================================================
    // 39. SMS LOGS
    // ================================================================
    fun getSmsLogs(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val c = db.rawQuery(
                """SELECT * FROM sms_logs ORDER BY created_at DESC LIMIT 500""",
                null
            )
            cursorToJsonArray(c)
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

    // ================================================================
    // 40. SMS MESSAGES (جدول sms_messages)
    // ================================================================
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

    // ================================================================
    // 41. PAYMENTS (مع العميل)
    // ================================================================
    fun getPaymentsWithCustomer(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val c = db.rawQuery(
                """SELECT p.*, pt.commercial_name as customer_name
                   FROM payments p
                   LEFT JOIN parties pt ON p.customer_party_id = pt.id
                   WHERE p.is_deleted = 0
                   ORDER BY p.created_at DESC""",
                null
            )
            cursorToJsonArray(c)
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

    // ================================================================
    // 42. CASH DEPOSITS
    // ================================================================
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

    // ================================================================
    // 43. REPORTS
    // ================================================================
    fun getDailySales(stationId: Int, date: String? = null): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val targetDate = date ?: getCurrentDate()
            val c = db.rawQuery(
                """SELECT s.*, f.fuel_name, p.commercial_name as customer_name
                   FROM sales_transactions s
                   LEFT JOIN fuel_types f ON s.fuel_type_id = f.id
                   LEFT JOIN parties p ON s.customer_party_id = p.id
                   WHERE s.station_id = ? AND date(s.created_at) = ? AND s.is_deleted = 0
                   ORDER BY s.created_at DESC""",
                arrayOf(stationId.toString(), targetDate)
            )
            cursorToJsonArray(c)
        } finally {
            dbLock.unlock()
        }
    }

    fun getMonthlySales(stationId: Int, month: Int? = null, year: Int? = null): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val cal = Calendar.getInstance()
            val m = month ?: (cal.get(Calendar.MONTH) + 1)
            val y = year ?: cal.get(Calendar.YEAR)
            val monthStr = String.format("%02d", m)
            val c = db.rawQuery(
                """SELECT strftime('%Y-%m-%d', created_at) as day,
                          COUNT(*) as transactions,
                          COALESCE(SUM(net_amount),0) as total_sales,
                          COALESCE(SUM(liters),0) as total_liters
                   FROM sales_transactions
                   WHERE station_id = ? AND strftime('%Y-%m', created_at) = ? AND is_deleted = 0
                   GROUP BY day
                   ORDER BY day""",
                arrayOf(stationId.toString(), "$y-$monthStr")
            )
            cursorToJsonArray(c)
        } finally {
            dbLock.unlock()
        }
    }

    fun getEodReport(stationId: Int, fromDate: String? = null, toDate: String? = null): JSONObject {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val from = fromDate ?: getCurrentDate()
            val to = toDate ?: getCurrentDate()
            val c = db.rawQuery(
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
            )
            val result = JSONObject()
            if (c.moveToFirst()) {
                result.put("total_sales", c.getDouble(0))
                result.put("cash_sales", c.getDouble(1))
                result.put("credit_sales", c.getDouble(2))
                result.put("bank_sales", c.getDouble(3))
                result.put("card_sales", c.getDouble(4))
                result.put("deferred_sales", c.getDouble(5))
                result.put("cash_sales_actual", c.getDouble(6))
                result.put("total_liters", c.getDouble(7))
                result.put("transaction_count", c.getInt(8))
                result.put("total_payments", c.getDouble(9))
            } else {
                result.put("total_sales", 0)
                result.put("total_payments", 0)
            }
            c.close()
            result
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 44. SETTINGS (get/set)
    // ================================================================
    fun getSetting(key: String): String {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val c = db.rawQuery("SELECT setting_value FROM system_settings WHERE setting_key = ?", arrayOf(key))
            val value = if (c.moveToFirst()) c.getString(0) else ""
            c.close()
            value
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

    // ================================================================
    // 45. AI CHAT HISTORY
    // ================================================================
    fun getAiChatHistory(sessionId: String): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val c = db.rawQuery(
                "SELECT * FROM ai_chat_history WHERE session_id = ? ORDER BY created_at ASC",
                arrayOf(sessionId)
            )
            cursorToJsonArray(c)
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

    // ================================================================
    // 46. EXPORT ALL DATA
    // ================================================================
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
                val cursor = db.query(table, null, null, null, null, null, null)
                val arr = cursorToJsonArray(cursor)
                result.put(table, arr)
            }
            result
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 47. GET PRODUCT CATEGORIES
    // ================================================================
    fun getProductCategories(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val c = db.rawQuery("SELECT * FROM product_categories WHERE is_deleted = 0 ORDER BY category_name", null)
            cursorToJsonArray(c)
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 48. GET FUEL NAME BY ID
    // ================================================================
    fun getFuelNameById(fuelTypeId: Int): String? {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val c = db.rawQuery("SELECT fuel_name FROM fuel_types WHERE id = ?", arrayOf(fuelTypeId.toString()))
            val name = if (c.moveToFirst()) c.getString(0) else null
            c.close()
            name
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 49. GET DIESEL PRICE, GASOLINE PRICE, MANAGER PHONE, RETENTION DAYS
    // ================================================================
    fun getDieselPrice(): Double {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val c = db.rawQuery(
                "SELECT default_sale_price FROM fuel_types WHERE fuel_code = 'DIESEL' AND is_deleted = 0 LIMIT 1",
                null
            )
            val price = if (c.moveToFirst()) c.getDouble(0) else 0.0
            c.close()
            price
        } finally {
            dbLock.unlock()
        }
    }

    fun getGasolinePrice(fuelCode: String = "PETROL_95"): Double {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val c = db.rawQuery(
                "SELECT default_sale_price FROM fuel_types WHERE fuel_code = ? AND is_deleted = 0 LIMIT 1",
                arrayOf(fuelCode)
            )
            val price = if (c.moveToFirst()) c.getDouble(0) else 0.0
            c.close()
            price
        } finally {
            dbLock.unlock()
        }
    }

    fun getManagerPhone(): String? {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val c = db.rawQuery("""
                SELECT u.phone FROM users u
                JOIN roles r ON u.role_id = r.id
                WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN', 'STATION_MANAGER')
                  AND u.status = 'active' AND u.is_deleted = 0
                ORDER BY r.level ASC LIMIT 1
            """.trimIndent(), null)
            val phone = if (c.moveToFirst()) c.getString(0) else null
            c.close()
            phone
        } finally {
            dbLock.unlock()
        }
    }

    fun getRetentionDays(): Int {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val c = db.rawQuery(
                "SELECT setting_value FROM system_settings WHERE setting_key = 'retention_days' LIMIT 1",
                null
            )
            val days = if (c.moveToFirst()) c.getInt(0) else 90
            c.close()
            days.coerceIn(7, 365)
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 50. NOTIFICATIONS
    // ================================================================
    fun getNotifications(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val c = db.rawQuery(
                """SELECT * FROM notifications WHERE is_deleted = 0 ORDER BY created_at DESC LIMIT 100""",
                null
            )
            cursorToJsonArray(c)
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 51. DASHBOARD STATS
    // ================================================================
    fun getDashboardStats(stationId: Int): JSONObject {
        val stats = JSONObject()
        val db = readableDatabase

        db.rawQuery(
            "SELECT COALESCE(SUM(net_amount),0), COALESCE(SUM(liters),0), COUNT(*) FROM sales_transactions WHERE station_id=? AND date(created_at) = date('now') AND is_deleted=0",
            arrayOf(stationId.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                stats.put("total_sales", c.getDouble(0))
                stats.put("total_liters", c.getDouble(1))
                stats.put("transactions_today", c.getInt(2))
            }
        }

        db.rawQuery("SELECT COALESCE(SUM(current_quantity),0) FROM tanks WHERE station_id=? AND is_deleted=0", arrayOf(stationId.toString())).use { c ->
            if (c.moveToFirst()) stats.put("total_remaining", c.getDouble(0))
        }

        db.rawQuery(
            "SELECT COALESCE(SUM(remaining_amount),0) FROM sales_transactions WHERE station_id=? AND payment_status IN ('pending','partial') AND is_deleted=0",
            arrayOf(stationId.toString())
        ).use { c ->
            if (c.moveToFirst()) stats.put("total_due", c.getDouble(0))
        }

        db.rawQuery("SELECT COUNT(*) FROM parties WHERE is_active=1 AND is_deleted=0", null).use { c ->
            if (c.moveToFirst()) stats.put("total_customers", c.getInt(0))
        }

        db.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(remaining_amount),0) FROM sales_transactions WHERE station_id=? AND is_credit=1 AND date(due_date) < date('now') AND is_deleted=0",
            arrayOf(stationId.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                stats.put("overdue_count", c.getInt(0))
                stats.put("overdue_amount", c.getDouble(1))
            }
        }

        return stats
    }

    // ================================================================
    // 52. LOG ACTIVITY (overloads)
    // ================================================================
    fun logActivity(operator: String, action: String, description: String): Long {
        dbLock.lock()
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("user_id", 0) // could be improved
                put("action", action)
                put("description", description)
                put("created_at", getCurrentDateTime())
            }
            db.insert("user_activity_log", null, cv)
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 53. HELPERS
    // ================================================================
    private fun getPartyBalance(partyId: Int): Double {
        val db = readableDatabase
        val c = db.rawQuery("SELECT COALESCE(current_balance,0) FROM parties WHERE id=?", arrayOf(partyId.toString()))
        return c.use { if (it.moveToFirst()) it.getDouble(0) else 0.0 }
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
        writableDatabase.execSQL(sql, bindArgs)
    }

    // ================================================================
    // 54. DYNAMIC DATA HELPERS (من SmsReceiver)
    // ================================================================
    fun getDriverPhones(): List<String> {
        val phones = mutableListOf<String>()
        val db = readableDatabase
        val c = db.rawQuery(
            "SELECT phone, phone2 FROM drivers WHERE status = 'active' AND is_deleted = 0",
            null
        )
        c.use {
            while (it.moveToNext()) {
                it.getString(0)?.let { p -> if (p.isNotBlank()) phones.add(p) }
                it.getString(1)?.let { p -> if (p.isNotBlank()) phones.add(p) }
            }
        }
        return phones.distinct()
    }

    fun getTrustedSmscList(): List<String> {
        val phones = mutableListOf<String>()
        val db = readableDatabase
        val c = db.rawQuery(
            "SELECT phone FROM sms_whitelist WHERE enabled = 1 ORDER BY name",
            null
        )
        c.use { while (it.moveToNext()) phones.add(it.getString(0)) }
        return phones
    }

    fun getCustomerBalanceByPhone(phone: String): Double {
        val db = readableDatabase
        val c = db.rawQuery("""
            SELECT current_balance FROM parties
            WHERE phone = ? AND is_deleted = 0
            LIMIT 1
        """.trimIndent(), arrayOf(phone))
        return c.use { if (it.moveToFirst()) it.getDouble(0) else 0.0 }
    }

    fun getLastOrderByPhone(phone: String): JSONObject? {
        val db = readableDatabase
        val c = db.rawQuery("""
            SELECT s.* FROM sales_transactions s
            JOIN parties p ON s.customer_party_id = p.id
            WHERE p.phone = ? AND s.is_deleted = 0
            ORDER BY s.id DESC LIMIT 1
        """.trimIndent(), arrayOf(phone))
        return c.use {
            if (it.moveToFirst()) {
                JSONObject().apply {
                    put("sale_code", it.getString(it.getColumnIndexOrThrow("sale_code")))
                    put("liters", it.getDouble(it.getColumnIndexOrThrow("liters")))
                    put("delivery_location", it.getString(it.getColumnIndexOrThrow("delivery_location")))
                    put("status", it.getString(it.getColumnIndexOrThrow("status")))
                    put("created_at", it.getString(it.getColumnIndexOrThrow("created_at")))
                }
            } else null
        }
    }

    fun getOrderHistoryByPhone(phone: String, limit: Int = 50): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        val c = db.rawQuery("""
            SELECT s.sale_code, s.liters, s.net_amount, s.created_at
            FROM sales_transactions s
            JOIN parties p ON s.customer_party_id = p.id
            WHERE p.phone = ? AND s.is_deleted = 0
            ORDER BY s.id DESC LIMIT ?
        """.trimIndent(), arrayOf(phone, limit.toString()))
        c.use {
            while (it.moveToNext()) {
                arr.put(JSONObject().apply {
                    put("sale_code", it.getString(0))
                    put("liters", it.getDouble(1))
                    put("net_amount", it.getDouble(2))
                    put("created_at", it.getString(3))
                })
            }
        }
        return arr
    }

    fun getOrderHistoryByPhone(phone: String): JSONArray = getOrderHistoryByPhone(phone, 50)

    fun getPartyIdByPhone(phone: String): Int? {
        val db = readableDatabase
        val cleanPhone = phone.replace("[^0-9]".toRegex(), "").takeLast(9)
        val c = db.rawQuery(
            "SELECT id FROM parties WHERE phone = ? AND is_deleted = 0 LIMIT 1",
            arrayOf(cleanPhone)
        )
        return c.use { if (it.moveToFirst()) it.getInt(0) else null }
    }

    fun getSystemSetting(key: String, defaultValue: String = ""): String {
        val db = readableDatabase
        val c = db.rawQuery(
            "SELECT setting_value FROM system_settings WHERE setting_key = ? LIMIT 1",
            arrayOf(key)
        )
        return c.use { if (it.moveToFirst()) it.getString(0) else defaultValue }
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
            val shiftId = getCurrentShift()?.optLong("shift_id", 1)?.toInt() ?: 1

            val saleId = insertSaleTransaction(
                stationId = 1,
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
                dueDate = dateOnlyFormat.format(Date()),
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

    // ================================================================
    // 55. SALES BY FUEL TYPE
    // ================================================================
    fun getSalesByFuelType(): JSONArray {
        val arr = JSONArray()
        val db = readableDatabase
        val c = db.rawQuery("""
            SELECT f.fuel_name, f.fuel_name_ar, 
                   COALESCE(SUM(s.liters), 0) as total_liters,
                   COALESCE(SUM(s.net_amount), 0) as total_amount,
                   COUNT(*) as transaction_count
            FROM fuel_types f
            LEFT JOIN sales_transactions s ON s.fuel_type_id = f.id AND s.is_deleted = 0
            WHERE f.is_deleted = 0
            GROUP BY f.id
            ORDER BY total_amount DESC
        """.trimIndent(), null)
        c.use {
            while (it.moveToNext()) {
                arr.put(JSONObject().apply {
                    put("fuel_name", it.getString(0))
                    put("fuel_name_ar", it.getString(1))
                    put("total_liters", it.getDouble(2))
                    put("total_amount", it.getDouble(3))
                    put("transaction_count", it.getInt(4))
                })
            }
        }
        return arr
    }

    // ================================================================
    // 56. GET CUSTOMER COUNT
    // ================================================================
    fun getCustomerCount(): Int {
        val db = readableDatabase
        val c = db.rawQuery("SELECT COUNT(*) FROM parties WHERE party_type_id = 1 AND is_deleted = 0", null)
        val count = if (c.moveToFirst()) c.getInt(0) else 0
        c.close()
        return count
    }

    // ================================================================
    // 57. GET DRIVER PHONES (للتكامل مع SmsReceiver)
    // ================================================================
    fun getDriverPhonesList(): JSONArray {
        val arr = JSONArray()
        getDriverPhones().forEach { arr.put(it) }
        return arr
    }

    // ================================================================
    // 58. دوال CASH MOVEMENTS (المضافة في V12)
    // ================================================================
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
            val c = db.rawQuery(
                """SELECT * FROM cash_movements WHERE is_deleted = 0 ORDER BY created_at DESC""",
                null
            )
            cursorToJsonArray(c)
        } finally {
            dbLock.unlock()
        }
    }

    fun getTodayCash(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val today = getCurrentDate()
            val c = db.rawQuery(
                """SELECT * FROM cash_movements WHERE date(created_at) = ? AND is_deleted = 0 ORDER BY created_at DESC""",
                arrayOf(today)
            )
            cursorToJsonArray(c)
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 59. دوال SHIFTS (الإضافية)
    // ================================================================
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
            val c = db.rawQuery(
                """SELECT 'sales' as type, COUNT(*) as count, COALESCE(SUM(net_amount), 0) as total
                   FROM sales_transactions WHERE shift_id = ? AND is_deleted = 0
                   UNION ALL
                   SELECT 'deliveries', COUNT(*), COALESCE(SUM(total_amount), 0)
                   FROM deliveries WHERE shift_id = ? AND is_deleted = 0
                   UNION ALL
                   SELECT 'expenses', COUNT(*), COALESCE(SUM(amount), 0)
                   FROM shift_expenses WHERE shift_id = ?""",
                arrayOf(shiftId.toString(), shiftId.toString(), shiftId.toString())
            )
            c.use {
                while (it.moveToNext()) {
                    arr.put(JSONObject().apply {
                        put("type", it.getString(0))
                        put("count", it.getInt(1))
                        put("total", it.getDouble(2))
                    })
                }
            }
            arr
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 60. دوال NOTIFICATIONS (الإضافية)
    // ================================================================
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
            val c = db.rawQuery("SELECT COUNT(*) FROM notifications WHERE is_read = 0 AND is_deleted = 0", null)
            val count = if (c.moveToFirst()) c.getInt(0) else 0
            c.close()
            count
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

    // ================================================================
    // 61. دوال SMS MESSAGES (من جدول sms_messages) - دوال إضافية
    // ================================================================
    fun getSmsMessages(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val c = db.rawQuery(
                """SELECT * FROM sms_messages ORDER BY created_at DESC LIMIT 500""",
                null
            )
            cursorToJsonArray(c)
        } finally {
            dbLock.unlock()
        }
    }

    fun getSmsMessagesByPhone(phone: String): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val c = db.rawQuery(
                """SELECT * FROM sms_messages WHERE phone_number = ? ORDER BY created_at DESC""",
                arrayOf(phone)
            )
            cursorToJsonArray(c)
        } finally {
            dbLock.unlock()
        }
    }

    fun getSmsMessagesByStatus(status: String): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val c = db.rawQuery(
                """SELECT * FROM sms_messages WHERE status = ? ORDER BY created_at DESC""",
                arrayOf(status)
            )
            cursorToJsonArray(c)
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
            val c1 = db.rawQuery("SELECT COUNT(*) FROM sms_messages", null)
            stats.put("total", if (c1.moveToFirst()) c1.getInt(0) else 0)
            c1.close()
            val c2 = db.rawQuery("SELECT COUNT(*) FROM sms_messages WHERE status = 'sent'", null)
            stats.put("sent", if (c2.moveToFirst()) c2.getInt(0) else 0)
            c2.close()
            val c3 = db.rawQuery("SELECT COUNT(*) FROM sms_messages WHERE status = 'pending'", null)
            stats.put("pending", if (c3.moveToFirst()) c3.getInt(0) else 0)
            c3.close()
            val c4 = db.rawQuery("SELECT COUNT(*) FROM sms_messages WHERE status = 'failed'", null)
            stats.put("failed", if (c4.moveToFirst()) c4.getInt(0) else 0)
            c4.close()
            stats
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 62. دوال SMS TEMPLATES
    // ================================================================
    fun getSmsTemplates(): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val c = db.rawQuery("SELECT * FROM sms_templates ORDER BY created_at DESC", null)
            cursorToJsonArray(c)
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

    // ================================================================
    // 63. دوال SETTINGS (الإضافية)
    // ================================================================
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
            val c = db.rawQuery("SELECT setting_key, setting_value FROM settings", null)
            c.use {
                while (it.moveToNext()) {
                    map[it.getString(0)] = it.getString(1)
                }
            }
            map
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 64. دوال التقارير والتنبيهات
    // ================================================================
    fun getOverduePayments(): JSONArray {
        dbLock.lock()
        return try {
            val arr = JSONArray()
            val db = readableDatabase
            val c = db.rawQuery(
                """SELECT s.*, p.commercial_name as customer_name, p.phone as customer_phone
                   FROM sales_transactions s
                   LEFT JOIN parties p ON s.customer_party_id = p.id
                   WHERE s.remaining_amount > 0 AND date(s.due_date) < date('now') AND s.is_deleted=0
                   ORDER BY s.due_date""",
                null
            )
            c.use {
                while (it.moveToNext()) {
                    arr.put(JSONObject().apply {
                        put("sale_id", it.getInt(it.getColumnIndexOrThrow("id")))
                        put("customer_party_id", it.getInt(it.getColumnIndexOrThrow("customer_party_id")))
                        put("customer_name", it.getString(it.getColumnIndexOrThrow("customer_name")))
                        put("customer_phone", it.getString(it.getColumnIndexOrThrow("customer_phone")))
                        put("remaining_amount", it.getDouble(it.getColumnIndexOrThrow("remaining_amount")))
                        put("due_date", it.getString(it.getColumnIndexOrThrow("due_date")))
                        put("invoice_number", it.getString(it.getColumnIndexOrThrow("invoice_number")))
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
            val c = db.rawQuery(
                """SELECT * FROM stock_alerts WHERE is_resolved = 0 ORDER BY created_at DESC""",
                null
            )
            cursorToJsonArray(c)
        } finally {
            dbLock.unlock()
        }
    }

    fun getRecentActivity(limit: Int): JSONArray {
        dbLock.lock()
        return try {
            val db = readableDatabase
            val c = db.rawQuery(
                """SELECT * FROM user_activity_log ORDER BY created_at DESC LIMIT ?""",
                arrayOf(limit.toString())
            )
            cursorToJsonArray(c)
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 65. دوال VEHICLES
    // ================================================================
    fun getVehicles(): JSONArray {
        dbLock.lock()
        return try {
            val arr = JSONArray()
            val db = readableDatabase
            val c = db.rawQuery(
                """SELECT v.*, p.commercial_name as party_name, d.full_name as driver_name
                   FROM vehicles v
                   LEFT JOIN parties p ON v.party_id = p.id
                   LEFT JOIN drivers d ON v.id = d.vehicle_id
                   WHERE v.is_deleted = 0
                   ORDER BY v.plate_number""",
                null
            )
            c.use {
                while (it.moveToNext()) {
                    arr.put(JSONObject().apply {
                        put("vehicle_id", it.getInt(it.getColumnIndexOrThrow("id")))
                        put("vehicle_code", it.getString(it.getColumnIndexOrThrow("vehicle_code")))
                        put("plate_number", it.getString(it.getColumnIndexOrThrow("plate_number")))
                        put("brand", it.getString(it.getColumnIndexOrThrow("brand")))
                        put("model", it.getString(it.getColumnIndexOrThrow("model")))
                        put("tank_capacity", it.getDouble(it.getColumnIndexOrThrow("tank_capacity")))
                        put("status", it.getString(it.getColumnIndexOrThrow("status")))
                        put("party_name", it.getString(it.getColumnIndexOrThrow("party_name")))
                        put("driver_name", it.getString(it.getColumnIndexOrThrow("driver_name")))
                    })
                }
            }
            arr
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 66. دوال TANK STATS
    // ================================================================
    fun getTankStats(): JSONArray {
        dbLock.lock()
        return try {
            val arr = JSONArray()
            val db = readableDatabase
            val c = db.rawQuery(
                """SELECT t.*, f.fuel_name, f.fuel_name_ar,
                          ROUND((t.current_quantity / t.capacity_liters * 100), 2) as fill_percent
                   FROM tanks t
                   LEFT JOIN fuel_types f ON t.fuel_type_id = f.id
                   WHERE t.is_deleted = 0
                   ORDER BY t.tank_code""",
                null
            )
            c.use {
                while (it.moveToNext()) {
                    arr.put(JSONObject().apply {
                        put("tank_id", it.getInt(it.getColumnIndexOrThrow("id")))
                        put("tank_code", it.getString(it.getColumnIndexOrThrow("tank_code")))
                        put("tank_name", it.getString(it.getColumnIndexOrThrow("tank_name")))
                        put("capacity_liters", it.getDouble(it.getColumnIndexOrThrow("capacity_liters")))
                        put("current_quantity", it.getDouble(it.getColumnIndexOrThrow("current_quantity")))
                        put("fill_percent", it.getDouble(it.getColumnIndexOrThrow("fill_percent")))
                        put("fuel_name", it.getString(it.getColumnIndexOrThrow("fuel_name")))
                        put("status", it.getString(it.getColumnIndexOrThrow("status")))
                    })
                }
            }
            arr
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 67. دوال BACKUP & EXPORT
    // ================================================================
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
            val cursor = db.query(tableName, null, null, null, null, null, null)
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
            cursor.close()

            val exportDir = File(contextRef.getExternalFilesDir(null), "exports")
            if (!exportDir.exists()) exportDir.mkdirs()
            val exportFile = File(exportDir, "${tableName}_${System.currentTimeMillis()}.csv")
            exportFile.writeText(csv.toString())
            exportFile.absolutePath
        } finally {
            dbLock.unlock()
        }
    }

    fun importFromCSV(tableName: String, path: String): Int {
        // تنفيذ الاستيراد حسب الحاجة (يمكن تنفيذها لاحقاً)
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
                val cursor = db.rawQuery("SELECT COUNT(*) FROM $table", null)
                val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
                cursor.close()
                val obj = JSONObject()
                obj.put("table", table)
                obj.put("count", count)
                result.put(obj)
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

    // ================================================================
    // 68. دوال PRODUCTS (مع overload)
    // ================================================================
    fun getProducts(): JSONArray {
        return getProducts(null)
    }

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
            val c = db.rawQuery(sql, args)
            c.use {
                while (it.moveToNext()) {
                    arr.put(JSONObject().apply {
                        put("product_id", it.getInt(it.getColumnIndexOrThrow("id")))
                        put("product_code", it.getString(it.getColumnIndexOrThrow("product_code")))
                        put("product_name", it.getString(it.getColumnIndexOrThrow("product_name")))
                        put("product_name_ar", it.getString(it.getColumnIndexOrThrow("product_name_ar")))
                        put("category_name", it.getString(it.getColumnIndexOrThrow("category_name")))
                        put("sale_price", it.getDouble(it.getColumnIndexOrThrow("sale_price")))
                        put("purchase_price", it.getDouble(it.getColumnIndexOrThrow("purchase_price")))
                        put("quantity", it.getDouble(it.getColumnIndexOrThrow("quantity")))
                        put("status", it.getString(it.getColumnIndexOrThrow("status")))
                    })
                }
            }
            arr
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 69. دوال FUEL TYPES (مع overload)
    // ================================================================
    fun getFuelTypes(): JSONArray {
        dbLock.lock()
        return try {
            val arr = JSONArray()
            val db = readableDatabase
            val c = db.rawQuery(
                "SELECT id, fuel_code, fuel_name, fuel_name_ar, default_sale_price, is_active FROM fuel_types WHERE is_deleted=0 ORDER BY fuel_name",
                null
            )
            c.use {
                while (it.moveToNext()) {
                    arr.put(JSONObject().apply {
                        put("fuel_type_id", it.getInt(0))
                        put("fuel_code", it.getString(1))
                        put("fuel_name", it.getString(2))
                        put("fuel_name_ar", it.getString(3))
                        put("default_sale_price", it.getDouble(4))
                        put("is_active", it.getInt(5) == 1)
                    })
                }
            }
            arr
        } finally {
            dbLock.unlock()
        }
    }

    // ================================================================
    // 70. دوال INSERT/UPDATE/DELETE PRODUCT (لـ SMSService)
    // ================================================================
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
}
