package com.aistudio.dieselstationsms.kxmpzq.sms

import android.content.ContentValues
import com.aistudio.dieselstationsms.kxmpzq.DatabaseHelper
import java.security.MessageDigest
import java.util.UUID

object SmsPaymentParser {
    data class ParsedPayment(
        val institution: String,
        val amount: Double,
        val creditedAmount: Double?,
        val senderName: String,
        val receiverName: String,
        val reference: String,
        val currency: String,
        val rawMessage: String
    )

    private val firstAmount = Regex("(?i)(?:لكم|المبلغ|مبلغ)\\s*[:：]?\\s*([0-9][0-9,\\. ]*)")
    private val sender = Regex("(?i)(?:من حساب|من|sender)\\s*[:：]?\\s*([^\\n]+)")
    private val receiver = Regex("(?i)(?:إلى|الى|لكم)\\s*[:：]?\\s*([^\\n]+)")
    private val reference = Regex("(?i)(?:مرجع|reference|رقم العملية)\\s*[:：]?\\s*([^\\n]+)")
    private val amountToken = Regex("[0-9][0-9,\\. ]*")

    fun parse(raw: String): ParsedPayment? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val normalized = SmsMessageNormalizer.normalizeForMatch(text)
        if (!(normalized.contains("من حساب") || normalized.contains("لكم") || normalized.contains("تحويل"))) return null

        val amountMatches = firstAmount.findAll(text).toList()
        val amount = amountMatches.firstOrNull()?.groupValues?.getOrNull(1)?.let(::parseAmount) ?: return null
        val credited = amountMatches.getOrNull(1)?.groupValues?.getOrNull(1)?.let(::parseAmount)
        val senderName = sender.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val receiverLine = receiver.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val receiverName = receiverLine.replace(amountToken, "").trim(' ', ':', '،')
        val institution = text.lineSequence().firstOrNull()?.substringBefore("لكم")?.trim().orEmpty().ifBlank { "غير محددة" }
        val ref = reference.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val currency = when {
            normalized.contains("ريال") || normalized.contains("ر ي") || normalized.contains("ري") -> "YER"
            else -> "UNKNOWN"
        }
        return ParsedPayment(institution, amount, credited, senderName, receiverName, ref, currency, text)
    }

    private fun parseAmount(value: String): Double? = value.replace(",", "").replace(" ", "").toDoubleOrNull()
}

class SmsPaymentService(private val db: DatabaseHelper) {
    enum class ResultStatus { PARSED, DUPLICATE, REJECTED }
    data class RecordResult(val status: ResultStatus, val eventId: String? = null, val payment: SmsPaymentParser.ParsedPayment? = null)

    fun recordIncoming(phone: String, partyId: Int?, rawMessage: String): RecordResult {
        val parsed = SmsPaymentParser.parse(rawMessage) ?: return RecordResult(ResultStatus.REJECTED)
        val now = System.currentTimeMillis()
        val idempotencyKey = sha256(listOf(phone, parsed.institution, parsed.amount, parsed.senderName, parsed.reference).joinToString("|"))
        val eventId = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("payment_event_id", eventId)
            put("idempotency_key", idempotencyKey)
            put("phone", phone)
            put("party_id", partyId)
            put("institution", parsed.institution.take(100))
            put("amount", parsed.amount)
            put("currency", parsed.currency)
            put("sender_name", parsed.senderName.take(200))
            put("receiver_name", parsed.receiverName.take(200))
            put("reference", parsed.reference.take(100))
            put("raw_message", parsed.rawMessage.take(2000))
            put("status", "PARSED")
            put("created_at", now)
            put("updated_at", now)
        }
        val inserted = db.writableDatabase.insertWithOnConflict(
            "sms_payment_events",
            null,
            values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
        )
        return if (inserted == -1L) RecordResult(ResultStatus.DUPLICATE, eventId, parsed)
        else RecordResult(ResultStatus.PARSED, eventId, parsed)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
