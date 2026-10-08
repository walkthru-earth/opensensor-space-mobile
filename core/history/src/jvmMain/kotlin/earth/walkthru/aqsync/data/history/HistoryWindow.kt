package earth.walkthru.aqsync.data.history

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One half-open local-calendar window. The zone is part of query identity. */
data class HistoryWindow(val fromS: Long, val toS: Long, val zone: ZoneId) {
    init {
        require(fromS < toS)
    }

    val seconds: Long get() = toS - fromS

    /** Move by whole local dates, never by 86,400-second approximations. */
    fun page(range: HistoryRange, direction: Int): HistoryWindow = endingOn(
        Instant.ofEpochSecond(toS).atZone(zone).toLocalDate().plusDays(direction * range.days),
        range,
        zone,
    )

    companion object {
        fun endingOn(endDate: LocalDate, range: HistoryRange, zone: ZoneId): HistoryWindow = HistoryWindow(
            endDate.minusDays(range.days).atStartOfDay(zone).toEpochSecond(),
            endDate.atStartOfDay(zone).toEpochSecond(),
            zone,
        )

        fun endingAt(toS: Long, range: HistoryRange, zone: ZoneId): HistoryWindow =
            endingOn(Instant.ofEpochSecond(toS).atZone(zone).toLocalDate(), range, zone)

        fun current(range: HistoryRange, zone: ZoneId, clock: Clock = Clock.system(zone)): HistoryWindow =
            endingOn(LocalDate.now(clock.withZone(zone)).plusDays(1), range, zone)

        fun dayContaining(epochS: Long, zone: ZoneId): HistoryWindow =
            endingOn(Instant.ofEpochSecond(epochS).atZone(zone).toLocalDate().plusDays(1), HistoryRange.DAY, zone)
    }
}
