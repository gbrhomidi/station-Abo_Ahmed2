package com.aistudio.dieselstationsms.kxmpzq.data

import android.database.sqlite.SQLiteDatabase
import androidx.annotation.WorkerThread
import org.json.JSONObject

class AuditLogDao(private val db: SQLiteDatabase) {
    @WorkerThread fun getPendingAuditLog(limit: Int = 200): List<JSONObject> =
        MovementDaoSupport.list(db, "movement_audit_log", "success=0", emptyArray(), "id ASC", limit)
    @WorkerThread fun byMovement(movementId: Long, limit: Int = 200): List<JSONObject> {
        require(movementId > 0)
        return MovementDaoSupport.list(db, "movement_audit_log", "movement_id=?", arrayOf(movementId.toString()), "id DESC", limit)
    }
    @WorkerThread fun append(data: JSONObject): Long =
        MovementDaoSupport.insert(db, "movement_audit_log", data)
}
