package space.opensensor.today

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import space.opensensor.data.history.HistoryPoint
import space.opensensor.data.history.HistoryRange
import space.opensensor.data.history.HistorySeries
import space.opensensor.data.history.HistoryWindow
import space.opensensor.data.history.MetricSpec
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.LiveSample
import space.opensensor.family.air.AirFamily
import space.opensensor.family.air.AirMetrics
import space.opensensor.family.air.Averaging
import space.opensensor.family.air.NowReading
import space.opensensor.family.air.Pm25Basis
import space.opensensor.family.air.Pm25NowCast
import space.opensensor.family.air.QcFlags
import space.opensensor.family.air.QcRules
import space.opensensor.settings.DetailLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class AirNowFlowsTest {
    init {
        AirFamily.install()
    }

    private val hour = 1_767_225_600L // a whole UTC hour

    /** The AirNow worked example as hourly points, the latest complete hour first, the running hour at 99. */
    private fun airNowHours(): List<HistoryPoint> {
        val means = listOf(21f, null, 35f, 49.2f, 48.6f, 53.7f, 66.2f, 69.2f, 64.9f, 50f, 43f, 34.9f)
        val past = means.mapIndexed { i, m -> HistoryPoint(hour - (i + 1) * 3_600L, hour - i * 3_600L, m, null, null, 1f) }
        return past + HistoryPoint(hour, hour + 3_600, 99f, null, null, 0.2f)
    }

    private fun series(metric: MetricSpec, window: HistoryWindow, points: List<HistoryPoint>) =
        HistorySeries(metric, HistoryRange.DAY, window.fromS, window.toS, points, validRows = 0, expectedRows = 0, mean = null, max = null, binned = false)

    @Test
    fun theNowCastReadsTheTwelveHoursBeforeTheRunningOne() = runTest {
        val windows = mutableListOf<HistoryWindow>()
        val observe = { _: String, metric: MetricSpec, window: HistoryWindow ->
            windows += window
            flowOf(series(metric, window, airNowHours())) as Flow<HistorySeries>
        }
        val cast = pm25NowCast(MutableStateFlow<DeviceInfo?>(WAVESHARE.copy(thing = "st-1")), observe, flowOf(hour)).filterNotNull().first()
        assertEquals(28.4, cast.value, 1e-9)
        assertEquals(Pm25Basis.CORRECTED, cast.basis)
        assertEquals(HistoryWindow(hour - 12 * 3_600, hour, TimeZone.UTC), windows.first())
    }

    @Test
    fun theNowCastFallsBackToRawAndIsNullWithoutTheHours() = runTest {
        val observe = { _: String, metric: MetricSpec, window: HistoryWindow ->
            flowOf(series(metric, window, if (metric == AirMetrics.PM25) airNowHours() else emptyList())) as Flow<HistorySeries>
        }
        val info = MutableStateFlow<DeviceInfo?>(WAVESHARE.copy(thing = "st-1"))
        assertEquals(Pm25Basis.RAW, pm25NowCast(info, observe, flowOf(hour)).filterNotNull().first().basis)
        val empty = { _: String, metric: MetricSpec, window: HistoryWindow -> flowOf(series(metric, window, emptyList())) as Flow<HistorySeries> }
        assertNull(pm25NowCast(info, empty, flowOf(hour)).take(2).toList().last())
    }

    @Test
    fun noNowCastWithoutAnAirThing() = runTest {
        val seen = mutableListOf<String>()
        val observe = { id: String, metric: MetricSpec, window: HistoryWindow ->
            seen += id
            flowOf(series(metric, window, airNowHours())) as Flow<HistorySeries>
        }
        assertNull(pm25NowCast(MutableStateFlow<DeviceInfo?>(null), observe, flowOf(hour)).take(1).toList().single())
        assertNull(pm25NowCast(MutableStateFlow<DeviceInfo?>(SoilFixture.info), observe, flowOf(hour)).take(1).toList().single())
        assertEquals(emptyList(), seen)
    }

    private fun live(i: Int, n03: Int = 900) = LiveSample.parse(
        """{"seq":$i,"mono":${i * 10_000_000L},"pms":4,"pm1":8,"pm25":12,"pm10":14,"n03":$n03,"n05":250,"n1":30,"n25":2,"rh":50}""",
    )

    @Test
    fun liveFlagsComeFromOneWindowPerThing() = runTest {
        val a = WAVESHARE.copy(thing = "st-a")
        val snapshots = (1..QcRules.FLATLINE_ROWS).map { TodaySnapshot(address = "AA", info = a, live = live(it)) } +
            TodaySnapshot(address = "AA", info = WAVESHARE.copy(thing = "st-b"), live = live(QcRules.FLATLINE_ROWS + 1))
        val flags = snapshots.asFlow().withLiveQc().map { it.second }.toList()
        assertEquals(QcFlags.NONE, flags[QcRules.FLATLINE_ROWS - 2])
        assertEquals(QcFlags.FLATLINE, flags[QcRules.FLATLINE_ROWS - 1])
        assertEquals(QcFlags.NONE, flags.last(), "a new thing starts a new window")
    }

    @Test
    fun theControllerJudgesTheNowCastAndCarriesTheLiveFlags() = runTest {
        val source = object : TodaySource {
            override val snapshot = MutableStateFlow(TodaySnapshot(address = "AA", info = WAVESHARE.copy(thing = "st-1"), live = live(1)))
            override val notices = MutableSharedFlow<TodayNotice>()
            override fun sync() = Unit
            override fun setTime() = Unit
            override fun flush() = Unit
            override fun stopSync() = Unit
            override fun reconnect() = Unit
            override fun disconnect() = Unit
        }
        val nowCast = MutableStateFlow<Pm25NowCast?>(null)
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val c = TodayController(scope, source, MutableStateFlow(DetailLevel.DETAILED), nowCast)
        assertEquals(Averaging.LIVE, (c.state.value.airReading as NowReading.Reading).averaging)
        nowCast.value = Pm25NowCast(40.0, Pm25Basis.RAW)
        val cast = c.state.value.airReading as NowReading.Reading
        assertEquals(Averaging.NOWCAST, cast.averaging)
        assertEquals(40.0, cast.primary, 0.0)
        assertEquals(12.0, cast.live, 0.0)
        (2..QcRules.FLATLINE_ROWS).forEach { source.snapshot.value = source.snapshot.value.copy(live = live(it)) }
        assertEquals(QcFlags.FLATLINE, c.state.value.liveFlags)
        assertEquals(QcFlags.FLATLINE, (c.state.value.airReading as NowReading.Reading).flags)
    }

    private companion object {
        val WAVESHARE = DeviceInfo(proto = 3, schema = "waveshare-sim7670g-telemetry-v5", dict = "a8605aafb6f76b794e9e7fb32927be6d203afeeb14527206c0e49c1eb0436809")
    }
}
