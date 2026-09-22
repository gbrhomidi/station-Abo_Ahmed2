package com.aistudio.dieselstationsms.kxmpzq.sms

import com.aistudio.dieselstationsms.kxmpzq.utils.PhoneUtils
import java.util.Locale

interface BankMessageProfile {
    val bankId: String
    fun matches(message: String): Boolean
    fun parse(message: String): ParsedBankMessage?
}

data class ParsedBankMessage(
    val amount: Double,
    val currency: String = "YER",
    val senderName: String? = null,
    val balance: Double? = null,
    val reference: String? = null
)

/** محللات مستقلة لكل بنك؛ إضافة بنك جديد لا تغيّر محللات البنوك الأخرى. */
class AlKuraimiBankMessageProfile : BankMessageProfile {
    override val bankId: String = "ALKURAIMI"
    override fun matches(message: String): Boolean = message.lowercase(Locale.ROOT).contains("كريمي") || message.lowercase(Locale.ROOT).contains("alkuraimi") || Regex("(?:مبلغ|amount|received|استلام)\\s*[:=]?\\s*[0-9]").containsMatchIn(message)
    override fun parse(message: String): ParsedBankMessage? {
        val amount = Regex("(?:مبلغ|amount|received|استلام)\\s*[:=]?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE).find(message)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
            ?: Regex("([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(?:ريال|yer|YER)", RegexOption.IGNORE_CASE).find(message)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
            ?: return null
        val reference = Regex("(?:مرجع|reference|ref)\\s*[:#=]?\\s*([A-Za-z0-9-]+)", RegexOption.IGNORE_CASE).find(message)?.groupValues?.get(1)
        val balance = Regex("(?:الرصيد|balance)\\s*[:=]?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE).find(message)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
        val sender = Regex("(?:من|from|اسم)\\s*[:=]?\\s*([\\p{L} .'-]{2,60})", RegexOption.IGNORE_CASE).find(message)?.groupValues?.get(1)?.trim()
        return ParsedBankMessage(amount, senderName = sender, balance = balance, reference = reference)
    }
}

class BankMessageParser(private val profiles: List<BankMessageProfile> = listOf(AlKuraimiBankMessageProfile())) {
    fun parse(senderPhone: String, rawMessage: String, receivedAt: Long = System.currentTimeMillis()): BankPaymentCandidate? {
        val normalizedPhone = PhoneUtils.normalize(senderPhone) ?: senderPhone.trim().takeIf { it.matches(Regex("[0-9+]{3,20}")) } ?: return null
        val profile = profiles.firstOrNull { it.matches(rawMessage) } ?: return null
        val parsed = profile.parse(rawMessage) ?: return null
        return BankPaymentCandidate(
            fingerprint = BankMessageFingerprint.of(normalizedPhone, rawMessage, receivedAt),
            senderPhone = normalizedPhone,
            bankId = profile.bankId,
            bankAccountId = "",
            amount = parsed.amount,
            currency = parsed.currency,
            senderName = parsed.senderName,
            balance = parsed.balance,
            reference = parsed.reference,
            eventTimestamp = receivedAt
        )
    }
}
