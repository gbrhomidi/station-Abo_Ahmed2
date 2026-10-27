package com.aistudio.dieselstationsms.kxmpzq

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Workpaper for reconciling historical financial data.
 *
 * Read-only workpaper used before and after source-backed historical repairs.
 * Missing required schema and unresolved source differences block statement approval.
 */
internal class HistoricalFinancialReconciliationAudit(private val db: SQLiteDatabase) {

    fun run(stationId: Int): JSONObject {
        require(stationId > 0) { "A valid station scope is required" }
        val checks = JSONObject()
        val issues = JSONArray()
        val schemaComplete = checkRequiredSchema(checks, issues)
        if (schemaComplete) {
            checkSales(stationId, checks, issues)
            checkPayments(stationId, checks, issues)
            checkCustomerAndSupplierLedgers(stationId, checks, issues)
            checkInventory(stationId, checks, issues)
            checkFuelAndRefills(stationId, checks, issues)
            checkPeriodsAndCurrency(stationId, checks, issues)
            checkOpeningBalances(stationId, checks, issues)
        }
        return JSONObject()
            .put("station_id", stationId)
            .put("checked_at", now())
            .put("status", if (issues.length() == 0) "reconciled" else "reconciliation_required")
            .put("approval_status", if (issues.length() == 0) "ready" else "blocked")
            .put("ready_for_financial_statement_approval", issues.length() == 0)
            .put("blocking_issue_count", issues.length())
            .put("checks", checks)
            .put("issues", issues)
            .put("automatic_repairs", false)
    }

    /**
     * Prevent a false clean report when a checker silently returns because a table
     * or a critical column is absent. Such a database cannot pass statement approval.
     */
    private fun checkRequiredSchema(checks: JSONObject, issues: JSONArray): Boolean {
        val required = linkedMapOf(
            "sales_transactions" to setOf("id","station_id","status","is_deleted","sale_code","net_amount","paid_amount","remaining_amount","currency_id","amount_in_default"),
            "journal_entries" to setOf("id","station_id","reference_type","reference_id","status","is_deleted","fiscal_year","fiscal_period"),
            "journal_entry_items" to setOf("journal_entry_id","account_id","debit","credit"),
            "payments" to setOf("id","station_id","status","is_deleted","payment_code","amount","sale_id","customer_party_id","cash_box_id","bank_account_id","journal_entry_id","payment_method","currency_id","amount_in_default"),
            "parties" to setOf("id","station_id","party_type_id","legal_name","current_balance","total_due","total_purchases","total_payments","is_deleted"),
            "party_types" to setOf("id","type_code"),
            "customer_ledger" to setOf("id","party_id","debit","credit"),
            "supplier_ledger" to setOf("id","party_id","debit","credit","transaction_type","transaction_id"),
            "accounts" to setOf("id","account_code","account_type","is_active","is_deleted","opening_balance","parent_account_id"),
            "products" to setOf("id","station_id","product_name","product_name_ar","quantity","is_deleted"),
            "inventory_levels" to setOf("product_id","quantity_on_hand"),
            "inventory_movements" to setOf("id","station_id","status","is_deleted","product_id","quantity_before","quantity_change","quantity_after"),
            "fuel_sales" to setOf("id","station_id","sale_id","fuel_type_id","quantity","cost_per_liter","cost_amount","status","is_deleted"),
            "fuel_types" to setOf("id","default_purchase_price"),
            "tank_refills" to setOf("id","station_id","refill_code","supplier_id","net_amount","delivered_quantity","actual_quantity","tank_level_before","tank_level_after","status","is_deleted"),
            "fiscal_periods" to setOf("station_id","start_date","end_date")
        )
        val gaps = JSONArray()
        var gapCount = 0
        for ((table, needed) in required) {
            if (!tableExists(table)) {
                gaps.put(JSONObject().put("table",table).put("missing_table",true))
                gapCount++
                continue
            }
            val missing = (needed - columns(table)).sorted()
            if (missing.isNotEmpty()) {
                gaps.put(JSONObject().put("table",table).put("missing_columns",JSONArray(missing)))
                gapCount += missing.size
            }
        }
        checks.put("required_schema_gaps",gaps)
        if (gaps.length() > 0) {
            add(issues,"historical_schema_incomplete","critical",
                "لا يمكن اعتماد القوائم المالية: توجد جداول أو أعمدة مطلوبة للمصالحة غير متاحة",gapCount,null,gaps)
            return false
        }
        return true
    }

