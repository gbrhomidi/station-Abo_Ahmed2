package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/**
 * Additive Stage 6 schema for durable SMS attempt ownership and callback provenance.
 * Must execute inside DatabaseHelper's existing schema-upgrade transaction.
 */
internal object SmsOutboxSchema {
    fun ensureSchema(db: SQLiteDatabase) {
        check(db.inTransaction()) { "SMS outbox schema must be applied in the database migration transaction" }
        check(tableExists(db, "sms_outbox")) { "sms_outbox must exist before attempt schema migration" }
        check(tableExists(db, "sms_outbox_parts")) { "sms_outbox_parts must exist before attempt schema migration" }

        ensureColumn(db, "sms_outbox", "claimed_at", "INTEGER")
        ensureColumn(db, "sms_outbox", "lease_until", "INTEGER")
        ensureColumn(db, "sms_outbox", "lease_owner", "TEXT")
        ensureColumn(db, "sms_outbox", "attempt_token", "TEXT")
        ensureColumn(db, "sms_outbox", "claim_generation", "INTEGER NOT NULL DEFAULT 0")
        ensureColumn(db, "sms_outbox", "claim_count", "INTEGER NOT NULL DEFAULT 0")
        ensureColumn(db, "sms_outbox", "submission_outcome", "TEXT NOT NULL DEFAULT 'NONE'")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_outbox_attempts (
                attempt_token TEXT PRIMARY KEY NOT NULL,
                message_id TEXT NOT NULL,
                attempt_number INTEGER NOT NULL DEFAULT 0,
                claim_generation INTEGER NOT NULL,
                lease_owner TEXT NOT NULL,
                claimed_at INTEGER NOT NULL,
                lease_until INTEGER NOT NULL,
                started_at INTEGER,
                completed_at INTEGER,
                state TEXT NOT NULL DEFAULT 'CLAIMED'
                    CHECK(state IN ('CLAIMED','SUBMITTING','SUBMITTED','DELIVERED',
                                    'CONFIRMED_FAILURE','UNKNOWN','PARTIAL_UNKNOWN',
                                    'DELIVERY_UNCONFIRMED','CANCELLED','LOCAL_RETRY')),
                failure_code TEXT,
                FOREIGN KEY(message_id) REFERENCES sms_outbox(message_id) ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_outbox_attempts_message ON sms_outbox_attempts(message_id, claimed_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_outbox_attempts_lease ON sms_outbox_attempts(state, lease_until)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_outbox_attempt_parts (
                attempt_token TEXT NOT NULL,
                message_id TEXT NOT NULL,
                part_index INTEGER NOT NULL CHECK(part_index >= 0),
                send_state TEXT NOT NULL DEFAULT 'PENDING'
                    CHECK(send_state IN ('PENDING','SENT','FAILED')),
                delivery_state TEXT NOT NULL DEFAULT 'PENDING'
                    CHECK(delivery_state IN ('PENDING','DELIVERED','UNCONFIRMED')),
                sent_at INTEGER,
                delivered_at INTEGER,
                send_result_code TEXT,
                delivery_result_code TEXT,
                failure_reason TEXT,
                PRIMARY KEY(attempt_token, part_index),
                FOREIGN KEY(attempt_token) REFERENCES sms_outbox_attempts(attempt_token) ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_outbox_attempt_parts_message ON sms_outbox_attempt_parts(message_id, attempt_token)")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sms_outbox_late_callbacks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                message_id TEXT NOT NULL,
                attempt_token TEXT NOT NULL,
                part_index INTEGER NOT NULL,
                callback_kind TEXT NOT NULL,
                result_code TEXT NOT NULL,
                received_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_outbox_late_callbacks_message ON sms_outbox_late_callbacks(message_id, received_at)")

        // A process upgrade cannot prove whether a legacy SENDING message reached the modem.
        // Retain it as a terminal, explicitly unknown result instead of replaying it.
        val stranded = mutableListOf<String>()
        db.rawQuery(
            "SELECT message_id FROM sms_outbox WHERE status = 'SENDING' AND attempt_token IS NULL",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) stranded += cursor.getString(0)
        }
        stranded.forEach { messageId ->
            db.update(
                "sms_outbox",
                ContentValues().apply {
                    put("status", "FAILED")
                    put("submission_outcome", "UNKNOWN")
                    put("failure_code", "SUBMISSION_UNKNOWN_AFTER_UPGRADE")
                    put("failure_reason", "Transport outcome cannot be established after application upgrade")
                    put("failed_at", System.currentTimeMillis())
                    putNull("lease_until")
                    putNull("lease_owner")
                },
                "message_id = ? AND status = 'SENDING' AND attempt_token IS NULL",
                arrayOf(messageId)
            )
            db.update(
                "sms_messages",
                ContentValues().apply {
                    put("status", "failed")
                    put("updated_at", System.currentTimeMillis().toString())
                },
                "uuid = ?",
                arrayOf(messageId)
            )
        }
    }

    private fun ensureColumn(db: SQLiteDatabase, table: String, column: String, definition: String) {
        if (!tableExists(db, table)) return
        val exists = db.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            var found = false
            if (cursor.moveToFirst()) {
                do {
                    if (cursor.getString(nameIndex).equals(column, ignoreCase = true)) {
                        found = true
                        break
                    }
                } while (cursor.moveToNext())
                }
            found
        }
        if (!exists) db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf(table)
        ).use { it.moveToFirst() }
}
