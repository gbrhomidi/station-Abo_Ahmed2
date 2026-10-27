package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

/**
 * Repairs source-backed historical inconsistencies for one station.
 *
 * Balances are reconstructed only from existing, internally consistent ledgers.
 * Missing posted journals are recreated only where source amount, source type,
 * accounts, settlement evidence and fiscal-period policy are deterministic.
 * Ambiguous source records are logged as skipped, never guessed.
 */
internal class HistoricalFinancialReconciliationRepair(private val db: SQLiteDatabase) {
    private val eps = 0.01
    private var station = 0
    private var actor = 0L
    private var runId = 0L
    private lateinit var actions: JSONArray

    fun run(stationId: Int, actorId: Long): JSONObject {
        require(stationId > 0 && actorId > 0L) { "المحطة والمستخدم مطلوبان" }
        station = stationId
        actor = actorId
        ensureWorkpaper()
        actions = JSONArray()
        val before = audit()
        val runUuid = UUID.randomUUID().toString()
        val started = now()
        var failure: String? = null

        db.beginTransaction()
        try {
            runId = db.insertOrThrow("financial_reconciliation_runs", null, ContentValues().apply {
                put("run_uuid", runUuid); put("station_id", station); put("actor_user_id", actor)
                put("started_at", started); put("status", "running"); put("summary_json", "{}")
            })
            repairPartyLedgers()
            repairSaleArithmetic()
            repairInventoryTotals()
            repairCashBalances()
            repairBankBalances()
            repairExactDuplicateParentOpenings()
            clearInvalidSettlementLinks()
            repairMissingPayments()
            repairMissingFuelSupplyJournals()
            repairMissingSaleJournals()
            repairMissingCogsJournals()

            val after = audit()
            val repaired = actionCount("repaired")
            val skipped = actionCount("skipped")
            val summary = JSONObject().put("run_id", runId).put("run_uuid", runUuid)
                .put("station_id", station).put("actor_user_id", actor).put("started_at", started)
                .put("finished_at", now()).put("before", before).put("after", after)
                .put("repaired_action_count", repaired).put("skipped_action_count", skipped).put("actions", actions)
            db.update("financial_reconciliation_runs", ContentValues().apply {
                put("finished_at", now())
                put("status", if (after.optBoolean("ready_for_financial_statement_approval", false)) "reconciled" else "completed_with_blockers")
                put("summary_json", summary.toString())
            }, "id=?", arrayOf(runId.toString()))
            db.setTransactionSuccessful()
            return JSONObject().put("success", true)
                .put("status", if (after.optBoolean("ready_for_financial_statement_approval", false)) "reconciled" else "completed_with_blockers")
                .put("message", if (after.optBoolean("ready_for_financial_statement_approval", false))
                    "اكتملت المصالحة ولم يرصد التدقيق عوائق" else
                    "اكتملت الإصلاحات الممكنة؛ بقيت حالات تتطلب دليلاً أو معالجة إضافية")
                .put("run_id", runId).put("station_id", station).put("before", before).put("after", after)
                .put("repaired_action_count", repaired).put("skipped_action_count", skipped)
                .put("actions", actions)
                .put("ready_for_financial_statement_approval", after.optBoolean("ready_for_financial_statement_approval", false))
        } catch (e: Exception) {
            failure = (e.message ?: e.javaClass.simpleName).take(700)
        } finally {
            db.endTransaction()
        }

        // The failed repair transaction rolls back completely. Keep a separate durable failure record.
        val failedId = db.insertOrThrow("financial_reconciliation_runs", null, ContentValues().apply {
            put("run_uuid", runUuid); put("station_id", station); put("actor_user_id", actor)
            put("started_at", started); put("finished_at", now()); put("status", "rolled_back")
            put("summary_json", JSONObject().put("before", before).put("error", failure).put("rolled_back", true).toString())
        })
        return JSONObject().put("success", false).put("status", "rolled_back")
            .put("message", "أُلغيت العملية بالكامل بعد فشل التحقق؛ لم تُحفظ إصلاحات جزئية")
            .put("run_id", failedId).put("error", failure ?: "unknown").put("rolled_back", true).put("before", before)
    }

