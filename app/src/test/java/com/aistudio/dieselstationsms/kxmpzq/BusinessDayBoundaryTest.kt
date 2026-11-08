package com.aistudio.dieselstationsms.kxmpzq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class BusinessDayBoundaryTest {

    @Test
    fun localMidnightIsAnExactBoundaryInTheSelectedStationDeviceZone() {
        val zone = ZoneId.of("Asia/Aden")
        val beforeMidnight = ZonedDateTime.of(2026, 11, 7, 23, 59, 59, 0, zone)
        val atMidnight = beforeMidnight.plusSeconds(1)

        assertEquals("2026-11-07", BusinessDay.currentDate(zone.id, beforeMidnight))
        assertEquals("2026-11-08", BusinessDay.currentDate(zone.id, atMidnight))

        val context = BusinessDay.context(zone.id, atMidnight)
        assertEquals("2026-11-08", context.businessDate)
        assertEquals("2026-11-08 00:00:00", context.startDateTime)
        assertEquals("2026-11-09 00:00:00", context.endDateTime)
        assertEquals(zone.id, context.zoneId)
    }

    @Test
    fun theSameInstantCanBelongToDifferentCivilDaysWithoutHardcodedUtcOffset() {
        val instant = Instant.parse("2026-11-08T00:30:00Z")

        assertEquals("2026-11-08", BusinessDay.forInstant(instant, "Asia/Aden"))
        assertEquals("2026-11-07", BusinessDay.forInstant(instant, "America/New_York"))
        assertNotEquals(
            BusinessDay.forInstant(instant, "Asia/Aden"),
            BusinessDay.forInstant(instant, "America/New_York")
        )
    }
}
