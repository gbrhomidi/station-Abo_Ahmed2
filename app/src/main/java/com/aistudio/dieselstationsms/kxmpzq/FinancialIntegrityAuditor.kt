package com.aistudio.dieselstationsms.kxmpzq

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only integrity diagnostics. Never changes balances or posted journals.
 */
internal class FinancialIntegrityAuditor(private val database: SQLiteDatabase) {
    fun audit(stationId: Int): JSONObject {
        require(stationId > 0) { "A valid station scope is required" }
        val issues = JSONArray()
        val checked = JSONObject()
        val jcols = columns("journal_entries")
        if (!tableExists("journal_entries") ||
            !jcols.containsAll(listOf("id", "station_id", "status", "total_debit", "total_credit"))) {
            issue(issues, "audit_schema_unavailable", "مخطط قيود اليومية غير مكتمل", 1)
            return output(stationId, issues, checked)
        }
        val deleted = if (jcols.contains("is_deleted")) " AND is_deleted=0" else ""
        val posted = "station_id=? AND status='posted'" + deleted
        val args = arrayOf(stationId.toString())
        checked.put("posted_journals", scalar("SELECT COUNT(*) FROM journal_entries WHERE " + posted, args))
        val unbalanced = rows(
            "SELECT id,total_debit,total_credit FROM journal_entries WHERE " + posted +
                " AND ABS(COALESCE(total_debit,0)-COALESCE(total_credit,0))>0.01", args
        ) { c -> JSONObject().put("journal_id", c.getLong(0)).put("debit", c.getDouble(1)).put("credit", c.getDouble(2)) }
        checked.put("unbalanced_posted_journals", unbalanced)
        if (unbalanced.length() > 0) issue(issues, "unbalanced_posted_journals", "قيود يومية مرحلة غير متوازنة", unbalanced.length(), unbalanced)

        if (tableExists("journal_entry_items") &&
            columns("journal_entry_items").containsAll(listOf("journal_entry_id", "debit", "credit"))) {
            val mismatches = rows(
                "SELECT j.id,j.total_debit,j.total_credit,COALESCE(SUM(i.debit),0),COALESCE(SUM(i.credit),0) " +
                    "FROM journal_entries j LEFT JOIN journal_entry_items i ON i.journal_entry_id=j.id " +
                    "WHERE j." + posted + " GROUP BY j.id HAVING " +
                    "ABS(COALESCE(j.total_debit,0)-COALESCE(SUM(i.debit),0))>0.01 OR " +
                    "ABS(COALESCE(j.total_credit,0)-COALESCE(SUM(i.credit),0))>0.01", args
            ) { c -> JSONObject().put("journal_id", c.getLong(0)).put("header_debit", c.getDouble(1))
                .put("header_credit", c.getDouble(2)).put("items_debit", c.getDouble(3)).put("items_credit", c.getDouble(4)) }
            checked.put("journal_item_mismatches", mismatches)
            if (mismatches.length() > 0) issue(issues, "journal_item_totals_mismatch", "بنود القيد لا تطابق إجمالي رأس القيد", mismatches.length(), mismatches)
        } else {
            issue(issues, "journal_items_schema_unavailable", "تعذر مطابقة رؤوس القيود مع البنود", 1)
        }

        if (jcols.containsAll(listOf("reference_type", "reference_id"))) {
            val duplicates = rows(
                "SELECT reference_type,reference_id,COUNT(*) FROM journal_entries WHERE " + posted +
                    " AND reference_type IN ('payment','expense','receipt') AND reference_id IS NOT NULL " +
                    "AND CAST(reference_id AS TEXT)<>'' GROUP BY reference_type,reference_id HAVING COUNT(*)>1", args
            ) { c -> JSONObject().put("reference_type", c.getString(0)).put("reference_id", c.getString(1)).put("posted_count", c.getInt(2)) }
            checked.put("duplicate_posted_source_journals", duplicates)
            if (duplicates.length() > 0) issue(issues, "duplicate_posted_source_journals", "مصدر مالي مرتبط بأكثر من قيد مرحل؛ يلزم التحقيق قبل التعديل", duplicates.length(), duplicates)
        } else {
            issue(issues, "journal_reference_schema_unavailable", "حقول مرجع المصدر غير متاحة لفحص التكرار", 1)
        }
        auditSource("payments", "payment", listOf("completed", "paid"), stationId, issues, checked)
        auditSource("expenses", "expense", listOf("paid"), stationId, issues, checked)
        auditSource("receipts", "receipt", listOf("active", "paid", "completed"), stationId, issues, checked)
        auditCashBoxes(stationId, issues, checked)
        auditBankAccounts(stationId, issues, checked)
        auditReversalLinks(stationId, issues, checked)
        auditLedgerTrialBalance(stationId, issues, checked)
        return output(stationId, issues, checked)
    }

