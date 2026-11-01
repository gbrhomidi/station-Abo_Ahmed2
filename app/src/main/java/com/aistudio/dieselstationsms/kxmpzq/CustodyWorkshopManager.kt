package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Native SQLite workflows for employee operating imprests and station workshop work orders.
 *
 * The manager is deliberately independent of UI state. Every write is station-scoped,
 * permission-gated, idempotent, transactional, and auditable. No network or mock store is used.
 */
object CustodyWorkshopManager {
    private const val CUSTODY = "custody"
    private const val WORKSHOP = "workshop"

    fun ensureSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS operating_custodies (
            id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL UNIQUE,
            custody_code TEXT NOT NULL UNIQUE, station_id INTEGER NOT NULL,
            employee_id INTEGER NOT NULL, shift_id INTEGER, cost_center TEXT,
            purpose TEXT NOT NULL, requested_amount REAL NOT NULL CHECK(requested_amount>0),
            approved_amount REAL NOT NULL DEFAULT 0 CHECK(approved_amount>=0),
            issued_amount REAL NOT NULL DEFAULT 0 CHECK(issued_amount>=0),
            spent_amount REAL NOT NULL DEFAULT 0 CHECK(spent_amount>=0),
            returned_amount REAL NOT NULL DEFAULT 0 CHECK(returned_amount>=0),
            outstanding_amount REAL NOT NULL DEFAULT 0 CHECK(outstanding_amount>=0),
            status TEXT NOT NULL DEFAULT 'draft' CHECK(status IN
              ('draft','approved','issued','settlement_pending','settled','shortage_pending',
               'overage_pending','cancelled','reversed')),
            custody_account_id INTEGER, cash_box_id INTEGER, bank_account_id INTEGER,
            created_by INTEGER NOT NULL, approved_by INTEGER, approved_at TEXT,
            issued_at TEXT, settled_by INTEGER, settled_at TEXT,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, is_deleted INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS custody_movements (
            id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL UNIQUE,
            custody_id INTEGER NOT NULL, station_id INTEGER NOT NULL,
            movement_type TEXT NOT NULL CHECK(movement_type IN
              ('issue','expense','cash_return','shortage','overage','transfer','payroll_recovery',
               'deposit_transit','purchase','reversal')),
            amount REAL NOT NULL CHECK(amount>=0), expense_type TEXT, employee_id INTEGER,
            product_id INTEGER, warehouse_id INTEGER, quantity REAL,
            cash_box_id INTEGER, bank_account_id INTEGER, document_no TEXT, document_hash TEXT,
            reference_type TEXT, reference_id INTEGER, journal_entry_id INTEGER,
            status TEXT NOT NULL DEFAULT 'completed', notes TEXT, created_by INTEGER NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS custody_documents (
            id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL UNIQUE,
            station_id INTEGER NOT NULL, custody_id INTEGER NOT NULL, movement_id INTEGER,
            document_no TEXT NOT NULL, document_hash TEXT NOT NULL, document_type TEXT NOT NULL,
            amount REAL NOT NULL DEFAULT 0, file_uri TEXT, created_by INTEGER NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(station_id, document_hash), UNIQUE(station_id, document_no)
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS custody_audit_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL UNIQUE,
            custody_id INTEGER NOT NULL, station_id INTEGER NOT NULL, action TEXT NOT NULL,
            old_value TEXT, new_value TEXT, performed_by INTEGER NOT NULL,
            performed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS work_orders (
            id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL UNIQUE,
            order_code TEXT NOT NULL UNIQUE, station_id INTEGER NOT NULL,
            asset_type TEXT NOT NULL, asset_id INTEGER NOT NULL, maintenance_request_id INTEGER,
            customer_party_id INTEGER, vendor_party_id INTEGER, cost_center TEXT,
            title TEXT NOT NULL, description TEXT, priority TEXT NOT NULL DEFAULT 'normal',
            status TEXT NOT NULL DEFAULT 'draft' CHECK(status IN
              ('draft','approved','in_progress','on_hold','completed','closed','cancelled','reversed')),
            estimate_labor_cost REAL NOT NULL DEFAULT 0 CHECK(estimate_labor_cost>=0),
            estimate_parts_cost REAL NOT NULL DEFAULT 0 CHECK(estimate_parts_cost>=0),
            actual_labor_cost REAL NOT NULL DEFAULT 0 CHECK(actual_labor_cost>=0),
            actual_parts_cost REAL NOT NULL DEFAULT 0 CHECK(actual_parts_cost>=0),
            invoiced_amount REAL NOT NULL DEFAULT 0 CHECK(invoiced_amount>=0),
            bill_to_customer INTEGER NOT NULL DEFAULT 0,
            warranty_status TEXT, downtime_minutes REAL NOT NULL DEFAULT 0,
            created_by INTEGER NOT NULL, approved_by INTEGER, approved_at TEXT,
            started_at TEXT, completed_at TEXT, closed_at TEXT, cancelled_at TEXT,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, is_deleted INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS work_order_parts (
            id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL UNIQUE,
            work_order_id INTEGER NOT NULL, station_id INTEGER NOT NULL, product_id INTEGER NOT NULL,
            warehouse_id INTEGER NOT NULL, movement_type TEXT NOT NULL
              CHECK(movement_type IN ('issue','return','purchase','damage')),
            quantity REAL NOT NULL CHECK(quantity>0), returned_quantity REAL NOT NULL DEFAULT 0,
            damaged_quantity REAL NOT NULL DEFAULT 0, unit_cost REAL NOT NULL CHECK(unit_cost>=0),
            total_cost REAL NOT NULL CHECK(total_cost>=0), inventory_movement_id INTEGER,
            journal_entry_id INTEGER, reference_id INTEGER, created_by INTEGER NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS work_order_labor (
            id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL UNIQUE,
            work_order_id INTEGER NOT NULL, station_id INTEGER NOT NULL, employee_id INTEGER NOT NULL,
            work_date TEXT NOT NULL, hours REAL NOT NULL CHECK(hours>0),
            hourly_rate REAL NOT NULL CHECK(hourly_rate>=0), amount REAL NOT NULL CHECK(amount>=0),
            notes TEXT, journal_entry_id INTEGER, created_by INTEGER NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS work_order_invoices (
            id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL UNIQUE,
            invoice_code TEXT NOT NULL, station_id INTEGER NOT NULL, work_order_id INTEGER NOT NULL,
            invoice_type TEXT NOT NULL CHECK(invoice_type IN ('customer','vendor')),
            party_id INTEGER, subtotal REAL NOT NULL CHECK(subtotal>0),
            tax_amount REAL NOT NULL DEFAULT 0 CHECK(tax_amount>=0),
            total_amount REAL NOT NULL CHECK(total_amount>0), status TEXT NOT NULL DEFAULT 'posted',
            journal_entry_id INTEGER NOT NULL, idempotency_key TEXT NOT NULL,
            created_by INTEGER NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(station_id, invoice_code), UNIQUE(station_id, idempotency_key)
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS work_order_capitalizations (
            id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL UNIQUE,
            work_order_id INTEGER NOT NULL, station_id INTEGER NOT NULL,
            decision TEXT NOT NULL CHECK(decision IN ('expense','capitalize')),
            amount REAL NOT NULL CHECK(amount>0), journal_entry_id INTEGER NOT NULL,
            approval_reference TEXT, created_by INTEGER NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS work_order_warranty_claims (
            id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL UNIQUE,
            work_order_id INTEGER NOT NULL, station_id INTEGER NOT NULL,
            claim_type TEXT NOT NULL, supplier_party_id INTEGER, reference_no TEXT,
            claim_amount REAL NOT NULL DEFAULT 0 CHECK(claim_amount>=0),
            status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','approved','rejected','recovered')),
            notes TEXT, created_by INTEGER NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS work_order_downtime (
            id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL UNIQUE,
            work_order_id INTEGER NOT NULL, station_id INTEGER NOT NULL, asset_type TEXT NOT NULL,
            asset_id INTEGER NOT NULL, started_at TEXT NOT NULL, ended_at TEXT,
            downtime_minutes REAL NOT NULL DEFAULT 0, lost_sales_estimate REAL NOT NULL DEFAULT 0,
            notes TEXT, created_by INTEGER NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS workshop_budgets (
            id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL UNIQUE,
            station_id INTEGER NOT NULL, budget_year INTEGER NOT NULL, budget_month INTEGER NOT NULL,
            cost_center TEXT NOT NULL, budget_amount REAL NOT NULL CHECK(budget_amount>=0),
            created_by INTEGER NOT NULL, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(station_id,budget_year,budget_month,cost_center)
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS work_order_audit_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL UNIQUE,
            work_order_id INTEGER NOT NULL, station_id INTEGER NOT NULL, action TEXT NOT NULL,
            old_value TEXT, new_value TEXT, performed_by INTEGER NOT NULL,
            performed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS custody_workshop_idempotency (
            id INTEGER PRIMARY KEY AUTOINCREMENT, module TEXT NOT NULL, action TEXT NOT NULL,
            station_id INTEGER NOT NULL, idempotency_key TEXT NOT NULL, resource_id INTEGER NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(module,action,station_id,idempotency_key)
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_custody_station_status ON operating_custodies(station_id,status,is_deleted)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_custody_employee_open ON operating_custodies(station_id,employee_id,status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_custody_movements_custody ON custody_movements(custody_id,created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_work_orders_station_status ON work_orders(station_id,status,is_deleted)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_work_order_parts_order ON work_order_parts(work_order_id,product_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_work_order_labor_order ON work_order_labor(work_order_id,employee_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_work_order_invoice_station ON work_order_invoices(station_id,invoice_type,created_at)")
        ensurePermissions(db)
    }

    private fun ensurePermissions(db: SQLiteDatabase) {
        val modules = listOf(CUSTODY to "العهد التشغيلية", WORKSHOP to "ورش الصيانة")
        val actions = listOf("read", "create", "update", "delete", "approve", "export")
        modules.forEach { module ->
            actions.forEach { action ->
                val code = module.first + "." + action
                db.execSQL("""INSERT OR IGNORE INTO permissions
                    (uuid,permission_code,permission_name,permission_name_ar,module,module_name_ar,action,requires_station,is_active,is_deleted)
                    VALUES (?,?,?,?,?,?,?,1,1,0)""",
                    arrayOf("PER-CW-" + module.first.uppercase(Locale.ROOT) + "-" + action.uppercase(Locale.ROOT),
                        code, code, module.second + " - " + action, module.first, module.second, action))
                db.execSQL("""INSERT OR IGNORE INTO role_permissions
                    (uuid,role_id,permission_id,can_create,can_read,can_update,can_delete,can_export,can_print,can_approve)
                    SELECT ?,1,id,?,?,?,?,?,0,? FROM permissions WHERE permission_code=?""",
                    arrayOf("RP-CW-ADMIN-" + module.first + "-" + action,
                        if (action == "create") 1 else 0,
                        if (action == "read") 1 else 0,
                        if (action == "update") 1 else 0,
                        if (action == "delete") 1 else 0,
                        if (action == "export") 1 else 0,
                        if (action == "approve") 1 else 0,
                        code))
            }
        }
    }

    fun executeCustody(helper: DatabaseHelper, stationId: Int, actorId: Long,
                       action: String, input: JSONObject): JSONObject {
        require(stationId > 0 && actorId > 0L)
        val permissionAction = when (action) {
            "approve" -> "approve"
            "disburse" -> "create"
            "settle", "return", "expense", "variance", "resolve_variance", "transfer", "recover",
            "configure" -> "update"
            "reverse" -> "delete"
            "ageing", "reconcile", "get", "list" -> "read"
            else -> if (action == "create") "create" else "update"
        }
        if (!helper.hasPermission(actorId, "custody." + permissionAction))
            throw SecurityException("لا تملك صلاحية العهد التشغيلية: " + permissionAction)
        val db = helper.writableDatabase
        return transaction(db) {
            if (action == "ageing") return@transaction custodyAgeing(db, stationId)
            if (action == "reconcile") return@transaction custodyReconcile(db, stationId)
            if (action == "list") return@transaction listCustodies(db, stationId, input)
            if (action == "get") return@transaction getCustody(db, stationId, input.optLong("custody_id"))
            idempotent(db, CUSTODY, action, stationId, input) { key ->
                when (action) {
                    "create" -> createCustody(db, stationId, actorId, input, key)
                    "approve" -> approveCustody(db, stationId, actorId, input)
                    "disburse" -> disburseCustody(helper, db, stationId, actorId, input)
                    "expense" -> custodyExpense(db, stationId, actorId, input)
                    "return" -> returnCustody(db, stationId, actorId, input)
                    "settle" -> settleCustody(db, stationId, actorId, input)
                    "variance" -> recordCustodyVariance(db, stationId, actorId, input)
                    "resolve_variance" -> resolveCustodyVariance(db, stationId, actorId, input)
                    "transfer" -> transferCustody(db, stationId, actorId, input)
                    "recover" -> recoverCustody(db, stationId, actorId, input)
                    "reverse" -> reverseCustody(db, stationId, actorId, input)
                    "configure" -> configureCustodyAccount(db, stationId, actorId, input)
                    else -> error("عملية عهدة غير مدعومة: " + action)
                }
            }
        }
    }

    fun executeWorkshop(helper: DatabaseHelper, stationId: Int, actorId: Long,
                        action: String, input: JSONObject): JSONObject {
        require(stationId > 0 && actorId > 0L)
        val permissionAction = when (action) {
            "read", "list", "profitability", "reconcile", "budget_report" -> "read"
            "create" -> "create"
            "approve" -> "approve"
            "issue_part" -> "create"
            "invoice", "purchase_part" -> "create"
            "close" -> "update"
            "reverse" -> "delete"
            else -> "update"
        }
        if (!helper.hasPermission(actorId, "workshop." + permissionAction))
            throw SecurityException("لا تملك صلاحية ورشة الصيانة: " + permissionAction)
        val db = helper.writableDatabase
        return transaction(db) {
            when (action) {
                "profitability" -> workshopProfitability(db, stationId, input)
                "budget_report" -> workshopBudgetReport(db, stationId, input)
                "reconcile" -> workshopReconcile(db, stationId, input)
                "list" -> listWorkOrders(db, stationId, input)
                "read" -> getWorkOrder(db, stationId, input.optLong("work_order_id"))
                else -> idempotent(db, WORKSHOP, action, stationId, input) { key ->
                    when (action) {
                        "create" -> createWorkOrder(db, stationId, actorId, input)
                        "approve" -> approveWorkOrder(db, stationId, actorId, input)
                        "start" -> startWorkOrder(db, stationId, actorId, input)
                        "labor" -> addWorkOrderLabor(db, stationId, actorId, input)
                        "issue_part" -> issueWorkOrderPart(db, stationId, actorId, input)
                        "return_part" -> returnWorkOrderPart(db, stationId, actorId, input)
                        "purchase_part" -> purchaseWorkOrderPart(db, stationId, actorId, input)
                        "estimate" -> updateWorkOrderEstimate(db, stationId, actorId, input)
                        "invoice" -> invoiceWorkOrder(db, stationId, actorId, input, key)
                        "close" -> closeWorkOrder(db, stationId, actorId, input)
                        "cancel" -> cancelWorkOrder(db, stationId, actorId, input)
                        "warranty" -> createWarrantyClaim(db, stationId, actorId, input)
                        "downtime" -> recordDowntime(db, stationId, actorId, input)
                        "damage_part" -> damageWorkOrderPart(db, stationId, actorId, input)
                        "capitalize" -> capitalizeWorkOrderCost(db, stationId, actorId, input)
                        "stocktake" -> stocktake(db, stationId, actorId, input)
                        "budget" -> saveWorkshopBudget(db, stationId, actorId, input)
                        "reverse" -> reverseWorkOrder(db, stationId, actorId, input)
                        else -> error("عملية ورشة غير مدعومة: " + action)
                    }
                }
            }
        }
    }

    private inline fun transaction(db: SQLiteDatabase, block: () -> JSONObject): JSONObject {
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private inline fun idempotent(db: SQLiteDatabase, module: String, action: String,
                                  stationId: Int, input: JSONObject,
                                  block: (String) -> JSONObject): JSONObject {
        val key = input.optString("idempotency_key", "").trim()
        require(key.isNotEmpty()) { "مفتاح منع التكرار idempotency_key مطلوب" }
        val existing = db.rawQuery("""SELECT resource_id FROM custody_workshop_idempotency
            WHERE module=? AND action=? AND station_id=? AND idempotency_key=?""",
            arrayOf(module, action, stationId.toString(), key)).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
        if (existing > 0L) return success(existing, "تم التعرف على إعادة الطلب دون تكرار الأثر").put("replayed", true)
        val result = block(key)
        val resourceId = result.optLong("id", result.optLong("custody_id", result.optLong("work_order_id", 0L)))
        require(result.optBoolean("success") && resourceId > 0L) { "تعذر حفظ نتيجة العملية لمنع التكرار" }
        db.insertOrThrow("custody_workshop_idempotency", null, ContentValues().apply {
            put("module", module); put("action", action); put("station_id", stationId)
            put("idempotency_key", key); put("resource_id", resourceId)
        })
        return result
    }

    private fun success(id: Long, message: String): JSONObject =
        JSONObject().put("success", true).put("id", id).put("message", message)

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    private fun today(): String = now().substring(0, 10)
    private fun uuid(): String = UUID.randomUUID().toString()

    private fun requireEmployee(db: SQLiteDatabase, stationId: Int, employeeId: Long) {
        require(employeeId > 0L) { "معرف الموظف مطلوب" }
        db.rawQuery("""SELECT 1 FROM employees WHERE id=? AND station_id=?
            AND COALESCE(is_deleted,0)=0 AND status IN ('active','on_leave')""",
            arrayOf(employeeId.toString(), stationId.toString())).use {
            require(it.moveToFirst()) { "الموظف غير موجود أو خارج المحطة الحالية" }
        }
    }

    private fun custodyRow(db: SQLiteDatabase, stationId: Int, id: Long): JSONObject {
        require(id > 0L) { "معرف العهدة غير صالح" }
        return db.rawQuery("""SELECT * FROM operating_custodies
            WHERE id=? AND station_id=? AND is_deleted=0""",
            arrayOf(id.toString(), stationId.toString())).use {
            require(it.moveToFirst()) { "العهدة غير موجودة في المحطة الحالية" }
            cursorObject(it)
        }
    }

    private fun createCustody(db: SQLiteDatabase, stationId: Int, actorId: Long,
                              p: JSONObject, key: String): JSONObject {
        val employeeId = p.optLong("employee_id")
        val amount = p.optDouble("amount", Double.NaN)
        val purpose = p.optString("purpose", "").trim()
        require(amount.isFinite() && amount > 0.0) { "مبلغ العهدة يجب أن يكون أكبر من صفر" }
        require(purpose.isNotBlank()) { "غرض العهدة مطلوب" }
        requireEmployee(db, stationId, employeeId)
        val shiftId = p.optLong("shift_id", 0L)
        if (shiftId > 0L) {
            db.rawQuery("SELECT 1 FROM shifts WHERE id=? AND station_id=? AND COALESCE(is_deleted,0)=0",
                arrayOf(shiftId.toString(), stationId.toString())).use {
                require(it.moveToFirst()) { "الوردية خارج نطاق المحطة الحالية" }
            }
        }
        val code = p.optString("custody_code", "CST-" + UUID.randomUUID().toString().take(12).uppercase(Locale.ROOT))
        val id = db.insertOrThrow("operating_custodies", null, ContentValues().apply {
            put("uuid", uuid()); put("custody_code", code); put("station_id", stationId)
            put("employee_id", employeeId); if (shiftId > 0L) put("shift_id", shiftId)
            put("cost_center", p.optString("cost_center", ""))
            put("purpose", purpose); put("requested_amount", amount)
            put("created_by", actorId); put("created_at", now()); put("updated_at", now())
        })
        custodyAudit(db, id, stationId, "create", JSONObject(), JSONObject().put("status", "draft").put("requested_amount", amount), actorId)
        return success(id, "تم إنشاء العهدة في SQLite دون صرف أو قيد مالي")
            .put("custody_code", code).put("status", "draft")
    }

    private fun approveCustody(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val id = p.optLong("custody_id")
        val row = custodyRow(db, stationId, id)
        require(row.optString("status") == "draft") { "لا يمكن اعتماد العهدة من الحالة الحالية" }
        val approved = p.optDouble("approved_amount", row.optDouble("requested_amount"))
        require(approved.isFinite() && approved > 0 && approved <= row.optDouble("requested_amount")) {
            "قيمة الاعتماد يجب أن تكون موجبة ولا تتجاوز المبلغ المطلوب"
        }
        val old = JSONObject(row.toString())
        db.update("operating_custodies", ContentValues().apply {
            put("approved_amount", approved); put("status", "approved")
            put("approved_by", actorId); put("approved_at", now()); put("updated_at", now())
        }, "id=? AND station_id=? AND status='draft'", arrayOf(id.toString(), stationId.toString())).also {
            require(it == 1) { "تغيرت حالة العهدة أثناء الاعتماد" }
        }
        custodyAudit(db, id, stationId, "approve", old, JSONObject().put("status", "approved").put("approved_amount", approved), actorId)
        return success(id, "تم اعتماد العهدة")
    }

    private fun disburseCustody(helper: DatabaseHelper, db: SQLiteDatabase, stationId: Int,
                                actorId: Long, p: JSONObject): JSONObject {
        val id = p.optLong("custody_id")
        val row = custodyRow(db, stationId, id)
        require(row.optString("status") in setOf("approved", "issued")) { "لا يمكن صرف عهدة غير معتمدة" }
        val amount = positiveAmount(p, "amount")
        require(row.optDouble("issued_amount") + amount <= row.optDouble("approved_amount") + 0.000001) {
            "إجمالي الصرف يتجاوز مبلغ الاعتماد"
        }
        val source = sourceAccount(db, stationId, actorId, p)
        val custodyAccount = accountId(db, "CWS-CUSTODY-" + stationId, "ذمم العهد التشغيلية", "asset", "debit")
        val journal = postJournal(db, stationId, actorId, "operating_custody", id,
            "صرف عهدة " + row.optString("custody_code"),
            listOf(Triple(custodyAccount, amount, 0.0), Triple(source.accountId, 0.0, amount)))
        source.changeBalance(-amount)
        val movementId = addCustodyMovement(db, id, stationId, "issue", amount, actorId,
            journalId = journal, cashBoxId = source.cashBoxId, bankAccountId = source.bankAccountId)
        val issued = row.optDouble("issued_amount") + amount
        val spent = row.optDouble("spent_amount")
        val returned = row.optDouble("returned_amount")
        db.update("operating_custodies", ContentValues().apply {
            put("issued_amount", issued); put("outstanding_amount", (issued - spent - returned).coerceAtLeast(0.0))
            put("status", "issued"); put("cash_box_id", source.cashBoxId)
            put("bank_account_id", source.bankAccountId); put("custody_account_id", custodyAccount)
            put("issued_at", now()); put("updated_at", now())
        }, "id=? AND station_id=?", arrayOf(id.toString(), stationId.toString()))
        custodyAudit(db, id, stationId, "disburse", row, JSONObject().put("issued_amount", issued), actorId)
        return success(id, "تم صرف العهدة وتسجيل القيد المزدوج").put("movement_id", movementId).put("journal_entry_id", journal)
    }

    private data class SourceAccount(val accountId: Long, val cashBoxId: Long, val bankAccountId: Long, val changeBalance: (Double) -> Unit)
    private fun sourceAccount(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): SourceAccount {
        val bankId = p.optLong("bank_account_id", 0L)
        if (bankId > 0L) {
            val row = db.rawQuery("""SELECT id,current_balance,status FROM bank_accounts
                WHERE id=? AND station_id=? AND is_deleted=0""",
                arrayOf(bankId.toString(), stationId.toString())).use {
                require(it.moveToFirst()) { "الحساب البنكي خارج المحطة الحالية" }
                Triple(it.getLong(0), it.getDouble(1), it.getString(2))
            }
            require(row.third == "active") { "الحساب البنكي غير نشط" }
            val account = accountId(db, "CWS-BANK-" + bankId, "بنك - " + bankId, "asset", "debit", bankAccountId = bankId)
            return SourceAccount(account, 0L, bankId) { delta ->
                val after = row.second + delta
                require(after >= -0.000001) { "رصيد البنك غير كافٍ" }
                db.update("bank_accounts", ContentValues().apply {
                    put("current_balance", after); put("available_balance", after); put("updated_at", now())
                }, "id=? AND station_id=?", arrayOf(bankId.toString(), stationId.toString()))
            }
        }
        val cashId = p.optLong("cash_box_id", 0L)
        require(cashId > 0L) { "حدد الصندوق أو الحساب البنكي" }
        val row = db.rawQuery("""SELECT id,current_balance,status FROM cash_boxes
            WHERE id=? AND station_id=? AND is_deleted=0""",
            arrayOf(cashId.toString(), stationId.toString())).use {
            require(it.moveToFirst()) { "الصندوق خارج المحطة الحالية" }
            Triple(it.getLong(0), it.getDouble(1), it.getString(2))
        }
        require(row.third == "active") { "الصندوق غير نشط" }
        val account = accountId(db, "CWS-CASH-" + cashId, "صندوق " + cashId, "asset", "debit", cashBoxId = cashId)
        return SourceAccount(account, cashId, 0L) { delta ->
            val after = row.second + delta
            require(after >= -0.000001) { "رصيد الصندوق غير كافٍ" }
            db.update("cash_boxes", ContentValues().apply {
                put("current_balance", after); put("updated_at", now())
            }, "id=? AND station_id=?", arrayOf(cashId.toString(), stationId.toString()))
        }
    }

    private fun custodyExpense(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val id = p.optLong("custody_id")
        val row = custodyRow(db, stationId, id)
        require(row.optString("status") in setOf("issued", "settlement_pending")) { "العهدة غير مصروفة أو غير قابلة للتسوية" }
        val amount = positiveAmount(p, "amount")
        require(amount <= row.optDouble("outstanding_amount") + 0.000001) { "المصروف يتجاوز رصيد العهدة المتاح" }
        val docNo = p.optString("document_no", "").trim()
        val docHash = p.optString("document_hash", "").trim()
        require(docNo.isNotBlank() && docHash.isNotBlank()) { "رقم المستند وبصمته مطلوبان لتوثيق المصروف" }
        val expenseType = p.optString("expense_type", "expense").lowercase(Locale.ROOT)
        require(expenseType in setOf("expense", "inventory", "capital_asset", "deposit_transit")) { "تصنيف المصروف غير صالح" }
        if (expenseType == "inventory") {
            val qty = positiveAmount(p, "quantity")
            val product = p.optLong("product_id")
            val warehouse = requireWarehouse(db, stationId, p.optLong("warehouse_id"))
            requireProduct(db, product, stationId)
            adjustInventory(db, stationId, actorId, product, warehouse, qty, true, amount / qty,
                "custody_purchase", id, "شراء من عهدة " + row.optString("custody_code"))
        }
        val credit = row.optLong("custody_account_id").takeIf { it > 0 } ?: accountId(db,
            "CWS-CUSTODY-" + stationId, "ذمم العهد التشغيلية", "asset", "debit")
        val debit = when (expenseType) {
            "inventory" -> accountId(db, "CWS-INVENTORY-" + stationId, "مخزون قطع الغيار", "asset", "debit")
            "capital_asset" -> accountId(db, "CWS-FIXED-ASSET-" + stationId, "أصول معدات المحطة", "asset", "debit")
            "deposit_transit" -> accountId(db, "CWS-CASH-TRANSIT-" + stationId, "نقدية بالطريق", "asset", "debit")
            else -> accountId(db, "CWS-OPERATING-EXP-" + stationId, "مصروفات تشغيلية من العهد", "expense", "debit")
        }
        val journal = postJournal(db, stationId, actorId, "operating_custody", id,
            "تسوية مصروف عهدة " + row.optString("custody_code"),
            listOf(Triple(debit, amount, 0.0), Triple(credit, 0.0, amount)))
        val movementId = addCustodyMovement(db, id, stationId,
            if (expenseType == "inventory") "purchase" else "expense", amount, actorId,
            expenseType = expenseType, productId = p.optLong("product_id", 0L),
            warehouseId = p.optLong("warehouse_id", 0L), quantity = p.optDouble("quantity", 0.0),
            documentNo = docNo, documentHash = docHash, journalId = journal)
        db.insertOrThrow("custody_documents", null, ContentValues().apply {
            put("uuid", uuid()); put("station_id", stationId); put("custody_id", id)
            put("movement_id", movementId); put("document_no", docNo); put("document_hash", docHash)
            put("document_type", p.optString("document_type", "receipt")); put("amount", amount)
            put("file_uri", p.optString("file_uri", "")); put("created_by", actorId)
        })
        val spent = row.optDouble("spent_amount") + amount
        val outstanding = (row.optDouble("issued_amount") - spent - row.optDouble("returned_amount")).coerceAtLeast(0.0)
        db.update("operating_custodies", ContentValues().apply {
            put("spent_amount", spent); put("outstanding_amount", outstanding)
            put("status", if (outstanding <= 0.000001) "settlement_pending" else "issued")
            put("updated_at", now())
        }, "id=? AND station_id=?", arrayOf(id.toString(), stationId.toString()))
        custodyAudit(db, id, stationId, "expense", row, JSONObject().put("spent_amount", spent), actorId)
        return success(id, "تم تسجيل المصروف والمستند والقيد في SQLite")
            .put("movement_id", movementId).put("journal_entry_id", journal)
    }

    private fun returnCustody(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val id = p.optLong("custody_id")
        val row = custodyRow(db, stationId, id)
        require(row.optString("status") in setOf("issued", "settlement_pending", "shortage_pending", "overage_pending")) {
            "العهدة ليست في حالة تسمح بإعادة النقد"
        }
        val amount = positiveAmount(p, "amount")
        require(amount <= row.optDouble("outstanding_amount") + 0.000001) { "المبلغ المعاد يتجاوز الرصيد المفتوح" }
        val source = sourceAccount(db, stationId, actorId, p)
        val custody = row.optLong("custody_account_id").takeIf { it > 0 } ?: accountId(db,
            "CWS-CUSTODY-" + stationId, "ذمم العهد التشغيلية", "asset", "debit")
        val journal = postJournal(db, stationId, actorId, "operating_custody", id,
            "إعادة متبقي عهدة " + row.optString("custody_code"),
            listOf(Triple(source.accountId, amount, 0.0), Triple(custody, 0.0, amount)))
        source.changeBalance(amount)
        val returned = row.optDouble("returned_amount") + amount
        val outstanding = (row.optDouble("issued_amount") - row.optDouble("spent_amount") - returned).coerceAtLeast(0.0)
        val movement = addCustodyMovement(db, id, stationId, "cash_return", amount, actorId,
            journalId = journal, cashBoxId = source.cashBoxId, bankAccountId = source.bankAccountId)
        db.update("operating_custodies", ContentValues().apply {
            put("returned_amount", returned); put("outstanding_amount", outstanding)
            put("status", if (outstanding <= 0.000001) "settlement_pending" else "issued")
            put("updated_at", now())
        }, "id=? AND station_id=?", arrayOf(id.toString(), stationId.toString()))
        custodyAudit(db, id, stationId, "return", row, JSONObject().put("returned_amount", returned), actorId)
        return success(id, "تم إثبات إعادة النقد").put("movement_id", movement).put("journal_entry_id", journal)
    }

    private fun settleCustody(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val id = p.optLong("custody_id")
        val row = custodyRow(db, stationId, id)
        require(row.optDouble("outstanding_amount") <= 0.000001) { "لا يمكن إقفال العهدة مع وجود رصيد غير مسوّى" }
        require(row.optString("status") in setOf("settlement_pending", "issued")) { "حالة العهدة لا تسمح بالإقفال" }
        db.update("operating_custodies", ContentValues().apply {
            put("status", "settled"); put("settled_by", actorId); put("settled_at", now()); put("updated_at", now())
        }, "id=? AND station_id=?", arrayOf(id.toString(), stationId.toString()))
        custodyAudit(db, id, stationId, "settle", row, JSONObject().put("status", "settled"), actorId)
        return success(id, "تم إقفال العهدة بعد مطابقة المصروفات والنقد المعاد")
    }

    private fun recordCustodyVariance(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val id = p.optLong("custody_id")
        val row = custodyRow(db, stationId, id)
        require(row.optString("status") in setOf("issued", "settlement_pending")) { "لا توجد عهدة مفتوحة للجرد" }
        val amount = positiveAmount(p, "amount")
        require(amount <= row.optDouble("outstanding_amount") + 0.000001) { "فرق الجرد يتجاوز الرصيد المتبقي" }
        val type = p.optString("variance_type", "").lowercase(Locale.ROOT)
        require(type == "shortage" || type == "overage") { "نوع الفرق يجب أن يكون shortage أو overage" }
        val movement = addCustodyMovement(db, id, stationId, type, amount, actorId,
            status = "pending_investigation", notes = p.optString("reason", ""))
        val status = if (type == "shortage") "shortage_pending" else "overage_pending"
        db.update("operating_custodies", ContentValues().apply { put("status", status); put("updated_at", now()) },
            "id=? AND station_id=?", arrayOf(id.toString(), stationId.toString()))
        custodyAudit(db, id, stationId, type, row, JSONObject().put("status", status).put("amount", amount), actorId)
        return success(id, "تم تعليق فرق الجرد للتحقيق دون تحميله تلقائياً على المصروف أو الإيراد")
            .put("movement_id", movement).put("status", status)
    }

    private fun resolveCustodyVariance(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val id = p.optLong("custody_id")
        val row = custodyRow(db, stationId, id)
        val movementId = p.optLong("movement_id")
        val movement = db.rawQuery("""SELECT movement_type,amount,status FROM custody_movements
            WHERE id=? AND custody_id=? AND station_id=?""",
            arrayOf(movementId.toString(), id.toString(), stationId.toString())).use {
            require(it.moveToFirst()) { "محضر فرق الجرد غير موجود" }
            Triple(it.getString(0), it.getDouble(1), it.getString(2))
        }
        require(movement.third == "pending_investigation") { "فرق الجرد تمت معالجته سابقاً" }
        val decision = p.optString("decision", "")
        val custody = row.optLong("custody_account_id").takeIf { it > 0 } ?: accountId(db,
            "CWS-CUSTODY-" + stationId, "ذمم العهد التشغيلية", "asset", "debit")
        val journal = if (movement.first == "shortage") {
            val debit = if (decision == "employee_responsibility")
                accountId(db, "CWS-EMPLOYEE-RECEIVABLE-" + stationId, "ذمم على الموظفين", "asset", "debit")
            else {
                require(decision == "company_expense") { "حدد قرار العجز: employee_responsibility أو company_expense" }
                accountId(db, "CWS-SHORTAGE-EXP-" + stationId, "مصروف عجز عهد معتمد", "expense", "debit")
            }
            postJournal(db, stationId, actorId, "operating_custody", id, "تسوية عجز عهدة",
                listOf(Triple(debit, movement.second, 0.0), Triple(custody, 0.0, movement.second)))
        } else {
            require(decision == "suspense_liability") { "الزيادة تبقى التزاماً معلقاً ولا تعترف كإيراد تلقائياً" }
            val suspense = accountId(db, "CWS-CUSTODY-SUSPENSE-" + stationId, "مبالغ عهد معلقة لحين التحقيق", "liability", "credit")
            postJournal(db, stationId, actorId, "operating_custody", id, "إثبات زيادة عهدة معلقة",
                listOf(Triple(custody, movement.second, 0.0), Triple(suspense, 0.0, movement.second)))
        }
        db.update("custody_movements", ContentValues().apply {
            put("status", "resolved"); put("journal_entry_id", journal)
        }, "id=? AND station_id=?", arrayOf(movementId.toString(), stationId.toString()))
        if (movement.first == "shortage") {
            val spent = row.optDouble("spent_amount") + movement.second
            val outstanding = (row.optDouble("issued_amount") - spent - row.optDouble("returned_amount")).coerceAtLeast(0.0)
            db.update("operating_custodies", ContentValues().apply {
                put("spent_amount", spent); put("outstanding_amount", outstanding); put("status", "issued"); put("updated_at", now())
            }, "id=? AND station_id=?", arrayOf(id.toString(), stationId.toString()))
        } else {
            db.update("operating_custodies", ContentValues().apply { put("status", "issued"); put("updated_at", now()) },
                "id=? AND station_id=?", arrayOf(id.toString(), stationId.toString()))
        }
        custodyAudit(db, id, stationId, "resolve_variance", row,
            JSONObject().put("movement_id", movementId).put("journal_entry_id", journal).put("decision", decision), actorId)
        return success(id, "تمت تسوية فرق الجرد وفق القرار المعتمد").put("journal_entry_id", journal)
    }

    private fun transferCustody(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val id = p.optLong("custody_id")
        val row = custodyRow(db, stationId, id)
        require(row.optString("status") == "issued") { "لا يمكن نقل العهدة إلا وهي مفتوحة ومصروفة" }
        val employeeId = p.optLong("to_employee_id")
        requireEmployee(db, stationId, employeeId)
        val old = row.optLong("employee_id")
        db.update("operating_custodies", ContentValues().apply { put("employee_id", employeeId); put("updated_at", now()) },
            "id=? AND station_id=? AND employee_id=?", arrayOf(id.toString(), stationId.toString(), old.toString()))
        addCustodyMovement(db, id, stationId, "transfer", 0.0, actorId, employeeId = employeeId,
            status = "completed", notes = "من الموظف " + old + " إلى " + employeeId)
        custodyAudit(db, id, stationId, "transfer", row, JSONObject().put("employee_id", employeeId), actorId)
        return success(id, "تم نقل مسؤولية العهدة بمحضر تدقيق")
    }

    private fun recoverCustody(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val id = p.optLong("custody_id")
        val row = custodyRow(db, stationId, id)
        val amount = positiveAmount(p, "amount")
        require(amount <= row.optDouble("outstanding_amount") + 0.000001) { "قيمة الاسترداد تتجاوز المتبقي" }
        require(p.optString("approval_reference").isNotBlank()) { "مرجع موافقة الاسترداد مطلوب" }
        val custody = row.optLong("custody_account_id").takeIf { it > 0 } ?: accountId(db,
            "CWS-CUSTODY-" + stationId, "ذمم العهد التشغيلية", "asset", "debit")
        val payroll = accountId(db, "CWS-PAYROLL-PAYABLE-" + stationId, "مستحقات رواتب الموظفين", "liability", "credit")
        val journal = postJournal(db, stationId, actorId, "operating_custody", id, "استرداد عهدة من مستحقات موظف",
            listOf(Triple(payroll, amount, 0.0), Triple(custody, 0.0, amount)))
        val spent = row.optDouble("spent_amount") + amount
        val outstanding = (row.optDouble("issued_amount") - spent - row.optDouble("returned_amount")).coerceAtLeast(0.0)
        db.update("operating_custodies", ContentValues().apply {
            put("spent_amount", spent); put("outstanding_amount", outstanding); put("updated_at", now())
        }, "id=? AND station_id=?", arrayOf(id.toString(), stationId.toString()))
        val movement = addCustodyMovement(db, id, stationId, "payroll_recovery", amount, actorId,
            journalId = journal, notes = p.optString("approval_reference"))
        custodyAudit(db, id, stationId, "payroll_recovery", row, JSONObject().put("amount", amount), actorId)
        return success(id, "تم إثبات الاسترداد المعتمد دون تنفيذ دفع رواتب فعلي").put("movement_id", movement).put("journal_entry_id", journal)
    }

    private fun reverseCustody(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val id = p.optLong("custody_id")
        val row = custodyRow(db, stationId, id)
        require(p.optString("reason").isNotBlank()) { "سبب العكس مطلوب" }
        if (row.optDouble("issued_amount") <= 0.0) {
            db.update("operating_custodies", ContentValues().apply { put("status", "cancelled"); put("updated_at", now()) },
                "id=? AND station_id=?", arrayOf(id.toString(), stationId.toString()))
            custodyAudit(db, id, stationId, "cancel", row, JSONObject().put("status", "cancelled"), actorId)
            return success(id, "تم إلغاء العهدة غير المصروفة مع الاحتفاظ بسجلها")
        }
        require(row.optDouble("outstanding_amount") <= 0.000001) {
            "يجب تسوية الرصيد أو إرجاعه قبل عكس العهدة المرحلة"
        }
        val movements = mutableListOf<Pair<Long, Long>>()
        db.rawQuery("""SELECT id,journal_entry_id,amount,movement_type FROM custody_movements
            WHERE custody_id=? AND station_id=? AND journal_entry_id IS NOT NULL AND status='completed'
            ORDER BY id DESC""", arrayOf(id.toString(), stationId.toString())).use { c ->
            while (c.moveToNext()) movements.add(c.getLong(0) to c.getLong(1))
        }
        var reversals = 0
        movements.forEach { (_, journalId) ->
            val lines = mutableListOf<Triple<Long, Double, Double>>()
            db.rawQuery("SELECT account_id,debit,credit FROM journal_entry_items WHERE journal_entry_id=?",
                arrayOf(journalId.toString())).use { c ->
                while (c.moveToNext()) lines.add(Triple(c.getLong(0), c.getDouble(1), c.getDouble(2)))
            }
            if (lines.isNotEmpty()) {
                val swapped = lines.map { Triple(it.first, it.third, it.second) }
                val reverseId = postJournal(db, stationId, actorId, "operating_custody_reversal", id,
                    "عكس قيد عهدة: " + p.optString("reason"), swapped)
                reversals++
                db.update("custody_movements", ContentValues().apply { put("status", "reversed") },
                    "journal_entry_id=? AND station_id=?", arrayOf(journalId.toString(), stationId.toString()))
                db.update("custody_movements", ContentValues().apply { put("movement_type", "reversal"); put("journal_entry_id", reverseId) },
                    "id=? AND station_id=?", arrayOf(movements.firstOrNull { it.second == journalId }?.first?.toString() ?: "0", stationId.toString()))
            }
        }
        db.update("operating_custodies", ContentValues().apply { put("status", "reversed"); put("updated_at", now()) },
            "id=? AND station_id=?", arrayOf(id.toString(), stationId.toString()))
        custodyAudit(db, id, stationId, "reverse", row, JSONObject().put("status", "reversed").put("reversal_count", reversals), actorId)
        return success(id, "تم عكس القيود دون حذف المستند الأصلي").put("reversal_count", reversals)
    }

    private fun configureCustodyAccount(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val id = p.optLong("custody_id")
        val row = custodyRow(db, stationId, id)
        require(row.optDouble("issued_amount") == 0.0) { "تغيير الربط المحاسبي يطبق على العهد الجديدة فقط بعد وجود حركات" }
        val account = p.optLong("account_id")
        db.rawQuery("SELECT account_type,is_active,is_deleted FROM accounts WHERE id=?",
            arrayOf(account.toString())).use {
            require(it.moveToFirst() && it.getString(0) == "asset" && it.getInt(1) == 1 && it.getInt(2) == 0) {
                "حساب العهدة غير صالح"
            }
        }
        db.update("operating_custodies", ContentValues().apply { put("custody_account_id", account); put("updated_at", now()) },
            "id=? AND station_id=?", arrayOf(id.toString(), stationId.toString()))
        custodyAudit(db, id, stationId, "configure_account", row, JSONObject().put("account_id", account), actorId)
        return success(id, "تم تحديث الربط للعهدة غير المصروفة فقط")
    }

    private fun addCustodyMovement(db: SQLiteDatabase, custodyId: Long, stationId: Int,
        type: String, amount: Double, actorId: Long, expenseType: String? = null,
        employeeId: Long = 0L, productId: Long = 0L, warehouseId: Long = 0L,
        quantity: Double = 0.0, cashBoxId: Long = 0L, bankAccountId: Long = 0L,
        documentNo: String? = null, documentHash: String? = null,
        journalId: Long = 0L, status: String = "completed", notes: String? = null): Long {
        return db.insertOrThrow("custody_movements", null, ContentValues().apply {
            put("uuid", uuid()); put("custody_id", custodyId); put("station_id", stationId)
            put("movement_type", type); put("amount", amount); if (expenseType != null) put("expense_type", expenseType)
            if (employeeId > 0) put("employee_id", employeeId); if (productId > 0) put("product_id", productId)
            if (warehouseId > 0) put("warehouse_id", warehouseId); if (quantity > 0) put("quantity", quantity)
            if (cashBoxId > 0) put("cash_box_id", cashBoxId); if (bankAccountId > 0) put("bank_account_id", bankAccountId)
            if (documentNo != null) put("document_no", documentNo); if (documentHash != null) put("document_hash", documentHash)
            if (journalId > 0) put("journal_entry_id", journalId); put("status", status)
            put("notes", notes ?: ""); put("created_by", actorId); put("created_at", now())
        })
    }

    private fun custodyAudit(db: SQLiteDatabase, id: Long, stationId: Int, action: String,
                             old: JSONObject, new: JSONObject, actorId: Long) {
        db.insertOrThrow("custody_audit_events", null, ContentValues().apply {
            put("uuid", uuid()); put("custody_id", id); put("station_id", stationId); put("action", action)
            put("old_value", old.toString()); put("new_value", new.toString()); put("performed_by", actorId)
        })
    }

    private fun custodyAgeing(db: SQLiteDatabase, stationId: Int): JSONObject {
        val rows = JSONArray()
        db.rawQuery("""SELECT employee_id,COUNT(*) custody_count,
            ROUND(SUM(outstanding_amount),2) outstanding_amount,
            MIN(created_at) oldest_created_at
            FROM operating_custodies WHERE station_id=? AND is_deleted=0
            AND status NOT IN ('settled','cancelled','reversed')
            GROUP BY employee_id ORDER BY MIN(created_at)""", arrayOf(stationId.toString())).use { c ->
            while (c.moveToNext()) rows.put(JSONObject().put("employee_id", c.getLong(0))
                .put("custody_count", c.getInt(1)).put("outstanding_amount", c.getDouble(2))
                .put("oldest_created_at", c.getString(3)))
        }
        return JSONObject().put("success", true).put("station_id", stationId).put("data", rows)
    }

    private fun custodyReconcile(db: SQLiteDatabase, stationId: Int): JSONObject {
        val outstanding = scalar(db, "SELECT COALESCE(SUM(outstanding_amount),0) FROM operating_custodies WHERE station_id=? AND is_deleted=0 AND status NOT IN ('cancelled','reversed')", stationId)
        val badJournals = scalar(db, """SELECT COUNT(*) FROM journal_entries
            WHERE station_id=? AND reference_type IN ('operating_custody','operating_custody_reversal')
            AND status='posted' AND ABS(total_debit-total_credit)>0.01""", stationId)
        val badEvents = scalar(db, "SELECT COUNT(*) FROM operating_custodies c WHERE c.station_id=? AND NOT EXISTS (SELECT 1 FROM custody_audit_events a WHERE a.custody_id=c.id)", stationId)
        return JSONObject().put("success", true).put("station_id", stationId)
            .put("outstanding_custody_balance", outstanding).put("unbalanced_journals", badJournals)
            .put("custodies_without_audit", badEvents).put("passed", badJournals == 0.0 && badEvents == 0.0)
    }

    private fun createWorkOrder(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val assetId = p.optLong("asset_id")
        require(assetId > 0L) { "الأصل مطلوب لأمر العمل" }
        val assetType = p.optString("asset_type", "fixed_asset")
        db.rawQuery("SELECT 1 FROM fixed_assets WHERE id=? AND station_id=? AND status<>'disposed'",
            arrayOf(assetId.toString(), stationId.toString())).use {
            require(it.moveToFirst()) { "الأصل غير موجود أو خارج المحطة الحالية" }
        }
        val requestId = p.optLong("maintenance_request_id", 0L)
        if (requestId > 0L) {
            db.rawQuery("SELECT 1 FROM maintenance_requests WHERE id=? AND station_id=? AND is_deleted=0",
                arrayOf(requestId.toString(), stationId.toString())).use {
                require(it.moveToFirst()) { "طلب الصيانة المرتبط خارج المحطة الحالية" }
            }
        }
        val code = p.optString("order_code", "WO-" + UUID.randomUUID().toString().take(12).uppercase(Locale.ROOT))
        val id = db.insertOrThrow("work_orders", null, ContentValues().apply {
            put("uuid", uuid()); put("order_code", code); put("station_id", stationId)
            put("asset_type", assetType); put("asset_id", assetId)
            if (requestId > 0) put("maintenance_request_id", requestId)
            put("customer_party_id", p.optLong("customer_party_id", 0L).takeIf { it > 0 })
            put("vendor_party_id", p.optLong("vendor_party_id", 0L).takeIf { it > 0 })
            put("cost_center", p.optString("cost_center", "WORKSHOP"))
            put("title", p.optString("title", "صيانة أصل")); put("description", p.optString("description", ""))
            put("priority", p.optString("priority", "normal")); put("bill_to_customer", if (p.optBoolean("bill_to_customer")) 1 else 0)
            put("created_by", actorId); put("created_at", now()); put("updated_at", now())
        })
        workAudit(db, id, stationId, "create", JSONObject(), JSONObject().put("status", "draft"), actorId)
        return success(id, "تم إنشاء أمر العمل في SQLite").put("order_code", code).put("status", "draft")
    }

    private fun workOrder(db: SQLiteDatabase, stationId: Int, id: Long): JSONObject {
        require(id > 0L) { "معرف أمر العمل غير صالح" }
        return db.rawQuery("SELECT * FROM work_orders WHERE id=? AND station_id=? AND is_deleted=0",
            arrayOf(id.toString(), stationId.toString())).use {
            require(it.moveToFirst()) { "أمر العمل غير موجود في المحطة الحالية" }
            cursorObject(it)
        }
    }

    private fun approveWorkOrder(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val id = p.optLong("work_order_id")
        val row = workOrder(db, stationId, id)
        require(row.optString("status") == "draft") { "أمر العمل ليس في حالة مسودة" }
        db.update("work_orders", ContentValues().apply {
            put("status", "approved"); put("approved_by", actorId); put("approved_at", now()); put("updated_at", now())
        }, "id=? AND station_id=? AND status='draft'", arrayOf(id.toString(), stationId.toString())).also {
            require(it == 1) { "تغيرت حالة أمر العمل أثناء الاعتماد" }
        }
        workAudit(db, id, stationId, "approve", row, JSONObject().put("status", "approved"), actorId)
        return success(id, "تم اعتماد أمر العمل")
    }

    private fun startWorkOrder(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val id = p.optLong("work_order_id")
        val row = workOrder(db, stationId, id)
        require(row.optString("status") == "approved") { "يجب اعتماد أمر العمل قبل بدء التنفيذ" }
        db.update("work_orders", ContentValues().apply { put("status", "in_progress"); put("started_at", now()); put("updated_at", now()) },
            "id=? AND station_id=?", arrayOf(id.toString(), stationId.toString()))
        workAudit(db, id, stationId, "start", row, JSONObject().put("status", "in_progress"), actorId)
        return success(id, "بدأ تنفيذ أمر العمل")
    }

    private fun addWorkOrderLabor(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val id = p.optLong("work_order_id")
        val row = workOrder(db, stationId, id)
        require(row.optString("status") == "in_progress") { "أمر العمل غير قيد التنفيذ" }
        val employeeId = p.optLong("employee_id")
        requireEmployee(db, stationId, employeeId)
        val hours = positiveAmount(p, "hours")
        val rate = p.optDouble("hourly_rate", Double.NaN)
        require(rate.isFinite() && rate >= 0.0) { "معدل الساعة غير صالح" }
        val amount = hours * rate
        val wageExpense = accountId(db, "CWS-WORKSHOP-LABOR-" + stationId, "تكلفة أجور فنيي الورشة", "expense", "debit")
        val laborAccrual = accountId(db, "CWS-WORKSHOP-LABOR-ACCRUAL-" + stationId, "أجور فنيين مستحقة", "liability", "credit")
        val journal = if (amount > 0) postJournal(db, stationId, actorId, "work_order_labor", id,
            "تكلفة ساعات فني لأمر عمل", listOf(Triple(wageExpense, amount, 0.0), Triple(laborAccrual, 0.0, amount))) else 0L
        val laborId = db.insertOrThrow("work_order_labor", null, ContentValues().apply {
            put("uuid", uuid()); put("work_order_id", id); put("station_id", stationId)
            put("employee_id", employeeId); put("work_date", p.optString("work_date", today()))
            put("hours", hours); put("hourly_rate", rate); put("amount", amount)
            put("notes", p.optString("notes", "")); if (journal > 0) put("journal_entry_id", journal); put("created_by", actorId)
        })
        db.update("work_orders", ContentValues().apply {
            put("actual_labor_cost", row.optDouble("actual_labor_cost") + amount); put("updated_at", now())
        }, "id=? AND station_id=?", arrayOf(id.toString(), stationId.toString()))
        workAudit(db, id, stationId, "labor", row, JSONObject().put("labor_id", laborId).put("amount", amount), actorId)
        return success(id, "تم تسجيل ساعات الفني وتكلفتها").put("labor_id", laborId).put("journal_entry_id", journal)
    }

    private fun issueWorkOrderPart(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val id = p.optLong("work_order_id")
        val row = workOrder(db, stationId, id)
        require(row.optString("status") == "in_progress") { "أمر العمل غير قيد التنفيذ" }
        val productId = p.optLong("product_id")
        requireProduct(db, productId, stationId)
        val warehouse = requireWarehouse(db, stationId, p.optLong("warehouse_id"))
        val qty = positiveAmount(p, "quantity")
        val cost = adjustInventory(db, stationId, actorId, productId, warehouse, qty, false, 0.0,
            "work_order_issue", id, "صرف قطعة لأمر العمل")
        val amount = qty * cost
        val inventoryAccount = accountId(db, "CWS-INVENTORY-" + stationId, "مخزون قطع الغيار", "asset", "debit")
        val expenseAccount = accountId(db, "CWS-WORKSHOP-PARTS-" + stationId, "تكلفة قطع غيار الصيانة", "expense", "debit")
        val journal = if (amount > 0) postJournal(db, stationId, actorId, "work_order_parts", id,
            "صرف قطع غيار لأمر عمل", listOf(Triple(expenseAccount, amount, 0.0), Triple(inventoryAccount, 0.0, amount))) else 0L
        val invMove = lastInventoryMovement(db, stationId, productId, "work_order_issue", id)
        val partId = db.insertOrThrow("work_order_parts", null, ContentValues().apply {
            put("uuid", uuid()); put("work_order_id", id); put("station_id", stationId); put("product_id", productId)
            put("warehouse_id", warehouse); put("movement_type", "issue"); put("quantity", qty)
            put("unit_cost", cost); put("total_cost", amount); put("inventory_movement_id", invMove)
            if (journal > 0) put("journal_entry_id", journal); put("created_by", actorId)
        })
        db.update("work_orders", ContentValues().apply {
            put("actual_parts_cost", row.optDouble("actual_parts_cost") + amount); put("updated_at", now())
        }, "id=? AND station_id=?", arrayOf(id.toString(), stationId.toString()))
        workAudit(db, id, stationId, "issue_part", row, JSONObject().put("part_id", partId).put("quantity", qty), actorId)
        return success(id, "تم صرف قطع الغيار وتحديث المخزون والقيد").put("part_id", partId)
            .put("inventory_movement_id", invMove).put("journal_entry_id", journal).put("unit_cost", cost)
    }

    private fun returnWorkOrderPart(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val id = p.optLong("work_order_id")
        val row = workOrder(db, stationId, id)
        val partId = p.optLong("part_id")
        val part = db.rawQuery("""SELECT product_id,warehouse_id,quantity,returned_quantity,damaged_quantity,unit_cost
            FROM work_order_parts WHERE id=? AND work_order_id=? AND station_id=? AND movement_type='issue'""",
            arrayOf(partId.toString(), id.toString(), stationId.toString())).use {
            require(it.moveToFirst()) { "سطر صرف قطع الغيار غير موجود" }
            doubleArrayOf(it.getLong(0).toDouble(), it.getLong(1).toDouble(), it.getDouble(2),
                it.getDouble(3), it.getDouble(4), it.getDouble(5))
        }
        val qty = positiveAmount(p, "quantity")
        require(qty <= part[2] - part[3] - part[4] + 0.000001) { "كمية الإرجاع تتجاوز القطع غير المسواة" }
        adjustInventory(db, stationId, actorId, part[0].toLong(), part[1].toLong(), qty, true,
            part[5], "work_order_return", id, "إرجاع قطعة غير مستخدمة")
        val amount = qty * part[5]
        val inventory = accountId(db, "CWS-INVENTORY-" + stationId, "مخزون قطع الغيار", "asset", "debit")
        val expense = accountId(db, "CWS-WORKSHOP-PARTS-" + stationId, "تكلفة قطع غيار الصيانة", "expense", "debit")
        val journal = if (amount > 0) postJournal(db, stationId, actorId, "work_order_parts_return", id,
            "عكس تكلفة القطع المعادة", listOf(Triple(inventory, amount, 0.0), Triple(expense, 0.0, amount))) else 0L
        db.update("work_order_parts", ContentValues().apply { put("returned_quantity", part[3] + qty) },
            "id=? AND work_order_id=? AND station_id=?", arrayOf(partId.toString(), id.toString(), stationId.toString()))
        db.insertOrThrow("work_order_parts", null, ContentValues().apply {
            put("uuid", uuid()); put("work_order_id", id); put("station_id", stationId)
            put("product_id", part[0].toLong()); put("warehouse_id", part[1].toLong())
            put("movement_type", "return"); put("quantity", qty); put("unit_cost", part[5])
            put("total_cost", amount); put("reference_id", partId)
            if (journal > 0) put("journal_entry_id", journal)
            put("created_by", actorId)
        })
        db.update("work_orders", ContentValues().apply {
            put("actual_parts_cost", (row.optDouble("actual_parts_cost") - amount).coerceAtLeast(0.0)); put("updated_at", now())
        }, "id=? AND station_id=?", arrayOf(id.toString(), stationId.toString()))
        workAudit(db, id, stationId, "return_part", row, JSONObject().put("part_id", partId).put("quantity", qty), actorId)
        return success(id, "تم إرجاع القطعة وعكس تكلفتها").put("journal_entry_id", journal)
    }

    private fun purchaseWorkOrderPart(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val id = p.optLong("work_order_id")
        workOrder(db, stationId, id)
        val product = p.optLong("product_id")
        requireProduct(db, product, stationId)
        val warehouse = requireWarehouse(db, stationId, p.optLong("warehouse_id"))
        val qty = positiveAmount(p, "quantity")
        val unitCost = p.optDouble("unit_cost", Double.NaN)
        require(unitCost.isFinite() && unitCost > 0) { "تكلفة الشراء غير صالحة" }
        val amount = qty * unitCost
        val supplierId = p.optLong("supplier_party_id", 0L)
        if (supplierId > 0L) requirePartyInStation(db, stationId, supplierId)
        adjustInventory(db, stationId, actorId, product, warehouse, qty, true, unitCost,
            "work_order_purchase", id, "استلام قطع غيار من مورد")
        val inventory = accountId(db, "CWS-INVENTORY-" + stationId, "مخزون قطع الغيار", "asset", "debit")
        val payable = accountId(db, "CWS-WORKSHOP-AP-" + stationId, "ذمم موردو قطع الغيار", "liability", "credit")
        val journal = postJournal(db, stationId, actorId, "work_order_purchase", id,
            "شراء قطع غيار على الحساب", listOf(Triple(inventory, amount, 0.0), Triple(payable, 0.0, amount)))
        val invMove = lastInventoryMovement(db, stationId, product, "work_order_purchase", id)
        val partId = db.insertOrThrow("work_order_parts", null, ContentValues().apply {
            put("uuid", uuid()); put("work_order_id", id); put("station_id", stationId); put("product_id", product)
            put("warehouse_id", warehouse); put("movement_type", "purchase"); put("quantity", qty)
            put("unit_cost", unitCost); put("total_cost", amount); put("inventory_movement_id", invMove)
            put("journal_entry_id", journal); put("reference_id", supplierId); put("created_by", actorId)
        })
        workAudit(db, id, stationId, "purchase_part", JSONObject(), JSONObject().put("part_id", partId), actorId)
        return success(id, "تم استلام قطع الغيار وإثبات ذمة المورد").put("part_id", partId).put("journal_entry_id", journal)
    }

    private fun updateWorkOrderEstimate(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val id = p.optLong("work_order_id")
        val row = workOrder(db, stationId, id)
        require(row.optString("status") in setOf("draft", "approved", "in_progress")) { "لا يمكن تعديل تقدير أمر عمل مغلق" }
        require(row.optDouble("invoiced_amount") <= 0.000001) { "لا يمكن تغيير تقدير أمر العمل بعد إصدار الفاتورة" }
        val labor = p.optDouble("estimate_labor_cost", Double.NaN)
        val parts = p.optDouble("estimate_parts_cost", Double.NaN)
        require(labor.isFinite() && parts.isFinite() && labor >= 0 && parts >= 0) { "قيم التقدير غير صالحة" }
        db.update("work_orders", ContentValues().apply {
            put("estimate_labor_cost", labor); put("estimate_parts_cost", parts); put("updated_at", now())
        }, "id=? AND station_id=?", arrayOf(id.toString(), stationId.toString()))
        workAudit(db, id, stationId, "estimate", row, JSONObject().put("labor", labor).put("parts", parts), actorId)
        return success(id, "تم تحديث التقدير قبل الفوترة")
            .put("estimate_total", labor + parts)
            .put("actual_total", row.optDouble("actual_labor_cost") + row.optDouble("actual_parts_cost"))
    }

    private fun invoiceWorkOrder(db: SQLiteDatabase, stationId: Int, actorId: Long,
                                 p: JSONObject, key: String): JSONObject {
        val id = p.optLong("work_order_id")
        val row = workOrder(db, stationId, id)
        require(row.optString("status") in setOf("in_progress", "completed")) { "أمر العمل غير جاهز للفوترة" }
        val type = p.optString("invoice_type", "customer")
        require(type == "customer" || type == "vendor") { "نوع الفاتورة غير صالح" }
        val subtotal = positiveAmount(p, "subtotal")
        val taxRate = p.optDouble("tax_rate", 0.0)
        require(taxRate.isFinite() && taxRate >= 0 && taxRate <= 100) { "معدل الضريبة غير صالح" }
        val tax = subtotal * taxRate / 100.0
        val total = subtotal + tax
        val code = p.optString("invoice_code", "WOI-" + UUID.randomUUID().toString().take(12).uppercase(Locale.ROOT))
        val partyId = p.optLong("party_id", if (type == "customer") row.optLong("customer_party_id") else row.optLong("vendor_party_id"))
        if (partyId > 0L) requirePartyInStation(db, stationId, partyId)
        val invoiceExists = db.rawQuery("SELECT 1 FROM work_order_invoices WHERE station_id=? AND invoice_code=?",
            arrayOf(stationId.toString(), code)).use { it.moveToFirst() }
        require(!invoiceExists) { "رقم الفاتورة مستخدم سابقاً" }
        val debit = if (type == "customer")
            accountId(db, "CWS-WORKSHOP-AR-" + stationId, "ذمم عملاء الورشة", "asset", "debit")
        else accountId(db, "CWS-WORKSHOP-EXP-" + stationId, "مصروفات ومشتريات الورشة", "expense", "debit")
        val credit = if (type == "customer")
            accountId(db, "CWS-WORKSHOP-REVENUE-" + stationId, "إيرادات خدمات الورشة", "revenue", "credit")
        else accountId(db, "CWS-WORKSHOP-AP-" + stationId, "ذمم موردو الورشة", "liability", "credit")
        val lines = mutableListOf(Triple(debit, subtotal, 0.0), Triple(credit, 0.0, subtotal))
        if (tax > 0) {
            if (type == "customer") {
                val taxAccount = accountId(db, "CWS-WORKSHOP-TAX-OUT-" + stationId, "ضريبة القيمة المضافة المستحقة", "liability", "credit")
                lines.add(Triple(debit, tax, 0.0))
                lines.add(Triple(taxAccount, 0.0, tax))
            } else {
                val inputTax = accountId(db, "CWS-WORKSHOP-TAX-IN-" + stationId, "ضريبة مدخلات قطع الغيار", "asset", "debit")
                lines.add(Triple(inputTax, tax, 0.0))
                lines.add(Triple(credit, 0.0, tax))
            }
        }
        val journal = postJournal(db, stationId, actorId, "work_order_invoice", id,
            "فاتورة ورشة " + code, lines)
        val invoiceId = db.insertOrThrow("work_order_invoices", null, ContentValues().apply {
            put("uuid", uuid()); put("invoice_code", code); put("station_id", stationId); put("work_order_id", id)
            put("invoice_type", type); if (partyId > 0) put("party_id", partyId)
            put("subtotal", subtotal); put("tax_amount", tax); put("total_amount", total)
            put("journal_entry_id", journal); put("idempotency_key", key); put("created_by", actorId)
        })
        if (type == "customer") db.update("work_orders", ContentValues().apply {
            put("invoiced_amount", row.optDouble("invoiced_amount") + total); put("updated_at", now())
        }, "id=? AND station_id=?", arrayOf(id.toString(), stationId.toString()))
        workAudit(db, id, stationId, "invoice", row, JSONObject().put("invoice_id", invoiceId).put("total", total), actorId)
        return success(id, "تم إصدار الفاتورة وقيد الضريبة والذمم").put("invoice_id", invoiceId)
            .put("journal_entry_id", journal).put("total_amount", total)
    }

    private fun closeWorkOrder(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val id = p.optLong("work_order_id")
        val row = workOrder(db, stationId, id)
        require(row.optString("status") in setOf("in_progress", "completed")) { "حالة أمر العمل لا تسمح بالإغلاق" }
        val unresolved = scalar(db, """SELECT COUNT(*) FROM work_order_parts
            WHERE work_order_id=? AND station_id=? AND movement_type='issue'
            AND quantity>returned_quantity+damaged_quantity+0.000001""", id, stationId)
        require(unresolved == 0.0) { "توجد قطع مصروفة لم تتم تسويتها بالإرجاع أو التلف" }
        if (row.optInt("bill_to_customer") == 1) require(row.optDouble("invoiced_amount") > 0) {
            "أمر العمل قابل للفوترة ولم تصدر فاتورته بعد"
        }
        db.update("work_orders", ContentValues().apply {
            put("status", "closed"); put("completed_at", now()); put("closed_at", now()); put("updated_at", now())
        }, "id=? AND station_id=?", arrayOf(id.toString(), stationId.toString()))
        workAudit(db, id, stationId, "close", row, JSONObject().put("status", "closed"), actorId)
        return success(id, "تم إغلاق أمر العمل بعد مطابقة القطع والتكاليف")
    }

    private fun cancelWorkOrder(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val id = p.optLong("work_order_id")
        val row = workOrder(db, stationId, id)
        require(row.optString("status") in setOf("draft", "approved", "in_progress", "on_hold")) { "لا يمكن إلغاء أمر العمل في حالته الحالية" }
        require(p.optString("reason").isNotBlank()) { "سبب الإلغاء مطلوب" }
        val unresolved = scalar(db, """SELECT COUNT(*) FROM work_order_parts
            WHERE work_order_id=? AND station_id=? AND movement_type='issue'
            AND quantity>returned_quantity+damaged_quantity+0.000001""", id, stationId)
        require(unresolved == 0.0) { "أعد القطع غير المستخدمة أو وثّق تلفها قبل إلغاء أمر العمل" }
        db.update("work_orders", ContentValues().apply {
            put("status", "cancelled"); put("cancelled_at", now()); put("updated_at", now())
        }, "id=? AND station_id=?", arrayOf(id.toString(), stationId.toString()))
        workAudit(db, id, stationId, "cancel", row, JSONObject().put("reason", p.optString("reason")), actorId)
        return success(id, "تم إلغاء أمر العمل بعد تسوية المواد")
    }

    private fun damageWorkOrderPart(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val orderId = p.optLong("work_order_id")
        val order = workOrder(db, stationId, orderId)
        require(order.optString("status") == "in_progress") { "أمر العمل غير قيد التنفيذ" }
        val product = p.optLong("product_id")
        requireProduct(db, product, stationId)
        val warehouse = requireWarehouse(db, stationId, p.optLong("warehouse_id"))
        val qty = positiveAmount(p, "quantity")
        require(p.optString("damage_reason").isNotBlank()) { "سبب التلف مطلوب" }
        val cost = adjustInventory(db, stationId, actorId, product, warehouse, qty, false, 0.0,
            "work_order_damage", orderId, "تلف قطعة داخل الورشة: " + p.optString("damage_reason"))
        val amount = qty * cost
        val loss = accountId(db, "CWS-WORKSHOP-DAMAGE-" + stationId, "خسائر تلف قطع الغيار", "expense", "debit")
        val inventory = accountId(db, "CWS-INVENTORY-" + stationId, "مخزون قطع الغيار", "asset", "debit")
        val journal = if (amount > 0) postJournal(db, stationId, actorId, "work_order_damage", orderId,
            "إثبات تلف قطعة في أمر عمل", listOf(Triple(loss, amount, 0.0), Triple(inventory, 0.0, amount))) else 0L
        val movement = lastInventoryMovement(db, stationId, product, "work_order_damage", orderId)
        val partId = db.insertOrThrow("work_order_parts", null, ContentValues().apply {
            put("uuid", uuid()); put("work_order_id", orderId); put("station_id", stationId)
            put("product_id", product); put("warehouse_id", warehouse); put("movement_type", "damage")
            put("quantity", qty); put("unit_cost", cost); put("total_cost", amount)
            put("inventory_movement_id", movement); if (journal > 0) put("journal_entry_id", journal)
            put("created_by", actorId)
        })
        db.update("work_orders", ContentValues().apply {
            put("actual_parts_cost", order.optDouble("actual_parts_cost") + amount); put("updated_at", now())
        }, "id=? AND station_id=?", arrayOf(orderId.toString(), stationId.toString()))
        workAudit(db, orderId, stationId, "damage_part", order, JSONObject().put("part_id", partId).put("quantity", qty), actorId)
        return success(orderId, "تم إخراج القطعة التالفة من المخزون وإثبات خسارتها").put("journal_entry_id", journal)
    }

    private fun capitalizeWorkOrderCost(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val orderId = p.optLong("work_order_id")
        val order = workOrder(db, stationId, orderId)
        require(order.optString("status") in setOf("approved", "in_progress", "completed")) { "أمر العمل غير مؤهل للرسملة" }
        val amount = positiveAmount(p, "amount")
        val decision = p.optString("decision", "")
        require(decision == "expense" || decision == "capitalize") { "حدد المعالجة: expense أو capitalize" }
        val credit = accountId(db, "CWS-WORKSHOP-AP-" + stationId, "ذمم موردو الورشة", "liability", "credit")
        val debit = if (decision == "capitalize")
            accountId(db, "CWS-WORKSHOP-CAPEX-" + stationId, "تحسينات أصول المحطة", "asset", "debit")
        else accountId(db, "CWS-WORKSHOP-EXP-" + stationId, "مصروفات صيانة دورية", "expense", "debit")
        val journal = postJournal(db, stationId, actorId, "work_order_capitalization", orderId,
            if (decision == "capitalize") "رسملة تحسين جوهري" else "تحميل صيانة دورية على المصروف",
            listOf(Triple(debit, amount, 0.0), Triple(credit, 0.0, amount)))
        val capitalizationId = db.insertOrThrow("work_order_capitalizations", null, ContentValues().apply {
            put("uuid", uuid()); put("work_order_id", orderId); put("station_id", stationId)
            put("decision", decision); put("amount", amount); put("journal_entry_id", journal)
            put("approval_reference", p.optString("approval_reference")); put("created_by", actorId)
        })
        workAudit(db, orderId, stationId, "capitalization", order,
            JSONObject().put("decision", decision).put("amount", amount), actorId)
        return success(orderId, "تم إثبات قرار المصروف أو الرسملة بقيد فعلي")
            .put("capitalization_id", capitalizationId).put("journal_entry_id", journal)
    }

    private fun listCustodies(db: SQLiteDatabase, stationId: Int, p: JSONObject): JSONObject {
        val rows = JSONArray()
        val status = p.optString("status", "")
        val limit = p.optInt("limit", 200).coerceIn(1, 500)
        val sql = if (status.isBlank())
            "SELECT * FROM operating_custodies WHERE station_id=? AND is_deleted=0 ORDER BY id DESC LIMIT ?"
        else "SELECT * FROM operating_custodies WHERE station_id=? AND is_deleted=0 AND status=? ORDER BY id DESC LIMIT ?"
        val args = if (status.isBlank()) arrayOf(stationId.toString(), limit.toString())
            else arrayOf(stationId.toString(), status, limit.toString())
        db.rawQuery(sql, args).use { c -> while (c.moveToNext()) rows.put(cursorObject(c)) }
        return JSONObject().put("success", true).put("station_id", stationId).put("data", rows)
    }

    private fun listWorkOrders(db: SQLiteDatabase, stationId: Int, p: JSONObject): JSONObject {
        val rows = JSONArray()
        val status = p.optString("status", "")
        val limit = p.optInt("limit", 200).coerceIn(1, 500)
        val sql = if (status.isBlank())
            "SELECT * FROM work_orders WHERE station_id=? AND is_deleted=0 ORDER BY id DESC LIMIT ?"
        else "SELECT * FROM work_orders WHERE station_id=? AND is_deleted=0 AND status=? ORDER BY id DESC LIMIT ?"
        val args = if (status.isBlank()) arrayOf(stationId.toString(), limit.toString())
            else arrayOf(stationId.toString(), status, limit.toString())
        db.rawQuery(sql, args).use { c -> while (c.moveToNext()) rows.put(cursorObject(c)) }
        return JSONObject().put("success", true).put("station_id", stationId).put("data", rows)
    }

    private fun createWarrantyClaim(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val id = p.optLong("work_order_id")
        workOrder(db, stationId, id)
        val amount = p.optDouble("claim_amount", 0.0)
        require(amount.isFinite() && amount >= 0) { "قيمة مطالبة الضمان غير صالحة" }
        val claim = db.insertOrThrow("work_order_warranty_claims", null, ContentValues().apply {
            put("uuid", uuid()); put("work_order_id", id); put("station_id", stationId)
            put("claim_type", p.optString("claim_type", "supplier")); put("supplier_party_id", p.optLong("supplier_party_id", 0L).takeIf { it > 0 })
            put("reference_no", p.optString("reference_no", "")); put("claim_amount", amount)
            put("notes", p.optString("notes", "")); put("created_by", actorId)
        })
        workAudit(db, id, stationId, "warranty_claim", JSONObject(), JSONObject().put("claim_id", claim), actorId)
        return success(id, "تم إنشاء مطالبة الضمان دون تحميل العميل").put("claim_id", claim)
    }

    private fun recordDowntime(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val id = p.optLong("work_order_id")
        val order = workOrder(db, stationId, id)
        val started = p.optString("started_at", now())
        val ended = p.optString("ended_at", "")
        val minutes = p.optDouble("downtime_minutes", 0.0)
        val lost = p.optDouble("lost_sales_estimate", 0.0)
        require(minutes.isFinite() && minutes >= 0 && lost.isFinite() && lost >= 0) { "قيم التوقف غير صالحة" }
        val eventId = db.insertOrThrow("work_order_downtime", null, ContentValues().apply {
            put("uuid", uuid()); put("work_order_id", id); put("station_id", stationId)
            put("asset_type", order.optString("asset_type")); put("asset_id", order.optLong("asset_id"))
            put("started_at", started); if (ended.isNotBlank()) put("ended_at", ended)
            put("downtime_minutes", minutes); put("lost_sales_estimate", lost); put("notes", p.optString("notes", ""))
            put("created_by", actorId)
        })
        db.update("work_orders", ContentValues().apply {
            put("downtime_minutes", order.optDouble("downtime_minutes") + minutes); put("updated_at", now())
        }, "id=? AND station_id=?", arrayOf(id.toString(), stationId.toString()))
        workAudit(db, id, stationId, "downtime", JSONObject(), JSONObject().put("event_id", eventId)
            .put("lost_sales_estimate", lost), actorId)
        return success(id, "تم تسجيل توقف الأصل؛ تقدير المبيعات المفقودة ليس إيراداً ولا قيداً محاسبياً")
            .put("event_id", eventId)
    }

    private fun stocktake(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val id = p.optLong("work_order_id")
        workOrder(db, stationId, id)
        val product = p.optLong("product_id")
        requireProduct(db, product, stationId)
        val warehouse = requireWarehouse(db, stationId, p.optLong("warehouse_id"))
        val counted = p.optDouble("counted_quantity", Double.NaN)
        require(counted.isFinite() && counted >= 0) { "كمية الجرد غير صالحة" }
        val current = inventoryQuantity(db, product, warehouse)
        val difference = counted - current
        if (!p.optBoolean("approve", false)) {
            return JSONObject().put("success", true).put("approved", false).put("system_quantity", current)
                .put("counted_quantity", counted).put("variance", difference)
                .put("message", "تمت معاينة الجرد دون تعديل المخزون")
        }
        require(p.optString("approval_reference").isNotBlank()) { "مرجع اعتماد تسوية الجرد مطلوب" }
        if (kotlin.math.abs(difference) > 0.000001) {
            val unitCost = inventoryCost(db, product, warehouse)
            val value = kotlin.math.abs(difference) * unitCost
            adjustInventory(db, stationId, actorId, product, warehouse, kotlin.math.abs(difference),
                difference > 0, unitCost, "work_order_stocktake", id, "تسوية جرد قطع الغيار")
            if (value > 0.000001) {
                val inventory = accountId(db, "CWS-INVENTORY-" + stationId, "مخزون قطع الغيار", "asset", "debit")
                val variance = if (difference > 0)
                    accountId(db, "CWS-STOCKTAKE-GAIN-" + stationId, "فروقات جرد دائنة", "revenue", "credit")
                else accountId(db, "CWS-STOCKTAKE-LOSS-" + stationId, "خسائر فروقات الجرد", "expense", "debit")
                val lines = if (difference > 0)
                    listOf(Triple(inventory, value, 0.0), Triple(variance, 0.0, value))
                else listOf(Triple(variance, value, 0.0), Triple(inventory, 0.0, value))
                postJournal(db, stationId, actorId, "work_order_stocktake", id, "تسوية جرد معتمدة", lines)
            }
        }
        return JSONObject().put("success", true).put("approved", true).put("system_quantity", current)
            .put("counted_quantity", counted).put("variance", difference).put("message", "تم تطبيق تسوية الجرد المعتمدة")
    }

    private fun saveWorkshopBudget(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val year = p.optInt("budget_year")
        val month = p.optInt("budget_month")
        val center = p.optString("cost_center", "WORKSHOP")
        val amount = p.optDouble("budget_amount", Double.NaN)
        require(year in 2000..2200 && month in 1..12 && amount.isFinite() && amount >= 0) { "بيانات ميزانية الورشة غير صالحة" }
        db.execSQL("""INSERT INTO workshop_budgets(uuid,station_id,budget_year,budget_month,cost_center,budget_amount,created_by)
            VALUES(?,?,?,?,?,?,?) ON CONFLICT(station_id,budget_year,budget_month,cost_center)
            DO UPDATE SET budget_amount=excluded.budget_amount,updated_at=CURRENT_TIMESTAMP""",
            arrayOf(uuid(), stationId, year, month, center, amount, actorId))
        val id = scalar(db, """SELECT id FROM workshop_budgets WHERE station_id=? AND budget_year=?
            AND budget_month=? AND cost_center=?""", stationId, year, month, center).toLong()
        return success(id, "تم حفظ ميزانية مركز الورشة")
    }

    private fun reverseWorkOrder(db: SQLiteDatabase, stationId: Int, actorId: Long, p: JSONObject): JSONObject {
        val id = p.optLong("work_order_id")
        val row = workOrder(db, stationId, id)
        require(p.optString("reason").isNotBlank()) { "سبب العكس مطلوب" }
        require(row.optString("status") == "cancelled" || row.optString("status") == "closed") {
            "لا يمكن عكس أمر العمل قبل إغلاقه أو إلغائه"
        }
        val journals = mutableListOf<Long>()
        db.rawQuery("""SELECT DISTINCT je.id FROM journal_entries je
            WHERE je.station_id=? AND je.reference_id=? AND je.reference_type LIKE 'work_order%'
            AND je.status='posted' AND je.is_deleted=0""",
            arrayOf(stationId.toString(), id.toString())).use { c -> while (c.moveToNext()) journals.add(c.getLong(0)) }
        journals.forEach { journalId ->
            val lines = mutableListOf<Triple<Long, Double, Double>>()
            db.rawQuery("SELECT account_id,debit,credit FROM journal_entry_items WHERE journal_entry_id=?",
                arrayOf(journalId.toString())).use { c -> while (c.moveToNext()) lines.add(Triple(c.getLong(0), c.getDouble(1), c.getDouble(2))) }
            if (lines.isNotEmpty()) postJournal(db, stationId, actorId, "work_order_reversal", id,
                "عكس أمر عمل: " + p.optString("reason"), lines.map { Triple(it.first, it.third, it.second) })
        }
        db.update("work_orders", ContentValues().apply { put("status", "reversed"); put("updated_at", now()) },
            "id=? AND station_id=?", arrayOf(id.toString(), stationId.toString()))
        workAudit(db, id, stationId, "reverse", row, JSONObject().put("reversed_journals", journals.size), actorId)
        return success(id, "تم عكس قيود أمر العمل دون حذف مستنداته").put("reversed_journals", journals.size)
    }

    private fun workshopProfitability(db: SQLiteDatabase, stationId: Int, p: JSONObject): JSONObject {
        val id = p.optLong("work_order_id", 0L)
        val args = if (id > 0) arrayOf(stationId.toString(), id.toString()) else arrayOf(stationId.toString())
        val where = if (id > 0) "AND w.id=?" else ""
        val rows = JSONArray()
        db.rawQuery("""SELECT w.id,w.order_code,w.actual_labor_cost,w.actual_parts_cost,w.invoiced_amount,
            COALESCE(w.invoiced_amount,0)-COALESCE(w.actual_labor_cost,0)-COALESCE(w.actual_parts_cost,0) AS margin
            FROM work_orders w WHERE w.station_id=? AND w.is_deleted=0 $where ORDER BY w.id DESC""", args).use { c ->
            while (c.moveToNext()) rows.put(JSONObject().put("work_order_id", c.getLong(0)).put("order_code", c.getString(1))
                .put("labor_cost", c.getDouble(2)).put("parts_cost", c.getDouble(3))
                .put("revenue", c.getDouble(4)).put("margin", c.getDouble(5)))
        }
        return JSONObject().put("success", true).put("station_id", stationId).put("data", rows)
    }

    private fun workshopBudgetReport(db: SQLiteDatabase, stationId: Int, p: JSONObject): JSONObject {
        val year = p.optInt("budget_year")
        val month = p.optInt("budget_month")
        val rows = JSONArray()
        db.rawQuery("""SELECT b.cost_center,b.budget_amount,
            COALESCE((SELECT SUM(w.actual_labor_cost+w.actual_parts_cost) FROM work_orders w
              WHERE w.station_id=b.station_id AND w.cost_center=b.cost_center
              AND strftime('%Y',w.created_at)=CAST(b.budget_year AS TEXT)
              AND CAST(strftime('%m',w.created_at) AS INTEGER)=b.budget_month
              AND w.status NOT IN ('cancelled','reversed')),0) actual_cost
            FROM workshop_budgets b WHERE b.station_id=? AND b.budget_year=? AND b.budget_month=?""",
            arrayOf(stationId.toString(), year.toString(), month.toString())).use { c ->
            while (c.moveToNext()) rows.put(JSONObject().put("cost_center", c.getString(0))
                .put("budget", c.getDouble(1)).put("actual", c.getDouble(2))
                .put("variance", c.getDouble(1) - c.getDouble(2)))
        }
        return JSONObject().put("success", true).put("data", rows)
    }

    private fun workshopReconcile(db: SQLiteDatabase, stationId: Int, p: JSONObject): JSONObject {
        val mismatchedOrders = scalar(db, """SELECT COUNT(*) FROM work_orders w WHERE w.station_id=?
            AND w.is_deleted=0 AND (ABS(w.actual_labor_cost-COALESCE((SELECT SUM(l.amount) FROM work_order_labor l WHERE l.work_order_id=w.id),0))>0.01
            OR ABS(w.actual_parts_cost-COALESCE((SELECT SUM(CASE WHEN p.movement_type IN ('issue','damage') THEN p.total_cost
              WHEN p.movement_type='return' THEN -p.total_cost ELSE 0 END) FROM work_order_parts p
              WHERE p.work_order_id=w.id),0))>0.01)""", stationId)
        val badJournals = scalar(db, """SELECT COUNT(*) FROM journal_entries
            WHERE station_id=? AND reference_type LIKE 'work_order%' AND status='posted'
            AND ABS(total_debit-total_credit)>0.01""", stationId)
        return JSONObject().put("success", true).put("station_id", stationId)
            .put("mismatched_work_orders", mismatchedOrders).put("unbalanced_journals", badJournals)
            .put("passed", mismatchedOrders == 0.0 && badJournals == 0.0)
    }

    private fun getWorkOrder(db: SQLiteDatabase, stationId: Int, id: Long): JSONObject {
        val row = workOrder(db, stationId, id)
        return JSONObject().put("success", true).put("data", row)
    }

    private fun getCustody(db: SQLiteDatabase, stationId: Int, id: Long): JSONObject {
        return JSONObject().put("success", true).put("data", custodyRow(db, stationId, id))
    }

    private fun workAudit(db: SQLiteDatabase, id: Long, stationId: Int, action: String,
                          old: JSONObject, new: JSONObject, actorId: Long) {
        db.insertOrThrow("work_order_audit_events", null, ContentValues().apply {
            put("uuid", uuid()); put("work_order_id", id); put("station_id", stationId); put("action", action)
            put("old_value", old.toString()); put("new_value", new.toString()); put("performed_by", actorId)
        })
    }

    private fun requireProduct(db: SQLiteDatabase, productId: Long, stationId: Int) {
        require(productId > 0L) { "الصنف مطلوب" }
        db.rawQuery("""SELECT 1 FROM products WHERE id=? AND COALESCE(is_deleted,0)=0
            AND COALESCE(status,'active')='active' AND (station_id IS NULL OR station_id=? OR station_id=0)""",
            arrayOf(productId.toString(), stationId.toString())).use {
            require(it.moveToFirst()) { "الصنف غير موجود أو خارج نطاق المحطة" }
        }
    }

    private fun requireWarehouse(db: SQLiteDatabase, stationId: Int, warehouseId: Long): Long {
        require(warehouseId > 0L) { "المستودع مطلوب" }
        db.rawQuery("SELECT 1 FROM warehouses WHERE id=? AND station_id=? AND is_active=1",
            arrayOf(warehouseId.toString(), stationId.toString())).use {
            require(it.moveToFirst()) { "المستودع خارج المحطة أو غير نشط" }
        }
        return warehouseId
    }

    private fun requirePartyInStation(db: SQLiteDatabase, stationId: Int, partyId: Long) {
        db.rawQuery("SELECT 1 FROM parties WHERE id=? AND (station_id IS NULL OR station_id=?) AND is_active=1 AND COALESCE(is_deleted,0)=0",
            arrayOf(partyId.toString(), stationId.toString())).use {
            require(it.moveToFirst()) { "الطرف غير موجود أو خارج المحطة" }
        }
    }

    private fun inventoryQuantity(db: SQLiteDatabase, productId: Long, warehouseId: Long): Double =
        scalar(db, "SELECT COALESCE(quantity_on_hand,0) FROM inventory_levels WHERE product_id=? AND warehouse_id=?",
            productId, warehouseId)

    private fun inventoryCost(db: SQLiteDatabase, productId: Long, warehouseId: Long): Double =
        scalar(db, "SELECT COALESCE(average_cost,0) FROM inventory_levels WHERE product_id=? AND warehouse_id=?",
            productId, warehouseId)

    /** Adds/removes real stock and immutable movement/ledger records. Returns average unit cost. */
    private fun adjustInventory(db: SQLiteDatabase, stationId: Int, actorId: Long,
        productId: Long, warehouseId: Long, qty: Double, incoming: Boolean, incomingUnitCost: Double,
        referenceType: String, referenceId: Long, reason: String): Double {
        require(qty.isFinite() && qty > 0) { "كمية المخزون يجب أن تكون أكبر من صفر" }
        val before = inventoryQuantity(db, productId, warehouseId)
        val oldCost = inventoryCost(db, productId, warehouseId)
        if (!incoming) require(before + 0.000001 >= qty) { "رصيد قطع الغيار غير كافٍ" }
        val after = if (incoming) before + qty else before - qty
        val cost = if (incoming && incomingUnitCost > 0) incomingUnitCost else oldCost
        val avg = if (incoming && before + qty > 0)
            ((before * oldCost) + (qty * incomingUnitCost)) / (before + qty) else oldCost
        db.execSQL("""INSERT INTO inventory_levels(product_id,warehouse_id,quantity_on_hand,quantity_committed,average_cost)
            VALUES(?,?,?,0,?) ON CONFLICT(product_id,warehouse_id) DO UPDATE SET
            quantity_on_hand=excluded.quantity_on_hand,average_cost=excluded.average_cost""",
            arrayOf(productId, warehouseId, after, avg))
        val movementType = when {
            referenceType.contains("return") -> "return"
            referenceType.contains("damage") -> "damage"
            incoming -> "in"
            else -> "out"
        }
        val movementId = db.insertOrThrow("inventory_movements", null, ContentValues().apply {
            put("uuid", uuid()); put("movement_code", "CWM-" + UUID.randomUUID().toString().take(10))
            put("product_id", productId); put("station_id", stationId); put("warehouse_id", warehouseId)
            put("movement_type", movementType); put("movement_subtype", referenceType)
            put("quantity_before", before); put("quantity_change", if (incoming) qty else -qty)
            put("quantity_after", after); put("unit_cost", cost); put("total_cost", qty * cost)
            put("reference_type", referenceType); put("reference_id", referenceId)
            put("reason", reason); put("performed_by", actorId); put("created_by", actorId)
            put("status", "completed"); put("created_at", now())
        })
        db.insertOrThrow("inventory_ledger", null, ContentValues().apply {
            put("uuid", uuid()); put("product_id", productId); put("warehouse_id", warehouseId)
            put("transaction_date", now()); put("transaction_type", referenceType)
            put("transaction_id", movementId); put("reference_number", referenceId.toString())
            put("debit", if (incoming) qty else 0.0); put("credit", if (incoming) 0.0 else qty)
            put("balance", after); put("average_cost", avg); put("description", reason); put("created_by", actorId)
        })
        return cost
    }

    private fun lastInventoryMovement(db: SQLiteDatabase, stationId: Int, productId: Long,
                                      subtype: String, referenceId: Long): Long =
        scalar(db, """SELECT id FROM inventory_movements WHERE station_id=? AND product_id=?
            AND movement_subtype=? AND reference_id=? ORDER BY id DESC LIMIT 1""",
            stationId, productId, subtype, referenceId).toLong()

    private fun accountId(db: SQLiteDatabase, code: String, name: String,
                          type: String, normal: String, cashBoxId: Long = 0L,
                          bankAccountId: Long = 0L): Long {
        val existing = scalar(db, "SELECT id FROM accounts WHERE account_code=?", code).toLong()
        if (existing > 0) return existing
        val id = db.insertOrThrow("accounts", null, ContentValues().apply {
            put("uuid", uuid()); put("account_code", code); put("account_name", name)
            put("account_name_ar", name); put("level", 1); put("account_type", type)
            put("account_category", "system_custody_workshop"); put("normal_balance", normal)
            put("opening_balance", 0.0); put("current_balance", 0.0); put("is_active", 1)
            put("is_control_account", 0); put("is_cash_account", if (cashBoxId > 0) 1 else 0)
            if (cashBoxId > 0) put("cash_box_id", cashBoxId)
            if (bankAccountId > 0) put("bank_account_id", bankAccountId)
            put("created_at", now())
        })
        return id
    }

    private fun postJournal(db: SQLiteDatabase, stationId: Int, actorId: Long,
        referenceType: String, referenceId: Long, description: String,
        lines: List<Triple<Long, Double, Double>>): Long {
        require(lines.size >= 2) { "القيد يجب أن يحتوي بندين على الأقل" }
        val normalized = lines.filter { it.second > 0.000001 || it.third > 0.000001 }
        val debitTotal = normalized.sumOf { it.second }
        val creditTotal = normalized.sumOf { it.third }
        require(debitTotal > 0 && kotlin.math.abs(debitTotal - creditTotal) < 0.011) { "القيد غير متوازن" }
        normalized.forEach {
            require(it.first > 0 && ((it.second > 0 && it.third == 0.0) || (it.third > 0 && it.second == 0.0))) {
                "بند القيد يجب أن يكون مديناً أو دائناً فقط"
            }
        }
        val entry = db.insertOrThrow("journal_entries", null, ContentValues().apply {
            put("uuid", uuid()); put("entry_number", "CWS-" + UUID.randomUUID().toString().take(16).uppercase(Locale.ROOT))
            put("entry_date", today()); put("entry_type", "general")
            put("reference_type", referenceType); put("reference_id", referenceId)
            put("reference_code", referenceType + "-" + referenceId); put("description", description)
            put("description_ar", description); put("total_debit", debitTotal); put("total_credit", creditTotal)
            put("is_balanced", 1); put("status", "posted"); put("posted_at", now()); put("posted_by", actorId)
            put("station_id", stationId); put("created_by", actorId); put("created_at", now())
        })
        normalized.forEachIndexed { index, line ->
            db.insertOrThrow("journal_entry_items", null, ContentValues().apply {
                put("uuid", uuid()); put("journal_entry_id", entry); put("line_number", index + 1)
                put("account_id", line.first); put("debit", line.second); put("credit", line.third)
                put("description", description)
            })
            val normal = scalarText(db, "SELECT normal_balance FROM accounts WHERE id=?", line.first)
            val old = scalar(db, "SELECT current_balance FROM accounts WHERE id=?", line.first)
            val next = if (normal == "credit") old + line.third - line.second else old + line.second - line.third
            db.update("accounts", ContentValues().apply { put("current_balance", next); put("updated_at", now()) },
                "id=?", arrayOf(line.first.toString()))
        }
        return entry
    }

    private fun positiveAmount(p: JSONObject, key: String): Double {
        val value = p.optDouble(key, Double.NaN)
        require(value.isFinite() && value > 0.0) { "القيمة " + key + " يجب أن تكون أكبر من صفر" }
        return value
    }

    private fun cursorObject(c: android.database.Cursor): JSONObject {
        val out = JSONObject()
        for (i in 0 until c.columnCount) {
            val value: Any? = when (c.getType(i)) {
                android.database.Cursor.FIELD_TYPE_NULL -> null
                android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                android.database.Cursor.FIELD_TYPE_BLOB -> android.util.Base64.encodeToString(c.getBlob(i), android.util.Base64.NO_WRAP)
                else -> c.getString(i)
            }
            out.put(c.getColumnName(i), value)
        }
        return out
    }

    private fun scalar(db: SQLiteDatabase, sql: String, vararg args: Any): Double {
        val values = args.map { it.toString() }.toTypedArray()
        return db.rawQuery(sql, values).use { if (it.moveToFirst()) it.getDouble(0) else 0.0 }
    }

    private fun scalarText(db: SQLiteDatabase, sql: String, arg: Any): String =
        db.rawQuery(sql, arrayOf(arg.toString())).use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }
}