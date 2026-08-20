package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/**
 * مصدر الحقيقة للرسائل الصادرة. الإدراج يعني QUEUED فقط، وليس SENT.
 */
object SmsOutboxRepository {
    data class OutboxMessage(
        val messageId: String,
        val recipient: String,
        val body: String,
        val partsCount: Int,
        val attemptCount: Int,
        val subscriptionId: Int?,
        val channel: String = "sms"
    )

    data class EnqueueResult(
        val messageId: String,
        val status: String,
        val partsCount: Int
    )

    fun enqueue(
        db: DatabaseHelper,
        recipient: String,
        body: String,
        eventId: String? = null,
        conversationId: String? = null,
        businessEntityId: String? = null,
        channel: String = "sms",
        priority: SmsBudgetManager.Priority = SmsBudgetManager.Priority.NORMAL,
        subscriptionId: Int? = null,
        dedupeKey: String? = null
    ): EnqueueResult? {
        val cleanRecipient = recipient.trim()
        val prepared = SmsBudgetManager.prepare(body, priority = priority)
        if (cleanRecipient.isEmpty() || prepared.body.isEmpty()) return null

        val now = System.currentTimeMillis()
        val messageId = UUID.randomUUID().toString()
        val safeChannel = channel.trim().lowercase().takeIf { it in setOf("sms", "whatsapp") } ?: "sms"
        val safeDedupe = dedupeKey ?: sha256(
            listOf(safeChannel, cleanRecipient, prepared.body, eventId.orEmpty(), conversationId.orEmpty()).joinToString("|")
        )
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("message_id", messageId)
                put("event_id", eventId)
                put("conversation_id", conversationId)
                put("business_entity_id", businessEntityId)
                put("channel", safeChannel)
                put("recipient", cleanRecipient)
                put("body", prepared.body)
                put("parts_count", if (safeChannel == "whatsapp") 1 else prepared.partsCount)
                put("priority", priority.name)
                put("status", "QUEUED")
                put("attempt_count", 0)
                put("created_at", now)
                put("queued_at", now)
                put("subscription_id", subscriptionId)
                put("dedupe_key", safeDedupe)
                put("next_attempt_at", now)
            }
            val inserted = database.insertWithOnConflict(
                "sms_outbox",
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE
            )
            if (inserted == -1L) {
                val existing = database.rawQuery(
                    "SELECT message_id, parts_count, status FROM sms_outbox WHERE dedupe_key = ? LIMIT 1",
                    arrayOf(safeDedupe)
                ).use { cursor ->
                    if (!cursor.moveToFirst()) null else Triple(cursor.getString(0), cursor.getInt(1), cursor.getString(2))
                }
                database.setTransactionSuccessful()
                return existing?.let { EnqueueResult(it.first, it.third, it.second) }
            }

