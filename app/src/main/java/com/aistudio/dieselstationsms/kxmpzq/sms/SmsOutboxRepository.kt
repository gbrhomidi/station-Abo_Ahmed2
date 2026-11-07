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
import java.security.SecureRandom
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

    /** attemptCount counts carrier submissions; claimCount counts local leases. */
    data class OutboxMessage(
        val messageId: String,
        val recipient: String,
        val body: String,
        val partsCount: Int,
        val attemptCount: Int,
        val subscriptionId: Int?,
        val eventId: String?,
        val businessEntityId: String?,
        val attemptToken: String = "",
        val leaseOwner: String? = null,
        val claimGeneration: Int = 0,
        val claimCount: Int = 0
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

    fun claimNext(
        db: DatabaseHelper,
        now: Long = System.currentTimeMillis(),
        leaseDurationMs: Long = DEFAULT_LEASE_DURATION_MS,
        leaseOwner: String = UUID.randomUUID().toString()
    ): OutboxMessage? {
        require(leaseDurationMs in 60_000L..MAX_LEASE_DURATION_MS) { "SMS lease duration is outside the allowed range" }
        require(leaseOwner.isNotBlank() && leaseOwner.length <= 80) { "SMS lease owner is invalid" }
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val job = database.rawQuery(
                """
                SELECT message_id, recipient, body, parts_count, attempt_count, subscription_id,
                       event_id, business_entity_id, COALESCE(claim_generation,0), COALESCE(claim_count,0)
                FROM sms_outbox
                WHERE status IN ('QUEUED','RETRY_PENDING') AND next_attempt_at <= ? AND attempt_count < $MAX_ATTEMPTS
                ORDER BY CASE priority WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'NORMAL' THEN 2 ELSE 3 END, created_at ASC
                LIMIT 1
                """.trimIndent(),
                arrayOf(now.toString())
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else OutboxMessage(
                    messageId = cursor.getString(0), recipient = cursor.getString(1), body = cursor.getString(2),
                    partsCount = cursor.getInt(3), attemptCount = cursor.getInt(4),
                    subscriptionId = if (cursor.isNull(5)) null else cursor.getInt(5),
                    eventId = if (cursor.isNull(6)) null else cursor.getString(6),
                    businessEntityId = if (cursor.isNull(7)) null else cursor.getString(7),
                    claimGeneration = cursor.getInt(8), claimCount = cursor.getInt(9)
                )
            } ?: run {
                database.setTransactionSuccessful()
                return null
            }
            val token = UUID.randomUUID().toString()
            val generation = job.claimGeneration + 1
            val leaseUntil = Math.addExact(now, leaseDurationMs)
            val changed = database.update(
                "sms_outbox",
                ContentValues().apply {
                    put("status", "SENDING")
                    put("claim_count", job.claimCount + 1)
                    put("claimed_at", now)
                    put("lease_until", leaseUntil)
                    put("lease_owner", leaseOwner)
                    put("attempt_token", token)
                    put("claim_generation", generation)
                    put("submission_outcome", "IN_FLIGHT")
                    putNull("failure_code")
                    putNull("failure_reason")
                    putNull("failed_at")
                },
                "message_id = ? AND status IN ('QUEUED','RETRY_PENDING') AND next_attempt_at <= ? AND attempt_count < ?",
                arrayOf(job.messageId, now.toString(), MAX_ATTEMPTS.toString())
            )
            if (changed != 1) {
                database.setTransactionSuccessful()
                return null
            }
            database.insertOrThrow("sms_outbox_attempts", null, ContentValues().apply {
                put("attempt_token", token)
                put("message_id", job.messageId)
                put("attempt_number", job.attemptCount)
                put("claim_generation", generation)
                put("lease_owner", leaseOwner)
                put("claimed_at", now)
                put("lease_until", leaseUntil)
                put("state", "CLAIMED")
            })
            for (index in 0 until job.partsCount) {
                database.insertOrThrow("sms_outbox_attempt_parts", null, ContentValues().apply {
                    put("attempt_token", token)
                    put("message_id", job.messageId)
                    put("part_index", index)
                })
            }
            syncLegacyStatus(database, job.messageId, "sending", now)
            database.setTransactionSuccessful()
            return job.copy(attemptToken = token, leaseOwner = leaseOwner,
                claimGeneration = generation, claimCount = job.claimCount + 1)
        } finally {
            database.endTransaction()
        }
    }

    /** Durable boundary immediately before invoking SmsManager. */
    fun beginTransport(db: DatabaseHelper, job: OutboxMessage, now: Long = System.currentTimeMillis()): OutboxMessage? {
        if (job.attemptToken.isBlank()) return null
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val changed = database.update(
                "sms_outbox",
                ContentValues().apply {
                    put("attempt_count", job.attemptCount + 1)
                    put("submission_outcome", "SUBMITTING")
                },
                "message_id = ? AND attempt_token = ? AND status = 'SENDING' AND lease_until > ?",
                arrayOf(job.messageId, job.attemptToken, now.toString())
            )
            if (changed != 1) {
                database.setTransactionSuccessful()
                return null
            }
            val attemptChanged = database.update(
                "sms_outbox_attempts",
                ContentValues().apply {
                    put("attempt_number", job.attemptCount + 1)
                    put("state", "SUBMITTING")
                    put("started_at", now)
                },
                "attempt_token = ? AND message_id = ? AND state = 'CLAIMED'",
                arrayOf(job.attemptToken, job.messageId)
            )
            check(attemptChanged == 1) { "SMS attempt claim is no longer owned by this worker" }
            database.setTransactionSuccessful()
            return job.copy(attemptCount = job.attemptCount + 1)
        } finally {
            database.endTransaction()
        }
    }

    /** Release a pre-transport claim after a local failure without consuming a carrier attempt. */
    fun releaseClaimForLocalRetry(db: DatabaseHelper, job: OutboxMessage, reasonCode: String,
                                  now: Long = System.currentTimeMillis()): Boolean {
        if (job.attemptToken.isBlank()) return false
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val changed = database.update(
                "sms_outbox",
                ContentValues().apply {
                    put("status", "RETRY_PENDING")
                    put("submission_outcome", "LOCAL_RETRY")
                    put("failure_code", reasonCode.take(80))
                    put("failure_reason", "Local preparation failed before SmsManager invocation")
                    put("next_attempt_at", now + LOCAL_RETRY_DELAY_MS)
                    put("failed_at", now)
                    putNull("lease_until")
                    putNull("lease_owner")
                    putNull("attempt_token")
                },
                "message_id = ? AND attempt_token = ? AND status = 'SENDING'",
                arrayOf(job.messageId, job.attemptToken)
            )
            if (changed == 1) {
                database.update("sms_outbox_attempts", ContentValues().apply {
                    put("state", "LOCAL_RETRY")
                    put("failure_code", reasonCode.take(80))
                    put("completed_at", now)
                }, "attempt_token = ? AND state = 'CLAIMED'", arrayOf(job.attemptToken))
                syncLegacyStatus(database, job.messageId, "queued", now)
            }
            database.setTransactionSuccessful()
            return changed == 1
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


    fun markFailed(
        db: DatabaseHelper, messageId: String, code: String, reason: String, retry: Boolean,
        now: Long = System.currentTimeMillis(), attemptToken: String, jitterMs: Long? = null
    ) {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val current = database.rawQuery(
                "SELECT attempt_token, attempt_count FROM sms_outbox WHERE message_id = ? AND status = 'SENDING' LIMIT 1",
                arrayOf(messageId)
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else cursor.getString(0).orEmpty() to cursor.getInt(1)
            } ?: run {
                database.setTransactionSuccessful()
                return
            }
            if (attemptToken.isBlank() || current.first != attemptToken) {
                database.setTransactionSuccessful()
                return
            }
            val token = attemptToken
            val safeCode = code.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(80).ifBlank { "TRANSPORT_FAILURE" }
            val shouldQueue = retry && shouldRetryAfterAttempt(current.second)
            val changed = database.update(
                "sms_outbox",
                ContentValues().apply {
                    put("status", if (shouldQueue) "RETRY_PENDING" else "FAILED")
                    put("submission_outcome", "CONFIRMED_FAILURE")
                    put("failure_code", safeCode)
                    put("failure_reason", safeFailureReason(safeCode))
                    put("failed_at", now)
                    if (shouldQueue) put("next_attempt_at", now + (jitterMs?.let { retryDelayMs(current.second, it) } ?: randomizedRetryDelayMs(current.second)))
                    putNull("lease_until")
                    putNull("lease_owner")
                },
                "message_id = ? AND attempt_token = ? AND status = 'SENDING'",
                arrayOf(messageId, token)
            )
            if (changed == 1) {
                database.update("sms_outbox_attempts", ContentValues().apply {
                    put("state", "CONFIRMED_FAILURE")
                    put("failure_code", safeCode)
                    put("completed_at", now)
                }, "attempt_token = ? AND message_id = ? AND state IN ('CLAIMED','SUBMITTING')", arrayOf(token, messageId))
                syncLegacyStatus(database, messageId, "failed", now)
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /** An exception after entering SmsManager is ambiguous and must not trigger replay. */
    fun markSubmissionUnknown(db: DatabaseHelper, job: OutboxMessage, reasonCode: String,
                              now: Long = System.currentTimeMillis()) {
        if (job.attemptToken.isBlank()) return
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val accepted = database.rawQuery(
                """
                SELECT 1 FROM sms_outbox_attempt_parts
                WHERE attempt_token = ? AND message_id = ?
                  AND (send_state = 'SENT' OR delivery_state = 'DELIVERED') LIMIT 1
                """.trimIndent(), arrayOf(job.attemptToken, job.messageId)
            ).use { it.moveToFirst() }
            val outcome = if (accepted) "PARTIAL_UNKNOWN" else "UNKNOWN"
            val changed = database.update(
                "sms_outbox",
                ContentValues().apply {
                    put("status", "FAILED")
                    put("submission_outcome", outcome)
                    put("failure_code", outcome)
                    put("failure_reason", safeFailureReason(outcome))
                    put("failed_at", now)
                    putNull("lease_until")
                    putNull("lease_owner")
                },
                "message_id = ? AND attempt_token = ? AND status = 'SENDING'",
                arrayOf(job.messageId, job.attemptToken)
            )
            if (changed == 1) {
                database.update("sms_outbox_attempts", ContentValues().apply {
                    put("state", outcome)
                    put("failure_code", outcome)
                    put("completed_at", now)
                }, "attempt_token = ? AND message_id = ?", arrayOf(job.attemptToken, job.messageId))
                syncLegacyStatus(database, job.messageId, "failed", now)
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
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
    /** Cancel before transport, only while the caller still owns this exact claim token. */
    fun cancelClaimed(db: DatabaseHelper, messageId: String, reason: String, attemptToken: String): Boolean {
        if (attemptToken.isBlank()) return false
        val now = System.currentTimeMillis()
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val changed = database.update(
                "sms_outbox",
                ContentValues().apply {
                    put("status", "CANCELLED")
                    put("submission_outcome", "CANCELLED")
                    put("failed_at", now)
                    put("failure_code", "BUSINESS_INELIGIBLE")
                    put("failure_reason", safeFailureReason("BUSINESS_INELIGIBLE"))
                    putNull("lease_until")
                    putNull("lease_owner")
                    putNull("attempt_token")
                },
                "message_id = ? AND attempt_token = ? AND status = 'SENDING'",
                arrayOf(messageId, attemptToken)
            )
            if (changed == 1) {
                database.update("sms_outbox_attempts", ContentValues().apply {
                    put("state", "CANCELLED")
                    put("completed_at", now)
                }, "attempt_token = ? AND message_id = ? AND state = 'CLAIMED'", arrayOf(attemptToken, messageId))
                database.delete("sms_outbox_parts", "message_id = ? AND status = 'PENDING'", arrayOf(messageId))
                syncLegacyStatus(database, messageId, "cancelled", now)
            }
            database.setTransactionSuccessful()
            return changed == 1
        } finally {
            database.endTransaction()
        }
    }

    fun prepareParts(
        db: DatabaseHelper,
        messageId: String,
        partsCount: Int,
        attemptToken: String
    ) {
        require(partsCount > 0) { "SMS must contain at least one part" }
        require(attemptToken.isNotBlank()) { "SMS attempt token is required" }
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val ownsLease = database.rawQuery(
                "SELECT 1 FROM sms_outbox WHERE message_id = ? AND attempt_token = ? AND status = 'SENDING' LIMIT 1",
                arrayOf(messageId, attemptToken)
            ).use { it.moveToFirst() }
            check(ownsLease) { "Cannot prepare SMS parts without the active attempt lease" }
            for (index in 0 until partsCount) {
                database.insertWithOnConflict(
                    "sms_outbox_attempt_parts",
                    null,
                    ContentValues().apply {
                        put("attempt_token", attemptToken)
                        put("message_id", messageId)
                        put("part_index", index)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE
                )
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
        db: DatabaseHelper, messageId: String, partIndex: Int, delivered: Boolean,
        success: Boolean, resultCode: String, reason: String = "", attemptToken: String
    ) {
        val token = attemptToken.takeIf { it.isNotBlank() } ?: return
        if (partIndex < 0) return
        val now = System.currentTimeMillis()
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val parent = database.rawQuery(
                "SELECT status, attempt_token, submission_outcome, attempt_count FROM sms_outbox WHERE message_id = ? LIMIT 1",
                arrayOf(messageId)
            ).use { c ->
                if (!c.moveToFirst()) null else ParentAttempt(c.getString(0).orEmpty(), c.getString(1).orEmpty(),
                    c.getString(2).orEmpty(), c.getInt(3))
            }
            if (parent == null || parent.token != token ||
                parent.outcome in setOf("UNKNOWN", "PARTIAL_UNKNOWN", "CANCELLED", "LOCAL_RETRY")) {
                recordLateCallback(database, messageId, token, partIndex,
                    if (delivered) "DELIVERED" else "SENT", resultCode, now)
                database.setTransactionSuccessful()
                return
            }
            val exists = database.rawQuery(
                "SELECT 1 FROM sms_outbox_attempt_parts WHERE attempt_token = ? AND message_id = ? AND part_index = ? LIMIT 1",
                arrayOf(token, messageId, partIndex.toString())
            ).use { it.moveToFirst() }
            if (!exists) {
                recordLateCallback(database, messageId, token, partIndex,
                    if (delivered) "DELIVERED" else "SENT", resultCode, now)
                database.setTransactionSuccessful()
                return
            }
            val code = resultCode.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(80).ifBlank { "UNKNOWN_RESULT" }
            val values = ContentValues()
            if (delivered) {
                values.put("send_state", "SENT")
                values.put("delivery_state", if (success) "DELIVERED" else "UNCONFIRMED")
                values.put("sent_at", now)
                if (success) values.put("delivered_at", now)
                values.put("delivery_result_code", code)
                if (!success) values.put("failure_reason", safeFailureReason(code))
            } else if (success) {
                values.put("send_state", "SENT")
                values.put("sent_at", now)
                values.put("send_result_code", code)
            } else {
                values.put("send_state", "FAILED")
                values.put("send_result_code", code)
                values.put("failure_reason", safeFailureReason(code))
            }
            val where = when {
                delivered && success -> "attempt_token = ? AND message_id = ? AND part_index = ?"
                delivered -> "attempt_token = ? AND message_id = ? AND part_index = ? AND delivery_state <> 'DELIVERED'"
                else -> "attempt_token = ? AND message_id = ? AND part_index = ? AND send_state = 'PENDING'"
            }
            database.update("sms_outbox_attempt_parts", values, where, arrayOf(token, messageId, partIndex.toString()))
            val projection = database.rawQuery(
                "SELECT send_state, delivery_state FROM sms_outbox_attempt_parts WHERE attempt_token = ? AND message_id = ? AND part_index = ?",
                arrayOf(token, messageId, partIndex.toString())
            ).use { c -> if (!c.moveToFirst()) null else c.getString(0) to c.getString(1) }
            if (projection != null) {
                val status = when {
                    projection.second == "DELIVERED" -> "DELIVERED"
                    projection.first == "SENT" -> "SENT"
                    projection.first == "FAILED" -> "FAILED"
                    else -> "PENDING"
                }
                database.update("sms_outbox_parts", ContentValues().apply {
                    put("status", status)
                    if (status == "SENT" || status == "DELIVERED") put("sent_at", now)
                    if (status == "DELIVERED") put("delivered_at", now)
                    if (status == "FAILED") {
                        put("failure_code", code)
                        put("failure_reason", safeFailureReason(code))
                    }
                }, "message_id = ? AND part_index = ?", arrayOf(messageId, partIndex.toString()))
            }

            val summary = database.rawQuery(
                """
                SELECT COUNT(*),
                       SUM(CASE WHEN send_state = 'PENDING' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN send_state = 'FAILED' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN send_state = 'SENT' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN delivery_state = 'DELIVERED' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN delivery_state = 'UNCONFIRMED' THEN 1 ELSE 0 END)
                FROM sms_outbox_attempt_parts WHERE attempt_token = ? AND message_id = ?
                """.trimIndent(), arrayOf(token, messageId)
            ).use { c ->
                check(c.moveToFirst())
                PartSummary(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getInt(5))
            }
            if (summary.total == 0) {
                database.setTransactionSuccessful()
                return
            }
            if (summary.pendingSend == 0 && summary.failedSend > 0) {
                if (summary.accepted > 0 || summary.delivered > 0) {
                    finishAttempt(database, messageId, token, now, "FAILED", "PARTIAL_UNKNOWN", "PARTIAL_UNKNOWN", "PARTIAL_UNKNOWN")
                } else {
                    val retry = shouldRetryAfterAttempt(parent.attemptCount)
                    val next = if (retry) now + randomizedRetryDelayMs(parent.attemptCount) else null
                    finishAttempt(database, messageId, token, now,
                        if (retry) "RETRY_PENDING" else "FAILED", "CONFIRMED_FAILURE", code, code, next)
                }
            } else if (summary.pendingSend == 0 && summary.failedSend == 0) {
                when {
                    summary.delivered == summary.total ->
                        finishAttempt(database, messageId, token, now, "DELIVERED", "DELIVERED", "OK", "")
                    summary.unconfirmedDelivery > 0 ->
                        finishAttempt(database, messageId, token, now, "FAILED", "DELIVERY_UNCONFIRMED",
                            "DELIVERY_UNCONFIRMED", "DELIVERY_UNCONFIRMED")
                    else -> {
                        database.update("sms_outbox", ContentValues().apply {
                            put("status", "DELIVERY_PENDING")
                            put("submission_outcome", "SUBMITTED")
                            put("sent_at", now)
                            putNull("lease_until")
                            putNull("lease_owner")
                        }, "message_id = ? AND attempt_token = ? AND status IN ('SENDING','DELIVERY_PENDING','RETRY_PENDING')",
                            arrayOf(messageId, token))
                        database.update("sms_outbox_attempts", ContentValues().apply { put("state", "SUBMITTED") },
                            "attempt_token = ? AND state IN ('SUBMITTING','SUBMITTED')", arrayOf(token))
                        syncLegacyStatus(database, messageId, "sent", now)
                    }
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private data class ParentAttempt(val status: String, val token: String, val outcome: String, val attemptCount: Int)
    private data class PartSummary(val total: Int, val pendingSend: Int, val failedSend: Int,
                                   val accepted: Int, val delivered: Int, val unconfirmedDelivery: Int)

    private fun finishAttempt(
        database: SQLiteDatabase, messageId: String, token: String, now: Long,
        status: String, outcome: String, failureCode: String, failureReason: String,
        nextAttemptAt: Long? = null
    ) {
        val values = ContentValues().apply {
            put("status", status)
            put("submission_outcome", outcome)
            if (status == "DELIVERED") put("delivered_at", now) else put("failed_at", now)
            if (outcome == "DELIVERED") {
                putNull("failure_code"); putNull("failure_reason")
            } else {
                put("failure_code", failureCode.take(80))
                put("failure_reason", safeFailureReason(failureReason))
            }
            if (nextAttemptAt != null) put("next_attempt_at", nextAttemptAt)
            putNull("lease_until"); putNull("lease_owner")
        }
        val changed = database.update("sms_outbox", values,
            "message_id = ? AND attempt_token = ? AND status IN ('SENDING','DELIVERY_PENDING','RETRY_PENDING','FAILED')",
            arrayOf(messageId, token))
        if (changed == 1) {
            val state = when (outcome) {
                "DELIVERED" -> "DELIVERED"
                "PARTIAL_UNKNOWN" -> "PARTIAL_UNKNOWN"
                "DELIVERY_UNCONFIRMED" -> "DELIVERY_UNCONFIRMED"
                else -> "CONFIRMED_FAILURE"
            }
            database.update("sms_outbox_attempts", ContentValues().apply {
                put("state", state)
                if (failureCode.isNotBlank()) put("failure_code", failureCode.take(80))
                put("completed_at", now)
            }, "attempt_token = ? AND message_id = ?", arrayOf(token, messageId))
            syncLegacyStatus(database, messageId, if (status == "DELIVERED") "delivered" else "failed", now)
        }
    }

    private fun recordLateCallback(
        database: SQLiteDatabase, messageId: String, token: String, partIndex: Int,
        kind: String, resultCode: String, now: Long
    ) {
        database.insertWithOnConflict("sms_outbox_late_callbacks", null, ContentValues().apply {
            put("message_id", messageId)
            put("attempt_token", token)
            put("part_index", partIndex)
            put("callback_kind", kind)
            put("result_code", resultCode.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(80))
            put("received_at", now)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** Expired SENDING leases are unknown outcomes, never eligible for automatic replay. */
    fun recoverStuck(db: DatabaseHelper, now: Long = System.currentTimeMillis()): List<String> {
        val database = db.writableDatabase
        val recovered = mutableListOf<String>()
        database.beginTransaction()
        try {
            val expired = mutableListOf<Pair<String, String>>()
            database.rawQuery(
                "SELECT message_id, COALESCE(attempt_token,'') FROM sms_outbox " +
                    "WHERE status = 'SENDING' AND (lease_until IS NULL OR lease_until <= ?)",
                arrayOf(now.toString())
            ).use { c -> while (c.moveToNext()) expired += c.getString(0) to c.getString(1) }
            expired.forEach { (messageId, token) ->
                val accepted = token.isNotBlank() && database.rawQuery(
                    """
                    SELECT 1 FROM sms_outbox_attempt_parts
                    WHERE attempt_token = ? AND message_id = ?
                      AND (send_state = 'SENT' OR delivery_state = 'DELIVERED') LIMIT 1
                    """.trimIndent(), arrayOf(token, messageId)
                ).use { it.moveToFirst() }
                val outcome = if (accepted) "PARTIAL_UNKNOWN" else "UNKNOWN"
                val where = if (token.isBlank())
                    "message_id = ? AND status = 'SENDING' AND (lease_until IS NULL OR lease_until <= ?) AND attempt_token IS NULL"
                else "message_id = ? AND status = 'SENDING' AND (lease_until IS NULL OR lease_until <= ?) AND attempt_token = ?"
                val args = if (token.isBlank()) arrayOf(messageId, now.toString()) else arrayOf(messageId, now.toString(), token)
                val changed = database.update("sms_outbox", ContentValues().apply {
                    put("status", "FAILED")
                    put("submission_outcome", outcome)
                    put("failure_code", outcome)
                    put("failure_reason", safeFailureReason(outcome))
                    put("failed_at", now)
                    putNull("lease_until")
                    putNull("lease_owner")
                }, where, args)
                if (changed == 1) {
                    if (token.isNotBlank()) database.update("sms_outbox_attempts", ContentValues().apply {
                        put("state", outcome); put("failure_code", outcome); put("completed_at", now)
                    }, "attempt_token = ? AND message_id = ?", arrayOf(token, messageId))
                    syncLegacyStatus(database, messageId, "failed", now)
                    recovered += messageId
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return recovered
    }

    /** Missing delivery receipts are unconfirmed outcomes, not proof of submission failure. */
    fun failDeliveryTimeouts(db: DatabaseHelper, timeoutMs: Long = 15 * 60 * 1000L): List<String> {
        require(timeoutMs > 0L)
        val now = System.currentTimeMillis()
        val cutoff = now - timeoutMs
        val database = db.writableDatabase
        val ids = mutableListOf<Pair<String, String>>()
        database.beginTransaction()
        try {
            database.rawQuery(
                """
                SELECT message_id, COALESCE(attempt_token,'') FROM sms_outbox
                WHERE status = 'DELIVERY_PENDING' AND sent_at IS NOT NULL AND sent_at < ?
                  AND COALESCE(submission_outcome,'') <> 'DELIVERY_UNCONFIRMED'
                """.trimIndent(), arrayOf(cutoff.toString())
            ).use { c -> while (c.moveToNext()) ids += c.getString(0) to c.getString(1) }
            ids.forEach { (messageId, token) ->
                val changed = database.update("sms_outbox", ContentValues().apply {
                    put("status", "FAILED")
                    put("submission_outcome", "DELIVERY_UNCONFIRMED")
                    put("failed_at", now)
                    put("failure_code", "DELIVERY_UNCONFIRMED")
                    put("failure_reason", safeFailureReason("DELIVERY_UNCONFIRMED"))
                    putNull("lease_until")
                    putNull("lease_owner")
                }, "message_id = ? AND status = 'DELIVERY_PENDING' AND attempt_token = ?",
                    arrayOf(messageId, token))
                if (changed == 1) {
                    database.update("sms_outbox_attempts", ContentValues().apply {
                        put("state", "DELIVERY_UNCONFIRMED")
                        put("failure_code", "DELIVERY_UNCONFIRMED")
                        put("completed_at", now)
                    }, "attempt_token = ? AND message_id = ?", arrayOf(token, messageId))
                    syncLegacyStatus(database, messageId, "failed", now)
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        return ids.map { it.first }
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
                put("submission_outcome", "NONE")
                putNull("attempt_token")
                putNull("lease_until")
                putNull("lease_owner")
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

    private const val DEFAULT_LEASE_DURATION_MS = 10 * 60 * 1000L
    private const val MAX_LEASE_DURATION_MS = 30 * 60 * 1000L
    private const val LOCAL_RETRY_DELAY_MS = 5_000L

    fun retryDelayMs(attemptNumber: Int, jitterMs: Long = 0L): Long {
        val exponent = (attemptNumber - 1).coerceIn(0, 6)
        val base = (30_000L shl exponent).coerceAtMost(60 * 60 * 1000L)
        val spread = base / 5
        return (base + jitterMs.coerceIn(-spread, spread)).coerceAtLeast(1_000L)
    }

    private fun randomizedRetryDelayMs(attemptNumber: Int): Long {
        val base = retryDelayMs(attemptNumber, 0L)
        val spread = (base / 5).coerceAtLeast(1L).coerceAtMost(1_000_000L).toInt()
        val offset = SecureRandom().nextInt(spread * 2 + 1) - spread
        return retryDelayMs(attemptNumber, offset.toLong())
    }

    private fun safeFailureReason(value: String): String {
        val code = value.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(80)
        return when (code) {
            "SEND_SMS_PERMISSION" -> "SMS permission is not available"
            "RESULT_ERROR_NO_SERVICE" -> "Cellular service is unavailable"
            "RESULT_ERROR_RADIO_OFF" -> "The cellular radio is unavailable"
            "RESULT_ERROR_NULL_PDU" -> "The SMS payload could not be encoded"
            "DEBT_REVALIDATION_ERROR" -> "Business eligibility could not be revalidated"
            "PARTIAL_UNKNOWN" -> "Some message parts may have reached the carrier"
            "DELIVERY_UNCONFIRMED" -> "Submission was accepted but delivery was not confirmed"
            "UNKNOWN" -> "The carrier submission outcome is unknown"
            else -> "SMS transport returned a failure result"
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
