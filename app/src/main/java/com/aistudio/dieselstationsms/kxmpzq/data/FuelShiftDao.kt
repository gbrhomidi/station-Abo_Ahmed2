package com.aistudio.dieselstationsms.kxmpzq.data

import android.database.sqlite.SQLiteDatabase
import androidx.annotation.WorkerThread
import org.json.JSONObject
import java.util.UUID

class FuelShiftDao(private val db: SQLiteDatabase) {
    @WorkerThread fun create(stationId: Long, shiftCode: String, openedAt: String, userId: Long): Long {
        require(stationId > 0 && shiftCode.isNotBlank() && openedAt.isNotBlank() && userId > 0)
        return MovementDaoSupport.insert(db, "fuel_shifts", JSONObject().apply {
            put("uuid", UUID.randomUUID().toString()); put("station_id", stationId)
            put("shift_code", shiftCode.trim()); put("opened_at", openedAt); put("opened_by", userId)
        })
    }
    @WorkerThread fun close(shiftId: Long, closedAt: String, userId: Long): Int {
        require(shiftId > 0 && closedAt.isNotBlank() && userId > 0)
        return db.update("fuel_shifts", android.content.ContentValues().apply {
            put("closed_at", closedAt); put("closed_by", userId); put("status", "closed")
        }, "id=? AND status='open'", arrayOf(shiftId.toString()))
    }
    @WorkerThread fun getOpenShift(stationId: Long): JSONObject? {
        require(stationId > 0)
        return db.query("fuel_shifts", null, "station_id=? AND status='open'",
            arrayOf(stationId.toString()), null, null, "opened_at DESC", "1").use {
            if (it.moveToFirst()) MovementDaoSupport.row(it) else null
        }
    }
    @WorkerThread fun get(shiftId: Long): JSONObject? = MovementDaoSupport.get(db, "fuel_shifts", "id", shiftId)
    @WorkerThread fun recordReading(shiftId: Long, tankId: Long, type: String, at: String, liters: Double, userId: Long, temperatureC: Double? = null, correctedLiters: Double? = null, waterLevelMm: Double? = null): Long {
        require(shiftId > 0 && tankId > 0 && userId > 0 && type in setOf("opening","closing") && at.isNotBlank() && liters >= 0)
        val values = android.content.ContentValues().apply {
            put("shift_id", shiftId); put("tank_id", tankId); put("reading_type", type)
            put("reading_at", at); put("volume_liters", liters); put("recorded_by", userId)
            if (temperatureC != null) put("temperature_c", temperatureC)
            if (correctedLiters != null) put("temperature_corrected_volume", correctedLiters)
            if (waterLevelMm != null) put("water_level_mm", waterLevelMm)
        }
        return db.insertOrThrow("fuel_shift_readings", null, values)
    }
    @WorkerThread fun deleteCancelled(shiftId: Long): Int =
        db.delete("fuel_shifts", "id=? AND status='cancelled'", arrayOf(shiftId.toString()))
}