    private fun auditSource(table: String, refType: String, statuses: List<String>, stationId: Int, issues: JSONArray, checked: JSONObject) {
        if (!tableExists(table)) return
        val cols = columns(table)
        if (!cols.containsAll(listOf("id", "station_id", "status"))) return
        val deleted = if (cols.contains("is_deleted")) " AND s.is_deleted=0" else ""
        val statusMarks = statuses.joinToString(",") { "?" }
        val jdeleted = if (columns("journal_entries").contains("is_deleted")) " AND j.is_deleted=0" else ""
        val sql = "SELECT s.id FROM " + table + " s WHERE s.station_id=? AND s.status IN (" + statusMarks + ")" + deleted +
            " AND NOT EXISTS (SELECT 1 FROM journal_entries j WHERE j.station_id=s.station_id AND j.reference_type=? " +
            "AND CAST(j.reference_id AS TEXT)=CAST(s.id AS TEXT) AND j.status='posted'" + jdeleted + ")"
        val args = arrayOf(stationId.toString(), *statuses.toTypedArray(), refType)
        val missing = rows(sql, args) { c -> JSONObject().put("source_id", c.getLong(0)) }
        val key = "missing_" + table + "_journals"
        checked.put(key, missing)
        if (missing.length() > 0) issue(issues, key, "سجلات مصدر فعالة بلا قيد يومية مرحل مطابق", missing.length(), missing)
    }


