package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.utils.PhoneUtils
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeParseException
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
    private const val DEFAULT_TERMS = "صافي 30 يوم"
    private const val DEFAULT_TERMS_DAYS = 30
    private val creditMethods = setOf("credit", "credit_sale", "credit_account", "on_account", "آجل", "اجل", "بيع آجل", "على الحساب")

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

    fun ensureSchema(helper: DatabaseHelper) {
        val db = helper.writableDatabase
        if (!tableExists(db, "sales_transactions")) return
        if (tableExists(db, "parties")) {
            addColumn(db, "parties", "payment_terms", "TEXT NOT NULL DEFAULT '$DEFAULT_TERMS'")
        }
        addColumn(db, "sales_transactions", "due_date", "TEXT")
        addColumn(db, "sales_transactions", "payment_terms_at_sale", "TEXT")
        if (!triggerExists(db, "trg_debt_due_date_sales_insert_v2") ||
            !triggerExists(db, "trg_debt_due_date_sales_update_v2")
        ) createDueDateTriggers(db)
        db.execSQL("CREATE TABLE IF NOT EXISTS debt_due_date_migrations (migration_key TEXT PRIMARY KEY, applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)")
        val backfillApplied = db.rawQuery(
            "SELECT 1 FROM debt_due_date_migrations WHERE migration_key='credit-sale-due-date-v2' LIMIT 1", null
        ).use { it.moveToFirst() }
        if (!backfillApplied) {
            backfillMissingDueDates(db)
            db.execSQL("INSERT OR IGNORE INTO debt_due_date_migrations(migration_key) VALUES('credit-sale-due-date-v2')")
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_debt_due_dates_station_date ON sales_transactions(station_id,due_date)")
    }

    /** Store the customer terms explicitly; blank terms always mean Net 30, not "no due date". */
    fun persistPartyTerms(helper: DatabaseHelper, partyId: Long, stationId: Int, requestedTerms: String?): String {
        val db = helper.writableDatabase
        require(partyId > 0L && stationId > 0) { "معرف العميل أو المحطة غير صالح" }
        require(tableExists(db, "parties")) { "جدول العملاء غير متاح" }
        val cols = columns(db, "parties")
        val termsColumn = listOf("payment_terms", "payment_terms_text", "credit_terms").firstOrNull { it in cols }
            ?: return requestedTerms.orEmpty().trim().ifBlank { DEFAULT_TERMS }
        val terms = requestedTerms.orEmpty().trim().ifBlank { DEFAULT_TERMS }
        val stationClause = if ("station_id" in cols) " AND station_id=?" else ""
        val args = if ("station_id" in cols) arrayOf(partyId.toString(), stationId.toString()) else arrayOf(partyId.toString())
        val values = ContentValues().apply { put(termsColumn, terms) }
        if ("updated_at" in cols) values.put("updated_at", java.time.LocalDateTime.now().toString())
        val changed = db.update("parties", values, "id=?$stationClause", args)
        check(changed == 1) { "لم يتم حفظ شروط الدفع للعميل المحدد في المحطة الحالية" }
        return terms
    }

    fun getCustomerDebts(
        helper: DatabaseHelper,
        stationId: Int,
        fromDate: String? = null,
        toDate: String? = null
    ): JSONArray {
        ensureSchema(helper)
        val rows = readDebts(helper.readableDatabase, stationId)
        val output = JSONArray()
        rows.filter { row ->
            val saleDate = row.createdAt.take(10)
            (fromDate.isNullOrBlank() || saleDate.isBlank() || saleDate >= fromDate.take(10)) &&
                (toDate.isNullOrBlank() || saleDate.isBlank() || saleDate <= toDate.take(10))
        }.forEach { output.put(toJson(it)) }
        return output
    }

    /**
     * Returns one nearest-due invoice per customer for the requested calendar-day window.
     * days=0 means due today; negative/overdue balances are not automatically re-reminded.
     */
    fun getDueSoonDebtReminders(helper: DatabaseHelper, stationId: Int, days: Int): JSONArray {
        ensureSchema(helper)
        require(stationId > 0) { "معرف المحطة غير صالح" }
        val maxDays = days.coerceIn(0, 365)
        val today = LocalDate.now()
        val all = readDebts(helper.readableDatabase, stationId)
        val openByCustomer = all.filter { it.remaining > 0.000001 && it.customerId > 0L }
            .groupBy { it.customerId }
        val candidates = all.filter { debt ->
            if (debt.remaining <= 0.000001 || debt.customerId <= 0L || debt.dueDate.isBlank()) return@filter false
            val due = parseDate(debt.dueDate) ?: return@filter false
            val delta = java.time.temporal.ChronoUnit.DAYS.between(today, due)
            delta in 0..maxDays
        }.groupBy { it.customerId }
        val result = JSONArray()
        candidates.forEach { (customerId, dueRows) ->
            val nearest = dueRows.minWithOrNull(compareBy<SaleDebt>({ parseDate(it.dueDate) ?: LocalDate.MAX }, { it.transactionId }))
                ?: return@forEach
            val due = parseDate(nearest.dueDate) ?: return@forEach
            val daysRemaining = java.time.temporal.ChronoUnit.DAYS.between(today, due).toInt()
            val customerRows = openByCustomer[customerId].orEmpty()
            val outstanding = customerRows.sumOf { it.remaining }.coerceAtLeast(0.0)
            val dueDateKey = nearest.dueDate.take(10)
            val dueAmount = dueRows.filter { it.dueDate.take(10) == dueDateKey }.sumOf { it.remaining }.coerceAtLeast(0.0)
            val phone = PhoneUtils.normalize(nearest.phone)
            val eventId = "debt-reminder:$stationId:$customerId:${nearest.dueDate.take(10)}"
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
                put("nearest_due_date", nearest.dueDate.take(10))
                put("due_date", nearest.dueDate.take(10))
                put("days_remaining", daysRemaining)
                put("due_soon_amount", dueAmount)
                put("total_outstanding", outstanding)
                put("remaining_amount", nearest.remaining)
                put("last_reminder_at", if (alreadyQueued) nearest.dueDate.take(10) else "")
                put("eligible", eligible)
                put("station_id", stationId)
                put("event_id", eventId)
                put("risk_level", nearest.riskLevel)
            })
        }
        return result
    }

    fun getOverdueDebts(helper: DatabaseHelper, stationId: Int): JSONArray {
        val all = getCustomerDebts(helper, stationId)
        val out = JSONArray()
        for (i in 0 until all.length()) {
            val row = all.optJSONObject(i) ?: continue
            val status = row.optString("status")
            if (row.optDouble("remaining_amount", 0.0) > 0.000001 && status in setOf("overdue", "doubtful")) out.put(row)
        }
        return out
    }

    /** Public so the SMS worker and UI use exactly the same calendar-date arithmetic. */
    fun daysUntil(dueDate: String?, today: LocalDate = LocalDate.now()): Int? {
        val due = parseDate(dueDate) ?: return null
        return java.time.temporal.ChronoUnit.DAYS.between(today, due).toInt()
    }

    fun statusFor(remaining: Double, paid: Double, dueDate: String?, riskLevel: String? = null, today: LocalDate = LocalDate.now()): String {
        if (remaining <= 0.000001) return "paid"
        val due = parseDate(dueDate)
        val daysUntil = due?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it).toInt() }
        val risk = riskLevel.orEmpty().trim().lowercase(Locale.ROOT)
        if ((daysUntil != null && daysUntil <= -90) ||
            risk in setOf("high", "critical", "blacklisted", "مشدد", "مرتفع", "محظور") ||
            risk.contains("high") || risk.contains("blacklist") || risk.contains("مرتفع") || risk.contains("محظور")
        ) return "doubtful"
        if (daysUntil != null && daysUntil < 0) return "overdue"
        if (paid > 0.000001) return "partial"
        return "current"
    }

    private fun readDebts(db: SQLiteDatabase, stationId: Int): List<SaleDebt> {
        if (!tableExists(db, "sales_transactions") || !tableExists(db, "parties")) return emptyList()
        val s = columns(db, "sales_transactions")
        val p = columns(db, "parties")
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
        val phoneExpr = listOf("phone", "phone_number", "mobile", "phone1").firstOrNull { it in p }?.let { "COALESCE(p.`$it`,'')" } ?: "''"
        val currentTermsExpr = listOf("payment_terms", "payment_terms_text", "credit_terms").firstOrNull { it in p }?.let { "COALESCE(p.`$it`,'')" } ?: "'$DEFAULT_TERMS'"
        val termsExpr = if ("payment_terms_at_sale" in s) "COALESCE(NULLIF(s.payment_terms_at_sale,''),$currentTermsExpr,'$DEFAULT_TERMS')" else currentTermsExpr
        val riskExpr = listOf("risk_level", "credit_risk", "risk_category").firstOrNull { it in p }?.let { "COALESCE(p.`$it`,'')" } ?: "''"
        val stationJoin = if ("station_id" in p) " AND p.station_id=s.station_id" else ""
        val partyDeleted = if ("is_deleted" in p) " AND COALESCE(p.is_deleted,0)=0" else ""
        val saleDeleted = if ("is_deleted" in s) " AND COALESCE(s.is_deleted,0)=0" else ""
        val baseCredit = "LOWER(REPLACE(REPLACE(TRIM($payment),' ',''),'-','_')) IN ('credit','credit_sale','credit_account','on_account','آجل','اجل','بيعآجل','علىالحساب')"
        val where = "$baseCredit$saleDeleted"
        val sql = """
            SELECT s.id,s.station_id,COALESCE(s.$customerId,0),$partyNameExpr,$phoneExpr,
                   $invoiceExpr,$saleCodeExpr,$orderExpr,$payment,$dueExpr,$createdExpr,
                   $totalExpr,$paidExpr,$remainingExpr,$riskExpr,$termsExpr
            FROM sales_transactions s
            LEFT JOIN parties p ON p.id=s.$customerId$stationJoin$partyDeleted
            WHERE s.station_id=? AND $where
            ORDER BY CASE WHEN $dueExpr='' THEN 1 ELSE 0 END,$dueExpr,s.id
        """.trimIndent()
        val rows = ArrayList<SaleDebt>()
        db.rawQuery(sql, arrayOf(stationId.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val due = cursor.getString(9).orEmpty().take(10)
                val created = cursor.getString(10).orEmpty()
                val total = cursor.getDouble(11).coerceAtLeast(0.0)
                val paid = cursor.getDouble(12).coerceAtLeast(0.0)
                val remaining = cursor.getDouble(13).coerceAtLeast(0.0)
                // Guard legacy rows at read time too; the migration/trigger should persist this,
                // but one malformed historical row must never silently be treated as paid.
                val effectiveDue = due.ifBlank {
                    val terms = cursor.getString(15).orEmpty().ifBlank { DEFAULT_TERMS }
                    dueDateForTerms(terms, created)
                }
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

    private fun toJson(row: SaleDebt): JSONObject {
        val days = daysUntil(row.dueDate)
        val daysOverdue = days?.let { (-it).coerceAtLeast(0) } ?: 0
        val status = statusFor(row.remaining, row.paid, row.dueDate, row.riskLevel)
        val isCredit = row.paymentMethod.trim().lowercase(Locale.ROOT) in creditMethods
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
            put("payment_status", status)
            put("status", status)
            put("days_remaining", days ?: JSONObject.NULL)
            put("days_overdue", daysOverdue)
            put("risk_level", row.riskLevel)
            put("risk_classification", if (status == "doubtful") "doubtful" else if (status == "overdue") "overdue" else "normal")
            put("payment_terms_at_sale", row.terms)
        }
    }

    private fun triggerExists(db: SQLiteDatabase, name: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='trigger' AND name=? LIMIT 1", arrayOf(name)).use { it.moveToFirst() }

    private fun createDueDateTriggers(db: SQLiteDatabase) {
        val s = columns(db, "sales_transactions")
        val p = columns(db, "parties")
        val customerColumn = listOf("customer_party_id", "customer_id", "party_id").firstOrNull { it in s } ?: return
        if ("payment_method" !in s || "due_date" !in s) return
        val baseParts = listOf("business_day", "transaction_date", "sale_date", "created_at")
            .filter { it in s }
            .map { "NULLIF(substr(NEW.`$it`,1,10),'')" }
            .toMutableList()
        baseParts += "date('now','localtime')"
        val baseDate = "COALESCE(${baseParts.joinToString(",")})"
        val stationScope = if ("station_id" in p && "station_id" in s) " AND p.station_id=NEW.station_id" else ""
        val notDeleted = if ("is_deleted" in p) " AND COALESCE(p.is_deleted,0)=0" else ""
        val termsColumn = listOf("payment_terms", "payment_terms_text", "credit_terms").firstOrNull { it in p }
        val terms = if (termsColumn != null) {
            "COALESCE((SELECT NULLIF(TRIM(p.`$termsColumn`),'') FROM parties p WHERE p.id=NEW.`$customerColumn`$stationScope$notDeleted LIMIT 1),'$DEFAULT_TERMS')"
        } else "'$DEFAULT_TERMS'"
        val days = sqlDaysFromTerms(terms)
        val creditCondition = "LOWER(REPLACE(REPLACE(TRIM(COALESCE(NEW.payment_method,'')),' ',''),'-','_')) IN ('credit','credit_sale','credit_account','on_account','آجل','اجل','بيعآجل','علىالحساب')"
        val remainingCondition = if ("remaining_amount" in s) " AND COALESCE(NEW.remaining_amount,0)>0" else ""
        val termsSnapshot = if ("payment_terms_at_sale" in s) ", payment_terms_at_sale=COALESCE(NULLIF(payment_terms_at_sale,''),$terms)" else ""
        val update = "UPDATE sales_transactions SET due_date=date($baseDate, '+' || ($days) || ' days')$termsSnapshot WHERE id=NEW.id AND (due_date IS NULL OR TRIM(due_date)='')"
        val triggerWhen = "WHEN $creditCondition$remainingCondition AND (NEW.due_date IS NULL OR TRIM(NEW.due_date)='')"
        db.execSQL("DROP TRIGGER IF EXISTS trg_debt_due_date_sales_insert_v2")
        db.execSQL("DROP TRIGGER IF EXISTS trg_debt_due_date_sales_update_v2")
        db.execSQL("""
            CREATE TRIGGER trg_debt_due_date_sales_insert_v2
            AFTER INSERT ON sales_transactions
            $triggerWhen
            BEGIN
                $update;
            END
        """.trimIndent())
        val updateColumns = listOf("payment_method", customerColumn, "remaining_amount")
            .filter { it in s }.distinct().joinToString(",")
        if (updateColumns.isNotBlank()) {
            db.execSQL("""
                CREATE TRIGGER trg_debt_due_date_sales_update_v2
                AFTER UPDATE OF $updateColumns ON sales_transactions
                $triggerWhen
                BEGIN
                    $update;
                END
            """.trimIndent())
        }
    }

    private fun backfillMissingDueDates(db: SQLiteDatabase) {
        val s = columns(db, "sales_transactions")
        val p = columns(db, "parties")
        val customerColumn = listOf("customer_party_id", "customer_id", "party_id").firstOrNull { it in s } ?: return
        if ("payment_method" !in s || "due_date" !in s || !tableExists(db, "parties")) return
        val baseParts = listOf("business_day", "transaction_date", "sale_date", "created_at")
            .filter { it in s }
            .map { "NULLIF(substr(`$it`,1,10),'')" }
            .toMutableList()
        baseParts += "date('now','localtime')"
        val baseDate = "COALESCE(${baseParts.joinToString(",")})"
        val stationScope = if ("station_id" in p && "station_id" in s) " AND p.station_id=sales_transactions.station_id" else ""
        val notDeleted = if ("is_deleted" in p) " AND COALESCE(p.is_deleted,0)=0" else ""
        val termsColumn = listOf("payment_terms", "payment_terms_text", "credit_terms").firstOrNull { it in p }
        val terms = if (termsColumn != null) {
            "COALESCE((SELECT NULLIF(TRIM(p.`$termsColumn`),'') FROM parties p WHERE p.id=sales_transactions.`$customerColumn`$stationScope$notDeleted LIMIT 1),'$DEFAULT_TERMS')"
        } else "'$DEFAULT_TERMS'"
        val days = sqlDaysFromTerms(terms)
        val creditCondition = "LOWER(REPLACE(REPLACE(TRIM(COALESCE(payment_method,'')),' ',''),'-','_')) IN ('credit','credit_sale','credit_account','on_account','آجل','اجل','بيعآجل','علىالحساب')"
        val remainingCondition = if ("remaining_amount" in s) " AND COALESCE(remaining_amount,0)>0" else ""
        val snap = if ("payment_terms_at_sale" in s) ", payment_terms_at_sale=COALESCE(NULLIF(payment_terms_at_sale,''),$terms)" else ""
        db.execSQL("""
            UPDATE sales_transactions
            SET due_date=date($baseDate, '+' || ($days) || ' days')$snap
            WHERE (due_date IS NULL OR TRIM(due_date)='')
              AND $creditCondition$remainingCondition
        """.trimIndent())
    }

    private fun sqlDaysFromTerms(termsExpression: String): String {
        // Descending order prevents a short token such as "5" from matching "15" first.
        val parts = StringBuilder("CASE ")
        parts.append("WHEN lower($termsExpression) LIKE '%cash%' OR lower($termsExpression) LIKE '%immediate%' OR lower($termsExpression) LIKE '%upon receipt%' OR lower($termsExpression) LIKE '%عند الاستلام%' OR lower($termsExpression) LIKE '%نقد%' OR lower($termsExpression) LIKE '%فوري%' THEN 0 ")
        for (day in 365 downTo 1) {
            parts.append("WHEN lower($termsExpression) LIKE '%$day%' THEN $day ")
        }
        parts.append("ELSE $DEFAULT_TERMS_DAYS END")
        return parts.toString()
    }

    private fun dueDateForTerms(terms: String, baseDate: String): String {
        val days = parseTermsDays(terms)
        val base = parseDate(baseDate) ?: LocalDate.now()
        return base.plusDays(days.toLong()).toString()
    }

    private fun parseTermsDays(terms: String?): Int {
        val text = terms.orEmpty().trim().lowercase(Locale.ROOT)
        if (text.isBlank()) return DEFAULT_TERMS_DAYS
        if (listOf("cash", "immediate", "upon receipt", "عند الاستلام", "نقد", "فوري").any { text.contains(it) }) return 0
        val number = Regex("(?<!\\d)(\\d{1,3})(?!\\d)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return number?.coerceIn(0, 365) ?: DEFAULT_TERMS_DAYS
    }

    private fun parseDate(value: String?): LocalDate? {
        val text = value.orEmpty().trim()
        if (text.length < 10) return null
        return try {
            LocalDate.parse(text.substring(0, 10))
        } catch (_: DateTimeParseException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun hasOutboxDedupeKey(db: SQLiteDatabase, dedupeKey: String): Boolean {
        if (!tableExists(db, "sms_outbox") || "dedupe_key" !in columns(db, "sms_outbox")) return false
        return runCatching {
            db.rawQuery("SELECT 1 FROM sms_outbox WHERE dedupe_key=? LIMIT 1", arrayOf(dedupeKey)).use { it.moveToFirst() }
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