    private fun checkSales(stationId: Int, checks: JSONObject, issues: JSONArray) {
        if (!tableExists("sales_transactions") || !tableExists("journal_entries")) return
        val sales = rows(
            "SELECT s.id,COALESCE(s.sale_code,''),COALESCE(s.net_amount,0) FROM sales_transactions s " +
                "WHERE s.station_id=? AND s.status='completed' AND COALESCE(s.is_deleted,0)=0 " +
                "AND NOT EXISTS(SELECT 1 FROM journal_entries j WHERE j.station_id=s.station_id AND j.reference_type='sale' " +
                "AND j.reference_id=s.id AND j.status='posted' AND COALESCE(j.is_deleted,0)=0) ORDER BY s.id",
            arrayOf(stationId.toString())
        ) { c -> JSONObject().put("sale_id", c.getLong(0)).put("sale_code", c.getString(1)).put("amount", c.getDouble(2)) }
        val cogs = rows(
            "SELECT s.id,COALESCE(s.sale_code,''),COALESCE(s.net_amount,0) FROM sales_transactions s " +
                "WHERE s.station_id=? AND s.status='completed' AND COALESCE(s.is_deleted,0)=0 " +
                "AND NOT EXISTS(SELECT 1 FROM journal_entries j WHERE j.station_id=s.station_id AND j.reference_type='sale_cogs' " +
                "AND j.reference_id=s.id AND j.status='posted' AND COALESCE(j.is_deleted,0)=0) ORDER BY s.id",
            arrayOf(stationId.toString())
        ) { c -> JSONObject().put("sale_id", c.getLong(0)).put("sale_code", c.getString(1)).put("sales_amount", c.getDouble(2)) }
        checks.put("missing_sale_journals", sales).put("missing_sale_cogs_journals", cogs)
        add(issues, "missing_sale_journals", "critical", "مبيعات مكتملة بلا قيد إيراد/تحصيل/ذمم مطابق", sales.length(), sum(sales, "amount"), sales)
        add(issues, "missing_sale_cogs_journals", "critical", "مبيعات مكتملة بلا قيد تكلفة مطابق؛ لا يمكن استنتاج تكلفة تاريخية من سعر اليوم", cogs.length(), sum(cogs, "sales_amount"), cogs)

        val arithmetic = rows(
            "SELECT id,COALESCE(sale_code,''),COALESCE(net_amount,0),COALESCE(paid_amount,0),COALESCE(remaining_amount,0) " +
                "FROM sales_transactions WHERE station_id=? AND status='completed' AND COALESCE(is_deleted,0)=0 " +
                "AND ABS(COALESCE(net_amount,0)-COALESCE(paid_amount,0)-COALESCE(remaining_amount,0))>0.01 ORDER BY id",
            arrayOf(stationId.toString())
        ) { c -> JSONObject().put("sale_id", c.getLong(0)).put("sale_code", c.getString(1)).put("net", c.getDouble(2))
            .put("paid", c.getDouble(3)).put("remaining", c.getDouble(4)).put("difference", c.getDouble(2)-c.getDouble(3)-c.getDouble(4)) }
        checks.put("sales_payment_arithmetic_mismatches", arithmetic)
        add(issues, "sales_payment_arithmetic_mismatches", "critical", "صافي الفاتورة لا يساوي المدفوع زائد المتبقي", arithmetic.length(), sum(arithmetic, "difference"), arithmetic)
    }

