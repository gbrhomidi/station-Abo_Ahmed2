package com.aistudio.dieselstationsms.kxmpzq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class BusinessDayBoundaryTest {

    @Test
    fun defaultBusinessZoneIsTheYemenStationZone() {
        assertEquals("Asia/Aden", BusinessDay.DEFAULT_ZONE_ID)
        assertEquals("Asia/Aden", BusinessDay.context().zoneId)
    }

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
    fun newYorkCivilDayRangesRespectBothDstTransitions() {
        val zone = "America/New_York"
        val springForward = BusinessDay.range(LocalDate.parse("2026-03-08"), zone)
        val fallBack = BusinessDay.range(LocalDate.parse("2026-11-01"), zone)

        assertEquals(23L, Duration.between(springForward.first.toInstant(), springForward.second.toInstant()).toHours())
        assertEquals(25L, Duration.between(fallBack.first.toInstant(), fallBack.second.toInstant()).toHours())
        assertEquals("2026-11-02 00:00:00", fallBack.second.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
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
