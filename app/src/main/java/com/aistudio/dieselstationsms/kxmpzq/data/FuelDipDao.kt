package com.aistudio.dieselstationsms.kxmpzq.data

import android.database.sqlite.SQLiteDatabase
import androidx.annotation.WorkerThread
import org.json.JSONObject
import java.util.UUID

class FuelDipDao(private val db: SQLiteDatabase) {
    @WorkerThread fun create(data: JSONObject): Long {
        require(data.optLong("station_id") > 0 && data.optLong("tank_id") > 0)
        require(data.optString("dip_at").isNotBlank())
        require(data.optDouble("dip_cm") >= 0 && data.optDouble("dip_liters") >= 0)
        val row = JSONObject(data.toString()).put("uuid", data.optString("uuid").ifBlank { UUID.randomUUID().toString() })
        return MovementDaoSupport.insert(db, "fuel_dips", row)
    }
    @WorkerThread fun update(id: Long, data: JSONObject): Int =
        MovementDaoSupport.update(db, "fuel_dips", "id", id, data)
    @WorkerThread fun get(id: Long): JSONObject? = MovementDaoSupport.get(db, "fuel_dips", "id", id)
    @WorkerThread fun byShift(shiftId: Long): List<JSONObject> {
        require(shiftId > 0)
        return MovementDaoSupport.list(db, "fuel_dips", "shift_id=?", arrayOf(shiftId.toString()), "dip_at DESC")
    }
    @WorkerThread fun delete(id: Long): Int = db.delete("fuel_dips", "id=?", arrayOf(id.toString()))
}
