package space.opensensor.today

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import space.opensensor.airquality.Pm25Basis
import space.opensensor.data.history.AirMetrics
import space.opensensor.data.history.ArchiveDayQuery
import space.opensensor.data.history.HistorySeries
import space.opensensor.data.history.HistoryWindow
import space.opensensor.data.history.MetricSpec
import space.opensensor.data.history.TodaySoFar
import space.opensensor.data.history.archiveQueryResults
import space.opensensor.data.history.localDateOf
import space.opensensor.data.history.startOfDay
import space.opensensor.designsystem.nowEpochMillis

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
 * Today so far for the connected [thing], the local calendar day like the History Day view, from
 * the hourly tier of the phone archive. The EPA corrected PM2.5 mean when the day has one, else raw. Null until the archive has a mean for this thing and day.
 * A thing or day change drops the old answer first, so a value never shows under the wrong device.
 * Both apps feed it their own [observeHourlyDay] (the shared `HistorySeriesReader`).
 */
fun todaySoFar(
    thing: Flow<String?>,
    observeHourlyDay: (thing: String, metric: MetricSpec, window: HistoryWindow) -> Flow<HistorySeries>,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    dayStarts: Flow<Long> = localDayStarts(zone),
): Flow<TodaySoFar?> =
    combine(thing.distinctUntilChanged(), dayStarts) { s, start -> ArchiveDayQuery(s, start, zone) }
        .archiveQueryResults { query ->
            val id = query.thing
            if (id == null) {
                flowOf(null)
            } else {
                // Corrected PM2.5 when the day has rows with a valid RH, else raw, labelled by its basis.
                combine(observeHourlyDay(id, AirMetrics.PM25_CORRECTED, query.window), observeHourlyDay(id, AirMetrics.PM25, query.window)) { corrected, raw ->
                    val series = if (corrected.mean != null) corrected else raw
                    series.mean?.let { TodaySoFar(it, series.validRows.toInt(), series.coverage, series.metric.basis ?: Pm25Basis.RAW) }
                }
            }
        }
        .map { it.value }
