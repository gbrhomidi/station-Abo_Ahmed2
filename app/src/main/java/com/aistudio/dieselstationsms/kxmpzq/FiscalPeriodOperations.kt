package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/**
 * Transactional fiscal-period lifecycle operations.
 *
 * Kept separate from DatabaseHelper's large legacy implementation while using its
 * SQLite database and the fiscal-period schema installed by ensureFiscalPeriodSchema.
 */
fun DatabaseHelper.closeFiscalPeriod(
    stationId: Int,
    year: Int,
    month: Int,
    userId: Long,
    reason: String
): Long {
    require(stationId > 0) { "نطاق المحطة غير صالح" }
    require(userId > 0) { "المستخدم المنفذ غير صالح" }
    require(reason.isNotBlank()) { "سبب الإقفال مطلوب" }
    require(year in 2000..2200) { "السنة المالية غير صالحة" }
    require(month in 1..12) { "الشهر المالي غير صالح" }

    val start = LocalDate.of(year, month, 1)
    val end = start.withDayOfMonth(start.lengthOfMonth())
    val db = writableDatabase
    db.beginTransaction()
    try {
        var periodId = -1L
        var previousStatus: String? = null
        db.rawQuery(
            "SELECT id, status FROM fiscal_periods WHERE station_id=? AND fiscal_year=? AND fiscal_month=?",
            arrayOf(stationId.toString(), year.toString(), month.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                periodId = cursor.getLong(0)
                previousStatus = cursor.getString(1)
            }
        }
        require(previousStatus !in setOf("closing", "closed")) {
            "الفترة مغلقة بالفعل أو يجري إقفالها"
        }

        val oldRow = if (periodId > 0) {
            JSONObject()
                .put("id", periodId)
                .put("station_id", stationId)
                .put("fiscal_year", year)
                .put("fiscal_month", month)
                .put("status", previousStatus)
        } else null

        if (periodId < 0) {
            periodId = db.insertOrThrow(
                "fiscal_periods",
                null,
                ContentValues().apply {
                    put("station_id", stationId)
                    put("fiscal_year", year)
                    put("fiscal_month", month)
                    put("period_start", start.toString())
                    put("period_end", end.toString())
                    put("status", "open")
                }
            )
        }

        val draftCount = db.rawQuery(
            """SELECT COUNT(*) FROM journal_entries
               WHERE station_id=? AND COALESCE(is_deleted,0)=0 AND status='draft'
                 AND date(entry_date) BETWEEN date(?) AND date(?)""".trimIndent(),
            arrayOf(stationId.toString(), start.toString(), end.toString())
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        require(draftCount == 0) {
            "لا يمكن إقفال الفترة مع وجود قيود مسودة غير محسومة: $draftCount"
        }

        val invalidPostedCount = db.rawQuery(
            """SELECT COUNT(*) FROM journal_entries
               WHERE station_id=? AND COALESCE(is_deleted,0)=0 AND status='posted'
                 AND date(entry_date) BETWEEN date(?) AND date(?)
                 AND (ABS(COALESCE(total_debit,0)-COALESCE(total_credit,0)) > 0.01
                      OR COALESCE(is_balanced,1) <> 1)""".trimIndent(),
            arrayOf(stationId.toString(), start.toString(), end.toString())
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        require(invalidPostedCount == 0) {
            "تعذر الإقفال: توجد قيود مرحلة غير متوازنة أو غير معلمة كمتوازنة: $invalidPostedCount"
        }

        val changed = db.update(
            "fiscal_periods",
            ContentValues().apply {
                put("status", "closing")
                putNull("closed_at")
                putNull("closed_by")
            },
            "id=? AND station_id=? AND status IN ('open','reopened')",
            arrayOf(periodId.toString(), stationId.toString())
        )
        check(changed == 1) { "تعذر نقل الفترة إلى حالة الإقفال" }

        db.execSQL(
            """INSERT OR IGNORE INTO period_closing_entries(period_id, journal_entry_id)
               SELECT ?, id FROM journal_entries
               WHERE station_id=? AND COALESCE(is_deleted,0)=0 AND status='posted'
                 AND date(entry_date) BETWEEN date(?) AND date(?)""".trimIndent(),
            arrayOf(periodId, stationId, start.toString(), end.toString())
        )

        val now = java.time.LocalDateTime.now().toString()
        val closed = db.update(
            "fiscal_periods",
            ContentValues().apply {
                put("status", "closed")
                put("closed_at", now)
                put("closed_by", userId)
            },
            "id=? AND station_id=? AND status='closing'",
            arrayOf(periodId.toString(), stationId.toString())
        )
        check(closed == 1) { "تعذر إتمام إقفال الفترة" }

        val newRow = JSONObject()
            .put("id", periodId)
            .put("station_id", stationId)
            .put("fiscal_year", year)
            .put("fiscal_month", month)
            .put("period_start", start.toString())
            .put("period_end", end.toString())
            .put("status", "closed")
            .put("closed_by", userId)
            .put("reason", reason.trim())
        insertFiscalPeriodAudit(db, userId, "close", periodId, oldRow, newRow)
        db.setTransactionSuccessful()
        return periodId
    } finally {
        db.endTransaction()
    }
}

fun DatabaseHelper.reopenFiscalPeriod(
    periodId: Long,
    stationId: Int,
    userId: Long,
    reason: String
): Boolean {
    require(periodId > 0) { "معرّف الفترة غير صالح" }
    require(stationId > 0) { "نطاق المحطة غير صالح" }
    require(userId > 0) { "المستخدم المنفذ غير صالح" }
    require(reason.isNotBlank()) { "سبب إعادة الفتح مطلوب" }

    val db = writableDatabase
    db.beginTransaction()
    try {
        val oldRow = db.rawQuery(
            "SELECT * FROM fiscal_periods WHERE id=? AND station_id=?",
            arrayOf(periodId.toString(), stationId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else {
                val row = JSONObject()
                for (i in 0 until cursor.columnCount) {
                    when (cursor.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> row.put(cursor.getColumnName(i), JSONObject.NULL)
                        android.database.Cursor.FIELD_TYPE_INTEGER -> row.put(cursor.getColumnName(i), cursor.getLong(i))
                        android.database.Cursor.FIELD_TYPE_FLOAT -> row.put(cursor.getColumnName(i), cursor.getDouble(i))
                        else -> row.put(cursor.getColumnName(i), cursor.getString(i))
                    }
                }
                row
            }
        } ?: throw IllegalArgumentException("الفترة غير موجودة ضمن نطاق المحطة المحددة")
        require(oldRow.optString("status") == "closed") {
            "لا يمكن إعادة فتح فترة ليست مغلقة"
        }

        val now = java.time.LocalDateTime.now().toString()
        val changed = db.update(
            "fiscal_periods",
            ContentValues().apply {
                put("status", "reopened")
                put("reopened_at", now)
                put("reopened_by", userId)
                put("reopen_reason", reason.trim())
            },
            "id=? AND station_id=? AND status='closed'",
            arrayOf(periodId.toString(), stationId.toString())
        )
        check(changed == 1) { "تغيرت حالة الفترة أثناء إعادة الفتح" }

        val newRow = JSONObject(oldRow.toString())
            .put("status", "reopened")
            .put("reopened_at", now)
            .put("reopened_by", userId)
            .put("reopen_reason", reason.trim())
        insertFiscalPeriodAudit(db, userId, "reopen", periodId, oldRow, newRow)
        db.setTransactionSuccessful()
        return true
    } finally {
        db.endTransaction()
    }
}

fun DatabaseHelper.isDateInClosedPeriod(stationId: Int, date: String): Boolean {
    require(stationId > 0) { "نطاق المحطة غير صالح" }
    val parsedDate = try {
        LocalDate.parse(date.take(10)).toString()
    } catch (_: Exception) {
        throw IllegalArgumentException("التاريخ غير صالح؛ استخدم YYYY-MM-DD")
    }
    return readableDatabase.rawQuery(
        """SELECT 1 FROM fiscal_periods
           WHERE station_id=? AND date(?) BETWEEN date(period_start) AND date(period_end)
             AND status IN ('closing','closed') LIMIT 1""".trimIndent(),
        arrayOf(stationId.toString(), parsedDate)
    ).use { it.moveToFirst() }
}

fun DatabaseHelper.getFiscalPeriods(stationId: Int, year: Int): JSONArray {
    require(stationId > 0) { "نطاق المحطة غير صالح" }
    require(year in 2000..2200) { "السنة المالية غير صالحة" }
    val result = JSONArray()
    readableDatabase.rawQuery(
        """SELECT p.*,
             (SELECT COUNT(*) FROM audit_logs a
              WHERE a.table_name='fiscal_periods' AND a.record_id=p.id) AS audit_count
           FROM fiscal_periods p
           WHERE p.station_id=? AND p.fiscal_year=?
           ORDER BY p.fiscal_month""".trimIndent(),
        arrayOf(stationId.toString(), year.toString())
    ).use { cursor ->
        while (cursor.moveToNext()) {
            val row = JSONObject()
            for (i in 0 until cursor.columnCount) {
                val key = cursor.getColumnName(i)
                when (cursor.getType(i)) {
                    android.database.Cursor.FIELD_TYPE_NULL -> row.put(key, JSONObject.NULL)
                    android.database.Cursor.FIELD_TYPE_INTEGER -> row.put(key, cursor.getLong(i))
                    android.database.Cursor.FIELD_TYPE_FLOAT -> row.put(key, cursor.getDouble(i))
                    else -> row.put(key, cursor.getString(i))
                }
            }
            result.put(row)
        }
    }
    return result
}

private fun insertFiscalPeriodAudit(
    db: SQLiteDatabase,
    userId: Long,
    action: String,
    periodId: Long,
    oldRow: JSONObject?,
    newRow: JSONObject
) {
    db.insertOrThrow(
        "audit_logs",
        null,
        ContentValues().apply {
            put("uuid", UUID.randomUUID().toString())
            put("user_id", userId)
            put("action_type", action)
            put("table_name", "fiscal_periods")
            put("record_id", periodId)
            put("old_row_json", oldRow?.toString())
            put("new_row_json", newRow.toString())
            put("changed_columns", if (action == "close") "status,closed_at,closed_by" else "status,reopened_at,reopened_by,reopen_reason")
        }
    )
}
