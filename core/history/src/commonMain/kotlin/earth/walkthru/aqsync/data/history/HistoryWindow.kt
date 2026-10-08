package earth.walkthru.aqsync.data.history

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * One half-open local-calendar window. The zone is part of query identity. On the JVM, convert a
 * `java.time.ZoneId` with `toKotlinTimeZone()` (kotlinx-datetime).
 */
data class HistoryWindow(val fromS: Long, val toS: Long, val zone: TimeZone) {
    init {
        require(fromS < toS)
    }

    val seconds: Long get() = toS - fromS

    /** Move by whole local dates, never by 86,400-second approximations. */
    fun page(range: HistoryRange, direction: Int): HistoryWindow = endingOn(
        localDateOf(toS, zone).plus(direction * range.days, DateTimeUnit.DAY),
        range,
        zone,
    )

    companion object {
        fun endingOn(endDate: LocalDate, range: HistoryRange, zone: TimeZone): HistoryWindow = HistoryWindow(
            endDate.minus(range.days, DateTimeUnit.DAY).atStartOfDayIn(zone).epochSeconds,
            endDate.atStartOfDayIn(zone).epochSeconds,
            zone,
        )

        fun endingAt(toS: Long, range: HistoryRange, zone: TimeZone): HistoryWindow =
            endingOn(localDateOf(toS, zone), range, zone)

        fun current(range: HistoryRange, zone: TimeZone, clock: Clock = Clock.System): HistoryWindow =
            endingOn(clock.todayIn(zone).plus(1, DateTimeUnit.DAY), range, zone)

        fun dayContaining(epochS: Long, zone: TimeZone): HistoryWindow =
            endingOn(localDateOf(epochS, zone).plus(1, DateTimeUnit.DAY), HistoryRange.DAY, zone)
    }
}

/** The local calendar date of an epoch second in [zone]. */
fun localDateOf(epochS: Long, zone: TimeZone): LocalDate = Instant.fromEpochSeconds(epochS).toLocalDateTime(zone).date

/** Epoch second of local midnight starting [date] in [zone] (the first instant of the day across DST gaps). */
fun startOfDay(date: LocalDate, zone: TimeZone): Long = date.atStartOfDayIn(zone).epochSeconds
