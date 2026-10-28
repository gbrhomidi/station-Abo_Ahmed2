package com.aistudio.dieselstationsms.kxmpzq

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityValidatorFinanceGuardTest {
    @Test
    fun cashDepositsAreNotEditableThroughGenericOperationalCrud() {
        assertFalse(SecurityValidator.isTableAllowedForGeneralCrud("cash_deposits"))
        assertFalse(SecurityValidator.isTableAllowedForGeneralCrud("CASH_DEPOSITS"))
        assertFalse(SecurityValidator.isTableAllowedForGeneralCrud(" cash_deposits "))
    }

    @Test
    fun unrelatedOperationalTablesRemainAllowed() {
        assertTrue(SecurityValidator.isTableAllowedForGeneralCrud("budgets"))
    }
}
