package com.aistudio.dieselstationsms.kxmpzq.sms

import org.junit.Assert.*
import org.junit.Test

class FuelCommerceWorkflowTest {
    private val now = 1_700_000_000_000L

    private fun order(id: String = "FO-1", total: Double = 500.0, phone: String = "967771234567", name: String? = "أحمد", ref: String? = null) =
        BankPaymentMatchingEngine.CandidateOrder(id, total, phone, name, now - 60_000, now + 60_000, ref)

    private fun payment(amount: Double = 500.0, phone: String = "967771234567", ref: String? = null, currency: String = "YER") =
        BankPaymentCandidate("fp-$amount-$ref", phone, "ALKURAIMI", "ACCOUNT-1", amount, currency, "أحمد", null, ref, now)

    @Test fun stateMachineRejectsSkippingPayment() {
        assertTrue(FuelOrderStateMachine.canTransition(FuelOrderStatus.QUOTED, FuelOrderStatus.AWAITING_PAYMENT))
        assertTrue(FuelOrderStateMachine.canTransition(FuelOrderStatus.QUOTED, FuelOrderStatus.PAYMENT_VERIFIED))
        assertFalse(FuelOrderStateMachine.canTransition(FuelOrderStatus.QUOTED, FuelOrderStatus.COMPLETED))
        assertFalse(FuelOrderStateMachine.canTransition(FuelOrderStatus.DELIVERED, FuelOrderStatus.PAYMENT_VERIFIED))
    }

    @Test fun creditDecisionUsesAvailableCreditNotLimitAlone() {
        val decision = CreditEligibilityEngine.decide(1000.0, 700.0, 301.0)
        assertFalse(decision.eligible)
        assertEquals(300.0, decision.availableCredit, 0.001)
    }

    @Test fun creditAtExactAvailableBoundaryIsAccepted() {
        val decision = CreditEligibilityEngine.decide(1000.0, 700.0, 300.0)
        assertTrue(decision.eligible)
        assertEquals(300.0, decision.availableCredit, 0.001)
    }

    @Test fun creditRejectsNegativeOutstandingAndBlacklistedLimit() {
        assertFalse(CreditEligibilityEngine.decide(-1.0, 0.0, 10.0).eligible)
        assertFalse(CreditEligibilityEngine.decide(100.0, 100.0, 0.01).eligible)
        assertFalse(CreditEligibilityEngine.decide(100.0, 0.0, -1.0).eligible)
    }

    @Test fun deliveryTimeAndLocationAreNormalized() {
        assertNotNull(DeliveryTimeResolver.resolve("الساعة 3"))
        assertNotNull(DeliveryTimeResolver.resolve("الآن"))
        assertEquals("بئر شعبان", LocationResolver.normalize("بير شعبان"))
        assertEquals("شارع الستين", LocationResolver.normalize("شارع 60"))
    }

    @Test fun exactReferenceAndAmountIsVerified() {
        val result = BankPaymentMatchingEngine().match(payment(ref = "Q-9"), listOf(order(ref = "Q-9")), now)
        assertTrue(result.matched)
        assertFalse(result.reviewRequired)
        assertEquals("FO-1", result.orderId)
    }

    @Test fun wrongAmountIsRejectedWithoutHumanReview() {
        val result = BankPaymentMatchingEngine().match(payment(amount = 499.0), listOf(order()), now)
        assertFalse(result.matched)
        assertFalse(result.reviewRequired)
        assertEquals("no_exact_amount_in_valid_window", result.reason)
    }

    @Test fun staleOrderIsNotMatched() {
        val expired = order().copy(createdAt = now - 200_000, expiresAt = now - 1000)
        val result = BankPaymentMatchingEngine().match(payment(), listOf(expired), now)
        assertFalse(result.matched)
        assertEquals("no_exact_amount_in_valid_window", result.reason)
    }

    @Test fun multipleEqualOrdersRequireReview() {
        val result = BankPaymentMatchingEngine().match(payment(), listOf(order("FO-1"), order("FO-2")), now)
        assertFalse(result.matched)
        assertTrue(result.reviewRequired)
        assertEquals("multiple_orders_same_amount", result.reason)
    }

    @Test fun ambiguousReferenceRequiresReview() {
        val result = BankPaymentMatchingEngine().match(payment(ref = "Q-9"), listOf(order("FO-1", ref = "Q-9"), order("FO-2", ref = "Q-9")), now)
        assertFalse(result.matched)
        assertTrue(result.reviewRequired)
        assertEquals("ambiguous_reference", result.reason)
    }

    @Test fun customerIdentityMismatchDoesNotAutoApprove() {
        val result = BankPaymentMatchingEngine().match(payment(phone = "967770000000"), listOf(order(phone = "967771111111", name = "محمد")), now)
        assertFalse(result.matched)
        assertFalse(result.reviewRequired)
        assertEquals("customer_identity_mismatch", result.reason)
    }

    @Test fun nonYerPaymentIsRejected() {
        val result = BankPaymentMatchingEngine().match(payment(currency = "USD"), listOf(order()), now)
        assertFalse(result.matched)
        assertFalse(result.reviewRequired)
    }

    @Test fun fingerprintChangesAcrossDifferentMessages() {
        assertNotEquals(BankMessageFingerprint.of("967771234567", "مبلغ 500", now), BankMessageFingerprint.of("967771234567", "مبلغ 700", now))
        assertEquals(BankMessageFingerprint.of("967771234567", "مبلغ 500", now), BankMessageFingerprint.of("967771234567", "مبلغ 500", now))
    }

    @Test fun bankParserExtractsAmountReferenceAndBalance() {
        val parsed = BankMessageParser().parse("771234567", "الكريمي: مبلغ 1,250 ريال، المرجع: REF-77، الرصيد: 9,000", now)
        assertNotNull(parsed)
        assertEquals(1250.0, parsed!!.amount, 0.001)
        assertEquals(9000.0, parsed.balance!!, 0.001)
        assertEquals("REF-77", parsed.reference)
    }
}
