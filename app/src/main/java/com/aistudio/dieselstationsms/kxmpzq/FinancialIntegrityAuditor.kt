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
