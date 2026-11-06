package com.aistudio.dieselstationsms.kxmpzq.sms

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import android.database.sqlite.SQLiteDatabase
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.utils.PhoneUtils
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/**
 * مصدر الحقيقة للرسائل الصادرة. الإدراج يعني QUEUED فقط، وليس SENT.
 */
object SmsOutboxRepository {
    const val MAX_ATTEMPTS = 3

    /** attemptNumber is one-based and includes the currently claimed attempt. */
    fun shouldRetryAfterAttempt(attemptNumber: Int): Boolean =
        attemptNumber in 1 until MAX_ATTEMPTS

    /** attemptCount includes the attempt claimed for this message. */
    data class OutboxMessage(
        val messageId: String,
        val recipient: String,
        val body: String,
        val partsCount: Int,
        val attemptCount: Int,
        val subscriptionId: Int?,
        val eventId: String?,
        val businessEntityId: String?,
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
        priority: SmsBudgetManager.Priority = SmsBudgetManager.Priority.NORMAL,
        subscriptionId: Int? = null,
        dedupeKey: String? = null
    ): EnqueueResult? {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val result = enqueueWithinTransaction(
                database = database,
                recipient = recipient,
                body = body,
                eventId = eventId,
                conversationId = conversationId,
                businessEntityId = businessEntityId,
                priority = priority,
                subscriptionId = subscriptionId,
                dedupeKey = dedupeKey
            )
            database.setTransactionSuccessful()
            return result
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Inserts the outbox row and legacy SMS log inside the caller's SQLite transaction.
     * Business persistence uses this to commit the record and its notification atomically.
     */
    internal fun enqueueWithinTransaction(
        database: SQLiteDatabase,
        recipient: String,
        body: String,
        eventId: String? = null,
        conversationId: String? = null,
        businessEntityId: String? = null,
        priority: SmsBudgetManager.Priority = SmsBudgetManager.Priority.NORMAL,
        subscriptionId: Int? = null,
        dedupeKey: String? = null
    ): EnqueueResult? {
        check(database.inTransaction()) { "SMS outbox insert requires an active SQLite transaction" }
        val cleanRecipient = recipient.trim()
        val prepared = SmsBudgetManager.prepare(body, priority = priority)
        if (cleanRecipient.isEmpty() || prepared.body.isEmpty()) return null

        val now = System.currentTimeMillis()
        val messageId = UUID.randomUUID().toString()
        val safeDedupe = dedupeKey?.trim()?.takeIf { it.isNotEmpty() } ?: sha256(
            listOf("sms", cleanRecipient, prepared.body, eventId.orEmpty(), conversationId.orEmpty()).joinToString("|")
        )
        val values = ContentValues().apply {
            put("message_id", messageId)
            put("event_id", eventId)
            put("conversation_id", conversationId)
            put("business_entity_id", businessEntityId)
            put("channel", "sms")
            put("recipient", cleanRecipient)
            put("body", prepared.body)
            put("parts_count", prepared.partsCount)
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
                if (!cursor.moveToFirst()) null
                else Triple(cursor.getString(0), cursor.getInt(1), cursor.getString(2))
            }
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
        database.insertWithOnConflict(
            "sms_messages",
            null,
            logValues,
            SQLiteDatabase.CONFLICT_IGNORE
        )
        return EnqueueResult(messageId, "QUEUED", prepared.partsCount)
    }

    fun claimNext(db: DatabaseHelper, now: Long = System.currentTimeMillis()): OutboxMessage? {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val job = database.rawQuery(
                """
                SELECT message_id, recipient, body, parts_count, attempt_count, subscription_id,
                       event_id, business_entity_id
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
                    eventId = if (cursor.isNull(6)) null else cursor.getString(6),
                    businessEntityId = if (cursor.isNull(7)) null else cursor.getString(7)
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
                database.setTransactionSuccessful()
                return job.copy(attemptCount = job.attemptCount + 1)
            }
            database.setTransactionSuccessful()
            return null
        } finally {
            database.endTransaction()
        }
    }

    fun markSent(db: DatabaseHelper, messageId: String, now: Long = System.currentTimeMillis()) {
        update(db, messageId, "SENDING", ContentValues().apply { put("sent_at", now); put("status", "DELIVERY_PENDING") }, now, "sent")
    }


    fun markDelivered(db: DatabaseHelper, messageId: String, now: Long = System.currentTimeMillis()) {
        update(db, messageId, "DELIVERY_PENDING", ContentValues().apply { put("delivered_at", now); put("status", "DELIVERED") }, now, "delivered")
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

    /** Cancel a claimed message before transport when its business source is no longer eligible. */
    fun cancelClaimed(db: DatabaseHelper, messageId: String, reason: String): Boolean {
        val now = System.currentTimeMillis()
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val changed = database.update(
                "sms_outbox",
                ContentValues().apply {
                    put("status", "CANCELLED")
                    put("failed_at", now)
                    put("failure_code", "BUSINESS_INELIGIBLE")
                    put("failure_reason", reason.take(500))
                },
                "message_id = ? AND status = 'SENDING'",
                arrayOf(messageId)
            )
            if (changed == 1) {
                // A final pre-transport validation can reject the message after parts were
                // prepared. Remove only unsent parts; no transport callback can own them yet.
                database.delete(
                    "sms_outbox_parts",
                    "message_id = ? AND status = 'PENDING'",
                    arrayOf(messageId)
                )
                syncLegacyStatus(database, messageId, "cancelled", now)
            }
            database.setTransactionSuccessful()
            return changed == 1
        } finally {
            database.endTransaction()
        }
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
            val previousStatus = database.rawQuery(
                "SELECT status FROM sms_outbox_parts WHERE message_id = ? AND part_index = ? LIMIT 1",
                arrayOf(messageId, partIndex.toString())
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            // Delivery is monotonic: a late SENT/FAILED callback must never downgrade DELIVERED.
            if (previousStatus != "DELIVERED" || status == "DELIVERED") {
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
            }

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
                val retry = shouldRetryAfterAttempt(attempt)
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

    /**
     * أعد إدراج الرسائل التي فشلت حصراً لأن إذن SEND_SMS كان مفقوداً.
     * لا نعيد رسائل فشلت بسبب رقم غير صالح أو خطأ شبكة/مشغل.
     */
    fun requeuePermissionFailures(db: DatabaseHelper, now: Long = System.currentTimeMillis()): Int {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val ids = database.rawQuery(
                "SELECT message_id FROM sms_outbox WHERE status = 'FAILED' AND failure_code = 'SEND_SMS_PERMISSION'",
                null
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            if (ids.isEmpty()) {
                database.setTransactionSuccessful()
                return 0
            }
            val values = ContentValues().apply {
                put("status", "QUEUED")
                put("attempt_count", 0)
                putNull("failure_code")
                putNull("failure_reason")
                putNull("failed_at")
                put("next_attempt_at", now)
                put("queued_at", now)
            }
            var changed = 0
            ids.forEach { id ->
                val count = database.update(
                    "sms_outbox",
                    values,
                    "message_id = ? AND status = 'FAILED' AND failure_code = 'SEND_SMS_PERMISSION'",
                    arrayOf(id)
                )
                if (count == 1) {
                    syncLegacyStatus(database, id, "queued", now)
                    changed++
                }
            }
            database.setTransactionSuccessful()
            return changed
        } finally {
            database.endTransaction()
        }
    }

    fun hasPending(db: DatabaseHelper): Boolean = db.readableDatabase.rawQuery(
        "SELECT 1 FROM sms_outbox WHERE status IN ('QUEUED','RETRY_PENDING','SENDING','DELIVERY_PENDING') LIMIT 1",
        null
    ).use { it.moveToFirst() }

    private fun update(db: DatabaseHelper, messageId: String, expected: String, values: ContentValues, now: Long, legacyStatus: String) {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val changed = database.update("sms_outbox", values, "message_id = ? AND status = ?", arrayOf(messageId, expected))
            if (changed == 1) syncLegacyStatus(database, messageId, legacyStatus, now)
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


/**
 * Commits a business record and its SMS outbox row in one SQLite transaction.
 * WorkManager must be scheduled only after execute() returns successfully.
 */
object SmsOutboxAtomicTransaction {
    data class NotificationDraft(
        val recipient: String,
        val body: String,
        val dedupeKey: String,
        val eventId: String? = null,
        val conversationId: String? = null,
        val businessEntityId: String? = null,
        val priority: SmsBudgetManager.Priority = SmsBudgetManager.Priority.NORMAL,
        val subscriptionId: Int? = null
    )

    data class Outcome<T>(
        val value: T,
        val queuedMessageId: String?
    )

    data class DedupeRecoveryResult(
        val finalizedWithOutbox: Int,
        val releasedWithoutOutbox: Int,
        val expired: Int
    )

    /**
     * The business operation and a valid notification either both commit or both roll back.
     * If SMS is disabled, permission is absent, or contact data is invalid, the business
     * operation commits without queuing an SMS, matching the previous opt-out behavior.
     */
    fun <T> execute(
        db: DatabaseHelper,
        context: Context,
        persist: () -> T,
        notificationFactory: (T) -> NotificationDraft?
    ): Outcome<T> {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val value = persist()
            val smsEnabled = db.getSetting("sms_send_enabled") != "0"
            val smsPermitted = runCatching {
                ContextCompat.checkSelfPermission(
                    context.applicationContext,
                    Manifest.permission.SEND_SMS
                ) == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)

            var queuedMessageId: String? = null
            if (smsEnabled) {
                if (!smsPermitted) {
                    Log.i(TAG, "SEND_SMS permission is absent; retaining this notification as QUEUED")
                }
                // Database/contact lookup failures abort the transaction; silently committing
                // a financial event after an unexpected lookup error would lose its SMS.
                val draft = notificationFactory(value)

                if (draft != null) {
                    val recipient = runCatching { PhoneUtils.normalize(draft.recipient)?.trim() }
                        .getOrNull()
                        ?.takeIf { it.isNotEmpty() }
                    val body = runCatching {
                        SmsMessageNormalizer.normalizeForSms(draft.body)
                    }.getOrDefault("")

                    // Invalid recipient/content must not block the business record.
                    if (recipient != null && body.isNotBlank() && body.length <= MAX_MESSAGE_LENGTH) {
                        val dedupeKey = draft.dedupeKey.trim().ifBlank {
                            sha256(listOf(recipient, body, draft.eventId.orEmpty()).joinToString("|"))
                        }
                        val queued = SmsOutboxRepository.enqueueWithinTransaction(
                            database = database,
                            recipient = recipient,
                            body = body,
                            eventId = draft.eventId,
                            conversationId = draft.conversationId,
                            businessEntityId = draft.businessEntityId,
                            priority = draft.priority,
                            subscriptionId = draft.subscriptionId,
                            dedupeKey = dedupeKey
                        ) ?: throw IllegalStateException("Valid business SMS could not be inserted into the outbox")

                        // Align the legacy sendReplyOnce guard with this outbox key in the same commit.
                        val now = System.currentTimeMillis()
                        val reservation = ContentValues().apply {
                            put("dedupe_key", dedupeKey)
                            put("phone", recipient)
                            put("message_hash", sha256(body))
                            put("message_preview", body.take(120))
                            // In the legacy guard, "sent" means accepted into the durable outbox.
                            put("status", "sent")
                            put("reserved_at", now)
                            put("sent_at", now)
                        }
                        val markerInserted = database.insertWithOnConflict(
                            OUTBOUND_DEDUPE_TABLE,
                            null,
                            reservation,
                            SQLiteDatabase.CONFLICT_IGNORE
                        )
                        if (markerInserted == -1L) {
                            val markerUpdated = database.update(
                                OUTBOUND_DEDUPE_TABLE,
                                ContentValues().apply {
                                    put("phone", recipient)
                                    put("message_hash", sha256(body))
                                    put("message_preview", body.take(120))
                                    put("status", "sent")
                                    put("reserved_at", now)
                                    put("sent_at", now)
                                },
                                "dedupe_key = ?",
                                arrayOf(dedupeKey)
                            )
                            check(markerUpdated == 1) {
                                "Unable to synchronize the durable outbound dedupe marker"
                            }
                        }
                        queuedMessageId = queued.messageId
                    } else {
                        Log.w(TAG, "Business SMS skipped because recipient or message content is invalid")
                    }
                }
            }

            database.setTransactionSuccessful()
            return Outcome(value, queuedMessageId)
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Repairs the process-death window between reserving a key and inserting its outbox row.
     * A matching outbox row finalizes the reservation; an orphan reservation is released.
     */
    fun recoverOutboundDedupeReservations(
        db: DatabaseHelper,
        now: Long = System.currentTimeMillis(),
        staleAfterMs: Long = 5 * 60 * 1000L,
        retentionMs: Long = 24L * 60L * 60L * 1000L
    ): DedupeRecoveryResult {
        require(staleAfterMs >= 0L) { "staleAfterMs must not be negative" }
        require(retentionMs > staleAfterMs) { "retentionMs must exceed staleAfterMs" }
        val database = db.writableDatabase
        val staleCutoff = now - staleAfterMs
        val retentionCutoff = now - retentionMs
        database.beginTransaction()
        try {
            val args = arrayOf(staleCutoff.toString(), retentionCutoff.toString())
            val finalized = database.update(
                OUTBOUND_DEDUPE_TABLE,
                ContentValues().apply {
                    put("status", "sent")
                    put("sent_at", now)
                },
                """
                status = 'reserved'
                AND reserved_at <= ?
                AND reserved_at > ?
                AND EXISTS (
                    SELECT 1 FROM sms_outbox
                    WHERE sms_outbox.dedupe_key = sms_outbound_dedupe.dedupe_key
                )
                """.trimIndent(),
                args
            )
            val released = database.delete(
                OUTBOUND_DEDUPE_TABLE,
                """
                status = 'reserved'
                AND reserved_at <= ?
                AND reserved_at > ?
                AND NOT EXISTS (
                    SELECT 1 FROM sms_outbox
                    WHERE sms_outbox.dedupe_key = sms_outbound_dedupe.dedupe_key
                )
                """.trimIndent(),
                args
            )
            val expired = database.delete(
                OUTBOUND_DEDUPE_TABLE,
                "reserved_at <= ?",
                arrayOf(retentionCutoff.toString())
            )
            database.setTransactionSuccessful()
            return DedupeRecoveryResult(finalized, released, expired)
        } finally {
            database.endTransaction()
        }
    }

    private const val TAG = "SmsOutboxAtomic"
    private const val MAX_MESSAGE_LENGTH = 4096
    private const val OUTBOUND_DEDUPE_TABLE = "sms_outbound_dedupe"

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