    private fun checkPayments(stationId: Int, checks: JSONObject, issues: JSONArray) {
        if (!tableExists("payments") || !tableExists("journal_entries")) return
        val missing = rows(
            "SELECT p.id,COALESCE(p.payment_code,''),COALESCE(p.amount,0),COALESCE(p.sale_id,0),COALESCE(p.customer_party_id,0)," +
                "COALESCE(p.cash_box_id,0),COALESCE(p.bank_account_id,0),COALESCE(p.journal_entry_id,0),COALESCE(p.payment_method,'') " +
                "FROM payments p WHERE p.station_id=? AND p.status='completed' AND COALESCE(p.is_deleted,0)=0 AND " +
                "(COALESCE(p.journal_entry_id,0)=0 OR NOT EXISTS(SELECT 1 FROM journal_entries j WHERE j.id=p.journal_entry_id " +
                "AND j.station_id=p.station_id AND j.reference_type='payment' AND j.reference_id=p.id AND j.status='posted' AND COALESCE(j.is_deleted,0)=0)) ORDER BY p.id",
            arrayOf(stationId.toString())
        ) { c -> JSONObject().put("payment_id", c.getLong(0)).put("payment_code", c.getString(1)).put("amount", c.getDouble(2))
            .put("sale_id", c.getLong(3)).put("customer_party_id", c.getLong(4)).put("cash_box_id", c.getLong(5))
            .put("bank_account_id", c.getLong(6)).put("journal_entry_id", c.getLong(7)).put("payment_method", c.getString(8)) }
        checks.put("completed_payments_without_posted_journal", missing)
        add(issues, "completed_payments_without_posted_journal", "critical",
            "مدفوعات مكتملة بلا قيد مرحّل مطابق؛ يلزم إثبات مصدر التسوية وتخصيص الفاتورة أولاً",
            missing.length(), sum(missing, "amount"), missing)
        val withoutSource = rows(
            "SELECT id,COALESCE(payment_code,''),COALESCE(amount,0),COALESCE(payment_method,'') FROM payments " +
                "WHERE station_id=? AND status='completed' AND COALESCE(is_deleted,0)=0 AND " +
                "COALESCE(cash_box_id,0)=0 AND COALESCE(bank_account_id,0)=0 ORDER BY id",
            arrayOf(stationId.toString())
        ) { c -> JSONObject().put("payment_id", c.getLong(0)).put("payment_code", c.getString(1)).put("amount", c.getDouble(2)).put("payment_method", c.getString(3)) }
        checks.put("completed_payments_without_settlement_source", withoutSource)
        add(issues, "completed_payments_without_settlement_source", "critical", "مدفوعات بلا صندوق أو بنك محفوظ في سجل المصدر",
            withoutSource.length(), sum(withoutSource, "amount"), withoutSource)
        val unscoped = if (hasColumn("payments", "station_id")) scalar(
            "SELECT COUNT(*) FROM payments WHERE (station_id IS NULL OR station_id=0) AND status='completed' AND COALESCE(is_deleted,0)=0",
            emptyArray()
        ) else 0
        checks.put("completed_payments_missing_station_scope", unscoped)
        add(issues, "completed_payments_missing_station_scope", "critical",
            "مدفوعات مكتملة بلا نطاق محطة؛ لا يجوز إسنادها تلقائياً إلى هذه المحطة", unscoped, null, null)
    }