            val logValues = ContentValues().apply {
                put("uuid", messageId)
                put("phone_number", cleanRecipient)
                put("message_body", prepared.body)
                put("message_type", "outgoing")
                put("status", "queued")
                put("created_at", now.toString())
                put("updated_at", now.toString())
            }
            database.insertWithOnConflict("sms_messages", null, logValues, SQLiteDatabase.CONFLICT_IGNORE)
            database.setTransactionSuccessful()
            return EnqueueResult(messageId, "QUEUED", if (safeChannel == "whatsapp") 1 else prepared.partsCount)
        } finally {
            database.endTransaction()
        }
    }

    fun claimNext(db: DatabaseHelper, now: Long = System.currentTimeMillis()): OutboxMessage? {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val job = database.rawQuery(
                """
                SELECT message_id, recipient, body, parts_count, attempt_count, subscription_id, channel
                FROM sms_outbox
                WHERE status IN ('QUEUED','RETRY_PENDING') AND next_attempt_at <= ?
                ORDER BY CASE priority WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'NORMAL' THEN 2 ELSE 3 END, created_at ASC
                LIMIT 1
                """.trimIndent(),
                arrayOf(now.toString())
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else OutboxMessage(
                    messageId = cursor.getString(0),
                    recipient = cursor.getString(1),
                    body = cursor.getString(2),
                    partsCount = cursor.getInt(3),
                    attemptCount = cursor.getInt(4),
                    subscriptionId = if (cursor.isNull(5)) null else cursor.getInt(5),
                    channel = cursor.getString(6).orEmpty().ifBlank { "sms" }
                )
            }
            if (job != null) {
                val changed = database.update(
                    "sms_outbox",
                    ContentValues().apply {
                        put("status", "SENDING")
                        put("attempt_count", job.attemptCount + 1)
                    },
                    "message_id = ? AND status IN ('QUEUED','RETRY_PENDING')",
                    arrayOf(job.messageId)
                )
                if (changed != 1) {
                    database.setTransactionSuccessful()
                    return null
                }
                syncLegacyStatus(database, job.messageId, "sending", now)
            }
            database.setTransactionSuccessful()
            return job
        } finally {
            database.endTransaction()
        }
    }

    fun markSent(db: DatabaseHelper, messageId: String, now: Long = System.currentTimeMillis()) {
        update(db, messageId, "SENDING", ContentValues().apply { put("sent_at", now); put("status", "DELIVERY_PENDING") }, now, "sent")
    }

    fun markExternalSent(
        db: DatabaseHelper,
        messageId: String,
        providerMessageId: String,
        now: Long = System.currentTimeMillis()
    ) {
        update(
            db,
            messageId,
            "SENDING",
            ContentValues().apply {
                put("sent_at", now)
                put("status", "DELIVERY_PENDING")
                put("provider_message_id", providerMessageId.take(240))
            },
            now,
            "sent"
        )
    }

    fun markDelivered(db: DatabaseHelper, messageId: String, now: Long = System.currentTimeMillis()) {
        update(db, messageId, "DELIVERY_PENDING", ContentValues().apply { put("delivered_at", now); put("status", "DELIVERED") }, now, "delivered")
    }

    fun recordExternalStatus(
        db: DatabaseHelper,
        providerMessageId: String,
        recipient: String,
        status: String,
        errorJson: String = "",
        occurredAt: Long = System.currentTimeMillis()
    ): Boolean {
        val normalizedStatus = status.trim().lowercase()
        if (providerMessageId.isBlank() || normalizedStatus !in setOf("sent", "delivered", "read", "failed")) return false
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val eventKey = sha256("whatsapp|$providerMessageId|$normalizedStatus|$occurredAt")
            val inserted = database.insertWithOnConflict(
                "sms_channel_delivery_events",
                null,
                ContentValues().apply {
                    put("event_key", eventKey)
                    put("channel", "whatsapp")
                    put("provider_message_id", providerMessageId.take(240))
                    put("recipient", recipient.take(80))
                    put("status", normalizedStatus)
                    put("error_json", errorJson.take(1500))
                    put("occurred_at", occurredAt)
                    put("created_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )
            val messageId = database.rawQuery(
                "SELECT message_id FROM sms_outbox WHERE channel = 'whatsapp' AND provider_message_id = ? LIMIT 1",
                arrayOf(providerMessageId)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            if (messageId != null) {
                when (normalizedStatus) {
                    "sent" -> database.update(
                        "sms_outbox",
                        ContentValues().apply { put("status", "DELIVERY_PENDING"); put("sent_at", occurredAt) },
                        "message_id = ? AND status IN ('SENDING','DELIVERY_PENDING')",
                        arrayOf(messageId)
                    )
                    "delivered", "read" -> database.update(
                        "sms_outbox",
                        ContentValues().apply { put("status", "DELIVERED"); put("delivered_at", occurredAt) },
                        "message_id = ? AND status IN ('SENDING','DELIVERY_PENDING','DELIVERED')",
                        arrayOf(messageId)
                    )
                    "failed" -> database.update(
                        "sms_outbox",
                        ContentValues().apply {
                            put("status", "FAILED")
                            put("failed_at", occurredAt)
                            put("failure_code", "WHATSAPP_PROVIDER_FAILED")
                            put("failure_reason", errorJson.take(500))
                        },
                        "message_id = ? AND status IN ('SENDING','DELIVERY_PENDING')",
                        arrayOf(messageId)
                    )
                }
                syncLegacyStatus(database, messageId, if (normalizedStatus == "failed") "failed" else if (normalizedStatus == "sent") "sent" else "delivered", occurredAt)
            }
            database.setTransactionSuccessful()
            return inserted != -1L || messageId != null
        } finally {
            database.endTransaction()
        }
    }

    fun markFailed(db: DatabaseHelper, messageId: String, code: String, reason: String, retry: Boolean, now: Long = System.currentTimeMillis()) {
        val status = if (retry) "RETRY_PENDING" else "FAILED"
        val values = ContentValues().apply {
            put("status", status)
            put("failure_code", code.take(100))
            put("failure_reason", reason.take(500))
            put("failed_at", now)
            if (retry) put("next_attempt_at", now + 60_000L)
        }
        update(db, messageId, "SENDING", values, now, if (retry) "failed" else "failed")
    }

    fun cancel(db: DatabaseHelper, messageId: String): Boolean {
        val now = System.currentTimeMillis()
        val changed = db.writableDatabase.update(
            "sms_outbox",
            ContentValues().apply { put("status", "CANCELLED"); put("failed_at", now); put("failure_code", "CANCELLED") },
            "message_id = ? AND status IN ('DRAFT','QUEUED','RETRY_PENDING')",
            arrayOf(messageId)
        )
        if (changed > 0) syncLegacyStatus(db.writableDatabase, messageId, "cancelled", now)
        return changed > 0
    }

    fun prepareParts(db: DatabaseHelper, messageId: String, partsCount: Int) {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            for (index in 0 until partsCount) {
                database.insertWithOnConflict(
                    "sms_outbox_parts",
                    null,
                    ContentValues().apply {
                        put("message_id", messageId)
                        put("part_index", index)
                        put("status", "PENDING")
                    },
                    SQLiteDatabase.CONFLICT_IGNORE
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun recordPartResult(
        db: DatabaseHelper,
        messageId: String,
        partIndex: Int,
        delivered: Boolean,
        success: Boolean,
        resultCode: String,
        reason: String = ""
    ) {
        val now = System.currentTimeMillis()
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val status = when {
                !success -> "FAILED"
                delivered -> "DELIVERED"
                else -> "SENT"
            }
            database.update(
                "sms_outbox_parts",
                ContentValues().apply {
                    put("status", status)
                    if (status == "SENT") put("sent_at", now)
                    if (status == "DELIVERED") put("delivered_at", now)
                    if (!success) {
                        put("failure_code", resultCode.take(100))
                        put("failure_reason", reason.take(500))
                    }
                },
                "message_id = ? AND part_index = ?",
                arrayOf(messageId, partIndex.toString())
            )

            val failed = database.rawQuery(
                "SELECT 1 FROM sms_outbox_parts WHERE message_id = ? AND status = 'FAILED' LIMIT 1",
                arrayOf(messageId)
            ).use { it.moveToFirst() }
            val total = database.rawQuery(
                "SELECT COUNT(*) FROM sms_outbox_parts WHERE message_id = ?",
                arrayOf(messageId)
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            val completeStatus = if (delivered) "DELIVERED" else "SENT"
            val completed = database.rawQuery(
                "SELECT COUNT(*) FROM sms_outbox_parts WHERE message_id = ? AND status = ?",
                arrayOf(messageId, completeStatus)
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

            if (failed) {
                val attempt = database.rawQuery(
                    "SELECT attempt_count FROM sms_outbox WHERE message_id = ? LIMIT 1",
                    arrayOf(messageId)
                ).use { if (it.moveToFirst()) it.getInt(0) else 1 }
                val retry = attempt < 3
                database.update(
                    "sms_outbox",
                    ContentValues().apply {
                        put("status", if (retry) "RETRY_PENDING" else "FAILED")
                        put("failed_at", now)
                        put("failure_code", resultCode.take(100))
                        put("failure_reason", reason.take(500))
                        if (retry) put("next_attempt_at", now + 60_000L)
                    },
                    "message_id = ? AND status IN ('SENDING','DELIVERY_PENDING')",
                    arrayOf(messageId)
                )
                syncLegacyStatus(database, messageId, "failed", now)
            } else if (total > 0 && completed == total) {
                if (delivered) {
                    database.update("sms_outbox", ContentValues().apply { put("status", "DELIVERED"); put("delivered_at", now) }, "message_id = ?", arrayOf(messageId))
                    syncLegacyStatus(database, messageId, "delivered", now)
                } else {
                    database.update("sms_outbox", ContentValues().apply { put("status", "DELIVERY_PENDING"); put("sent_at", now) }, "message_id = ? AND status = 'SENDING'", arrayOf(messageId))
                    syncLegacyStatus(database, messageId, "sent", now)
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun recoverStuck(db: DatabaseHelper, olderThanMs: Long = 5 * 60 * 1000L) {
        val now = System.currentTimeMillis()
        db.writableDatabase.update(
            "sms_outbox",
            ContentValues().apply { put("status", "RETRY_PENDING"); put("next_attempt_at", now) },
            "status = 'SENDING' AND queued_at < ?",
            arrayOf((now - olderThanMs).toString())
        )
    }

    /** يحول انتظار التسليم الذي تجاوز المهلة إلى فشل قابل للتنبيه والاسترداد. */
    fun failDeliveryTimeouts(db: DatabaseHelper, timeoutMs: Long = 15 * 60 * 1000L): List<String> {
        val now = System.currentTimeMillis()
        val cutoff = now - timeoutMs
        val database = db.writableDatabase
        val ids = mutableListOf<String>()
        database.beginTransaction()
        try {
            database.rawQuery(
                "SELECT message_id FROM sms_outbox WHERE status = 'DELIVERY_PENDING' AND sent_at IS NOT NULL AND sent_at < ?",
                arrayOf(cutoff.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) ids += cursor.getString(0)
            }
            ids.forEach { messageId ->
                database.update(
                    "sms_outbox",
                    ContentValues().apply {
                        put("status", "FAILED")
                        put("failed_at", now)
                        put("failure_code", "DELIVERY_TIMEOUT")
                        put("failure_reason", "No delivery callback within ${timeoutMs / 60000L} minutes")
                    },
                    "message_id = ? AND status = 'DELIVERY_PENDING'",
                    arrayOf(messageId)
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        ids.forEach { syncLegacyStatus(database, it, "failed", now) }
        return ids
    }

    fun hasPending(db: DatabaseHelper): Boolean = db.readableDatabase.rawQuery(
        "SELECT 1 FROM sms_outbox WHERE status IN ('QUEUED','RETRY_PENDING','SENDING','DELIVERY_PENDING') LIMIT 1",
        null
    ).use { it.moveToFirst() }

    private fun update(db: DatabaseHelper, messageId: String, expected: String, values: ContentValues, now: Long, legacyStatus: String) {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            database.update("sms_outbox", values, "message_id = ? AND status = ?", arrayOf(messageId, expected))
            syncLegacyStatus(database, messageId, legacyStatus, now)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun syncLegacyStatus(database: SQLiteDatabase, messageId: String, status: String, now: Long) {
        database.update(
            "sms_messages",
            ContentValues().apply { put("status", status); put("updated_at", now.toString()); if (status == "sent" || status == "delivered") put("sent_at", now.toString()) },
            "uuid = ?",
            arrayOf(messageId)
        )
        val trace = database.rawQuery(
            "SELECT event_id, conversation_id, recipient, failure_code, failure_reason FROM sms_outbox WHERE message_id = ? LIMIT 1",
            arrayOf(messageId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else arrayOf(
                if (cursor.isNull(0)) messageId else cursor.getString(0),
                if (cursor.isNull(1)) "" else cursor.getString(1),
                cursor.getString(2),
                if (cursor.isNull(3)) "" else cursor.getString(3),
                if (cursor.isNull(4)) "" else cursor.getString(4)
            )
        } ?: return
        if (trace[1].isBlank()) return
        val stage = when (status) {
            "queued" -> "MESSAGE_QUEUED"
            "sending" -> "MESSAGE_SENDING"
            "sent" -> "SMS_SENT"
            "delivered" -> "DELIVERY_RESULT"
            "failed" -> "DELIVERY_FAILED"
            "cancelled" -> "MESSAGE_CANCELLED"
            else -> "MESSAGE_STATUS"
        }
        database.insert(
            "sms_conversation_trace", null, ContentValues().apply {
                put("trace_id", UUID.randomUUID().toString())
                put("conversation_id", trace[1])
                put("event_id", trace[0])
                put("stage", stage)
                put("payload_json", JSONObject().apply {
                    put("message_id", messageId)
                    put("recipient", trace[2])
                    put("status", status)
                    put("failure_code", trace[3])
                    put("failure_reason", trace[4])
                }.toString())
                put("created_at", now)
            }
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