    /**
     * Reconciles the cached cash-box balance against the opening balance and the
     * immutable, non-deleted movement ledger. Also verifies every movement's
     * before/after chain. This is diagnostic only: it never repairs money values.
     */
    private fun auditCashBoxes(stationId: Int, issues: JSONArray, checked: JSONObject) {
        if (!tableExists("cash_boxes") || !tableExists("cash_movements")) {
            issue(issues, "cash_reconciliation_schema_unavailable", "تعذر مطابقة أرصدة الصناديق مع سجل الحركات", 1)
            return
        }
        val boxColumns = columns("cash_boxes")
        val movementColumns = columns("cash_movements")
        if (!boxColumns.containsAll(listOf("id", "station_id", "opening_balance", "current_balance", "is_deleted")) ||
            !movementColumns.containsAll(listOf("id", "cash_box_id", "movement_type", "amount", "balance_before", "balance_after", "is_deleted"))) {
            issue(issues, "cash_reconciliation_schema_unavailable", "حقول مطابقة الصناديق والحركات غير مكتملة", 1)
            return
        }

        val boxes = rows(
            "SELECT id,COALESCE(opening_balance,0),COALESCE(current_balance,0) FROM cash_boxes WHERE station_id=? AND is_deleted=0 ORDER BY id",
            arrayOf(stationId.toString())
        ) { c -> JSONObject().put("cash_box_id", c.getLong(0)).put("opening_balance", c.getDouble(1)).put("current_balance", c.getDouble(2)) }
        val diagnostics = JSONArray()
        var issueCount = 0
        for (i in 0 until boxes.length()) {
            val box = boxes.getJSONObject(i)
            val boxId = box.getLong("cash_box_id")
            val opening = box.getDouble("opening_balance")
            val current = box.getDouble("current_balance")
            var expected = opening
            var previousAfter = opening
            var movementCount = 0
            var unknownTypes = 0
            var brokenChain = 0
            var inbound = 0.0
            var outbound = 0.0
            val movementDetails = rows(
                "SELECT id,movement_type,COALESCE(amount,0),balance_before,balance_after FROM cash_movements WHERE cash_box_id=? AND is_deleted=0 ORDER BY id",
                arrayOf(boxId.toString())
            ) { c ->
                JSONObject().put("movement_id", c.getLong(0)).put("movement_type", c.getString(1))
                    .put("amount", c.getDouble(2))
                    .put("balance_before", if (c.isNull(3)) JSONObject.NULL else c.getDouble(3))
                    .put("balance_after", if (c.isNull(4)) JSONObject.NULL else c.getDouble(4))
            }
            var lastAfter: Double? = null
            for (m in 0 until movementDetails.length()) {
                val movement = movementDetails.getJSONObject(m)
                val type = movement.optString("movement_type").lowercase(Locale.ROOT)
                val amount = movement.optDouble("amount", Double.NaN)
                val before = if (movement.isNull("balance_before")) Double.NaN else movement.optDouble("balance_before", Double.NaN)
                val after = if (movement.isNull("balance_after")) Double.NaN else movement.optDouble("balance_after", Double.NaN)
                movementCount++
                if (!amount.isFinite() || amount < 0.0 || !before.isFinite() || !after.isFinite()) {
                    brokenChain++
                    continue
                }
                if (kotlin.math.abs(before - previousAfter) > 0.01) brokenChain++
                val delta = when (type) {
                    "in" -> amount.also { inbound += it }
                    "out", "refund" -> (-amount).also { outbound += amount }
                    else -> {
                        unknownTypes++
                        0.0
                    }
                }
                if (type in setOf("in", "out", "refund") && kotlin.math.abs((before + delta) - after) > 0.01) brokenChain++
                if (type in setOf("in", "out", "refund")) expected += delta
                previousAfter = after
                lastAfter = after
            }
            val expectedDelta = current - expected
            val lastDelta = lastAfter?.let { current - it } ?: current - opening
            val mismatch = kotlin.math.abs(expectedDelta) > 0.01 ||
                kotlin.math.abs(lastDelta) > 0.01 || unknownTypes > 0 || brokenChain > 0
            val result = JSONObject().put("cash_box_id", boxId)
                .put("opening_balance", opening).put("current_balance", current)
                .put("expected_balance_from_movements", expected)
                .put("current_vs_expected_delta", expectedDelta)
                .put("current_vs_last_movement_delta", lastDelta)
                .put("inbound_total", inbound).put("outbound_total", outbound)
                .put("movement_count", movementCount).put("unknown_movement_types", unknownTypes)
                .put("broken_movement_chain_count", brokenChain)
                .put("reconciled", !mismatch)
            diagnostics.put(result)
            if (mismatch) {
                issueCount++
                issues.put(JSONObject().put("code", "cash_box_reconciliation_mismatch")
                    .put("message", "رصيد الصندوق لا يطابق الرصيد الافتتاحي والحركات الفعلية")
                    .put("count", 1).put("records", JSONArray().put(result)))
            }
        }
        checked.put("cash_box_reconciliation", diagnostics)
        checked.put("cash_box_count", boxes.length())
        checked.put("cash_box_reconciliation_issue_count", issueCount)
    }


