package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Duration
import java.util.UUID
import kotlin.math.abs
import kotlin.math.round

/**
 * Station-scoped operating-custody and workshop domain repository.
 *
 * It records business events in SQLite, posts balanced immutable journal entries,
 * uses idempotency keys for retryable operations, and never initiates external payments.
 */
object CustodyWorkshopRepository {
    private val permissions = listOf(
        "custody.read","custody.create","custody.update","custody.approve","custody.disburse","custody.settle","custody.reverse",
        "workshop.read","workshop.create","workshop.update","workshop.approve","workshop.issue_parts","workshop.return_parts",
        "workshop.invoice","workshop.close","workshop.stocktake"
    )

    fun ensureSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS operating_custodies(
            id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT NOT NULL UNIQUE,custody_code TEXT NOT NULL,
            station_id INTEGER NOT NULL,employee_id INTEGER NOT NULL,shift_id INTEGER,cost_center TEXT,purpose TEXT NOT NULL,
            amount REAL NOT NULL CHECK(amount>0),status TEXT NOT NULL DEFAULT 'requested'
              CHECK(status IN ('requested','approved','issued','partially_settled','settled','reversed','rejected')),
            requested_by INTEGER NOT NULL,approved_by INTEGER,issued_by INTEGER,expense_total REAL NOT NULL DEFAULT 0 CHECK(expense_total>=0),
            returned_total REAL NOT NULL DEFAULT 0 CHECK(returned_total>=0),recovered_total REAL NOT NULL DEFAULT 0 CHECK(recovered_total>=0),outstanding_amount REAL NOT NULL DEFAULT 0 CHECK(outstanding_amount>=0),
            is_deleted INTEGER NOT NULL DEFAULT 0,idempotency_key TEXT NOT NULL,created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(station_id,custody_code),UNIQUE(station_id,idempotency_key),FOREIGN KEY(employee_id) REFERENCES employees(id),
            FOREIGN KEY(requested_by) REFERENCES users(id),FOREIGN KEY(approved_by) REFERENCES users(id),FOREIGN KEY(issued_by) REFERENCES users(id))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS operating_custody_expenses(
            id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT NOT NULL UNIQUE,custody_id INTEGER NOT NULL,station_id INTEGER NOT NULL,
            expense_type TEXT NOT NULL CHECK(expense_type IN ('expense','asset','inventory')),description TEXT NOT NULL,
            amount REAL NOT NULL CHECK(amount>0),account_id INTEGER NOT NULL,product_id INTEGER,warehouse_id INTEGER,
            receipt_number TEXT NOT NULL,document_sha256 TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'accepted',
            idempotency_key TEXT NOT NULL,created_by INTEGER NOT NULL,created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,journal_entry_id INTEGER,
            UNIQUE(station_id,receipt_number),UNIQUE(station_id,idempotency_key),FOREIGN KEY(custody_id) REFERENCES operating_custodies(id),
            FOREIGN KEY(account_id) REFERENCES accounts(id),FOREIGN KEY(product_id) REFERENCES products(id),
            FOREIGN KEY(warehouse_id) REFERENCES warehouses(id),FOREIGN KEY(created_by) REFERENCES users(id),
            FOREIGN KEY(journal_entry_id) REFERENCES journal_entries(id))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS operating_custody_returns(
            id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT NOT NULL UNIQUE,custody_id INTEGER NOT NULL,station_id INTEGER NOT NULL,
            amount REAL NOT NULL CHECK(amount>0),source_account_id INTEGER NOT NULL,reference_number TEXT NOT NULL,
            idempotency_key TEXT NOT NULL,journal_entry_id INTEGER NOT NULL,created_by INTEGER NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(station_id,reference_number),UNIQUE(station_id,idempotency_key),
            FOREIGN KEY(custody_id) REFERENCES operating_custodies(id),FOREIGN KEY(source_account_id) REFERENCES accounts(id),
            FOREIGN KEY(journal_entry_id) REFERENCES journal_entries(id),FOREIGN KEY(created_by) REFERENCES users(id))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS operating_custody_variances(
            id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT NOT NULL UNIQUE,custody_id INTEGER NOT NULL,station_id INTEGER NOT NULL,
            variance_type TEXT NOT NULL CHECK(variance_type IN ('shortage','surplus')),amount REAL NOT NULL CHECK(amount>0),
            reason TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'investigation' CHECK(status IN ('investigation','approved','rejected','posted')),
            resolved_by INTEGER,resolved_at TEXT,created_by INTEGER NOT NULL,idempotency_key TEXT NOT NULL,
            UNIQUE(station_id,idempotency_key),FOREIGN KEY(custody_id) REFERENCES operating_custodies(id),
            FOREIGN KEY(resolved_by) REFERENCES users(id),FOREIGN KEY(created_by) REFERENCES users(id))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS operating_custody_events(
            id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT NOT NULL UNIQUE,custody_id INTEGER NOT NULL,station_id INTEGER NOT NULL,
            event_type TEXT NOT NULL,old_value TEXT,new_value TEXT,note TEXT,performed_by INTEGER NOT NULL,
            performed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,FOREIGN KEY(custody_id) REFERENCES operating_custodies(id),
            FOREIGN KEY(performed_by) REFERENCES users(id))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS custody_workshop_account_mappings(
            station_id INTEGER NOT NULL,domain TEXT NOT NULL,mapping_key TEXT NOT NULL,account_id INTEGER NOT NULL,
            version INTEGER NOT NULL DEFAULT 1,updated_by INTEGER NOT NULL,updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY(station_id,domain,mapping_key),FOREIGN KEY(account_id) REFERENCES accounts(id),FOREIGN KEY(updated_by) REFERENCES users(id))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS custody_workshop_idempotency(
            id INTEGER PRIMARY KEY AUTOINCREMENT,station_id INTEGER NOT NULL,operation TEXT NOT NULL,
            idempotency_key TEXT NOT NULL,resource_id INTEGER NOT NULL,created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(station_id,operation,idempotency_key))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS workshop_work_orders(
            id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT NOT NULL UNIQUE,work_order_code TEXT NOT NULL,station_id INTEGER NOT NULL,
            asset_type TEXT NOT NULL,asset_id INTEGER NOT NULL,customer_party_id INTEGER,vendor_party_id INTEGER,title TEXT NOT NULL,
            description TEXT,priority TEXT NOT NULL DEFAULT 'normal',
            status TEXT NOT NULL DEFAULT 'open' CHECK(status IN ('open','approved','in_progress','on_hold','completed','invoiced','cancelled','closed')),
            requested_by INTEGER NOT NULL,assigned_to INTEGER,cost_center TEXT,estimated_cost REAL NOT NULL DEFAULT 0 CHECK(estimated_cost>=0),
            actual_parts_cost REAL NOT NULL DEFAULT 0 CHECK(actual_parts_cost>=0),actual_labor_cost REAL NOT NULL DEFAULT 0 CHECK(actual_labor_cost>=0),
            other_cost REAL NOT NULL DEFAULT 0 CHECK(other_cost>=0),invoice_net REAL NOT NULL DEFAULT 0 CHECK(invoice_net>=0),
            invoice_tax REAL NOT NULL DEFAULT 0 CHECK(invoice_tax>=0),started_at TEXT,completed_at TEXT,closed_at TEXT,
            approved_by INTEGER,approved_at TEXT,idempotency_key TEXT NOT NULL,created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,is_deleted INTEGER NOT NULL DEFAULT 0,
            UNIQUE(station_id,work_order_code),UNIQUE(station_id,idempotency_key),
            FOREIGN KEY(customer_party_id) REFERENCES parties(id),FOREIGN KEY(vendor_party_id) REFERENCES parties(id),
            FOREIGN KEY(requested_by) REFERENCES users(id),FOREIGN KEY(assigned_to) REFERENCES employees(id),FOREIGN KEY(approved_by) REFERENCES users(id))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS workshop_work_order_parts(
            id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT NOT NULL UNIQUE,work_order_id INTEGER NOT NULL,station_id INTEGER NOT NULL,
            product_id INTEGER NOT NULL,warehouse_id INTEGER NOT NULL,quantity_issued REAL NOT NULL DEFAULT 0 CHECK(quantity_issued>=0),
            quantity_returned REAL NOT NULL DEFAULT 0 CHECK(quantity_returned>=0),quantity_damaged REAL NOT NULL DEFAULT 0 CHECK(quantity_damaged>=0),
            unit_cost REAL NOT NULL CHECK(unit_cost>=0),total_cost REAL NOT NULL CHECK(total_cost>=0),
            status TEXT NOT NULL DEFAULT 'issued',idempotency_key TEXT NOT NULL,journal_entry_id INTEGER,created_by INTEGER NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(station_id,idempotency_key),
            FOREIGN KEY(work_order_id) REFERENCES workshop_work_orders(id),FOREIGN KEY(product_id) REFERENCES products(id),
            FOREIGN KEY(warehouse_id) REFERENCES warehouses(id),FOREIGN KEY(journal_entry_id) REFERENCES journal_entries(id),
            FOREIGN KEY(created_by) REFERENCES users(id))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS workshop_work_order_labor(
            id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT NOT NULL UNIQUE,work_order_id INTEGER NOT NULL,station_id INTEGER NOT NULL,
            employee_id INTEGER NOT NULL,work_date TEXT NOT NULL,hours REAL NOT NULL CHECK(hours>0),hourly_rate REAL NOT NULL CHECK(hourly_rate>=0),
            amount REAL NOT NULL CHECK(amount>=0),status TEXT NOT NULL DEFAULT 'approved',idempotency_key TEXT NOT NULL,
            created_by INTEGER NOT NULL,created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(station_id,idempotency_key),
            FOREIGN KEY(work_order_id) REFERENCES workshop_work_orders(id),FOREIGN KEY(employee_id) REFERENCES employees(id),
            FOREIGN KEY(created_by) REFERENCES users(id))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS workshop_work_order_invoices(
            id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT NOT NULL UNIQUE,invoice_code TEXT NOT NULL,work_order_id INTEGER NOT NULL,
            station_id INTEGER NOT NULL,customer_party_id INTEGER NOT NULL,net_amount REAL NOT NULL CHECK(net_amount>=0),
            tax_rate REAL NOT NULL DEFAULT 0 CHECK(tax_rate>=0 AND tax_rate<=100),tax_amount REAL NOT NULL DEFAULT 0 CHECK(tax_amount>=0),
            total_amount REAL NOT NULL CHECK(total_amount>=0),status TEXT NOT NULL DEFAULT 'issued',journal_entry_id INTEGER NOT NULL,
            idempotency_key TEXT NOT NULL,created_by INTEGER NOT NULL,created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(station_id,invoice_code),UNIQUE(station_id,idempotency_key),FOREIGN KEY(work_order_id) REFERENCES workshop_work_orders(id),
            FOREIGN KEY(customer_party_id) REFERENCES parties(id),FOREIGN KEY(journal_entry_id) REFERENCES journal_entries(id),
            FOREIGN KEY(created_by) REFERENCES users(id))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS workshop_work_order_events(
            id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT NOT NULL UNIQUE,work_order_id INTEGER NOT NULL,station_id INTEGER NOT NULL,
            event_type TEXT NOT NULL,old_value TEXT,new_value TEXT,note TEXT,performed_by INTEGER NOT NULL,
            performed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,FOREIGN KEY(work_order_id) REFERENCES workshop_work_orders(id),
            FOREIGN KEY(performed_by) REFERENCES users(id))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS workshop_warranty_claims(
            id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT NOT NULL UNIQUE,work_order_id INTEGER NOT NULL,station_id INTEGER NOT NULL,
            supplier_party_id INTEGER,product_id INTEGER,claim_type TEXT NOT NULL,claim_amount REAL NOT NULL DEFAULT 0 CHECK(claim_amount>=0),
            status TEXT NOT NULL DEFAULT 'submitted',evidence_reference TEXT,idempotency_key TEXT NOT NULL,created_by INTEGER NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(station_id,idempotency_key),
            FOREIGN KEY(work_order_id) REFERENCES workshop_work_orders(id),FOREIGN KEY(supplier_party_id) REFERENCES parties(id),
            FOREIGN KEY(product_id) REFERENCES products(id),FOREIGN KEY(created_by) REFERENCES users(id))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS workshop_downtime_events(
            id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT NOT NULL UNIQUE,work_order_id INTEGER NOT NULL,station_id INTEGER NOT NULL,
            asset_type TEXT NOT NULL,asset_id INTEGER NOT NULL,started_at TEXT NOT NULL,ended_at TEXT,reason TEXT NOT NULL,
            duration_minutes INTEGER NOT NULL DEFAULT 0 CHECK(duration_minutes>=0),created_by INTEGER NOT NULL,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,FOREIGN KEY(work_order_id) REFERENCES workshop_work_orders(id),
            FOREIGN KEY(created_by) REFERENCES users(id))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS workshop_budgets(
            id INTEGER PRIMARY KEY AUTOINCREMENT,station_id INTEGER NOT NULL,fiscal_year INTEGER NOT NULL,cost_center TEXT NOT NULL,
            budget_amount REAL NOT NULL CHECK(budget_amount>=0),approved_by INTEGER NOT NULL,updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(station_id,fiscal_year,cost_center),FOREIGN KEY(approved_by) REFERENCES users(id))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS workshop_stocktakes(
            id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT NOT NULL UNIQUE,station_id INTEGER NOT NULL,warehouse_id INTEGER NOT NULL,
            product_id INTEGER NOT NULL,system_quantity REAL NOT NULL,counted_quantity REAL NOT NULL CHECK(counted_quantity>=0),
            variance_quantity REAL NOT NULL,reason TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'posted',idempotency_key TEXT NOT NULL,
            journal_entry_id INTEGER,created_by INTEGER NOT NULL,created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(station_id,idempotency_key),FOREIGN KEY(warehouse_id) REFERENCES warehouses(id),
            FOREIGN KEY(product_id) REFERENCES products(id),FOREIGN KEY(journal_entry_id) REFERENCES journal_entries(id),
            FOREIGN KEY(created_by) REFERENCES users(id))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_custody_station_status ON operating_custodies(station_id,status,is_deleted,created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_custody_expenses_scope ON operating_custody_expenses(custody_id,station_id,status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_work_orders_station_status ON workshop_work_orders(station_id,status,is_deleted,created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_work_order_parts_scope ON workshop_work_order_parts(work_order_id,station_id)")
        permissions.forEach { code ->
            val domain = code.substringBefore('.')
            val action = code.substringAfter('.')
            db.execSQL(
                "INSERT OR IGNORE INTO permissions(uuid,permission_code,permission_name,permission_name_ar,module,module_name_ar,action,requires_station,is_active,is_deleted) VALUES(?,?,?,?,?,?,?,1,1,0)",
                arrayOf("CW-" + code.uppercase().replace('.','-'),code,code,code,domain,if(domain=="custody")"العهد" else "الورشة",action)
            )
        }
    }

    fun execute(db: SQLiteDatabase, command: String, data: JSONObject, stationId: Int, actorId: Long): JSONObject {
        require(stationId>0 && actorId>0) { "المحطة والمستخدم الحالي مطلوبان" }
        requirePermission(db,actorId,permissionFor(command))
        return when(command) {
            "configure_account" -> configureAccount(db,data,stationId,actorId)
            "custody_create" -> custodyCreate(db,data,stationId,actorId)
            "custody_approve" -> custodyApprove(db,data,stationId,actorId)
            "custody_disburse" -> custodyDisburse(db,data,stationId,actorId)
            "custody_expense" -> custodyExpense(db,data,stationId,actorId)
            "custody_return" -> custodyReturn(db,data,stationId,actorId)
            "custody_settle" -> custodySettle(db,data,stationId,actorId)
            "custody_variance" -> custodyVariance(db,data,stationId,actorId)
            "custody_transfer" -> custodyTransfer(db,data,stationId,actorId)
            "custody_recover" -> custodyRecover(db,data,stationId,actorId)
            "custody_reverse" -> custodyReverse(db,data,stationId,actorId)
            "custody_ageing" -> custodyAgeing(db,stationId)
            "custody_reconcile" -> custodyReconcile(db,stationId)
            "work_order_create" -> workOrderCreate(db,data,stationId,actorId)
            "work_order_approve" -> workOrderApprove(db,data,stationId,actorId)
            "work_order_assign" -> workOrderAssign(db,data,stationId,actorId)
            "work_order_start" -> workOrderStart(db,data,stationId,actorId)
            "work_order_issue_part" -> workOrderIssuePart(db,data,stationId,actorId)
            "work_order_return_part" -> workOrderReturnPart(db,data,stationId,actorId)
            "work_order_purchase_part" -> workOrderPurchasePart(db,data,stationId,actorId)
            "work_order_labor" -> workOrderLabor(db,data,stationId,actorId)
            "work_order_estimate" -> workOrderEstimate(db,data,stationId,actorId)
            "work_order_invoice" -> workOrderInvoice(db,data,stationId,actorId)
            "work_order_warranty" -> workOrderWarranty(db,data,stationId,actorId)
            "work_order_cancel" -> workOrderCancel(db,data,stationId,actorId)
            "work_order_damage_part" -> workOrderDamagePart(db,data,stationId,actorId)
            "work_order_downtime" -> workOrderDowntime(db,data,stationId,actorId)
            "work_order_close" -> workOrderClose(db,data,stationId,actorId)
            "work_order_stocktake" -> workOrderStocktake(db,data,stationId,actorId)
            "work_order_budget" -> workOrderBudget(db,data,stationId,actorId)
            "work_order_report" -> workOrderReport(db,stationId)
            "work_order_reconcile" -> workOrderReconcile(db,stationId)
            else -> throw IllegalArgumentException("عملية غير مدعومة: " + command)
        }
    }

    private fun permissionFor(c:String)=when(c) {
        "configure_account"->"accounting.update"
        "custody_create"->"custody.create";"custody_approve","custody_variance"->"custody.approve"
        "custody_disburse"->"custody.disburse";"custody_expense","custody_transfer"->"custody.update"
        "custody_return","custody_settle","custody_recover"->"custody.settle";"custody_reverse"->"custody.reverse"
        "custody_ageing","custody_reconcile"->"custody.read"
        "work_order_create"->"workshop.create";"work_order_approve","work_order_budget"->"workshop.approve"
        "work_order_issue_part","work_order_purchase_part","work_order_damage_part"->"workshop.issue_parts"
        "work_order_return_part"->"workshop.return_parts";"work_order_invoice"->"workshop.invoice"
        "work_order_close"->"workshop.close";"work_order_stocktake"->"workshop.stocktake"
        "work_order_report","work_order_reconcile"->"workshop.read"
        else->"workshop.update"
    }

    private fun requirePermission(db:SQLiteDatabase,userId:Long,code:String) {
        val action=code.substringAfterLast('.')
        val col=when(action) {
            "read"->"can_read";"create"->"can_create";"update"->"can_update";"approve","disburse","settle","reverse","issue_parts","return_parts","invoice","close","stocktake"->"can_approve"
            "delete"->"can_delete";"export"->"can_export";else->throw SecurityException("صلاحية غير معروفة: "+code)
        }
        val found=db.rawQuery("""SELECT 1 FROM permissions p WHERE p.permission_code=? AND p.is_active=1 AND COALESCE(p.is_deleted,0)=0 AND (
            EXISTS(SELECT 1 FROM user_permissions up WHERE up.user_id=? AND up.permission_id=p.id AND up.is_granted=1)
            OR EXISTS(SELECT 1 FROM users u JOIN role_permissions rp ON rp.role_id=u.role_id
              WHERE u.id=? AND rp.permission_id=p.id AND COALESCE(rp.is_deleted,0)=0 AND rp.$col=1)) LIMIT 1""",
            arrayOf(code,userId.toString(),userId.toString())).use{it.moveToFirst()}
        if(!found) throw SecurityException("لا تملك صلاحية تنفيذ العملية: "+code)
    }

    private fun configureAccount(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db) {
        val domain=d.optString("domain");val key=d.optString("mapping_key");val account=d.optLong("account_id")
        require(domain=="custody"||domain=="workshop")
        val allowed=if(domain=="custody") setOf("custody_receivable","expense","asset","inventory","payroll_payable","shortage","surplus")
            else setOf("inventory_asset","maintenance_expense","labor_expense","labor_payable","accounts_payable","accounts_receivable","service_revenue","vat_payable","damage_expense","inventory_variance","warranty_receivable")
        require(key in allowed){"مفتاح الحساب غير معروف"}
        requireAccount(db,account)
        db.execSQL("""INSERT INTO custody_workshop_account_mappings(station_id,domain,mapping_key,account_id,version,updated_by)
          VALUES(?,?,?,?,1,?) ON CONFLICT(station_id,domain,mapping_key) DO UPDATE SET account_id=excluded.account_id,
          version=custody_workshop_account_mappings.version+1,updated_by=excluded.updated_by,updated_at=CURRENT_TIMESTAMP""",
          arrayOf(s,domain,key,account,u))
        result(account,"تم تحديث الربط للمعاملات الجديدة فقط")
    }

    private fun mapping(db:SQLiteDatabase,s:Int,domain:String,key:String):Long {
        val id=db.rawQuery("SELECT account_id FROM custody_workshop_account_mappings WHERE station_id=? AND domain=? AND mapping_key=?",
            arrayOf(s.toString(),domain,key)).use{if(it.moveToFirst())it.getLong(0)else 0L}
        requireAccount(db,id)
        return id
    }

    private fun requireAccount(db:SQLiteDatabase,id:Long,types:Set<String>?=null) {
        require(id>0){"الحساب المحاسبي مطلوب"}
        val type=db.rawQuery("""SELECT account_type FROM accounts a WHERE id=? AND is_active=1 AND COALESCE(is_deleted,0)=0
          AND NOT EXISTS(SELECT 1 FROM accounts c WHERE c.parent_account_id=a.id AND c.is_active=1 AND COALESCE(c.is_deleted,0)=0)""",
          arrayOf(id.toString())).use{if(it.moveToFirst())it.getString(0)else null}
        require(type!=null){"الحساب غير موجود أو غير نشط أو تجميعي"}
        require(types==null||type in types){"نوع الحساب غير مناسب"}
    }

    private fun tx(db:SQLiteDatabase,block:()->JSONObject):JSONObject {
        db.beginTransaction()
        try { val value=block();db.setTransactionSuccessful();return value } finally { db.endTransaction() }
    }

    private fun result(id:Long,message:String,data:JSONObject=JSONObject())=
        JSONObject().put("success",true).put("id",id).put("message",message).put("data",data)

    private fun money(v:Double):Double {
        require(v.isFinite()&&v>=0.0){"قيمة مالية غير صالحة"}
        return round(v*100.0)/100.0
    }

    private fun remember(db:SQLiteDatabase,s:Int,op:String,key:String,id:Long) {
        require(key.isNotBlank()&&key.length<=120){"مفتاح منع التكرار مطلوب"}
        db.insertOrThrow("custody_workshop_idempotency",null,ContentValues().apply{
            put("station_id",s);put("operation",op);put("idempotency_key",key);put("resource_id",id)
        })
    }

    private fun replay(db:SQLiteDatabase,s:Int,op:String,key:String):Long? {
        require(key.isNotBlank()&&key.length<=120){"مفتاح منع التكرار مطلوب"}
        return db.rawQuery("SELECT resource_id FROM custody_workshop_idempotency WHERE station_id=? AND operation=? AND idempotency_key=?",
            arrayOf(s.toString(),op,key)).use{if(it.moveToFirst())it.getLong(0)else null}
    }

    private fun row(db:SQLiteDatabase,table:String,id:Long,s:Int):JSONObject {
        require(id>0)
        return db.rawQuery("SELECT * FROM $table WHERE id=? AND station_id=? AND COALESCE(is_deleted,0)=0",
            arrayOf(id.toString(),s.toString())).use{require(it.moveToFirst()){"السجل غير موجود ضمن المحطة الحالية"};json(it)}
    }

    private fun json(c:Cursor):JSONObject {
        val o=JSONObject()
        for(i in 0 until c.columnCount) {
            if(c.isNull(i))o.put(c.getColumnName(i),JSONObject.NULL)
            else when(c.getType(i)) {
                Cursor.FIELD_TYPE_INTEGER->o.put(c.getColumnName(i),c.getLong(i))
                Cursor.FIELD_TYPE_FLOAT->o.put(c.getColumnName(i),c.getDouble(i))
                else->o.put(c.getColumnName(i),c.getString(i))
            }
        }
        return o
    }

    private fun employee(db:SQLiteDatabase,id:Long,s:Int) {
        require(id>0)
        val found=db.rawQuery("SELECT 1 FROM employees WHERE id=? AND COALESCE(is_deleted,0)=0 AND status IN ('active','on_leave') AND (station_id=? OR station_id IS NULL OR station_id=0)",
            arrayOf(id.toString(),s.toString())).use{it.moveToFirst()}
        require(found){"الموظف غير نشط أو خارج المحطة"}
    }

    private fun openPeriod(db:SQLiteDatabase,s:Int) {
        val closed=db.rawQuery("SELECT 1 FROM fiscal_periods WHERE station_id=? AND date('now') BETWEEN date(period_start) AND date(period_end) AND status='closed' LIMIT 1",
            arrayOf(s.toString())).use{it.moveToFirst()}
        require(!closed){"الفترة المالية مغلقة"}
    }

    private fun journal(db:SQLiteDatabase,s:Int,u:Long,ref:String,id:Long,description:String,lines:List<Triple<Long,Double,Double>>,type:String="general"):Long {
        require(lines.size>=2&&id>0)
        val totals=linkedMapOf<Long,Pair<Double,Double>>()
        lines.forEach{(a,d,c)->
            val debit=money(d);val credit=money(c)
            require((debit>0&&credit==0.0)||(credit>0&&debit==0.0)){"بند القيد يجب أن يكون مديناً أو دائناً فقط"}
            requireAccount(db,a)
            val old=totals[a]?: (0.0 to 0.0);totals[a]=(old.first+debit) to (old.second+credit)
        }
        require(totals.values.all{it.first==0.0||it.second==0.0}){"الحساب نفسه لا يجوز أن يظهر مديناً ودائناً"}
        val debit=money(totals.values.sumOf{it.first});val credit=money(totals.values.sumOf{it.second})
        require(debit>0&&abs(debit-credit)<0.01){"القيد غير متوازن"}
        val date=LocalDate.now().toString();val now=LocalDateTime.now().toString().replace('T',' ').take(19)
        val journalId=db.insertOrThrow("journal_entries",null,ContentValues().apply{
            put("uuid",UUID.randomUUID().toString());put("entry_number","CW-"+s+"-"+UUID.randomUUID().toString().take(12).uppercase())
            put("entry_date",date);put("entry_type",type);put("reference_type",ref);put("reference_id",id);put("reference_code",ref+"-"+id)
            put("description",description);put("description_ar",description);put("total_debit",debit);put("total_credit",credit)
            put("is_balanced",1);put("status","posted");put("posted_at",now);put("posted_by",u)
            put("fiscal_year",date.substring(0,4).toInt());put("fiscal_period",date.substring(5,7).toInt())
            put("station_id",s);put("created_at",now);put("updated_at",now);put("created_by",u);put("updated_by",u);put("is_deleted",0)
        })
        totals.entries.forEachIndexed{index,e->
            db.insertOrThrow("journal_entry_items",null,ContentValues().apply{
                put("uuid",UUID.randomUUID().toString());put("journal_entry_id",journalId);put("line_number",index+1)
                put("account_id",e.key);put("debit",money(e.value.first));put("credit",money(e.value.second))
                put("description",description);put("description_ar",description)
            })
        }
        return journalId
    }

    private fun auditCustody(db:SQLiteDatabase,id:Long,s:Int,u:Long,event:String,old:String?,new:String?,note:String="") {
        db.insertOrThrow("operating_custody_events",null,ContentValues().apply{
            put("uuid",UUID.randomUUID().toString());put("custody_id",id);put("station_id",s);put("event_type",event)
            put("old_value",old);put("new_value",new);put("note",note);put("performed_by",u)
        })
    }
    private fun auditWork(db:SQLiteDatabase,id:Long,s:Int,u:Long,event:String,old:String?,new:String?,note:String="") {
        db.insertOrThrow("workshop_work_order_events",null,ContentValues().apply{
            put("uuid",UUID.randomUUID().toString());put("work_order_id",id);put("station_id",s);put("event_type",event)
            put("old_value",old);put("new_value",new);put("note",note);put("performed_by",u)
        })
    }

    private fun custodyCreate(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        replay(db,s,"custody_create",d.optString("idempotency_key"))?.let{return@tx result(it,"طلب العهدة موجود مسبقاً",JSONObject().put("replayed",true))}
        val emp=d.optLong("employee_id");employee(db,emp,s);val amount=money(d.optDouble("amount",Double.NaN))
        require(amount>0);require(d.optString("purpose").isNotBlank());openPeriod(db,s)
        val id=db.insertOrThrow("operating_custodies",null,ContentValues().apply{
            put("uuid",UUID.randomUUID().toString());put("custody_code","CST-"+s+"-"+UUID.randomUUID().toString().take(9).uppercase())
            put("station_id",s);put("employee_id",emp);put("shift_id",d.optLong("shift_id").takeIf{it>0})
            put("cost_center",d.optString("cost_center"));put("purpose",d.optString("purpose"));put("amount",amount)
            put("status","requested");put("requested_by",u);put("idempotency_key",d.optString("idempotency_key"))
        })
        remember(db,s,"custody_create",d.optString("idempotency_key"),id);auditCustody(db,id,s,u,"created",null,"requested")
        result(id,"تم إنشاء طلب العهدة")
    }

    private fun custodyApprove(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        val id=d.optLong("custody_id");val old=row(db,"operating_custodies",id,s)
        require(old.optString("status")=="requested"&&old.optLong("requested_by")!=u){"يجب أن تكون العهدة معلقة وأن يختلف المعتمد عن مقدم الطلب"}
        db.update("operating_custodies",ContentValues().apply{put("status","approved");put("approved_by",u);put("updated_at","CURRENT_TIMESTAMP")},
            "id=? AND station_id=? AND status='requested'",arrayOf(id.toString(),s.toString())).also{require(it==1)}
        auditCustody(db,id,s,u,"approved","requested","approved");result(id,"تم اعتماد العهدة")
    }

    private fun custodyDisburse(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        val key=d.optString("idempotency_key");replay(db,s,"custody_disburse",key)?.let{return@tx result(it,"الصرف موجود مسبقاً",JSONObject().put("replayed",true))}
        val id=d.optLong("custody_id");val c=row(db,"operating_custodies",id,s)
        require(c.optString("status")=="approved");openPeriod(db,s)
        val amount=c.optDouble("amount");val source=d.optLong("source_account_id");requireAccount(db,source,setOf("asset"))
        val rec=mapping(db,s,"custody","custody_receivable")
        val j=journal(db,s,u,"operating_custody",id,"صرف عهدة "+c.optString("custody_code"),listOf(Triple(rec,amount,0.0),Triple(source,0.0,amount)))
        db.update("operating_custodies",ContentValues().apply{put("status","issued");put("issued_by",u);put("outstanding_amount",amount);put("updated_at","CURRENT_TIMESTAMP")},
            "id=? AND station_id=?",arrayOf(id.toString(),s.toString()))
        adjustCash(db,source,-amount);remember(db,s,"custody_disburse",key,id);auditCustody(db,id,s,u,"disbursed","approved","issued","journal="+j)
        result(id,"تم صرف العهدة وإنشاء قيد متوازن",JSONObject().put("journal_entry_id",j))
    }

    private fun custodyExpense(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        val key=d.optString("idempotency_key");replay(db,s,"custody_expense",key)?.let{return@tx result(it,"المصروف مسجل مسبقاً",JSONObject().put("replayed",true))}
        val id=d.optLong("custody_id");val c=row(db,"operating_custodies",id,s)
        require(c.optString("status") in setOf("issued","partially_settled"))
        val amount=money(d.optDouble("amount",Double.NaN));require(amount>0)
        val available=c.optDouble("amount")-c.optDouble("expense_total")-c.optDouble("returned_total")-c.optDouble("recovered_total")
        require(amount<=available+0.001){"مصروف العهدة يتجاوز المبلغ المتاح"}
        val receipt=d.optString("receipt_number").trim();require(receipt.isNotBlank()){"رقم المستند مطلوب"}
        val type=d.optString("expense_type","expense");require(type in setOf("expense","asset","inventory"))
        if(type=="inventory") {require(d.optLong("product_id")>0&&d.optLong("warehouse_id")>0);requireWarehouse(db,d.optLong("warehouse_id"),s)}
        val account=d.optLong("account_id");requireAccount(db,account)
        val digest=d.optString("document_sha256").ifBlank{sha256(receipt)}
        require(digest.matches(Regex("[0-9a-fA-F]{64}")))
        val eid=db.insertOrThrow("operating_custody_expenses",null,ContentValues().apply{
            put("uuid",UUID.randomUUID().toString());put("custody_id",id);put("station_id",s);put("expense_type",type)
            put("description",d.optString("description",receipt));put("amount",amount);put("account_id",account)
            put("product_id",d.optLong("product_id").takeIf{it>0});put("warehouse_id",d.optLong("warehouse_id").takeIf{it>0})
            put("receipt_number",receipt);put("document_sha256",digest);put("status","accepted")
            put("idempotency_key",key);put("created_by",u)
        })
        if(type=="inventory"){
            val qty=money(d.optDouble("quantity",Double.NaN));val unit=money(d.optDouble("unit_cost",amount/qty))
            require(qty>0&&abs(qty*unit-amount)<0.02){"كمية وتكلفة المخزون لا تطابق إجمالي المصروف"}
            changeStock(db,d.optLong("product_id"),d.optLong("warehouse_id"),s,qty,unit,u,"in","custody_expense",eid,"شراء مخزون من العهدة")
        }
        db.execSQL("UPDATE operating_custodies SET expense_total=expense_total+?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND station_id=?",arrayOf(amount,id,s))
        remember(db,s,"custody_expense",key,eid);auditCustody(db,id,s,u,"expense_added",null,amount.toString(),receipt)
        result(eid,"تم تسجيل مصروف العهدة والتحقق من مستنده")
    }

    private fun custodyReturn(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        val key=d.optString("idempotency_key");replay(db,s,"custody_return",key)?.let{return@tx result(it,"الإعادة مسجلة مسبقاً",JSONObject().put("replayed",true))}
        val id=d.optLong("custody_id");val c=row(db,"operating_custodies",id,s);require(c.optString("status") in setOf("issued","partially_settled"))
        val amount=money(d.optDouble("amount",Double.NaN));val available=c.optDouble("amount")-c.optDouble("expense_total")-c.optDouble("returned_total")-c.optDouble("recovered_total")
        require(amount>0&&amount<=available+0.001);openPeriod(db,s)
        val source=d.optLong("source_account_id");requireAccount(db,source,setOf("asset"));val rec=mapping(db,s,"custody","custody_receivable")
        val j=journal(db,s,u,"operating_custody_return",id,"إعادة متبقي العهدة",listOf(Triple(source,amount,0.0),Triple(rec,0.0,amount)))
        val ref=d.optString("reference_number").ifBlank{"RET-"+key}
        val rid=db.insertOrThrow("operating_custody_returns",null,ContentValues().apply{
            put("uuid",UUID.randomUUID().toString());put("custody_id",id);put("station_id",s);put("amount",amount);put("source_account_id",source)
            put("reference_number",ref);put("idempotency_key",key);put("journal_entry_id",j);put("created_by",u)
        })
        db.execSQL("UPDATE operating_custodies SET returned_total=returned_total+?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND station_id=?",arrayOf(amount,id,s))
        adjustCash(db,source,amount);remember(db,s,"custody_return",key,rid);auditCustody(db,id,s,u,"cash_returned",null,amount.toString(),"journal="+j)
        result(rid,"تم إثبات إعادة النقد",JSONObject().put("journal_entry_id",j))
    }

    private fun custodySettle(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        val id=d.optLong("custody_id");val c=row(db,"operating_custodies",id,s);require(c.optString("status") in setOf("issued","partially_settled"))
        val spent=c.optDouble("expense_total");val returned=c.optDouble("returned_total");val recovered=c.optDouble("recovered_total")
        require(abs(c.optDouble("amount")-spent-returned-recovered)<=0.01){"المصروف المقبول والنقد المعاد لا يساويان مبلغ العهدة"}
        val rec=mapping(db,s,"custody","custody_receivable")
        val q=db.rawQuery("SELECT account_id,SUM(amount) FROM operating_custody_expenses WHERE custody_id=? AND station_id=? AND status='accepted' AND journal_entry_id IS NULL GROUP BY account_id",
            arrayOf(id.toString(),s.toString()))
        val lines=mutableListOf<Triple<Long,Double,Double>>();q.use{cur->while(cur.moveToNext())lines.add(Triple(cur.getLong(0),cur.getDouble(1),0.0))}
        if(lines.isNotEmpty()){
            val total=lines.sumOf{it.second};lines.add(Triple(rec,0.0,total));val j=journal(db,s,u,"operating_custody_expense_settlement",id,"تسوية مصروفات العهدة",lines)
            db.execSQL("UPDATE operating_custody_expenses SET journal_entry_id=? WHERE custody_id=? AND station_id=? AND journal_entry_id IS NULL",arrayOf(j,id,s))
        }
        db.update("operating_custodies",ContentValues().apply{put("status","settled");put("updated_at","CURRENT_TIMESTAMP")},"id=? AND station_id=?",arrayOf(id.toString(),s.toString()))
        auditCustody(db,id,s,u,"settled",c.optString("status"),"settled");result(id,"تمت تسوية العهدة ومطابقة قيمها")
    }

    private fun custodyVariance(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        val key=d.optString("idempotency_key");replay(db,s,"custody_variance",key)?.let{return@tx result(it,"فرق العهدة موجود مسبقاً",JSONObject().put("replayed",true))}
        val id=d.optLong("custody_id");row(db,"operating_custodies",id,s)
        val amount=money(d.optDouble("amount",Double.NaN));require(amount>0)
        val kind=d.optString("variance_type");require(kind=="shortage"||kind=="surplus")
        val reason=d.optString("reason").trim();require(reason.isNotBlank()){"محضر الجرد والتحقيق مطلوب"}
        val vid=db.insertOrThrow("operating_custody_variances",null,ContentValues().apply{
            put("uuid",UUID.randomUUID().toString());put("custody_id",id);put("station_id",s);put("variance_type",kind)
            put("amount",amount);put("reason",reason);put("status","investigation");put("created_by",u);put("idempotency_key",key)
        })
        remember(db,s,"custody_variance",key,vid);auditCustody(db,id,s,u,"variance_investigation",null,kind,reason)
        result(vid,"تم تعليق فرق العهدة للتحقيق دون إثبات إيراد/مصروف تلقائي")
    }

    private fun custodyTransfer(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        val key=d.optString("idempotency_key");replay(db,s,"custody_transfer",key)?.let{return@tx result(it,"محضر التسليم مسجل مسبقاً",JSONObject().put("replayed",true))}
        val id=d.optLong("custody_id");val c=row(db,"operating_custodies",id,s);require(c.optString("status") in setOf("issued","partially_settled"))
        val target=d.optLong("new_employee_id");require(target!=c.optLong("employee_id"));employee(db,target,s)
        val handover=d.optString("handover_reference").trim();require(handover.isNotBlank())
        db.update("operating_custodies",ContentValues().apply{put("employee_id",target);put("updated_at","CURRENT_TIMESTAMP")},"id=? AND station_id=?",arrayOf(id.toString(),s.toString()))
        remember(db,s,"custody_transfer",key,id);auditCustody(db,id,s,u,"transferred",c.optString("employee_id"),target.toString(),handover)
        result(id,"تم نقل مسؤولية العهدة مع حفظ سجل التسليم")
    }

    private fun custodyRecover(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        val key=d.optString("idempotency_key");replay(db,s,"custody_recover",key)?.let{return@tx result(it,"الاسترداد مسجل مسبقاً",JSONObject().put("replayed",true))}
        val id=d.optLong("custody_id");val c=row(db,"operating_custodies",id,s);require(c.optString("status") in setOf("issued","partially_settled"))
        val amount=money(d.optDouble("amount",Double.NaN));val avail=c.optDouble("amount")-c.optDouble("expense_total")-c.optDouble("returned_total")-c.optDouble("recovered_total")
        require(amount>0&&amount<=avail+0.001);val payroll=mapping(db,s,"custody","payroll_payable");val rec=mapping(db,s,"custody","custody_receivable")
        openPeriod(db,s);val j=journal(db,s,u,"operating_custody_recovery",id,"استرداد عهدة من مستحقات الموظف",listOf(Triple(payroll,amount,0.0),Triple(rec,0.0,amount)),"payroll")
        db.execSQL("UPDATE operating_custodies SET recovered_total=recovered_total+? WHERE id=? AND station_id=?",arrayOf(amount,id,s))
        remember(db,s,"custody_recover",key,id);auditCustody(db,id,s,u,"salary_recovery",null,amount.toString(),"journal="+j+"; auth="+d.optString("authorization_reference"))
        result(id,"تم إثبات الاسترداد محاسبياً دون تنفيذ خصم راتب فعلي",JSONObject().put("journal_entry_id",j))
    }

    private fun custodyReverse(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        val id=d.optLong("custody_id");val c=row(db,"operating_custodies",id,s);require(c.optString("status") !in setOf("settled","reversed"))
        val reason=d.optString("reason").trim();require(reason.isNotBlank())
        val ids=mutableListOf<Long>()
        db.rawQuery("""SELECT id FROM journal_entries WHERE station_id=? AND status='posted' AND is_deleted=0 AND reversed_entry_id IS NULL
          AND reference_type IN ('operating_custody','operating_custody_return','operating_custody_expense_settlement','operating_custody_recovery')
          AND reference_id=?""",arrayOf(s.toString(),id.toString())).use{while(it.moveToNext())ids.add(it.getLong(0))}
        var last=0L
        ids.forEach{last=reverseJournal(db,it,s,u,reason)}
        db.update("operating_custodies",ContentValues().apply{put("status","reversed")},"id=? AND station_id=?",arrayOf(id.toString(),s.toString()))
        auditCustody(db,id,s,u,"reversed",c.optString("status"),"reversed",reason)
        result(id,"تم إنشاء قيود عكسية مع الحفاظ على القيود الأصلية",JSONObject().put("reversal_journal_entry_id",last).put("reversed_count",ids.size))
    }

    private fun reverseJournal(db:SQLiteDatabase,id:Long,s:Int,u:Long,reason:String):Long{
        val original=db.rawQuery("SELECT * FROM journal_entries WHERE id=? AND station_id=? AND status='posted' AND reversed_entry_id IS NULL",
            arrayOf(id.toString(),s.toString())).use{require(it.moveToFirst());json(it)}
        val lines=mutableListOf<Triple<Long,Double,Double>>()
        db.rawQuery("SELECT account_id,debit,credit FROM journal_entry_items WHERE journal_entry_id=? ORDER BY line_number",arrayOf(id.toString()))
            .use{while(it.moveToNext())lines.add(Triple(it.getLong(0),it.getDouble(2),it.getDouble(1)))}
        val reversal=journal(db,s,u,"reversal",id,"عكس: "+original.optString("description")+" - "+reason,lines)
        db.update("journal_entries",ContentValues().apply{put("reversed_entry_id",reversal);put("reversal_reason",reason)},"id=? AND station_id=?",arrayOf(id.toString(),s.toString()))
        return reversal
    }

    private fun custodyAgeing(db:SQLiteDatabase,s:Int):JSONObject{
        val out=JSONArray()
        db.rawQuery("""SELECT employee_id,COUNT(*) custody_count,ROUND(SUM(amount-expense_total-returned_total-recovered_total),2) outstanding
          FROM operating_custodies WHERE station_id=? AND is_deleted=0 AND status IN ('issued','partially_settled')
          GROUP BY employee_id ORDER BY outstanding DESC""",arrayOf(s.toString())).use{while(it.moveToNext())out.put(JSONObject()
            .put("employee_id",it.getLong(0)).put("custody_count",it.getInt(1)).put("outstanding",it.getDouble(2)))}
        return JSONObject().put("success",true).put("data",out)
    }
    private fun custodyReconcile(db:SQLiteDatabase,s:Int):JSONObject{
        val out=scalar(db,"SELECT COALESCE(SUM(amount-expense_total-returned_total-recovered_total),0) FROM operating_custodies WHERE station_id=? AND status IN ('issued','partially_settled') AND is_deleted=0",arrayOf(s.toString()))
        val gl=scalar(db,"SELECT COALESCE(SUM(i.debit-i.credit),0) FROM journal_entry_items i JOIN journal_entries e ON e.id=i.journal_entry_id WHERE e.station_id=? AND e.status='posted' AND e.reference_type LIKE 'operating_custody%' ",arrayOf(s.toString()))
        return JSONObject().put("success",abs(out-gl)<0.01).put("custody_outstanding",out).put("journal_net",gl).put("difference",abs(out-gl))
    }

    private fun workOrderCreate(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        replay(db,s,"work_order_create",d.optString("idempotency_key"))?.let{return@tx result(it,"أمر العمل موجود مسبقاً",JSONObject().put("replayed",true))}
        val type=d.optString("asset_type");val asset=d.optLong("asset_id");require(type in setOf("fixed_asset","tank","pump","vehicle","other")&&asset>0)
        validateAsset(db,type,asset,s);val title=d.optString("title").trim();require(title.isNotBlank());openPeriod(db,s)
        val id=db.insertOrThrow("workshop_work_orders",null,ContentValues().apply{
            put("uuid",UUID.randomUUID().toString());put("work_order_code","WO-"+s+"-"+UUID.randomUUID().toString().take(10).uppercase())
            put("station_id",s);put("asset_type",type);put("asset_id",asset);put("customer_party_id",d.optLong("customer_party_id").takeIf{it>0})
            put("vendor_party_id",d.optLong("vendor_party_id").takeIf{it>0});put("title",title);put("description",d.optString("description"))
            put("priority",d.optString("priority","normal"));put("status","open");put("requested_by",u);put("cost_center",d.optString("cost_center"))
            put("idempotency_key",d.optString("idempotency_key"))
        })
        remember(db,s,"work_order_create",d.optString("idempotency_key"),id);auditWork(db,id,s,u,"created",null,"open")
        result(id,"تم إنشاء أمر العمل وربطه بأصل المحطة")
    }

    private fun validateAsset(db:SQLiteDatabase,type:String,id:Long,s:Int){
        val table=when(type){"fixed_asset"->"fixed_assets";"tank"->"tanks";"pump"->"pumps";"vehicle"->"vehicles";else->return}
        val found=db.rawQuery("SELECT 1 FROM $table WHERE id=? AND station_id=? AND COALESCE(is_deleted,0)=0",arrayOf(id.toString(),s.toString())).use{it.moveToFirst()}
        require(found){"الأصل خارج نطاق المحطة أو غير موجود"}
    }
    private fun work(db:SQLiteDatabase,id:Long,s:Int)=row(db,"workshop_work_orders",id,s)

    private fun workOrderApprove(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        val id=d.optLong("work_order_id");val w=work(db,id,s)
        require(w.optString("status")=="open"&&w.optLong("requested_by")!=u){"يجب اعتماد أمر مفتوح من مستخدم غير مقدمه"}
        db.update("workshop_work_orders",ContentValues().apply{put("status","approved");put("approved_by",u);put("approved_at","CURRENT_TIMESTAMP")},"id=? AND station_id=?",arrayOf(id.toString(),s.toString()))
        auditWork(db,id,s,u,"approved","open","approved");result(id,"تم اعتماد أمر العمل")
    }
    private fun workOrderAssign(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        val id=d.optLong("work_order_id");val w=work(db,id,s);require(w.optString("status") in setOf("open","approved"))
        val emp=d.optLong("employee_id");employee(db,emp,s)
        db.update("workshop_work_orders",ContentValues().apply{put("assigned_to",emp)},"id=? AND station_id=?",arrayOf(id.toString(),s.toString()))
        auditWork(db,id,s,u,"assigned",w.optString("assigned_to"),emp.toString());result(id,"تم إسناد أمر العمل")
    }
    private fun workOrderStart(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        val id=d.optLong("work_order_id");val w=work(db,id,s);require(w.optString("status") in setOf("open","approved"))
        db.update("workshop_work_orders",ContentValues().apply{put("status","in_progress");put("started_at","CURRENT_TIMESTAMP")},"id=? AND station_id=?",arrayOf(id.toString(),s.toString()))
        auditWork(db,id,s,u,"started",w.optString("status"),"in_progress");result(id,"بدأ أمر العمل")
    }

    private fun requireWarehouse(db:SQLiteDatabase,id:Long,s:Int){
        require(db.rawQuery("SELECT 1 FROM warehouses WHERE id=? AND station_id=? AND is_active=1",arrayOf(id.toString(),s.toString())).use{it.moveToFirst()}){"المخزن خارج المحطة أو غير نشط"}
    }
    private fun rowCost(db:SQLiteDatabase,id:Long,s:Int)=db.rawQuery("SELECT other_cost FROM workshop_work_orders WHERE id=? AND station_id=?",arrayOf(id.toString(),s.toString())).use{if(it.moveToFirst())it.getDouble(0)else 0.0}
    private fun stock(db:SQLiteDatabase,p:Long,w:Long)=db.rawQuery("SELECT quantity_on_hand FROM inventory_levels WHERE product_id=? AND warehouse_id=?",arrayOf(p.toString(),w.toString())).use{if(it.moveToFirst())it.getDouble(0)else 0.0}
    private fun changeStock(db:SQLiteDatabase,p:Long,w:Long,s:Int,delta:Double,cost:Double,u:Long,kind:String,ref:String,refId:Long,reason:String){
        val before=stock(db,p,w);val after=money(before+delta);require(after>=0){"رصيد المخزون غير كافٍ"}
        val hasLevel=db.rawQuery("SELECT 1 FROM inventory_levels WHERE product_id=? AND warehouse_id=?",arrayOf(p.toString(),w.toString())).use{it.moveToFirst()}
        if(!hasLevel) db.insertOrThrow("inventory_levels",null,ContentValues().apply{put("product_id",p);put("warehouse_id",w);put("quantity_on_hand",after);put("quantity_committed",0);put("average_cost",cost)})
        else db.update("inventory_levels",ContentValues().apply{put("quantity_on_hand",after);if(delta>0)put("average_cost",cost)},"product_id=? AND warehouse_id=?",arrayOf(p.toString(),w.toString()))
        db.insertOrThrow("inventory_movements",null,ContentValues().apply{
            put("uuid",UUID.randomUUID().toString());put("movement_code","CWW-"+s+"-"+UUID.randomUUID().toString().take(12))
            put("product_id",p);put("station_id",s);put("warehouse_id",w);put("movement_type",kind);put("quantity_before",before)
            put("quantity_change",delta);put("quantity_after",after);put("unit_cost",cost);put("total_cost",abs(delta)*cost)
            put("reference_type",ref);put("reference_id",refId);put("reference_code",ref+"-"+refId);put("reason",reason)
            put("performed_by",u);put("status","completed");put("created_by",u)
        })
        db.insertOrThrow("inventory_ledger",null,ContentValues().apply{
            put("uuid",UUID.randomUUID().toString());put("product_id",p);put("warehouse_id",w);put("transaction_date",LocalDate.now().toString())
            put("transaction_type",kind);put("transaction_id",refId);put("reference_number",ref+"-"+refId)
            put("debit",if(delta>0)abs(delta)else 0.0);put("credit",if(delta<0)abs(delta)else 0.0);put("balance",after)
            put("average_cost",cost);put("description",reason);put("created_by",u)
        })
    }

    private fun workOrderIssuePart(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        val key=d.optString("idempotency_key");replay(db,s,"work_order_issue_part",key)?.let{return@tx result(it,"صرف القطعة مسجل مسبقاً",JSONObject().put("replayed",true))}
        val id=d.optLong("work_order_id");val w=work(db,id,s);require(w.optString("status") in setOf("approved","in_progress"))
        val p=d.optLong("product_id");val wh=d.optLong("warehouse_id");val qty=money(d.optDouble("quantity",Double.NaN));val cost=money(d.optDouble("unit_cost",Double.NaN))
        require(p>0&&qty>0);requireWarehouse(db,wh,s);require(stock(db,p,wh)>=qty);openPeriod(db,s)
        val inv=mapping(db,s,"workshop","inventory_asset");val exp=mapping(db,s,"workshop","maintenance_expense")
        changeStock(db,p,wh,s,-qty,cost,u,"out","work_order",id,"صرف قطع غيار")
        val total=money(qty*cost);val j=journal(db,s,u,"work_order_part_issue",id,"تكلفة قطع غيار أمر العمل",listOf(Triple(exp,total,0.0),Triple(inv,0.0,total)))
        val pid=db.insertOrThrow("workshop_work_order_parts",null,ContentValues().apply{
            put("uuid",UUID.randomUUID().toString());put("work_order_id",id);put("station_id",s);put("product_id",p);put("warehouse_id",wh)
            put("quantity_issued",qty);put("unit_cost",cost);put("total_cost",total);put("status","issued");put("idempotency_key",key);put("journal_entry_id",j);put("created_by",u)
        })
        db.execSQL("UPDATE workshop_work_orders SET actual_parts_cost=actual_parts_cost+? WHERE id=? AND station_id=?",arrayOf(total,id,s))
        remember(db,s,"work_order_issue_part",key,pid);auditWork(db,id,s,u,"part_issued",null,p+":"+qty)
        result(pid,"تم صرف قطع الغيار وتحديث المخزون والقيود",JSONObject().put("journal_entry_id",j).put("quantity_after",stock(db,p,wh)))
    }

    private fun workOrderReturnPart(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        val key=d.optString("idempotency_key");replay(db,s,"work_order_return_part",key)?.let{return@tx result(it,"إرجاع القطعة مسجل مسبقاً",JSONObject().put("replayed",true))}
        val pid=d.optLong("part_id")
        val part=db.rawQuery("SELECT * FROM workshop_work_order_parts WHERE id=? AND station_id=?",arrayOf(pid.toString(),s.toString())).use{require(it.moveToFirst());json(it)}
        val id=part.optLong("work_order_id");work(db,id,s)
        val qty=money(d.optDouble("quantity",Double.NaN));val available=part.optDouble("quantity_issued")-part.optDouble("quantity_returned")-part.optDouble("quantity_damaged")
        require(qty>0&&qty<=available+0.001)
        val p=part.optLong("product_id");val wh=part.optLong("warehouse_id");val cost=part.optDouble("unit_cost")
        val inv=mapping(db,s,"workshop","inventory_asset");val exp=mapping(db,s,"workshop","maintenance_expense")
        changeStock(db,p,wh,s,qty,cost,u,"return","work_order_part",pid,"إرجاع قطعة غير مستخدمة")
        val value=money(qty*cost);val j=journal(db,s,u,"work_order_part_return",pid,"عكس تكلفة القطعة المرتجعة",listOf(Triple(inv,value,0.0),Triple(exp,0.0,value)))
        val returned=part.optDouble("quantity_returned")+qty
        db.update("workshop_work_order_parts",ContentValues().apply{put("quantity_returned",returned);put("status",if(returned+part.optDouble("quantity_damaged")>=part.optDouble("quantity_issued")-0.001)"returned" else "partial")},"id=?",arrayOf(pid.toString()))
        db.execSQL("UPDATE workshop_work_orders SET actual_parts_cost=MAX(0,actual_parts_cost-?) WHERE id=? AND station_id=?",arrayOf(value,id,s))
        remember(db,s,"work_order_return_part",key,pid);auditWork(db,id,s,u,"part_returned",null,p+":"+qty)
        result(pid,"تم إرجاع القطعة وعكس تكلفة المخزون",JSONObject().put("journal_entry_id",j).put("quantity_after",stock(db,p,wh)))
    }

    private fun workOrderPurchasePart(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        val key=d.optString("idempotency_key");replay(db,s,"work_order_purchase_part",key)?.let{return@tx result(it,"شراء القطعة مسجل مسبقاً",JSONObject().put("replayed",true))}
        val id=d.optLong("work_order_id");work(db,id,s)
        val p=d.optLong("product_id");val wh=d.optLong("warehouse_id");val qty=money(d.optDouble("quantity",Double.NaN));val cost=money(d.optDouble("unit_cost",Double.NaN))
        val vendor=d.optLong("vendor_party_id");require(p>0&&qty>0&&cost>0&&vendor>0);requireWarehouse(db,wh,s);openPeriod(db,s)
        val inv=mapping(db,s,"workshop","inventory_asset");val ap=mapping(db,s,"workshop","accounts_payable")
        changeStock(db,p,wh,s,qty,cost,u,"in","work_order_purchase",id,"استلام قطعة صيانة من مورد آجل")
        val total=money(qty*cost);val j=journal(db,s,u,"work_order_purchase",id,"شراء قطع صيانة آجل",listOf(Triple(inv,total,0.0),Triple(ap,0.0,total)),"purchase")
        db.update("workshop_work_orders",ContentValues().apply{put("vendor_party_id",vendor);put("other_cost",rowCost(db,id,s)+total)},"id=? AND station_id=?",arrayOf(id.toString(),s.toString()))
        val pid=db.insertOrThrow("workshop_work_order_parts",null,ContentValues().apply{
            put("uuid",UUID.randomUUID().toString());put("work_order_id",id);put("station_id",s);put("product_id",p);put("warehouse_id",wh)
            put("quantity_issued",0);put("quantity_returned",0);put("quantity_damaged",0);put("unit_cost",cost);put("total_cost",total)
            put("status","returned");put("idempotency_key",key);put("journal_entry_id",j);put("created_by",u)
        })
        remember(db,s,"work_order_purchase_part",key,pid);auditWork(db,id,s,u,"parts_purchased",null,p+":"+qty)
        result(pid,"تم استلام المخزون وإثبات حساب المورد",JSONObject().put("journal_entry_id",j))
    }

    private fun workOrderLabor(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        val key=d.optString("idempotency_key");replay(db,s,"work_order_labor",key)?.let{return@tx result(it,"ساعات الفني مسجلة مسبقاً",JSONObject().put("replayed",true))}
        val id=d.optLong("work_order_id");val w=work(db,id,s);require(w.optString("status") in setOf("approved","in_progress"))
        val emp=d.optLong("employee_id");employee(db,emp,s);val hours=money(d.optDouble("hours",Double.NaN));val rate=money(d.optDouble("hourly_rate",Double.NaN))
        require(hours>0);val amount=money(hours*rate)
        openPeriod(db,s)
        val laborAccount=mapping(db,s,"workshop","labor_expense");val payable=mapping(db,s,"workshop","labor_payable")
        val journalId=journal(db,s,u,"work_order_labor",id,"تكلفة ساعات فني",listOf(Triple(laborAccount,amount,0.0),Triple(payable,0.0,amount)),"payroll")
        val lid=db.insertOrThrow("workshop_work_order_labor",null,ContentValues().apply{
            put("uuid",UUID.randomUUID().toString());put("work_order_id",id);put("station_id",s);put("employee_id",emp)
            put("work_date",d.optString("work_date",LocalDate.now().toString()));put("hours",hours);put("hourly_rate",rate);put("amount",amount)
            put("status","approved");put("idempotency_key",key);put("created_by",u)
        })
        db.execSQL("UPDATE workshop_work_orders SET actual_labor_cost=actual_labor_cost+? WHERE id=? AND station_id=?",arrayOf(amount,id,s))
        remember(db,s,"work_order_labor",key,lid);auditWork(db,id,s,u,"labor_recorded",null,emp+":"+hours)
        result(lid,"تم تسجيل ساعات الفني وتكلفتها دون تنفيذ صرف رواتب",JSONObject().put("amount",amount).put("journal_entry_id",journalId))
    }

    private fun workOrderEstimate(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        val id=d.optLong("work_order_id");val w=work(db,id,s);require(w.optString("status") in setOf("open","approved"))
        val amount=money(d.optDouble("estimated_cost",Double.NaN))
        db.update("workshop_work_orders",ContentValues().apply{put("estimated_cost",amount)},"id=? AND station_id=?",arrayOf(id.toString(),s.toString()))
        auditWork(db,id,s,u,"estimate_updated",w.optString("estimated_cost"),amount.toString());result(id,"تم حفظ التقدير دون تعديل التكلفة الفعلية")
    }

    private fun workOrderInvoice(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        val key=d.optString("idempotency_key");replay(db,s,"work_order_invoice",key)?.let{return@tx result(it,"الفاتورة موجودة مسبقاً",JSONObject().put("replayed",true))}
        val id=d.optLong("work_order_id");val w=work(db,id,s);require(w.optString("status") in setOf("approved","in_progress","completed"))
        val customer=d.optLong("customer_party_id",w.optLong("customer_party_id"));require(customer>0)
        val net=money(d.optDouble("net_amount",Double.NaN));val rate=d.optDouble("tax_rate",0.0);require(rate in 0.0..100.0)
        val tax=money(net*rate/100.0);val total=money(net+tax);openPeriod(db,s)
        val ar=mapping(db,s,"workshop","accounts_receivable");val rev=mapping(db,s,"workshop","service_revenue");val vat=mapping(db,s,"workshop","vat_payable")
        val lines=mutableListOf(Triple(ar,total,0.0),Triple(rev,0.0,net));if(tax>0)lines.add(Triple(vat,0.0,tax))
        val j=journal(db,s,u,"work_order_invoice",id,"فاتورة صيانة للغير",lines,"sales")
        val iid=db.insertOrThrow("workshop_work_order_invoices",null,ContentValues().apply{
            put("uuid",UUID.randomUUID().toString());put("invoice_code","WS-"+s+"-"+UUID.randomUUID().toString().take(10).uppercase())
            put("work_order_id",id);put("station_id",s);put("customer_party_id",customer);put("net_amount",net);put("tax_rate",rate)
            put("tax_amount",tax);put("total_amount",total);put("status","issued");put("journal_entry_id",j);put("idempotency_key",key);put("created_by",u)
        })
        db.update("workshop_work_orders",ContentValues().apply{put("customer_party_id",customer);put("invoice_net",net);put("invoice_tax",tax);put("status","invoiced")},"id=? AND station_id=?",arrayOf(id.toString(),s.toString()))
        remember(db,s,"work_order_invoice",key,iid);auditWork(db,id,s,u,"invoiced",w.optString("status"),"invoiced")
        result(iid,"تم إنشاء فاتورة الذمم والإيراد والضريبة",JSONObject().put("journal_entry_id",j).put("net",net).put("tax",tax).put("total",total))
    }

    private fun workOrderWarranty(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        val key=d.optString("idempotency_key");replay(db,s,"work_order_warranty",key)?.let{return@tx result(it,"مطالبة الضمان موجودة مسبقاً",JSONObject().put("replayed",true))}
        val id=d.optLong("work_order_id");work(db,id,s);val amount=money(d.optDouble("claim_amount",0.0))
        val type=d.optString("claim_type").ifBlank{"supplier_warranty"};require(type in setOf("supplier_warranty","repair_warranty","parts_warranty"))
        val evidence=d.optString("evidence_reference").trim();require(evidence.isNotBlank())
        val wid=db.insertOrThrow("workshop_warranty_claims",null,ContentValues().apply{
            put("uuid",UUID.randomUUID().toString());put("work_order_id",id);put("station_id",s);put("supplier_party_id",d.optLong("supplier_party_id").takeIf{it>0})
            put("product_id",d.optLong("product_id").takeIf{it>0});put("claim_type",type);put("claim_amount",amount);put("status","submitted")
            put("evidence_reference",evidence);put("idempotency_key",key);put("created_by",u)
        })
        remember(db,s,"work_order_warranty",key,wid);auditWork(db,id,s,u,"warranty_claim",null,wid.toString(),evidence)
        result(wid,"تم تسجيل مطالبة الضمان دون تحميل العميل")
    }

    private fun workOrderCancel(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        val id=d.optLong("work_order_id");val w=work(db,id,s);require(w.optString("status") !in setOf("invoiced","closed","cancelled"))
        val open=scalar(db,"SELECT COUNT(*) FROM workshop_work_order_parts WHERE work_order_id=? AND station_id=? AND quantity_issued>quantity_returned+quantity_damaged+0.001",arrayOf(id.toString(),s.toString()))
        require(open==0.0){"يجب إرجاع القطع غير المستخدمة أو توثيق التالف قبل الإلغاء"}
        db.update("workshop_work_orders",ContentValues().apply{put("status","cancelled")},"id=? AND station_id=?",arrayOf(id.toString(),s.toString()))
        auditWork(db,id,s,u,"cancelled",w.optString("status"),"cancelled",d.optString("reason"));result(id,"تم إلغاء أمر العمل بعد تسوية المواد")
    }

    private fun workOrderDamagePart(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        val key=d.optString("idempotency_key");replay(db,s,"work_order_damage_part",key)?.let{return@tx result(it,"التلف مسجل مسبقاً",JSONObject().put("replayed",true))}
        val pid=d.optLong("part_id");val p=db.rawQuery("SELECT * FROM workshop_work_order_parts WHERE id=? AND station_id=?",arrayOf(pid.toString(),s.toString())).use{require(it.moveToFirst());json(it)}
        val order=p.optLong("work_order_id");work(db,order,s);val qty=money(d.optDouble("quantity",Double.NaN))
        val avail=p.optDouble("quantity_issued")-p.optDouble("quantity_returned")-p.optDouble("quantity_damaged");require(qty>0&&qty<=avail+0.001)
        db.execSQL("UPDATE workshop_work_order_parts SET quantity_damaged=quantity_damaged+?,status='damaged' WHERE id=? AND station_id=?",arrayOf(qty,pid,s))
        remember(db,s,"work_order_damage_part",key,pid);auditWork(db,order,s,u,"part_damaged",null,qty.toString(),d.optString("damage_report"))
        result(pid,"تم توثيق القطعة التالفة بمحضر؛ لا يعاد إدخالها للمخزون")
    }

    private fun workOrderDowntime(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        val id=d.optLong("work_order_id");val w=work(db,id,s);val start=d.optString("started_at").ifBlank{LocalDate.now().toString()+"T00:00:00"}
        val end=d.optString("ended_at");val reason=d.optString("reason").trim();require(reason.isNotBlank())
        val minutes=if(end.isBlank())0 else Duration.between(LocalDateTime.parse(start.replace(' ','T')),LocalDateTime.parse(end.replace(' ','T'))).toMinutes().toInt()
        require(minutes>=0)
        val did=db.insertOrThrow("workshop_downtime_events",null,ContentValues().apply{
            put("uuid",UUID.randomUUID().toString());put("work_order_id",id);put("station_id",s);put("asset_type",w.optString("asset_type"))
            put("asset_id",w.optLong("asset_id"));put("started_at",start);put("ended_at",end.takeIf{it.isNotBlank()})
            put("reason",reason);put("duration_minutes",minutes);put("created_by",u)
        })
        auditWork(db,id,s,u,"downtime_recorded",null,minutes.toString(),reason);result(did,"تم تسجيل وقت التوقف الفعلي دون إثبات مبيعات افتراضية")
    }

    private fun workOrderClose(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        val id=d.optLong("work_order_id");val w=work(db,id,s);require(w.optString("status") in setOf("in_progress","completed","invoiced"))
        val open=scalar(db,"SELECT COUNT(*) FROM workshop_work_order_parts WHERE work_order_id=? AND station_id=? AND quantity_issued>quantity_returned+quantity_damaged+0.001",arrayOf(id.toString(),s.toString()))
        val labor=scalar(db,"SELECT COUNT(*) FROM workshop_work_order_labor WHERE work_order_id=? AND station_id=? AND status='draft'",arrayOf(id.toString(),s.toString()))
        require(open==0.0&&labor==0.0){"توجد قطع أو ساعات لم تتم تسويتها"}
        val actual=w.optDouble("actual_parts_cost")+w.optDouble("actual_labor_cost")+w.optDouble("other_cost")
        db.update("workshop_work_orders",ContentValues().apply{put("status","closed");put("completed_at","CURRENT_TIMESTAMP");put("closed_at","CURRENT_TIMESTAMP")},"id=? AND station_id=?",arrayOf(id.toString(),s.toString()))
        auditWork(db,id,s,u,"closed",w.optString("status"),"closed");result(id,"تم إغلاق أمر العمل",JSONObject().put("actual_cost",actual).put("estimate_variance",actual-w.optDouble("estimated_cost")))
    }

    private fun workOrderStocktake(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        val key=d.optString("idempotency_key");replay(db,s,"work_order_stocktake",key)?.let{return@tx result(it,"تسوية الجرد مسجلة مسبقاً",JSONObject().put("replayed",true))}
        val wh=d.optLong("warehouse_id");val p=d.optLong("product_id");requireWarehouse(db,wh,s)
        val count=money(d.optDouble("counted_quantity",Double.NaN));val system=stock(db,p,wh);val variance=count-system
        val reason=d.optString("reason").trim();require(reason.isNotBlank())
        val id=db.insertOrThrow("workshop_stocktakes",null,ContentValues().apply{
            put("uuid",UUID.randomUUID().toString());put("station_id",s);put("warehouse_id",wh);put("product_id",p)
            put("system_quantity",system);put("counted_quantity",count);put("variance_quantity",variance);put("reason",reason)
            put("status","posted");put("idempotency_key",key);put("created_by",u)
        })
        if(abs(variance)>0.001){
            val inv=mapping(db,s,"workshop","inventory_asset");val contra=mapping(db,s,"workshop","inventory_variance")
            val cost=db.rawQuery("SELECT average_cost FROM inventory_levels WHERE product_id=? AND warehouse_id=?",arrayOf(p.toString(),wh.toString())).use{if(it.moveToFirst())it.getDouble(0)else 0.0}
            changeStock(db,p,wh,s,variance,cost,u,if(variance>0)"adjustment" else "damage","workshop_stocktake",id,reason)
            val value=money(abs(variance)*cost);val lines=if(variance>0)listOf(Triple(inv,value,0.0),Triple(contra,0.0,value)) else listOf(Triple(contra,value,0.0),Triple(inv,0.0,value))
            val j=journal(db,s,u,"workshop_stocktake",id,"تسوية جرد قطع الغيار",lines,"adjustment")
            db.update("workshop_stocktakes",ContentValues().apply{put("journal_entry_id",j)},"id=?",arrayOf(id.toString()))
        }
        remember(db,s,"work_order_stocktake",key,id);result(id,"تمت تسوية المخزون مع تسجيل الفرق",JSONObject().put("system",system).put("counted",count).put("variance",variance))
    }

    private fun workOrderBudget(db:SQLiteDatabase,d:JSONObject,s:Int,u:Long)=tx(db){
        val year=d.optInt("fiscal_year",LocalDate.now().year);val center=d.optString("cost_center").trim();val amount=money(d.optDouble("budget_amount",Double.NaN))
        require(center.isNotBlank())
        db.execSQL("""INSERT INTO workshop_budgets(station_id,fiscal_year,cost_center,budget_amount,approved_by) VALUES(?,?,?,?,?)
          ON CONFLICT(station_id,fiscal_year,cost_center) DO UPDATE SET budget_amount=excluded.budget_amount,approved_by=excluded.approved_by,updated_at=CURRENT_TIMESTAMP""",
          arrayOf(s,year,center,amount,u))
        result(year.toLong(),"تم حفظ ميزانية الورشة",JSONObject().put("cost_center",center).put("budget_amount",amount))
    }

    private fun workOrderReport(db:SQLiteDatabase,s:Int):JSONObject{
        val arr=JSONArray()
        db.rawQuery("""SELECT id,work_order_code,asset_type,asset_id,status,estimated_cost,actual_parts_cost,actual_labor_cost,other_cost,
          invoice_net,invoice_tax,(actual_parts_cost+actual_labor_cost+other_cost) actual_cost,
          (invoice_net-(actual_parts_cost+actual_labor_cost+other_cost)) margin FROM workshop_work_orders
          WHERE station_id=? AND is_deleted=0 ORDER BY id""",arrayOf(s.toString())).use{while(it.moveToNext())arr.put(json(it))}
        return JSONObject().put("success",true).put("data",arr)
    }
    private fun workOrderReconcile(db:SQLiteDatabase,s:Int):JSONObject{
        val unbalanced=scalar(db,"SELECT COUNT(*) FROM journal_entries WHERE station_id=? AND status='posted' AND reference_type LIKE 'work_order%' AND ABS(total_debit-total_credit)>0.01",arrayOf(s.toString()))
        val cross=scalar(db,"SELECT COUNT(*) FROM inventory_movements m JOIN warehouses w ON w.id=m.warehouse_id WHERE m.station_id=? AND w.station_id<>m.station_id",arrayOf(s.toString()))
        val orphan=scalar(db,"SELECT COUNT(*) FROM workshop_work_order_parts p LEFT JOIN workshop_work_orders w ON w.id=p.work_order_id WHERE p.station_id=? AND (w.id IS NULL OR w.station_id<>p.station_id)",arrayOf(s.toString()))
        return JSONObject().put("success",unbalanced==0.0&&cross==0.0&&orphan==0.0).put("unbalanced_journals",unbalanced.toInt()).put("cross_station_movements",cross.toInt()).put("orphan_parts",orphan.toInt())
    }

    private fun adjustCash(db:SQLiteDatabase,account:Long,amount:Double){
        val cash=db.rawQuery("SELECT COALESCE(cash_box_id,0) FROM accounts WHERE id=?",arrayOf(account.toString())).use{if(it.moveToFirst())it.getLong(0)else 0L}
        if(cash>0)db.execSQL("UPDATE cash_boxes SET current_balance=current_balance+?,updated_at=CURRENT_TIMESTAMP WHERE id=?",arrayOf(amount,cash))
        val bank=db.rawQuery("SELECT COALESCE(bank_account_id,0) FROM accounts WHERE id=?",arrayOf(account.toString())).use{if(it.moveToFirst())it.getLong(0)else 0L}
        if(bank>0){
            val cols=mutableSetOf<String>();db.rawQuery("PRAGMA table_info(bank_accounts)",null).use{while(it.moveToNext())cols.add(it.getString(1))}
            val col=if("current_balance" in cols)"current_balance" else if("balance" in cols)"balance" else null
            if(col!=null)db.execSQL("UPDATE bank_accounts SET $col=$col+? WHERE id=?",arrayOf(amount,bank))
        }
    }
    private fun scalar(db:SQLiteDatabase,sql:String,args:Array<String>)=db.rawQuery(sql,args).use{if(it.moveToFirst())it.getDouble(0)else 0.0}
    private fun sha256(v:String)=MessageDigest.getInstance("SHA-256").digest(v.toByteArray()).joinToString(""){"%02x".format(it)}
}
