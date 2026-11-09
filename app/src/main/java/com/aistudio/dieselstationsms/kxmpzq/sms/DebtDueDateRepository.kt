package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.BusinessDay
import com.aistudio.dieselstationsms.kxmpzq.utils.IsoCivilDate
import com.aistudio.dieselstationsms.kxmpzq.utils.PhoneUtils
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

/**
 * One source of truth for credit-sale maturity dates, debt aging and automatic reminders.
 *
 * Payment terms are a free-text customer field in this app (for example, "صافي 30 يوم").
 * The repository turns those terms into an immutable due date on each credit sale, repairs
 * legacy credit rows without due dates, and ensures reminder candidates are based on the
 * actual outstanding amount and calendar dates (not 24-hour millisecond arithmetic).
 */
object DebtDueDateRepository {
    private val creditMethods = setOf("credit", "credit_sale", "credit_account", "on_account", "آجل", "اجل", "بيع آجل", "على الحساب")
    private val trustedDueDateSources = setOf("sale_creation", "sales_trigger_v3", "sales_trigger_v4", "historical_snapshot_reconstruction")

    sealed class TermsParseResult {
        data class Known(
            val days: Int,
            val basis: String = "business_day",
            val source: String = "explicit_text"
        ) : TermsParseResult()

        data class Unknown(val reason: String, val normalized: String = "") : TermsParseResult()
    }

    fun isTrustedDueDate(termsSource: String?, dueDateSource: String?): Boolean =
        termsSource == "explicit_text" && dueDateSource in trustedDueDateSources

    /** SQL predicate for trusted, non-empty maturity dates; alias is restricted to a SQL identifier. */
    fun trustedDueDateSql(alias: String): String {
        require(Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(alias)) { "اسم مستعار SQL غير صالح" }
        val sources = trustedDueDateSources.sorted().joinToString(",") { "'$it'" }
        return "TRIM(COALESCE($alias.due_date,''))<>'' AND COALESCE($alias.payment_terms_source_at_sale,'')='explicit_text' AND COALESCE($alias.due_date_source,'') IN ($sources)"
    }

    private data class SaleDebt(
        val transactionId: Long,
        val stationId: Int,
        val customerId: Long,
        val customerName: String,
        val phone: String,
        val invoiceNumber: String,
        val saleCode: String,
        val orderType: String,
        val paymentMethod: String,
        val dueDate: String,
        val createdAt: String,
        val total: Double,
        val paid: Double,
        val remaining: Double,
        val riskLevel: String,
        val terms: String
    )

    private data class ParsedDebtReminder(
        val message: SmsOutboxRepository.OutboxMessage,
        val stationId: Int,
        val customerId: Long,
        val dueDate: String,
        val invoiceIds: List<Long>,
        val stage: String?
    )

    /** Compatibility entry point for explicit schema initialization. */
    fun ensureSchema(helper: DatabaseHelper) {
        ensureSchema(helper.writableDatabase)
    }

