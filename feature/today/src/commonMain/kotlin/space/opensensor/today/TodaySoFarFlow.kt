package space.opensensor.today

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import space.opensensor.data.history.ArchiveDayQuery
import space.opensensor.data.history.DeviceCapabilities
import space.opensensor.data.history.HistorySeries
import space.opensensor.data.history.HistoryWindow
import space.opensensor.data.history.MetricSpec
import space.opensensor.data.history.Metrics
import space.opensensor.data.history.ServedTemplates
import space.opensensor.data.history.TodaySoFar
import space.opensensor.data.history.archiveQueryResults
import space.opensensor.data.history.localDateOf
import space.opensensor.data.history.startOfDay
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.designsystem.nowEpochMillis
import space.opensensor.family.air.AirFamily
import space.opensensor.family.air.AirMetrics
import space.opensensor.model.BoardTemplate

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
 * The metrics Today so far averages for [capabilities], in preference order. An air board with
 * particles takes the EPA corrected PM2.5 and falls back to raw. Any other thing takes the metric of
 * its first visible primary column, so a soil board averages its soil reading. Empty when the thing
 * is unknown or shows no primary.
 */
fun todayMetrics(capabilities: DeviceCapabilities): List<MetricSpec> {
    val thing = capabilities.thing ?: return emptyList()
    if (AirFamily.PARTICLES in capabilities) return listOf(AirMetrics.PM25_CORRECTED, AirMetrics.PM25)
    val primary = thing.primary.firstOrNull() ?: return emptyList()
    return listOfNotNull(Metrics.of(thing.template, thing.profile).firstOrNull { it.id == primary.name })
}

/** One Today so far read, the day of a thing and the metrics to try in order. */
private data class TodayQuery(val day: ArchiveDayQuery, val metrics: List<MetricSpec>)

/**
 * Today so far for the connected device [info], the local calendar day like the History Day view,
 * from the hourly tier of the phone archive. The first of [todayMetrics] that has a mean wins. Null
 * until the archive has a mean for this thing and day. A thing or day change drops the old answer
 * first, so a value never shows under the wrong device. The thing resolves against the [served]
 * templates as they arrive, so a template the board serves after `INFO` still picks the metrics.
 * Both apps feed it their own [observeHourlyDay] (the shared `HistorySeriesReader`).
 */
fun todaySoFar(
    info: Flow<DeviceInfo?>,
    observeHourlyDay: (thing: String, metric: MetricSpec, window: HistoryWindow) -> Flow<HistorySeries>,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    dayStarts: Flow<Long> = localDayStarts(zone),
    served: Flow<List<BoardTemplate>> = ServedTemplates.shared.all,
): Flow<TodaySoFar?> {
    val things = combine(info, served) { i, templates -> i?.thing to todayMetrics(DeviceCapabilities.from(i, templates)) }.distinctUntilChanged()
    return combine(things, dayStarts) { (thing, metrics), start -> TodayQuery(ArchiveDayQuery(thing, start, zone), metrics) }
        .archiveQueryResults { query ->
            val id = query.day.thing
            if (id == null || query.metrics.isEmpty()) {
                flowOf(null)
            } else {
                combine(query.metrics.map { observeHourlyDay(id, it, query.day.window) }) { all ->
                    all.firstNotNullOfOrNull { series ->
                        series.mean?.let { TodaySoFar(it, series.validRows.toInt(), series.coverage, series.metric) }
                    }
                }
            }
        }
        .map { it.value }
}