    private fun checkCustomerAndSupplierLedgers(stationId: Int, checks: JSONObject, issues: JSONArray) {
        if (tableExists("parties") && tableExists("party_types") && tableExists("customer_ledger")) {
            val customers = rows(
                "SELECT p.id,COALESCE(p.legal_name,''),COALESCE(p.current_balance,0),COUNT(l.id)," +
                    "COALESCE(SUM(COALESCE(l.debit,0)-COALESCE(l.credit,0)),0) FROM parties p " +
                    "JOIN party_types t ON t.id=p.party_type_id LEFT JOIN customer_ledger l ON l.party_id=p.id " +
                    "WHERE p.station_id=? AND COALESCE(p.is_deleted,0)=0 AND t.type_code<>'SUPPLIER' GROUP BY p.id " +
                    "HAVING ABS(COALESCE(p.current_balance,0)-COALESCE(SUM(COALESCE(l.debit,0)-COALESCE(l.credit,0)),0))>0.01 " +
                    "OR (COUNT(l.id)=0 AND ABS(COALESCE(p.current_balance,0))>0.01) ORDER BY p.id",
                arrayOf(stationId.toString())
            ) { c -> JSONObject().put("party_id", c.getLong(0)).put("party_name", c.getString(1))
                .put("current_balance", c.getDouble(2)).put("ledger_row_count", c.getInt(3)).put("ledger_net_debit", c.getDouble(4)) }
            checks.put("customer_subledger_mismatches", customers)
            add(issues, "customer_subledger_mismatches", "critical",
                "رصيد العميل لا يطابق دفتر العميل؛ الرصيد الافتتاحي غير الموثق يحتاج سنداً مستقلاً",
                customers.length(), sum(customers, "current_balance"), customers)
            val ar = controlAccount(stationId, "1103", "asset", false)
            checks.put("customer_receivable_control_reconciliation", ar)
            if (ar.optBoolean("available") && abs(ar.optDouble("difference", 0.0)) > 0.01)
                add(issues, "customer_receivable_control_reconciliation", "critical",
                    "حساب رقابة المدينين لا يطابق مجموع أرصدة العملاء في المحطة", 1, ar.optDouble("difference"), JSONArray().put(ar))
        }
        if (tableExists("parties") && tableExists("party_types") && tableExists("supplier_ledger")) {
            val suppliers = rows(
                "SELECT p.id,COALESCE(p.legal_name,''),COALESCE(p.current_balance,0),COALESCE(p.total_due,0)," +
                    "COALESCE(p.total_purchases,0),COALESCE(p.total_payments,0),COUNT(l.id)," +
                    "COALESCE(SUM(COALESCE(l.credit,0)-COALESCE(l.debit,0)),0) FROM parties p " +
                    "JOIN party_types t ON t.id=p.party_type_id LEFT JOIN supplier_ledger l ON l.party_id=p.id " +
                    "WHERE p.station_id=? AND t.type_code='SUPPLIER' AND COALESCE(p.is_deleted,0)=0 GROUP BY p.id HAVING " +
                    "ABS(COALESCE(p.current_balance,0)-COALESCE(p.total_due,0))>0.01 OR " +
                    "ABS(COALESCE(p.current_balance,0)-COALESCE(SUM(COALESCE(l.credit,0)-COALESCE(l.debit,0)),0))>0.01 OR " +
                    "ABS(COALESCE(p.total_purchases,0)-COALESCE(p.total_payments,0)-COALESCE(p.total_due,0))>0.01 ORDER BY p.id",
                arrayOf(stationId.toString())
            ) { c -> JSONObject().put("supplier_id", c.getLong(0)).put("supplier_name", c.getString(1))
                .put("current_balance", c.getDouble(2)).put("total_due", c.getDouble(3))
                .put("total_purchases", c.getDouble(4)).put("total_payments", c.getDouble(5))
                .put("ledger_row_count", c.getInt(6)).put("ledger_net_credit", c.getDouble(7)) }
            checks.put("supplier_subledger_mismatches", suppliers)
            add(issues, "supplier_subledger_mismatches", "critical",
                "رصيد المورد أو مجموع دفتر المورد لا يطابق إجمالي المشتريات والدفعات", suppliers.length(), null, suppliers)
            val ap = controlAccount(stationId, "2101", "liability", true)
            checks.put("supplier_payable_control_reconciliation", ap)
            if (ap.optBoolean("available") && abs(ap.optDouble("difference", 0.0)) > 0.01)
                add(issues, "supplier_payable_control_reconciliation", "critical",
                    "حساب رقابة الدائنين لا يطابق دفتر الموردين", 1, ap.optDouble("difference"), JSONArray().put(ap))
        }
    }

