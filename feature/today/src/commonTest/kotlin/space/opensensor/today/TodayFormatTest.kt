package space.opensensor.today

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import space.opensensor.data.history.DeviceCapabilities
import space.opensensor.data.history.HistoryRange
import space.opensensor.data.history.HistorySeries
import space.opensensor.data.history.HistoryWindow
import space.opensensor.data.history.MetricSpec
import space.opensensor.data.history.TodaySoFar
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.sync.ClockQuality
import space.opensensor.family.air.AirFamily
import space.opensensor.family.air.AirMetrics
import space.opensensor.family.air.Pm25Basis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class TodayFormatTest {
    init {
        AirFamily.install()
    }

    @Test
    fun readingsDropATrailingZeroAndMissingStaysMissing() {
        assertEquals("21", fmt(21.0))
        assertEquals("21.5", fmt(21.46))
        assertEquals("1000.0", fmt(1000.0))
        assertEquals(NO_VALUE, fmt(null))
    }

    @Test
    fun bytesUseBinaryUnits() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.5 KiB", formatBytes(1536))
        assertEquals("2.00 MiB", formatBytes(2L * 1024 * 1024))
        assertEquals("1.00 GiB", formatBytes(1024L * 1024 * 1024))
    }

    @Test
    fun uptimeKeepsTheTwoLargestUnits() {
        assertEquals("1m 5s", formatUptime(65))
        assertEquals("2h 3m", formatUptime(2 * 3600 + 3 * 60))
        assertEquals("1d 4h", formatUptime(86_400 + 4 * 3600))
    }

    @Test
    fun agoPicksTheLargestWholeUnit() {
        assertEquals(AgoUnit.JUST_NOW to 0L, agoParts(-5))
        assertEquals(AgoUnit.SECONDS to 12L, agoParts(12_400))
        assertEquals(AgoUnit.MINUTES to 2L, agoParts(150_000))
        assertEquals(AgoUnit.HOURS to 3L, agoParts(3 * 3_600_000L + 1))
        assertEquals(AgoUnit.DAYS to 2L, agoParts(2 * 86_400_000L))
    }

    @Test
    fun fillReplacesEitherPlaceholder() {
        assertEquals("12 s ago", fill("%1\$s s ago", "12"))
        assertEquals("قبل 12 ث", fill("قبل %1\$d ث", "12"))
    }

    @Test
    fun eachClockProblemHasItsOwnReason() {
        assertNotEquals(ClockQuality.MISSING.reasonRes(), ClockQuality.RTC_RESTORED.reasonRes())
        assertNotEquals(ClockQuality.MISSING.reasonRes(), ClockQuality.UNKNOWN.reasonRes())
    }

    @Test
    fun theFirmwareClockPrintsAsAnInstant() {
        assertEquals("2026-01-01T00:00:00Z", isoInstant(1_767_225_600L * 1_000_000_000L))
    }

    @Test
    fun todaySoFarComesFromTheHourlyDayAndIsNullWithoutAThing() = runTest {
        val zone = TimeZone.UTC
        val info = MutableStateFlow<DeviceInfo?>(null)
        val seen = mutableListOf<String>()
        val observe = { id: String, metric: MetricSpec, window: HistoryWindow ->
            seen += id
            flowOf(
                HistorySeries(metric, HistoryRange.DAY, window.fromS, window.toS, emptyList(), validRows = 30, expectedRows = 60, mean = 12.5f, max = 20f, binned = false),
            ) as Flow<HistorySeries>
        }
        assertNull(todaySoFar(info, observe, zone, flowOf(1_767_225_600L)).take(1).toList().single())

        info.value = WAVESHARE.copy(thing = "st-1")
        val value = todaySoFar(info, observe, zone, flowOf(1_767_225_600L)).filterNotNull().first()
        assertEquals(TodaySoFar(12.5f, 30, 0.5f, AirMetrics.PM25_CORRECTED), value)
        assertEquals(Pm25Basis.CORRECTED, value.metric.basis)
        assertEquals(listOf("st-1", "st-1"), seen)
    }

    @Test
    fun todaySoFarFallsBackToRawLabelledRawWithoutAValidHumidity() = runTest {
        val observe = { _: String, metric: MetricSpec, window: HistoryWindow ->
            val mean = if (metric == AirMetrics.PM25) 18f else null
            flowOf(
                HistorySeries(metric, HistoryRange.DAY, window.fromS, window.toS, emptyList(), validRows = if (mean == null) 0 else 30, expectedRows = 60, mean = mean, max = mean, binned = false),
            ) as Flow<HistorySeries>
        }
        val value = todaySoFar(MutableStateFlow<DeviceInfo?>(WAVESHARE.copy(thing = "st-1")), observe, TimeZone.UTC, flowOf(1_767_225_600L)).filterNotNull().first()
        assertEquals(TodaySoFar(18f, 30, 0.5f, AirMetrics.PM25), value)
        assertEquals(Pm25Basis.RAW, value.metric.basis)
    }

    @Test
    fun todaySoFarOfAThingWithoutParticlesAveragesItsFirstPrimary() {
        val soil = SoilFixture.capabilities()
        assertEquals(listOf(SoilFixture.MOISTURE), todayMetrics(soil).map { it.id })
        assertEquals(listOf(AirMetrics.PM25_CORRECTED, AirMetrics.PM25), todayMetrics(DeviceCapabilities.from(WAVESHARE)))
        assertEquals(emptyList(), todayMetrics(DeviceCapabilities.NONE))
    }

    private companion object {
        val WAVESHARE = DeviceInfo(proto = 3, schema = "waveshare-sim7670g-telemetry-v5", dict = "50a53393e940be182ded0b1119de6ac6d9521e7d28dfd244a24666ccf2a616c7")
    }
}
