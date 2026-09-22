package com.aistudio.dieselstationsms.kxmpzq

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * نموذج داخلي مشترك للاستعلامات المرقمة.
 *
 * لا يستقبل هذا النموذج اسم جدول أو عمود أو JOIN من JavaScript أو من مدخلات
 * المستخدم. البنية والإسقاط والترتيب معرفة مسبقاً داخل [QueryTarget]، بينما
 * selectionArgs وحدها تحمل القيم الديناميكية إلى SQLite كـ bind arguments.
 */
internal object QueryUtils {

    internal enum class QueryTarget(
        val fromSql: String,
        val projectionSql: String,
        private val orderColumns: Map<String, String>,
        val defaultOrderKey: String
    ) {
        SALES_TRANSACTIONS(
            fromSql = "sales_transactions s LEFT JOIN parties p ON p.id = s.customer_party_id",
            projectionSql = """
                s.id AS sale_id, s.sale_code, s.invoice_number, s.station_id,
                s.shift_id, s.customer_party_id,
                COALESCE(p.commercial_name, p.commercial_name_ar, '') AS customer_name,
                s.subtotal, s.discount_amount, s.tax_amount, s.gross_amount,
                s.net_amount, s.payment_method, s.payment_status,
                s.paid_amount, s.remaining_amount, s.status,
                s.order_type, s.created_at
            """.trimIndent(),
            orderColumns = mapOf(
                "invoice_number" to "s.invoice_number",
                "net_amount" to "s.net_amount",
                "payment_method" to "s.payment_method",
                "created_at" to "s.created_at",
                "id" to "s.id"
            ),
            defaultOrderKey = "id"
        );

        fun orderExpression(key: String): String =
            orderColumns[key] ?: orderColumns.getValue(defaultOrderKey)
    }

    internal data class PageRequest(
        val target: QueryTarget,
        val selection: String,
        val selectionArgs: List<String>,
        val sortKey: String = target.defaultOrderKey,
        val ascending: Boolean = false,
        val limit: Int = 50,
        val offset: Int = 0
    ) {
        val safeLimit: Int get() = limit.coerceIn(1, 1000)
        val safeOffset: Int get() = offset.coerceAtLeast(0)
    }

    internal fun executePage(db: SQLiteDatabase, request: PageRequest): JSONArray {
        val whereClause = request.selection.trim().takeIf { it.isNotEmpty() }?.let { " WHERE $it" } ?: ""
        val direction = if (request.ascending) "ASC" else "DESC"
        val sql = "SELECT ${request.target.projectionSql} FROM ${request.target.fromSql}$whereClause " +
            "ORDER BY ${request.target.orderExpression(request.sortKey)} $direction LIMIT ? OFFSET ?"
        val args = request.selectionArgs.toMutableList().apply {
            add(request.safeLimit.toString())
            add(request.safeOffset.toString())
        }
        return db.rawQuery(sql, args.toTypedArray()).use(::cursorToJsonArray)
    }

    internal fun count(db: SQLiteDatabase, request: PageRequest): Int {
        val whereClause = request.selection.trim().takeIf { it.isNotEmpty() }?.let { " WHERE $it" } ?: ""
        val sql = "SELECT COUNT(*) FROM ${request.target.fromSql}$whereClause"
        return db.rawQuery(sql, request.selectionArgs.toTypedArray()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun cursorToJsonArray(cursor: Cursor): JSONArray {
        val result = JSONArray()
        while (cursor.moveToNext()) {
            val row = JSONObject()
            for (index in 0 until cursor.columnCount) {
                when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_INTEGER -> row.put(cursor.getColumnName(index), cursor.getLong(index))
                    Cursor.FIELD_TYPE_FLOAT -> row.put(cursor.getColumnName(index), cursor.getDouble(index))
                    Cursor.FIELD_TYPE_STRING -> row.put(cursor.getColumnName(index), cursor.getString(index))
                    Cursor.FIELD_TYPE_NULL -> row.put(cursor.getColumnName(index), JSONObject.NULL)
                }
            }
            result.put(row)
        }
        return result
    }
}
