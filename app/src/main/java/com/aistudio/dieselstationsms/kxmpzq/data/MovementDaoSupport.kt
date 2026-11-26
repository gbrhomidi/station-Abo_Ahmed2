package com.aistudio.dieselstationsms.kxmpzq.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.annotation.WorkerThread
import org.json.JSONObject

internal object MovementDaoSupport {
    fun values(json: JSONObject): ContentValues = ContentValues().apply {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) continue
            val value = json.opt(key)
            when {
                value == null || value === JSONObject.NULL -> putNull(key)
                value is Boolean -> put(key, if (value) 1 else 0)
                value is Int -> put(key, value)
                value is Long -> put(key, value)
                value is Double -> put(key, value)
                value is Float -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }

    fun row(cursor: Cursor): JSONObject = JSONObject().apply {
        for (i in 0 until cursor.columnCount) {
            val name = cursor.getColumnName(i)
            when (cursor.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> put(name, JSONObject.NULL)
                Cursor.FIELD_TYPE_INTEGER -> put(name, cursor.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> put(name, cursor.getDouble(i))
                else -> put(name, cursor.getString(i))
            }
        }
    }

    @WorkerThread
    fun insert(db: SQLiteDatabase, table: String, data: JSONObject): Long =
        db.insertOrThrow(table, null, values(data))

    @WorkerThread
    fun update(db: SQLiteDatabase, table: String, idColumn: String, id: Long, data: JSONObject): Int {
        require(id > 0) { "Invalid record id" }
        val safe = values(data).apply { remove(idColumn) }
        require(safe.size() > 0) { "No mutable fields supplied" }
        return db.update(table, safe, "$idColumn=?", arrayOf(id.toString()))
    }

    @WorkerThread
    fun get(db: SQLiteDatabase, table: String, idColumn: String, id: Long): JSONObject? =
        db.query(table, null, "$idColumn=?", arrayOf(id.toString()), null, null, null).use {
            if (it.moveToFirst()) row(it) else null
        }

    @WorkerThread
    fun list(db: SQLiteDatabase, table: String, where: String, args: Array<String>, order: String, limit: Int = 200): List<JSONObject> {
        require(limit in 1..1000)
        return db.query(table, null, where, args, null, null, order, limit.toString()).use { c ->
            buildList { while (c.moveToNext()) add(row(c)) }
        }
    }
}
