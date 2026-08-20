package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.ContentValues
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import org.json.JSONObject
import java.util.UUID

class SmsOperationalNervousSystem(private val db: DatabaseHelper) {
    data class HealthSnapshot(
        val score: Int,
        val queued: Int,
        val failed: Int,
        val openSla: Int,
        val databaseHealthy: Boolean
    )

    fun snapshot(): HealthSnapshot {
        val database = db.readableDatabase
        val queued = count(database, "sms_outbox", "status IN ('QUEUED','RETRY_PENDING','SENDING','DELIVERY_PENDING')")
        val failed = count(database, "sms_outbox", "status = 'FAILED'")
        val openSla = count(database, "sms_sla_tasks", "status = 'OPEN'")
        val healthy = runCatching { db.checkIntegrity() }.getOrDefault(false)
        val score = if (!healthy) 0 else (100 - failed.coerceAtMost(50) - openSla.coerceAtMost(30)).coerceAtLeast(0)
        return HealthSnapshot(score, queued, failed, openSla, healthy)
    }

    fun registerComplaintSla(
        phone: String,
        conversationId: String,
        category: String,
        severity: String,
        metadata: JSONObject = JSONObject()
    ) {
        val minutes = when (severity) { "CRITICAL" -> 5L; "HIGH" -> 15L; else -> 60L }
        val now = System.currentTimeMillis()
        db.writableDatabase.insertWithOnConflict(
            "sms_sla_tasks", null, ContentValues().apply {
                put("task_id", UUID.randomUUID().toString())
                put("conversation_id", conversationId)
                put("phone", phone)
                put("task_type", "COMPLAINT_$category")
                put("severity", severity)
                put("due_at", now + minutes * 60_000L)
                put("metadata_json", metadata.toString())
                put("created_at", now)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    fun recoverOutbox() {
        val recoveryId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        db.writableDatabase.insertWithOnConflict(
            "sms_recovery_actions", null, ContentValues().apply {
                put("recovery_id", recoveryId)
                put("target_type", "SMS_OUTBOX")
                put("target_id", "stuck_messages")
                put("policy_version", "SMS_RECOVERY_V2")
                put("action", "REQUEUE_STUCK")
                put("status", "PLANNED")
                put("reason", "Worker startup or health check")
                put("created_at", now)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
        )
        SmsOutboxRepository.recoverStuck(db)
        db.writableDatabase.update(
            "sms_recovery_actions",
            ContentValues().apply { put("status", "COMPLETED"); put("completed_at", System.currentTimeMillis()) },
            "recovery_id = ? AND status = 'PLANNED'",
            arrayOf(recoveryId)
        )
    }

    private fun count(database: android.database.sqlite.SQLiteDatabase, table: String, where: String): Int =
        database.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
}
