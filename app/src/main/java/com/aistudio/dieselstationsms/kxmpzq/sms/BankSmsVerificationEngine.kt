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

    fun registerIdentity(senderPhone: String, bankId: String, bankAccountId: String, method: String): Boolean {
        val phone = PhoneUtils.normalize(senderPhone) ?: senderPhone.trim().takeIf { it.matches(Regex("[0-9+]{3,20}")) } ?: return false
        require(bankId.isNotBlank() && bankAccountId.isNotBlank())
        val now = System.currentTimeMillis()
        val values = ContentValues().apply { put("sender_phone", phone); put("bank_id", bankId.trim()); put("bank_account_id", bankAccountId.trim()); put("source_type", "BANK"); put("verified", 1); put("verification_method", method.take(80)); put("active", 1); put("created_at", now); put("updated_at", now) }
        return db.writableDatabase.insertWithOnConflict("sms_sender_identities", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE) != -1L
    }
}
