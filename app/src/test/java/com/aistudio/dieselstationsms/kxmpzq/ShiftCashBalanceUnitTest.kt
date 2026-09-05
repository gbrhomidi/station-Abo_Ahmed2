package com.aistudio.dieselstationsms.kxmpzq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShiftCashBalanceUnitTest {
    @Test
    fun expectedClosingCashAddsOpeningCashAndCashSales() {
        assertEquals(
            1350.0,
            DatabaseHelper.calculateExpectedClosingCash(1000.0, 350.0),
            0.0001
        )
    }

    @Test
    fun cashVarianceIsPositiveForSurplusAndNegativeForShortage() {
        assertEquals(
            50.0,
            DatabaseHelper.calculateCashVariance(1000.0, 350.0, 1400.0),
            0.0001
        )
        assertEquals(
            -50.0,
            DatabaseHelper.calculateCashVariance(1000.0, 350.0, 1300.0),
            0.0001
        )
    }

    @Test
    fun exactClosingCashProducesZeroVariance() {
        assertEquals(
            0.0,
            DatabaseHelper.calculateCashVariance(250.25, 99.75, 350.0),
            0.0001
        )
    }

    @Test
    fun negativeOrNonFiniteBalancesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DatabaseHelper.calculateExpectedClosingCash(-1.0, 10.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DatabaseHelper.calculateExpectedClosingCash(100.0, Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DatabaseHelper.calculateCashVariance(100.0, 10.0, -1.0)
        }
    }
}
