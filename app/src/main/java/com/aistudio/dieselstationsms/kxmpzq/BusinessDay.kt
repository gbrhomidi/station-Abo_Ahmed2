package com.aistudio.dieselstationsms.kxmpzq

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class BusinessDayContext(
    val businessDate: String,
    val startDateTime: String,
    val endDateTime: String,
    val zoneId: String
)

/** Business Day is independent from pricing validity. The operating day starts at 00:00. */
object BusinessDay {
    const val DEFAULT_ZONE_ID = "Asia/Riyadh"
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun context(zoneId: String = DEFAULT_ZONE_ID, now: ZonedDateTime = ZonedDateTime.now(ZoneId.of(zoneId))): BusinessDayContext {
        val zone = ZoneId.of(zoneId)
        val localDate = now.withZoneSameInstant(zone).toLocalDate()
        val start = localDate.atStartOfDay(zone)
        val end = start.plusDays(1)
        return BusinessDayContext(localDate.format(dateFormatter), start.format(formatter), end.format(formatter), zone.id)
    }

    fun currentDate(zoneId: String = DEFAULT_ZONE_ID, now: ZonedDateTime = ZonedDateTime.now(ZoneId.of(zoneId))): String =
        context(zoneId, now).businessDate

    fun forInstant(instant: Instant, zoneId: String = DEFAULT_ZONE_ID): String =
        context(zoneId, instant.atZone(ZoneId.of(zoneId))).businessDate

    fun range(date: LocalDate, zoneId: String = DEFAULT_ZONE_ID): Pair<ZonedDateTime, ZonedDateTime> {
        val zone = ZoneId.of(zoneId)
        val start = date.atStartOfDay(zone)
        return start to start.plusDays(1)
    }
}
