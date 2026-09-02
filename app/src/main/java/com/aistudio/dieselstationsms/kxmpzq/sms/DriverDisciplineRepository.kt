package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.ContentValues
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import org.json.JSONObject
import java.util.UUID

class DriverDisciplineRepository(private val db: DatabaseHelper) {
    fun recordRefusal(driverId: Long, orderId: String, taskCode: String, reason: String = "DRIVER_REFUSAL"): Long {
        val database = db.writableDatabase
        val amount = db.getSetting("driver_refusal_penalty")?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
        val now = System.currentTimeMillis()
        val id = database.insertWithOnConflict("driver_penalties", null, ContentValues().apply {
            put("penalty_id", "PEN-${UUID.randomUUID()}")
            put("driver_id", driverId); put("order_id", orderId); put("task_code", taskCode)
            put("penalty_type", reason); put("amount", amount); put("currency", "YER")
            put("status", "PENDING_PAYROLL"); put("created_at", now); put("updated_at", now)
        }, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
        LiveUpdateHub.publish(JSONObject().put("type", "driver_penalty").put("driver_id", driverId).put("order_id", orderId).put("task_code", taskCode).put("amount", amount).put("status", "PENDING_PAYROLL"))
        return id
    }

    fun getOutstandingForDriver(driverId: Long): Double = db.readableDatabase.rawQuery("SELECT COALESCE(SUM(amount),0) FROM driver_penalties WHERE driver_id = ? AND status = 'PENDING_PAYROLL'", arrayOf(driverId.toString())).use { if (it.moveToFirst()) it.getDouble(0) else 0.0 }
}