    /**
     * Initializes the debt schema on the SQLiteOpenHelper lifecycle connection.
     * Normal read paths must not call this method; DatabaseHelper invokes it from
     * its transactional onCreate/onUpgrade flow.
     */
    fun ensureSchema(db: SQLiteDatabase) {
        if(!tableExists(db,"sales_transactions")) return
        if(tableExists(db,"parties")) {
            addColumn(db,"parties","payment_terms","TEXT")
            addColumn(db,"parties","payment_terms_id","INTEGER")
            addColumn(db,"parties","payment_terms_days","INTEGER")
            addColumn(db,"parties","payment_terms_source","TEXT")
        }
        addColumn(db,"sales_transactions","due_date","TEXT")
        addColumn(db,"sales_transactions","transaction_date","TEXT")
        addColumn(db,"sales_transactions","payment_terms_at_sale","TEXT")
        addColumn(db,"sales_transactions","payment_terms_id_at_sale","INTEGER")
        addColumn(db,"sales_transactions","payment_terms_days_at_sale","INTEGER")
        addColumn(db,"sales_transactions","payment_terms_source_at_sale","TEXT")
        addColumn(db,"sales_transactions","due_date_basis","TEXT")
        addColumn(db,"sales_transactions","due_date_source","TEXT")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS payment_terms (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                code TEXT NOT NULL UNIQUE,
                name_ar TEXT NOT NULL,
                name_en TEXT NOT NULL DEFAULT '',
                days INTEGER NOT NULL CHECK(days BETWEEN 0 AND 365),
                applies_to TEXT NOT NULL DEFAULT 'customer',
                station_id INTEGER,
                is_default INTEGER NOT NULL DEFAULT 0,
                is_active INTEGER NOT NULL DEFAULT 1,
                sort_order INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
        """.trimIndent())
        seedDefaultPaymentTerms(db)
        if(!triggerExists(db,"trg_debt_due_date_sales_insert_v4") ||
           !triggerExists(db,"trg_debt_due_date_sales_update_v4")) createDueDateTriggers(db)

        // Historical repair is evidence-gated: only invoice-time terms and an
        // invoice-time basis may reconstruct missing dates, never current customer defaults.
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_debt_due_dates_station_date ON sales_transactions(station_id,due_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_payment_terms_scope_active ON payment_terms(station_id,applies_to,is_active,sort_order)")
        reconstructProvableHistoricalDueDates(db)
    }

    /** Additive, transactional migration from the V58 sales schema to V59. */
    fun migrateV58ToV59(db: SQLiteDatabase) {
        check(db.inTransaction()) { "V58-to-V59 migration requires the onUpgrade transaction" }
        if (!tableExists(db, "sales_transactions")) return
        addColumn(db, "sales_transactions", "transaction_date", "TEXT")
        val salesColumns = columns(db, "sales_transactions")

        if (tableExists(db, "fuel_sales") && setOf("sale_id", "station_id", "sale_date").all { it in columns(db, "fuel_sales") }) {
            db.execSQL(
                """UPDATE sales_transactions
                   SET transaction_date=(
                       SELECT CASE
                           WHEN TRIM(COALESCE(fs.sale_date,''))<>''
                            AND date(substr(fs.sale_date,1,10),'+0 days')=substr(fs.sale_date,1,10)
                           THEN substr(fs.sale_date,1,10) END
                       FROM fuel_sales fs
                       WHERE fs.sale_id=sales_transactions.id AND fs.station_id=sales_transactions.station_id
                       ORDER BY fs.id DESC LIMIT 1
                   )
                   WHERE (sales_transactions.transaction_date IS NULL OR TRIM(sales_transactions.transaction_date)='')
                     AND EXISTS (
                         SELECT 1 FROM fuel_sales fs
                         WHERE fs.sale_id=sales_transactions.id AND fs.station_id=sales_transactions.station_id
                     )"""
            )
        }
        if ("business_day" in salesColumns) {
            db.execSQL(
                """UPDATE sales_transactions
                   SET transaction_date=substr(business_day,1,10)
                   WHERE (transaction_date IS NULL OR TRIM(transaction_date)='')
                     AND TRIM(COALESCE(business_day,''))<>''
                     AND date(substr(business_day,1,10),'+0 days')=substr(business_day,1,10)"""
            )
        }

        val snapshotColumns = setOf(
            "payment_terms_source_at_sale", "payment_terms_days_at_sale", "payment_terms_at_sale",
            "due_date_basis", "transaction_date"
        )
        if (snapshotColumns.all { it in salesColumns }) {
            db.execSQL(
                """UPDATE sales_transactions
                   SET due_date_basis=substr(transaction_date,1,10)
                   WHERE (due_date_basis IS NULL OR TRIM(due_date_basis)='')
                     AND COALESCE(payment_terms_source_at_sale,'')='explicit_text'
                     AND payment_terms_at_sale IS NOT NULL AND TRIM(payment_terms_at_sale)<>''
                     AND payment_terms_days_at_sale BETWEEN 0 AND 365
                     AND transaction_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
                     AND date(transaction_date,'+0 days')=transaction_date"""
            )
        }
        reconstructProvableHistoricalDueDates(db)
    }

    /**
     * Existing V59 installations already have the payment_terms table, but its original
     * DEFAULT was 'both' and the built-in rows may also have been seeded with that scope.
     * Run this repair as an explicit SQLiteOpenHelper upgrade transaction so installed
     * databases receive the same customer-only catalogue as fresh installations.
     */
    fun migrateV59ToV60(db: SQLiteDatabase) {
        check(db.inTransaction()) { "V59-to-V60 migration requires the onUpgrade transaction" }
        if (!tableExists(db, "sales_transactions")) return
        ensureSchema(db)

        val invalidBuiltInScopes = db.rawQuery(
            """SELECT COUNT(*) FROM payment_terms
               WHERE station_id IS NULL
                 AND code IN ('AFTER_1','AFTER_3','NET_7','NET_15','NET_30','NET_45','NET_60','NET_90')
                 AND applies_to<>'customer'""",
            null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
        check(invalidBuiltInScopes == 0L) {
            "Built-in payment terms were not migrated to the customer scope"
        }
    }

    /** Rebuild only from immutable invoice-time terms plus a stored invoice-time basis. */
    private fun reconstructProvableHistoricalDueDates(db: SQLiteDatabase) {
        val saleColumns = columns(db, "sales_transactions")
        val required = setOf(
            "payment_terms_source_at_sale", "payment_terms_days_at_sale", "payment_terms_at_sale",
            "due_date_basis", "due_date", "due_date_source", "payment_method", "remaining_amount",
            "status", "is_deleted"
        )
        if (!required.all { it in saleColumns }) return
        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        try {
            db.execSQL(
                """UPDATE sales_transactions
                   SET due_date=date(due_date_basis,'+' || payment_terms_days_at_sale || ' days'),
                       due_date_source='historical_snapshot_reconstruction'
                   WHERE (due_date IS NULL OR TRIM(due_date)='')
                     AND COALESCE(payment_terms_source_at_sale,'')='explicit_text'
                     AND payment_terms_at_sale IS NOT NULL AND TRIM(payment_terms_at_sale)<>''
                     AND payment_terms_days_at_sale BETWEEN 0 AND 365
                     AND LOWER(REPLACE(REPLACE(TRIM(COALESCE(payment_method,'')),' ',''),'-','_'))
                         IN ('credit','credit_sale','credit_account','on_account','آجل','اجل','بيعآجل','علىالحساب')
                     AND COALESCE(remaining_amount,0)>0.000001
                     AND LOWER(COALESCE(status,'')) NOT IN ('cancelled','refunded')
                     AND COALESCE(is_deleted,0)=0
                     AND due_date_basis GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
                     AND date(due_date_basis,'+0 days')=due_date_basis"""
            )
            if (ownsTransaction) db.setTransactionSuccessful()
        } finally {
            if (ownsTransaction && db.inTransaction()) db.endTransaction()
        }
    }

    private data class PaymentTermSeed(
        val code: String,
        val nameAr: String,
        val nameEn: String,
        val days: Int,
        val sortOrder: Int
    )

    private fun seedDefaultPaymentTerms(db: SQLiteDatabase) {
        val items=listOf(
            PaymentTermSeed("CASH","نقدي / فوري","Cash / Immediate",0,0),
            PaymentTermSeed("AFTER_1","بعد يوم واحد","After 1 day",1,10),
            PaymentTermSeed("AFTER_3","بعد 3 أيام","After 3 days",3,20),
            PaymentTermSeed("NET_7","بعد 7 أيام","After 7 days",7,30),
            PaymentTermSeed("NET_15","بعد 15 يوماً","After 15 days",15,40),
            PaymentTermSeed("NET_30","بعد 30 يوماً","After 30 days",30,50),
            PaymentTermSeed("NET_45","بعد 45 يوماً","After 45 days",45,60),
            PaymentTermSeed("NET_60","بعد 60 يوماً","After 60 days",60,70),
            PaymentTermSeed("NET_90","بعد 90 يوماً","After 90 days",90,80)
        )
        items.forEach { item ->
            db.execSQL(
                """INSERT OR IGNORE INTO payment_terms
                   (code,name_ar,name_en,days,applies_to,station_id,is_default,is_active,sort_order)
                   VALUES(?,?,?,?, 'customer', NULL, ?, 1, ?)""",
                arrayOf(item.code,item.nameAr,item.nameEn,item.days,if(item.code=="NET_30") 1 else 0,item.sortOrder)
            )
            // Catalogue labels may be updated; immutable invoice snapshots are not.
            db.execSQL(
                "UPDATE payment_terms SET name_ar=?, name_en=?, days=?, applies_to='customer', sort_order=? WHERE code=? AND station_id IS NULL",
                arrayOf(item.nameAr, item.nameEn, item.days, item.sortOrder, item.code)
            )
        }
        // The global UNIQUE(code) catalogue cannot reuse NET_7/NET_30 for a second scope.
        // Supplier equivalents therefore have explicit supplier-only codes and never inherit
        // the customer-only built-in records.
        items.forEach { item ->
            val supplierCode = "SUPPLIER_${item.code}"
            db.execSQL(
                """INSERT OR IGNORE INTO payment_terms
                   (code,name_ar,name_en,days,applies_to,station_id,is_default,is_active,sort_order)
                   VALUES(?,?,?,?,'supplier',NULL,?,1,?)""",
                arrayOf(supplierCode, item.nameAr, item.nameEn, item.days, if (item.code=="NET_30") 1 else 0, item.sortOrder)
            )
            db.execSQL(
                "UPDATE payment_terms SET name_ar=?,name_en=?,days=?,applies_to='supplier',sort_order=? WHERE code=? AND station_id IS NULL",
                arrayOf(item.nameAr, item.nameEn, item.days, item.sortOrder, supplierCode)
            )
        }
    }

    private fun findPaymentTermId(
        db: SQLiteDatabase,
        days: Int,
        stationId: Int?,
        appliesTo: String
    ): Long? {
        require(appliesTo == "customer" || appliesTo == "supplier") { "نطاق شرط الدفع غير صالح" }
        return db.rawQuery(
            """SELECT id FROM payment_terms WHERE days=? AND is_active=1 AND applies_to=?
               AND (station_id IS NULL OR station_id=?)
               ORDER BY CASE WHEN station_id IS NULL THEN 0 ELSE 1 END,sort_order,id LIMIT 1""",
            arrayOf(days.toString(), appliesTo, (stationId ?: 0).toString())
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }

    private fun partyPaymentTermScope(db: SQLiteDatabase, partyId: Long, stationId: Int): String {
        if (!tableExists(db, "party_types")) return "customer"
        val typeCode = runCatching {
            db.rawQuery(
                """SELECT LOWER(COALESCE(pt.type_code,'')) FROM parties p
                   LEFT JOIN party_types pt ON pt.id=p.party_type_id
                   WHERE p.id=? AND p.station_id=? AND COALESCE(p.is_deleted,0)=0 LIMIT 1""",
                arrayOf(partyId.toString(), stationId.toString())
            ).use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else "" }
        }.getOrDefault("")
        return if (typeCode.contains("supplier") || typeCode.contains("vendor") || typeCode.contains("مورد")) {
            "supplier"
        } else {
            "customer"
        }
    }

    fun persistPartyTerms(helper: DatabaseHelper,partyId:Long,stationId:Int,requestedTerms:String?):String {
        require(partyId>0L && stationId>0) { "معرف الطرف أو المحطة غير صالح" }
        val terms=requestedTerms.orEmpty().trim()
        val days=parseTermsDays(terms)
        val db=helper.writableDatabase
        val appliesTo = partyPaymentTermScope(db, partyId, stationId)
        val term=days?.let { findPaymentTermId(db,it,stationId,appliesTo) }
        val changed=db.update(
            "parties",
            ContentValues().apply {
                put("payment_terms",terms)
                if(days!=null) put("payment_terms_days",days) else putNull("payment_terms_days")
                if(term!=null) put("payment_terms_id",term) else putNull("payment_terms_id")
                if(days!=null) put("payment_terms_source","explicit_text") else putNull("payment_terms_source")
                put("updated_at",java.time.LocalDateTime.now().toString())
            },
            "id=? AND station_id=? AND is_deleted=0",
            arrayOf(partyId.toString(),stationId.toString())
        )
        check(changed==1) { "لم يتم حفظ شروط الدفع للطرف المحدد في المحطة الحالية" }
        return terms
    }

    fun applySaleSnapshot(db:SQLiteDatabase,saleId:Long,stationId:Int,partyId:Long,businessDate:String):JSONObject {
        require(saleId>0L && stationId>0 && partyId>0L) { "بيانات لقطة شروط الدفع غير صالحة" }
        val party=db.rawQuery(
            """SELECT COALESCE(payment_terms,''),payment_terms_days,COALESCE(payment_terms_id,0),COALESCE(payment_terms_source,'')
               FROM parties WHERE id=? AND station_id=? AND is_deleted=0 AND is_active=1 LIMIT 1""",
            arrayOf(partyId.toString(),stationId.toString())
        ).use { c ->
            require(c.moveToFirst()) { "العميل غير موجود أو غير نشط ضمن المحطة" }
            listOf(c.getString(0).orEmpty(),if(c.isNull(1)) null else c.getInt(1),c.getLong(2).takeIf { it>0L },c.getString(3).orEmpty())
        }
        val terms=party[0] as String
        val source=party[3] as String
        val days=if(source=="explicit_text") (party[1] as? Int)?.takeIf { it in 0..365 } ?: parseTermsDays(terms) else null
        val base=parseDate(businessDate)
        val due=if(base!=null && days!=null) base.plusDays(days.toLong()).toString() else null
        val values=ContentValues().apply {
            put("payment_terms_at_sale",terms)
            if(days!=null) put("payment_terms_days_at_sale",days) else putNull("payment_terms_days_at_sale")
            if(source.isNotBlank()) put("payment_terms_source_at_sale",source) else putNull("payment_terms_source_at_sale")
            if(party[2]!=null) put("payment_terms_id_at_sale",party[2] as Long) else putNull("payment_terms_id_at_sale")
            if(base!=null) {
                put("due_date_basis",base.toString())
                if(due!=null) { put("due_date",due);put("due_date_source","sale_creation") }
                else { putNull("due_date");put("due_date_source","missing_terms") }
            } else {
                putNull("due_date_basis");putNull("due_date");put("due_date_source","missing_base_date")
            }
        }
        val changed=db.update("sales_transactions",values,"id=? AND station_id=? AND is_deleted=0",arrayOf(saleId.toString(),stationId.toString()))
        check(changed==1) { "تعذر حفظ شروط الدفع وتاريخ الاستحقاق مع فاتورة البيع" }
        return JSONObject().apply {
            put("payment_terms_id",party[2]?:JSONObject.NULL);put("payment_terms",terms)
            put("payment_terms_source",source)
            put("payment_terms_days",days?:JSONObject.NULL);put("due_date",due?:JSONObject.NULL)
            put("due_date_source",when { base==null -> "missing_base_date";days==null -> "missing_terms";else -> "sale_creation" })
        }
    }


    /**
     * Attaches a selected payment_terms row to a newly-created credit invoice.
     * The date basis is read from the persisted invoice, never from browser input.
     * An existing SQLite transaction is reused; otherwise this method owns one.
     */
    fun applySelectedPaymentTermSnapshot(
        db: SQLiteDatabase, saleId: Long, stationId: Int, partyId: Long, paymentTermId: Long,
        allowExistingSnapshot: Boolean = false, allowSettledInvoice: Boolean = false
    ): JSONObject {
        require(saleId > 0L && stationId > 0 && partyId > 0L && paymentTermId > 0L) {
            "معرف الفاتورة أو المحطة أو العميل أو شرط الدفع غير صالح"
        }
        require(tableExists(db, "sales_transactions") && tableExists(db, "payment_terms") &&
            tableExists(db, "parties")) { "جداول الفواتير أو شروط الدفع غير مهيأة" }
        val saleColumns = columns(db, "sales_transactions")
        val customerColumn = listOf("customer_party_id", "customer_id", "party_id")
            .firstOrNull { it in saleColumns } ?: throw IllegalStateException("تعذر تحديد عميل الفاتورة")
        require("payment_method" in saleColumns) { "طريقة الدفع غير محفوظة في الفاتورة" }
        val bases = listOf("transaction_date", "business_day", "sale_date").filter { it in saleColumns }
        require(bases.isNotEmpty()) { "لا يوجد تاريخ عمل أو تاريخ معاملة محفوظ للفاتورة" }

        data class SaleContext(
            val customerId: Long, val paymentMethod: String, val deleted: Boolean,
            val status: String, val remaining: Double, val baseDate: LocalDate?
        )
        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        try {
            val sale = db.rawQuery(
                buildString {
                    append("SELECT COALESCE(`$customerColumn`,0),LOWER(TRIM(COALESCE(payment_method,''))),")
                    append(if ("is_deleted" in saleColumns) "COALESCE(is_deleted,0)," else "0,")
                    append(if ("status" in saleColumns) "LOWER(TRIM(COALESCE(status,'')))," else "'' ,")
                    append(if ("remaining_amount" in saleColumns) "COALESCE(remaining_amount,0)," else "1,")
                    append(bases.joinToString(",") { "`$it`" })
                    append(" FROM sales_transactions WHERE id=? AND station_id=? LIMIT 1")
                },
                arrayOf(saleId.toString(), stationId.toString())
            ).use { c ->
                require(c.moveToFirst()) { "الفاتورة غير موجودة ضمن المحطة المحددة" }
                SaleContext(
                    c.getLong(0), c.getString(1).orEmpty(), c.getInt(2) != 0, c.getString(3).orEmpty(),
                    c.getDouble(4),
                    (0 until bases.size).asSequence().mapNotNull { parseDate(c.getString(5 + it)) }.firstOrNull()
                )
            }
            require(sale.customerId == partyId) { "العميل المحدد لا يطابق العميل المرتبط بالفاتورة" }
            require(!sale.deleted) { "لا يمكن تعيين شروط دفع لفاتورة محذوفة" }
            val method = sale.paymentMethod.lowercase(Locale.ROOT).replace(" ", "").replace("-", "_")
            require(method in setOf("credit","credit_sale","credit_account","on_account","آجل","اجل","بيعآجل","علىالحساب")) {
                "شروط الدفع الآجل لا تطبق على فاتورة نقدية"
            }
            require(sale.status !in setOf("cancelled","canceled","refunded","void","reversed")) {
                "لا يمكن تعيين شروط دفع لفاتورة ملغاة أو معكوسة"
            }
            require(allowSettledInvoice || sale.remaining > 0.000001) { "الفاتورة لا تحتوي رصيداً متبقياً يستلزم الاستحقاق" }
            if (!allowExistingSnapshot) {
                val hasSnapshot = db.rawQuery(
                    "SELECT 1 FROM sales_transactions WHERE id=? AND station_id=? AND (payment_terms_at_sale IS NOT NULL OR payment_terms_id_at_sale IS NOT NULL OR due_date IS NOT NULL) LIMIT 1",
                    arrayOf(saleId.toString(), stationId.toString())
                ).use { it.moveToFirst() }
                require(!hasSnapshot) { "لقطة شروط الدفع للفواتير المحفوظة غير قابلة للتعديل؛ يلزم مسار تصحيح محاسبي موثق" }
            }
            val partyActive = db.rawQuery(
                """SELECT 1 FROM parties WHERE id=? AND station_id=?
                   AND COALESCE(is_active,1)=1 AND COALESCE(is_deleted,0)=0 LIMIT 1""",
                arrayOf(partyId.toString(), stationId.toString())
            ).use { it.moveToFirst() }
            require(partyActive) { "العميل غير نشط أو لا يتبع المحطة الحالية" }
            val term = db.rawQuery(
                """SELECT id,name_ar,days FROM payment_terms
                   WHERE id=? AND is_active=1 AND applies_to='customer'
                     AND (station_id IS NULL OR station_id=?) LIMIT 1""",
                arrayOf(paymentTermId.toString(), stationId.toString())
            ).use { c ->
                require(c.moveToFirst()) { "شرط الدفع غير نشط أو غير متاح لهذه المحطة أو لنوع العميل" }
                Triple(c.getLong(0), c.getString(1).orEmpty(), c.getInt(2))
            }
            require(term.third in 0..365) { "عدد أيام شرط الدفع خارج النطاق المسموح" }

            val baseDate = sale.baseDate
                ?: throw IllegalArgumentException("لا يمكن اعتماد فاتورة آجلة دون تاريخ أساس صالح للاستحقاق")
            val due = baseDate.plusDays(term.third.toLong())
            val values = ContentValues().apply {
                put("payment_terms_at_sale", term.second)
                put("payment_terms_id_at_sale", term.first)
                put("payment_terms_days_at_sale", term.third)
                put("payment_terms_source_at_sale", "explicit_text")
                put("due_date_basis", baseDate.toString())
                put("due_date", due.toString())
                put("due_date_source", "sale_creation")
            }
            val where = "id=? AND station_id=? AND `$customerColumn`=?" +
                if ("is_deleted" in saleColumns) " AND COALESCE(is_deleted,0)=0" else ""
            check(db.update(
                "sales_transactions", values, where,
                arrayOf(saleId.toString(), stationId.toString(), partyId.toString())
            ) == 1) { "تعذر تثبيت نسخة شروط الدفع على الفاتورة" }

            if (ownsTransaction) db.setTransactionSuccessful()
            return JSONObject().apply {
                put("success", true); put("sale_id", saleId)
                put("payment_terms_id", term.first); put("payment_terms", term.second)
                put("payment_terms_days", term.third)
                put("due_date_basis", baseDate.toString())
                put("due_date", due.toString())
                put("due_date_source", "sale_creation")
            }
        } finally {
            if (ownsTransaction && db.inTransaction()) db.endTransaction()
        }
    }

    fun getPaymentTerms(helper:DatabaseHelper,stationId:Int,appliesTo:String="customer"):JSONArray {
        require(stationId>0) { "معرف المحطة غير صالح" }
        val scope=appliesTo.lowercase(Locale.ROOT)
        require(scope in setOf("customer","supplier","both")) { "نوع شروط الدفع غير صالح" }
        return helper.readableDatabase.rawQuery(
            """SELECT id,code,name_ar,name_en,days,applies_to,station_id,is_default,sort_order
               FROM payment_terms WHERE is_active=1 AND days>0 AND applies_to=?
                 AND (station_id IS NULL OR station_id=?)
               ORDER BY CASE WHEN is_default=1 THEN 0 ELSE 1 END,sort_order,id""",
            arrayOf(scope,stationId.toString())
        ).use { c ->
            val out=JSONArray()
            while(c.moveToNext()) out.put(JSONObject().apply {
                put("id",c.getLong(0));put("code",c.getString(1));put("name_ar",c.getString(2));put("name_en",c.getString(3))
                put("days",c.getInt(4));put("applies_to",c.getString(5))
                put("station_id",if(c.isNull(6)) JSONObject.NULL else c.getInt(6))
                put("is_default",c.getInt(7)==1);put("sort_order",c.getInt(8))
            })
            out
        }
    }

    fun getCustomerDebts(
        helper: DatabaseHelper,
        stationId: Int,
        fromDate: String? = null,
        toDate: String? = null,
        asOfDate: String = BusinessDay.currentDate()
    ): JSONArray {
        require(stationId > 0) { "معرف المحطة غير صالح" }
        IsoCivilDate.requireOptionalRange(fromDate, toDate)
        IsoCivilDate.requireValid(asOfDate, "تاريخ العمل")
        val today = LocalDate.parse(asOfDate)
        val rows = readDebts(helper.readableDatabase, stationId)
        val output = JSONArray()
        rows.filter { row ->
            // The caller's date range is a maturity-date filter. Current maturity status
            // is calculated against one explicit operating-date snapshot, not repeated
            // calls to the device clock while serializing individual invoices.
            val dueDate = row.dueDate
            val dueDateIsValid = IsoCivilDate.isValid(dueDate)
            (fromDate.isNullOrEmpty() || (dueDateIsValid && dueDate >= fromDate)) &&
                (toDate.isNullOrEmpty() || (dueDateIsValid && dueDate <= toDate))
        }.forEach { output.put(toJson(it, today)) }
        return output
    }

    /**
     * Canonical customer-debt aggregation for the dashboard, CRM balance columns and
     * customer-debt reports. It deliberately reuses readDebts(), so saved remaining
     * balances, trusted invoice-time due dates, station isolation and cancellation
     * exclusions have exactly one implementation.
     *
     * asOfDate is supplied by the caller once per screen/report load. This keeps a
     * midnight rollover from classifying rows against different dates in one response
     * and makes the method deterministic when device date and stored business_day differ.
     */
    /**
     * Returns a customer's open invoice balance using the exact same station-scoped
     * invoice selection and remaining_amount source as the dashboard/CRM summary.
     */
    fun getCustomerOutstandingBalance(
        helper: DatabaseHelper,
        stationId: Int,
        customerPartyId: Long
    ): Double {
        require(stationId > 0 && customerPartyId > 0L) { "معرف العميل أو المحطة غير صالح" }
        return readDebts(helper.readableDatabase, stationId)
            .asSequence()
            .filter { it.customerId == customerPartyId }
            .sumOf { it.remaining }
            .coerceAtLeast(0.0)
    }

    fun getCustomerDebtSummary(
        helper: DatabaseHelper,
        stationId: Int,
        asOfDate: String = BusinessDay.currentDate()
    ): JSONObject {
        require(stationId > 0) { "معرف المحطة غير صالح" }
        IsoCivilDate.requireValid(asOfDate, "تاريخ العمل")
        val today = LocalDate.parse(asOfDate)
        return buildCustomerDebtSummary(
            readDebts(helper.readableDatabase, stationId),
            stationId,
            asOfDate,
            today
        )
    }

    /**
     * Summary and invoice-aging rows are derived from the exact same readDebts()
     * snapshot and operating date. This prevents totals and invoice rows from
     * splitting across midnight or a concurrent payment update.
     */
    fun getCustomerDebtReportBundle(
        helper: DatabaseHelper,
        stationId: Int,
        fromDate: String? = null,
        toDate: String? = null,
        asOfDate: String = BusinessDay.currentDate()
    ): JSONObject {
        require(stationId > 0) { "معرف المحطة غير صالح" }
        IsoCivilDate.requireOptionalRange(fromDate, toDate)
        IsoCivilDate.requireValid(asOfDate, "تاريخ العمل")
        val today = LocalDate.parse(asOfDate)
        val rows = readDebts(helper.readableDatabase, stationId)
        val invoices = JSONArray()
        rows.filter { row ->
            val dueDate = row.dueDate
            val validDueDate = IsoCivilDate.isValid(dueDate)
            row.remaining > 0.000001 &&
                (fromDate.isNullOrEmpty() || (validDueDate && dueDate >= fromDate)) &&
                (toDate.isNullOrEmpty() || (validDueDate && dueDate <= toDate))
        }.forEach { invoices.put(toJson(it, today)) }
        val summary = buildCustomerDebtSummary(rows, stationId, asOfDate, today)
        return JSONObject().apply {
            put("station_id", stationId)
            put("as_of_date", asOfDate)
            put("summary", summary)
            put("invoices", invoices)
        }
    }

    private fun buildCustomerDebtSummary(
        allRows: List<SaleDebt>,
        stationId: Int,
        asOfDate: String,
        today: LocalDate
    ): JSONObject {
        val openRows = allRows.filter { it.remaining > 0.000001 }
        val overdueRows = openRows.filter { parseDate(it.dueDate)?.isBefore(today) == true }
        val dueTodayRows = openRows.filter { parseDate(it.dueDate) == today }
        val dueNextSevenRows = openRows.filter { due ->
            val date = parseDate(due.dueDate)
            date != null && !date.isBefore(today) && !date.isAfter(today.plusDays(7))
        }
        val futureRows = openRows.filter { parseDate(it.dueDate)?.isAfter(today) == true }
        val undatedRows = openRows.filter { parseDate(it.dueDate) == null }
        val totalOutstanding = openRows.sumOf { it.remaining }.coerceAtLeast(0.0)
        val totalOverdue = overdueRows.sumOf { it.remaining }.coerceAtLeast(0.0)
        val totalDueNextSeven = dueNextSevenRows.sumOf { it.remaining }.coerceAtLeast(0.0)
        val totalUndated = undatedRows.sumOf { it.remaining }.coerceAtLeast(0.0)

        val customerBalances = JSONArray()
        val byCustomer = openRows.asSequence()
            .filter { it.customerId > 0L }
            .groupBy { it.customerId }
        byCustomer.toSortedMap().forEach { (customerId, rows) ->
            val overdue = rows.filter { parseDate(it.dueDate)?.isBefore(today) == true }
            val dueToday = rows.filter { parseDate(it.dueDate) == today }
            val dueNextSeven = rows.filter {
                val date = parseDate(it.dueDate)
                date != null && !date.isBefore(today) && !date.isAfter(today.plusDays(7))
            }
            val undated = rows.filter { parseDate(it.dueDate) == null }
            val balance = rows.sumOf { it.remaining }.coerceAtLeast(0.0)
            val representative = rows.first()
            customerBalances.put(JSONObject().apply {
                put("customer_party_id", customerId)
                put("customer_id", customerId)
                put("party_id", customerId)
                put("station_id", stationId)
                put("customer_name", representative.customerName)
                put("commercial_name", representative.customerName)
                put("commercial_name_ar", representative.customerName)
                put("phone", representative.phone)
                put("current_balance", balance)
                put("total_due", balance)
                put("outstanding_amount", balance)
                put("remaining_amount", balance)
                put("overdue_amount", overdue.sumOf { it.remaining }.coerceAtLeast(0.0))
                put("due_today_amount", dueToday.sumOf { it.remaining }.coerceAtLeast(0.0))
                put("due_next_7_days_amount", dueNextSeven.sumOf { it.remaining }.coerceAtLeast(0.0))
                put("undated_amount", undated.sumOf { it.remaining }.coerceAtLeast(0.0))
                put("open_invoice_count", rows.size)
                put("overdue_invoice_count", overdue.size)
                put("due_today_invoice_count", dueToday.size)
                put("due_next_7_days_invoice_count", dueNextSeven.size)
                put("undated_invoice_count", undated.size)
                put("has_overdue", overdue.isNotEmpty())
            })
        }

        return JSONObject().apply {
            put("source", "DebtDueDateRepository.readDebts")
            put("station_id", stationId)
            put("as_of_date", asOfDate)
            put("total_customer_debts", totalOutstanding)
            put("customer_debts", totalOutstanding)
            put("total_due_amount", totalOutstanding)
            put("total_overdue_amount", totalOverdue)
            put("overdue_amount", totalOverdue)
            put("due_next_7_days_amount", totalDueNextSeven)
            put("undated_amount", totalUndated)
            put("open_invoice_count", openRows.size)
            put("overdue_invoice_count", overdueRows.size)
            put("due_invoice_count", dueNextSevenRows.size)
            put("due_next_7_days_invoice_count", dueNextSevenRows.size)
            put("due_today_invoice_count", dueTodayRows.size)
            put("future_due_invoice_count", futureRows.size)
            put("undated_invoice_count", undatedRows.size)
            put("paid_invoice_count", allRows.count { it.remaining <= 0.000001 })
            put("invoice_count", allRows.size)
            put("debtor_count", byCustomer.size)
            put("customers_with_debt", byCustomer.size)
            put("customer_balances", customerBalances)
        }
    }
    /**
     * Returns one nearest-due invoice per customer for the requested calendar-day window.
     * days=0 means due today; negative/overdue balances are not automatically re-reminded.
     */
    fun getDueSoonDebtReminders(helper: DatabaseHelper, stationId: Int, days: Int): JSONArray {
        require(stationId > 0) { "معرف المحطة غير صالح" }
        val maxDays = days.coerceIn(0, 365)
        val today = LocalDate.parse(BusinessDay.currentDate())
        val all = readDebts(helper.readableDatabase, stationId)
        val openByCustomer = all.filter { it.remaining > 0.000001 && it.customerId > 0L }
            .groupBy { it.customerId }
        val candidates = all.filter { debt ->
            if (debt.remaining <= 0.000001 || debt.customerId <= 0L || debt.dueDate.isBlank()) return@filter false
            val due = parseDate(debt.dueDate) ?: return@filter false
            val delta = java.time.temporal.ChronoUnit.DAYS.between(today, due)
            delta in 0L..maxDays.toLong()
        }.groupBy { it.customerId }
        val result = JSONArray()
        candidates.forEach { (customerId, dueRows) ->
            val nearest = dueRows.minWithOrNull(compareBy<SaleDebt>({ parseDate(it.dueDate) ?: LocalDate.MAX }, { it.transactionId }))
                ?: return@forEach
            val due = parseDate(nearest.dueDate) ?: return@forEach
            val daysRemaining = java.time.temporal.ChronoUnit.DAYS.between(today, due).toInt()
            val customerRows = openByCustomer[customerId].orEmpty()
            val outstanding = customerRows.sumOf { it.remaining }.coerceAtLeast(0.0)
            val dueDateKey = nearest.dueDate
            val sameDateRows = dueRows.filter { it.dueDate == dueDateKey }
            val dueAmount = sameDateRows.sumOf { it.remaining }.coerceAtLeast(0.0)
            val phone = PhoneUtils.normalize(nearest.phone)
            val invoiceSet = sameDateRows.map { it.transactionId }.sorted().joinToString(",")
            val stage = if (daysRemaining == 0) "due" else "pre"
            val eventId = "debt-reminder:$stationId:$customerId:$dueDateKey:$invoiceSet:$stage"
            val dedupeKey = if (phone != null) "$eventId:$phone" else ""
            val alreadyQueued = dedupeKey.isNotBlank() && hasOutboxDedupeKey(helper.readableDatabase, dedupeKey)
            val eligible = outstanding > 0.000001 && phone != null && !alreadyQueued
            result.put(JSONObject().apply {
                put("id", nearest.transactionId)
                put("transaction_id", nearest.transactionId)
                put("sale_id", nearest.transactionId)
                put("customer_party_id", customerId)
                put("customer_id", customerId)
                put("customer_name", nearest.customerName)
                put("phone", nearest.phone)
                put("customer_phone", nearest.phone)
                put("invoice_number", nearest.invoiceNumber)
                put("sale_code", nearest.saleCode)
                put("order_type", nearest.orderType)
                put("payment_method", nearest.paymentMethod)
                put("nearest_due_date", nearest.dueDate)
                put("due_date", nearest.dueDate)
                put("days_remaining", daysRemaining)
                put("due_soon_amount", dueAmount)
                put("total_outstanding", outstanding)
                put("remaining_amount", nearest.remaining)
                put("last_reminder_at", if (alreadyQueued) nearest.dueDate else "")
                put("eligible", eligible)
                put("station_id", stationId)
                put("event_id", eventId)
                put("risk_level", nearest.riskLevel)
            })
        }
        return result
    }

    /**
     * Catch up on a missed due-day reminder once SMS permissions/network were unavailable.
     * The event keeps the original invoice maturity date as its dedupe key, so reopening the
     * app does not send a repeated daily reminder for the same maturity date.
     */
    fun getOverdueReminderCandidates(helper: DatabaseHelper, stationId: Int): JSONArray {
        require(stationId > 0) { "معرف المحطة غير صالح" }
        val today = LocalDate.parse(BusinessDay.currentDate())
        val all = readDebts(helper.readableDatabase, stationId)
        val openByCustomer = all.filter { it.remaining > 0.000001 && it.customerId > 0L }
            .groupBy { it.customerId }
        val overdueByCustomer = all.filter { debt ->
            if (debt.remaining <= 0.000001 || debt.customerId <= 0L) return@filter false
            val due = parseDate(debt.dueDate) ?: return@filter false
            due.isBefore(today)
        }.groupBy { it.customerId }
        val output = JSONArray()
        overdueByCustomer.forEach { (customerId, overdueRows) ->
            // Use the most recent overdue maturity for context while including the customer's
            // full open balance in the reminder message.
            val nearest = overdueRows.maxByOrNull { parseDate(it.dueDate) ?: LocalDate.MIN } ?: return@forEach
            val dueDate = nearest.dueDate
            val due = parseDate(dueDate) ?: return@forEach
            val daysRemaining = java.time.temporal.ChronoUnit.DAYS.between(today, due).toInt()
            if (daysRemaining >= 0) return@forEach
            val customerRows = openByCustomer[customerId].orEmpty()
            val totalOutstanding = customerRows.sumOf { it.remaining }.coerceAtLeast(0.0)
            val dueAmount = overdueRows.filter { it.dueDate == dueDate }
                .sumOf { it.remaining }.coerceAtLeast(0.0)
            val phone = PhoneUtils.normalize(nearest.phone)
            val invoiceSet = overdueRows.filter { it.dueDate == dueDate }
                .map { it.transactionId }.sorted().joinToString(",")
            val eventId = "debt-reminder:$stationId:$customerId:$dueDate:$invoiceSet:overdue"
            val dedupeKey = if (phone != null) "$eventId:$phone" else ""
            val dueDayEventIds = listOf(
                "debt-reminder:$stationId:$customerId:$dueDate:$invoiceSet:due",
                "debt-reminder:$stationId:$customerId:$dueDate:$invoiceSet"
            )
            val dueDayWasSent = phone != null &&
                dueDayEventIds.any { hasSentDebtReminder(helper.readableDatabase, it, phone) }
            val alreadyQueued = (dedupeKey.isNotBlank() && hasOutboxDedupeKey(helper.readableDatabase, dedupeKey)) ||
                dueDayWasSent
            output.put(JSONObject().apply {
                put("id", nearest.transactionId)
                put("transaction_id", nearest.transactionId)
                put("sale_id", nearest.transactionId)
                put("customer_party_id", customerId)
                put("customer_id", customerId)
                put("customer_name", nearest.customerName)
                put("phone", nearest.phone)
                put("customer_phone", nearest.phone)
                put("invoice_number", nearest.invoiceNumber)
                put("sale_code", nearest.saleCode)
                put("order_type", nearest.orderType)
                put("payment_method", nearest.paymentMethod)
                put("nearest_due_date", dueDate)
                put("due_date", dueDate)
                put("days_remaining", daysRemaining)
                put("due_soon_amount", dueAmount)
                put("total_outstanding", totalOutstanding)
                put("remaining_amount", nearest.remaining)
                put("last_reminder_at", if (alreadyQueued) dueDate else "")
                put("eligible", totalOutstanding > 0.000001 && phone != null && !alreadyQueued)
                put("station_id", stationId)
                put("event_id", eventId)
                put("risk_level", nearest.riskLevel)
                put("status", statusFor(nearest.remaining, nearest.paid, nearest.dueDate, nearest.riskLevel))
            })
        }
        return output
    }

    /**
     * Revalidates a queued debt reminder immediately before SMS transport. The event identity
     * must still describe the currently selected due-date cohort for this customer, the cohort
     * must have an open balance and a trusted maturity date, and the recipient must match the
     * customer's current normalized phone number. Non-debt messages are unaffected.
     */
    fun isCurrentDebtReminder(
        helper: DatabaseHelper,
        eventId: String?,
        businessEntityId: String?,
        recipient: String,
        body: String
    ): Boolean {
        val message = SmsOutboxRepository.OutboxMessage(
            messageId = "single-message-validation",
            recipient = recipient,
            body = body,
            partsCount = 1,
            attemptCount = 0,
            subscriptionId = null,
            eventId = eventId,
            businessEntityId = businessEntityId
        )
        return message.messageId in validateDebtReminderBatch(helper, listOf(message))
    }

    /**
     * Pre-validates a bounded Outbox batch using one customer-scoped debt read per station,
     * rather than one station-wide scan per message. The worker performs an individual final
     * revalidation after this pre-filter and immediately before each transport. Non-debt
     * notifications pass through; malformed debt events fail closed.
     */
    fun validateDebtReminderBatch(
        helper: DatabaseHelper,
        messages: List<SmsOutboxRepository.OutboxMessage>
    ): Set<String> {
        val eligibleMessageIds = linkedSetOf<String>()
        val parsedReminders = mutableListOf<ParsedDebtReminder>()

        messages.forEach { message ->
            if (message.eventId?.startsWith("debt-reminder:") != true) {
                eligibleMessageIds += message.messageId
            } else {
                parseDebtReminder(message)?.let(parsedReminders::add)
            }
        }
        if (parsedReminders.isEmpty()) return eligibleMessageIds

        val today = LocalDate.parse(BusinessDay.currentDate())
        val preDueDays = reminderLeadDays(helper)
        val database = helper.readableDatabase
        parsedReminders.groupBy { it.stationId }.forEach { (stationId, stationMessages) ->
            val customerIds = stationMessages.map { it.customerId }.toSet()
            val debtsByCustomer = readDebts(
                db = database,
                stationId = stationId,
                onlyCustomerIds = customerIds
            ).groupBy { it.customerId }

            stationMessages.forEach { reminder ->
                val customerDebts = debtsByCustomer[reminder.customerId].orEmpty()
                if (isCurrentDebtReminderForCustomer(reminder, customerDebts, today, preDueDays)) {
                    eligibleMessageIds += reminder.message.messageId
                }
            }
        }
        return eligibleMessageIds
    }

    private fun parseDebtReminder(
        message: SmsOutboxRepository.OutboxMessage
    ): ParsedDebtReminder? {
        // Five-part events are legacy due-day/overdue messages. New events carry an
        // explicit stage so pre-due notices cannot suppress a later due-day notice.
        val parts = message.eventId?.split(":", limit = 6) ?: return null
        if (parts.size !in 5..6) return null
        val stationId = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val customerId = parts[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val eventDueDate = parts[3].takeIf { it.length == 10 && parseDate(it) != null } ?: return null
        if (message.businessEntityId != "party:$customerId") return null
        val invoiceIds = parts[4].split(',').map { it.toLongOrNull() ?: return null }
        if (invoiceIds.isEmpty() || invoiceIds.any { it <= 0L } ||
            invoiceIds != invoiceIds.distinct().sorted()
        ) return null
        val stage = parts.getOrNull(5)
        if (stage != null && stage !in setOf("pre", "due", "overdue")) return null

        return ParsedDebtReminder(message, stationId, customerId, eventDueDate, invoiceIds, stage)
    }

    private fun isCurrentDebtReminderForCustomer(
        reminder: ParsedDebtReminder,
        debts: List<SaleDebt>,
        today: LocalDate,
        preDueDays: Int
    ): Boolean {
        val openCustomerDebts = debts.filter { it.remaining > 0.000001 }
        if (openCustomerDebts.isEmpty()) return false
        val customerDebts = openCustomerDebts.filter { it.dueDate.isNotBlank() }
        if (customerDebts.isEmpty()) return false

        val dueToday = customerDebts.filter { parseDate(it.dueDate) == today }
        val expectedDate: LocalDate
        val expectedRows: List<SaleDebt>
        if (dueToday.isNotEmpty()) {
            expectedDate = today
            expectedRows = dueToday
        } else {
            // Mirror the worker's precedence: nearest upcoming invoice in the configured
            // pre-due window first, otherwise the latest overdue maturity for catch-up.
            val nearestUpcoming = customerDebts.mapNotNull { parseDate(it.dueDate) }
                .filter { due ->
                    due.isAfter(today) &&
                        java.time.temporal.ChronoUnit.DAYS.between(today, due) <= preDueDays.toLong()
                }
                .minOrNull()
            if (nearestUpcoming != null) {
                expectedDate = nearestUpcoming
                expectedRows = customerDebts.filter { parseDate(it.dueDate) == nearestUpcoming }
            } else {
                val latestOverdue = customerDebts.mapNotNull { parseDate(it.dueDate) }
                    .filter { it.isBefore(today) }
                    .maxOrNull() ?: return false
                expectedDate = latestOverdue
                expectedRows = customerDebts.filter { parseDate(it.dueDate) == latestOverdue }
            }
        }

        if (expectedDate.toString() != reminder.dueDate) return false
        val currentInvoiceIds = expectedRows.map { it.transactionId }.distinct().sorted()
        if (currentInvoiceIds != reminder.invoiceIds) return false
        val daysRemaining = java.time.temporal.ChronoUnit.DAYS.between(today, expectedDate).toInt()
        when (reminder.stage) {
            "due" -> if (daysRemaining != 0) return false
            "pre" -> if (daysRemaining !in 1..preDueDays) return false
            "overdue" -> if (daysRemaining >= 0) return false
            // Keep already-queued legacy events valid only for their original due/overdue cases.
            null -> if (daysRemaining > 0) return false
        }
        if (expectedRows.sumOf { it.remaining } <= 0.000001) return false

        val currentPhone = PhoneUtils.normalize(expectedRows.first().phone) ?: return false
        if (PhoneUtils.normalize(reminder.message.recipient) != currentPhone) return false

        val outstanding = openCustomerDebts.sumOf { it.remaining }.coerceAtLeast(0.0)
        val dueAmount = expectedRows.sumOf { it.remaining }.coerceAtLeast(0.0)
        val outstandingText = formatReminderAmount(outstanding)
        val dueAmountText = formatReminderAmount(dueAmount)
        val body = reminder.message.body

        // Do not send stale maturity wording, remaining-day count or balances after a
        // payment, a calendar-day rollover or a changed reminder lead-window setting.
        return when {
            expectedDate == today ->
                body.contains("موعد استحقاق الدين هو اليوم") &&
                    body.contains("المبلغ المستحق اليوم $dueAmountText") &&
                    body.contains("إجمالي الرصيد القائم $outstandingText")
            expectedDate.isAfter(today) ->
                body.contains("يتبقى $daysRemaining يوم على موعد استحقاق الدين") &&
                    body.contains("المبلغ المستحق بتاريخ $expectedDate $dueAmountText") &&
                    body.contains("إجمالي الرصيد القائم $outstandingText")
            else ->
                body.contains("قد مضى منذ ${-daysRemaining} يوم") &&
                    body.contains("المبلغ المتأخر $dueAmountText") &&
                    body.contains("إجمالي الرصيد القائم $outstandingText")
        }
    }

    private fun formatReminderAmount(value: Double): String = if (value % 1.0 == 0.0) {
        value.toLong().toString()
    } else {
        String.format(Locale.US, "%,.2f", value)
    }

    fun getOverdueDebts(helper: DatabaseHelper, stationId: Int): JSONArray {
        val all = getCustomerDebts(helper, stationId)
        val out = JSONArray()
        for (i in 0 until all.length()) {
            val row = all.optJSONObject(i) ?: continue
            val status = row.optString("status")
            if (row.optDouble("remaining_amount", 0.0) > 0.000001 && status == "overdue") out.put(row)
        }
        return out
    }

    const val DEFAULT_PRE_DUE_REMINDER_DAYS = 3
    const val MAX_PRE_DUE_REMINDER_DAYS = 30

    /**
     * The lead window is read on each worker pass, so changes take effect without deleting
     * or rebuilding WorkManager's durable periodic request. Invalid legacy values fail safe
     * to the documented three-day default.
     */
    fun reminderLeadDays(helper: DatabaseHelper): Int =
        helper.getSetting("debt_reminder_days_before_due").trim().toIntOrNull()
            ?.takeIf { it in 1..MAX_PRE_DUE_REMINDER_DAYS }
            ?: DEFAULT_PRE_DUE_REMINDER_DAYS

    /** Public so the SMS worker and UI use exactly the same calendar-date arithmetic. */
    fun daysUntil(dueDate: String?, today: LocalDate = LocalDate.parse(BusinessDay.currentDate())): Int? {
        val due = parseDate(dueDate) ?: return null
        return java.time.temporal.ChronoUnit.DAYS.between(today, due).toInt()
    }

    // riskLevel remains in the signature for bridge/source compatibility. Credit
    // risk is intentionally independent from payment/maturity status.
    @Suppress("UNUSED_PARAMETER")
    fun statusFor(remaining:Double,paid:Double,dueDate:String?,riskLevel:String?=null,today:LocalDate=LocalDate.parse(BusinessDay.currentDate())):String {
        if(remaining<=0.000001) return "paid"
        val due=parseDate(dueDate) ?: return "undated"
        if(due.isBefore(today)) return "overdue"
        if(paid>0.000001) return "partial"
        return "current"
    }

    /** Calendar maturity dimension kept separate from the legacy mixed status field. */
    fun maturityStatusFor(
        remaining: Double,
        dueDate: String?,
        today: LocalDate = LocalDate.parse(BusinessDay.currentDate())
    ): String {
        if (remaining <= 0.000001) return "PAID"
        val due = parseDate(dueDate) ?: return "UNDATED"
        return when {
            due.isBefore(today) -> "OVERDUE"
            due == today -> "DUE_TODAY"
            else -> "NOT_DUE"
        }
    }


    private fun readDebts(
        db: SQLiteDatabase,
        stationId: Int,
        onlyCustomerIds: Set<Long>? = null
    ): List<SaleDebt> {
        if (!tableExists(db, "sales_transactions") || !tableExists(db, "parties")) return emptyList()
        if (onlyCustomerIds != null &&
            (onlyCustomerIds.isEmpty() || onlyCustomerIds.any { it <= 0L })
        ) return emptyList()
        val s = columns(db, "sales_transactions")
        val p = columns(db, "parties")
        val pc = columns(db, "party_contacts")
        if (!setOf("id", "station_id").all { it in s } || "id" !in p) return emptyList()
        val customerId = listOf("customer_party_id", "customer_id", "party_id").firstOrNull { it in s } ?: return emptyList()
        val payment = if ("payment_method" in s) "COALESCE(s.payment_method,'')" else "''"
        val totalExpr = when {
            "net_amount" in s -> "COALESCE(s.net_amount,0)"
            "total_amount" in s -> "COALESCE(s.total_amount,0)"
            "gross_amount" in s -> "COALESCE(s.gross_amount,0)"
            else -> "0"
        }
        val paidExpr = if ("paid_amount" in s) "COALESCE(s.paid_amount,0)" else "0"
        val remainingExpr = if ("remaining_amount" in s) "MAX(COALESCE(s.remaining_amount,0),0)" else "MAX(($totalExpr)-($paidExpr),0)"
        val dueExpr = if ("due_date" in s) "COALESCE(s.due_date,'')" else "''"
        val createdExpr = listOf("created_at", "transaction_date", "business_day").firstOrNull { it in s }?.let { "COALESCE(s.`$it`,'')" } ?: "''"
        val invoiceExpr = listOf("invoice_number", "invoice_code").firstOrNull { it in s }?.let { "COALESCE(s.`$it`,'')" } ?: "''"
        val saleCodeExpr = if ("sale_code" in s) "COALESCE(s.sale_code,'')" else "''"
        val orderExpr = listOf("order_type", "sale_type", "transaction_type").firstOrNull { it in s }?.let { "COALESCE(s.`$it`,'')" } ?: "''"
        val partyNameExpr = listOf("commercial_name_ar", "commercial_name", "legal_name", "party_name").firstOrNull { it in p }
            ?.let { "COALESCE(p.`$it`,'')" } ?: "''"
        val legacyPhoneExpr = listOf("phone", "phone_number", "mobile", "phone1").firstOrNull { it in p }
            ?.let { "COALESCE(p.`$it`,'')" }
        val contactPhoneExpr = if ("party_id" in pc && ("phone" in pc || "phone2" in pc)) {
            val primaryPhone = if ("phone" in pc) "NULLIF(TRIM(pc.phone),'')" else "NULL"
            val alternatePhone = if ("phone2" in pc) "NULLIF(TRIM(pc.phone2),'')" else "NULL"
            val active = if ("is_active" in pc) "COALESCE(pc.is_active,1)=1" else "1=1"
            val notDeleted = if ("is_deleted" in pc) "COALESCE(pc.is_deleted,0)=0" else "1=1"
            val primaryOrder = if ("is_primary" in pc) "COALESCE(pc.is_primary,0) DESC," else ""
            "(SELECT COALESCE($primaryPhone,$alternatePhone,'') FROM party_contacts pc " +
                "WHERE pc.party_id=p.id AND $active AND $notDeleted ORDER BY $primaryOrder pc.id ASC LIMIT 1)"
        } else null
        val phoneExpr = legacyPhoneExpr ?: contactPhoneExpr ?: "''"
        val currentTermsExpr = listOf("payment_terms", "payment_terms_text", "credit_terms").firstOrNull { it in p }?.let { "COALESCE(p.`$it`,'')" } ?: "''"
        val termsExpr = if ("payment_terms_at_sale" in s) "COALESCE(NULLIF(s.payment_terms_at_sale,''),$currentTermsExpr)" else currentTermsExpr
        val riskExpr = listOf("risk_level", "credit_risk", "risk_category").firstOrNull { it in p }?.let { "COALESCE(p.`$it`,'')" } ?: "''"
        val stationJoin = if ("station_id" in p) " AND p.station_id=s.station_id" else ""
        val partyDeleted = if ("is_deleted" in p) " AND COALESCE(p.is_deleted,0)=0" else ""
        val saleDeleted = if ("is_deleted" in s) " AND COALESCE(s.is_deleted,0)=0" else ""
        val saleStatus = if ("status" in s) " AND LOWER(COALESCE(s.status,'')) NOT IN ('cancelled','refunded','void','reversed')" else ""
        val termsSourceExpr = if ("payment_terms_source_at_sale" in s) "COALESCE(s.payment_terms_source_at_sale,'')" else "''"
        val baseCredit = "LOWER(REPLACE(REPLACE(TRIM($payment),' ',''),'-','_')) IN ('credit','credit_sale','credit_account','on_account','آجل','اجل','بيعآجل','علىالحساب')"
        val where = "$baseCredit$saleDeleted$saleStatus"
        // Keep the client predicate on the raw indexed column. Binding the value lets SQLite
        // use the station/customer composite index without applying a function to the key.
        val sortedCustomerIds = onlyCustomerIds?.sorted()
        val customerFilter = when {
            sortedCustomerIds == null -> ""
            sortedCustomerIds.size == 1 -> " AND s.`$customerId` = ?"
            else -> " AND s.`$customerId` IN (${List(sortedCustomerIds.size) { "?" }.joinToString(",")})"
        }
        // The station-wide API keeps its established result ordering; the targeted validation
        // path reads a bounded set of customers and performs cohort selection in Kotlin.
        val orderBy = if (sortedCustomerIds == null) {
            "ORDER BY CASE WHEN $dueExpr='' THEN 1 ELSE 0 END,$dueExpr,s.id"
        } else ""
        val sql = """
            SELECT s.id,s.station_id,COALESCE(s.$customerId,0),$partyNameExpr,$phoneExpr,
                   $invoiceExpr,$saleCodeExpr,$orderExpr,$payment,$dueExpr,$createdExpr,
                   $totalExpr,$paidExpr,$remainingExpr,$riskExpr,$termsExpr,
                   COALESCE(s.due_date_source,''),$termsSourceExpr
            FROM sales_transactions s
            LEFT JOIN parties p ON p.id=s.$customerId$stationJoin$partyDeleted
            WHERE s.station_id=? AND $where$customerFilter
            $orderBy
        """.trimIndent()
        val rows = ArrayList<SaleDebt>()
        val selectionArgs = buildList {
            add(stationId.toString())
            sortedCustomerIds?.forEach { add(it.toString()) }
        }.toTypedArray()
        db.rawQuery(sql, selectionArgs).use { cursor ->
            while (cursor.moveToNext()) {
                val due = cursor.getString(9).orEmpty().take(10)
                val created = cursor.getString(10).orEmpty()
                val total = cursor.getDouble(11).coerceAtLeast(0.0)
                val paid = cursor.getDouble(12).coerceAtLeast(0.0)
                val remaining = cursor.getDouble(13).coerceAtLeast(0.0)
                // Only source-verified maturity values are eligible for aging and reminders.
                // Legacy values without provenance are retained in SQLite but classified as UNDATED.
                val dueSource = if (cursor.columnCount > 16) cursor.getString(16).orEmpty() else ""
                val termsSource = if (cursor.columnCount > 17) cursor.getString(17).orEmpty() else ""
                val effectiveDue = if (isTrustedDueDate(termsSource, dueSource)) due else ""
                val customerIdValue = cursor.getLong(2)
                val name = cursor.getString(3).orEmpty().trim().ifBlank { if (customerIdValue > 0L) "عميل #$customerIdValue" else "عميل غير مسجل" }
                val row = SaleDebt(
                    transactionId = cursor.getLong(0),
                    stationId = cursor.getInt(1),
                    customerId = customerIdValue,
                    customerName = name,
                    phone = cursor.getString(4).orEmpty(),
                    invoiceNumber = cursor.getString(5).orEmpty(),
                    saleCode = cursor.getString(6).orEmpty(),
                    orderType = cursor.getString(7).orEmpty(),
                    paymentMethod = cursor.getString(8).orEmpty(),
                    dueDate = effectiveDue,
                    createdAt = created,
                    total = total,
                    paid = paid,
                    remaining = remaining,
                    riskLevel = cursor.getString(14).orEmpty(),
                    terms = cursor.getString(15).orEmpty()
                )
                rows += row
            }
        }
        return rows
    }

    private fun toJson(row: SaleDebt, today: LocalDate = LocalDate.parse(BusinessDay.currentDate())): JSONObject {
        val days = daysUntil(row.dueDate, today)
        val daysOverdue = days?.let { (-it).coerceAtLeast(0) } ?: 0
        val status = statusFor(row.remaining, row.paid, row.dueDate, row.riskLevel, today)
        val isCredit = row.paymentMethod.trim().lowercase(Locale.ROOT) in creditMethods
        val overdueAmount = if (status == "overdue" || status == "doubtful") row.remaining else 0.0
        return JSONObject().apply {
            put("id", row.transactionId)
            put("transaction_id", row.transactionId)
            put("sale_id", row.transactionId)
            put("customer_party_id", row.customerId)
            put("customer_id", row.customerId)
            put("party_id", row.customerId)
            put("customer_name", row.customerName)
            put("commercial_name", row.customerName)
            put("commercial_name_ar", row.customerName)
            put("customer_phone", row.phone)
            put("phone", row.phone)
            put("invoice_number", row.invoiceNumber.ifBlank { "—" })
            put("sale_code", row.saleCode)
            put("station_id", row.stationId)
            put("order_type", row.orderType)
            put("payment_method", row.paymentMethod)
            put("is_credit", isCredit)
            put("due_date", row.dueDate)
            put("created_at", row.createdAt)
            put("net_amount", row.total)
            put("gross_amount", row.total)
            put("total_amount", row.total)
            put("total_debt", row.total)
            put("paid_amount", row.paid)
            put("remaining_amount", row.remaining)
            put("total_due", row.remaining)
            put("overdue_amount", overdueAmount)
            put("payment_status", status)
            put("status", status)
            put("maturity_status", maturityStatusFor(row.remaining, row.dueDate, today))
            put("days_remaining", days ?: JSONObject.NULL)
            put("days_overdue", daysOverdue)
            put("risk_level", row.riskLevel)
            put("risk_classification", if (status == "doubtful") "doubtful" else if (status == "overdue") "overdue" else "normal")
            put("payment_terms_at_sale", row.terms)
        }
    }

    private fun triggerExists(db: SQLiteDatabase, name: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='trigger' AND name=? LIMIT 1", arrayOf(name)).use { it.moveToFirst() }

    private fun createDueDateTriggers(db:SQLiteDatabase) {
        val sc=columns(db,"sales_transactions")
        val pc=columns(db,"parties")
        val customer=listOf("customer_party_id","customer_id","party_id").firstOrNull { it in sc } ?: return
        if("payment_method" !in sc || "due_date" !in sc) return
        val bases=listOf("transaction_date","business_day","sale_date").filter { it in sc }
        if(bases.isEmpty()) return
        val basisParts=bases.map { "NULLIF(substr(NEW.`"+it+"`,1,10),'')" }
        val basis=if(basisParts.size==1) basisParts[0] else "COALESCE("+basisParts.joinToString(",")+")"
        val scope=if("station_id" in pc && "station_id" in sc) " AND p.station_id=NEW.station_id" else ""
        val live=if("is_deleted" in pc) " AND COALESCE(p.is_deleted,0)=0" else ""
        val party="p.id=NEW.`"+customer+"`"+scope+live
        val terms=if("payment_terms" in pc) "COALESCE((SELECT NULLIF(TRIM(p.payment_terms),'') FROM parties p WHERE "+party+" LIMIT 1),'')" else "''"
        val termsSource=if("payment_terms_source" in pc) "(SELECT NULLIF(TRIM(p.payment_terms_source),'') FROM parties p WHERE "+party+" LIMIT 1)" else "NULL"
        val partyDays=if("payment_terms_days" in pc) "(SELECT p.payment_terms_days FROM parties p WHERE "+party+" LIMIT 1)" else "NULL"
        val days="CASE WHEN COALESCE(NEW.payment_terms_source_at_sale,$termsSource)='explicit_text' THEN COALESCE(NEW.payment_terms_days_at_sale,$partyDays) ELSE NULL END"
        val termId=if("payment_terms_id" in pc) "(SELECT p.payment_terms_id FROM parties p WHERE "+party+" LIMIT 1)" else "NULL"
        val credit="LOWER(REPLACE(REPLACE(TRIM(COALESCE(NEW.payment_method,'')),' ',''),'-','_')) IN ('credit','credit_sale','credit_account','on_account','آجل','اجل','بيعآجل','علىالحساب')"
        val remaining=if("remaining_amount" in sc) " AND COALESCE(NEW.remaining_amount,0)>0.000001" else ""
        val sql="UPDATE sales_transactions SET "+
            "payment_terms_source_at_sale=COALESCE(NULLIF(payment_terms_source_at_sale,''),"+termsSource+"),"+
            "payment_terms_id_at_sale=CASE WHEN COALESCE(NEW.payment_terms_source_at_sale,"+termsSource+")='explicit_text' THEN COALESCE(payment_terms_id_at_sale,"+termId+") ELSE NULL END,"+
            "payment_terms_at_sale=COALESCE(NULLIF(payment_terms_at_sale,''),"+terms+"),"+
            "payment_terms_days_at_sale="+days+","+
            "due_date=CASE WHEN "+basis+" IS NOT NULL AND ("+days+") IS NOT NULL THEN date("+basis+", '+' || ("+days+") || ' days') ELSE NULL END,"+
            "due_date_basis="+basis+","+
            "due_date_source=CASE WHEN "+basis+" IS NULL THEN 'missing_base_date' WHEN ("+days+") IS NULL THEN 'missing_terms' ELSE 'sales_trigger_v4' END "+
            "WHERE id=NEW.id AND (due_date IS NULL OR TRIM(due_date)='')"
        val condition="WHEN "+credit+remaining+" AND (NEW.due_date IS NULL OR TRIM(NEW.due_date)='')"
        db.execSQL("DROP TRIGGER IF EXISTS trg_debt_due_date_sales_insert_v2")
        db.execSQL("DROP TRIGGER IF EXISTS trg_debt_due_date_sales_update_v2")
        db.execSQL("DROP TRIGGER IF EXISTS trg_debt_due_date_sales_insert_v3")
        db.execSQL("DROP TRIGGER IF EXISTS trg_debt_due_date_sales_update_v3")
        db.execSQL("""
            CREATE TRIGGER trg_debt_due_date_sales_insert_v4 AFTER INSERT ON sales_transactions
            $condition
            BEGIN
                $sql;
            END
        """.trimIndent())
        val updates=listOf("payment_method",customer,"remaining_amount","business_day","transaction_date","sale_date")
            .filter { it in sc }.distinct().joinToString(",")
        if(updates.isNotBlank()) db.execSQL("""
            CREATE TRIGGER trg_debt_due_date_sales_update_v4 AFTER UPDATE OF $updates ON sales_transactions
            $condition
            BEGIN
                $sql;
            END
        """.trimIndent())
    }


    // Historical due-date reconstruction is intentionally not implemented in this phase.
    // Add it only as a separately reviewed, explicitly authorized migration in a later phase.
    fun parseTermsResult(terms: String?): TermsParseResult {
        val normalized=terms.orEmpty().map { ch ->
            when(ch) {
                '٠' -> '0';'١' -> '1';'٢' -> '2';'٣' -> '3';'٤' -> '4'
                '٥' -> '5';'٦' -> '6';'٧' -> '7';'٨' -> '8';'٩' -> '9'
                else -> ch
            }
        }.joinToString("").trim().lowercase(Locale.ROOT)
        if(normalized.isBlank()) return TermsParseResult.Unknown("empty_terms")
        if(normalized in setOf("cash","نقد","نقدي","نقداً","فوري","فوراً","عند الاستلام","upon receipt","immediate")) return TermsParseResult.Known(0)
        if(normalized in setOf("بعد يوم", "بعد يوم واحد", "after 1 day", "after one day")) return TermsParseResult.Known(1)
        val explicit=Regex("""(?:net|صافي|خلال|within|بعد|after)\s*(\d{1,3})(?:\s*(?:days?|يوم(?:اً|ًا|ين)?|أيام))?""")
            .findAll(normalized).mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.toList()
        val exactDays=Regex("""^(\d{1,3})\s*(?:days?|يوم(?:اً|ًا|ين)?|أيام)$""")
            .matchEntire(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val exactNumber=if(Regex("""^\d{1,3}$""").matches(normalized)) normalized.toIntOrNull() else null
        val days=when {
            explicit.size==1 -> explicit.single()
            explicit.size>1 -> return TermsParseResult.Unknown("conflicting_explicit_terms", normalized)
            exactDays!=null -> exactDays
            exactNumber!=null -> exactNumber
            else -> return TermsParseResult.Unknown("unsupported_terms_format", normalized)
        }
        return if (days in 0..365) TermsParseResult.Known(days) else TermsParseResult.Unknown("days_out_of_range", normalized)
    }

    /** Compatibility adapter; callers that need provenance must use parseTermsResult. */
    fun parseTermsDays(terms:String?):Int? = when (val result = parseTermsResult(terms)) {
        is TermsParseResult.Known -> result.days
        is TermsParseResult.Unknown -> null
    }

    private fun parseDate(value: String?): LocalDate? = IsoCivilDate.parse(value)

    private fun hasOutboxDedupeKey(db: SQLiteDatabase, dedupeKey: String): Boolean {
        if (!tableExists(db, "sms_outbox") || "dedupe_key" !in columns(db, "sms_outbox")) return false
        return runCatching {
            db.rawQuery(
                """SELECT 1 FROM sms_outbox
                   WHERE (dedupe_key=? OR dedupe_key LIKE ?)
                     AND UPPER(COALESCE(status,'')) NOT IN ('CANCELLED','CANCELED','FAILED') LIMIT 1""",
                arrayOf(dedupeKey, "$dedupeKey:retry:%")
            ).use { it.moveToFirst() }
        }.getOrDefault(false)
    }

    private fun hasSentDebtReminder(db: SQLiteDatabase, eventId: String, recipient: String): Boolean {
        val outboxColumns = columns(db, "sms_outbox")
        if (!setOf("event_id", "recipient", "status").all { it in outboxColumns }) return false
        return runCatching {
            db.rawQuery(
                """SELECT 1 FROM sms_outbox
                   WHERE event_id=? AND recipient=?
                     AND UPPER(status) IN ('SENT','DELIVERY_PENDING','DELIVERED')
                   LIMIT 1""",
                arrayOf(eventId, recipient)
            ).use { it.moveToFirst() }
        }.getOrDefault(false)
    }

    private fun columns(db: SQLiteDatabase, table: String): Set<String> {
        if (!tableExists(db, table)) return emptySet()
        return db.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            val found = LinkedHashSet<String>()
            while (cursor.moveToNext()) found.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            found
        }
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(table)).use { it.moveToFirst() }

    private fun addColumn(db: SQLiteDatabase, table: String, name: String, definition: String) {
        if (name in columns(db, table)) return
        db.execSQL("ALTER TABLE `$table` ADD COLUMN `$name` $definition")
    }
}
