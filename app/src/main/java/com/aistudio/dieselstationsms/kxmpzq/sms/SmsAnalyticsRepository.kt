package com.aistudio.dieselstationsms.kxmpzq.sms

import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import org.json.JSONArray
import org.json.JSONObject

class SmsAnalyticsRepository(private val db: DatabaseHelper) {
    fun driverPerformance(days: Int = 30): JSONArray {
        val out = JSONArray(); val since = System.currentTimeMillis() - days.coerceIn(1, 365) * 86_400_000L
        db.readableDatabase.rawQuery("SELECT d.id, COALESCE(d.full_name_ar, d.full_name) AS driver_name, SUM(CASE WHEN t.status IN ('ACCEPTED','OUT_FOR_DELIVERY','ARRIVED','COMPLETED') THEN 1 ELSE 0 END) AS accepted, COUNT(*) AS offered, SUM(CASE WHEN t.status IN ('COMPLETED','ARRIVED') THEN 1 ELSE 0 END) AS completed, AVG(CASE WHEN t.assigned_at IS NOT NULL THEN t.assigned_at - t.created_at END) AS response_ms FROM sms_delivery_tasks t JOIN drivers d ON d.id = t.driver_id WHERE t.created_at >= ? GROUP BY d.id ORDER BY accepted DESC, response_ms ASC", arrayOf(since.toString())).use { c -> while (c.moveToNext()) out.put(JSONObject().put("driver_id", c.getLong(0)).put("driver_name", c.getString(1)).put("accepted", c.getInt(2)).put("offered", c.getInt(3)).put("completed", c.getInt(4)).put("acceptance_rate", if (c.getInt(3) == 0) 0.0 else c.getDouble(2) / c.getInt(3)).put("completion_rate", if (c.getInt(2) == 0) 0.0 else c.getDouble(4) / c.getInt(2)).put("avg_response_ms", if (c.isNull(5)) JSONObject.NULL else c.getDouble(5))) }
        return out
    }

    fun matchedPaymentsDaily(days: Int = 30): JSONArray {
        val out = JSONArray(); val since = System.currentTimeMillis() - days.coerceIn(1, 365) * 86_400_000L
        db.readableDatabase.rawQuery("SELECT date(created_at / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS matches, COALESCE(SUM(amount),0) AS total_amount FROM fuel_payment_matches WHERE status = 'VERIFIED' AND created_at >= ? GROUP BY day ORDER BY day ASC", arrayOf(since.toString())).use { c -> while (c.moveToNext()) out.put(JSONObject().put("day", c.getString(0)).put("matches", c.getInt(1)).put("total_amount", c.getDouble(2))) }
        return out
    }
}
