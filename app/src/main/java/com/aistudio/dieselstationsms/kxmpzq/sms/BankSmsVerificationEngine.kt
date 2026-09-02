package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.ContentValues
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import com.aistudio.dieselstationsms.kxmpzq.utils.PhoneUtils
import org.json.JSONObject
import java.util.UUID

class BankSmsVerificationEngine(private val db: DatabaseHelper, private val parser: BankMessageParser = BankMessageParser()) {
    fun verifyAndRecord(senderPhone: String, rawMessage: String, receivedAt: Long = System.currentTimeMillis()): String {
        val phone = PhoneUtils.normalize(senderPhone) ?: senderPhone.trim().takeIf { it.matches(Regex("[0-9+]{3,20}")) } ?: return "REJECTED_INVALID_SENDER"
        val identity = db.readableDatabase.rawQuery("SELECT bank_id, bank_account_id, verified, active FROM sms_sender_identities WHERE sender_phone = ? AND source_type = 'BANK' LIMIT 1", arrayOf(phone)).use { c ->
            if (!c.moveToFirst()) null else arrayOf(c.getString(0), c.getString(1), c.getInt(2).toString(), c.getInt(3).toString())
        } ?: return "REJECTED_UNTRUSTED_SENDER"
        if (identity[2] != "1" || identity[3] != "1") return "REJECTED_UNVERIFIED_SENDER"
        val parsed = parser.parse(phone, rawMessage, receivedAt) ?: return "REJECTED_UNPARSEABLE"
        if (parsed.bankId != identity[0]) return "REJECTED_BANK_MISMATCH"
        val candidate = parsed.copy(bankAccountId = identity[1])
        val values = ContentValues().apply {
            put("payment_event_id", "PE-${UUID.randomUUID()}")
            put("idempotency_key", candidate.fingerprint)
            put("fingerprint", candidate.fingerprint)
            put("phone", candidate.senderPhone)
            put("bank_id", candidate.bankId)
            put("bank_account_id", candidate.bankAccountId)
            put("institution", candidate.bankId)
            put("amount", candidate.amount)
            put("currency", candidate.currency)
            put("sender_name", candidate.senderName)
            put("balance", candidate.balance)
            put("reference", candidate.reference)
            put("event_timestamp", candidate.eventTimestamp)
            put("raw_message", rawMessage.take(4000))
            put("status", "PARSED")
            put("created_at", System.currentTimeMillis())
            put("updated_at", System.currentTimeMillis())
        }
        val inserted = db.writableDatabase.insertWithOnConflict("sms_payment_events", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
        return if (inserted == -1L) "DUPLICATE" else "PARSED"
    }

    fun verifyAndMatch(senderPhone: String, rawMessage: String, receivedAt: Long = System.currentTimeMillis()): PaymentMatch {
        val phone = PhoneUtils.normalize(senderPhone) ?: senderPhone.trim().takeIf { it.matches(Regex("[0-9+]{3,20}")) } ?: return PaymentMatch(false, false, null, "invalid_sender")
        val identity = db.readableDatabase.rawQuery("SELECT bank_id, bank_account_id, verified, active FROM sms_sender_identities WHERE sender_phone = ? AND source_type = 'BANK' LIMIT 1", arrayOf(phone)).use { c -> if (!c.moveToFirst()) null else Triple(c.getString(0), c.getString(1), c.getInt(2) == 1 && c.getInt(3) == 1) } ?: return PaymentMatch(false, false, null, "untrusted_bank_sender")
        if (!identity.third) return PaymentMatch(false, false, null, "unverified_bank_sender")
        val parsed = parser.parse(phone, rawMessage, receivedAt) ?: return PaymentMatch(false, false, null, "unparseable_bank_message")
        val candidate = parsed.copy(bankAccountId = identity.second)
        val orders = mutableListOf<BankPaymentMatchingEngine.CandidateOrder>()
        db.readableDatabase.rawQuery("SELECT o.order_id, o.total_amount, o.phone, COALESCE(p.commercial_name_ar, p.commercial_name, p.legal_name), o.created_at, o.expires_at, o.quote_id FROM fuel_orders o LEFT JOIN parties p ON p.id = o.customer_id WHERE o.status = 'AWAITING_PAYMENT' AND o.expires_at >= ?", arrayOf(System.currentTimeMillis().toString())).use { c ->
            while (c.moveToNext()) orders += BankPaymentMatchingEngine.CandidateOrder(c.getString(0), c.getDouble(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5), c.getString(6))
        }
        val result = BankPaymentMatchingEngine().match(candidate, orders)
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            val eventInserted = database.insertWithOnConflict("sms_payment_events", null, ContentValues().apply { put("payment_event_id", "PE-${UUID.randomUUID()}"); put("idempotency_key", candidate.fingerprint); put("fingerprint", candidate.fingerprint); put("phone", candidate.senderPhone); put("bank_id", candidate.bankId); put("bank_account_id", candidate.bankAccountId); put("institution", candidate.bankId); put("amount", candidate.amount); put("currency", candidate.currency); put("sender_name", candidate.senderName); put("balance", candidate.balance); put("reference", candidate.reference); put("event_timestamp", candidate.eventTimestamp); put("raw_message", rawMessage.take(4000)); put("status", "PARSED"); put("created_at", System.currentTimeMillis()); put("updated_at", System.currentTimeMillis()) }, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
            if (eventInserted == -1L) { database.setTransactionSuccessful(); return PaymentMatch(false, false, null, "duplicate_payment_fingerprint") }
            database.insertWithOnConflict("fuel_payment_matches", null, ContentValues().apply { put("match_id", "FM-${UUID.randomUUID()}"); put("order_id", result.orderId); put("fingerprint", candidate.fingerprint); put("amount", candidate.amount); put("bank_id", candidate.bankId); put("bank_account_id", candidate.bankAccountId); put("status", if (result.matched) "VERIFIED" else if (result.reviewRequired) "REVIEW_REQUIRED" else "REJECTED"); put("reason", result.reason); put("created_at", System.currentTimeMillis()) }, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
            if (result.orderId != null && result.matched) {
                database.update("fuel_orders", ContentValues().apply { put("status", "PAYMENT_VERIFIED"); put("payment_status", "VERIFIED") }, "order_id = ? AND status = 'AWAITING_PAYMENT'", arrayOf(result.orderId))
                database.update("sms_payment_events", ContentValues().apply { put("matched_order_key", result.orderId); put("status", "VERIFIED"); put("updated_at", System.currentTimeMillis()) }, "fingerprint = ?", arrayOf(candidate.fingerprint))
            } else if (result.reviewRequired) {
                database.update("sms_payment_events", ContentValues().apply { put("status", "SUSPICIOUS"); put("updated_at", System.currentTimeMillis()) }, "fingerprint = ?", arrayOf(candidate.fingerprint))
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        return result
    }

    fun registerIdentity(senderPhone: String, bankId: String, bankAccountId: String, method: String): Boolean {
        val phone = PhoneUtils.normalize(senderPhone) ?: senderPhone.trim().takeIf { it.matches(Regex("[0-9+]{3,20}")) } ?: return false
        require(bankId.isNotBlank() && bankAccountId.isNotBlank())
        val now = System.currentTimeMillis()
        val values = ContentValues().apply { put("sender_phone", phone); put("bank_id", bankId.trim()); put("bank_account_id", bankAccountId.trim()); put("source_type", "BANK"); put("verified", 1); put("verification_method", method.take(80)); put("active", 1); put("created_at", now); put("updated_at", now) }
        return db.writableDatabase.insertWithOnConflict("sms_sender_identities", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE) != -1L
    }
}