    private fun controlAccount(stationId: Int, code: String, accountType: String, supplier: Boolean): JSONObject {
        val accountScope = if (hasColumn("accounts","station_id")) " AND station_id=?" else ""
        val accountArgs = if (hasColumn("accounts","station_id")) arrayOf(code,accountType,stationId.toString()) else arrayOf(code,accountType)
        val ids = query(
            "SELECT id,COALESCE(opening_balance,0) FROM accounts WHERE account_code=? AND account_type=? AND is_active=1 AND COALESCE(is_deleted,0)=0" + accountScope,
            accountArgs
        ) { c -> c.getLong(0) to c.getDouble(1) }
        if (ids.size != 1) return JSONObject().put("available", false).put("reason", "الحساب الرقابي غير فريد أو غير نشط")
        val id = ids[0].first
        val gl = scalarDouble(
            "SELECT COALESCE(SUM(i.debit-i.credit),0) FROM journal_entry_items i JOIN journal_entries j ON j.id=i.journal_entry_id " +
                "WHERE i.account_id=? AND j.station_id=? AND j.status='posted' AND COALESCE(j.is_deleted,0)=0",
            arrayOf(id.toString(), stationId.toString())
        )
        val partyTotal = scalarDouble(
            "SELECT COALESCE(SUM(p.current_balance),0) FROM parties p JOIN party_types t ON t.id=p.party_type_id " +
                "WHERE p.station_id=? AND COALESCE(p.is_deleted,0)=0 AND " +
                if (supplier) "t.type_code='SUPPLIER'" else "t.type_code<>'SUPPLIER'",
            arrayOf(stationId.toString())
        )
        val expected = if (supplier) -partyTotal else partyTotal
        val book = ids[0].second + gl
        return JSONObject().put("available", true).put("account_id", id).put("account_code", code)
            .put("opening_balance", ids[0].second).put("posted_gl_net_debit", gl)
            .put("party_balance_total", partyTotal).put("expected_gl_net_debit", expected)
            .put("difference", book - expected).put("reconciled", abs(book - expected) <= 0.01)
    }

    private fun checkInventory(stationId: Int, checks: JSONObject, issues: JSONArray) {
        val movementsExist = tableExists("inventory_movements")
        val ledgerCount = if (!tableExists("inventory_ledger")) 0 else when {
            hasColumn("inventory_ledger","station_id") ->
                scalar("SELECT COUNT(*) FROM inventory_ledger WHERE station_id=?", arrayOf(stationId.toString()))
            hasColumn("inventory_ledger","product_id") && tableExists("products") && hasColumn("products","station_id") ->
                scalar("SELECT COUNT(*) FROM inventory_ledger l JOIN products p ON p.id=l.product_id WHERE p.station_id=?", arrayOf(stationId.toString()))
            else -> -1
        }
        val movementCount = if (movementsExist) scalar(
            "SELECT COUNT(*) FROM inventory_movements WHERE station_id=? AND status='completed' AND COALESCE(is_deleted,0)=0",
            arrayOf(stationId.toString())
        ) else 0
        checks.put("completed_inventory_movement_count", movementCount).put("inventory_ledger_row_count", ledgerCount)
        if (movementCount > 0 && ledgerCount <= 0)
            add(issues, "inventory_ledger_missing_or_unscoped", "high",
                if (ledgerCount == -1)
                    "تعذر عزل دفتر المخزون للمحطة؛ لا يمكن إثبات اكتماله دون مفتاح نطاق واضح"
                else "توجد حركات مخزون معتمدة لكن دفتر المخزون المساعد فارغ؛ يلزم إعادة بنائه من مستندات الحركة والتحقق من الكلفة",
                1, null, null)

        if (tableExists("products") && tableExists("inventory_levels")) {
            val levels = rows(
                "SELECT p.id,COALESCE(p.product_name_ar,p.product_name,''),COALESCE(p.quantity,0)," +
                    "COALESCE((SELECT SUM(l.quantity_on_hand) FROM inventory_levels l WHERE l.product_id=p.id),0) " +
                    "FROM products p WHERE p.station_id=? AND COALESCE(p.is_deleted,0)=0 AND " +
                    "EXISTS(SELECT 1 FROM inventory_levels l WHERE l.product_id=p.id) AND " +
                    "ABS(COALESCE(p.quantity,0)-(SELECT SUM(l.quantity_on_hand) FROM inventory_levels l WHERE l.product_id=p.id))>0.01 ORDER BY p.id",
                arrayOf(stationId.toString())
            ) { c -> JSONObject().put("product_id", c.getLong(0)).put("product_name", c.getString(1))
                .put("product_quantity", c.getDouble(2)).put("sum_warehouse_quantity", c.getDouble(3))
                .put("difference", c.getDouble(2) - c.getDouble(3)) }
            checks.put("product_quantity_vs_warehouse_levels", levels)
            add(issues, "product_quantity_vs_warehouse_levels", "critical",
                "كمية المنتج لا تطابق مجموع أرصدة المستودعات", levels.length(), null, levels)
        }
        if (movementsExist) {
            val broken = rows(
                "SELECT id,product_id,COALESCE(quantity_before,0),COALESCE(quantity_change,0),COALESCE(quantity_after,0) " +
                    "FROM inventory_movements WHERE station_id=? AND status='completed' AND COALESCE(is_deleted,0)=0 " +
                    "AND ABS(COALESCE(quantity_before,0)+COALESCE(quantity_change,0)-COALESCE(quantity_after,0))>0.01 ORDER BY id",
                arrayOf(stationId.toString())
            ) { c -> JSONObject().put("movement_id", c.getLong(0)).put("product_id", c.getLong(1))
                .put("quantity_before", c.getDouble(2)).put("quantity_change", c.getDouble(3)).put("quantity_after", c.getDouble(4)) }
            checks.put("inventory_movement_arithmetic_mismatches", broken)
            add(issues, "inventory_movement_arithmetic_mismatches", "critical",
                "حركات مخزون لا تحقق الرصيد السابق + التغير = الرصيد اللاحق؛ لا تعدل الكميات تلقائياً", broken.length(), null, broken)
        }
    }

