package com.aistudio.dieselstationsms.kxmpzq.suppliers

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.aistudio.dieselstationsms.kxmpzq.BusinessDay
import com.aistudio.dieselstationsms.kxmpzq.sms.DebtDueDateRepository
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Invoice-level supplier payable records.
 *
 * supplier_ledger remains the general-party control ledger. This repository stores the
 * subsidiary invoice balances and the explicit allocations that explain each supplier payment.
 * Legacy payments that have no invoice source are preserved as unapplied allocations rather
 * than silently assigned to an invented invoice.
 */
object SupplierPayablesRepository {
    private const val EPSILON = 0.000001

    private data class InvoiceBalance(
        val id: Long,
        val amount: Double,
        val paid: Double,
        val remaining: Double,
        val invoiceDate: String,
        val dueDate: String,
        val currencyId: Long
    )

    fun ensureSchema(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS supplier_invoices (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL UNIQUE,
                station_id INTEGER NOT NULL,
                supplier_id INTEGER NOT NULL,
                source_type TEXT NOT NULL CHECK(source_type IN ('fuel_refill','product_purchase','manual')),
                source_id INTEGER NOT NULL,
                invoice_number TEXT NOT NULL,
                invoice_date TEXT,
                due_date TEXT,
                due_date_basis TEXT,
                due_date_source TEXT NOT NULL DEFAULT 'missing_base_date',
                payment_terms_id_at_purchase INTEGER,
                payment_terms_at_purchase TEXT NOT NULL DEFAULT '',
                payment_terms_days_at_purchase INTEGER,
                payment_terms_source_at_purchase TEXT,
                currency_id INTEGER,
                total_amount REAL NOT NULL CHECK(total_amount >= 0),
                paid_amount REAL NOT NULL DEFAULT 0 CHECK(paid_amount >= 0),
                remaining_amount REAL NOT NULL CHECK(remaining_amount >= 0),
                status TEXT NOT NULL DEFAULT 'open' CHECK(status IN ('open','partial','paid','cancelled')),
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                is_deleted INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        if("payment_terms_source_at_purchase" !in columns(db,"supplier_invoices")) {
            db.execSQL("ALTER TABLE supplier_invoices ADD COLUMN payment_terms_source_at_purchase TEXT")
        }
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS supplier_invoice_allocations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL UNIQUE,
                station_id INTEGER NOT NULL,
                supplier_id INTEGER NOT NULL,
                supplier_invoice_id INTEGER,
                payment_id INTEGER NOT NULL,
                allocation_kind TEXT NOT NULL CHECK(allocation_kind IN ('invoice','unapplied_legacy')),
                amount REAL NOT NULL CHECK(amount > 0),
                currency_id INTEGER,
                notes TEXT,
                status TEXT NOT NULL DEFAULT 'posted' CHECK(status IN ('posted','reversed')),
                allocated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                is_deleted INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (supplier_invoice_id) REFERENCES supplier_invoices(id),
                FOREIGN KEY (payment_id) REFERENCES payments(id),
                FOREIGN KEY (supplier_id) REFERENCES parties(id)
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_supplier_invoice_source_unique ON supplier_invoices(station_id,source_type,source_id) WHERE is_deleted=0")
        val allocationColumns = columns(db, "supplier_invoice_allocations")
        if ("reversed_at" !in allocationColumns) db.execSQL("ALTER TABLE supplier_invoice_allocations ADD COLUMN reversed_at TEXT")
        if ("reversal_reason" !in allocationColumns) db.execSQL("ALTER TABLE supplier_invoice_allocations ADD COLUMN reversal_reason TEXT")
        if ("reversed_by" !in allocationColumns) db.execSQL("ALTER TABLE supplier_invoice_allocations ADD COLUMN reversed_by INTEGER")
        if ("unapplied_reason" !in allocationColumns) db.execSQL("ALTER TABLE supplier_invoice_allocations ADD COLUMN unapplied_reason TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_supplier_invoice_aging ON supplier_invoices(station_id,supplier_id,due_date,status,is_deleted)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_supplier_invoice_date ON supplier_invoices(station_id,supplier_id,invoice_date,is_deleted)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_supplier_invoice_alloc_invoice ON supplier_invoice_allocations(station_id,supplier_invoice_id,status,is_deleted)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_supplier_invoice_alloc_payment ON supplier_invoice_allocations(station_id,payment_id,status,is_deleted)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS supplier_allocation_reversals (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL UNIQUE,
                station_id INTEGER NOT NULL,
                supplier_id INTEGER NOT NULL,
                allocation_id INTEGER NOT NULL,
                payment_id INTEGER NOT NULL,
                supplier_invoice_id INTEGER NOT NULL,
                amount REAL NOT NULL CHECK(amount > 0),
                idempotency_key TEXT NOT NULL,
                reason TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'posted' CHECK(status IN ('posted','reversed')),
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER NOT NULL,
                FOREIGN KEY (allocation_id) REFERENCES supplier_invoice_allocations(id),
                FOREIGN KEY (payment_id) REFERENCES payments(id),
                FOREIGN KEY (supplier_invoice_id) REFERENCES supplier_invoices(id),
                UNIQUE(station_id,idempotency_key)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_supplier_allocation_reversals_allocation ON supplier_allocation_reversals(station_id,allocation_id,status)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS supplier_invoice_tasks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL UNIQUE,
                station_id INTEGER NOT NULL,
                supplier_id INTEGER NOT NULL,
                supplier_invoice_id INTEGER NOT NULL,
                task_type TEXT NOT NULL,
                due_period TEXT NOT NULL,
                reference TEXT NOT NULL DEFAULT '',
                task_date TEXT,
                amount REAL NOT NULL DEFAULT 0,
                priority TEXT NOT NULL DEFAULT 'متوسطة',
                status TEXT NOT NULL DEFAULT 'غير مدفوعة',
                notes TEXT,
                is_resolved INTEGER NOT NULL DEFAULT 0,
                is_archived INTEGER NOT NULL DEFAULT 0,
                is_deleted INTEGER NOT NULL DEFAULT 0,
                resolved_at TEXT,
                archived_at TEXT,
                created_by INTEGER,
                updated_by INTEGER,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(station_id,supplier_id,supplier_invoice_id,task_type,due_period),
                FOREIGN KEY (supplier_invoice_id) REFERENCES supplier_invoices(id),
                FOREIGN KEY (supplier_id) REFERENCES parties(id)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_supplier_invoice_tasks_pending ON supplier_invoice_tasks(station_id,is_resolved,is_archived,is_deleted,task_date)")

        db.execSQL("""
            CREATE UNIQUE INDEX IF NOT EXISTS idx_supplier_invoice_payment_once
            ON supplier_invoice_allocations(payment_id,supplier_invoice_id)
            WHERE supplier_invoice_id IS NOT NULL AND status='posted' AND is_deleted=0
        """.trimIndent())
        db.execSQL("""
            CREATE UNIQUE INDEX IF NOT EXISTS idx_supplier_legacy_unapplied_once
            ON supplier_invoice_allocations(payment_id)
            WHERE supplier_invoice_id IS NULL AND allocation_kind='unapplied_legacy' AND status='posted' AND is_deleted=0
        """.trimIndent())
        // Recreate the guard on every schema ensure so upgraded installations receive
        // the net-of-reversals invariant, not a stale IF-NOT-EXISTS trigger body.
        db.execSQL("DROP TRIGGER IF EXISTS trg_supplier_alloc_insert_guard")
        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS trg_supplier_alloc_insert_guard
            BEFORE INSERT ON supplier_invoice_allocations
            WHEN NEW.status='posted' AND NEW.is_deleted=0
            BEGIN
                SELECT RAISE(ABORT,'supplier_allocation_scope_mismatch') WHERE NOT EXISTS (
                    SELECT 1 FROM payments p WHERE p.id=NEW.payment_id AND p.station_id=NEW.station_id
                      AND p.supplier_party_id=NEW.supplier_id AND p.status='completed' AND COALESCE(p.is_deleted,0)=0
                      AND ((NEW.currency_id IS NULL OR NEW.currency_id<=0) AND (p.currency_id IS NULL OR p.currency_id<=0)
                           OR NEW.currency_id>0 AND p.currency_id=NEW.currency_id));
                SELECT RAISE(ABORT,'supplier_allocation_invoice_mismatch')
                WHERE NEW.supplier_invoice_id IS NOT NULL AND NOT EXISTS (
                    SELECT 1 FROM supplier_invoices i WHERE i.id=NEW.supplier_invoice_id
                      AND i.station_id=NEW.station_id AND i.supplier_id=NEW.supplier_id AND i.is_deleted=0
                      AND ((NEW.currency_id IS NULL OR NEW.currency_id<=0) AND (i.currency_id IS NULL OR i.currency_id<=0)
                           OR NEW.currency_id>0 AND i.currency_id=NEW.currency_id));
                SELECT RAISE(ABORT,'supplier_payment_overallocation') WHERE
                    (SELECT COALESCE(SUM(a.amount),0) FROM supplier_invoice_allocations a
                     WHERE a.payment_id=NEW.payment_id AND a.status='posted' AND a.is_deleted=0) + NEW.amount
                    > (SELECT amount FROM payments WHERE id=NEW.payment_id) + 0.000001;
                SELECT RAISE(ABORT,'supplier_invoice_overallocation') WHERE NEW.supplier_invoice_id IS NOT NULL AND
                    ((SELECT COALESCE(SUM(a.amount),0) FROM supplier_invoice_allocations a
                       WHERE a.supplier_invoice_id=NEW.supplier_invoice_id AND a.station_id=NEW.station_id
                         AND a.status='posted' AND a.is_deleted=0)
                     - (SELECT COALESCE(SUM(r.amount),0) FROM supplier_allocation_reversals r
                        WHERE r.supplier_invoice_id=NEW.supplier_invoice_id AND r.station_id=NEW.station_id
                          AND r.status='posted') + NEW.amount)
                    > (SELECT total_amount FROM supplier_invoices WHERE id=NEW.supplier_invoice_id AND station_id=NEW.station_id) + 0.000001;
            END
        """.trimIndent())
    }


    /**
     * Persist one operational task for every open supplier invoice. The composite unique
     * key is invoice- and maturity-period-specific, so refreshes and retries cannot duplicate tasks.
     */
    fun synchronizeSupplierInvoiceTasks(
        db: SQLiteDatabase,
        stationId: Int,
        asOfDate: String = BusinessDay.currentDate()
    ): Int {
        require(stationId > 0) { "معرف المحطة غير صالح لمزامنة مهام الموردين" }
        val asOf = try {
            LocalDate.parse(asOfDate).also { parsed ->
                require(parsed.toString() == asOfDate) { "تاريخ المزامنة يجب أن يكون بصيغة YYYY-MM-DD" }
            }
        } catch (error: DateTimeParseException) {
            throw IllegalArgumentException("تاريخ المزامنة غير صالح؛ استخدم YYYY-MM-DD", error)
        }
        data class Candidate(
            val invoiceId: Long,
            val supplierId: Long,
            val invoiceNumber: String,
            val dueDate: String?,
            val remaining: Double,
            val supplierName: String,
            val createdAt: String,
            val createdBy: Long?
        )
        val candidates = mutableListOf<Candidate>()
        db.rawQuery(
            """SELECT i.id,i.supplier_id,COALESCE(i.invoice_number,''),
                      CASE WHEN i.due_date_source='purchase_creation'
                             AND COALESCE(i.payment_terms_source_at_purchase,'')='explicit_text'
                             AND date(i.due_date,'+0 days')=i.due_date
                           THEN i.due_date ELSE NULL END,
                      MAX(COALESCE(i.remaining_amount,0),0),
                      TRIM(COALESCE(p.commercial_name_ar,p.commercial_name,'')),
                      COALESCE(i.created_at,''),i.created_by
               FROM supplier_invoices i
               JOIN parties p ON p.id=i.supplier_id AND p.station_id=i.station_id
                            AND p.is_deleted=0 AND p.is_active=1
               WHERE i.station_id=? AND i.is_deleted=0
                 AND i.status IN ('open','partial')
                 AND COALESCE(i.remaining_amount,0)>CAST(? AS REAL)
               ORDER BY i.id""",
            arrayOf(stationId.toString(), EPSILON.toString())
        ).use { c ->
            while (c.moveToNext()) {
                candidates += Candidate(
                    c.getLong(0), c.getLong(1), c.getString(2).orEmpty(),
                    c.getString(3)?.takeIf { it.isNotBlank() }, c.getDouble(4).coerceAtLeast(0.0),
                    c.getString(5).orEmpty(), c.getString(6).orEmpty(),
                    if (c.isNull(7)) null else c.getLong(7)
                )
            }
        }

        val now = asOf.toString() + " 00:00:00"
        val activeKeys = HashSet<String>()
        var changed = 0
        candidates.forEach { invoice ->
            val maturity = invoice.dueDate?.let(::parseDate)
            val dueDate = maturity?.toString()
            val period = dueDate ?: "UNDATED"
            val key = "${invoice.supplierId}:${invoice.invoiceId}:$period"
            activeKeys += key
            val daysUntilDue = maturity?.let { java.time.temporal.ChronoUnit.DAYS.between(asOf, it) }
            val taskStatus = when {
                maturity == null -> "بحاجة مراجعة"
                daysUntilDue != null && daysUntilDue < 0 -> "متأخرة"
                daysUntilDue != null && daysUntilDue <= 3 -> "قريبة من الاستحقاق"
                else -> "غير مدفوعة"
            }
            val priority = if (maturity == null || (daysUntilDue != null && daysUntilDue < 0)) "عالية" else "متوسطة"
            val notes = "المورد: ${invoice.supplierName} — فاتورة: ${invoice.invoiceNumber}"
            val existingId = db.rawQuery(
                """SELECT id FROM supplier_invoice_tasks
                   WHERE station_id=? AND supplier_id=? AND supplier_invoice_id=?
                     AND task_type='supplier_invoice_due' AND due_period=? LIMIT 1""",
                arrayOf(stationId.toString(), invoice.supplierId.toString(), invoice.invoiceId.toString(), period)
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            val values = ContentValues().apply {
                put("reference", invoice.invoiceNumber.ifBlank { invoice.invoiceId.toString() })
                if (dueDate != null) put("task_date", dueDate) else putNull("task_date")
                put("amount", invoice.remaining)
                put("priority", priority)
                put("status", taskStatus)
                put("notes", notes)
                put("is_resolved", 0)
                putNull("resolved_at")
                put("updated_at", now)
                if (invoice.createdBy != null) put("created_by", invoice.createdBy)
            }
            if (existingId > 0L) {
                changed += db.update(
                    "supplier_invoice_tasks", values, "id=? AND station_id=?",
                    arrayOf(existingId.toString(), stationId.toString())
                )
            } else {
                values.put("uuid", UUID.randomUUID().toString())
                values.put("station_id", stationId)
                values.put("supplier_id", invoice.supplierId)
                values.put("supplier_invoice_id", invoice.invoiceId)
                values.put("task_type", "supplier_invoice_due")
                values.put("due_period", period)
                values.put("created_at", invoice.createdAt.ifBlank { now })
                changed += if (db.insertWithOnConflict(
                    "supplier_invoice_tasks", null, values, SQLiteDatabase.CONFLICT_IGNORE
                ) > 0L) 1 else 0
            }
        }

        // Close tasks for paid, cancelled, deleted or otherwise ineligible invoices.
        // A changed maturity period retires its old task and creates one new period record.
        val stale = mutableListOf<Long>()
        db.rawQuery(
            """SELECT id,supplier_id,supplier_invoice_id,due_period
               FROM supplier_invoice_tasks
               WHERE station_id=? AND is_deleted=0 AND is_resolved=0""",
            arrayOf(stationId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val key = "${c.getLong(1)}:${c.getLong(2)}:${c.getString(3)}"
                if (key !in activeKeys) stale += c.getLong(0)
            }
        }
        stale.forEach { id ->
            changed += db.update(
                "supplier_invoice_tasks",
                ContentValues().apply {
                    put("is_resolved", 1)
                    put("status", "مكتملة")
                    put("amount", 0.0)
                    put("resolved_at", now)
                    put("updated_at", now)
                },
                "id=? AND station_id=? AND is_resolved=0 AND is_deleted=0",
                arrayOf(id.toString(), stationId.toString())
            )
        }
        return changed
    }

    private fun validatePurchaseSource(
        db: SQLiteDatabase,
        stationId: Int,
        supplierId: Long,
        sourceType: String,
        sourceId: Long,
        amount: Double
    ) {
        if (sourceType == "manual") return
        val sql = when (sourceType) {
            "fuel_refill" -> """SELECT 1 FROM tank_refills
                WHERE id=? AND station_id=? AND supplier_id=? AND status='completed'
                  AND is_deleted=0 AND ABS(COALESCE(net_amount,0)-?)<=0.000001 LIMIT 1"""
            "product_purchase" -> """SELECT 1 FROM inventory_movements
                WHERE id=? AND station_id=? AND movement_type='in'
                  AND COALESCE(movement_subtype,'')='in' AND status='completed'
                  AND is_deleted=0 AND reference_id=?
                  AND ABS(COALESCE(total_cost,0)-?)<=0.000001 LIMIT 1"""
            else -> throw IllegalArgumentException("نوع مصدر فاتورة المورد غير مدعوم")
        }
        val args = arrayOf(sourceId.toString(), stationId.toString(), supplierId.toString(), amount.toString())
        val exists = db.rawQuery(sql, args).use { it.moveToFirst() }
        require(exists) {
            if (sourceType == "fuel_refill") "لا يمكن إنشاء فاتورة مورد دون توريد وقود مكتمل مطابق للمورد والمحطة والمبلغ"
            else "لا يمكن إنشاء فاتورة مورد دون حركة شراء منتجات مكتملة ومطابقة للمورد والمحطة والمبلغ"
        }
    }

    @Synchronized
    fun createPurchaseInvoice(
        db: SQLiteDatabase,
        stationId: Int,
        supplierId: Long,
        sourceType: String,
        sourceId: Long,
        invoiceNumber: String,
        amount: Double,
        invoiceDate: String?,
        currencyId: Long?,
        actorId: Long
    ): Long {
        if (db.inTransaction()) return createPurchaseInvoiceWithinTransaction(
            db, stationId, supplierId, sourceType, sourceId, invoiceNumber, amount, invoiceDate, currencyId, actorId
        )
        db.beginTransaction()
        try {
            val id = createPurchaseInvoiceWithinTransaction(
                db, stationId, supplierId, sourceType, sourceId, invoiceNumber, amount, invoiceDate, currencyId, actorId
            )
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    private fun createPurchaseInvoiceWithinTransaction(
        db: SQLiteDatabase,
        stationId: Int,
        supplierId: Long,
        sourceType: String,
        sourceId: Long,
        invoiceNumber: String,
        amount: Double,
        invoiceDate: String?,
        currencyId: Long?,
        actorId: Long
    ): Long {
        require(stationId > 0 && supplierId > 0L && sourceId > 0L && actorId > 0L) {
            "بيانات فاتورة المورد غير صالحة"
        }
        require(sourceType in setOf("fuel_refill","product_purchase","manual")) {
            "نوع مصدر فاتورة المورد غير صالح"
        }
        require(amount.isFinite() && amount > EPSILON) { "مبلغ فاتورة المورد غير صالح" }
        validatePurchaseSource(db, stationId, supplierId, sourceType, sourceId, amount)

        val existing = db.rawQuery(
            """SELECT id,supplier_id,total_amount,invoice_number,invoice_date,currency_id
               FROM supplier_invoices
               WHERE station_id=? AND source_type=? AND source_id=? AND is_deleted=0 LIMIT 1""",
            arrayOf(stationId.toString(),sourceType,sourceId.toString())
        ).use { c ->
            if (c.moveToFirst()) JSONObject().apply {
                put("id", c.getLong(0)); put("supplier_id", c.getLong(1)); put("amount", c.getDouble(2))
                put("invoice_number", c.getString(3).orEmpty()); put("invoice_date", c.getString(4).orEmpty())
                put("currency_id", if (c.isNull(5)) JSONObject.NULL else c.getLong(5))
            } else null
        }
        if (existing != null) {
            val normalizedNumber = invoiceNumber.trim().ifBlank { "$sourceType-$sourceId" }
            val normalizedDate = parseDate(invoiceDate)?.toString().orEmpty()
            val existingCurrency = existing.optLong("currency_id", 0L)
            val requestedCurrency = currencyId?.takeIf { it > 0L } ?: 0L
            require(existing.getLong("supplier_id") == supplierId &&
                abs(existing.getDouble("amount") - amount) <= EPSILON &&
                existing.optString("invoice_number") == normalizedNumber &&
                existing.optString("invoice_date") == normalizedDate &&
                existingCurrency == requestedCurrency) {
                "تعارض بصمة فاتورة المورد للمصدر نفسه؛ لم يتم تحديث السجل السابق"
            }
            return existing.getLong("id")
        }

        val partyColumns = columns(db,"parties")
        val termsExpr = if ("payment_terms" in partyColumns) "COALESCE(p.payment_terms,'')" else "''"
        val daysExpr = if ("payment_terms_days" in partyColumns) "p.payment_terms_days" else "NULL"
        val termIdExpr = if ("payment_terms_id" in partyColumns) "p.payment_terms_id" else "NULL"
        val termSourceExpr = if ("payment_terms_source" in partyColumns) "p.payment_terms_source" else "NULL"
        val partyStation = if ("station_id" in partyColumns) " AND p.station_id=?" else ""
        val args = if ("station_id" in partyColumns) arrayOf(supplierId.toString(),stationId.toString()) else arrayOf(supplierId.toString())
        val terms = db.rawQuery(
            "SELECT $termsExpr,$daysExpr,$termIdExpr,$termSourceExpr,COALESCE(pt.type_code,'') " +
                "FROM parties p JOIN party_types pt ON pt.id=p.party_type_id " +
                "WHERE p.id=?$partyStation AND p.is_deleted=0 AND p.is_active=1 AND pt.is_deleted=0 LIMIT 1",
            args
        ).use { c ->
            require(c.moveToFirst()) { "المورد غير موجود أو غير نشط ضمن المحطة" }
            require(c.getString(4).equals("SUPPLIER", ignoreCase = true)) { "الطرف المحدد ليس مورداً" }
            listOf(c.getString(0).orEmpty(),if(c.isNull(1)) null else c.getInt(1),
                if(c.isNull(2)) null else c.getLong(2),c.getString(3).orEmpty())
        }
        val termsText=terms[0] as String
        val termsSource=terms[3] as String
        val days=if(termsSource=="explicit_text") (terms[1] as? Int)?.takeIf { it in 0..365 } ?: DebtDueDateRepository.parseTermsDays(termsText) else null
        val base = parseOptionalCanonicalDate(invoiceDate, "تاريخ فاتورة المورد")
        val due = if(base!=null && days!=null) base.plusDays(days.toLong()).toString() else null
        val now = BusinessDay.currentDate()
        val id = db.insertOrThrow("supplier_invoices",null,ContentValues().apply {
            put("uuid",UUID.randomUUID().toString())
            put("station_id",stationId)
            put("supplier_id",supplierId)
            put("source_type",sourceType)
            put("source_id",sourceId)
            put("invoice_number",invoiceNumber.trim().ifBlank { "$sourceType-$sourceId" })
            if (base != null) {
                put("invoice_date",base.toString())
                put("due_date_basis",base.toString())
                if(due!=null) { put("due_date",due);put("due_date_source","purchase_creation") }
                else { putNull("due_date");put("due_date_source","missing_terms") }
            } else {
                putNull("invoice_date")
                putNull("due_date_basis")
                putNull("due_date")
                put("due_date_source","missing_base_date")
            }
            if (terms[2] != null) put("payment_terms_id_at_purchase",terms[2] as Long)
            put("payment_terms_at_purchase",termsText)
            if(days!=null) put("payment_terms_days_at_purchase",days) else putNull("payment_terms_days_at_purchase")
            if(termsSource.isNotBlank()) put("payment_terms_source_at_purchase",termsSource) else putNull("payment_terms_source_at_purchase")
            if (currencyId != null && currencyId > 0L) put("currency_id",currencyId)
            put("total_amount",amount)
            put("paid_amount",0.0)
            put("remaining_amount",amount)
            put("status","open")
            put("created_at",now+" 00:00:00")
            put("updated_at",now+" 00:00:00")
            put("created_by",actorId)
        })
        check(id > 0L) { "تعذر حفظ فاتورة المورد" }
        synchronizeSupplierInvoiceTasks(db, stationId)
        return id
    }

    /**
     * Allocate the supplier payment FIFO by earliest maturity date, then invoice date.
     * A payment against an old control balance that has no invoice-level record is retained as
     * an explicit unapplied legacy allocation; it is never fabricated into an invoice payment.
     */
    @Synchronized
    fun allocatePayment(
        db: SQLiteDatabase,
        stationId: Int,
        supplierId: Long,
        paymentId: Long,
        amount: Double,
        actorId: Long,
        explicitInvoiceId: Long = 0L
    ): JSONObject {
        if (db.inTransaction()) {
            return allocatePaymentWithinTransaction(
                db, stationId, supplierId, paymentId, amount, actorId, explicitInvoiceId
            )
        }
        db.beginTransaction()
        try {
            val result = allocatePaymentWithinTransaction(
                db, stationId, supplierId, paymentId, amount, actorId, explicitInvoiceId
            )
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun allocatePaymentWithinTransaction(
        db: SQLiteDatabase,
        stationId: Int,
        supplierId: Long,
        paymentId: Long,
        amount: Double,
        actorId: Long,
        explicitInvoiceId: Long
    ): JSONObject {
        require(stationId > 0 && supplierId > 0L && paymentId > 0L && actorId > 0L) {
            "بيانات تخصيص دفعة المورد غير صالحة"
        }
        require(amount.isFinite() && amount > EPSILON) { "مبلغ تخصيص المورد غير صالح" }
        val payment = db.rawQuery(
            """SELECT COALESCE(supplier_party_id,0),COALESCE(station_id,0),amount,
                      COALESCE(currency_id,0),status,COALESCE(is_deleted,0)
               FROM payments WHERE id=? LIMIT 1""",
            arrayOf(paymentId.toString())
        ).use { c ->
            require(c.moveToFirst()) { "دفعة المورد غير موجودة" }
            JSONObject().apply {
                put("supplier_id",c.getLong(0));put("station_id",c.getLong(1))
                put("amount",c.getDouble(2));put("currency_id",c.getLong(3))
                put("status",c.getString(4));put("is_deleted",c.getInt(5))
            }
        }
        require(payment.getLong("supplier_id")==supplierId && payment.getInt("station_id")==stationId) {
            "دفعة المورد خارج نطاق المورد أو المحطة"
        }
        require(payment.getString("status")=="completed" && payment.getInt("is_deleted")==0) {
            "لا يمكن تخصيص دفعة غير مكتملة أو محذوفة"
        }
        require(abs(payment.getDouble("amount")-amount)<=0.000001) { "مبلغ التخصيص لا يطابق مبلغ الدفعة" }

        data class ExistingAllocation(val exists: Boolean, val amount: Double)

        val existing = db.rawQuery(
            """SELECT COUNT(*),COALESCE(SUM(amount),0)
               FROM supplier_invoice_allocations
               WHERE payment_id=? AND station_id=? AND status='posted' AND is_deleted=0""",
            arrayOf(paymentId.toString(),stationId.toString())
        ).use { c ->
            require(c.moveToFirst()) { "تعذر قراءة تخصيصات دفعة المورد" }
            ExistingAllocation(
                exists = c.getInt(0) > 0,
                amount = c.getDouble(1)
            )
        }
        if (existing.exists) {
            require(abs(existing.amount-amount)<=0.000001) {
                "تم تخصيص الدفعة مسبقاً بمبلغ يختلف عن مبلغ الدفعة"
            }
            return JSONObject().apply {
                put("success",true);put("idempotent",true)
                put("allocated_amount",existing.amount)
                put("unapplied_amount",(amount-existing.amount).coerceAtLeast(0.0))
            }
        }

        data class Candidate(val id:Long,val remaining:Double)
        val candidates=mutableListOf<Candidate>()
        val paymentCurrencyId=payment.getLong("currency_id")
        val currencyClause=if(paymentCurrencyId>0L) " AND currency_id=?" else " AND (currency_id IS NULL OR currency_id=0)"
        val currencyArgs=arrayListOf(stationId.toString(),supplierId.toString())
        if(paymentCurrencyId>0L) currencyArgs.add(paymentCurrencyId.toString())
        val invoiceFilter=if(explicitInvoiceId>0L) " AND id=?" else ""
        if(explicitInvoiceId>0L) currencyArgs.add(explicitInvoiceId.toString())
        currencyArgs.add(EPSILON.toString())
        db.rawQuery(
            """SELECT id,remaining_amount FROM supplier_invoices
               WHERE station_id=? AND supplier_id=? $currencyClause $invoiceFilter
                 AND is_deleted=0 AND status IN ('open','partial') AND remaining_amount>?
               ORDER BY CASE WHEN due_date_source='purchase_creation' AND payment_terms_source_at_purchase='explicit_text'
                                      AND due_date IS NOT NULL AND TRIM(due_date)<>'' THEN 0 ELSE 1 END,
                        CASE WHEN due_date_source='purchase_creation' AND payment_terms_source_at_purchase='explicit_text' THEN date(due_date) END,
                        CASE WHEN invoice_date IS NULL OR TRIM(invoice_date)='' THEN 1 ELSE 0 END,
                        date(invoice_date),id""",
            currencyArgs.toTypedArray()
        ).use { c -> while(c.moveToNext()) candidates += Candidate(c.getLong(0),c.getDouble(1).coerceAtLeast(0.0)) }

        if(explicitInvoiceId>0L) {
            require(candidates.size==1) { "الفاتورة المحددة غير موجودة أو مسددة أو بعملة مختلفة" }
            require(amount<=candidates.first().remaining+0.000001) {
                "مبلغ الدفعة يتجاوز رصيد الفاتورة المحددة؛ وزع الدفعة دون تجاوز المتبقي"
            }
        }

        var remainingPayment=amount
        var allocated=0.0
        var allocations=0
        candidates.forEach { invoice ->
            if(remainingPayment<=EPSILON) return@forEach
            val part=min(remainingPayment,invoice.remaining)
            if(part<=EPSILON) return@forEach
            db.insertOrThrow("supplier_invoice_allocations",null,ContentValues().apply {
                put("uuid",UUID.randomUUID().toString());put("station_id",stationId);put("supplier_id",supplierId)
                put("supplier_invoice_id",invoice.id);put("payment_id",paymentId)
                put("allocation_kind","invoice");put("amount",part)
                if(paymentCurrencyId>0L) put("currency_id",paymentCurrencyId) else putNull("currency_id")
                put("status","posted");put("allocated_at",BusinessDay.currentDate());put("created_by",actorId)
            })
            val updated=db.rawQuery(
                """SELECT MAX(0,COALESCE(SUM(a.amount),0) - COALESCE((
                       SELECT SUM(r.amount) FROM supplier_allocation_reversals r
                       WHERE r.station_id=a.station_id AND r.supplier_invoice_id=a.supplier_invoice_id
                         AND r.status='posted'),0))
                   FROM supplier_invoice_allocations a
                   WHERE a.supplier_invoice_id=? AND a.status='posted' AND a.is_deleted=0""",
                arrayOf(invoice.id.toString())
            ).use { c -> c.moveToFirst();c.getDouble(0) }
            val total=db.rawQuery(
                "SELECT total_amount FROM supplier_invoices WHERE id=? AND station_id=? AND supplier_id=? AND is_deleted=0",
                arrayOf(invoice.id.toString(),stationId.toString(),supplierId.toString())
            ).use { c -> require(c.moveToFirst()) { "فاتورة المورد غير موجودة أثناء التخصيص" };c.getDouble(0) }
            require(updated<=total+0.000001) { "إجمالي تخصيصات المورد يتجاوز قيمة الفاتورة" }
            val newRemaining=(total-updated).coerceAtLeast(0.0)
            val newStatus=when {
                newRemaining<=EPSILON -> "paid"
                updated>EPSILON -> "partial"
                else -> "open"
            }
            val changed=db.update(
                "supplier_invoices",
                ContentValues().apply {
                    put("paid_amount",updated);put("remaining_amount",newRemaining)
                    put("status",newStatus);put("updated_at",BusinessDay.currentDate()+" 00:00:00")
                },
                "id=? AND station_id=? AND supplier_id=? AND is_deleted=0",
                arrayOf(invoice.id.toString(),stationId.toString(),supplierId.toString())
            )
            require(changed==1) { "تعذر تحديث رصيد فاتورة المورد بعد التخصيص" }
            remainingPayment-=part;allocated+=part;allocations++
        }
        if(remainingPayment>EPSILON) {
            val unappliedReason = when {
                paymentCurrencyId <= 0L && candidates.isEmpty() -> "unknown_currency_or_no_eligible_invoice"
                candidates.isNotEmpty() -> "current_payment_excess_after_invoice_allocation"
                else -> "historical_or_unmatched_supplier_balance"
            }
            val unappliedNotes = when (unappliedReason) {
                "current_payment_excess_after_invoice_allocation" ->
                    "فائض دفعة حالية بعد تخصيص الفواتير المؤهلة؛ يبقى رصيداً دائناً غير موزع."
                "unknown_currency_or_no_eligible_invoice" ->
                    "دفعة غير موزعة بسبب غموض العملة أو عدم وجود فاتورة مؤهلة؛ تتطلب المطابقة."
                else ->
                    "رصيد مورد تاريخي أو غير مطابق لفاتورة مسجلة؛ يعرض كتسوية منفصلة."
            }
            db.insertOrThrow("supplier_invoice_allocations",null,ContentValues().apply {
                put("uuid",UUID.randomUUID().toString());put("station_id",stationId);put("supplier_id",supplierId)
                putNull("supplier_invoice_id");put("payment_id",paymentId)
                put("allocation_kind", "unapplied_legacy")
                put("unapplied_reason", unappliedReason)
                put("amount",remainingPayment)
                if(paymentCurrencyId>0L) put("currency_id",paymentCurrencyId) else putNull("currency_id")
                put("notes",unappliedNotes)
                put("status","posted");put("allocated_at",BusinessDay.currentDate());put("created_by",actorId)
            })
        }
        synchronizeSupplierInvoiceTasks(db, stationId)
        return JSONObject().apply {
            put("success",true);put("idempotent",false)
            put("allocated_amount",allocated);put("unapplied_amount",remainingPayment)
            put("invoice_allocation_count",allocations)
        }
    }


    /**
     * Reverse only part of one posted invoice allocation. The original allocation remains
     * unchanged and each reversal is stored independently. This never writes a cash/bank
     * movement or a journal entry because it changes only the subsidiary payable allocation.
     */
    fun reversePaymentAllocation(
        db: SQLiteDatabase,
        stationId: Int,
        supplierId: Long,
        allocationId: Long,
        amount: Double,
        actorId: Long,
        reason: String,
        idempotencyKey: String
    ): JSONObject {
        require(stationId > 0 && supplierId > 0L && allocationId > 0L && actorId > 0L) {
            "بيانات عكس تخصيص المورد غير صالحة"
        }
        require(amount.isFinite() && amount > EPSILON) { "مبلغ العكس الجزئي غير صالح" }
        val key = idempotencyKey.trim()
        require(key.isNotBlank()) { "مفتاح عكس التخصيص مطلوب لمنع التكرار" }
        if (db.inTransaction()) {
            return reversePaymentAllocationWithinTransaction(db, stationId, supplierId, allocationId, amount, actorId, reason, key)
        }
        db.beginTransaction()
        try {
            val result = reversePaymentAllocationWithinTransaction(db, stationId, supplierId, allocationId, amount, actorId, reason, key)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun reversePaymentAllocationWithinTransaction(
        db: SQLiteDatabase,
        stationId: Int,
        supplierId: Long,
        allocationId: Long,
        amount: Double,
        actorId: Long,
        reason: String,
        idempotencyKey: String
    ): JSONObject {
        db.rawQuery(
            "SELECT allocation_id,supplier_id,amount FROM supplier_allocation_reversals WHERE station_id=? AND idempotency_key=? AND status='posted' LIMIT 1",
            arrayOf(stationId.toString(), idempotencyKey)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                require(cursor.getLong(0) == allocationId && cursor.getLong(1) == supplierId &&
                    abs(cursor.getDouble(2) - amount) <= EPSILON) {
                    "مفتاح عكس التخصيص مستخدم مسبقاً لعملية أو مبلغ مختلف"
                }
                return JSONObject().apply {
                    put("success", true); put("idempotent", true); put("reversed_amount", cursor.getDouble(2)); put("allocation_id", allocationId)
                }
            }
        }
        data class Original(val paymentId: Long, val invoiceId: Long, val amount: Double, val currencyId: Long)
        val original = db.rawQuery(
            """SELECT payment_id,supplier_invoice_id,amount,COALESCE(currency_id,0)
               FROM supplier_invoice_allocations
               WHERE id=? AND station_id=? AND supplier_id=? AND allocation_kind='invoice'
                 AND status='posted' AND is_deleted=0""",
            arrayOf(allocationId.toString(), stationId.toString(), supplierId.toString())
        ).use { cursor ->
            require(cursor.moveToFirst()) { "التخصيص الأصلي غير موجود أو غير نشط" }
            Original(cursor.getLong(0), cursor.getLong(1), cursor.getDouble(2), cursor.getLong(3))
        }
        val alreadyReversed = db.rawQuery(
            "SELECT COALESCE(SUM(amount),0) FROM supplier_allocation_reversals WHERE station_id=? AND allocation_id=? AND status='posted'",
            arrayOf(stationId.toString(), allocationId.toString())
        ).use { cursor -> cursor.moveToFirst(); cursor.getDouble(0) }
        val available = original.amount - alreadyReversed
        require(available > EPSILON) { "لا يوجد رصيد تخصيص متبقٍ للعكس" }
        require(amount <= available + EPSILON) { "مبلغ العكس يتجاوز المتبقي من التخصيص" }
        val paymentCurrency = db.rawQuery(
            "SELECT COALESCE(currency_id,0) FROM payments WHERE id=? AND station_id=? AND supplier_party_id=? AND status='completed' AND is_deleted=0",
            arrayOf(original.paymentId.toString(), stationId.toString(), supplierId.toString())
        ).use { cursor -> require(cursor.moveToFirst()) { "دفعة المورد غير صالحة" }; cursor.getLong(0) }
        require(paymentCurrency == original.currencyId) { "عملة التخصيص لا تطابق عملة الدفعة" }
        db.insertOrThrow("supplier_allocation_reversals", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString()); put("station_id", stationId); put("supplier_id", supplierId)
            put("allocation_id", allocationId); put("payment_id", original.paymentId); put("supplier_invoice_id", original.invoiceId)
            put("amount", amount); put("idempotency_key", idempotencyKey)
            put("reason", reason.trim().ifBlank { "عكس تخصيص المورد" }); put("status", "posted"); put("created_by", actorId)
        })
        val activePaid = db.rawQuery(
            """SELECT COALESCE((SELECT SUM(a.amount) FROM supplier_invoice_allocations a
                                  WHERE a.station_id=? AND a.supplier_invoice_id=? AND a.status='posted' AND a.is_deleted=0),0)
                      - COALESCE((SELECT SUM(r.amount) FROM supplier_allocation_reversals r
                                  WHERE r.station_id=? AND r.supplier_invoice_id=? AND r.status='posted'),0)""",
            arrayOf(stationId.toString(), original.invoiceId.toString(), stationId.toString(), original.invoiceId.toString())
        ).use { cursor -> cursor.moveToFirst(); cursor.getDouble(0).coerceAtLeast(0.0) }
        val total = db.rawQuery(
            "SELECT total_amount FROM supplier_invoices WHERE id=? AND station_id=? AND supplier_id=? AND is_deleted=0",
            arrayOf(original.invoiceId.toString(), stationId.toString(), supplierId.toString())
        ).use { cursor -> require(cursor.moveToFirst()) { "فاتورة المورد غير موجودة" }; cursor.getDouble(0) }
        require(activePaid <= total + EPSILON) { "رصيد تخصيصات الفاتورة غير صالح" }
        val remaining = (total - activePaid).coerceAtLeast(0.0)
        val status = when { remaining <= EPSILON -> "paid"; activePaid > EPSILON -> "partial"; else -> "open" }
        require(db.update(
            "supplier_invoices",
            ContentValues().apply { put("paid_amount", activePaid); put("remaining_amount", remaining); put("status", status); put("updated_at", BusinessDay.currentDate() + " 00:00:00") },
            "id=? AND station_id=? AND supplier_id=? AND is_deleted=0",
            arrayOf(original.invoiceId.toString(), stationId.toString(), supplierId.toString())
        ) == 1) { "تعذر تحديث فاتورة المورد بعد العكس الجزئي" }
        return JSONObject().apply {
            put("success", true); put("idempotent", false); put("reversed_amount", amount); put("allocation_id", allocationId)
            put("payment_id", original.paymentId); put("supplier_invoice_id", original.invoiceId)
            put("remaining_allocation_amount", available - amount); put("invoice_paid_amount", activePaid)
            put("invoice_remaining_amount", remaining); put("cash_journal_created", false)
        }
    }

    /**
     * Reverse active allocations and reopen affected supplier invoices. Invoke inside the same
     * SQLite transaction that restores the supplier control balance and reverses the GL entry.
     */
    fun reversePaymentAllocations(
        db: SQLiteDatabase,
        stationId: Int,
        supplierId: Long,
        paymentId: Long,
        actorId: Long,
        reason: String
    ): JSONObject {
        require(stationId > 0 && supplierId > 0L && paymentId > 0L && actorId > 0L) {
            "بيانات عكس تخصيص دفعة المورد غير صالحة"
        }
        if (db.inTransaction()) {
            return reversePaymentAllocationsWithinTransaction(db, stationId, supplierId, paymentId, actorId, reason)
        }
        db.beginTransaction()
        try {
            val result = reversePaymentAllocationsWithinTransaction(db, stationId, supplierId, paymentId, actorId, reason)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun reversePaymentAllocationsWithinTransaction(
        db: SQLiteDatabase,
        stationId: Int,
        supplierId: Long,
        paymentId: Long,
        actorId: Long,
        reason: String
    ): JSONObject {
        val paymentValid = db.rawQuery(
            """SELECT 1 FROM payments WHERE id=? AND station_id=? AND supplier_party_id=?
               AND status='completed' AND is_deleted=0 LIMIT 1""",
            arrayOf(paymentId.toString(), stationId.toString(), supplierId.toString())
        ).use { it.moveToFirst() }
        require(paymentValid) { "دفعة المورد غير مكتملة أو لا تتبع المورد والمحطة المحددين" }

        data class AllocationRow(val invoiceId: Long?, val amount: Double)
        val active = mutableListOf<AllocationRow>()
        db.rawQuery(
            """SELECT supplier_invoice_id,amount FROM supplier_invoice_allocations
               WHERE station_id=? AND supplier_id=? AND payment_id=?
                 AND status='posted' AND is_deleted=0 ORDER BY id""",
            arrayOf(stationId.toString(), supplierId.toString(), paymentId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) active += AllocationRow(
                if (cursor.isNull(0)) null else cursor.getLong(0), cursor.getDouble(1)
            )
        }
        if (active.isEmpty()) return JSONObject().apply {
            put("success", true); put("idempotent", true)
            put("reversed_amount", 0.0); put("reopened_invoice_count", 0)
        }

        // A full payment reversal supersedes earlier partial allocation reversals.
        // Retire their active effect before deactivating the original allocation rows,
        // while preserving the reversal records as an audit trail.
        db.execSQL(
            """UPDATE supplier_allocation_reversals
               SET status='reversed'
               WHERE station_id=? AND status='posted'
                 AND allocation_id IN (
                    SELECT id FROM supplier_invoice_allocations
                    WHERE station_id=? AND supplier_id=? AND payment_id=?
                      AND status='posted' AND is_deleted=0
                 )""",
            arrayOf(stationId.toString(), stationId.toString(), supplierId.toString(), paymentId.toString())
        )
        val updated = db.update(
            "supplier_invoice_allocations",
            ContentValues().apply {
                put("status", "reversed")
                put("reversed_at", java.time.LocalDateTime.now().toString())
                put("reversal_reason", reason.trim().ifBlank { "عكس دفعة المورد" })
                put("reversed_by", actorId)
            },
            "station_id=? AND supplier_id=? AND payment_id=? AND status='posted' AND is_deleted=0",
            arrayOf(stationId.toString(), supplierId.toString(), paymentId.toString())
        )
        require(updated == active.size) { "تغيرت تخصيصات الدفعة أثناء عملية العكس؛ أعد المحاولة" }

        val invoiceIds = active.mapNotNull { it.invoiceId }.distinct()
        invoiceIds.forEach { invoiceId ->
            val amounts = db.rawQuery(
                """SELECT i.total_amount,
                          COALESCE((SELECT SUM(a.amount) FROM supplier_invoice_allocations a
                                    WHERE a.station_id=i.station_id AND a.supplier_invoice_id=i.id
                                      AND COALESCE(a.currency_id,0)=COALESCE(i.currency_id,0)
                                      AND a.status='posted' AND a.is_deleted=0),0)
                   FROM supplier_invoices i
                   WHERE i.id=? AND i.station_id=? AND i.supplier_id=? AND i.is_deleted=0""",
                arrayOf(invoiceId.toString(), stationId.toString(), supplierId.toString())
            ).use { cursor ->
                require(cursor.moveToFirst()) { "فاتورة المورد المرتبطة بالتخصيص غير موجودة" }
                Pair(cursor.getDouble(0), cursor.getDouble(1))
            }
            val total = amounts.first
            val paid = amounts.second
            require(total.isFinite() && paid.isFinite() && total >= -EPSILON && paid >= -EPSILON) {
                "أرصدة تخصيص فاتورة المورد غير صالحة"
            }
            require(paid <= total + EPSILON) { "تخصيصات الفاتورة المتبقية تتجاوز قيمتها الأصلية" }
            val remaining = (total - paid).coerceAtLeast(0.0)
            val status = when {
                remaining <= EPSILON -> "paid"
                paid > EPSILON -> "partial"
                else -> "open"
            }
            val rows = db.update(
                "supplier_invoices",
                ContentValues().apply {
                    put("paid_amount", paid); put("remaining_amount", remaining)
                    put("status", status); put("updated_at", BusinessDay.currentDate() + " 00:00:00")
                },
                "id=? AND station_id=? AND supplier_id=? AND is_deleted=0",
                arrayOf(invoiceId.toString(), stationId.toString(), supplierId.toString())
            )
            require(rows == 1) { "تعذر إعادة فتح فاتورة المورد بعد عكس التخصيص" }
        }
        return JSONObject().apply {
            put("success", true); put("idempotent", false)
            put("reversed_amount", active.sumOf { it.amount })
            put("reversed_allocation_count", updated)
            put("reopened_invoice_count", invoiceIds.size)
        }
    }

    fun getSupplierInvoices(
        db: SQLiteDatabase,
        stationId: Int,
        supplierId: Long = 0L,
        fromDate: String? = null,
        toDate: String? = null,
        asOfDate: String? = null
    ): JSONArray {
        require(stationId>0) { "معرف المحطة غير صالح" }
        if(supplierId>0L) requireSupplierScope(db,supplierId,stationId)
        val from = parseOptionalCanonicalDate(fromDate, "بداية فترة فواتير المورد")
        val to = parseOptionalCanonicalDate(toDate, "نهاية فترة فواتير المورد")
        val today = parseOptionalCanonicalDate(asOfDate, "تاريخ تقييم استحقاق فاتورة المورد")
            ?: LocalDate.parse(BusinessDay.currentDate())
        require(from == null || to == null || !from.isAfter(to)) {
            "بداية فترة فواتير المورد تتجاوز نهايتها"
        }
        val where=mutableListOf("i.station_id=?","i.is_deleted=0")
        val args=mutableListOf(stationId.toString())
        if(supplierId>0L){where+="i.supplier_id=?";args+=supplierId.toString()}
        if(from!=null){where+="date(i.invoice_date)>=date(?)";args+=from.toString()}
        if(to!=null){where+="date(i.invoice_date)<=date(?)";args+=to.toString()}
        val rows=JSONArray()
        db.rawQuery(
            """SELECT i.id,i.supplier_id,i.source_type,i.source_id,i.invoice_number,i.invoice_date,i.due_date,
                      i.due_date_source,i.payment_terms_at_purchase,i.payment_terms_days_at_purchase,
                      i.currency_id,i.total_amount,i.paid_amount,i.remaining_amount,i.status,
                      COALESCE(i.payment_terms_source_at_purchase,''),
                      COALESCE(p.commercial_name_ar,p.commercial_name,'') AS supplier_name,
                      MAX(0,COALESCE((SELECT SUM(a.amount) FROM supplier_invoice_allocations a
                        WHERE a.station_id=i.station_id AND a.supplier_invoice_id=i.id
                          AND a.status='posted' AND a.is_deleted=0),0)
                        - COALESCE((SELECT SUM(r.amount) FROM supplier_allocation_reversals r
                          WHERE r.station_id=i.station_id AND r.supplier_invoice_id=i.id
                            AND r.status='posted'),0)) AS allocated_amount
               FROM supplier_invoices i
               LEFT JOIN parties p ON p.id=i.supplier_id AND p.station_id=i.station_id AND p.is_deleted=0
               WHERE ${where.joinToString(" AND ")}
               ORDER BY date(i.invoice_date) DESC,i.id DESC""",
            args.toTypedArray()
        ).use { c ->
            while(c.moveToNext()) {
                val due=c.getString(6).orEmpty().take(10)
                val source=c.getString(7).orEmpty()
                val termsSource=c.getString(15).orEmpty()
                val trusted=source=="purchase_creation" && termsSource=="explicit_text"
                val dueDate=if(trusted) parseDate(due) else null
                val maturityStatus=when {
                    c.getDouble(13)<=EPSILON -> "paid"
                    dueDate==null -> "undated"
                    dueDate.isBefore(today) -> "overdue"
                    dueDate.isEqual(today) -> "due_today"
                    else -> "not_due"
                }
                rows.put(JSONObject().apply {
                    put("id",c.getLong(0));put("supplier_id",c.getLong(1));put("source_type",c.getString(2))
                    put("source_id",c.getLong(3));put("invoice_number",c.getString(4))
                    put("invoice_date",c.getString(5).orEmpty().take(10))
                    put("due_date",if(trusted) due else "")
                    put("legacy_due_date",if(!trusted) due else "")
                    put("due_date_source",source)
                    put("payment_terms_source",termsSource)
                    put("maturity_status",maturityStatus)
                    put("payment_terms",c.getString(8).orEmpty())
                    put("payment_terms_days",if(c.isNull(9)) JSONObject.NULL else c.getInt(9))
                    put("currency_id",if(c.isNull(10)) JSONObject.NULL else c.getLong(10))
                    put("amount",c.getDouble(11));put("total_amount",c.getDouble(11))
                    put("paid_amount",c.getDouble(12));put("remaining_amount",c.getDouble(13))
                    put("allocated_amount",c.getDouble(17));put("status",c.getString(14))
                    put("supplier_name",c.getString(16).orEmpty())
                })
            }
        }
        return rows
    }

    fun getSupplierPayments(
        db: SQLiteDatabase,
        stationId: Int,
        supplierId: Long = 0L,
        fromDate: String? = null,
        toDate: String? = null
    ): JSONArray {
        require(stationId>0) { "معرف المحطة غير صالح" }
        val from = parseOptionalCanonicalDate(fromDate, "بداية فترة دفعات المورد")
        val to = parseOptionalCanonicalDate(toDate, "نهاية فترة دفعات المورد")
        require(from == null || to == null || !from.isAfter(to)) { "بداية فترة دفعات المورد تتجاوز نهايتها" }
        val where=mutableListOf("p.station_id=?","p.supplier_party_id IS NOT NULL","p.is_deleted=0","p.status IN ('completed','refunded')")
        val args=mutableListOf(stationId.toString())
        if(supplierId>0L){requireSupplierScope(db,supplierId,stationId);where+="p.supplier_party_id=?";args+=supplierId.toString()}
        if(from!=null){where+="date(p.created_at)>=date(?)";args+=from.toString()}
        if(to!=null){where+="date(p.created_at)<=date(?)";args+=to.toString()}
        val rows=JSONArray()
        db.rawQuery(
            """SELECT p.id,p.supplier_party_id,p.payment_code,p.amount,p.payment_method,p.status,p.created_at,
                      p.currency_id,COALESCE(pt.commercial_name_ar,pt.commercial_name,''),
                      COALESCE((SELECT SUM(a.amount) FROM supplier_invoice_allocations a
                        WHERE a.payment_id=p.id AND a.station_id=p.station_id
                          AND a.status='posted' AND a.is_deleted=0),0) AS allocated_amount,
                      COALESCE((SELECT SUM(
                          CASE WHEN a.status='reversed' THEN a.amount
                               ELSE COALESCE((SELECT SUM(r.amount) FROM supplier_allocation_reversals r
                                              WHERE r.station_id=a.station_id AND r.allocation_id=a.id
                                                AND r.status='posted'),0)
                          END)
                        FROM supplier_invoice_allocations a
                        WHERE a.payment_id=p.id AND a.station_id=p.station_id
                          AND a.status IN ('posted','reversed') AND a.is_deleted=0),0) AS reversed_amount
               FROM payments p
               LEFT JOIN parties pt ON pt.id=p.supplier_party_id AND pt.station_id=p.station_id
               WHERE ${where.joinToString(" AND ")}
               ORDER BY datetime(p.created_at) DESC,p.id DESC""",
            args.toTypedArray()
        ).use { c ->
            while(c.moveToNext()) {
                val paymentId = c.getLong(0)
                val paymentRow = JSONObject().apply {
                    put("id",paymentId);put("supplier_id",c.getLong(1));put("payment_code",c.getString(2))
                    put("amount",c.getDouble(3));put("payment_method",c.getString(4));put("status",c.getString(5))
                    put("date",c.getString(6).orEmpty());put("currency_id",if(c.isNull(7)) JSONObject.NULL else c.getLong(7))
                    put("supplier_name",c.getString(8).orEmpty())
                    // allocated_amount is the historical gross allocation; net is explicit
                    // so callers cannot mistake the pre-reversal amount for active coverage.
                    put("allocated_amount",c.getDouble(9))
                    put("reversed_amount",c.getDouble(10))
                    put("net_allocated_amount",(c.getDouble(9)-c.getDouble(10)).coerceAtLeast(0.0))
                }
                val allocationRows = JSONArray()
                db.rawQuery(
                    """SELECT a.id,a.supplier_invoice_id,COALESCE(i.invoice_number,''),a.amount,
                              COALESCE((SELECT SUM(r.amount) FROM supplier_allocation_reversals r
                                        WHERE r.station_id=a.station_id AND r.allocation_id=a.id AND r.status='posted'),0)
                       FROM supplier_invoice_allocations a
                       LEFT JOIN supplier_invoices i ON i.id=a.supplier_invoice_id
                           AND i.station_id=a.station_id AND i.supplier_id=a.supplier_id
                       WHERE a.station_id=? AND a.supplier_id=? AND a.payment_id=?
                         AND a.allocation_kind='invoice' AND a.status='posted' AND a.is_deleted=0
                       ORDER BY a.id""",
                    arrayOf(stationId.toString(),c.getLong(1).toString(),paymentId.toString())
                ).use { allocations ->
                    while(allocations.moveToNext()) {
                        val originalAmount = allocations.getDouble(3)
                        val reversedAmount = allocations.getDouble(4).coerceAtLeast(0.0)
                        allocationRows.put(JSONObject().apply {
                            put("allocation_id",allocations.getLong(0));put("supplier_invoice_id",allocations.getLong(1))
                            put("invoice_number",allocations.getString(2).orEmpty());put("allocated_amount",originalAmount)
                            put("reversed_amount",reversedAmount)
                            put("remaining_amount",(originalAmount-reversedAmount).coerceAtLeast(0.0))
                        })
                    }
                }
                paymentRow.put("allocations",allocationRows)
                rows.put(paymentRow)
            }
        }
        return rows
    }

    fun getSupplierAging(
        db: SQLiteDatabase,
        stationId: Int,
        supplierId: Long,
        asOfDate: String? = null
    ): JSONObject {
        require(supplierId>0L && stationId>0) { "معرف المورد والمحطة مطلوبان" }
        requireSupplierScope(db,supplierId,stationId)
        val asOf=parseOptionalCanonicalDate(asOfDate, "تاريخ أعمار ذمم المورد") ?: LocalDate.parse(BusinessDay.currentDate())
        val invoices=getSupplierInvoices(db,stationId,supplierId, asOfDate=asOf.toString())
        val byCurrency=linkedMapOf<Long,JSONObject>()
        val legacyOffsets = JSONArray()
        var unallocatedWithoutCurrency = 0.0
        db.rawQuery(
            """SELECT COALESCE(currency_id,0),COALESCE(unapplied_reason,'historical_or_unmatched_supplier_balance'),COALESCE(SUM(amount),0),COUNT(*)
               FROM supplier_invoice_allocations
               WHERE station_id=? AND supplier_id=? AND supplier_invoice_id IS NULL
                 AND allocation_kind='unapplied_legacy'
                 AND status='posted' AND is_deleted=0
               GROUP BY COALESCE(currency_id,0)
               ORDER BY COALESCE(currency_id,0)""",
            arrayOf(stationId.toString(),supplierId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val currencyId = cursor.getLong(0)
                val reason = cursor.getString(1).orEmpty()
                val amount = cursor.getDouble(2)
                if (currencyId <= 0L) unallocatedWithoutCurrency += amount
                legacyOffsets.put(JSONObject().apply {
                    put("currency_id", if(currencyId > 0L) currencyId else JSONObject.NULL)
                    put("amount", amount)
                    put("allocation_count", cursor.getInt(3))
                    put("classification", reason)
                    put("legacy_balance_offset_not_invoice_allocated", reason == "historical_or_unmatched_supplier_balance")
                })
            }
        }
        // A posted partial allocation reversal releases supplier credit without
        // reversing the underlying cash payment. Report that released amount as
        // unapplied credit while the original allocation remains posted. Full payment
        // reversals retire these reversal rows and the original allocations together.
        db.rawQuery(
            """SELECT COALESCE(a.currency_id,0),COALESCE(SUM(r.amount),0),COUNT(*)
               FROM supplier_allocation_reversals r
               JOIN supplier_invoice_allocations a
                 ON a.id=r.allocation_id AND a.station_id=r.station_id AND a.supplier_id=r.supplier_id
               WHERE r.station_id=? AND r.supplier_id=? AND r.status='posted'
                 AND a.status='posted' AND a.is_deleted=0
               GROUP BY COALESCE(a.currency_id,0)
               ORDER BY COALESCE(a.currency_id,0)""",
            arrayOf(stationId.toString(),supplierId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val currencyId = cursor.getLong(0)
                val amount = cursor.getDouble(1)
                if (currencyId <= 0L) unallocatedWithoutCurrency += amount
                legacyOffsets.put(JSONObject().apply {
                    put("currency_id", if(currencyId > 0L) currencyId else JSONObject.NULL)
                    put("amount", amount)
                    put("allocation_count", cursor.getInt(2))
                    put("classification", "allocation_reversal_released_credit")
                    put("legacy_balance_offset_not_invoice_allocated", false)
                })
            }
        }
        for(i in 0 until invoices.length()) {
            val row=invoices.getJSONObject(i)
            val remaining=row.optDouble("remaining_amount",0.0).coerceAtLeast(0.0)
            if(remaining<=EPSILON || row.optString("status")=="cancelled") continue
            val currency=row.optLong("currency_id",0L)
            val total=byCurrency.getOrPut(currency) {
                JSONObject().apply {
                    put("currency_id",if(currency>0L) currency else JSONObject.NULL)
                    put("open_balance",0.0);put("not_due_amount",0.0);put("due_today_amount",0.0)
                    put("overdue_amount",0.0);put("undated_amount",0.0)
                    put("overdue_1_30",0.0);put("overdue_31_60",0.0)
                    put("overdue_61_90",0.0);put("overdue_over_90",0.0)
                    put("invoice_count",0)
                }
            }
            total.put("open_balance",total.getDouble("open_balance")+remaining)
            total.put("invoice_count",total.getInt("invoice_count")+1)
            val due=parseDate(row.optString("due_date"))
            if(due==null) {
                total.put("undated_amount",total.getDouble("undated_amount")+remaining)
            } else if(due.isBefore(asOf)) {
                val days=java.time.temporal.ChronoUnit.DAYS.between(due,asOf).toInt()
                total.put("overdue_amount",total.getDouble("overdue_amount")+remaining)
                val bucket=when {
                    days<=30 -> "overdue_1_30"
                    days<=60 -> "overdue_31_60"
                    days<=90 -> "overdue_61_90"
                    else -> "overdue_over_90"
                }
                total.put(bucket,total.getDouble(bucket)+remaining)
            } else if(due.isEqual(asOf)) {
                total.put("due_today_amount",total.getDouble("due_today_amount")+remaining)
            } else {
                total.put("not_due_amount",total.getDouble("not_due_amount")+remaining)
            }
        }
        val currencyRows=JSONArray()
        byCurrency.values.forEach { currencyRows.put(it) }
        return JSONObject().apply {
            put("supplier_id",supplierId);put("station_id",stationId);put("as_of_date",asOf.toString())
            put("currency_totals",currencyRows)
            put("legacy_payment_offsets_by_currency",legacyOffsets)
            put("unapplied_legacy_payments_without_currency",unallocatedWithoutCurrency)
            put("invoice_count",currencyRows.let { arr -> (0 until arr.length()).sumOf { arr.getJSONObject(it).getInt("invoice_count") } })
        }
    }

    /** Canonical station-level payable KPIs derived from invoice balances and posted allocations. */
    fun getSupplierPayablesSummary(db: SQLiteDatabase, stationId: Int, asOfDate: String): JSONObject {
        require(stationId > 0) { "معرف المحطة غير صالح" }
        val asOf = parseOptionalCanonicalDate(asOfDate, "تاريخ مستحقات الموردين")
            ?: throw IllegalArgumentException("تاريخ مستحقات الموردين مطلوب")
        var invoiced = 0.0; var allocated = 0.0; var outstanding = 0.0; var overdue = 0.0
        var openCount = 0; var overdueCount = 0
        db.rawQuery("""SELECT i.total_amount,
                    MAX(0,COALESCE((SELECT SUM(a.amount) FROM supplier_invoice_allocations a
                              WHERE a.station_id=i.station_id AND a.supplier_invoice_id=i.id
                                AND a.status='posted' AND a.is_deleted=0),0)
                              - COALESCE((SELECT SUM(r.amount) FROM supplier_allocation_reversals r
                                WHERE r.station_id=i.station_id AND r.supplier_invoice_id=i.id
                                  AND r.status='posted'),0)),
                    i.due_date, i.due_date_source, COALESCE(i.payment_terms_source_at_purchase,'')
             FROM supplier_invoices i JOIN parties p ON p.id=i.supplier_id AND p.station_id=i.station_id
              AND p.is_deleted=0 AND p.is_active=1
             WHERE i.station_id=? AND i.is_deleted=0 AND i.status IN ('open','partial','paid')""",
            arrayOf(stationId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val total = c.getDouble(0).coerceAtLeast(0.0)
                val paid = c.getDouble(1).coerceIn(0.0, total)
                val remaining = (total - paid).coerceAtLeast(0.0)
                invoiced += total; allocated += paid
                if (remaining <= EPSILON) continue
                outstanding += remaining; openCount++
                val trusted = c.getString(3).orEmpty() == "purchase_creation" && c.getString(4).orEmpty() == "explicit_text"
                if (trusted && parseDate(c.getString(2))?.isBefore(asOf) == true) { overdue += remaining; overdueCount++ }
            }
        }
        var unapplied = 0.0
        db.rawQuery("""SELECT
                COALESCE((SELECT SUM(amount) FROM supplier_invoice_allocations
                          WHERE station_id=? AND supplier_invoice_id IS NULL
                            AND allocation_kind='unapplied_legacy' AND status='posted' AND is_deleted=0),0)
                + COALESCE((SELECT SUM(r.amount)
                            FROM supplier_allocation_reversals r
                            JOIN supplier_invoice_allocations a
                              ON a.id=r.allocation_id AND a.station_id=r.station_id AND a.supplier_id=r.supplier_id
                            WHERE r.station_id=? AND r.status='posted'
                              AND a.status='posted' AND a.is_deleted=0),0)""",
            arrayOf(stationId.toString(),stationId.toString())
        ).use { c -> if (c.moveToFirst()) unapplied = c.getDouble(0).coerceAtLeast(0.0) }
        return JSONObject().apply {
            put("source", "SupplierPayablesRepository.supplier_invoices")
            put("station_id", stationId); put("as_of_date", asOf.toString())
            put("total_invoiced", invoiced); put("total_allocated", allocated)
            put("total_supplier_payables", outstanding); put("total_outstanding", outstanding)
            put("total_overdue", overdue); put("open_invoice_count", openCount)
            put("overdue_invoice_count", overdueCount); put("unapplied_payment_amount", unapplied)
        }
    }

    /** Read-only statement: invoices/payments affect the running balance; allocations are links only. */
    fun getSupplierStatement(
        db: SQLiteDatabase,
        stationId: Int,
        supplierId: Long,
        fromDate: String? = null,
        toDate: String? = null
    ): JSONObject {
        require(stationId > 0 && supplierId > 0) { "معرف المورد والمحطة مطلوبان" }
        requireSupplierScope(db, supplierId, stationId)
        val from = parseOptionalCanonicalDate(fromDate, "تاريخ بداية كشف المورد")
        val to = parseOptionalCanonicalDate(toDate, "تاريخ نهاية كشف المورد")
        require(from == null || to == null || !from.isAfter(to)) { "نطاق كشف المورد غير صالح" }
        data class Movement(val date: String, val type: String, val reference: String, val id: Long, val currency: Long, val increase: Double, val decrease: Double, val details: JSONObject)
        val movements = mutableListOf<Movement>()
        val opening = linkedMapOf<Long, Double>()
        val invoices = getSupplierInvoices(db, stationId, supplierId)
        for (i in 0 until invoices.length()) {
            val row = invoices.getJSONObject(i)
            val date = row.optString("invoice_date").take(10)
            val amount = row.optDouble("total_amount", 0.0)
            val currency = row.optLong("currency_id", 0L)
            if (from != null && parseDate(date)?.isBefore(from) == true) opening[currency] = (opening[currency] ?: 0.0) + amount
            if ((from == null || parseDate(date)?.let { !it.isBefore(from) } == true) &&
                (to == null || parseDate(date)?.let { !it.isAfter(to) } == true)) {
                movements += Movement(date, "supplier_invoice", row.optString("invoice_number"), row.optLong("id"), currency, amount, 0.0, row)
            }
        }
        val payments = getSupplierPayments(db, stationId, supplierId)
        for (i in 0 until payments.length()) {
            val row = payments.getJSONObject(i)
            val date = row.optString("date").take(10)
            val parsed = parseDate(date)
            val currency = row.optLong("currency_id", 0L)
            val signedIncrease = if (row.optString("status") == "refunded") row.optDouble("amount", 0.0) else 0.0
            val decrease = if (row.optString("status") == "refunded") 0.0 else row.optDouble("amount", 0.0)
            val net = signedIncrease - decrease
            if (from != null && parsed?.isBefore(from) == true) opening[currency] = (opening[currency] ?: 0.0) + net
            if ((from == null || parsed?.let { !it.isBefore(from) } == true) &&
                (to == null || parsed?.let { !it.isAfter(to) } == true)) {
                val links = JSONArray()
                db.rawQuery("SELECT supplier_invoice_id,amount,status,COALESCE(unapplied_reason,'') FROM supplier_invoice_allocations WHERE payment_id=? AND station_id=? ORDER BY id", arrayOf(row.optLong("id").toString(), stationId.toString())).use { c ->
                    while (c.moveToNext()) links.put(JSONObject().apply {
                        put("supplier_invoice_id", if (c.isNull(0)) JSONObject.NULL else c.getLong(0)); put("amount", c.getDouble(1)); put("status", c.getString(2)); put("unapplied_reason", c.getString(3).orEmpty())
                    })
                }
                val details = JSONObject(row.toString()).put("allocation_links", links)
                movements += Movement(date, if (row.optString("status") == "refunded") "supplier_payment_reversal" else "supplier_payment", row.optString("payment_code"), row.optLong("id"), currency, signedIncrease, decrease, details)
            }
        }
        movements.sortWith(compareBy<Movement>({ it.date }, { it.type }, { it.reference }, { it.id }))
        val running = opening.toMutableMap()
        val out = JSONArray()
        movements.forEach { m ->
            running[m.currency] = (running[m.currency] ?: 0.0) + m.increase - m.decrease
            out.put(JSONObject().apply {
                put("date", m.date); put("reference", m.reference); put("movement_type", m.type); put("movement_id", m.id)
                put("currency_id", if (m.currency > 0) m.currency else JSONObject.NULL); put("increase", m.increase); put("decrease", m.decrease)
                put("running_balance", running[m.currency] ?: 0.0); put("details", m.details)
            })
        }
        return JSONObject().apply {
            put("station_id", stationId); put("supplier_id", supplierId); put("from_date", from?.toString() ?: ""); put("to_date", to?.toString() ?: "")
            put("opening_balances", JSONObject().apply { opening.forEach { (k, v) -> put(if (k > 0) k.toString() else "unknown", v) } })
            put("movements", out); put("allocations_are_links", true)
        }
    }

    /** Read-only reconciliation workpaper. Scalar control ledgers cannot be compared safely
     * with invoice balances until their currency basis and historical FX snapshots are provable.
     */
    fun reconcileSupplier(db: SQLiteDatabase, stationId: Int, supplierId: Long, asOfDate: String? = null): JSONObject {
        requireSupplierScope(db, supplierId, stationId)
        val aging = getSupplierAging(db, stationId, supplierId, asOfDate)
        val currencyTotals = aging.getJSONArray("currency_totals")
        val legacyOffsets = aging.getJSONArray("legacy_payment_offsets_by_currency")

        val ledger = db.rawQuery(
            "SELECT COALESCE(SUM(credit-debit),0) FROM supplier_ledger WHERE party_id=?",
            arrayOf(supplierId.toString())
        ).use { if (it.moveToFirst()) it.getDouble(0) else 0.0 }
        val party = db.rawQuery(
            "SELECT current_balance,total_due,total_payments FROM parties WHERE id=? AND station_id=? AND is_deleted=0",
            arrayOf(supplierId.toString(), stationId.toString())
        ).use { c ->
            require(c.moveToFirst()) { "المورد غير موجود" }
            JSONObject().apply {
                put("current_balance", c.getDouble(0))
                put("total_due", c.getDouble(1))
                put("total_payments", c.getDouble(2))
            }
        }
        val gl = db.rawQuery(
            """SELECT COALESCE(SUM(ji.credit-ji.debit),0)
               FROM journal_entry_items ji
               JOIN journal_entries je ON je.id=ji.journal_entry_id
               WHERE ji.supplier_party_id=? AND je.station_id=? AND je.status='posted'
                 AND COALESCE(je.is_deleted,0)=0""",
            arrayOf(supplierId.toString(), stationId.toString())
        ).use { if (it.moveToFirst()) it.getDouble(0) else 0.0 }

        val exceptions = JSONArray()
        fun exception(code: String, amount: Double?, message: String) {
            exceptions.put(JSONObject().apply {
                put("code", code)
                if (amount == null) put("amount", JSONObject.NULL) else put("amount", amount)
                put("message", message)
                put("supplier_id", supplierId)
                put("station_id", stationId)
            })
        }

        // supplier_ledger and party aggregate balances do not carry a provable currency
        // dimension. Journal lines also require a consistent functional-currency basis and
        // historical FX policy before they can be compared to the invoice subledger.
        exception(
            "currency_basis_unverifiable",
            null,
            "تعذرت المطابقة الرقمية: دفتر المورد وأرصدة الطرف لا يثبتان أساس العملة، ولا يجوز جمع العملات أو مقارنتها دون مبالغ وظيفية وأسعار صرف موثقة"
        )

        db.rawQuery(
            """SELECT COUNT(*) FROM supplier_invoice_allocations a
               LEFT JOIN supplier_invoices i ON i.id=a.supplier_invoice_id
               WHERE a.station_id=? AND a.supplier_id=? AND a.supplier_invoice_id IS NOT NULL
                 AND (i.id IS NULL OR i.station_id<>a.station_id OR i.supplier_id<>a.supplier_id)""",
            arrayOf(stationId.toString(), supplierId.toString())
        ).use {
            if (it.moveToFirst() && it.getInt(0) > 0) {
                exception("orphan_allocation", it.getInt(0).toDouble(), "تخصيصات تشير إلى فواتير غير صالحة")
            }
        }

        return JSONObject().apply {
            put("station_id", stationId)
            put("supplier_id", supplierId)
            put("as_of_date", asOfDate ?: BusinessDay.currentDate())
            put("open_invoice_balances_by_currency", currencyTotals)
            put("unapplied_supplier_credits_by_currency", legacyOffsets)
            // Deprecated scalar fields remain present but NULL: cross-currency sums are invalid.
            put("open_invoice_balance", JSONObject.NULL)
            put("unapplied_supplier_credits", JSONObject.NULL)
            put("expected_control_balance", JSONObject.NULL)
            put("supplier_ledger_balance", ledger)
            put("party_balance", party)
            put("gl_control_balance", gl)
            put("control_balances_comparable", false)
            put("comparison_block_reason", "currency_basis_unverifiable")
            put("exceptions", exceptions)
            put("status", if (exceptions.length() > 1) "reconciliation_required" else "reconciliation_unverifiable")
            put("repairs_performed", false)
        }
    }

    private fun requireSupplierScope(db: SQLiteDatabase, supplierId: Long, stationId: Int) {
        db.rawQuery(
            """SELECT 1 FROM parties p JOIN party_types pt ON pt.id=p.party_type_id
               WHERE p.id=? AND p.station_id=? AND p.is_deleted=0 AND p.is_active=1
                 AND pt.type_code='SUPPLIER' AND pt.is_deleted=0 LIMIT 1""",
            arrayOf(supplierId.toString(),stationId.toString())
        ).use { c -> require(c.moveToFirst()) { "المورد غير موجود أو غير نشط ضمن المحطة" } }
    }

    private fun columns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info(`$table`)",null).use { c ->
            val names=linkedSetOf<String>()
            while(c.moveToNext()) names.add(c.getString(c.getColumnIndexOrThrow("name")))
            names
        }

    private fun parseOptionalCanonicalDate(value: String?, label: String): LocalDate? {
        val raw = value.orEmpty().trim()
        if (raw.isEmpty()) return null
        require(raw.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "$label يجب أن يكون بصيغة YYYY-MM-DD" }
        return try {
            LocalDate.parse(raw).also { require(it.toString() == raw) { "$label غير صالح" } }
        } catch (error: DateTimeParseException) {
            throw IllegalArgumentException("$label غير صالح؛ استخدم تاريخاً تقويمياً صحيحاً", error)
        }
    }

    private fun parseDate(value: String?): LocalDate? {
        val raw=value.orEmpty().trim()
        if(raw.length<10) return null
        return try { LocalDate.parse(raw.substring(0,10)) }
        catch(_: DateTimeParseException) { null }
    }
}
