package com.aistudio.dieselstationsms.kxmpzq

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Central append-only movement audit writer. Normal movement mutations are also
 * captured by SQLite triggers so non-bridge writers cannot bypass the journal.
 */
object AuditLogger {
    fun log(
        db: SQLiteDatabase,
        movementId: Long?,
        sourceTable: String,
        action: String,
        userId: Long?,
        reasonCodeId: Long?,
        beforeJson: String?,
        afterJson: String?,
        success: Boolean = true,
        failureReason: String? = null,
        stationId: Long? = null
    ): Long {
        require(sourceTable.isNotBlank() && action.isNotBlank())
        return db.insertOrThrow("movement_audit_log", null, ContentValues().apply {
            put("movement_id", movementId ?: 0L)
            if (stationId != null && stationId > 0) put("station_id", stationId) else putNull("station_id")
            put("source_table", sourceTable)
            put("action", action)
            if (userId != null && userId > 0) put("user_id", userId) else putNull("user_id")
            if (reasonCodeId != null && reasonCodeId > 0) put("reason_code_id", reasonCodeId) else putNull("reason_code_id")
            put("before_json", beforeJson)
            put("after_json", afterJson)
            put("success", if (success) 1 else 0)
            put("failure_reason", failureReason)
        })
    }

    fun recordFailedAttempt(db: SQLiteDatabase, movementId: Long, userId: Long, action: String, error: String) {
        // Failure evidence must survive the transaction that rejected the attempted mutation.
        log(db, movementId, "inventory_movements", "failed_$action", userId, null, null, null, false, error.take(1000), db.rawQuery("SELECT station_id FROM inventory_movements WHERE id=?", arrayOf(movementId.toString())).use { c -> if (c.moveToFirst()) c.getLong(0) else null })
    }

    fun page(db: SQLiteDatabase, stationId: Long, limit: Int = 100, offset: Int = 0): JSONArray {
        require(stationId > 0 && limit in 1..500 && offset >= 0)
        val result = JSONArray()
        db.rawQuery(
            "SELECT * FROM movement_audit_log WHERE station_id=? ORDER BY id DESC LIMIT ? OFFSET ?",
            arrayOf(stationId.toString(), limit.toString(), offset.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val row = JSONObject()
                for (i in 0 until c.columnCount) {
                    when (c.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> row.put(c.getColumnName(i), JSONObject.NULL)
                        android.database.Cursor.FIELD_TYPE_INTEGER -> row.put(c.getColumnName(i), c.getLong(i))
                        android.database.Cursor.FIELD_TYPE_FLOAT -> row.put(c.getColumnName(i), c.getDouble(i))
                        else -> row.put(c.getColumnName(i), c.getString(i))
                    }
                }
                result.put(row)
            }
        }
        return result
    }
}
