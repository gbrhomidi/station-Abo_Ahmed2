package com.aistudio.dieselstationsms.kxmpzq.sms

/** حالات الطلب التجارية؛ لا يجوز الانتقال بينها إلا عبر FuelOrderRepository. */
enum class FuelOrderStatus {
    DRAFT, QUOTED, AWAITING_PAYMENT, PAYMENT_VERIFIED, AWAITING_DELIVERY,
    READY_FOR_DISPATCH, DRIVER_ASSIGNED, OUT_FOR_DELIVERY, DELIVERED, COMPLETED,
    CANCELLED, EXPIRED, PAYMENT_FAILED, PAYMENT_MISMATCH, DELIVERY_FAILED
}

data class FuelOrderDraft(
    val orderId: String,
    val customerId: Long,
    val phone: String,
    val fuelTypeId: Long,
    val quantity: Double,
    val unit: String,
    val liters: Double,
    val unitPrice: Double,
    val totalAmount: Double,
    val quoteId: String? = null,
    val paymentMode: String = "PREPAID",
    val paymentStatus: String = "PENDING",
    val deliveryLocation: String? = null,
    val deliveryLocationOriginal: String? = null,
    val requestedDeliveryAt: Long? = null,
    val driverId: Long? = null,
    val vehicleId: Long? = null,
    val status: FuelOrderStatus = FuelOrderStatus.DRAFT,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = createdAt + 30 * 60 * 1000L,
    val idempotencyKey: String
)

data class FuelQuote(
    val quoteId: String,
    val orderId: String,
    val fuelTypeId: Long,
    val quantity: Double,
    val liters: Double,
    val unitPrice: Double,
    val discount: Double,
    val deliveryFee: Double,
    val total: Double,
    val currency: String,
    val priceVersion: String,
    val expiresAt: Long,
    val createdAt: Long = System.currentTimeMillis()
)

data class CreditDecision(val eligible: Boolean, val availableCredit: Double, val reason: String)

data class BankPaymentCandidate(
    val fingerprint: String,
    val senderPhone: String,
    val bankId: String,
    val bankAccountId: String,
    val amount: Double,
    val currency: String,
    val senderName: String? = null,
    val balance: Double? = null,
    val reference: String? = null,
    val eventTimestamp: Long? = null
)

data class PaymentMatch(val matched: Boolean, val reviewRequired: Boolean, val orderId: String?, val reason: String)

object FuelOrderStateMachine {
    private val transitions = mapOf(
        FuelOrderStatus.DRAFT to setOf(FuelOrderStatus.QUOTED, FuelOrderStatus.CANCELLED, FuelOrderStatus.EXPIRED),
        FuelOrderStatus.QUOTED to setOf(FuelOrderStatus.AWAITING_PAYMENT, FuelOrderStatus.PAYMENT_VERIFIED, FuelOrderStatus.CANCELLED, FuelOrderStatus.EXPIRED),
        FuelOrderStatus.AWAITING_PAYMENT to setOf(FuelOrderStatus.PAYMENT_VERIFIED, FuelOrderStatus.PAYMENT_FAILED, FuelOrderStatus.PAYMENT_MISMATCH, FuelOrderStatus.CANCELLED, FuelOrderStatus.EXPIRED),
        FuelOrderStatus.PAYMENT_VERIFIED to setOf(FuelOrderStatus.AWAITING_DELIVERY, FuelOrderStatus.CANCELLED),
        FuelOrderStatus.AWAITING_DELIVERY to setOf(FuelOrderStatus.READY_FOR_DISPATCH, FuelOrderStatus.CANCELLED),
        FuelOrderStatus.READY_FOR_DISPATCH to setOf(FuelOrderStatus.DRIVER_ASSIGNED, FuelOrderStatus.DELIVERY_FAILED, FuelOrderStatus.CANCELLED),
        FuelOrderStatus.DRIVER_ASSIGNED to setOf(FuelOrderStatus.OUT_FOR_DELIVERY, FuelOrderStatus.DELIVERY_FAILED, FuelOrderStatus.CANCELLED),
        FuelOrderStatus.OUT_FOR_DELIVERY to setOf(FuelOrderStatus.DELIVERED, FuelOrderStatus.DELIVERY_FAILED),
        FuelOrderStatus.DELIVERED to setOf(FuelOrderStatus.COMPLETED),
        FuelOrderStatus.COMPLETED to emptySet(),
        FuelOrderStatus.CANCELLED to emptySet(),
        FuelOrderStatus.EXPIRED to emptySet(),
        FuelOrderStatus.PAYMENT_FAILED to setOf(FuelOrderStatus.AWAITING_PAYMENT, FuelOrderStatus.CANCELLED),
        FuelOrderStatus.PAYMENT_MISMATCH to setOf(FuelOrderStatus.AWAITING_PAYMENT, FuelOrderStatus.CANCELLED),
        FuelOrderStatus.DELIVERY_FAILED to setOf(FuelOrderStatus.READY_FOR_DISPATCH, FuelOrderStatus.CANCELLED)
    )

    fun canTransition(from: FuelOrderStatus, to: FuelOrderStatus): Boolean =
        from == to || transitions[from].orEmpty().contains(to)

    fun requireTransition(from: FuelOrderStatus, to: FuelOrderStatus) {
        require(canTransition(from, to)) { "Invalid FuelOrder transition: $from -> $to" }
    }
}

object DeliveryTimeResolver {
    private val nowWords = setOf("الان", "الآن", "حالاً", "الحين", "دحين", "now")
    fun resolve(text: String?, now: Long = System.currentTimeMillis()): Long? {
        val value = text?.trim().orEmpty()
        if (value.isBlank()) return null
        if (value.lowercase() in nowWords) return now
        val match = Regex("(?:الساعة\\s*)?(\\d{1,2})(?::(\\d{2}))?\\s*(?:صباحاً|صباحا|مساءً|مساء|العصر)?", RegexOption.IGNORE_CASE).find(value)
            ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59) return null
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis < now) calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return calendar.timeInMillis
    }
}

object LocationResolver {
    /** مفاتيح مضغوطة حتى تعمل المطابقة مع أو بدون مسافات وباختلاف الهمزات الشائع في SMS. */
    private val aliases = mapOf(
        "بيرشعبان" to "بئر شعبان",
        "بئرشعبان" to "بئر شعبان",
        "بيرالشعبان" to "بئر شعبان",
        "بئرالشعبان" to "بئر شعبان",
        "شارع60" to "شارع الستين",
        "شارعالستين" to "شارع الستين"
    )
    fun normalize(original: String?): String? {
        val value = original?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        if (value.isBlank()) return null
        val compact = value.replace(" ", "").lowercase()
        return aliases[compact] ?: value
    }
}

object CreditEligibilityEngine {
    fun decide(creditLimit: Double, currentOutstanding: Double, requestedAmount: Double): CreditDecision {
        if (!creditLimit.isFinite() || !currentOutstanding.isFinite() || !requestedAmount.isFinite() || requestedAmount < 0) {
            return CreditDecision(false, 0.0, "invalid_financial_values")
        }
        val available = (creditLimit - currentOutstanding).coerceAtLeast(0.0)
        return if (requestedAmount <= available + 0.0001) CreditDecision(true, available, "within_available_credit")
        else CreditDecision(false, available, "credit_limit_exceeded")
    }
}

object BankMessageFingerprint {
    fun of(senderPhone: String, rawMessage: String, receivedAt: Long): String {
        val canonical = listOf(senderPhone.trim(), rawMessage.trim(), receivedAt / 60_000L).joinToString("|")
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
