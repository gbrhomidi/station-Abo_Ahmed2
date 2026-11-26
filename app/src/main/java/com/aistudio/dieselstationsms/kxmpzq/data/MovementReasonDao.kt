package com.aistudio.dieselstationsms.kxmpzq.data

import android.database.sqlite.SQLiteDatabase
import androidx.annotation.WorkerThread
import org.json.JSONObject

class MovementReasonDao(private val db: SQLiteDatabase) {
    @WorkerThread fun getByCode(code: String): JSONObject? {
        require(code.isNotBlank())
        return db.query("movement_reasons", null, "code=? AND is_active=1", arrayOf(code), null, null, null, "1").use {
            if (it.moveToFirst()) MovementDaoSupport.row(it) else null
        }
    }
    @WorkerThread fun listActive(appliesTo: String = "BOTH"): List<JSONObject> {
        require(appliesTo in setOf("PRODUCT","FUEL","BOTH"))
        return MovementDaoSupport.list(db, "movement_reasons",
            "is_active=1 AND (applies_to=? OR applies_to='BOTH')", arrayOf(appliesTo), "label_ar COLLATE NOCASE")
    }
    @WorkerThread fun create(code: String, labelAr: String, labelEn: String?, appliesTo: String, requiresNote: Boolean): Long {
        require(code.matches(Regex("[A-Z0-9_]{2,40}")) && labelAr.isNotBlank())
        require(appliesTo in setOf("PRODUCT","FUEL","BOTH"))
        return db.insertOrThrow("movement_reasons", null, android.content.ContentValues().apply {
            put("code", code); put("label_ar", labelAr); put("label_en", labelEn)
            put("applies_to", appliesTo); put("requires_note", if (requiresNote) 1 else 0)
        })
    }
    @WorkerThread fun deactivate(id: Long): Int = db.update("movement_reasons", android.content.ContentValues().apply { put("is_active", 0) }, "id=?", arrayOf(id.toString()))
}
