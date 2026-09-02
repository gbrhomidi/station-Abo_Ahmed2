package com.aistudio.dieselstationsms.kxmpzq.sms

import org.junit.Assert.*
import org.junit.Test

class FuelCommerceWorkflowTest {
    @Test fun stateMachineRejectsSkippingPayment() {
        assertTrue(FuelOrderStateMachine.canTransition(FuelOrderStatus.QUOTED, FuelOrderStatus.AWAITING_PAYMENT))
        assertFalse(FuelOrderStateMachine.canTransition(FuelOrderStatus.QUOTED, FuelOrderStatus.COMPLETED))
    }

    @Test fun creditDecisionUsesAvailableCreditNotLimitAlone() {
        val decision = CreditEligibilityEngine.decide(1000.0, 700.0, 301.0)
        assertFalse(decision.eligible)
        assertEquals(300.0, decision.availableCredit, 0.001)
    }

    @Test fun deliveryTimeAndLocationAreNormalized() {
        assertNotNull(DeliveryTimeResolver.resolve("الساعة 3"))
        assertEquals("بئر شعبان", LocationResolver.normalize("بير شعبان"))
    }

    @Test fun paymentMatcherRequiresExactAmountAndIdentity() {
        val payment = BankPaymentCandidate("fp", "967771234567", "ALKURAIMI", "A1", 500.0, "YER")
        val order = BankPaymentMatchingEngine.CandidateOrder("FO-1", 500.0, "967771234567", "أحمد", System.currentTimeMillis() - 1000, System.currentTimeMillis() + 60_000, null)
        assertTrue(BankPaymentMatchingEngine().match(payment, listOf(order)).matched)
    }

    @Test fun fingerprintChangesAcrossDifferentMessages() {
        assertNotEquals(BankMessageFingerprint.of("967771234567", "مبلغ 500", 1_000_000), BankMessageFingerprint.of("967771234567", "مبلغ 700", 1_000_000))
    }
}
