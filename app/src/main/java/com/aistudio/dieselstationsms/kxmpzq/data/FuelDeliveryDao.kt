package com.aistudio.dieselstationsms.kxmpzq.data

import android.database.sqlite.SQLiteDatabase
import androidx.annotation.WorkerThread
import org.json.JSONObject
import java.util.UUID

class FuelDeliveryDao(private val db: SQLiteDatabase) {
    @WorkerThread fun create(data: JSONObject): Long {
        require(data.optLong("station_id") > 0 && data.optLong("tank_id") > 0)
        require(data.optDouble("quantity_liters") > 0 && data.optString("delivered_at").isNotBlank())
        val row = JSONObject(data.toString()).put("uuid", data.optString("uuid").ifBlank { UUID.randomUUID().toString() })
        return MovementDaoSupport.insert(db, "fuel_deliveries", row)
    }
    @WorkerThread fun update(id: Long, data: JSONObject): Int =
        MovementDaoSupport.update(db, "fuel_deliveries", "id", id, data)
    @WorkerThread fun get(id: Long): JSONObject? = MovementDaoSupport.get(db, "fuel_deliveries", "id", id)
    @WorkerThread fun byStation(stationId: Long, limit: Int = 200): List<JSONObject> {
        require(stationId > 0)
        return MovementDaoSupport.list(db, "fuel_deliveries", "station_id=?", arrayOf(stationId.toString()), "delivered_at DESC,id DESC", limit)
    }
    @WorkerThread fun delete(id: Long): Int = db.delete("fuel_deliveries", "id=?", arrayOf(id.toString()))
}
