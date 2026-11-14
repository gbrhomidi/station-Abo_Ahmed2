package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.aistudio.dieselstationsms.kxmpzq.BusinessDay
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

/** Invoice-level customer payment allocation and partial reversal ledger. */
object CustomerReceivablesRepository {
    private const val EPSILON = 0.000001
    data class PaymentAllocation(val salesTransactionId: Long, val amount: Double)

    fun ensureSchema(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS customer_payment_allocations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL UNIQUE,
                station_id INTEGER NOT NULL,
                customer_party_id INTEGER NOT NULL,
                sales_transaction_id INTEGER NOT NULL,
                payment_id INTEGER NOT NULL,
                amount REAL NOT NULL CHECK(amount > 0),
                status TEXT NOT NULL DEFAULT 'posted' CHECK(status IN ('posted','reversed')),
                allocated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER,
                is_deleted INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (customer_party_id) REFERENCES parties(id),
                FOREIGN KEY (sales_transaction_id) REFERENCES sales_transactions(id),
                FOREIGN KEY (payment_id) REFERENCES payments(id)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS customer_payment_allocation_reversals (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL UNIQUE,
                station_id INTEGER NOT NULL,
                customer_party_id INTEGER NOT NULL,
                allocation_id INTEGER NOT NULL,
                payment_id INTEGER NOT NULL,
                sales_transaction_id INTEGER NOT NULL,
                amount REAL NOT NULL CHECK(amount > 0),
                idempotency_key TEXT NOT NULL,
                reason TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'posted' CHECK(status IN ('posted','reversed')),
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                created_by INTEGER NOT NULL,
                FOREIGN KEY (allocation_id) REFERENCES customer_payment_allocations(id),
                FOREIGN KEY (payment_id) REFERENCES payments(id),
                FOREIGN KEY (sales_transaction_id) REFERENCES sales_transactions(id),
                FOREIGN KEY (customer_party_id) REFERENCES parties(id),
                UNIQUE(station_id,idempotency_key)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_customer_payment_alloc_invoice ON customer_payment_allocations(station_id,sales_transaction_id,status,is_deleted)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_customer_payment_alloc_payment ON customer_payment_allocations(station_id,payment_id,status,is_deleted)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_customer_payment_alloc_reversals ON customer_payment_allocation_reversals(station_id,allocation_id,status)")
        db.execSQL("""
            CREATE UNIQUE INDEX IF NOT EXISTS idx_customer_payment_alloc_once
            ON customer_payment_allocations(payment_id,sales_transaction_id)
            WHERE status='posted' AND is_deleted=0
        """.trimIndent())
        db.execSQL("DROP TRIGGER IF EXISTS trg_customer_payment_alloc_insert_guard")
        db.execSQL("""
            CREATE TRIGGER trg_customer_payment_alloc_insert_guard
            BEFORE INSERT ON customer_payment_allocations
            WHEN NEW.status='posted' AND NEW.is_deleted=0
            BEGIN
                SELECT RAISE(ABORT,'customer_payment_allocation_scope_mismatch')
                WHERE NOT EXISTS (
                    SELECT 1 FROM payments p
                    JOIN parties c ON c.id=p.customer_party_id AND c.station_id=p.station_id AND c.is_deleted=0
                    WHERE p.id=NEW.payment_id AND p.station_id=NEW.station_id
                      AND p.customer_party_id=NEW.customer_party_id
                      AND p.status='completed' AND COALESCE(p.is_deleted,0)=0
                );
                SELECT RAISE(ABORT,'customer_payment_allocation_invoice_mismatch')
                WHERE NOT EXISTS (
                    SELECT 1 FROM sales_transactions s
                    WHERE s.id=NEW.sales_transaction_id AND s.station_id=NEW.station_id
                      AND s.customer_party_id=NEW.customer_party_id AND s.is_deleted=0
                      AND s.status='completed'
                      AND LOWER(REPLACE(REPLACE(TRIM(COALESCE(s.payment_method,'')),' ',''),'-','_'))
                          IN ('credit','credit_sale','credit_account','on_account','آجل','اجل','بيعآجل','علىالحساب')
                      AND LOWER(COALESCE(s.payment_status,'')) NOT IN ('cancelled','refunded')
                );
                SELECT RAISE(ABORT,'customer_payment_overallocation')
                WHERE (
                    SELECT COALESCE(SUM(a.amount),0) -
                           COALESCE((SELECT SUM(r.amount) FROM customer_payment_allocation_reversals r
                                     WHERE r.payment_id=NEW.payment_id AND r.status='posted'),0)
                    FROM customer_payment_allocations a
                    WHERE a.payment_id=NEW.payment_id AND a.status='posted' AND a.is_deleted=0
                ) + NEW.amount >
                (SELECT amount FROM payments WHERE id=NEW.payment_id) + 0.000001;
                SELECT RAISE(ABORT,'customer_invoice_overallocation')
                WHERE (
                    SELECT COALESCE(SUM(a.amount),0) -
                           COALESCE((SELECT SUM(r.amount) FROM customer_payment_allocation_reversals r
                                     WHERE r.sales_transaction_id=NEW.sales_transaction_id AND r.status='posted'),0)
                    FROM customer_payment_allocations a
                    WHERE a.sales_transaction_id=NEW.sales_transaction_id AND a.station_id=NEW.station_id
                      AND a.status='posted' AND a.is_deleted=0
                ) + NEW.amount >
                (SELECT net_amount FROM sales_transactions WHERE id=NEW.sales_transaction_id) + 0.000001;
            END
        """.trimIndent())
        db.execSQL("DROP TRIGGER IF EXISTS trg_customer_payment_alloc_reversal_guard")
        db.execSQL("""
            CREATE TRIGGER trg_customer_payment_alloc_reversal_guard
            BEFORE INSERT ON customer_payment_allocation_reversals
            WHEN NEW.status='posted'
            BEGIN
                SELECT RAISE(ABORT,'customer_payment_allocation_reversal_scope_mismatch')
                WHERE NOT EXISTS (
                    SELECT 1 FROM customer_payment_allocations a
                    JOIN payments p ON p.id=a.payment_id
                    JOIN sales_transactions s ON s.id=a.sales_transaction_id
                    WHERE a.id=NEW.allocation_id AND a.station_id=NEW.station_id
                      AND a.customer_party_id=NEW.customer_party_id
                      AND a.payment_id=NEW.payment_id
                      AND a.sales_transaction_id=NEW.sales_transaction_id
                      AND a.status='posted' AND a.is_deleted=0
                      AND p.station_id=NEW.station_id AND p.customer_party_id=NEW.customer_party_id
                      AND p.status='completed' AND p.is_deleted=0
                      AND s.station_id=NEW.station_id AND s.customer_party_id=NEW.customer_party_id
                      AND s.is_deleted=0
                );
                SELECT RAISE(ABORT,'customer_payment_allocation_reversal_exceeds_available')
                WHERE COALESCE((
                    SELECT SUM(a.amount) -
                           COALESCE((SELECT SUM(r.amount) FROM customer_payment_allocation_reversals r
                                     WHERE r.allocation_id=NEW.allocation_id AND r.status='posted'),0)
                    FROM customer_payment_allocations a
                    WHERE a.id=NEW.allocation_id AND a.status='posted' AND a.is_deleted=0
                ),0) + 0.000001 < NEW.amount;
            END
        """.trimIndent())
    }

    /** Must run inside the payment's existing SQLite transaction. */
    fun recordPaymentAllocations(
        db: SQLiteDatabase,
        stationId: Int,
        customerId: Int,
        paymentId: Long,
        actorId: Long,
        allocations: List<PaymentAllocation>
    ): Int {
        require(db.inTransaction()) { "تسجيل تخصيصات دفعة العميل يتطلب معاملة SQLite نشطة" }
        require(stationId > 0 && customerId > 0 && paymentId > 0L && actorId > 0L) {
            "بيانات تخصيص دفعة العميل غير صالحة"
        }
        require(allocations.isNotEmpty()) { "لا توجد فواتير لتسجيل تخصيص الدفعة" }
        require(allocations.all { it.salesTransactionId > 0L && it.amount.isFinite() && it.amount > EPSILON }) {
            "تحتوي تخصيصات الدفعة على فاتورة أو مبلغ غير صالح"
        }
        require(allocations.map { it.salesTransactionId }.distinct().size == allocations.size) {
            "لا يجوز تكرار الفاتورة ضمن تخصيصات الدفعة الواحدة"
        }
        allocations.forEach { allocation ->
            db.insertOrThrow("customer_payment_allocations", null, ContentValues().apply {
                put("uuid", UUID.randomUUID().toString())
                put("station_id", stationId)
                put("customer_party_id", customerId)
                put("sales_transaction_id", allocation.salesTransactionId)
                put("payment_id", paymentId)
                put("amount", allocation.amount)
                put("status", "posted")
                put("created_by", actorId)
            })
        }
        return allocations.size
    }

    /** Reverse an invoice allocation only; cash/bank and its original GL journal remain posted. */
    fun reversePaymentAllocation(
        db: SQLiteDatabase,
        stationId: Int,
        customerId: Int,
        allocationId: Long,
        amount: Double,
        actorId: Long,
        reason: String,
        idempotencyKey: String
    ): JSONObject {
        require(stationId > 0 && customerId > 0 && allocationId > 0L && actorId > 0L) {
            "بيانات عكس تخصيص العميل غير صالحة"
        }
        require(amount.isFinite() && amount > EPSILON) { "مبلغ العكس الجزئي غير صالح" }
        val key = idempotencyKey.trim()
        require(key.isNotBlank()) { "مفتاح عكس التخصيص مطلوب لمنع التكرار" }
        if (db.inTransaction()) {
            // A caller may catch a failed reversal and still commit its outer transaction.
            // Isolate this multi-table operation so a late ledger failure cannot leave the
            // allocation reversal or balance updates partially persisted.
            val savepoint = "customer_receivables_allocation_reverse"
            db.execSQL("SAVEPOINT $savepoint")
            try {
                val result = reverseWithinTransaction(
                    db, stationId, customerId, allocationId, amount, actorId, reason, key
                )
                db.execSQL("RELEASE SAVEPOINT $savepoint")
                return result
            } catch (error: Throwable) {
                try {
                    db.execSQL("ROLLBACK TO SAVEPOINT $savepoint")
                } finally {
                    db.execSQL("RELEASE SAVEPOINT $savepoint")
                }
                throw error
            }
        }
        db.beginTransaction()
        try {
            val result = reverseWithinTransaction(db, stationId, customerId, allocationId, amount, actorId, reason, key)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun reverseWithinTransaction(
        db: SQLiteDatabase,
        stationId: Int,
        customerId: Int,
        allocationId: Long,
        amount: Double,
        actorId: Long,
        reason: String,
        key: String
    ): JSONObject {
        db.rawQuery(
            "SELECT allocation_id,customer_party_id,amount FROM customer_payment_allocation_reversals WHERE station_id=? AND idempotency_key=? AND status='posted' LIMIT 1",
            arrayOf(stationId.toString(), key)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                require(cursor.getLong(0) == allocationId && cursor.getInt(1) == customerId &&
                    abs(cursor.getDouble(2) - amount) <= EPSILON) {
                    "مفتاح عكس التخصيص مستخدم لعملية أو مبلغ مختلف"
                }
                return JSONObject().apply {
                    put("success", true); put("idempotent", true)
                    put("reversed_amount", cursor.getDouble(2)); put("allocation_id", allocationId)
                }
            }
        }
        data class Original(val paymentId: Long, val invoiceId: Long, val originalAmount: Double)
        val original = db.rawQuery(
            """SELECT payment_id,sales_transaction_id,amount
               FROM customer_payment_allocations
               WHERE id=? AND station_id=? AND customer_party_id=? AND status='posted' AND is_deleted=0""",
            arrayOf(allocationId.toString(), stationId.toString(), customerId.toString())
        ).use { cursor ->
            require(cursor.moveToFirst()) { "تخصيص الدفعة غير موجود أو غير نشط" }
            Original(cursor.getLong(0), cursor.getLong(1), cursor.getDouble(2))
        }
        val alreadyReversed = db.rawQuery(
            "SELECT COALESCE(SUM(amount),0) FROM customer_payment_allocation_reversals WHERE station_id=? AND allocation_id=? AND status='posted'",
            arrayOf(stationId.toString(), allocationId.toString())
        ).use { cursor -> cursor.moveToFirst(); cursor.getDouble(0) }
        val available = (original.originalAmount - alreadyReversed).coerceAtLeast(0.0)
        require(available > EPSILON) { "لا يوجد رصيد تخصيص متبقٍ للعكس" }
        require(amount <= available + EPSILON) { "مبلغ العكس يتجاوز المتبقي من تخصيص الفاتورة" }
        val paymentValid = db.rawQuery(
            """SELECT 1 FROM payments WHERE id=? AND station_id=? AND customer_party_id=?
               AND status='completed' AND is_deleted=0 LIMIT 1""",
            arrayOf(original.paymentId.toString(), stationId.toString(), customerId.toString())
        ).use { it.moveToFirst() }
        require(paymentValid) { "دفعة العميل غير مكتملة أو خارج المحطة المحددة" }
        val invoice = db.rawQuery(
            """SELECT COALESCE(net_amount,0),COALESCE(paid_amount,0),COALESCE(remaining_amount,0)
               FROM sales_transactions
               WHERE id=? AND station_id=? AND customer_party_id=? AND is_deleted=0 AND status='completed'""",
            arrayOf(original.invoiceId.toString(), stationId.toString(), customerId.toString())
        ).use { cursor ->
            require(cursor.moveToFirst()) { "فاتورة العميل غير موجودة أو لم تعد قابلة للتسوية" }
            doubleArrayOf(cursor.getDouble(0), cursor.getDouble(1), cursor.getDouble(2))
        }
        require(invoice[1] + EPSILON >= amount) { "مبلغ العكس يتجاوز المدفوع المسجل على الفاتورة" }
        require(invoice[2] + amount <= invoice[0] + EPSILON) { "عكس التخصيص سيؤدي إلى تجاوز إجمالي الفاتورة" }
        val reversalId = db.insertOrThrow("customer_payment_allocation_reversals", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("station_id", stationId)
            put("customer_party_id", customerId)
            put("allocation_id", allocationId)
            put("payment_id", original.paymentId)
            put("sales_transaction_id", original.invoiceId)
            put("amount", amount)
            put("idempotency_key", key)
            put("reason", reason.trim().ifBlank { "عكس تخصيص دفعة عميل" })
            put("status", "posted")
            put("created_by", actorId)
        })
        val newPaid = (invoice[1] - amount).coerceAtLeast(0.0)
        val newRemaining = (invoice[2] + amount).coerceAtLeast(0.0)
        val paymentStatus = when {
            newRemaining <= EPSILON -> "paid"
            newPaid > EPSILON -> "partial"
            else -> "pending"
        }
        require(db.update(
            "sales_transactions",
            ContentValues().apply {
                put("paid_amount", newPaid)
                put("remaining_amount", newRemaining)
                put("payment_status", paymentStatus)
                put("updated_at", BusinessDay.currentDate() + " 00:00:00")
            },
            "id=? AND station_id=? AND customer_party_id=? AND is_deleted=0",
            arrayOf(original.invoiceId.toString(), stationId.toString(), customerId.toString())
        ) == 1) { "تعذر إعادة فتح رصيد فاتورة العميل" }
        val partyUpdated = db.compileStatement(
            "UPDATE parties SET current_balance=COALESCE(current_balance,0)+?, total_due=COALESCE(total_due,0)+?, updated_at=? WHERE id=? AND station_id=? AND is_deleted=0"
        ).apply {
            bindDouble(1, amount)
            bindDouble(2, amount)
            bindString(3, BusinessDay.currentDate() + " 00:00:00")
            bindLong(4, customerId.toLong())
            bindLong(5, stationId.toLong())
        }.executeUpdateDelete()
        require(partyUpdated == 1) { "تعذر تحديث رصيد العميل بعد عكس التخصيص" }
        val balance = db.rawQuery(
            "SELECT COALESCE(current_balance,0) FROM parties WHERE id=? AND station_id=? AND is_deleted=0",
            arrayOf(customerId.toString(), stationId.toString())
        ).use { cursor -> require(cursor.moveToFirst()); cursor.getDouble(0) }
        db.insertOrThrow("customer_ledger", null, ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("party_id", customerId)
            put("transaction_date", BusinessDay.currentDate() + " 00:00:00")
            put("transaction_type", "payment_allocation_reversal")
            put("transaction_id", reversalId)
            put("reference_number", "CPAR-$reversalId")
            put("debit", amount)
            put("credit", 0.0)
            put("balance", balance)
            put("description", "عكس تخصيص جزئي للدفعة ${original.paymentId} عن فاتورة ${original.invoiceId}: ${reason.trim()}")
            put("created_by", actorId)
        })
        return JSONObject().apply {
            put("success", true); put("idempotent", false)
            put("reversed_amount", amount); put("allocation_id", allocationId)
            put("payment_id", original.paymentId); put("sales_transaction_id", original.invoiceId)
            put("remaining_allocation_amount", available - amount)
            put("invoice_paid_amount", newPaid); put("invoice_remaining_amount", newRemaining)
            put("cash_or_bank_reversed", false); put("journal_reversed", false)
        }
    }
}
