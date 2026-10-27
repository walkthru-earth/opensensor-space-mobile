package space.opensensor.today

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import space.opensensor.data.history.DeviceCapabilities
import space.opensensor.data.history.HistorySeries
import space.opensensor.data.history.HistoryWindow
import space.opensensor.data.history.MetricSpec
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.designsystem.nowEpochMillis
import space.opensensor.family.air.AirFamily
import space.opensensor.family.air.AirMetrics
import space.opensensor.family.air.LiveQc
import space.opensensor.family.air.Pm25Basis
import space.opensensor.family.air.Pm25NowCast
import space.opensensor.family.air.PmNowCast

/** The start of the current UTC hour, checked once a minute so a new hour moves the NowCast window. */
fun utcHourStarts(periodMs: Long = 60_000): Flow<Long> = flow {
    while (true) {
        emit(PmNowCast.hourStart(nowEpochMillis() / 1000))
        delay(periodMs)
    }
}.distinctUntilChanged()

/**
 * The PM2.5 NowCast (#93) of the connected device [info], from the 12 complete UTC hours of the
 * phone archive's hourly tier before the running hour. An air board with particles tries the EPA
 * corrected PM2.5 first and falls back to raw, the first with a NowCast wins. Null for any other
 * thing, and until two of the three latest hours are in the archive. A thing change drops the old
 * answer first, so a value never shows under the wrong device. A new hour keeps the last answer
 * until the new window is read, so the hero does not flip to the live value at each hour. Both apps
 * feed it their own [observeHourlyDay] (the shared `HistorySeriesReader`), the window is hour
 * aligned so only whole hourly buckets are read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun pm25NowCast(
    info: Flow<DeviceInfo?>,
    observeHourlyDay: (thing: String, metric: MetricSpec, window: HistoryWindow) -> Flow<HistorySeries>,
    hourStarts: Flow<Long> = utcHourStarts(),
): Flow<Pm25NowCast?> {
    val things = info.map { device ->
        val particles = AirFamily.PARTICLES in DeviceCapabilities.from(device)
        device?.thing to if (particles) listOf(AirMetrics.PM25_CORRECTED, AirMetrics.PM25) else emptyList()
    }.distinctUntilChanged()
    return things.flatMapLatest { (thing, metrics) ->
        if (thing == null || metrics.isEmpty()) {
            flowOf(null)
        } else {
            flow {
                emit(null)
                emitAll(hourStarts.flatMapLatest { hour -> nowCastAt(thing, metrics, hour, observeHourlyDay) })
            }
        }
    }
}

private fun nowCastAt(
    thing: String,
    metrics: List<MetricSpec>,
    hourStartS: Long,
    observeHourlyDay: (thing: String, metric: MetricSpec, window: HistoryWindow) -> Flow<HistorySeries>,
): Flow<Pm25NowCast?> {
    val window = HistoryWindow(hourStartS - PmNowCast.HOURS * 3_600L, hourStartS, TimeZone.UTC)
    return combine(metrics.map { observeHourlyDay(thing, it, window) }) { all ->
        all.firstNotNullOfOrNull { series ->
            PmNowCast.of(PmNowCast.hourlySlots(series.points, hourStartS))?.let { value ->
                Pm25NowCast(value, if (series.metric.id == AirFamily.PM25_CORRECTED_ID) Pm25Basis.CORRECTED else Pm25Basis.RAW)
            }
        }
    }
}

/**
 * Each snapshot with the quality flags of its live sample (#91), from one [LiveQc] window per
 * device and thing. A new device or thing starts a new window, so one board's samples never judge
 * another's.
 */
internal fun Flow<TodaySnapshot>.withLiveQc(): Flow<Pair<TodaySnapshot, Int>> = flow {
    var key: Pair<String?, String?>? = null
    var qc = LiveQc()
    collect { snapshot ->
        val current = snapshot.address to snapshot.info?.thing
        if (current != key) {
            key = current
            qc = LiveQc()
        }
        emit(snapshot to qc.add(DeviceCapabilities.from(snapshot.info).reading(snapshot.live), snapshot.liveAtEpochMs))
    }
}