    /**
     * Reconciles station-scoped bank account balances against the bank ledger.
     * The ledger convention used by the writer is debit increases the bank asset
     * and credit decreases it. This is read-only and never repairs balances.
     */
    private fun auditBankAccounts(stationId: Int, issues: JSONArray, checked: JSONObject) {
        if (!tableExists("bank_accounts") || !tableExists("bank_ledger")) {
            issue(issues, "bank_reconciliation_schema_unavailable", "تعذر مطابقة أرصدة الحسابات البنكية مع دفتر البنك", 1)
            return
        }
        val accountColumns = columns("bank_accounts")
        val ledgerColumns = columns("bank_ledger")
        if (!accountColumns.containsAll(listOf("id", "station_id", "opening_balance", "current_balance", "is_deleted")) ||
            !ledgerColumns.containsAll(listOf("id", "bank_account_id", "debit", "credit", "balance"))) {
            issue(issues, "bank_reconciliation_schema_unavailable", "حقول مطابقة الحسابات البنكية ودفتر البنك غير مكتملة", 1)
            return
        }

        val accounts = rows(
            "SELECT id,COALESCE(opening_balance,0),COALESCE(current_balance,0) FROM bank_accounts WHERE station_id=? AND is_deleted=0 ORDER BY id",
            arrayOf(stationId.toString())
        ) { c -> JSONObject().put("bank_account_id", c.getLong(0))
            .put("opening_balance", c.getDouble(1)).put("current_balance", c.getDouble(2)) }
        val diagnostics = JSONArray()
        var issueCount = 0
        for (i in 0 until accounts.length()) {
            val account = accounts.getJSONObject(i)
            val accountId = account.getLong("bank_account_id")
            val opening = account.getDouble("opening_balance")
            val current = account.getDouble("current_balance")
            var expected = opening
            var previousBalance = opening
            var brokenChain = 0
            var invalidAmounts = 0
            var debitTotal = 0.0
            var creditTotal = 0.0
            var lastBalance: Double? = null
            val ledger = rows(
                "SELECT id,COALESCE(debit,0),COALESCE(credit,0),balance FROM bank_ledger WHERE bank_account_id=? ORDER BY id",
                arrayOf(accountId.toString())
            ) { c -> JSONObject().put("ledger_id", c.getLong(0)).put("debit", c.getDouble(1))
                .put("credit", c.getDouble(2)).put("balance", c.getDouble(3)) }
            for (j in 0 until ledger.length()) {
                val row = ledger.getJSONObject(j)
                val debit = row.getDouble("debit")
                val credit = row.getDouble("credit")
                val balance = row.getDouble("balance")
                if (debit < -0.01 || credit < -0.01 || (debit > 0.01 && credit > 0.01)) invalidAmounts++
                val calculated = previousBalance + debit - credit
                if (kotlin.math.abs(calculated - balance) > 0.01) brokenChain++
                debitTotal += debit
                creditTotal += credit
                expected += debit - credit
                previousBalance = balance
                lastBalance = balance
            }
            val expectedDelta = current - expected
            val lastDelta = lastBalance?.let { current - it } ?: current - opening
            val mismatch = kotlin.math.abs(expectedDelta) > 0.01 ||
                kotlin.math.abs(lastDelta) > 0.01 || brokenChain > 0 || invalidAmounts > 0
            val result = JSONObject().put("bank_account_id", accountId)
                .put("opening_balance", opening).put("current_balance", current)
                .put("expected_balance_from_ledger", expected)
                .put("current_vs_expected_delta", expectedDelta)
                .put("current_vs_last_ledger_delta", lastDelta)
                .put("debit_total", debitTotal).put("credit_total", creditTotal)
                .put("ledger_entry_count", ledger.length()).put("broken_ledger_chain_count", brokenChain)
                .put("invalid_ledger_amount_count", invalidAmounts).put("reconciled", !mismatch)
            diagnostics.put(result)
            if (mismatch) {
                issueCount++
                issues.put(JSONObject().put("code", "bank_account_reconciliation_mismatch")
                    .put("message", "رصيد الحساب البنكي لا يطابق الرصيد الافتتاحي ودفتر البنك")
                    .put("count", 1).put("records", JSONArray().put(result)))
            }
        }
        checked.put("bank_account_reconciliation", diagnostics)
        checked.put("bank_account_count", accounts.length())
        checked.put("bank_account_reconciliation_issue_count", issueCount)
    }

