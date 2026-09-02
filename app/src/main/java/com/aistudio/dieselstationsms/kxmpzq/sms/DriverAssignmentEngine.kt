package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.ContentValues
import android.content.Context
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.utils.PhoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/** اختيار حتمي للسائق؛ لا يعتمد على AI ولا يرسل المهمة قبل تسجيلها ذريًا. */
class DriverAssignmentEngine(private val context: Context, private val db: DatabaseHelper) {
    private val replyManager = SmsReplyManager(context, db)

    suspend fun assign(orderId: String): Boolean = withContext(Dispatchers.IO) {
        val database = db.writableDatabase
        var driverPhone: String? = null
        var driverName = ""
        var taskCode = ""
        database.beginTransaction()
        try {
            val order = database.rawQuery("SELECT delivery_location, requested_delivery_at, status FROM fuel_orders WHERE order_id = ? LIMIT 1", arrayOf(orderId)).use { c ->
                if (!c.moveToFirst()) null else Triple(c.getString(0).orEmpty(), c.getLong(1).takeIf { !c.isNull(1) }, c.getString(2))
            } ?: return@withContext false
            if (order.third !in setOf("AWAITING_DELIVERY", "READY_FOR_DISPATCH", "DELIVERY_FAILED")) return@withContext false
            val candidate = database.rawQuery("""
                SELECT d.id, d.full_name, COALESCE(d.full_name_ar, d.full_name), d.phone, d.vehicle_id,
                       COALESCE(d.station_id, vehicle_owner.station_id, 1) AS assignment_station_id,
                       (SELECT COUNT(*) FROM sms_delivery_tasks t WHERE t.driver_id = d.id AND t.status IN ('ASSIGNED','ACCEPTED','OUT_FOR_DELIVERY')) AS active_tasks
                FROM drivers d
                JOIN vehicles v ON v.id = d.vehicle_id
                LEFT JOIN parties vehicle_owner ON vehicle_owner.id = v.party_id
                WHERE d.status = 'active' AND d.is_deleted = 0 AND d.phone IS NOT NULL AND d.phone <> ''
                  AND v.status = 'active' AND v.is_deleted = 0
                  AND (vehicle_owner.station_id = d.station_id OR (vehicle_owner.station_id IS NULL AND d.station_id IS NULL))
                  AND NOT EXISTS (SELECT 1 FROM sms_delivery_tasks busy WHERE busy.driver_id = d.id AND busy.status IN ('ASSIGNED','ACCEPTED','OUT_FOR_DELIVERY'))
                ORDER BY active_tasks ASC, d.updated_at ASC, d.id ASC LIMIT 1
            """.trimIndent(), null).use { c ->
                if (!c.moveToFirst()) null else arrayOf(c.getLong(0), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5))
            } ?: return@withContext false
            taskCode = "DT-${UUID.randomUUID().toString().take(8).uppercase()}"
            driverName = candidate[1] as String
            driverPhone = PhoneUtils.normalize(candidate[2] as String) ?: candidate[2] as String
            val inserted = database.insertOrThrow("sms_delivery_tasks", null, ContentValues().apply {
                put("delivery_id", taskCode); put("order_id", orderId); put("driver_id", candidate[0] as Long); put("vehicle_id", (candidate[3] as Long).takeIf { it > 0 }); put("station_id", candidate[4] as Long); put("location", order.first); put("scheduled_at", order.second); put("status", "ASSIGNED"); put("attempt_count", 0); put("created_at", System.currentTimeMillis()); put("updated_at", System.currentTimeMillis())
            })
            if (inserted <= 0) return@withContext false
            database.update("fuel_orders", ContentValues().apply { put("status", "READY_FOR_DISPATCH") }, "order_id = ? AND status IN ('AWAITING_DELIVERY','DELIVERY_FAILED')", arrayOf(orderId))
            database.update("fuel_orders", ContentValues().apply { put("status", "DRIVER_ASSIGNED"); put("driver_id", candidate[0] as Long); put("vehicle_id", (candidate[3] as Long).takeIf { it > 0 }) }, "order_id = ? AND status = 'READY_FOR_DISPATCH'", arrayOf(orderId))
            appendEvent(database, orderId, "READY_FOR_DISPATCH", JSONObject().put("task_code", taskCode))
            appendEvent(database, orderId, "DRIVER_ASSIGNED", JSONObject().put("driver_id", candidate[0] as Long).put("vehicle_id", candidate[3] as Long).put("task_code", taskCode))
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        val sent = driverPhone?.let { replyManager.sendReplyOnce(it, "لديك مهمة توصيل رقم $taskCode للطلب $orderId إلى $driverName. أرسل 1 لتوثيق استلام المهمة؛ التوصيل إلزامي ضمن مهامك الوظيفية.") } ?: false
        sent
    }