    private fun checkFuelAndRefills(stationId: Int, checks: JSONObject, issues: JSONArray) {
        if (tableExists("fuel_sales") && tableExists("fuel_types")) {
            val costs = rows(
                "SELECT fs.id,COALESCE(fs.sale_id,0),COALESCE(fs.fuel_type_id,0),COALESCE(fs.quantity,0)," +
                    "COALESCE(fs.cost_per_liter,0),COALESCE(fs.cost_amount,0),COALESCE(ft.default_purchase_price,0) " +
                    "FROM fuel_sales fs LEFT JOIN fuel_types ft ON ft.id=fs.fuel_type_id WHERE fs.station_id=? " +
                    "AND fs.status='completed' AND COALESCE(fs.is_deleted,0)=0 AND (COALESCE(fs.cost_per_liter,0)<=0 OR " +
                    "COALESCE(fs.cost_amount,0)<=0 OR (COALESCE(ft.default_purchase_price,0)>0 AND fs.cost_per_liter<ft.default_purchase_price*0.5)) ORDER BY fs.id",
                arrayOf(stationId.toString())
            ) { c -> JSONObject().put("fuel_sale_id", c.getLong(0)).put("sale_id", c.getLong(1))
                .put("fuel_type_id", c.getLong(2)).put("quantity", c.getDouble(3))
                .put("recorded_cost_per_liter", c.getDouble(4)).put("recorded_cost_amount", c.getDouble(5))
                .put("current_default_purchase_price_reference_only", c.getDouble(6)) }
            checks.put("fuel_sales_cost_anomalies", costs)
            add(issues, "fuel_sales_cost_anomalies", "critical",
                "تكلفة وقود تاريخية مفقودة أو شاذة؛ لا يستخدم السعر الحالي لإعادة كتابة تكلفة تاريخية", costs.length(), null, costs)
        }
        if (!tableExists("tank_refills")) return
        val quantity = rows(
            "SELECT id,COALESCE(refill_code,''),COALESCE(delivered_quantity,0),COALESCE(actual_quantity,0)," +
                "COALESCE(tank_level_before,0),COALESCE(tank_level_after,0),COALESCE(net_amount,0) FROM tank_refills " +
                "WHERE station_id=? AND status='completed' AND COALESCE(is_deleted,0)=0 AND " +
                "ABS(COALESCE(actual_quantity,0)-COALESCE(delivered_quantity,0))>0.01 ORDER BY id",
            arrayOf(stationId.toString())
        ) { c -> JSONObject().put("refill_id", c.getLong(0)).put("refill_code", c.getString(1))
            .put("delivered_quantity", c.getDouble(2)).put("actual_quantity", c.getDouble(3))
            .put("tank_level_before", c.getDouble(4)).put("tank_level_after", c.getDouble(5)).put("net_amount", c.getDouble(6)) }
        checks.put("fuel_refill_quantity_anomalies", quantity)
        add(issues, "fuel_refill_quantity_anomalies", "critical",
            "حقل الكمية الفعلية لا يساوي الكمية المسلمة؛ قد يحتوي قراءة مستوى الخزان ولا يجوز استبداله آلياً", quantity.length(), null, quantity)

        val missing = rows(
            "SELECT r.id,COALESCE(r.refill_code,''),COALESCE(r.supplier_id,0),COALESCE(r.net_amount,0) FROM tank_refills r " +
                "WHERE r.station_id=? AND r.status='completed' AND COALESCE(r.is_deleted,0)=0 AND NOT EXISTS(" +
                "SELECT 1 FROM journal_entries j WHERE j.station_id=r.station_id AND j.reference_type='fuel_supply' " +
                "AND j.reference_id=r.id AND COALESCE(j.is_deleted,0)=0) ORDER BY r.id",
            arrayOf(stationId.toString())
        ) { c -> JSONObject().put("refill_id", c.getLong(0)).put("refill_code", c.getString(1))
            .put("supplier_id", c.getLong(2)).put("net_amount", c.getDouble(3))
            .put("supplier_ledger_proof_available", oneExactSupplierCredit(c.getLong(2), c.getLong(0), c.getDouble(3))) }
        checks.put("missing_fuel_supply_journals", missing)
        add(issues, "missing_fuel_supply_journals", "critical",
            "توريدات وقود مكتملة بلا قيد أستاذ عام؛ الحالات ذات الإثبات المطابق تظهر قابلة للإصلاح الآمن", missing.length(), sum(missing, "net_amount"), missing)
    }

