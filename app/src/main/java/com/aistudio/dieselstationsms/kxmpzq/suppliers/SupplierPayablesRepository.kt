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
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_supplier_invoice_aging ON supplier_invoices(station_id,supplier_id,due_date,status,is_deleted)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_supplier_invoice_date ON supplier_invoices(station_id,supplier_id,invoice_date,is_deleted)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_supplier_invoice_alloc_invoice ON supplier_invoice_allocations(station_id,supplier_invoice_id,status,is_deleted)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_supplier_invoice_alloc_payment ON supplier_invoice_allocations(station_id,payment_id,status,is_deleted)")
        db.execSQL("""
            CREATE UNIQUE INDEX IF NOT EXISTS idx_supplier_invoice_payment_once
            ON supplier_invoice_allocations(payment_id,supplier_invoice_id)
            WHERE supplier_invoice_id IS NOT NULL AND status='posted' AND is_deleted=0
        """.trimIndent())
        db.execSQL("""
            CREATE UNIQUE INDEX IF NOT EXISTS idx_supplier_legacy_unapplied_once
            ON supplier_invoice_allocations(payment_id)
            WHERE supplier_invoice_id IS NULL AND status='posted' AND is_deleted=0
        """.trimIndent())
    }

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
        ensureSchema(db)
        require(stationId > 0 && supplierId > 0L && sourceId > 0L && actorId > 0L) {
            "بيانات فاتورة المورد غير صالحة"
        }
        require(sourceType in setOf("fuel_refill","product_purchase","manual")) {
            "نوع مصدر فاتورة المورد غير صالح"
        }
        require(amount.isFinite() && amount > EPSILON) { "مبلغ فاتورة المورد غير صالح" }

        val existing = db.rawQuery(
            """SELECT id,supplier_id,total_amount FROM supplier_invoices
               WHERE station_id=? AND source_type=? AND source_id=? AND is_deleted=0 LIMIT 1""",
            arrayOf(stationId.toString(),sourceType,sourceId.toString())
        ).use { c ->
            if (c.moveToFirst()) longArrayOf(c.getLong(0),c.getLong(1),c.getDouble(2).toBits()) else null
        }
        if (existing != null) {
            require(existing[1] == supplierId) { "مصدر الشراء مرتبط بمورد مختلف" }
            require(abs(Double.fromBits(existing[2]) - amount) <= 0.01) {
                "مبلغ فاتورة المورد المصدر يختلف عن المبلغ المسجل مسبقاً"
            }
            return existing[0]
        }

        val partyColumns = columns(db,"parties")
        val termsExpr = if ("payment_terms" in partyColumns) "COALESCE(payment_terms,'')" else "''"
        val daysExpr = if ("payment_terms_days" in partyColumns) "payment_terms_days" else "NULL"
        val termIdExpr = if ("payment_terms_id" in partyColumns) "payment_terms_id" else "NULL"
        val termSourceExpr = if ("payment_terms_source" in partyColumns) "payment_terms_source" else "NULL"
        val partyStation = if ("station_id" in partyColumns) " AND station_id=?" else ""
        val args = if ("station_id" in partyColumns) arrayOf(supplierId.toString(),stationId.toString()) else arrayOf(supplierId.toString())
        val terms = db.rawQuery(
            "SELECT $termsExpr,$daysExpr,$termIdExpr,$termSourceExpr FROM parties WHERE id=?$partyStation AND is_deleted=0 AND is_active=1 LIMIT 1",
            args
        ).use { c ->
            require(c.moveToFirst()) { "المورد غير موجود أو غير نشط ضمن المحطة" }
            listOf(c.getString(0).orEmpty(),if(c.isNull(1)) null else c.getInt(1),
                if(c.isNull(2)) null else c.getLong(2),c.getString(3).orEmpty())
        }
        val termsText=terms[0] as String
        val termsSource=terms[3] as String
        val days=if(termsSource=="explicit_text") (terms[1] as? Int)?.takeIf { it in 0..365 } ?: DebtDueDateRepository.parseTermsDays(termsText) else null
        val base = parseDate(invoiceDate)
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
        return id
    }

    /**
     * Allocate the supplier payment FIFO by earliest maturity date, then invoice date.
     * A payment against an old control balance that has no invoice-level record is retained as
     * an explicit unapplied legacy allocation; it is never fabricated into an invoice payment.
     */
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
        ensureSchema(db)
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
        require(abs(payment.getDouble("amount")-amount)<=0.01) { "مبلغ التخصيص لا يطابق مبلغ الدفعة" }

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
            require(abs(existing.amount-amount)<=0.01) {
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
               ORDER BY CASE WHEN due_date IS NULL OR TRIM(due_date)='' THEN 1 ELSE 0 END,
                        date(due_date),date(invoice_date),id""",
            currencyArgs.toTypedArray()
        ).use { c -> while(c.moveToNext()) candidates += Candidate(c.getLong(0),c.getDouble(1).coerceAtLeast(0.0)) }

        if(explicitInvoiceId>0L) {
            require(candidates.size==1) { "الفاتورة المحددة غير موجودة أو مسددة أو بعملة مختلفة" }
            require(amount<=candidates.first().remaining+0.01) {
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
                """SELECT COALESCE(SUM(a.amount),0) FROM supplier_invoice_allocations a
                   WHERE a.supplier_invoice_id=? AND a.status='posted' AND a.is_deleted=0""",
                arrayOf(invoice.id.toString())
            ).use { c -> c.moveToFirst();c.getDouble(0) }
            val total=db.rawQuery(
                "SELECT total_amount FROM supplier_invoices WHERE id=? AND station_id=? AND supplier_id=? AND is_deleted=0",
                arrayOf(invoice.id.toString(),stationId.toString(),supplierId.toString())
            ).use { c -> require(c.moveToFirst()) { "فاتورة المورد غير موجودة أثناء التخصيص" };c.getDouble(0) }
            require(updated<=total+0.01) { "إجمالي تخصيصات المورد يتجاوز قيمة الفاتورة" }
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
            db.insertOrThrow("supplier_invoice_allocations",null,ContentValues().apply {
                put("uuid",UUID.randomUUID().toString());put("station_id",stationId);put("supplier_id",supplierId)
                putNull("supplier_invoice_id");put("payment_id",paymentId)
                put("allocation_kind","unapplied_legacy");put("amount",remainingPayment)
                if(paymentCurrencyId>0L) put("currency_id",paymentCurrencyId) else putNull("currency_id")
                put("notes","دفعة غير موزعة على فاتورة مسجلة؛ تخصم من رصيد المورد التاريخي")
                put("status","posted");put("allocated_at",BusinessDay.currentDate());put("created_by",actorId)
            })
        }
        return JSONObject().apply {
            put("success",true);put("idempotent",false)
            put("allocated_amount",allocated);put("unapplied_amount",remainingPayment)
            put("invoice_allocation_count",allocations)
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
        ensureSchema(db)
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
            require(paid <= total + 0.01) { "تخصيصات الفاتورة المتبقية تتجاوز قيمتها الأصلية" }
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
        toDate: String? = null
    ): JSONArray {
        ensureSchema(db)
        require(stationId>0) { "معرف المحطة غير صالح" }
        if(supplierId>0L) requireSupplierScope(db,supplierId,stationId)
        val where=mutableListOf("i.station_id=?","i.is_deleted=0")
        val args=mutableListOf(stationId.toString())
        if(supplierId>0L){where+="i.supplier_id=?";args+=supplierId.toString()}
        if(!fromDate.isNullOrBlank()){where+="date(i.invoice_date)>=date(?)";args+=fromDate.take(10)}
        if(!toDate.isNullOrBlank()){where+="date(i.invoice_date)<=date(?)";args+=toDate.take(10)}
        val rows=JSONArray()
        db.rawQuery(
            """SELECT i.id,i.supplier_id,i.source_type,i.source_id,i.invoice_number,i.invoice_date,i.due_date,
                      i.due_date_source,i.payment_terms_at_purchase,i.payment_terms_days_at_purchase,
                      i.currency_id,i.total_amount,i.paid_amount,i.remaining_amount,i.status,
                      COALESCE(i.payment_terms_source_at_purchase,''),
                      COALESCE(p.commercial_name_ar,p.commercial_name,'') AS supplier_name,
                      COALESCE((SELECT SUM(a.amount) FROM supplier_invoice_allocations a
                        WHERE a.supplier_invoice_id=i.id AND a.status='posted' AND a.is_deleted=0),0) AS allocated_amount
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
                val today=LocalDate.parse(BusinessDay.currentDate())
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
        ensureSchema(db)
        require(stationId>0) { "معرف المحطة غير صالح" }
        val where=mutableListOf("p.station_id=?","p.supplier_party_id IS NOT NULL","p.is_deleted=0","p.status IN ('completed','refunded')")
        val args=mutableListOf(stationId.toString())
        if(supplierId>0L){requireSupplierScope(db,supplierId,stationId);where+="p.supplier_party_id=?";args+=supplierId.toString()}
        if(!fromDate.isNullOrBlank()){where+="date(p.created_at)>=date(?)";args+=fromDate.take(10)}
        if(!toDate.isNullOrBlank()){where+="date(p.created_at)<=date(?)";args+=toDate.take(10)}
        val rows=JSONArray()
        db.rawQuery(
            """SELECT p.id,p.supplier_party_id,p.payment_code,p.amount,p.payment_method,p.status,p.created_at,
                      p.currency_id,COALESCE(pt.commercial_name_ar,pt.commercial_name,''),
                      COALESCE((SELECT SUM(a.amount) FROM supplier_invoice_allocations a
                        WHERE a.payment_id=p.id AND a.station_id=p.station_id
                          AND a.status='posted' AND a.is_deleted=0),0) AS allocated_amount,
                      COALESCE((SELECT SUM(a.amount) FROM supplier_invoice_allocations a
                        WHERE a.payment_id=p.id AND a.station_id=p.station_id
                          AND a.status='reversed' AND a.is_deleted=0),0) AS reversed_amount
               FROM payments p
               LEFT JOIN parties pt ON pt.id=p.supplier_party_id AND pt.station_id=p.station_id
               WHERE ${where.joinToString(" AND ")}
               ORDER BY datetime(p.created_at) DESC,p.id DESC""",
            args.toTypedArray()
        ).use { c ->
            while(c.moveToNext()) rows.put(JSONObject().apply {
                put("id",c.getLong(0));put("supplier_id",c.getLong(1));put("payment_code",c.getString(2))
                put("amount",c.getDouble(3));put("payment_method",c.getString(4));put("status",c.getString(5))
                put("date",c.getString(6).orEmpty());put("currency_id",if(c.isNull(7)) JSONObject.NULL else c.getLong(7))
                put("supplier_name",c.getString(8).orEmpty());put("allocated_amount",c.getDouble(9))
                put("reversed_amount",c.getDouble(10))
            })
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
        val asOf=parseDate(asOfDate) ?: LocalDate.parse(BusinessDay.currentDate())
        val invoices=getSupplierInvoices(db,stationId,supplierId)
        val byCurrency=linkedMapOf<Long,JSONObject>()
        val legacyOffsets = JSONArray()
        var unallocatedWithoutCurrency = 0.0
        db.rawQuery(
            """SELECT COALESCE(currency_id,0),COALESCE(SUM(amount),0),COUNT(*)
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
                val amount = cursor.getDouble(1)
                if (currencyId <= 0L) unallocatedWithoutCurrency += amount
                legacyOffsets.put(JSONObject().apply {
                    put("currency_id", if(currencyId > 0L) currencyId else JSONObject.NULL)
                    put("amount", amount)
                    put("allocation_count", cursor.getInt(2))
                    put("classification", "legacy_balance_offset_not_invoice_allocated")
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

    private fun parseDate(value: String?): LocalDate? {
        val raw=value.orEmpty().trim()
        if(raw.length<10) return null
        return try { LocalDate.parse(raw.substring(0,10)) }
        catch(_: DateTimeParseException) { null }
    }
}
