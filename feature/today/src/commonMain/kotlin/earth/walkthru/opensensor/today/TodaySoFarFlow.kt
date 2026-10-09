package earth.walkthru.opensensor.today

import earth.walkthru.aqsync.data.history.ArchiveDayQuery
import earth.walkthru.aqsync.data.history.HistorySeries
import earth.walkthru.aqsync.data.history.HistoryWindow
import earth.walkthru.aqsync.data.history.MetricSpec
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.data.history.TodaySoFar
import earth.walkthru.aqsync.data.history.archiveQueryResults
import earth.walkthru.aqsync.data.history.localDateOf
import earth.walkthru.aqsync.data.history.startOfDay
import earth.walkthru.opensensor.designsystem.nowEpochMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone

/** Epoch second of the current local midnight in [zone]. */
internal fun currentDayStart(zone: TimeZone, nowS: Long = nowEpochMillis() / 1000): Long = startOfDay(localDateOf(nowS, zone), zone)

/** The start of the local day, checked once a minute so midnight moves the window while Today is open. */
fun localDayStarts(zone: TimeZone, periodMs: Long = 60_000): Flow<Long> = flow {
    while (true) {
        emit(currentDayStart(zone))
        delay(periodMs)
    }
}.distinctUntilChanged()

/**
 * Today so far for the connected [station], the local calendar day like the History Day view, from
 * the hourly tier of the phone archive. Null until the archive has a mean for this station and day.
 * A station or day change drops the old answer first, so a value never shows under the wrong device.
 * Both apps feed it their own [observeHourlyDay] (the shared `HistorySeriesReader`).
 */
fun todaySoFar(
    station: Flow<String?>,
    observeHourlyDay: (station: String, metric: MetricSpec, window: HistoryWindow) -> Flow<HistorySeries>,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    dayStarts: Flow<Long> = localDayStarts(zone),
): Flow<TodaySoFar?> =
    combine(station.distinctUntilChanged(), dayStarts) { s, start -> ArchiveDayQuery(s, start, zone) }
        .archiveQueryResults { query ->
            val id = query.station
            if (id == null) {
                flowOf(null)
            } else {
                observeHourlyDay(id, Metrics.primary, query.window).map { series ->
                    series.mean?.let { TodaySoFar(it, series.validRows.toInt(), series.coverage) }
                }
            }
        }
        .map { it.value }