    private fun checkPeriodsAndCurrency(stationId: Int, checks: JSONObject, issues: JSONArray) {
        val periodCount = if (hasColumn("fiscal_periods", "station_id")) scalar("SELECT COUNT(*) FROM fiscal_periods WHERE station_id=?", arrayOf(stationId.toString())) else 0
        val unperioded = if (hasColumn("journal_entries", "station_id") && hasColumn("journal_entries", "status") && hasColumn("journal_entries", "fiscal_year") && hasColumn("journal_entries", "fiscal_period")) scalar(
            "SELECT COUNT(*) FROM journal_entries WHERE station_id=? AND status='posted' AND COALESCE(is_deleted,0)=0 AND (fiscal_year IS NULL OR fiscal_period IS NULL)",
            arrayOf(stationId.toString())
        ) else 0
        val noSalesCurrency = if (hasColumn("sales_transactions", "station_id") && hasColumn("sales_transactions", "status") && hasColumn("sales_transactions", "currency_id") && hasColumn("sales_transactions", "amount_in_default")) scalar(
            "SELECT COUNT(*) FROM sales_transactions WHERE station_id=? AND status='completed' AND COALESCE(is_deleted,0)=0 AND (currency_id IS NULL OR amount_in_default IS NULL)",
            arrayOf(stationId.toString())
        ) else 0
        val noPaymentCurrency = if (hasColumn("payments", "station_id") && hasColumn("payments", "status") && hasColumn("payments", "currency_id") && hasColumn("payments", "amount_in_default")) scalar(
            "SELECT COUNT(*) FROM payments WHERE station_id=? AND status='completed' AND COALESCE(is_deleted,0)=0 AND (currency_id IS NULL OR amount_in_default IS NULL)",
            arrayOf(stationId.toString())
        ) else 0
        checks.put("fiscal_period_count", periodCount).put("posted_journals_without_period", unperioded)
            .put("sales_without_currency_or_default_amount", noSalesCurrency).put("payments_without_currency_or_default_amount", noPaymentCurrency)
        if (periodCount == 0 || unperioded > 0)
            add(issues, "fiscal_period_reconciliation_missing", "high",
                "التقويم المالي مفقود أو أن قيوداً مرحلة بلا سنة/فترة مالية؛ يلزم إنشاء واعتماد التقويم من سياسة المحطة", maxOf(if (periodCount == 0) 1 else 0, unperioded), null, null)
        if (noSalesCurrency + noPaymentCurrency > 0)
            add(issues, "historical_currency_evidence_missing", "high",
                "معاملات تاريخية بلا عملة/مبلغ بالعملة الافتراضية؛ لا يجوز اختراع سعر صرف تاريخي", noSalesCurrency + noPaymentCurrency, null, null)
    }