    /**
     * Audits both directions of every journal reversal relationship. A valid pair
     * must be in the same station, point to one another, have the expected statuses,
     * and reverse the debit/credit header totals. No journal is modified here.
     */
    private fun auditReversalLinks(stationId: Int, issues: JSONArray, checked: JSONObject) {
        val required = listOf("id", "station_id", "status", "reference_type", "reference_id",
            "reversed_entry_id", "total_debit", "total_credit", "is_deleted")
        if (!tableExists("journal_entries") || !columns("journal_entries").containsAll(required)) {
            issue(issues, "reversal_audit_schema_unavailable", "حقول التحقق من روابط القيود العكسية غير مكتملة", 1)
            return
        }
        val args = arrayOf(stationId.toString())
        val invalidPostedReversals = rows(
            """SELECT rev.id,COALESCE(rev.reference_id,0),COALESCE(rev.reversed_entry_id,0)
               FROM journal_entries rev
               WHERE rev.station_id=? AND rev.status='posted' AND rev.is_deleted=0 AND rev.reference_type='reversal'
                 AND NOT EXISTS (
                   SELECT 1 FROM journal_entries orig
                   WHERE orig.id=rev.reference_id AND orig.station_id=rev.station_id AND orig.is_deleted=0
                     AND orig.status='reversed' AND orig.reversed_entry_id=rev.id
                     AND rev.reversed_entry_id=orig.id
                     AND ABS(COALESCE(rev.total_debit,0)-COALESCE(orig.total_credit,0))<=0.01
                     AND ABS(COALESCE(rev.total_credit,0)-COALESCE(orig.total_debit,0))<=0.01
                 )""",
            args
        ) { c -> JSONObject().put("reversal_journal_id", c.getLong(0)).put("reference_id", c.getLong(1)).put("reversed_entry_id", c.getLong(2)) }
        val invalidReversedOriginals = rows(
            """SELECT orig.id,COALESCE(orig.reversed_entry_id,0)
               FROM journal_entries orig
               WHERE orig.station_id=? AND orig.status='reversed' AND orig.is_deleted=0
                 AND NOT EXISTS (
                   SELECT 1 FROM journal_entries rev
                   WHERE rev.id=orig.reversed_entry_id AND rev.station_id=orig.station_id
                     AND rev.is_deleted=0 AND rev.status='posted' AND rev.reference_type='reversal'
                     AND rev.reference_id=orig.id AND rev.reversed_entry_id=orig.id
                     AND ABS(COALESCE(rev.total_debit,0)-COALESCE(orig.total_credit,0))<=0.01
                     AND ABS(COALESCE(rev.total_credit,0)-COALESCE(orig.total_debit,0))<=0.01
                 )""",
            args
        ) { c -> JSONObject().put("original_journal_id", c.getLong(0)).put("reversed_entry_id", c.getLong(1)) }
        val unexpectedLinks = rows(
            """SELECT entry.id,entry.status,entry.reference_type,COALESCE(entry.reversed_entry_id,0)
               FROM journal_entries entry
               WHERE entry.station_id=? AND entry.is_deleted=0 AND entry.reversed_entry_id IS NOT NULL
                 AND entry.reference_type<>'reversal' AND entry.status<>'reversed'
                 AND NOT EXISTS (
                   SELECT 1 FROM journal_entries rev
                   WHERE rev.id=entry.reversed_entry_id AND rev.station_id=entry.station_id
                     AND rev.is_deleted=0 AND rev.status='posted' AND rev.reference_type='reversal'
                     AND rev.reference_id=entry.id AND rev.reversed_entry_id=entry.id
                 )""",
            args
        ) { c -> JSONObject().put("journal_id", c.getLong(0)).put("status", c.getString(1)).put("reference_type", c.getString(2)).put("reversed_entry_id", c.getLong(3)) }
        val duplicateReversals = rows(
            """SELECT reference_id,COUNT(*) FROM journal_entries
               WHERE station_id=? AND is_deleted=0 AND status='posted' AND reference_type='reversal'
                 AND reference_id IS NOT NULL GROUP BY reference_id HAVING COUNT(*)>1""",
            args
        ) { c -> JSONObject().put("original_journal_id", c.getLong(0)).put("posted_reversal_count", c.getInt(1)) }
        val reversalItemMismatches = JSONArray()
        if (tableExists("journal_entry_items") &&
            columns("journal_entry_items").containsAll(listOf("journal_entry_id", "account_id", "debit", "credit"))) {
            val validPairs = rows(
                """SELECT rev.id,orig.id FROM journal_entries rev
                   JOIN journal_entries orig ON orig.id=rev.reference_id AND orig.station_id=rev.station_id
                   WHERE rev.station_id=? AND rev.status='posted' AND rev.is_deleted=0
                     AND rev.reference_type='reversal' AND orig.status='reversed' AND orig.is_deleted=0
                     AND orig.reversed_entry_id=rev.id AND rev.reversed_entry_id=orig.id""",
                args
            ) { c -> JSONObject().put("reversal_id", c.getLong(0)).put("original_id", c.getLong(1)) }
            for (i in 0 until validPairs.length()) {
                val pair = validPairs.getJSONObject(i)
                val reversalId = pair.getLong("reversal_id")
                val originalId = pair.getLong("original_id")
                fun lineTotals(journalId: Long): Map<Long, Pair<Double, Double>> {
                    val totals = linkedMapOf<Long, Pair<Double, Double>>()
                    rows(
                        """SELECT account_id,COALESCE(SUM(debit),0),COALESCE(SUM(credit),0)
                           FROM journal_entry_items WHERE journal_entry_id=? GROUP BY account_id""",
                        arrayOf(journalId.toString())
                    ) { c ->
                        totals[c.getLong(0)] = c.getDouble(1) to c.getDouble(2)
                        JSONObject()
                    }
                    return totals
                }
                val originalLines = lineTotals(originalId)
                val reversalLines = lineTotals(reversalId)
                for (accountId in (originalLines.keys + reversalLines.keys).toSet()) {
                    val originalLine = originalLines[accountId] ?: (0.0 to 0.0)
                    val reversalLine = reversalLines[accountId] ?: (0.0 to 0.0)
                    if (kotlin.math.abs(reversalLine.first - originalLine.second) > 0.01 ||
                        kotlin.math.abs(reversalLine.second - originalLine.first) > 0.01) {
                        reversalItemMismatches.put(JSONObject().put("original_journal_id", originalId)
                            .put("reversal_journal_id", reversalId).put("account_id", accountId)
                            .put("original_debit", originalLine.first).put("original_credit", originalLine.second)
                            .put("reversal_debit", reversalLine.first).put("reversal_credit", reversalLine.second))
                    }
                }
            }
        } else {
            issue(issues, "reversal_items_schema_unavailable", "تعذر التحقق من انعكاس بنود الحسابات في القيد العكسي", 1)
        }
        checked.put("invalid_posted_reversal_links", invalidPostedReversals)
        checked.put("reversed_originals_without_valid_reversal", invalidReversedOriginals)
        checked.put("unexpected_reversed_entry_links", unexpectedLinks)
        checked.put("duplicate_posted_reversals", duplicateReversals)
        checked.put("reversal_item_mismatches", reversalItemMismatches)
        val count = invalidPostedReversals.length() + invalidReversedOriginals.length() +
            unexpectedLinks.length() + duplicateReversals.length() + reversalItemMismatches.length()
        checked.put("invalid_reversal_link_count", count)
        if (count > 0) {
            val records = JSONArray()
            for (array in listOf(invalidPostedReversals, invalidReversedOriginals, unexpectedLinks, duplicateReversals, reversalItemMismatches)) {
                for (i in 0 until array.length()) records.put(array.getJSONObject(i))
            }
            issue(issues, "invalid_reversal_links", "روابط القيود العكسية أو بنودها غير متبادلة أو غير متسقة أو مكررة", count, records)
        }
    }