    suspend fun handleDriverReply(phone: String, body: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = PhoneUtils.normalize(phone) ?: phone.trim()
        val accepted = body.trim() == "1" || body.trim().contains("قبول")
        val rejected = body.trim() == "2" || body.trim().contains("رفض")
        if (!accepted && !rejected) return@withContext false
        val database = db.writableDatabase
        var orderId: String? = null
        var taskCode: String? = null
        database.beginTransaction()
        try {
            val task = database.rawQuery("SELECT delivery_id, order_id, driver_id FROM sms_delivery_tasks t JOIN drivers d ON d.id = t.driver_id WHERE (d.phone IN (?, ?) OR d.phone2 IN (?, ?)) AND t.status = 'ASSIGNED' ORDER BY t.created_at ASC LIMIT 1", arrayOf(normalized, normalized.removePrefix("967"), normalized, normalized.removePrefix("967"))).use { c -> if (c.moveToFirst()) Triple(c.getString(0), c.getString(1), c.getLong(2)) else null } ?: return@withContext false
            taskCode = task.first; orderId = task.second
            if (accepted) {
                database.update("sms_delivery_tasks", ContentValues().apply { put("status", "ACCEPTED"); put("assigned_at", System.currentTimeMillis()); put("updated_at", System.currentTimeMillis()) }, "delivery_id = ? AND status = 'ASSIGNED'", arrayOf(task.first))
                appendEvent(database, task.second, "DRIVER_ACCEPTED", JSONObject().put("driver_id", task.third).put("task_code", task.first).put("mandatory", true))
            } else {
                val penaltyId = DriverDisciplineRepository(db).recordRefusal(task.third, task.second, task.first)
                database.update("sms_delivery_tasks", ContentValues().apply { put("updated_at", System.currentTimeMillis()); put("failure_reason", "DRIVER_REFUSAL_PENALTY:$penaltyId") }, "delivery_id = ? AND status = 'ASSIGNED'", arrayOf(task.first))
                appendEvent(database, task.second, "DRIVER_REFUSAL_RECORDED", JSONObject().put("driver_id", task.third).put("task_code", task.first).put("penalty_id", penaltyId).put("mandatory", true))
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        if (accepted) {
            replyManager.sendReplyOnce(normalized, "تم تسجيل قبول مهمة التوصيل $taskCode. التوصيل جزء إلزامي من مهامك الوظيفية.")
        } else {
            replyManager.sendReplyOnce(normalized, "لا يمكن رفض مهمة التوصيل. تم تسجيل المخالفة وإدراج الجزاء المستحق: خصم الراتب يحدد وفق سياسة المحطة.")
        }
        true
    }

    private fun appendEvent(database: android.database.sqlite.SQLiteDatabase, orderId: String, type: String, payload: JSONObject) {
        database.insertWithOnConflict("sms_business_events", null, ContentValues().apply { put("event_id", "EV-${UUID.randomUUID()}"); put("conversation_id", orderId); put("event_type", type); put("aggregate_type", "FUEL_ORDER"); put("aggregate_id", orderId); put("payload_json", payload.toString()); put("created_at", System.currentTimeMillis()) }, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
        LiveUpdateHub.publish(JSONObject().put("type", "driver_task").put("order_id", orderId).put("event_type", type).put("payload", payload))
    }
}
