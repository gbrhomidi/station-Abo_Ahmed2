package com.aistudio.dieselstationsms.kxmpzq.sms

/** محرك قرار مالي حتمي؛ الذكاء الاصطناعي لا يملك صلاحية اعتماد الدفع. */
class BankPaymentMatchingEngine {
    data class CandidateOrder(val orderId: String, val total: Double, val phone: String, val customerName: String?, val createdAt: Long, val expiresAt: Long, val quoteReference: String?)

    fun match(payment: BankPaymentCandidate, orders: List<CandidateOrder>, now: Long = System.currentTimeMillis()): PaymentMatch {
        val valid = orders.filter { payment.currency.equals("YER", true) && now in it.createdAt..it.expiresAt && kotlin.math.abs(it.total - payment.amount) <= 0.0001 }
        if (valid.isEmpty()) return PaymentMatch(false, false, null, "no_exact_amount_in_valid_window")
        if (!payment.reference.isNullOrBlank()) {
            val referenced = valid.filter { it.quoteReference == payment.reference || it.orderId == payment.reference }
            if (referenced.size == 1) return PaymentMatch(true, false, referenced.first().orderId, "reference_exact_amount_trusted_bank")
            if (referenced.size > 1) return PaymentMatch(false, true, null, "ambiguous_reference")
        }
        // لا يجوز الاعتماد التلقائي عند وجود أكثر من طلب صالح بنفس المبلغ،
        // حتى لو تطابقت هوية العميل؛ يلزم تدخل بشري لمنع إسناد دفعة إلى طلب خاطئ.
        if (valid.size > 1) return PaymentMatch(false, true, null, "multiple_orders_same_amount")
        val customerMatches = valid.filter { it.phone == payment.senderPhone || (!payment.senderName.isNullOrBlank() && !it.customerName.isNullOrBlank() && it.customerName.equals(payment.senderName, true)) }
        return when {
            customerMatches.size == 1 -> PaymentMatch(true, false, customerMatches.first().orderId, "customer_exact_amount_trusted_bank")
            else -> PaymentMatch(false, false, null, "customer_identity_mismatch")
        }
    }
}