    private fun checkOpeningBalances(stationId: Int, checks: JSONObject, issues: JSONArray) {
        if (!tableExists("accounts")) return
        val stationScope = if (hasColumn("accounts","station_id")) " AND child.station_id=? AND parent.station_id=?" else ""
        val stationArgs = if (stationScope.isNotEmpty()) arrayOf(stationId.toString(),stationId.toString()) else emptyArray()
        val overlaps = rows(
            "SELECT child.id,COALESCE(child.account_code,''),COALESCE(child.opening_balance,0),parent.id," +
                "COALESCE(parent.account_code,''),COALESCE(parent.opening_balance,0) FROM accounts child " +
                "JOIN accounts parent ON parent.id=child.parent_account_id WHERE child.is_active=1 AND parent.is_active=1 " +
                "AND COALESCE(child.is_deleted,0)=0 AND COALESCE(parent.is_deleted,0)=0" + stationScope + " " +
                "AND ABS(COALESCE(child.opening_balance,0))>0.01 AND ABS(COALESCE(parent.opening_balance,0))>0.01 ORDER BY child.id",
            stationArgs
        ) { c -> JSONObject().put("child_account_id", c.getLong(0)).put("child_account_code", c.getString(1))
            .put("child_opening_balance", c.getDouble(2)).put("parent_account_id", c.getLong(3))
            .put("parent_account_code", c.getString(4)).put("parent_opening_balance", c.getDouble(5)) }
        checks.put("opening_balances_on_parent_and_child", overlaps)
        add(issues, "opening_balances_on_parent_and_child", "critical",
            "أرصدة افتتاحية مسجلة في حساب أب وتفاصيله معاً؛ لا يمكن احتساب الميزانية قبل تحديد الرصيد المعتمد", overlaps.length(), null, overlaps)
    }

    private fun oneExactSupplierCredit(supplierId: Long, refillId: Long, amount: Double): Boolean {
        if (!tableExists("supplier_ledger")) return false
        val matches = query(
            "SELECT COALESCE(debit,0),COALESCE(credit,0) FROM supplier_ledger WHERE party_id=? AND transaction_type='fuel_supply_credit' AND transaction_id=?",
            arrayOf(supplierId.toString(), refillId.toString())
        ) { c -> c.getDouble(0) to c.getDouble(1) }
        return matches.size == 1 && abs(matches[0].first) <= 0.01 && abs(matches[0].second - amount) <= 0.01
    }

    private fun add(target: JSONArray, code: String, severity: String, message: String, count: Int, amount: Double?, records: JSONArray?) {
        if (count <= 0) return
        val finding = JSONObject().put("code", code).put("severity", severity).put("message", message).put("count", count)
        if (amount != null) finding.put("amount", amount)
        if (records != null) finding.put("records", records)
        target.put(finding)
    }
    private fun sum(rows: JSONArray, key: String): Double {
        var out = 0.0
        for (i in 0 until rows.length()) out += rows.getJSONObject(i).optDouble(key, 0.0)
        return out
    }
    private fun tableExists(table: String) = scalar("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)) > 0
    private fun columns(table: String): Set<String> {
        if (!table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) || !tableExists(table)) return emptySet()
        val result = linkedSetOf<String>()
        db.rawQuery("PRAGMA table_info(" + table + ")", null).use { c -> while (c.moveToNext()) result.add(c.getString(1)) }
        return result
    }
    private fun hasColumn(table: String, name: String): Boolean = columns(table).contains(name)
    private fun scalar(sql: String, args: Array<String>): Int {
        db.rawQuery(sql, args).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }
    private fun scalarDouble(sql: String, args: Array<String>): Double {
        db.rawQuery(sql, args).use { c -> return if (c.moveToFirst()) c.getDouble(0) else 0.0 }
    }
    private fun rows(sql: String, args: Array<String>, mapper: (Cursor) -> JSONObject): JSONArray {
        val out = JSONArray()
        db.rawQuery(sql, args).use { c -> while (c.moveToNext()) out.put(mapper(c)) }
        return out
    }
    private fun <T> query(sql: String, args: Array<String>, mapper: (Cursor) -> T): List<T> {
        val out = ArrayList<T>()
        db.rawQuery(sql, args).use { c -> while (c.moveToNext()) out.add(mapper(c)) }
        return out
    }
    private fun normalizeDate(input: String): String {
        val value = input.trim().take(10)
        if (value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return value
        return now().take(10)
    }
    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