    /**
     * Reconciles the posted general-ledger lines to trial-balance totals for one
     * station. Reversed originals are excluded by status; their posted reversal
     * is included once, matching the accounting reports' posted-only contract.
     */
    private fun auditLedgerTrialBalance(stationId: Int, issues: JSONArray, checked: JSONObject) {
        if (!tableExists("journal_entry_items")) {
            issue(issues, "trial_balance_schema_unavailable", "تعذر مطابقة دفتر الأستاذ مع ميزان المراجعة", 1)
            return
        }
        val itemColumns = columns("journal_entry_items")
        if (!itemColumns.containsAll(listOf("journal_entry_id", "account_id", "debit", "credit"))) {
            issue(issues, "trial_balance_schema_unavailable", "حقول بنود دفتر الأستاذ غير مكتملة", 1)
            return
        }
        val postedFilter = "je.station_id=? AND je.status='posted'" +
            (if (columns("journal_entries").contains("is_deleted")) " AND je.is_deleted=0" else "")
        val args = arrayOf(stationId.toString())
        val totals = rows(
            """SELECT COUNT(DISTINCT je.id),COALESCE(SUM(COALESCE(ji.debit,0)),0),
                      COALESCE(SUM(COALESCE(ji.credit,0)),0)
               FROM journal_entries je
               LEFT JOIN journal_entry_items ji ON ji.journal_entry_id=je.id
               WHERE $postedFilter""",
            args
        ) { c -> JSONObject().put("posted_journal_count", c.getInt(0))
            .put("ledger_debit_total", c.getDouble(1)).put("ledger_credit_total", c.getDouble(2)) }
        val summary = totals.optJSONObject(0) ?: JSONObject()
        val debit = summary.optDouble("ledger_debit_total", 0.0)
        val credit = summary.optDouble("ledger_credit_total", 0.0)
        val delta = debit - credit
        val byAccount = rows(
            """SELECT ji.account_id,COALESCE(SUM(ji.debit),0),COALESCE(SUM(ji.credit),0),
                      COALESCE(SUM(ji.debit-ji.credit),0),COUNT(DISTINCT je.id)
               FROM journal_entry_items ji
               JOIN journal_entries je ON je.id=ji.journal_entry_id
               WHERE $postedFilter
               GROUP BY ji.account_id ORDER BY ji.account_id""",
            args
        ) { c -> JSONObject().put("account_id", c.getLong(0))
            .put("debit_total", c.getDouble(1)).put("credit_total", c.getDouble(2))
            .put("net_debit_balance", c.getDouble(3)).put("journal_count", c.getInt(4)) }
        val unbalancedPosted = rows(
            """SELECT je.id,COALESCE(SUM(ji.debit),0),COALESCE(SUM(ji.credit),0)
               FROM journal_entries je
               LEFT JOIN journal_entry_items ji ON ji.journal_entry_id=je.id
               WHERE $postedFilter GROUP BY je.id
               HAVING ABS(COALESCE(SUM(ji.debit),0)-COALESCE(SUM(ji.credit),0))>0.01""",
            args
        ) { c -> JSONObject().put("journal_id", c.getLong(0))
            .put("ledger_debit", c.getDouble(1)).put("ledger_credit", c.getDouble(2)) }
        checked.put("ledger_trial_balance", JSONObject()
            .put("station_id", stationId)
            .put("posted_journal_count", summary.optInt("posted_journal_count", 0))
            .put("ledger_debit_total", debit).put("ledger_credit_total", credit)
            .put("debit_credit_delta", delta)
            .put("balanced", kotlin.math.abs(delta) <= 0.01 && unbalancedPosted.length() == 0)
            .put("account_balances", byAccount)
            .put("unbalanced_posted_journals_by_lines", unbalancedPosted))
        if (kotlin.math.abs(delta) > 0.01 || unbalancedPosted.length() > 0) {
            val records = JSONArray().put(JSONObject().put("station_id", stationId)
                .put("ledger_debit_total", debit).put("ledger_credit_total", credit)
                .put("debit_credit_delta", delta).put("unbalanced_journal_count", unbalancedPosted.length()))
            issue(issues, "ledger_trial_balance_mismatch",
                "إجماليات دفتر الأستاذ لا تتطابق مع ميزان مراجعة متوازن للقيود المرحلة", 1, records)
        }
    }

