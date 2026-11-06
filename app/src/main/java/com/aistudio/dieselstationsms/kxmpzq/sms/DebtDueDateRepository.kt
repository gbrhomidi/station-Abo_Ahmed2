package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.BusinessDay
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
    private val creditMethods = setOf("credit", "credit_sale", "credit_account", "on_account", "آجل", "اجل", "بيع آجل", "على الحساب")
    private val trustedDueDateSources = setOf("sale_creation", "sales_trigger_v3", "sales_trigger_v4", "legacy_rebuilt_snapshot")

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
        val invoiceIds: List<Long>
    )

    fun ensureSchema(helper: DatabaseHelper) {
        val db=helper.writableDatabase
        if(!tableExists(db,"sales_transactions")) return
        if(tableExists(db,"parties")) {
            addColumn(db,"parties","payment_terms","TEXT")
            addColumn(db,"parties","payment_terms_id","INTEGER")
            addColumn(db,"parties","payment_terms_days","INTEGER")
            addColumn(db,"parties","payment_terms_source","TEXT")
        }
        addColumn(db,"sales_transactions","due_date","TEXT")
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
                applies_to TEXT NOT NULL DEFAULT 'both',
                station_id INTEGER,
                is_default INTEGER NOT NULL DEFAULT 0,
                is_active INTEGER NOT NULL DEFAULT 1,
                sort_order INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
        """.trimIndent())
        seedDefaultPaymentTerms(db)
        db.execSQL("CREATE TABLE IF NOT EXISTS debt_due_date_migrations (migration_key TEXT PRIMARY KEY, applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)")
        if(!triggerExists(db,"trg_debt_due_date_sales_insert_v4") ||
           !triggerExists(db,"trg_debt_due_date_sales_update_v4")) createDueDateTriggers(db)

        val applied=db.rawQuery(
            "SELECT 1 FROM debt_due_date_migrations WHERE migration_key='credit-sale-due-date-v3' LIMIT 1",
            null
        ).use { it.moveToFirst() }
        if(!applied) {
            db.beginTransaction()
            try {
                backfillMissingDueDates(db)
                db.execSQL("INSERT INTO debt_due_date_migrations(migration_key) VALUES('credit-sale-due-date-v3')")
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_debt_due_dates_station_date ON sales_transactions(station_id,due_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_payment_terms_scope_active ON payment_terms(station_id,applies_to,is_active,sort_order)")
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
            PaymentTermSeed("NET_7","صافي 7 أيام","Net 7 days",7,10),
            PaymentTermSeed("NET_15","صافي 15 يوماً","Net 15 days",15,20),
            PaymentTermSeed("NET_30","صافي 30 يوم","Net 30 days",30,30),
            PaymentTermSeed("NET_45","صافي 45 يوماً","Net 45 days",45,40),
            PaymentTermSeed("NET_60","صافي 60 يوماً","Net 60 days",60,50),
            PaymentTermSeed("NET_90","صافي 90 يوماً","Net 90 days",90,60)
        )
        items.forEach { item ->
            db.execSQL(
                """INSERT OR IGNORE INTO payment_terms
                   (code,name_ar,name_en,days,applies_to,station_id,is_default,is_active,sort_order)
                   VALUES(?,?,?,?, 'both', NULL, ?, 1, ?)""",
                arrayOf(item.code,item.nameAr,item.nameEn,item.days,if(item.code=="NET_30") 1 else 0,item.sortOrder)
            )
        }
    }

    private fun findPaymentTermId(db: SQLiteDatabase, days: Int, stationId: Int?): Long? =
        db.rawQuery(
            """SELECT id FROM payment_terms WHERE days=? AND is_active=1
               AND applies_to IN ('both','customer','supplier')
               AND (station_id IS NULL OR station_id=?)
               ORDER BY CASE WHEN station_id IS NULL THEN 0 ELSE 1 END,sort_order,id LIMIT 1""",
            arrayOf(days.toString(),(stationId?:0).toString())
        ).use { c -> if(c.moveToFirst()) c.getLong(0) else null }

    fun persistPartyTerms(helper: DatabaseHelper,partyId:Long,stationId:Int,requestedTerms:String?):String {
        ensureSchema(helper)
        require(partyId>0L && stationId>0) { "معرف الطرف أو المحطة غير صالح" }
        val terms=requestedTerms.orEmpty().trim()
        val days=parseTermsDays(terms)
        val db=helper.writableDatabase
        val term=days?.let { findPaymentTermId(db,it,stationId) }
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

    fun getPaymentTerms(helper:DatabaseHelper,stationId:Int,appliesTo:String="customer"):JSONArray {
        ensureSchema(helper)
        require(stationId>0) { "معرف المحطة غير صالح" }
        val scope=appliesTo.lowercase(Locale.ROOT)
        require(scope in setOf("customer","supplier","both")) { "نوع شروط الدفع غير صالح" }
        return helper.readableDatabase.rawQuery(
            """SELECT id,code,name_ar,name_en,days,applies_to,station_id,is_default,sort_order
               FROM payment_terms WHERE is_active=1 AND applies_to IN ('both',?)
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
        toDate: String? = null
    ): JSONArray {
        ensureSchema(helper)
        val rows = readDebts(helper.readableDatabase, stationId)
        val output = JSONArray()
        rows.filter { row ->
            // The caller's date range is a maturity-date filter, matching the dashboard's
            // due-invoice KPI. Filtering by createdAt hides older invoices that mature soon.
            val dueDate = row.dueDate.take(10)
            (fromDate.isNullOrBlank() || (dueDate.isNotBlank() && dueDate >= fromDate.take(10))) &&
                (toDate.isNullOrBlank() || (dueDate.isNotBlank() && dueDate <= toDate.take(10)))
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
            val dueDateKey = nearest.dueDate.take(10)
            val sameDateRows = dueRows.filter { it.dueDate.take(10) == dueDateKey }
            val dueAmount = sameDateRows.sumOf { it.remaining }.coerceAtLeast(0.0)
            val phone = PhoneUtils.normalize(nearest.phone)
            val invoiceSet = sameDateRows.map { it.transactionId }.sorted().joinToString(",")
            val eventId = "debt-reminder:$stationId:$customerId:$dueDateKey:$invoiceSet"
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

    /**
     * Catch up on a missed due-day reminder once SMS permissions/network were unavailable.
     * The event keeps the original invoice maturity date as its dedupe key, so reopening the
     * app does not send a repeated daily reminder for the same maturity date.
     */
    fun getOverdueReminderCandidates(helper: DatabaseHelper, stationId: Int): JSONArray {
        ensureSchema(helper)
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
            val dueDate = nearest.dueDate.take(10)
            val due = parseDate(dueDate) ?: return@forEach
            val daysRemaining = java.time.temporal.ChronoUnit.DAYS.between(today, due).toInt()
            if (daysRemaining >= 0) return@forEach
            val customerRows = openByCustomer[customerId].orEmpty()
            val totalOutstanding = customerRows.sumOf { it.remaining }.coerceAtLeast(0.0)
            val dueAmount = overdueRows.filter { it.dueDate.take(10) == dueDate }
                .sumOf { it.remaining }.coerceAtLeast(0.0)
            val phone = PhoneUtils.normalize(nearest.phone)
            val invoiceSet = overdueRows.filter { it.dueDate.take(10) == dueDate }
                .map { it.transactionId }.sorted().joinToString(",")
            val eventId = "debt-reminder:$stationId:$customerId:$dueDate:$invoiceSet"
            val dedupeKey = if (phone != null) "$eventId:$phone" else ""
            val alreadyQueued = dedupeKey.isNotBlank() && hasOutboxDedupeKey(helper.readableDatabase, dedupeKey)
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
     * Validates a bounded Outbox batch using one customer-scoped debt read per station,
     * rather than one station scan/query per message. Non-debt notifications pass through;
     * malformed debt events fail closed. The worker's batch limit bounds SQL bind parameters
     * and the time between this snapshot and transport.
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
                if (isCurrentDebtReminderForCustomer(reminder, customerDebts, today)) {
                    eligibleMessageIds += reminder.message.messageId
                }
            }
        }
        return eligibleMessageIds
    }

    private fun parseDebtReminder(
        message: SmsOutboxRepository.OutboxMessage
    ): ParsedDebtReminder? {
        val parts = message.eventId?.split(":", limit = 5) ?: return null
        if (parts.size != 5) return null
        val stationId = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val customerId = parts[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val eventDueDate = parts[3].takeIf { it.length == 10 && parseDate(it) != null } ?: return null
        if (message.businessEntityId != "party:$customerId") return null
        val invoiceIds = parts[4].split(',').map { it.toLongOrNull() ?: return null }
        if (invoiceIds.isEmpty() || invoiceIds.any { it <= 0L } ||
            invoiceIds != invoiceIds.distinct().sorted()
        ) return null

        return ParsedDebtReminder(message, stationId, customerId, eventDueDate, invoiceIds)
    }

    private fun isCurrentDebtReminderForCustomer(
        reminder: ParsedDebtReminder,
        debts: List<SaleDebt>,
        today: LocalDate
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
            val latestOverdue = customerDebts.mapNotNull { parseDate(it.dueDate) }
                .filter { it.isBefore(today) }
                .maxOrNull() ?: return false
            expectedDate = latestOverdue
            expectedRows = customerDebts.filter { parseDate(it.dueDate) == latestOverdue }
        }

        if (expectedDate.toString() != reminder.dueDate) return false
        val currentInvoiceIds = expectedRows.map { it.transactionId }.distinct().sorted()
        if (currentInvoiceIds != reminder.invoiceIds) return false
        if (expectedRows.sumOf { it.remaining } <= 0.000001) return false

        val currentPhone = PhoneUtils.normalize(expectedRows.first().phone) ?: return false
        if (PhoneUtils.normalize(reminder.message.recipient) != currentPhone) return false

        val outstanding = openCustomerDebts.sumOf { it.remaining }.coerceAtLeast(0.0)
        val dueAmount = expectedRows.sumOf { it.remaining }.coerceAtLeast(0.0)
        val outstandingText = formatReminderAmount(outstanding)
        val dueAmountText = formatReminderAmount(dueAmount)
        val body = reminder.message.body

        // Do not send stale maturity wording or stale amounts after a partial payment/date roll.
        return if (expectedDate == today) {
            body.contains("موعد استحقاق الدين هو اليوم") &&
                body.contains("المبلغ المستحق اليوم $dueAmountText") &&
                body.contains("إجمالي الرصيد القائم $outstandingText")
        } else {
            body.contains("قد مضى منذ") &&
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

    private fun toJson(row: SaleDebt): JSONObject {
        val days = daysUntil(row.dueDate)
        val daysOverdue = days?.let { (-it).coerceAtLeast(0) } ?: 0
        val status = statusFor(row.remaining, row.paid, row.dueDate, row.riskLevel)
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
        val bases=listOf("business_day","transaction_date","sale_date").filter { it in sc }
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


    private fun backfillMissingDueDates(db:SQLiteDatabase) {
        val sc=columns(db,"sales_transactions")
        val pc=columns(db,"parties")
        val customer=listOf("customer_party_id","customer_id","party_id").firstOrNull { it in sc } ?: return
        if("payment_method" !in sc || "due_date" !in sc || !tableExists(db,"parties")) return
        val bases=listOf("business_day","transaction_date","sale_date").filter { it in sc }
        val basisParts=bases.map { "NULLIF(substr(s.`"+it+"`,1,10),'')" }
        val basis=when {
            basisParts.isEmpty() -> "NULL"
            basisParts.size==1 -> basisParts[0]
            else -> "COALESCE("+basisParts.joinToString(",")+")"
        }
        val scope=if("station_id" in pc && "station_id" in sc) " AND p.station_id=s.station_id" else ""
        val partyLive=if("is_deleted" in pc) " AND COALESCE(p.is_deleted,0)=0" else ""
        val pTerms=if("payment_terms" in pc) "COALESCE(p.payment_terms,'')" else "''"
        val pDays=if("payment_terms_days" in pc) "p.payment_terms_days" else "NULL"
        val pTerm=if("payment_terms_id" in pc) "p.payment_terms_id" else "NULL"
        val sTerms=if("payment_terms_at_sale" in sc) "COALESCE(s.payment_terms_at_sale,'')" else "''"
        val sDays=if("payment_terms_days_at_sale" in sc) "s.payment_terms_days_at_sale" else "NULL"
        val sTerm=if("payment_terms_id_at_sale" in sc) "s.payment_terms_id_at_sale" else "NULL"
        val saleLive=if("is_deleted" in sc) "COALESCE(s.is_deleted,0)=0" else "1=1"
        val credit="LOWER(REPLACE(REPLACE(TRIM(COALESCE(s.payment_method,'')),' ',''),'-','_')) IN ('credit','credit_sale','credit_account','on_account','آجل','اجل','بيعآجل','علىالحساب')"
        val saleStatus=if("status" in sc) "AND LOWER(COALESCE(s.status,'')) NOT IN ('cancelled','refunded','void','reversed')" else ""
        data class Legacy(val id:Long,val station:Int,val due:String,val source:String,val base:String,val st:String,val sd:Int?,val si:Long?,val pt:String,val pd:Int?,val pi:Long?,val termsSource:String)
        val rows=mutableListOf<Legacy>()
        db.rawQuery(
            """SELECT s.id,COALESCE(s.station_id,0),COALESCE(s.due_date,''),COALESCE(s.due_date_source,''),
                      COALESCE($basis,''),$sTerms,$sDays,$sTerm,$pTerms,$pDays,$pTerm
                      ,COALESCE(s.payment_terms_source_at_sale,'')
               FROM sales_transactions s
               LEFT JOIN parties p ON p.id=s.`$customer`$scope$partyLive
               WHERE ($credit) AND ($saleLive) $saleStatus""",
            null
        ).use { c -> while(c.moveToNext()) rows+=Legacy(
            c.getLong(0),c.getInt(1),c.getString(2).orEmpty(),c.getString(3).orEmpty(),c.getString(4).orEmpty(),
            c.getString(5).orEmpty(),if(c.isNull(6)) null else c.getInt(6),if(c.isNull(7)) null else c.getLong(7),
            c.getString(8).orEmpty(),if(c.isNull(9)) null else c.getInt(9),if(c.isNull(10)) null else c.getLong(10),c.getString(11).orEmpty()
        ) }
        rows.forEach { row ->
            if(row.due.isNotBlank()) {
                if(row.source.isBlank()) db.update(
                    "sales_transactions",
                    ContentValues().apply { put("due_date_source","legacy_unverified");putNull("due_date_basis") },
                    "id=? AND station_id=?",arrayOf(row.id.toString(),row.station.toString())
                )
                return@forEach
            }
            val base=parseDate(row.base)
            if(row.termsSource!="explicit_text") {
                db.update("sales_transactions",ContentValues().apply {
                    putNull("due_date")
                    if(base!=null) { put("due_date_basis",base.toString());put("due_date_source","missing_terms") }
                    else { putNull("due_date_basis");put("due_date_source","missing_base_date") }
                },"id=? AND station_id=? AND (due_date IS NULL OR TRIM(due_date)='')",arrayOf(row.id.toString(),row.station.toString()))
                return@forEach
            }
            val snapshot=row.st.isNotBlank() || row.sd!=null || row.si!=null
            val terms=row.st.ifBlank { row.pt }
            val days=(row.sd ?: row.pd ?: parseTermsDays(terms))?.takeIf { it in 0..365 }
            if(days==null) {
                db.update("sales_transactions",ContentValues().apply { putNull("due_date");put("due_date_source","missing_terms") },
                    "id=? AND station_id=? AND (due_date IS NULL OR TRIM(due_date)='')",arrayOf(row.id.toString(),row.station.toString()))
                return@forEach
            }
            val termId=row.si ?: row.pi
            val source=when { base==null -> "missing_base_date";snapshot -> "legacy_rebuilt_snapshot";else -> "legacy_rebuilt_estimated" }
            val values=ContentValues().apply {
                put("payment_terms_at_sale",terms);put("payment_terms_days_at_sale",days)
                if(termId!=null) put("payment_terms_id_at_sale",termId) else putNull("payment_terms_id_at_sale")
                if(base!=null) { put("due_date_basis",base.toString());put("due_date",base.plusDays(days.toLong()).toString()) }
                else { putNull("due_date_basis");putNull("due_date") }
                put("due_date_source",source)
            }
            db.update("sales_transactions",values,"id=? AND station_id=? AND (due_date IS NULL OR TRIM(due_date)='')",arrayOf(row.id.toString(),row.station.toString()))
        }
    }


    fun parseTermsDays(terms:String?):Int? {
        val normalized=terms.orEmpty().map { ch ->
            when(ch) {
                '٠' -> '0';'١' -> '1';'٢' -> '2';'٣' -> '3';'٤' -> '4'
                '٥' -> '5';'٦' -> '6';'٧' -> '7';'٨' -> '8';'٩' -> '9'
                else -> ch
            }
        }.joinToString("").trim().lowercase(Locale.ROOT)
        if(normalized.isBlank()) return null
        if(normalized in setOf("cash","نقد","نقدي","نقداً","فوري","فوراً","عند الاستلام","upon receipt","immediate")) return 0
        val explicit=Regex("""(?:net|صافي|خلال|within)\s*(\d{1,3})(?:\s*(?:days?|يوم(?:اً|ًا|ين)?|أيام))?""")
            .findAll(normalized).mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.toList()
        val exactDays=Regex("""^(\d{1,3})\s*(?:days?|يوم(?:اً|ًا|ين)?|أيام)$""")
            .matchEntire(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val exactNumber=if(Regex("""^\d{1,3}$""").matches(normalized)) normalized.toIntOrNull() else null
        val days=when {
            explicit.size==1 -> explicit.single()
            explicit.size>1 -> return null
            exactDays!=null -> exactDays
            exactNumber!=null -> exactNumber
            else -> return null
        }
        return days.takeIf { it in 0..365 }
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
