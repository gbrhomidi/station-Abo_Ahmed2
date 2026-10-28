package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Source-backed accounting operations that were previously missing from the app.
 *
 * All methods are station-scoped. Posted accounting entries are created through
 * DatabaseHelper's existing journal workflow (including approval requirements);
 * this class never updates posted journal rows directly.
 */
internal class ExpandedAccountingService(
    private val helper: DatabaseHelper,
    private val db: SQLiteDatabase,
    private val stationId: Int,
    private val userId: Long
) {
    init {
        require(stationId > 0 && userId > 0L) { "نطاق المحطة والمستخدم مطلوبان" }
        ensureSchema()
    }

    fun execute(action: String, p: JSONObject): JSONObject = when (action) {
        "supplier_aging" -> supplierAging(p)
        "supplier_statement" -> supplierStatement(p)
        "purchase_invoice_list" -> purchaseInvoiceList(p)
        "purchase_invoice_detail" -> purchaseInvoiceDetail(p)
        "purchase_invoice_create" -> createPurchaseInvoice(p)
        "purchase_invoice_post" -> postPurchaseInvoice(p)
        "purchase_return_list" -> purchaseReturnList(p)
        "purchase_return_create" -> createPurchaseReturn(p)
        "purchase_return_post" -> postPurchaseReturn(p)
        "vat_return" -> vatReturn(p)
        "cash_flow_statement" -> cashFlowStatement(p)
        "equity_changes" -> equityChanges(p)
        "account_classification_save" -> saveAccountClassification(p)
        "tax_profile_save" -> saveTaxProfile(p)
        "bank_statement_import" -> importBankStatement(p)
        "bank_reconciliation" -> bankReconciliation(p)
        "bank_statement_match" -> matchBankStatementLine(p)
        "bank_statement_batches" -> bankStatementBatches(p)
        else -> throw IllegalArgumentException("وظيفة محاسبية غير معروفة")
    }

    private fun ensureSchema() {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS expanded_purchase_invoices (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                station_id INTEGER NOT NULL,
                supplier_id INTEGER NOT NULL,
                invoice_number TEXT NOT NULL,
                invoice_date TEXT NOT NULL,
                due_date TEXT NOT NULL,
                currency_id INTEGER NOT NULL DEFAULT 1,
                subtotal REAL NOT NULL DEFAULT 0,
                vat_amount REAL NOT NULL DEFAULT 0,
                total_amount REAL NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'draft',
                payable_account_id INTEGER,
                input_vat_account_id INTEGER,
                journal_entry_id INTEGER,
                notes TEXT NOT NULL DEFAULT '',
                created_by INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                is_deleted INTEGER NOT NULL DEFAULT 0,
                UNIQUE(station_id, invoice_number)
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS expanded_purchase_invoice_lines (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                station_id INTEGER NOT NULL,
                invoice_id INTEGER NOT NULL,
                line_no INTEGER NOT NULL,
                description TEXT NOT NULL,
                quantity REAL NOT NULL,
                unit_price REAL NOT NULL,
                vat_rate REAL NOT NULL DEFAULT 0,
                net_amount REAL NOT NULL,
                vat_amount REAL NOT NULL,
                total_amount REAL NOT NULL,
                account_id INTEGER NOT NULL,
                created_at TEXT NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS expanded_purchase_returns (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                station_id INTEGER NOT NULL,
                invoice_id INTEGER NOT NULL,
                supplier_id INTEGER NOT NULL,
                return_number TEXT NOT NULL,
                return_date TEXT NOT NULL,
                reason TEXT NOT NULL,
                subtotal REAL NOT NULL DEFAULT 0,
                vat_amount REAL NOT NULL DEFAULT 0,
                total_amount REAL NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'draft',
                journal_entry_id INTEGER,
                created_by INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                is_deleted INTEGER NOT NULL DEFAULT 0,
                UNIQUE(station_id, return_number)
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS expanded_purchase_return_lines (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                station_id INTEGER NOT NULL,
                return_id INTEGER NOT NULL,
                invoice_line_id INTEGER NOT NULL,
                quantity REAL NOT NULL,
                net_amount REAL NOT NULL,
                vat_amount REAL NOT NULL,
                total_amount REAL NOT NULL,
                account_id INTEGER NOT NULL,
                created_at TEXT NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS expanded_tax_profiles (
                station_id INTEGER PRIMARY KEY,
                jurisdiction_code TEXT NOT NULL,
                tax_registration_number TEXT NOT NULL,
                filing_frequency TEXT NOT NULL,
                tax_reporting_currency_id INTEGER NOT NULL DEFAULT 1,
                tax_schema_version TEXT NOT NULL DEFAULT '',
                is_confirmed INTEGER NOT NULL DEFAULT 0,
                confirmed_by INTEGER,
                confirmed_at TEXT,
                updated_at TEXT NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS expanded_finance_account_classification (
                station_id INTEGER NOT NULL,
                account_id INTEGER NOT NULL,
                classification_type TEXT NOT NULL,
                classification_code TEXT NOT NULL,
                updated_by INTEGER NOT NULL,
                updated_at TEXT NOT NULL,
                PRIMARY KEY(station_id, account_id, classification_type)
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS expanded_bank_statement_imports (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                station_id INTEGER NOT NULL,
                bank_account_id INTEGER NOT NULL,
                statement_reference TEXT NOT NULL,
                file_name TEXT NOT NULL DEFAULT '',
                file_hash TEXT NOT NULL,
                statement_start_date TEXT,
                statement_end_date TEXT,
                declared_opening_balance REAL,
                declared_closing_balance REAL,
                imported_count INTEGER NOT NULL DEFAULT 0,
                duplicate_count INTEGER NOT NULL DEFAULT 0,
                created_by INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                UNIQUE(station_id, bank_account_id, statement_reference)
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS expanded_bank_statement_lines (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                station_id INTEGER NOT NULL,
                import_id INTEGER NOT NULL,
                bank_account_id INTEGER NOT NULL,
                row_number INTEGER NOT NULL,
                posted_date TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                reference TEXT NOT NULL DEFAULT '',
                external_id TEXT NOT NULL DEFAULT '',
                signed_amount REAL NOT NULL,
                row_hash TEXT NOT NULL,
                match_status TEXT NOT NULL DEFAULT 'unmatched',
                matched_ledger_id INTEGER,
                match_rule TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,
                updated_at TEXT,
                updated_by INTEGER,
                UNIQUE(bank_account_id, row_hash)
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS expanded_bank_statement_match_audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                station_id INTEGER NOT NULL,
                statement_line_id INTEGER NOT NULL,
                bank_account_id INTEGER NOT NULL,
                action TEXT NOT NULL,
                previous_status TEXT NOT NULL,
                new_status TEXT NOT NULL,
                previous_ledger_id INTEGER,
                new_ledger_id INTEGER,
                reason TEXT NOT NULL DEFAULT '',
                actor_user_id INTEGER NOT NULL,
                occurred_at TEXT NOT NULL
            )"""
        )
        ensureColumn("expanded_bank_statement_lines", "updated_at", "TEXT")
        ensureColumn("expanded_bank_statement_lines", "updated_by", "INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_exp_purchase_station_date ON expanded_purchase_invoices(station_id, invoice_date, status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_exp_purchase_supplier ON expanded_purchase_invoices(station_id, supplier_id, due_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_exp_purchase_lines_invoice ON expanded_purchase_invoice_lines(invoice_id, station_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_exp_purchase_returns_invoice ON expanded_purchase_returns(invoice_id, station_id, status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_exp_bank_lines_import ON expanded_bank_statement_lines(import_id, match_status)")
    }

    private fun supplierAging(p: JSONObject): JSONObject {
        requireTable("parties")
        requireTable("party_types")
        requireTable("supplier_ledger")
        val asOf = parseDate(p.optString("as_of_date").ifBlank { today() })
        val rows = query(
            "SELECT p.id,COALESCE(p.legal_name,''),COALESCE(p.current_balance,0),COALESCE(p.total_due,0) " +
                "FROM parties p JOIN party_types t ON t.id=p.party_type_id " +
                "WHERE p.station_id=? AND t.type_code='SUPPLIER' AND COALESCE(p.is_deleted,0)=0 ORDER BY p.id",
            arrayOf(stationId.toString())
        ) { c ->
            JSONObject().put("supplier_id", c.getLong(0)).put("supplier_name", c.getString(1))
                .put("party_balance", c.getDouble(2)).put("party_total_due", c.getDouble(3))
        }
        val output = JSONArray()
        var totalBalance = 0.0
        var current = 0.0
        var d1 = 0.0
        var d2 = 0.0
        var d3 = 0.0
        var d4 = 0.0
        var undated = 0.0
        var varianceTotal = 0.0
        var unknownDateRows = 0
        for (i in 0 until rows.size) {
            val party = rows[i]
            val id = party.getLong("supplier_id")
            val events = supplierLedgerEvents(id)
            val obligations = mutableListOf<OpenObligation>()
            var advances = 0.0
            var ledgerBalance = 0.0
            for (e in events) {
                val eventDate = e.eventDate
                if (eventDate.isNullOrBlank()) {
                    unknownDateRows++
                    continue
                }
                // An as-of aging report must not pull in transactions after the requested date.
                if (eventDate > asOf) continue
                val credit = e.credit.coerceAtLeast(0.0)
                val debit = e.debit.coerceAtLeast(0.0)
                ledgerBalance += credit - debit
                if (credit > 0.005) {
                    val source = sourceDates(e.type, e.transactionId, e.eventDate)
                    val obligation = OpenObligation(
                        e.id, e.transactionId, credit, source.first, source.second, e.eventDate, e.type
                    )
                    if (advances > 0.005) {
                        val used = minOf(advances, obligation.remaining)
                        obligation.remaining -= used
                        advances -= used
                    }
                    obligations.add(obligation)
                }
                if (debit > 0.005) {
                    var remaining = debit
                    val returnInvoiceId = sourceInvoiceForReturn(e.type, e.transactionId)
                    if (returnInvoiceId > 0L) {
                        for (o in obligations.filter { obligationInvoiceId(it) == returnInvoiceId && it.remaining > 0.005 }) {
                            val used = minOf(remaining, o.remaining)
                            o.remaining -= used
                            remaining -= used
                            if (remaining <= 0.005) break
                        }
                    }
                    if (remaining > 0.005) {
                        for (o in obligations.filter { it.remaining > 0.005 }.sortedWith(compareBy<OpenObligation> { it.invoiceDate ?: "9999-12-31" }.thenBy { it.id })) {
                            val used = minOf(remaining, o.remaining)
                            o.remaining -= used
                            remaining -= used
                            if (remaining <= 0.005) break
                        }
                    }
                    if (remaining > 0.005) advances += remaining
                }
            }
            val supplier = JSONObject()
                .put("supplier_id", id)
                .put("supplier_name", party.getString("supplier_name"))
                .put("party_balance", party.getDouble("party_balance"))
                .put("party_total_due", party.getDouble("party_total_due"))
            var sCurrent = 0.0
            var s1 = 0.0
            var s2 = 0.0
            var s3 = 0.0
            var s4 = 0.0
            var sUndated = 0.0
            val details = JSONArray()
            for (o in obligations) {
                if (o.remaining <= 0.005) continue
                val bucket = if (o.dueDate.isNullOrBlank()) {
                    sUndated += o.remaining
                    "undated"
                } else {
                    val overdue = daysBetween(o.dueDate!!, asOf).coerceAtLeast(0)
                    when {
                        overdue == 0 -> { sCurrent += o.remaining; "current" }
                        overdue <= 30 -> { s1 += o.remaining; "1_30" }
                        overdue <= 60 -> { s2 += o.remaining; "31_60" }
                        overdue <= 90 -> { s3 += o.remaining; "61_90" }
                        else -> { s4 += o.remaining; "over_90" }
                    }
                }
                details.put(
                    JSONObject().put("ledger_id", o.id).put("transaction_type", o.type)
                        .put("invoice_date", o.invoiceDate ?: JSONObject.NULL)
                        .put("due_date", o.dueDate ?: JSONObject.NULL)
                        .put("days_overdue", if (o.dueDate.isNullOrBlank()) JSONObject.NULL else daysBetween(o.dueDate!!, asOf).coerceAtLeast(0))
                        .put("open_amount", money(o.remaining)).put("bucket", bucket)
                )
            }
            val agingBalance = money(sCurrent + s1 + s2 + s3 + s4 + sUndated)
            val currentBalanceComparable = asOf >= today()
            val variance = if (currentBalanceComparable) money(party.getDouble("party_balance") - ledgerBalance) else JSONObject.NULL
            supplier.put("not_overdue", money(sCurrent)).put("overdue_1_30", money(s1))
                .put("overdue_31_60", money(s2)).put("overdue_61_90", money(s3))
                .put("overdue_over_90", money(s4)).put("undated_or_no_terms", money(sUndated))
                .put("unallocated_supplier_advances", money(advances)).put("aging_balance", agingBalance)
                .put("ledger_balance_as_of", money(ledgerBalance)).put("ledger_vs_current_party_balance_variance", variance)
                .put("current_party_balance_comparison_available", currentBalanceComparable)
                .put("details", details)
            output.put(supplier)
            totalBalance += party.getDouble("party_balance")
            current += sCurrent
            d1 += s1
            d2 += s2
            d3 += s3
            d4 += s4
            undated += sUndated
            if (currentBalanceComparable) varianceTotal += (variance as Double)
        }
        return JSONObject().put("success", true).put("as_of_date", asOf).put("station_id", stationId)
            .put("allocation_policy", "توزيع الدفعات غير المخصصة على أقدم الفواتير المفتوحة؛ تظهر السجلات ذات تاريخ/أجل مفقود في بند مستقل")
            .put("supplier_count", output.length()).put("supplier_balance_total", money(totalBalance))
            .put("not_overdue_total", money(current)).put("overdue_1_30_total", money(d1))
            .put("overdue_31_60_total", money(d2)).put("overdue_61_90_total", money(d3))
            .put("overdue_over_90_total", money(d4)).put("undated_total", money(undated))
            .put("ledger_party_variance_total", money(varianceTotal))
            .put("undated_ledger_row_count", unknownDateRows)
            .put("party_balance_variance_is_current_only", asOf >= today())
            .put("aging_complete", unknownDateRows == 0 && (asOf < today() || abs(varianceTotal) <= 0.01) && undated <= 0.01)
            .put("data", output)
    }

    private fun supplierStatement(p: JSONObject): JSONObject {
        requireTable("supplier_ledger")
        requireTable("parties")
        requireTable("party_types")
        val supplierId = p.optLong("supplier_id", 0L)
        require(supplierId > 0L) { "يجب تحديد المورد" }
        val supplier = query(
            "SELECT p.id,COALESCE(p.legal_name,''),COALESCE(p.current_balance,0) FROM parties p " +
                "JOIN party_types t ON t.id=p.party_type_id WHERE p.id=? AND p.station_id=? AND " +
                "t.type_code='SUPPLIER' AND COALESCE(p.is_deleted,0)=0",
            arrayOf(supplierId.toString(), stationId.toString())
        ) { c -> JSONObject().put("id", c.getLong(0)).put("name", c.getString(1)).put("balance", c.getDouble(2)) }.firstOrNull()
            ?: throw IllegalArgumentException("المورد غير موجود في المحطة الحالية")
        val from = p.optString("from_date").trim().takeIf { it.isNotEmpty() }?.let { parseDate(it) }
        val to = p.optString("to_date").trim().takeIf { it.isNotEmpty() }?.let { parseDate(it) }
        if (from != null && to != null) require(from <= to) { "نطاق التاريخ غير صحيح" }
        val events = supplierLedgerEvents(supplierId)
        var opening = 0.0
        var running = 0.0
        val visible = JSONArray()
        val undated = JSONArray()
        var complete = true
        for (e in events) {
            val signed = e.credit - e.debit
            val dt = e.eventDate
            if (dt.isNullOrBlank()) {
                complete = false
                running += signed
                undated.put(
                    JSONObject().put("ledger_id", e.id).put("transaction_type", e.type)
                        .put("transaction_id", e.transactionId).put("debit", money(e.debit))
                        .put("credit", money(e.credit)).put("date", JSONObject.NULL)
                )
                continue
            }
            if (from != null && dt < from) {
                opening += signed
                running += signed
                continue
            }
            if (to != null && dt > to) {
                running += signed
                continue
            }
            running += signed
            val source = sourceDates(e.type, e.transactionId, dt)
            visible.put(
                JSONObject().put("ledger_id", e.id).put("date", dt)
                    .put("transaction_type", e.type).put("transaction_id", e.transactionId)
                    .put("description", ledgerDescription(e.type, e.transactionId))
                    .put("invoice_date", source.first ?: JSONObject.NULL)
                    .put("due_date", source.second ?: JSONObject.NULL)
                    .put("debit", money(e.debit)).put("credit", money(e.credit))
                    .put("running_balance", money(running))
            )
        }
        val filteredRunning = opening + visibleBalance(visible) 
        return JSONObject().put("success", true).put("station_id", stationId)
            .put("supplier", supplier).put("from_date", from ?: JSONObject.NULL)
            .put("to_date", to ?: JSONObject.NULL).put("opening_balance", money(opening))
            .put("closing_balance", money(if (from == null && to == null) running else filteredRunning))
            .put("supplier_current_balance", supplier.getDouble("balance"))
            .put("entries", visible).put("undated_entries", undated)
            .put("statement_complete", complete).put("undated_count", undated.length())
    }

    private fun createPurchaseInvoice(p: JSONObject): JSONObject {
        val supplierId = p.optLong("supplier_id", 0L)
        require(supplierId > 0L) { "يجب تحديد المورد" }
        requireSupplier(supplierId)
        val number = clean(p.optString("invoice_number"), 100)
        require(number.isNotBlank()) { "رقم فاتورة المورد مطلوب" }
        val invoiceDate = parseDate(p.optString("invoice_date"))
        val dueDate = parseDate(p.optString("due_date"))
        require(dueDate >= invoiceDate) { "تاريخ الاستحقاق لا يجوز أن يسبق تاريخ الفاتورة" }
        val lines = p.optJSONArray("lines") ?: JSONArray()
        require(lines.length() in 1..300) { "يجب إدخال بند واحد على الأقل وبحد أقصى 300 بند" }
        val currency = p.optLong("currency_id", 1L).coerceAtLeast(1L)
        val prepared = mutableListOf<PurchaseLine>()
        var subtotal = 0.0
        var vat = 0.0
        for (i in 0 until lines.length()) {
            val line = lines.optJSONObject(i) ?: throw IllegalArgumentException("بيانات بند المشتريات غير صالحة")
            val description = clean(line.optString("description"), 500)
            val qty = line.optDouble("quantity", Double.NaN)
            val price = line.optDouble("unit_price", Double.NaN)
            val rate = line.optDouble("vat_rate", 0.0)
            val accountId = line.optLong("account_id", 0L)
            require(description.isNotBlank() && qty.isFinite() && qty > 0 && price.isFinite() && price >= 0) {
                "وصف البند والكمية وسعر الوحدة مطلوبة ويجب أن تكون القيم صحيحة"
            }
            require(rate.isFinite() && rate in 0.0..100.0) { "نسبة ضريبة القيمة المضافة غير صحيحة" }
            requireValidAccount(accountId, setOf("asset", "expense", "cost", "inventory"))
            val net = money(qty * price)
            val tax = money(net * rate / 100.0)
            subtotal += net
            vat += tax
            prepared.add(PurchaseLine(description, qty, price, rate, net, tax, money(net + tax), accountId))
        }
        subtotal = money(subtotal)
        vat = money(vat)
        val total = money(subtotal + vat)
        val stamp = now()
        var id = 0L
        db.beginTransaction()
        try {
            id = db.insertOrThrow("expanded_purchase_invoices", null, ContentValues().apply {
                put("station_id", stationId); put("supplier_id", supplierId); put("invoice_number", number)
                put("invoice_date", invoiceDate); put("due_date", dueDate); put("currency_id", currency)
                put("subtotal", subtotal); put("vat_amount", vat); put("total_amount", total)
                put("status", "draft"); put("notes", clean(p.optString("notes"), 2000))
                put("created_by", userId); put("created_at", stamp); put("updated_at", stamp); put("is_deleted", 0)
            })
            prepared.forEachIndexed { index, line ->
                val cv = ContentValues().apply {
                    put("station_id", stationId); put("invoice_id", id); put("line_no", index + 1)
                    put("description", line.description); put("quantity", line.quantity)
                    put("unit_price", line.unitPrice); put("vat_rate", line.vatRate)
                    put("net_amount", line.net); put("vat_amount", line.vat)
                    put("total_amount", line.total); put("account_id", line.accountId); put("created_at", stamp)
                }
                db.insertOrThrow("expanded_purchase_invoice_lines", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return JSONObject().put("success", true).put("invoice_id", id).put("invoice_number", number)
            .put("status", "draft").put("subtotal", subtotal).put("vat_amount", vat).put("total_amount", total)
            .put("message", "تم حفظ مسودة فاتورة المشتريات في SQLite؛ لم تُرحّل محاسبياً بعد")
    }

    private fun postPurchaseInvoice(p: JSONObject): JSONObject {
        val invoiceId = p.optLong("invoice_id", 0L)
        require(invoiceId > 0) { "معرف فاتورة المشتريات مطلوب" }
        var invoice = purchaseInvoice(invoiceId) ?: throw IllegalArgumentException("فاتورة المشتريات غير موجودة")
        val status = invoice.optString("status")
        if (status == "posted") return JSONObject().put("success", true).put("invoice_id", invoiceId).put("status", status).put("journal_entry_id", invoice.optLong("journal_entry_id"))
        var journalId = invoice.optLong("journal_entry_id", 0L)
        if (journalId <= 0L) journalId = findSourceJournal("general_purchase_invoice", invoiceId)
        if (journalId > 0L && status != "posted") {
            val payableId = p.optLong("payable_account_id", invoice.optLong("payable_account_id", 0L))
            val inputVatId = p.optLong("input_vat_account_id", invoice.optLong("input_vat_account_id", 0L))
            requireValidAccount(payableId, setOf("liability"))
            if (invoice.optDouble("vat_amount") > 0.005) requireValidAccount(inputVatId, setOf("asset"))
            db.update("expanded_purchase_invoices", ContentValues().apply {
                put("journal_entry_id", journalId); put("payable_account_id", payableId)
                if (inputVatId > 0L) put("input_vat_account_id", inputVatId)
                put("status", "posting"); put("updated_at", now())
            }, "id=? AND station_id=? AND is_deleted=0", arrayOf(invoiceId.toString(), stationId.toString()))
            invoice = purchaseInvoice(invoiceId) ?: invoice
        }
        if (journalId <= 0L) {
            val payableId = p.optLong("payable_account_id", 0L)
            requireValidAccount(payableId, setOf("liability"))
            val vatAmount = invoice.optDouble("vat_amount")
            val inputVatId = p.optLong("input_vat_account_id", 0L)
            if (vatAmount > 0.005) requireValidAccount(inputVatId, setOf("asset"))
            val lines = invoiceLines(invoiceId)
            val items = JSONArray()
            lines.forEach { line ->
                items.put(JSONObject().put("account_id", line.accountId).put("debit", line.net)
                    .put("credit", 0.0).put("description", line.description))
            }
            if (vatAmount > 0.005) items.put(JSONObject().put("account_id", inputVatId)
                .put("debit", vatAmount).put("credit", 0.0).put("description", "ضريبة مدخلات - فاتورة مشتريات"))
            items.put(JSONObject().put("account_id", payableId).put("debit", 0.0)
                .put("credit", invoice.optDouble("total_amount")).put("description", "ذمم دائنة - مورد"))
            val journal = JSONObject().put("entry_date", invoice.optString("invoice_date"))
                .put("description", "فاتورة مشتريات عامة " + invoice.optString("invoice_number"))
                .put("entry_type", "general").put("reference_type", "general_purchase_invoice")
                .put("reference_id", invoiceId).put("currency_id", invoice.optLong("currency_id", 1L))
                .put("items", items)
            journalId = helper.saveJournalEntry(journal, userId, stationId)
            val link = ContentValues().apply {
                put("journal_entry_id", journalId); put("payable_account_id", payableId)
                if (inputVatId > 0L) put("input_vat_account_id", inputVatId)
                put("status", "posting"); put("updated_at", now())
            }
            db.update("expanded_purchase_invoices", link, "id=? AND station_id=? AND status='draft'",
                arrayOf(invoiceId.toString(), stationId.toString()))
            invoice = purchaseInvoice(invoiceId) ?: invoice
        }
        val journalStatus = journalStatus(journalId)
        if (journalStatus == "posted") {
            finalizePurchaseInvoice(invoiceId)
            return JSONObject().put("success", true).put("invoice_id", invoiceId).put("status", "posted")
                .put("journal_entry_id", journalId).put("message", "الفاتورة والقيد ودفتر المورد متزامنة في SQLite")
        }
        if (journalStatus == "draft" || journalStatus == "pending") {
            helper.requestOrPostJournalEntry(journalId, userId, stationId)
        }
        val finalStatus = journalStatus(journalId)
        if (finalStatus == "posted") {
            finalizePurchaseInvoice(invoiceId)
            return JSONObject().put("success", true).put("invoice_id", invoiceId).put("status", "posted")
                .put("journal_entry_id", journalId)
        }
        db.update("expanded_purchase_invoices",
            ContentValues().apply { put("status", "pending_approval"); put("updated_at", now()) },
            "id=? AND station_id=?", arrayOf(invoiceId.toString(), stationId.toString()))
        return JSONObject().put("success", true).put("invoice_id", invoiceId)
            .put("status", "pending_approval").put("journal_entry_id", journalId)
            .put("message", "أُنشئ القيد عبر مسار الاعتماد؛ لم يُثبت رصيد المورد أو الضريبة قبل الترحيل النهائي")
    }

    private fun createPurchaseReturn(p: JSONObject): JSONObject {
        val invoiceId = p.optLong("invoice_id", 0L)
        val invoice = purchaseInvoice(invoiceId) ?: throw IllegalArgumentException("فاتورة الشراء الأصلية غير موجودة")
        require(invoice.optString("status") == "posted") { "لا يمكن إرجاع مشتريات قبل ترحيل فاتورتها الأصلية" }
        val returnDate = parseDate(p.optString("return_date"))
        val reason = clean(p.optString("reason"), 1000)
        require(reason.length >= 3) { "سبب المرتجع مطلوب" }
        val requested = p.optJSONArray("lines") ?: JSONArray()
        require(requested.length() in 1..300) { "حدد بنداً واحداً على الأقل للمرتجع" }
        val prepared = mutableListOf<ReturnLine>()
        var netTotal = 0.0
        var vatTotal = 0.0
        for (i in 0 until requested.length()) {
            val item = requested.optJSONObject(i) ?: throw IllegalArgumentException("بيانات بند المرتجع غير صالحة")
            val sourceLineId = item.optLong("invoice_line_id", 0L)
            val qty = item.optDouble("quantity", Double.NaN)
            require(sourceLineId > 0 && qty.isFinite() && qty > 0)
            val source = query(
                "SELECT id,quantity,unit_price,vat_rate,account_id,description FROM expanded_purchase_invoice_lines " +
                    "WHERE id=? AND invoice_id=? AND station_id=?",
                arrayOf(sourceLineId.toString(), invoiceId.toString(), stationId.toString())
            ) { c -> PurchaseLine(c.getString(5), c.getDouble(1), c.getDouble(2), c.getDouble(3), 0.0, 0.0, 0.0, c.getLong(4)) to c.getLong(0) }
                .firstOrNull() ?: throw IllegalArgumentException("بند المرتجع لا ينتمي إلى الفاتورة الأصلية")
            val alreadyReturned = scalarDouble(
                "SELECT COALESCE(SUM(rl.quantity),0) FROM expanded_purchase_return_lines rl " +
                    "JOIN expanded_purchase_returns r ON r.id=rl.return_id WHERE rl.invoice_line_id=? " +
                    "AND r.station_id=? AND r.status IN ('draft','posting','pending_approval','posted') AND r.is_deleted=0",
                arrayOf(sourceLineId.toString(), stationId.toString())
            )
            require(qty <= source.first.quantity - alreadyReturned + 0.000001) {
                "كمية المرتجع تتجاوز الكمية الأصلية المتاحة للمرتجع"
            }
            val net = money(qty * source.first.unitPrice)
            val tax = money(net * source.first.vatRate / 100.0)
            val total = money(net + tax)
            prepared.add(ReturnLine(sourceLineId, qty, net, tax, total, source.first.accountId))
            netTotal += net
            vatTotal += tax
        }
        netTotal = money(netTotal)
        vatTotal = money(vatTotal)
        val total = money(netTotal + vatTotal)
        val returnNumber = clean(p.optString("return_number"), 100).ifBlank {
            "PR-" + stationId + "-" + System.currentTimeMillis()
        }
        val stamp = now()
        var returnId = 0L
        db.beginTransaction()
        try {
            returnId = db.insertOrThrow("expanded_purchase_returns", null, ContentValues().apply {
                put("station_id", stationId); put("invoice_id", invoiceId)
                put("supplier_id", invoice.optLong("supplier_id")); put("return_number", returnNumber)
                put("return_date", returnDate); put("reason", reason); put("subtotal", netTotal)
                put("vat_amount", vatTotal); put("total_amount", total); put("status", "draft")
                put("created_by", userId); put("created_at", stamp); put("updated_at", stamp); put("is_deleted", 0)
            })
            prepared.forEach { item ->
                db.insertOrThrow("expanded_purchase_return_lines", null, ContentValues().apply {
                    put("station_id", stationId); put("return_id", returnId)
                    put("invoice_line_id", item.invoiceLineId); put("quantity", item.quantity)
                    put("net_amount", item.net); put("vat_amount", item.vat); put("total_amount", item.total)
                    put("account_id", item.accountId); put("created_at", stamp)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return JSONObject().put("success", true).put("return_id", returnId).put("return_number", returnNumber)
            .put("status", "draft").put("subtotal", netTotal).put("vat_amount", vatTotal).put("total_amount", total)
            .put("message", "حُفظ مرتجع المشتريات كمسودة؛ لم يخفض ذمة المورد قبل الترحيل")
    }

    private fun postPurchaseReturn(p: JSONObject): JSONObject {
        val returnId = p.optLong("return_id", 0L)
        var record = purchaseReturn(returnId) ?: throw IllegalArgumentException("مرتجع المشتريات غير موجود")
        if (record.optString("status") == "posted") return JSONObject().put("success", true)
            .put("return_id", returnId).put("status", "posted").put("journal_entry_id", record.optLong("journal_entry_id"))
        val invoice = purchaseInvoice(record.optLong("invoice_id")) ?: throw IllegalArgumentException("الفاتورة الأصلية غير موجودة")
        require(invoice.optString("status") == "posted") { "يجب أن تكون الفاتورة الأصلية مرحلة" }
        var journalId = record.optLong("journal_entry_id", 0L)
        if (journalId <= 0L) journalId = findSourceJournal("general_purchase_return", returnId)
        if (journalId > 0L && record.optString("status") != "posted") {
            db.update("expanded_purchase_returns", ContentValues().apply {
                put("journal_entry_id", journalId); put("status", "posting"); put("updated_at", now())
            }, "id=? AND station_id=? AND is_deleted=0", arrayOf(returnId.toString(), stationId.toString()))
            record = purchaseReturn(returnId) ?: record
        }
        if (journalId <= 0L) {
            val payableId = invoice.optLong("payable_account_id", 0L)
            requireValidAccount(payableId, setOf("liability"))
            val lines = query(
                "SELECT invoice_line_id,quantity,net_amount,vat_amount,total_amount,account_id " +
                    "FROM expanded_purchase_return_lines WHERE return_id=? AND station_id=? ORDER BY id",
                arrayOf(returnId.toString(), stationId.toString())
            ) { c -> ReturnLine(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getDouble(3), c.getDouble(4), c.getLong(5)) }
            val items = JSONArray()
            items.put(JSONObject().put("account_id", payableId).put("debit", record.optDouble("total_amount"))
                .put("credit", 0.0).put("description", "تخفيض ذمم المورد - مرتجع مشتريات"))
            lines.forEach { line ->
                items.put(JSONObject().put("account_id", line.accountId).put("debit", 0.0)
                    .put("credit", line.net).put("description", "عكس تكلفة/مصروف مشتريات"))
            }
            val vat = record.optDouble("vat_amount")
            if (vat > 0.005) {
                val vatAccount = invoice.optLong("input_vat_account_id", 0L)
                requireValidAccount(vatAccount, setOf("asset"))
                items.put(JSONObject().put("account_id", vatAccount).put("debit", 0.0)
                    .put("credit", vat).put("description", "عكس ضريبة مدخلات المشتريات"))
            }
            val journal = JSONObject().put("entry_date", record.optString("return_date"))
                .put("description", "مرتجع مشتريات " + record.optString("return_number"))
                .put("entry_type", "general").put("reference_type", "general_purchase_return")
                .put("reference_id", returnId).put("currency_id", invoice.optLong("currency_id", 1L))
                .put("items", items)
            journalId = helper.saveJournalEntry(journal, userId, stationId)
            db.update("expanded_purchase_returns", ContentValues().apply {
                put("journal_entry_id", journalId); put("status", "posting"); put("updated_at", now())
            }, "id=? AND station_id=? AND status='draft'", arrayOf(returnId.toString(), stationId.toString()))
            record = purchaseReturn(returnId) ?: record
        }
        var status = journalStatus(journalId)
        if (status == "draft" || status == "pending") {
            helper.requestOrPostJournalEntry(journalId, userId, stationId)
            status = journalStatus(journalId)
        }
        if (status == "posted") {
            finalizePurchaseReturn(returnId)
            return JSONObject().put("success", true).put("return_id", returnId).put("status", "posted")
                .put("journal_entry_id", journalId).put("message", "رُحّل المرتجع وعُكس أثره على ذمة المورد وضريبة المدخلات")
        }
        db.update("expanded_purchase_returns", ContentValues().apply {
            put("status", "pending_approval"); put("updated_at", now())
        }, "id=? AND station_id=?", arrayOf(returnId.toString(), stationId.toString()))
        return JSONObject().put("success", true).put("return_id", returnId)
            .put("status", "pending_approval").put("journal_entry_id", journalId)
            .put("message", "المرتجع بانتظار اعتماد القيد؛ لم يُعدّل رصيد المورد قبل الترحيل")
    }

    private fun purchaseInvoiceDetail(p: JSONObject): JSONObject {
        syncPostedDocuments()
        val invoiceId = p.optLong("invoice_id", 0L)
        val invoice = purchaseInvoice(invoiceId) ?: throw IllegalArgumentException("فاتورة المشتريات غير موجودة في المحطة الحالية")
        val lines = JSONArray()
        invoiceLines(invoiceId).forEachIndexed { index, line ->
            val lineId = scalarLong(
                "SELECT id FROM expanded_purchase_invoice_lines WHERE invoice_id=? AND station_id=? AND line_no=?",
                arrayOf(invoiceId.toString(), stationId.toString(), (index + 1).toString())
            )
            lines.put(JSONObject().put("invoice_line_id", lineId).put("line_no", index + 1)
                .put("description", line.description).put("quantity", line.quantity)
                .put("unit_price", line.unitPrice).put("vat_rate", line.vatRate)
                .put("net_amount", line.net).put("vat_amount", line.vat)
                .put("total_amount", line.total).put("account_id", line.accountId))
        }
        return JSONObject().put("success", true).put("invoice", invoice).put("lines", lines)
    }

    private fun purchaseInvoiceList(p: JSONObject): JSONObject {
        syncPostedDocuments()
        val from = p.optString("from_date").trim().takeIf { it.isNotEmpty() }?.let { parseDate(it) }
        val to = p.optString("to_date").trim().takeIf { it.isNotEmpty() }?.let { parseDate(it) }
        var sql = "SELECT id,invoice_number,supplier_id,invoice_date,due_date,subtotal,vat_amount,total_amount,status,journal_entry_id,notes FROM expanded_purchase_invoices WHERE station_id=? AND is_deleted=0"
        val args = mutableListOf(stationId.toString())
        if (from != null) { sql += " AND invoice_date>=?"; args.add(from) }
        if (to != null) { sql += " AND invoice_date<=?"; args.add(to) }
        val data = query(sql + " ORDER BY invoice_date DESC,id DESC", args.toTypedArray()) { c ->
            JSONObject().put("invoice_id", c.getLong(0)).put("invoice_number", c.getString(1))
                .put("supplier_id", c.getLong(2)).put("invoice_date", c.getString(3)).put("due_date", c.getString(4))
                .put("subtotal", c.getDouble(5)).put("vat_amount", c.getDouble(6)).put("total_amount", c.getDouble(7))
                .put("status", c.getString(8)).put("journal_entry_id", if (c.isNull(9)) JSONObject.NULL else c.getLong(9))
                .put("notes", c.getString(10))
        }
        return JSONObject().put("success", true).put("station_id", stationId).put("data", JSONArray(data))
            .put("count", data.size)
    }

    private fun purchaseReturnList(p: JSONObject): JSONObject {
        syncPostedDocuments()
        val data = query(
            "SELECT id,return_number,invoice_id,supplier_id,return_date,subtotal,vat_amount,total_amount,status,journal_entry_id,reason " +
                "FROM expanded_purchase_returns WHERE station_id=? AND is_deleted=0 ORDER BY return_date DESC,id DESC",
            arrayOf(stationId.toString())
        ) { c -> JSONObject().put("return_id", c.getLong(0)).put("return_number", c.getString(1))
            .put("invoice_id", c.getLong(2)).put("supplier_id", c.getLong(3)).put("return_date", c.getString(4))
            .put("subtotal", c.getDouble(5)).put("vat_amount", c.getDouble(6)).put("total_amount", c.getDouble(7))
            .put("status", c.getString(8)).put("journal_entry_id", if (c.isNull(9)) JSONObject.NULL else c.getLong(9))
            .put("reason", c.getString(10)) }
        return JSONObject().put("success", true).put("station_id", stationId).put("data", JSONArray(data))
            .put("count", data.size)
    }

    private fun syncPostedDocuments() {
        val invoices = query(
            "SELECT id FROM expanded_purchase_invoices WHERE station_id=? AND status IN ('posting','pending_approval') AND journal_entry_id IS NOT NULL AND is_deleted=0",
            arrayOf(stationId.toString())
        ) { it.getLong(0) }
        invoices.forEach { id ->
            val inv = purchaseInvoice(id) ?: return@forEach
            if (journalStatus(inv.optLong("journal_entry_id", 0L)) == "posted") finalizePurchaseInvoice(id)
        }
        val returns = query(
            "SELECT id FROM expanded_purchase_returns WHERE station_id=? AND status IN ('posting','pending_approval') AND journal_entry_id IS NOT NULL AND is_deleted=0",
            arrayOf(stationId.toString())
        ) { it.getLong(0) }
        returns.forEach { id ->
            val ret = purchaseReturn(id) ?: return@forEach
            if (journalStatus(ret.optLong("journal_entry_id", 0L)) == "posted") finalizePurchaseReturn(id)
        }
    }

    private fun finalizePurchaseInvoice(id: Long) {
        val inv = purchaseInvoice(id) ?: throw IllegalArgumentException("فاتورة المشتريات غير موجودة")
        db.beginTransaction()
        try {
            val exists = scalar(
                "SELECT COUNT(*) FROM supplier_ledger WHERE party_id=? AND transaction_type='general_purchase_invoice' AND transaction_id=?",
                arrayOf(inv.optLong("supplier_id").toString(), id.toString())
            ) > 0
            if (!exists) {
                insertSupplierLedger(inv.optLong("supplier_id"), 0.0, inv.optDouble("total_amount"),
                    "general_purchase_invoice", id, inv.optString("invoice_date"), "فاتورة مشتريات " + inv.optString("invoice_number"))
                updateSupplierTotals(inv.optLong("supplier_id"), inv.optDouble("total_amount"), inv.optDouble("total_amount"), inv.optDouble("total_amount"))
            }
            db.update("expanded_purchase_invoices", ContentValues().apply {
                put("status", "posted"); put("updated_at", now())
            }, "id=? AND station_id=?", arrayOf(id.toString(), stationId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun finalizePurchaseReturn(id: Long) {
        val ret = purchaseReturn(id) ?: throw IllegalArgumentException("مرتجع المشتريات غير موجود")
        db.beginTransaction()
        try {
            val exists = scalar(
                "SELECT COUNT(*) FROM supplier_ledger WHERE party_id=? AND transaction_type='general_purchase_return' AND transaction_id=?",
                arrayOf(ret.optLong("supplier_id").toString(), id.toString())
            ) > 0
            if (!exists) {
                insertSupplierLedger(ret.optLong("supplier_id"), ret.optDouble("total_amount"), 0.0,
                    "general_purchase_return", id, ret.optString("return_date"), "مرتجع مشتريات " + ret.optString("return_number"))
                updateSupplierTotals(ret.optLong("supplier_id"), -ret.optDouble("total_amount"),
                    -ret.optDouble("total_amount"), -ret.optDouble("total_amount"))
            }
            db.update("expanded_purchase_returns", ContentValues().apply {
                put("status", "posted"); put("updated_at", now())
            }, "id=? AND station_id=?", arrayOf(id.toString(), stationId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun vatReturn(p: JSONObject): JSONObject {
        val from = parseDate(p.optString("from_date"))
        val to = parseDate(p.optString("to_date"))
        require(from <= to) { "نطاق فترة الإقرار الضريبي غير صحيح" }
        val sales = findSalesVatSource(from, to)
        val purchaseVat = scalarDouble(
            "SELECT COALESCE(SUM(vat_amount),0) FROM expanded_purchase_invoices WHERE station_id=? AND status='posted' AND is_deleted=0 AND invoice_date>=? AND invoice_date<=?",
            arrayOf(stationId.toString(), from, to)
        )
        val returnVat = scalarDouble(
            "SELECT COALESCE(SUM(vat_amount),0) FROM expanded_purchase_returns WHERE station_id=? AND status='posted' AND is_deleted=0 AND return_date>=? AND return_date<=?",
            arrayOf(stationId.toString(), from, to)
        )
        val inputVatNet = money(purchaseVat - returnVat)
        val outputVat = sales.optDouble("output_vat", 0.0)
        val gaps = sales.optJSONArray("schema_gaps") ?: JSONArray()
        val missingSalesJournals = sales.optInt("completed_sales_without_posted_journal", -1)
        val hasTaxProfile = taxProfileConfigured()
        val sourceComplete = sales.optBoolean("source_available") && gaps.length() == 0 && missingSalesJournals == 0
        val readyForReview = sourceComplete && hasTaxProfile
        return JSONObject().put("success", true).put("station_id", stationId).put("from_date", from).put("to_date", to)
            .put("output_vat", money(outputVat)).put("posted_purchase_input_vat", money(purchaseVat))
            .put("posted_purchase_return_input_vat", money(returnVat)).put("net_recoverable_input_vat", inputVatNet)
            .put("net_vat_payable", money(outputVat - inputVatNet))
            .put("outbound_source", sales.optString("source", "unavailable"))
            .put("outbound_document_count", sales.optInt("document_count", 0))
            .put("completed_sales_without_posted_journal", missingSalesJournals)
            .put("tax_profile_configured", hasTaxProfile)
            .put("calculation_is_preparatory_workpaper", true)
            .put("ready_for_tax_review", readyForReview)
            .put("purchase_invoice_count", scalar("SELECT COUNT(*) FROM expanded_purchase_invoices WHERE station_id=? AND status='posted' AND is_deleted=0 AND invoice_date>=? AND invoice_date<=?", arrayOf(stationId.toString(), from, to)))
            .put("purchase_return_count", scalar("SELECT COUNT(*) FROM expanded_purchase_returns WHERE station_id=? AND status='posted' AND is_deleted=0 AND return_date>=? AND return_date<=?", arrayOf(stationId.toString(), from, to)))
            .put("schema_gaps", gaps).put("ready_for_filing", false)
            .put("filing_block_reason", when {
                !sourceComplete -> "حقول مصدر الضريبة أو قيود المبيعات المرحلة غير مكتملة"
                !hasTaxProfile -> "يجب ضبط ملف ضريبي مؤكد للمحطة"
                else -> "هذه ورقة عمل ضريبية؛ لم يتم بعد ربط فئات الضرائب وصناديق الإقرار بالنموذج القانوني الوطني، لذلك لا تُعامل كإقرار جاهز للتقديم"
            })
    }

    private fun findSalesVatSource(from: String, to: String): JSONObject {
        val gaps = JSONArray()
        if (!tableExists("sales_transactions")) {
            gaps.put("جدول sales_transactions غير موجود")
            return JSONObject().put("source_available", false).put("schema_gaps", gaps)
        }
        val c = columns("sales_transactions")
        val vatCol = listOf("vat_amount", "tax_amount", "vat_total", "tax_total", "tax_amount_total", "vat_total_amount")
            .firstOrNull { c.contains(it) }
        val dateCol = listOf("sale_date", "transaction_date", "business_day", "date", "created_at")
            .firstOrNull { c.contains(it) }
        if (vatCol == null) gaps.put("لا يوجد حقل صريح لضريبة المخرجات في sales_transactions")
        if (dateCol == null) gaps.put("لا يوجد حقل تاريخ موثوق في sales_transactions")
        if (!c.contains("id")) gaps.put("لا يوجد معرف فاتورة sales_transactions.id")
        if (!c.contains("station_id")) gaps.put("لا يوجد station_id في sales_transactions")
        if (!c.contains("status")) gaps.put("لا يوجد status يسمح بتمييز المبيعات المرحلة")
        if (!c.contains("is_deleted")) gaps.put("لا يوجد is_deleted لإثبات حالة السجل")
        if (!tableExists("journal_entries")) gaps.put("جدول journal_entries غير موجود للتحقق من قيد المبيعات")
        val jc = columns("journal_entries")
        if (!jc.containsAll(setOf("id", "station_id", "reference_type", "reference_id", "status", "is_deleted"))) {
            gaps.put("حقول journal_entries لا تكفي لإثبات قيد مبيعات مرحّل")
        }
        if (vatCol == null || dateCol == null || !c.containsAll(setOf("id", "station_id", "status", "is_deleted")) ||
            !jc.containsAll(setOf("id", "station_id", "reference_type", "reference_id", "status", "is_deleted"))) {
            return JSONObject().put("source_available", false).put("source", "sales_transactions")
                .put("schema_gaps", gaps).put("output_vat", 0.0).put("document_count", 0)
        }
        val dateExpression = "substr(COALESCE(" + q(dateCol) + ",'') ,1,10)"
        val where = " station_id=? AND COALESCE(is_deleted,0)=0 AND lower(status) IN ('completed','posted','approved','paid') AND " +
            dateExpression + ">=? AND " + dateExpression + "<=?"
        val args = arrayOf(stationId.toString(), from, to)
        val sum = scalarDouble("SELECT COALESCE(SUM(" + q(vatCol) + "),0) FROM sales_transactions WHERE " + where, args)
        val count = scalar("SELECT COUNT(*) FROM sales_transactions WHERE " + where, args)
        val missingJournals = scalar(
            "SELECT COUNT(*) FROM sales_transactions s WHERE s.station_id=? AND COALESCE(s.is_deleted,0)=0 " +
                "AND lower(s.status) IN ('completed','posted','approved','paid') AND " +
                "substr(COALESCE(s." + q(dateCol) + ",'') ,1,10)>=? AND substr(COALESCE(s." + q(dateCol) + ",'') ,1,10)<=? " +
                "AND NOT EXISTS(SELECT 1 FROM journal_entries j WHERE j.station_id=s.station_id AND j.reference_type='sale' " +
                "AND j.reference_id=s.id AND j.status='posted' AND COALESCE(j.is_deleted,0)=0)",
            args
        )
        return JSONObject().put("source_available", true).put("source", "sales_transactions." + vatCol)
            .put("schema_gaps", gaps).put("output_vat", money(sum)).put("document_count", count)
            .put("completed_sales_without_posted_journal", missingJournals)
    }

    private fun cashFlowStatement(p: JSONObject): JSONObject {
        val from = parseDate(p.optString("from_date"))
        val to = parseDate(p.optString("to_date"))
        require(from <= to) { "نطاق قائمة التدفقات النقدية غير صحيح" }
        val missing = JSONArray()
        listOf("journal_entries", "journal_entry_items", "accounts").forEach { if (!tableExists(it)) missing.put("الجدول " + it + " غير موجود") }
        if (missing.length() > 0) return JSONObject().put("success", true).put("ready_for_approval", false).put("schema_gaps", missing)
        val jcols = columns("journal_entries")
        val icols = columns("journal_entry_items")
        val acols = columns("accounts")
        if (!jcols.containsAll(setOf("id", "station_id", "entry_date", "status", "is_deleted")) ||
            !icols.containsAll(setOf("journal_entry_id", "account_id", "debit", "credit")) ||
            !acols.containsAll(setOf("id", "account_code", "account_type", "is_cash_account", "is_bank_account"))) {
            missing.put("حقول القيد أو السطور أو تعريف حسابات النقد/البنوك غير مكتملة")
            return JSONObject().put("success", true).put("ready_for_approval", false).put("schema_gaps", missing)
        }
        val rows = journalLines(from, to)
        val byEntry = rows.groupBy { it.journalId }
        val sums = linkedMapOf("operating" to 0.0, "investing" to 0.0, "financing" to 0.0)
        val details = JSONArray()
        var unclassifiedAmount = 0.0
        var unclassifiedCount = 0
        for ((journalId, lines) in byEntry) {
            val cashLines = lines.filter { it.cash }
            val cashNet = money(cashLines.sumOf { it.debit - it.credit })
            if (abs(cashNet) <= 0.005) continue
            val counterpart = lines.filter { !it.cash && abs(it.debit - it.credit) > 0.005 }
            val totalWeight = counterpart.sumOf { abs(it.debit - it.credit) }
            if (counterpart.isEmpty() || totalWeight <= 0.005) {
                unclassifiedAmount += abs(cashNet); unclassifiedCount++
                details.put(JSONObject().put("journal_entry_id", journalId).put("net_cash_change", cashNet)
                    .put("classification", "unclassified").put("reason", "لا توجد سطور مقابلة قابلة للتصنيف"))
                continue
            }
            var knownWeight = 0.0
            for (line in counterpart) {
                val category = classification(line.accountId, "cash_flow")
                if (category !in setOf("operating", "investing", "financing")) continue
                val share = abs(cashNet) * abs(line.debit - line.credit) / totalWeight
                sums[category] = (sums[category] ?: 0.0) + if (cashNet > 0) share else -share
                knownWeight += abs(line.debit - line.credit)
            }
            val unknownWeight = (totalWeight - knownWeight).coerceAtLeast(0.0)
            if (unknownWeight > 0.005) {
                val portion = abs(cashNet) * unknownWeight / totalWeight
                unclassifiedAmount += portion; unclassifiedCount++
            }
            details.put(JSONObject().put("journal_entry_id", journalId).put("net_cash_change", cashNet)
                .put("classified", money(abs(cashNet) - abs(cashNet) * unknownWeight / totalWeight))
                .put("unclassified", money(abs(cashNet) * unknownWeight / totalWeight)))
        }
        val beginning = cashBalanceBefore(from)
        val net = money(sums.values.sum())
        val endingCalculated = money(beginning + net)
        val currentPeriodIsToday = to == today()
        val currentBalance = if (currentPeriodIsToday) cashAndBankCurrentBalance() else JSONObject.NULL
        val closingVariance = if (currentPeriodIsToday) money((currentBalance as Double) - endingCalculated) else JSONObject.NULL
        val ready = unclassifiedCount == 0 && abs(unclassifiedAmount) <= 0.01 &&
            (!currentPeriodIsToday || abs(closingVariance as Double) <= 0.01)
        return JSONObject().put("success", true).put("station_id", stationId).put("from_date", from).put("to_date", to)
            .put("operating_cash_flow", money(sums["operating"] ?: 0.0))
            .put("investing_cash_flow", money(sums["investing"] ?: 0.0))
            .put("financing_cash_flow", money(sums["financing"] ?: 0.0))
            .put("net_change_in_cash", net).put("beginning_cash_and_bank", money(beginning))
            .put("calculated_ending_cash_and_bank", endingCalculated).put("current_book_cash_and_bank", currentBalance)
            .put("closing_cash_variance_against_current_books", closingVariance)
            .put("current_book_comparison_applicable", currentPeriodIsToday)
            .put("unclassified_cash_amount", money(unclassifiedAmount)).put("unclassified_journal_count", unclassifiedCount)
            .put("classification_details", details).put("ready_for_approval", ready)
            .put("statement_basis", "قيود دفتر الأستاذ العامة المرحلة فقط؛ التحويلات الداخلية ذات صافي نقدي صفر مستبعدة")
            .put("approval_block_reason", when {
                unclassifiedCount > 0 || abs(unclassifiedAmount) > 0.01 -> "توجد حركات نقدية مقابلة لحسابات لم تُصنّف تشغيلياً أو استثمارياً أو تمويلياً"
                currentPeriodIsToday && abs(closingVariance as Double) > 0.01 -> "الرصيد الختامي المحسوب لا يطابق إجمالي دفاتر الصندوق والبنك الحالية"
                else -> JSONObject.NULL
            })
    }

    private fun equityChanges(p: JSONObject): JSONObject {
        val from = parseDate(p.optString("from_date"))
        val to = parseDate(p.optString("to_date"))
        require(from <= to) { "نطاق قائمة التغيرات في حقوق الملكية غير صحيح" }
        if (!tableExists("accounts") || !tableExists("journal_entries") || !tableExists("journal_entry_items")) {
            return JSONObject().put("success", true).put("ready_for_approval", false)
                .put("schema_gaps", JSONArray().put("يلزم وجود الحسابات والقيود وقيودها التفصيلية"))
        }
        val acols = columns("accounts")
        val jcols = columns("journal_entries")
        if (!acols.containsAll(setOf("id", "account_code", "account_type", "opening_balance")) ||
            !jcols.containsAll(setOf("id", "station_id", "entry_date", "status", "is_deleted"))) {
            return JSONObject().put("success", true).put("ready_for_approval", false)
                .put("schema_gaps", JSONArray().put("حقول حسابات حقوق الملكية وتواريخ القيود غير مكتملة"))
        }
        val equityTypes = listOf("equity", "capital", "owner_equity")
        val accountStationScope = if (acols.contains("station_id")) " AND station_id=?" else ""
        val accountArgs = if (acols.contains("station_id")) arrayOf(stationId.toString()) else emptyArray()
        val accounts = query(
            "SELECT id,account_code,COALESCE(" + q(firstColumn(acols, listOf("account_name", "name", "account_name_ar")) ?: "account_code") + ",'') ," +
                "lower(COALESCE(account_type,'')),COALESCE(opening_balance,0) FROM accounts " +
                "WHERE COALESCE(is_active,1)=1 AND COALESCE(is_deleted,0)=0" + accountStationScope + " ORDER BY account_code",
            accountArgs
        ) { c -> EquityAccount(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getDouble(4)) }
            .filter { it.type in equityTypes }
        val details = JSONArray()
        var openingTotal = 0.0
        var periodChange = 0.0
        var contributions = 0.0
        var distributions = 0.0
        var other = 0.0
        var unclassified = 0.0
        var currentProfit = 0.0
        var unmatchedEquityAccounts = 0
        for (a in accounts) {
            val prior = scalarDouble(
                "SELECT COALESCE(SUM(i.credit-i.debit),0) FROM journal_entry_items i JOIN journal_entries j ON j.id=i.journal_entry_id " +
                    "WHERE i.account_id=? AND j.station_id=? AND j.status='posted' AND COALESCE(j.is_deleted,0)=0 AND j.entry_date<?",
                arrayOf(a.id.toString(), stationId.toString(), from)
            )
            val change = scalarDouble(
                "SELECT COALESCE(SUM(i.credit-i.debit),0) FROM journal_entry_items i JOIN journal_entries j ON j.id=i.journal_entry_id " +
                    "WHERE i.account_id=? AND j.station_id=? AND j.status='posted' AND COALESCE(j.is_deleted,0)=0 AND j.entry_date>=? AND j.entry_date<=?",
                arrayOf(a.id.toString(), stationId.toString(), from, to)
            )
            val opening = a.opening + prior
            val category = classification(a.id, "equity")
            when (category) {
                "capital_contribution" -> contributions += change
                "owner_distribution" -> distributions += change
                "retained_earnings" -> other += change
                "other" -> other += change
                else -> if (abs(change) > 0.005) { unclassified += abs(change); unmatchedEquityAccounts++ }
            }
            openingTotal += opening
            periodChange += change
            details.put(JSONObject().put("account_id", a.id).put("account_code", a.code).put("account_name", a.name)
                .put("account_type", a.type).put("classification", category.ifBlank { "unclassified" })
                .put("opening_equity", money(opening)).put("period_movement_credit_less_debit", money(change))
                .put("closing_equity_before_current_result", money(opening + change)))
        }
        val pnl = periodProfitAndLoss(from, to)
        currentProfit = pnl.first
        val possibleClosingJournals = scalar(
            "SELECT COUNT(*) FROM journal_entries WHERE station_id=? AND status='posted' AND COALESCE(is_deleted,0)=0 " +
                "AND entry_date>=? AND entry_date<=? AND (lower(COALESCE(reference_type,'')) LIKE '%clos%' OR lower(COALESCE(entry_type,'')) LIKE '%clos%' OR lower(COALESCE(reference_type,'')) LIKE '%retained%')",
            arrayOf(stationId.toString(), from, to)
        )
        val ready = accounts.isNotEmpty() && unmatchedEquityAccounts == 0 && possibleClosingJournals == 0 && pnl.second
        val closing = money(openingTotal + periodChange + currentProfit)
        return JSONObject().put("success", true).put("station_id", stationId).put("from_date", from).put("to_date", to)
            .put("opening_equity", money(openingTotal)).put("capital_contributions_net", money(contributions))
            .put("owner_distributions_net", money(distributions)).put("other_equity_movements_net", money(other))
            .put("period_net_profit_or_loss", money(currentProfit)).put("equity_account_movements_net", money(periodChange))
            .put("calculated_closing_equity", closing).put("equity_accounts", details)
            .put("unclassified_equity_movement_absolute", money(unclassified)).put("unclassified_equity_account_count", unmatchedEquityAccounts)
            .put("possible_profit_closing_entries", possibleClosingJournals).put("p_and_l_schema_complete", pnl.second)
            .put("ready_for_approval", ready)
            .put("approval_block_reason", when {
                accounts.isEmpty() -> "لا توجد حسابات مصنفة صراحةً ضمن حقوق الملكية"
                !pnl.second -> "تعذر إثبات نتيجة الفترة من حسابات الإيرادات والمصروفات"
                unmatchedEquityAccounts > 0 -> "توجد حركة حقوق ملكية دون تصنيف مساهمة/توزيع/أرباح مبقاة"
                possibleClosingJournals > 0 -> "رُصدت قيود إقفال محتملة؛ راجع احتمال تكرار إدراج نتيجة الفترة"
                else -> JSONObject.NULL
            })
    }

    private fun periodProfitAndLoss(from: String, to: String): Pair<Double, Boolean> {
        val ac = columns("accounts")
        val jc = columns("journal_entries")
        if (!ac.containsAll(setOf("id", "account_type")) || !jc.containsAll(setOf("id", "station_id", "entry_date", "status", "is_deleted"))) return 0.0 to false
        val accountScope = if (ac.contains("station_id")) " AND station_id=?" else ""
        val accountArgs = if (ac.contains("station_id")) arrayOf(stationId.toString()) else emptyArray()
        val types = query(
            "SELECT id,lower(COALESCE(account_type,'')) FROM accounts WHERE COALESCE(is_active,1)=1 AND COALESCE(is_deleted,0)=0" + accountScope,
            accountArgs
        ) { c -> c.getLong(0) to c.getString(1) }
        var income = 0.0
        var expense = 0.0
        for ((id, type) in types) {
            val movement = scalarDouble(
                "SELECT COALESCE(SUM(i.credit-i.debit),0) FROM journal_entry_items i JOIN journal_entries j ON j.id=i.journal_entry_id " +
                    "WHERE i.account_id=? AND j.station_id=? AND j.status='posted' AND COALESCE(j.is_deleted,0)=0 AND j.entry_date>=? AND j.entry_date<=?",
                arrayOf(id.toString(), stationId.toString(), from, to)
            )
            if (type in listOf("income", "revenue", "sales", "other_income")) income += movement
            if (type in listOf("expense", "cost", "cogs", "cost_of_sales")) expense += -movement
        }
        val hasPnl = types.any { it.second in listOf("income", "revenue", "sales", "other_income", "expense", "cost", "cogs", "cost_of_sales") }
        return money(income - expense) to hasPnl
    }

    private fun saveTaxProfile(p: JSONObject): JSONObject {
        val jurisdiction = clean(p.optString("jurisdiction_code"), 30).uppercase(Locale.ROOT)
        val registration = clean(p.optString("tax_registration_number"), 120)
        val frequency = clean(p.optString("filing_frequency"), 30).lowercase(Locale.ROOT)
        val currency = p.optLong("tax_reporting_currency_id", 1L)
        val schemaVersion = clean(p.optString("tax_schema_version"), 80)
        require(jurisdiction.matches(Regex("[A-Z0-9_-]{2,30}"))) { "رمز الولاية/الدولة الضريبية مطلوب" }
        require(registration.isNotBlank()) { "رقم التسجيل الضريبي مطلوب" }
        require(frequency in setOf("monthly", "quarterly", "annually")) { "دورية الإقرار غير صحيحة" }
        require(currency > 0L) { "عملة الإقرار مطلوبة" }
        require(schemaVersion.isNotBlank()) { "إصدار مخطط الإقرار/التصنيف مطلوب؛ لا يُفترض نموذج ضريبي وطني تلقائياً" }
        val confirmed = p.optBoolean("confirm_tax_profile", false)
        db.insertWithOnConflict("expanded_tax_profiles", null, ContentValues().apply {
            put("station_id", stationId); put("jurisdiction_code", jurisdiction)
            put("tax_registration_number", registration); put("filing_frequency", frequency)
            put("tax_reporting_currency_id", currency); put("tax_schema_version", schemaVersion)
            put("is_confirmed", if (confirmed) 1 else 0)
            if (confirmed) { put("confirmed_by", userId); put("confirmed_at", now()) }
            put("updated_at", now())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        return JSONObject().put("success", true).put("jurisdiction_code", jurisdiction)
            .put("filing_frequency", frequency).put("is_confirmed", confirmed)
            .put("message", if (confirmed) "تم حفظ الملف الضريبي؛ يظل التقرير ورقة عمل حتى إضافة خرائط الإقرار الوطني" else "تم حفظ الملف الضريبي كمسودة غير معتمدة")
    }

    private fun taxProfileConfigured(): Boolean {
        if (!tableExists("expanded_tax_profiles")) return false
        val cols = columns("expanded_tax_profiles")
        if (!cols.containsAll(setOf("station_id", "jurisdiction_code", "tax_registration_number", "filing_frequency", "tax_schema_version", "is_confirmed"))) return false
        return scalar(
            "SELECT COUNT(*) FROM expanded_tax_profiles WHERE station_id=? AND COALESCE(is_confirmed,0)=1 " +
                "AND TRIM(COALESCE(jurisdiction_code,''))<>'' AND TRIM(COALESCE(tax_registration_number,''))<>'' " +
                "AND TRIM(COALESCE(filing_frequency,''))<>'' AND TRIM(COALESCE(tax_schema_version,''))<>''",
            arrayOf(stationId.toString())
        ) == 1
    }

    private fun saveAccountClassification(p: JSONObject): JSONObject {
        val accountId = p.optLong("account_id", 0L)
        val type = p.optString("classification_type").trim().lowercase(Locale.ROOT)
        val code = p.optString("classification_code").trim().lowercase(Locale.ROOT)
        requireValidAccount(accountId, setOf("asset", "liability", "equity", "capital", "owner_equity", "income", "revenue", "expense", "cost", "inventory"))
        val allowed = when (type) {
            "cash_flow" -> setOf("operating", "investing", "financing")
            "equity" -> setOf("capital_contribution", "owner_distribution", "retained_earnings", "other")
            else -> throw IllegalArgumentException("نوع تصنيف غير مدعوم")
        }
        require(code in allowed) { "رمز التصنيف غير مسموح" }
        db.insertWithOnConflict("expanded_finance_account_classification", null, ContentValues().apply {
            put("station_id", stationId); put("account_id", accountId); put("classification_type", type)
            put("classification_code", code); put("updated_by", userId); put("updated_at", now())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        return JSONObject().put("success", true).put("account_id", accountId).put("classification_type", type)
            .put("classification_code", code).put("message", "تم حفظ تصنيف الحساب في SQLite")
    }

    private fun importBankStatement(p: JSONObject): JSONObject {
        val bankAccountId = p.optLong("bank_account_id", 0L)
        require(bankAccountId > 0L) { "يجب تحديد الحساب البنكي" }
        val bank = query(
            "SELECT id,COALESCE(current_balance,0) FROM bank_accounts WHERE id=? AND station_id=? AND COALESCE(is_deleted,0)=0",
            arrayOf(bankAccountId.toString(), stationId.toString())
        ) { c -> c.getLong(0) to c.getDouble(1) }.firstOrNull()
            ?: throw IllegalArgumentException("الحساب البنكي غير موجود في المحطة الحالية")
        val format = p.optString("format", "csv").trim().lowercase(Locale.ROOT)
        val content = p.optString("content")
        require(content.isNotBlank() && content.length <= 5_000_000) { "محتوى كشف البنك فارغ أو يتجاوز الحد المسموح" }
        val parsed = when (format) {
            "csv", "txt" -> parseCsvStatement(content, p.optJSONObject("columns"))
            "ofx" -> parseOfxStatement(content)
            "json" -> parseJsonStatement(content)
            else -> throw IllegalArgumentException("صيغة الكشف غير مدعومة؛ استخدم CSV أو OFX أو JSON")
        }
        require(parsed.isNotEmpty() && parsed.size <= 50_000) { "الكشف فارغ أو يتجاوز 50000 حركة" }
        val fileHash = sha256(content)
        val statementRef = clean(p.optString("statement_reference"), 150).ifBlank { fileHash }
        val existing = query(
            "SELECT id,imported_count,duplicate_count FROM expanded_bank_statement_imports WHERE station_id=? AND bank_account_id=? AND statement_reference=?",
            arrayOf(stationId.toString(), bankAccountId.toString(), statementRef)
        ) { c -> JSONObject().put("id", c.getLong(0)).put("count", c.getInt(1)).put("duplicates", c.getInt(2)) }.firstOrNull()
        if (existing != null) return JSONObject().put("success", true).put("duplicate_import", true)
            .put("import_id", existing.getLong("id")).put("imported_count", existing.getInt("count"))
            .put("duplicate_count", existing.getInt("duplicates")).put("message", "هذا الكشف مستورد سابقاً ولم تُنشأ نسخة مكررة")
        val now = now()
        var importId = 0L
        var count = 0
        var duplicates = 0
        db.beginTransaction()
        try {
            importId = db.insertOrThrow("expanded_bank_statement_imports", null, ContentValues().apply {
                put("station_id", stationId); put("bank_account_id", bankAccountId); put("statement_reference", statementRef)
                put("file_name", clean(p.optString("file_name"), 250)); put("file_hash", fileHash)
                put("statement_start_date", p.optString("statement_start_date").takeIf { it.isNotBlank() }?.let { parseDate(it) })
                put("statement_end_date", p.optString("statement_end_date").takeIf { it.isNotBlank() }?.let { parseDate(it) })
                if (p.has("opening_balance") && !p.isNull("opening_balance")) put("declared_opening_balance", p.getDouble("opening_balance"))
                if (p.has("closing_balance") && !p.isNull("closing_balance")) put("declared_closing_balance", p.getDouble("closing_balance"))
                put("created_by", userId); put("created_at", now)
            })
            parsed.forEachIndexed { index, line ->
                val hash = sha256(bankAccountId.toString() + "|" + line.date + "|" + line.reference.trim().lowercase(Locale.ROOT) +
                    "|" + line.description.trim().lowercase(Locale.ROOT) + "|" + money(line.signedAmount).toString())
                val cv = ContentValues().apply {
                    put("station_id", stationId); put("import_id", importId); put("bank_account_id", bankAccountId)
                    put("row_number", index + 1); put("posted_date", line.date); put("description", clean(line.description, 1000))
                    put("reference", clean(line.reference, 250)); put("external_id", clean(line.externalId, 250))
                    put("signed_amount", money(line.signedAmount)); put("row_hash", hash)
                    put("match_status", "unmatched"); put("match_rule", ""); put("created_at", now)
                }
                val lineId = db.insertWithOnConflict("expanded_bank_statement_lines", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                if (lineId < 0L) { duplicates++; return@forEachIndexed }
                count++
                autoMatchStatementLine(lineId, bankAccountId, line)
            }
            db.update("expanded_bank_statement_imports", ContentValues().apply {
                put("imported_count", count); put("duplicate_count", duplicates)
            }, "id=? AND station_id=?", arrayOf(importId.toString(), stationId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return JSONObject().put("success", true).put("import_id", importId).put("bank_account_id", bank.first)
            .put("imported_count", count).put("duplicate_count", duplicates).put("source_row_count", parsed.size)
            .put("auto_matched_count", scalar("SELECT COUNT(*) FROM expanded_bank_statement_lines WHERE import_id=? AND match_status='matched'", arrayOf(importId.toString())))
            .put("reconciliation_url_action", "bank_reconciliation")
            .put("message", "استورد الكشف إلى SQLite مع بصمة لمنع التكرار؛ الحركات الملتبسة تبقى غير مطابقة للمراجعة")
    }

    private fun bankStatementBatches(p: JSONObject): JSONObject {
        val bankCols = columns("bank_accounts")
        val nameCol = firstColumn(bankCols, listOf("account_name", "name", "account_name_ar"))
        val nameExpr = if (nameCol != null) "COALESCE(b." + q(nameCol) + ",'')" else "CAST(i.bank_account_id AS TEXT)"
        val rows = query(
            "SELECT i.id,i.bank_account_id," + nameExpr + ",i.statement_reference,i.file_name," +
                "i.statement_start_date,i.statement_end_date,i.declared_opening_balance,i.declared_closing_balance," +
                "i.imported_count,i.duplicate_count,i.created_at FROM expanded_bank_statement_imports i " +
                "LEFT JOIN bank_accounts b ON b.id=i.bank_account_id WHERE i.station_id=? ORDER BY i.id DESC LIMIT ?",
            arrayOf(stationId.toString(), p.optInt("limit", 100).coerceIn(1, 500).toString())
        ) { c -> JSONObject().put("import_id", c.getLong(0)).put("bank_account_id", c.getLong(1))
            .put("bank_account_name", c.getString(2)).put("statement_reference", c.getString(3))
            .put("file_name", c.getString(4)).put("statement_start_date", c.getString(5))
            .put("statement_end_date", c.getString(6)).put("declared_opening_balance", if (c.isNull(7)) JSONObject.NULL else c.getDouble(7))
            .put("declared_closing_balance", if (c.isNull(8)) JSONObject.NULL else c.getDouble(8))
            .put("imported_count", c.getInt(9)).put("duplicate_count", c.getInt(10)).put("created_at", c.getString(11)) }
        return JSONObject().put("success", true).put("data", JSONArray(rows)).put("count", rows.size)
    }

    private fun bankReconciliation(p: JSONObject): JSONObject {
        val importId = p.optLong("import_id", 0L)
        require(importId > 0L) { "يجب تحديد كشف البنك المستورد" }
        val header = query(
            "SELECT id,bank_account_id,statement_reference,statement_start_date,statement_end_date," +
                "declared_opening_balance,declared_closing_balance,imported_count,duplicate_count FROM expanded_bank_statement_imports " +
                "WHERE id=? AND station_id=?", arrayOf(importId.toString(), stationId.toString())
        ) { c -> JSONObject().put("id", c.getLong(0)).put("bank_account_id", c.getLong(1)).put("statement_reference", c.getString(2))
            .put("start_date", if (c.isNull(3)) JSONObject.NULL else c.getString(3)).put("end_date", if (c.isNull(4)) JSONObject.NULL else c.getString(4))
            .put("opening", if (c.isNull(5)) JSONObject.NULL else c.getDouble(5)).put("closing", if (c.isNull(6)) JSONObject.NULL else c.getDouble(6))
            .put("imported", c.getInt(7)).put("duplicates", c.getInt(8)) }.firstOrNull()
            ?: throw IllegalArgumentException("كشف البنك غير موجود في المحطة الحالية")
        val lines = query(
            "SELECT id,row_number,posted_date,description,reference,external_id,signed_amount,match_status,matched_ledger_id,match_rule " +
                "FROM expanded_bank_statement_lines WHERE import_id=? AND station_id=? ORDER BY posted_date,row_number",
            arrayOf(importId.toString(), stationId.toString())
        ) { c -> JSONObject().put("line_id", c.getLong(0)).put("row_number", c.getInt(1)).put("date", c.getString(2))
            .put("description", c.getString(3)).put("reference", c.getString(4)).put("external_id", c.getString(5))
            .put("signed_amount", c.getDouble(6)).put("match_status", c.getString(7))
            .put("matched_ledger_id", if (c.isNull(8)) JSONObject.NULL else c.getLong(8)).put("match_rule", c.getString(9)) }
        val importedTotal = lines.sumOf { it.getDouble("signed_amount") }
        val matched = lines.count { it.optString("match_status") == "matched" }
        val ignored = lines.count { it.optString("match_status") == "ignored" }
        val unmatched = lines.size - matched - ignored
        val bankAccountId = header.getLong("bank_account_id")
        val ledgerColumns = columns("bank_ledger")
        val dateCol = firstColumn(ledgerColumns, listOf("transaction_date", "entry_date", "posted_date", "date", "created_at"))
        val ledgerScope = (if (ledgerColumns.contains("station_id")) " AND station_id=?" else "") +
            (if (ledgerColumns.contains("is_deleted")) " AND COALESCE(is_deleted,0)=0" else "")
        val args = mutableListOf(bankAccountId.toString())
        if (ledgerColumns.contains("station_id")) args.add(stationId.toString())
        val dateScopeAvailable = dateCol != null && !header.isNull("start_date") && !header.isNull("end_date")
        val ledgerWhereDate = if (dateScopeAvailable) {
            args.add(header.getString("start_date")); args.add(header.getString("end_date"))
            " AND substr(COALESCE(" + q(dateCol!!) + ",'') ,1,10)>=? AND substr(COALESCE(" + q(dateCol!!) + ",'') ,1,10)<=?"
        } else ""
        val unmatchedLedger = if (!dateScopeAvailable || !tableExists("bank_ledger")) -1 else {
            args.add(importId.toString())
            args.add(stationId.toString())
            scalar(
                "SELECT COUNT(*) FROM bank_ledger WHERE bank_account_id=?" + ledgerScope + ledgerWhereDate +
                    " AND id NOT IN (SELECT matched_ledger_id FROM expanded_bank_statement_lines " +
                    "WHERE import_id=? AND station_id=? AND match_status='matched' AND matched_ledger_id IS NOT NULL)",
                args.toTypedArray()
            )
        }
        val statementExpectedClosing = if (!header.isNull("opening")) money(header.getDouble("opening") + importedTotal) else JSONObject.NULL
        val closingDifference = if (!header.isNull("closing") && !header.isNull("opening")) {
            money(header.getDouble("closing") - (header.getDouble("opening") + importedTotal))
        } else JSONObject.NULL
        val bookBalance = scalarDouble("SELECT COALESCE(current_balance,0) FROM bank_accounts WHERE id=? AND station_id=?",
            arrayOf(bankAccountId.toString(), stationId.toString()))
        val candidates = JSONArray()
        for (i in 0 until lines.size) {
            val line = lines[i]
            if (line.optString("match_status") == "matched" || line.optString("match_status") == "ignored") continue
            val lineCandidates = findLedgerCandidates(bankAccountId, line.getString("date"),
                line.getString("reference"), line.getDouble("signed_amount"))
            candidates.put(JSONObject().put("line_id", line.getLong("line_id")).put("candidate_count", lineCandidates.length())
                .put("candidates", lineCandidates))
        }
        return JSONObject().put("success", true).put("station_id", stationId).put("statement", header)
             .put("transaction_count", lines.size).put("matched_count", matched).put("unmatched_count", unmatched)
            .put("ignored_count", ignored).put("unmatched_book_ledger_count", unmatchedLedger)
            .put("date_scoped_ledger_comparison_available", dateScopeAvailable)
            .put("opening_and_closing_balances_provided", !header.isNull("opening") && !header.isNull("closing"))
            .put("statement_transaction_net", money(importedTotal)).put("calculated_statement_closing", statementExpectedClosing)
            .put("declared_closing_difference", closingDifference).put("current_book_balance", money(bookBalance))
            .put("book_balance_is_current_not_historical", true).put("match_candidates", candidates)
            .put("lines", lines).put("fully_reconciled", unmatched == 0 && unmatchedLedger == 0 && dateScopeAvailable &&
                !header.isNull("opening") && !header.isNull("closing") && header.optInt("duplicates") == 0 &&
                closingDifference is Double && abs(closingDifference) <= 0.01)
            .put("message", "المطابقة سطر بسطر؛ رصيد الدفتر الحالي لا يُقارن مباشرة برصيد كشف تاريخي إلا بعد مراعاة الحركات اللاحقة")
    }

    private fun matchBankStatementLine(p: JSONObject): JSONObject {
        val lineId = p.optLong("line_id", 0L)
        require(lineId > 0L) { "معرف سطر الكشف مطلوب" }
        val row = query(
            "SELECT id,bank_account_id,signed_amount,match_status,matched_ledger_id FROM expanded_bank_statement_lines WHERE id=? AND station_id=?",
            arrayOf(lineId.toString(), stationId.toString())
        ) { c -> JSONObject().put("id", c.getLong(0)).put("bank_account_id", c.getLong(1))
            .put("amount", c.getDouble(2)).put("status", c.getString(3))
            .put("matched", if (c.isNull(4)) 0L else c.getLong(4)) }.firstOrNull()
            ?: throw IllegalArgumentException("سطر الكشف غير موجود في المحطة الحالية")
        val action = p.optString("action").lowercase(Locale.ROOT)
        val reasonForAudit = clean(p.optString("reason"), 500)
        var newLedgerId: Long? = null
        when (action) {
            "unmatch" -> {
                db.update("expanded_bank_statement_lines", ContentValues().apply {
                    put("match_status", "unmatched"); putNull("matched_ledger_id"); put("match_rule", "تم إلغاء المطابقة يدوياً")
                    put("updated_at", now()); put("updated_by", userId)
                }, "id=? AND station_id=?", arrayOf(lineId.toString(), stationId.toString()))
            }
            "ignore" -> {
                val reason = clean(p.optString("reason"), 500)
                require(reason.length >= 3) { "سبب استثناء السطر مطلوب" }
                db.update("expanded_bank_statement_lines", ContentValues().apply {
                    put("match_status", "ignored"); putNull("matched_ledger_id"); put("match_rule", "استثناء: " + reason)
                    put("updated_at", now()); put("updated_by", userId)
                }, "id=? AND station_id=?", arrayOf(lineId.toString(), stationId.toString()))
            }
            "match" -> {
                val ledgerId = p.optLong("bank_ledger_id", 0L)
                require(ledgerId > 0L) { "يجب تحديد حركة الدفتر المراد مطابقتها" }
                val ledgerCols = columns("bank_ledger")
                val scopeSql = (if (ledgerCols.contains("station_id")) " AND station_id=?" else "") +
                    (if (ledgerCols.contains("is_deleted")) " AND COALESCE(is_deleted,0)=0" else "")
                val ledgerArgs = mutableListOf(ledgerId.toString(), row.getLong("bank_account_id").toString())
                if (ledgerCols.contains("station_id")) ledgerArgs.add(stationId.toString())
                val ledger = query("SELECT id,COALESCE(debit,0),COALESCE(credit,0) FROM bank_ledger WHERE id=? AND bank_account_id=?" + scopeSql,
                    ledgerArgs.toTypedArray()) { c ->
                    Triple(c.getLong(0), c.getDouble(1), c.getDouble(2))
                }.firstOrNull() ?: throw IllegalArgumentException("حركة الدفتر لا تخص الحساب البنكي المحدد")
                require(abs(money(ledger.second - ledger.third) - row.getDouble("amount")) <= 0.01) {
                    "لا تتطابق قيمة أو جهة الحركة مع حركة دفتر البنك"
                }
                val already = scalar("SELECT COUNT(*) FROM expanded_bank_statement_lines WHERE station_id=? AND matched_ledger_id=? AND match_status='matched' AND id<>?",
                    arrayOf(stationId.toString(), ledgerId.toString(), lineId.toString()))
                require(already == 0) { "حركة دفتر البنك مطابقة بالفعل لسطر كشف آخر" }
                newLedgerId = ledgerId
                db.update("expanded_bank_statement_lines", ContentValues().apply {
                    put("match_status", "matched"); put("matched_ledger_id", ledgerId); put("match_rule", "مطابقة يدوية بعد التحقق من الحساب والقيمة والاتجاه")
                    put("updated_at", now()); put("updated_by", userId)
                }, "id=? AND station_id=?", arrayOf(lineId.toString(), stationId.toString()))
            }
            else -> throw IllegalArgumentException("إجراء المطابقة غير معروف")
        }
        val resulting = query(
            "SELECT match_status,matched_ledger_id FROM expanded_bank_statement_lines WHERE id=? AND station_id=?",
            arrayOf(lineId.toString(), stationId.toString())
        ) { c -> JSONObject().put("status", c.getString(0)).put("ledger_id", if (c.isNull(1)) JSONObject.NULL else c.getLong(1)) }
            .firstOrNull() ?: throw IllegalStateException("تعذر إعادة قراءة سطر المطابقة بعد الحفظ")
        val audit = ContentValues().apply {
            put("station_id", stationId); put("statement_line_id", lineId)
            put("bank_account_id", row.getLong("bank_account_id")); put("action", action)
            put("previous_status", row.getString("status")); put("new_status", resulting.getString("status"))
            if (row.optLong("matched", 0L) > 0L) put("previous_ledger_id", row.optLong("matched")) else putNull("previous_ledger_id")
            if (!resulting.isNull("ledger_id")) put("new_ledger_id", resulting.getLong("ledger_id")) else putNull("new_ledger_id")
            put("reason", reasonForAudit); put("actor_user_id", userId); put("occurred_at", now())
        }
        db.insertOrThrow("expanded_bank_statement_match_audit", null, audit)
        return JSONObject().put("success", true).put("line_id", lineId)
            .put("action", action).put("new_status", resulting.getString("status"))
            .put("message", "تم تحديث حالة المطابقة في SQLite وتسجيل حدث التدقيق الدائم")
    }

    private fun autoMatchStatementLine(lineId: Long, bankAccountId: Long, line: ImportedLine) {
        val candidates = findLedgerCandidates(bankAccountId, line.date, line.reference, line.signedAmount)
        if (candidates.length() != 1) return
        val candidate = candidates.getJSONObject(0)
        val hasStrongEvidence = candidate.optBoolean("date_exact") || candidate.optBoolean("reference_exact")
        if (!hasStrongEvidence) return
        val ledgerId = candidate.optLong("bank_ledger_id")
        val existing = scalar("SELECT COUNT(*) FROM expanded_bank_statement_lines WHERE station_id=? AND matched_ledger_id=? AND match_status='matched'",
            arrayOf(stationId.toString(), ledgerId.toString()))
        if (existing > 0) return
        db.update("expanded_bank_statement_lines", ContentValues().apply {
            put("match_status", "matched"); put("matched_ledger_id", ledgerId)
            put("match_rule", if (candidate.optBoolean("reference_exact")) "مطابقة تلقائية بقيمة واتجاه ومرجع متطابق" else "مطابقة تلقائية بقيمة واتجاه وتاريخ متطابقين مع مرشح وحيد")
        }, "id=? AND station_id=?", arrayOf(lineId.toString(), stationId.toString()))
    }

    private fun findLedgerCandidates(bankAccountId: Long, date: String, reference: String, amount: Double): JSONArray {
        if (!tableExists("bank_ledger")) return JSONArray()
        val cols = columns("bank_ledger")
        if (!cols.containsAll(setOf("id", "bank_account_id", "debit", "credit"))) return JSONArray()
        val dateCol = firstColumn(cols, listOf("transaction_date", "entry_date", "posted_date", "date", "created_at"))
        val refCol = firstColumn(cols, listOf("reference", "reference_number", "reference_no", "external_reference", "description"))
        val selectDate = if (dateCol != null) ",COALESCE(" + q(dateCol) + ",'')" else ",''"
        val selectRef = if (refCol != null) ",COALESCE(" + q(refCol) + ",'')" else ",''"
        val scope = (if (cols.contains("station_id")) " AND station_id=?" else "") +
            (if (cols.contains("is_deleted")) " AND COALESCE(is_deleted,0)=0" else "")
        val args = mutableListOf(bankAccountId.toString())
        if (cols.contains("station_id")) args.add(stationId.toString())
        val queryRows = query("SELECT id,COALESCE(debit,0),COALESCE(credit,0)" + selectDate + selectRef +
            " FROM bank_ledger WHERE bank_account_id=?" + scope + " ORDER BY id DESC LIMIT 2000", args.toTypedArray()) { c ->
            JSONObject().put("bank_ledger_id", c.getLong(0)).put("signed_amount", money(c.getDouble(1) - c.getDouble(2)))
                .put("date", c.getString(3)).put("reference", c.getString(4))
        }
        val result = JSONArray()
        for (i in 0 until queryRows.size) {
            val row = queryRows[i]
            if (abs(row.optDouble("signed_amount") - amount) > 0.01) continue
            val exactDate = date.isNotBlank() && row.optString("date").take(10) == date
            val exactRef = reference.isNotBlank() && row.optString("reference").isNotBlank() &&
                row.optString("reference").trim().equals(reference.trim(), ignoreCase = true)
            result.put(JSONObject().put("bank_ledger_id", row.getLong("bank_ledger_id"))
                .put("signed_amount", row.getDouble("signed_amount")).put("ledger_date", row.optString("date"))
                .put("ledger_reference", row.optString("reference")).put("date_exact", exactDate).put("reference_exact", exactRef))
        }
        return result
    }

    private fun parseCsvStatement(content: String, mapping: JSONObject?): List<ImportedLine> {
        val rows = parseCsvRows(content)
        require(rows.size >= 2) { "ملف CSV يجب أن يحتوي على عناوين وأحد الحركات على الأقل" }
        val headers = rows.first().map { it.trim() }
        fun find(key: String, candidates: List<String>): Int {
            val override = mapping?.optString(key)?.trim().orEmpty()
            if (override.isNotBlank()) {
                val indexByName = headers.indexOfFirst { it.equals(override, true) }
                if (indexByName >= 0) return indexByName
                val indexByNumber = override.toIntOrNull()
                if (indexByNumber != null && indexByNumber in headers.indices) return indexByNumber
            }
            return headers.indexOfFirst { h ->
                val n = h.lowercase(Locale.ROOT).replace(" ", "").replace("_", "").replace("-", "")
                candidates.any { candidate -> n.contains(candidate) }
            }
        }
        val dateIndex = find("date", listOf("date", "postingdate", "valuedate", "تاريخ", "اليوم"))
        val descIndex = find("description", listOf("description", "narrative", "details", "memo", "البيان", "الوصف", "تفاصيل"))
        val refIndex = find("reference", listOf("reference", "refno", "transactionid", "checkno", "مرجع", "رقم"))
        val debitIndex = find("debit", listOf("debit", "withdrawal", "moneyout", "مدين", "سحب", "خصم"))
        val creditIndex = find("credit", listOf("credit", "deposit", "moneyin", "دائن", "إيداع", "إيداع"))
        val amountIndex = find("amount", listOf("amount", "transactionamount", "المبلغ", "قيمة"))
        val typeIndex = find("type", listOf("type", "direction", "نوع", "اتجاه"))
        require(dateIndex >= 0) { "لم يتم العثور على عمود التاريخ؛ استخدم columns.date لتحديده" }
        require((debitIndex >= 0 && creditIndex >= 0) || amountIndex >= 0) {
            "لم يتم العثور على عمودي المدين والدائن أو عمود مبلغ؛ استخدم تعيين الأعمدة"
        }
        val result = mutableListOf<ImportedLine>()
        rows.drop(1).forEachIndexed { i, row ->
            if (row.all { it.isBlank() }) return@forEachIndexed
            val date = parseDate(row.getOrNull(dateIndex).orEmpty())
            val debit = if (debitIndex >= 0) number(row.getOrNull(debitIndex).orEmpty()) else 0.0
            val credit = if (creditIndex >= 0) number(row.getOrNull(creditIndex).orEmpty()) else 0.0
            val rawAmount = if (amountIndex >= 0) number(row.getOrNull(amountIndex).orEmpty()) else credit - debit
            val type = row.getOrNull(typeIndex).orEmpty().lowercase(Locale.ROOT)
            val amount = if (debitIndex >= 0 && creditIndex >= 0) {
                require(debit >= 0 && credit >= 0 && !(debit > 0.005 && credit > 0.005)) {
                    "السطر " + (i + 2) + " يحتوي مبلغاً في عمودي المدين والدائن معاً أو مبلغاً سالباً"
                }
                credit - debit
            } else when {
                type.contains("debit") || type.contains("withdraw") || type.contains("مدين") || type.contains("سحب") -> -abs(rawAmount)
                type.contains("credit") || type.contains("deposit") || type.contains("دائن") || type.contains("إيداع") -> abs(rawAmount)
                else -> rawAmount
            }
            require(amount.isFinite() && abs(amount) > 0.000001) { "المبلغ في السطر " + (i + 2) + " فارغ أو يساوي صفراً" }
            result.add(ImportedLine(date, row.getOrNull(descIndex).orEmpty(), row.getOrNull(refIndex).orEmpty(), "", amount))
        }
        return result
    }

    private fun parseOfxStatement(content: String): List<ImportedLine> {
        val matches = Regex("(?is)<STMTTRN>(.*?)</STMTTRN>").findAll(content)
        val result = mutableListOf<ImportedLine>()
        for (m in matches) {
            val block = m.groupValues[1]
            fun tag(name: String): String = Regex("(?is)<" + name + ">([^<\\r\\n]+)").find(block)?.groupValues?.get(1)?.trim().orEmpty()
            val rawDate = tag("DTPOSTED").take(8)
            val amount = number(tag("TRNAMT"))
            require(rawDate.isNotBlank() && amount.isFinite() && abs(amount) > 0.000001) { "سطر OFX لا يحتوي تاريخاً ومبلغاً صالحين" }
            result.add(ImportedLine(parseDate(rawDate), tag("NAME") + " " + tag("MEMO"), tag("CHECKNUM"), tag("FITID"), amount))
        }
        require(result.isNotEmpty()) { "لم يتم العثور على حركات STMTTRN داخل ملف OFX" }
        return result
    }

    private fun parseJsonStatement(content: String): List<ImportedLine> {
        val root = JSONObject(content)
        val array = root.optJSONArray("transactions") ?: root.optJSONArray("data")
            ?: throw IllegalArgumentException("كشف JSON يحتاج إلى مصفوفة transactions")
        val result = mutableListOf<ImportedLine>()
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: throw IllegalArgumentException("سطر JSON غير صالح")
            val date = parseDate(row.optString("date", row.optString("posted_date")))
            val signed = when {
                row.has("signed_amount") -> row.optDouble("signed_amount")
                row.has("amount") -> row.optDouble("amount")
                else -> row.optDouble("credit") - row.optDouble("debit")
            }
            require(signed.isFinite() && abs(signed) > 0.000001) { "مبلغ سطر JSON غير صالح" }
            result.add(ImportedLine(date, row.optString("description"), row.optString("reference"), row.optString("external_id"), signed))
        }
        return result
    }

    private fun parseCsvRows(value: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            when {
                ch == '"' && quoted && i + 1 < value.length && value[i + 1] == '"' -> { field.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                (ch == ',' || ch == ';' || ch == '\t') && !quoted -> { row.add(field.toString()); field.setLength(0) }
                (ch == '\n' || ch == '\r') && !quoted -> {
                    if (ch == '\r' && i + 1 < value.length && value[i + 1] == '\n') i++
                    row.add(field.toString()); field.setLength(0)
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = mutableListOf()
                }
                else -> field.append(ch)
            }
            i++
        }
        row.add(field.toString())
        if (row.any { it.isNotBlank() }) rows.add(row)
        require(!quoted) { "ملف CSV يحتوي اقتباساً غير مغلق" }
        return rows
    }

    private fun journalLines(from: String, to: String): List<JournalLine> {
        return query(journalLineSql("j.entry_date>=? AND j.entry_date<=?"), arrayOf(stationId.toString(), from, to)) { journalLineFromCursor(it) }
    }

    private fun journalLinesBefore(date: String): List<JournalLine> {
        return query(journalLineSql("j.entry_date<?"), arrayOf(stationId.toString(), date)) { journalLineFromCursor(it) }
    }

    private fun journalLineSql(dateWhere: String): String {
        val ac = columns("accounts")
        val nameCol = firstColumn(ac, listOf("account_name", "name", "account_name_ar"))
        val nameSql = if (nameCol != null) "COALESCE(a." + q(nameCol) + ",'')" else "COALESCE(a.account_code,'')"
        return "SELECT j.id,j.entry_date,COALESCE(j.reference_type,''),COALESCE(j.reference_id,0)," +
            "i.account_id,COALESCE(i.debit,0),COALESCE(i.credit,0),lower(COALESCE(a.account_type,''))," +
            "COALESCE(a.account_code,'')," + nameSql + ",COALESCE(a.is_cash_account,0),COALESCE(a.is_bank_account,0) " +
            "FROM journal_entries j JOIN journal_entry_items i ON i.journal_entry_id=j.id " +
            "JOIN accounts a ON a.id=i.account_id WHERE j.station_id=? AND j.status='posted' AND COALESCE(j.is_deleted,0)=0 AND " +
            dateWhere + " ORDER BY j.entry_date,j.id,i.id"
    }

    private fun journalLineFromCursor(c: Cursor): JournalLine {
        return JournalLine(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4),
            c.getDouble(5), c.getDouble(6), c.getString(7), c.getString(8), c.getString(9),
            c.getInt(10) == 1 || c.getInt(11) == 1)
    }

    private fun cashBalanceBefore(date: String): Double {
        var balance = 0.0
        if (tableExists("cash_boxes")) {
            balance += scalarDouble("SELECT COALESCE(SUM(opening_balance),0) FROM cash_boxes WHERE station_id=? AND COALESCE(is_deleted,0)=0",
                arrayOf(stationId.toString()))
        }
        if (tableExists("bank_accounts")) {
            balance += scalarDouble("SELECT COALESCE(SUM(opening_balance),0) FROM bank_accounts WHERE station_id=? AND COALESCE(is_deleted,0)=0",
                arrayOf(stationId.toString()))
        }
        balance += journalLinesBefore(date).filter { it.cash }.sumOf { it.debit - it.credit }
        return money(balance)
    }

    private fun cashAndBankCurrentBalance(): Double {
        val cash = if (tableExists("cash_boxes")) scalarDouble(
            "SELECT COALESCE(SUM(current_balance),0) FROM cash_boxes WHERE station_id=? AND COALESCE(is_deleted,0)=0",
            arrayOf(stationId.toString())) else 0.0
        val bank = if (tableExists("bank_accounts")) scalarDouble(
            "SELECT COALESCE(SUM(current_balance),0) FROM bank_accounts WHERE station_id=? AND COALESCE(is_deleted,0)=0",
            arrayOf(stationId.toString())) else 0.0
        return money(cash + bank)
    }

    private fun supplierLedgerEvents(supplierId: Long): List<SupplierEvent> {
        val cols = columns("supplier_ledger")
        require(cols.containsAll(setOf("id", "party_id", "debit", "credit", "transaction_type", "transaction_id"))) {
            "حقول دفتر المورد لا تكفي لإثبات كشف الحساب"
        }
        val dateCol = firstColumn(cols, listOf("transaction_date", "entry_date", "date", "created_at", "posting_date"))
        val stationScope = if (cols.contains("station_id")) " AND station_id=?" else ""
        val args = if (cols.contains("station_id")) arrayOf(supplierId.toString(), stationId.toString()) else arrayOf(supplierId.toString())
        val sql = "SELECT id,COALESCE(debit,0),COALESCE(credit,0),COALESCE(transaction_type,'')," +
            "COALESCE(transaction_id,0)," + (if (dateCol != null) "COALESCE(" + q(dateCol) + ",'')" else "''") +
            " FROM supplier_ledger WHERE party_id=?" + stationScope + " ORDER BY " + (if (dateCol != null) q(dateCol) + ",id" else "id")
        return query(sql, args) { c ->
            val rawType = c.getString(3)
            val txId = c.getLong(4)
            val rawDate = c.getString(5).takeIf { it.isNotBlank() }?.let { normalizeDateMaybe(it) }
            val source = sourceDates(rawType, txId, rawDate)
            SupplierEvent(c.getLong(0), c.getDouble(1), c.getDouble(2), rawType, txId, source.first ?: rawDate)
        }
    }

    private fun sourceDates(type: String, transactionId: Long, ledgerDate: String?): Pair<String?, String?> {
        if (type == "general_purchase_invoice") {
            return query("SELECT invoice_date,due_date FROM expanded_purchase_invoices WHERE id=? AND station_id=?",
                arrayOf(transactionId.toString(), stationId.toString())) { it.getString(0) to it.getString(1) }.firstOrNull()
                ?: (ledgerDate to null)
        }
        if (type == "general_purchase_return") {
            val inv = query("SELECT return_date,invoice_id FROM expanded_purchase_returns WHERE id=? AND station_id=?",
                arrayOf(transactionId.toString(), stationId.toString())) { it.getString(0) to it.getLong(1) }.firstOrNull()
            return (inv?.first ?: ledgerDate) to null
        }
        if (type == "fuel_supply_credit" && tableExists("tank_refills")) {
            val cols = columns("tank_refills")
            val dateCol = firstColumn(cols, listOf("refill_date", "delivery_date", "transaction_date", "created_at"))
            if (dateCol != null && cols.contains("id") && cols.contains("station_id")) {
                val date = scalarText("SELECT COALESCE(" + q(dateCol) + ",'') FROM tank_refills WHERE id=? AND station_id=?",
                    arrayOf(transactionId.toString(), stationId.toString())).takeIf { it.isNotBlank() }?.let { normalizeDateMaybe(it) }
                return (date ?: ledgerDate) to null
            }
        }
        return ledgerDate to null
    }

    private fun sourceInvoiceForReturn(type: String, transactionId: Long): Long {
        if (type != "general_purchase_return") return 0L
        return scalarLong("SELECT invoice_id FROM expanded_purchase_returns WHERE id=? AND station_id=?",
            arrayOf(transactionId.toString(), stationId.toString()))
    }

    private fun obligationInvoiceId(o: OpenObligation): Long = if (o.type == "general_purchase_invoice") o.transactionId else 0L

    private fun ledgerDescription(type: String, transactionId: Long): String = when (type) {
        "general_purchase_invoice" -> scalarText("SELECT invoice_number FROM expanded_purchase_invoices WHERE id=? AND station_id=?",
            arrayOf(transactionId.toString(), stationId.toString())).let { "فاتورة مشتريات " + it }
        "general_purchase_return" -> scalarText("SELECT return_number FROM expanded_purchase_returns WHERE id=? AND station_id=?",
            arrayOf(transactionId.toString(), stationId.toString())).let { "مرتجع مشتريات " + it }
        else -> type.replace('_', ' ') + " #" + transactionId
    }

    private fun visibleBalance(entries: JSONArray): Double {
        var total = 0.0
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            total += e.optDouble("credit") - e.optDouble("debit")
        }
        return money(total)
    }

    private fun finalizeSupplierUpdate(partyId: Long, balanceDelta: Double, dueDelta: Double, purchasesDelta: Double) {
        updateSupplierTotals(partyId, balanceDelta, dueDelta, purchasesDelta)
    }

    private fun updateSupplierTotals(partyId: Long, balanceDelta: Double, dueDelta: Double, purchasesDelta: Double) {
        val cols = columns("parties")
        val set = mutableListOf<String>()
        val args = mutableListOf<String>()
        fun addDelta(col: String, delta: Double) {
            if (cols.contains(col)) { set.add(q(col) + "=COALESCE(" + q(col) + ",0)+?"); args.add(delta.toString()) }
        }
        addDelta("current_balance", balanceDelta)
        addDelta("total_due", dueDelta)
        addDelta("total_purchases", purchasesDelta)
        if (set.isEmpty()) return
        args.add(partyId.toString()); args.add(stationId.toString())
        db.execSQL("UPDATE parties SET " + set.joinToString(",") + " WHERE id=? AND station_id=? AND COALESCE(is_deleted,0)=0", args.toTypedArray())
    }

    private fun insertSupplierLedger(partyId: Long, debit: Double, credit: Double, type: String, transactionId: Long, date: String, description: String) {
        requireTable("supplier_ledger")
        val cols = columns("supplier_ledger")
        require(cols.containsAll(setOf("party_id", "debit", "credit", "transaction_type", "transaction_id"))) {
            "لا يمكن ترحيل مشتريات: بنية دفتر المورد لا تدعم الربط المطلوب"
        }
        val values = ContentValues().apply {
            put("party_id", partyId); put("debit", money(debit)); put("credit", money(credit))
            put("transaction_type", type); put("transaction_id", transactionId)
            if (cols.contains("station_id")) put("station_id", stationId)
            if (cols.contains("transaction_date")) put("transaction_date", date)
            if (cols.contains("entry_date")) put("entry_date", date)
            if (cols.contains("date")) put("date", date)
            if (cols.contains("created_at")) put("created_at", now())
            if (cols.contains("description")) put("description", description)
            if (cols.contains("reference")) put("reference", type + ":" + transactionId)
            if (cols.contains("reference_number")) put("reference_number", type + ":" + transactionId)
            if (cols.contains("created_by")) put("created_by", userId)
            if (cols.contains("status")) put("status", "posted")
            if (cols.contains("is_deleted")) put("is_deleted", 0)
        }
        val id = db.insertOrThrow("supplier_ledger", null, values)
        require(id > 0) { "فشل حفظ أثر فاتورة المورد في دفتر الموردين" }
    }

    private fun purchaseInvoice(id: Long): JSONObject? = query(
        "SELECT id,station_id,supplier_id,invoice_number,invoice_date,due_date,currency_id,subtotal,vat_amount,total_amount,status,payable_account_id,input_vat_account_id,journal_entry_id,notes " +
            "FROM expanded_purchase_invoices WHERE id=? AND station_id=? AND is_deleted=0",
        arrayOf(id.toString(), stationId.toString())
    ) { c -> JSONObject().put("id", c.getLong(0)).put("station_id", c.getLong(1)).put("supplier_id", c.getLong(2))
        .put("invoice_number", c.getString(3)).put("invoice_date", c.getString(4)).put("due_date", c.getString(5))
        .put("currency_id", c.getLong(6)).put("subtotal", c.getDouble(7)).put("vat_amount", c.getDouble(8))
        .put("total_amount", c.getDouble(9)).put("status", c.getString(10))
        .put("payable_account_id", if (c.isNull(11)) 0L else c.getLong(11))
        .put("input_vat_account_id", if (c.isNull(12)) 0L else c.getLong(12))
        .put("journal_entry_id", if (c.isNull(13)) 0L else c.getLong(13)).put("notes", c.getString(14)) }.firstOrNull()

    private fun purchaseReturn(id: Long): JSONObject? = query(
        "SELECT id,station_id,invoice_id,supplier_id,return_number,return_date,reason,subtotal,vat_amount,total_amount,status,journal_entry_id " +
            "FROM expanded_purchase_returns WHERE id=? AND station_id=? AND is_deleted=0",
        arrayOf(id.toString(), stationId.toString())
    ) { c -> JSONObject().put("id", c.getLong(0)).put("station_id", c.getLong(1)).put("invoice_id", c.getLong(2))
        .put("supplier_id", c.getLong(3)).put("return_number", c.getString(4)).put("return_date", c.getString(5))
        .put("reason", c.getString(6)).put("subtotal", c.getDouble(7)).put("vat_amount", c.getDouble(8))
        .put("total_amount", c.getDouble(9)).put("status", c.getString(10))
        .put("journal_entry_id", if (c.isNull(11)) 0L else c.getLong(11)) }.firstOrNull()

    private fun invoiceLines(invoiceId: Long): List<PurchaseLine> = query(
        "SELECT description,quantity,unit_price,vat_rate,net_amount,vat_amount,total_amount,account_id FROM expanded_purchase_invoice_lines " +
            "WHERE invoice_id=? AND station_id=? ORDER BY line_no",
        arrayOf(invoiceId.toString(), stationId.toString())
    ) { c -> PurchaseLine(c.getString(0), c.getDouble(1), c.getDouble(2), c.getDouble(3),
        c.getDouble(4), c.getDouble(5), c.getDouble(6), c.getLong(7)) }

    private fun findSourceJournal(referenceType: String, referenceId: Long): Long {
        val cols = columns("journal_entries")
        if (!cols.containsAll(setOf("id", "station_id", "reference_type", "reference_id", "status"))) return 0L
        val deleted = if (cols.contains("is_deleted")) " AND COALESCE(is_deleted,0)=0" else ""
        return scalarLong(
            "SELECT id FROM journal_entries WHERE station_id=? AND reference_type=? AND reference_id=?" +
                deleted + " ORDER BY CASE lower(status) WHEN 'posted' THEN 0 WHEN 'pending' THEN 1 ELSE 2 END,id DESC LIMIT 1",
            arrayOf(stationId.toString(), referenceType, referenceId.toString())
        )
    }

    private fun journalStatus(id: Long): String {
        if (id <= 0L || !tableExists("journal_entries")) return ""
        return scalarText("SELECT COALESCE(status,'') FROM journal_entries WHERE id=? AND station_id=? AND COALESCE(is_deleted,0)=0",
            arrayOf(id.toString(), stationId.toString())).lowercase(Locale.ROOT)
    }

    private fun requireSupplier(id: Long) {
        val found = scalar(
            "SELECT COUNT(*) FROM parties p JOIN party_types t ON t.id=p.party_type_id WHERE p.id=? AND p.station_id=? " +
                "AND t.type_code='SUPPLIER' AND COALESCE(p.is_deleted,0)=0",
            arrayOf(id.toString(), stationId.toString())
        )
        require(found == 1) { "المورد غير موجود أو لا ينتمي إلى المحطة الحالية" }
    }

    private fun requireValidAccount(id: Long, allowedTypes: Set<String>) {
        require(id > 0L) { "يجب تحديد حساب أستاذ عام صالح" }
        requireTable("accounts")
        val cols = columns("accounts")
        require(cols.containsAll(setOf("id", "account_type", "is_active", "is_deleted"))) {
            "بنية دليل الحسابات غير مكتملة"
        }
        val stationWhere = if (cols.contains("station_id")) " AND station_id=?" else ""
        val args = mutableListOf(id.toString())
        if (cols.contains("station_id")) args.add(stationId.toString())
        val type = scalarText("SELECT lower(COALESCE(account_type,'')) FROM accounts WHERE id=?" + stationWhere +
            " AND is_active=1 AND COALESCE(is_deleted,0)=0", args.toTypedArray())
        require(type in allowedTypes) { "الحساب المحدد غير نشط أو تصنيفه المحاسبي غير مناسب: " + type }
    }

    private fun classification(accountId: Long, type: String): String = scalarText(
        "SELECT classification_code FROM expanded_finance_account_classification WHERE station_id=? AND account_id=? AND classification_type=?",
        arrayOf(stationId.toString(), accountId.toString(), type)
    )

    private fun sourceDateColumn(table: String): String? = firstColumn(columns(table),
        listOf("transaction_date", "entry_date", "date", "created_at", "posting_date"))

    private fun ensureColumn(table: String, column: String, definition: String) {
        if (tableExists(table) && !columns(table).contains(column)) {
            db.execSQL("ALTER TABLE " + q(table) + " ADD COLUMN " + q(column) + " " + definition)
        }
    }

    private fun firstColumn(cols: Set<String>, options: List<String>): String? = options.firstOrNull { cols.contains(it) }

    private fun normalizeDateMaybe(value: String): String? {
        val s = value.trim()
        if (s.isBlank()) return null
        val first = s.take(10)
        return try { parseDate(first) } catch (_: Exception) {
            try { parseDate(s.take(8)) } catch (_: Exception) { null }
        }
    }

    private fun parseDate(value: String): String {
        val s = value.trim().takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("التاريخ مطلوب")
        val formats = listOf("yyyy-MM-dd", "yyyyMMdd", "dd/MM/yyyy", "d/M/yyyy", "MM/dd/yyyy", "dd-MM-yyyy")
        for (f in formats) {
            try {
                val format = SimpleDateFormat(f, Locale.US).apply { isLenient = false }
                val parsed = format.parse(s) ?: continue
                return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(parsed)
            } catch (_: Exception) { }
        }
        throw IllegalArgumentException("صيغة التاريخ غير صالحة: " + s.take(32))
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    private fun daysBetween(from: String, to: String): Int {
        val a = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(parseDate(from))?.time ?: return 0
        val b = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(parseDate(to))?.time ?: return 0
        return ((b - a) / 86_400_000L).toInt()
    }
    private fun money(value: Double): Double = if (!value.isFinite()) 0.0 else (value * 100.0).roundToLong() / 100.0
    private fun number(value: String): Double {
        val normalized = value.trim().replace(" ", "").replace(",", "").replace(Regex("[^0-9.()\\-+]"), "")
        if (normalized.isBlank() || normalized in setOf("-", "+", ".", "()")) return 0.0
        val neg = normalized.startsWith("(") && normalized.endsWith(")")
        val cleanValue = normalized.removePrefix("(").removeSuffix(")")
        val result = cleanValue.toDoubleOrNull() ?: throw IllegalArgumentException("مبلغ غير رقمي: " + value.take(40))
        return if (neg) -abs(result) else result
    }
    private fun clean(value: String?, max: Int): String = (value ?: "").replace(Regex("[\\u0000-\\u001f]"), " ").trim().take(max)
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun q(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""
    private fun tableExists(table: String): Boolean = scalar(
        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)
    ) > 0
    private fun requireTable(table: String) { require(tableExists(table)) { "الجدول المطلوب غير موجود: " + table } }
    private fun columns(table: String): Set<String> {
        val result = linkedSetOf<String>()
        if (!tableExists(table)) return result
        db.rawQuery("PRAGMA table_info(" + q(table) + ")", null).use { c -> while (c.moveToNext()) result.add(c.getString(1)) }
        return result
    }
    private fun scalar(sql: String, args: Array<String>): Int {
        db.rawQuery(sql, args).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }
    private fun scalarLong(sql: String, args: Array<String>): Long {
        db.rawQuery(sql, args).use { c -> return if (c.moveToFirst()) c.getLong(0) else 0L }
    }
    private fun scalarDouble(sql: String, args: Array<String>): Double {
        db.rawQuery(sql, args).use { c -> return if (c.moveToFirst()) c.getDouble(0) else 0.0 }
    }
    private fun scalarText(sql: String, args: Array<String>): String {
        db.rawQuery(sql, args).use { c -> return if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else "" }
    }
    private fun <T> query(sql: String, args: Array<String>, mapper: (Cursor) -> T): List<T> {
        val result = mutableListOf<T>()
        db.rawQuery(sql, args).use { c -> while (c.moveToNext()) result.add(mapper(c)) }
        return result
    }

    private fun returnSourceInvoiceId(type: String, txId: Long): Long = sourceInvoiceForReturn(type, txId)

    private data class SupplierEvent(
        val id: Long, val debit: Double, val credit: Double, val type: String,
        val transactionId: Long, val eventDate: String?
    )
    private data class OpenObligation(
        val id: Long, val transactionId: Long, val originalAmount: Double, val invoiceDate: String?, val dueDate: String?,
        val eventDate: String?, val type: String, var remaining: Double = originalAmount
    )
    private data class PurchaseLine(
        val description: String, val quantity: Double, val unitPrice: Double, val vatRate: Double,
        val net: Double, val vat: Double, val total: Double, val accountId: Long
    )
    private data class ReturnLine(
        val invoiceLineId: Long, val quantity: Double, val net: Double, val vat: Double, val total: Double, val accountId: Long
    )
    private data class ImportedLine(
        val date: String, val description: String, val reference: String, val externalId: String, val signedAmount: Double
    )
    private data class JournalLine(
        val journalId: Long, val date: String, val referenceType: String, val referenceId: Long, val accountId: Long,
        val debit: Double, val credit: Double, val accountType: String, val accountCode: String, val accountName: String, val cash: Boolean
    )
    private data class EquityAccount(val id: Long, val code: String, val name: String, val type: String, val opening: Double)
}
