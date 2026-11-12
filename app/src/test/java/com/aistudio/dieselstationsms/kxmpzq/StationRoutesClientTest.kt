package com.aistudio.dieselstationsms.kxmpzq

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationRoutesClientTest {
    @Test
    fun coordinateValidationAcceptsNormalYemenCoordinates() {
        assertTrue(StationRoutesClient.Coordinate(15.3694, 44.1910).isValid())
    }

    @Test
    fun coordinateValidationRejectsOutOfRangeValues() {
        assertFalse(StationRoutesClient.Coordinate(91.0, 44.0).isValid())
        assertFalse(StationRoutesClient.Coordinate(15.0, 181.0).isValid())
    }

    @Test
    fun coordinateValidationRejectsNullIslandAndNonFiniteValues() {
        assertFalse(StationRoutesClient.Coordinate(0.0, 0.0).isValid())
        assertFalse(StationRoutesClient.Coordinate(Double.NaN, 44.0).isValid())
        assertFalse(StationRoutesClient.Coordinate(15.0, Double.POSITIVE_INFINITY).isValid())
    }
}