    private fun output(stationId: Int, issues: JSONArray, checked: JSONObject) = JSONObject()
        .put("status", if (issues.length() == 0) "no_detected_issues" else "issues_found")
        .put("station_id", stationId)
        .put("checked_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
        .put("checked", checked).put("issue_count", issues.length()).put("issues", issues)
        .put("automatic_repairs", false)

    private fun issue(target: JSONArray, code: String, message: String, count: Int, records: JSONArray? = null) {
        val item = JSONObject().put("code", code).put("message", message).put("count", count)
        if (records != null) item.put("records", records)
        target.put(item)
    }
    private fun tableExists(table: String): Boolean =
        scalar("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)) > 0
    private fun columns(table: String): Set<String> {
        if (!table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) || !tableExists(table)) return emptySet()
        val result = linkedSetOf<String>()
        database.rawQuery("PRAGMA table_info(" + table + ")", null).use { c -> while (c.moveToNext()) result.add(c.getString(1)) }
        return result
    }
    private fun scalar(sql: String, args: Array<String>): Int {
        database.rawQuery(sql, args).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }
    private fun rows(sql: String, args: Array<String>, mapper: (Cursor) -> JSONObject): JSONArray {
        val result = JSONArray()
        database.rawQuery(sql, args).use { c -> while (c.moveToNext()) result.put(mapper(c)) }
        return result
    }
}