    private fun ensureWorkpaper() {
        db.execSQL("""CREATE TABLE IF NOT EXISTS financial_reconciliation_runs(
            id INTEGER PRIMARY KEY AUTOINCREMENT,run_uuid TEXT NOT NULL UNIQUE,station_id INTEGER NOT NULL,
            actor_user_id INTEGER NOT NULL,started_at TEXT NOT NULL,finished_at TEXT,status TEXT NOT NULL,
            summary_json TEXT NOT NULL DEFAULT '{}')""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS financial_reconciliation_actions(
            id INTEGER PRIMARY KEY AUTOINCREMENT,run_id INTEGER NOT NULL,station_id INTEGER NOT NULL,
            action_code TEXT NOT NULL,entity_type TEXT NOT NULL,entity_id INTEGER NOT NULL DEFAULT 0,
            status TEXT NOT NULL,explanation TEXT NOT NULL,before_json TEXT NOT NULL DEFAULT '{}',
            after_json TEXT NOT NULL DEFAULT '{}',created_at TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fin_recon_action_run ON financial_reconciliation_actions(run_id,status)")
    }

    private fun repairPartyLedgers() {
        if (!tableExists("parties") || !tableExists("party_types") ||
            !columns("parties").containsAll(setOf("id","station_id","party_type_id","current_balance")) ||
            !hasColumn("party_types","type_code")) return
        val parties = query("SELECT p.id,COALESCE(t.type_code,'') FROM parties p LEFT JOIN party_types t ON t.id=p.party_type_id " +
            "WHERE p.station_id=? AND COALESCE(p.is_deleted,0)=0 ORDER BY p.id", arrayOf(station.toString())) {
            it.getLong(0) to it.getString(1)
        }
        for ((partyId, kind) in parties) {
            val supplier = kind.equals("SUPPLIER", true)
            val table = if (supplier) "supplier_ledger" else "customer_ledger"
            if (!tableExists(table) || !columns(table).containsAll(setOf("id","party_id","debit","credit","balance","transaction_date"))) continue
            val ledgerCols = columns(table)
            val ledgerScope = if (ledgerCols.contains("station_id")) " AND station_id=?" else ""
            val ledgerArgs = if (ledgerCols.contains("station_id")) arrayOf(partyId.toString(), station.toString()) else arrayOf(partyId.toString())
            val typeExpr = if (ledgerCols.contains("transaction_type")) "COALESCE(transaction_type,'')" else "''"
            val entries = query("SELECT id,COALESCE(debit,0),COALESCE(credit,0),COALESCE(balance,0)," + typeExpr + " FROM " + table +
                " WHERE party_id=?" + ledgerScope + " ORDER BY transaction_date,id", ledgerArgs) {
                Ledger(it.getLong(0),it.getDouble(1),it.getDouble(2),it.getDouble(3),it.getString(4))
            }
            if (entries.isEmpty()) {
                val bal = scalarD("SELECT COALESCE(current_balance,0) FROM parties WHERE id=? AND station_id=?",
                    arrayOf(partyId.toString(),station.toString()))
                if (abs(bal)>eps) skip("party_opening_without_ledger", "party", partyId,
                    "رصيد افتتاحي بلا حركة دفترية؛ لم يُصفّر ولم يُنشأ سند تخميني", JSONObject().put("current_balance",bal))
                continue
            }
            val invalid = entries.firstOrNull { it.debit < -eps || it.credit < -eps || (it.debit > eps && it.credit > eps) }
            if (invalid != null) {
                skip("party_ledger_line_invalid", table, invalid.id, "حركة دفترية غير صالحة؛ لم يُعد بناء أي رصيد لهذا الطرف",
                    JSONObject().put("debit",invalid.debit).put("credit",invalid.credit))
                continue
            }
            var running=0.0; var debits=0.0; var credits=0.0
            for (e in entries) {
                debits+=e.debit; credits+=e.credit
                running += if (supplier) e.credit-e.debit else e.debit-e.credit
                if (abs(e.balance-running)>eps) {
                    db.update(table, ContentValues().apply { put("balance",running) }, "id=? AND party_id=?",
                        arrayOf(e.id.toString(),partyId.toString()))
                    action("rebuild_party_running_balance",table,e.id,"repaired",
                        "إعادة احتساب الرصيد الجاري من دفتر الحركات دون تغيير مبالغ المدين والدائن",
                        JSONObject().put("balance",e.balance),JSONObject().put("balance",running))
                }
            }
            val balance=if(supplier) credits-debits else debits-credits
            val old=JSONObject().put("current_balance",scalarD("SELECT COALESCE(current_balance,0) FROM parties WHERE id=? AND station_id=?",
                arrayOf(partyId.toString(),station.toString())))
            val typeClasses = entries.map { it.type.trim().lowercase(Locale.ROOT) }.map { type ->
                when (type) {
                    "fuel_supply_cash", "fuel_supply_credit", "purchase", "supplier_purchase", "purchase_invoice",
                    "inventory_purchase", "fuel_purchase" -> "purchase"
                    "supplier_payment", "purchase_payment", "supplier_payment_voucher", "payment" -> "payment"
                    "opening", "opening_balance", "balance_brought_forward" -> "opening"
                    else -> "unknown"
                }
            }
            val supplierTotalsProven = supplier && ledgerCols.contains("transaction_type") &&
                typeClasses.isNotEmpty() && typeClasses.none { it == "unknown" }
            val purchases = entries.indices.filter { typeClasses.getOrNull(it) == "purchase" }.sumOf { entries[it].credit }
            val payments = entries.indices.filter { typeClasses.getOrNull(it) == "payment" }.sumOf { entries[it].debit }
            val cv=ContentValues().apply {
                put("current_balance",balance)
                if(supplier && hasColumn("parties","total_due")) put("total_due",balance)
                if(supplierTotalsProven && hasColumn("parties","total_purchases")) put("total_purchases",purchases)
                if(supplierTotalsProven && hasColumn("parties","total_payments")) put("total_payments",payments)
            }
            val changed=abs(old.optDouble("current_balance")-balance)>eps ||
                (supplier && hasColumn("parties","total_due") && abs(scalarD("SELECT COALESCE(total_due,0) FROM parties WHERE id=? AND station_id=?",arrayOf(partyId.toString(),station.toString()))-balance)>eps) ||
                (supplierTotalsProven && hasColumn("parties","total_purchases") && abs(scalarD("SELECT COALESCE(total_purchases,0) FROM parties WHERE id=? AND station_id=?",arrayOf(partyId.toString(),station.toString()))-purchases)>eps) ||
                (supplierTotalsProven && hasColumn("parties","total_payments") && abs(scalarD("SELECT COALESCE(total_payments,0) FROM parties WHERE id=? AND station_id=?",arrayOf(partyId.toString(),station.toString()))-payments)>eps)
            if(changed) {
                db.update("parties",cv,"id=? AND station_id=?",arrayOf(partyId.toString(),station.toString()))
                val next=JSONObject().put("current_balance",balance)
                if(supplier && hasColumn("parties","total_due")) next.put("total_due",balance)
                if(supplierTotalsProven && hasColumn("parties","total_purchases")) next.put("total_purchases",purchases)
                if(supplierTotalsProven && hasColumn("parties","total_payments")) next.put("total_payments",payments)
                action("reconcile_party_totals_from_ledger","parties",partyId,"repaired",
                    if(supplier) "تحديث رصيد المورد؛ إجماليات الشراء/السداد لا تتغير إلا إذا صُنفت أنواع كل الحركات" else "تحديث رصيد العميل من دفتر العميل",old,next)
            }
            val supplierAggregatesDiffer =
                (hasColumn("parties","total_purchases") &&
                    abs(scalarD("SELECT COALESCE(total_purchases,0) FROM parties WHERE id=? AND station_id=?",
                        arrayOf(partyId.toString(),station.toString())) - credits) > eps) ||
                (hasColumn("parties","total_payments") &&
                    abs(scalarD("SELECT COALESCE(total_payments,0) FROM parties WHERE id=? AND station_id=?",
                        arrayOf(partyId.toString(),station.toString())) - debits) > eps)
            if (supplier && !supplierTotalsProven && supplierAggregatesDiffer) {
                skip("supplier_totals_source_types_ambiguous","party",partyId,
                    "أنواع حركات المورد لا تكفي لإثبات إجمالي المشتريات والمدفوعات؛ لم تُغيّر الإجماليات",
                    JSONObject().put("ledger_credits",credits).put("ledger_debits",debits))
            }
        }
    }
    private data class Ledger(val id:Long,val debit:Double,val credit:Double,val balance:Double,val type:String)

    private fun repairSaleArithmetic() {
        if(!tableExists("sales_transactions")) return
        val c=columns("sales_transactions")
        if(!c.containsAll(setOf("id","station_id","status","net_amount","paid_amount","remaining_amount"))) return
        val sales=query("SELECT id,COALESCE(net_amount,0),COALESCE(paid_amount,0),COALESCE(remaining_amount,0) " +
            "FROM sales_transactions WHERE station_id=? AND status='completed'"+
            (if(c.contains("is_deleted")) " AND COALESCE(is_deleted,0)=0" else "")+" ORDER BY id",arrayOf(station.toString())) {
            SaleArithmetic(it.getLong(0),it.getDouble(1),it.getDouble(2),it.getDouble(3))
        }
        for(s in sales) {
            if(!s.net.isFinite() || !s.paid.isFinite() || s.net < -eps || s.paid < -eps || s.paid > s.net+eps) {
                skip("sale_payment_split_ambiguous","sale",s.id,"المدفوع لا يمكن مطابقته مع صافي الفاتورة؛ لم يُغيّر المبلغ المصدر",
                    JSONObject().put("net",s.net).put("paid",s.paid).put("remaining",s.remaining)); continue
            }
            val next=max(0.0,s.net-s.paid)
            if(abs(next-s.remaining)>eps) {
                db.update("sales_transactions",ContentValues().apply{put("remaining_amount",next)},
                    "id=? AND station_id=?",arrayOf(s.id.toString(),station.toString()))
                action("repair_sale_remaining_amount","sales_transactions",s.id,"repaired",
                    "المتبقي = صافي الفاتورة - المدفوع؛ لم يُغيّر مبلغ البيع أو المدفوع",
                    JSONObject().put("net",s.net).put("paid",s.paid).put("remaining",s.remaining),
                    JSONObject().put("net",s.net).put("paid",s.paid).put("remaining",next))
            }
        }
    }
    private data class SaleArithmetic(val id:Long,val net:Double,val paid:Double,val remaining:Double)

    private fun repairInventoryTotals() {
        if(!tableExists("products") || !tableExists("inventory_levels") ||
            !columns("products").containsAll(setOf("id","station_id","quantity")) ||
            !columns("inventory_levels").containsAll(setOf("product_id","quantity_on_hand"))) return
        val ps=query("SELECT id,COALESCE(quantity,0) FROM products WHERE station_id=?"+
            (if(hasColumn("products","is_deleted")) " AND COALESCE(is_deleted,0)=0" else "")+" ORDER BY id",
            arrayOf(station.toString())) { it.getLong(0) to it.getDouble(1) }
        val lf=if(hasColumn("inventory_levels","is_deleted")) " AND COALESCE(is_deleted,0)=0" else ""
        for((id,old) in ps) {
            if(scalar("SELECT COUNT(*) FROM inventory_levels WHERE product_id=?"+lf,arrayOf(id.toString()))==0) continue
            val qty=scalarD("SELECT COALESCE(SUM(quantity_on_hand),0) FROM inventory_levels WHERE product_id=?"+lf,arrayOf(id.toString()))
            if(!qty.isFinite() || qty < -eps) {
                skip("inventory_quantity_invalid","product",id,"إجمالي كميات المستودعات غير صالح",JSONObject().put("quantity",old).put("warehouse_total",qty)); continue
            }
            if(abs(qty-old)>eps) {
                db.update("products",ContentValues().apply{put("quantity",qty)},"id=? AND station_id=?",arrayOf(id.toString(),station.toString()))
                action("reconcile_product_quantity","products",id,"repaired","الكمية الإجمالية مشتقة من أرصدة المستودعات المسجلة",
                    JSONObject().put("quantity",old),JSONObject().put("quantity",qty))
            }
        }
    }

    private fun repairCashBalances() {
        if(!tableExists("cash_boxes") || !tableExists("cash_movements") ||
            !columns("cash_boxes").containsAll(setOf("id","station_id","opening_balance","current_balance")) ||
            !columns("cash_movements").containsAll(setOf("id","cash_box_id","movement_type","amount","balance_before","balance_after"))) return
        val boxes=query("SELECT id,COALESCE(opening_balance,0),COALESCE(current_balance,0) FROM cash_boxes WHERE station_id=?"+
            (if(hasColumn("cash_boxes","is_deleted")) " AND COALESCE(is_deleted,0)=0" else "")+" ORDER BY id",arrayOf(station.toString())) {
            Triple(it.getLong(0),it.getDouble(1),it.getDouble(2))
        }
        for((id,opening,current) in boxes) {
            val movementScope = (if (hasColumn("cash_movements", "station_id")) " AND station_id=?" else "") +
                (if (hasColumn("cash_movements", "is_deleted")) " AND COALESCE(is_deleted,0)=0" else "") +
                (if (hasColumn("cash_movements", "status")) " AND lower(status) IN ('completed','posted','approved')" else "")
            val movementArgs = if (hasColumn("cash_movements", "station_id")) arrayOf(id.toString(), station.toString()) else arrayOf(id.toString())
            val ms=query("SELECT movement_type,COALESCE(amount,0),balance_before,balance_after FROM cash_movements WHERE cash_box_id=?"+
                movementScope+" ORDER BY id",movementArgs) {
                Movement(it.getString(0).lowercase(Locale.ROOT),it.getDouble(1),
                    if(it.isNull(2)) Double.NaN else it.getDouble(2),if(it.isNull(3)) Double.NaN else it.getDouble(3))
            }
            var expected=opening; var previous=opening; var valid=true
            for(m in ms) {
                val delta=when(m.type){"in"->m.amount;"out","refund"-> -m.amount;else->Double.NaN}
                if(!m.amount.isFinite() || m.amount<0 || !m.before.isFinite() || !m.after.isFinite() ||
                    !delta.isFinite() || abs(m.before-previous)>eps || abs(m.before+delta-m.after)>eps) {valid=false;break}
                expected+=delta; previous=m.after
            }
            if(valid && ms.isNotEmpty() && abs(previous-expected)<=eps && abs(current-expected)>eps) {
                db.update("cash_boxes",ContentValues().apply{put("current_balance",expected)},"id=? AND station_id=?",arrayOf(id.toString(),station.toString()))
                action("reconcile_cash_box_balance","cash_boxes",id,"repaired","تحديث الرصيد الحالي فقط من سلسلة حركات صحيحة؛ بقيت الحركات كما هي",
                    JSONObject().put("opening_balance",opening).put("current_balance",current),
                    JSONObject().put("opening_balance",opening).put("current_balance",expected))
            } else if(!valid) skip("cash_movement_chain_ambiguous","cash_box",id,
                "سلسلة الصندوق مكسورة أو تحتوي نوع حركة غير معروف؛ لم يُخمن الرصيد ولم تُعدّل الحركات",
                JSONObject().put("opening_balance",opening).put("current_balance",current).put("movement_count",ms.size))
        }
    }
    private data class Movement(val type:String,val amount:Double,val before:Double,val after:Double)

    private fun repairBankBalances() {
        if(!tableExists("bank_accounts") || !tableExists("bank_ledger") ||
            !columns("bank_accounts").containsAll(setOf("id","station_id","opening_balance","current_balance")) ||
            !columns("bank_ledger").containsAll(setOf("id","bank_account_id","debit","credit","balance"))) return
        val accounts=query("SELECT id,COALESCE(opening_balance,0),COALESCE(current_balance,0) FROM bank_accounts WHERE station_id=?"+
            (if(hasColumn("bank_accounts","is_deleted")) " AND COALESCE(is_deleted,0)=0" else "")+" ORDER BY id",arrayOf(station.toString())) {
            Triple(it.getLong(0),it.getDouble(1),it.getDouble(2))
        }
        for((id,opening,current) in accounts) {
            val bankLedgerScope = (if (hasColumn("bank_ledger", "station_id")) " AND station_id=?" else "") +
                (if (hasColumn("bank_ledger", "is_deleted")) " AND COALESCE(is_deleted,0)=0" else "") +
                (if (hasColumn("bank_ledger", "status")) " AND lower(status) IN ('completed','posted','approved')" else "")
            val bankLedgerArgs = if (hasColumn("bank_ledger", "station_id")) arrayOf(id.toString(), station.toString()) else arrayOf(id.toString())
            val entries=query("SELECT COALESCE(debit,0),COALESCE(credit,0),COALESCE(balance,0) FROM bank_ledger WHERE bank_account_id=?"+bankLedgerScope+" ORDER BY id",
                bankLedgerArgs) { BankLine(it.getDouble(0),it.getDouble(1),it.getDouble(2)) }
            if(entries.isEmpty()) continue
            var expected=opening; var valid=true
            for(e in entries) {
                val after=expected+e.debit-e.credit
                if(e.debit < -eps || e.credit < -eps || (e.debit>eps && e.credit>eps) || abs(after-e.balance)>eps) {valid=false;break}
                expected=after
            }
            if(valid && abs(current-expected)>eps) {
                db.update("bank_accounts",ContentValues().apply{put("current_balance",expected)},"id=? AND station_id=?",arrayOf(id.toString(),station.toString()))
                action("reconcile_bank_balance","bank_accounts",id,"repaired","تحديث الرصيد من دفتر بنك متسق دون تعديل حركاته",
                    JSONObject().put("opening_balance",opening).put("current_balance",current),
                    JSONObject().put("opening_balance",opening).put("current_balance",expected))
            } else if(!valid) skip("bank_ledger_chain_ambiguous","bank_account",id,
                "دفتر البنك لا يحقق تسلسل الرصيد؛ لم تُعدّل الحركات أو الرصيد",JSONObject().put("current_balance",current))
        }
    }
    private data class BankLine(val debit:Double,val credit:Double,val balance:Double)

    private fun repairExactDuplicateParentOpenings() {
        if(!tableExists("accounts") || !columns("accounts").containsAll(setOf("id","parent_account_id","opening_balance","is_active","is_deleted"))) return
        // A station-less chart can be shared by all stations: never mutate it from a station-scoped repair.
        if(!hasColumn("accounts","station_id")) {
            val possible = scalar("SELECT COUNT(*) FROM accounts p JOIN accounts c ON c.parent_account_id=p.id " +
                "WHERE p.is_active=1 AND c.is_active=1 AND COALESCE(p.is_deleted,0)=0 AND COALESCE(c.is_deleted,0)=0 " +
                "AND ABS(COALESCE(p.opening_balance,0))>0.01 AND ABS(COALESCE(c.opening_balance,0))>0.01")
            if(possible>0) skip("parent_opening_chart_not_station_scoped","accounts",0,
                "الحسابات ليست معزولة حسب المحطة؛ لم تُعدّل أرصدة افتتاحية مشتركة تلقائياً",
                JSONObject().put("candidate_parent_count",possible))
            return
        }
        val parents=query(
            "SELECT p.id,COALESCE(p.account_code,''),COALESCE(p.opening_balance,0),COALESCE(SUM(c.opening_balance),0) " +
                "FROM accounts p JOIN accounts c ON c.parent_account_id=p.id AND c.station_id=p.station_id " +
                "WHERE p.station_id=? AND p.is_active=1 AND c.is_active=1 " +
                "AND COALESCE(p.is_deleted,0)=0 AND COALESCE(c.is_deleted,0)=0 " +
                "GROUP BY p.id HAVING ABS(COALESCE(p.opening_balance,0))>0.01 " +
                "AND ABS(COALESCE(SUM(c.opening_balance),0))>0.01 ORDER BY p.id",
            arrayOf(station.toString())
        ) { Parent(it.getLong(0),it.getString(1),it.getDouble(2),it.getDouble(3)) }
        for(p in parents) {
            if(abs(p.parent-p.children)<=eps) {
                db.update("accounts",ContentValues().apply{put("opening_balance",0.0)},
                    "id=? AND station_id=?",arrayOf(p.id.toString(),station.toString()))
                action("clear_duplicate_parent_opening","accounts",p.id,"repaired",
                    "أُزيل رصيد افتتاحي مكرر من حساب تجميعي لأن قيمته تساوي تماماً مجموع أرصدة أبنائه في المحطة نفسها؛ حُفظت أرصدة الأبناء",
                    JSONObject().put("account_code",p.code).put("parent_opening_balance",p.parent)
                        .put("children_opening_sum",p.children),
                    JSONObject().put("account_code",p.code).put("parent_opening_balance",0.0)
                        .put("children_opening_sum",p.children))
            } else {
                skip("parent_opening_allocation_ambiguous","account",p.id,
                    "رصيد الأب والأبناء متداخل لكن المبالغ لا تتطابق؛ يلزم تحديد مصدر الرصيد المعتمد",
                    JSONObject().put("account_code",p.code).put("parent_opening_balance",p.parent)
                        .put("children_opening_sum",p.children))
            }
        }
    }
    private data class Parent(val id:Long,val code:String,val parent:Double,val children:Double)

    private fun clearInvalidSettlementLinks() {
        if(!tableExists("accounts")) return
        val ac=columns("accounts")
        if(!ac.containsAll(setOf("id","account_type","is_active","is_deleted","cash_box_id","bank_account_id","is_cash_account","is_bank_account"))) return
        val accountScope = if (ac.contains("station_id")) " AND station_id=?" else ""
        val accountArgs = if (ac.contains("station_id")) arrayOf(station.toString()) else emptyArray()
        val links=query("SELECT id,COALESCE(account_code,''),COALESCE(account_type,''),COALESCE(cash_box_id,0),COALESCE(bank_account_id,0),COALESCE(is_cash_account,0),COALESCE(is_bank_account,0) " +
            "FROM accounts WHERE is_active=1 AND is_deleted=0 AND (COALESCE(cash_box_id,0)>0 OR COALESCE(bank_account_id,0)>0)" + accountScope,accountArgs) {
            AccountLink(it.getLong(0),it.getString(1),it.getString(2),it.getLong(3),it.getLong(4),it.getInt(5),it.getInt(6))
        }
        for(a in links) {
            if(a.cash>0) {
                val existsAnywhere=tableExists("cash_boxes") && scalar("SELECT COUNT(*) FROM cash_boxes WHERE id=?",arrayOf(a.cash.toString()))>0
                val belongsHere=existsAnywhere && (!hasColumn("cash_boxes","station_id") ||
                    scalar("SELECT COUNT(*) FROM cash_boxes WHERE id=? AND station_id=?"+
                        (if(hasColumn("cash_boxes","is_deleted")) " AND COALESCE(is_deleted,0)=0" else ""),
                        arrayOf(a.cash.toString(),station.toString()))>0)
                if (existsAnywhere && !belongsHere) {
                    skip("foreign_station_cash_mapping","accounts",a.id,
                        "رابط الحساب يشير إلى صندوق محطة أخرى؛ حُفظ دون تعديل لتجنب كسر عزل المحطات",
                        JSONObject().put("account_code",a.code).put("cash_box_id",a.cash))
                    continue
                }
                val invalid=(!existsAnywhere || a.type!="asset" || a.isCash!=1 || a.bank>0 || a.isBank==1)
                if(invalid) {
                    db.update("accounts",ContentValues().apply{putNull("cash_box_id");put("is_cash_account",0)},"id=?",arrayOf(a.id.toString()))
                    action("clear_invalid_cash_mapping","accounts",a.id,"repaired","إزالة ربط صندوق غير صالح أو مربوط بحساب غير نقدي دون تغيير الرصيد",
                        JSONObject().put("cash_box_id",a.cash).put("account_type",a.type).put("is_cash_account",a.isCash),
                        JSONObject().put("cash_box_id",JSONObject.NULL).put("is_cash_account",0))
                }
            }
            if(a.bank>0) {
                val existsAnywhere=tableExists("bank_accounts") && scalar("SELECT COUNT(*) FROM bank_accounts WHERE id=?",arrayOf(a.bank.toString()))>0
                val belongsHere=existsAnywhere && (!hasColumn("bank_accounts","station_id") ||
                    scalar("SELECT COUNT(*) FROM bank_accounts WHERE id=? AND station_id=?"+
                        (if(hasColumn("bank_accounts","is_deleted")) " AND COALESCE(is_deleted,0)=0" else ""),
                        arrayOf(a.bank.toString(),station.toString()))>0)
                if (existsAnywhere && !belongsHere) {
                    skip("foreign_station_bank_mapping","accounts",a.id,
                        "رابط الحساب يشير إلى بنك محطة أخرى؛ حُفظ دون تعديل لتجنب كسر عزل المحطات",
                        JSONObject().put("account_code",a.code).put("bank_account_id",a.bank))
                    continue
                }
                val invalid=(!existsAnywhere || a.type!="asset" || a.isBank!=1 || a.cash>0 || a.isCash==1)
                if(invalid) {
                    db.update("accounts",ContentValues().apply{putNull("bank_account_id");put("is_bank_account",0)},"id=?",arrayOf(a.id.toString()))
                    action("clear_invalid_bank_mapping","accounts",a.id,"repaired","إزالة ربط بنك غير صالح أو مربوط بحساب غير بنكي دون تغيير الرصيد",
                        JSONObject().put("bank_account_id",a.bank).put("account_type",a.type).put("is_bank_account",a.isBank),
                        JSONObject().put("bank_account_id",JSONObject.NULL).put("is_bank_account",0))
                }
            }
        }
    }
    private data class AccountLink(val id:Long,val code:String,val type:String,val cash:Long,val bank:Long,val isCash:Int,val isBank:Int)

    private data class Line(val account:Long,val debit:Double,val credit:Double,val text:String)
    private fun repairMissingPayments() {
        if(!tableExists("payments")) return
        val c=columns("payments")
        if(!c.containsAll(setOf("id","station_id","payment_code","amount","status","cash_box_id","bank_account_id","customer_party_id","supplier_party_id","journal_entry_id"))) return
        val rows=query("SELECT id,COALESCE(payment_code,''),COALESCE(amount,0),COALESCE(customer_party_id,0),COALESCE(supplier_party_id,0)," +
            "COALESCE(cash_box_id,0),COALESCE(bank_account_id,0),COALESCE(journal_entry_id,0) FROM payments WHERE station_id=? AND status='completed'"+
            (if(c.contains("is_deleted")) " AND COALESCE(is_deleted,0)=0" else "")+" ORDER BY id",arrayOf(station.toString())) {
            Payment(it.getLong(0),it.getString(1),it.getDouble(2),it.getLong(3),it.getLong(4),it.getLong(5),it.getLong(6),it.getLong(7))
        }
        for(p in rows) {
            if(referenceExists("payment",p.id)) {
                if(!postedReferenceExists("payment",p.id))
                    skip("source_journal_not_posted","payment",p.id,"يوجد قيد سابق غير مرحّل أو ملغى لهذا التسديد؛ لم يُنشأ قيد مكرر تلقائياً",JSONObject().put("payment_code",p.code))
                continue
            }
            if(p.amount<=0 || !p.amount.isFinite() || ((p.customer>0)==(p.supplier>0)) || ((p.cash>0)==(p.bank>0))) {
                skip("payment_source_ambiguous","payment",p.id,"مصدر الدفع أو الطرف أو وسيلة التسوية غير فريد",JSONObject().put("amount",p.amount));continue
            }
            val partyId = if(p.customer>0) p.customer else p.supplier
            val partyBelongsHere = tableExists("parties") && hasColumn("parties","station_id") &&
                scalar("SELECT COUNT(*) FROM parties WHERE id=? AND station_id=?"+
                    (if(hasColumn("parties","is_deleted")) " AND COALESCE(is_deleted,0)=0" else ""),
                    arrayOf(partyId.toString(),station.toString())) == 1
            val ledgerTable = if(p.customer>0) "customer_ledger" else "supplier_ledger"
            val expectedType = if(p.customer>0) "payment" else "supplier_payment"
            val ledgerRows = if(partyBelongsHere && tableExists(ledgerTable) &&
                columns(ledgerTable).containsAll(setOf("party_id","transaction_type","transaction_id","debit","credit")))
                query("SELECT COALESCE(debit,0),COALESCE(credit,0) FROM "+ledgerTable+
                    " WHERE party_id=? AND transaction_type=? AND transaction_id=?",
                    arrayOf(partyId.toString(),expectedType,p.id.toString())) { it.getDouble(0) to it.getDouble(1) }
            else emptyList()
            val ledgerProven = if(p.customer>0) ledgerRows.size==1 &&
                abs(ledgerRows[0].first)<=eps && abs(ledgerRows[0].second-p.amount)<=eps
            else ledgerRows.size==1 && abs(ledgerRows[0].first-p.amount)<=eps && abs(ledgerRows[0].second)<=eps
            if(!partyBelongsHere || !ledgerProven) {
                skip("payment_subledger_evidence_missing","payment",p.id,
                    "لم يُنشأ قيد التسديد: الطرف أو حركة دفتر العميل/المورد لا يثبتان المبلغ نفسه",
                    JSONObject().put("party_id",partyId).put("party_belongs_to_station",partyBelongsHere)
                        .put("ledger_table",ledgerTable).put("ledger_type",expectedType).put("matching_ledger_rows",ledgerRows.size)
                        .put("amount",p.amount))
                continue
            }
            val set=settlement(p.cash,p.bank)
            val control=uniqueCode(if(p.customer>0) "1103" else "2101",if(p.customer>0) "asset" else "liability")
            if(set<=0 || control<=0) {
                skip("payment_accounts_ambiguous","payment",p.id,"حساب التسوية أو الحساب الرقابي غير فريد أو غير صحيح",JSONObject().put("settlement_account",set).put("control_account",control));continue
            }
            val lines=if(p.customer>0) listOf(Line(set,p.amount,0.0,"تحصيل العميل "+p.code),Line(control,0.0,p.amount,"تخفيض ذمم العميل "+p.code))
                else listOf(Line(control,p.amount,0.0,"تخفيض ذمم المورد "+p.code),Line(set,0.0,p.amount,"سداد المورد "+p.code))
            val date=sourceDate("payments",p.id)
            if(!periodOpen(date)) {skip("payment_period_blocked","payment",p.id,"الفترة الأصلية مغلقة أو لا يمكن التحقق من فتحها",JSONObject().put("date",date));continue}
            val jid=postJournal("payment",p.id,p.code,"receipt",p.amount,date,lines,"قيد مصالحة للتسديد "+p.code)
            if(jid>0) {
                if(c.contains("journal_entry_id")) db.update("payments",ContentValues().apply{put("journal_entry_id",jid)},"id=? AND station_id=?",arrayOf(p.id.toString(),station.toString()))
                action("rebuild_payment_journal","payments",p.id,"repaired","قيد مستعاد من سند تسديد وطرف وحساب تسوية مسجل",
                    JSONObject().put("old_journal_id",p.journal).put("amount",p.amount),JSONObject().put("journal_entry_id",jid).put("amount",p.amount))
            }
        }
    }
    private data class Payment(val id:Long,val code:String,val amount:Double,val customer:Long,val supplier:Long,val cash:Long,val bank:Long,val journal:Long)

    private fun repairMissingFuelSupplyJournals() {
        if(!tableExists("tank_refills") || !tableExists("supplier_ledger")) return
        val c=columns("tank_refills")
        if(!c.containsAll(setOf("id","station_id","supplier_id","net_amount","status"))) return
        val code=if(c.contains("refill_code")) "COALESCE(refill_code,'')" else "CAST(id AS TEXT)"
        val method=if(c.contains("payment_method")) "lower(COALESCE(payment_method,''))" else "''"
        val cash=if(c.contains("cash_box_id")) "COALESCE(cash_box_id,0)" else "0"
        val bank=if(c.contains("bank_account_id")) "COALESCE(bank_account_id,0)" else "0"
        val rows=query("SELECT id,"+code+",COALESCE(supplier_id,0),COALESCE(net_amount,0),"+method+","+cash+","+bank+
            " FROM tank_refills WHERE station_id=? AND status='completed'"+
            (if(c.contains("is_deleted")) " AND COALESCE(is_deleted,0)=0" else "")+" ORDER BY id",arrayOf(station.toString())) {
            Refill(it.getLong(0),it.getString(1),it.getLong(2),it.getDouble(3),it.getString(4),it.getLong(5),it.getLong(6))
        }
        for(r in rows) {
            if(referenceExists("fuel_supply",r.id)) {
                if(!postedReferenceExists("fuel_supply",r.id))
                    skip("source_journal_not_posted","tank_refills",r.id,"يوجد قيد سابق غير مرحّل أو ملغى لهذا التوريد؛ لم يُنشأ قيد مكرر تلقائياً",JSONObject().put("refill_code",r.code))
                continue
            }
            val isCash=r.method=="cash"
            val validSource=r.amount>0 && r.amount.isFinite() && r.supplier>0
            val ledgerType=if(isCash) "fuel_supply_cash" else "fuel_supply_credit"
            val evidence=query("SELECT COALESCE(debit,0),COALESCE(credit,0) FROM supplier_ledger WHERE party_id=? AND transaction_type=? AND transaction_id=?",
                arrayOf(r.supplier.toString(),ledgerType,r.id.toString())) { it.getDouble(0) to it.getDouble(1) }
            val proven=evidence.size==1 && if(isCash)
                abs(evidence[0].first)<=eps && abs(evidence[0].second-r.amount)<=eps
            else abs(evidence[0].first)<=eps && abs(evidence[0].second-r.amount)<=eps
            val inv=inventoryAccount()
            val credit=if(isCash) settlement(r.cash,r.bank) else uniqueCode("2101","liability")
            if(!validSource || !proven || inv<=0 || credit<=0 || (isCash && ((r.cash>0)==(r.bank>0))) || (!isCash && r.method!="credit")) {
                skip("fuel_supply_journal_evidence_incomplete","tank_refills",r.id,
                    "قيد التوريد لم يُنشأ: دليل دفتر المورد أو الحساب أو وسيلة التسوية غير مكتمل",
                    JSONObject().put("amount",r.amount).put("supplier_id",r.supplier).put("supplier_ledger_proof",proven).put("inventory_account",inv).put("credit_account",credit));continue
            }
            val date=sourceDate("tank_refills",r.id)
            if(!periodOpen(date)) {skip("fuel_supply_period_blocked","tank_refills",r.id,"الفترة الأصلية مغلقة أو غير قابلة للتحقق",JSONObject().put("date",date));continue}
            val jid=postJournal("fuel_supply",r.id,r.code,"purchase",r.amount,date,listOf(
                Line(inv,r.amount,0.0,"مخزون توريد الوقود "+r.code),
                Line(credit,0.0,r.amount,if(isCash) "سداد توريد الوقود "+r.code else "ذمم المورد لتوريد الوقود "+r.code)
            ),"قيد مصالحة لتوريد الوقود "+r.code)
            if(jid>0) action("rebuild_fuel_supply_journal","tank_refills",r.id,"repaired",
                "قيد مستعاد من مستند التوريد ودفتر المورد المتطابق والحسابات الفريدة",
                JSONObject().put("amount",r.amount),JSONObject().put("journal_entry_id",jid).put("amount",r.amount))
        }
    }
    private data class Refill(val id:Long,val code:String,val supplier:Long,val amount:Double,val method:String,val cash:Long,val bank:Long)

    private fun repairMissingSaleJournals() {
        if(!tableExists("sales_transactions")) return
        val c=columns("sales_transactions")
        if(!c.containsAll(setOf("id","station_id","status","net_amount","paid_amount","remaining_amount"))) return
        val rows=query("SELECT id,"+(if(c.contains("invoice_number")) "COALESCE(invoice_number,'')" else "CAST(id AS TEXT)")+
            ",COALESCE(net_amount,0),COALESCE(paid_amount,0),COALESCE(remaining_amount,0),"+
            (if(c.contains("payment_method")) "lower(COALESCE(payment_method,''))" else "''")+","+
            (if(c.contains("customer_party_id")) "COALESCE(customer_party_id,0)" else "0")+","+
            (if(c.contains("created_at")) "COALESCE(created_at,'')" else "''")+
            " FROM sales_transactions WHERE station_id=? AND status='completed'"+(if(c.contains("is_deleted")) " AND COALESCE(is_deleted,0)=0" else "")+" ORDER BY id",
            arrayOf(station.toString())) { Sale(it.getLong(0),it.getString(1),it.getDouble(2),it.getDouble(3),it.getDouble(4),it.getString(5),it.getLong(6),it.getString(7)) }
        for(s in rows) {
            if(referenceExists("sale",s.id)) {
                if(!postedReferenceExists("sale",s.id))
                    skip("source_journal_not_posted","sales_transactions",s.id,"يوجد قيد سابق غير مرحّل أو ملغى لهذه الفاتورة؛ يلزم فحصه بدلاً من إنشاء قيد مكرر",JSONObject().put("sale_code",s.code))
                continue
            }
            if(s.net<=0 || !s.net.isFinite() || s.paid< -eps || s.paid>s.net+eps) {
                skip("sale_journal_source_invalid","sale",s.id,"صافي البيع/المدفوع غير صالح",JSONObject().put("net",s.net).put("paid",s.paid));continue
            }
            val fuelRows=if(tableExists("fuel_sales") && hasColumn("fuel_sales","sale_id"))
                scalar("SELECT COUNT(*) FROM fuel_sales WHERE sale_id=?"+(if(hasColumn("fuel_sales","station_id")) " AND station_id=?" else "")+
                    (if(hasColumn("fuel_sales","is_deleted")) " AND COALESCE(is_deleted,0)=0" else ""),
                    if(hasColumn("fuel_sales","station_id")) arrayOf(s.id.toString(),station.toString()) else arrayOf(s.id.toString())) else 0
            val itemRows=if(tableExists("sale_items") && hasColumn("sale_items","sale_id")) {
                val ic=columns("sale_items")
                scalar("SELECT COUNT(*) FROM sale_items WHERE sale_id=?"+
                    (if(ic.contains("station_id")) " AND station_id=?" else "")+
                    (if(ic.contains("is_deleted")) " AND COALESCE(is_deleted,0)=0" else "")+
                    (if(ic.contains("item_type")) " AND item_type='product'" else ""),
                    if(ic.contains("station_id")) arrayOf(s.id.toString(),station.toString()) else arrayOf(s.id.toString()))
            } else 0
            val isFuel=when {
                fuelRows>0 && itemRows==0 -> true
                itemRows>0 && fuelRows==0 -> false
                fuelRows==0 && itemRows==0 && c.contains("fuel_type_id") ->
                    scalar("SELECT COUNT(*) FROM sales_transactions WHERE id=? AND COALESCE(fuel_type_id,0)>0",arrayOf(s.id.toString()))>0
                else -> {skip("sale_journal_type_ambiguous","sale",s.id,"مصدر البيع (وقود/منتجات) غير قابل للتحديد",JSONObject().put("fuel_rows",fuelRows).put("item_rows",itemRows));continue}
            }
            val revenue=uniqueCode(if(isFuel) "4101" else "4102","revenue")
            if(revenue<=0) {skip("sale_revenue_account_ambiguous","sale",s.id,"حساب الإيراد المحدد غير موجود أو غير فريد",JSONObject().put("code",if(isFuel) "4101" else "4102"));continue}
            var subsequent=0.0
            if(tableExists("payments") && hasColumn("payments","sale_id") && hasColumn("payments","amount")) {
                val pc=columns("payments")
                subsequent=scalarD("SELECT COALESCE(SUM(amount),0) FROM payments WHERE sale_id=?"+
                    (if(pc.contains("station_id")) " AND station_id=?" else "")+(if(pc.contains("status")) " AND status='completed'" else "")+
                    (if(pc.contains("is_deleted")) " AND COALESCE(is_deleted,0)=0" else ""),
                    if(pc.contains("station_id")) arrayOf(s.id.toString(),station.toString()) else arrayOf(s.id.toString()))
            }
            val initialPaid=s.paid-subsequent
            if(initialPaid < -eps || initialPaid > s.net+eps) {
                skip("sale_payment_split_ambiguous","sale",s.id,"المدفوعات المرتبطة لا تسمح بعزل التحصيل الأصلي من التحصيلات اللاحقة",
                    JSONObject().put("net",s.net).put("paid_total",s.paid).put("payments_linked",subsequent));continue
            }
            val paid=max(0.0,initialPaid); val arAmount=max(0.0,s.net-paid)
            if(paid>eps && s.method=="credit") {
                skip("sale_journal_initial_settlement_unknown","sale",s.id,
                    "الفاتورة مصنفة آجل مع وجود مبلغ مدفوع؛ لا يوجد مصدر موثق للحساب الذي استلم الدفعة",
                    JSONObject().put("paid",paid).put("method",s.method))
                continue
            }
            val set=if(paid>eps) saleSettlementFromSource(s.id,s.method,paid) else 0L
            val ar=if(arAmount>eps) uniqueCode("1103","asset") else 0L
            if ((paid > eps && (set <= 0 || s.method.isBlank())) ||
                (arAmount > eps && (ar <= 0 || s.customer <= 0))) {
                skip("sale_journal_accounts_ambiguous","sale",s.id,"حساب التحصيل أو المدينين أو العميل غير قابل للتحديد الفريد",
                    JSONObject().put("paid",paid).put("receivable",arAmount).put("settlement_account",set).put("receivable_account",ar).put("customer_id",s.customer));continue
            }
            val date=sourceDate("sales_transactions",s.id)
            if(!periodOpen(date)) {skip("sale_period_blocked","sale",s.id,"الفترة الأصلية مغلقة أو غير قابلة للتحقق",JSONObject().put("date",date));continue}
            val lines=ArrayList<Line>()
            if(paid>eps) lines.add(Line(set,paid,0.0,"تحصيل مبيعات "+s.code))
            if(arAmount>eps) lines.add(Line(ar,arAmount,0.0,"ذمم مدينة "+s.code))
            lines.add(Line(revenue,0.0,s.net,"إيراد مبيعات "+s.code))
            val jid=postJournal("sale",s.id,s.code,"sales",s.net,date,lines,"قيد مصالحة لمبيعات "+s.code)
            if(jid>0) action("rebuild_sale_revenue_journal","sales_transactions",s.id,"repaired",
                "قيد مستعاد من فاتورة المصدر وحساب الإيراد المعتمد مع فصل المدفوعات اللاحقة",
                JSONObject().put("net",s.net).put("paid_total",s.paid).put("subsequent_payments",subsequent),
                JSONObject().put("journal_entry_id",jid).put("initial_paid",paid).put("receivable",arAmount))
        }
    }
    private data class Sale(val id:Long,val code:String,val net:Double,val paid:Double,val remaining:Double,val method:String,val customer:Long,val date:String)

    private fun repairMissingCogsJournals() {
        if(!tableExists("sales_transactions") || !columns("sales_transactions").containsAll(setOf("id","station_id","status"))) return
        val suffix=if(hasColumn("sales_transactions","is_deleted")) " AND COALESCE(is_deleted,0)=0" else ""
        val sales=query("SELECT id FROM sales_transactions WHERE station_id=? AND status='completed'"+suffix+" ORDER BY id",arrayOf(station.toString())){it.getLong(0)}
        for(id in sales) {
            if(referenceExists("sale_cogs",id)) {
                if(!postedReferenceExists("sale_cogs",id))
                    skip("source_journal_not_posted","sales_transactions",id,"يوجد قيد تكلفة سابق غير مرحّل أو ملغى؛ لم يُنشأ قيد آخر تلقائياً",JSONObject().put("sale_id",id))
                continue
            }
            val cost=historicalCost(id)
            if(cost<=eps || !cost.isFinite()) {skip("sale_cogs_cost_not_proven","sale",id,"لم تتوفر تكلفة تاريخية مؤكدة؛ لم تُستخدم تكلفة الشراء الحالية",JSONObject().put("cost",cost));continue}
            val inv=inventoryAccount(); val cogs=cogsAccount()
            if(inv<=0 || cogs<=0 || inv==cogs) {skip("sale_cogs_accounts_ambiguous","sale",id,"حساب المخزون/تكلفة المبيعات غير فريد",JSONObject().put("inventory_account",inv).put("cogs_account",cogs));continue}
            val date=sourceDate("sales_transactions",id)
            if(!periodOpen(date)) {skip("sale_cogs_period_blocked","sale",id,"الفترة الأصلية مغلقة أو غير قابلة للتحقق",JSONObject().put("date",date));continue}
            val code=if(hasColumn("sales_transactions","invoice_number")) scalarS("SELECT COALESCE(invoice_number,CAST(id AS TEXT)) FROM sales_transactions WHERE id=? AND station_id=?",arrayOf(id.toString(),station.toString())) else id.toString()
            val jid=postJournal("sale_cogs",id,code,"sales",cost,date,listOf(
                Line(cogs,cost,0.0,"تكلفة البضاعة المباعة "+code),Line(inv,0.0,cost,"تخفيض المخزون "+code)
            ),"قيد مصالحة لتكلفة المبيعات "+code)
            if(jid>0) action("rebuild_sale_cogs_journal","sales_transactions",id,"repaired","تكلفة مستعادة من بنود بيع/حركات مخزون تحمل تكلفة تاريخية مثبتة",
                JSONObject().put("cost",cost),JSONObject().put("journal_entry_id",jid).put("cost",cost))
        }
    }

    /**
     * Returns only a cost that can be reproduced from source-level historical costs.
     * Mixed invoices add fuel and product cost components; current/default purchase prices
     * are never used. Missing or contradictory line costs return zero so the caller logs a skip.
     */
    private fun historicalCost(id:Long):Double {
        var fuelRows = 0
        var fuelCost = 0.0
        if (tableExists("fuel_sales")) {
            val c=columns("fuel_sales")
            if (c.containsAll(setOf("sale_id","cost_amount","quantity"))) {
                val scope=(if(c.contains("station_id")) " AND station_id=?" else "")+
                    (if(c.contains("status")) " AND status='completed'" else "")+
                    (if(c.contains("is_deleted")) " AND COALESCE(is_deleted,0)=0" else "")
                val args=if(c.contains("station_id")) arrayOf(id.toString(),station.toString()) else arrayOf(id.toString())
                fuelRows=scalar("SELECT COUNT(*) FROM fuel_sales WHERE sale_id=?"+scope,args)
                if(fuelRows>0) {
                    val bad = scalar("SELECT COUNT(*) FROM fuel_sales WHERE sale_id=?"+scope+
                        " AND (COALESCE(quantity,0)<=0 OR COALESCE(cost_amount,0)<=0"+
                        (if(c.contains("cost_per_liter")) " OR COALESCE(cost_per_liter,0)<=0" else "")+")",args)
                    if(bad>0) return 0.0
                    fuelCost=scalarD("SELECT COALESCE(SUM(cost_amount),0) FROM fuel_sales WHERE sale_id=?"+scope,args)
                    if(!fuelCost.isFinite() || fuelCost<=eps) return 0.0
                }
            }
        }

        var productRows = 0
        var itemCost: Double? = null
        if(tableExists("sale_items")) {
            val c=columns("sale_items")
            if(c.containsAll(setOf("sale_id","quantity"))) {
                val itemType=if(c.contains("item_type")) " AND item_type='product'" else ""
                val scope=itemType+(if(c.contains("station_id")) " AND station_id=?" else "")+
                    (if(c.contains("is_deleted")) " AND COALESCE(is_deleted,0)=0" else "")
                val args=if(c.contains("station_id")) arrayOf(id.toString(),station.toString()) else arrayOf(id.toString())
                productRows=scalar("SELECT COUNT(*) FROM sale_items WHERE sale_id=?"+scope,args)
                if(productRows>0) {
                    val costColumn=when { c.contains("cost_price")->"cost_price"; c.contains("unit_cost")->"unit_cost"; else->"" }
                    if(costColumn.isNotEmpty()) {
                        val bad=scalar("SELECT COUNT(*) FROM sale_items WHERE sale_id=?"+scope+
                            " AND (COALESCE(quantity,0)<=0 OR COALESCE("+costColumn+",0)<=0)",args)
                        if(bad==0) {
                            val value=scalarD("SELECT COALESCE(SUM(quantity*"+costColumn+"),0) FROM sale_items WHERE sale_id=?"+scope,args)
                            if(value.isFinite() && value>eps) itemCost=value
                        }
                    }
                }
            }
        }

        var movementRows=0
        var movementCost: Double? = null
        var movementSourceInvalid=false
        if(tableExists("inventory_movements") && hasColumn("inventory_movements","reference_type") &&
            hasColumn("inventory_movements","reference_id") && hasColumn("inventory_movements","quantity_change") &&
            hasColumn("inventory_movements","unit_cost") && (productRows>0 || fuelRows==0)) {
            val c=columns("inventory_movements")
            val scope=(if(c.contains("station_id")) " AND station_id=?" else "")+
                (if(c.contains("status")) " AND status='completed'" else "")+
                (if(c.contains("is_deleted")) " AND COALESCE(is_deleted,0)=0" else "")
            val args=if(c.contains("station_id")) arrayOf(id.toString(),station.toString()) else arrayOf(id.toString())
            val predicate=" WHERE reference_type='sale' AND reference_id=?"+scope
            movementRows=scalar("SELECT COUNT(*) FROM inventory_movements"+predicate,args)
            if(movementRows>0) {
                movementSourceInvalid=scalar("SELECT COUNT(*) FROM inventory_movements"+predicate+
                    " AND (ABS(COALESCE(quantity_change,0))<=0.000001 OR COALESCE(unit_cost,0)<=0)",args)>0
                if(!movementSourceInvalid) {
                    val value=scalarD("SELECT COALESCE(SUM(ABS(quantity_change)*unit_cost),0) FROM inventory_movements"+predicate,args)
                    if(value.isFinite() && value>eps) movementCost=value
                }
            }
        }

        var productCost=0.0
        if(productRows>0) {
            // If movements exist for these product lines but their historical costing is incomplete,
            // avoid posting a partial COGS journal even when item data looks plausible.
            if(movementRows>0 && movementSourceInvalid) return 0.0
            if(itemCost!=null && movementCost!=null &&
                abs(itemCost-movementCost)>max(0.01, max(itemCost,movementCost)*0.01)) return 0.0
            productCost=itemCost ?: movementCost ?: return 0.0
        } else if(fuelRows==0 && movementRows>0) {
            if(movementSourceInvalid || movementCost==null) return 0.0
            productCost=movementCost
        }
        val total=fuelCost+productCost
        return if(total.isFinite() && total>eps && (fuelRows>0 || productRows>0 || movementRows>0)) total else 0.0
    }

    private fun postJournal(refType:String,refId:Long,refCode:String,entryType:String,amount:Double,date:String,lines:List<Line>,description:String):Long {
        if(referenceExists(refType,refId)) return 0L
        if(amount<=0 || !amount.isFinite() || lines.size<2 ||
            abs(lines.sumOf{it.debit}-amount)>eps || abs(lines.sumOf{it.credit}-amount)>eps) {
            skip("journal_not_balanced",refType,refId,"فشل القيد اختبار توازن المدين والدائن",JSONObject().put("amount",amount));return 0L
        }
        if(!tableExists("journal_entries") || !tableExists("journal_entry_items")) return 0L
        val h=columns("journal_entries"); val l=columns("journal_entry_items")
        if(!h.containsAll(setOf("uuid","entry_number","entry_date","entry_type","reference_type","reference_id","reference_code","total_debit","total_credit","is_balanced","status","station_id","is_deleted")) ||
            !l.containsAll(setOf("uuid","journal_entry_id","line_number","account_id","debit","credit"))) {
            skip("journal_schema_incomplete",refType,refId,"مخطط القيد أو البنود غير مكتمل؛ لم يُنشأ قيد جزئي",JSONObject());return 0L
        }
        val d=dateOnly(date)
        if(d.isBlank()) { skip("source_date_missing",refType,refId,"تاريخ مستند المصدر غير صالح",JSONObject().put("date",date)); return 0L }
        val stamp=now()
        val header=ContentValues().apply {
            put("uuid",UUID.randomUUID().toString())
            put("entry_number","HREC-"+station+"-"+refType.replace(Regex("[^A-Za-z0-9]"),"")+"-"+refId+"-"+UUID.randomUUID().toString().take(6))
            put("entry_date",d);put("entry_type",entryType);put("reference_type",refType);put("reference_id",refId);put("reference_code",refCode)
            put("total_debit",amount);put("total_credit",amount);put("is_balanced",1);put("status","posted");put("station_id",station);put("is_deleted",0)
            if(h.contains("description")) put("description",description)
            if(h.contains("description_ar")) put("description_ar",description)
            if(h.contains("posted_at")) put("posted_at",stamp)
            if(h.contains("posted_by")) put("posted_by",actor)
            if(h.contains("created_at")) put("created_at",stamp)
            if(h.contains("updated_at")) put("updated_at",stamp)
            if(h.contains("created_by")) put("created_by",actor)
            if(h.contains("updated_by")) put("updated_by",actor)
            if(h.contains("fiscal_year")) put("fiscal_year",d.take(4).toIntOrNull()?:0)
            if(h.contains("fiscal_period")) put("fiscal_period",d.substring(5,7).toIntOrNull()?:0)
            if(h.contains("extra_data")) put("extra_data",JSONObject().put("reconciliation_run_id",runId).put("source_type",refType).put("source_id",refId).toString())
        }
        val id=db.insertOrThrow("journal_entries",null,header)
        var lineNo=1
        for(line in lines) db.insertOrThrow("journal_entry_items",null,ContentValues().apply {
            put("uuid",UUID.randomUUID().toString());put("journal_entry_id",id);put("line_number",lineNo++)
            put("account_id",line.account);put("debit",line.debit);put("credit",line.credit)
            if(l.contains("description")) put("description",line.text)
            if(l.contains("description_ar")) put("description_ar",line.text)
        })
        return id
    }

    private fun referenceExists(type:String,id:Long):Boolean {
        if(!tableExists("journal_entries") || !columns("journal_entries").containsAll(setOf("station_id","reference_type","reference_id"))) return true
        return scalar("SELECT COUNT(*) FROM journal_entries WHERE station_id=? AND reference_type=? AND reference_id=?"+
            (if(hasColumn("journal_entries","is_deleted")) " AND COALESCE(is_deleted,0)=0" else ""),
            arrayOf(station.toString(),type,id.toString()))>0
    }
    private fun postedReferenceExists(type:String,id:Long):Boolean {
        if(!tableExists("journal_entries") ||
            !columns("journal_entries").containsAll(setOf("station_id","reference_type","reference_id","status"))) return false
        val deleted=if(hasColumn("journal_entries","is_deleted")) " AND COALESCE(is_deleted,0)=0" else ""
        return scalar("SELECT COUNT(*) FROM journal_entries WHERE station_id=? AND reference_type=? AND reference_id=? AND status='posted'"+deleted,
            arrayOf(station.toString(),type,id.toString()))>0
    }

    private fun accountStationFilter(): String =
        if (hasColumn("accounts", "station_id")) " AND station_id=?" else ""

    private fun accountStationArgs(args: Array<String>): Array<String> =
        if (hasColumn("accounts", "station_id")) args + station.toString() else args

    private fun settlement(cashId:Long,bankId:Long):Long {
        if((cashId>0)==(bankId>0) || !tableExists("accounts")) return 0L
        val isCash = cashId > 0L
        val table = if(isCash) "cash_boxes" else "bank_accounts"
        val id = if(isCash) cashId else bankId
        if(!tableExists(table) || !hasColumn(table,"station_id")) return 0L
        if(scalar("SELECT COUNT(*) FROM "+table+" WHERE id=? AND station_id=?"+
            (if(hasColumn(table,"is_deleted")) " AND COALESCE(is_deleted,0)=0" else ""),
            arrayOf(id.toString(),station.toString())) != 1) return 0L
        return if(isCash) uniqueId("SELECT id FROM accounts WHERE cash_box_id=? AND account_type='asset' AND is_cash_account=1 AND is_active=1 AND is_deleted=0" + accountStationFilter(),accountStationArgs(arrayOf(cashId.toString())))
            else uniqueId("SELECT id FROM accounts WHERE bank_account_id=? AND account_type='asset' AND is_bank_account=1 AND is_active=1 AND is_deleted=0" + accountStationFilter(),accountStationArgs(arrayOf(bankId.toString())))
    }
    private fun saleSettlementFromSource(saleId:Long, method:String, amount:Double):Long {
        if(!amount.isFinite() || amount<=0) return 0L
        if(method=="cash" && tableExists("cash_movements") &&
            columns("cash_movements").containsAll(setOf("cash_box_id","movement_type","amount","reference_type","reference_id"))) {
            val rows=query("SELECT cash_box_id,COALESCE(amount,0) FROM cash_movements WHERE reference_type='sale' AND reference_id=?"+
                (if(hasColumn("cash_movements","is_deleted")) " AND COALESCE(is_deleted,0)=0" else "")+
                " AND lower(movement_type)='in'",arrayOf(saleId.toString())) { it.getLong(0) to it.getDouble(1) }
                .filter { it.first>0 && abs(it.second-amount)<=eps }
            if(rows.size!=1) return 0L
            return settlement(rows[0].first,0L)
        }
        if(method in setOf("bank_transfer","cheque","credit_card","mobile_money") &&
            tableExists("bank_ledger") && columns("bank_ledger").containsAll(setOf("bank_account_id","transaction_type","transaction_id","debit","credit"))) {
            val rows=query("SELECT bank_account_id,COALESCE(debit,0),COALESCE(credit,0) FROM bank_ledger WHERE transaction_id=?"+
                " AND lower(transaction_type) IN ('sale','sales','sale_receipt')",arrayOf(saleId.toString())) {
                Triple(it.getLong(0),it.getDouble(1),it.getDouble(2))
            }.filter { it.first>0 && abs(it.second-amount)<=eps && abs(it.third)<=eps }
            if(rows.size!=1) return 0L
            return settlement(0L,rows[0].first)
        }
        return 0L
    }

    private fun uniqueGenericSettlement(cash:Boolean):Long {
        if(!tableExists("accounts")) return 0
        val canonical=if(cash) "SELECT id FROM accounts WHERE account_code='1101' AND account_type='asset' AND is_cash_account=1 AND is_active=1 AND is_deleted=0" + accountStationFilter()
            else "SELECT id FROM accounts WHERE is_bank_account=1 AND account_type='asset' AND is_active=1 AND is_deleted=0" + accountStationFilter()
        val preferred=uniqueId(canonical,accountStationArgs(emptyArray()))
        if(preferred>0) return preferred
        val fallback=if(cash) "SELECT id FROM accounts WHERE account_type='asset' AND is_cash_account=1 AND is_active=1 AND is_deleted=0"
            else "SELECT id FROM accounts WHERE account_type='asset' AND is_bank_account=1 AND is_active=1 AND is_deleted=0"
        return uniqueId(fallback+accountStationFilter(),accountStationArgs(emptyArray()))
    }
    private fun uniqueCode(code:String,type:String):Long =
        if(!tableExists("accounts") || !columns("accounts").containsAll(setOf("account_code","account_type","is_active","is_deleted"))) 0
        else uniqueId("SELECT id FROM accounts WHERE account_code=? AND account_type=? AND is_active=1 AND is_deleted=0" + accountStationFilter(),accountStationArgs(arrayOf(code,type)))
    private fun inventoryAccount():Long = if(!tableExists("accounts")) 0 else uniqueId(
        """SELECT id FROM accounts WHERE account_type='asset' AND is_active=1 AND is_deleted=0 AND (
           account_code='1201' OR lower(COALESCE(account_category,'')) LIKE '%inventory%' OR
           lower(COALESCE(account_category,'')) LIKE '%stock%' OR lower(COALESCE(account_name,'')) LIKE '%inventory%' OR
           COALESCE(account_name_ar,'') LIKE '%المخزون%' OR COALESCE(account_name_ar,'') LIKE '%البضاعة%')""" + accountStationFilter(),accountStationArgs(emptyArray()))
    private fun cogsAccount():Long = if(!tableExists("accounts")) 0 else uniqueId(
        """SELECT id FROM accounts WHERE account_type='expense' AND is_active=1 AND is_deleted=0 AND (
           account_code='5101' OR lower(COALESCE(account_category,'')) LIKE '%cogs%' OR
           lower(COALESCE(account_name,'')) LIKE '%cost of goods sold%' OR COALESCE(account_name_ar,'') LIKE '%تكلفة البضاعة%')""" + accountStationFilter(),accountStationArgs(emptyArray()))

    private fun periodOpen(date:String):Boolean {
        if (!date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return false
        if(!tableExists("fiscal_periods")) return false
        val c=columns("fiscal_periods")
        if(!c.containsAll(setOf("station_id","start_date","end_date"))) return false
        val status=if(c.contains("status")) " AND lower(status) IN ('open','active','opened')" else ""
        val closed=if(c.contains("is_closed")) " AND COALESCE(is_closed,0)=0" else ""
        return scalar("SELECT COUNT(*) FROM fiscal_periods WHERE station_id=? AND start_date<=? AND end_date>=?"+status+closed,
            arrayOf(station.toString(),date,date))==1
    }
    private fun sourceDate(table:String,id:Long):String {
        val c=columns(table)
        val col=listOf("transaction_date","payment_date","refill_date","sale_date","entry_date","created_at","date").firstOrNull{c.contains(it)}?:return ""
        val value=scalarS("SELECT COALESCE(CAST("+col+" AS TEXT),'') FROM "+table+" WHERE id=?",arrayOf(id.toString()))
        val date=value.trim().take(10)
        return if(date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) date else ""
    }
    private fun dateOnly(s:String):String {
        val d=s.trim().take(10)
        return if(d.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) d else ""
    }
    private fun audit():JSONObject = try {
        val historical=HistoricalFinancialReconciliationAudit(db).run(station)
        val integrity=FinancialIntegrityAuditor(db).audit(station)
        val histCount=historical.optInt("blocking_issue_count",1)
        val glCount=integrity.optInt("issue_count",1)
        val glOnlyCount=(glCount-histCount).coerceAtLeast(0)
        val ready=historical.optBoolean("ready_for_financial_statement_approval",false) && glOnlyCount==0
        historical.put("financial_integrity_audit",integrity)
            .put("blocking_issue_count",histCount+glOnlyCount)
            .put("ready_for_financial_statement_approval",ready)
            .put("approval_status",if(ready) "ready" else "blocked")
            .put("status",if(ready) "reconciled" else "reconciliation_required")
    } catch(e:Exception){JSONObject().put("status","audit_failed").put("approval_status","blocked")
        .put("ready_for_financial_statement_approval",false).put("blocking_issue_count",1).put("error",(e.message?:e.javaClass.simpleName).take(400))}
    private fun action(code:String,entity:String,id:Long,status:String,why:String,before:JSONObject,after:JSONObject) {
        actions.put(JSONObject().put("action_code",code).put("entity_type",entity).put("entity_id",id).put("status",status)
            .put("explanation",why).put("before",before).put("after",after))
        db.insertOrThrow("financial_reconciliation_actions",null,ContentValues().apply {
            put("run_id",runId);put("station_id",station);put("action_code",code);put("entity_type",entity);put("entity_id",id)
            put("status",status);put("explanation",why);put("before_json",before.toString());put("after_json",after.toString());put("created_at",now())
        })
    }
    private fun skip(code:String,entity:String,id:Long,why:String,details:JSONObject)=action(code,entity,id,"skipped",why,details,JSONObject())
    private fun actionCount(status:String):Int {var n=0;for(i in 0 until actions.length())if(actions.optJSONObject(i)?.optString("status")==status)n++;return n}
    private fun tableExists(t:String)=scalar("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",arrayOf(t))>0
    private fun columns(t:String):Set<String>{
        if(!t.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))||!tableExists(t))return emptySet()
        val out=linkedSetOf<String>();db.rawQuery("PRAGMA table_info("+t+")",null).use{c->while(c.moveToNext())out.add(c.getString(1))};return out
    }
    private fun hasColumn(t:String,c:String)=columns(t).contains(c)
    private fun scalar(sql:String,args:Array<String>)=db.rawQuery(sql,args).use{c->if(c.moveToFirst())c.getInt(0)else 0}
    private fun scalarD(sql:String,args:Array<String>)=db.rawQuery(sql,args).use{c->if(c.moveToFirst())c.getDouble(0)else 0.0}
    private fun scalarS(sql:String,args:Array<String>)=db.rawQuery(sql,args).use{c->if(c.moveToFirst())c.getString(0)?: "" else ""}
    private fun uniqueId(sql:String,args:Array<String>):Long {
        db.rawQuery(sql,args).use{c->if(!c.moveToFirst())return 0L;val id=c.getLong(0);if(c.moveToNext())return 0L;return id}
    }
    private fun <T> query(sql:String,args:Array<String>,mapper:(Cursor)->T):List<T>{
        val out=ArrayList<T>();db.rawQuery(sql,args).use{c->while(c.moveToNext())out.add(mapper(c))};return out
    }
    private fun now()=SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(Date())
}